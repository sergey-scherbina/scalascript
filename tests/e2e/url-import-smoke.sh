#!/usr/bin/env bash
# URL imports smoke — verifies `[Card](http://…/card.ssc)` resolves
# through a local cache at `~/.cache/ssc/<scheme>/<authority>/<path>`,
# fetches on first access, and reuses the cache on subsequent runs.
# Also exercises the `SSC_NO_NETWORK=1` flag.
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
#   not covered here and its own gap is `v2/BUGS.md native-lane-ignores-declarative-route-registration`.
#
# The launcher is now a COMMAND WITH ARGUMENTS, passed as one string and invoked UNQUOTED. Passing
# the extra words as separate arguments instead is a silent trap: helpers that take positional args
# shift `expected` into `$4`, and helpers that use `"$launcher"` drop the words entirely — which
# made three differently-labelled lanes all run the same default lane and turned two gates green
# for no reason. Both happened here before this note was written.
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# Overridable so this gate can be pointed at another toolchain — how the other e2e gates are checked
# against a pre-fix binary, and how the concurrency repro below was run.
BIN="${BIN:-$ROOT/bin}"

# EVERYTHING PER-RUN. This gate used a fixed port, two fixed /tmp paths and a `rm -rf "$CACHE_ENTRY"`,
# so a second copy of it running at the same time broke the first: same port to bind, same consumer
# file to overwrite, same log to truncate, and a trap that killed whatever held the port — the other
# instance's server. MEASURED before fixing: alone it is 4/4 and exits 0; two started A SECOND APART
# give 2/4 and 0/4, both exit 1, and that reproduced 3 trials out of 3. The offset matters — three
# started simultaneously all passed, which is why this reads as a flake rather than a broken gate:
# whether it fails depends on where the second instance is in its sequence when the first wipes.
# After the fix, the same offset protocol is 4/4 and 4/4 across 3 trials.
# The suite is simply where a second instance is likely; several agents run it at once in this repo.
# scripts/BUGS.md url-import-flakes-under-suite-load.
WEB=$(mktemp -d)
PORT=0
for _try in 1 2 3 4 5 6 7 8 9 10; do
  _p=$(( 9870 + RANDOM % 4000 ))
  if ! lsof -ti :"$_p" >/dev/null 2>&1; then PORT=$_p; break; fi
done
[ "$PORT" -ne 0 ] || { echo "url-import-smoke: could not find a free port in 10 tries"; exit 1; }
CONSUMER="$WEB/consumer.ssc"
HTTPLOG="$WEB/http.log"
# The import cache is hard-coded in ImportResolver.scala:27 as `os.home / ".cache" / "ssc"` with no
# env override — `SSC_CACHE_DIR` is a DIFFERENT cache (bin/ssc's artifacts), and trying to use it
# here isolated nothing. But the cache is keyed by scheme/authority/path, and the authority carries
# the per-run PORT, so this run's entry is already private. What was not private was the WIPE: the
# old `rm -rf "$CACHE_ENTRY"` removed every instance's entries, so a concurrent run deleted this one's
# between its fetch and its cache-hit case. MEASURED: with per-run ports and paths but a whole-tree
# wipe, two instances gave 4/4 and 2/4 — the second failing exactly on "cache hit (server stopped)".
# Wiping only this run's subtree is what makes them independent.
CACHE_ENTRY="$HOME/.cache/ssc/http/127.0.0.1:$PORT"
# Kills only THIS run's server by pid, never by port: killing by port is what took out the other
# instance.
trap 'kill $HTTP_PID 2>/dev/null; rm -rf "$WEB"' EXIT

# Serve a component over HTTP
cat > "$WEB/card.ssc" <<'EOF'
---
name: card
---
# Card
```scalascript
object Card:
  def render(t: String): String = "url-card-" + t
```
EOF

# Start a local HTTP server in the WEB dir.
( cd "$WEB" && python3 -m http.server $PORT --bind 127.0.0.1 ) > "$HTTPLOG" 2>&1 &
HTTP_PID=$!
# Loudly, and with room for a loaded runner. The old loop was 20 x 0.5 s and fell THROUGH on
# timeout, so a server that never came up surfaced as four unrelated case failures further down.
_ready=0
for i in $(seq 1 60); do
    if curl -sS -o /dev/null -m 1 "http://127.0.0.1:$PORT/card.ssc" 2>/dev/null; then _ready=1; break; fi
    sleep 0.5
done
if [ "$_ready" -ne 1 ]; then
    echo "url-import-smoke: the local http server never answered on 127.0.0.1:$PORT after 30s"
    echo "  server log:"; sed 's/^/    /' "$HTTPLOG" 2>/dev/null | head -5
    exit 1
fi

cat > $CONSUMER <<EOF
---
name: consumer
---
# Consumer

[Card](http://127.0.0.1:$PORT/card.ssc)

\`\`\`scalascript
println(Card.render("hi"))
\`\`\`
EOF

echo "============================================================"
echo "  URL imports smoke"
echo "============================================================"
echo

fail=0
check() {
    local label="$1"
    local cmd="$2"
    local extra_env="$3"
    local expected="url-card-hi"
    local got
    got=$(env $extra_env $cmd $CONSUMER 2>/dev/null | grep -vE '^\s*$' | tr '\n' '|')
    if [ "$got" = "$expected|" ]; then
        echo "  [PASS] $label"
    else
        echo "  [FAIL] $label  got: $got"
        fail=1
    fi
}

# Cold fetch — cache empty, network needed.
echo "Case A: cold fetch (network)"
check "INT" "$BIN/ssc-tools run --v1"   ""
echo "  cache after fetch:"
find "$CACHE_ENTRY" -type f 2>/dev/null | sed 's/^/    /'

# Now the cache should have the file; turn off the server and verify hit.
kill $HTTP_PID 2>/dev/null
wait $HTTP_PID 2>/dev/null
sleep 1
echo
echo "Case B: cache hit (server stopped)"
check "INT" "$BIN/ssc-tools run --v1"   ""

# With SSC_NO_NETWORK=1 + cache present, still works.
echo
echo "Case C: SSC_NO_NETWORK=1 with cache hit"
check "INT" "$BIN/ssc-tools run --v1"   "SSC_NO_NETWORK=1"

# With SSC_NO_NETWORK=1 + cache empty, should fail.
echo
echo "Case D: SSC_NO_NETWORK=1 with empty cache (should fail)"
rm -rf "$CACHE_ENTRY"
# unquoted on purpose — this is a COMMAND WITH ARGUMENTS, not a path
# shellcheck disable=SC2086
out=$(SSC_NO_NETWORK=1 $BIN/ssc-tools run --v1 $CONSUMER 2>&1 || true)
if echo "$out" | grep -q "SSC_NO_NETWORK=1"; then
    echo "  [PASS] INT  refused fetch as expected"
else
    echo "  [FAIL] INT  did not refuse: $out"
    fail=1
fi

echo
if [ $fail -eq 0 ]; then
    echo "All URL-import cases pass."
    exit 0
fi
exit 1
