#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import random
import shutil
import signal
import subprocess
import tempfile
import time
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
class TimingCase:
    case_id: str
    note: str
    random_seed: int
    entries: tuple[SeedEntry, ...]
    setup_kind: str
    plan: str
    expected_final_nicknames: tuple[str, ...]


CASES: tuple[TimingCase, ...] = (
    TimingCase(
        case_id="sofian_manual_push_show_seed61",
        note="Push avec approbation jitterée, puis show immédiat",
        random_seed=61,
        entries=(SeedEntry("sofian terki"),),
        setup_kind="empty",
        plan="manual_push_then_show",
        expected_final_nicknames=("sofian terki",),
    ),
    TimingCase(
        case_id="sofian_push_verify_pull_burst_seed62",
        note="Push puis rafale verify/pull/diff avec micro-délais",
        random_seed=62,
        entries=(SeedEntry("sofian terki"),),
        setup_kind="empty",
        plan="push_then_read_burst",
        expected_final_nicknames=("sofian terki",),
    ),
    TimingCase(
        case_id="alpha_beta_manual_push_show_second_seed63",
        note="Push jitteré puis show du second item sur alpha/beta",
        random_seed=63,
        entries=(SeedEntry("alpha"), SeedEntry("beta")),
        setup_kind="empty",
        plan="manual_push_then_show_second",
        expected_final_nicknames=("alpha", "beta"),
    ),
    TimingCase(
        case_id="leading_space_manual_push_delete_seed64",
        note="Push jitteré avec espace initial puis delete immédiat",
        random_seed=64,
        entries=(SeedEntry(" leading"),),
        setup_kind="empty",
        plan="manual_push_then_delete",
        expected_final_nicknames=(),
    ),
    TimingCase(
        case_id="multi_concurrent_reads_seed65",
        note="Push puis concurrence de lectures CLI avec faibles décalages",
        random_seed=65,
        entries=(SeedEntry("github"), SeedEntry("gmail"), SeedEntry("proton")),
        setup_kind="empty",
        plan="concurrent_reads",
        expected_final_nicknames=("github", "gmail", "proton"),
    ),
)


