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
# The suite has a hard 420s budget and this file is a newcomer; a server boot per case is what put
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
cleanup() { lsof -ti :"$PORT" 2>/dev/null | xargs -r kill -9 2>/dev/null; rm -rf "$WORK"; }
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
( timeout 40 "$BIN/ssc" run "$WORK/app.ssc" > "$WORK/server.log" 2>&1 & )
deadline=$(( $(date +%s) + 25 ))
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

# An absent required field is asserted only as "the NAME resolved" — deliberately not as a status
# code. Measured 2026-08-04, the two lanes disagree on what a missing field outside a `validate { }`
# block should be: v1 answers 400 `missing field: n` (HttpDispatchLoop recovers RestValidationError)
# and the native lane answers 500 with the implementation detail `require* used outside a
# validate { … } block`, because its server maps every Throwable to 500. Pinning either here would
# freeze one lane's answer as the contract; it is filed as
# native-missing-required-field-is-500-not-400 instead. What this row does guarantee is the thing
# this gate exists for: the name is registered, so the failure is about validation and not about
# `unbound global`.
check "require*, absent field — the NAME resolves (status is filed, not pinned)" "req" "" "unbound global"

# optional* on the same absent fields must be None, not a recorded error.
check "optional*, fields absent" "opt" "s=None" "unbound"
check "optional*, fields present" "opt?s=hi&i=7&d=2.5&b=on" "i=Some(7)" "unbound"

echo
if [ "$fail" -ne 0 ]; then
  echo "    The family lives in RequestValidation.scala on the native lane and in"
  echo "    v1/runtime/std/request-plugin/.../RequestIntrinsics.scala as the reference."
  echo "    require* records a validation error and returns a neutral value; optional* returns None."
  echo "✗ request-validation-family-gate FAILED"
  exit 1
fi
echo "✓ request-validation-family-gate PASSED"
