#!/usr/bin/env bash
# v3 SSC3-11 — the gate that will decide the UniML front swap.
#
# `v3/specs/40-front-on-uniml.md` §7: the UniML front is adopted when, for every fixture and every
# corpus case v3 compiles, BOTH fronts print the same `Ast` in canonical form. Comparing parsers any
# other way means comparing them through the lowering, the verifier and a backend, where a
# difference arrives far from its cause — and a compensating PAIR of differences arrives not at all.
#
# WHAT THIS GATE IS TODAY, said plainly: there is ONE front. It therefore compares nothing, and
# saying "GREEN" on that basis would be the vacuous-gate shape this repository has already paid for
# twice. So it does the two things it honestly can:
#
#   1. TOTALITY — every fixture prints an `Ast`, non-empty, exit 0. A printer with a missing arm is
#      a compile error in Scala, but `floatText` on a NaN or a name with a quote in it is not, and
#      those reach this gate rather than a user.
#   2. A SELF-TEST of the COMPARATOR — one fixture's output is mutated by one token and the
#      comparison must report a difference. That is the doctrine applied before the thing it guards
#      exists: a comparator nobody has watched fail is a hypothesis, and it is much cheaper to find
#      out now than on the day the second front lands and everything is green for the wrong reason.
#
# When `Front.available` grows a second entry this file starts comparing front to front with no
# edit — that is the point of listing the fronts rather than hard-coding two names.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
# Overridable so this gate can be POINTED AT a deliberately broken front and shown to catch it.
# A guard that cannot be made to fire is a guard nobody has checked, which is the thing this file
# spent the day being an example of.
SSC3="${SSC3:-$ROOT/v3/ssc3}"

fail=0
ran=0
agree=0
disagree=0
unimlonly=0

# The agreement FLOOR. It may rise in any commit and fall in none — the same non-regression rule the
# corpus number carries, and for the same reason: a gate that is permanently red stops being read,
# and a gate with no floor lets the number slide back without anyone noticing.
FLOOR="${SSC3_FRONT_AGREE_FLOOR:-54}"

# The fronts the DRIVER says it can run. Asked rather than duplicated here: a list in two places is
# a list that disagrees with itself. The kernel knows one front; the driver knows whether the second
# one's classpath is present, which is a fact about the working tree rather than about the code.
fronts="$("$SSC3" fronts)"
nfronts="$(printf '%s\n' "$fronts" | grep -c .)"

echo "── fronts declared runnable: $(printf '%s' "$fronts" | tr '\n' ' ') ──"

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

# GENERATED NAMES ARE CANONICALISED BEFORE COMPARING, and without this the gate cannot ever pass on
# a construct that generates one.
#
# The `match` desugaring invents a binder named after the SOURCE POSITION it came from — `$m20_14`.
# The two fronts compute that position differently, so the same program prints `$m20_14` on one and
# `$m21_7` on the other, and three `actors-*` cases differed by nothing else. A differential that
# compares generated names is measuring the name generator.
#
# Renumbered BY ORDER OF FIRST APPEARANCE, which keeps the comparison honest: two trees that bind
# DIFFERENT variables still canonicalise differently, because the order they appear in differs. It
# erases the numbering, not the binding.
canon() {
  python3 -c '
import re, sys
t = sys.stdin.read()
seen = {}
def sub(m):
    k = m.group(0)
    if k not in seen: seen[k] = "$m%d" % (len(seen) + 1)
    return seen[k]
sys.stdout.write(re.sub(r"\$m\d+_\d+", sub, t))
'
}

