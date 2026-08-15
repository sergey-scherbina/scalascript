#!/usr/bin/env bash
#
# smoke-baseline-harvest — build `tests/smoke-baseline.tsv` from real CI smoke runs.
#
# WHAT THIS IS FOR. `scripts/smoke-ci.ssc` carries a hand-fitted budget, `436 + 1.263 x probe`, and
# that 436 is not a constant: it is "the cost of the 78 checks the suite had on 2026-08-10", written
# as a number. Every check added since is a silent bite out of the margin, and the answer so far has
# been to raise the cap -- four times, 420 -> 500 -> 600 -> 750. This tool exists so the next answer
# is a MEASUREMENT of what the suite now contains.
#
# WHY A TABLE OF CHECKS RATHER THAN A COUNT. Measured over nine CI runs on 2026-08-10: the 79th check
# to be registered, `launcher-set-complete`, costs 0.0 s. A rule that scaled the budget by the NUMBER
# of checks would have credited it the average, 8.5 s -- 164x its real cost. Nine of the 79 checks
# cost under 0.05 s while one costs 124.8 s. Checks are not interchangeable and a count cannot say so.
#
# WHAT THE NUMBERS MEAN. Each row is that check's cost NORMALISED to a reference host, so a row from
# a fast runner and one from a slow runner are comparable. The normalisation uses smoke-ci's own
# host-speed fit, which is why this script READS those constants out of `scripts/smoke-ci.ssc`
# instead of copying them: the day someone refits the line, a duplicated copy here would keep
# normalising by the old one and every baseline would drift with nothing to show for it.
#
# SUCCESS-ONLY WAS A SELECTION BIAS, AND A SELF-REINFORCING ONE. Harvesting only `success` runs
# looks obviously right and is not: the thing that most often makes a smoke run UNSUCCESSFUL is
# exceeding the budget derived from this very table. So the slow runs — exactly the ones the budget
# must cover — were filtered out, the baselines stayed low, the budget stayed low, and more runs
# failed. Measured 2026-08-12: the table refreshed under the old filter summed to 636.7 s while CI
# was taking 916.6 s, and `main` went red on 72 % of pushes.
#
# `failure` runs are included now. Their per-check timings are real measurements — a run that failed
# because one check broke, or because the total exceeded a cap, still timed every check it ran.
# `cancelled` and `timed_out` stay excluded: those reached no verdict and may be truncated.
#
# READ THE PRINTED PROBE, NEVER INVERT THE BUDGET. The first version recovered the probe
# arithmetically from the budget the suite prints, since `budget = 436 + 1.263 x probe + 80` is
# invertible. It is not invertible where it is CLAMPED, and one run in nine was: probe 141 ms, and
# the inversion confidently answered 160.7. The suite prints `host probe <n>ms` and that is the only
# honest source.
#
# A SHARE COLUMN IS CARRIED TOO because it is the more stable unit: across those nine runs the share
# was tighter than the second-count for 61 of 78 checks. Host speed inflates every check together, so
# a share cancels it while a second-count does not.
#
#   usage:  tests/smoke-baseline-harvest.sh [--runs N] [--out PATH] [--dry-run]
#           tests/smoke-baseline-harvest.sh --self-test
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNS=12
MIN_RUNS=6
SEARCH=120        # how far back to LOOK for successful runs; unrelated to how many we want.
                  # It was RUNS*3, which silently returned 3 usable runs on a day when CI was
                  # churning cancelled builds — and a median over 3 points is not a median.
OUT="$REPO_ROOT/tests/smoke-baseline.tsv"
DRY=0
SELFTEST=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --runs)      RUNS="$2"; shift 2 ;;
    --min-runs)  MIN_RUNS="$2"; shift 2 ;;
    --search)    SEARCH="$2"; shift 2 ;;
    --out)       OUT="$2"; shift 2 ;;
    --dry-run)   DRY=1; shift ;;
    --self-test) SELFTEST=1; shift ;;
    -h|--help)   sed -n '2,32p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "smoke-baseline-harvest: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

die() { echo "smoke-baseline-harvest: $*" >&2; exit 1; }

# ── the normalisation constants, read from the suite rather than copied ───────────────────────────
# `val predicted = 436 + (1263 * clamped) / 1000`, `val lo`, `val hi` in `budgetFor`.
# A miss here is FATAL on purpose. Falling back to a remembered value is how a tool keeps answering
# confidently after the thing it measures has moved, which is the failure this entry is about.
SMOKE_SRC="$REPO_ROOT/scripts/smoke-ci.ssc"
[[ -f "$SMOKE_SRC" ]] || die "cannot find $SMOKE_SRC"
FIT_LINE="$(grep -E 'val predicted = [0-9]+ \+ \([0-9]+ \* clamped\) / 1000' "$SMOKE_SRC" || true)"
[[ -n "$FIT_LINE" ]] || die "the host-speed fit in scripts/smoke-ci.ssc no longer matches the shape this
tool normalises by ('val predicted = A + (B * clamped) / 1000'). Someone refit the line; re-read
budgetFor and update the parser here, because a stale normalisation silently biases every baseline."
FIT_A="$(sed -E 's/.*val predicted = ([0-9]+) \+ .*/\1/' <<<"$FIT_LINE")"
FIT_B="$(sed -E 's/.*\(([0-9]+) \* clamped\).*/\1/' <<<"$FIT_LINE")"
CLAMP_LO="$(grep -E '^\s*val lo = [0-9]+' "$SMOKE_SRC" | head -1 | grep -oE '[0-9]+$' || true)"
CLAMP_HI="$(grep -E '^\s*val hi = [0-9]+' "$SMOKE_SRC" | head -1 | grep -oE '[0-9]+$' || true)"
[[ -n "$CLAMP_LO" && -n "$CLAMP_HI" ]] || die "could not read the clamp bounds (val lo / val hi) from budgetFor"

