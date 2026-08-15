#!/usr/bin/env bash
#
# rust-route-handler-shapes-gate — a route handler that ANSWERS a `Response` takes the Request entry
# no matter which of the four ways a program spells it, and a handler that answers a String still
# takes the String entry.
#
# THE REPORT (rozum, `rust-inline-route-handler-is-typed-as-a-string-handler`). `std/http.ssc`
# declares `handler: Request => Response`, and the doc example is written inline — but the target
# chooser read only the lambda's PARAMETER ANNOTATION. An unannotated `req =>` is what everybody
# writes, so the call went to `_http_route`, the plain String entry, and rustc refused the crate:
#
#     route("GET", "/x", req => Response(404, Map(), "no"))    E0308: expected String, found Response
#
# The workaround is to extract a `def h(req: Request): Response` and pass `req => h(req)` — which
# already worked, and is why this was reported as ergonomics rather than as a hole. It is not
# ergonomics: the block form `route(m, p) { req => … }` is the spelling the docs use for a
# multi-line handler, and it failed too.
#
# THE FIX KEYS OFF WHAT THE BODY PROVES, NOT OFF THE PARAMETER, and that direction is forced. The
# chooser's own comment asks that an untyped lambda keep receiving the path/body string it has always
# received — so widening on "the parameter is unannotated" would silently change the meaning of
# working programs. A body that builds a `Response` cannot be a string handler: it does not compile
# as one, which IS the reported E0308. So the last two rows are the ones that matter here. They are
# not symmetry: if either flips to the Request entry, this fix took programs that work today and
# changed what their handler is handed.
#
# THE ROWS ARE READ FROM THE EMITTED CRATE, not from "did it build". A build says the crate is
# well-typed; only the entry name says WHICH surface was chosen, and a String handler that kept
# compiling while quietly moving to `_http_route_req` is exactly the regression the controls exist
# to catch. One row then runs on a real socket, because the reported symptom is a 404 that could not
# be answered.
#
# NOT A ROW: the builder chain `Response(…).withHeader(…)`. It selects the Request entry correctly
# and the crate still does not compile, for a reason with nothing to do with routing — this backend
# lowers a case-class method that reads its own fields into a free fn with the fields unbound, so
# bare `case class P(x: Int, y: Int)` with `def shifted(d: Int): P = P(x + d, y)` is already E0425.
# A red row here would be measuring THAT defect from the wrong gate; it is filed on its own.
#
# COST: four cargo builds and one socket run, ~90 s. Lives in ci.yml with the other cargo gates.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "rust-route-handler-shapes-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }

if ! command -v cargo >/dev/null 2>&1; then
  echo "rust-route-handler-shapes-gate: [skip] cargo is not on PATH. That is a SKIP, not a pass." >&2
  exit 0
fi

