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


@dataclass(frozen=True)
class SeedEntry:
    nickname: str
    charsets: tuple[str, ...] = ("ALL_SETS",)


@dataclass(frozen=True)
class RoundTripCase:
    case_id: str
    note: str
    parsed_entries: tuple[SeedEntry, ...]
    expected_entries: tuple[SeedEntry, ...]
    raw_entries: tuple[SeedEntry, ...] | None = None


CASES: tuple[RoundTripCase, ...] = (
    RoundTripCase(
        case_id="empty_three_cycles",
        note="Vault vide sur trois cycles",
        parsed_entries=(),
        expected_entries=(),
    ),
    RoundTripCase(
        case_id="sofian_space_three_cycles",
        note="Nickname avec espace au milieu",
        parsed_entries=(SeedEntry("sofian terki"),),
        expected_entries=(SeedEntry("sofian terki"),),
    ),
    RoundTripCase(
        case_id="mixed_charsets_three_cycles",
        note="Corpus multi-entrée avec charsets variés",
        parsed_entries=(
            SeedEntry("github", ("UPPERCASE", "LOWERCASE", "NUMBERS")),
            SeedEntry("gmail", ("ALL_SETS",)),
            SeedEntry("proton", ("LOWERCASE", "SPECIAL", "BRACKETS")),
        ),
        expected_entries=(
            SeedEntry("github", ("UPPERCASE", "LOWERCASE", "NUMBERS")),
            SeedEntry("gmail", ("ALL_SETS",)),
            SeedEntry("proton", ("LOWERCASE", "SPECIAL", "BRACKETS")),
        ),
    ),
    RoundTripCase(
        case_id="utf8_19bytes_three_cycles",
        note="Nickname UTF-8 à exactement 19 octets",
        parsed_entries=(SeedEntry("éééééééééa"),),
        expected_entries=(SeedEntry("éééééééééa"),),
    ),
    RoundTripCase(
        case_id="embedded_raw_preferred_three_cycles",
        note="raw_metadatas embarqué doit rester prioritaire et stable",
        parsed_entries=(SeedEntry("parsed-loses"),),
        expected_entries=(SeedEntry("raw-wins"),),
        raw_entries=(SeedEntry("raw-wins"),),
    ),
)


