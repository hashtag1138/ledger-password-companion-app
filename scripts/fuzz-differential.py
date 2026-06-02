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
    default_cli_bin,
    default_root_dir,
    ensure_cli_exists,
    find_free_port,
    print_json,
)


NANOS_PLUS_130_REF = "nanos+_1.6.0_1.3.0_sdk_v26.0.2"
NANOS_PLUS_131_REF = "nanos+_1.6.1_1.3.1_sdk_v26.1.7"


@dataclass(frozen=True)
class VersionSpec:
    version_id: str
    git_ref: str
    build_dir_name: str


@dataclass(frozen=True)
class SeedEntry:
    nickname: str
    charsets: tuple[str, ...] = ("ALL_SETS",)


@dataclass(frozen=True)
class DifferentialCase:
    case_id: str
    note: str
    setup_kind: str
    seed_entries: tuple[SeedEntry, ...]
    action: str
    position: int = 1


VERSION_SPECS: tuple[VersionSpec, ...] = (
    VersionSpec(
        version_id="nanos_plus_1_3_0",
        git_ref=NANOS_PLUS_130_REF,
        build_dir_name="nanos-plus-1.3.0",
    ),
    VersionSpec(
        version_id="nanos_plus_1_3_1",
        git_ref=NANOS_PLUS_131_REF,
        build_dir_name="nanos-plus-1.3.1",
    ),
    VersionSpec(
        version_id="master",
        git_ref="master",
        build_dir_name="master",
    ),
)


CASES: tuple[DifferentialCase, ...] = (
    DifferentialCase(
        case_id="sofian_show_first",
        note="Référence valide avec un seul identifiant contenant un espace",
        setup_kind="backup",
        seed_entries=(SeedEntry("sofian terki"),),
        action="show",
        position=1,
    ),
    DifferentialCase(
        case_id="leading_space_delete_first",
        note="Référence valide avec espace en tête puis delete",
        setup_kind="backup",
        seed_entries=(SeedEntry(" leading"),),
        action="delete",
        position=1,
    ),
    DifferentialCase(
        case_id="alpha_beta_show_second",
        note="Seed valide connu pour crasher sur show du second item",
        setup_kind="backup",
        seed_entries=(SeedEntry("alpha"), SeedEntry("beta")),
        action="show",
        position=2,
    ),
    DifferentialCase(
        case_id="second_len_plus1_show_second",
        note="Seed raw corrompu accepté puis show du second item",
        setup_kind="mutated_second_len_plus1",
        seed_entries=(SeedEntry("github"), SeedEntry("gmail")),
        action="show",
        position=2,
    ),
)