def build_backup_json(entries: tuple[SeedEntry, ...]) -> str:
    payload = {
        "format": "ledger-passwords-companion.v1",
        "storage_size": 4096,
        "app": {"name": "Passwords", "version": "fuzz-chaos-timing"},
        "parsed": [{"nickname": entry.nickname, "charsets": list(entry.charsets)} for entry in entries],
        "nicknames_erased_but_still_stored": [],
        "corruptions_encountered": [],
        "raw_metadatas": None,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


def load_backup_nicknames(path: Path) -> list[str]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return sorted(entry["nickname"] for entry in payload.get("parsed", []))


def pull_summary(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return {
        "parsed_nicknames": sorted(entry["nickname"] for entry in payload.get("parsed", [])),
        "erased_count": len(payload.get("nicknames_erased_but_still_stored", [])),
        "corruption_count": len(payload.get("corruptions_encountered", [])),
        "raw_present": payload.get("raw_metadatas") is not None,
    }


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


def stop_process_tree(process: subprocess.Popen[str] | None) -> None:
    if process is None or process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=3)


def safe_screen_text(harness: SpeculosHarness) -> str:
    try:
        return harness.current_screen_text()
    except Exception:
        return ""


def has_crash_signature(harness: SpeculosHarness) -> bool:
    return "crashed with signal" in harness.log_tail(lines=80).lower()


def sleep_with_jitter(rng: random.Random, minimum: float, maximum: float) -> float:
    duration = rng.uniform(minimum, maximum)
    time.sleep(duration)
    return duration


def start_auto_approver(root_dir: Path, server: str, api_port: int) -> subprocess.Popen[str]:
    return subprocess.Popen(
        [
            str(root_dir / "scripts" / "speculos-auto-approve.sh"),
            "--server",
            server,
            "--api-port",
            str(api_port),
        ],
        cwd=root_dir,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        text=True,
        start_new_session=True,
    )


def run_cli_manual_approval(
    harness: SpeculosHarness,
    args: list[str],
    rng: random.Random,
    *,
    timeout_seconds: float = 35.0,
) -> dict[str, Any]:
    process = subprocess.Popen(
        [str(harness.cli_bin), *args],
        cwd=harness.root_dir,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        start_new_session=True,
    )
    prompt_events: list[dict[str, Any]] = []
    last_screen = ""
    try:
        deadline = time.time() + timeout_seconds
        while process.poll() is None:
            harness.ensure_running()
            if time.time() >= deadline:
                process.kill()
                stdout, stderr = process.communicate(timeout=2)
                raise HarnessError(
                    f"Manual approval CLI timed out for args={args!r}; stdout={stdout!r}; stderr={stderr!r}; screen={safe_screen_text(harness)!r}",
                )
            screen = safe_screen_text(harness)
            if screen and screen != last_screen:
                if "Transfer metadatas" in screen or "Overwrite metadatas" in screen:
                    delay = sleep_with_jitter(rng, 0.01, 0.16)
                    harness.press_button("right")
                    prompt_events.append({"screen": screen, "button": "right", "delay_seconds": round(delay, 4)})
                elif "Approve" in screen:
                    delay = sleep_with_jitter(rng, 0.005, 0.12)
                    harness.press_button("both")
                    prompt_events.append({"screen": screen, "button": "both", "delay_seconds": round(delay, 4)})
                last_screen = screen
            time.sleep(rng.uniform(0.01, 0.06))

        stdout, stderr = process.communicate(timeout=2)
        if process.returncode != 0:
            raise HarnessError(
                f"Manual approval CLI failed for args={args!r}; stdout={stdout!r}; stderr={stderr!r}; screen={safe_screen_text(harness)!r}",
            )
        return {
            "args": args,
            "stdout": stdout.strip(),
            "stderr": stderr.strip(),
            "prompt_events": prompt_events,
        }
    finally:
        stop_process_tree(process)


def run_concurrent_cli_burst(
    harness: SpeculosHarness,
    rng: random.Random,
    command_specs: list[tuple[str, list[str]]],
    *,
    timeout_seconds: float = 40.0,
) -> list[dict[str, Any]]:
    approver = start_auto_approver(harness.root_dir, harness.server, harness.api_port)
    processes: list[tuple[str, subprocess.Popen[str]]] = []
    launch_events: list[dict[str, Any]] = []
    try:
        for index, (name, args) in enumerate(command_specs):
            if index > 0:
                delay = sleep_with_jitter(rng, 0.005, 0.09)
                launch_events.append({"event": "stagger", "delay_seconds": round(delay, 4), "before": name})
            process = subprocess.Popen(
                [str(harness.cli_bin), *args],
                cwd=harness.root_dir,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                start_new_session=True,
            )
            processes.append((name, process))
            launch_events.append({"event": "launch", "name": name, "args": args})

        deadline = time.time() + timeout_seconds
        while any(process.poll() is None for _, process in processes):
            harness.ensure_running()
            if time.time() >= deadline:
                for _, process in processes:
                    if process.poll() is None:
                        process.kill()
                outputs = []
                for name, process in processes:
                    stdout, stderr = process.communicate(timeout=2)
                    outputs.append({"name": name, "stdout": stdout, "stderr": stderr, "returncode": process.returncode})
                raise HarnessError(
                    f"Concurrent CLI burst timed out; outputs={outputs!r}; screen={safe_screen_text(harness)!r}",
                )
            time.sleep(0.03)

        results: list[dict[str, Any]] = []
        for name, process in processes:
            stdout, stderr = process.communicate(timeout=2)
            if process.returncode != 0:
                raise HarnessError(
                    f"Concurrent CLI command failed for {name}: stdout={stdout!r}; stderr={stderr!r}; screen={safe_screen_text(harness)!r}",
                )
            results.append(
                {
                    "name": name,
                    "stdout": stdout.strip(),
                    "stderr": stderr.strip(),
                    "returncode": process.returncode,
                },
            )
        results.extend(launch_events)
        return results
    finally:
        for _, process in processes:
            stop_process_tree(process)
        stop_process_tree(approver)


def run_case(
    *,
    case: TimingCase,
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
    rng = random.Random(case.random_seed)
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
    pulled_backup = artifacts_dir / f"{case.case_id}-pull.json"
    seed_backup.write_text(build_backup_json(case.entries), encoding="utf-8")

    harness.start()
    try:
        harness.initialize_first_run()
        events: list[dict[str, Any]] = []

        push_args = ["device", "push", str(seed_backup), "--server", harness.server, "--port", str(harness.apdu_port)]
        verify_args = ["device", "verify", str(seed_backup), "--server", harness.server, "--port", str(harness.apdu_port)]
        diff_args = ["device", "diff", str(seed_backup), "--server", harness.server, "--port", str(harness.apdu_port)]
        info_args = ["device", "info", "--server", harness.server, "--port", str(harness.apdu_port)]
        pull_args = ["device", "pull", "--out", str(pulled_backup), "--server", harness.server, "--port", str(harness.apdu_port)]

        if case.plan == "manual_push_then_show":
            events.append({"action": "manual_push", **run_cli_manual_approval(harness, push_args, rng)})
            events.append({"action": "post_push_jitter", "delay_seconds": round(sleep_with_jitter(rng, 0.0, 0.03), 4)})
            events.append({"action": "show", "screen_text": harness.show_password(position=1)})
        elif case.plan == "push_then_read_burst":
            events.append({"action": "push", "stdout": harness.push_backup(seed_backup).stdout.strip()})
            for step_name, runner in (
                ("verify", lambda: harness.verify_backup(seed_backup).stdout.strip()),
                ("pull", lambda: harness.pull_backup(pulled_backup).stdout.strip()),
                ("diff", lambda: harness.diff_backup(seed_backup).stdout.strip()),
                ("info", lambda: harness.run_cli(info_args, auto_approve=True, timeout_seconds=20.0).stdout.strip()),
                ("pull_again", lambda: harness.pull_backup(pulled_backup).stdout.strip()),
            ):
                events.append({"action": "pre_step_jitter", "step": step_name, "delay_seconds": round(sleep_with_jitter(rng, 0.0, 0.05), 4)})
                events.append({"action": step_name, "stdout": runner()})
        elif case.plan == "manual_push_then_show_second":
            events.append({"action": "manual_push", **run_cli_manual_approval(harness, push_args, rng)})
            events.append({"action": "post_push_jitter", "delay_seconds": round(sleep_with_jitter(rng, 0.0, 0.02), 4)})
            events.append({"action": "show", "position": 2, "screen_text": harness.show_password(position=2)})
        elif case.plan == "manual_push_then_delete":
            events.append({"action": "manual_push", **run_cli_manual_approval(harness, push_args, rng)})
            events.append({"action": "post_push_jitter", "delay_seconds": round(sleep_with_jitter(rng, 0.0, 0.025), 4)})
            events.append({"action": "delete", "screen_text": harness.delete_password(position=1)})
        elif case.plan == "concurrent_reads":
            events.append({"action": "push", "stdout": harness.push_backup(seed_backup).stdout.strip()})
            pull_a = artifacts_dir / f"{case.case_id}-pull-a.json"
            pull_b = artifacts_dir / f"{case.case_id}-pull-b.json"
            burst = run_concurrent_cli_burst(
                harness,
                rng,
                [
                    ("info", info_args),
                    (
                        "pull_a",
                        ["device", "pull", "--out", str(pull_a), "--server", harness.server, "--port", str(harness.apdu_port)],
                    ),
                    (
                        "verify",
                        verify_args,
                    ),
                    (
                        "pull_b",
                        ["device", "pull", "--out", str(pull_b), "--server", harness.server, "--port", str(harness.apdu_port)],
                    ),
                ],
            )
            events.append({"action": "concurrent_burst", "results": burst})
        else:
            raise HarnessError(f"Unknown timing plan: {case.plan}")

        final_pull = harness.pull_backup(pulled_backup)
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
            "plan": case.plan,
            "events": events,
            "final_pull_stdout": final_pull.stdout.strip(),
            "pull_summary": pull_summary(pulled_backup),
        }
    except Exception as error:
        return {
            "case_id": case.case_id,
            "status": "failed",
            "note": case.note,
            "plan": case.plan,
            "error": str(error),
            "screen_text": safe_screen_text(harness),
            "speculos_crash": has_crash_signature(harness),
            "speculos_log_tail": harness.log_tail(),
            "seed_backup": seed_backup.read_text(encoding="utf-8"),
        }
    finally:
        harness.stop()
        cleanup_speculos_containers()


def parse_args() -> argparse.Namespace:
    root_dir = default_root_dir()
    parser = argparse.ArgumentParser(description="FZ-06 chaos timing against app-passwords via CLI + Speculos")
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
        raise SystemExit("No timing cases selected")

    artifacts_dir_obj: tempfile.TemporaryDirectory[str] | None = None
    if args.artifacts_dir:
        artifacts_dir = Path(args.artifacts_dir).resolve()
        artifacts_dir.mkdir(parents=True, exist_ok=True)
    else:
        artifacts_dir_obj = tempfile.TemporaryDirectory(prefix="ledger-pw-fz06-")
        artifacts_dir = Path(artifacts_dir_obj.name)

    results: list[dict[str, Any]] = []
    try:
        for case in selected_cases:
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
        "fuzzer": "FZ-06",
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