def build_backup_json(
    *,
    parsed_entries: tuple[SeedEntry, ...],
    raw_hex: str | None = None,
    app_version: str = "fuzz-roundtrip",
) -> str:
    payload = {
        "format": "ledger-passwords-companion.v1",
        "storage_size": 4096,
        "app": {"name": "Passwords", "version": app_version},
        "parsed": [{"nickname": entry.nickname, "charsets": list(entry.charsets)} for entry in parsed_entries],
        "nicknames_erased_but_still_stored": [],
        "corruptions_encountered": [],
        "raw_metadatas": raw_hex,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


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
        subprocess.run([docker, "kill", *container_ids], capture_output=True, text=True, check=False)


def export_raw_via_cli(cli_bin: Path, root_dir: Path, backup_path: Path, raw_path: Path) -> None:
    result = subprocess.run(
        [str(cli_bin), "file", "export-raw", str(backup_path), "--out", str(raw_path)],
        cwd=root_dir,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise HarnessError(
            f"Failed to export raw metadata from {backup_path}: stdout={result.stdout!r} stderr={result.stderr!r}",
        )


def raw_from_backup_json(cli_bin: Path, root_dir: Path, backup_path: Path) -> bytes:
    payload = json.loads(backup_path.read_text(encoding="utf-8"))
    raw_hex = payload.get("raw_metadatas")
    if raw_hex:
        return bytes.fromhex(raw_hex)
    with tempfile.TemporaryDirectory(prefix="ledger-pw-fz08-raw-") as tmp_dir:
        raw_path = Path(tmp_dir) / "metadata.bin"
        export_raw_via_cli(cli_bin, root_dir, backup_path, raw_path)
        return raw_path.read_bytes()


def semantics_from_backup_json(backup_path: Path) -> list[tuple[str, tuple[str, ...]]]:
    payload = json.loads(backup_path.read_text(encoding="utf-8"))
    return sorted(
        (entry["nickname"], tuple(entry.get("charsets", [])))
        for entry in payload.get("parsed", [])
    )


def expected_semantics(entries: tuple[SeedEntry, ...]) -> list[tuple[str, tuple[str, ...]]]:
    return sorted((entry.nickname, tuple(entry.charsets)) for entry in entries)


def assert_utf8_limit(case: RoundTripCase) -> None:
    if case.case_id != "utf8_19bytes_three_cycles":
        return
    nickname = case.expected_entries[0].nickname
    utf8_length = len(nickname.encode("utf-8"))
    if utf8_length != 19:
        raise HarnessError(f"Expected 19 UTF-8 bytes for {nickname!r}, got {utf8_length}")


def prepare_seed_backup(
    *,
    case: RoundTripCase,
    root_dir: Path,
    cli_bin: Path,
    artifacts_dir: Path,
) -> Path:
    seed_path = artifacts_dir / f"{case.case_id}-seed.json"
    if case.raw_entries is None:
        seed_path.write_text(build_backup_json(parsed_entries=case.parsed_entries), encoding="utf-8")
        return seed_path

    raw_seed_path = artifacts_dir / f"{case.case_id}-raw-seed.json"
    raw_seed_path.write_text(build_backup_json(parsed_entries=case.raw_entries), encoding="utf-8")
    raw_bytes = raw_from_backup_json(cli_bin, root_dir, raw_seed_path)
    seed_path.write_text(
        build_backup_json(parsed_entries=case.parsed_entries, raw_hex=raw_bytes.hex()),
        encoding="utf-8",
    )
    return seed_path


def run_case(
    *,
    case: RoundTripCase,
    cycles: int,
    artifacts_dir: Path,
    root_dir: Path,
    app_path: Path,
    cli_bin: Path,
    server: str,
    apdu_port: int,
    api_port: int,
    display: str,
) -> dict[str, Any]:
    assert_utf8_limit(case)
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
    seed_backup = prepare_seed_backup(case=case, root_dir=root_dir, cli_bin=cli_bin, artifacts_dir=artifacts_dir)
    expected_raw = raw_from_backup_json(cli_bin, root_dir, seed_backup)
    expected_semantic_entries = expected_semantics(case.expected_entries)
    harness.start()
    try:
        harness.initialize_first_run()
        cycle_results: list[dict[str, Any]] = []
        current_input = seed_backup
        for cycle in range(1, cycles + 1):
            push = harness.push_backup(current_input)
            verify_seed = harness.verify_backup(seed_backup)
            verify_current = harness.verify_backup(current_input)

            pulled_backup = artifacts_dir / f"{case.case_id}-cycle{cycle}-pull.json"
            pull = harness.pull_backup(pulled_backup)
            actual_raw = raw_from_backup_json(cli_bin, root_dir, pulled_backup)
            actual_semantics = semantics_from_backup_json(pulled_backup)
            if actual_raw != expected_raw:
                raise HarnessError(
                    f"{case.case_id} cycle {cycle}: raw mismatch baseline ({len(actual_raw)} bytes vs {len(expected_raw)} bytes)",
                )
            if actual_semantics != expected_semantic_entries:
                raise HarnessError(
                    f"{case.case_id} cycle {cycle}: semantic mismatch expected {expected_semantic_entries!r}, got {actual_semantics!r}",
                )

            cycle_results.append(
                {
                    "cycle": cycle,
                    "push_stdout": push.stdout.strip(),
                    "verify_seed_stdout": verify_seed.stdout.strip(),
                    "verify_current_stdout": verify_current.stdout.strip(),
                    "pull_stdout": pull.stdout.strip(),
                    "raw_size": len(actual_raw),
                    "semantics": actual_semantics,
                    "raw_matches_baseline": True,
                },
            )
            current_input = pulled_backup

        return {
            "case_id": case.case_id,
            "status": "ok",
            "note": case.note,
            "cycles": cycles,
            "expected_semantics": expected_semantic_entries,
            "expected_raw_size": len(expected_raw),
            "cycle_results": cycle_results,
        }
    except Exception as error:
        return {
            "case_id": case.case_id,
            "status": "failed",
            "note": case.note,
            "cycles": cycles,
            "error": str(error),
            "screen_text": safe_screen_text(harness),
            "speculos_log_tail": harness.log_tail(),
            "seed_backup": seed_backup.read_text(encoding="utf-8"),
        }
    finally:
        harness.stop()
        cleanup_speculos_containers()


def safe_screen_text(harness: SpeculosHarness) -> str:
    try:
        return harness.current_screen_text()
    except Exception:
        return ""


def parse_args() -> argparse.Namespace:
    root_dir = default_root_dir()
    parser = argparse.ArgumentParser(description="FZ-08 round-trip stability against app-passwords via CLI + Speculos")
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
    parser.add_argument("--cycles", type=int, default=3, help="Number of push/verify/pull cycles per case.")
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
        raise SystemExit("No round-trip cases selected")

    artifacts_dir_obj: tempfile.TemporaryDirectory[str] | None = None
    if args.artifacts_dir:
        artifacts_dir = Path(args.artifacts_dir).resolve()
        artifacts_dir.mkdir(parents=True, exist_ok=True)
    else:
        artifacts_dir_obj = tempfile.TemporaryDirectory(prefix="ledger-pw-fz08-")
        artifacts_dir = Path(artifacts_dir_obj.name)

    results: list[dict[str, Any]] = []
    try:
        for case in selected_cases:
            apdu_port = args.apdu_port or find_free_port(args.server)
            api_port = args.api_port or find_free_port(args.server)
            results.append(
                run_case(
                    case=case,
                    cycles=args.cycles,
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
        "fuzzer": "FZ-08",
        "cases": [case.case_id for case in selected_cases],
        "cycles": args.cycles,
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
