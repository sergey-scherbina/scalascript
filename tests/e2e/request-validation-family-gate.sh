#!/usr/bin/env bash
#
# request-validation-family-gate — every name in the request-validation family must exist on the
# native lane, and require* must differ from optional* in the way that matters.
#
# The v1 request-plugin family is ELEVEN names. The native lane had FOUR, so a handler calling any
# of the other seven died with
#
#     500 native HTTP handler failed: unbound global: requireInt
#
# which reads like a scoping problem and is not one — it is a name that was never registered.
#
# ── WHAT IT ASSERTS BEYOND "THE NAME RESOLVES" ───────────────────────────────────────────────────
#
# require* and optional* differ on exactly one axis, and it is the axis a re-implementation gets
# wrong: on a MISSING or UNPARSEABLE field, require* records a validation error and returns a
# neutral value so `validate { … }` can collect every field's complaint in one pass, while
# optional* returns None and is not an error at all. A gate that only checked "requireInt(req, "n")
# returns 7 for n=7" would pass an implementation that rejects every valid optional field.
#
# So each name is probed twice — present and absent — over one boot, on one program, five paths.
# The suite has a hard budget (500s since 2026-08-04) and this file is a newcomer; a server boot per case is what put
# two earlier gates 88s into it (tests/BUGS.md smoke-suite-over-its-own-budget).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/bin"
PORT="${SSC_REQUEST_VALIDATION_PORT:-8797}"
export SSC_NO_BUILD_CHECK=1
echo "── request validation family (native lane)"

[ -x "$BIN/ssc" ] || { echo "✗ no launcher at $BIN/ssc — build first"; exit 1; }
command -v curl >/dev/null || { echo "✗ curl not available"; exit 1; }

WORK="$(mktemp -d)"
cleanup() { for p in "$PORT" "$(( PORT + 1 ))"; do lsof -ti :"$p" 2>/dev/null | xargs -r kill -9 2>/dev/null; done; rm -rf "$WORK"; }
trap cleanup EXIT

cat > "$WORK/app.ssc" <<EOF
[route, serve, Response, Request](std/http.ssc)

route("GET", "/req"):
  req =>
    Response.text(
      "s=" + requireString(req, "s") +
      " i=" + requireInt(req, "i").toString +
      " d=" + requireDouble(req, "d").toString +
      " b=" + requireBool(req, "b").toString)

route("GET", "/opt"):
  req =>
    Response.text(
      "s=" + optionalString(req, "s").toString +
      " i=" + optionalInt(req, "i").toString +
      " d=" + optionalDouble(req, "d").toString +
      " b=" + optionalBool(req, "b").toString)

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
until [ -n "$(curl -sS -m 3 "http://localhost:$PORT/req?s=a&i=1&d=2.5&b=yes" 2>/dev/null)" ]; do
  [ "$(date +%s)" -ge "$deadline" ] && { echo "✗ the server never listened"; sed 's/^/    /' "$WORK/server.log" | head -6; exit 1; }
  sleep 1
done

fail=0
check() {  # $1 label | $2 query | $3 substring that must be present | $4 substring that must be ABSENT
  local body
  body="$(curl -sS -m 4 "http://localhost:$PORT/$2" 2>/dev/null)"
  if [ -z "$3" ]; then
    if [ -n "$body" ]; then echo "  ✓ $1"
    else echo "  ✗ $1 — empty response; the handler produced nothing at all"; fail=1; fi
  elif printf '%s' "$body" | grep -qF "$3"; then echo "  ✓ $1"
  else echo "  ✗ $1 — '${body:0:74}' lacks '$3'"; fail=1; fi
  if [ -n "${4:-}" ] && printf '%s' "$body" | grep -qF "$4"; then
    echo "  ✗ $1 — '$4' present; an unbound name serves as a sentinel or an error string"; fail=1
  fi
}

# All four require* names resolve AND parse.
check "require*, fields present" "req?s=hi&i=7&d=2.5&b=yes" "s=hi i=7 d=2.5 b=true" "unbound"

# A missing required field is a CLIENT error and both lanes must say so identically. This row was
# once deliberately unpinned, because the lanes disagreed — v1 answered 400 `missing field: n`
# while the native lane answered 500 with the name of an internal block form — and pinning either
# would have frozen one lane's answer as the contract. They agree now, so it is pinned, on BOTH,
# with the status AND the body: the whole defect was that the reply carried an implementation
# detail at the wrong status, and asserting only the code would let that text come back.
check_status() {  # $1 label | $2 lane-launcher-args | $3 path | $4 expected status | $5 expected body substring
  local out code body
  out="$(curl -sS -m 4 -w '\n%{http_code}' "http://localhost:$2/$3" 2>/dev/null)"
  code="$(printf '%s' "$out" | tail -1)"
  body="$(printf '%s' "$out" | sed '$d')"
  if [ "$code" = "$4" ] && printf '%s' "$body" | grep -qF "$5"; then
    echo "  ✓ $1  ($4, '$5')"
  else
    echo "  ✗ $1 — got $code '${body:0:64}', expected $4 containing '$5'"; fail=1
  fi
  case "$body" in
    *"validate {"*|*"require* used outside"*)
      echo "  ✗ $1 — the body names an internal block form; that is for the developer, not the caller"
      fail=1 ;;
  esac
}

check_status "native: absent required field is a client error" "$PORT" "req" 400 "missing field: s"

# THE SAME ROW ON v1. The defect this pins was a DIVERGENCE, so a gate that only watches the lane
# that was wrong cannot see it come back the other way — if v1 later starts answering 500 here,
# a native-only check stays green while the pair is broken again. v1 is a second boot and this
# suite has a hard budget, so it is exactly one request: the one that differed.
V1_PORT=$(( PORT + 1 ))
sed "s/serve($PORT)/serve($V1_PORT)/" "$WORK/app.ssc" > "$WORK/app-v1.ssc"
lsof -ti :"$V1_PORT" 2>/dev/null | xargs -r kill -9 2>/dev/null
( timeout 120 "$BIN/ssc-tools" run --v1 "$WORK/app-v1.ssc" > "$WORK/server-v1.log" 2>&1 & )
v1_deadline=$(( $(date +%s) + 60 ))
v1_up=0
while [ "$(date +%s)" -lt "$v1_deadline" ]; do
  if [ -n "$(curl -sS -m 3 "http://localhost:$V1_PORT/req?s=a&i=1&d=2.5&b=yes" 2>/dev/null)" ]; then v1_up=1; break; fi
  sleep 1
done
if [ "$v1_up" = "1" ]; then
  check_status "v1: absent required field is a client error" "$V1_PORT" "req" 400 "missing field: s"
else
  echo "  ✗ v1 lane never listened — the parity half of this gate did not run"
  sed 's/^/    /' "$WORK/server-v1.log" | head -5
  fail=1
fi
lsof -ti :"$V1_PORT" 2>/dev/null | xargs -r kill -9 2>/dev/null

# optional* on the same absent fields must be None, not a recorded error.
check "optional*, fields absent" "opt" "s=None" "unbound"
check "optional*, fields present" "opt?s=hi&i=7&d=2.5&b=on" "i=Some(7)" "unbound"

echo
if [ "$fail" -ne 0 ]; then
  echo "    The family lives in RequestValidation.scala on the native lane and in"
  echo "    v1/runtime/plugins/request-plugin/.../RequestIntrinsics.scala as the reference."
  echo "    require* records a validation error and returns a neutral value; optional* returns None."
  echo "✗ request-validation-family-gate FAILED"
  exit 1
fi
echo "✓ request-validation-family-gate PASSED"
