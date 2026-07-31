#!/usr/bin/env bash
#
# negtc-manifest-contract-gate — `bc-parity-sweep` must accept the manifest its OWNER accepts.
#
# `tests/fixtures/v21-explicit-lanes/manifest.tsv` has exactly one owner:
# `tests/e2e/v21-explicit-lanes-gate.sh`. It checks membership properly — exact totals, per-lane and
# per-family counts, that every `file` still exists under examples/, that every `regression` script
# exists and is executable.
#
# `scripts/bc-parity-sweep` only READS the fixture (a per-case lane lookup). It used to re-assert
# membership anyway, with its own copy of the contract frozen at `total != 15` on 2026-07-12. The
# owner was kept current — 15 -> 13 -> 12 on 2026-07-31, MCP then NFC joining the standard graph —
# and the copy was not. From 06:25 that day the `sbt` job died at exit 2 on a fixture its own
# dedicated gate had just certified green, 34 minutes earlier in the same job.
#
# WHAT THIS GATE PINS, in both directions, because deleting a check and weakening a check look
# identical in a diff:
#   * the two consumers agree about the CHECKED-IN manifest, and about one with a different (valid)
#     row count — the case the frozen number got wrong;
#   * a manifest that is genuinely broken for the field bc-parity-sweep reads is still refused, by
#     name.
#
# EXIT CODES CANNOT TELL THESE APART. Both a rejected manifest and a missing launcher exit 2, and
# this gate deliberately runs without a built tower so it costs seconds. So it asserts on the
# MESSAGE, never the status — the repo has paid for that confusion before (AGENTS.md; memory
# `two_front_bug_pairs`: "compare OUTPUT, never the exit code").
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SWEEP="$ROOT/scripts/bc-parity-sweep"
REAL="$ROOT/tests/fixtures/v21-explicit-lanes/manifest.tsv"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fail=0
ok()  { printf '✓ %s\n' "$*"; }
bad() { printf '✗ %s\n' "$*"; fail=1; }

echo "── negtc explicit-lane manifest contract gate"
[ -x "$SWEEP" ] || { bad "not executable: $SWEEP"; exit 1; }
[ -f "$REAL"  ] || { bad "missing fixture: $REAL"; exit 1; }

# `--only` selects nothing and `--ssc` names a path that cannot exist: we want the manifest
# validation and NOTHING after it, identically on a host with a built tower and one without. The
# launcher refusal that follows a clean manifest is the marker that validation ran and passed.
sweep_stderr() { # sweep_stderr <manifest>
  BC_PARITY_EXPLICIT_MANIFEST="$1" "$SWEEP" --ssc "$TMP/no-such-launcher" \
    --only 'zzzz-no-such-case*.ssc' --report "$TMP/report.tsv" 2>&1 >/dev/null
}

REJECT='explicit-lane manifest rejected'
PAST='staged launcher not found'

# WHY `accepts` DEMANDS THE LAUNCHER MESSAGE. Without it this helper passes vacuously whenever the
# validation is not reached at all — which is precisely the pre-fix arrangement, where the launcher
# check came FIRST and a manifest was never inspected on a machine with no build. Measured while
# writing this gate: against the unfixed script all five refusal cases went red (correct) but both
# `accepts` cases went GREEN, having proven nothing. Requiring the marker makes "it got past
# validation" an assertion instead of an assumption.
accepts() { # accepts <label> <manifest>
  local out; out=$(sweep_stderr "$2")
  case "$out" in
    *"$REJECT"*) bad "$1 — refused a manifest it must accept:"; printf '%s\n' "$out" | sed 's/^/    /' ;;
    *"$PAST"*)   ok "$1" ;;
    *) bad "$1 — validation was never reached (no refusal, no launcher check):"
       printf '%s\n' "${out:-<no output>}" | sed 's/^/    /' ;;
  esac
}

# And `rejects` demands the launcher message is ABSENT: a refusal must happen BEFORE the build is
# consulted, or a broken fixture stays invisible until six minutes into a CI job — which is the
# other half of what went wrong here.
rejects() { # rejects <label> <manifest> <expected-reason-substring>
  local out; out=$(sweep_stderr "$2")
  case "$out" in
    *"$REJECT"*)
      case "$out" in
        *"$3"*)
          case "$out" in
            *"$PAST"*) bad "$1 — refused only after the launcher check; a fixture defect must not need a build" ;;
            *) ok "$1" ;;
          esac ;;
        *) bad "$1 — refused, but not for the stated reason (wanted '$3'):"; printf '%s\n' "$out" | sed 's/^/    /' ;;
      esac ;;
    *) bad "$1 — accepted a manifest it must refuse:"; printf '%s\n' "${out:-<no output>}" | sed 's/^/    /' ;;
  esac
}

# ── it agrees with the owner about the real fixture ──────────────────────────
accepts "accepts the checked-in manifest ($(($(wc -l < "$REAL") - 1)) rows)" "$REAL"

# The regression itself: membership changes when a case IMPROVES out of the manifest. A reader who
# re-freezes a count in this script fails HERE, not six minutes into a CI job.
# `sed '$d'`, not `head -n -1`: BSD head has no negative count, so the GNU form silently produced an
# EMPTY file here and the assertion "passed" by hitting the no-rows refusal instead.
sed '$d' "$REAL" > "$TMP/fewer.tsv"
accepts "accepts a manifest with a different (valid) row count" "$TMP/fewer.tsv"

# ── and still refuses what it genuinely cannot read ──────────────────────────
{ printf 'file\tfamily\tLANE\tregression\tdependency_tier\n'; tail -n +2 "$REAL"; } > "$TMP/header.tsv"
rejects "refuses a wrong header" "$TMP/header.tsv" 'invalid header'

awk -F '\t' 'BEGIN{OFS="\t"} NR>1 && !done {$3="sideways-lane"; done=1} {print}' "$REAL" > "$TMP/lane.tsv"
rejects "refuses an unknown lane" "$TMP/lane.tsv" 'invalid lane'

{ cat "$REAL"; sed -n '2p' "$REAL"; } > "$TMP/dup.tsv"
rejects "refuses a duplicated member" "$TMP/dup.tsv" 'duplicate member'

awk -F '\t' 'BEGIN{OFS="\t"} NR>1 && !done {$0=$1 OFS $2 OFS $3; done=1} {print}' "$REAL" > "$TMP/short.tsv"
rejects "refuses a row missing fields" "$TMP/short.tsv" 'malformed row'

head -1 "$REAL" > "$TMP/empty.tsv"
rejects "refuses a manifest with no rows" "$TMP/empty.tsv" 'no rows'

echo
[ "$fail" -eq 0 ] && { echo "✓ negtc explicit-lane manifest contract gate PASSED"; exit 0; }
echo "✗ negtc explicit-lane manifest contract gate FAILED"; exit 1