# The reference host every row is normalised to: the MIDPOINT of the clamp, so a baseline is never
# anchored on an extrapolated part of the line.
REF_PROBE=$(( (CLAMP_LO + CLAMP_HI) / 2 ))

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

# ── the parser, written out so the self-test and the real run share ONE implementation ────────────
cat > "$work/parse.py" <<'PARSER'
import os, re, sys, statistics as st

A   = int(os.environ["FIT_A"]);   B  = int(os.environ["FIT_B"])
LO  = int(os.environ["CLAMP_LO"]); HI = int(os.environ["CLAMP_HI"])
REF = int(os.environ["REF_PROBE"])

# `budget PINNED by SSC_SMOKE_BUDGET` runs are REJECTED: their probe was still measured, but a pinned
# budget usually means someone was working around a slow or loaded host, which is exactly the
# condition under which the fit does not describe the machine. Better to drop the sample than to
# normalise by a line that does not apply to it.
PROBE = re.compile(r'host probe (\d+)ms \(100 process spawns; budget derived from it\)')
CHECK = re.compile(r'\b(?:ok|FAIL)\s+([a-z0-9][a-z0-9-]+)\s+([0-9.]+)s')
TOTAL = re.compile(r'checks: (\d+)/\d+ green\s+([0-9.]+)s of (\d+)s')

def factor(p):                      # cost on host p, relative to the reference host
    c = max(LO, min(HI, p))
    return (A + B * c / 1000.0) / (A + B * REF / 1000.0)

runs, rejected = [], []
for path in sys.argv[1:]:
    try:    txt = open(path, errors='replace').read()
    except OSError: continue
    pr, tot = PROBE.findall(txt), TOTAL.findall(txt)
    checks  = {m.group(1): float(m.group(2)) for m in CHECK.finditer(txt)}
    if not pr or not tot or not checks:
        rejected.append((os.path.basename(path), "no probe / no total / no checks")); continue
    runs.append(dict(n=int(tot[-1][0]), total=float(tot[-1][1]), probe=int(pr[-1]), checks=checks))

if not runs:
    sys.stderr.write("no usable runs: every log lacked a derived-budget probe line\n"); sys.exit(1)

names = sorted(set().union(*[set(r["checks"]) for r in runs]))
base  = {c: st.median([r["checks"][c] / factor(r["probe"]) for r in runs if c in r["checks"]])
         for c in names}
total = sum(base.values()) or 1.0
sizes  = sorted({r["n"] for r in runs})
probes = sorted(r["probe"] for r in runs)

print(f"# smoke-baseline — per-check cost normalised to a reference host, from real CI runs.")
print(f"# Regenerate with tests/smoke-baseline-harvest.sh; do not hand-edit a row.")
print(f"# runs-used\t{len(runs)}")
print(f"# suite-sizes\t{','.join(map(str, sizes))}")
print(f"# probe-range-ms\t{probes[0]}-{probes[-1]}\treference\t{REF}")
print(f"# fit\t{A} + {B}/1000 x probe\tclamp\t{LO}-{HI}")
print(f"# sum-seconds\t{total:.1f}")
if len(sizes) > 1:
    print(f"# NOTE: the suite changed size across these runs ({sizes}); a check absent from some runs")
    print(f"# has its median taken over the runs that HAVE it, which is right, but the sum above is")
    print(f"# the cost of the union rather than of any one run.")
for name, reason in rejected:
    print(f"# rejected\t{name}\t{reason}")
# INTEGER columns on purpose. The consumer is scripts/smoke-ci.ssc, and hand-rolling a decimal
# parser in that language subset is a source of bugs with nothing to buy it -- `.split(".")` alone
# is a coin-flip on whether the separator is a regex. Tenths of a second and basis points of the
# run are exact in Int and read fine (1260 = 126.0 s, 1864 = 18.64 %).
print("# check\ttenths\tshare-bp")
for c in names:
    print(f"{c}\t{round(base[c] * 10)}\t{round(base[c] / total * 10000)}")
PARSER

