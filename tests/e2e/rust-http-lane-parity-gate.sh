#!/usr/bin/env bash
#
# rust-http-lane-parity-gate — a route handler answers the SAME thing on `run` and on `build-rust`:
# its declared status, and a query value that has been decoded.
#
# TWO USER REPORTS FROM ROZUM, both `impact: blocks`, both measured here on the current tree before
# anything was changed:
#
#   route-handler-lowered-to-string       `std/http.ssc` declares `handler: Request => Response`;
#                                         build-rust lowered the callback as `-> String`, so the
#                                         crate did not compile — E0308, expected String, found
#                                         Response — and a handler could only ever answer 200.
#   query-not-percent-decoded-on-build-rust
#                                         a query value arrived raw: `%3A` stayed `%3A` and `+`
#                                         stayed `+`, while `run` decoded both. Nothing failed; a
#                                         route keyed on the value simply matched nothing.
#
# THE ROWS ASSERT THE WIRE, NOT THE BUILD. "It compiles" was half of the first report and none of
# the second: the property is that a 404 written in the source reaches the client as a 404, and that
# `m=[a:b]` arrives decoded. Both lanes are asked the same question over a real socket and their
# answers are compared to each other — the interpreter is the oracle, so a row that changes both
# lanes together still fails.
#
# WHAT IS DELIBERATELY NOT HERE: an INLINE handler (`route(m, p, req => Response(…))`). It is a
# different defect — the walker cannot see a Request parameter in a bare lambda, so the call is
# emitted against the plain-string runtime entry and the return type is never consulted — and it is
# filed as `rust-inline-route-handler-is-typed-as-a-string-handler` with the three distinct rustc
# errors its three spellings produce. A gate row for it would be red on purpose, which is not a gate.
#
# COST: two cargo builds and four server starts, ~40 s warm. It lives in ci.yml beside the other
# cargo gates rather than on the push path.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "rust-http-lane-parity-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }
command -v curl >/dev/null 2>&1 || { echo "rust-http-lane-parity-gate: needs curl" >&2; exit 2; }

# The probes import std/ by a RELATIVE path, exactly as examples/ do, so they live under examples/.
# Unique per run: two concurrent runs sharing one directory is a race this repo has already paid for.
sandbox=$(mktemp -d "$ROOT/examples/_httppar.XXXXXX")
# PORTS ARE PER RUN, not fixed: `wired-gates-share-hard-coded-tcp-ports` in tests/BUGS.md records a
# gate answering another gate's request. $RANDOM twice, in the ephemeral range, and each probe
# refuses to guess — it curls only after its own port answers.
PORT_A=$(( 20000 + RANDOM % 20000 ))
PORT_B=$(( 20000 + RANDOM % 20000 ))
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

wait_for() { # $1 port — returns 1 if it never came up
  local i
  for i in $(seq 1 60); do
    curl -sS -m 2 -o /dev/null "http://localhost:$1/__ping" 2>/dev/null && return 0
    curl -sS -m 2 -o /dev/null -w '%{http_code}' "http://localhost:$1/" 2>/dev/null | grep -q . && return 0
    sleep 0.5
  done
  return 1
}

echo "── a handler's declared status reaches the client, on both lanes"

cat > "$sandbox/status.ssc" <<SSC
[route, serve, Request, Response](../../std/http.ssc)

def handler(req: Request): Response =
  if req.path == "/ok" then Response(200, Map("Content-Type" -> "text/plain"), "ok")
  else Response(404, Map("Content-Type" -> "text/plain"), "missing")

def main(): Unit =
  route("GET", "/ok", req => handler(req))
  route("GET", "/gone", req => handler(req))
  serve($PORT_A)
SSC

status_of() { # $1 port → "<ok-code> <gone-code> <gone-body>"
  local a b body
  a=$(curl -sS -m 3 -o /dev/null -w '%{http_code}' "http://localhost:$1/ok" 2>/dev/null)
  b=$(curl -sS -m 3 -o /dev/null -w '%{http_code}' "http://localhost:$1/gone" 2>/dev/null)
  body=$(curl -sS -m 3 "http://localhost:$1/gone" 2>/dev/null)
  printf '%s %s %s' "$a" "$b" "$body"
}

