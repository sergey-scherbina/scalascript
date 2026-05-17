#!/usr/bin/env bash
# `ssc render` smoke — verifies that headless static rendering produces
# the same HTML as the running interpreter under `serve`.
#
# 1. Run `ssc render examples/components-demo.ssc` — captures the GET /
#    response from a fresh headless interpreter.
# 2. Spin up the same example via `ssc` (server mode), curl GET /,
#    capture the response body.
# 3. Diff the two — they should be byte-identical.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXAMPLE="$ROOT/examples/components-demo.ssc"
BIN="$ROOT/bin"
PORT=8768

trap 'pkill -9 -f "examples/components-demo\.ssc" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

echo "============================================================"
echo "  ssc render smoke — headless vs served"
echo "============================================================"
echo

# Headless render
"$BIN/ssc" render "$EXAMPLE" > "/tmp/render-smoke-headless.html" 2>"/tmp/render-smoke-headless.err"
if [ ! -s "/tmp/render-smoke-headless.html" ]; then
    echo "[FAIL] headless render produced no output"
    cat /tmp/render-smoke-headless.err
    exit 1
fi
echo "  headless: $(wc -c < /tmp/render-smoke-headless.html) bytes"

# Served render
lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null
sleep 1
"$BIN/ssc" "$EXAMPLE" > /tmp/render-smoke-serve.log 2>&1 &
SERVE_PID=$!
for i in $(seq 1 30); do
    if curl -sS -o /dev/null -m 1 "http://localhost:$PORT/" 2>/dev/null; then break; fi
    sleep 1
done
curl -sS "http://localhost:$PORT/" > "/tmp/render-smoke-served.html"
kill $SERVE_PID 2>/dev/null
wait $SERVE_PID 2>/dev/null
echo "  served:   $(wc -c < /tmp/render-smoke-served.html) bytes"

if diff -q "/tmp/render-smoke-headless.html" "/tmp/render-smoke-served.html" > /dev/null; then
    echo
    echo "[PASS] headless 'render' matches served output byte-for-byte"
    exit 0
fi
echo
echo "[FAIL] headless render differs from served output"
echo "  diff (first 40 lines):"
diff "/tmp/render-smoke-headless.html" "/tmp/render-smoke-served.html" | head -40
exit 1