if [[ $SELFTEST -eq 1 ]]; then
  # Asserts the PARSING, on a fixture spelling the two shapes this tool is wrong about if it guesses:
  # a CLAMPED probe, and a run whose budget was PINNED. A self-test that re-ran only the happy path
  # would pass on both bugs.
  cat > "$work/good.txt" <<'FIX'
smoke-ci — 3 checks across 2 declared modules, budget 719s, host probe 141ms (100 process spawns; budget derived from it)
  ok   alpha                              10.0s
  ok   beta                                0.0s
  ok   gamma                              90.0s
checks: 3/3 green    100.0s of 719s budget
FIX
  cat > "$work/pinned.txt" <<'FIX'
smoke-ci — 3 checks across 2 declared modules, budget 750s, host probe 141ms (100 process spawns; budget PINNED by SSC_SMOKE_BUDGET)
  ok   alpha                              10.0s
checks: 3/3 green    100.0s of 750s budget
FIX
  out="$(FIT_A="$FIT_A" FIT_B="$FIT_B" CLAMP_LO="$CLAMP_LO" CLAMP_HI="$CLAMP_HI" REF_PROBE="$REF_PROBE" \
         python3 "$work/parse.py" "$work/good.txt" "$work/pinned.txt" 2>&1)" \
    || die "self-test: the parser exited non-zero:
$out"
  grep -q "^# runs-used	1$" <<<"$out" || die "self-test: expected exactly ONE usable run — the pinned-budget
run must be rejected, because a pinned budget is what someone sets when the fit does not describe the
host. Got:
$out"
  grep -qE "^alpha	[0-9.]+" <<<"$out" || die "self-test: no row for a check that ran:
$out"
  grep -qE "^beta	0	" <<<"$out" || die "self-test: a 0.0s check must keep its measured 0.0 rather than
be imputed an average — that imputation is the defect this table exists to remove:
$out"
  # The clamp is the reason the probe is read rather than inverted, so assert it BITES: at probe 141,
  # below the clamp floor, a 90.0s check must normalise to more than 90.0s (the reference host is
  # slower than the clamp floor), and to exactly what the clamped factor gives.
  g="$(grep -E "^gamma	" <<<"$out" | cut -f2)"   # tenths
  python3 -c "
import sys
A,B,LO,HI,REF=$FIT_A,$FIT_B,$CLAMP_LO,$CLAMP_HI,$REF_PROBE
f=(A+B*max(LO,min(HI,141))/1000.0)/(A+B*REF/1000.0)
want=round(900.0/f); got=float('$g')
sys.exit(0 if abs(want-got)<=1 else 1)" \
    || die "self-test: a probe below the clamp floor was not normalised by the CLAMPED factor —
this is the exact case where inverting the budget gave 160.7 for a real probe of 141. got gamma=$g"
  echo "smoke-baseline-harvest --self-test: OK  (fit ${FIT_A} + ${FIT_B}/1000 x probe, clamp ${CLAMP_LO}-${CLAMP_HI}, reference ${REF_PROBE}ms)"
  exit 0
fi

command -v gh >/dev/null 2>&1 || die "gh is not installed; this reads completed CI runs"

# "success and failure", not "successful": the selection below takes both, and saying otherwise
# describes the very bias the 2026-08-12 fix removed — a reader would think it was still here, and
# an auditor might "correct" the code to match the message and put it back.
echo "harvesting up to $RUNS smoke runs (success and failure; fit ${FIT_A} + ${FIT_B}/1000 x probe, reference host ${REF_PROBE}ms)"
gh run list --workflow smoke.yml --limit "$SEARCH" --json databaseId,conclusion \
  | python3 -c "import json,sys; [print(r['databaseId']) for r in json.load(sys.stdin) if r['conclusion'] in ('success','failure')]" \
  > "$work/ids.txt" || die "could not list smoke runs"

n=0
while read -r id; do
  [[ $n -lt $RUNS ]] || break
  gh run view "$id" --log > "$work/$id.log" 2>/dev/null || continue
  n=$((n + 1))
done < "$work/ids.txt"
[[ $n -gt 0 ]] || die "no run logs could be downloaded"
echo "  downloaded $n run log(s)"
# A MEDIAN OVER THREE POINTS IS NOT A MEDIAN, and the first real use of this tool produced exactly
# that: CI was churning cancelled builds, only 3 successes were inside the search window, and a
# table was written without a word of complaint. Refuse instead — a thin table is worse than none,
# because the budget derived from it looks just as authoritative.
[[ $n -ge $MIN_RUNS ]] || die "only $n usable run(s) in the last $SEARCH; want at least $MIN_RUNS.
Widen with --search N, or lower the bar deliberately with --min-runs N."

FIT_A="$FIT_A" FIT_B="$FIT_B" CLAMP_LO="$CLAMP_LO" CLAMP_HI="$CLAMP_HI" REF_PROBE="$REF_PROBE" \
  python3 "$work/parse.py" "$work"/*.log > "$work/out.tsv" || die "the parser failed"

if [[ $DRY -eq 1 ]]; then
  cat "$work/out.tsv"
else
  mv "$work/out.tsv" "$OUT"
  echo "wrote $OUT"
fi
