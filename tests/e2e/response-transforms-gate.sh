#!/usr/bin/env bash
#
# response-transforms-gate — `resp.withHeader(n, v)` must reach the wire, and a not-implemented
# Response member must never be served as a 200.
#
# The native lane had no Response transforms at all — only the factories (html/text/json/redirect/
# notFound/status/apply). `std/middleware.ssc`'s `withTiming` ends in
# `resp.withHeader("X-Response-Time-Ms", ms)`, so every timing middleware returned the
# not-implemented sentinel:
#
#     DataV(Stub,Vector(StrV(Response.withHeader)))
#
# ── THE SECOND ASSERTION IS THE POINT ────────────────────────────────────────────────────────────
#
# That sentinel arrived at the client as the response BODY, with status 200 and the process exiting
# 0. Nothing about the transport said anything was wrong — a gate watching exit codes, or status
# codes, sees a healthy server. So this file asserts the header is really on the wire AND that no
# response body is a printed sentinel, because the first assertion alone would go green again the
# moment some other member stubs out.
#
# Asserted over a real socket against the response HEADERS, never the launcher's combined output: a
# diagnostic quotes the offending source line, so a marker string matches inside the error text of
# the very program that failed to serve it.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/bin"
PORT="${SSC_RESPONSE_TRANSFORMS_PORT:-8795}"
export SSC_NO_BUILD_CHECK=1
echo "── response transforms reach the wire (native lane)"

[ -x "$BIN/ssc" ] || { echo "✗ no launcher at $BIN/ssc — build first"; exit 1; }
command -v curl >/dev/null || { echo "✗ curl not available"; exit 1; }

WORK="$(mktemp -d)"
cleanup() { lsof -ti :"$PORT" 2>/dev/null | xargs -r kill -9 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT

cat > "$WORK/app.ssc" <<EOF
[route, serve, Response, Request](std/http.ssc)

route("GET", "/one"):
  req =>
    Response.html("body-one").withHeader("X-Probe", "alpha")

route("GET", "/two"):
  req =>
    Response.html("body-two").withHeader("X-Probe", "alpha").withHeader("X-Second", "beta")

route("GET", "/replace"):
  req =>
    Response.html("body-replace").withHeader("X-Probe", "first").withHeader("X-Probe", "second")

serve($PORT)
EOF

lsof -ti :"$PORT" 2>/dev/null | xargs -r kill -9 2>/dev/null
# The boot deadline is 60s and the server's own timeout 120s. Both are CEILINGS: the poll loop
# leaves the moment the server answers, so on an idle host this costs nothing — measured 4s to
# listen. They were 22-25s and 30-40s, which is fine alone and wrong inside the suite: under a
# host running several agents' builds this gate reported `✗ server never listened` at 22.7s and
# 25.4s while PASSING standalone in 5s. A boot timeout that prints "never listened" reads as a
# product failure, which is the same fault I diagnosed in six other gates: a deadline sized on an
# idle host, reported as if it were the defect being hunted.
( timeout 120 "$BIN/ssc" run "$WORK/app.ssc" > "$WORK/server.log" 2>&1 & )

deadline=$(( $(date +%s) + 60 ))
until [ -n "$(curl -sS -m 3 "http://localhost:$PORT/one" 2>/dev/null)" ]; do
  [ "$(date +%s)" -ge "$deadline" ] && { echo "✗ server never listened"; sed 's/^/    /' "$WORK/server.log" | head -5; exit 1; }
  sleep 1
done

fail=0
check() {  # $1 path | $2 header | $3 expected value | $4 expected body
  local hdrs body got
  hdrs="$(curl -sS -m 4 -D - -o "$WORK/body.txt" "http://localhost:$PORT/$1" 2>/dev/null)"
  body="$(cat "$WORK/body.txt")"
  got="$(printf '%s' "$hdrs" | tr -d '\r' | grep -i "^$2:" | head -1 | sed "s/^[^:]*: *//")"
  if [ "$got" = "$3" ]; then echo "  ✓ /$1  $2: $3"
  else echo "  ✗ /$1  $2 was '${got:-<absent>}', expected '$3'"; fail=1; fi
  if [ "$body" = "$4" ]; then echo "  ✓ /$1  body intact"
  else echo "  ✗ /$1  body was '${body:0:70}', expected '$4'"; fail=1; fi
  # A stubbed member serves its sentinel as a 200 body — status and exit code both look healthy,
  # so the sentinel itself has to be the thing asserted against.
  case "$body" in
    *Stub*|*"DataV("*) echo "  ✗ /$1  the body is a not-implemented SENTINEL, served as a normal response"; fail=1 ;;
  esac
}

check one     X-Probe  alpha  body-one
check two     X-Second beta   body-two
check replace X-Probe  second body-replace   # last write wins, as in the v1 reference

echo
if [ "$fail" -ne 0 ]; then
  echo "    Response transforms are natives taking the receiver as argument 0, like cacheable/noCache"
  echo "    (HttpFastNativePlugin). v1's semantics are in HttpModel.scala: replace on collision."
  echo "✗ response-transforms-gate FAILED"
  exit 1
fi
echo "✓ response-transforms-gate PASSED"
