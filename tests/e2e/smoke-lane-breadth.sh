#!/usr/bin/env bash
#
# smoke-lane-breadth — every backend lane still runs, in ONE invocation, in ~9 s.
#
# WHAT THIS REPLACED, and why it is not a coverage cut. Smoke used to carry three checks here:
#
#   corpus-breadth-slice     13 cases over int,js,v2      14.9 s
#   corpus-jvm-lane           4 cases over jvm            17.8 s
#   conformance-lanes-flag    the gate on the two above   30.2 s
#                                                         ──────
#                                                         62.9 s
#
# They were sized for a world where smoke was the ONLY per-push verdict, so a corpus SAMPLE on the
# push path was the only corpus signal that existed. Since 2026-08-01 tier 2 (`ci.yml` on push) runs
# the WHOLE corpus — 353 cases, all lanes — on the very same push, so sampling 17 of them here buys
# nothing on CI.
#
# What it did still buy is BREADTH, and that part is real: `v1/runtime/backend/interpreter`,
# `v1/runtime/backend/jvm` and `v2` have NO other check in the suite. Deleting the three outright
# would have taken smoke's only coverage of the interpreter, the JVM backend and v2 with them — not
# a duplicate removed but three core modules gone dark, locally, where tier 2 does not exist. So the
# breadth is kept and the sampling is dropped: one case, four lanes, one process.
#
# `conformance-lanes-flag` went with them because it was not independent coverage — it existed to
# stop those two checks failing SILENTLY (a zero-match `--only` exited 0 over nothing; a `--lanes`
# value could be eaten as the positional corpus dir). That protection is not discarded, it is
# INLINED below: this script asserts the case count and every expected lane by name, so the same
# two failure modes are refused here, in the check itself, for free instead of for 30 seconds.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CASE="${1:-effects}"
LANES="int,js,jvm,v2"
fail=0
ok()  { printf '✓ %s\n' "$*"; }
bad() { printf '✗ %s\n' "$*"; fail=1; }

echo "── smoke lane breadth ($CASE over $LANES)"

# `effects` is the deliberate choice: it is the one corpus case whose front-matter declares ALL FOUR
# backends, so a single case exercises every lane. Picking a case that declares fewer would make
# lanes silently SKIP and this check would pass having run two of them.
out="$(cd "$ROOT" && SSC_CONF_WARM_JVM=1 scala-cli --server=false tests/conformance/run.sc -- \
        --only "$CASE" --no-memo --lanes "$LANES" 2>&1)"
rc=$?

# THE RUNNER'S OWN LINES GO TO STDOUT, not just into `$out`. `scripts/smoke-ci` attributes lanes to
# their owning modules by parsing `PASS [lane]` out of every `corpus-*` check's stdout — so a wrapper
# that captures and never re-emits them makes the whole per-lane rollup report UNREADABLE and fails
# the suite. It did exactly that on the first run of this script, which is the rollup working.
printf '%s\n' "$out" | grep -E "^\s*(PASS|FAIL|SKIP|KNOWN-RED) \[|^Results:|^\s*Results:" || true

# ── the two silent-failure modes conformance-lanes-flag existed to catch ──────
#
# NOT the exit code: a zero-match `--only` and an eaten `--lanes` both exit 0. Assert the shape.
case "$out" in
  *"1 passed, 0 failed out of 1 tests"*) ok "the case ran and passed" ;;
  *"out of 0 tests"*|*"0 passed"*)
    bad "the --only pattern selected NOTHING — this is the zero-match green that exits 0"
    printf '%s\n' "$out" | tail -4 | sed 's/^/      /' ;;
  *) bad "unexpected result (rc=$rc):"; printf '%s\n' "$out" | tail -8 | sed 's/^/      /' ;;
esac

# Every lane by NAME. A `--lanes` value eaten as the positional corpus dir silently runs the default
# lane set, which would still print a passing case — so the presence of each label is the assertion.
for lane in "INT" "JS " "JVM" "V2 "; do
  case "$out" in
    *"PASS [$lane]"*) ok "lane $lane ran" ;;
    *"KNOWN-RED [$lane]"*) ok "lane $lane ran (declared red)" ;;
    *) bad "lane $lane did not run — --lanes was not honoured, or the case does not declare it"
       printf '%s\n' "$out" | grep -E "PASS|FAIL|SKIP|KNOWN" | sed 's/^/      /' ;;
  esac
done

echo
[ "$fail" -eq 0 ] && { echo "✓ smoke lane breadth PASSED"; exit 0; }
echo "✗ smoke lane breadth FAILED"; exit 1
