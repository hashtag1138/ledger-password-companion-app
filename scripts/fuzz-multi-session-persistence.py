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
class PersistenceCase:
    case_id: str
    note: str
    entries: tuple[SeedEntry, ...]
    post_restart_action: str
    position: int = 1
    mutation: str | None = None


@dataclass(frozen=True)
class EntryOffset:
    offset: int
    length: int


CASES: tuple[PersistenceCase, ...] = (
    PersistenceCase(
        case_id="sofian_restart_list",
        note="Simple state reread after restart through the list screen",
        entries=(SeedEntry("sofian terki"),),
        post_restart_action="list",
    ),
    PersistenceCase(
        case_id="sofian_restart_show_first",
        note="Simple state reread after restart through show password",
        entries=(SeedEntry("sofian terki"),),
        post_restart_action="show",
        position=1,
    ),
    PersistenceCase(
        case_id="sofian_restart_type_first",
        note="Simple state reread after restart through type password",
        entries=(SeedEntry("sofian terki"),),
        post_restart_action="type",
        position=1,
    ),
    PersistenceCase(
        case_id="alpha_beta_restart_show_second",
        note="Two valid entries reread after restart then show the second item",
        entries=(SeedEntry("alpha"), SeedEntry("beta")),
        post_restart_action="show",
        position=2,
    ),
    PersistenceCase(
        case_id="dense_twelve_restart_show_last",
        note="Dense list of 12 entries reread after restart then show the last item",
        entries=tuple(SeedEntry(f"slot-{index:02d}") for index in range(1, 13)),
        post_restart_action="show",
        position=12,
    ),
    PersistenceCase(
        case_id="second_len_plus1_restart_show_second",
        note="Accepted corrupted raw then reread after restart before showing the second item",
        entries=(SeedEntry("github"), SeedEntry("gmail")),
        post_restart_action="show",
        position=2,
        mutation="second_len_plus1",
    ),
)


