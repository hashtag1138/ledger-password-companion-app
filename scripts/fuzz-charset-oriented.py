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


CHARSET_BITS: tuple[tuple[int, str, str], ...] = (
    (0x01, "UPPERCASE", "ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
    (0x02, "LOWERCASE", "abcdefghijklmnopqrstuvwxyz"),
    (0x04, "NUMBERS", "0123456789"),
    (0x08, "MINUS", "-"),
    (0x10, "UNDERLINE", "_"),
    (0x20, "SPACE", " "),
    (0x40, "SPECIAL", "\"#$%&'*+,./:;=?!@\\^`|~"),
    (0x80, "BRACKETS", "[]{}()<>"),
)

REQUIRED_MIN_BITS = (0x01, 0x02, 0x04, 0x20)
PASSWORD_SIZE = 20
STORAGE_SIZE = 4096


@dataclass(frozen=True)
class DirectVector:
    mask: int
    seed: str
    expected: str | None = None
    note: str = ""


@dataclass(frozen=True)
class DirectBatchCase:
    case_id: str
    note: str
    vectors: tuple[DirectVector, ...]


@dataclass(frozen=True)
class RawUiCase:
    case_id: str
    note: str
    mask: int
    nickname: str
    expected_direct: str | None = None


DIRECT_BATCHES: tuple[DirectBatchCase, ...] = (
    DirectBatchCase(
        case_id="official_vectors_gmail_and_alias",
        note="Vecteurs officiels app-passwords pour gmail, avec alias 0x00 -> ALL_SETS",
        vectors=(
            DirectVector(0x00, "gmail", "*m8ZlP1|}O vzvJrQNT4", "0x00 doit être normalisé en ALL_SETS"),
            DirectVector(0x01, "gmail", "HMYDQUIOVKPCKJIHQJEN"),
            DirectVector(0x03, "gmail", "KqIJcPjhENivHvOdmuKQ"),
            DirectVector(0x07, "gmail", "xNX8IQO4vP0ucO41J6JW"),
            DirectVector(0x0F, "gmail", "w14JrbA9HNvWU1ON5MGP"),
            DirectVector(0x1F, "gmail", "vy4Joa86FKvVS1ON4KEP"),
            DirectVector(0x3F, "gmail", "kD83CP1UZO vQvJIuNx4"),
            DirectVector(0x7F, "gmail", "?u8htP1|DO v7vJzYNb4"),
            DirectVector(0xFF, "gmail", "*m8ZlP1|}O vzvJrQNT4"),
        ),
    ),
    DirectBatchCase(
        case_id="official_vectors_seed20_allsets",
        note="Vecteurs officiels ALL_SETS pour seeds de longueur 20",
        vectors=(
            DirectVector(0xFF, "aseedoflengthequal20", "29!uO;UPx UT8Hkmi- 5"),
            DirectVector(0xFF, "aSeedOfLengthEqual20", " $4,P.usI*C\\k1fv2;M;"),
        ),
    ),
    DirectBatchCase(
        case_id="single_bit_masks_and_rare_combo",
        note="Bits isolés et combo rare UPPERCASE+BRACKETS",
        vectors=(
            DirectVector(0x01, "gmail"),
            DirectVector(0x02, "gmail"),
            DirectVector(0x04, "gmail"),
            DirectVector(0x08, "gmail", "-" * PASSWORD_SIZE),
            DirectVector(0x10, "gmail", "_" * PASSWORD_SIZE),
            DirectVector(0x20, "gmail", " " * PASSWORD_SIZE),
            DirectVector(0x40, "gmail"),
            DirectVector(0x80, "gmail"),
            DirectVector(0x81, "gmail"),
        ),
    ),
)


RAW_UI_CASES: tuple[RawUiCase, ...] = (
    RawUiCase(
        case_id="raw_mask00_alias_allsets",
        note="Metadata raw avec charset 0x00, alias ALL_SETS côté génération",
        mask=0x00,
        nickname="gmail",
        expected_direct="*m8ZlP1|}O vzvJrQNT4",
    ),
    RawUiCase(
        case_id="raw_mask20_space_only",
        note="Metadata raw SPACE only",
        mask=0x20,
        nickname="gmail",
        expected_direct=" " * PASSWORD_SIZE,
    ),
    RawUiCase(
        case_id="raw_mask40_special_only",
        note="Metadata raw SPECIAL only",
        mask=0x40,
        nickname="gmail",
    ),
    RawUiCase(
        case_id="raw_mask80_brackets_only",
        note="Metadata raw BRACKETS only",
        mask=0x80,
        nickname="gmail",
    ),
    RawUiCase(
        case_id="raw_mask81_uppercase_brackets",
        note="Metadata raw combo rare UPPERCASE + BRACKETS",
        mask=0x81,
        nickname="gmail",
    ),
)


def allowed_chars_for_mask(mask: int) -> str:
    normalized = 0xFF if mask in (0x00, 0xFF) else mask
    chunks = [chars for bit, _, chars in CHARSET_BITS if normalized & bit]
    return "".join(chunks)


def expected_charset_names(mask: int) -> list[str]:
    if mask in (0x00, 0xFF):
        return ["ALL_SETS"]
    return [name for bit, name, _ in CHARSET_BITS if mask & bit]


def required_presence_charsets(mask: int) -> list[str]:
    normalized = 0xFF if mask in (0x00, 0xFF) else mask
    return [name for bit, name, _ in CHARSET_BITS if normalized & bit and bit in REQUIRED_MIN_BITS]


def assert_password_invariants(*, password: str, mask: int, seed: str) -> None:
    if len(password) != PASSWORD_SIZE:
        raise HarnessError(
            f"Generated password has wrong length for mask=0x{mask:02x} seed={seed!r}: {len(password)}",
        )
    allowed = allowed_chars_for_mask(mask)
    disallowed = sorted({char for char in password if char not in allowed})
    if disallowed:
        raise HarnessError(
            f"Generated password uses chars outside mask=0x{mask:02x} for seed={seed!r}: {disallowed!r} in {password!r}",
        )
    for bit, name, chars in CHARSET_BITS:
        if bit in REQUIRED_MIN_BITS and ((0xFF if mask in (0x00, 0xFF) else mask) & bit):
            if not any(char in chars for char in password):
                raise HarnessError(
                    f"Generated password misses required charset {name} for mask=0x{mask:02x} seed={seed!r}: {password!r}",
                )


def build_raw_metadata(mask: int, nickname: str) -> bytes:
    nickname_bytes = nickname.encode("utf-8")
    if len(nickname_bytes) > 19:
        raise HarnessError(f"Nickname too long for raw metadata: {nickname!r}")
    raw = bytearray(STORAGE_SIZE)
    raw[0] = len(nickname_bytes) + 1
    raw[1] = 0x00
    raw[2] = mask
    raw[3 : 3 + len(nickname_bytes)] = nickname_bytes
    raw[3 + len(nickname_bytes)] = 0x00
    raw[4 + len(nickname_bytes)] = 0x00
    return bytes(raw)


def pulled_summary(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    parsed = payload.get("parsed", [])
    raw_hex = payload.get("raw_metadatas")
    charset_byte = bytes.fromhex(raw_hex)[2] if raw_hex else None
    return {
        "parsed": parsed,
        "first_charset_byte": charset_byte,
        "raw_present": raw_hex is not None,
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
        subprocess.run([docker, "kill", *container_ids], capture_output=True, text=True, check=False)


def safe_screen_text(harness: SpeculosHarness) -> str:
    try:
        return harness.current_screen_text()
    except Exception:
        return ""


def run_direct_batch(
    *,
    case: DirectBatchCase,
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
    harness.start()
    try:
        harness.initialize_first_run()
        vector_results = []
        for vector in case.vectors:
            password = harness.generate_password_test(vector.mask, vector.seed)
            assert_password_invariants(password=password, mask=vector.mask, seed=vector.seed)
            if vector.expected is not None and password != vector.expected:
                raise HarnessError(
                    f"{case.case_id}: expected {vector.expected!r} for mask=0x{vector.mask:02x} seed={vector.seed!r}, got {password!r}",
                )
            vector_results.append(
                {
                    "mask": f"0x{vector.mask:02x}",
                    "seed": vector.seed,
                    "password": password,
                    "expected": vector.expected,
                    "required_charsets": required_presence_charsets(vector.mask),
                    "note": vector.note,
                },
            )
        return {
            "case_id": case.case_id,
            "status": "ok",
            "note": case.note,
            "vector_results": vector_results,
        }
    except Exception as error:
        return {
            "case_id": case.case_id,
            "status": "failed",
            "note": case.note,
            "error": str(error),
            "screen_text": safe_screen_text(harness),
            "speculos_log_tail": harness.log_tail(),
        }
    finally:
        harness.stop()


def run_raw_ui_case(
    *,
    case: RawUiCase,
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
    pulled_backup = artifacts_dir / f"{case.case_id}-pull.json"
    harness.start()
    try:
        harness.initialize_first_run()
        raw = build_raw_metadata(case.mask, case.nickname)
        harness.load_raw_metadata(raw)
        direct_password = harness.generate_password_test(case.mask, case.nickname)
        assert_password_invariants(password=direct_password, mask=case.mask, seed=case.nickname)
        if case.expected_direct is not None and direct_password != case.expected_direct:
            raise HarnessError(
                f"{case.case_id}: expected direct password {case.expected_direct!r}, got {direct_password!r}",
            )
        show_screen = harness.show_first_password()
        type_screen = harness.type_first_password()
        pull_result = harness.pull_backup(pulled_backup)
        summary = pulled_summary(pulled_backup)
        parsed = summary["parsed"]
        if len(parsed) != 1:
            raise HarnessError(f"{case.case_id}: expected 1 parsed entry after pull, got {len(parsed)}")
        actual_entry = parsed[0]
        if actual_entry.get("nickname") != case.nickname:
            raise HarnessError(
                f"{case.case_id}: nickname mismatch after pull, expected {case.nickname!r}, got {actual_entry.get('nickname')!r}",
            )
        actual_charsets = actual_entry.get("charsets", [])
        expected_charsets = expected_charset_names(case.mask)
        if actual_charsets != expected_charsets:
            raise HarnessError(
                f"{case.case_id}: parsed charsets mismatch, expected {expected_charsets!r}, got {actual_charsets!r}",
            )
        if summary["first_charset_byte"] != case.mask:
            raise HarnessError(
                f"{case.case_id}: raw charset byte mismatch, expected 0x{case.mask:02x}, "
                f"got 0x{summary['first_charset_byte']:02x}",
            )
        return {
            "case_id": case.case_id,
            "status": "ok",
            "note": case.note,
            "mask": f"0x{case.mask:02x}",
            "direct_password": direct_password,
            "show_screen": show_screen,
            "type_screen": type_screen,
            "pull_stdout": pull_result.stdout.strip(),
            "parsed_charsets": actual_charsets,
            "first_charset_byte": f"0x{summary['first_charset_byte']:02x}",
        }
    except Exception as error:
        return {
            "case_id": case.case_id,
            "status": "failed",
            "note": case.note,
            "error": str(error),
            "screen_text": safe_screen_text(harness),
            "speculos_log_tail": harness.log_tail(),
        }
    finally:
        harness.stop()


def parse_args() -> argparse.Namespace:
    root_dir = default_root_dir()
    parser = argparse.ArgumentParser(
        description="FZ-10 charset-oriented fuzzing against app-passwords via Speculos",
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
        help="Comma-separated case ids. Empty means all direct batches and raw UI cases.",
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

    all_cases = {case.case_id: case for case in DIRECT_BATCHES}
    all_cases.update({case.case_id: case for case in RAW_UI_CASES})
    if args.cases.strip():
        selected_ids = [case_id.strip() for case_id in args.cases.split(",") if case_id.strip()]
        missing = [case_id for case_id in selected_ids if case_id not in all_cases]
        if missing:
            raise HarnessError(f"Unknown FZ-10 case ids: {', '.join(missing)}")
    else:
        selected_ids = [case.case_id for case in DIRECT_BATCHES] + [case.case_id for case in RAW_UI_CASES]

    with tempfile.TemporaryDirectory(prefix="ledger-pw-fz10-") as tmp_dir:
        artifacts_dir = Path(tmp_dir)
        results = []
        failures = []
        for case_id in selected_ids:
            apdu_port = args.apdu_port or find_free_port(args.server)
            api_port = args.api_port or find_free_port(args.server)
            case = all_cases[case_id]
            if isinstance(case, DirectBatchCase):
                result = run_direct_batch(
                    case=case,
                    root_dir=root_dir,
                    app_path=app_path,
                    cli_bin=cli_bin,
                    server=args.server,
                    apdu_port=apdu_port,
                    api_port=api_port,
                    display=args.display,
                )
            else:
                result = run_raw_ui_case(
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
                failures.append(case_id)

        report = {
            "fuzzer": "FZ-10",
            "cases": selected_ids,
            "results": results,
            "failures": failures,
        }
        if args.json_out:
            Path(args.json_out).write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
        print_json(report)
        return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
