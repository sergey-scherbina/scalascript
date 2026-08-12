#!/usr/bin/env bash
#
# f-output-agreement-gate — front F must not answer DIFFERENTLY from the reference front on a file
# it accepts. This is the correctness counterpart to the coverage census, and the reason it exists
# is that the coverage census cannot see the failures that matter most.
#
# WHAT IT CAUGHT WHEN IT WAS RUN BY HAND, over four days:
#   * a multi-statement match-arm body returned its FIRST statement and dropped the rest — F answered
#     2 where both the reference front and the v1 interpreter answer 3, with no diagnostic;
#   * a curried extern had its call site flattened, so `httpClient(url) { … }` ran the call and threw
#     the block away silently;
#   * an extern vararg call started building a Cons list where the oracle passes the arguments
#     individually — a divergence introduced BY A FIX, three hours old, that no test noticed.
#
# NONE of those moved the coverage number. `ssc info --front-report` answers "did F lower the file",
# and all three were lowered — to code that gave the wrong answer. A suite that only counts coverage
# is green through every one of them, which is why this is a gate and not a note.
#
# METHOD. For each corpus file: run it once with SSC_FRONT_STRICT=1 (F, refusing to fall back) and
# once with SSC_FRONT=legacy (the reference), and compare stdout+stderr.
#
#   F declines            -> NOT this gate's business (that is coverage) — counted, not judged
#   both fail identically -> environment or a shared gap, not a divergence
#   both produce the same -> agreement
#   anything else         -> DISAGREEMENT, and if the reference RAN and F did not, F is WORSE
#
# THREE NUMBERS ARE FROZEN, not one. A floor on the good number is not a guard: agreement can climb
# while divergence climbs with it, and both can look fine while the SUBJECT SET quietly shrinks to
# nothing. So the gate pins the worse-count as a ceiling, agreement as a floor, AND the size of the
# set it actually measured. Drop the corpus to one file and the third number fails.
#
# IT MUST FAIL LOUDLY IF IT MEASURES NOTHING. A gate that reports 0 == 0 as green is worse than no
# gate; `specs/…` calls this out and it has bitten this repository before.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"

# ── the frozen expectations ──────────────────────────────────────────────────────────────────────
# Measured 2026-08-12 over the 639-file subject set at -P 8:
#   declined 239 · timed out 133 · measured 267 · agree 222 · both fail identically 32 · disagree 8
#   arbitrated: F contradicted by both other lanes 0 · reference contradicted 3 · all three differ 2
#
# FWRONG_MAX IS THE NUMBER THAT MATTERS, and it is 0. It used to be called "F worse than the
# reference" and counted every divergence against F — which was wrong three times out of five,
# because the reference front is not an oracle. A divergence is now put to the v1 interpreter and
# charged to whichever front the other two contradict, so this counts files where F is contradicted
# BY BOTH OTHER LANES. Nothing else belongs in a ceiling.
#
# The ceiling is TIGHT and the two floors are slack, on purpose. Under load a subject moves into the
# TIMEOUT bucket, which can only make `agree` and `subjects` smaller and can only REMOVE rows from
# the ceiling — never add. 133 of 639 timed out on the host this was measured on, so the floors are
# set well below the measurement rather than just under it: a busy runner must not turn this red for
# being busy, and a shrinking subject count is reported by SUBJECT_MIN rather than hidden.
FWRONG_MAX=${FWRONG_MAX:-0}
AGREE_MIN=${AGREE_MIN:-150}
SUBJECT_MIN=${SUBJECT_MIN:-180}

CAP=${CAP:-45}

ssc_usable_or_skip f-output-agreement-gate "$ssc"

