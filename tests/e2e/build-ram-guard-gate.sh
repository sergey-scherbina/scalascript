#!/usr/bin/env bash
#
# build-ram-guard-gate — prove the host memory guard would actually fire.
#
# The failure this exists to prevent is not a crash. It is a guard that runs forever and does
# nothing, which is what the previous one did: loaded every 20 s for a week, 0-byte log, straight
# through two OOM events, one of which force-rebooted the machine. Nothing detected that, because a
# silent guard and a working guard look identical from the outside.
#
# So every assertion here observes behaviour, prints expected/got, and — critically — checks BOTH
# directions. A guard that always fires is as useless as one that never does, and only comparing the
# two tells them apart.
#
# Usage: tests/e2e/build-ram-guard-gate.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GUARD="$ROOT/scripts/build-ram-guard"
INSTALL="$ROOT/scripts/build-guards-install"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
export SSC_GUARD_LOG="$TMP/guard.log" SSC_GUARD_STATE="$TMP/guard.state"

fail=0
ok()  { printf '✓ %s\n' "$*"; }
bad() { printf '✗ %s\n' "$*"; fail=1; }
eq()  { if [ "$2" = "$3" ]; then ok "$1 ($3)"; else bad "$1: expected=$2 got=$3"; fi; }

echo "── build RAM guard gate"
[ -x "$GUARD" ]   || bad "not executable: $GUARD"
[ -x "$INSTALL" ] || bad "not executable: $INSTALL"

# ── 1. the guard's own tier table ────────────────────────────────────────────
"$GUARD" --self-test >"$TMP/self.txt" 2>&1
eq "guard --self-test" 0 "$?"
grep -q 'never consults memorystatus_level' "$TMP/self.txt" \
  && ok "self-test asserts the decision block ignores memorystatus_level" \
  || { bad "self-test no longer asserts the memorystatus_level invariant"; sed 's/^/    /' "$TMP/self.txt"; }

# ── 2. a healthy host must produce NO kill decisions ─────────────────────────
# Floors set absurdly low so nothing can qualify. If the guard acts here it is hair-triggered, and a
# hair-triggered guard that kills agents' builds gets disabled by the first person it annoys —
# which is how you end up with no guard at all.
: > "$SSC_GUARD_LOG"
SSC_GUARD_REAP_FLOOR_MB=1 SSC_GUARD_SHED_FLOOR_MB=1 SSC_GUARD_THRASH_PAGES=999999999 \
  "$GUARD" --dry-run >/dev/null 2>&1
eq "healthy tick exits 0" 0 "$?"
if grep -q 'would-kill\|KILL' "$SSC_GUARD_LOG" 2>/dev/null; then
  bad "guard proposed a kill on a host it should consider healthy:"
  sed 's/^/    /' "$SSC_GUARD_LOG"
else
  ok "healthy tick proposed no kills"
fi

# ── 3. …and under pressure it must ACT ───────────────────────────────────────
# Same machine, same instant, only the floor moved. That is the comparison: if this does not
# produce decisions while §2 did not, the guard cannot act at all.
: > "$SSC_GUARD_LOG"
SSC_GUARD_REAP_FLOOR_MB=99999999 SSC_GUARD_IDLE_SAMPLE=1 "$GUARD" --dry-run >/dev/null 2>&1
eq "pressure tick exits 0" 0 "$?"
if grep -q '^\[.*\] ACT ' "$SSC_GUARD_LOG" 2>/dev/null; then
  ok "guard entered its action path under a forced-low floor"
else
  bad "guard did NOT act with the reap floor above all possible memory — it cannot fire at all:"
  sed 's/^/    /' "$SSC_GUARD_LOG"
fi
grep -q 'available=.*pageouts_rate=' "$SSC_GUARD_LOG" \
  && ok "every action line records the reading that caused it" \
  || bad "action lines do not record available/pageout_rate — an unexplained kill is unreviewable"

# ── 4. dry-run must never actually kill ──────────────────────────────────────
# Asserted on the PIDs the guard NAMED, not on the host's builder COUNT.
#
# It used to sample `ps ax | grep -c` before and after and require `after >= before`. That counts
# every builder on the machine, including other agents', so a sibling's compile finishing normally
# between the two samples made the count drop and this cell blamed the dry run. Measured 2026-08-04:
# the same commit FAILED here inside smoke ("expected>=5 got=4") and PASSED minutes later, in the
# shared checkout and in the same worktree — three runs, one tree, two verdicts.
# BUGS `build-ram-guard-gate-fails-under-ambient-load`.
#
# The guard's own dry-run lines name their targets — `DRY T2-idle would-kill pid=54466 (...)` — so
# the exact question is answerable: every process the guard said it WOULD kill must still be alive.
# That is stronger than the count (it checks the specific processes, not the population) and it does
# not depend on what else the host happens to be doing.
DRYLOG="$(mktemp "${TMPDIR:-/tmp}/ssc-ramguard-dry.XXXXXX")"
SSC_GUARD_LOG="$DRYLOG" SSC_GUARD_REAP_FLOOR_MB=99999999 SSC_GUARD_SHED_FLOOR_MB=99999999 \
  SSC_GUARD_THRASH_PAGES=0 SSC_GUARD_IDLE_SAMPLE=1 "$GUARD" --dry-run >/dev/null 2>&1
