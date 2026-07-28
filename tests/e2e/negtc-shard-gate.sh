#!/usr/bin/env bash
#
# negtc-shard-gate — prove that sharding the negative-toolchain release gate does not silently
# shrink it.
#
# A sharded RELEASE gate has one catastrophic failure mode and it fails GREEN: a scheme that drops
# cases reports success over less than it claims, and the release ships. So this does not check the
# arithmetic — it runs both real sweeps and byte-compares
#
#     union(shard 0/N … shard N-1/N)  ==  no-shard
#
# for each of them, printing the diff on mismatch.
#
# Cheap by construction: `--list` enumerates the corpus before the staged-jar check, so this needs no
# built tower and runs in seconds.
#
# Usage: tests/e2e/negtc-shard-gate.sh [N]      (default N=4)
set -uo pipefail

N="${1:-4}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

fail=0
ok()  { printf '✓ %s\n' "$*"; }
bad() { printf '✗ %s\n' "$*"; fail=1; }

echo "── negtc shard partition gate (N=$N)"

for sweep in native-front-corpus bc-parity-sweep; do
  S="$ROOT/scripts/$sweep"
  [ -x "$S" ] || { bad "not executable: $S"; continue; }
  echo "  ── $sweep"

  "$S" --list > "$TMP/$sweep.all.raw" 2>"$TMP/$sweep.err"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    bad "$sweep --list exited $rc"; sed 's/^/      /' "$TMP/$sweep.err"; continue
  fi
  LC_ALL=C sort "$TMP/$sweep.all.raw" > "$TMP/$sweep.all"
  total=$(wc -l < "$TMP/$sweep.all" | tr -d ' ')
  if [ "$total" -lt 2 ]; then
    bad "$sweep listed $total case(s) — it did not enumerate the corpus"; continue
  fi
  ok "$sweep: $total case(s) unsharded"

  : > "$TMP/$sweep.union"
  i=0; min=999999; max=0
  while [ "$i" -lt "$N" ]; do
    "$S" --list --shard "$i/$N" 2>/dev/null | LC_ALL=C sort > "$TMP/$sweep.$i"
    c=$(wc -l < "$TMP/$sweep.$i" | tr -d ' ')
    [ "$c" -lt "$min" ] && min=$c
    [ "$c" -gt "$max" ] && max=$c
    [ "$c" -eq 0 ] && bad "$sweep shard $i/$N is EMPTY — a shard testing nothing still reports success"
    cat "$TMP/$sweep.$i" >> "$TMP/$sweep.union"
    i=$(( i + 1 ))
  done

  # COVERAGE — the union is exactly the corpus.
  LC_ALL=C sort "$TMP/$sweep.union" > "$TMP/$sweep.union.sorted"
  if diff -u "$TMP/$sweep.all" "$TMP/$sweep.union.sorted" > "$TMP/$sweep.diff"; then
    ok "$sweep: union of $N shards == unsharded ($total cases, byte-identical)"
  else
    bad "$sweep: union != unsharded — cases are being DROPPED or DUPLICATED"
    printf '      expected=<unsharded listing> got=<union of shards>; diff:\n'
    sed 's/^/      /' "$TMP/$sweep.diff" | head -30
  fi

  # DISJOINT — no case runs twice (wasted CI time, and a red reported twice).
  dups="$(LC_ALL=C sort "$TMP/$sweep.union" | uniq -d)"
  [ -z "$dups" ] && ok "$sweep: shards are disjoint" \
    || { bad "$sweep: these cases appear in more than one shard:"; printf '      %s\n' $dups; }

  # BALANCE — a degenerate split makes the matrix pointless: as slow as before, but looking parallel.
  [ "$(( max - min ))" -le 1 ] && ok "$sweep: balanced (min=$min max=$max)" \
    || bad "$sweep: unbalanced min=$min max=$max (round-robin should differ by at most 1)"

  # The flag must be VALIDATED, not silently ignored — an ignored --shard means every matrix job
  # runs the whole corpus, which looks green and costs N times the CI budget.
  "$S" --list --shard "9/$N" >/dev/null 2>&1 \
    && bad "$sweep: --shard 9/$N was accepted; out-of-range must fail loudly" \
    || ok "$sweep: --shard 9/$N is rejected"
  "$S" --list --shard "notashard" >/dev/null 2>&1 \
    && bad "$sweep: --shard notashard was accepted" \
    || ok "$sweep: malformed --shard is rejected"
done

# The two sweeps run over the SAME corpus and are shipped as one gate. If they ever disagree about
# what is in scope, a case could be covered by one sweep's shard k and the other's shard j, and no
# single matrix job would test both halves of it.
if [ -f "$TMP/native-front-corpus.all" ] && [ -f "$TMP/bc-parity-sweep.all" ]; then
  if diff -u "$TMP/native-front-corpus.all" "$TMP/bc-parity-sweep.all" > "$TMP/cross.diff"; then
    ok "both sweeps select the identical corpus, so shard k covers the same cases in each"
  else
    bad "the two sweeps disagree about which cases are in scope:"
    sed 's/^/      /' "$TMP/cross.diff" | head -20
  fi
fi

# The release gate must REFUSE --shard, loudly. It runs the taxonomy + freeze after the sweeps, and
# those compare against frozen WHOLE-CORPUS counts by exact equality — a partial report drifts every
# metric, so a sharded run of that gate is red regardless of the tree's health. Accepting the flag
# would turn a real optimisation into a confident wrong red; refusing with an explanation is the
# honest interface until the map/reduce split lands (BACKLOG negtc-gate-shard-reduce).
GATE="$ROOT/tests/e2e/v21-negative-toolchain-release-gate.sh"
if [ -f "$GATE" ]; then
  out="$("$GATE" --shard 0/4 2>&1)"; rc=$?
  if [ "$rc" -eq 2 ] && printf '%s' "$out" | grep -q 'not supported here, on purpose'; then
    ok "the release gate refuses --shard with an explanation (exit $rc)"
  else
    bad "the release gate did not refuse --shard: expected=exit 2 + explanation got=exit $rc"
    printf '      %s\n' "$(printf '%s' "$out" | head -3)"
  fi
  printf '%s' "$out" | grep -q 'negtc-gate-shard-reduce' \
    && ok "the refusal names where the real fix is tracked" \
    || bad "the refusal does not point at the backlog item, so the reader is left stuck"
fi

echo
[ "$fail" -eq 0 ] && { echo "✓ negtc shard partition gate PASSED"; exit 0; }
echo "✗ negtc shard partition gate FAILED"; exit 1
