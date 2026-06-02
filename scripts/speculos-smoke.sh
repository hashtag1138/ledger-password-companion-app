#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER="${SPECULOS_SERVER:-127.0.0.1}"
PORT="${SPECULOS_APDU_PORT:-9999}"
API_PORT="${SPECULOS_API_PORT:-5000}"
BACKUP_FILE="${ROOT_DIR}/test-fixtures/backup-example.json"
CLI_BIN="${ROOT_DIR}/cli/build/install/ledger-pw/bin/ledger-pw"
BUILD_CLI=1
WITH_PUSH=0
WAIT_TIMEOUT_SECONDS=15
AUTO_APPROVE=0
FIRST_RUN=0

usage() {
    cat <<'EOF'
Usage:
  scripts/speculos-smoke.sh [options]

Options:
  --server HOST       Speculos TCP host. Default: 127.0.0.1.
  --port PORT         Speculos TCP port. Default: 9999.
  --api-port PORT     Speculos REST API port. Default: 5000.
  --backup PATH       Backup JSON used for the optional push/verify round-trip.
  --cli PATH          Path to the ledger-pw CLI binary.
  --with-push         Run the write-path smoke test against Speculos.
  --auto-approve      Approve Passwords prompts through the Speculos REST API.
  --first-run         Dismiss the Passwords disclaimer and pick QWERTY once.
  --skip-build        Reuse the existing CLI installDist output.
  --wait SECONDS      Timeout waiting for Speculos TCP port. Default: 15.
  --help              Show this help.

Notes:
  - Speculos must already be running with the Passwords app loaded.
  - Approve prompts in the Speculos Web UI or console when the CLI asks for them.
    Use --auto-approve to let this script press `both` when a metadata prompt appears.
  - Use --first-run immediately after a fresh Speculos launch of app-passwords.
  - `--with-push` stays on the emulator only; it never touches a real Ledger.
EOF
}

while (($# > 0)); do
    case "$1" in
        --server)
            SERVER="${2:-}"
            shift 2
            ;;
        --port)
            PORT="${2:-}"
            shift 2
            ;;
        --api-port)
            API_PORT="${2:-}"
            shift 2
            ;;
        --backup)
            BACKUP_FILE="${2:-}"
            shift 2
            ;;
        --cli)
            CLI_BIN="${2:-}"
            shift 2
            ;;
        --with-push)
            WITH_PUSH=1
            shift
            ;;
        --auto-approve)
            AUTO_APPROVE=1
            shift
            ;;
        --first-run)
            FIRST_RUN=1
            shift
            ;;
        --skip-build)
            BUILD_CLI=0
            shift
            ;;
        --wait)
            WAIT_TIMEOUT_SECONDS="${2:-}"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

if [[ ! -f "$BACKUP_FILE" ]]; then
    echo "Backup JSON not found: $BACKUP_FILE" >&2
    exit 1
fi

wait_for_port() {
    local host="$1"
    local port="$2"
    local timeout_seconds="$3"
    local deadline=$((SECONDS + timeout_seconds))

    while ((SECONDS < deadline)); do
        if (echo >"/dev/tcp/${host}/${port}") >/dev/null 2>&1; then
            return 0
        fi
        sleep 0.2
    done
    return 1
}

http_post_json() {
    local url="$1"
    local payload="$2"
    python3 - "$url" "$payload" <<'PY'
import json
import time
import sys
import urllib.request

url = sys.argv[1]
payload = sys.argv[2].encode("utf-8")
request = urllib.request.Request(
    url,
    data=payload,
    headers={"Content-Type": "application/json"},
)
deadline = time.time() + 10
while True:
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            response.read()
        break
    except Exception:
        if time.time() >= deadline:
            raise
        time.sleep(0.2)
PY
}

http_delete() {
    local url="$1"
    python3 - "$url" <<'PY'
import sys
import urllib.request

request = urllib.request.Request(sys.argv[1], method="DELETE")
with urllib.request.urlopen(request, timeout=5) as response:
    response.read()
PY
}

