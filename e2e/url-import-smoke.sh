#!/usr/bin/env bash
# URL imports smoke — verifies `[Card](http://…/card.ssc)` resolves
# through a local cache at `~/.cache/ssc/<scheme>/<authority>/<path>`,
# fetches on first access, and reuses the cache on subsequent runs.
# Also exercises the `SSC_NO_NETWORK=1` flag.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"
PORT=9870
WEB=$(mktemp -d)
trap 'kill $HTTP_PID 2>/dev/null; rm -rf "$WEB" /tmp/url-smoke-consumer.ssc; lsof -ti :$PORT 2>/dev/null | xargs -r kill -9 2>/dev/null' EXIT

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

# Wipe cache so we start clean.
rm -rf ~/.cache/ssc

# Start a local HTTP server in the WEB dir.
( cd "$WEB" && python3 -m http.server $PORT --bind 127.0.0.1 ) > /tmp/url-smoke-http.log 2>&1 &
HTTP_PID=$!
for i in $(seq 1 20); do
    if curl -sS -o /dev/null -m 1 "http://127.0.0.1:$PORT/card.ssc" 2>/dev/null; then break; fi
    sleep 0.5
done

cat > /tmp/url-smoke-consumer.ssc <<EOF
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
    got=$(env $extra_env $cmd /tmp/url-smoke-consumer.ssc 2>/dev/null | grep -vE '^\s*$' | tr '\n' '|')
    if [ "$got" = "$expected|" ]; then
        echo "  [PASS] $label"
    else
        echo "  [FAIL] $label  got: $got"
        fail=1
    fi
}

# Cold fetch — cache empty, network needed.
echo "Case A: cold fetch (network)"
check "INT" "$BIN/ssc"   ""
echo "  cache after fetch:"
find ~/.cache/ssc -type f | sed 's/^/    /'

# Now the cache should have the file; turn off the server and verify hit.
kill $HTTP_PID 2>/dev/null
wait $HTTP_PID 2>/dev/null
sleep 1
echo
echo "Case B: cache hit (server stopped)"
check "INT" "$BIN/ssc"   ""

# With SSC_NO_NETWORK=1 + cache present, still works.
echo
echo "Case C: SSC_NO_NETWORK=1 with cache hit"
check "INT" "$BIN/ssc"   "SSC_NO_NETWORK=1"

# With SSC_NO_NETWORK=1 + cache empty, should fail.
echo
echo "Case D: SSC_NO_NETWORK=1 with empty cache (should fail)"
rm -rf ~/.cache/ssc
out=$(SSC_NO_NETWORK=1 "$BIN/ssc" /tmp/url-smoke-consumer.ssc 2>&1 || true)
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