run_status=""
"$ssc" run "$sandbox/status.ssc" >"$sandbox/status-run.log" 2>&1 &
run_pid=$!
if wait_for "$PORT_A"; then run_status="$(status_of "$PORT_A")"; else run_status="server never came up"; fi
kill "$run_pid" 2>/dev/null; wait "$run_pid" 2>/dev/null

rust_status=""
if (cd "$sandbox" && timeout 600 "$tools" build-rust "$sandbox/status.ssc" >"$sandbox/status-build.log" 2>&1); then
  (cd "$sandbox" && ./status) >"$sandbox/status-rust.log" 2>&1 &
  rust_pid=$!
  if wait_for "$PORT_A"; then rust_status="$(status_of "$PORT_A")"; else rust_status="server never came up"; fi
  kill "$rust_pid" 2>/dev/null; wait "$rust_pid" 2>/dev/null
else
  rust_status="build-rust FAILED: $(grep -m1 -E 'error\[E[0-9]+\]' "$sandbox/status-build.log")"
fi

WANT_STATUS="200 404 missing"
if [[ "$run_status" == "$WANT_STATUS" && "$rust_status" == "$WANT_STATUS" ]]; then
  echo "  ✓ /ok=200 /gone=404 body=missing on both lanes"
else
  echo "  ✗ status parity: run='$run_status' rust='$rust_status', wanted '$WANT_STATUS' from both"
  fails=$((fails + 1))
fi

echo "── a query value is decoded, on both lanes"

cat > "$sandbox/query.ssc" <<SSC
[route, serve, Request](../../std/http.ssc)

def handler(req: Request): String =
  "m=[" + req.query.get("m").getOrElse("<absent>") + "] q=[" + req.query.get("q").getOrElse("<absent>") + "]"

def main(): Unit =
  route("GET", "/q", req => handler(req))
  serve($PORT_B)
SSC

# `%3A` is the colon a browser sends in a model spec; `+` and `%20` are both a space, and a form
# body uses the first — which is why the decoder serves this and the form path from one function.
QUERY='m=mlx-community%3AQwen3.5-4B-MLX-4bit&q=a+b%20c'
WANT_QUERY='m=[mlx-community:Qwen3.5-4B-MLX-4bit] q=[a b c]'

query_of() { curl -sS -m 3 "http://localhost:$1/q?$QUERY" 2>/dev/null; }

run_query=""
"$ssc" run "$sandbox/query.ssc" >"$sandbox/query-run.log" 2>&1 &
run_pid=$!
if wait_for "$PORT_B"; then run_query="$(query_of "$PORT_B")"; else run_query="server never came up"; fi
kill "$run_pid" 2>/dev/null; wait "$run_pid" 2>/dev/null

rust_query=""
if (cd "$sandbox" && timeout 600 "$tools" build-rust "$sandbox/query.ssc" >"$sandbox/query-build.log" 2>&1); then
  (cd "$sandbox" && ./query) >"$sandbox/query-rust.log" 2>&1 &
  rust_pid=$!
  if wait_for "$PORT_B"; then rust_query="$(query_of "$PORT_B")"; else rust_query="server never came up"; fi
  kill "$rust_pid" 2>/dev/null; wait "$rust_pid" 2>/dev/null
else
  rust_query="build-rust FAILED: $(grep -m1 -E 'error\[E[0-9]+\]' "$sandbox/query-build.log")"
fi

if [[ "$run_query" == "$WANT_QUERY" && "$rust_query" == "$WANT_QUERY" ]]; then
  echo "  ✓ $WANT_QUERY on both lanes"
else
  echo "  ✗ query parity: run='$run_query' rust='$rust_query', wanted '$WANT_QUERY' from both"
  fails=$((fails + 1))
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-http-lane-parity-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-http-lane-parity-gate: PASS"