# The sandbox lives INSIDE the repo, like rust-http-lane-parity-gate's, so the import can be
# written relative. That is not a style choice: an ABSOLUTE import path does not resolve `Response`
# on this lane — `[Response](/abs/…/std/http.ssc)` raises "which this crate does not define" where
# `../../std/http.ssc` emits fine. Filed separately; a gate written the other way would have been
# measuring THAT, and it did — every Response row was red for it before this line changed.
sandbox=$(mktemp -d "$ROOT/examples/_routeshapes.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

# Every program is a whole main, because the entry is chosen per call site and a fragment would not
# reach the walker the same way.
shape() { # $1 name, $2 body line(s)
  { printf '[route, serve, Request, Response](../../std/http.ssc)\n'
    printf 'def main(): Unit =\n'
    printf '%s\n' "$2"
    printf '  println("ready")\n'
    printf 'main()\n'
  } > "$sandbox/$1.ssc"
}

shape inline-response '  route("GET", "/x", req => Response(404, Map("Content-Type" -> "text/plain"), "no"))'
shape inline-if       '  route("GET", "/x", req => if req.path == "/x" then Response(200, Map(), "y") else Response(404, Map(), "n"))'
shape block-form      '  route("GET", "/x") { req => Response(404, Map("Content-Type" -> "text/plain"), "no") }'
shape string-lambda   '  route("GET", "/x", req => "plain")'

# The def-returning-String control needs a def, so it is written out in full rather than via shape().
{ printf '[route, serve, Request](../../std/http.ssc)\n'
  printf 'def h(req: Request): String = "hi"\n'
  printf 'def main(): Unit =\n'
  printf '  route("GET", "/x", req => h(req))\n'
  printf '  println("ready")\n'
  printf 'main()\n'
} > "$sandbox/string-def.ssc"

# TWO decisions are read, not one, because they are made by two functions and each can regress
# alone: which runtime ENTRY the call takes (`_http_route_req` hands the handler a `Request`;
# `_http_route` is the original String surface), and what the adapter says the handler RETURNS.
# The surfaces, and every one of them is a shape a program writes today:
#
#   string-entry    `_http_route`, no adapter          — an untyped lambda answering a String
#   req->String     `_http_route_req`, `-> String`     — a `def h(req: Request): String`
#   req->Response   `_http_route_req`, `-> Response`   — anything that answers a Response
surface_is() { # $1 name, $2 wanted surface
  local name=$1 want=$2 got rs
  if ! (cd "$sandbox" && timeout 600 "$tools" emit-rust "$sandbox/$name.ssc" >"$sandbox/$name.emit.log" 2>&1); then
    echo "  ✗ $name: emit-rust failed: $(tail -1 "$sandbox/$name.emit.log")"
    fails=$((fails + 1)); return
  fi
  rs="$sandbox/$name-rust/src/generated/ssc_program.rs"
  if ! [[ -f "$rs" ]]; then
    echo "  ✗ $name: no generated crate at $rs"
    fails=$((fails + 1)); return
  fi
  if grep -q '_http_route_req' "$rs"; then
    if grep -q 'Fn(Request) -> Response' "$rs"; then got=req-\>Response
    elif grep -q 'Fn(Request) -> String' "$rs"; then got=req-\>String
    else got='req->?'
    fi
  elif grep -q '_http_route' "$rs"; then
    got=string-entry
  else
    got='<no route call>'
  fi
  if [[ "$got" == "$want" ]]; then
    echo "  ✓ $name: $got"
  else
    echo "  ✗ $name: emitted $got, wanted $want"
    fails=$((fails + 1))
  fi
}

builds() { # $1 name
  if (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/$1.ssc" >"$sandbox/$1.build.log" 2>&1); then
    echo "  ✓ $1: crate compiles"
  else
    echo "  ✗ $1: $(grep -m1 -E 'error\[E[0-9]+\]|^error' "$sandbox/$1.build.log")"
    fails=$((fails + 1))
  fi
}

echo "── a handler that answers a Response takes the Request entry, however it is spelled"
surface_is inline-response 'req->Response'
surface_is inline-if       'req->Response'
surface_is block-form      'req->Response'

echo "── the controls: a handler that answers a String is still handed one"
# An untyped lambda answering a String keeps the entry it has always had — the target chooser's
# comment asks for exactly this, and widening on "the parameter is unannotated" would break it.
surface_is string-lambda 'string-entry'
# A `def h(req: Request): String` takes the Request entry with a String return, which is what
# `route-handler-lowered-to-string` established; this row is the one that fails if the RETURN side
# is widened along with the parameter side. I first wrote it expecting `string-entry` and the gate
# called correct behaviour a regression — the expectation was wrong, not the walker.
surface_is string-def 'req->String'

echo "── and the chosen entry produces a crate that compiles"
builds inline-response
builds block-form
builds string-lambda

echo "── the reported symptom: a route whose job is refusing answers 404 on the wire"
port=$(( 21000 + RANDOM % 2000 ))
{ printf '[route, serve, Request, Response](../../std/http.ssc)\n'
  printf 'def main(): Unit =\n'
  printf '  route("GET", "/refuse", req => Response(404, Map("Content-Type" -> "text/plain"), "no"))\n'
  printf '  serve(%d)\n' "$port"
  printf 'main()\n'
} > "$sandbox/wire.ssc"

if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/wire.ssc" >"$sandbox/wire.build.log" 2>&1); then
  echo "  ✗ wire: build failed: $(grep -m1 -E 'error\[E[0-9]+\]|^error' "$sandbox/wire.build.log")"
  fails=$((fails + 1))
else
  "$sandbox/wire" >"$sandbox/wire.out" 2>&1 &
  srv=$!
  for _ in $(seq 1 60); do
    curl -fsS -o /dev/null "http://127.0.0.1:$port/refuse" 2>/dev/null && break
    curl -sS -o /dev/null -w '' "http://127.0.0.1:$port/refuse" 2>/dev/null && break
    sleep 0.5
  done
  code=$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/refuse" 2>/dev/null)
  kill "$srv" 2>/dev/null; wait "$srv" 2>/dev/null
  if [[ "$code" == "404" ]]; then
    echo "  ✓ wire: GET /refuse -> $code"
  else
    echo "  ✗ wire: GET /refuse -> '${code:-<no answer>}', wanted 404"
    fails=$((fails + 1))
  fi
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-route-handler-shapes-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-route-handler-shapes-gate: PASS"
