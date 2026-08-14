#!/usr/bin/env bash
#
# rust-string-length-gate — `String.length` answers the same unit on every lane, and it is the unit
# `substring` and `charAt` index in.
#
# THE REPORT (rozum, `string-length-counts-bytes-not-characters`, `impact: blocks`). `length` lowered
# to Rust's `String::len()`, which is BYTES, while `substring`/`charAt` on the same lane index UTF-16
# CODE UNITS — so the two could not be combined:
#
#     val s = "aé·b"                 run   build-rust
#     s.length                        4        6
#     s.indexOf("b")                  3        3        <- the index agreed
#     s.substring(1, s.length)     "é·b"    PANIC
#
# It is not a wrong value, it is a PANIC on the request path. The reporter found it porting a route
# that patches a token into an HTML page — `html.substring(at, html.length)`, the obvious way to
# write "from here to the end". One accented character anywhere in 27 KB of served HTML killed the
# tokio worker, and every request after it answered `PoisonError`. An ASCII test page hides it.
#
# THE UNIT IS CODE UNITS, AND THAT WAS MEASURED, NOT CHOSEN. `"a😀b".length` is 4 on both reference
# lanes — a surrogate pair counts as two — not 3. `chars().count()` is the intuitive repair and puts
# the lane wrong again on precisely the inputs nobody writes a test for, which is why the astral row
# below exists and why the fix reuses the `_str_*` helpers' basis instead of introducing a second
# answer to "what is a code unit".
#
# THE LAST ROW IS THE ANTI-ROW. The old arm answered `.len()` for EVERY receiver, and for a Vec that
# is correct; the fix only redirects a receiver the walker can see is a String. A `List(...).length`
# that started answering something else would break every list in the corpus, so it is asserted here
# beside the strings.
#
# COST: one cargo build and six short runs, ~30 s. Lives in ci.yml with the other cargo gates.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "rust-string-length-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "${TMPDIR:-/tmp}/str-length.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

# `aé·b` is 4 code units in 6 UTF-8 bytes; `a😀b` is 4 code units in 3 characters. Both numbers are
# in the program on purpose: one separates bytes from code units, the other code units from chars.
cat > "$sandbox/len.ssc" <<'SSC'
def main(): Unit =
  val s: String = "aé·b"
  println(s.length)
  println(s.indexOf("b"))
  println(s.substring(1, s.length))
  val e: String = "a😀b"
  println(e.length)
  val xs: List[Int] = List(10, 20, 30)
  println(xs.length)
  println(xs.size)
main()
SSC

WANT='4|3|é·b|4|3|3|'

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

echo "── length is UTF-16 code units on every lane, and a Vec's length is still its elements"

lane_says "run   " "$ssc" run "$sandbox/len.ssc"
lane_says "--v1  " "$tools" run --v1 "$sandbox/len.ssc"

if ! command -v cargo >/dev/null 2>&1; then
  echo "  [skip] cargo is not on PATH — the Rust row cannot run. That is a SKIP, not a pass."
elif ! (cd "$sandbox" && timeout 600 "$tools" build-rust "$sandbox/len.ssc" >"$sandbox/build.log" 2>&1); then
  echo "  ✗ build-rust failed: $(grep -m1 -E 'error\[E[0-9]+\]' "$sandbox/build.log")"
  fails=$((fails + 1))
else
  lane_says "rust  " "$sandbox/len"
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-string-length-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-string-length-gate: PASS"
