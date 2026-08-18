#!/usr/bin/env bash
#
# v2-unknown-member-refuses-gate — a method that does not exist must not become OUTPUT, and the
# eta-expansion that made that possible must keep working when the value is CALLED.
#
# THE DEFECT, found 2026-08-14 while someone was checking something else. `recv.name` on a BUILTIN
# receiver has no nullary dispatch, so the v2 runtime answers with the eta-expansion function value
# `x => recv.name(x)` (Runtime.scala, the `__method__` fallback). That fallback cannot tell a
# genuine method reference from a typo — both lower to the same `__method__(name, recv)` node — so
# a name that exists NOWHERE survived as a closure, and printing it gave:
#
#     def main(): Unit = println("m: " + "a".nosuch)     ->  m: <closure>     exit 0
#
# Both v2 fronts agreed with each other and diverged from the reference, which is why the fix is in
# the shared runtime rather than in a front. It is the same shape as the `Stub` breadcrumb the
# rozum incident produced — `{"cell":{Stub}}` in an HTTP 200 body — with a different sentinel: a
# wrong answer that reaches a user instead of an error that stops.
#
# WHY THE FIX STOPS THE VALUE AT RENDERING RATHER THAN REFUSING THE SELECTION, and why row 4 below
# is not optional. The obvious repair is "make a bare selection on a builtin refuse, like the
# interpreter does". Measured, that would delete a capability v2 has and the corpus uses:
#
#     lane          println("abc".contains)      List("b","z").exists("abc".contains)
#     interpreter   No method 'contains'         No method 'contains'
#     v2            <closure>  (now: refuses)    true                    (unchanged)
#
# The reference refuses the bare selection even for a method that EXISTS, so refusing only where
# the value would ESCAPE moves both cases toward the oracle and takes nothing away. Row 4 is the
# anti-row that fails if someone later "simplifies" this by deleting the eta fallback.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/unknown-member.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

# The marker the runtime prints. Kept in one place because both the assertion and its self-test
# read it, and a message reworded without touching this file must fail LOUDLY rather than let every
# row pass on "no <closure> in the output".
MARKER='was selected but never called'

run_front() { # $1 front (legacy|F), $2 file → combined output
  if [[ "$1" == legacy ]]; then
    SSC_NO_BUILD_CHECK=1 SSC_FRONT=legacy timeout 200 "$ssc" run "$2" 2>&1
  else
    SSC_NO_BUILD_CHECK=1 SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$2" 2>&1
  fi
}

# A refusal is THREE facts, not one: the wrong answer is absent, the right message is present, and
# the run does not exit 0. Asserting only the first would pass on a crash with no message; asserting
# only the second would pass on a program that printed the closure AND then complained.
refuses_out() { # $1 out, $2 rc, $3 marker → "" when it is a proper refusal, else the reason
  local out=$1 rc=$2 marker=${3:-$MARKER}
  if printf '%s\n' "$out" | grep -Fqx 'm: <closure>'; then
    printf 'printed the closure as output'
  # The serialised spelling of the same wrong answer. Listed separately because it is a different
  # renderer (the json codec, not `show`) and a reader should not have to infer that.
  elif printf '%s\n' "$out" | grep -Fq '"<function>"'; then
    printf 'serialised the closure into the payload'
  elif ! printf '%s\n' "$out" | grep -Fq "$marker"; then
    printf 'no refusal message (looked for "%s")' "$marker"
  elif [[ "$rc" -eq 0 ]]; then
    printf 'exited 0'
  fi
}

# The marker is per row because the two halves refuse from DIFFERENT places and must keep saying
# so: a bare selection is stopped at rendering (this fix), an applied call at dispatch
# (`__method0__`, which predates it). Asserting one message for both would hide either half moving.
refuses() { # $1 name, $2 marker, $3 source — must be refused on BOTH fronts
  local name=$1 marker=$2 src=$3 out rc why bad=0
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  for front in legacy F; do
    out=$(run_front "$front" "$sandbox/$name.ssc"); rc=$?
    why=$(refuses_out "$out" "$rc" "$marker")
    if [[ -n "$why" ]]; then
      echo "  ✗ $name on $front: $why"
      printf '%s\n' "$out" | head -3 | sed 's/^/        /'
      bad=1
    fi
  done
  [[ "$bad" -eq 0 ]] && echo "  ✓ $name: refused on both fronts" || fails=$((fails + 1))
}

