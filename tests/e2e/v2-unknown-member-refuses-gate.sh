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

answers() { # $1 name, $2 expected first line, $3 source — on BOTH fronts
  local name=$1 want=$2 src=$3 r f
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  r=$(run_front legacy "$sandbox/$name.ssc" | head -1)
  f=$(run_front F "$sandbox/$name.ssc" | head -1)
  if [[ "$r" == "$want" && "$f" == "$want" ]]; then
    echo "  ✓ $name: $want"
  else
    echo "  ✗ $name: reference='$r' F='$f', wanted '$want' from both"
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

echo "── the eta-expansion still works when the value is CALLED"

# 4. THE ANTI-ROW. Deleting the eta fallback would make rows 1-3 pass and break this.
answers eta-passed-to-a-hof true \
  'def main(): Unit = println(List("b", "z").exists("abc".contains))'

# 5. The same value through a local binding — the shape the runtime comment cites as the reason the
#    fallback exists (`list.exists(lc.contains)`), written the way a user actually hits it.
answers eta-bound-then-called true \
  'def main(): Unit =
  val f = "abc".contains
  println(List("b", "z").exists(f))'

# 6. A real nullary member still answers with its VALUE rather than a function — the dispatch this
#    fix must not have shadowed.
answers nullary-member-still-answers 'm: 3' \
  'def main(): Unit = println("m: " + "abc".length)'

echo
if [[ "$fails" -ne 0 ]]; then
  echo "v2-unknown-member-refuses-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "v2-unknown-member-refuses-gate: PASS"
