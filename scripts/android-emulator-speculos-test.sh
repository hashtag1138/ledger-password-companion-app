#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EMULATOR_SERIAL="${ANDROID_EMULATOR_SERIAL:-}"
SPECULOS_HOST="${SPECULOS_EMULATOR_HOST:-10.0.2.2}"
SPECULOS_API_SERVER="${SPECULOS_SERVER:-127.0.0.1}"
SPECULOS_API_PORT="${SPECULOS_API_PORT:-5000}"
SPECULOS_APDU_PORT="${SPECULOS_APDU_PORT:-10100}"
AUTO_APPROVE=1
FIRST_RUN=0
CLEAR_APP_DATA=1

usage() {
    cat <<'EOF'
Usage:
  scripts/android-emulator-speculos-test.sh [options]

Options:
  --serial SERIAL       Emulator serial. Default: first connected `emulator-*`.
  --speculos-host HOST  Host reached from the emulator. Default: 10.0.2.2.
  --api-server HOST     Host running the Speculos REST API. Default: 127.0.0.1.
  --api-port PORT       Speculos REST API port. Default: 5000.
  --apdu-port PORT      Speculos APDU TCP port. Default: 10100.
  --no-auto-approve     Do not start the Speculos auto-approver in background.
  --first-run           Dismiss the Passwords disclaimer and pick QWERTY once.
  --keep-app-data       Do not clear emulator app data before the test.
  --help                Show this help.

Prerequisite:
  Launch Speculos separately, for example:
    scripts/run-speculos-passwords.sh build/speculos/app-passwords/bin/app.elf \
      --api-port 5000 --apdu-port 10100 --display headless

Then this script runs the Android connected test against the emulator.
EOF
}

while (($# > 0)); do
    case "$1" in
        --serial)
            EMULATOR_SERIAL="${2:-}"
            shift 2
            ;;
        --speculos-host)
            SPECULOS_HOST="${2:-}"
            shift 2
            ;;
        --api-server)
            SPECULOS_API_SERVER="${2:-}"
            shift 2
            ;;
        --api-port)
            SPECULOS_API_PORT="${2:-}"
            shift 2
            ;;
        --apdu-port)
            SPECULOS_APDU_PORT="${2:-}"
            shift 2
            ;;
        --no-auto-approve)
            AUTO_APPROVE=0
            shift
            ;;
        --first-run)
            FIRST_RUN=1
            shift
            ;;
        --keep-app-data)
            CLEAR_APP_DATA=0
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

if [[ -z "$EMULATOR_SERIAL" ]]; then
    EMULATOR_SERIAL="$(adb devices | awk '/^emulator-/{print $1; exit}')"
fi

if [[ -z "$EMULATOR_SERIAL" ]]; then
    echo "No Android emulator detected." >&2
    exit 1
fi

adb -s "$EMULATOR_SERIAL" wait-for-device >/dev/null

AUTO_APPROVER_PID=""
cleanup() {
    if [[ -n "$AUTO_APPROVER_PID" ]]; then
        kill "$AUTO_APPROVER_PID" >/dev/null 2>&1 || true
        wait "$AUTO_APPROVER_PID" >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

if ((AUTO_APPROVE)); then
    APPROVER_ARGS=(
        --server "$SPECULOS_API_SERVER"
        --api-port "$SPECULOS_API_PORT"
    )
    if ((FIRST_RUN)); then
        APPROVER_ARGS+=(--first-run)
    fi
    "${ROOT_DIR}/scripts/speculos-auto-approve.sh" "${APPROVER_ARGS[@]}" &
    AUTO_APPROVER_PID=$!
fi

if ((CLEAR_APP_DATA)); then
    adb -s "$EMULATOR_SERIAL" shell pm clear com.ledgerpasswords.companion >/dev/null || true
fi

ANDROID_SERIAL="$EMULATOR_SERIAL" \
    "${ROOT_DIR}/gradlew" :android-app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.speculosHost="$SPECULOS_HOST" \
    -Pandroid.testInstrumentationRunnerArguments.speculosPort="$SPECULOS_APDU_PORT"
