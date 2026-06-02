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
from fuzz_oracles import classify_oracle_evidence, collect_oracle_evidence, parse_selected_nickname, safe_screen_text


@dataclass(frozen=True)
class SeedEntry:
    nickname: str
    charsets: tuple[str, ...] = ("ALL_SETS",)


@dataclass(frozen=True)
class EntryOffset:
    offset: int
    length: int


@dataclass(frozen=True)
class IncidentCase:
    case_id: str
    note: str
    plan: str
    entries: tuple[SeedEntry, ...]
    position: int = 1
    expected_selected_nickname: str | None = None
    expected_pull_nicknames: tuple[str, ...] | None = None
    mutation: str | None = None
    repetitions: int = 1


CASES: tuple[IncidentCase, ...] = (
    IncidentCase(
        case_id="sofian_push_show_control",
        note="Contrôle minimal du flux incident: push puis show sur un seul identifiant",
        plan="push_show",
        entries=(SeedEntry("sofian terki"),),
        position=1,
        expected_selected_nickname="sofian terki",
        expected_pull_nicknames=("sofian terki",),
    ),
    IncidentCase(
        case_id="sofian_push_verify_show_control",
        note="Même flux mais avec verify séparé avant le show",
        plan="push_verify_show",
        entries=(SeedEntry("sofian terki"),),
        position=1,
        expected_selected_nickname="sofian terki",
        expected_pull_nicknames=("sofian terki",),
    ),
    IncidentCase(
        case_id="sofian_push_restart_show_first",
        note="Push de sofian terki, restart Speculos, puis show du premier item",
        plan="push_restart_show",
        entries=(SeedEntry("sofian terki"),),
        position=1,
        expected_selected_nickname="sofian terki",
        expected_pull_nicknames=("sofian terki",),
    ),
    IncidentCase(
        case_id="leading_space_push_type_repeat3",
        note="Seed flaky historique rejoué 3 fois sur push -> type",
        plan="push_type_repeat",
        entries=(SeedEntry(" leading"),),
        position=1,
        expected_pull_nicknames=(" leading",),
        repetitions=3,
    ),
    IncidentCase(
        case_id="alpha_beta_push_show_second",
        note="Deux entrées valides, push puis show du second item",
        plan="push_show",
        entries=(SeedEntry("alpha"), SeedEntry("beta")),
        position=2,
        expected_selected_nickname="beta",
        expected_pull_nicknames=("alpha", "beta"),
    ),
    IncidentCase(
        case_id="alpha_beta_push_verify_show_second",
        note="Deux entrées valides, verify séparé, puis show du second item",
        plan="push_verify_show",
        entries=(SeedEntry("alpha"), SeedEntry("beta")),
        position=2,
        expected_selected_nickname="beta",
        expected_pull_nicknames=("alpha", "beta"),
    ),
    IncidentCase(
        case_id="second_len_plus1_show_second",
        note="Raw corrompu accepté, puis show du second item",
        plan="raw_show",
        entries=(SeedEntry("github"), SeedEntry("gmail")),
        position=2,
        expected_selected_nickname="gmail",
        expected_pull_nicknames=("github", "gmail\x00"),
        mutation="second_len_plus1",
    ),
    IncidentCase(
        case_id="second_len_plus1_restart_show_second",
        note="Raw corrompu accepté, restart Speculos, puis show du second item",
        plan="raw_restart_show",
        entries=(SeedEntry("github"), SeedEntry("gmail")),
        position=2,
        expected_selected_nickname="gmail\x00",
        expected_pull_nicknames=("github", "gmail\x00"),
        mutation="second_len_plus1",
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
        subprocess.run([docker, "kill", *container_ids], capture_output=True, text=True, check=False)


def build_backup_json(entries: tuple[SeedEntry, ...], app_version: str = "fuzz-incident-regression") -> str:
    payload = {
        "format": "ledger-passwords-companion.v1",
        "storage_size": 4096,
        "app": {"name": "Passwords", "version": app_version},
        "parsed": [{"nickname": entry.nickname, "charsets": list(entry.charsets)} for entry in entries],
        "nicknames_erased_but_still_stored": [],
        "corruptions_encountered": [],
        "raw_metadatas": None,
    }
    return json.dumps(payload, ensure_ascii=False, indent=2)


def write_seed_backup(path: Path, entries: tuple[SeedEntry, ...]) -> None:
    path.write_text(build_backup_json(entries), encoding="utf-8")


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


def load_backup_nicknames(path: Path) -> tuple[str, ...]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return tuple(sorted(entry["nickname"] for entry in payload.get("parsed", [])))


def assert_utf8_limits(case: IncidentCase) -> None:
    for entry in case.entries:
        utf8_length = len(entry.nickname.encode("utf-8"))
        if utf8_length > 19:
            raise HarnessError(
                f"{case.case_id}: nickname {entry.nickname!r} exceeds Ledger limit with {utf8_length} UTF-8 bytes",
            )


def prepare_mutated_raw(
    *,
    root_dir: Path,
    cli_bin: Path,
    seed_backup: Path,
    raw_path: Path,
) -> bytes:
    export_seed_raw(cli_bin, root_dir, seed_backup, raw_path)
    seed_raw = raw_path.read_bytes()
    return mutate_second_len_plus1(seed_raw)


def expected_names(case: IncidentCase) -> tuple[str, ...] | None:
    if case.expected_pull_nicknames is None:
        return None
    return tuple(sorted(case.expected_pull_nicknames))


def check_selected(case: IncidentCase, screen_text: str) -> str:
    selected = parse_selected_nickname(screen_text)
    if case.expected_selected_nickname is not None and selected != case.expected_selected_nickname:
        raise HarnessError(
            f"Unexpected selected nickname for {case.case_id}: expected {case.expected_selected_nickname!r}, got {selected!r}",
        )
    return selected


def check_pull_names(case: IncidentCase, pulled_names: tuple[str, ...]) -> None:
    expected = expected_names(case)
    if expected is not None and pulled_names != expected:
        raise HarnessError(
            f"Unexpected pulled state for {case.case_id}: expected {expected!r}, got {pulled_names!r}",
        )


def run_single_flow(
    *,
    case: IncidentCase,
    harness: SpeculosHarness,
    seed_backup: Path,
    pulled_backup: Path,
    mutated_raw: bytes | None,
) -> dict[str, Any]:
    harness.start()
    try:
        harness.initialize_first_run()
        if mutated_raw is None:
            push = harness.push_backup(seed_backup)
            setup = {"action": "push", "stdout": push.stdout.strip()}
        else:
            harness.load_raw_metadata(mutated_raw)
            setup = {"action": "load_raw_metadata", "bytes": len(mutated_raw)}

        if case.plan == "push_verify_show":
            verify = harness.verify_backup(seed_backup)
            setup["verify_stdout"] = verify.stdout.strip()

        if case.plan in {"push_show", "push_verify_show", "raw_show"}:
            screen_text = harness.show_password(position=case.position)
            selected = check_selected(case, screen_text)
            pull = harness.pull_backup(pulled_backup)
            pulled_names = load_backup_nicknames(pulled_backup)
            check_pull_names(case, pulled_names)
            return {
                "setup": setup,
                "post_action": "show",
                "screen_text": screen_text,
                "selected_nickname": selected,
                "pull_stdout": pull.stdout.strip(),
                "pulled_names": list(pulled_names),
            }

        if case.plan == "push_type_repeat":
            screen_text = harness.type_password(position=case.position)
            pull = harness.pull_backup(pulled_backup)
            pulled_names = load_backup_nicknames(pulled_backup)
            check_pull_names(case, pulled_names)
            return {
                "setup": setup,
                "post_action": "type",
                "screen_text": screen_text,
                "pull_stdout": pull.stdout.strip(),
                "pulled_names": list(pulled_names),
            }

        raise HarnessError(f"Unsupported non-restart plan: {case.plan}")
    finally:
        harness.stop()


def run_restart_flow(
    *,
    case: IncidentCase,
    root_dir: Path,
    app_path: Path,
    cli_bin: Path,
    server: str,
    display: str,
    seed_backup: Path,
    artifacts_dir: Path,
    mutated_raw: bytes | None,
) -> dict[str, Any]:
    state_dir = artifacts_dir / f"{case.case_id}-state"
    pre_restart_backup = artifacts_dir / f"{case.case_id}-pre.json"
    post_restart_backup = artifacts_dir / f"{case.case_id}-post.json"

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

    setup: dict[str, Any] | None = None
    pre_names: tuple[str, ...] | None = None
    post_names: tuple[str, ...] | None = None
    screen_text = ""
    selected: str | None = None
    try:
        writer.start()
        writer.initialize_first_run()
        if mutated_raw is None:
            push = writer.push_backup(seed_backup)
            setup = {"action": "push", "stdout": push.stdout.strip()}
        else:
            writer.load_raw_metadata(mutated_raw)
            setup = {"action": "load_raw_metadata", "bytes": len(mutated_raw)}

        pre_pull = writer.pull_backup(pre_restart_backup)
        pre_names = load_backup_nicknames(pre_restart_backup)
        check_pull_names(case, pre_names)
        writer.stop()

        reader.start()
        screen_text = reader.show_password(position=case.position)
        selected = check_selected(case, screen_text)
        post_pull = reader.pull_backup(post_restart_backup)
        post_names = load_backup_nicknames(post_restart_backup)
        check_pull_names(case, post_names)

        return {
            "ok": True,
            "setup": setup,
            "pre_restart_pull_stdout": pre_pull.stdout.strip(),
            "pre_restart_names": list(pre_names),
            "post_action": "show",
            "screen_text": screen_text,
            "selected_nickname": selected,
            "post_restart_pull_stdout": post_pull.stdout.strip(),
            "post_restart_names": list(post_names),
        }
    except Exception as error:
        return {
            "ok": False,
            "error": str(error),
            "setup": setup,
            "pre_restart_names": list(pre_names) if pre_names is not None else None,
            "screen_text": screen_text,
            "selected_nickname": selected,
            "post_restart_names": list(post_names) if post_names is not None else None,
            "writer_screen_text": safe_screen_text(writer),
            "reader_screen_text": safe_screen_text(reader),
            "writer_log_tail": writer.log_tail(),
            "reader_log_tail": reader.log_tail(),
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


def run_case(
    *,
    case: IncidentCase,
    artifacts_dir: Path,
    root_dir: Path,
    app_path: Path,
    cli_bin: Path,
    server: str,
    display: str,
) -> dict[str, Any]:
    assert_utf8_limits(case)
    cleanup_speculos_containers()
    seed_backup = artifacts_dir / f"{case.case_id}-seed.json"
    raw_path = artifacts_dir / f"{case.case_id}-seed.bin"
    pulled_backup = artifacts_dir / f"{case.case_id}-pull.json"
    write_seed_backup(seed_backup, case.entries)

    mutated_raw: bytes | None = None
    if case.mutation is not None:
        if case.mutation != "second_len_plus1":
            raise HarnessError(f"Unsupported mutation: {case.mutation}")
        mutated_raw = prepare_mutated_raw(root_dir=root_dir, cli_bin=cli_bin, seed_backup=seed_backup, raw_path=raw_path)

    if case.plan == "push_type_repeat":
        iterations: list[dict[str, Any]] = []
        failures: list[dict[str, Any]] = []
        for attempt in range(1, case.repetitions + 1):
            harness = SpeculosHarness(
                root_dir=root_dir,
                app_path=app_path,
                cli_bin=cli_bin,
                server=server,
                apdu_port=find_free_port(server),
                api_port=find_free_port(server),
                display=display,
            )
            try:
                result = run_single_flow(
                    case=case,
                    harness=harness,
                    seed_backup=seed_backup,
                    pulled_backup=artifacts_dir / f"{case.case_id}-attempt{attempt}.json",
                    mutated_raw=mutated_raw,
                )
                iterations.append(
                    {
                        "attempt": attempt,
                        "status": "ok",
                        **result,
                        "oracle_evidence": classify_oracle_evidence(
                            screen_text=result.get("screen_text", ""),
                            actual_names=result.get("pulled_names"),
                            expected_names=list(expected_names(case) or []),
                            expected_selected_nickname=case.expected_selected_nickname,
                            actual_selected_nickname=parse_selected_nickname(result.get("screen_text", "")) if result.get("post_action") == "show" else None,
                        ),
                    },
                )
            except Exception as error:
                iteration = {
                    "attempt": attempt,
                    "status": "failed",
                    "error": str(error),
                    "screen_text": safe_screen_text(harness),
                    "log_tail": harness.log_tail(),
                }
                iteration["oracle_evidence"] = collect_oracle_evidence(
                    harness=harness,
                    error=error,
                    screen_text=iteration["screen_text"],
                    expected_names=expected_names(case),
                    expected_selected_nickname=case.expected_selected_nickname,
                )
                iterations.append(iteration)
                failures.append(iteration)
            finally:
                try:
                    harness.stop()
                except Exception:
                    pass
                cleanup_speculos_containers()

        if failures:
            return {
                "case_id": case.case_id,
                "status": "failed",
                "note": case.note,
                "iterations": iterations,
                "seed_backup": seed_backup.read_text(encoding="utf-8"),
            }
        return {
            "case_id": case.case_id,
            "status": "ok",
            "note": case.note,
            "iterations": iterations,
        }

    harness = SpeculosHarness(
        root_dir=root_dir,
        app_path=app_path,
        cli_bin=cli_bin,
        server=server,
        apdu_port=find_free_port(server),
        api_port=find_free_port(server),
        display=display,
    )
    try:
        if case.plan in {"push_show", "push_verify_show", "raw_show"}:
            result = run_single_flow(
                case=case,
                harness=harness,
                seed_backup=seed_backup,
                pulled_backup=pulled_backup,
                mutated_raw=mutated_raw,
            )
            return {
                "case_id": case.case_id,
                "status": "ok",
                "note": case.note,
                **result,
                "oracle_evidence": classify_oracle_evidence(
                    screen_text=result.get("screen_text", ""),
                    actual_names=result.get("pulled_names"),
                    expected_names=list(expected_names(case) or []),
                    expected_selected_nickname=case.expected_selected_nickname,
                    actual_selected_nickname=result.get("selected_nickname"),
                ),
            }
        if case.plan in {"push_restart_show", "raw_restart_show"}:
            result = run_restart_flow(
                case=case,
                root_dir=root_dir,
                app_path=app_path,
                cli_bin=cli_bin,
                server=server,
                display=display,
                seed_backup=seed_backup,
                artifacts_dir=artifacts_dir,
                mutated_raw=mutated_raw,
            )
            if not result.pop("ok"):
                return {
                    "case_id": case.case_id,
                    "status": "failed",
                    "note": case.note,
                    **result,
                    "oracle_evidence": classify_oracle_evidence(
                        error_text=result["error"],
                        screen_text=result.get("screen_text", "") or result.get("reader_screen_text", ""),
                        log_tail=result.get("reader_log_tail", "") or result.get("writer_log_tail", ""),
                        expected_names=list(expected_names(case) or []),
                        actual_names=result.get("post_restart_names"),
                        expected_selected_nickname=case.expected_selected_nickname,
                        actual_selected_nickname=result.get("selected_nickname"),
                    ),
                }
            return {
                "case_id": case.case_id,
                "status": "ok",
                "note": case.note,
                **{key: value for key, value in result.items() if key != "ok"},
                "oracle_evidence": classify_oracle_evidence(
                    screen_text=result.get("screen_text", ""),
                    actual_names=result.get("post_restart_names"),
                    expected_names=list(expected_names(case) or []),
                    expected_selected_nickname=case.expected_selected_nickname,
                    actual_selected_nickname=result.get("selected_nickname"),
                ),
            }
        raise HarnessError(f"Unsupported incident plan: {case.plan}")
    except Exception as error:
        return {
            "case_id": case.case_id,
            "status": "failed",
            "note": case.note,
            "error": str(error),
            "screen_text": safe_screen_text(harness),
            "speculos_log_tail": harness.log_tail(),
            "seed_backup": seed_backup.read_text(encoding="utf-8"),
            "mutated_raw_hex_prefix": mutated_raw[:128].hex() if mutated_raw is not None else None,
            "oracle_evidence": collect_oracle_evidence(
                harness=harness,
                error=error,
                expected_names=expected_names(case),
                expected_selected_nickname=case.expected_selected_nickname,
            ),
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
        description="FZ-15 incident regression scenarios for app-passwords via CLI + Speculos",
    )
    parser.add_argument("--app", default=str(default_app_path(root_dir)), help="Path to app.elf")
    parser.add_argument("--cli", default=str(default_cli_bin(root_dir)), help="Path to ledger-pw CLI")
    parser.add_argument("--server", default="127.0.0.1", help="Speculos API/APDU host")
    parser.add_argument("--display", default="headless", help="Speculos display mode")
    parser.add_argument("--cases", default="", help="Comma-separated case ids. Empty means the full curated set.")
    parser.add_argument("--artifacts-dir", default="", help="Directory to keep generated artifacts. Default: temp dir.")
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
        raise SystemExit("No incident-regression cases selected")

    artifacts_dir = Path(args.artifacts_dir).resolve() if args.artifacts_dir else Path(
        tempfile.mkdtemp(prefix="ledger-pw-fz15-"),
    )
    artifacts_dir.mkdir(parents=True, exist_ok=True)

    results = []
    for case in cases:
        print(f"[FZ-15] {case.case_id}", flush=True)
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
        "fuzzer": "FZ-15 incident regression",
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
