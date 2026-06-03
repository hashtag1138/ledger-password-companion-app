#!/usr/bin/env bash
set -euo pipefail

SERVER="${SPECULOS_SERVER:-127.0.0.1}"
API_PORT="${SPECULOS_API_PORT:-5000}"
WAIT_TIMEOUT_SECONDS=15
FIRST_RUN=0

usage() {
    cat <<'EOF'
Usage:
  scripts/speculos-auto-approve.sh [options]

Options:
  --server HOST     Speculos API host. Default: 127.0.0.1.
  --api-port PORT   Speculos API port. Default: 5000.
  --wait SECONDS    Timeout waiting for the API. Default: 15.
  --first-run       Dismiss the Passwords disclaimer and choose QWERTY once.
  --help            Show this help.

This script runs until interrupted and automatically approves Passwords prompts
through the Speculos REST API. Use it in the background while Android or CLI
tests talk to the Speculos APDU TCP port.
EOF
}

while (($# > 0)); do
    case "$1" in
        --server)
            SERVER="${2:-}"
            shift 2
            ;;
        --api-port)
            API_PORT="${2:-}"
            shift 2
            ;;
        --wait)
            WAIT_TIMEOUT_SECONDS="${2:-}"
            shift 2
            ;;
        --first-run)
            FIRST_RUN=1
            shift
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
import sys
import urllib.request

url = sys.argv[1]
payload = sys.argv[2].encode("utf-8")
request = urllib.request.Request(
    url,
    data=payload,
    headers={"Content-Type": "application/json"},
)
with urllib.request.urlopen(request, timeout=5) as response:
    response.read()
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

echo "Waiting for Speculos API on ${SERVER}:${API_PORT}"
if ! wait_for_port "$SERVER" "$API_PORT" "$WAIT_TIMEOUT_SECONDS"; then
    echo "Timed out waiting for Speculos API on ${SERVER}:${API_PORT}" >&2
    exit 1
fi

if ((FIRST_RUN)); then
    initialize_passwords_first_run
fi

http_delete "http://${SERVER}:${API_PORT}/events" >/dev/null 2>&1 || true

echo "Auto-approving Passwords prompts on ${SERVER}:${API_PORT}"
last_screen=""
while true; do
    text="$(current_screen_text 2>/dev/null || true)"
    if [[ -z "$text" ]]; then
        last_screen=""
    elif [[ "$text" != "$last_screen" ]]; then
        if [[ "$text" == *"Approve"* ]]; then
            press_button both
        elif [[ "$text" == *"Refuse"* || "$text" == *"Transfer metadatas"* || "$text" == *"Overwrite metadatas"* ]]; then
            press_button right
        fi
        last_screen="$text"
    fi
    sleep 0.2
done
