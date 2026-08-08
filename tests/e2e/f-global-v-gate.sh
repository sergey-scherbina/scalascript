#!/usr/bin/env bash
#
# f-global-v-gate — front F must LOWER a `val` written INLINE in a match-arm body, not leak it.
#
#     case "paddingX" => val n = _lenOf(v, theme); s"padding-left:${n}px;"
#
# The arm-body sequence (armSeqExpr) knew `expr; expr` and did not know `val`. parseExpr consumed no
# `val`, so the tokens LEAKED past the arm and the top-level item parser read them as a top-level
# val: the entry emitted `(prim cell.set (global n__cell) ..)` while collectTopVals — which scans
# top level only, correctly — never declared the cell. F then declined the file over an unbound
# global it had invented itself.
#
# WHY THE REPORTED NAME IS NOT THE CAUSE, and why this gate asserts two different ones: when the
# initializer mentions an enclosing parameter (`val n = _lenOf(v, theme)`) that name is unbound at
# top level and surfaces FIRST, so the real file reported `(global v)`; with a closed initializer
# (`val n = 1`) the cell surfaces instead, `(global n__cell)`. One mechanism, two symptoms, neither
# naming the construct at fault. Both shapes are pinned below so a partial fix cannot pass.
#
# SCALE, measured 2026-08-08 over the 140-file corpus: 32 files were GAP and 16 of them — every
# frontend example — reported `(global v)`, all inherited from ONE module, runtime/std/ui/lower.ssc,
# which every one of them imports. That module is checked directly at the end: it is the subject,
# the examples are only its consumers.
#
# MEASURED WITH SSC_FRONT_STRICT=1 on purpose. Without it the decline is silent and the program
# still prints the right answer through the reference front, so a plain output comparison is green
# whether F lowered the file or refused it. Strict mode is the only thing that distinguishes the
# two states — the same reason f-bare-member-call-gate uses it.
#
# CONTROLS, so this cannot pass by accepting everything: the braceless MULTI-LINE arm val already
# worked before the fix (parseBlock handles it) and must still be F; an arm with no val at all must
# still be F; and a genuinely unbound name must still be REFUSED — the leak was fixed by teaching
# the parser a statement, not by widening what counts as bound.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-global-v.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0
export SSC_NO_BUILD_CHECK=1

echo "── F lowers a val written inline in a match arm"

if [[ ! -x "$ssc" ]]; then
  echo "SKIP f-global-v-gate: $ssc not built (run scripts/sbtc installBin)"
  exit 0
fi

# $1 name, $2 expected stdout, $3 source. Strict run answers "did F lower it?", plain run answers
# "is the result right?" — a front that lowered the file to WRONG code would pass a strict-only check.
lowered_and_correct() {
  local name=$1 want=$2 src=$3
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  local strict out
  strict=$(SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1)
  if grep -qF 'refusing to fall back' <<<"$strict"; then
    echo "  ✗ $name: F DECLINED — $(grep -oE 'unbound global: \(global [A-Za-z0-9_]+\)' <<<"$strict" | head -1)"
    fails=$((fails + 1)); return
  fi
  out=$(timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>/dev/null | head -1)
  if [[ "$out" == "$want" ]]; then
    echo "  ✓ $name: F lowered it, answer $out"
  else
    echo "  ✗ $name: F lowered it but answered '$out', wanted '$want'"
    fails=$((fails + 1))
  fi
}

# ── the construct itself ─────────────────────────────────────────────────────────────────────────

# Closed initializer: the invented cell name is what surfaced, `(global n__cell)`.
lowered_and_correct arm-val-closed 1 'def f(k: String): String = k match
  case "a" => val n = 1; n.toString
  case _   => ""
def main() = println(f("a"))'

# Initializer mentioning an enclosing PARAMETER: this is the real lower.ssc shape, and the reason
# the corpus reported `(global v)` rather than a cell.
lowered_and_correct arm-val-uses-param 2 'def f(k: String, v: String): String = k match
  case "a" => val n = v.length; n.toString
  case _   => ""
def main() = println(f("a", "zz"))'

# The binding must be visible to the whole rest of the body, not just the next token.
lowered_and_correct arm-val-two-uses 6 'def f(k: String, v: String): String = k match
  case "a" => val n = v.length; (n + n).toString
  case _   => ""
def main() = println(f("a", "zzz"))'

# Two vals in one arm — the second is parsed by the continuation, which must extend the env again
# rather than restart it.
lowered_and_correct arm-val-twice 7 'def f(k: String): String = k match
  case "a" => val n = 3; val m = n + 4; m.toString
  case _   => ""
def main() = println(f("a"))'

# String interpolation in the body, the exact lower.ssc rendering — the de Bruijn slot for `n` has
# to be right INSIDE the interpolation too, not only in a bare reference.
lowered_and_correct arm-val-interpolated n=4 'def f(k: String, v: String): String = k match
  case "a" => val n = v.length; s"n=${n}"
  case _   => ""
