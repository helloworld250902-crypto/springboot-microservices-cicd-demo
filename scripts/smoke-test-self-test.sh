#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PORT=$((25000 + $$ % 10000))
SERVER_PID=""
SERVER_LOG=$(mktemp)
JAVA_BIN=java
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
fi

cleanup() {
  if [ -n "$SERVER_PID" ]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  rm -f "$SERVER_LOG"
}
trap cleanup EXIT

"$JAVA_BIN" "$SCRIPT_DIR/SmokeTestServer.java" "$PORT" >"$SERVER_LOG" 2>&1 &
SERVER_PID=$!

ready=false
for _ in {1..30}; do
  if curl --fail --silent "http://127.0.0.1:$PORT/actuator/health" >/dev/null; then
    ready=true
    break
  fi
  sleep 0.2
done

if [ "$ready" != true ]; then
  echo "fake order service did not start" >&2
  exit 1
fi

ORDER_SERVICE_URL="http://127.0.0.1:$PORT" \
PRODUCT_SERVICE_URL="http://127.0.0.1:$PORT" \
  bash "$SCRIPT_DIR/smoke-test.sh"

kill "$SERVER_PID"
wait "$SERVER_PID" 2>/dev/null || true
SERVER_PID=""

if ORDER_SERVICE_URL="http://127.0.0.1:$PORT" \
  PRODUCT_SERVICE_URL="http://127.0.0.1:$PORT" \
  bash "$SCRIPT_DIR/smoke-test.sh" >/dev/null 2>&1; then
  echo "smoke test should fail when the service is unavailable" >&2
  exit 1
fi

echo "smoke test self-test OK"
