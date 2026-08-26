#!/usr/bin/env bash
#
# f-curried-def-gate — front F must LOWER a def with more than one parameter clause, including the
# default and named-argument forms, and must produce the RIGHT answer rather than a plausible one.
#
# `f-multi-parameter-clause-def-is-not-lowered`. F already lowered a curried def at the TOTAL arity
# (emitDefU) and already flattened a plain positional call onto it (emitAppCur). What it did not do
# was carry either through the DEFAULT/NAMED-argument machinery:
#
#   * the default registry held the params of the FIRST clause only, so synthCall built its
#     application at the first clause's arity — `arity: 2 expected, 1 given`;
#   * a synthesised call is a string starting `(app (lam 1 `, not `(app (global `, so isCurriedApp
#     read false and the later clause NESTED instead of flattening.
#
# Both halves had to move together: fixing either alone leaves the same error message.
#
# THE SHAPE IS THE ORACLE'S, not an invention. Dumped from ssc1-front+ssc1-lower for
# `def v(gap: Int = 0)(c: String)` called `v(gap = 24)("a")`:
#
#   (def v (lam 2 …))
#   (app (lam 1 (app (lam 1 (app (global v) (local 1) (local 0))) "a")) 24)
#
# — ONE application of `v` carrying both clauses, wrapped in lams that fix evaluation order.
#
# MEASURED WITH SSC_FRONT_STRICT=1: without it the decline is silent, the reference front answers,
# and a plain output check is green whether F lowered the file or refused it.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-curried.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0
export SSC_NO_BUILD_CHECK=1

echo "── F lowers a curried def, and answers correctly"

# The guard is FUNCTIONAL: `-x "$ssc"` was the old test and it is not the question — a fresh
# worktree has an executable launcher and no jars, so every case below "failed" on
# ClassNotFoundException instead of skipping. See tests/e2e/lib/ssc-usable.sh.
ssc_usable_or_skip f-curried-def-gate "$ssc"

lowered_and_correct() {
  local name=$1 want=$2 src=$3
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  local strict out
  strict=$(SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1)
  if grep -qF 'refusing to fall back' <<<"$strict"; then
    echo "  ✗ $name: F DECLINED — $(grep -oE 'unbound global: \(global [A-Za-z0-9_]+\)' <<<"$strict" | head -1)"
    fails=$((fails + 1)); return
  fi
  out=$(timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1 | head -1)
  if [[ "$out" == "$want" ]]; then
    echo "  ✓ $name: $out"
  else
    echo "  ✗ $name: answered '$out', wanted '$want'"
    fails=$((fails + 1))
  fi
}

# ── the four forms measured broken, in the entry's order ─────────────────────────────────────────

lowered_and_correct named-arg-with-default a24 'def v(gap: Int = 0)(c: String): String = c + gap.toString
def main(): Unit = println(v(gap = 24)("a"))'

# The whole first clause omitted. Real: examples/frontend/ios-hello uses `vstack()(` and `hstack()(`.
lowered_and_correct omitted-first-clause a0 'def v(gap: Int = 0)(c: String): String = c + gap.toString
def main(): Unit = println(v()("a"))'

# A MIDDLE param of the first clause omitted, the rest supplied across clauses — this is what the
# padding has to get right, and it is the case a "pad only when the clause is empty" shortcut fails.
lowered_and_correct partial-first-clause 1-2-9 'def v(a: Int = 1, b: Int = 2)(c: Int): String = a.toString + "-" + b.toString + "-" + c.toString
def main(): Unit = println(v(1)(9))'

lowered_and_correct positional-with-default a24 'def v(gap: Int = 0)(c: String): String = c + gap.toString
def main(): Unit = println(v(24)("a"))'

lowered_and_correct named-arg-no-default a24 'def v(gap: Int)(c: String): String = c + gap.toString
def main(): Unit = println(v(gap = 24)("a"))'

# ── varargs, which turned out to be missing ENTIRELY, not only under currying ─────────────────────
# Measured before the fix: `def f(xs: String*)` called `f("a")` answered `<closure>` — a silent wrong
# answer with no currying involved at all. The oracle builds the list at the CALL site
# (`(ctor Cons "a" (ctor Cons "b" (ctor Nil)))`) and leaves the param as one ordinary slot; F had the
# param side right and the call side absent.
echo "── varargs"

lowered_and_correct varargs-plain 2 'def f(xs: String*): Int = xs.toList.length
def main(): Unit = println(f("a", "b"))'

lowered_and_correct varargs-single 1 'def f(xs: String*): Int = xs.toList.length
def main(): Unit = println(f("a"))'

lowered_and_correct varargs-empty 0 'def f(xs: String*): Int = xs.toList.length
def main(): Unit = println(f())'

lowered_and_correct varargs-after-fixed 3 'def f(n: Int, xs: String*): Int = n + xs.toList.length
def main(): Unit = println(f(1, "a", "b"))'

# THE ANTI-ROW, and it is the reason `varargs-after-fixed` above is a fix and not a hole. Those two
# rows were red because `registerParamTypes` records a def as soon as ONE parameter has a known type,
# and recording a signature fixes its ARITY — which a vararg def does not have. The repair skips the
# registration for a trailing-vararg def, so the obvious way to "pass" those rows is to weaken the
# registry for everyone. This row is what that costs: `h` has no vararg, three arguments for two
# parameters is a real error, and it must still be REFUSED. `--v1` prints 3 here — the reference lane
# is the permissive one — so the expectation is deliberately the v2 message and not the other lane's
# answer. (f-curried-def-gate-red-on-varargs-after-a-fixed-parameter.)
refuses_overapplication() {
  local name=$1 src=$2 out
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  out=$(timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1 | head -1)
  if grep -qF 'TYPEERR' <<<"$out"; then
    echo "  ✓ $name: refused — $out"
  else
    echo "  ✗ $name: answered '$out'; over-applying a NON-vararg def must stay a type error"
    fails=$((fails + 1))
  fi
}