def main() = println(f("a", "zzzz"))'

# THE SECOND DECISION SITE, reached by a different surface form: with a statement in front of it the
# val is dispatched by armSeqStmt, not armBodyExpr. Fixing only the first site leaves this broken
# and every test above still green.
#
# The leading statement is a VALUE, not a println: this harness compares `head -1`, so a statement
# that prints puts its own line first and the case fails on its own output rather than on the front.
# (It did, on the first run of this gate — the pre-fix control reported `answered 'x'` for a decline
# that had not happened.)
lowered_and_correct arm-val-after-stmt 5 'def f(k: String): String = k match
  case "a" => "x".length; val n = 5; n.toString
  case _   => ""
def main() = println(f("a"))'

# A wildcard arm shares armBodyExpr with the named arms; if the val head were handled in the ctor
# path only, this would still leak.
lowered_and_correct arm-val-in-default 8 'def f(k: String): String = k match
  case "a" => ""
  case _   => val n = 8; n.toString
def main() = println(f("z"))'

echo "── controls: the shapes that already worked must not change"

# Already F before the fix — the braceless multi-line body goes through parseBlock. If this ever
# fails, the fix took a path away from the block parser rather than adding one.
lowered_and_correct ctl-multiline-val 1 'def f(k: String): String = k match
  case "a" =>
    val n = 1
    n.toString
  case _ => ""
def main() = println(f("a"))'

# NOT a control — this one was a SILENT WRONG ANSWER, and it is the more severe half of this fix.
# A multi-statement arm body on the ordered resolver returned its FIRST statement and dropped the
# rest, with no decline and no diagnostic. Measured before the fix: 2 under F, 3 under the reference
# front, 3 under the v1 interpreter. A decline is loud and costs a measurement; this cost an answer.
lowered_and_correct arm-seq-two-stmts 3 'def f(k: String): String = k match
  case "a" => "xy".length; 3.toString
  case _   => ""
def main() = println(f("a"))'

# The same body shape on a CTOR-first match, which routes to parseCtorMatch instead. It already
# worked; it is here so that a later change cannot fix one resolver and regress the other.
lowered_and_correct arm-seq-ctor-path 3 'def f(o: Option[Int]): String = o match
  case Some(n) => "xy".length; 3.toString
  case None    => ""
def main() = println(f(Some(1)))'

# A top-level val must still lower through the cell machinery this bug impersonated.
lowered_and_correct ctl-top-level-val 9 'val top = 9
def f(k: String): String = k match
  case "a" => top.toString
  case _   => ""
def main() = println(f("a"))'

# ── the guard was taught a statement, not weakened ───────────────────────────────────────────────
# An arm body naming something that does not exist must STILL be refused. Without this, "F lowers
# the file" above would also be satisfied by a front that stopped checking.
echo "── control: an unknown name in an arm is still refused"
cat > "$sandbox/ctl-unknown.ssc" <<'EOF'
def f(k: String): String = k match
  case "a" => val n = nosuchthing(1); n.toString
  case _   => ""
def main() = println(f("a"))
EOF
unknown=$(SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/ctl-unknown.ssc" 2>&1)
unknown_name=$(grep -oE '\(global [A-Za-z0-9_]+\)' <<<"$unknown" | head -1)
if ! grep -qF 'refusing to fall back' <<<"$unknown"; then
  echo "  ✗ ctl-unknown: an undefined name was ACCEPTED — the guard was widened, not taught"
  fails=$((fails + 1))
elif [[ "$unknown_name" == "(global nosuchthing)" ]]; then
  echo "  ✓ ctl-unknown: still refused, and names nosuchthing"
else
  # Refused, but blaming the wrong name. Before the fix this said `(global n__cell)` — the invented
  # cell masked the real undefined name. Refusal alone is not enough: a diagnostic that names an
  # identifier the user never wrote is what sent nine measurement runs after the wrong module.
  echo "  ✗ ctl-unknown: refused but blamed $unknown_name, not (global nosuchthing)"
  fails=$((fails + 1))
fi

# ── the real subject ─────────────────────────────────────────────────────────────────────────────
# The corpus files never contained the construct; they import the module that does. Asserting the
# module directly is what keeps this honest if the examples are ever edited.
echo "── the module the 16 corpus files inherited this from"
for mod in runtime/std/ui/lower.ssc; do
  verdict=$("$ssc" info --front-report "$ROOT/$mod" 2>/dev/null | tail -1 | awk -F'\t' '{print $2}')
  if [[ "$verdict" == "F" ]]; then
    echo "  ✓ $mod: F"
  else
    echo "  ✗ $mod: $verdict (wanted F)"
    fails=$((fails + 1))
  fi
done

if [[ $fails -eq 0 ]]; then
  echo "✓ f-global-v-gate PASSED"
  exit 0
fi
echo "✗ f-global-v-gate: $fails failure(s)"
exit 1
