#!/usr/bin/env bash
#
# route-handler-shapes-gate — every spelling of a route handler that works today must keep working,
# and the one that only worked on v1 must work on the native lane too.
#
# `route(m, p) { … }` hands the plugin the BLOCK. When the block is literally a lambda it IS the
# handler; when it is any other expression it is a 0-arity THUNK whose result is the handler.
# Registering the thunk made the server call it with the request:
#
#     500 native HTTP handler failed: native callback arity: 0 expected, 1 given
#
# ── WHY THIS GATE IS A TABLE AND NOT A REGRESSION TEST ───────────────────────────────────────────
#
# The obvious fix — unwrap any handler that is not arity 1 — would have broken two spellings that
# already worked, and a single-case test would have shipped that. The census that shaped the fix:
#
#   route(m, p, h)                 native ok      v1 server does not start
#   route(m, p): req => …          both ok
#   route(m, p) { req => … }       both ok
#   route(m, p) { <a Response> }   BOTH fail      <- not a supported shape, and must STAY an error
#   route(m, p) { wrap { … } }     native failed, v1 ok
#
# Only the last row was the defect. The fourth row is in this file deliberately: it asserts that a
# block whose value is not callable still fails, because "make the block form work" without that
# row reads as licence to evaluate anything and hope.
#
# Rows are asserted on the RESPONSE BODY over a real socket. Reading the launcher's combined output
# would be a false pass — a diagnostic quotes the offending source line, so a body marker like `S5`
# appears in the ERROR TEXT of the very program that failed to serve it.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/bin"
PORT="${SSC_ROUTE_SHAPES_PORT:-8793}"
export SSC_NO_BUILD_CHECK=1
echo "── route handler shapes (native lane)"

[ -x "$BIN/ssc" ] || { echo "✗ no launcher at $BIN/ssc — build first"; exit 1; }
command -v curl >/dev/null || { echo "✗ curl not available"; exit 1; }

WORK="$(mktemp -d)"
cleanup() { lsof -ti :"$PORT" 2>/dev/null | xargs -r kill -9 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT

# ONE program, five paths, ONE boot. This gate cost 45.9 s of a 420 s suite budget when each row
# booted its own server (tests/BUGS.md smoke-suite-over-its-own-budget names it), and the boot was
# the whole expense -- the assertions are five HTTP round trips. The shapes are independent of each
# other, so they cost one process between them.
cat > "$WORK/app.ssc" <<EOF
[route, serve, Response, Request](std/http.ssc)

def wrap(handler: Request => Response): Request => Response = req => handler(req)

def named(req: Request): Response = Response.html("arg3")

route("GET", "/arg3", named)

route("GET", "/colon"):
  req =>
    Response.html("colon")

route("GET", "/blocklambda") { req =>
  Response.html("blocklambda")
}

route("GET", "/blockexpr") {
  wrap { req => Response.html("blockexpr") }
}

route("GET", "/blockvalue") {
  Response.html("blockvalue")
}

serve($PORT)
EOF

start_server() {
  lsof -ti :"$PORT" 2>/dev/null | xargs -r kill -9 2>/dev/null
  sleep 1
  ( timeout 120 "$BIN/ssc" run "$WORK/app.ssc" > "$WORK/server.log" 2>&1 & )
  local deadline=$(( $(date +%s) + 60 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    [ -n "$(curl -sS -m 3 "http://localhost:$PORT/colon" 2>/dev/null)" ] && return 0
    sleep 1
  done
  return 1
}

get() { curl -sS -m 4 "http://localhost:$PORT/$1" 2>/dev/null; }

if ! start_server; then
  echo "✗ the server never listened — no row below could be measured"
  sed 's/^/    /' "$WORK/server.log" | head -6
  exit 1
fi

fail=0

# name | must the body equal the name?  (no = must NOT serve it)
for row in "arg3|yes" "colon|yes" "blocklambda|yes" "blockexpr|yes" "blockvalue|no"; do
  IFS='|' read -r name want <<< "$row"
  body="$(get "$name")"
  if [ "$want" = "yes" ]; then
    if [ "$body" = "$name" ]; then echo "  ✓ $name serves its handler"
    else
      echo "  ✗ $name — body was '${body:0:60}', expected '$name'"; fail=1
    fi
  else
    if [ "$body" = "$name" ]; then
      echo "  ✗ $name — a block whose value is not callable now serves; the unwrap went too far"
      fail=1
    else
      echo "  ✓ $name still refuses (a non-callable block is not a handler)"
    fi
  fi
done

echo
if [ "$fail" -ne 0 ]; then
  echo "    The block form passes a 0-arity THUNK whose result is the handler; unwrap exactly one"
  echo "    level and only when arity is 0 (HttpFastNativePlugin, native \"route\")."
  echo "✗ route-handler-shapes-gate FAILED"
  exit 1
fi
echo "✓ route-handler-shapes-gate PASSED"
