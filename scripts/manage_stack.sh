#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: manage_stack.sh <command>

Commands:
  start   Launch the Pion relay, Jetson WebSocket broadcaster, and WebRTC receiver.
  stop    Terminate processes launched by the last start invocation.
  status  Show whether the stack is currently running.

Environment variables:
  GO_BIN             Go executable to use (default: go)
  PYTHON_BIN         Python executable to use (default: python3)

  STREAM_ID          Stream identifier consumed by webrtc_receiver.py (default: demo)
  SIGNALING_URL      Full signaling URL for the Pion relay (default: ws://127.0.0.1:8080/ws)
  OVERLAY_WS         WebSocket URL that will receive detection overlays (default: ws://127.0.0.1:8765)
  MODEL_PATH         YOLO model path passed to webrtc_receiver.py (default: yolov8n.pt)

  PION_ADDR          Address passed to the relay --addr flag (default: :8080)
  PION_HTTPS_ADDR    Optional HTTPS bind for --https-addr
  PION_TLS_CERT      Optional certificate for --tls-cert
  PION_TLS_KEY       Optional key for --tls-key

  WS_HOST            Host for websocket_server.py (default: 0.0.0.0)
  WS_PORT            Port for websocket_server.py (default: 8765)
  WS_CERTFILE        Optional TLS certificate for websocket_server.py
  WS_KEYFILE         Optional TLS key for websocket_server.py
  WS_INSECURE_PORT   Optional secondary HTTP port (e.g., 8081)
  WS_STATIC_DIR      Static directory served by websocket_server.py (default: <repo>/browser)
  WS_RECORDINGS_DIR  Optional directory exposed via --recordings-dir
USAGE
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="$ROOT_DIR/.run"
STATE_FILE="$STATE_DIR/webrtc_stack.pids"

GO_BIN=${GO_BIN:-go}
PYTHON_BIN=${PYTHON_BIN:-python3}

STREAM_ID=${STREAM_ID:-demo}
SIGNALING_URL=${SIGNALING_URL:-ws://127.0.0.1:8080/ws}
OVERLAY_WS=${OVERLAY_WS:-ws://127.0.0.1:8765}
MODEL_PATH=${MODEL_PATH:-yolov8n.pt}

PION_ADDR=${PION_ADDR:-:8080}
PION_HTTPS_ADDR=${PION_HTTPS_ADDR:-}
PION_TLS_CERT=${PION_TLS_CERT:-}
PION_TLS_KEY=${PION_TLS_KEY:-}

WS_HOST=${WS_HOST:-0.0.0.0}
WS_PORT=${WS_PORT:-8765}
WS_CERTFILE=${WS_CERTFILE:-}
WS_KEYFILE=${WS_KEYFILE:-}
WS_INSECURE_PORT=${WS_INSECURE_PORT:-}
WS_STATIC_DIR=${WS_STATIC_DIR:-$ROOT_DIR/browser}
WS_RECORDINGS_DIR=${WS_RECORDINGS_DIR:-}

SUPPORTS_WAIT_N=0
if [[ ${BASH_VERSINFO[0]} -gt 4 || ( ${BASH_VERSINFO[0]} -eq 4 && ${BASH_VERSINFO[1]} -ge 3 ) ]]; then
  SUPPORTS_WAIT_N=1
fi

COMMAND=${1:-}

ensure_state_dir() {
  mkdir -p "$STATE_DIR"
}

is_running() {
  if [[ -f "$STATE_FILE" ]]; then
    while IFS=":" read -r _ pid; do
      if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        return 0
      fi
    done < "$STATE_FILE"
  fi
  return 1
}

print_status() {
  if is_running; then
    echo "Stack is running. Active processes:"
    while IFS=":" read -r name pid; do
      if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        echo "  $name (PID $pid)"
      fi
    done < "$STATE_FILE"
  else
    echo "Stack is not running."
  fi
}

COMPONENTS=()
PIDS=()
RUNNING=0

record_state() {
  ensure_state_dir
  {
    for idx in "${!COMPONENTS[@]}"; do
      printf "%s:%s\n" "${COMPONENTS[$idx]}" "${PIDS[$idx]}"
    done
  } > "$STATE_FILE"
}

start_processes() {
  if is_running; then
    echo "Existing stack detected. Use 'stop' before starting a new instance." >&2
    exit 1
  fi

  RUNNING=1
  trap 'cleanup' EXIT
  trap 'cleanup; exit 130' INT
  trap 'cleanup; exit 143' TERM

  echo "Starting Pion relay..."
  local pion_cmd=($GO_BIN run main.go --addr "$PION_ADDR")
  if [[ -n "$PION_HTTPS_ADDR" ]]; then
    pion_cmd+=(--https-addr "$PION_HTTPS_ADDR")
  fi
  if [[ -n "$PION_TLS_CERT" ]]; then
    pion_cmd+=(--tls-cert "$PION_TLS_CERT")
  fi
  if [[ -n "$PION_TLS_KEY" ]]; then
    pion_cmd+=(--tls-key "$PION_TLS_KEY")
  fi
  (
    cd "$ROOT_DIR/pion-server"
    "${pion_cmd[@]}"
  ) &
  COMPONENTS+=("pion-relay")
  PIDS+=($!)

  echo "Starting Jetson WebSocket broadcaster..."
  local ws_cmd=($PYTHON_BIN websocket_server.py --host "$WS_HOST" --port "$WS_PORT" --static-dir "$WS_STATIC_DIR")
  if [[ -n "$WS_CERTFILE" ]]; then
    ws_cmd+=(--certfile "$WS_CERTFILE")
  fi
  if [[ -n "$WS_KEYFILE" ]]; then
    ws_cmd+=(--keyfile "$WS_KEYFILE")
  fi
  if [[ -n "$WS_INSECURE_PORT" ]]; then
    ws_cmd+=(--insecure-port "$WS_INSECURE_PORT")
  fi
  if [[ -n "$WS_RECORDINGS_DIR" ]]; then
    ws_cmd+=(--recordings-dir "$WS_RECORDINGS_DIR")
  fi
  (
    cd "$ROOT_DIR/jetson"
    "${ws_cmd[@]}"
  ) &
  COMPONENTS+=("websocket-server")
  PIDS+=($!)

  echo "Starting WebRTC receiver..."
  local receiver_cmd=($PYTHON_BIN webrtc_receiver.py "$STREAM_ID")
  if [[ -n "$SIGNALING_URL" ]]; then
    receiver_cmd+=(--signaling-url "$SIGNALING_URL")
  fi
  if [[ -n "$OVERLAY_WS" ]]; then
    receiver_cmd+=(--overlay-ws "$OVERLAY_WS")
  fi
  if [[ -n "$MODEL_PATH" ]]; then
    receiver_cmd+=(--model "$MODEL_PATH")
  fi
  (
    cd "$ROOT_DIR/jetson"
    "${receiver_cmd[@]}"
  ) &
  COMPONENTS+=("webrtc-receiver")
  PIDS+=($!)

  record_state
  echo "Processes started. Press Ctrl+C to stop."
  wait_for_children
}

wait_for_children() {
  local exit_code=0
  if (( SUPPORTS_WAIT_N )); then
    while true; do
      if ! wait -n "${PIDS[@]}" 2>/dev/null; then
        exit_code=$?
        if [[ $exit_code -eq 127 ]]; then
          exit_code=0
        fi
        break
      fi
    done
  else
    wait || exit_code=$?
  fi
  RUNNING=0
  cleanup
  exit $exit_code
}

cleanup() {
  trap - EXIT INT TERM
  if (( RUNNING )) || [[ -f "$STATE_FILE" ]]; then
    echo "Shutting down stack..."
    local entries=()
    if (( ${#PIDS[@]} )); then
      for idx in "${!PIDS[@]}"; do
        entries+=("${COMPONENTS[$idx]}:${PIDS[$idx]}")
      done
    elif [[ -f "$STATE_FILE" ]]; then
      mapfile -t entries < "$STATE_FILE"
    fi

    for entry in "${entries[@]}"; do
      local name="${entry%%:*}"
      local pid="${entry##*:}"
      if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        echo "  Stopping $name (PID $pid)"
        kill "$pid" 2>/dev/null || true
      fi
    done

    for entry in "${entries[@]}"; do
      local pid="${entry##*:}"
      if [[ -n "$pid" ]]; then
        wait "$pid" 2>/dev/null || true
      fi
    done

    rm -f "$STATE_FILE"
  fi
}

stop_processes() {
  if ! is_running; then
    echo "Stack is not running."
    return 0
  fi
  echo "Stopping processes recorded in $STATE_FILE..."
  while IFS=":" read -r name pid; do
    if [[ -z "$pid" ]]; then
      continue
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "  $name (PID $pid) is already stopped."
      continue
    fi
    echo "  Sending SIGTERM to $name (PID $pid)"
    kill "$pid" 2>/dev/null || true
    for _ in {1..20}; do
      if ! kill -0 "$pid" 2>/dev/null; then
        echo "  $name stopped."
        break
      fi
      sleep 0.5
    done
    if kill -0 "$pid" 2>/dev/null; then
      echo "  $name did not exit gracefully. Sending SIGKILL."
      kill -9 "$pid" 2>/dev/null || true
    fi
  done < "$STATE_FILE"
  rm -f "$STATE_FILE"
}

case "$COMMAND" in
  start)
    start_processes
    ;;
  stop)
    stop_processes
    ;;
  status)
    print_status
    ;;
  -h|--help|help|"" )
    usage
    ;;
  *)
    echo "Unknown command: $COMMAND" >&2
    usage >&2
    exit 1
    ;;
esac
