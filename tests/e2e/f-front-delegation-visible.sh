#!/usr/bin/env bash
#
# f-front-delegation-visible — when F does not compile a file, say so; when the user made a typo,
# do not.
#
# BUGS.md `f-front-silent-delegation-hides-coverage-gaps`. F is the DEFAULT native front. When it
# cannot lower a file, `RunNativeV2` transparently re-lowers through the legacy front and uses that
# result. Correct behaviour — but it was announced only under `SSC_FRONT_TRACE`, an env var nobody
# sets, so every F coverage gap looked like plain success at the CLI, and any corpus run driven
# through `bin/ssc` measured the LEGACY front for those programs while reporting them as F.
#
# THE TEST IS TWO-SIDED BY NECESSITY, not for symmetry. The fallback fires for two unrelated
# reasons, because an F gap and a user error both surface as an unbound global: `undefinedThing()`
# in user code routes through exactly the same path as a construct F cannot lower. A one-sided test
# ("the marker appears") would be satisfied by printing it unconditionally — which would put a
# compiler-internals line under every typo, and a message that appears when nothing is wrong is one
# people filter out within a day, taking the real signal with it.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="$ROOT/bin/ssc"
MARKER='ssc: F did not lower this file; compiled with the default front instead'
# Strict mode (SSC_FRONT_STRICT=1) turns that fallback into a hard error, for measurement runs.
# Duplicated from the runner for the same reason as MARKER: a silent rewording must fail here.
STRICT_MARKER='refusing to fall back to the reference front'
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/f-delegation.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

if [[ ! -x "$ssc" ]]; then
  echo "SKIP f-front-delegation-visible: $ssc not built (run scripts/sbtc installBin)"
  exit 0
fi

# The marker string is duplicated between the runner and this gate on purpose — that is what makes a
# silent rewording fail here instead of silently switching the measurement off. Assert they match.
if ! grep -qF "$MARKER" \
     "$ROOT/v1/tools/cli/src/main/scala/scalascript/cli/RunNativeV2.scala"; then
  echo "FAIL the marker in RunNativeV2.scala no longer matches the one this gate greps for"
  echo "     gate expects: $MARKER"
  fails=$((fails + 1))
fi
if ! grep -qF "$STRICT_MARKER" \
     "$ROOT/v1/tools/cli/src/main/scala/scalascript/cli/RunNativeV2.scala"; then
  echo "FAIL the strict-refusal text in RunNativeV2.scala no longer matches this gate"
  echo "     gate expects: $STRICT_MARKER"
  fails=$((fails + 1))
fi

# ── a program F handles: no marker, correct output ───────────────────────────────────────────────
cat > "$sandbox/plain.ssc" <<'SSC'
def main(): Int =
  val xs = List(1, 2, 3)
  xs.map(x => x * 2).sum
SSC
out=$("$ssc" run "$sandbox/plain.ssc" 2>"$sandbox/plain.err"); rc=$?
err=$(cat "$sandbox/plain.err")
if [[ $rc -ne 0 ]]; then
  echo "FAIL [plain] exit $rc"; echo "     stderr: $err"; fails=$((fails + 1))
elif grep -qF "$MARKER" <<<"$err"; then
  echo "FAIL [plain] claimed a delegation for a file F compiles — the marker would be meaningless"
  echo "     stderr: $err"; fails=$((fails + 1))
else
  echo "ok   [plain] compiled by F, no marker"
fi

# ── a user error: the fallback DOES fire, and must stay quiet about it ────────────────────────────
# `undefinedThing()` is an unbound global, which is exactly the signal F's coverage pre-check uses,
# so this genuinely exercises the delegation path — it is not a program that merely fails early.
cat > "$sandbox/typo.ssc" <<'SSC'
def main(): Int = undefinedThing()
SSC
"$ssc" run "$sandbox/typo.ssc" >"$sandbox/typo.out" 2>"$sandbox/typo.err"
err=$(cat "$sandbox/typo.err")
if grep -qF "$MARKER" <<<"$err"; then
  echo "FAIL [user-error] printed the coverage-gap marker for a plain typo"
  echo "     A message that fires when nothing is wrong is one people learn to ignore."
  echo "     stderr: $err"; fails=$((fails + 1))
else
  echo "ok   [user-error] fallback fired silently, as it should"
fi

# ── a real F gap: the marker must appear ─────────────────────────────────────────────────────────
# `summon` is a construct F does not lower today and the reference front does — the two conditions
# that define a coverage gap. Written inline rather than SEARCHED for: scanning the conformance
# tree for a delegating file costs ~17s per file across 374 files (the census lowers through both
# fronts), which is ~100 minutes to establish one bit that a two-line file establishes in one run.
#
# Inherently perishable — when F grows to cover `summon` this stops being a gap. That is handled,
# not ignored: the census below distinguishes "F now covers it" (SKIP, and this block needs a new
# construct) from "still a gap but nothing was announced" (FAIL, the signal is off). Those two must
# never look the same, which is the whole reason this gate exists.
cat > "$sandbox/gap.ssc" <<'SSC'
trait Monoid[A]:
  def empty: A
  def combine(a: A, b: A): A

given intSum: Monoid[Int] with
  def empty: Int = 0
  def combine(a: Int, b: Int): Int = a + b

def combineAll[A: Monoid](xs: List[A]): A =
  xs.foldLeft(summon[Monoid[A]].empty)(summon[Monoid[A]].combine)

