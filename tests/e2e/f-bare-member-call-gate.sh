#!/usr/bin/env bash
#
# f-bare-member-call-gate — front F must LOWER a bare call to a sibling member of the enclosing
# `object`, not decline the whole file over it.
#
# `f-declines-every-non-top-level-def`. F resolves a bare applied name against top-level defs and
# @-cells; `calleeOf` had an arm for the current object's `var`s (`isCurObjVar`) and none for its
# methods, so
#
#     object M:
#       def twice(): Int = 2
#       def quad(): Int = twice() * 2
#
# lowered `twice` to a top-level `(global twice)`, `validateNoReader` rejected it, and F declined
# the file — which sends the whole program to the reference front. That matters beyond the one
# construct: an F decline is FILE-scoped, so one such method makes every measurement in that file
# the reference front's numbers rather than F's.
#
# MEASURED WITH SSC_FRONT_STRICT=1 on purpose. Without it the fallback is silent and the program
# still prints the right answer, so a plain output comparison passes whether F lowered the file or
# declined it — the gate would be green either way and prove nothing. Strict mode turns the decline
# into a hard error, which is the only way this can distinguish the two states.
#
# The `case class` form needs one thing an object does not: a class method's global takes the
# RECEIVER as its first parameter (`ccMParams` seeds the body env with `Cons("__self", Nil)`), so a
# bare `twice()` must pass it. `calleeOf` returns only the callee and the caller appends the source
# arguments, so the self slot is prepended in `parseCallPlain`, where `env` is in scope. `__self`
# being absent from env is exactly what tells an object body from a class body, so one test covers
# both and no separate "am I in a class" flag exists to drift.
#
# ── a `def` nested in a FUNCTION body ────────────────────────────────────────────────────────────
# Lowered as `(letrec ((lam ar body)) rest)`, so the binding is visible inside the lambda AND in the
# rest of the block. letrec rather than let because `loop` calling `loop` is the shape this exists
# for — the recursive case is the one the parser corpus is built out of.
#
# STILL OPEN and NOT asserted here, measured on this build: a trait DEFAULT method reading an
# abstract sibling lowers but dies at runtime with `__method__: no dispatch for .describe`,
# identically before and after these changes, so it is a separate pre-existing gap, not a regression.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-bare-member.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0
export SSC_NO_BUILD_CHECK=1

echo "── F lowers a bare call to a sibling object member"

# The guard is FUNCTIONAL: `-x "$ssc"` was the old test and it is not the question — a fresh
# worktree has an executable launcher and no jars, so every case below "failed" on
# ClassNotFoundException instead of skipping. See tests/e2e/lib/ssc-usable.sh.
ssc_usable_or_skip f-bare-member-call-gate "$ssc"

# $1 name, $2 expected stdout, $3 source. Runs twice: strict (did F lower it?) and plain (is the
# answer right?). Both matter — F lowering a file to the WRONG code would pass a strict-only check.
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

lowered_and_correct object-sibling 4 'object M:
  def twice(): Int = 2
  def quad(): Int = twice() * 2
def main() = println(M.quad())'

lowered_and_correct object-sibling-with-arg 12 'object M:
  def scale(n: Int): Int = n * 2
  def go(): Int = scale(6)
def main() = println(M.go())'

lowered_and_correct object-chain 22 'object M:
  def base(): Int = 2
  def mid(): Int = base() * 10
  def top(): Int = mid() + base()
def main() = println(M.top())'

# A sibling call must not swallow a same-named TOP-LEVEL def when the object has no such member.
# Without this, "resolve bare names to the enclosing object" would look correct on every case above
# while quietly capturing ordinary global calls.
lowered_and_correct global-still-reachable 100 'def helper(): Int = 100
object M:
  def go(): Int = helper()
def main() = println(M.go())'

# The object`s own `var` arm must keep working — it is the arm this fix was inserted next to, and
# the two are one `if` chain.
lowered_and_correct object-var-still-works 7 'object M:
  var seed = 7
  def go(): Int = seed
def main() = println(M.go())'

# ── case class: the half that needs the receiver threaded ────────────────────────────────────────
lowered_and_correct class-sibling 12 'case class B(n: Int):
  def twice(): Int = n * 2
  def quad(): Int = twice() * 2
def main() = println(B(3).quad())'

lowered_and_correct class-sibling-with-arg 15 'case class B(n: Int):
  def scale(k: Int): Int = n * k
  def go(): Int = scale(5)
def main() = println(B(3).go())'

# Self-recursion is the same construct pointing at itself, and it is the shape that shows the self
# slot is threaded on EVERY iteration rather than once: a wrong receiver would recurse on the wrong
# instance and answer with the wrong field.
lowered_and_correct class-self-recursion 7 'case class B(n: Int):
  def down(k: Int): Int = if k == 0 then n else down(k - 1)