# EACH FRONT IS PROBED ONCE, BEFORE THE CORPUS, and a front that cannot print stops the gate here.
#
# `fronts` lists what is REGISTERED — a fact about the working tree. Whether a front COMPILES is a
# different fact, and this gate used to discover it once per fixture. On 2026-08-08 the UniML front
# stopped compiling (a package collision with its own `scalascript`); the run paid for the same
# failing compile fifty times, was still going at forty minutes, and `timeout` killed it before it
# printed a verdict. Two people read partial logs of that run and drew opposite conclusions — "red
# with 36 disagreements" and "green while comparing nothing" — and neither was a reading of a
# finished run. A gate nobody can read is not a slow gate.
#
# Failing here rather than warning: a differential with a front that cannot print is not a
# comparison, and the one thing it must never do is produce a number anyway.
# The probe is WRITTEN HERE, not taken from the fixtures. Picking `ls | head -1` was my first
# version and it is wrong: some fixtures are legitimately refused by one front — `object-nested-class`
# is declared uniml-only — so a probe that happens to land on one would fail the gate for a
# construct, not for a broken front. A `println` neither front can refuse separates "this front does
# not work" from "this front does not have that construct", which is the whole distinction.
probe="$work/probe.ssc"
printf '```scalascript\nprintln(1)\n```\n' > "$probe"
if [ -n "$probe" ]; then
  for fr in $fronts; do
    if ! "$SSC3" ast "$probe" "$fr" > "$work/probe.$fr" 2>"$work/probe.$fr.err"; then
      echo "  FAIL front '$fr' cannot print an Ast at all — it is registered but does not work:"
      sed 's/^/         /' "$work/probe.$fr.err" | grep -v '^\s*$' | head -3
      echo "       Every fixture below would refuse for this one reason. Fix the front, or"
      echo "       unregister it; comparing what is left against itself is not a differential."
      echo "== v3 SSC3-11 gate: RED (a declared front does not run) =="
      exit 1
    fi
  done
fi

