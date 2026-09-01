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
# The name column is 34 wide and a 34+-char name GLUES to its duration (no separator) in every
# log written before the smoke-ci printer fix — so the name is non-greedy, the separator optional,
# and the duration anchored to the line end, which parses both the historic glued form and the
# fixed one.
CHECK = re.compile(r'\b(?:ok|FAIL)\s+([a-z0-9][a-z0-9-]+?)\s*([0-9]+\.[0-9])s\s*$', re.M)
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

# ── merge.py — carry forward rows CI structurally cannot measure ─────────────────────────────────
#
# A separate file rather than inline, so `--self-test` exercises THE SAME code the real path runs.
# Inline, the self-test would have to restate the logic, and a restated check passes against its own
# restatement rather than against the tool.
#
#   merge.py <fresh.tsv> <old.tsv> <suite.ssc> <merged.tsv>   → writes merged, prints carried names
cat > "$work/merge.py" <<'MERGER'
import re, sys

fresh_path, old_path, suite_path, merged_path = sys.argv[1:5]

fresh_lines = open(fresh_path).read().splitlines()
fresh_names = {l.split("\t")[0] for l in fresh_lines if l and not l.startswith("#")}

# Every name the suite still registers. `Check("<module>", "<name>", …)` — the name is the SECOND
# string. Matching the first would collect MODULE names and carry rows for things that are not
# checks at all.
registered = set(re.findall(r'Check\(\s*"[^"]*"\s*,\s*"([^"]+)"', open(suite_path).read()))

# THE RULE IS "STILL REGISTERED", NOT "WAS OPTIONAL". Keying on `optional` would carry a row for a
# check somebody deleted, and stale rows are the other way this table rots. A check absent from the
# harvest AND absent from the suite is correctly dropped.
carried = []
for line in open(old_path).read().splitlines():
    if not line or line.startswith("#"):
        continue
    cols = line.split("\t")
    if cols[0] in fresh_names or cols[0] not in registered:
        continue
    carried.append(cols[:3] + ["carried"])

out = list(fresh_lines)
if carried:
    # `# sum-seconds` must keep describing THE TABLE. tests/e2e/smoke-guard-headroom.sh:144 states
    # the invariant in its own comment — "the column sums to the header's sum-seconds" — and it
    # reads the tenths column into its cost model. Appending rows without re-summing would leave a
    # header that understates the file by exactly the carried rows, which are among the most
    # expensive checks in the suite, so the understatement would be large and silent: measured
    # 2026-08-31, the nine carried rows are 580.4 s against a fresh total of 1111.5 s.
    #
    # The header is already approximate by ~0.4 s and was BEFORE this change (1425.5 header against
    # a 1425.9 column, measured on the pre-change table): parse.py sums float medians and then
    # rounds each row separately, so 118 roundings accumulate. Adding a sum of ALREADY-ROUNDED
    # tenths introduces no further error, so that drift stays exactly what it was. Said here
    # because the next person to compare the two numbers will otherwise blame the carry-forward.
    added = sum(int(c[1]) for c in carried) / 10.0
    for i, l in enumerate(out):
        if l.startswith("# sum-seconds\t"):
            out[i] = "# sum-seconds\t%.1f" % (float(l.split("\t")[1]) + added)
            break
    header_end = max(i for i, l in enumerate(out) if l.startswith("#"))
    out = out[: header_end + 1] + [
        "# carried-forward\t%d\tregistered in scripts/smoke-ci.ssc but absent from these runs" % len(carried),
        "# (optional checks are not run by smoke.yml, so CI cannot measure them; their numbers are",
        "#  the previous table's and may be stale — refresh via a CI run with SSC_SMOKE_OPTIONAL=1)",
    ] + out[header_end + 1 :] + ["\t".join(c) for c in carried]

open(merged_path, "w").write("\n".join(out) + "\n")
for c in carried:
    print(c[0])
MERGER

