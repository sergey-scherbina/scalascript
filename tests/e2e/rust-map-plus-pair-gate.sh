#!/usr/bin/env bash
#
# rust-map-plus-pair-gate — `map + (k -> v)` is a Map update on every lane, the original map is
# untouched, and the header a route sets that way actually reaches the wire.
#
# THE DEFECT. `+` on a Map is Scala's immutable "the same map with this pair added". On the Rust
# lane it reached rustc as an ADDITION:
#
#     val h2 = h + ("b" -> "2")
#     error[E0369]: cannot add `(String, String)` to `HashMap<String, String>`
#
# It is not a corner: `std/http.ssc` writes `withHeader` exactly that way —
# `Response(status, headers + (name -> value), body)` — so no builder chain compiled on this lane.
# The lowering was never missing, only unreachable: the `.updated(k, v)` arm a few lines below
# already renders `{ let mut m2 = q.clone(); m2.insert(k, v); m2 }`, which is the same operation
# spelled differently. The ARGUMENT decides the receiver without a type check — `->` builds a pair,
# and nothing else takes one on the right of `+`.
#
# THE ROW THAT MATTERS MOST IS THE THIRD ONE. `h` must still answer `?` for the key added to `h2`:
# the operator is IMMUTABLE, and a lowering that inserted into the receiver instead of a clone would
# pass the first two rows and silently corrupt every caller that kept the original.
#
# TWO ANTI-ROWS, because this arm sits in front of `+` for everything else. `"x" + "y"` must still
# be `xy` (the string-concat arm follows this one) and `1 + 2` must still be `3`. If either moves,
# the new arm is deciding by the wrong half of the expression.
#
# THE WIRE ROW IS THE POINT OF THE WHOLE THING. `Response(...).withHeader("X-A", "b")` — ordinary
# HTTP code, and the spelling std/http.ssc itself uses — must answer with that header actually set.
# It took three separate fixes to get here (the Request entry for inline handlers, a case-class
# method's receiver, and this), and a build-only row would not show that the last one is what makes
# the header arrive rather than merely compile.
#
# COST: two cargo builds and one socket run, ~60 s. Lives in ci.yml with the other cargo gates.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "rust-map-plus-pair-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }

# Inside `examples/` so the std import can stay relative: an ABSOLUTE import path drops the case
# classes it names on this lane (rust-absolute-import-path-inlines-nothing), which would turn the
# wire row red for a reason that has nothing to do with Maps.
sandbox=$(mktemp -d "$ROOT/examples/_mappair.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/pair.ssc" <<'SSC'
def main(): Unit =
  val h: Map[String, String] = Map("a" -> "1")
  val h2: Map[String, String] = h + ("b" -> "2")
  println(h2.getOrElse("a", "?"))
  println(h2.getOrElse("b", "?"))
  println(h.getOrElse("b", "?"))
  println("x" + "y")
  println(1 + 2)
main()
SSC

WANT='1|2|?|xy|3|'

lane_says() { # $1 label, $2.. command
  local label=$1; shift
  local out
  out=$(timeout 200 "$@" 2>&1 | head -8 | tr '\n' '|')
  if [[ "$out" == "$WANT" ]]; then
    echo "  ✓ $label: $out"
  else
    echo "  ✗ $label: got '$out', wanted '$WANT'"
    fails=$((fails + 1))
  fi
}

echo "── map + (k -> v) adds the pair, leaves the original alone, and does not eat + for anything else"
lane_says "run   " "$ssc" run "$sandbox/pair.ssc"
lane_says "--v1  " "$tools" run --v1 "$sandbox/pair.ssc"

if ! command -v cargo >/dev/null 2>&1; then
  echo "  [skip] cargo is not on PATH — the Rust rows cannot run. That is a SKIP, not a pass."
  echo "rust-map-plus-pair-gate: PASS (rust rows skipped)"
  exit 0
fi

if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/pair.ssc" >"$sandbox/pair.log" 2>&1); then
  echo "  ✗ rust  : build failed: $(grep -m1 -E 'error\[E[0-9]+\]|^error' "$sandbox/pair.log")"
  fails=$((fails + 1))
else
  lane_says "rust  " "$sandbox/pair"
fi

echo "── a header set through the builder chain reaches the wire"
port=$(( 23000 + RANDOM % 2000 ))
{ printf '[route, serve, Request, Response](../../std/http.ssc)\n'
  printf 'def main(): Unit =\n'
  printf '  route("GET", "/h", req => Response(201, Map("Content-Type" -> "text/plain"), "y").withHeader("X-A", "b"))\n'
  printf '  serve(%d)\n' "$port"
  printf 'main()\n'
} > "$sandbox/wire.ssc"

if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/wire.ssc" >"$sandbox/wire.log" 2>&1); then
  echo "  ✗ wire: build failed: $(grep -m1 -E 'error\[E[0-9]+\]|^error' "$sandbox/wire.log")"
  fails=$((fails + 1))
else
  "$sandbox/wire" >"$sandbox/wire.out" 2>&1 &
  srv=$!
  for _ in $(seq 1 60); do
    curl -sS -o /dev/null "http://127.0.0.1:$port/h" 2>/dev/null && break
    sleep 0.5
  done
  head=$(curl -sS -D - -o /dev/null "http://127.0.0.1:$port/h" 2>/dev/null)
  kill "$srv" 2>/dev/null; wait "$srv" 2>/dev/null
  code=$(printf '%s' "$head" | head -1 | awk '{print $2}')
  hdr=$(printf '%s' "$head" | tr -d '\r' | grep -i '^X-A:' | head -1)
  if [[ "$code" == "201" && -n "$hdr" ]]; then
    echo "  ✓ wire: HTTP $code, $hdr"
  else
    echo "  ✗ wire: status '${code:-<no answer>}', X-A '${hdr:-<absent>}' — wanted 201 and the header set"
    fails=$((fails + 1))
  fi
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-map-plus-pair-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-map-plus-pair-gate: PASS"
