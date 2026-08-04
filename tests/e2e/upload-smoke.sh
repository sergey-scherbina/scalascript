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
#
# RUNNERS CORRECTED 2026-08-04 (tests/BUGS.md orphaned-e2e-gates-52). Two were wrong, and neither
# wrong is visible from the failure it produced:
#
#   `$BIN/sscc` is `ssc-tools compile-jvm` — it COMPILES and exits, printing `JVM artifact written`.
#   As a "JVM backend" it never ran anything: this gate's JVM lane was measuring a compiler, and
#   reported it as "the server process EXITED before it listened".
#
#   `$BIN/ssc` is `StandardMain`, the NATIVE/default lane, not the interpreter. The label said INT
#   for a launcher that stopped being the interpreter when the lane map moved, so this gate's
#   failures were filed against the wrong lane's owner. The runner now matches the label; NATIVE is
#   COVERED as of 2026-08-05 — see the NATIVE row below; its separate declarative-route gap is
#   `v2/BUGS.md native-lane-ignores-declarative-route-registration`.
#
# The launcher is now a COMMAND WITH ARGUMENTS, passed as one string and invoked UNQUOTED. Passing
# the extra words as separate arguments instead is a silent trap: helpers that take positional args
# shift `expected` into `$4`, and helpers that use `"$launcher"` drop the words entirely — which
# made three differently-labelled lanes all run the same default lane and turned two gates green
# for no reason. Both happened here before this note was written.
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
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
    # $1 = pid of the launched server, when the caller has one. A dead process is the answer NOW:
    # measured 2026-08-02, four of the five delegating launchers exited instantly ("compile-jvm
    # requires the optional tools tier") and this loop still burned its whole deadline waiting for
    # a listener that could never appear. Three backends x 60-90s put the gate past the census's
    # 180s timeout, so it was filed as HANGING and left unwired -- the one diagnosis that stops
    # anyone reading the actual error. Polling a corpse is never worth the wall clock.
    local pid="${1:-}"
    local deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt $deadline ]; do
        if curl -sS -o /dev/null -m 1 "http://localhost:$PORT/" 2>/dev/null; then
            return 0
        fi
        if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
            return 2   # the server process is gone; nothing will ever answer
        fi
        sleep 1
    done
    return 1
}

run_backend() {
    local label="$1"
    local launcher="$2"
    kill_port
    $launcher "$EXAMPLE" > "/tmp/upload-smoke-$label.log" 2>&1 &
    local pid=$!
    wait_for_server "$pid"; wrc=$?
    if [ "$wrc" -ne 0 ]; then
        kill -9 $pid 2>/dev/null
        if [ "$wrc" -eq 2 ]; then
            echo "[FAIL] $label: the server process EXITED before it listened"
        else
            echo "[FAIL] $label: server did not start before the deadline"
        fi
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
echo "  Multipart upload smoke — four backends · port $PORT"
echo "============================================================"
echo

fail=0
run_backend INT "$BIN/ssc-tools run --v1"   || fail=1
run_backend JVM "$BIN/ssc-tools run-jvm"  || fail=1
run_backend JS  "$BIN/ssc-tools run-js"  || fail=1
# NATIVE joined on 2026-08-05. It was the lane this gate's own header called "not covered here", and
# for good reason: it had no multipart at all — `req.files` was a hardcoded empty map, so the handler
# answered its own "missing 'file' part" at HTTP **200**, which no status check can see. A parser now
# lives in the engine (MultipartFast) and the host fills `files`.
# BUGS `multipart-upload-three-lanes-three-answers`.
run_backend NATIVE "$BIN/ssc"            || fail=1

echo
if [ $fail -eq 0 ]; then
    echo "All four backends agree on the multipart roundtrip."
    exit 0
else
    echo "One or more backends FAILED — see logs in /tmp/upload-smoke-*.log"
    exit 1
fi
