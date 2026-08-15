#!/usr/bin/env bash
#
# http-bind-address-gate — `SSC_HTTP_BIND` means the same thing on every lane, and a value that
# cannot be resolved stops the server instead of binding wider than asked.
#
# THE REPORT (rozum, `serve-binds-all-interfaces`): `serve(port)` bound `0.0.0.0` with no way to say
# otherwise, so four live .ssc services were LAN-reachable beside Rust services that bound loopback,
# and a console route moving from Rust to .ssc would have widened its exposure as a side effect of a
# refactor meant to preserve behaviour.
#
# WHAT THE REPORT DID NOT CONTAIN, and what this gate exists for: the lanes did not agree with each
# other either. Measured with `lsof` on one four-line server, one port, before any change:
#
#   run (v2 native)     127.0.0.1     <- the DEFAULT lane was the odd one out
#   --v1 (interpreter)  *
#   build-rust          *
#
# `FastHttpServer` defaults `host` to loopback and the native host lets it bind; the other two
# callers build their own socket with the WILDCARD constructor and never reach that default. The
# difference fell out of which caller made the socket — nothing recorded it as a decision.
#
# SO THE ROWS ASSERT THE KNOB, NOT THE DEFAULT. Each lane keeps the default it had, because changing
# one is a product decision and not a bug fix (`ssc run` becoming LAN-visible on upgrade and a
# deployed service going dark on upgrade are both wrong surprises, in opposite directions). What
# must hold is that a program CAN say where it binds, that saying it works identically everywhere,
# and that a bad value is loud. The remaining default divergence is filed with this measurement.
#
# ASSERTED FROM THE OPERATING SYSTEM, via `lsof`, not from a log line: the property is which socket
# exists, and a server that prints "Listening on 0.0.0.0" while bound to loopback would satisfy any
# check that reads its output.
#
# TWO SPELLINGS ON PURPOSE. `route(m, p, handler)` — the form `std/http.ssc` declares — is what the
# native and Rust lanes accept; `--v1` refuses it and takes only `route(m, p) { handler }`, which in
# turn does not build on Rust. That is not this gate's subject: it is
# `rust-inline-route-handler-is-typed-as-a-string-handler`, and each row here uses the spelling its
# lane accepts so that a failure means a BIND failure.
#
# COST: one cargo build and eight short-lived servers, ~60 s. It lives in ci.yml with the other
# cargo gates.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "http-bind-address-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }
command -v lsof >/dev/null 2>&1 || { echo "http-bind-address-gate: needs lsof to read the bound socket" >&2; exit 2; }
command -v curl >/dev/null 2>&1 || { echo "http-bind-address-gate: needs curl" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_bindgate.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

# A port per RUN, not a constant: `wired-gates-share-hard-coded-tcp-ports` in tests/BUGS.md records
# one gate answering another's request, and CI runs jobs in parallel.
PORT=$(( 20000 + RANDOM % 20000 ))

cat > "$sandbox/decl.ssc" <<SSC
[route, serve, Request](../../std/http.ssc)
def handler(req: Request): String = "ok"
def main(): Unit =
  route("GET", "/b", req => handler(req))
  serve($PORT)
SSC
cat > "$sandbox/block.ssc" <<SSC
[route, serve, Request](../../std/http.ssc)
def handler(req: Request): String = "ok"
def main(): Unit =
  route("GET", "/b") { req => handler(req) }
  serve($PORT)
SSC

# The bound address as the OS reports it, reduced to `loopback` / `wildcard` so the row reads as the
# property rather than as an address format (`*:8080`, `127.0.0.1:8080`, `[::1]:8080`).
bound_kind() {
  local socks
  socks=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {print $9}' | sort -u)
  if [[ -z "$socks" ]]; then printf 'nothing listening'
  elif printf '%s\n' "$socks" | grep -q '^\*:'; then printf 'wildcard'
  elif printf '%s\n' "$socks" | grep -qE '^(127\.0\.0\.1|\[::1\]):'; then printf 'loopback'
  else printf 'other(%s)' "$(printf '%s' "$socks" | tr '\n' ' ')"
  fi
}

# Starts a server, waits for ITS OWN port to answer, reads the socket, stops it.
# `env VAR=v cmd`, never `VAR=v "$@"`: an assignment prefix applies to a SIMPLE command, and with
# the command coming from `"$@"` bash read `SSC_HTTP_BIND=0.0.0.0` as the program name and reported
# `command not found` — three rows failed as "nothing listening" and the defect was in the harness.
observe() { # $1 label, $2 want, $3.. the command
  local label=$1 want=$2; shift 2
  "$@" >"$sandbox/$label.log" 2>&1 &
  local pid=$! got i
  for i in $(seq 1 60); do
    curl -sS -m 2 -o /dev/null "http://127.0.0.1:$PORT/b" 2>/dev/null && break
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.5
  done
  got="$(bound_kind)"
  kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
  sleep 0.3
  if [[ "$got" == "$want" ]]; then
    echo "  ✓ $label: $got"
  else
    echo "  ✗ $label: $got, wanted $want"
    tail -3 "$sandbox/$label.log" | sed 's/^/        /'
    fails=$((fails + 1))
  fi
}

echo "── the default follows what the COMMAND IS FOR, and SSC_HTTP_BIND is honoured by all of them"

# THE RULE THESE ROWS PIN, decided by the owner 2026-08-15 and no longer an accident of which caller
# builds the socket:
#
#     run-it-now commands  -> loopback     `ssc run`, `ssc-tools run --v1`
#     a built artifact     -> wildcard     `build-rust`
#
# Both blanket answers cost something — all-loopback takes deployed services dark on upgrade,
# all-wildcard puts the dev-loop command on whatever network its user is on — and sorting by purpose
# pays neither. `v1 default` changed from wildcard to loopback here; the `rust default` row below is
# deliberately still wildcard, and that asymmetry is the decision rather than an oversight.
observe "run default"        loopback env -u SSC_HTTP_BIND "$ssc" run "$sandbox/decl.ssc"
observe "run bind=0.0.0.0"   wildcard env SSC_HTTP_BIND=0.0.0.0   "$ssc" run "$sandbox/decl.ssc"
observe "v1 default"         loopback env -u SSC_HTTP_BIND "$tools" run --v1 "$sandbox/block.ssc"
observe "v1 bind=0.0.0.0"    wildcard env SSC_HTTP_BIND=0.0.0.0   "$tools" run --v1 "$sandbox/block.ssc"

if ! command -v cargo >/dev/null 2>&1; then
  echo "  [skip] cargo is not on PATH — the Rust rows cannot run. That is a SKIP, not a pass."
elif ! (cd "$sandbox" && timeout 600 "$tools" build-rust "$sandbox/decl.ssc" >"$sandbox/build.log" 2>&1); then
  echo "  ✗ build-rust failed: $(grep -m1 -E 'error\[E[0-9]+\]' "$sandbox/build.log")"
  fails=$((fails + 1))
else
  observe "rust default"       wildcard env -u SSC_HTTP_BIND "$sandbox/decl"
  observe "rust bind=127.0.0.1" loopback env SSC_HTTP_BIND=127.0.0.1 "$sandbox/decl"
fi

echo "── an address the host cannot resolve stops the server"

# The message differs between a JVM lane and Rust and is NOT asserted; what is asserted is that the
# server refuses to start and says the variable's name, so the operator can act on it. A silent
# fallback to the wildcard is the failure this whole change exists to prevent.
refuses() { # $1 label, $2.. command
  local label=$1; shift
  local out rc
  out=$(env SSC_HTTP_BIND='не-адрес' timeout 90 "$@" 2>&1); rc=$?
  if [[ "$rc" -ne 0 ]] && grep -q 'SSC_HTTP_BIND' <<<"$out"; then
    echo "  ✓ $label: refused — $(grep -o 'SSC_HTTP_BIND[^"]*"[^"]*"[^,]*' <<<"$out" | head -1)"
  else
    echo "  ✗ $label: exit=$rc and no SSC_HTTP_BIND in the output"
    printf '%s\n' "$out" | head -3 | sed 's/^/        /'
    fails=$((fails + 1))
  fi
}

refuses "v1 bad value" "$tools" run --v1 "$sandbox/block.ssc"
if command -v cargo >/dev/null 2>&1 && [[ -x "$sandbox/decl" ]]; then
  refuses "rust bad value" "$sandbox/decl"
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "http-bind-address-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "http-bind-address-gate: PASS"
