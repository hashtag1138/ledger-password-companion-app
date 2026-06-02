#!/usr/bin/env bash
set -euo pipefail

APP_PATH="${SPECULOS_APP_ELF:-}"
APP_NAME="${SPECULOS_APPNAME:-Passwords:0.0.0}"
MODEL="${SPECULOS_MODEL:-nanosp}"
DISPLAY_MODE="${SPECULOS_DISPLAY:-headless}"
API_PORT="${SPECULOS_API_PORT:-5000}"
APDU_PORT="${SPECULOS_APDU_PORT:-9999}"
VNC_PORT="${SPECULOS_VNC_PORT:-}"
BUTTON_PORT="${SPECULOS_BUTTON_PORT:-}"
SDK="${SPECULOS_SDK:-}"
SEED="${SPECULOS_SEED:-}"
STATE_DIR="${SPECULOS_STATE_DIR:-}"
RUNNER="auto"
IMAGE="${SPECULOS_IMAGE:-ghcr.io/ledgerhq/speculos}"
EXTRA_ARGS=()

usage() {
    cat <<'EOF'
Usage:
  scripts/run-speculos-passwords.sh --app /path/to/app.elf [options]
  scripts/run-speculos-passwords.sh /path/to/app.elf [options]

Options:
  --app PATH         Ledger app ELF to run in Speculos.
  --model MODEL      Speculos model. Default: nanosp.
  --display MODE     Speculos display mode. Default: headless.
  --api-port PORT    REST/Web UI port. Default: 5000.
  --apdu-port PORT   APDU TCP port. Default: 9999.
  --vnc-port PORT    Enable and publish the VNC port.
  --button-port PORT Enable and publish the button port.
  --sdk VERSION      Optional SDK version passed to Speculos.
  --seed WORDS       Optional Speculos seed.
  --state-dir PATH   Working directory used by Speculos for persisted files such
                     as NVRAM snapshots.
  --docker           Force Docker runner.
  --local            Force local `speculos` runner.
  --image IMAGE      Docker image. Default: ghcr.io/ledgerhq/speculos.
  --help             Show this help.

Notes:
  - The script sets SPECULOS_APPNAME to `Passwords:0.0.0` by default so
    `ledger-pw device info` returns the expected app name under Speculos.
  - In `headless` mode, open http://127.0.0.1:<api-port> for the Web UI.
  - Pass extra Speculos flags after `--`.

Examples:
  scripts/run-speculos-passwords.sh ~/src/app-passwords/build/nanos2/bin/app.elf
  scripts/run-speculos-passwords.sh --app ./app.elf --display text --sdk 1.0.3
  scripts/run-speculos-passwords.sh ./app.elf --docker -- --automation file:rules.json
EOF
}

while (($# > 0)); do
    case "$1" in
        --app)
            APP_PATH="${2:-}"
            shift 2
            ;;
        --model)
            MODEL="${2:-}"
            shift 2
            ;;
        --display)
            DISPLAY_MODE="${2:-}"
            shift 2
            ;;
        --api-port)
            API_PORT="${2:-}"
            shift 2
            ;;
        --apdu-port)
            APDU_PORT="${2:-}"
            shift 2
            ;;
        --vnc-port)
            VNC_PORT="${2:-}"
            shift 2
            ;;
        --button-port)
            BUTTON_PORT="${2:-}"
            shift 2
            ;;
        --sdk)
            SDK="${2:-}"
            shift 2
            ;;
        --seed)
            SEED="${2:-}"
            shift 2
            ;;
        --state-dir)
            STATE_DIR="${2:-}"
            shift 2
            ;;
        --docker)
            RUNNER="docker"
            shift
            ;;
        --local)
            RUNNER="local"
            shift
            ;;
        --image)
            IMAGE="${2:-}"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        --)
            shift
            EXTRA_ARGS+=("$@")
            break
            ;;
        -*)
            echo "Unknown option: $1" >&2
            echo "Use -- to pass raw Speculos flags through this wrapper." >&2
            exit 1
            ;;
        *)
            if [[ -z "$APP_PATH" ]]; then
                APP_PATH="$1"
                shift
            else
                echo "Unexpected positional argument: $1" >&2
                exit 1
            fi
            ;;
    esac
done

if [[ -z "$APP_PATH" ]]; then
    echo "Missing app.elf path." >&2
    usage >&2
    exit 1
fi

if [[ ! -f "$APP_PATH" ]]; then
    echo "App ELF not found: $APP_PATH" >&2
    exit 1
fi

