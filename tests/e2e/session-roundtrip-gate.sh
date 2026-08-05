#!/usr/bin/env bash
#
# session-roundtrip-gate — a session written by one request must be READABLE by the next, on both
# lanes, and the cookie must be signed.
#
# ── WHY A ROUND TRIP AND NOT "withSession EMITS A COOKIE" ────────────────────────────────────────
#
# Because the half that was missing is the READ half. `NioNativeHttpServerHost` built every request
# with an empty session map, so a correctly signed cookie would have been written and never read
# back: `withSession` would look like it worked and lose the session on the very next request. A
# gate that only checked for a `Set-Cookie` header would have passed that state, which is worse than
# no gate — it certifies the exact failure it exists to prevent.
#
# So the shape is: POST /login writes a session, then GET /me sends the cookie back and must see the
# value. One boot per lane, four requests total.
#
# ── AND WHY IT ASSERTS THE COOKIE IS NOT PLAINTEXT ───────────────────────────────────────────────
#
# The signing is HMAC-SHA256 over SSC_SESSION_SECRET, shared with the v1 lane through
# `scalascript-http-session`. A reimplementation that "worked" by storing the payload verbatim would
# pass a round-trip test and hand every client an editable session. The value must not contain the
# payload in the clear, and it must carry a `.` separating body from signature.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/bin"
PORT="${SSC_SESSION_GATE_PORT:-8801}"
export SSC_NO_BUILD_CHECK=1
# Fixed so the two lanes produce comparable cookies and the run is reproducible.
export SSC_SESSION_SECRET="session-roundtrip-gate-fixed-secret"
echo "── session round trip (both lanes)"

[ -x "$BIN/ssc" ] || { echo "✗ no launcher at $BIN/ssc — build first"; exit 1; }
command -v curl >/dev/null || { echo "✗ curl not available"; exit 1; }

WORK="$(mktemp -d)"
cleanup() { for p in "$PORT" "$(( PORT + 1 ))"; do lsof -ti :"$p" 2>/dev/null | xargs -r kill -9 2>/dev/null; done; rm -rf "$WORK"; }
trap cleanup EXIT

emit() {  # $1 = port
  cat > "$WORK/app-$1.ssc" <<EOF
[route, serve, Response, Request](std/http.ssc)

route("GET", "/login"):
  req =>
    Response.text("logged-in").withSession(Map("user" -> "ada", "role" -> "admin"))

route("GET", "/me"):
  req =>
    Response.text("user=" + req.session.getOrElse("user", "<none>"))

route("GET", "/logout"):
  req =>
    Response.text("bye").clearSession()

serve($1)
EOF
}

fail=0

run_lane() {  # $1 label | $2 port | $3.. launcher argv
  local label="$1" port="$2"; shift 2
  emit "$port"
  lsof -ti :"$port" 2>/dev/null | xargs -r kill -9 2>/dev/null
  ( timeout 120 "$@" "$WORK/app-$port.ssc" > "$WORK/$label.log" 2>&1 & )
  local deadline=$(( $(date +%s) + 60 ))
  until [ -n "$(curl -sS -m 3 "http://localhost:$port/me" 2>/dev/null)" ]; do
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "  ✗ $label: the server never listened"; sed 's/^/      /' "$WORK/$label.log" | head -5; fail=1; return
    fi
    sleep 1
  done

  local cookie
  cookie="$(curl -sS -m 5 -D - -o /dev/null "http://localhost:$port/login" 2>/dev/null \
            | tr -d '\r' | grep -i '^set-cookie:' | head -1 | sed 's/^[^:]*: *//')"

  if [ -z "$cookie" ]; then
    echo "  ✗ $label: /login sent no Set-Cookie"; fail=1
  else
    case "$cookie" in
      *"user=ada"*|*"role=admin"*)
        echo "  ✗ $label: the cookie carries the payload IN THE CLEAR — anyone can edit their session"
        fail=1 ;;
      session=*.*)
        echo "  ✓ $label: /login sets a signed session cookie" ;;
      *)
        echo "  ✗ $label: cookie has no body.signature shape: '${cookie:0:60}'"; fail=1 ;;
    esac
  fi

  # THE ROUND TRIP. Sending the cookie back must produce the value that was stored.
  local body
  body="$(curl -sS -m 5 -H "Cookie: ${cookie%%;*}" "http://localhost:$port/me" 2>/dev/null)"
  if [ "$body" = "user=ada" ]; then
    echo "  ✓ $label: the next request READS the session back"
  else
    echo "  ✗ $label: next request saw '${body:0:40}', expected 'user=ada' — written but not readable"
    fail=1
  fi

  # An unsigned/tampered cookie must not be honoured.
  body="$(curl -sS -m 5 -H "Cookie: session=eyJ1c2VyIjoiZXZlIn0.forged" "http://localhost:$port/me" 2>/dev/null)"
  if [ "$body" = "user=<none>" ]; then
    echo "  ✓ $label: a forged signature is rejected"
  else
    echo "  ✗ $label: forged cookie accepted — saw '${body:0:40}'"; fail=1
  fi

  lsof -ti :"$port" 2>/dev/null | xargs -r kill -9 2>/dev/null
}

run_lane native "$PORT" "$BIN/ssc" run
run_lane v1 "$(( PORT + 1 ))" "$BIN/ssc-tools" run --v1

echo
if [ "$fail" -ne 0 ]; then
  echo "    Both halves have to work: withSession/clearSession in std/http.ssc sign through the"
  echo "    shared scalascript-http-session module, and the host must read the cookie back into"
  echo "    Request.session — it was hard-coded empty, which made the write half look correct."
  echo "✗ session-roundtrip-gate FAILED"
  exit 1
fi
echo "✓ session-roundtrip-gate PASSED"