targets="$(grep -oE 'would-kill pid=[0-9]+' "$DRYLOG" 2>/dev/null | grep -oE '[0-9]+$' | sort -u)"
if [ -z "$targets" ]; then
  # Vacuous rather than green: with no named target there is nothing to verify, and saying so beats
  # a pass that looks like evidence.
  printf '  note: dry-run named no would-kill target — this check had nothing to verify\n'
else
  dead=""
  for pid in $targets; do kill -0 "$pid" 2>/dev/null || dead="$dead $pid"; done
  if [ -z "$dead" ]; then
    ok "dry-run killed none of the $(printf '%s\n' "$targets" | wc -l | tr -d ' ') process(es) it named"
  else
    bad "dry-run KILLED a process it only claimed it WOULD kill:$dead"
  fi
fi
rm -f "$DRYLOG"

# ── 5. the ladder is ordered, and T3 is reachable only in a real emergency ───
# The tier that can kill an agent's running compile must require BOTH low memory and active
# thrashing. Low-memory-alone reaching T3 is the false alarm that makes a guard untrustworthy.
out="$(SSC_GUARD_SHED_FLOOR_MB=99999999 SSC_GUARD_THRASH_PAGES=999999999 "$GUARD" --explain 2>&1)"
case "$out" in
  *tier=T3*) bad "T3 reached with thrashing impossible: $out" ;;
  *)         ok "T3 not reachable without thrashing, even at an absurd shed floor" ;;
esac
grep -q 'T1-orphan' "$GUARD" && grep -q 'T2-idle' "$GUARD" && grep -q 'T3-heaviest' "$GUARD" \
  && ok "all three ladder tiers are implemented" \
  || bad "the escalation ladder is incomplete — check T1/T2/T3 in $GUARD"

# ── 6. the log can never be ambiguous again ─────────────────────────────────
# "Log is empty" must mean "not running", never "all was well".
: > "$SSC_GUARD_LOG"; rm -f "$SSC_GUARD_STATE"
SSC_GUARD_REAP_FLOOR_MB=1 SSC_GUARD_SHED_FLOOR_MB=1 SSC_GUARD_THRASH_PAGES=999999999 \
  SSC_GUARD_HEARTBEAT_EVERY=1 "$GUARD" >/dev/null 2>&1
if grep -q '^\[.*\] ok available=' "$SSC_GUARD_LOG" 2>/dev/null; then
  ok "a healthy tick still writes a heartbeat line"
else
  bad "a healthy tick wrote nothing — an empty log would again be indistinguishable from a dead guard"
fi

# ── 7. the installer points at the repo, dry-runs by default, and finds drift ─
"$INSTALL" >"$TMP/inst.txt" 2>&1
eq "installer default mode exits 0" 0 "$?"
grep -q 'DRY RUN' "$TMP/inst.txt" \
  && ok "installer is dry-run by default (it edits persistent machine config)" \
  || { bad "installer did not announce a dry run — default must not mutate launchd"; sed 's/^/    /' "$TMP/inst.txt"; }
if grep -qE "ProgramArguments|$ROOT|/scripts/build-ram-guard" "$TMP/inst.txt"; then
  ok "installer targets a path inside the checkout"
else
  bad "installer output never names a repo path; drift is the thing it exists to remove"
fi
# It must resolve to the MAIN checkout: a launchd agent pointing into a worktree becomes a dead path
# the first time that worktree is removed.
target="$(grep -o '/[^ ]*/scripts/build-ram-guard' "$TMP/inst.txt" | head -1)"
if [ -z "$target" ]; then
  bad "installer never printed a build-ram-guard target path"
else
  case "$target" in
    *-wt-*) bad "installer targets a WORKTREE ($target) — it becomes a dead path the moment that worktree is removed" ;;
    *)      ok "installer targets the main checkout, not a worktree ($target)" ;;
  esac
  # Deliberately checks the file in THIS tree, not at $target. When this gate runs from a feature
  # worktree the main checkout has not merged yet, so $target legitimately does not exist and
  # asserting on it would fail for a reason that is not a defect. What must hold is that the file
  # the installer will point at is the one in the repo.
  [ -f "$ROOT/scripts/build-ram-guard" ] \
    && ok "the guard the installer points at exists in the repo" \
    || bad "scripts/build-ram-guard is missing from the repo"
fi

echo
[ "$fail" -eq 0 ] && { echo "✓ build RAM guard gate PASSED"; exit 0; }
echo "✗ build RAM guard gate FAILED"; exit 1
