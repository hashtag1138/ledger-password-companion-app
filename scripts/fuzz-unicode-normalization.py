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


def escaped(value: str) -> str:
    return value.encode("unicode_escape").decode("ascii")


@dataclass(frozen=True)
class SeedEntry:
    nickname: str
    charsets: tuple[str, ...] = ("ALL_SETS",)


@dataclass(frozen=True)
class UnicodeStep:
    action: str
    position: int | None = None


@dataclass(frozen=True)
class UnicodeCase:
    case_id: str
    note: str
    entries: tuple[SeedEntry, ...]
    steps: tuple[UnicodeStep, ...]
    expected_final_nicknames: tuple[str, ...]


CASES: tuple[UnicodeCase, ...] = (
    UnicodeCase(
        case_id="nfc_nfd_delete_second",
        note="NFC/NFD visuellement proches, suppression ciblée du second item",
        entries=(
            SeedEntry("é"),
            SeedEntry("e\u0301"),
            SeedEntry("plain"),
        ),
        steps=(
            UnicodeStep("push"),
            UnicodeStep("show", position=1),
            UnicodeStep("show", position=2),
            UnicodeStep("delete", position=2),
        ),
        expected_final_nicknames=("é", "plain"),
    ),
    UnicodeCase(
        case_id="nbsp_delete_second",
        note="Espace ASCII vs NBSP, suppression ciblée du second item",
        entries=(
            SeedEntry("foo bar"),
            SeedEntry("foo\u00a0bar"),
            SeedEntry("plain"),
        ),
        steps=(
            UnicodeStep("push"),
            UnicodeStep("show", position=1),
            UnicodeStep("show", position=2),
            UnicodeStep("delete", position=2),
        ),
        expected_final_nicknames=("foo bar", "plain"),
    ),
    UnicodeCase(
        case_id="zero_width_delete_second",
        note="Nom visible vs nom avec zero-width space, suppression du second item",
        entries=(
            SeedEntry("zerowidth"),
            SeedEntry("zero\u200bwidth"),
            SeedEntry("plain"),
        ),
        steps=(
            UnicodeStep("push"),
            UnicodeStep("show", position=1),
            UnicodeStep("show", position=2),
            UnicodeStep("delete", position=2),
        ),
        expected_final_nicknames=("zerowidth", "plain"),
    ),
    UnicodeCase(
        case_id="bidi_delete_second",
        note="Nom ASCII vs nom avec bidi override, suppression du second item",
        entries=(
            SeedEntry("abc123"),
            SeedEntry("abc\u202e123"),
            SeedEntry("plain"),
        ),
        steps=(
            UnicodeStep("push"),
            UnicodeStep("show", position=1),
            UnicodeStep("show", position=2),
            UnicodeStep("delete", position=2),
        ),
        expected_final_nicknames=("abc123", "plain"),
    ),
    UnicodeCase(
        case_id="mixed_unicode_show_all",
        note="Liste mixte Unicode: show first/middle/last puis type sur un item avec zero-width joiner",
        entries=(
            SeedEntry("é"),
            SeedEntry("e\u0301"),
            SeedEntry("foo\u202fbar"),
            SeedEntry("zero\u200djoin"),
            SeedEntry("abc\u202e123"),
        ),
        steps=(
            UnicodeStep("push"),
            UnicodeStep("show", position=1),
            UnicodeStep("show", position=3),
            UnicodeStep("show", position=5),
            UnicodeStep("type", position=4),
        ),
        expected_final_nicknames=(
            "é",
            "e\u0301",
            "foo\u202fbar",
            "zero\u200djoin",
            "abc\u202e123",
        ),
    ),
)


