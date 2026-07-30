#!/usr/bin/env bash
#
# negtc-mapreduce-gate — map+merge+reduce must equal one unsharded run, byte for byte.
#
# This is the property the whole split rests on. If it does not hold, a sharded release gate reports a
# verdict about something other than the corpus — the worst failure a release gate can have, and one
# that shows up as GREEN.
#
# It is proven on the SWEEP REPORTS rather than by running the 58-minute gate twice: the reports are
# the only thing the shards produce and the only thing reduce consumes, so equality there is
# equality of everything downstream. Restricted to a small `--only` slice, so this costs minutes.
#
# Usage: tests/e2e/negtc-mapreduce-gate.sh [N] [only-glob]
set -uo pipefail

N="${1:-3}"
# A SINGLE glob. `bc-parity-sweep`'s --only is a shell `case` pattern and the `|`-alternation form
# silently selects NOTHING — which is how the first version of this gate passed over zero cases.
ONLY="${2:-a*}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fail=0
ok()  { printf '✓ %s\n' "$*"; }
bad() { printf '✗ %s\n' "$*"; fail=1; }

echo "── negtc map/reduce equality gate (N=$N)"
MERGE="$ROOT/scripts/negtc-merge-reports"
[ -x "$MERGE" ] || { bad "not executable: $MERGE"; exit 1; }

# The merge helper's own invariants first — a broken merge would make the equality below meaningless.
printf 'file\tcategory\nb.ssc\tidentical\n' > "$TMP/s1.tsv"
printf 'file\tcategory\na.ssc\tskipped-server\n' > "$TMP/s2.tsv"
"$MERGE" "$TMP/m.tsv" "$TMP/s1.tsv" "$TMP/s2.tsv" >/dev/null 2>&1
if [ "$(head -1 "$TMP/m.tsv")" = "$(printf 'file\tcategory')" ] && [ "$(wc -l < "$TMP/m.tsv" | tr -d ' ')" = "3" ]; then
  ok "merge keeps ONE header and both rows"
else
  bad "merge produced the wrong shape:"; sed 's/^/    /' "$TMP/m.tsv"
fi
# Deterministic regardless of shard order.
"$MERGE" "$TMP/m2.tsv" "$TMP/s2.tsv" "$TMP/s1.tsv" >/dev/null 2>&1
cmp -s "$TMP/m.tsv" "$TMP/m2.tsv" && ok "merge is order-independent" \
  || { bad "merge depends on shard order:"; diff "$TMP/m.tsv" "$TMP/m2.tsv" | sed 's/^/    /'; }
# A doubled row must be refused, not silently double-counted (too-high counts pass floor checks).
"$MERGE" "$TMP/m3.tsv" "$TMP/s1.tsv" "$TMP/s1.tsv" >/dev/null 2>&1 \
  && bad "merge accepted a duplicated case" || ok "merge refuses a duplicated case"
# Headers of different shapes must be refused.
printf 'other\theader\nx\ty\n' > "$TMP/s3.tsv"
"$MERGE" "$TMP/m4.tsv" "$TMP/s1.tsv" "$TMP/s3.tsv" >/dev/null 2>&1 \
  && bad "merge accepted mismatched headers" || ok "merge refuses mismatched headers"

# ── the equality itself, on the real sweeps over a small slice ───────────────
if [ ! -x "$ROOT/bin/ssc" ] || [ ! -d "$ROOT/bin/lib/standard" ]; then
  echo "  (skip equality: no staged launcher — run scripts/sbtc \"installBin\" first)"
  echo
  [ "$fail" -eq 0 ] && { echo "✓ merge invariants PASSED (equality skipped)"; exit 0; }
  echo "✗ gate FAILED"; exit 1
fi

sweep() { # sweep <out> [shard]
  local out=$1 sh=${2:-}
  local args=(--strict --only "$ONLY" --report "$out")
  [ -n "$sh" ] && args=(--only "$ONLY" --shard "$sh" --report "$out")
  "$ROOT/scripts/bc-parity-sweep" "${args[@]}" >/dev/null 2>&1 || true
}

sweep "$TMP/whole.tsv"
i=0; parts=()
while [ "$i" -lt "$N" ]; do sweep "$TMP/p$i.tsv" "$i/$N"; parts+=("$TMP/p$i.tsv"); i=$((i+1)); done
"$MERGE" "$TMP/merged.tsv" "${parts[@]}" >/dev/null 2>&1

# Compare on sorted content: the unsharded run emits in corpus order, the merge sorts.
{ head -1 "$TMP/whole.tsv"; tail -n +2 "$TMP/whole.tsv" | LC_ALL=C sort; } > "$TMP/whole.sorted"
rows=$(( $(wc -l < "$TMP/merged.tsv") - 1 ))
# REFUSE TO PASS ON AN EMPTY COMPARISON. The first version of this gate reported
# "0 rows, byte-identical" and exited 0 — both sides were empty because the --only pattern matched
# nothing, so it proved equality of nothing while looking like proof. That is the exact failure this
# repo keeps paying for (AGENTS.md: "green from a proxy is not green"), and this gate committed it on
# its first run. A comparison that covers no rows is a gate that is not running.
if [ "$rows" -lt "$N" ]; then
  bad "the comparison covered only $rows row(s) for N=$N — too few to prove anything"
  printf '    --only %q selected %s case(s); pick a pattern that selects at least N\n' "$ONLY" "$rows"
elif cmp -s "$TMP/whole.sorted" "$TMP/merged.tsv"; then
  ok "map+merge over $N shards == one unsharded sweep ($rows rows, byte-identical)"
else
  bad "map+merge != unsharded — a sharded gate would judge something other than the corpus"
  diff -u "$TMP/whole.sorted" "$TMP/merged.tsv" | head -20 | sed 's/^/    /'
fi

echo
[ "$fail" -eq 0 ] && { echo "✓ negtc map/reduce equality gate PASSED"; exit 0; }
echo "✗ negtc map/reduce equality gate FAILED"; exit 1