def build_backup_json(entries: tuple[SeedEntry, ...]) -> str:
    payload = {
        "format": "ledger-passwords-companion.v1",
        "storage_size": 4096,
        "app": {"name": "Passwords", "version": "fuzz-multi-session-persistence"},
        "parsed": [{"nickname": entry.nickname, "charsets": list(entry.charsets)} for entry in entries],
        "nicknames_erased_but_still_stored": [],
        "corruptions_encountered": [],
        "raw_metadatas": None,
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
        subprocess.run([docker, "stop", *container_ids], capture_output=True, text=True, check=False)


def export_seed_raw(cli_bin: Path, root_dir: Path, backup_path: Path, raw_path: Path) -> None:
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


def parse_entry_offsets(raw: bytes) -> list[EntryOffset]:
    offsets: list[EntryOffset] = []
    offset = 0
    while offset < len(raw):
        length = raw[offset]
        if length == 0:
            break
        offsets.append(EntryOffset(offset=offset, length=length))
        offset += length + 2
    return offsets


def mutate_second_len_plus1(raw: bytes) -> bytes:
    mutated = bytearray(raw)
    offsets = parse_entry_offsets(mutated)
    if len(offsets) < 2:
        raise HarnessError("second_len_plus1 requires at least two entries")
    mutated[offsets[1].offset] = min(0xFF, offsets[1].length + 1)
    return bytes(mutated)


def prepare_seed_backup(
    *,
    case: PersistenceCase,
    artifacts_dir: Path,
) -> Path:
    backup_path = artifacts_dir / f"{case.case_id}-seed.json"
    backup_path.write_text(build_backup_json(case.entries), encoding="utf-8")
    return backup_path


def load_backup_nicknames(path: Path) -> list[str]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return sorted(entry["nickname"] for entry in payload.get("parsed", []))


def safe_screen_text(harness: SpeculosHarness) -> str:
    try:
        return harness.current_screen_text()
    except Exception:
        return ""


def assert_case_limits(case: PersistenceCase) -> None:
    for entry in case.entries:
        utf8_length = len(entry.nickname.encode("utf-8"))
        if utf8_length > 19:
            raise HarnessError(
                f"{case.case_id}: nickname {entry.nickname!r} exceeds Ledger limit with {utf8_length} UTF-8 bytes",
            )


def find_nvram_snapshot(state_dir: Path) -> Path:
    candidates = sorted(path for path in state_dir.glob("*_nvram.bin") if path.is_file())
    if not candidates:
        raise HarnessError(f"No NVRAM snapshot found in {state_dir}")
    if len(candidates) > 1:
        raise HarnessError(f"Multiple NVRAM snapshots found in {state_dir}: {[path.name for path in candidates]!r}")
    return candidates[0]


def execute_post_restart_action(harness: SpeculosHarness, case: PersistenceCase) -> dict[str, Any]:
    if case.post_restart_action == "list":
        screen_text = harness.show_password_list()
        return {"action": "list", "screen_text": screen_text}
    if case.post_restart_action == "show":
        screen_text = harness.show_password(position=case.position)
        return {"action": "show", "position": case.position, "screen_text": screen_text}
    if case.post_restart_action == "type":
        screen_text = harness.type_password(position=case.position)
        return {"action": "type", "position": case.position, "screen_text": screen_text}
    raise HarnessError(f"Unknown post-restart action: {case.post_restart_action}")


def run_case(
    *,
    case: PersistenceCase,
    artifacts_dir: Path,
    root_dir: Path,
    app_path: Path,
    cli_bin: Path,
    server: str,
    display: str,
) -> dict[str, Any]:
    assert_case_limits(case)
    cleanup_speculos_containers()

    seed_backup = prepare_seed_backup(case=case, artifacts_dir=artifacts_dir)
    seed_raw_path = artifacts_dir / f"{case.case_id}-seed.bin"
    mutated_raw_path = artifacts_dir / f"{case.case_id}-mutated.bin"
    pre_restart_backup = artifacts_dir / f"{case.case_id}-pre-restart.json"
    post_restart_backup = artifacts_dir / f"{case.case_id}-post-restart.json"
    state_dir = artifacts_dir / f"{case.case_id}-state"
    nvram_path: Path | None = None

    writer = SpeculosHarness(
        root_dir=root_dir,
        app_path=app_path,
        cli_bin=cli_bin,
        server=server,
        apdu_port=find_free_port(server),
        api_port=find_free_port(server),
        display=display,
        state_dir=state_dir,
        save_nvram=True,
    )

    reader = SpeculosHarness(
        root_dir=root_dir,
        app_path=app_path,
        cli_bin=cli_bin,
        server=server,
        apdu_port=find_free_port(server),
        api_port=find_free_port(server),
        display=display,
        state_dir=state_dir,
        load_nvram=True,
        save_nvram=True,
    )

    initial_write: dict[str, Any] | None = None
    pre_restart_nicknames: list[str] | None = None
    post_restart_action: dict[str, Any] | None = None

    try:
        writer.start()
        writer.initialize_first_run()
        if case.mutation is None:
            push = writer.push_backup(seed_backup)
            initial_write = {"action": "push", "stdout": push.stdout.strip()}
        else:
            export_seed_raw(cli_bin, root_dir, seed_backup, seed_raw_path)
            seed_raw = seed_raw_path.read_bytes()
            if case.mutation != "second_len_plus1":
                raise HarnessError(f"Unsupported mutation: {case.mutation}")
            mutated_raw = mutate_second_len_plus1(seed_raw)
            mutated_raw_path.write_bytes(mutated_raw)
            writer.load_raw_metadata(mutated_raw)
            initial_write = {
                "action": "load_raw_metadata",
                "mutation": case.mutation,
                "mutated_raw_path": str(mutated_raw_path),
            }

        pre_pull = writer.pull_backup(pre_restart_backup)
        pre_restart_nicknames = load_backup_nicknames(pre_restart_backup)
        writer.stop()

        nvram_path = find_nvram_snapshot(state_dir)

        reader.start()
        post_restart_action = execute_post_restart_action(reader, case)
        post_pull = reader.pull_backup(post_restart_backup)
        post_restart_nicknames = load_backup_nicknames(post_restart_backup)
        if post_restart_nicknames != pre_restart_nicknames:
            raise HarnessError(
                f"Post-restart state mismatch for {case.case_id}: expected {pre_restart_nicknames!r}, got {post_restart_nicknames!r}",
            )

        return {
            "case_id": case.case_id,
            "status": "ok",
            "note": case.note,
            "initial_write": initial_write,
            "pre_restart_pull_stdout": pre_pull.stdout.strip(),
            "pre_restart_nicknames": pre_restart_nicknames,
            "nvram_path": str(nvram_path),
            "nvram_size": nvram_path.stat().st_size,
            "post_restart_action": post_restart_action,
            "post_restart_pull_stdout": post_pull.stdout.strip(),
            "post_restart_nicknames": post_restart_nicknames,
        }
    except Exception as error:
        return {
            "case_id": case.case_id,
            "status": "failed",
            "note": case.note,
            "error": str(error),
            "initial_write": initial_write,
            "pre_restart_nicknames": pre_restart_nicknames,
            "post_restart_action": post_restart_action,
            "writer_screen_text": safe_screen_text(writer),
            "reader_screen_text": safe_screen_text(reader),
            "writer_log_tail": writer.log_tail(),
            "reader_log_tail": reader.log_tail(),
            "state_dir": str(state_dir),
            "nvram_path": str(nvram_path) if nvram_path is not None else None,
            "nvram_files": [path.name for path in sorted(state_dir.glob("*_nvram.bin"))],
            "seed_backup": seed_backup.read_text(encoding="utf-8"),
            "pre_restart_backup": pre_restart_backup.read_text(encoding="utf-8") if pre_restart_backup.exists() else None,
            "post_restart_backup": post_restart_backup.read_text(encoding="utf-8") if post_restart_backup.exists() else None,
            "mutated_raw_hex_prefix": mutated_raw_path.read_bytes()[:128].hex() if mutated_raw_path.exists() else None,
        }
    finally:
        try:
            writer.stop()
        except Exception:
            pass
        try:
            reader.stop()
        except Exception:
            pass
        cleanup_speculos_containers()


def parse_args() -> argparse.Namespace:
    root_dir = default_root_dir()
    parser = argparse.ArgumentParser(
        description="FZ-12 multi-session persistence fuzzer for app-passwords via CLI + Speculos",
    )
    parser.add_argument("--app", default=str(default_app_path(root_dir)), help="Path to app.elf")
    parser.add_argument("--cli", default=str(default_cli_bin(root_dir)), help="Path to ledger-pw CLI")
    parser.add_argument("--server", default="127.0.0.1", help="Speculos API/APDU host")
    parser.add_argument("--display", default="headless", help="Speculos display mode")
    parser.add_argument(
        "--cases",
        default="",
        help="Comma-separated case ids. Empty means the full curated set.",
    )
    parser.add_argument(
        "--artifacts-dir",
        default="",
        help="Directory to keep generated files and pulled states. Default: temp dir.",
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

    selected_case_ids = {part.strip() for part in args.cases.split(",") if part.strip()}
    cases = tuple(case for case in CASES if not selected_case_ids or case.case_id in selected_case_ids)
    if not cases:
        raise SystemExit("No persistence cases selected")

    artifacts_dir = Path(args.artifacts_dir).resolve() if args.artifacts_dir else Path(
        tempfile.mkdtemp(prefix="ledger-pw-fz12-"),
    )
    artifacts_dir.mkdir(parents=True, exist_ok=True)

    results = []
    for case in cases:
        print(f"[FZ-12] {case.case_id}", flush=True)
        results.append(
            run_case(
                case=case,
                artifacts_dir=artifacts_dir,
                root_dir=root_dir,
                app_path=app_path,
                cli_bin=cli_bin,
                server=args.server,
                display=args.display,
            ),
        )

    report = {
        "fuzzer": "FZ-12 multi-session persistence",
        "cases": [case.case_id for case in cases],
        "artifacts_dir": str(artifacts_dir),
        "results": results,
        "failures": [result for result in results if result["status"] != "ok"],
    }
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print_json(report)
    return 1 if report["failures"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
