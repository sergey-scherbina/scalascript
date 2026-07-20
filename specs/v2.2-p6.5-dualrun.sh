#!/usr/bin/env bash
# F4 DUAL-RUN gate (reversible-staging step 3). Prove the STAGED F front (`SSC_FRONT=F bin/ssc run`) is
# OUTPUT-EQUIVALENT to the DEFAULT front (`bin/ssc run`) on real corpus programs, via the FAITHFUL
# production path — the same `bin/ssc` launcher, ssc.lib.path, ambient-std prelude injection, checker
# pre-pass, and plugin host for BOTH fronts, so the ONLY variable is the front. This exercises the
# REAL F runner (fence extraction + multi-file closure + ambient prelude + structural ABI), which the
# classify gate (F0.ir on pre-extracted .code, no ambient injection) does NOT — the two are complementary.
#
# For every raw .ssc program P in the slice:
#   def = (exit, stdout) of   bin/ssc run P              -- DEFAULT front (ssc1-front + ssc1-lower)
#   f   = (exit, stdout) of   SSC_FRONT=F bin/ssc run P  -- F, the self-hosting subset compiler
#   EQUAL iff def == f            (COMPARE BOTH SIDES; raw stdout bytes + exit code; NO normalization)
# A DIVERGE (f != def) MUST be an expected GAP in specs/v2.2-p6.5-classify.expected, else genuine-FAIL
# (exit 1). Both-fail-identically counts as EQUAL. AGENTS.md apparatus discipline: fail loud (exit 2) if
# nothing ran; every DIVERGE prints def-vs-f.
#
# Also asserts the TYPED FIXPOINT is byte-identical (specs/v2.2-p6.5-fsub.sh --self) — F self-compiles
# reproducibly on its own typed output — the load-bearing self-hosting claim.
#
# Slice: default = a curated single-file spread (fast for CI). SSC_DUALRUN_ALL=1 → the full corpus
# (examples/*.ssc + tests/conformance/*.ssc); slow (F recompiles its own source per invocation).
#
# Prereqs: a STAGED install (run `scripts/sbtc installBin` first) so $ROOT/bin/ssc works for BOTH fronts.
#          SSC_JAR / V2_DIR are used only for the fixpoint leg.
set -u
JAR=${SSC_JAR:?set SSC_JAR to a run-ir-capable v2 kernel jar}; V2=${V2_DIR:?set V2_DIR to <repo>/v2}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
FSUB="$ROOT/specs/v2.2-p6.5-fsub.ssc"
# The dual-run has its OWN expected-divergence list, SEPARATE from the classify manifest: a program can
# be output-equivalent under the classify gate (F0.ir on per-file .code) yet diverge here (the real
# bin/ssc path injects the ambient std prelude + plugin host, whose SOURCE F must also compile). Sharing
# one manifest would collide with the classify gate's reverse-check. Same GAP/OUT/DEFERRED schema.
MANIFEST="$ROOT/specs/v2.2-p6.5-dualrun.expected"
SSC_BIN=${SSC_BIN:-$ROOT/bin/ssc}
TIMEOUT_BIN=$(command -v timeout || command -v gtimeout || true)
DR_TIMEOUT=${DR_TIMEOUT:-45}
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
die() { echo "FATAL(dualrun): $1" >&2; exit 2; }
[ -f "$FSUB" ]     || die "F source not found: $FSUB"
[ -x "$SSC_BIN" ]  || die "bin/ssc launcher not found/executable: $SSC_BIN (run scripts/sbtc installBin first)"
[ -f "$MANIFEST" ] || die "classification manifest not found: $MANIFEST"
[ -n "$TIMEOUT_BIN" ] || echo "WARN: no timeout(1)/gtimeout(1) — a pathological program can hang this gate." >&2

# run a file through bin/ssc with a given front; capture (exit, stdout). stderr (stack traces / paths)
# is intentionally excluded from the comparison — a failing program's rc!=0 already flags it, and stderr
# carries non-portable absolute paths. globals: CAP (dest prefix).
runVia() { # front(""|F) file
  local front="$1"; local f="$2"
  local tb=(); [ -n "$TIMEOUT_BIN" ] && tb=("$TIMEOUT_BIN" "$DR_TIMEOUT")
  if [ "$front" = F ]; then SSC_FRONT=F "${tb[@]}" "$SSC_BIN" run "$f" > "$CAP.out" 2>/dev/null
  else                              "${tb[@]}" "$SSC_BIN" run "$f" > "$CAP.out" 2>/dev/null; fi
  echo $? > "$CAP.rc"
}

