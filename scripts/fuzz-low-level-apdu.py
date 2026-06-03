#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

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
from fuzz_oracles import classify_oracle_evidence, collect_oracle_evidence


@dataclass(frozen=True)
class SeedEntry:
    nickname: str


@dataclass(frozen=True)
class ApduStep:
    label: str
    cla: int
    ins: int
    p1: int
    p2: int
    data: bytes


@dataclass(frozen=True)
class ApduCase:
    case_id: str
    note: str
    setup_entries: tuple[SeedEntry, ...]
    steps: tuple[ApduStep, ...]
    acceptable_states: tuple[tuple[str, ...], ...]
    auto_approve: bool = False


CLA_SDK = 0xB0
CLA_PASSWORDS = 0xE0
INS_GET_APP_INFO = 0x01
INS_GET_APP_CONFIG = 0x03
INS_DUMP_METADATAS = 0x04
INS_LOAD_METADATAS = 0x05
MORE_DATA = 0x00
LAST_CHUNK = 0xFF


def build_backup_json(entries: Iterable[SeedEntry]) -> str:
    payload = {
        "format": "ledger-passwords-companion.v1",
        "storage_size": 4096,
        "app": {"name": "Passwords", "version": "fuzz-low-level-apdu"},
        "parsed": [{"nickname": entry.nickname, "charsets": ["ALL_SETS"]} for entry in entries],
        "nicknames_erased_but_still_stored": [],
        "corruptions_encountered": [],
        "raw_metadatas": None,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


def build_raw(entries: Iterable[SeedEntry], *, padded: bool, storage_size: int = 4096) -> bytes:
    output = bytearray(storage_size if padded else max(storage_size, 4096))
    offset = 0
    for entry in entries:
        nickname = entry.nickname.encode("utf-8")
        entry_length = len(nickname) + 2
        if offset + entry_length + 2 > storage_size:
            raise HarnessError(f"Entries do not fit in {storage_size} bytes")
        output[offset] = len(nickname)
        output[offset + 1] = 0x00
        output[offset + 2] = 0xFF
        output[offset + 3 : offset + 3 + len(nickname)] = nickname
        offset += entry_length + 1
    if offset + 1 >= storage_size:
        raise HarnessError("No room left for raw terminator")
    output[offset] = 0x00
    output[offset + 1] = 0x00
    if padded:
        return bytes(output)
    return bytes(output[: offset + 2])


def names(entries: Iterable[SeedEntry]) -> tuple[str, ...]:
    return tuple(entry.nickname for entry in entries)


SETUP_KEEP = (SeedEntry("keep-alpha"), SeedEntry("keep-beta"))
TARGET_SMALL = (SeedEntry("load-alpha"), SeedEntry("load-beta"))
TARGET_BULK = tuple(SeedEntry(f"bulk-{index:02d}") for index in range(1, 23))

RAW_TARGET_SMALL = build_raw(TARGET_SMALL, padded=False)
RAW_TARGET_BULK = build_raw(TARGET_BULK, padded=False)
BULK_CHUNK_1 = RAW_TARGET_BULK[:0xFF]
BULK_CHUNK_2 = RAW_TARGET_BULK[0xFF:0x1FE]
BULK_CHUNK_3 = RAW_TARGET_BULK[0x1FE:]
PARTIAL_PREFIX = RAW_TARGET_SMALL[:32]


CASES: tuple[ApduCase, ...] = (
    ApduCase(
        case_id="load_zero_length_nonfinal_then_valid_final",
        note="Prefix a valid final LOAD with an empty non-final chunk",
        setup_entries=SETUP_KEEP,
        steps=(
            ApduStep("empty-nonfinal", CLA_PASSWORDS, INS_LOAD_METADATAS, MORE_DATA, 0x00, b""),
            ApduStep("valid-final", CLA_PASSWORDS, INS_LOAD_METADATAS, LAST_CHUNK, 0x00, RAW_TARGET_SMALL),
        ),
        acceptable_states=(names(TARGET_SMALL),),
        auto_approve=True,
    ),
    ApduCase(
        case_id="load_partial_prefix_abandon",
        note="Send a first non-final LOAD chunk then abandon the sequence",
        setup_entries=SETUP_KEEP,
        steps=(ApduStep("partial-prefix", CLA_PASSWORDS, INS_LOAD_METADATAS, MORE_DATA, 0x00, PARTIAL_PREFIX),),
        acceptable_states=(names(SETUP_KEEP),),
        auto_approve=True,
    ),
    ApduCase(
        case_id="load_valid_final_then_extra_nonfinal",
        note="Valid final LOAD followed by a stray non-final chunk",
        setup_entries=SETUP_KEEP,
        steps=(
            ApduStep("valid-final", CLA_PASSWORDS, INS_LOAD_METADATAS, LAST_CHUNK, 0x00, RAW_TARGET_SMALL),
            ApduStep("extra-nonfinal", CLA_PASSWORDS, INS_LOAD_METADATAS, MORE_DATA, 0x00, b"tail"),
        ),
        acceptable_states=(names(TARGET_SMALL),),
        auto_approve=True,
    ),
    ApduCase(
        case_id="load_out_of_order_two_chunk",
        note="Send an out-of-order multi-chunk LOAD sequence",
        setup_entries=SETUP_KEEP,
        steps=(
            ApduStep("chunk2-first", CLA_PASSWORDS, INS_LOAD_METADATAS, MORE_DATA, 0x00, BULK_CHUNK_2),
            ApduStep("chunk1-second", CLA_PASSWORDS, INS_LOAD_METADATAS, MORE_DATA, 0x00, BULK_CHUNK_1),
            ApduStep("chunk3-final", CLA_PASSWORDS, INS_LOAD_METADATAS, LAST_CHUNK, 0x00, BULK_CHUNK_3),
        ),
        acceptable_states=(names(SETUP_KEEP),),
        auto_approve=True,
    ),
    ApduCase(
        case_id="load_duplicate_first_chunk_then_final_remainder",
        note="Duplicate the first chunk of a multi-chunk LOAD before completion",
        setup_entries=SETUP_KEEP,
        steps=(
            ApduStep("chunk1", CLA_PASSWORDS, INS_LOAD_METADATAS, MORE_DATA, 0x00, BULK_CHUNK_1),
            ApduStep("chunk1-duplicate", CLA_PASSWORDS, INS_LOAD_METADATAS, MORE_DATA, 0x00, BULK_CHUNK_1),
            ApduStep("chunk2", CLA_PASSWORDS, INS_LOAD_METADATAS, MORE_DATA, 0x00, BULK_CHUNK_2),
            ApduStep("chunk3-final", CLA_PASSWORDS, INS_LOAD_METADATAS, LAST_CHUNK, 0x00, BULK_CHUNK_3),
        ),
        acceptable_states=(names(SETUP_KEEP),),
        auto_approve=True,
    ),
    ApduCase(
        case_id="dump_bad_p1_payload_then_pull",
        note="DUMP with invalid p1/p2 and a stray payload",
        setup_entries=TARGET_SMALL,
        steps=(ApduStep("dump-bad-p1", CLA_PASSWORDS, INS_DUMP_METADATAS, LAST_CHUNK, 0x7A, b"junk"),),
        acceptable_states=(names(TARGET_SMALL),),
        auto_approve=False,
    ),
    ApduCase(
        case_id="dump_partial_then_info_then_pull",
        note="Start a DUMP, interleave GET_APP_INFO, then pull the full state through the CLI",
        setup_entries=TARGET_SMALL,
        steps=(
            ApduStep("dump-first", CLA_PASSWORDS, INS_DUMP_METADATAS, 0x00, 0x00, b""),
            ApduStep("sdk-info", CLA_SDK, INS_GET_APP_INFO, 0x00, 0x00, b""),
        ),
        acceptable_states=(names(TARGET_SMALL),),
        auto_approve=False,
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


def write_backup(path: Path, entries: Iterable[SeedEntry]) -> None:
    path.write_text(build_backup_json(entries), encoding="utf-8")


def parse_pull_names(path: Path) -> tuple[str, ...]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return tuple(entry["nickname"] for entry in payload.get("parsed", []))


def format_step_results(steps: tuple[ApduStep, ...], responses: list[tuple[bytes, int]]) -> list[dict[str, Any]]:
    results: list[dict[str, Any]] = []
    for step, (data, status_word) in zip(steps, responses, strict=True):
        results.append(
            {
                "label": step.label,
                "cla": f"0x{step.cla:02x}",
                "ins": f"0x{step.ins:02x}",
                "p1": f"0x{step.p1:02x}",
                "p2": f"0x{step.p2:02x}",
                "data_length": len(step.data),
                "status_word": f"0x{status_word:04x}",
                "response_length": len(data),
                "response_hex_prefix": data[:48].hex(),
            },
        )
    return results


def run_case(
    root_dir: Path,
    cli_bin: Path,
    app_path: Path,
    case: ApduCase,
) -> dict[str, Any]:
    cleanup_speculos_containers()
    apdu_port = find_free_port()
    api_port = find_free_port()

    with tempfile.TemporaryDirectory(prefix=f"ledger-pw-{case.case_id}-") as tmpdir_name:
        tmpdir = Path(tmpdir_name)
        setup_backup = tmpdir / "setup.json"
        pulled_backup = tmpdir / "pulled.json"
        write_backup(setup_backup, case.setup_entries)

        harness = SpeculosHarness(
            root_dir=root_dir,
            app_path=app_path,
            cli_bin=cli_bin,
            apdu_port=apdu_port,
            api_port=api_port,
        )
        responses: list[tuple[bytes, int]] = []
        pulled_names: tuple[str, ...] | None = None
        try:
            harness.start()
            harness.initialize_first_run()
            harness.push_backup(setup_backup)

            responses = harness.exchange_apdu_sequence(
                [(step.cla, step.ins, step.p1, step.p2, step.data) for step in case.steps],
                auto_approve=case.auto_approve,
                timeout_seconds=15.0,
            )

            info_result = harness.run_cli(
                ["device", "info", "--server", harness.server, "--port", str(harness.apdu_port)],
                auto_approve=False,
                timeout_seconds=20.0,
            )
            pull_result = harness.pull_backup(pulled_backup)
            pulled_names = parse_pull_names(pulled_backup)

            if pulled_names not in case.acceptable_states:
                raise HarnessError(
                    f"Unexpected pulled state for {case.case_id}: expected one of {case.acceptable_states!r}, got {pulled_names!r}",
                )

            return {
                "case_id": case.case_id,
                "status": "passed",
                "note": case.note,
                "setup_names": list(names(case.setup_entries)),
                "acceptable_states": [list(state) for state in case.acceptable_states],
                "step_results": format_step_results(case.steps, responses),
                "info_stdout": info_result.stdout.strip(),
                "pull_stdout": pull_result.stdout.strip(),
                "pulled_names": list(pulled_names),
                "screen_text": harness.current_screen_text(),
                "speculos_crash": False,
                "oracle_evidence": classify_oracle_evidence(
                    screen_text=harness.current_screen_text(),
                    expected_names=list(case.acceptable_states[0]) if len(case.acceptable_states) == 1 else None,
                    actual_names=list(pulled_names),
                    expected_screen_substrings=("Manage passwords", "Passwords list"),
                ),
            }
        except Exception as error:
            speculos_crash = False
            log_tail = ""
            screen_text = ""
            try:
                log_tail = harness.log_tail(160)
                screen_text = harness.current_screen_text()
                speculos_crash = "signal 11" in log_tail or "app crashed" in log_tail.lower()
            except Exception:
                pass
            return {
                "case_id": case.case_id,
                "status": "failed",
                "note": case.note,
                "setup_names": list(names(case.setup_entries)),
                "acceptable_states": [list(state) for state in case.acceptable_states],
                "error": str(error),
                "step_results": format_step_results(case.steps, responses) if responses else [],
                "pulled_names": list(pulled_names) if pulled_names is not None else None,
                "screen_text": screen_text,
                "speculos_crash": speculos_crash,
                "speculos_log_tail": log_tail,
                "oracle_evidence": collect_oracle_evidence(
                    harness=harness,
                    error=error,
                    screen_text=screen_text,
                    expected_names=case.acceptable_states[0] if len(case.acceptable_states) == 1 else None,
                    actual_names=pulled_names,
                    expected_screen_substrings=("Manage passwords", "Passwords list"),
                ),
            }
        finally:
            harness.stop()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="FZ-03: low-level APDU fuzzing against Ledger Passwords in Speculos")
    parser.add_argument("--root-dir", type=Path, default=default_root_dir())
    parser.add_argument("--cli-bin", type=Path, default=None)
    parser.add_argument("--app-path", type=Path, default=None)
    parser.add_argument("--cases", type=str, default="")
    parser.add_argument("--json-out", type=Path, default=None)
    return parser.parse_args()


def selected_cases(filter_text: str) -> tuple[ApduCase, ...]:
    if not filter_text:
        return CASES
    requested = {part.strip() for part in filter_text.split(",") if part.strip()}
    return tuple(case for case in CASES if case.case_id in requested)


def main() -> int:
    args = parse_args()
    root_dir = args.root_dir.resolve()
    cli_bin = (args.cli_bin.resolve() if args.cli_bin else default_cli_bin(root_dir))
    app_path = (args.app_path.resolve() if args.app_path else default_app_path(root_dir))

    ensure_cli_exists(cli_bin)
    ensure_app_exists(app_path)

    cases = selected_cases(args.cases)
    if not cases:
        raise SystemExit("No cases selected")

    results = [run_case(root_dir, cli_bin, app_path, case) for case in cases]
    summary = {
        "fuzzer": "FZ-03 low-level APDU",
        "total_cases": len(results),
        "failed_cases": [result["case_id"] for result in results if result["status"] != "passed"],
        "results": results,
    }

    if args.json_out is not None:
        args.json_out.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")

    print_json(summary)
    return 0 if not summary["failed_cases"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