# The rows that must ANSWER share one program, because each `ssc run` is a JVM boot and this check
# is charged to a suite with a budget: four such rows on two fronts is eight boots, and one labelled
# program on two fronts is two. Attribution survives — every line carries its own label, and a
# mismatch prints both sides — which a single blob assertion would have thrown away.
answers_block() { # $1 name, $2 expected output (all lines), $3 source — on BOTH fronts
  local name=$1 want=$2 src=$3 r f bad=0
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  r=$(run_front legacy "$sandbox/$name.ssc")
  f=$(run_front F "$sandbox/$name.ssc")
  for front in legacy:"$r" F:"$f"; do
    if [[ "${front#*:}" != "$want" ]]; then
      echo "  ✗ $name on ${front%%:*}: output differs"
      diff <(printf '%s\n' "$want") <(printf '%s\n' "${front#*:}") | sed 's/^/        /'
      bad=1
    fi
  done
  if [[ "$bad" -eq 0 ]]; then
    printf '%s\n' "$want" | sed 's/^/  ✓ /'
  else
    fails=$((fails + 1))
  fi
}

# ── the self-test — the assertion must be able to say NO ──────────────────────────────────────────
#
# Fed a program that prints a closure for a legitimate reason (a lambda, which has no method behind
# it and is not affected by any of this), `refuses_out` must reject it. Without this, "no
# `m: <closure>` in the output" would also be satisfied by a gate that never ran the program.
self_test() {
  echo "── self-test: the refusal assertion rejects a printed closure"
  local out rc why
  printf '%s\n' 'def main(): Unit =
  val f = (x: Int) => x + 1
  println("m: " + f)' > "$sandbox/_selftest.ssc"
  out=$(run_front legacy "$sandbox/_selftest.ssc"); rc=$?
  if [[ "$out" != *"m: <closure>"* ]]; then
    echo "  ✗ the control did not print a closure at all — it printed:"
    printf '%s\n' "$out" | head -3 | sed 's/^/        /'
    echo "    Without a printed closure this self-test proves nothing; fix the control."
    return 1
  fi
  why=$(refuses_out "$out" "$rc")
  if [[ -z "$why" ]]; then
    echo "  ✗ SELF-TEST FAILED: a printed closure was accepted as a refusal."
    return 1
  fi
  echo "  ✓ a printed closure is rejected: $why"
  return 0
}

echo "── a method that does not exist must not reach output"
ssc_usable_or_skip v2-unknown-member-refuses-gate "$ssc"

if [[ "${1:-}" == "--self-test" ]]; then
  self_test || exit 1
  echo
fi

# 1-2. The defect itself, on two receiver TYPES — the hole is the missing nullary dispatch, not
#      anything about strings, and a fix that special-cases StrV would pass row 1 and fail row 2.
refuses unknown-member-on-a-string "$MARKER" 'def main(): Unit = println("m: " + "a".nosuch)'
refuses unknown-member-on-an-int   "$MARKER" 'def main(): Unit = println("m: " + 42.nosuch)'

# 3. The APPLIED form, already fixed by `__method0__` before this. It is here because both halves
#    are one property for a reader ("a name that does not exist fails"), and because a regression
#    there would otherwise be invisible until it reached a user.
refuses unknown-member-applied 'no dispatch for .nosuch' \
  'def main(): Unit = println("m: " + "a".nosuch())'

# 4. SERIALISATION IS A SECOND ESCAPE, and the one the incident this fix mirrors actually took.
#    `NativeJsonCodec` renders every closure as the JSON string "<function>", so with only the
#    rendering half in place `{"cell":"<function>"}` still reached a payload — measured, not
#    supposed. Its control lives in the block below: a genuine closure must still serialise.
refuses unknown-member-in-json "$MARKER" \
  'def main(): Unit = println(jsonStringify(Map("cell" -> "a".nosuch)))'

echo "── what must NOT change: the fallback is load-bearing"

# THE ANTI-ROWS. Deleting the eta fallback — the tempting "just make the selection refuse, like the
# interpreter" — would satisfy every row above and break `hof` and `bound`. `len` keeps a real
# nullary member answering with its VALUE rather than a function, and `json` keeps the codec's
# ordinary behaviour for a genuine closure.
answers_block eta-and-controls 'hof: true
bound: true
len: 3
json: {"cell":"<function>"}' \
  'def main(): Unit =
  println("hof: " + List("b", "z").exists("abc".contains))
  val f = "abc".contains
  println("bound: " + List("b", "z").exists(f))
  println("len: " + "abc".length)
  val g = (x: Int) => x + 1
  println("json: " + jsonStringify(Map("cell" -> g)))'

