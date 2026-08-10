#!/usr/bin/env bash
#
# smoke-budget-gate — proves the smoke budget is DERIVED from `tests/smoke-baseline.tsv` and that a
# missing or unreadable table leaves it exactly as good as the constant it replaced.
#
# WHY THIS IS NOT OPTIONAL. The budget used to be `436 + 1.263 x probe`, where 436 was the cost of
# the 78 checks the suite had on 2026-08-10 written as a number. Every check registered since ate the
# margin silently until the cap had to be raised — 420, 500, 600, 750, four times. It is now a sum
# over what the suite actually contains, which means the suite's own data file can now turn `main`
# red for everyone if the arithmetic is wrong or the fallback is missing. That is worth a gate.
#
# ASSERTS THE ARITHMETIC, not a remembered number. `scripts/smoke-ci --budget` prints the INPUTS it
# used — probe, row count, baseline seconds — so every expectation here is recomputed from those
# rather than pinned. A pinned number would go stale the first time the table is refreshed, and the
# usual repair for a stale expectation is to update it, which is how a gate stops asserting anything.
#
# THE PROBE IS MEASURED FRESH ON EVERY INVOCATION and this host is shared with other agents' builds,
# so two runs minutes apart can differ several-fold. Nothing here compares two runs' budgets
# directly; each is checked against its OWN reported probe.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

fail() { printf 'smoke-budget-gate[%s]: %s\n' "$1" "$2" >&2; exit 1; }

command -v python3 >/dev/null 2>&1 || fail bootstrap "python3 is required for the arithmetic"
[[ -x scripts/smoke-ci ]] || fail bootstrap "scripts/smoke-ci is missing or not executable"

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

# ── the fixtures, built BEFORE the single invocation that answers for all of them ─────────────────
[[ -f tests/smoke-baseline.tsv ]] || fail bootstrap "tests/smoke-baseline.tsv is missing"
MISSING="$tmp/does-not-exist.tsv"
REAL="tests/smoke-baseline.tsv"
# every row doubled: registering cost must raise the budget by that cost
awk -F'\t' 'BEGIN{OFS="\t"} /^#/{print; next} NF>=3{print $1, $2*2, $3}' "$REAL" > "$tmp/doubled.tsv"
# the most expensive row removed: rows must be matched BY NAME
grep -vE "^sbt-plugin-scripted	" "$REAL" > "$tmp/dropped.tsv"
# nothing parseable: the dangerous failure is a SILENT one that yields a tiny budget
printf 'nonsense without tabs\nanother line\n' > "$tmp/garbage.tsv"
# A TRUNCATED ROW FOR A REAL CHECK, which is the fixture the garbage one cannot replace and I only
# found by running the control: lines with no tabs match no check NAME, so dropping the column-count
# filter changed nothing and the case passed on a deliberately broken reader. A row whose name DOES
# match but whose columns are missing is the one that reaches `row(1)` on a one-element list.
{ grep -vE "^sbt-plugin-scripted	" "$REAL"; printf 'sbt-plugin-scripted\n'; } > "$tmp/truncated.tsv"

# ONE invocation for all five. Booting this toolchain costs ~10 s and the arithmetic costs
# microseconds; five invocations made this a 50 s check on the push path the suite exists to keep
# short. Output is <table>TAB<key>TAB<value>, parsed by NAME so a new field shifts nothing.
out="$tmp/budget.out"
scripts/smoke-ci --budget "$MISSING" "$REAL" "$tmp/doubled.tsv" "$tmp/dropped.tsv" "$tmp/garbage.tsv" \
  "$tmp/truncated.tsv" \
  > "$out" 2>"$tmp/err" \
  || fail invoke "scripts/smoke-ci --budget exited non-zero:
$(tail -5 "$tmp/err")"

field() { awk -F'\t' -v t="$1" -v k="$2" '$1==t && $2==k {print $3}' "$out"; }

