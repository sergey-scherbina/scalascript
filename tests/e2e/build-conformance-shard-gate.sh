#!/usr/bin/env bash
#
# build-conformance-shard-gate — prove that `run.sc --shard i/N` PARTITIONS the corpus.
#
# WHY THIS GATE EXISTS AT ALL
# ---------------------------
# Sharding a correctness suite has exactly one catastrophic failure mode, and it fails GREEN: a
# shard scheme that silently drops cases reports "all tests passed" while testing less than it
# claims. AGENTS.md names this the project's most expensive recurring pattern, and the corpus
# contract already lived it — 13 runs, zero verdicts, read as benign for weeks.
#
# So this does not check the arithmetic. It runs the real runner N+1 times and BYTE-COMPARES the
# sorted union of the shard listings against the unsharded listing:
#
#   union(shard 0/N … shard N-1/N)  ==  no-shard
#
# and prints the actual diff on mismatch, never a bare exit code.
#
# Cheap by construction: `--list` enumerates the corpus and exits before touching a launcher, so
# this runs in seconds in the `Validate` job rather than behind a 3.6-minute assembly.
#
# Usage: tests/e2e/build-conformance-shard-gate.sh [N]      (default N=4, the CI matrix width)
set -uo pipefail

N="${1:-4}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN="$ROOT/tests/conformance/run.sc"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail=0
note() { printf '  %s\n' "$*"; }
bad()  { printf '✗ %s\n' "$*"; fail=1; }
ok()   { printf '✓ %s\n' "$*"; }

echo "── conformance shard partition gate (N=$N)"

command -v scala-cli >/dev/null 2>&1 || { echo "SKIP: scala-cli not on PATH"; exit 0; }

# `--server=false`: never leave a bloop daemon behind (the exact leak scripts/conformance documents).
list() { scala-cli --server=false "$RUN" -- "$@" 2>/dev/null | grep -v '^--shard ' | grep -v '^--only '; }

# ONE invocation for the unsharded listing AND every shard's listing.
#
# It used to be N+1 separate `scala-cli --server=false` calls, plus two more below: SEVEN processes
# for a repository-wide property. MEASURED: 28.2 s serverless against 5.6 s with the scala-cli
# server — but reusing the server is not available here, because the bloop daemon is host-wide and
# stopping it would break whichever sibling agent is mid-build. So the calls were collapsed into one
# PROCESS instead, never into local arithmetic: each shard's membership still comes from run.sc.
# `--shard-all` and the real `--shard i/N` path share a single `shardSlice` definition there, and
# check 5 below asserts the two agree observably.
echo "  enumerating corpus and all $N shards in one pass…"
list --list --shard-all "$N" > "$TMP/raw.txt"

# Split the sections. A missing or misspelled header must not silently yield an empty file that then
# "passes" a comparison against another empty file.
awk -v tmp="$TMP" -v n="$N" '
  /^# all$/            { out = tmp "/all.raw"; next }
  /^# shard [0-9]+\//  { split($3, a, "/"); out = tmp "/shard." a[1] ".raw"; next }
  out                  { print > out }
' "$TMP/raw.txt"

if [ ! -s "$TMP/all.raw" ]; then
  bad "no '# all' section in --shard-all output — the listing format changed, or the run died"
  note "first lines of what came back:"; sed 's/^/    /' "$TMP/raw.txt" | head -10
  exit 1
fi
LC_ALL=C sort "$TMP/all.raw" > "$TMP/all.txt"
total=$(wc -l < "$TMP/all.txt" | tr -d ' ')
if [ "$total" -lt 2 ]; then
  bad "unsharded listing has $total case(s) — the runner did not enumerate the corpus"
  note "got:"; sed 's/^/    /' "$TMP/all.txt"
  exit 1
fi
ok "unsharded corpus: $total case(s)"

