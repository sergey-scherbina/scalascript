#!/usr/bin/env bash
#
# board-claim-parity.sh — the board and `.work/active/` must describe the same reality.
#
# SPRINT.md's contract states it in both directions: "A board row without a live
# .work/active/<slug>.claim is a lie; a claim without a board row is invisible work. Both directions
# have cost real duplicated effort on this project."
#
# It was never checked. Measured 2026-07-30: **4 live claims, 1 board row — 75% invisible**, and two
# of the three were mine. The cause was not discipline: nothing wrote the row, so the "same commit"
# rule could not be obeyed with the sanctioned tool. `scripts/coord-claim` now writes it; this gate
# is what stops it drifting again the first time someone hand-edits either side.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

BOARD="${SSC_BOARD:-$ROOT/SPRINT.md}"
LEDGER="${SSC_LEDGER:-$ROOT/.work/active/LEDGER.tsv}"
fail=0

[ -f "$LEDGER" ] || { echo "board-claim-parity: no ledger at $LEDGER" >&2; exit 2; }
[ -f "$BOARD" ]  || { echo "board-claim-parity: no board at $BOARD" >&2; exit 2; }

live=$(awk -F'\t' 'NR>2 && $1 !~ /^#/ && $1 != "" {print $1}' "$LEDGER" | sort -u)
rows=$(sed -n '/^## In flight/,/^## /p' "$BOARD" | grep '^| `' \
       | awk -F'|' '{gsub(/[` ]/,"",$4); if ($4 != "claim" && $4 != "") print $4}' | sort -u)

for c in $live; do
  printf '%s\n' "$rows" | grep -qx "$c" || {
    printf 'FAIL  invisible work — claim with no board row: %s\n' "$c" >&2
    printf '        Nobody can see this is being worked on. Add a row to %s, or release the claim.\n' "${BOARD#$ROOT/}" >&2
    fail=1; }
done

for r in $rows; do
  [ -n "$r" ] || continue
  printf '%s\n' "$live" | grep -qx "$r" || {
    printf 'FAIL  lie on the board — row with no live claim: %s\n' "$r" >&2
    printf '        The claim was released (or never made) and the row was left behind. Delete it.\n' >&2
    fail=1; }
done

if [ "$fail" -ne 0 ]; then
  printf '\nboard-claim-parity: FAIL (%s live claim(s), %s row(s))\n' \
    "$(printf '%s\n' "$live" | grep -c . || true)" "$(printf '%s\n' "$rows" | grep -c . || true)" >&2
  exit 1
fi
printf 'board-claim-parity: PASS (%s claim(s), each with exactly one row)\n' \
  "$(printf '%s\n' "$live" | grep -c . || true)"