for f in v3/tests/front/*.ssc; do
  name="$(basename "$f" .ssc)"
  # A fixture that is EXPECTED to be refused has no `.expected`; printing its Ast is not meaningful.
  [ -f "v3/tests/front/$name.expected" ] || continue
  ran=$((ran + 1))

  first=""
  for fr in $fronts; do
    out="$work/$name.$fr"
    if ! "$SSC3" ast "$f" "$fr" > "$out" 2>"$work/err"; then
      # The FIRST front is v3's own and must always print — a failure there is a real defect. A
      # second front refusing is expected work-in-progress and is counted, not failed.
      if [ "$fr" = "v3" ]; then
        # A fixture may exercise a construct ONLY the uniml front has — that is what the swap was
        # for, and `object-nested-class` is the first: v3's own parser refuses a `case class` inside
        # an `object`, and `v3/src/Parser.scala` belongs to another claim. Marked with a
        # `.uniml-only` file beside it so the state is DECLARED rather than discovered, and counted
        # so it cannot grow quietly — an unmarked refusal is still a failure.
        if [ -f "v3/tests/front/$name.uniml-only" ]; then
          unimlonly=$((unimlonly + 1))
        else
          echo "  FAIL $name — the v3 front could not print an Ast: $(head -1 "$work/err")"
          fail=1
        fi
      else
        echo "  refused $name — $(head -1 "$work/err" | sed 's/^ssc3: //' | cut -c1-70)"
        disagree=$((disagree + 1))
      fi
      continue
    fi
    if [ ! -s "$out" ]; then
      echo "  FAIL $name — front '$fr' printed NOTHING at exit 0"
      fail=1
      continue
    fi
    if [ -z "$first" ]; then
      first="$out"
    elif ! diff -q <(canon < "$first") <(canon < "$out") >/dev/null; then
      echo "  diff $name"
      diff <(canon < "$first") <(canon < "$out") | head -4 | sed 's/^/         /'
      disagree=$((disagree + 1))
    else
      agree=$((agree + 1))
    fi
  done
done

# ── THE CORPUS, not the fixture set ──────────────────────────────────────────────────────────────
# The 48 fixtures above are a PROBE SET, and this repository has already paid for trusting one: a
# parity probe set read 40/42 green while the corpus it was supposed to stand for read 34 against
# 48. A probe set is written by whoever is fixing the thing it measures, so it drifts toward what
# has been fixed. The conformance corpus was not, so it is the honest denominator.
#
# It compares only what BOTH fronts print. A case v3's own front refuses is not a front-to-front
# disagreement — it is a construct v3 does not have, which the corpus report already counts.
if [ "${SSC3_FRONT_DIFF_CORPUS:-1}" = 1 ] && [ "$nfronts" -ge 2 ]; then
  echo
  echo "── the conformance corpus ────────────────────────────────────────────────"
  cagree=0; cdiff=0; conly_v3=0; conly_uniml=0; cneither=0
  cdiffs="$work/corpus-diffs.txt"; : > "$cdiffs"
  # BOTH FRONTS ARE ASKED EVEN WHEN THE FIRST REFUSES. The loop used to short-circuit — if v3's
  # front failed it counted the file and moved on without ever asking UniML — which is why the one
  # number it produced could not tell a capability gap from a gap both fronts share. Asking twice
  # costs one extra invocation on the ~49 files v3 refuses, and buys the distinction the whole
  # guard below rests on.
  for f in tests/conformance/*.ssc; do
    cname="$(basename "$f" .ssc)"
    v3ok=0; unok=0
    "$SSC3" ast "$f" v3    > "$work/c.v3"    2>/dev/null && [ -s "$work/c.v3" ]    && v3ok=1
    "$SSC3" ast "$f" uniml > "$work/c.uniml" 2>/dev/null && [ -s "$work/c.uniml" ] && unok=1
    if [ "$v3ok" = 1 ] && [ "$unok" = 1 ]; then
      if diff -q <(canon < "$work/c.v3") <(canon < "$work/c.uniml") >/dev/null; then
        cagree=$((cagree + 1))
      else
        cdiff=$((cdiff + 1))
        printf '%s\t%s\n' "$cname" "$(diff <(canon < "$work/c.v3") <(canon < "$work/c.uniml") | head -2 | tr '\n' ' ' | cut -c1-110)" >> "$cdiffs"
      fi
    elif [ "$v3ok" = 1 ]; then conly_v3=$((conly_v3 + 1))
    elif [ "$unok" = 1 ]; then conly_uniml=$((conly_uniml + 1))
    else cneither=$((cneither + 1))
    fi
  done
  cboth=$((cagree + cdiff))
  ccap=$((conly_v3 + conly_uniml))
  echo "  both fronts print: $cboth; they AGREE on $cagree, differ on $cdiff"
  echo "  one front only: $ccap (v3 $conly_v3, uniml $conly_uniml) — a capability gap, guarded below"
  echo "  NEITHER front prints: $cneither — a gap the two SHARE, counted by the corpus report, not guarded here"
  [ "$cdiff" -gt 0 ] && sed 's/^/    /' "$cdiffs" | head -8
  CFLOOR="${SSC3_FRONT_CORPUS_FLOOR:-277}"
  if [ "$cagree" -lt "$CFLOOR" ]; then
    echo "  FAIL corpus agreement $cagree REGRESSED below the floor $CFLOOR"
    fail=1
  fi
  # THE DISAGREEMENT COUNT HAS ITS OWN CEILING, and it needed one.
  #
  # A floor on AGREE alone is not a guard: when typed patterns landed, the number of cases both
  # fronts can print went 105 -> 219, agreement went 105 -> 145, and the gate stayed green while
  # disagreements went 0 -> 74. The floor rose with the good number and said nothing about the bad
  # one. A ratio would have the same hole in the other direction — it improves whenever the
  # denominator grows.
  #
  # So: two numbers, two directions, both non-regressing. This is the same rule the corpus N
  # carries (I-5) applied to the thing the differential actually measures.
  CCEIL="${SSC3_FRONT_CORPUS_DIFF_CEILING:-0}"
  if [ "$cdiff" -gt "$CCEIL" ]; then
    echo "  FAIL corpus DISAGREEMENTS rose to $cdiff, above the ceiling $CCEIL"
    fail=1
  fi

  # AND THE THIRD BUCKET, which had no guard at all while being the largest number in this report.
  #
  # The lesson above was learnt once and then applied to only two of the three numbers. Files
  # exactly ONE front can print are not agreements and not disagreements, so neither the floor nor
  # the ceiling above says anything about them — and `a v3 refusal is not a disagreement`, printed
  # right there, is true and is also the reason nobody looked.
  #
  # It moved from a dozen to 128 in a single commit (the loader following `std-to-repo-root`), and
  # that particular move was an IMPROVEMENT: 116 files went from "the default front cannot load
  # this" to "it loads".
  #
  # CORRECTION, 2026-08-10, and it is the reason this number is now three numbers. I first wrote
  # here that 270 + 128 covering the corpus meant no file was left that NEITHER front can load.
  # That does not follow and it is false: `conly` counts every file where AT LEAST ONE front failed,
  # so a file both fronts refuse lands in it too. Measured, twice, on two trees: of the 123, eleven
  # are v3-only, seventy-four are uniml-only, and **thirty-eight are refused by both**
  # (`actors-bounded-mailbox` is one — v3 says `expected ')', found :`, UniML says `extension` is
  # outside Tier 0). Guarding those 38 as a front divergence would be wrong twice over: they are a
  # shared Tier-0 gap, already counted by the corpus report's UNSUPPORTED bucket, and they move for
  # reasons that have nothing to do with the fronts drifting apart.
  #
  # `v3/front-capability-gate.sh` owns the per-row statement of WHICH files diverge, by name, in
  # both directions. This file keeps the counts, split so that one cannot hide behind another. But
  # the
  # bucket grows the other way too, and that direction is invisible: a construct taught to one
  # front and not the other widens the capability gap without touching either number above. That is
  # `v3-two-fronts-differ-in-CAPABILITY` exactly, and it is why the entry says an output
  # differential cannot see capability by construction.
  #
  # So the guard is a CEILING, and it is expected to be LOWERED over time rather than raised: as
  # v3's own front catches up, files move from one-sided into agreement and this falls. Raising it
  # is a deliberate act that says the gap widened and someone decided that was acceptable.
  CONECEIL="${SSC3_FRONT_CORPUS_ONE_SIDED_CEILING:-85}"
  if [ "$ccap" -gt "$CONECEIL" ]; then
    echo "  FAIL corpus ONE-SIDED files rose to $ccap (v3 $conly_v3, uniml $conly_uniml), above the ceiling $CONECEIL"
    echo "       one front accepts these and the other refuses them; that gap is not visible in"
    echo "       the agree/differ numbers above, which is the whole reason this ceiling exists."
    fail=1
  fi
fi

echo
echo "── self-test: the comparator must SEE a one-token difference ─────────────"
probe="$(ls v3/tests/front/*.ssc | head -1)"
"$SSC3" ast "$probe" v3 > "$work/a" 2>/dev/null
sed 's/(int /(intX /' "$work/a" > "$work/b"
if diff -q "$work/a" "$work/b" >/dev/null; then
  # No integer literal in the probe: mutate something every Ast has instead.
  sed 's/(program/(programX/' "$work/a" > "$work/b"
fi
if diff -q "$work/a" "$work/b" >/dev/null; then
  echo "  FAIL the mutation changed nothing — the self-test proves nothing"
  fail=1
elif diff "$work/a" "$work/b" >/dev/null 2>&1; then
  echo "  FAIL the comparator reported no difference on a mutated file"
  fail=1
else
  echo "  ok   a one-token mutation is reported as a difference"
fi

echo
if [ "$ran" -eq 0 ]; then echo "== v3 SSC3-11 gate: NO CASES RAN =="; exit 2; fi
if [ "$nfronts" -lt 2 ]; then
  echo "  note ONE front is runnable, so nothing was COMPARED. This gate proves the printer is"
  echo "       total over $ran fixture(s) and that the comparator can see a difference — not that"
  echo "       two fronts agree. It starts comparing with no edit when Front.available grows."
fi
if [ "$nfronts" -ge 2 ]; then
  echo
  echo "  fronts AGREE on $agree of $ran fixture(s); $disagree still differ or are refused (floor $FLOOR)"
  echo "  $unimlonly fixture(s) are declared uniml-only — v3's own front does not have the construct"
  # RAISED TO 2 on 2026-08-09 for `annotation-own-line`. Each rise needs a reason and a way back,
  # or the ceiling becomes a formality: v3's own LEXER refuses `@` outright, and the skip belongs in
  # `Parser.scala`, which another claim holds. `Lexer.scala` is mine and tokenising `@` alone
  # changes nothing observable, so the pair has to land together.
  UOCEIL="${SSC3_FRONT_UNIML_ONLY_CEILING:-2}"
  if [ "$unimlonly" -gt "$UOCEIL" ]; then
    echo "  FAIL uniml-only fixtures rose to $unimlonly, above the ceiling $UOCEIL — the two fronts"
    echo "       are drifting apart, which is the opposite of what this gate is for"
    fail=1
  fi
  if [ "$agree" -lt "$FLOOR" ]; then
    echo "  FAIL agreement REGRESSED below the floor — raise it only after a measurement, never before"
    fail=1
  fi
  # A CEILING TOO, for the same reason the corpus half has one. Adding a fixture raises the
  # denominator, so a floor alone stays satisfied while the new fixture disagrees — measured the
  # hour after the corpus half was fixed: `unicode-capitalisation` landed differing, agreement was
  # 48 of 49, the floor was 48, and the gate said GREEN. Twice in two days is the shape, not luck.
  FIXCEIL="${SSC3_FRONT_FIXTURE_DIFF_CEILING:-0}"
  if [ "$disagree" -gt "$FIXCEIL" ]; then
    echo "  FAIL $disagree fixture(s) differ or are refused, above the ceiling $FIXCEIL"
    fail=1
  fi
fi
[ "$fail" = 0 ] && echo "== v3 SSC3-11 gate: GREEN ($ran fixture(s), $nfronts front(s), agree $agree) ==" \
                || echo "== v3 SSC3-11 gate: RED =="
[ "$fail" = 0 ]