if [[ $SELFTEST -eq 1 ]]; then
  # Asserts the PARSING, on a fixture spelling the two shapes this tool is wrong about if it guesses:
  # a CLAMPED probe, and a run whose budget was PINNED. A self-test that re-ran only the happy path
  # would pass on both bugs.
  cat > "$work/good.txt" <<'FIX'
smoke-ci — 3 checks across 2 declared modules, budget 719s, host probe 141ms (100 process spawns; budget derived from it)
  ok   alpha                              10.0s
  ok   beta                                0.0s
  ok   gamma                              90.0s
  ok   claim-activity-overrides-heartbeat0.0s
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
  # A 34+-character name fills the printer's column and the duration GLUES to it (no separator) in
  # every log written before the smoke-ci printer fix — the two checks whose names overflow had NO
  # baseline row while every shorter sibling was measured. The parser must split the glued form.
  grep -qE "^claim-activity-overrides-heartbeat	0	" <<<"$out" || die "self-test: a glued
name+duration line (34+-char name, no separator) was not parsed — the overflow shape stayed
unbaselined once already:
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
  # ── the merge, in BOTH directions ──────────────────────────────────────────────────────────────
  #
  # The one-directional assertion is "a missing row is carried", and it would be satisfied by a
  # merge that carries EVERYTHING — including rows for checks that were deleted, which is the other
  # way this table rots. So the deletion case is asserted too, and it is the load-bearing half:
  # only a merge that consults the suite can tell `kept` from `deleted`, since both are absent from
  # the fresh harvest in exactly the same way.
  printf '# sum-seconds\t10.0\n# check\ttenths\tshare-bp\nalpha\t100\t5000\n' > "$work/m-fresh.tsv"
  printf '# check\ttenths\tshare-bp\nalpha\t111\t4000\nkept\t980\t3000\ndeleted\t500\t3000\n' > "$work/m-old.tsv"
  cat > "$work/m-suite.ssc" <<'SUITE'
  Check("mod", "alpha", "x.sh", List(), 1000),
  Check("mod", "kept", "y.sh", List(), 1000, optional = true),
SUITE
  mout="$(python3 "$work/merge.py" "$work/m-fresh.tsv" "$work/m-old.tsv" "$work/m-suite.ssc" "$work/m-merged.tsv")" \
    || die "self-test: merge.py exited non-zero"

  grep -q '^kept$' <<<"$mout" \
    || die "self-test: 'kept' is registered in the suite and absent from the fresh harvest, so it
must be CARRIED. Dropping it is the defect: smoke.yml never sets SSC_SMOKE_OPTIONAL=1, so every
optional check is missing from every harvested log, and a wholesale replace deleted nine such rows.
Got: $mout"

  grep -q '^deleted$' <<<"$mout" \
    && die "self-test: 'deleted' is NOT registered in the suite, so it must be DROPPED, not carried.
A merge that carries everything absent would keep rows for checks nobody runs any more — stale rows
are the other way this table rots. Got: $mout"

  grep -qE '^alpha	100	5000$' "$work/m-merged.tsv" \
    || die "self-test: a FRESH row must win over the old one — the point of harvesting is the new
measurement. alpha should be 100/5000 from the fresh table, not 111/4000 from the old.
Got: $(grep '^alpha' "$work/m-merged.tsv")"

  grep -qE '^kept	980	3000	carried$' "$work/m-merged.tsv" \
    || die "self-test: a carried row must keep its previous numbers and be MARKED 'carried', so a
human can see which numbers were measured and which were inherited.
Got: $(grep '^kept' "$work/m-merged.tsv")"

  # The reader takes `row.length >= 3` and indexes 0..2 (scripts/smoke-ci.ssc:1128), so the fourth
  # column must not disturb it. Assert the shape the reader actually requires.
  awk -F'\t' '!/^#/ && NF < 3 { exit 1 }' "$work/m-merged.tsv" \
    || die "self-test: the merged table has a row with fewer than 3 columns; smoke-ci.ssc would