def build_backup_json(entries: tuple[SeedEntry, ...]) -> str:
    payload = {
        "format": "ledger-passwords-companion.v1",
        "storage_size": 4096,
        "app": {"name": "Passwords", "version": "fuzz-unicode-normalization"},
        "parsed": [{"nickname": entry.nickname, "charsets": list(entry.charsets)} for entry in entries],
        "nicknames_erased_but_still_stored": [],
        "corruptions_encountered": [],
        "raw_metadatas": None,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


def load_backup_nicknames(path: Path) -> list[str]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return sorted(entry["nickname"] for entry in payload.get("parsed", []))


def describe_nicknames(values: list[str] | tuple[str, ...]) -> list[dict[str, str]]:
    return [{"value": value, "escaped": escaped(value)} for value in values]


def assert_case_limits(case: UnicodeCase) -> None:
    for entry in case.entries:
        utf8_length = len(entry.nickname.encode("utf-8"))
        if utf8_length > 19:
            raise HarnessError(
                f"{case.case_id}: nickname {entry.nickname!r} exceeds Ledger limit with {utf8_length} UTF-8 bytes",
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
        subprocess.run([docker, "kill", *container_ids], capture_output=True, text=True, check=False)


def safe_screen_text(harness: SpeculosHarness) -> str:
    try:
        return harness.current_screen_text()
    except Exception:
        return ""


def execute_step(harness: SpeculosHarness, step: UnicodeStep, seed_backup: Path) -> dict[str, Any]:
    if step.action == "push":
        result = harness.push_backup(seed_backup)
        return {"action": "push", "stdout": result.stdout.strip()}
    if step.action == "show":
        screen_text = harness.show_password(position=step.position or 1)
        return {"action": "show", "position": step.position, "screen_text": screen_text}
    if step.action == "type":
        screen_text = harness.type_password(position=step.position or 1)
        return {"action": "type", "position": step.position, "screen_text": screen_text}
    if step.action == "delete":
        screen_text = harness.delete_password(position=step.position or 1)
        return {"action": "delete", "position": step.position, "screen_text": screen_text}
    raise HarnessError(f"Unknown unicode step action: {step.action}")


def run_case(
    *,
    case: UnicodeCase,
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
    pulled_backup = artifacts_dir / f"{case.case_id}-pulled.json"
    seed_backup.write_text(build_backup_json(case.entries), encoding="utf-8")

    harness.start()
    try:
        harness.initialize_first_run()
        executed_steps: list[dict[str, Any]] = []
        for step in case.steps:
            executed_steps.append(execute_step(harness, step, seed_backup))

        pull_result = harness.pull_backup(pulled_backup)
        final_nicknames = load_backup_nicknames(pulled_backup)
        expected = sorted(case.expected_final_nicknames)
        if final_nicknames != expected:
            raise HarnessError(
                f"Final pulled state mismatch for {case.case_id}: expected {expected!r}, got {final_nicknames!r}",
            )
        return {
            "case_id": case.case_id,
            "status": "ok",
            "note": case.note,
            "seed_entries": describe_nicknames([entry.nickname for entry in case.entries]),
            "steps": executed_steps,
            "final_pull_stdout": pull_result.stdout.strip(),
            "final_nicknames": describe_nicknames(final_nicknames),
        }
    except Exception as error:
        return {
            "case_id": case.case_id,
            "status": "failed",
            "note": case.note,
            "error": str(error),
            "seed_entries": describe_nicknames([entry.nickname for entry in case.entries]),
            "screen_text": safe_screen_text(harness),
            "speculos_log_tail": harness.log_tail(),
            "seed_backup": seed_backup.read_text(encoding="utf-8"),
        }
    finally:
        harness.stop()


def parse_args() -> argparse.Namespace:
    root_dir = default_root_dir()
    parser = argparse.ArgumentParser(
        description="FZ-11 Unicode and normalization fuzzing against app-passwords via CLI + Speculos",
    )
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
    parser.add_argument(
        "--cases",
        default="",
        help="Comma-separated case ids. Empty means the full curated set.",
    )
    parser.add_argument("--json-out", default="", help="Optional path for JSON report.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root_dir = default_root_dir()
    app_path = Path(args.app).resolve()
    cli_bin = Path(args.cli).resolve()
    ensure_app_exists(app_path)
    ensure_cli_exists(cli_bin)

    selected_cases = CASES
    if args.cases.strip():
        requested = {case_id.strip() for case_id in args.cases.split(",") if case_id.strip()}
        selected_cases = tuple(case for case in CASES if case.case_id in requested)
        missing = sorted(requested - {case.case_id for case in selected_cases})
        if missing:
            raise HarnessError(f"Unknown FZ-11 case ids: {', '.join(missing)}")

    with tempfile.TemporaryDirectory(prefix="ledger-pw-fz11-") as tmp_dir:
        artifacts_dir = Path(tmp_dir)
        results = []
        failures = []
        for case in selected_cases:
            apdu_port = args.apdu_port or find_free_port(args.server)
            api_port = args.api_port or find_free_port(args.server)
            result = run_case(
                case=case,
                artifacts_dir=artifacts_dir,
                root_dir=root_dir,
                app_path=app_path,
                cli_bin=cli_bin,
                server=args.server,
                apdu_port=apdu_port,
                api_port=api_port,
                display=args.display,
            )
            results.append(result)
            if result["status"] != "ok":
                failures.append(case.case_id)

        report = {
            "fuzzer": "FZ-11",
            "cases": [case.case_id for case in selected_cases],
            "results": results,
            "failures": failures,
        }
        if args.json_out:
            Path(args.json_out).write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print_json(report)
        return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