def main(): Int = combineAll(List(1, 2, 3))
SSC
gap_found=0
gap_file=""
verdict=$("$ssc" info --front-report "$sandbox/gap.ssc" 2>/dev/null | awk -F'\t' '{print $2}')
"$ssc" run "$sandbox/gap.ssc" >/dev/null 2>"$sandbox/gap.err" || true
if [[ "$verdict" == "GAP" ]]; then
  if grep -qF "$MARKER" "$sandbox/gap.err"; then
    echo "ok   [f-gap] a real gap delegates and says so"
    gap_found=1
    gap_file="$sandbox/gap.ssc"
  else
    echo "FAIL [f-gap] the census calls this file a GAP, but running it announced nothing —"
    echo "     the coverage gap is real and the signal that reports it is off."
    echo "     stderr: $(cat "$sandbox/gap.err")"
    fails=$((fails + 1))
  fi
elif [[ "$verdict" == "F" ]]; then
  echo "SKIP [f-gap] F now covers \`summon\`, so this file is no longer a gap. Good news, but this"
  echo "     block now tests nothing — replace it with a construct F still declines."
else
  echo "FAIL [f-gap] expected GAP or F from the census, got '\''$verdict'\'' — the fixture is broken,"
  echo "     so a passing run here would mean nothing."
  fails=$((fails + 1))
fi

# ── strict mode: a file F compiles is UNAFFECTED ─────────────────────────────────────────────────
# The first thing to get wrong would be a flag that fails on everything; then it is not a
# measurement tool, it is an off switch.
out=$(SSC_FRONT_STRICT=1 "$ssc" run "$sandbox/plain.ssc" 2>"$sandbox/strict-plain.err"); rc=$?
if [[ $rc -ne 0 ]]; then
  echo "FAIL [strict/plain] strict mode broke a file F compiles fine (exit $rc)"
  echo "     stderr: $(cat "$sandbox/strict-plain.err")"; fails=$((fails + 1))
else
  echo "ok   [strict/plain] F-compiled file runs normally under strict mode"
fi

# ── strict mode: the quiet category fails too, and says whose fault it is ────────────────────────
# BOTH-UNBOUND is silent by default because it is usually a typo. Under strict it must STILL fail:
# the measurement does not care whose fault it was — F's output was discarded either way.
SSC_FRONT_STRICT=1 "$ssc" run "$sandbox/typo.ssc" >/dev/null 2>"$sandbox/strict-typo.err"; rc=$?
err=$(cat "$sandbox/strict-typo.err")
if [[ $rc -eq 0 ]]; then
  echo "FAIL [strict/user-error] exited 0 — strict mode let a non-F run produce a result"
  fails=$((fails + 1))
elif ! grep -qF "$STRICT_MARKER" <<<"$err"; then
  echo "FAIL [strict/user-error] failed without the refusal message; the error is unexplained"
  echo "     stderr: $err"; fails=$((fails + 1))
elif ! grep -qiF "likely your program" <<<"$err"; then
  echo "FAIL [strict/user-error] refused without naming WHICH category — the fix differs per case"
  echo "     stderr: $err"; fails=$((fails + 1))
else
  echo "ok   [strict/user-error] refused, and named it as the user's program rather than an F gap"
fi

# ── strict mode: a real F gap fails and is named as a gap ────────────────────────────────────────
if [[ -n "$gap_file" ]]; then
  SSC_FRONT_STRICT=1 "$ssc" run "$gap_file" >/dev/null 2>"$sandbox/strict-gap.err"; rc=$?
  err=$(cat "$sandbox/strict-gap.err")
  if [[ $rc -eq 0 ]]; then
    echo "FAIL [strict/f-gap] $(basename "$gap_file") exited 0 under strict mode despite delegating"
    fails=$((fails + 1))
  elif ! grep -qF "coverage gap" <<<"$err"; then
    echo "FAIL [strict/f-gap] refused without calling it an F coverage gap"
    echo "     stderr: $err"; fails=$((fails + 1))
  elif grep -qF "$MARKER" <<<"$err"; then
    echo "FAIL [strict/f-gap] announced 'compiled with the default front instead' AND refused —"
    echo "     under strict mode no such compile happens, so that line states something untrue."
    echo "     stderr: $err"; fails=$((fails + 1))
  else
    echo "ok   [strict/f-gap] $(basename "$gap_file") refused and named as a coverage gap"
  fi

  # ── strict mode must NOT break the tool that FINDS the gaps ────────────────────────────────────
  # `frontReport` catches Throwable and prints ERROR, so a strict refusal raised inside it would
  # turn every GAP into ERROR — the census would lose the one category it exists to report, and it
  # would do so exactly when someone set the flag to go looking for gaps.
  rep=$(SSC_FRONT_STRICT=1 "$ssc" info --front-report "$gap_file" 2>/dev/null)
  if grep -q "ERROR" <<<"$rep"; then
    echo "FAIL [strict/front-report] strict mode turned the census into ERROR rows"
    echo "     report: $rep"; fails=$((fails + 1))
  elif ! grep -qE "GAP|BOTH-UNBOUND" <<<"$rep"; then
    echo "FAIL [strict/front-report] census did not report a delegation for a known delegating file"
    echo "     report: $rep"; fails=$((fails + 1))
  else
    echo "ok   [strict/front-report] census still reports the category under strict mode"
  fi
else
  echo "SKIP [strict/f-gap] no gap fixture available (see the [f-gap] line above)"
fi

if [[ $fails -ne 0 ]]; then
  echo "f-front-delegation-visible: $fails failure(s)"
  exit 1
fi
echo "f-front-delegation-visible: all checks passed"