refuses_overapplication overapply-non-vararg 'def h(a: Int, b: Int): Int = a + b
def main(): Unit = println(h(1, 2, 3))'

lowered_and_correct varargs-curried 3 'def f(n: Int)(xs: String*): Int = n + xs.toList.length
def main(): Unit = println(f(1)("a", "b"))'

# The corpus shape exactly: a defaulted first clause named at the call site, varargs in the second.
lowered_and_correct varargs-curried-named 26 'def f(n: Int = 0)(xs: String*): Int = n + xs.toList.length
def main(): Unit = println(f(n = 24)("a", "b"))'

# ── controls: the forms that already worked ──────────────────────────────────────────────────────
echo "── controls: single-clause and plain curried must not change"

lowered_and_correct ctl-single-clause-default 24 'def v(gap: Int = 0): String = gap.toString
def main(): Unit = println(v(gap = 24))'

lowered_and_correct ctl-plain-curried a24 'def v(gap: Int)(c: String): String = c + gap.toString
def main(): Unit = println(v(24)("a"))'

# A def that RETURNS a function is called with the same syntax and must STAY nested — this is the
# discriminator emitAppCur is built on, and widening the flattening to syntax rather than the callee
# table would break it while every test above stayed green.
lowered_and_correct ctl-returns-a-function 7 'def mk(n: Int): Int => Int = (x: Int) => x + n
def main(): Unit = println(mk(3)(4))'

# ── the module the corpus regression came through ────────────────────────────────────────────────
# std/ui/layout.ssc declares `def vstack(gap: Int = 0)(children: TkNode*)`; smoke-test.ssc is the
# corpus file that printed smoke:ok before F started lowering it and errored after.
# WHAT THIS ASSERTS, and why it is not `smoke:ok`: the file still does not run under F, but no longer
# for any reason this fix owns. Bisected by widget section on the fixed build — four of its seven
# sections now produce smoke:ok under F, and the three that do not fail on THREE DIFFERENT
# pre-existing gaps, each measured identical on the pre-fix binary:
#   inputSection       unbound global: selectFromView   (the reference front fails this too in isolation)
#   containersSection  element children expected a valid List
#   routingSection     duplicate native UI signal '__equality_…'
# See tests/BUGS.md `f-std-ui-gaps-behind-the-curried-def-fix`. Asserting smoke:ok here would tie this
# gate to three unrelated defects and go red for reasons it cannot diagnose; asserting the ABSENCE of
# an arity error is exactly the claim this fix makes, and it fails on the pre-fix binary.
echo "── the corpus file this was found through: no arity error remains"
smoke="$ROOT/examples/frontend/std-ui/smoke-test.ssc"
if [[ -f "$smoke" ]]; then
  out=$(SSC_FRONT_STRICT=1 timeout 300 "$ssc" run "$smoke" 2>&1 | tail -1)
  if [[ "$out" == "smoke:ok" ]]; then
    echo "  ✓ std-ui/smoke-test: smoke:ok under F (the three std/ui gaps are fixed too)"
  elif grep -qE 'arity: [0-9]+ expected' <<<"$out"; then
    echo "  ✗ std-ui/smoke-test: an arity error is back — $out"
    fails=$((fails + 1))
  else
    echo "  ✓ std-ui/smoke-test: no arity error (stops later, on a known std/ui gap): $out"
  fi
else
  echo "  SKIP std-ui/smoke-test: not present"
fi

# ── THE ANTI-ROWS FOR THE BODYLESS-DECL RULE ────────────────────────────────────────────────────
#
# `collectCurried` registers a name so `f(a)(b)` FLATTENS instead of nesting, and it now requires the
# def to HAVE A BODY: a bodyless declaration in a trait is a plugin-backed member whose value really
# is two closures, and flattening its call sites made `srv.tool("greet")(handler)` register nothing
# at all (BUGS.md mcp-v2-a-curried-plugin-native-yields-a-closure-instead-of-registering — the row
# that FAILS without the rule lives in v21-standard-mcp-smoke, which needs a plugin receiver).
#
# These three are what the rule must NOT take with it: a curried METHOD, reached three ways. Each
# has a body, so each must still flatten; each passes with the rule REVERTED, which is what makes
# them anti-rows rather than a second copy of the defect row.
lowered_and_correct object-curried-method 7 'object O:
  def add(a: Int)(b: Int): Int = a + b
def main(): Unit = println(O.add(3)(4))'
lowered_and_correct class-curried-method 111 'case class Box(k: Int):
  def add(a: Int)(b: Int): Int = a + b + k
def main(): Unit = println(Box(100).add(5)(6))'
lowered_and_correct class-curried-method-local 103 'case class Box(k: Int):
  def add(a: Int)(b: Int): Int = a + b + k
def main(): Unit =
  val b = Box(100)
  println(b.add(1)(2))'

if [[ $fails -eq 0 ]]; then
  echo "✓ f-curried-def-gate PASSED"
  exit 0
fi
echo "✗ f-curried-def-gate: $fails failure(s)"
exit 1
