#!/usr/bin/env bash
#
# claim-activity-overrides-heartbeat — commit evidence must outrank a stale heartbeat FIELD.
#
# BUGS.md `heartbeat-stale-while-active`. Measured 2026-07-30: `v2-backend-matrix-gaps` carried a
# heartbeat 10.7 HOURS old while committing to main every few minutes — 13 commits in the hour
# `coord-status` called it stale. It was triaged as orphaned twice, and only a manual `git log`
# prevented an edit landing underneath an agent actively working in that file.
#
# The field was not wrong to be stale: AGENTS.md tells agents to heartbeat on a MATERIAL STATUS
# CHANGE, not as running commentary, because heartbeat-only commits once flooded the log. So the
# protocol and the check disagreed. The resolution is that an agent which is COMMITTING is alive,
# and this gate is what keeps that true — the previous behaviour was a silent false-orphan, i.e. a
# check that fails by declaring the wrong thing, which is exactly the class AGENTS.md's
# "measurement apparatus" rule is about.
#
# Both directions are asserted, because a check that only ever says "live" would also pass a test
# that only looked at the live case.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fails=0

src="$ROOT/scripts/coord-status"

# ── 1. the helper exists and reads BOTH sources of commit evidence ───────────────────────────────
if ! grep -q 'claim_activity_epoch()' "$src"; then
  echo "FAIL scripts/coord-status has no claim_activity_epoch — commit evidence is not consulted"
  fails=$((fails + 1))
else
  for needle in 'refs/heads/' 'refs/remotes/origin/' '.work/active/\$slug.claim'; do
    if ! grep -q -- "$needle" "$src"; then
      echo "FAIL claim_activity_epoch does not consult $needle"
      echo "     both the branch tip (local AND remote) and the claim file must count as activity:"
      echo "     an agent may commit only to its branch, or only bump the claim on main."
      fails=$((fails + 1))
    fi
  done
fi

# ── 2. the staleness path actually USES it, before reporting ──────────────────────────────────────
# Read the ORDER from the code: the activity check has to come before the 'potentially stale' print,
# or a live claim is still reported as stale and the helper is decoration.
activity_line="$(grep -n 'claim_activity_epoch "\$slug"' "$src" | head -1 | cut -d: -f1)"
report_line="$(grep -n "printf 'potentially stale heartbeat" "$src" | head -1 | cut -d: -f1)"
if [[ -z "$activity_line" || -z "$report_line" ]]; then
  echo "FAIL cannot locate both the activity check and the stale report in scripts/coord-status"
  echo "     activity_line='${activity_line:-missing}' report_line='${report_line:-missing}'"
  fails=$((fails + 1))
elif [[ "$activity_line" -ge "$report_line" ]]; then
  echo "FAIL the activity check (line $activity_line) does not run BEFORE the stale report (line $report_line)"
  echo "     a live claim would still be printed as stale"
  fails=$((fails + 1))
fi

# ── 3. a claim reported live must SAY its field was stale ─────────────────────────────────────────
# Silently ignoring the divergence would hide a genuinely un-heartbeated agent from the human.
if ! grep -q 'stale heartbeat field, ignored' "$src"; then
  echo "FAIL a claim kept alive by commit activity must name its stale heartbeat field explicitly"
  echo "     otherwise the divergence between protocol and field becomes invisible"
  fails=$((fails + 1))
fi

# ── 4. the threshold is SHARED, not a second copy ─────────────────────────────────────────────────
# heartbeat-threshold-single-source.sh pins the number itself; here we only assert the activity
# comparison reuses the same literal rather than introducing a second, driftable one.
hb="$(sed -n 's/.*heartbeat_age_seconds" -gt \([0-9][0-9]*\).*/\1/p' "$src" | head -1)"
act="$(sed -n 's/.*activity_age_seconds" -le \([0-9][0-9]*\).*/\1/p' "$src" | head -1)"
if [[ -z "$hb" || -z "$act" ]]; then
  echo "FAIL cannot read both thresholds (heartbeat='${hb:-missing}' activity='${act:-missing}')"
  fails=$((fails + 1))
elif [[ "$hb" != "$act" ]]; then
  echo "FAIL the activity window ($act s) differs from the heartbeat threshold ($hb s)"
  echo "     two numbers deciding the same question will drift; they must be one"
  fails=$((fails + 1))
fi

# ── 5. a date in the FUTURE must be reported, not read as freshness ───────────────────────────────
# The clamp this replaces read a negative age as 0, i.e. "just committed", which is the least safe
# reading available: the claim looks freshly alive forever and nobody ever reaps it. It also killed a
# whole job's self-test — tests/BUGS.md `ci-status-guard-selftest-two-stacked-defects` traced the
# fixture's pinned clock sitting BEFORE a real commit, the age going negative, and the assertion the
# test existed to make becoming unreachable. Deterministically dead, not flaky.
# BOTH paths, because both clamped: the heartbeat field and the commit activity. Asserting "some
# ANOMALY line exists" passed while the activity report was deleted — the surviving heartbeat line
# satisfied it — so the control that should have failed did not, and the check was measuring the
# wrong thing.
anomaly_lines="$(grep -c 'ANOMALY:.*FUTURE' "$src" || true)"
if [[ "${anomaly_lines:-0}" -lt 2 ]]; then
  echo "FAIL a timestamp in the future is absorbed silently on at least one path (found $anomaly_lines of 2)"
  echo "     both the heartbeat field and the commit activity clamped a negative age to zero;"
  echo "     reading it as '0 seconds old' keeps a claim alive forever"
  fails=$((fails + 1))
fi

# ── 6. and naming it is not enough: it must not COUNT as activity ────────────────────────────────
# A report that still lets the claim through as live would satisfy check 5 and change nothing.
if ! grep -qE 'if \[\[ -z "\$future_anomaly" && "\$activity_age_seconds" -le' "$src"; then
  echo "FAIL the activity-freshness branch is not guarded by the future-date anomaly"
  echo "     naming the anomaly while still counting it as freshness fixes nothing"
  fails=$((fails + 1))
fi

# ── 7. the skew tolerance must NOT be a second copy of the staleness threshold ────────────────────
# Same argument as check 4: two numbers deciding one question drift. The tolerance answers a
# DIFFERENT question (is this skew or an anomaly), so it is its own constant — but it must not be
# written as the staleness number, which would silently couple them.
tol="$(sed -n 's/^SKEW_TOLERANCE=\([0-9][0-9]*\).*/\1/p' "$src" | head -1)"
if [[ -z "$tol" ]]; then
  echo "FAIL no SKEW_TOLERANCE constant — the future-date test is then a magic number at its site"
  fails=$((fails + 1))
elif [[ -n "$hb" && "$tol" == "$hb" ]]; then
  echo "FAIL the skew tolerance ($tol s) is the staleness threshold — two questions, one number"
  fails=$((fails + 1))
fi

if [[ "$fails" -gt 0 ]]; then
  echo "claim-activity-overrides-heartbeat: $fails check(s) FAILED"
  exit 1
fi
echo "claim-activity-overrides-heartbeat: OK (commit evidence outranks a stale heartbeat field)"
