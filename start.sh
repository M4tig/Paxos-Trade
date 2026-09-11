#!/usr/bin/env bash
# Start DidaTrade servers, clients (app), and console.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$ROOT/src"
ENV_DIR="$ROOT/dev_env"
ENV_SCRIPT="$ENV_DIR/env.sh"
LOG_DIR="$ROOT/logs"

# README defaults
DEFAULT_HOST="localhost"
DEFAULT_PORT="9000"
DEFAULT_SCHEDULER="A"
DEFAULT_SERVER_COUNT="3"
DEFAULT_APP_COUNT="1"
DEFAULT_APP_ID="1"
DEFAULT_CONSOLE_ENABLED="true"

HOST="$DEFAULT_HOST"
PORT="$DEFAULT_PORT"
SCHEDULER="$DEFAULT_SCHEDULER"

SERVER_COUNT="$DEFAULT_SERVER_COUNT"
SERVER_PORT=""
SERVER_SCHEDULER=""

APP_COUNT="$DEFAULT_APP_COUNT"
APP_ID="$DEFAULT_APP_ID"
APP_HOST=""
APP_PORT=""
APP_SCHEDULER=""

CONSOLE_ENABLED="$DEFAULT_CONSOLE_ENABLED"
CONSOLE_HOST=""
CONSOLE_PORT=""
CONSOLE_SCHEDULER=""

SERVER_PIDS=()

usage() {
    cat <<EOF
Usage:
  $(basename "$0") --default
  $(basename "$0") --config <file>
  $(basename "$0")

  --default     Start with README defaults (no prompts).
  --config FILE Start using a startup config file.
                See startup_config/README.md for the format.

  With no flags, values are collected interactively. Press Enter to
  keep the value shown in brackets.

Run this script from the repository root.
EOF
}

die() {
    echo "Error: $*" >&2
    exit 1
}

trim() {
    local s="$1"
    s="${s#"${s%%[![:space:]]*}"}"
    s="${s%"${s##*[![:space:]]}"}"
    printf '%s' "$s"
}

to_lower() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

is_true() {
    local v
    v="$(to_lower "$1")"
    case "$v" in
        1|y|yes|true|on) return 0 ;;
        *) return 1 ;;
    esac
}

prompt() {
    local __var="$1"
    local __label="$2"
    local __default="$3"
    local __input=""
    read -r -p "${__label} [${__default}]: " __input || true
    if [[ -z "$__input" ]]; then
        printf -v "$__var" '%s' "$__default"
    else
        printf -v "$__var" '%s' "$(trim "$__input")"
    fi
}

apply_key() {
    local key="$1"
    local val="$2"
    case "$key" in
        host) HOST="$val" ;;
        port) PORT="$val" ;;
        scheduler) SCHEDULER="$val" ;;
        server.count) SERVER_COUNT="$val" ;;
        server.port) SERVER_PORT="$val" ;;
        server.scheduler) SERVER_SCHEDULER="$val" ;;
        app.count) APP_COUNT="$val" ;;
        app.id) APP_ID="$val" ;;
        app.host) APP_HOST="$val" ;;
        app.port) APP_PORT="$val" ;;
        app.scheduler) APP_SCHEDULER="$val" ;;
        console.enabled) CONSOLE_ENABLED="$val" ;;
        console.host) CONSOLE_HOST="$val" ;;
        console.port) CONSOLE_PORT="$val" ;;
        console.scheduler) CONSOLE_SCHEDULER="$val" ;;
        *) die "unknown config key '$key'" ;;
    esac
}