# The formulas, in ONE place, taking the probe the run reported.
expect() { # expect <mode> <probe> <baseline-seconds>
  python3 -c "
mode, probe, base = '$1', int('$2'), int('$3')
lo, hi = 161, 288
c = max(lo, min(hi, probe))
if mode == 'fallback':
    print(436 + (1263 * c) // 1000 + 80)
else:
    print((base * (436000 + 1263 * c)) // (436000 + 1263 * 224) + 123)
"
}

probe="$(field "$REAL" probe-ms)"
[[ -n "$probe" ]] || fail invoke "no probe-ms row for $REAL — the output shape changed:
$(head -8 "$out")"

# ── 1. no table at all: the budget must be the constant this replaced ─────────────────────────────
# The fail-safe direction matters more than the feature. A lost or unreadable file must not make the
# budget TIGHTER than the suite — that is a red main for everyone caused by a missing data file.
m_rows="$(field "$MISSING" baseline-rows)"
[[ "$m_rows" == "0" ]] || fail missing-table "a nonexistent baseline path still reported $m_rows rows"
want="$(expect fallback "$probe" 0)"
got="$(field "$MISSING" budget)"
[[ "$got" == "$want" ]] || fail missing-table \
  "with no baseline the budget must fall back to the constant it replaced: got $got, want $want at probe ${probe}ms"

# ── 2. the real table: the budget must be the derived arithmetic ──────────────────────────────────
r_secs="$(field "$REAL" baseline-seconds)"
r_rows="$(field "$REAL" baseline-rows)"
r_budget="$(field "$REAL" budget)"
[[ "${r_rows:-0}" -gt 0 ]] || fail real-table "the shipped baseline parsed to 0 rows"
[[ "${r_secs:-0}" -gt 0 ]] || fail real-table "the shipped baseline summed to ${r_secs}s"
want="$(expect derived "$probe" "$r_secs")"
[[ "$r_budget" == "$want" ]] || fail real-table \
  "derived budget is not the documented arithmetic: got $r_budget, want $want (baseline ${r_secs}s, probe ${probe}ms)"

# ── 3. THE POINT: growth pays for itself ──────────────────────────────────────────────────────────
d_secs="$(field "$tmp/doubled.tsv" baseline-seconds)"
d_budget="$(field "$tmp/doubled.tsv" budget)"
want="$(expect derived "$probe" "$d_secs")"
[[ "$d_budget" == "$want" ]] || fail doubled-table \
  "doubled baseline gave $d_budget, want $want (baseline ${d_secs}s)"
[[ "${d_secs:-0}" -gt "${r_secs:-0}" ]] || fail doubled-table \
  "doubling every row did not raise the baseline sum ($d_secs vs $r_secs) — the budget is not reading the table"

# ── 4. a check with NO row must not shrink the budget ─────────────────────────────────────────────
# The fail-safe that lets someone register a check without waiting for a refreshed table: dropping a
# row lowers the derived sum, and the missing check is charged its MEASURED cost at the end of a real
# run instead. Asserted here as "fewer rows, smaller sum" — the charging half needs a full run.
p_secs="$(field "$tmp/dropped.tsv" baseline-seconds)"
[[ "${p_secs:-0}" -lt "${r_secs:-0}" ]] || fail dropped-row \
  "removing the most expensive row left the baseline sum at ${p_secs}s (was ${r_secs}s) — rows are not matched by name"

# ── 5. a garbage table must not be read as a tiny budget ──────────────────────────────────────────
g_secs="$(field "$tmp/garbage.tsv" baseline-seconds)"
g_budget="$(field "$tmp/garbage.tsv" budget)"
[[ "${g_secs:-0}" -eq 0 ]] || fail garbage-table "a table with no valid rows summed to ${g_secs}s"
want="$(expect fallback "$probe" 0)"
[[ "$g_budget" == "$want" ]] || fail garbage-table \
  "an unparseable table must fall back to the constant, not to a small budget: got $g_budget, want $want"

# ── 6. a truncated row must be DROPPED, not read past its end ─────────────────────────────────────
# `sbt-plugin-scripted` with no columns after it. The reader must skip it — reaching for column 1 of
# a one-element row is an index error inside the suite runner, which reports as the whole suite
# dying rather than as a bad data file. The observable is that the sum matches the dropped-row case
# exactly: same rows present, one name that carries no numbers.
t_secs="$(field "$tmp/truncated.tsv" baseline-seconds)"
[[ -n "$t_secs" ]] || fail truncated-row \
  "no answer for the truncated table — the reader did not survive a row with a real name and no columns"
[[ "$t_secs" == "$p_secs" ]] || fail truncated-row \
  "a name-only row was not dropped: sum ${t_secs}s against ${p_secs}s for the same table without it"

echo "smoke-budget-gate: PASS  (shipped baseline ${r_rows} rows / ${r_secs}s -> budget ${r_budget}s at probe ${probe}ms)"
