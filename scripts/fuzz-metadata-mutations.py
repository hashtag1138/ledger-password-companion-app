#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

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
class MutationCase:
    case_id: str
    note: str
    seed_entries: tuple[SeedEntry, ...]
    mutation: str
    scenarios: tuple[str, ...]
    position: int = 1


@dataclass(frozen=True)
class EntryOffset:
    offset: int
    length: int


def build_backup_json(entries: tuple[SeedEntry, ...]) -> str:
    payload = {
        "format": "ledger-passwords-companion.v1",
        "storage_size": 4096,
        "app": {"name": "Passwords", "version": "fuzz-metadata-mutations"},
        "parsed": [{"nickname": entry.nickname, "charsets": list(entry.charsets)} for entry in entries],
        "nicknames_erased_but_still_stored": [],
        "corruptions_encountered": [],
        "raw_metadatas": None,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


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


def terminator_offset(raw: bytes) -> int:
    offsets = parse_entry_offsets(raw)
    if not offsets:
        return 0
    last = offsets[-1]
    return last.offset + last.length + 2


def mutate_first_len_plus4(raw: bytes) -> bytes:
    mutated = bytearray(raw)
    offsets = parse_entry_offsets(mutated)
    mutated[offsets[0].offset] = min(0xFF, offsets[0].length + 4)
    return bytes(mutated)


def mutate_first_charset_ff(raw: bytes) -> bytes:
    mutated = bytearray(raw)
    offsets = parse_entry_offsets(mutated)
    mutated[offsets[0].offset + 2] = 0xFF
    return bytes(mutated)


def mutate_second_kind_unknown(raw: bytes) -> bytes:
    mutated = bytearray(raw)
    offsets = parse_entry_offsets(mutated)
    if len(offsets) < 2:
        raise HarnessError("second_kind_unknown requires at least two entries")
    mutated[offsets[1].offset + 1] = 0x7F
    return bytes(mutated)


def mutate_second_len_plus1(raw: bytes) -> bytes:
    mutated = bytearray(raw)
    offsets = parse_entry_offsets(mutated)
    if len(offsets) < 2:
        raise HarnessError("second_len_plus1 requires at least two entries")
    mutated[offsets[1].offset] = min(0xFF, offsets[1].length + 1)
    return bytes(mutated)


def mutate_second_len_overflow(raw: bytes) -> bytes:
    mutated = bytearray(raw)
    offsets = parse_entry_offsets(mutated)
    if len(offsets) < 2:
        raise HarnessError("second_len_overflow requires at least two entries")
    second = offsets[1]
    remaining = len(mutated) - second.offset - 1
    mutated[second.offset] = min(0xFF, remaining)
    return bytes(mutated)


def mutate_terminator_removed_tail_ff(raw: bytes) -> bytes:
    mutated = bytearray(raw)
    tail = terminator_offset(mutated)
    end = min(len(mutated), tail + 32)
    for index in range(tail, end):
        mutated[index] = 0xFF
    return bytes(mutated)


def mutate_padding_non_zero_after_terminator(raw: bytes) -> bytes:
    mutated = bytearray(raw)
    tail = terminator_offset(mutated)
    end = min(len(mutated), tail + 18)
    if tail < len(mutated):
        mutated[tail] = 0x00
    if tail + 1 < len(mutated):
        mutated[tail + 1] = 0x00
    for index in range(tail + 2, end):
        mutated[index] = 0xAA
    return bytes(mutated)


MUTATORS: dict[str, Callable[[bytes], bytes]] = {
    "first_len_plus4": mutate_first_len_plus4,
    "first_charset_ff": mutate_first_charset_ff,
    "second_kind_unknown": mutate_second_kind_unknown,
    "second_len_plus1": mutate_second_len_plus1,
    "second_len_overflow": mutate_second_len_overflow,
    "terminator_removed_tail_ff": mutate_terminator_removed_tail_ff,
    "padding_non_zero_after_terminator": mutate_padding_non_zero_after_terminator,
}


CASES: tuple[MutationCase, ...] = (
    MutationCase(
        case_id="first_len_plus4",
        note="Length du premier entry gonflé pour chevaucher la suite",
        seed_entries=(SeedEntry("github"), SeedEntry("gmail")),
        mutation="first_len_plus4",
        scenarios=("pull", "show", "type"),
    ),
    MutationCase(
        case_id="first_charset_ff",
        note="Charset du premier entry forcé à 0xFF",
        seed_entries=(SeedEntry("github"), SeedEntry("gmail")),
        mutation="first_charset_ff",
        scenarios=("pull", "show", "type", "delete"),
    ),
    MutationCase(
        case_id="second_kind_unknown",
        note="Kind incohérent sur le second entry",
        seed_entries=(SeedEntry("github"), SeedEntry("gmail")),
        mutation="second_kind_unknown",
        scenarios=("pull", "show", "type"),
    ),
    MutationCase(
        case_id="second_len_plus1",
        note="Length du second entry étendu d'un octet dans le padding",
        seed_entries=(SeedEntry("github"), SeedEntry("gmail")),
        mutation="second_len_plus1",
        scenarios=("pull", "show", "type", "delete"),
        position=2,
    ),
    MutationCase(
        case_id="second_len_overflow",
        note="Length du second entry forcé pour déborder jusqu'au buffer",
        seed_entries=(SeedEntry("github"), SeedEntry("gmail")),
        mutation="second_len_overflow",
        scenarios=("pull", "show"),
    ),
    MutationCase(
        case_id="terminator_removed_tail_ff",
        note="Suppression du terminateur puis tail rempli de 0xFF",
        seed_entries=(SeedEntry("github"), SeedEntry("gmail")),
        mutation="terminator_removed_tail_ff",
        scenarios=("pull", "show"),
    ),
    MutationCase(
        case_id="padding_non_zero_after_terminator",
        note="Padding non nul après terminateur intact",
        seed_entries=(SeedEntry("github"), SeedEntry("gmail")),
        mutation="padding_non_zero_after_terminator",
        scenarios=("pull", "show", "type", "delete"),
    ),
)


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


def pull_summary(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return {
        "parsed_nicknames": sorted(entry["nickname"] for entry in payload.get("parsed", [])),
        "erased_count": len(payload.get("nicknames_erased_but_still_stored", [])),
        "corruption_count": len(payload.get("corruptions_encountered", [])),
        "raw_present": payload.get("raw_metadatas") is not None,
    }


def execute_scenario(
    harness: SpeculosHarness,
    case: MutationCase,
    scenario: str,
    mutated_raw: bytes,
    pulled_backup: Path,
) -> dict[str, Any]:
    harness.load_raw_metadata(mutated_raw)
    if scenario == "pull":
        result = harness.pull_backup(pulled_backup)
        return {"action": "pull", "stdout": result.stdout.strip(), "summary": pull_summary(pulled_backup)}
    if scenario == "show":
        screen_text = harness.show_password(position=case.position)
        return {"action": "show", "position": case.position, "screen_text": screen_text}
    if scenario == "type":
        screen_text = harness.type_password(position=case.position)
        return {"action": "type", "position": case.position, "screen_text": screen_text}
    if scenario == "delete":
        screen_text = harness.delete_password(position=case.position)
        return {"action": "delete", "position": case.position, "screen_text": screen_text}
    raise HarnessError(f"Unknown scenario {scenario}")


def run_case(
    *,
    root_dir: Path,
    app_path: Path,
    cli_bin: Path,
    server: str,
    api_port: int,
    apdu_port: int,
    display: str,
    case: MutationCase,
    scenario: str,
    artifacts_dir: Path,
) -> dict[str, Any]:
    cleanup_speculos_containers()
    backup_path = artifacts_dir / f"{case.case_id}.json"
    seed_raw_path = artifacts_dir / f"{case.case_id}-seed.bin"
    mutated_raw_path = artifacts_dir / f"{case.case_id}-mutated.bin"
    pulled_backup = artifacts_dir / f"{case.case_id}-{scenario}-pulled.json"
    backup_path.write_text(build_backup_json(case.seed_entries), encoding="utf-8")
    export_seed_raw(cli_bin, root_dir, backup_path, seed_raw_path)
    seed_raw = seed_raw_path.read_bytes()
    mutated_raw = MUTATORS[case.mutation](seed_raw)
    mutated_raw_path.write_bytes(mutated_raw)

    harness = SpeculosHarness(
        root_dir=root_dir,
        app_path=app_path,
        cli_bin=cli_bin,
        server=server,
        apdu_port=apdu_port,
        api_port=api_port,
        display=display,
    )
    harness.start()
    try:
        harness.initialize_first_run()
        outcome = execute_scenario(harness, case, scenario, mutated_raw, pulled_backup)
        return {
            "case_id": case.case_id,
            "scenario": scenario,
            "status": "ok",
            "note": case.note,
            "mutation": case.mutation,
            "seed_entries": [entry.nickname for entry in case.seed_entries],
            "mutated_raw_path": str(mutated_raw_path),
            "outcome": outcome,
        }
    except Exception as error:
        return {
            "case_id": case.case_id,
            "scenario": scenario,
            "status": "failed",
            "note": case.note,
            "mutation": case.mutation,
            "seed_entries": [entry.nickname for entry in case.seed_entries],
            "error": str(error),
            "screen_text": safe_screen_text(harness),
            "speculos_log_tail": harness.log_tail(),
            "mutated_raw_hex_prefix": mutated_raw[:128].hex(),
            "mutated_raw_path": str(mutated_raw_path),
            "pulled_backup": pulled_backup.read_text(encoding="utf-8") if pulled_backup.exists() else None,
        }
    finally:
        try:
            harness.stop()
        except Exception:
            pass
        cleanup_speculos_containers()


def safe_screen_text(harness: SpeculosHarness) -> str:
    try:
        return harness.current_screen_text()
    except Exception:
        return ""


def parse_args() -> argparse.Namespace:
    root_dir = default_root_dir()
    parser = argparse.ArgumentParser(
        description="FZ-02 valid metadata mutation fuzzer against app-passwords via CLI + Speculos",
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
        "--scenarios",
        default="",
        help="Comma-separated scenarios to override per-case defaults.",
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
        raise SystemExit("No mutation cases selected")

    scenario_override = tuple(part.strip() for part in args.scenarios.split(",") if part.strip())
    artifacts_dir = Path(args.artifacts_dir).resolve() if args.artifacts_dir else Path(
        tempfile.mkdtemp(prefix="ledger-pw-fz02-"),
    )
    artifacts_dir.mkdir(parents=True, exist_ok=True)

    results = []
    for case in cases:
        scenarios = scenario_override or case.scenarios
        for scenario in scenarios:
            print(f"[FZ-02] {case.case_id} / {scenario}", flush=True)
            results.append(
                run_case(
                    root_dir=root_dir,
                    app_path=app_path,
                    cli_bin=cli_bin,
                    server=args.server,
                    api_port=find_free_port(args.server),
                    apdu_port=find_free_port(args.server),
                    display=args.display,
                    case=case,
                    scenario=scenario,
                    artifacts_dir=artifacts_dir,
                ),
            )

    report = {
        "fuzzer": "FZ-02 valid metadata mutation",
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