press_button() {
    local button="$1"
    http_post_json "http://${SERVER}:${API_PORT}/button/${button}" '{"action":"press-and-release"}'
}

current_screen_text() {
    python3 - "$SERVER" "$API_PORT" <<'PY'
import json
import sys
import urllib.request

server = sys.argv[1]
api_port = sys.argv[2]
with urllib.request.urlopen(
    f"http://{server}:{api_port}/events?currentscreenonly=true",
    timeout=5,
) as response:
    body = json.load(response)
texts = [event.get("text", "") for event in body.get("events", []) if event.get("text", "")]
print(" ".join(texts))
PY
}

initialize_passwords_first_run() {
    echo "Dismissing the Passwords disclaimer and selecting QWERTY"
    for _ in 1 2 3 4; do
        press_button right
        sleep 0.1
    done
    press_button both
    sleep 0.2
    press_button both
    sleep 0.2
}

approve_metadata_prompt() {
    press_button right
    sleep 0.15
    press_button both
}

run_cli_command() {
    local expects_prompt="$1"
    shift

    if ((AUTO_APPROVE == 0 || expects_prompt == 0)); then
        "$CLI_BIN" "$@"
        return
    fi

    "$CLI_BIN" "$@" &
    local pid=$!
    local last_screen=""

    while kill -0 "$pid" >/dev/null 2>&1; do
        local text=""
        text="$(current_screen_text 2>/dev/null || true)"
        if [[ -n "$text" && "$text" != "$last_screen" ]]; then
            case "$text" in
                *"Transfer metadatas"*|*"Overwrite metadatas"*)
                    press_button right
                    ;;
                *"Approve"*)
                    press_button both
                    ;;
            esac
            last_screen="$text"
        fi
        sleep 0.2
    done

    wait "$pid"
}

if ((BUILD_CLI)); then
    echo "Building the CLI installDist output"
    "${ROOT_DIR}/gradlew" :cli:installDist
fi

if [[ ! -x "$CLI_BIN" ]]; then
    echo "CLI binary not found or not executable: $CLI_BIN" >&2
    exit 1
fi

echo "Waiting for Speculos on ${SERVER}:${PORT}"
if ! wait_for_port "$SERVER" "$PORT" "$WAIT_TIMEOUT_SECONDS"; then
    echo "Timed out waiting for Speculos on ${SERVER}:${PORT}" >&2
    exit 1
fi

if ((AUTO_APPROVE || FIRST_RUN)); then
    echo "Waiting for Speculos API on ${SERVER}:${API_PORT}"
    if ! wait_for_port "$SERVER" "$API_PORT" "$WAIT_TIMEOUT_SECONDS"; then
        echo "Timed out waiting for Speculos API on ${SERVER}:${API_PORT}" >&2
        exit 1
    fi
fi

if ((FIRST_RUN)); then
    initialize_passwords_first_run
fi

http_delete "http://${SERVER}:${API_PORT}/events" >/dev/null 2>&1 || true

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ledger-pw-speculos.XXXXXX")"
PULLED_FILE="${TMP_DIR}/device-backup.json"

echo "Read-only smoke test"
run_cli_command 0 device info --server "$SERVER" --port "$PORT"
run_cli_command 1 device pull --out "$PULLED_FILE" --server "$SERVER" --port "$PORT"
run_cli_command 1 device verify "$PULLED_FILE" --server "$SERVER" --port "$PORT"
echo "Pulled device state saved to $PULLED_FILE"

if ((WITH_PUSH)); then
    echo "Write-path smoke test"
    run_cli_command 1 device push "$BACKUP_FILE" --server "$SERVER" --port "$PORT"
    run_cli_command 1 device verify "$BACKUP_FILE" --server "$SERVER" --port "$PORT"
    run_cli_command 1 device diff "$BACKUP_FILE" --server "$SERVER" --port "$PORT"
fi
