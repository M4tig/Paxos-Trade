#!/usr/bin/env bash
# Build DidaTrade: activates the local dev environment and runs Maven.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$ROOT/src"
ENV_DIR="$ROOT/dev_env"
ENV_SCRIPT="$ENV_DIR/env.sh"
CONTRACT_DIR="$SRC_DIR/contract"

CLEAN="true"
SKIP_TESTS="false"
ARCH=""

usage() {
    cat <<EOF
Usage:
  $(basename "$0") [options]

  --skip-tests    Compile and package without running tests.
  --no-clean      Build incrementally instead of running 'clean' first.
  --arch ARCH     Contract pom to use: 'intel' (Linux) or 'arm' (macOS).
                  Detected from the current OS when omitted.
  -h, --help      Show this message.

Run this script from the repository root.
EOF
}

die() {
    echo "Error: $*" >&2
    exit 1
}

# Despite their names, the templates differ by OS rather than by CPU:
# intel-pom.xml builds protoc for linux-x86_64 and arm-pom.xml for osx-x86_64.
# Neither targets an ARM-native protoc, so pick the template by OS.
detect_arch() {
    local os machine
    os="$(uname -s)"
    machine="$(uname -m)"
    case "$os" in
        Linux) printf 'intel' ;;
        Darwin) printf 'arm' ;;
        *) die "no contract template for ${os}/${machine}; pass --arch intel|arm" ;;
    esac
}

# The contract pom is not tracked in git (see .gitignore), so it has to be
# copied from the architecture-specific template before the first build.
ensure_contract_pom() {
    local template="$CONTRACT_DIR/${ARCH}-pom.xml"

    [[ -f "$template" ]] || die "missing contract template: ${template}"

    if [[ -f "$CONTRACT_DIR/pom.xml" ]]; then
        echo "Contract pom already present, leaving it untouched."
        echo "Delete ${CONTRACT_DIR}/pom.xml to switch architecture."
    else
        cp "$template" "$CONTRACT_DIR/pom.xml"
        echo "Copied ${ARCH}-pom.xml to ${CONTRACT_DIR}/pom.xml"
    fi
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)
            usage
            exit 0
            ;;
        --skip-tests)
            SKIP_TESTS="true"
            shift
            ;;
        --no-clean)
            CLEAN="false"
            shift
            ;;
        --arch)
            [[ $# -ge 2 ]] || die "--arch requires a value (intel or arm)"
            ARCH="$2"
            shift 2
            ;;
        *)
            usage >&2
            die "unexpected argument: $1"
            ;;
    esac
done

if [[ "$PWD" != "$ROOT" ]]; then
    die "run this script from the repository root: ${ROOT}"
fi

if [[ ! -d "$ENV_DIR" ]]; then
    echo "Error: no development environment found at ${ENV_DIR}." >&2
    echo "Please run setup_env.sh from the root directory." >&2
    exit 1
fi

[[ -f "$ENV_SCRIPT" ]] || die "missing ${ENV_SCRIPT} (re-run setup_env.sh from the root directory)"
# shellcheck disable=SC1090
source "$ENV_SCRIPT"

command -v java >/dev/null 2>&1 || die "java not on PATH after activating ${ENV_DIR}"
command -v mvn >/dev/null 2>&1 || die "mvn not on PATH after activating ${ENV_DIR}"

ARCH="${ARCH:-$(detect_arch)}"
case "$ARCH" in
    intel|arm) ;;
    *) die "unknown architecture '${ARCH}': expected 'intel' or 'arm'" ;;
esac

ensure_contract_pom

MVN_ARGS=(-B)
if [[ "$CLEAN" == "true" ]]; then
    MVN_ARGS+=(clean)
fi
MVN_ARGS+=(install)
if [[ "$SKIP_TESTS" == "true" ]]; then
    MVN_ARGS+=(-DskipTests)
fi

echo
echo "Java  : $(java -version 2>&1 | head -n 1)"
echo "Maven : $(mvn -v 2>&1 | head -n 1)"
echo "Running: mvn ${MVN_ARGS[*]}"
echo

cd "$SRC_DIR"
mvn "${MVN_ARGS[@]}"

echo
echo "Build complete. Start the system with ./start.sh"
