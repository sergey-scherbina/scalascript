#!/usr/bin/env bash
#
# The two fronts must ACCEPT AND REFUSE THE SAME PROGRAMS.
#
# WHY THIS IS NOT front-diff.sh. That gate compares the two fronts' AST OUTPUT, and `Front.scala`
# explains why: they agree on every fixture, so no fixture's output can distinguish them. Sound, and
# it leaves a hole exactly one shape wide — **a program one front REFUSES and the other RUNS produces
# no output to compare**. Capability divergence is invisible to an output differential by
# construction, and this gate is the axis that differential cannot see.
#
# HOW IT WENT UNNOTICED FOR A DAY. `Front.default` picks UniML whenever it is registered, which
# depends on the WORKING TREE — UniML needs `uniml-classpath.sh`, so a worktree without it runs v3's
# own front while the shared checkout runs UniML. Algebraic effects were built and verified in a
# worktree, where they work; on the shared checkout the same file reports
# `` `effect` is outside SSC3 core Tier 0 ``, UniML's refusal. Both fronts covered 30 of 36 corpus
# files, so the COUNT matched and the SETS did not. A number that is true of both and describes
# neither is worse than a red gate. (BUGS.md v3-two-fronts-differ-in-CAPABILITY.)
#
# WHAT IT CHECKS: `ssc3 ast <file> <front>` for every corpus file on both fronts, comparing only
# whether each ACCEPTED. `ast` is the right instrument because it stops at the front — a difference
# it reports cannot be blamed on the lowering or the executor.
#
# Usage: v3/front-capability-gate.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
SSC3="v3/ssc3"

# KNOWN divergences, declared so this gate is green today and FAILS THE DAY ONE CLOSES. A declared
# gap that quietly becomes a closed one is a permanent exemption for a fixed bug, which this
# repository has shipped before and written down.
# `effect-oneshot` was here until 2026-08-08 and came out the same day the projection landed —
# which is the gate doing its job: it went red saying "no longer diverges; drop it from KNOWN_v3 in
# this commit", and this is that commit.
declare -a KNOWN_V3_ONLY=()                     # v3 accepts, uniml refuses
# `absval` was here for one commit. The projection accepted a trait's non-`def` member and dropped it
# SILENTLY; it now refuses, which is not a new decision — `20-core-language.md` and UniFront's own
# `AbstractVal` case already said v3's traits carry methods, not abstract state. This gate went red
# the moment that landed, which is the behaviour a declaration list is for.
declare -a KNOWN_UNIML_ONLY=(type-lambda-native)        # uniml accepts, v3 refuses

available="$($SSC3 front 2>/dev/null | sed -n 's/^available: //p')"
case "$available" in
  *uniml*)
    ;;
  *)
    # NOT a silent pass. One front cannot be compared with itself, and a gate that says nothing when
    # it cannot run is the failure mode this whole entry is about.
    echo "front-capability-gate: CANNOT RUN — only these fronts are registered: ${available:-none}"
    echo "  UniML needs its classpath; run v3/uniml-classpath.sh in this tree."
    if [ "${CI:-}" = "true" ]; then exit 1; fi
    exit 0
    ;;
esac

accepts() { # $1 front, $2 file  -> 0 if the front accepted
  timeout 180 $SSC3 ast "$2" "$1" >/dev/null 2>&1
}

fails=0
v3_only=""
uniml_only=""
checked=0

# TWO SOURCES, and the second is the one that found anything. The corpus is 36 real programs and
# exercises the constructs those programs happen to use; a front gap in something none of them
# writes is invisible to it. `v3/tests/front-capability/` is one small file per construct the
# PROJECTION explicitly refuses (`UniFront.scala`'s `no(...)` list) — the fronts' own statement of
# what they do not do, turned into something that can disagree.
#
# Measured when the probes were added: 14 constructs, 13 agree, and the one that did not —
# an abstract `val` in a trait — is invisible to the corpus entirely.
for f in bench/corpus/*.ssc v3/tests/front-capability/*.ssc; do
  n="$(basename "$f" .ssc)"
  checked=$((checked + 1))
  a3=1; au=1
  accepts v3 "$f" && a3=0
  accepts uniml "$f" && au=0
  if [ $a3 -eq 0 ] && [ $au -ne 0 ]; then v3_only="$v3_only $n"; fi
  if [ $au -eq 0 ] && [ $a3 -ne 0 ]; then uniml_only="$uniml_only $n"; fi
done

# `declared X actual` both ways: a divergence that appears is a regression, and a declared one that
# disappears means the list is stale and must shrink in the same commit that closed it.
check_set() { # $1 label, $2 declared (space list), $3 actual (space list)
  local label="$1" declared="$2" actual="$3" x
  for x in $actual; do
    case " $declared " in
      *" $x "*) printf '  KNOWN  %-24s accepted only by %s (declared)\n' "$x" "$label" ;;
      *) printf '  FAIL   %-24s NEW divergence — accepted only by %s\n' "$x" "$label"; fails=$((fails + 1)) ;;
    esac
  done
  for x in $declared; do
    case " $actual " in
      *" $x "*) ;;
      *) printf '  FAIL   %-24s no longer diverges; drop it from KNOWN_%s in this commit\n' "$x" "$label"
         fails=$((fails + 1)) ;;
    esac
  done
}

echo "── front capability: $checked programs (corpus + probes), both fronts ──────"
check_set "v3"    "${KNOWN_V3_ONLY[*]}"    "$v3_only"
check_set "uniml" "${KNOWN_UNIML_ONLY[*]}" "$uniml_only"

if [ $fails -ne 0 ]; then
  echo "front-capability-gate: FAIL ($fails)" >&2
  exit 1
fi
echo "front-capability-gate: OK (the two fronts differ on exactly the declared rows)"
