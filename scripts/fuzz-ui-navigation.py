#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import random
import shutil
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


BUTTONS = ("left", "right", "both")


@dataclass(frozen=True)
class SeedEntry:
    nickname: str
    charsets: tuple[str, ...] = ("ALL_SETS",)


@dataclass(frozen=True)
class NavigationCase:
    case_id: str
    note: str
    setup_kind: str
    random_seed: int
    steps: int
    seed_entries: tuple[SeedEntry, ...] = ()


CASES: tuple[NavigationCase, ...] = (
    NavigationCase(
        case_id="empty_home_walk_seed11",
        note="Chaotic navigation from an empty vault",
        setup_kind="empty",
        random_seed=11,
        steps=48,
    ),
    NavigationCase(
        case_id="single_entry_walk_seed21",
        note="Chaotic navigation with a single identifier",
        setup_kind="backup",
        random_seed=21,
        steps=64,
        seed_entries=(SeedEntry("sofian terki"),),
    ),
    NavigationCase(
        case_id="leading_space_walk_seed22",
        note="Chaotic navigation with an identifier that starts with a space",
        setup_kind="backup",
        random_seed=22,
        steps=64,
        seed_entries=(SeedEntry(" leading"),),
    ),
    NavigationCase(
        case_id="multi_entry_walk_seed31",
        note="Chaotic navigation on a multi-entry list",
        setup_kind="backup",
        random_seed=31,
        steps=72,
        seed_entries=(SeedEntry("github"), SeedEntry("gmail"), SeedEntry("proton")),
    ),
    NavigationCase(
        case_id="mutated_second_len_plus1_walk_seed41",
        note="Chaotic navigation after loading the accepted corrupted raw second_len_plus1",
        setup_kind="mutated_second_len_plus1",
        random_seed=41,
        steps=72,
        seed_entries=(SeedEntry("github"), SeedEntry("gmail")),
    ),
)