load_config() {
    local file="$1"
    [[ -f "$file" ]] || die "config file not found: $file"

    local line key val lineno=0
    while IFS= read -r line || [[ -n "$line" ]]; do
        lineno=$((lineno + 1))
        line="${line%%#*}"
        line="$(trim "$line")"
        [[ -z "$line" ]] && continue
        [[ "$line" == *=* ]] || die "$file:$lineno: expected key=value, got: $line"
        key="$(trim "${line%%=*}")"
        val="$(trim "${line#*=}")"
        [[ -n "$key" ]] || die "$file:$lineno: empty key"
        apply_key "$key" "$val"
    done < "$file"
}

resolve_values() {
    SERVER_PORT="${SERVER_PORT:-$PORT}"
    SERVER_SCHEDULER="${SERVER_SCHEDULER:-$SCHEDULER}"
    APP_HOST="${APP_HOST:-$HOST}"
    APP_PORT="${APP_PORT:-$PORT}"
    APP_SCHEDULER="${APP_SCHEDULER:-$SCHEDULER}"
    CONSOLE_HOST="${CONSOLE_HOST:-$HOST}"
    CONSOLE_PORT="${CONSOLE_PORT:-$PORT}"
    CONSOLE_SCHEDULER="${CONSOLE_SCHEDULER:-$SCHEDULER}"
}

interactive_prompt() {
    echo "Interactive setup — press Enter to accept the default."
    echo
    prompt HOST "Shared host" "$HOST"
    prompt PORT "Shared base port" "$PORT"
    prompt SCHEDULER "Shared scheduler (A, B, or C)" "$SCHEDULER"
    prompt SERVER_COUNT "Number of servers" "$SERVER_COUNT"
    prompt APP_COUNT "Number of clients (app)" "$APP_COUNT"
    prompt APP_ID "First client id" "$APP_ID"
    prompt CONSOLE_ENABLED "Start console (true/false)" "$CONSOLE_ENABLED"
}

validate() {
    [[ "$SERVER_COUNT" =~ ^[0-9]+$ ]] || die "server.count must be a non-negative integer"
    [[ "$APP_COUNT" =~ ^[0-9]+$ ]] || die "app.count must be a non-negative integer"
    [[ "$APP_ID" =~ ^[0-9]+$ ]] || die "app.id must be an integer"
    [[ "$SERVER_PORT" =~ ^[0-9]+$ ]] || die "server port must be an integer"
    [[ "$APP_PORT" =~ ^[0-9]+$ ]] || die "app port must be an integer"
    [[ "$CONSOLE_PORT" =~ ^[0-9]+$ ]] || die "console port must be an integer"
    [[ "$SERVER_SCHEDULER" =~ ^[A-Ca-c]$ ]] || die "server scheduler must be A, B, or C"
    [[ "$APP_SCHEDULER" =~ ^[A-Ca-c]$ ]] || die "app scheduler must be A, B, or C"
    [[ "$CONSOLE_SCHEDULER" =~ ^[A-Ca-c]$ ]] || die "console scheduler must be A, B, or C"
    if (( APP_COUNT > 0 )); then
        (( APP_ID >= 1 && APP_ID <= 99 )) || die "app.id must be in [1, 99]"
        (( APP_ID + APP_COUNT - 1 <= 99 )) || die "client ids would exceed 99"
    fi
}

cleanup() {
    local pid
    for pid in "${SERVER_PIDS[@]+"${SERVER_PIDS[@]}"}"; do
        kill "$pid" 2>/dev/null || true
    done
}

port_open() {
    local p="$1"
    bash -c "echo >/dev/tcp/127.0.0.1/${p}" >/dev/null 2>&1
}

wait_for_servers() {
    local id last=$((SERVER_PORT + SERVER_COUNT - 1))
    echo "Waiting for servers on ports ${SERVER_PORT}-${last}..."
    local attempt
    for attempt in $(seq 1 60); do
        local ready=1
        for id in $(seq 0 $((SERVER_COUNT - 1))); do
            port_open $((SERVER_PORT + id)) || { ready=0; break; }
        done
        if (( ready == 1 )); then
            echo "All servers are listening."
            return 0
        fi
        sleep 1
    done
    echo "Warning: timed out waiting for all servers. Continuing anyway." >&2
}

start_server() {
    local id="$1"
    local logfile="$LOG_DIR/server-${id}.log"
    (
        cd "$SRC_DIR/server"
        exec mvn exec:java -Dexec.args="${SERVER_PORT} ${id} ${SERVER_SCHEDULER}"
    ) >"$logfile" 2>&1 &
    SERVER_PIDS+=("$!")
    echo "Server ${id} started (pid $!, bind port $((SERVER_PORT + id)), log ${logfile})"
}

run_in_new_terminal() {
    local title="$1"
    local workdir="$2"
    local maven_args="$3"
    local inner
    inner="source '${ENV_SCRIPT}' && cd '${workdir}' && mvn exec:java -Dexec.args='${maven_args}'; echo; read -r -p 'Process exited. Press Enter to close...'"

    if command -v gnome-terminal >/dev/null 2>&1; then
        gnome-terminal --title="$title" -- bash -lc "$inner" &
    elif command -v konsole >/dev/null 2>&1; then
        konsole --title "$title" -e bash -lc "$inner" &
    elif command -v xfce4-terminal >/dev/null 2>&1; then
        xfce4-terminal --title="$title" -e "bash -lc $(printf '%q' "$inner")" &
    elif command -v xterm >/dev/null 2>&1; then
        xterm -T "$title" -e bash -lc "$inner" &
    else
        local safe
        safe="$(printf '%s' "$title" | tr ' /' '__')"
        local logfile="$LOG_DIR/${safe}.log"
        echo "No graphical terminal found; starting '${title}' in the background (log: ${logfile})."
        echo "This process expects a TTY; use a terminal emulator or tmux for interactive use."
        (
            cd "$workdir"
            mvn exec:java -Dexec.args="${maven_args}"
        ) >"$logfile" 2>&1 &
    fi
}

print_plan() {
    echo
    echo "Launch plan"
    echo "  servers : count=${SERVER_COUNT}  base_port=${SERVER_PORT}  scheduler=${SERVER_SCHEDULER}"
    echo "  app     : count=${APP_COUNT}  first_id=${APP_ID}  host=${APP_HOST}  port=${APP_PORT}  scheduler=${APP_SCHEDULER}"
    echo "  console : enabled=${CONSOLE_ENABLED}  host=${CONSOLE_HOST}  port=${CONSOLE_PORT}  scheduler=${CONSOLE_SCHEDULER}"
    echo
}

# --- argument parsing ---
MODE=""
CONFIG_FILE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)
            usage
            exit 0
            ;;
        --default)
            [[ -z "$MODE" ]] || die "choose only one of --default, --config, or interactive"
            MODE="default"
            shift
            ;;
        --config)
            [[ -z "$MODE" ]] || die "choose only one of --default, --config, or interactive"
            [[ $# -ge 2 ]] || die "--config requires a file path"
            MODE="config"
            CONFIG_FILE="$2"
            shift 2
            ;;
        *)
            if [[ -z "$MODE" && -f "$1" ]]; then
                MODE="config"
                CONFIG_FILE="$1"
                shift
            else
                usage >&2
                die "unexpected argument: $1"
            fi
            ;;
    esac
done

MODE="${MODE:-interactive}"

# --- environment ---
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
[[ -d "$SRC_DIR/server" && -d "$SRC_DIR/app" && -d "$SRC_DIR/console" ]] || die "expected Maven modules under ${SRC_DIR}"

case "$MODE" in
    default) ;;
    config) load_config "$CONFIG_FILE" ;;
    interactive) interactive_prompt ;;
