#!/usr/bin/env bash
#
# f-gap-tail-gate — an interpolated string may contain a STRING, and a plain one may not be scanned
# as if it could.
#
# THE CRASH, measured 2026-08-15: `f"${"x"}"` made F die with
# `Range [3, 3) out of bounds for length 2` — a crash inside the compiler, not a decline. F scanned
# every string with the plain rule (backslash and the closing quote), so the literal ended at the
# quote before `x`, the token's content was the two characters `${`, and the f-interpolator's brace
# scan then ran off the end of it.
#
# THE FIX IS A THREE-WAY SPLIT, mirroring the reference front: a string is INTERPOLATED only when an
# identifier abuts its opening quote (`s"…"`, `f"…"` — Scala's own rule), and only then may `${…}`
# balance braces and swallow inner quotes.
#
# THE PLAIN ROWS ARE THE POINT OF THE CONTROLS, and they are not hypothetical. The reference front's
# own comment records that scanning EVERY string that way makes a plain literal containing `${` eat
# its own closing quote:
#
#     println("${")             printed  ${")            -- the quote and the paren, as text
#     "${" + s + "}"  on "x"    printed  ${" + s + "}    -- the whole expression, as text
#
# and it records that F was the RIGHT one there — that was the first divergence that week where the
# REFERENCE was wrong. `std/ui/content.ssc` is the file that cares: its ContentInline.Expr arm
# renders an un-evaluated expression as exactly `"${" + source + "}"`. So the two plain rows below
# guard behaviour F already had, against a fix that would have been easy to write too broadly.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/gap-tail.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── interpolated strings may contain strings; plain strings are scanned plainly"
ssc_usable_or_skip f-gap-tail-gate "$ssc"

run_front() {
  if [[ "$1" == legacy ]]; then
    SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 200 "$ssc" run "$2" 2>&1 | head -1
  else
    SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$2" 2>&1 | head -1
  fi
}

both() {
  local name=$1 want=$2 src=$3 f r
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  r=$(run_front legacy "$sandbox/$name.ssc")
  f=$(run_front F "$sandbox/$name.ssc")
  if [[ "$r" == "$want" && "$f" == "$want" ]]; then
    echo "  ✓ $name: $want"
  else
    echo "  ✗ $name: reference='$r' F='$f', wanted '$want' from both"
    fails=$((fails + 1))
  fi
}

# ── the crash ────────────────────────────────────────────────────────────────────────────────────

both f-nested-string 'x   |' 'def main(): Unit = println(f"${"x"}%-4s|")'

both s-nested-string x! 'def main(): Unit = println(s"${"x"}!")'

both f-nested-call ab 'def id(v: String): String = v
def main(): Unit = println(f"${id("a")}${"b"}")'

# ── the plain-string behaviour that must NOT change ──────────────────────────────────────────────

both plain-dollar-brace '${' 'def main(): Unit = println("${")'

both plain-dollar-concat '${x}' 'def main(): Unit =
  val s = "x"
  println("${" + s + "}")'

# An interpolated string with no nesting, and a plain one with a quote-free `$`, both unchanged.
both interp-plain-var x 'def main(): Unit =
  val v = "x"
  println(s"$v")'

both f-spec-only 1.2 'def main(): Unit = println(f"${1.234}%.1f")'

if [[ $fails -eq 0 ]]; then echo "✓ f-gap-tail-gate PASSED"; exit 0; fi
echo "✗ f-gap-tail-gate: $fails failure(s)"
exit 1
