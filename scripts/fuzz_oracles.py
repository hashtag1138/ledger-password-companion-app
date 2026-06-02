#!/usr/bin/env python3
from __future__ import annotations

from typing import Any, Iterable

from speculos_fuzz_lib import SpeculosHarness


PASSWORD_POLLUTION_SENTINELS = ("password1", "password2", "password3")
TRANSFER_PROMPT_TOKENS = ("Transfer metadatas", "Overwrite metadatas", "Approve")


def safe_screen_text(harness: SpeculosHarness) -> str:
    try:
        return harness.current_screen_text()
    except Exception:
        return ""


def safe_log_tail(harness: SpeculosHarness | None, lines: int = 160) -> str:
    if harness is None:
        return ""
    try:
        return harness.log_tail(lines=lines)
    except Exception:
        return ""


def parse_selected_nickname(screen_text: str) -> str:
    return screen_text.split(" | ")[0].strip() if screen_text else ""


def classify_oracle_evidence(
    *,
    error_text: str = "",
    screen_text: str = "",
    log_tail: str = "",
    expected_names: Iterable[str] | None = None,
    actual_names: Iterable[str] | None = None,
    expected_selected_nickname: str | None = None,
    actual_selected_nickname: str | None = None,
    expected_screen_substrings: Iterable[str] = (),
) -> dict[str, Any]:
    error_lower = error_text.lower()
    screen = screen_text or ""
    log_lower = log_tail.lower()
    actual_names_list = list(actual_names) if actual_names is not None else None
    expected_names_list = list(expected_names) if expected_names is not None else None

    flags = {
        "speculos_crash": "crashed with signal" in log_lower or "app crashed" in log_lower,
        "timeout": "timed out" in error_lower or "timeout" in error_lower,
        "transport_closed": "remote end closed connection" in error_lower or "unexpected end of apdu stream" in error_lower,
        "stuck_transfer_prompt": any(token in screen for token in TRANSFER_PROMPT_TOKENS),
        "empty_screen": not screen.strip(),
        "home_screen_visible": "Manage passwords" in screen and "on your device" in screen,
        "unexpected_screen": False,
        "pulled_state_mismatch": False,
        "password_pollution": False,
        "embedded_nul_nickname": False,
        "unexpected_selected_nickname": False,
    }

    expected_screen_list = [token for token in expected_screen_substrings if token]
    if expected_screen_list:
        flags["unexpected_screen"] = not any(token in screen for token in expected_screen_list)

    if expected_names_list is not None and actual_names_list is not None:
        flags["pulled_state_mismatch"] = tuple(actual_names_list) != tuple(expected_names_list)

    if actual_names_list is not None:
        flags["password_pollution"] = any(name in PASSWORD_POLLUTION_SENTINELS for name in actual_names_list)
        flags["embedded_nul_nickname"] = any("\x00" in name for name in actual_names_list)

    if expected_selected_nickname is not None and actual_selected_nickname is not None:
        flags["unexpected_selected_nickname"] = actual_selected_nickname != expected_selected_nickname

    triggered = [name for name, enabled in flags.items() if enabled]
    return {
        "triggered": triggered,
        "flags": flags,
        "error_text": error_text,
        "screen_text": screen,
        "expected_names": expected_names_list,
        "actual_names": actual_names_list,
        "expected_selected_nickname": expected_selected_nickname,
        "actual_selected_nickname": actual_selected_nickname,
    }


def collect_oracle_evidence(
    *,
    harness: SpeculosHarness | None,
    error: Exception | str | None = None,
    screen_text: str | None = None,
    expected_names: Iterable[str] | None = None,
    actual_names: Iterable[str] | None = None,
    expected_selected_nickname: str | None = None,
    actual_selected_nickname: str | None = None,
    expected_screen_substrings: Iterable[str] = (),
    log_lines: int = 160,
) -> dict[str, Any]:
    actual_screen_text = screen_text if screen_text is not None else safe_screen_text(harness) if harness is not None else ""
    log_tail = safe_log_tail(harness, lines=log_lines)
    return classify_oracle_evidence(
        error_text=str(error) if error is not None else "",
        screen_text=actual_screen_text,
        log_tail=log_tail,
        expected_names=expected_names,
        actual_names=actual_names,
        expected_selected_nickname=expected_selected_nickname,
        actual_selected_nickname=actual_selected_nickname,
        expected_screen_substrings=expected_screen_substrings,
    ) | {"log_tail": log_tail}
