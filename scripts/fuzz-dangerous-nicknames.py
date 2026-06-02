#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

from speculos_fuzz_lib import (
    HarnessError,
    SpeculosHarness,
    default_app_path,
    default_cli_bin,
    default_root_dir,
    ensure_app_exists,
    ensure_cli_exists,
    print_json,
)


@dataclass(frozen=True)
class NicknameCase:
    case_id: str
    nickname: str
    note: str


CORPUS: tuple[NicknameCase, ...] = (
    NicknameCase("sofian_space", "sofian terki", "Incident réel observé sur vrai Ledger"),
    NicknameCase("leading_space", " leading", "Espace en tête"),
    NicknameCase("trailing_space", "trailing ", "Espace final"),
    NicknameCase("double_space", "double  gap", "Double espace interne"),
    NicknameCase("tab_inside", "tab\tinside", "Tabulation interne"),
    NicknameCase("newline_inside", "line\nbreak", "Retour ligne interne"),
    NicknameCase("slash_inside", "slash/name", "Slash"),
    NicknameCase("backslash_inside", r"back\slash", "Backslash"),
    NicknameCase("apostrophe", "apo'strophe", "Apostrophe simple"),
    NicknameCase("quote_backtick", "quo\"te`", "Guillemet et backtick"),
    NicknameCase("accented", "accentué", "Accent UTF-8 simple"),
    NicknameCase("combining", "e\u0301cole", "Combining mark"),
    NicknameCase("zero_width", "zero\u200bwidth", "Zero-width space"),
    NicknameCase("bidi_mark", "abc\u202ertl", "Bidi override"),
    NicknameCase("max_19_ascii", "abcdefghijklmnopqrs", "19 octets ASCII exacts"),
)

SCENARIOS = ("show", "type", "delete")


def build_backup_json(nickname: str) -> str:
    payload = {
        "format": "ledger-passwords-companion.v1",
        "storage_size": 4096,
        "app": {"name": "Passwords", "version": "fuzz-dangerous-nicknames"},
        "parsed": [{"nickname": nickname, "charsets": ["ALL_SETS"]}],
        "nicknames_erased_but_still_stored": [],
        "corruptions_encountered": [],
        "raw_metadatas": None,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


def run_case(
    *,
    harness: SpeculosHarness,
    case: NicknameCase,
    scenario: str,
    artifacts_dir: Path,
) -> dict:
    cleanup_speculos_containers()
    backup_path = artifacts_dir / f"{case.case_id}.json"
    backup_path.write_text(build_backup_json(case.nickname), encoding="utf-8")
    harness.start()
    try:
        harness.initialize_first_run()
        push_result = harness.push_backup(backup_path)
        if scenario == "show":
            screen_text = harness.show_first_password()
        elif scenario == "type":
            screen_text = harness.type_first_password()
        elif scenario == "delete":
            screen_text = harness.delete_first_password()
        else:
            raise HarnessError(f"Unknown scenario: {scenario}")
        return {
            "case_id": case.case_id,
            "nickname": case.nickname,
            "scenario": scenario,
            "status": "ok",
            "screen_text": screen_text,
            "push_stdout": push_result.stdout.strip(),
        }
    except Exception as error:
        return {
            "case_id": case.case_id,
            "nickname": case.nickname,
            "scenario": scenario,
            "status": "failed",
            "error": str(error),
            "screen_text": safe_screen_text(harness),
            "speculos_log_tail": harness.log_tail(),
            "backup_json": backup_path.read_text(encoding="utf-8"),
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
        description="FZ-14 dangerous nickname corpus against app-passwords via CLI + Speculos",
    )
    parser.add_argument("--app", default=str(default_app_path(root_dir)), help="Path to app.elf")
    parser.add_argument("--cli", default=str(default_cli_bin(root_dir)), help="Path to ledger-pw CLI")
    parser.add_argument("--server", default="127.0.0.1", help="Speculos API/APDU host")
    parser.add_argument("--apdu-port", type=int, default=10100, help="Speculos APDU TCP port")
    parser.add_argument("--api-port", type=int, default=5100, help="Speculos REST API port")
    parser.add_argument("--display", default="headless", help="Speculos display mode")
    parser.add_argument(
        "--scenarios",
        default="show,type,delete",
        help="Comma-separated scenarios from: show,type,delete",
    )
    parser.add_argument(
        "--cases",
        default="",
        help="Comma-separated case ids to run. Empty means the full corpus.",
    )
    parser.add_argument(
        "--artifacts-dir",
        default="",
        help="Directory to keep generated backup files and failing artifacts. Default: temp dir.",
    )
    parser.add_argument(
        "--json-out",
        default="",
        help="Optional path for the JSON report.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root_dir = default_root_dir()
    app_path = Path(args.app).resolve()
    cli_bin = Path(args.cli).resolve()
    ensure_app_exists(app_path)
    ensure_cli_exists(cli_bin)

    selected_scenarios = tuple(part.strip() for part in args.scenarios.split(",") if part.strip())
    unknown_scenarios = [scenario for scenario in selected_scenarios if scenario not in SCENARIOS]
    if unknown_scenarios:
        raise SystemExit(f"Unknown scenarios: {', '.join(unknown_scenarios)}")

    selected_case_ids = {part.strip() for part in args.cases.split(",") if part.strip()}
    corpus = tuple(case for case in CORPUS if not selected_case_ids or case.case_id in selected_case_ids)
    if not corpus:
        raise SystemExit("No cases selected")

    artifacts_dir = Path(args.artifacts_dir).resolve() if args.artifacts_dir else Path(
        tempfile.mkdtemp(prefix="ledger-pw-fz14-"),
    )
    artifacts_dir.mkdir(parents=True, exist_ok=True)

    cleanup_speculos_containers()

    harness = SpeculosHarness(
        root_dir=root_dir,
        app_path=app_path,
        cli_bin=cli_bin,
        server=args.server,
        apdu_port=args.apdu_port,
        api_port=args.api_port,
        display=args.display,
    )

    results = []
    for case in corpus:
        for scenario in selected_scenarios:
            print(f"[FZ-14] {case.case_id} / {scenario}", flush=True)
            result = run_case(harness=harness, case=case, scenario=scenario, artifacts_dir=artifacts_dir)
            results.append(result)

    report = {
        "fuzzer": "FZ-14 dangerous nicknames",
        "cases": len(corpus),
        "scenarios": list(selected_scenarios),
        "artifacts_dir": str(artifacts_dir),
        "results": results,
        "failures": [result for result in results if result["status"] != "ok"],
    }

    if args.json_out:
        Path(args.json_out).write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print_json(report)
    return 1 if report["failures"] else 0


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
    if not container_ids:
        return
    subprocess.run([docker, "stop", *container_ids], capture_output=True, text=True, check=False)


if __name__ == "__main__":
    raise SystemExit(main())