# THE SUBJECT SET IS A RULE, not a list. The hand-sampled 140 files the numbers were first measured
# on had no reproducible definition, and a threshold frozen against a set nobody can rebuild is not a
# gate — it is a number. `examples/*.ssc` plus one level of `examples/frontend/` is the same SHAPE
# that sample had, stated as a glob, so it rebuilds identically on any checkout and grows when the
# corpus grows. tests/conformance IS INCLUDED since 2026-08-12: excluding it was measured wrong —
# a sweep of those 398 files found FIVE divergences against ONE in the examples set.
corpus=$(cd "$ROOT" && ls examples/*.ssc examples/frontend/*/*.ssc tests/conformance/*.ssc 2>/dev/null | sort)
total=$(printf '%s\n' "$corpus" | grep -c . || true)
if [ "${total:-0}" -lt 100 ]; then
  echo "✗ f-output-agreement-gate: found only ${total:-0} corpus files — the subject list is broken,"
  echo "  and a gate that measures nothing must not report green."
  exit 1
fi
echo "── comparing F against the reference front over $total corpus files (cap ${CAP}s)"

cd "$ROOT" || exit 1
export SSC_NO_BUILD_CHECK=1

# One classification per file, computed in PARALLEL and aggregated afterwards. Sequentially this is
# 20-50 minutes; the work is entirely JVM startup, so it parallelises almost perfectly. Each worker
# prints exactly one line — `<verdict> <TAB> <file>` — and nothing else, so the aggregation cannot be
# confused by a subject that writes to stderr.
work=$(mktemp -d "${TMPDIR:-/tmp}/f-agreement.XXXXXX")
trap 'rm -rf "$work"' EXIT HUP INT TERM
export SSC_BIN="$ssc" CAP WORK="$work"
# A TIMEOUT IS NOT A VERDICT, and the first version of this worker treated it as one. Measured:
# examples/content-live-rows.ssc takes 13.3 s unloaded, so under `-P 4` it exceeded a 15 s cap, was
# killed, lost the "refusing to fall back" line that marks an F DECLINE, and was reported as
# "F is worse than the reference". Six files landed in that bucket for no reason but load, and
# examples/direct-syntax-demo.ssc did too — its two outputs are byte-identical when either side is
# allowed to finish. A gate whose verdict depends on how busy the machine is will be believed on a
# quiet host and disbelieved on a loud one, which is the worst of both.
#
# So the exit code is read, and a kill (124) puts the file in its own bucket that is never judged.
# Under contention the gate then degrades to "fewer subjects measured" — which the SUBJECT_MIN floor
# reports honestly — instead of manufacturing regressions.
cat > "$work/one.sh" <<'WORKER'
#!/usr/bin/env bash
f=$1
o="$WORK/$(echo "$f" | tr '/' '_')"
SSC_FRONT_STRICT=1 timeout "$CAP" "$SSC_BIN" run "$f" > "$o.f" 2>&1; frc=$?
[ $frc -eq 124 ] && { printf 'TIMEOUT\t%s\n' "$f"; rm -f "$o.f"; exit 0; }
fout=$(head -8 "$o.f"); rm -f "$o.f"
case "$fout" in *"refusing to fall back"*) printf 'DECLINED\t%s\n' "$f"; exit 0 ;; esac
SSC_FRONT=legacy timeout "$CAP" "$SSC_BIN" run "$f" > "$o.r" 2>&1; rrc=$?
[ $rrc -eq 124 ] && { printf 'TIMEOUT\t%s\n' "$f"; rm -f "$o.r"; exit 0; }
rout=$(head -8 "$o.r"); rm -f "$o.r"
if [ "$fout" = "$rout" ]; then
  case "$rout" in
    *"ssc: "*) printf 'BOTHFAIL\t%s\n' "$f" ;;
    *)         printf 'AGREE\t%s\n' "$f" ;;
  esac
  exit 0
fi
# Both failed, with different messages: a divergence worth seeing, but neither front is usable here.
case "$rout" in
  *"ssc: "*) printf 'DISAGREE\t%s\n' "$f"; exit 0 ;;
esac
# THE REFERENCE FRONT IS NOT AN ORACLE, and treating it as one is what this gate got wrong until
# 2026-08-12. Of the five divergences the conformance sweep found, THREE were reference-front
# defects with F in the right, and the gate counted every one of them against F. So a divergence is
# put to a THIRD lane, the v1 interpreter, and charged to whichever front the other two contradict.
# It costs one extra run per DIVERGENT row only, and divergences are the small bucket.
iout=$(timeout "$CAP" "$SSC_BIN"-tools run --v1 "$f" 2>&1 | head -8)
if [ "$fout" = "$iout" ]; then printf 'REF-WRONG\t%s\n' "$f"; exit 0; fi
if [ "$rout" = "$iout" ]; then printf 'F-WRONG\t%s\n' "$f"; exit 0; fi
printf 'UNRESOLVED\t%s\n' "$f"
WORKER
chmod +x "$work/one.sh"
printf '%s\n' "$corpus" | grep . | xargs -P "${JOBS:-8}" -I{} "$work/one.sh" {} > "$work/rows.txt" 2>/dev/null

declined=$(grep -c '^DECLINED' "$work/rows.txt" || true)
agree=$(grep -c '^AGREE' "$work/rows.txt" || true)
bothfail=$(grep -c '^BOTHFAIL' "$work/rows.txt" || true)
timedout=$(grep -c '^TIMEOUT' "$work/rows.txt" || true)
disagree=$(grep -c '^DISAGREE' "$work/rows.txt" || true)
fwrong=$(grep -c '^F-WRONG' "$work/rows.txt" || true)
refwrong=$(grep -c '^REF-WRONG' "$work/rows.txt" || true)
unresolved=$(grep -c '^UNRESOLVED' "$work/rows.txt" || true)
worse=$fwrong   # kept for the threshold block below
subjects=$((agree + bothfail + disagree + fwrong + refwrong + unresolved))

echo "    F declined (coverage, not judged here): $declined   timed out (not judged): $timedout"
echo "    measured: $subjects   agree: $agree   both fail identically: $bothfail   disagree: $disagree"
echo "    divergences arbitrated by the v1 interpreter:"
echo "      F contradicted by BOTH other lanes: $fwrong   reference contradicted: $refwrong   all three differ: $unresolved"
if [ "$disagree" -gt 0 ]; then echo "── disagreements (both fail, different message):"; awk -F'\t' '$1=="DISAGREE"{print "  " $2}' "$work/rows.txt"; fi
if [ "$fwrong" -gt 0 ]; then echo "── where F is WRONG (interpreter agrees with the reference):"; awk -F'\t' '$1=="F-WRONG"{print "  " $2}' "$work/rows.txt"; fi
if [ "$refwrong" -gt 0 ]; then echo "── where the REFERENCE is wrong (interpreter agrees with F) — not F's work:"; awk -F'\t' '$1=="REF-WRONG"{print "  " $2}' "$work/rows.txt"; fi
if [ "$unresolved" -gt 0 ]; then echo "── all three lanes differ:"; awk -F'\t' '$1=="UNRESOLVED"{print "  " $2}' "$work/rows.txt"; fi

fails=0
if [ "$subjects" -lt "$SUBJECT_MIN" ]; then
  echo "✗ measured only $subjects files, floor is $SUBJECT_MIN — the subject set shrank, so the two"
  echo "  numbers below are not comparable with the frozen ones and cannot be trusted."
  fails=$((fails + 1))
fi
if [ "$fwrong" -gt "$FWRONG_MAX" ]; then
  echo "✗ F is contradicted by BOTH other lanes on $fwrong files, ceiling is $FWRONG_MAX."
  echo "  Two independent lanes agreeing against F is a REGRESSION, not a coverage gap."
  fails=$((fails + 1))
fi
if [ "$agree" -lt "$AGREE_MIN" ]; then
  echo "✗ agreement fell to $agree, floor is $AGREE_MIN."
  fails=$((fails + 1))
fi

if [ "$fails" -eq 0 ]; then
  echo "✓ f-output-agreement-gate PASSED  (F-wrong $fwrong ≤ $FWRONG_MAX, agree $agree ≥ $AGREE_MIN, measured $subjects ≥ $SUBJECT_MIN)"
  exit 0
fi
echo "✗ f-output-agreement-gate: $fails threshold(s) breached"
exit 1
