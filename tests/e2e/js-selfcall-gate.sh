#!/usr/bin/env bash
#
# A program may call its OWN server on the js lane, and must not be answered by a socket that
# cannot exist.
#
# WHY. `std/http`'s js client is synchronous: it blocks the main thread in `Atomics.wait` while a
# Worker fetches. `serveAsync`'s listener lives on that same main thread, so a request to our own
# port could never be answered — the thread that must run the handler is the one blocked waiting
# for it. Measured before the fix: 11 attempts x 30.5 s plus backoff, then a silent `status 0`
# after ~6.5 minutes. Not a hang; a WRONG ANSWER that reads as an ordinary network failure.
# (BUGS.md js-httppost-to-own-serveasync-deadlocks.)
#
# WHAT THIS PINS, and why each case is here rather than one happy path:
#   1. self-call is SERVED    — the handler's own status and body come back, quickly;
#   2. handler state is SHARED — the handler mutates a program `var` and the caller sees it. This is
#      the case that rules out "just run the listener in a Worker", which would have been the
#      obvious fix and silently breaks exactly this;
#   3. an unrouted self-call 404s — the in-process path must not be MORE forgiving than the socket
#      it replaces, or a test passes in-process and fails deployed;
#   4. a call to a port we do NOT serve is answered BY THAT SERVER — the short-circuit must be
#      narrow. This case runs against a LIVE foreign server on purpose. The first version asserted
#      only `status=0` from an unserved port, which a totally broken http client also produces —
#      and one was hiding right there: `ws-server.mjs` joined the Worker's source with '\\n',
#      a literal backslash-n, so the Worker never parsed, never set the Atomics flag, and EVERY
#      outbound request on this lane returned `status 0 / "timeout"`. A gate whose pass condition
#      is also its failure symptom is not a gate.
#
# The INT lane runs the same fixture as the control: if it fails too, the fixture is wrong rather
# than the js lane.
#
# Usage: tests/e2e/js-selfcall-gate.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SSC="${SSC_BIN:-$ROOT/bin/ssc-tools}"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-selfcall.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

# The fixture lives under the repo so `[…](std/http.ssc)` resolves the same way a real program's
# import does.
FIX="$ROOT/.selfcall-gate-fixture.ssc"
trap 'rm -rf "$TMP"; rm -f "$FIX"' EXIT

# A real server on the foreign port, so case 4 can tell "not hijacked" from "client is broken".
FOREIGN_PORT=19764
cat > "$TMP/foreign.cjs" <<'EOF'
const http = require('http');
http.createServer((_q, s) => { s.writeHead(200); s.end('foreign-ok'); }).listen(Number(process.argv[2]));
EOF
node "$TMP/foreign.cjs" "$FOREIGN_PORT" &
FOREIGN_PID=$!
trap 'rm -rf "$TMP"; rm -f "$FIX"; kill $FOREIGN_PID 2>/dev/null || true' EXIT
for _ in 1 2 3 4 5 6 7 8 9 10; do
  node -e 'require("http").get("http://127.0.0.1:"+process.argv[1]+"/",r=>process.exit(r.statusCode===200?0:1)).on("error",()=>process.exit(1))' "$FOREIGN_PORT" && break
  sleep 0.3
done || { echo 'js-selfcall-gate: FAIL — the foreign control server never came up' >&2; exit 1; }

cat > "$FIX" <<'EOF'
# js self-call gate fixture

[route, serveAsync, stop, httpTimeout, httpPost, httpGet, Request, Response](std/http.ssc)

```scalascript
val port = 19763
var hits = 0

// `Response` takes THREE arguments — (status, headers, body). Written with two, the body string
// lands in `headers` and the response body is empty, which is how the first version of this gate
// "failed" against a correct implementation. The fixture is part of the apparatus; it gets the
// signature right or it measures itself.
route("POST", "/echo") { req =>
  hits = hits + 1
  Response(201, Map("Content-Type" -> "text/plain"), "echo:" + req.body + ":hits=" + hits.toString)
}

serveAsync(port)
httpTimeout(1500)

val a = httpPost("http://localhost:" + port + "/echo", "one", List())
println("A status=" + a.status.toString + " body=" + a.body)

val b = httpPost("http://localhost:" + port + "/echo", "two", List())
println("B status=" + b.status.toString + " body=" + b.body)

val c = httpGet("http://localhost:" + port + "/no-such-route", List())
println("C status=" + c.status.toString)

val d = httpGet("http://127.0.0.1:19764/elsewhere", List())
println("D status=" + d.status.toString + " body=" + d.body)

stop()
println("done")
```
EOF

fail() { printf 'js-selfcall-gate: FAIL — %s\n\n--- output ---\n%s\n' "$1" "$2" >&2; rm -f "$FIX"; exit 1; }

# A self-call that is NOT short-circuited burns 11 x (timeout+500) plus backoff. With
# httpTimeout(1500) that is ~40 s, so a 25 s budget fails loudly on a regression instead of
# looking slow.
run_lane() { # $1 = run|run-js  → prints output, non-zero on timeout
  SSC_NO_BUILD_CHECK=1 timeout 25 "$SSC" $1 "$FIX" 2>&1
}

js="$(run_lane run-js || true)"
grep -q 'A status=201 body=echo:one:hits=1' <<<"$js" || fail 'self-call was not served (case 1)' "$js"
grep -q 'B status=201 body=echo:two:hits=2' <<<"$js" || fail 'handler state is not shared with the caller (case 2)' "$js"
grep -q 'C status=404'                      <<<"$js" || fail 'an unrouted self-call did not 404 (case 3)' "$js"
grep -q 'D status=200 body=foreign-ok'      <<<"$js" || fail 'a call to a LIVE foreign server was not answered by it — either the short-circuit hijacked it, or the outbound client is broken (case 4)' "$js"
grep -q 'done'                              <<<"$js" || fail 'the program did not reach stop()' "$js"

# Control: the same fixture on the interpreter. If this fails, the fixture is wrong, not the lane.
int="$(SSC_NO_BUILD_CHECK=1 timeout 60 "$SSC" run --v1 "$FIX" 2>&1 || true)"
grep -q 'A status=201'                 <<<"$int" || fail 'CONTROL: the fixture does not work on the interpreter either' "$int"
grep -q 'D status=200 body=foreign-ok' <<<"$int" || fail 'CONTROL: the foreign server is not reachable from the interpreter either' "$int"

rm -f "$FIX"
printf 'js-selfcall-gate: OK (served, state shared, 404 preserved, live foreign server answered by itself)\n'