def main() = println(B(7).down(3))'

# The class must NOT capture a same-named top-level def it has no member for — the control that
# stops "resolve bare names to the enclosing class" from quietly swallowing ordinary global calls.
lowered_and_correct class-global-still-reachable 100 'def scale(k: Int): Int = 100
case class B(n: Int):
  def go(): Int = scale(5)
def main() = println(B(3).go())'

lowered_and_correct nested-def 6 'def outer(k: Int): Int =
  def helper(i: Int): Int = i * 2
  helper(k)
def main() = println(outer(3))'

# The recursive one. A `let` instead of a `letrec` passes `nested-def` above and fails here, which
# is the whole reason both are present.
lowered_and_correct nested-def-recursive 5 'def outer(k: Int): Int =
  def loop(i: Int): Int = if i == 0 then 0 else loop(i - 1) + 1
  loop(k)
def main() = println(outer(5))'

# Captures a parameter of the ENCLOSING function. This is what checks the env discipline: params are
# pushed onto `nm :: env`, so a slot miscount answers with the wrong value rather than failing.
lowered_and_correct nested-def-captures 13 'def outer(k: Int): Int =
  def add(i: Int): Int = i + k
  add(10)
def main() = println(outer(3))'

# ── `this` ───────────────────────────────────────────────────────────────────────────────────────
# `this` is the receiver slot under its source name; F had no notion of it, so `this.t()` lowered to
# `(global this)` and declined the file. Resolved to (local <__self>), guarded on __self being in
# scope — see the control below, which is what stops `this` binding to whatever slot happens to be
# there in a context that has no receiver.
lowered_and_correct this-method-call 12 'case class B(n: Int):
  def t(): Int = n * 2
  def q(): Int = this.t() * 2
def main() = println(B(3).q())'

lowered_and_correct this-field-read 12 'case class B(n: Int):
  def q(): Int = this.n * 3
def main() = println(B(4).q())'

# CONTROL: outside a class `this` has no meaning and must stay unbound. If this ever starts
# lowering, `this` has been bound to an arbitrary local and every case above proves nothing.
printf '%s\n' 'def main() =
  println(this)' > "$sandbox/this-outside.ssc"
