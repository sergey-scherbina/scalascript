#!/usr/bin/env bash
#
# f-trailing-block-gate — front F must pass an `extern def` call site through VERBATIM, and must run
# the trailing block of a curried extern rather than silently dropping it.
#
# `f-drops-a-trailing-block-argument-without-running-it`. `httpClient(url) { … }` printed `after` and
# never `inside-block` — no decline, no error, the block simply did not run. Both fronts completed,
# so this was the worst category on the board: two programs that RUN and disagree on the answer.
#
# THE CAUSE IS NOT THE BLOCK. Dumped F's own IR (bootstrapping F0 the way specs/v2.2-p6.5-corpus.sh
# does) beside the oracle's, for one three-line program:
#
#   F    (app (global httpClient) "u" (lam 0 …))          <- flattened onto the total arity
#   REF  (app (app (global httpClient) "u") (lam 0 …))    <- nested
#
# F was treating the extern like an ordinary curried def. The native implementation takes one
# argument and returns the applier; handed two, it discarded the thunk without complaint.
#
# AND THE RULE IS GENERAL — the same dump showed the oracle does nothing else to an extern call
# either. This gate pins all three, because two of them were live divergences and the third was one
# I had introduced myself with the vararg work:
#
#   extern def rd(options: Int = 5)   rd()        REF (app (global rd))          no default synthesis
#   extern def va(xs: Int*)           va(1, 2)    REF (app (global va) 1 2)      no vararg list
#
# The corresponding NON-extern forms must keep their transformations — that is what makes this a
# distinction rather than a retreat, and they are asserted below as controls.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
fails=0
export SSC_NO_BUILD_CHECK=1

echo "── an extern call site passes through verbatim"

# The guard is FUNCTIONAL: `-x "$ssc"` was the old test and it is not the question — a fresh
# worktree has an executable launcher and no jars, so every case below "failed" on
# ClassNotFoundException instead of skipping. See tests/e2e/lib/ssc-usable.sh.
ssc_usable_or_skip f-trailing-block-gate "$ssc"

# The probes live under examples/ because the ones that touch std/http import it by a RELATIVE path
# (`../v1/runtime/std/http.ssc`), exactly as examples/_bug1b.ssc does; from a temp dir that import
# does not resolve and every case fails for a reason that has nothing to do with the front.
sandbox="$ROOT/examples/_ftb_probe"
mkdir -p "$sandbox"
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

# $1 name, $2 expected FULL stdout (newlines as |), $3 source
runs_as() {
  local name=$1 want=$2 src=$3
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  local strict out
  strict=$(SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1)
  if grep -qF 'refusing to fall back' <<<"$strict"; then
    echo "  ✗ $name: F DECLINED"
    fails=$((fails + 1)); return
  fi
  out=$(timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1 | head -6 | tr '\n' '|')
  if [[ "$out" == "$want" ]]; then
    echo "  ✓ $name: $out"
  else
    echo "  ✗ $name: got '$out', wanted '$want'"
    fails=$((fails + 1))
  fi
}

# THE ORIGINAL: the block must run, and run BEFORE the statement after it.
runs_as extern-curried-block 'inside-block|after|' '[httpClient](../../v1/runtime/std/http.ssc)
def main(): Unit =
  httpClient("http://example.invalid") {
    println("inside-block")
  }
  println("after")'

# Single-line spelling of the same thing — the block shape was the first thing I suspected and it
# was never the cause, so both spellings stay pinned.
runs_as extern-curried-block-inline 'inside-block|after|' '[httpClient](../../v1/runtime/std/http.ssc)
def main(): Unit =
  httpClient("http://example.invalid") { println("inside-block") }
  println("after")'

# Several statements in the block, which is what examples/_bug1b.ssc actually writes.
runs_as extern-curried-block-multi 'inside-block|after|' '[httpClient, httpTimeout, httpRetry](../../v1/runtime/std/http.ssc)
def main(): Unit =
  httpClient("http://example.invalid") {
    println("inside-block")
    httpTimeout(2000)
    httpRetry(2, 500)
  }
  println("after")'

echo "── controls: the non-extern forms must KEEP their transformations"

# A curried plain def still flattens onto the total arity — the distinction is extern-ness, not the
# syntax. Without this control, "stop transforming curried calls" would pass everything above.
runs_as ctl-plain-curried-flattens 'a24|' 'def v(gap: Int)(c: String): String = c + gap.toString
def main(): Unit = println(v(24)("a"))'

runs_as ctl-plain-default-synthesised 'a0|' 'def v(gap: Int = 0)(c: String): String = c + gap.toString
def main(): Unit = println(v()("a"))'

runs_as ctl-plain-vararg-list '2|' 'def f(xs: String*): Int = xs.toList.length
def main(): Unit = println(f("a", "b"))'

# ── the corpus files this was found on ───────────────────────────────────────────────────────────
echo "── the corpus files"
for f in examples/_bug1b.ssc examples/_bug1c.ssc; do
  if [[ ! -f "$ROOT/$f" ]]; then echo "  SKIP $f: not present"; continue; fi
  out=$(SSC_FRONT_STRICT=1 timeout 200 "$ssc" run "$ROOT/$f" 2>&1 | head -1)
  if [[ "$out" == "inside-block" ]]; then
    echo "  ✓ $f: the block runs first"
  else
    echo "  ✗ $f: first line '$out', wanted 'inside-block'"
    fails=$((fails + 1))
  fi
done

if [[ $fails -eq 0 ]]; then
  echo "✓ f-trailing-block-gate PASSED"
  exit 0
fi
echo "✗ f-trailing-block-gate: $fails failure(s)"
exit 1
