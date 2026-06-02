#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from speculos_fuzz_lib import default_root_dir, print_json


@dataclass(frozen=True)
class OracleValidationCase:
    case_id: str
    command: list[str]
    result_case_id: str
    expect_triggered: tuple[str, ...]
    expect_not_triggered: tuple[str, ...] = ()


VALIDATIONS: tuple[OracleValidationCase, ...] = (
    OracleValidationCase(
        case_id="control_sofian_verify_show",
        command=["./scripts/fuzz-incident-regression.py", "--cases", "sofian_push_verify_show_control"],
        result_case_id="sofian_push_verify_show_control",
        expect_triggered=(),
        expect_not_triggered=(
            "speculos_crash",
            "timeout",
            "transport_closed",
            "pulled_state_mismatch",
            "password_pollution",
            "unexpected_selected_nickname",
        ),
    ),
    OracleValidationCase(
        case_id="crash_alpha_beta_show_second",
        command=["./scripts/fuzz-incident-regression.py", "--cases", "alpha_beta_push_show_second"],
        result_case_id="alpha_beta_push_show_second",
        expect_triggered=("speculos_crash", "transport_closed", "empty_screen"),
    ),
    OracleValidationCase(
        case_id="restart_pollution_sofian",
        command=["./scripts/fuzz-incident-regression.py", "--cases", "sofian_push_restart_show_first"],
        result_case_id="sofian_push_restart_show_first",
        expect_triggered=("pulled_state_mismatch", "password_pollution"),
    ),
    OracleValidationCase(
        case_id="timeout_dump_transfer_prompt",
        command=["./scripts/fuzz-low-level-apdu.py", "--cases", "dump_partial_then_info_then_pull"],
        result_case_id="dump_partial_then_info_then_pull",
        expect_triggered=("timeout", "stuck_transfer_prompt"),
    ),
)


def run_validation(root_dir: Path, case: OracleValidationCase, artifacts_dir: Path) -> dict[str, Any]:
    report_path = artifacts_dir / f"{case.case_id}.json"
    command = [*case.command, "--json-out", str(report_path)]
    result = subprocess.run(
        command,
        cwd=root_dir,
        capture_output=True,
        text=True,
        check=False,
    )
    if not report_path.exists():
        raise RuntimeError(
            f"{case.case_id}: runner did not produce {report_path}; stdout={result.stdout!r}; stderr={result.stderr!r}",
        )

    payload = json.loads(report_path.read_text(encoding="utf-8"))
    result_entry = next((entry for entry in payload.get("results", []) if entry.get("case_id") == case.result_case_id), None)
    if result_entry is None:
        raise RuntimeError(f"{case.case_id}: result {case.result_case_id!r} not found in {report_path}")

    oracle = result_entry.get("oracle_evidence")
    if oracle is None:
        raise RuntimeError(f"{case.case_id}: missing oracle_evidence in result {case.result_case_id!r}")

    triggered = set(oracle.get("triggered", []))
    missing = [name for name in case.expect_triggered if name not in triggered]
    unexpected = [name for name in case.expect_not_triggered if name in triggered]
    status = "ok" if not missing and not unexpected else "failed"
    return {
        "case_id": case.case_id,
        "status": status,
        "command": command,
        "runner_exit_code": result.returncode,
        "result_case_id": case.result_case_id,
        "expected_triggered": list(case.expect_triggered),
        "expected_not_triggered": list(case.expect_not_triggered),
        "actual_triggered": oracle.get("triggered", []),
        "missing": missing,
        "unexpected": unexpected,
        "oracle_flags": oracle.get("flags", {}),
        "runner_stdout_tail": result.stdout.splitlines()[-20:],
        "runner_stderr_tail": result.stderr.splitlines()[-20:],
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="FZ-16 oracle consolidation checks")
    parser.add_argument("--root-dir", type=Path, default=default_root_dir())
    parser.add_argument("--json-out", type=Path, default=None)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root_dir = args.root_dir.resolve()
    artifacts_dir = Path(tempfile.mkdtemp(prefix="ledger-pw-fz16-"))

    results = [run_validation(root_dir, case, artifacts_dir) for case in VALIDATIONS]
    report = {
        "fuzzer": "FZ-16 oracle consolidation",
        "artifacts_dir": str(artifacts_dir),
        "results": results,
        "failures": [entry for entry in results if entry["status"] != "ok"],
    }
    if args.json_out is not None:
        args.json_out.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print_json(report)
    return 1 if report["failures"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
