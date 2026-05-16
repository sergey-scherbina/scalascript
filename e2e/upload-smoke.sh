#!/usr/bin/env bash
# Cross-backend multipart-upload smoke harness.
#
# Boots examples/uploads.ssc on http://localhost:8766 through each of the
# three backends (interpreter, JVM-compiled, JS via Node), POSTs an
# identical multipart/form-data payload containing a 256-byte file with
# every byte value 0..255 in order, and asserts the response is identical
# across the three runtimes.  Verifies the multipart parser is byte-safe
# (size, first/last byte) and that req.files surfaces filename + content-
# type on every backend.
#
# Plain bash (not scala-cli) so that the bin/sscc and bin/jssc launchers,
# which themselves shell out to scala-cli, don't deadlock on the parent
# harness's scala-cli bloop server.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLE="$ROOT/examples/uploads.ssc"
BIN="$ROOT/bin"
PORT=8766
# Build a 256-byte file containing every byte value 0..255 in order.
# Use a fixed filename so the expected response is reproducible.
PAYLOAD_DIR="$(mktemp -d)"
PAYLOAD="$PAYLOAD_DIR/test_bytes.bin"
EXPECTED="filename=$(basename "$PAYLOAD")|content-type=application/octet-stream|size=256|first=0|last=255"
trap 'rm -rf "$PAYLOAD_DIR"; pkill -9 -f "examples/uploads\.ssc" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

python3 -c "import sys; sys.stdout.buffer.write(bytes(range(256)))" > "$PAYLOAD"

kill_port() {
    lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null
    sleep 1
}

wait_for_server() {
    local deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        if curl -sS -o /dev/null -m 1 "http://localhost:$PORT/" 2>/dev/null; then
            return 0
        fi
        sleep 1
    done
    return 1
}

run_backend() {
    local label="$1"
    local launcher="$2"
    kill_port
    "$launcher" "$EXAMPLE" > "/tmp/upload-smoke-$label.log" 2>&1 &
    local pid=$!
    if ! wait_for_server; then
        kill -9 $pid 2>/dev/null
        echo "[FAIL] $label: server did not start within 60s"
        echo "       log: /tmp/upload-smoke-$label.log"
        return 1
    fi
    local out
    out=$(curl -sS -X POST -F "file=@${PAYLOAD};type=application/octet-stream" "http://localhost:$PORT/upload" 2>&1)
    kill $pid 2>/dev/null
    wait $pid 2>/dev/null
    if [ "$out" = "$EXPECTED" ]; then
        echo "[PASS] $label"
        return 0
    fi
    echo "[FAIL] $label"
    echo "       expected: $EXPECTED"
    echo "       got:      $out"
    return 1
}

echo "============================================================"
echo "  Multipart upload smoke — three backends · port $PORT"
echo "============================================================"
echo

fail=0
run_backend INT "$BIN/ssc"   || fail=1
run_backend JVM "$BIN/sscc"  || fail=1
run_backend JS  "$BIN/jssc"  || fail=1

echo
if [ $fail -eq 0 ]; then
    echo "All three backends agree on the multipart roundtrip."
    exit 0
else
    echo "One or more backends FAILED — see logs in /tmp/upload-smoke-*.log"
    exit 1
fi
