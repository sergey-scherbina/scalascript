#!/usr/bin/env bash
#
# rust-serve-panic-gate — one panicking handler costs one request, not the server.
#
# THE REPORT (rozum, `rust-serve-dies-permanently-after-one-handler-panic`, `impact: blocks`). A
# handler read a `.jsonl` file and passed each line to `jsonParse`; the last line was empty — every
# file written by appending has one — so the handler panicked. The process then STAYED UP and
# answered nothing, for good:
#
#     curl /ok    -> alive
#     curl /boom  -> (nothing; the handler panicked)
#     curl /ok    -> (nothing, and never again)
#
# with an unbounded run of `PoisonError` in the log. From outside: healthy port, healthy process, no
# answers — the hardest failure shape to diagnose, from one malformed byte a caller sent.
#
# THE MECHANISM WAS THE LOCK, not the panic. The handler was called INSIDE
# `let guard = routes().lock()`, so a panic unwound through the guard and poisoned the mutex;
# every later request, on any route, then died on `lock().unwrap()`. The fix drops the lock before
# the call (the chosen handler is an `Arc`, so taking it out is a refcount bump) and catches the
# panic at the route boundary.
#
# ROW 2 IS 500 AND ROW 4 IS 404, AND THE DIFFERENCE IS DELIBERATE. My first version answered 404 for
# the panicking route, because `catch_unwind(...).ok()` made it indistinguishable from "no such
# route" — a second wrong answer in place of the first. A registered route that failed says 500; a
# path that was never registered says 404.
#
# ROWS 3 AND 5 ARE THE POINT OF THE WHOLE GATE: the same `/ok` that worked before the panic must
# work after it, and after an unrelated 404 too. A gate that only checked the panicking route would
# pass on a server that answers nothing afterwards.
#
# COST: one cargo build and five requests, ~60 s. Lives in ci.yml with the other cargo gates.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "rust-serve-panic-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }

if ! command -v cargo >/dev/null 2>&1; then
  echo "rust-serve-panic-gate: [skip] cargo is not on PATH. That is a SKIP, not a pass." >&2
  exit 0
fi

# Inside examples/ so the std imports stay relative — an absolute import path drops the case classes
# it names on this lane (rust-absolute-import-path-inlines-nothing).
sandbox=$(mktemp -d "$ROOT/examples/_servepanic.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

port=$(( 26000 + RANDOM % 2000 ))
{ printf '[route, serve, Request, Response](../../std/http.ssc)\n'
  printf '[jsonParse](../../std/json.ssc)\n'
  printf 'val TEXT: Map[String, String] = Map("Content-Type" -> "text/plain")\n'
  printf 'def okRoute(req: Request): Response = Response(200, TEXT, "alive")\n'
  printf 'def boomRoute(req: Request): Response =\n'
  printf '  val v = jsonParse("")\n'
  printf '  Response(200, TEXT, "never reached")\n'
  printf 'def main(): Unit =\n'
  printf '  route("GET", "/ok", r => okRoute(r))\n'
  printf '  route("GET", "/boom", r => boomRoute(r))\n'
  printf '  serve(%d)\n' "$port"
  printf 'main()\n'
} > "$sandbox/srv.ssc"

if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/srv.ssc" >"$sandbox/build.log" 2>&1); then
  echo "  ✗ build failed: $(grep -m1 -E 'error\[E[0-9]+\]|^error|Generic\(' "$sandbox/build.log" | cut -c1-90)" >&2
  exit 1
fi

"$sandbox/srv" >"$sandbox/srv.out" 2>&1 &
srv=$!
cleanup() { kill "$srv" 2>/dev/null; wait "$srv" 2>/dev/null; rm -rf "$sandbox"; }
trap cleanup EXIT HUP INT TERM

for _ in $(seq 1 60); do
  curl -sS -m2 -o /dev/null "http://127.0.0.1:$port/ok" 2>/dev/null && break
  sleep 0.5
done

row() { # $1 label, $2 path, $3 wanted status
  local label=$1 path=$2 want=$3 got
  got=$(curl -sS -m5 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port$path" 2>/dev/null)
  if [[ "$got" == "$want" ]]; then
    echo "  ✓ $label: $path -> $got"
  else
    echo "  ✗ $label: $path -> '${got:-<no answer>}', wanted $want"
    fails=$((fails + 1))
  fi
}

echo "── one panicking handler costs one request, not the server"
row "before      " /ok     200
row "the panic   " /boom   500
row "after       " /ok     200
row "missing path" /nosuch 404
row "still after " /ok     200

if ps -p "$srv" >/dev/null 2>&1; then
  echo "  ✓ the process is still up"
else
  echo "  ✗ the process died — the panic took the server with it"
  fails=$((fails + 1))
fi
poison=$(grep -c 'PoisonError' "$sandbox/srv.out" 2>/dev/null || true)
if [[ "${poison:-0}" -eq 0 ]]; then
  echo "  ✓ no PoisonError in the log"
else
  echo "  ✗ $poison PoisonError line(s) — the mutex is poisoned and routing is dead"
  fails=$((fails + 1))
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-serve-panic-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-serve-panic-gate: PASS"
