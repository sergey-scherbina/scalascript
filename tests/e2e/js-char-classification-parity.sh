#!/usr/bin/env bash
# Char classification must mean the same thing on the js lane and on the interpreter.
#
# The js runtime used to answer these with regex escapes that LOOK equivalent to Java's predicates
# and are not: `\p{Lu}` omits Other_Uppercase, `\s` counts the non-breaking spaces Java excludes,
# and isDigit was ASCII-only where Java accepts every Nd. Measured over the BMP, seven predicates
# disagreed on 627 code points, and toUpper turned 'ß' into 'S' by reading the first character of a
# multi-char expansion.
#
# This runs the SAME source on both lanes and diffs the output. It is a cross-lane differential, not
# a unit test of the regexes: a unit test would have agreed with whatever the runtime happened to do.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/bin"

# The launchers are produced by `./install.sh --dev`. Saying so beats a bare "not found", and
# refusing beats skipping: a gate that passes when it could not run is worse than no gate.
for launcher in ssc-tools jssc; do
  if [ ! -x "$BIN/$launcher" ]; then
    echo "FAIL: $BIN/$launcher is missing — run ./install.sh --dev first"
    exit 1
  fi
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SRC="$WORK/char-classification.ssc"

# Probes chosen from the MEASURED disagreements, one per cause, so a regression names its own reason.
#
# Characters are taken from a STRING rather than written as `Char` literals. That is not cosmetic: a
# char literal reaches the js runtime as a plain JS string and `'A'.isDigit` throws "Method not
# found" there — a separate gap, filed on its own, which would otherwise stop this gate before it
# reached the predicates it exists to compare.
cat > "$SRC" <<'EOF'
val names = List("ascii-A", "ascii-9", "arabic-indic-zero", "fullwidth-three", "roman-eight",
  "cyrillic-CHE", "cyrillic-che", "feminine-ordinal", "nbsp", "file-separator", "em-space",
  "sharp-s", "ligature-ff")

def show(name: String, c: Char): String =
  name + " digit=" + c.isDigit + " letter=" + c.isLetter + " upper=" + c.isUpper +
    " lower=" + c.isLower + " ws=" + c.isWhitespace + " space=" + c.isSpaceChar +
    " up=" + c.toUpper.toInt + " lo=" + c.toLower.toInt

def main(): Unit =
  val probes = "A9\u0660\uFF13\u2167\u0427\u0447\u00AA\u00A0\u001C\u2003\u00DF\uFB00"
  var i = 0
  probes.foreach { c =>
    println(show(names(i), c))
    i = i + 1
  }
EOF

echo "=== interpreter lane (the reference) ==="
int_out="$("$BIN/ssc-tools" run --v1 "$SRC" 2>&1)"
int_rc=$?
echo "$int_out"

echo
echo "=== js lane ==="
js_out="$("$BIN/jssc" "$SRC" 2>&1)"
js_rc=$?
echo "$js_out"

echo
if [ $int_rc -ne 0 ] || [ $js_rc -ne 0 ]; then
  echo "FAIL: a lane did not run (int rc=$int_rc, js rc=$js_rc)"
  exit 1
fi

if [ "$int_out" = "$js_out" ]; then
  echo "PASS: both lanes agree on every probe"
  exit 0
fi

echo "FAIL: the lanes disagree — the same source means different things"
echo
diff <(echo "$int_out") <(echo "$js_out") | sed 's/^/  /'
exit 1