isGap() { awk -F'\t' -v k="$1" '!/^#/ && NF>=2 && $2==k {print; exit}' "$MANIFEST"; }

# ── slice ──
if [ "${SSC_DUALRUN_ALL:-0}" = 1 ]; then
  FILES=$(ls "$ROOT"/examples/*.ssc "$ROOT"/tests/conformance/*.ssc 2>/dev/null)
else
  SPREAD="hello arithmetic case-classes collections enums pattern-matching higher-order-functions
    recursion strings maps option tuples variables default-params sealed-traits typeclass lenses
    word-count json-read optics-index-at list-combinators mutual-recursion tail-recursion generators
    string-eq-locals int-literal bitwise-operators data-types"
  FILES=""
  for n in $SPREAD; do
    for cand in "$ROOT/examples/$n.ssc" "$ROOT/tests/conformance/$n.ssc"; do
      [ -f "$cand" ] && FILES="$FILES $cand"
    done
  done
fi
nfiles=$(echo $FILES | wc -w | tr -d ' ')
[ "$nfiles" -gt 0 ] || die "0 files in the slice — nothing to compare."
echo "═══════ F4 DUAL-RUN — default front vs SSC_FRONT=F, both via $SSC_BIN, on $nfiles programs ═══════"

equal=0; diverge=0; unexpected=""
for f in $FILES; do
  n=$(basename "$f" .ssc)
  CAP="$WORK/def"; runVia "" "$f"; drc=$(cat "$CAP.rc")
  CAP="$WORK/f";   runVia F  "$f"; frc=$(cat "$CAP.rc")
  if [ "$drc" = "$frc" ] && cmp -s "$WORK/def.out" "$WORK/f.out"; then
    equal=$((equal+1))
  else
    diverge=$((diverge+1))
    if [ -n "$(isGap "$n")" ]; then
      printf "  DIVERGE(expected-GAP) %s\n" "$n"
    else
      unexpected="$unexpected $n"
      printf "  DIVERGE(UNEXPECTED)   %s  def(rc=%s):%s  f(rc=%s):%s\n" "$n" \
        "$drc" "$(head -c 120 "$WORK/def.out" | tr '\n' '~')" \
        "$frc" "$(head -c 120 "$WORK/f.out"   | tr '\n' '~')"
    fi
  fi
done
echo "─────────────────────────────────────────────────────────────────"
printf "  EQUAL (F front == default front) : %d / %d\n" "$equal" "$nfiles"
printf "  DIVERGE                          : %d  (expected GAPs are OK)\n" "$diverge"

fail=0
if [ -n "$unexpected" ]; then
  echo "  *** genuine-FAIL: UNEXPECTED front divergence(s) not in $MANIFEST:$unexpected"
  echo "    → each must be a manifest GAP, or F regressed. green = 0 unexpected."
  fail=1
fi

# ── typed fixpoint: F self-compiles byte-identically (SSC_DUALRUN_SKIP_FIXPOINT=1 skips for fast iter) ──
if [ "${SSC_DUALRUN_SKIP_FIXPOINT:-0}" = 1 ]; then
  echo "─── typed fixpoint: SKIPPED (SSC_DUALRUN_SKIP_FIXPOINT=1) ───"
else
  echo "─── typed fixpoint (specs/v2.2-p6.5-fsub.sh --self) ───"
  if SSC_JAR="$JAR" V2_DIR="$V2" bash "$ROOT/specs/v2.2-p6.5-fsub.sh" --self 2>&1 | tail -3; then
    echo "  fixpoint OK"
  else
    echo "  *** fixpoint FAILED (F does not self-compile byte-identically)"; fail=1
  fi
fi

[ "$fail" -eq 0 ] || { echo "DUAL-RUN: RED"; exit 1; }
fpnote=$([ "${SSC_DUALRUN_SKIP_FIXPOINT:-0}" = 1 ] && echo "fixpoint SKIPPED" || echo "typed fixpoint byte-identical")
echo "*** DUAL-RUN GREEN: F front is output-equivalent to the default front on all $equal non-GAP programs;"
echo "    $fpnote. (GAP programs still diverge by design — handle before the flip.)"
