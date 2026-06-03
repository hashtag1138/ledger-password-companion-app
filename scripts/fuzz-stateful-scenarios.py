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
class ScenarioEntry:
    nickname: str
    charsets: tuple[str, ...] = ("ALL_SETS",)


@dataclass(frozen=True)
class ScenarioStep:
    action: str
    position: int | None = None


@dataclass(frozen=True)
class StatefulScenario:
    scenario_id: str
    note: str
    entries: tuple[ScenarioEntry, ...]
    steps: tuple[ScenarioStep, ...]
    expected_final_nicknames: tuple[str, ...]


SCENARIOS: tuple[StatefulScenario, ...] = (
    StatefulScenario(
        scenario_id="sofian_push_show_pull",
        note="Direct regression around the incident nickname then show read",
        entries=(ScenarioEntry("sofian terki"),),
        steps=(ScenarioStep("push"), ScenarioStep("show", position=1)),
        expected_final_nicknames=("sofian terki",),
    ),
    StatefulScenario(
        scenario_id="sofian_push_type_pull",
        note="Type password after pushing a nickname with a space",
        entries=(ScenarioEntry("sofian terki"),),
        steps=(ScenarioStep("push"), ScenarioStep("type", position=1)),
        expected_final_nicknames=("sofian terki",),
    ),
    StatefulScenario(
        scenario_id="sofian_push_delete_pull",
        note="Delete immediately after pushing a nickname with a space",
        entries=(ScenarioEntry("sofian terki"),),
        steps=(ScenarioStep("push"), ScenarioStep("delete", position=1)),
        expected_final_nicknames=(),
    ),
    StatefulScenario(
        scenario_id="leading_space_push_show_pull",
        note="Show read with leading space",
        entries=(ScenarioEntry(" leading"),),
        steps=(ScenarioStep("push"), ScenarioStep("show", position=1)),
        expected_final_nicknames=(" leading",),
    ),
    StatefulScenario(
        scenario_id="leading_space_push_type_pull",
        note="Typing with leading space",
        entries=(ScenarioEntry(" leading"),),
        steps=(ScenarioStep("push"), ScenarioStep("type", position=1)),
        expected_final_nicknames=(" leading",),
    ),
    StatefulScenario(
        scenario_id="multi_show_second_pull",
        note="Select the second item in the list",
        entries=(ScenarioEntry("alpha"), ScenarioEntry("beta")),
        steps=(ScenarioStep("push"), ScenarioStep("show", position=2)),
        expected_final_nicknames=("alpha", "beta"),
    ),
    StatefulScenario(
        scenario_id="multi_type_second_delete_first_pull",
        note="Mix typing on one item, then delete another",
        entries=(ScenarioEntry("sofian terki"), ScenarioEntry("gmail")),
        steps=(ScenarioStep("push"), ScenarioStep("type", position=2), ScenarioStep("delete", position=1)),
        expected_final_nicknames=("gmail",),
    ),
    StatefulScenario(
        scenario_id="multi_push_verify_pull",
        note="Push then verify then pull on a multi-entry corpus",
        entries=(ScenarioEntry("github"), ScenarioEntry("gmail"), ScenarioEntry("sofian terki")),
        steps=(ScenarioStep("push"), ScenarioStep("verify_seed")),
        expected_final_nicknames=("github", "gmail", "sofian terki"),
    ),
)


