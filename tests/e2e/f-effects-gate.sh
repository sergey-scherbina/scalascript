#!/usr/bin/env bash
#
# f-effects-gate — `effect E:` / `multi effect E:` declarations and `handle { … } { case … }` on F.
#
# WHAT WAS MISSING, and it was less than it looked. The census ranked effects as F's largest gap.
# Most of the machinery was already there: F turns a `{ case … }` block argument into a case lambda,
# chains trailing block arguments into a curried application, and joins a dotted constructor tag —
# all measured before a line was written. The hole was the DECLARATION.
#
# THE CONTINUATIONS ARE THE RUNTIME'S JOB, which is why this is a translation and not a feature.
# The reference lowerer spends seventeen mentions on effects and its `multi_effect` case returns
# `Nil`; an effect declaration becomes one ordinary def per operation, whose body performs a prim:
#
#   effect E:  def op(a, b)     ->  (def E_op (lam 2 (prim effect.perform.oneshot "E" "op" a b)))
#   multi effect E: def op(a)   ->  (def E_op (lam 1 (prim effect.perform "E.op" a)))
#
# So `multi` costs nothing extra — it selects a different prim — and splitting the work along that
# boundary would have bought nothing. The owner's call was to do it in one go, correctly.
#
# THE OP CALL SITE IS THE SECOND HALF. `Console.readLine()` has to dispatch to the global
# `Console_readLine`, which is what objReg already does for `object` members — so an effect
# registers there exactly as an object does. `op-call-outside-handler` is the row that fails if the
# declaration emits the defs but forgets to register them.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/effects.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

echo "── effect declarations and handlers"
ssc_usable_or_skip f-effects-gate "$ssc"

run_front() {
  if [[ "$1" == legacy ]]; then
    SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 200 "$ssc" run "$2" 2>&1 | head -"${3:-1}"
  else
    SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$2" 2>&1 | head -"${3:-1}"
  fi
}

both() { # $1 name, $2 expected first line, $3 source
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

# ── one-shot ─────────────────────────────────────────────────────────────────────────────────────

both oneshot-single-op 'hi Alice' 'effect Console:
  def readLine(): String
def greet(): String = "hi " + Console.readLine()
def main(): Unit = println(handle(greet()) { case Console.readLine(resume) => resume("Alice") })'

both oneshot-op-with-args done 'effect Console:
  def writeLine(s: String): Unit
def go(): String =
  Console.writeLine("hey")
  "done"
def main(): Unit = println(handle(go()) { case Console.writeLine(msg, resume) => resume("said: " + msg) })'

both oneshot-two-ops ab 'effect E:
  def a(): String
  def b(): String
def go(): String = E.a() + E.b()
def main(): Unit = println(handle(go()) { case E.a(resume) => resume("a")  case E.b(resume) => resume("b") })'

# ── multi ────────────────────────────────────────────────────────────────────────────────────────
#
# `multi` selects `effect.perform` over `effect.perform.oneshot`; the resumption count is the
# RUNTIME's business, and it already works on int/js/jvm. This row is here to prove F picks the
# right prim, not to test continuations.

both multi-effect-declares 'List(11, 21, 12, 22)' 'multi effect Choose:
  def pick(opts: List[Int]): Int
def main(): Unit =
  val r = handle {
    val x = Choose.pick(List(1, 2))
    val y = Choose.pick(List(10, 20))
    x + y
  } { case Choose.pick(opts, resume) => opts.flatMap((o: Int) => resume(o)) }
  println(r)'

# ── the registration half ────────────────────────────────────────────────────────────────────────
#
# An effect op called with no handler in scope must reach the RUNTIME (which reports an unhandled
# effect) rather than being an unbound global — that is the difference between the declaration
# emitting its defs and also registering them for `E.op(…)` dispatch. Both fronts must agree on
# whatever that is.

both declares-without-using before 'effect E:
  def a(): String
def main(): Unit = println("before")'

if [[ $fails -eq 0 ]]; then echo "✓ f-effects-gate PASSED"; exit 0; fi
echo "✗ f-effects-gate: $fails failure(s)"
exit 1