this_out=$(SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/this-outside.ssc" 2>&1)
if grep -qF 'refusing to fall back' <<<"$this_out"; then
  echo "  ✓ this-outside-a-class: still unbound, as it must be"
else
  echo "  ✗ this-outside-a-class: F lowered it — `this` is binding to an arbitrary slot"
  fails=$((fails + 1))
fi

# ── declared externs are not an F gap ────────────────────────────────────────────────────────────
# `validateNoReader` accepted a global only if it was a top-level def or started with `@`. An
# `extern def` is a SIGNATURE — both fronts erase the declaration and the plugin registry binds the
# name at run time — so every program touching one was counted as an F coverage gap and delegated.
# The guard now also accepts names DECLARED extern in the program's import closure.
mkdir -p "$sandbox/mods"
printf -- '---\nname: base\nexports:\n  - thing\n---\n```scalascript\nextern def thing(n: Int): Int\n```\n' > "$sandbox/mods/base.ssc"
printf -- '---\nname: mid\nexports:\n  - useIt\n---\n```scalascript\n[thing](base.ssc)\n\ndef useIt(n: Int): Int = thing(n)\n```\n' > "$sandbox/mods/mid.ssc"
printf '[useIt](mods/mid.ssc)\n\ndef main() = println("ok")\n' > "$sandbox/extern-chain.ssc"
chain=$(SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/extern-chain.ssc" 2>&1)
if grep -qF 'refusing to fall back' <<<"$chain"; then
  echo "  ✗ extern-across-modules: F still declines — $(grep -oE 'unbound global: \(global [a-z]+\)' <<<"$chain" | head -1)"
  fails=$((fails + 1))
else
  echo "  ✓ extern-across-modules: a declared extern is no longer counted as an F gap"
fi

# THE CONTROL THAT MAKES THE ONE ABOVE MEAN ANYTHING. A genuine F gap surfaces as an unbound global
# too, so the guard must keep rejecting a name nothing declares. Without this case the change reads
# as "accept declared externs" while actually being "stop checking".
printf 'def main() = println(noSuchThingAnywhere(1))\n' > "$sandbox/unknown-name.ssc"
unk=$(SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/unknown-name.ssc" 2>&1)
if grep -qE 'refusing to fall back|unbound global' <<<"$unk"; then
  echo "  ✓ unknown-name: still rejected, the guard was widened and not removed"
else
  echo "  ✗ unknown-name: ACCEPTED — validateNoReader no longer catches an unbound global"
  fails=$((fails + 1))
fi

# ── a name-binding match arm no longer eats the rest of the file ─────────────────────────────────
# `parseConsArm` assumed the shape `h :: t` and indexed blind — head at token 0, tail at token 2,
# body from token 3. Given `case error => …` it took the ARROW as the cons operator and the BODY as
# the tail binder, then resumed inside the body, desynchronising the parser for the rest of the FILE.
# Measured on std/json-core.ssc before the fix: F emitted 96 defs to the reference front's 219, and
# after the first such arm 24 declarations were missing and 0 present.
lowered_and_correct binding-arm-unused 42 'case class Ok(v: Int, n: Int)
def f(x: Any): Any =
  x match {
    case Ok(v, n) => v
    case other => 0
  }
def afterIt(x: Int): Int = x * 2
def main() = println(afterIt(21))'

# The binder is USED in the body. This case asserted an honest DECLINE while only the parse half had
# landed; the binding half makes it lower, and the case was updated rather than deleted — which is
# the whole point of having written it that way.
lowered_and_correct binding-arm-used 42 'case class Ok(v: Int, n: Int)
def f(x: Any): Any =
  x match {
    case Ok(v, n) => v
    case error => error
  }
def afterIt(x: Int): Int = x * 2
def main() = println(afterIt(21))'

# The binder must carry the SCRUTINEE, not some other local. Without this the arm could bind the
# wrong slot and every case above would still pass.
lowered_and_correct binding-arm-value 7 'case class Ok(v: Int, n: Int)
def f(x: Any): Any =
  x match {
    case Ok(v, n) => v
    case error => error
  }
def main() = println(f(7))'

# `getOrElse` is two shapes under one name — the Option form takes one argument, a Map takes two.
# Routing both to the `lam 2` helper made the FIELD form arrive with three: `arity: 2 expected,
# 3 given`. A map VARIABLE took an earlier branch, which is why only the field form failed.
lowered_and_correct getOrElse-on-a-field 1 'case class Req(session: Map[String, String])
def main() =
  val r = Req(Map("a" -> "1"))
  println(r.session.getOrElse("a", "?"))'

lowered_and_correct getOrElse-option-form 5 'def main() =
  val o = Some(5)
  println(o.getOrElse(0))'


# ── `_` as a call ARGUMENT ───────────────────────────────────────────────────────────────────────
# F wraps a placeholder argument into a lambda only when every `_` sits at bracket depth 0, so
# `f(_, x)` — where they are one level in, inside the call — fell through as a bare name:
# `unbound global: (global _)`, 33 of the corpus decline reasons and three times the next one.
lowered_and_correct placeholder-first-arg 60 'def twice(a: Int, b: Int): Int = a * b
def main() = println(List(1,2,3).map(twice(_, 10)).sum)'

# The SECOND position, which the first attempt got wrong: the counter stopped at the first comma
# inside the call and so never saw this `_` at all.
lowered_and_correct placeholder-second-arg 294 'def sub(a: Int, b: Int): Int = a - b
def main() = println(List(1,2,3).map(sub(100, _)).sum)'

# CONTROLS. The arithmetic shorthand already worked and must keep working, and a nested call must
# NOT be captured by the outer wrap — Scala scopes `f(g(_))` to `g(_)`, so wrapping the outside
# would silently change what the program means rather than fail loudly.
lowered_and_correct placeholder-arith 9 'def main() = println(List(1,2,3).map(_ + 1).sum)'

lowered_and_correct nested-call-untouched 15 'def f(a: Int): Int = a + 1
def g(a: Int): Int = a * 2
def main() = println(List(1,2,3).map(x => f(g(x))).sum)'

# A USER-DEFINED `|`. Kind 63 went straight to `(prim i.or)`, so an `extension def |` was never
# dispatched and the integer primitive got the operands — `expected Int, got Lit("x")`. `~`, `~>`
# and `<~` have had the isExtMethod check since they were added; `|` never did, and `opNameK` did
# not even know the name, so `def |` was not collected as a member.
#
# This is what miscompiled the whole std/parsing stack: `a | b` is the alternation combinator there,
# so every parser built with a choice became a bitwise or. It stayed invisible because F declined
# those modules for the UNRELATED reason above and they ran on the reference front.
lowered_and_correct user-defined-pipe 'Alt(Lit(x), Lit(y))' 'case class Alt(l: Any, r: Any)
case class Lit(s: String)

extension (a: Lit)
  def |(b: Lit): Alt = Alt(a, b)

def main() = println(Lit("x") | Lit("y"))'

# …and the integer `|` must still be the primitive. A fix that routed every `|` to an extension
# would pass the case above and break arithmetic silently.
lowered_and_correct integer-or-still-primitive 7 'def main() = println(6 | 3)'


echo
if [[ $fails -eq 0 ]]; then
  echo "✓ f-bare-member-call-gate PASSED"
  exit 0
fi
echo "✗ f-bare-member-call-gate FAILED ($fails)"
exit 1