def build_backup_json(entries: tuple[SeedEntry, ...]) -> str:
    payload = {
        "format": "ledger-passwords-companion.v1",
        "storage_size": 4096,
        "app": {"name": "Passwords", "version": "fuzz-differential"},
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


def safe_screen_text(harness: SpeculosHarness) -> str:
    try:
        return harness.current_screen_text()
    except Exception:
        return ""


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


def classify_result(status: str, error: str = "", speculos_crash: bool = False) -> str:
    if status == "ok":
        return "ok"
    lowered = error.lower()
    if speculos_crash or "crashed with signal" in lowered:
        return "crash"
    if "timed out" in lowered or "timeout" in lowered:
        return "timeout"
    if "remote end closed connection without response" in lowered:
        return "crash"
    return "error"


def build_app_elf(
    *,
    root_dir: Path,
    spec: VersionSpec,
    image: str,
    rebuild: bool,
) -> Path:
    work_dir = root_dir / "build" / "speculos" / "differential" / spec.build_dir_name
    if rebuild and work_dir.exists():
        shutil.rmtree(work_dir)
    cmd = [
        str(root_dir / "scripts" / "build-passwords-app.sh"),
        "--dir",
        str(work_dir),
        "--ref",
        spec.git_ref,
        "--image",
        image,
        "--print-path",
    ]
    if not rebuild:
        cmd.append("--no-update")
    result = subprocess.run(
        cmd,
        cwd=root_dir,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise HarnessError(
            f"Failed to build {spec.version_id} ({spec.git_ref}): stdout={result.stdout!r} stderr={result.stderr!r}",
        )
    stdout_lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if not stdout_lines:
        raise HarnessError(f"Build for {spec.version_id} produced no stdout path")
    app_path = Path(stdout_lines[-1]).resolve()
    if not app_path.exists():
        raise HarnessError(f"Build reported app path {app_path}, but the file does not exist")
    return app_path


def execute_case_action(harness: SpeculosHarness, case: DifferentialCase) -> dict[str, Any]:
    if case.action == "show":
        return {"action": "show", "position": case.position, "screen_text": harness.show_password(position=case.position)}
    if case.action == "type":
        return {"action": "type", "position": case.position, "screen_text": harness.type_password(position=case.position)}
    if case.action == "delete":
        return {
            "action": "delete",
            "position": case.position,
            "screen_text": harness.delete_password(position=case.position),
        }
    raise HarnessError(f"Unknown action {case.action!r} for case {case.case_id}")


def run_case_for_version(
    *,
    version: VersionSpec,
    app_path: Path,
    case: DifferentialCase,
    root_dir: Path,
    cli_bin: Path,
    artifacts_dir: Path,
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
    backup_path = artifacts_dir / f"{version.version_id}-{case.case_id}-seed.json"
    backup_path.write_text(build_backup_json(case.seed_entries), encoding="utf-8")
    pulled_backup = artifacts_dir / f"{version.version_id}-{case.case_id}-pull.json"
    harness.start()
    try:
        harness.initialize_first_run()
        setup_details: dict[str, Any]
        if case.setup_kind == "backup":
            push_result = harness.push_backup(backup_path)
            setup_details = {"setup_kind": "backup", "push_stdout": push_result.stdout.strip()}
        elif case.setup_kind == "mutated_second_len_plus1":
            raw_path = artifacts_dir / f"{version.version_id}-{case.case_id}-seed.bin"
            export_seed_raw(cli_bin, root_dir, backup_path, raw_path)
            mutated_raw = mutate_second_len_plus1(raw_path.read_bytes())
            harness.load_raw_metadata(mutated_raw)
            setup_details = {"setup_kind": "mutated_second_len_plus1", "raw_size": len(mutated_raw)}
        else:
            raise HarnessError(f"Unknown setup kind {case.setup_kind!r}")

        action_details = execute_case_action(harness, case)
        final_pull = harness.pull_backup(pulled_backup)
        return {
            "version_id": version.version_id,
            "git_ref": version.git_ref,
            "case_id": case.case_id,
            "status": "ok",
            "note": case.note,
            "setup": setup_details,
            "action_result": action_details,
            "final_pull_stdout": final_pull.stdout.strip(),
            "pull_summary": pull_summary(pulled_backup),
        }
    except Exception as error:
        crash = "crashed with signal" in harness.log_tail(lines=80).lower()
        return {
            "version_id": version.version_id,
            "git_ref": version.git_ref,
            "case_id": case.case_id,
            "status": "failed",
            "note": case.note,
            "error": str(error),
            "screen_text": safe_screen_text(harness),
            "speculos_crash": crash,
            "speculos_log_tail": harness.log_tail(),
            "seed_backup": backup_path.read_text(encoding="utf-8"),
        }
    finally:
        harness.stop()
        cleanup_speculos_containers()


def compute_divergences(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_case: dict[str, list[dict[str, Any]]] = {}
    for result in results:
        by_case.setdefault(result["case_id"], []).append(result)
    divergences: list[dict[str, Any]] = []
    for case_id, case_results in sorted(by_case.items()):
        outcomes = {
            result["version_id"]: classify_result(
                result["status"],
                result.get("error", ""),
                bool(result.get("speculos_crash", False)),
            )
            for result in case_results
        }
        if len(set(outcomes.values())) > 1:
            divergences.append({"case_id": case_id, "outcomes": outcomes})
    return divergences


def parse_args() -> argparse.Namespace:
    root_dir = default_root_dir()
    parser = argparse.ArgumentParser(description="FZ-07 differential fuzzing across app-passwords versions")
    parser.add_argument("--cli", default=str(default_cli_bin(root_dir)), help="Path to ledger-pw CLI")
    parser.add_argument("--server", default="127.0.0.1", help="Speculos API/APDU host")
    parser.add_argument(
        "--apdu-port",
        type=int,
        default=0,
        help="Speculos APDU TCP port. Default: choose a free port per run.",
    )
    parser.add_argument(
        "--api-port",
        type=int,
        default=0,
        help="Speculos REST API port. Default: choose a free port per run.",
    )
    parser.add_argument("--display", default="headless", help="Speculos display mode")
    parser.add_argument(
        "--versions",
        default="",
        help="Comma-separated version ids. Empty means nanos_plus_1_3_0,nanos_plus_1_3_1,master.",
    )
    parser.add_argument(
        "--cases",
        default="",
        help="Comma-separated case ids. Empty means the full curated set.",
    )
    parser.add_argument(
        "--artifacts-dir",
        default="",
        help="Directory to keep generated artifacts. Default: temp dir.",
    )
    parser.add_argument(
        "--image",
        default="ghcr.io/ledgerhq/ledger-app-builder/ledger-app-dev-tools:latest",
        help="Docker image used to build app-passwords.",
    )
    parser.add_argument(
        "--reuse-builds",
        action="store_true",
        help="Reuse existing checkouts/builds with --no-update instead of fetching refs again.",
    )
    parser.add_argument("--json-out", default="", help="Optional path for the JSON report.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root_dir = default_root_dir()
    cli_bin = Path(args.cli).resolve()
    ensure_cli_exists(cli_bin)

    selected_version_ids = {item.strip() for item in args.versions.split(",") if item.strip()}
    selected_versions = tuple(
        spec for spec in VERSION_SPECS if not selected_version_ids or spec.version_id in selected_version_ids
    )
    if not selected_versions:
        raise SystemExit("No versions selected")

    selected_case_ids = {item.strip() for item in args.cases.split(",") if item.strip()}
    selected_cases = tuple(case for case in CASES if not selected_case_ids or case.case_id in selected_case_ids)
    if not selected_cases:
        raise SystemExit("No differential cases selected")

    artifacts_dir_obj: tempfile.TemporaryDirectory[str] | None = None
    if args.artifacts_dir:
        artifacts_dir = Path(args.artifacts_dir).resolve()
        artifacts_dir.mkdir(parents=True, exist_ok=True)
    else:
        artifacts_dir_obj = tempfile.TemporaryDirectory(prefix="ledger-pw-fz07-")
        artifacts_dir = Path(artifacts_dir_obj.name)

    build_results: list[dict[str, Any]] = []
    built_apps: dict[str, Path] = {}
    results: list[dict[str, Any]] = []
    try:
        for spec in selected_versions:
            try:
                app_path = build_app_elf(
                    root_dir=root_dir,
                    spec=spec,
                    image=args.image,
                    rebuild=not args.reuse_builds,
                )
                built_apps[spec.version_id] = app_path
                build_results.append(
                    {
                        "version_id": spec.version_id,
                        "git_ref": spec.git_ref,
                        "status": "ok",
                        "app_path": str(app_path),
                    },
                )
            except Exception as error:
                build_results.append(
                    {
                        "version_id": spec.version_id,
                        "git_ref": spec.git_ref,
                        "status": "failed",
                        "error": str(error),
                    },
                )

        for spec in selected_versions:
            app_path = built_apps.get(spec.version_id)
            if app_path is None:
                continue
            for case in selected_cases:
                apdu_port = args.apdu_port or find_free_port(args.server)
                api_port = args.api_port or find_free_port(args.server)
                results.append(
                    run_case_for_version(
                        version=spec,
                        app_path=app_path,
                        case=case,
                        root_dir=root_dir,
                        cli_bin=cli_bin,
                        artifacts_dir=artifacts_dir,
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
        "fuzzer": "FZ-07",
        "versions": [spec.version_id for spec in selected_versions],
        "cases": [case.case_id for case in selected_cases],
        "artifacts_dir": str(artifacts_dir),
        "builds": build_results,
        "results": results,
        "failures": failures,
        "divergences": compute_divergences(results),
    }

    if args.json_out:
        Path(args.json_out).write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")

    print_json(report)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
