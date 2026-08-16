#!/usr/bin/env bash
# `ssc-tools check` must not green-light a program its own runtime refuses — and must not reject the
# language's own constructs while doing it.
#
# ── WHY BOTH HALVES ARE IN ONE GATE ───────────────────────────────────────────────────────────────
#
# The v1 Typer's arity and argument-type checks WORKED and were invisible: `println` is declared
# `Function(List(Any), Unit)` in the prelude, variadicity is the literal test
# `paramTypes == List(SType.Any)`, and `inferType(arg)` — the only call that descends INTO an
# argument — was made only in the NON-variadic branch. So nothing inside a `println(...)` was ever
# type-checked, and that is how every program observes its result.
#
# Turning argument inference on did not break 12 corpus programs; it REVEALED 12 gaps the escape had
# been hiding, in five shapes. Each is closed and each has a row below, because a gap that was
# invisible once will be invisible again: nothing else in the suite exercises `check` on these
# constructs. (tests/BUGS.md v1-check-never-looks-inside-a-println.)
#
# ── THE CLAUSE MODEL, AND WHY ITS ROWS COME IN PAIRS ──────────────────────────────────────────────
#
# `def two(a: Int)(b: Int); two(1)(2)(3)` dies at runtime with `Not callable: 3` and used to
# type-check clean. The first attempt at catching it — "applying a concrete primitive is an error" —
# was implemented, measured and REVERTED, because the typer flattened every parameter clause into
# ONE list: under that type `two(1)` is an `Int`, so a LEGAL curried call was indistinguishable from
# applying a primitive and four correct corpus cases were rejected. The check was right; the TYPE
# was wrong.
#
# The fix is one arrow per clause, so `two(1)` is a Function and only a genuine over-application
# reaches the "not a function" arm. That is why every curried row below is a PAIR — the rejection
# AND the legal call that the reverted version broke. A gate holding only the rejection would pass
# just as happily on the version that rejects everything.
#
# `using`/`implicit` clauses are auto-supplied and must NOT become arrows, or `display(x)` on
# `def display[A](a: A)(using s: Show[A])` becomes an under-application typed `Show[A] => String`.
# The two tagless corpus cases below are the rows that hold that distinction.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SSC_TOOLS="$ROOT/bin/ssc-tools"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/ssc-v1-check.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0

[[ -x "$SSC_TOOLS" ]] || { echo "v1-check-sees-what-it-runs: no $SSC_TOOLS — run ./install.sh --dev" >&2; exit 2; }

check_src() {  # check_src <name> <src> -> the first line check prints
  printf '```scalascript\n%s\n```\n' "$2" > "$WORK/$1.ssc"
  SSC_NO_CDS=1 timeout 200 "$SSC_TOOLS" check "$WORK/$1.ssc" 2>&1 | head -1
}
# NOTE the matching: bash pattern tests, never `printf … | grep -q`. Under `pipefail` a `grep -q`
# that matches EARLY closes the pipe, `printf` takes SIGPIPE, and the pipeline's status makes a hit
# read as a miss — this repo has paid for that exact shape once already, on a one-line haystack where
# it looked impossible.
want_rejected() {
  local out; out="$(check_src "$1" "$2")"
  if [[ "$out" == *"error:"* ]]; then
    echo "  ok   [$1] rejected: ${out##*error: }"; pass=$((pass + 1))
  else
    echo "  FAIL [$1] check ACCEPTED a program its own runtime refuses"; echo "         $out"; fail=$((fail + 1))
  fi
}
want_accepted() {
  local out; out="$(check_src "$1" "$2")"
  if [[ "$out" == *": OK" ]]; then
    echo "  ok   [$1] accepted"; pass=$((pass + 1))
  else
    echo "  FAIL [$1] check REJECTED a construct the language has"; echo "         $out"; fail=$((fail + 1))
  fi
}
# A corpus case, checked as it stands — these are the five gaps, each reachable only through a real
# program rather than a snippet.
want_corpus_ok() {
  local f="$ROOT/tests/conformance/$1.ssc" out
  [[ -f "$f" ]] || { echo "  FAIL [$1] corpus case is gone — this row now proves nothing"; fail=$((fail + 1)); return; }
  out="$(SSC_NO_CDS=1 timeout 200 "$SSC_TOOLS" check "$f" 2>&1 | head -1)"
  if [[ "$out" == *": OK" ]]; then
    echo "  ok   [$1] accepted"; pass=$((pass + 1))
  else
    echo "  FAIL [$1] a closed gap has REOPENED"; echo "         ${out##*error: }"; fail=$((fail + 1))
  fi
}

echo "============================================================"
echo "  ssc-tools check — sees inside println, and knows the language"
echo "============================================================"
echo

# ── the win: what the runtime refuses, `check` now refuses too, THROUGH a println ────────────────
want_rejected zero-arg-over-applied \
'def z(): Int = 1
println(z(5))'

want_rejected wrong-arg-type \
'def h(a: Int): Int = a
println(h("x"))'

