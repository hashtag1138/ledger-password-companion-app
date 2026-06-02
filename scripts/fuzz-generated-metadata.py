#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from speculos_fuzz_lib import (
    HarnessError,
    SpeculosHarness,
    default_app_path,
    default_cli_bin,
    default_root_dir,
    ensure_app_exists,
    ensure_cli_exists,
    find_free_port,
    print_json,
)


STORAGE_SIZE = 4096


@dataclass(frozen=True)
class SeedEntry:
    nickname: str
    charsets: tuple[str, ...] = ("ALL_SETS",)


@dataclass(frozen=True)
class GeneratedCase:
    case_id: str
    note: str
    setup_mode: str
    entries: tuple[SeedEntry, ...]
    post_action: str
    position: int = 1
    expected_selected_nickname: str | None = None
    expected_min_corruptions: int = 0


def generated_name(prefix: str, index: int, total_bytes: int) -> str:
    base = f"{prefix}{index:03d}-"
    fill_size = total_bytes - len(base.encode("utf-8"))
    if fill_size < 0:
        raise ValueError(f"Base {base!r} already exceeds {total_bytes} bytes")
    return base + ("x" * fill_size)


VALID_MAXCOUNT_ENTRIES = tuple(SeedEntry(generated_name("vm", index, 19)) for index in range(177))
RAW_STORAGE_EDGE_ENTRIES = tuple(SeedEntry(generated_name("re", index, 19)) for index in range(186))
RAW_OVERLONG_ENTRY = SeedEntry("abcdefghijklmnopqrst")
RAW_BLANK_MIX_ENTRIES = (
    SeedEntry(""),
    SeedEntry("plain-target"),
    SeedEntry(" "),
)


CASES: tuple[GeneratedCase, ...] = (
    GeneratedCase(
        case_id="empty_control_pull",
        note="Vault vide de contrôle via backup.json",
        setup_mode="json",
        entries=(),
        post_action="none",
    ),
    GeneratedCase(
        case_id="json_maxcount_177_pull",
        note="177 entrées valides générées via backup.json",
        setup_mode="json",
        entries=VALID_MAXCOUNT_ENTRIES,
        post_action="none",
    ),
    GeneratedCase(
        case_id="raw_storage_edge_186_show_last",
        note="186 entrées de 19 octets exacts générées en raw jusqu'à 4094 octets",
        setup_mode="raw",
        entries=RAW_STORAGE_EDGE_ENTRIES,
        post_action="show",
        position=186,
        expected_selected_nickname=RAW_STORAGE_EDGE_ENTRIES[-1].nickname,
    ),
    GeneratedCase(
        case_id="raw_overlong_20byte_show",
        note="Nickname raw de 20 octets, hors limite companion mais décodable par l'app",
        setup_mode="raw",
        entries=(RAW_OVERLONG_ENTRY,),
        post_action="show",
        position=1,
        expected_selected_nickname=RAW_OVERLONG_ENTRY.nickname,
        expected_min_corruptions=1,
    ),
    GeneratedCase(
        case_id="raw_blank_plain_space_show_second",
        note="Entrées raw avec nickname vide et whitespace, puis show du second item visible",
        setup_mode="raw",
        entries=RAW_BLANK_MIX_ENTRIES,
        post_action="show",
        position=2,
        expected_selected_nickname="plain-target",
    ),
)


