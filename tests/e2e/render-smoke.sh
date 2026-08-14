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

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
EXAMPLE="$ROOT/examples/components-demo.ssc"
BIN="$ROOT/bin"
PORT=8768

trap 'pkill -9 -f "examples/components-demo\.ssc" 2>/dev/null; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

echo "============================================================"
echo "  ssc render smoke — headless vs served"
echo "============================================================"
echo

# Headless render
"$BIN/ssc-tools" render "$EXAMPLE" > "/tmp/render-smoke-headless.html" 2>"/tmp/render-smoke-headless.err"
if [ ! -s "/tmp/render-smoke-headless.html" ]; then
    echo "[FAIL] headless render produced no output"
    cat /tmp/render-smoke-headless.err
    exit 1
fi
echo "  headless: $(wc -c < /tmp/render-smoke-headless.html) bytes"

# Served render
lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null
sleep 1
# KNOWN GAP, re-measured 2026-08-15 — AND THE CAUSE THIS COMMENT NAMED IS GONE. It used to read:
# "the served half 404s and the diff is the whole page against 'Not Found' (9 bytes, measured
# 2026-08-04)", blaming `native-lane-ignores-declarative-route-registration`. That entry has been
# FIXED since 2026-08-06 (`debe22715`); the served half no longer 404s. Because this gate is invoked
# by nothing, nobody re-ran it, and the note kept explaining a cause that no longer existed — a
# known-gap comment is a DATED MEASUREMENT, and this one aged into a wrong explanation of a real
# defect.
#
# What is actually there now, on a freshly built toolchain: 54 bytes of
# `native HTTP handler failed: arity: 3 expected, 2 given`. Nothing in the program takes three
# arguments at that call — `Alert.render(title, body, level: String = "info")` is called with two and
# the DEFAULT IS NOT APPLIED. Supplying it by hand does not fix the page, it MOVES the error to
# `arity: 2 expected, 1 given`, i.e. to the next defaulted call. Filed as
# `native-serve-does-not-apply-a-default-argument-so-every-short-call-fails` in v2/BUGS.md, with the
# controls that rule out "defaults are broken" generally: on `bin/ssc run` the same shape works, in
# the same file and from an imported module alike.
#
# The headless half is fine — 4076 bytes of correct HTML through ssc-tools — so this gate remains a
# WITNESS to a defect elsewhere rather than one of its own, and it goes green when that entry is
# fixed. It stays unwired until then: a gate red on arrival is how a suite becomes noise.
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
