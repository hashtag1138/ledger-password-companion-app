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
class PromptScript:
    overwrite_buttons: tuple[str, ...]
    approve_buttons: tuple[str, ...]
    after_approve_buttons: tuple[str, ...] = ()


@dataclass(frozen=True)
class InterleavedCase:
    case_id: str
    note: str
    random_seed: int
    entries: tuple[SeedEntry, ...]
    setup_kind: str
    flow_action: str
    prompt_script: PromptScript
    post_action: str
    position: int = 1
    expected_final_nicknames: tuple[str, ...] = ()
    expected_selected_nickname: str | None = None


CASES: tuple[InterleavedCase, ...] = (
    InterleavedCase(
        case_id="sofian_push_prompt_bounce_show",
        note="Push a simple item with stray prompt navigation, then show",
        random_seed=131,
        entries=(SeedEntry("sofian terki"),),
        setup_kind="empty",
        flow_action="push",
        prompt_script=PromptScript(
            overwrite_buttons=("left", "right"),
            approve_buttons=("left", "right", "both"),
            after_approve_buttons=("both",),
        ),
        post_action="show",
        position=1,
        expected_final_nicknames=("sofian terki",),
        expected_selected_nickname="sofian terki",
    ),
    InterleavedCase(
        case_id="leading_space_push_prompt_spam_type",
        note="Push a nickname with leading space with spammed prompts, then type",
        random_seed=132,
        entries=(SeedEntry(" leading"),),
        setup_kind="empty",
        flow_action="push",
        prompt_script=PromptScript(
            overwrite_buttons=("left", "right", "left", "right"),
            approve_buttons=("left", "right", "left", "right", "both"),
        ),
        post_action="type",
        position=1,
        expected_final_nicknames=(" leading",),
    ),
    InterleavedCase(
        case_id="sofian_verify_prompt_doubletap",
        note="Verify with parasitic presses on Approve after a normal push",
        random_seed=133,
        entries=(SeedEntry("sofian terki"),),
        setup_kind="pushed",
        flow_action="verify",
        prompt_script=PromptScript(
            overwrite_buttons=(),
            approve_buttons=("left", "right", "both", "both"),
        ),
        post_action="none",
        expected_final_nicknames=("sofian terki",),
    ),
    InterleavedCase(
        case_id="alpha_beta_push_prompt_spam_show_second",
        note="Push alpha/beta with spammed prompts, then show the second item",
        random_seed=134,
        entries=(SeedEntry("alpha"), SeedEntry("beta")),
        setup_kind="empty",
        flow_action="push",
        prompt_script=PromptScript(
            overwrite_buttons=("left", "right", "left", "right"),
            approve_buttons=("left", "right", "left", "right", "both"),
            after_approve_buttons=("right",),
        ),
        post_action="show",
        position=2,
        expected_final_nicknames=("alpha", "beta"),
        expected_selected_nickname="beta",
    ),
    InterleavedCase(
        case_id="dense_twelve_push_prompt_spam_show_last",
        note="Push a dense list with spammed prompts, then show the last item",
        random_seed=135,
        entries=tuple(SeedEntry(f"slot-{index:02d}") for index in range(1, 13)),
        setup_kind="empty",
        flow_action="push",
        prompt_script=PromptScript(
            overwrite_buttons=("left", "right", "left", "right"),
            approve_buttons=("left", "right", "left", "right", "both", "both"),
        ),
        post_action="show",
        position=12,
        expected_final_nicknames=tuple(f"slot-{index:02d}" for index in range(1, 13)),
        expected_selected_nickname="slot-12",
    ),
)