def build_backup_json(entries: tuple[SeedEntry, ...]) -> str:
    payload = {
        "format": "ledger-passwords-companion.v1",
        "storage_size": STORAGE_SIZE,
        "app": {"name": "Passwords", "version": "fuzz-generated-metadata"},
        "parsed": [{"nickname": entry.nickname, "charsets": list(entry.charsets)} for entry in entries],
        "nicknames_erased_but_still_stored": [],
        "corruptions_encountered": [],
        "raw_metadatas": None,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


def build_raw(entries: tuple[SeedEntry, ...], storage_size: int = STORAGE_SIZE) -> bytes:
    raw = bytearray(storage_size)
    offset = 0
    for entry in entries:
        nickname_bytes = entry.nickname.encode("utf-8")
        length = 1 + len(nickname_bytes)
        if length > 0xFF:
            raise HarnessError(f"Generated nickname too long for raw metadata: {entry.nickname!r}")
        entry_size = length + 2
        if offset + entry_size + 2 > storage_size:
            raise HarnessError(
                f"Generated raw metadata does not fit storage: entry {entry.nickname!r} at offset {offset}",
            )
        raw[offset] = length
        raw[offset + 1] = 0x00
        raw[offset + 2] = 0xFF
        raw[offset + 3 : offset + 3 + len(nickname_bytes)] = nickname_bytes
        offset += entry_size
    return bytes(raw)


def cleanup_speculos_containers() -> None:
    docker = shutil.which("docker")
    if docker is None:
        return
    result = subprocess.run(
        [docker, "ps", "-q", "--filter", "ancestor=ghcr.io/ledgerhq/speculos"],
        capture_output=True,
        text=True,
        check=False,
    )
    container_ids = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if container_ids:
        subprocess.run([docker, "stop", *container_ids], capture_output=True, text=True, check=False)


def safe_screen_text(harness: SpeculosHarness) -> str:
    try:
        return harness.current_screen_text()
    except Exception:
        return ""


def has_crash_signature(harness: SpeculosHarness) -> bool:
    return "crashed with signal" in harness.log_tail(lines=120).lower()


def load_pull_payload(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def expected_nicknames(case: GeneratedCase) -> list[str]:
    return sorted(entry.nickname for entry in case.entries)


def execute_post_action(harness: SpeculosHarness, case: GeneratedCase) -> dict[str, Any]:
    if case.post_action == "none":
        return {"action": "none"}
    if case.post_action == "show":
        screen_text = harness.show_password(position=case.position)
        selected = screen_text.split(" | ")[0].strip() if screen_text else ""
        if case.expected_selected_nickname is not None and selected != case.expected_selected_nickname:
            raise HarnessError(
                f"Unexpected selected nickname for {case.case_id}: expected {case.expected_selected_nickname!r}, got {selected!r}",
            )
        return {"action": "show", "position": case.position, "screen_text": screen_text, "selected_nickname": selected}
    if case.post_action == "type":
        screen_text = harness.type_password(position=case.position)
        return {"action": "type", "position": case.position, "screen_text": screen_text}
    raise HarnessError(f"Unknown post action: {case.post_action}")


def assert_case_limits(case: GeneratedCase) -> None:
    if case.setup_mode == "json":
        for entry in case.entries:
            if len(entry.nickname.encode("utf-8")) > 19:
                raise HarnessError(f"{case.case_id}: invalid json setup for nickname {entry.nickname!r}")


def run_case(
    *,
    case: GeneratedCase,
    artifacts_dir: Path,
    root_dir: Path,
    app_path: Path,
    cli_bin: Path,
    server: str,
    apdu_port: int,
    api_port: int,
    display: str,
) -> dict[str, Any]:
    assert_case_limits(case)
    cleanup_speculos_containers()
    harness = SpeculosHarness(
        root_dir=root_dir,
        app_path=app_path,
        cli_bin=cli_bin,
        server=server,
        apdu_port=apdu_port,
        api_port=api_port,
        display=display,
    )
    seed_backup = artifacts_dir / f"{case.case_id}-seed.json"
    seed_raw = artifacts_dir / f"{case.case_id}-seed.bin"
    pulled_backup = artifacts_dir / f"{case.case_id}-pull.json"
    seed_backup.write_text(build_backup_json(case.entries), encoding="utf-8")
    if case.setup_mode == "raw":
        seed_raw.write_bytes(build_raw(case.entries))

    harness.start()
    try:
        harness.initialize_first_run()
        if case.setup_mode == "json":
            setup_result = {"action": "push", "stdout": harness.push_backup(seed_backup).stdout.strip()}
        elif case.setup_mode == "raw":
            harness.load_raw_metadata(seed_raw.read_bytes())
            setup_result = {"action": "load_raw_metadata", "raw_path": str(seed_raw), "raw_size": seed_raw.stat().st_size}
        else:
            raise HarnessError(f"Unknown setup mode: {case.setup_mode}")

        post_action = execute_post_action(harness, case)
        final_pull = harness.pull_backup(pulled_backup)
        payload = load_pull_payload(pulled_backup)
        final_nicknames = sorted(entry["nickname"] for entry in payload.get("parsed", []))
        expected = expected_nicknames(case)
        if final_nicknames != expected:
            raise HarnessError(
                f"Final pulled state mismatch for {case.case_id}: expected {expected!r}, got {final_nicknames!r}",
            )
        corruption_count = len(payload.get("corruptions_encountered", []))
        if corruption_count < case.expected_min_corruptions:
            raise HarnessError(
                f"Expected at least {case.expected_min_corruptions} corruption(s) for {case.case_id}, got {corruption_count}",
            )
        return {
            "case_id": case.case_id,
            "status": "ok",
            "note": case.note,
            "setup_mode": case.setup_mode,
            "setup_result": setup_result,
            "post_action": post_action,
            "final_pull_stdout": final_pull.stdout.strip(),
            "final_nicknames": final_nicknames,
            "corruption_count": corruption_count,
        }
    except Exception as error:
        return {
            "case_id": case.case_id,
            "status": "failed",
            "note": case.note,
            "setup_mode": case.setup_mode,
            "error": str(error),
            "screen_text": safe_screen_text(harness),
            "speculos_crash": has_crash_signature(harness),
            "speculos_log_tail": harness.log_tail(),
            "seed_backup": seed_backup.read_text(encoding="utf-8"),
            "seed_raw_hex_prefix": seed_raw.read_bytes()[:256].hex() if seed_raw.exists() else None,
            "pulled_backup": pulled_backup.read_text(encoding="utf-8") if pulled_backup.exists() else None,
        }
    finally:
        harness.stop()
        cleanup_speculos_containers()


def parse_args() -> argparse.Namespace:
    root_dir = default_root_dir()
    parser = argparse.ArgumentParser(description="FZ-01 generated metadata fuzzer via CLI + Speculos")
    parser.add_argument("--app", default=str(default_app_path(root_dir)), help="Path to app.elf")
    parser.add_argument("--cli", default=str(default_cli_bin(root_dir)), help="Path to ledger-pw CLI")
    parser.add_argument("--server", default="127.0.0.1", help="Speculos API/APDU host")
    parser.add_argument(
        "--apdu-port",
        type=int,
        default=0,
        help="Speculos APDU TCP port. Default: choose a free port per case.",
    )
    parser.add_argument(
        "--api-port",
        type=int,
        default=0,
        help="Speculos REST API port. Default: choose a free port per case.",
    )
    parser.add_argument("--display", default="headless", help="Speculos display mode")
    parser.add_argument("--cases", default="", help="Comma-separated case ids. Empty means the full curated set.")
    parser.add_argument(
        "--artifacts-dir",
        default="",
        help="Directory to keep generated artifacts. Default: temp dir.",
    )
    parser.add_argument("--json-out", default="", help="Optional path for the JSON report.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root_dir = default_root_dir()
    app_path = Path(args.app).resolve()
    cli_bin = Path(args.cli).resolve()
    ensure_app_exists(app_path)
    ensure_cli_exists(cli_bin)

    selected_ids = {item.strip() for item in args.cases.split(",") if item.strip()}
    selected_cases = tuple(case for case in CASES if not selected_ids or case.case_id in selected_ids)
    if not selected_cases:
        raise SystemExit("No generated cases selected")

    artifacts_dir_obj: tempfile.TemporaryDirectory[str] | None = None
    if args.artifacts_dir:
        artifacts_dir = Path(args.artifacts_dir).resolve()
        artifacts_dir.mkdir(parents=True, exist_ok=True)
    else:
        artifacts_dir_obj = tempfile.TemporaryDirectory(prefix="ledger-pw-fz01-")
        artifacts_dir = Path(artifacts_dir_obj.name)

    results: list[dict[str, Any]] = []
    try:
        for case in selected_cases:
            print(f"[FZ-01] {case.case_id}", flush=True)
            apdu_port = args.apdu_port or find_free_port(args.server)
            api_port = args.api_port or find_free_port(args.server)
            results.append(
                run_case(
                    case=case,
                    artifacts_dir=artifacts_dir,
                    root_dir=root_dir,
                    app_path=app_path,
                    cli_bin=cli_bin,
                    server=args.server,
                    apdu_port=apdu_port,
                    api_port=api_port,
                    display=args.display,
                ),
            )
    finally:
        if artifacts_dir_obj is not None:
            artifacts_dir_obj.cleanup()

    failures = [result for result in results if result["status"] != "ok"]
    report = {
        "fuzzer": "FZ-01",
        "cases": [case.case_id for case in selected_cases],
        "artifacts_dir": str(artifacts_dir),
        "results": results,
        "failures": failures,
    }

    if args.json_out:
        Path(args.json_out).write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    print_json(report)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
