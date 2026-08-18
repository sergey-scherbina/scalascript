#!/usr/bin/env bash
#
# f-triple-interp-gate — an interpolated string may be TRIPLE-quoted.
#
# THE DEFECT, and the first row is the one that matters:
#
#   s"""hello ${x}"""              F: 0                     ref: hello A     <- a WRONG ANSWER
#   s"""<div class="row ${x}">"""  F: unbound global: row   ref: the string  <- a decline
#
# `0` is what makes this worse than the defects it was found beside: exit 0, no diagnostic, a
# plausible value. `isInterpStart` fires on `s` followed by ANY quote, so a triple took the
# single-quote path and `scanInterp` stopped at the SECOND quote of the opening run — the token
# carried EMPTY content and the rest of the string was lexed as CODE, which is where `row` came from.
#
# NEITHER FEATURE'S OWN ROWS COULD SEE IT, which is the reusable part: a plain `"""…"""` was always
# fine (`lexStrOrTriple` -> `lexTriple`) and `s"…"` with escaped quotes was always fine. Only the
# PAIRING broke, so every existing row for either half passed while the combination was silently
# wrong. Found by reducing a corpus decline — `examples/frontend/forjson-chat-demo` reported
# `unbound global: (global row)`, and `row` appears in that file ONLY inside a string.
#
# THE SECOND HALF IS THE ESCAPING. A single-quoted interp carries source text whose quotes are
# already `\"`, so `emitStr` wraps it unharmed; a triple carries the quote RAW and `emitStr` does not
# escape, so the literal closed early and the expression evaluated to nothing — `raw-quote-no-interp`
# fails on that alone, with no interpolation involved. `escTripleStr` already solved exactly this for
# the plain triple token, so the triple interp is lexed as its own kind and routed through it.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-triple-interp.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── an interpolated string may be triple-quoted"
ssc_usable_or_skip f-triple-interp-gate "$ssc"

both() {
  local name=$1 want=$2 src=$3 f r
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  r=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 200 "$ssc" run "$sandbox/$name.ssc" < /dev/null 2>&1 | head -1)
  f=$(SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/$name.ssc" < /dev/null 2>&1 | head -1)
  if [[ "$r" == "$want" && "$f" == "$want" ]]; then
    echo "  ✓ $name: $want"
  else
    echo "  ✗ $name: reference='$r' F='$f', wanted '$want' from both"
    fails=$((fails + 1))
  fi
}

# ── the wrong answer ─────────────────────────────────────────────────────────────────────────────

both triple-interp-plain 'hello A' 'def main(): Unit =
  val x = "A"
  println(s"""hello ${x}""")'

both triple-interp-inner-quote '<div class="row A">' 'def main(): Unit =
  val x = "A"
  println(s"""<div class="row ${x}">""")'

# The escaping half on its own: no interpolation at all, and it still evaluated to nothing.
both raw-quote-no-interp 'a"b' 'def main(): Unit = println(s"""a"b""")'

both triple-interp-two-holes 'A-B' 'def main(): Unit =
  val a = "A"
  val b = "B"
  println(s"""${a}-${b}""")'

# A quote INSIDE the interpolation must not close the string — the balanced-brace path.
both quote-inside-the-hole 'x,y' 'def main(): Unit = println(s"""${"x" + "," + "y"}""")'

# ── the halves that were always fine and must stay so ────────────────────────────────────────────

both single-quoted-interp 'hi A' 'def main(): Unit =
  val x = "A"
  println(s"hi ${x}")'

both single-quoted-escaped 'a"b A' 'def main(): Unit =
  val x = "A"
  println(s"a\"b ${x}")'

both plain-triple '<div class="row">' 'def main(): Unit = println("""<div class="row">""")'

# Scala closes a triple on the LAST three quotes of a run, so the content keeps the extra.
both plain-triple-trailing-quote 'a="b"' 'def main(): Unit = println("""a="b"""")'

both plain-string 'ab' 'def main(): Unit = println("ab")'

if [[ $fails -eq 0 ]]; then echo "✓ f-triple-interp-gate PASSED"; exit 0; fi
echo "✗ f-triple-interp-gate: $fails failure(s)"
exit 1
