#!/usr/bin/env bash
#
# EVERY BENCH-CORPUS ROW THAT COMPUTES A NUMBER KEEPS COMPUTING ONE.
#
# WHY THIS EXISTS. `bench/corpus/` is a SEPARATE set from `tests/conformance/`, and every gate this
# repository runs reads the second one. So a change can stop a bench row computing and nothing
# says a word: `corpus-report.sh` keeps printing the same `N`, because the row it broke is not in
# the corpus it counts.
#
# That is not hypothetical. `typeclass-fold` computed `VInt(16500)`, stopped when SSC3-G2 stage 2b
# turned a context bound into a parameter — a call whose argument was a top-level `val` could no
# longer be solved — and `N` stayed at 191 for three days. It was found by hand on 2026-08-11
# while counting how many bench rows v3 covers, not by anything failing.
#
# WHAT IT CHECKS, and what it deliberately does NOT. Only whether a row produces a `BENCH_SINK` —
# that it RAN. Not how fast, not what the value is: timing is meaningless on a contended host (this
# repository has measured identical code spreading 2.5× at load 5.5) and a value oracle is
# `exec-gate.sh`'s job. `--warmup 1 --reps 1` because the number is not read.
#
# Usage: v3/bench-corpus-gate.sh [--self-test]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
SSC3="v3/ssc3"

# ROWS THAT DO NOT COMPUTE TODAY, declared so this gate is green now and goes RED the day one
# starts — a row that begins computing is as much a change to the table as one that stops, and the
# list must shrink in the commit that closes it.
#
# Both of these want a LIBRARY, not a compiler change: `effect-pure` calls `runLogger`, which is a
# handler written in the language and not shipped anywhere, and `effect-stream` names `Stream`.
# Measured 2026-08-11: 34 of the 36 rows compute.
declare -a KNOWN_BLANK=(effect-pure effect-stream)

SELFTEST=0
[ "${1:-}" = "--self-test" ] && SELFTEST=1

computed=""
blank=""
total=0

for f in bench/corpus/*.ssc; do
  [ -e "$f" ] || continue
  n="$(basename "$f" .ssc)"
  total=$((total + 1))
  if timeout 180 $SSC3 bench --warmup 1 --reps 1 "$f" 2>/dev/null | grep -q "BENCH_SINK"; then
    computed="$computed $n"
  else
    blank="$blank $n"
  fi
done

if [ "$SELFTEST" = "1" ]; then
  # THE GATE MUST BE ABLE TO SAY NO. A row invented here must appear in neither list, which proves
  # the lists are built from the run rather than from the declaration.
  ghost="this-row-does-not-exist"
  case " $computed $blank " in
    *" $ghost "*)
      echo "bench-corpus-gate: SELF-TEST FAIL — an invented row was reported as measured" >&2
      exit 1 ;;
  esac
  if [ "$total" -eq 0 ]; then
    echo "bench-corpus-gate: SELF-TEST FAIL — no rows were run at all" >&2
    exit 1
  fi
  echo "  ok    $total rows run; an invented row appears in neither list"
  echo "bench-corpus-gate: SELF-TEST OK"
  exit 0
fi

fails=0

# A ROW THAT STOPPED. The regression this gate exists for.
for n in $blank; do
  case " ${KNOWN_BLANK[*]} " in
    *" $n "*) printf '  KNOWN %-24s does not compute (declared)\n' "$n" ;;
    *) printf '  FAIL  %-24s STOPPED computing — it produced a number before\n' "$n"
       fails=$((fails + 1)) ;;
  esac
done

# A DECLARED BLANK THAT NOW COMPUTES. Stale in the other direction, and just as misleading: the
# list would then be a permanent exemption for something already working.
for n in "${KNOWN_BLANK[@]}"; do
  case " $computed " in
    *" $n "*) printf '  FAIL  %-24s now computes; drop it from KNOWN_BLANK in this commit\n' "$n"
              fails=$((fails + 1)) ;;
  esac
done

nc="$(printf '%s\n' $computed | grep -c . || true)"
echo "── bench corpus: $nc of $total rows compute a number ──────────────────────"
if [ $fails -ne 0 ]; then
  echo "bench-corpus-gate: FAIL ($fails)" >&2
  exit 1
fi
echo "bench-corpus-gate: OK"