def build_backup_json(entries: tuple[SeedEntry, ...]) -> str:
    payload = {
        "format": "ledger-passwords-companion.v1",
        "storage_size": 4096,
        "app": {"name": "Passwords", "version": "fuzz-apdu-ui-interleaved"},
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
    return "crashed with signal" in harness.log_tail(lines=120).lower()


def load_backup_nicknames(path: Path) -> list[str]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return sorted(entry["nickname"] for entry in payload.get("parsed", []))


def sleep_with_jitter(rng: random.Random, minimum: float, maximum: float) -> float:
    duration = rng.uniform(minimum, maximum)
    time.sleep(duration)
    return duration


def assert_case_limits(case: InterleavedCase) -> None:
    for entry in case.entries:
        utf8_length = len(entry.nickname.encode("utf-8"))
        if utf8_length > 19:
            raise HarnessError(
                f"{case.case_id}: nickname {entry.nickname!r} exceeds Ledger limit with {utf8_length} UTF-8 bytes",
            )


def execute_button_script(
    harness: SpeculosHarness,
    rng: random.Random,
    label: str,
    buttons: tuple[str, ...],
    events: list[dict[str, Any]],
) -> None:
    for button in buttons:
        delay = sleep_with_jitter(rng, 0.005, 0.06)
        harness.press_button(button)
        events.append(
            {
                "event": "button",
                "phase": label,
                "button": button,
                "delay_seconds": round(delay, 4),
                "screen_before": safe_screen_text(harness),
            },
        )


def run_cli_with_prompt_script(
    harness: SpeculosHarness,
    args: list[str],
    prompt_script: PromptScript,
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
    events: list[dict[str, Any]] = []
    handled_overwrite = False
    handled_approve = False
    post_approve_done = False
    last_screen = ""
    try:
        deadline = time.time() + timeout_seconds
        while process.poll() is None:
            harness.ensure_running()
            if time.time() >= deadline:
                process.kill()
                stdout, stderr = process.communicate(timeout=2)
                raise HarnessError(
                    f"Interleaved CLI timed out for args={args!r}; stdout={stdout!r}; stderr={stderr!r}; screen={safe_screen_text(harness)!r}",
                )

            screen = safe_screen_text(harness)
            if screen and screen != last_screen:
                events.append({"event": "screen", "screen": screen})
                last_screen = screen

            if ("Transfer metadatas" in screen or "Overwrite metadatas" in screen) and not handled_overwrite:
                execute_button_script(harness, rng, "overwrite", prompt_script.overwrite_buttons, events)
                handled_overwrite = True
            elif "Approve" in screen and not handled_approve:
                execute_button_script(harness, rng, "approve", prompt_script.approve_buttons, events)
                handled_approve = True
            elif handled_approve and not post_approve_done and prompt_script.after_approve_buttons:
                execute_button_script(harness, rng, "after_approve", prompt_script.after_approve_buttons, events)
                post_approve_done = True

            time.sleep(rng.uniform(0.01, 0.05))

        stdout, stderr = process.communicate(timeout=2)
        if process.returncode != 0:
            raise HarnessError(
                f"Interleaved CLI failed for args={args!r}; stdout={stdout!r}; stderr={stderr!r}; screen={safe_screen_text(harness)!r}",
            )
        return {
            "args": args,
            "stdout": stdout.strip(),
            "stderr": stderr.strip(),
            "events": events,
        }
    finally:
        stop_process_tree(process)


def execute_post_action(harness: SpeculosHarness, case: InterleavedCase) -> dict[str, Any]:
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
    if case.post_action == "delete":
        screen_text = harness.delete_password(position=case.position)
        return {"action": "delete", "position": case.position, "screen_text": screen_text}
    raise HarnessError(f"Unknown post action: {case.post_action}")


def prepare_setup(
    harness: SpeculosHarness,
    case: InterleavedCase,
    seed_backup: Path,
) -> None:
    if case.setup_kind == "empty":
        return
    if case.setup_kind == "pushed":
        harness.push_backup(seed_backup)
        return
    raise HarnessError(f"Unknown setup kind: {case.setup_kind}")


def flow_args(harness: SpeculosHarness, case: InterleavedCase, seed_backup: Path, pulled_backup: Path) -> list[str]:
    if case.flow_action == "push":
        return ["device", "push", str(seed_backup), "--server", harness.server, "--port", str(harness.apdu_port)]
    if case.flow_action == "verify":
        return ["device", "verify", str(seed_backup), "--server", harness.server, "--port", str(harness.apdu_port)]
    if case.flow_action == "pull":
        return ["device", "pull", "--out", str(pulled_backup), "--server", harness.server, "--port", str(harness.apdu_port)]
    if case.flow_action == "diff":
        return ["device", "diff", str(seed_backup), "--server", harness.server, "--port", str(harness.apdu_port)]
    raise HarnessError(f"Unknown flow action: {case.flow_action}")


def run_case(
    *,
    case: InterleavedCase,
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
        prepare_setup(harness, case, seed_backup)
        flow = run_cli_with_prompt_script(
            harness,
            flow_args(harness, case, seed_backup, pulled_backup),
            case.prompt_script,
            rng,
        )
        post_action = execute_post_action(harness, case)
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
            "flow_action": case.flow_action,
            "flow": flow,
            "post_action": post_action,
            "final_pull_stdout": final_pull.stdout.strip(),
            "final_nicknames": final_nicknames,
        }
    except Exception as error:
        return {
            "case_id": case.case_id,
            "status": "failed",
            "note": case.note,
            "flow_action": case.flow_action,
            "error": str(error),
            "screen_text": safe_screen_text(harness),
            "speculos_crash": has_crash_signature(harness),
            "speculos_log_tail": harness.log_tail(),
            "seed_backup": seed_backup.read_text(encoding="utf-8"),
            "pulled_backup": pulled_backup.read_text(encoding="utf-8") if pulled_backup.exists() else None,
        }
    finally:
        harness.stop()
        cleanup_speculos_containers()


def parse_args() -> argparse.Namespace:
    root_dir = default_root_dir()
    parser = argparse.ArgumentParser(description="FZ-13 APDU prompt + UI interleaving fuzzer via CLI + Speculos")
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
        raise SystemExit("No interleaved cases selected")

    artifacts_dir_obj: tempfile.TemporaryDirectory[str] | None = None
    if args.artifacts_dir:
        artifacts_dir = Path(args.artifacts_dir).resolve()
        artifacts_dir.mkdir(parents=True, exist_ok=True)
    else:
        artifacts_dir_obj = tempfile.TemporaryDirectory(prefix="ledger-pw-fz13-")
        artifacts_dir = Path(artifacts_dir_obj.name)

    results: list[dict[str, Any]] = []
    try:
        for case in selected_cases:
            print(f"[FZ-13] {case.case_id}", flush=True)
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
        "fuzzer": "FZ-13",
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
