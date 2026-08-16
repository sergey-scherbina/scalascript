#!/usr/bin/env bash
# A declared effect row must be TRUE and COMPLETE — not merely present.
#
# ── WHY ───────────────────────────────────────────────────────────────────────────────────────────
#
# `ssc-tools check` rejected `tests/conformance/effects.ssc` — a case that runs correctly on every
# lane — for declaring no effect row. Declaring one fixed it. Measured 2026-08-16, same file, one
# line changed:
#
#     ! Console   the effect greet actually leaks          OK
#     ! Choose    a different effect in the file           OK
#     ! Fail      another one                              OK
#     ! Nonsense  AN EFFECT THAT EXISTS NOWHERE            OK      <-- the whole finding
#
# The rule was one line — `if funIsEffectful && declared.isEmpty` — so the gate demanded a token and
# read only its emptiness. Annotating the five effect cases under that verifier would have been
# ceremony: any string passed, and the corpus would have been reported clean on five declarations
# that meant nothing. (tests/BUGS.md effect-row-verifier-demands-a-declaration-it-never-checks.)
#
# The data was already there and discarded: `leakingFuns` computed each function's leaked ops and
# kept only `.nonEmpty`. It now returns the names, so the verifier can ask whether the row says the
# truth.
#
# ── WHY THE ROWS COME IN PAIRS ────────────────────────────────────────────────────────────────────
#
# Every rejection below is paired with an acceptance, because a verifier that rejects EVERY row
# passes a rejection-only gate perfectly. The two that matter most:
#
#   * the truthful row is accepted           — else the check is just "no rows allowed"
#   * a self-DISCHARGING function is accepted — `capture()` handles its own effect and correctly
#     declares nothing (durable-save-run-verifier-red, af46212c3). This gate's subject rewrote the
#     function that decides that, so this row is the regression guard for the rewrite.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SSC_TOOLS="$ROOT/bin/ssc-tools"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/ssc-effrow.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
pass=0; fail=0

[[ -x "$SSC_TOOLS" ]] || { echo "effect-row-says-what-it-means: no $SSC_TOOLS — run ./install.sh --dev" >&2; exit 2; }

check_src() {
  printf '```scalascript\n%s\n```\n' "$2" > "$WORK/$1.ssc"
  SSC_NO_CDS=1 timeout 200 "$SSC_TOOLS" check "$WORK/$1.ssc" 2>&1 | head -1
}
# want_rejected <name> <needle-that-must-appear> <src>
want_rejected() {
  local out; out="$(check_src "$1" "$3")"
  if [[ "$out" == *"error:"* && "$out" == *"$2"* ]]; then
    echo "  ok   [$1] rejected: ${out##*error: }"; pass=$((pass + 1))
  elif [[ "$out" == *"error:"* ]]; then
    echo "  FAIL [$1] rejected, but not for the reason under test (wanted \"$2\")"
    echo "         ${out##*error: }"; fail=$((fail + 1))
  else
    echo "  FAIL [$1] accepted"; echo "         $out"; fail=$((fail + 1))
  fi
}
want_accepted() {
  local out; out="$(check_src "$1" "$2")"
  if [[ "$out" == *": OK" ]]; then echo "  ok   [$1] accepted"; pass=$((pass + 1))
  else echo "  FAIL [$1] rejected a legal program"; echo "         ${out##*error: }"; fail=$((fail + 1)); fi
}

# The same program throughout, differing only in the declared row — so a difference in verdict can
# only come from the row.
BODY='effect Console:
  def writeLine(s: String): Unit
  def readLine(): String

effect Fail:
  def die(msg: String): Int
'
greet() { printf '%s\ndef greet()%s =\n  val name = Console.readLine()\n  name\n\nval r = handle(greet()) {\n  case Console.readLine(resume) => resume("Alice")\n}\nprintln(r)\n' "$BODY" "$1"; }

echo "============================================================"
echo "  a declared effect row must be true and complete"
echo "============================================================"
echo

want_accepted truthful-row "$(greet ': String ! Console')"

want_rejected unknown-effect "naming no effect in scope" \
  "$(greet ': String ! Nonsense')"

want_rejected incomplete-row "must cover what escapes" \
  "$(greet ': String ! Fail')"

want_rejected no-row-at-all "declares no effect row" \
  "$(greet ': String')"

# The message must name the function's OWN leaked effects. It used to print every effect in the
# BLOCK — `greet` "reaches Console, Choose, Fail" when it touches only Console — which sends the
# reader to the wrong place.
out="$(check_src "own-set" "$(greet ': String')")"
if [[ "$out" == *"leaks Console"* && "$out" != *"Fail"* ]]; then
  echo "  ok   [own-set] the message names the function's own leaked effects, not the block's"; pass=$((pass + 1))
else
  echo "  FAIL [own-set] the message does not name greet's own set"; echo "         ${out##*error: }"; fail=$((fail + 1))
fi

echo
echo "  and the things that must NOT become errors:"

# A function that fully DISCHARGES its own effect leaks nothing and correctly declares nothing.
want_accepted self-discharging \
'effect Suspend:
  def point(): Int

def capture(): Int =
  handle(Suspend.point()) {
    case Suspend.point(resume) => resume(7)
  }
println(capture())'

# Anti-constant: a program with no effects at all is untouched by any of this.
want_accepted no-effects-at-all \
'def add(a: Int, b: Int): Int = a + b
println(add(1, 2))'

echo
if [ $fail -eq 0 ]; then
  echo "effect-row-says-what-it-means: OK ($pass checks)"
  exit 0
fi
echo "effect-row-says-what-it-means: $pass ok, $fail FAIL"
exit 1