echo "── a class-method call with the wrong number of arguments"

# THE SAME FAMILY AS EVERYTHING ABOVE — a wrong ANSWER where an error belongs — reached through the
# calling convention rather than through dispatch. A body method is registered as a tagged method
# and invoked with `self :: args`; `callClos` extends the closure's environment with however many
# values it is handed and the body reads its parameters by POSITION. Hand it one value too few and
# nothing fails: every parameter picks up its left-hand neighbour and the first one picks up `self`.
#
#   class D:  def h(a: Int, b: Int)     d.h(7)     -> H(D,7)   (v1: missing argument for 'b')
#   class R:  def k(a: Int)             r.k()      -> K(R)
#             def t(a, b, c)            r.t(1, 2)  -> T(R,1,2)
#
# The no-argument row is the one that would reach a user first, and it is the reason this is three
# rows and not one: `k()` is what someone writes when they forget a parameter exists, and the answer
# it used to give looks like a working call.
#
# The case-class half failed DIFFERENTLY before the fix — `Index -1 out of bounds for length 2`, an
# internal message with no source location — so it is asserted separately rather than assumed to
# share a fate with the plain class.
refuses class-method-one-short 'D.h: expected 2 argument(s), got 1' \
  'case class D(z: Int):
  def h(a: Int, b: Int): String = "H(" + a.toString + "," + b.toString + ")"

def main(): Unit = println(D(0).h(7))'

refuses class-method-nullary-call 'R.k: expected 1 argument(s), got 0' \
  'case class R(z: Int):
  def k(a: Int): String = "K(" + a.toString + ")"

def main(): Unit = println(R(0).k())'

refuses class-method-two-short 'T.t: expected 3 argument(s), got 2' \
  'case class T(z: Int):
  def t(a: Int, b: Int, c: Int): String = "T(" + a.toString + "," + b.toString + "," + c.toString + ")"

def main(): Unit = println(T(0).t(1, 2))'

# THE ANTI-ROW for the three above: refusing every class-method call would satisfy all of them.
# Right-arity calls must still answer, including the nullary method that has no arguments to get
# wrong — that one is the case a naive `args.length != arity` off-by-one would break.
answers_block class-method-right-arity 'one: K(5)
three: T(1,2,3)
nullary: 9' \
  'case class C(z: Int):
  def k(a: Int): String = "K(" + a.toString + ")"
  def t(a: Int, b: Int, c: Int): String = "T(" + a.toString + "," + b.toString + "," + c.toString + ")"
  def zed(): Int = z

def main(): Unit =
  val c = C(9)
  println("one: " + c.k(5))
  println("three: " + c.t(1, 2, 3))
  println("nullary: " + c.zed().toString)'

echo "── two methods, one name, different parameter counts"

# THE OTHER HALF of the arity work above. The refusals exist so a wrong count cannot answer; this
# row exists so a RIGHT count reaches the method that matches it. Two same-named methods used to
# collide on one mangled global `Tag_m`, so only the LAST declaration existed and the other was
# unreachable by any call — which the refusal rows above would happily have called correct.
#
# BOTH DECLARATION ORDERS, in one program, because "last wins" is exactly what this replaces: with a
# single order the row passes on an implementation that still keeps only the last, as long as the
# test happens to call that one. `A` declares one-arg first, `B` declares it second; if either order
# regressed, its half of the output changes and the other half does not.
#
# The sibling call is NOT here on purpose: `g(3)` from inside another method resolves through the
# bare global and refuses on the interpreter too (`missing argument for parameter 'b'` there,
# `arity: 3 expected, 2 given` here). Both lanes agree, so it is a documented limit rather than a
# regression, and asserting it would freeze a behaviour worth improving later.
answers_block class-method-overloads 'A one: 70
A two: 3
B one: 70
B two: 3' \
  'case class A(z: Int):
  def f(a: Int): Int = a * 10
  def f(a: Int, b: Int): Int = a + b

case class B(z: Int):
  def f(a: Int, b: Int): Int = a + b
  def f(a: Int): Int = a * 10

def main(): Unit =
  val a = A(0)
  val b = B(0)
  println("A one: " + a.f(7).toString)
  println("A two: " + a.f(1, 2).toString)
  println("B one: " + b.f(7).toString)
  println("B two: " + b.f(1, 2).toString)'

echo
if [[ "$fails" -ne 0 ]]; then
  echo "v2-unknown-member-refuses-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "v2-unknown-member-refuses-gate: PASS"