def build_backup_json(entries: tuple[ScenarioEntry, ...]) -> str:
    payload = {
        "format": "ledger-passwords-companion.v1",
        "storage_size": 4096,
        "app": {"name": "Passwords", "version": "fuzz-stateful-scenarios"},
        "parsed": [
            {"nickname": entry.nickname, "charsets": list(entry.charsets)}
            for entry in entries
        ],
        "nicknames_erased_but_still_stored": [],
        "corruptions_encountered": [],
        "raw_metadatas": None,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


def load_backup_nicknames(path: Path) -> list[str]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return sorted(entry["nickname"] for entry in payload.get("parsed", []))


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


def parse_args() -> argparse.Namespace:
    root_dir = default_root_dir()
    parser = argparse.ArgumentParser(
        description="FZ-04 stateful business scenarios against app-passwords via CLI + Speculos",
    )
    parser.add_argument("--app", default=str(default_app_path(root_dir)), help="Path to app.elf")
    parser.add_argument("--cli", default=str(default_cli_bin(root_dir)), help="Path to ledger-pw CLI")
    parser.add_argument("--server", default="127.0.0.1", help="Speculos API/APDU host")
    parser.add_argument(
        "--apdu-port",
        type=int,
        default=0,
        help="Speculos APDU TCP port. Default: choose a free port per scenario.",
    )
    parser.add_argument(
        "--api-port",
        type=int,
        default=0,
        help="Speculos REST API port. Default: choose a free port per scenario.",
    )
    parser.add_argument("--display", default="headless", help="Speculos display mode")
    parser.add_argument(
        "--scenarios",
        default="",
        help="Comma-separated scenario ids. Empty means the full curated set.",
    )
    parser.add_argument(
        "--artifacts-dir",
        default="",
        help="Directory to keep generated backup files and pulled states. Default: temp dir.",
    )
    parser.add_argument("--json-out", default="", help="Optional path for the JSON report.")
    return parser.parse_args()


def execute_step(
    harness: SpeculosHarness,
    scenario: StatefulScenario,
    step: ScenarioStep,
    seed_backup: Path,
    pulled_backup: Path,
) -> dict[str, Any]:
    if step.action == "push":
        result = harness.push_backup(seed_backup)
        return {"action": "push", "stdout": result.stdout.strip()}
    if step.action == "verify_seed":
        result = harness.verify_backup(seed_backup)
        return {"action": "verify_seed", "stdout": result.stdout.strip()}
    if step.action == "show":
        screen_text = harness.show_password(position=step.position or 1)
        return {"action": "show", "position": step.position, "screen_text": screen_text}
    if step.action == "type":
        screen_text = harness.type_password(position=step.position or 1)
        return {"action": "type", "position": step.position, "screen_text": screen_text}
    if step.action == "delete":
        screen_text = harness.delete_password(position=step.position or 1)
        return {"action": "delete", "position": step.position, "screen_text": screen_text}
    if step.action == "pull":
        result = harness.pull_backup(pulled_backup)
        return {"action": "pull", "stdout": result.stdout.strip()}
    raise HarnessError(f"Unknown scenario step: {step.action} in {scenario.scenario_id}")


def run_scenario(
    *,
    scenario: StatefulScenario,
    artifacts_dir: Path,
    root_dir: Path,
    app_path: Path,
    cli_bin: Path,
    server: str,
    apdu_port: int,
    api_port: int,
    display: str,
) -> dict[str, Any]:
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
    seed_backup = artifacts_dir / f"{scenario.scenario_id}-seed.json"
    pulled_backup = artifacts_dir / f"{scenario.scenario_id}-pulled.json"
    seed_backup.write_text(build_backup_json(scenario.entries), encoding="utf-8")
    harness.start()
    try:
        harness.initialize_first_run()
        executed_steps: list[dict[str, Any]] = []
        for step in scenario.steps:
            executed_steps.append(execute_step(harness, scenario, step, seed_backup, pulled_backup))
        pull_result = harness.pull_backup(pulled_backup)
        final_nicknames = load_backup_nicknames(pulled_backup)
        expected = sorted(scenario.expected_final_nicknames)
        if final_nicknames != expected:
            raise HarnessError(
                f"Final pulled state mismatch for {scenario.scenario_id}: expected {expected!r}, got {final_nicknames!r}",
            )
        return {
            "scenario_id": scenario.scenario_id,
            "status": "ok",
            "note": scenario.note,
            "steps": executed_steps,
            "final_pull_stdout": pull_result.stdout.strip(),
            "final_nicknames": final_nicknames,
        }
    except Exception as error:
        return {
            "scenario_id": scenario.scenario_id,
            "status": "failed",
            "note": scenario.note,
            "error": str(error),
            "screen_text": safe_screen_text(harness),
            "speculos_log_tail": harness.log_tail(),
            "seed_backup": seed_backup.read_text(encoding="utf-8"),
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


def main() -> int:
    args = parse_args()
    root_dir = default_root_dir()
    app_path = Path(args.app).resolve()
    cli_bin = Path(args.cli).resolve()
    ensure_app_exists(app_path)
    ensure_cli_exists(cli_bin)

    selected_ids = {part.strip() for part in args.scenarios.split(",") if part.strip()}
    scenarios = tuple(s for s in SCENARIOS if not selected_ids or s.scenario_id in selected_ids)
    if not scenarios:
        raise SystemExit("No scenarios selected")

    artifacts_dir = Path(args.artifacts_dir).resolve() if args.artifacts_dir else Path(
        tempfile.mkdtemp(prefix="ledger-pw-fz04-"),
    )
    artifacts_dir.mkdir(parents=True, exist_ok=True)

    results = []
    for scenario in scenarios:
        print(f"[FZ-04] {scenario.scenario_id}", flush=True)
        apdu_port = args.apdu_port if args.apdu_port > 0 else find_free_port(args.server)
        api_port = args.api_port if args.api_port > 0 else find_free_port(args.server)
        results.append(
            run_scenario(
                scenario=scenario,
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

    report = {
        "fuzzer": "FZ-04 stateful business scenarios",
        "scenarios": [scenario.scenario_id for scenario in scenarios],
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