APP_PATH="$(cd "$(dirname "$APP_PATH")" && pwd)/$(basename "$APP_PATH")"
APP_DIR="$(dirname "$APP_PATH")"
APP_BASENAME="$(basename "$APP_PATH")"

if [[ -n "$STATE_DIR" ]]; then
    mkdir -p "$STATE_DIR"
    STATE_DIR="$(cd "$STATE_DIR" && pwd)"
fi

speculos_binary() {
    if command -v speculos >/dev/null 2>&1; then
        echo "speculos"
        return 0
    fi
    if command -v speculos.py >/dev/null 2>&1; then
        echo "speculos.py"
        return 0
    fi
    return 1
}

build_speculos_args() {
    local -a args=(
        --model "$MODEL"
        --display "$DISPLAY_MODE"
        --api-port "$API_PORT"
        --apdu-port "$APDU_PORT"
    )
    if [[ -n "$VNC_PORT" ]]; then
        args+=(--vnc-port "$VNC_PORT")
    fi
    if [[ -n "$BUTTON_PORT" ]]; then
        args+=(--button-port "$BUTTON_PORT")
    fi
    if [[ -n "$SDK" ]]; then
        args+=(--sdk "$SDK")
    fi
    if [[ -n "$SEED" ]]; then
        args+=(--seed "$SEED")
    fi
    args+=("${EXTRA_ARGS[@]}")
    printf '%s\0' "${args[@]}"
}

readarray -d '' SPECULOS_ARGS < <(build_speculos_args)

run_local() {
    local binary
    binary="$(speculos_binary)"
    echo "Launching Speculos locally on APDU ${APDU_PORT} and API ${API_PORT}"
    echo "Speculos Web UI: http://127.0.0.1:${API_PORT}"
    if [[ -n "$STATE_DIR" ]]; then
        (
            cd "$STATE_DIR"
            SPECULOS_APPNAME="$APP_NAME" "$binary" "${SPECULOS_ARGS[@]}" "$APP_PATH"
        )
    else
        SPECULOS_APPNAME="$APP_NAME" "$binary" "${SPECULOS_ARGS[@]}" "$APP_PATH"
    fi
}

run_docker() {
    if ! command -v docker >/dev/null 2>&1; then
        echo "Docker is not installed." >&2
        exit 1
    fi

    local -a cmd=(
        docker run --rm
        -e "SPECULOS_APPNAME=$APP_NAME"
        -v "$APP_DIR:/speculos/apps:ro"
        -p "${API_PORT}:${API_PORT}"
        -p "${APDU_PORT}:${APDU_PORT}"
    )
    if [[ -t 0 && -t 1 ]]; then
        cmd+=(-it)
    fi
    if [[ -n "$VNC_PORT" ]]; then
        cmd+=(-p "${VNC_PORT}:${VNC_PORT}")
    fi
    if [[ -n "$BUTTON_PORT" ]]; then
        cmd+=(-p "${BUTTON_PORT}:${BUTTON_PORT}")
    fi
    if [[ -n "$STATE_DIR" ]]; then
        cmd+=(-v "${STATE_DIR}:/speculos/state:rw" --entrypoint /bin/sh)
    fi
    cmd+=("$IMAGE")
    if [[ -n "$STATE_DIR" ]]; then
        local -a speculos_cmd=(
            python /speculos/speculos.py
            "${SPECULOS_ARGS[@]}"
            "/speculos/apps/$APP_BASENAME"
        )
        local quoted_speculos_cmd=""
        printf -v quoted_speculos_cmd '%q ' "${speculos_cmd[@]}"
        cmd+=(-lc "cd /speculos/state && ${quoted_speculos_cmd}")
    else
        cmd+=("${SPECULOS_ARGS[@]}")
        cmd+=("/speculos/apps/$APP_BASENAME")
    fi

    echo "Launching Speculos in Docker on APDU ${APDU_PORT} and API ${API_PORT}"
    echo "Speculos Web UI: http://127.0.0.1:${API_PORT}"
    "${cmd[@]}"
}

case "$RUNNER" in
    local)
        if ! speculos_binary >/dev/null 2>&1; then
            echo "Local Speculos binary not found. Install it or rerun with --docker." >&2
            exit 1
        fi
        run_local
        ;;
    docker)
        run_docker
        ;;
    auto)
        if speculos_binary >/dev/null 2>&1; then
            run_local
        else
            run_docker
        fi
        ;;
    *)
        echo "Unknown runner: $RUNNER" >&2
        exit 1
        ;;
esac
