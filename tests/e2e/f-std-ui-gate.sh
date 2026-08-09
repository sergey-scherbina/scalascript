#!/usr/bin/env bash
#
# f-std-ui-gate — a chain of `++` must stay a list under front F.
#
# `f-std-ui-gaps-behind-the-curried-def-fix`, gap 2. `card(text("a"))` errored with
# `element children expected a valid List` under F and rendered fine under the reference front.
# The card was never the subject: runtime/std/ui/lower.ssc:510 builds one as
#
#     headerParts ++ [bodyEl] ++ footerParts
#
# and F turned that chain into a TUPLE. Dumped from F0 beside the oracle for `[1] ++ [2] ++ [1]`:
#
#     F    (prim sconcat (prim __arith__ "++" a b) a)               -> Tuple4.length
#     REF  (prim __arith__ "++" (prim __arith__ "++" a b) a)        -> 3
#
# The outer `++` became STRING concatenation. `isStrExprCode` counted a generic
# `(prim __arith__ "++" ..)` as evidence of a String — but that node is precisely what F emits when
# the operand type was NOT proven, so the predicate inverted its own stated rule ("an unknown left is
# never sconcat'd") and every second `++` in a chain was typed as text.
#
# WHAT THE CONTROLS ARE FOR. Removing that arm must not cost real typing, and the way to show it is
# to assert that a chain of STRINGS still concatenates correctly — it does, because a proven string
# operand is emitted as `(prim sconcat ..)`, which the next branch of isStrCode already recognises.
# Without those rows, "stop typing `++`" would pass every list case here and quietly degrade text.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-std-ui.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0
export SSC_NO_BUILD_CHECK=1

echo "── a chain of ++ stays a list"
ssc_usable_or_skip f-std-ui-gate "$ssc"

lowered_and_correct() {
  local name=$1 want=$2 src=$3
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  local strict out
  strict=$(SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1)
  if grep -qF 'refusing to fall back' <<<"$strict"; then
    echo "  ✗ $name: F DECLINED"; fails=$((fails + 1)); return
  fi
  out=$(timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1 | head -1)
  if [[ "$out" == "$want" ]]; then echo "  ✓ $name: $out"
  else echo "  ✗ $name: got '$out', wanted '$want'"; fails=$((fails + 1)); fi
}

lowered_and_correct chain-of-three 3 'def main(): Unit =
  val a = [1]
  val b = [2]
  println((a ++ b ++ a).length)'

# The exact shape lower.ssc builds a card from: empty ++ one ++ empty.
lowered_and_correct chain-empty-ends 1 'def main(): Unit =
  val e: List[Int] = List()
  println((e ++ [7] ++ e).length)'

# Empty ends produced by `map`, which is how header/footer are actually built.
lowered_and_correct chain-mapped-ends 1 'def g(x: Int): Int = x
def main(): Unit =
  val h: List[Int] = List()
  val hp = h.map(x => g(x))
  println((hp ++ [7] ++ hp).length)'

lowered_and_correct chain-of-four 4 'def main(): Unit =
  println(([1] ++ [2] ++ [3] ++ [4]).length)'

echo "── controls: string ++ must keep working, and a single ++ must not change"

lowered_and_correct ctl-string-chain abc 'def main(): Unit = println("a" ++ "b" ++ "c")'

lowered_and_correct ctl-string-from-values abc 'def main(): Unit =
  val a = "a"
  val b = "b"
  println(a ++ b ++ "c")'

lowered_and_correct ctl-single-list-concat 2 'def main(): Unit =
  val a = [1]
  val b = [2]
  println((a ++ b).length)'

lowered_and_correct ctl-length-on-concat 3 'def main(): Unit =
  println((["a"] ++ ["b"] ++ ["c"]).length)'

# ── the module and the widget this came from ─────────────────────────────────────────────────────
echo "── the std/ui widget that could not render"
probe="$ROOT/examples/_f_std_ui_probe.ssc"
cat > "$probe" <<'EOF'
[emit](std/ui/primitives.ssc)
[lower](std/ui/lower.ssc)
[defaultTheme](std/ui/theme.ssc)
[text](std/ui/typography.ssc)
[card](std/ui/containers.ssc)
def main(): Unit =
  emit(lower(card(text("a")), defaultTheme), "/tmp/ssc-f-std-ui-gate")
  println("card:ok")
EOF
out=$(SSC_FRONT_STRICT=1 timeout 300 "$ssc" run "$probe" 2>&1 | tail -1)
rm -f "$probe"
if [[ "$out" == "card:ok" ]]; then
  echo "  ✓ card renders under F"
else
  echo "  ✗ card: $out"
  fails=$((fails + 1))
fi

if [[ $fails -eq 0 ]]; then echo "✓ f-std-ui-gate PASSED"; exit 0; fi
echo "✗ f-std-ui-gate: $fails failure(s)"
exit 1
