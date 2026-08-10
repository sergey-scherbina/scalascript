#!/usr/bin/env bash
# A char in a NUMERIC context is its code point on the JS backend too.
#
# THE RULE (owner's decision, 2026-08-10): `println(s.charAt(0))` prints the character on every
# backend, and everywhere else a char is COERCED to the type the context asks for rather than
# having its representation changed. `bin/ssc` and `--v1` already agreed; JS did not.
#
# JS represents a char literal as a one-character STRING, and that is deliberate — char/char
# equality, pattern matching and the name-keyed numeric evidence are all built on it (see
# `genReceiver` in JsGen). So the divergence was NOT that the representation was wrong; it was
# that a char literal never widened where Scala widens it:
#
#     'x' == 120   →  "x" === 120   →  false   (ssc/v1: true)
#     'a' + 1      →  "a" + 1       →  "a1"    (ssc/v1: 98)
#
# The fix widens the OPERAND when its sibling is provably numeric. That boundary is the whole
# point, so this gate asserts BOTH sides of it: the numeric contexts must widen, and the
# non-numeric ones (`'a' == 'a'`, `'a' + "b"`, a char in a list, a match) must NOT — a fix that
# widened unconditionally would pass a gate that only checked the first group while quietly
# breaking every char comparison in the corpus.
#
# Written differentially: `bin/ssc` is the reference and JS must agree line for line. Freezing the
# expected text instead would make this gate assert my own reading of Scala rather than the
# behaviour the other lanes already ship.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SSC="$ROOT/bin/ssc"
TOOLS="$ROOT/bin/ssc-tools"
for l in "$SSC" "$TOOLS"; do
  [[ -x $l ]] || { echo "js-char-is-a-char: no launcher at $l — run ./install.sh --dev" >&2; exit 2; }
done

tmp=$(mktemp -d "${TMPDIR:-/tmp}/jschar.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

cat > "$tmp/char.ssc" <<'EOF'
def classify(c: Char): String =
  c match
    case 'a' => "first"
    case 'z' => "last"
    case _   => "middle"

// `c` here is an Int PARAM with the same name as the char val in main. The numeric evidence
// JsGen keeps is name-keyed and module-global, so without a clear this function would inherit
// main's `val c = 'a'` and emit `c.charCodeAt(0)` on a number — a TypeError, not a wrong answer.
// That is why this shape is in the gate and not just in the entry.
def shifted(c: Int): Int = c + 1

def main(): Unit =
  val s = "abc"
  // Numeric contexts: a char widens to its code point.
  println('x' == 120)
  println(120 == 'x')
  println('a' + 1)
  println(1 + 'a')
  println('a' * 2)
  println('a' < 98)
  println('a'.toInt)
  // Text contexts: a char stays a char.
  println(s.charAt(0))
  println('x')
  println('a' == 'a')
  println('a' == 'b')
  println('a' + "b")
  println("b" + 'a')
  println(classify('a'))
  println(classify('m'))
  println(List('a', 'b'))
  // A NAME bound to a char literal is the same defect one step removed: `val c = 'a'` emits
  // `const c = "a"`, so every numeric operator on `c` was a string operator too.
  val c = 'a'
  val d = 'a'
  println(c == 97)
  println(c + 1)
  println(c < 98)
  println(shifted(98))
  // …and the boundary again, on names: two char names compare as CHARS, not as codes.
  println(c == d)
  println(c == 'a')
  println(c == s.charAt(0))
  println(c + "b")
  println(classify(c))
EOF

"$SSC" run "$tmp/char.ssc" > "$tmp/ssc.txt" 2>"$tmp/ssc.err" || {
  echo "js-char-is-a-char: the REFERENCE lane failed — the gate cannot judge JS" >&2
  cat "$tmp/ssc.err" >&2; exit 2
}
"$TOOLS" run-js "$tmp/char.ssc" > "$tmp/js.raw" 2>"$tmp/js.err" || {
  echo "js-char-is-a-char: run-js failed" >&2; cat "$tmp/js.err" >&2; exit 1
}
# run-js may prefix build chatter; keep the last N lines, N = the reference's line count.
n=$(wc -l < "$tmp/ssc.txt" | tr -d ' ')
tail -n "$n" "$tmp/js.raw" > "$tmp/js.txt"

# The reference itself must be right, or an agreeing pair of wrong lanes reads as green.
# These are the two shapes the owner named; they are cheap and they anchor the differential.
grep -qx 'true' <(sed -n '1p' "$tmp/ssc.txt") || { echo "js-char-is-a-char: reference says 'x' == 120 is not true" >&2; exit 2; }
grep -qx '98'   <(sed -n '3p' "$tmp/ssc.txt") || { echo "js-char-is-a-char: reference says 'a' + 1 is not 98" >&2; exit 2; }
grep -qx 'a'    <(sed -n '8p' "$tmp/ssc.txt") || { echo "js-char-is-a-char: reference does not print charAt as a character" >&2; exit 2; }

if ! diff -u "$tmp/ssc.txt" "$tmp/js.txt" > "$tmp/diff.txt"; then
  echo "js-char-is-a-char: JS disagrees with bin/ssc about chars" >&2
  echo "  (-) bin/ssc   (+) run-js" >&2
  cat "$tmp/diff.txt" >&2
  exit 1
fi

echo "js-char-is-a-char: ok — $n lines agree across bin/ssc and run-js"
