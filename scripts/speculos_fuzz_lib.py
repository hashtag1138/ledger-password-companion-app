#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import socket
import signal
import subprocess
import sys
import tempfile
import time
import http.client
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


class HarnessError(RuntimeError):
    pass


@dataclass(frozen=True)
class CliResult:
    args: list[str]
    stdout: str
    stderr: str
    returncode: int


class SpeculosHarness:
    def __init__(
        self,
        root_dir: Path,
        app_path: Path,
        cli_bin: Path,
        server: str = "127.0.0.1",
        apdu_port: int = 10100,
        api_port: int = 5100,
        display: str = "headless",
        model: str = "nanosp",
        wait_seconds: float = 20.0,
        state_dir: Path | None = None,
        load_nvram: bool = False,
        save_nvram: bool = False,
    ) -> None:
        self.root_dir = root_dir
        self.app_path = app_path
        self.cli_bin = cli_bin
        self.server = server
        self.apdu_port = apdu_port
        self.api_port = api_port
        self.display = display
        self.model = model
        self.wait_seconds = wait_seconds
        self.state_dir = state_dir
        self.load_nvram = load_nvram
        self.save_nvram = save_nvram
        self.process: subprocess.Popen[str] | None = None
        self.log_path: Path | None = None

    def start(self) -> None:
        if self.process is not None:
            raise HarnessError("Speculos is already running")
        if self.state_dir is not None:
            self.state_dir.mkdir(parents=True, exist_ok=True)
        log_fd, log_name = tempfile.mkstemp(prefix="ledger-pw-speculos-", suffix=".log")
        os.close(log_fd)
        self.log_path = Path(log_name)
        with self.log_path.open("w", encoding="utf-8") as log_file:
            command = [
                str(self.root_dir / "scripts" / "run-speculos-passwords.sh"),
                str(self.app_path),
                "--api-port",
                str(self.api_port),
                "--apdu-port",
                str(self.apdu_port),
                "--display",
                self.display,
                "--model",
                self.model,
            ]
            if self.state_dir is not None:
                command.extend(["--state-dir", str(self.state_dir)])
            if self.load_nvram or self.save_nvram:
                command.append("--")
                if self.load_nvram:
                    command.append("--load-nvram")
                if self.save_nvram:
                    command.append("--save-nvram")
            self.process = subprocess.Popen(
                command,
                cwd=self.root_dir,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                text=True,
                start_new_session=True,
            )
        self._wait_for_port(self.server, self.apdu_port, self.wait_seconds)
        self._wait_for_port(self.server, self.api_port, self.wait_seconds)
        self._wait_for_api_ready()
        self.delete_events()

    def stop(self) -> None:
        if self.process is None:
            return
        if self.process.poll() is None:
            try:
                os.killpg(self.process.pid, signal.SIGTERM)
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGKILL)
                self.process.wait(timeout=5)
        self.process = None
        try:
            self._wait_for_port_closed(self.server, self.apdu_port, timeout_seconds=10.0)
            self._wait_for_port_closed(self.server, self.api_port, timeout_seconds=10.0)
        except HarnessError:
            pass

    def ensure_running(self) -> None:
        if self.process is None:
            raise HarnessError("Speculos process not started")
        if self.process.poll() is not None:
            raise HarnessError(f"Speculos exited with code {self.process.returncode}")

    def initialize_first_run(self) -> None:
        for _ in range(4):
            self.press_button("right")
            time.sleep(0.1)
        self.press_button("both")
        time.sleep(0.2)
        self.press_button("both")
        time.sleep(0.2)
        self.delete_events()

    def current_screen_text(self) -> str:
        body = self._http_get_json(f"http://{self.server}:{self.api_port}/events?currentscreenonly=true")
        texts = [event.get("text", "") for event in body.get("events", []) if event.get("text", "")]
        return " | ".join(texts)

    def delete_events(self) -> None:
        self._http_delete(f"http://{self.server}:{self.api_port}/events")

    def press_button(self, button: str) -> None:
        self._http_post_json(
            f"http://{self.server}:{self.api_port}/button/{button}",
            {"action": "press-and-release"},
        )
        time.sleep(0.2)

    def wait_for_screen_text(
        self,
        *,
        contains: str | None = None,
        excludes: Iterable[str] = (),
        timeout_seconds: float = 5.0,
    ) -> str:
        deadline = time.time() + timeout_seconds
        last_text = ""
        while time.time() < deadline:
            self.ensure_running()
            text = self.current_screen_text()
            last_text = text
            if contains is not None and contains not in text:
                time.sleep(0.1)
                continue
            if any(excluded in text for excluded in excludes):
                time.sleep(0.1)
                continue
            if text:
                return text
            time.sleep(0.1)
        raise HarnessError(
            f"Timed out waiting for screen contains={contains!r} excludes={list(excludes)!r}; last screen={last_text!r}",
        )

    def run_cli(self, args: list[str], *, auto_approve: bool = True, timeout_seconds: float = 20.0) -> CliResult:
        self.ensure_running()
        auto_approver = self._start_auto_approver() if auto_approve else None
        process = subprocess.Popen(
            [str(self.cli_bin), *args],
            cwd=self.root_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        try:
            deadline = time.time() + timeout_seconds
            while process.poll() is None:
                self.ensure_running()
                if time.time() >= deadline:
                    process.kill()
                    stdout, stderr = process.communicate(timeout=2)
                    raise HarnessError(
                        f"CLI timed out for args={args!r}; stdout={stdout!r}; stderr={stderr!r}; screen={self.current_screen_text()!r}",
                    )
                time.sleep(0.15)

            stdout, stderr = process.communicate(timeout=2)
            result = CliResult(args=args, stdout=stdout, stderr=stderr, returncode=process.returncode)
            if result.returncode != 0:
                raise HarnessError(
                    f"CLI failed for args={args!r}; stdout={stdout!r}; stderr={stderr!r}; screen={self.current_screen_text()!r}",
                )
            return result
        finally:
            self._stop_process(auto_approver)

    def push_backup(self, backup_path: Path) -> CliResult:
        return self.run_cli(
            ["device", "push", str(backup_path), "--server", self.server, "--port", str(self.apdu_port)],
            auto_approve=True,
            timeout_seconds=30.0,
        )

    def pull_backup(self, backup_path: Path) -> CliResult:
        return self.run_cli(
            ["device", "pull", "--out", str(backup_path), "--server", self.server, "--port", str(self.apdu_port)],
            auto_approve=True,
            timeout_seconds=30.0,
        )

    def verify_backup(self, backup_path: Path) -> CliResult:
        return self.run_cli(
            ["device", "verify", str(backup_path), "--server", self.server, "--port", str(self.apdu_port)],
            auto_approve=True,
            timeout_seconds=30.0,
        )

    def diff_backup(self, backup_path: Path) -> CliResult:
        return self.run_cli(
            ["device", "diff", str(backup_path), "--server", self.server, "--port", str(self.apdu_port)],
            auto_approve=True,
            timeout_seconds=30.0,
        )

    def load_raw_metadata(self, raw: bytes) -> None:
        if not raw:
            raise HarnessError("Raw metadata must not be empty")
        auto_approver = self._start_auto_approver()
        try:
            with socket.create_connection((self.server, self.apdu_port), timeout=5) as sock:
                sock.settimeout(10)
                offset = 0
                while offset < len(raw):
                    end = min(offset + 0xFF, len(raw))
                    chunk = raw[offset:end]
                    p1 = 0xFF if end == len(raw) else 0x00
                    response_data, status_word = self._exchange_apdu(sock, 0xE0, 0x05, p1, 0x00, chunk)
                    if status_word != 0x9000:
                        raise HarnessError(
                            f"LOAD_METADATAS failed at offset {offset}: sw=0x{status_word:04x} data={response_data.hex()}",
                        )
                    offset = end
        finally:
            self._stop_process(auto_approver)

    def exchange_apdu(
        self,
        cla: int,
        ins: int,
        p1: int = 0x00,
        p2: int = 0x00,
        data: bytes = b"",
        *,
        timeout_seconds: float = 10.0,
    ) -> tuple[bytes, int]:
        return self.exchange_apdu_sequence(
            [(cla, ins, p1, p2, data)],
            auto_approve=False,
            timeout_seconds=timeout_seconds,
        )[0]

    def exchange_apdu_sequence(
        self,
        steps: Iterable[tuple[int, int, int, int, bytes]],
        *,
        auto_approve: bool = False,
        timeout_seconds: float = 10.0,
    ) -> list[tuple[bytes, int]]:
        self.ensure_running()
        auto_approver = self._start_auto_approver() if auto_approve else None
        try:
            with socket.create_connection((self.server, self.apdu_port), timeout=5) as sock:
                sock.settimeout(timeout_seconds)
                outputs: list[tuple[bytes, int]] = []
                for cla, ins, p1, p2, data in steps:
                    outputs.append(self._exchange_apdu(sock, cla, ins, p1, p2, data))
                return outputs
        finally:
            self._stop_process(auto_approver)

    def generate_password_test(self, charset_mask: int, seed: str) -> str:
        if charset_mask < 0x00 or charset_mask > 0xFF:
            raise HarnessError(f"Charset mask out of range: 0x{charset_mask:02x}")
        payload = bytes((charset_mask,)) + seed.encode("utf-8")
        with socket.create_connection((self.server, self.apdu_port), timeout=5) as sock:
            sock.settimeout(10)
            response_data, status_word = self._exchange_apdu(sock, 0xE0, 0x99, 0x01, 0x00, payload)
        if status_word != 0x9000:
            raise HarnessError(
                f"GENERATE_PASSWORD test failed for mask=0x{charset_mask:02x} seed={seed!r}: "
                f"sw=0x{status_word:04x} data={response_data.hex()}",
            )
        try:
            return response_data.decode("ascii")
        except UnicodeDecodeError as error:
            raise HarnessError(
                f"GENERATE_PASSWORD returned non-ascii output for mask=0x{charset_mask:02x} seed={seed!r}: {response_data.hex()}",
            ) from error

    def home_to_menu(self) -> None:
        deadline = time.time() + 5.0
        last_text = ""
        while time.time() < deadline:
            text = self.current_screen_text()
            last_text = text
            if "Which action?" in text:
                return
            if "Tap to manage" in text:
                self.press_button("both")
                time.sleep(0.3)
                continue
            if "Manage passwords" in text:
                self.press_button("right")
                time.sleep(0.3)
                self.press_button("both")
                self.wait_for_screen_text(contains="Which action?", timeout_seconds=3.0)
                return
            if "PASSWORD HAS" in text:
                self.press_button("both")
                time.sleep(0.3)
                continue
            if text.strip() == "Back":
                self.press_button("both")
                time.sleep(0.3)
                continue
            if (
                text
                and "Passwords list" not in text
                and "Which action?" not in text
                and "Manage passwords" not in text
                and "Tap to manage" not in text
                and "Host keyboard" not in text
            ):
                # Password display screens expose a "Back" action on the right button.
                self.press_button("right")
                time.sleep(0.3)
                continue
            time.sleep(0.2)
        raise HarnessError(f"Timed out waiting to reach action menu; last screen={last_text!r}")

    def menu_select(self, index: int) -> None:
        for _ in range(index):
            self.press_button("right")
        self.press_button("both")

    def list_choose(self, position: int) -> None:
        for _ in range(position - 1):
            self.press_button("right")
        self.press_button("both")

    def confirm_yes(self) -> None:
        self.press_button("right")
        self.press_button("both")

    def show_password(self, position: int = 1) -> str:
        self.home_to_menu()
        self.menu_select(2)
        self.wait_for_screen_text(contains="Passwords list", timeout_seconds=3.0)
        before = self.current_screen_text()
        self.list_choose(position)
        after = self.wait_for_screen_text(
            excludes=("Passwords list", "Which action?", "Manage passwords", "Tap to manage", "Host keyboard"),
            timeout_seconds=3.0,
        )
        if after == before:
            raise HarnessError(f"Show password did not leave the list screen: {after!r}")
        return after

    def show_password_list(self) -> str:
        self.home_to_menu()
        self.menu_select(2)
        return self.wait_for_screen_text(contains="Passwords list", timeout_seconds=3.0)

    def type_password(self, position: int = 1) -> str:
        self.home_to_menu()
        self.menu_select(1)
        self.wait_for_screen_text(contains="Passwords list", timeout_seconds=3.0)
        self.list_choose(position)
        return self.wait_for_screen_text(contains="PASSWORD HAS", timeout_seconds=3.0)

    def delete_password(self, position: int = 1) -> str:
        self.home_to_menu()
        self.menu_select(3)
        self.wait_for_screen_text(contains="Passwords list", timeout_seconds=3.0)
        self.list_choose(position)
        self.confirm_yes()
        return self.wait_for_screen_text(contains="PASSWORD HAS", timeout_seconds=3.0)

    def show_first_password(self) -> str:
        return self.show_password(position=1)

    def type_first_password(self) -> str:
        return self.type_password(position=1)

    def delete_first_password(self) -> str:
        return self.delete_password(position=1)

    def log_tail(self, lines: int = 120) -> str:
        if self.log_path is None or not self.log_path.exists():
            return ""
        content = self.log_path.read_text(encoding="utf-8", errors="replace").splitlines()
        return "\n".join(content[-lines:])

    def _start_auto_approver(self) -> subprocess.Popen[str]:
        return subprocess.Popen(
            [
                str(self.root_dir / "scripts" / "speculos-auto-approve.sh"),
                "--server",
                self.server,
                "--api-port",
                str(self.api_port),
            ],
            cwd=self.root_dir,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            text=True,
            start_new_session=True,
        )

    def _stop_process(self, process: subprocess.Popen[str] | None) -> None:
        if process is None or process.poll() is not None:
            return
        try:
            os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=3)

    def _wait_for_port(self, host: str, port: int, timeout_seconds: float) -> None:
        deadline = time.time() + timeout_seconds
        while time.time() < deadline:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
                sock.settimeout(0.5)
                if sock.connect_ex((host, port)) == 0:
                    return
            time.sleep(0.1)
        raise HarnessError(f"Timed out waiting for {host}:{port}")

    def _wait_for_port_closed(self, host: str, port: int, timeout_seconds: float) -> None:
        deadline = time.time() + timeout_seconds
        while time.time() < deadline:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
                sock.settimeout(0.5)
                if sock.connect_ex((host, port)) != 0:
                    return
            time.sleep(0.1)
        raise HarnessError(f"Timed out waiting for {host}:{port} to close")

    def _wait_for_api_ready(self) -> None:
        deadline = time.time() + self.wait_seconds
        last_error = ""
        while time.time() < deadline:
            self.ensure_running()
            try:
                self._http_get_json(f"http://{self.server}:{self.api_port}/events?currentscreenonly=true")
                return
            except (urllib.error.URLError, ConnectionResetError, http.client.HTTPException, json.JSONDecodeError) as error:
                last_error = str(error)
                time.sleep(0.1)
        raise HarnessError(f"Timed out waiting for Speculos API readiness: {last_error}")

    def _http_get_json(self, url: str) -> dict:
        request = urllib.request.Request(url)
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.load(response)

    def _http_delete(self, url: str) -> None:
        try:
            request = urllib.request.Request(url, method="DELETE")
            with urllib.request.urlopen(request, timeout=5) as response:
                response.read()
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise

    def _http_post_json(self, url: str, payload: dict[str, str]) -> None:
        body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=5) as response:
            response.read()

    def _exchange_apdu(
        self,
        sock: socket.socket,
        cla: int,
        ins: int,
        p1: int,
        p2: int,
        data: bytes,
    ) -> tuple[bytes, int]:
        if len(data) > 0xFF:
            raise HarnessError(f"APDU payload too large: {len(data)} bytes")
        apdu = bytes((cla, ins, p1, p2, len(data))) + data
        sock.sendall(len(apdu).to_bytes(4, byteorder="big"))
        sock.sendall(apdu)
        response_length = int.from_bytes(self._socket_read_exactly(sock, 4), byteorder="big")
        response_data = self._socket_read_exactly(sock, response_length)
        status_word = int.from_bytes(self._socket_read_exactly(sock, 2), byteorder="big")
        return response_data, status_word

    def _socket_read_exactly(self, sock: socket.socket, length: int) -> bytes:
        output = bytearray()
        while len(output) < length:
            chunk = sock.recv(length - len(output))
            if not chunk:
                raise HarnessError(f"Unexpected end of APDU stream after {len(output)} bytes, expected {length}")
            output.extend(chunk)
        return bytes(output)


def default_root_dir() -> Path:
    return Path(__file__).resolve().parent.parent


def default_cli_bin(root_dir: Path) -> Path:
    return root_dir / "cli" / "build" / "install" / "ledger-pw" / "bin" / "ledger-pw"


def default_app_path(root_dir: Path) -> Path:
    return root_dir / "build" / "speculos" / "app-passwords" / "bin" / "app.elf"


def ensure_cli_exists(cli_bin: Path) -> None:
    if cli_bin.exists():
        return
    raise HarnessError(f"CLI binary not found: {cli_bin}. Run ./gradlew :cli:installDist first.")


def ensure_app_exists(app_path: Path) -> None:
    if app_path.exists():
        return
    raise HarnessError(
        f"Speculos app ELF not found: {app_path}. Run scripts/build-passwords-app.sh first.",
    )


def print_json(data: dict) -> None:
    json.dump(data, sys.stdout, indent=2, ensure_ascii=False)
    sys.stdout.write("\n")


def find_free_port(host: str = "127.0.0.1") -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind((host, 0))
        return int(sock.getsockname()[1])