silently DROP it (it filters row.length >= 3), which is the same silent loss this fix is about."

  # `# sum-seconds` must describe THE TABLE, not just the freshly-measured part of it.
  # tests/e2e/smoke-guard-headroom.sh:144 states this invariant in its own comment and reads the
  # tenths column into its cost model, so a header that omits the carried rows understates the file
  # by exactly the most expensive checks in the suite.
  python3 - "$work/m-merged.tsv" <<'PY' || die "self-test: '# sum-seconds' does not equal the sum of the rows.
Appending carried rows without re-summing leaves a header that silently understates the table."
import sys
rows, head = 0.0, None
for l in open(sys.argv[1]).read().splitlines():
    if l.startswith("# sum-seconds\t"): head = float(l.split("\t")[1])
    elif l and not l.startswith("#"):   rows += int(l.split("\t")[1]) / 10.0
sys.exit(0 if head is not None and abs(head - rows) < 0.05 else 1)
PY

  echo "smoke-baseline-harvest --self-test: OK  (fit ${FIT_A} + ${FIT_B}/1000 x probe, clamp ${CLAMP_LO}-${CLAMP_HI}, reference ${REF_PROBE}ms; merge carries registered, drops deleted, sum re-totalled)"
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

# ── CARRY FORWARD WHAT CI STRUCTURALLY CANNOT MEASURE ────────────────────────────────────────────
#
# This wrote `mv out.tsv $OUT` — a wholesale replacement — and that DELETED rows rather than
# refreshing them. `smoke.yml` runs the suite without `SSC_SMOKE_OPTIONAL=1`, so every check marked
# `optional = true` is absent from every harvested log, not because it got cheap but because it was
# never run. Replacing the table therefore drops exactly those rows.
#
# Measured 2026-08-31 on a real dry-run: 14 rows gained and NINE lost — `f-at-bind-pattern`,
# `f-bodyless-object`, `f-cons-nil-tail`, `f-cons2-no-arm`, `f-effects`, `f-foldable-grade`,
# `f-front-exit-reason`, `f-gap-tail`, `f-handler-param`, all `optional = true`. Two are among the
# most expensive checks in the suite (f-at-bind-pattern 98.0 s, f-bodyless-object 81.0 s, per
# smoke-ci.ssc:384). A check with no baseline row is CHARGED its measured time and cannot fail the
# budget, so the loss is silent in both directions: nothing goes red, and the budget headroom the
# table exists to compute becomes partly fictional.
#
# THE RULE IS "STILL REGISTERED", NOT "WAS OPTIONAL". Keying on `optional` would carry a row for a
# check somebody deleted, and stale rows are the other way this table rots. Registration in
# `scripts/smoke-ci.ssc` is the property that says the row still describes something that exists;
# a check absent from the harvest AND absent from the suite is correctly dropped.
#
# Carried rows keep their previous numbers and gain a fourth column, `carried`. The reader takes
# `row.length >= 3` and indexes 0..2 (smoke-ci.ssc:1128), so a fourth column is inert there while
# being visible to a human deciding whether a number has gone stale.
CARRIED_NOTE=""
if [[ -r "$OUT" ]]; then
  carried_names="$(python3 "$work/merge.py" "$work/out.tsv" "$OUT" \
                     "$REPO_ROOT/scripts/smoke-ci.ssc" "$work/merged.tsv")" \
    || die "the merge failed — refusing to write a table that would DROP rows silently"
  mv "$work/merged.tsv" "$work/out.tsv"
  if [[ -n "$carried_names" ]]; then
    CARRIED_NOTE="$(wc -l <<<"$carried_names" | tr -d ' ')"
    sed 's/^/  carried /' <<<"$carried_names" >&2
  fi
fi

if [[ $DRY -eq 1 ]]; then
  cat "$work/out.tsv"
else
  mv "$work/out.tsv" "$OUT"
  echo "wrote $OUT${CARRIED_NOTE:+  (${CARRIED_NOTE} row(s) carried forward)}"
fi