want_rejected too-many-args \
'def h(a: Int, b: Int): Int = a + b
println(h(1, 2, 3))'

# ── and the control: a correct program stays accepted ────────────────────────────────────────────
want_accepted correct \
'def ok(a: Int): Int = a + 1
println(ok(41))'

echo
echo "  the clause model — each rejection paired with the legal call the first attempt broke:"

want_rejected curried-over-applied \
'def two(a: Int)(b: Int): Int = a + b
println(two(1)(2)(3))'

want_accepted curried-exact \
'def two(a: Int)(b: Int): Int = a + b
println(two(1)(2))'

# A partial application is a VALUE. This is the row that fails if clauses are flattened back: with
# one list `two(1)` types as Int and `p(2)` becomes the over-application above.
want_accepted curried-partial \
'def two(a: Int)(b: Int): Int = a + b
val p = two(1)
println(p(2))'

want_accepted curried-three-clauses \
'def tri(a: Int)(b: Int)(c: Int): Int = a + b + c
println(tri(1)(2)(3))'

# Per-CLAUSE arity, not total arity: `two` has two parameters between its clauses, and passing both
# to the first clause is still wrong. Distinguishes a clause model from "the old check, arity 1".
want_rejected curried-clause-arity \
'def two(a: Int)(b: Int): Int = a + b
println(two(1, 2))'

# BOTH runtimes uncurry at a call: `use(mk)` below prints 3 on `run --v1` AND `bin/ssc run`. This is
# the row the clause model BROKE on its first build — `mk` became `Int => Int => Int`, the
# assignability rule compared arity 1 against 2, and `check` rejected a program its own runtime runs.
# Nothing in the corpus covers this shape, so without this row the regression ships.
want_accepted curried-passed-as-flat \
'def mk(a: Int)(b: Int): Int = a + b
def use(f: (Int, Int) => Int): Int = f(1, 2)
println(use(mk))'

# The reverse. Accepted because `--v1` — the runtime THIS checker belongs to — prints 3. The native
# lane refuses it (`arity: 2 expected, 1 given`), which is a lane divergence filed separately, not a
# verdict for the typer to pick.
want_accepted flat-passed-as-curried \
'def flat(a: Int, b: Int): Int = a + b
def use(f: Int => Int => Int): Int = f(1)(2)
println(use(flat))'

# The anti-constant row: applying a NON-primitive is not an error — `s(0)`, `xs(0)`, `m(k)` are how
# this language indexes. A "not a function" check that fires on everything non-Function fails here.
want_accepted apply-is-indexing \
'val xs = List(1, 2, 3)
val s = "abc"
println(xs(0))
println(s(1))'

echo
echo "  the boundary: arity and type are checked inside a variadic arg, undefined-name is NOT (yet):"

# The escape hid THREE checks. Two are ready; the third is not, and BOTH rows below have to hold or
# the split has silently moved. Reporting undefined names inside a variadic argument rejected 11 of
# the examples CI already checks — `Node`, `Transport`, `vstack` and friends, all real `std/` exports
# missing from a HAND-MAINTAINED prelude list whose next tier is the ~250 names `std/ui` exports.
# (tests/BUGS.md typer-prelude-list-should-be-generated-from-std-exports.)
want_rejected undefined-at-statement-level \
'thisNameDoesNotExistAnywhere(1)'

want_accepted undefined-inside-a-variadic-arg \
'println(thisNameDoesNotExistAnywhere(1))'

echo
echo "  the five gaps the println escape was hiding — each closed, each guarded:"
# HONEST LABELLING: the first two are load-bearing here — they are TYPE and ARITY verdicts, so they
# would fail today if the fix were reverted. The last four were undefined-NAME verdicts, and the row
# above turns that reporting off in argument position, so they no longer isolate their fix — they are
# kept as cheap regression guards on real scope corrections (an in-fence import and a `direct[M]`
# binder are genuine bindings, not prelude entries) and will isolate again when the generated prelude
# lets undefined-name reporting come back on.
want_corpus_ok set-ops-infix              # `+` on a Set is an ADD, not arithmetic
want_corpus_ok parenless-def-value        # a parenless def is invoked at its mention
want_corpus_ok mcp-server-tool            # an import written INSIDE a fence still binds its names
want_corpus_ok native-import-in-fence     # the same, on the case named for it
want_corpus_ok js-generator-next-option   # `suspend` is the generator construct's keyword
want_corpus_ok direct-syntax              # `x = expr` inside `direct[M]` INTRODUCES x

echo
echo "  the four cases the FIRST attempt at the curried check rejected — the reason it was reverted:"
want_corpus_ok curried-def-clauses
want_corpus_ok curried-def-three-clauses
want_corpus_ok fewer-braces-colon
want_corpus_ok tagless-resolution         # `(using …)` must NOT become an arrow

echo
if [ $fail -eq 0 ]; then
    echo "v1-check-sees-what-it-runs: OK ($pass checks)"
    exit 0
fi
echo "v1-check-sees-what-it-runs: $pass ok, $fail FAIL"
exit 1