: > "$TMP/union.txt"
i=0
while [ "$i" -lt "$N" ]; do
  if [ ! -f "$TMP/shard.$i.raw" ]; then
    bad "no '# shard $i/$N' section — --shard-all emitted fewer shards than asked for"
    exit 1
  fi
  LC_ALL=C sort "$TMP/shard.$i.raw" > "$TMP/shard.$i.txt"
  c=$(wc -l < "$TMP/shard.$i.txt" | tr -d ' ')
  note "shard $i/$N: $c case(s)"
  [ "$c" -eq 0 ] && bad "shard $i/$N is EMPTY — a shard that tests nothing still reports success"
  cat "$TMP/shard.$i.txt" >> "$TMP/union.txt"
  i=$(( i + 1 ))
done

# ── 1. COVERAGE: the union is exactly the corpus ─────────────────────────────
LC_ALL=C sort "$TMP/union.txt" > "$TMP/union.sorted.txt"
if diff -u "$TMP/all.txt" "$TMP/union.sorted.txt" > "$TMP/cover.diff"; then
  ok "union of $N shards == unsharded corpus ($total cases, byte-identical)"
else
  bad "union of $N shards != unsharded corpus — cases are being DROPPED or DUPLICATED"
  note "expected=<unsharded listing>  got=<union of shards>; diff:"
  sed 's/^/    /' "$TMP/cover.diff" | head -40
fi

# ── 2. DISJOINTNESS: no case runs in two shards ──────────────────────────────
dups=$(LC_ALL=C sort "$TMP/union.txt" | uniq -d)
if [ -z "$dups" ]; then
  ok "shards are disjoint (no case appears twice)"
else
  bad "these cases appear in more than one shard (wasted CI time, and a red is reported twice):"
  printf '    %s\n' $dups
fi

# ── 3. BALANCE: round-robin must not degenerate ──────────────────────────────
# Not a correctness property, but a silent regression here is what makes a matrix pointless — if one
# shard holds most of the corpus the job is as slow as before while looking parallel.
min=999999; max=0
i=0
while [ "$i" -lt "$N" ]; do
  c=$(wc -l < "$TMP/shard.$i.txt" | tr -d ' ')
  [ "$c" -lt "$min" ] && min=$c
  [ "$c" -gt "$max" ] && max=$c
  i=$(( i + 1 ))
done
if [ "$(( max - min ))" -le 1 ]; then
  ok "shards are balanced (min=$min max=$max, spread <= 1)"
else
  bad "shards are unbalanced: min=$min max=$max (expected spread <= 1 for round-robin)"
fi

# ── 4. the flag is validated, not silently ignored ───────────────────────────
if list --list --shard "9/4" >/dev/null 2>&1; then
  bad "--shard 9/4 was ACCEPTED; an out-of-range shard must fail loudly, not test nothing"
else
  ok "--shard 9/4 is rejected"
fi

# ── 5. the REAL --shard path agrees with --shard-all, and its value is not the corpus dir ─────
# Two properties in one invocation, and the first is what keeps check 1 honest. Checks 1-3 read
# `--shard-all`; if that ever computed membership differently from the `--shard i/N` a CI matrix
# actually runs, the gate would certify a partition nobody executes. run.sc shares one `shardSlice`
# between the two paths, and this asserts it observably rather than trusting the refactor.
#
# The second property is the original regression test: the old positional filter would have taken
# `0/4` as the conformance directory and quietly tested an empty corpus — a green run over nothing.
list --list --shard "0/$N" | LC_ALL=C sort > "$TMP/real0.txt"
c0=$(wc -l < "$TMP/real0.txt" | tr -d ' ')
if [ "$c0" -gt 0 ]; then
  ok "--shard value is not mistaken for the corpus directory ($c0 cases in shard 0)"
else
  bad "--shard 0/$N selected 0 cases — the value was probably parsed as the corpus dir"
fi
if diff -u "$TMP/shard.0.txt" "$TMP/real0.txt" > "$TMP/agree.diff"; then
  ok "--shard-all shard 0 == --shard 0/$N (the one-pass listing describes the real run)"
else
  bad "--shard-all disagrees with --shard 0/$N — checks 1-3 above are about a partition that the"
  note "CI matrix does not actually run. expected=<--shard-all shard 0>  got=<--shard 0/$N>; diff:"
  sed 's/^/    /' "$TMP/agree.diff" | head -30
fi

echo
[ "$fail" -eq 0 ] && { echo "✓ shard partition gate PASSED"; exit 0; }
echo "✗ shard partition gate FAILED"
exit 1
