#!/usr/bin/env bash
#
# ref-front-string-literal-gate — a PLAIN string literal ends at its closing quote, whatever it
# contains; an INTERPOLATED one may balance braces and quotes inside `${…}`.
#
# `reference-front-mislexes-a-dollar-brace-inside-a-plain-string-literal`. The lexer scanned every
# string as if it were interpolated, because at that point the `s` prefix is a separate token and it
# had no way to tell. So `${` inside a plain literal started a hunt for a matching `}` and the
# literal ran past its own closing quote:
#
#   println("${")             printed  ${")             the quote and the paren, as text
#   "${" + s + "}" on "x"     printed  ${" + s + "}     the whole expression, as text
#
# Both were already correct under front F, which is how this surfaced — the first divergence this
# week where the REFERENCE front was the wrong one. Found while reducing runtime/std/ui/content.ssc,
# whose ContentInline.Expr arm renders an un-evaluated expression as exactly `"${" + source + "}"`.
#
# THE FIX IS A ONE-BYTE LOOKBACK, Scala's own rule: a string is interpolated iff an identifier abuts
# its opening quote. THE CONTROLS BELOW ARE THE WHOLE RISK — the brace balancing exists so that
# `s"${q("in")}"` is not terminated by the inner quote, and switching plain strings off it must not
# take that away. A gate with only the two rows above would pass a change that broke every `s"…"`
# containing a quote.
#
# BOTH FRONTS ARE ASSERTED, and identically: this is a lexer shared by the default lane, and the two
# agreeing is the property worth pinning, not either one alone.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/ref-str.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0
export SSC_NO_BUILD_CHECK=1

echo "── a plain string literal ends at its closing quote"
ssc_usable_or_skip ref-front-string-literal-gate "$ssc"

# ── PREFLIGHT: the toolchain must carry the front source this gate is about ───────────────────
# The reference front is INTERPRETED from `ssc1-front.ssc0`, staged into the toolchain by the
# build — so a toolchain built before a front fix still runs the OLD lexer even though the
# checkout holds the new one, and `SSC_NO_BUILD_CHECK=1` above (set for the suite's noise budget)
# silences the launcher's own staleness warning.
#
# That is not hypothetical: on 2026-08-10 this gate's 3 failures were read as a residual defect
# and written into `v2/BUGS.md` as "the commit narrowed it, the front still swallows the closing
# quote". The lookback had been in `v2/lib/ssc1-front.ssc0` since the day before. The 3 rows were
# the PRE-FIX front, staged by an older build. Reproduced exactly, 2026-08-13, by reverting the
# lookback in a private copy of the toolchain: same 3 rows, same bytes.
#
# So compare the two files before asserting anything. A stale toolchain must read as "rebuild",
# never as a defect in the code being measured.
front_src="$ROOT/v2/lib/ssc1-front.ssc0"
front_staged=""
for c in "$(dirname -- "$ssc")/lib/native-front/tower/lib/ssc1-front.ssc0"; do
  [[ -r "$c" ]] && { front_staged="$c"; break; }
done
if [[ -n "$front_staged" && -r "$front_src" ]]; then
  if cmp -s "$front_staged" "$front_src"; then
    echo "  · toolchain carries this checkout's reference front"
  else
    echo "  ✗ STALE TOOLCHAIN — the front staged in the toolchain is NOT this checkout's:"
    echo "      staged:   $front_staged"
    echo "      checkout: $front_src"
    echo "    Every row below would measure the staged copy, and a front fix in the checkout"
    echo "    would read as a defect that is still open. Rebuild: ./install.sh --dev"
    exit 1
  fi
else
  # Said out loud rather than skipped in silence: an unchecked premise that prints nothing is how
  # the stale measurement above became a bug entry.
  echo "  · front staleness NOT checked — no staged ssc1-front.ssc0 under $(dirname -- "$ssc")/lib"
fi

# $1 name, $2 expected first line, $3 source. Asserts BOTH fronts.
both_print() {
  local name=$1 want=$2 src=$3
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  local f r
  f=$(SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1 | head -1)
  r=$(SSC_FRONT=legacy    timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1 | head -1)
  if [[ "$f" == "$want" && "$r" == "$want" ]]; then
    echo "  ✓ $name: $want"
  else
    echo "  ✗ $name: F='$f' reference='$r', wanted '$want' from both"
    fails=$((fails + 1))
  fi
}

both_print dollar-brace-alone '${' 'def main(): Unit = println("${")'

both_print dollar-brace-concatenated '${x}' 'def h(s: String): String = "${" + s + "}"
def main(): Unit = println(h("x"))'

both_print dollar-alone '$' 'def main(): Unit = println("$")'

both_print brace-alone '{}' 'def main(): Unit = println("{}")'

# A plain literal that looks exactly like an interpolation must stay text.
both_print looks-interpolated '${name}' 'def main(): Unit = println("${name}")'

echo "── controls: interpolation must keep balancing braces and quotes"

both_print ctl-simple-interp v=5 'def main(): Unit =
  val a = 5
  println(s"v=${a}")'

# THE case the brace balancing exists for: a quote inside the interpolation body.
both_print ctl-quote-inside-interp r=in 'def q(x: String): String = x
def main(): Unit = println(s"r=${q("in")}")'

# Nested braces inside the interpolation body.
both_print ctl-nested-braces n=7 'def main(): Unit =
  val m = Map("k" -> 7)
  println(s"n=${m.getOrElse("k", 0)}")'

both_print ctl-interp-then-text 'a=1 done' 'def main(): Unit =
  val a = 1
  println(s"a=${a} done")'

# A plain string immediately after an interpolated one — the lookback must read the byte before THIS
# quote, not remember the previous string.
both_print ctl-plain-after-interp '${' 'def main(): Unit =
  val a = 1
  val t = s"x=${a}"
  println("${")'

if [[ $fails -eq 0 ]]; then echo "✓ ref-front-string-literal-gate PASSED"; exit 0; fi
echo "✗ ref-front-string-literal-gate: $fails failure(s)"
exit 1