def build_backup_json(entries: tuple[SeedEntry, ...]) -> str:
    payload = {
        "format": "ledger-passwords-companion.v1",
        "storage_size": 4096,
        "app": {"name": "Passwords", "version": "fuzz-ui-navigation"},
        "parsed": [{"nickname": entry.nickname, "charsets": list(entry.charsets)} for entry in entries],
        "nicknames_erased_but_still_stored": [],
        "corruptions_encountered": [],
        "raw_metadatas": None,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


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


def parse_entry_offsets(raw: bytes) -> list[tuple[int, int]]:
    offsets: list[tuple[int, int]] = []
    offset = 0
    while offset < len(raw):
        length = raw[offset]
        if length == 0:
            break
        offsets.append((offset, length))
        offset += length + 2
    return offsets


def mutate_second_len_plus1(raw: bytes) -> bytes:
    mutated = bytearray(raw)
    offsets = parse_entry_offsets(mutated)
    if len(offsets) < 2:
        raise HarnessError("second_len_plus1 requires at least two entries")
    second_offset, second_length = offsets[1]
    mutated[second_offset] = min(0xFF, second_length + 1)
    return bytes(mutated)


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


def choose_button(screen_text: str, rng: random.Random) -> str:
    def pick(weighted: list[tuple[str, int]]) -> str:
        total = sum(weight for _, weight in weighted)
        ticket = rng.randrange(total)
        current = 0
        for button, weight in weighted:
            current += weight
            if ticket < current:
                return button
        return weighted[-1][0]

    if "Tap to manage" in screen_text:
        return pick([("both", 55), ("right", 25), ("left", 20)])
    if "Manage passwords" in screen_text:
        return pick([("right", 55), ("both", 25), ("left", 20)])
    if "Which action?" in screen_text:
        return pick([("right", 40), ("left", 25), ("both", 35)])
    if "Passwords list" in screen_text:
        return pick([("right", 40), ("left", 25), ("both", 35)])
    if "PASSWORD HAS" in screen_text:
        return pick([("both", 45), ("right", 35), ("left", 20)])
    if any(token in screen_text for token in ("Approve", "Refuse", "Yes", "No", "Cancel")):
        return pick([("right", 40), ("both", 40), ("left", 20)])
    if any(token in screen_text for token in ("Create a new", "Show password", "Type password", "Delete password")):
        return pick([("right", 45), ("both", 35), ("left", 20)])
    return pick([("left", 33), ("right", 33), ("both", 34)])


def has_crash_signature(harness: SpeculosHarness) -> bool:
    tail = harness.log_tail(lines=80)
    return "crashed with signal" in tail.lower()


def safe_screen_text(harness: SpeculosHarness) -> str:
    try:
        return harness.current_screen_text()
    except Exception:
        return ""


def setup_case(
    harness: SpeculosHarness,
    case: NavigationCase,
    root_dir: Path,
    cli_bin: Path,
    artifacts_dir: Path,
) -> dict[str, Any]:
    if case.setup_kind == "empty":
        return {"setup_kind": "empty"}

    backup_path = artifacts_dir / f"{case.case_id}-seed.json"
    backup_path.write_text(build_backup_json(case.seed_entries), encoding="utf-8")

    if case.setup_kind == "backup":
        result = harness.push_backup(backup_path)
        return {
            "setup_kind": "backup",
            "seed_entries": [entry.nickname for entry in case.seed_entries],
            "push_stdout": result.stdout.strip(),
        }

    if case.setup_kind == "mutated_second_len_plus1":
        seed_raw_path = artifacts_dir / f"{case.case_id}-seed.bin"
        mutated_raw_path = artifacts_dir / f"{case.case_id}-mutated.bin"
        export_seed_raw(cli_bin, root_dir, backup_path, seed_raw_path)
        mutated_raw = mutate_second_len_plus1(seed_raw_path.read_bytes())
        mutated_raw_path.write_bytes(mutated_raw)
        harness.load_raw_metadata(mutated_raw)
        return {
            "setup_kind": "mutated_second_len_plus1",
            "seed_entries": [entry.nickname for entry in case.seed_entries],
            "mutated_raw_path": str(mutated_raw_path),
        }

    raise HarnessError(f"Unknown setup kind: {case.setup_kind}")


def run_post_checks(harness: SpeculosHarness, artifacts_dir: Path, case_id: str) -> dict[str, Any]:
    info = harness.run_cli(
        ["device", "info", "--server", harness.server, "--port", str(harness.apdu_port)],
        auto_approve=True,
        timeout_seconds=20.0,
    )
    pulled_backup = artifacts_dir / f"{case_id}-post-pull.json"
    pull = harness.pull_backup(pulled_backup)
    return {
        "device_info_stdout": info.stdout.strip(),
        "pull_stdout": pull.stdout.strip(),
        "pull_summary": pull_summary(pulled_backup),
    }


def walk_buttons(harness: SpeculosHarness, case: NavigationCase) -> dict[str, Any]:
    rng = random.Random(case.random_seed)
    transitions: list[dict[str, Any]] = []
    unique_screens: dict[str, int] = {}
    stagnation_warnings: list[dict[str, Any]] = []
    last_screen = safe_screen_text(harness)
    stagnation_screen = last_screen
    stagnation_count = 0
    stagnation_buttons: set[str] = set()

    for step in range(case.steps):
        if has_crash_signature(harness):
            raise HarnessError("Speculos log reports app crash")

        button = choose_button(last_screen, rng)
        before = last_screen
        harness.press_button(button)
        time.sleep(0.05)
        after = safe_screen_text(harness)
        if not after and has_crash_signature(harness):
            raise HarnessError("Speculos log reports app crash after button press")

        unique_screens[after] = unique_screens.get(after, 0) + 1
        if len(transitions) < 30:
            transitions.append({"step": step + 1, "button": button, "before": before, "after": after})

        if after == stagnation_screen:
            stagnation_count += 1
            stagnation_buttons.add(button)
        else:
            stagnation_screen = after
            stagnation_count = 1
            stagnation_buttons = {button}

        if stagnation_count >= 18 and len(stagnation_buttons) >= 3:
            stagnation_warnings.append(
                {
                    "step": step + 1,
                    "screen": after,
                    "count": stagnation_count,
                    "buttons": sorted(stagnation_buttons),
                },
            )
            stagnation_count = 0
            stagnation_buttons = set()

        last_screen = after

    return {
        "final_screen": last_screen,
        "transitions": transitions,
        "unique_screen_count": len([screen for screen in unique_screens if screen]),
        "stagnation_warnings": stagnation_warnings,
    }


def run_case(
    *,
    root_dir: Path,
    app_path: Path,
    cli_bin: Path,
    server: str,
    api_port: int,
    apdu_port: int,
    display: str,
    case: NavigationCase,
    artifacts_dir: Path,
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
    harness.start()
    try:
        harness.initialize_first_run()
        setup = setup_case(harness, case, root_dir, cli_bin, artifacts_dir)
        walk = walk_buttons(harness, case)
        if has_crash_signature(harness):
            raise HarnessError("Speculos log reports app crash at end of navigation")
        post_checks = run_post_checks(harness, artifacts_dir, case.case_id)
        return {
            "case_id": case.case_id,
            "status": "ok",
            "note": case.note,
            "setup": setup,
            "random_seed": case.random_seed,
            "steps": case.steps,
            "walk": walk,
            "post_checks": post_checks,
        }
    except Exception as error:
        return {
            "case_id": case.case_id,
            "status": "failed",
            "note": case.note,
            "random_seed": case.random_seed,
            "steps": case.steps,
            "error": str(error),
            "screen_text": safe_screen_text(harness),
            "speculos_log_tail": harness.log_tail(),
        }
    finally:
        try:
            harness.stop()
        except Exception:
            pass
        cleanup_speculos_containers()


def parse_args() -> argparse.Namespace:
    root_dir = default_root_dir()
    parser = argparse.ArgumentParser(
        description="FZ-05 UI button navigation fuzzing against app-passwords via Speculos",
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
        "--steps",
        type=int,
        default=0,
        help="Override number of button presses for every case.",
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
        raise SystemExit("No navigation cases selected")

    if args.steps > 0:
        cases = tuple(
            NavigationCase(
                case_id=case.case_id,
                note=case.note,
                setup_kind=case.setup_kind,
                random_seed=case.random_seed,
                steps=args.steps,
                seed_entries=case.seed_entries,
            )
            for case in cases
        )

    artifacts_dir = Path(args.artifacts_dir).resolve() if args.artifacts_dir else Path(
        tempfile.mkdtemp(prefix="ledger-pw-fz05-"),
    )
    artifacts_dir.mkdir(parents=True, exist_ok=True)

    results = []
    for case in cases:
        print(f"[FZ-05] {case.case_id}", flush=True)
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
                artifacts_dir=artifacts_dir,
            ),
        )

    report = {
        "fuzzer": "FZ-05 UI button navigation",
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
