#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_URL="${APP_PASSWORDS_REPO:-https://github.com/LedgerHQ/app-passwords.git}"
REF="${APP_PASSWORDS_REF:-master}"
WORK_DIR="${APP_PASSWORDS_DIR:-${ROOT_DIR}/build/speculos/app-passwords}"
IMAGE="${LEDGER_APP_DEV_TOOLS_IMAGE:-ghcr.io/ledgerhq/ledger-app-builder/ledger-app-dev-tools:latest}"
UPDATE_REPO=1
PRINT_PATH_ONLY=0
ENABLE_POPULATE=1
MAKE_FLAGS=("TESTING=1")

usage() {
    cat <<'EOF'
Usage:
  scripts/build-passwords-app.sh [options]

Options:
  --dir PATH         Checkout/build directory. Default: build/speculos/app-passwords
  --ref REF          Git ref to checkout. Default: master.
  --repo URL         app-passwords Git repository URL.
  --image IMAGE      Docker image used for the build.
  --no-update        Reuse the existing checkout as-is.
  --no-populate      Build without the demo passwords preloaded in NVRAM.
  --print-path       Print the resulting app.elf path only.
  --help             Show this help.

Notes:
  - The build runs inside Ledger's official `ledger-app-dev-tools` Docker image.
  - By default the resulting Speculos-ready ELF is built with `TESTING=1 POPULATE=1`.
EOF
}

while (($# > 0)); do
    case "$1" in
        --dir)
            WORK_DIR="${2:-}"
            shift 2
            ;;
        --ref)
            REF="${2:-}"
            shift 2
            ;;
        --repo)
            REPO_URL="${2:-}"
            shift 2
            ;;
        --image)
            IMAGE="${2:-}"
            shift 2
            ;;
        --no-update)
            UPDATE_REPO=0
            shift
            ;;
        --no-populate)
            ENABLE_POPULATE=0
            shift
            ;;
        --print-path)
            PRINT_PATH_ONLY=1
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

if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is required to build app-passwords." >&2
    exit 1
fi

mkdir -p "$(dirname "$WORK_DIR")"

WORK_DIR="$(mkdir -p "$WORK_DIR" && cd "$WORK_DIR" && pwd)"

if [[ ! -d "$WORK_DIR/.git" ]]; then
    rm -rf "$WORK_DIR"
    git clone --depth=1 --branch "$REF" "$REPO_URL" "$WORK_DIR"
elif ((UPDATE_REPO)); then
    git -C "$WORK_DIR" fetch --depth=1 origin "$REF"
    git -C "$WORK_DIR" checkout --force FETCH_HEAD
fi

if ((ENABLE_POPULATE)); then
    MAKE_FLAGS+=("POPULATE=1")
fi

docker run --rm \
    --user "$(id -u):$(id -g)" \
    -v "$WORK_DIR:/app" \
    -w /app \
    "$IMAGE" \
    bash -lc "BOLOS_SDK=\$NANOSP_SDK make all ${MAKE_FLAGS[*]}"

APP_ELF="$WORK_DIR/bin/app.elf"
if [[ ! -f "$APP_ELF" ]]; then
    echo "Build completed but app.elf was not found at $APP_ELF" >&2
    exit 1
fi

if ((PRINT_PATH_ONLY)); then
    printf '%s\n' "$APP_ELF"
else
    echo "Built app-passwords test ELF:"
    echo "$APP_ELF"
fi