esac

resolve_values
validate
print_plan

mkdir -p "$LOG_DIR"
trap cleanup EXIT INT TERM

if (( SERVER_COUNT > 0 )); then
    for id in $(seq 0 $((SERVER_COUNT - 1))); do
        start_server "$id"
    done
    wait_for_servers
else
    echo "No servers requested."
fi

console_on=false
if is_true "$CONSOLE_ENABLED"; then
    console_on=true
fi

foreground=""
if (( APP_COUNT > 0 )); then
    foreground="app"
elif [[ "$console_on" == true ]]; then
    foreground="console"
fi

if [[ "$console_on" == true ]]; then
    if [[ "$foreground" == "console" ]]; then
        echo "Starting console in this terminal (Ctrl+C stops the console and the servers)."
        cd "$SRC_DIR/console"
        mvn exec:java -Dexec.args="${CONSOLE_HOST} ${CONSOLE_PORT} ${CONSOLE_SCHEDULER}"
    else
        echo "Starting console in a new terminal."
        run_in_new_terminal "DidaTrade console" "$SRC_DIR/console" \
            "${CONSOLE_HOST} ${CONSOLE_PORT} ${CONSOLE_SCHEDULER}"
    fi
fi

if (( APP_COUNT > 0 )); then
    local_i=0
    for local_i in $(seq 0 $((APP_COUNT - 1))); do
        cid=$((APP_ID + local_i))
        args="${cid} ${APP_HOST} ${APP_PORT} ${APP_SCHEDULER}"
        if [[ "$foreground" == "app" && "$local_i" -eq 0 ]]; then
            continue
        fi
        echo "Starting client ${cid} in a new terminal."
        run_in_new_terminal "DidaTrade client ${cid}" "$SRC_DIR/app" "$args"
    done

    if [[ "$foreground" == "app" ]]; then
        echo "Starting client ${APP_ID} in this terminal (Ctrl+C stops the client and the servers)."
        cd "$SRC_DIR/app"
        mvn exec:java -Dexec.args="${APP_ID} ${APP_HOST} ${APP_PORT} ${APP_SCHEDULER}"
    fi
fi

if [[ -z "$foreground" ]]; then
    echo "Servers are running in the background. Press Ctrl+C to stop them."
    wait || true
fi
