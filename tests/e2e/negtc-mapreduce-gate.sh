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

# ── the CLI ci.yml actually types, parsed ────────────────────────────────────
#
# WHY THIS EXISTS. The equality below proves the map/reduce SEMANTICS and says nothing about whether
# the workflow can invoke them. On run 30649090567 all four shards were green, the merge produced
# 214 of 214 rows, and the reduce job then died in 0 s on `usage:` — `--report` was accepted only as
# argument ONE and ci.yml passed it last. Twenty-three minutes of correct work discarded by an
# argument-order rule that no message mentioned.
#
# The invocations are READ OUT OF ci.yml rather than restated here. A copy would have been green:
# I would have copied the order I had already written, which was the broken one.
CI_YML="$ROOT/.github/workflows/ci.yml"
# A throwaway repo holding a COPY of the gate, so the probes below can never reach real work.
PROBE="$TMP/probe"
mkdir -p "$PROBE/tests/e2e"
git -C "$PROBE" init -q 2>/dev/null
cp "$ROOT/tests/e2e/v21-negative-toolchain-release-gate.sh" "$PROBE/tests/e2e/"
chmod +x "$PROBE/tests/e2e/v21-negative-toolchain-release-gate.sh"
if [ -f "$CI_YML" ]; then
  # Every `v21-negative-toolchain-release-gate.sh …` command in the workflow, line continuations
  # folded, `${{ matrix.shard }}` expanded to 0. Empty is a failure: a silent zero-invocation match
  # would make this check vacuous, which is the failure this whole file exists to refuse.
  invocations="$(tr '\n' '\r' < "$CI_YML" \
    | sed 's/\\\r[[:space:]]*/ /g' | tr '\r' '\n' \
    | grep -o 'tests/e2e/v21-negative-toolchain-release-gate\.sh[^|&;]*' \
    | sed 's/\${{ matrix\.shard }}/0/g')"
  n_inv=$(printf '%s\n' "$invocations" | grep -c . || true)
  if [ "$n_inv" -lt 2 ]; then
    bad "found $n_inv gate invocation(s) in ci.yml — expected the map and the reduce; the extraction broke"
  else
    ok "read $n_inv gate invocation(s) out of ci.yml"
    # A `while read` on the RIGHT of a pipe runs in a SUBSHELL, so every `bad` in it would set
    # `fail=1` in a copy and this gate would report the failures and then exit 0. Redirected instead.
    while IFS= read -r inv; do
      [ -n "$inv" ] || continue
      # THE INPUTS MUST EXIST, and this is the whole difficulty. The gate checks `--reduce`'s inputs
      # BEFORE it checks for unrecognised arguments, so a probe with absent inputs exits 2 at
      # "input missing or empty" and never reaches the parse verdict at all. Written that way first,
      # this check passed against the very script whose argument handling had just discarded a
      # 23-minute CI run — green, and proving nothing. Materialise every path the invocation names,
      # so the only thing left to refuse is the arguments.
      # shellcheck disable=SC2086
      set -- $inv
      while [ $# -gt 0 ]; do
        case $1 in
          --native-in|--parity-in)
            mkdir -p "$PROBE/$(dirname "$2")" && printf 'file\tcategory\nx.ssc\tidentical\n' > "$PROBE/$2"; shift 2 ;;
          --native-out|--parity-out|--report)
            mkdir -p "$PROBE/$(dirname "$2")"; shift 2 ;;
          *) shift ;;
        esac
      done
      # Run the COPY in $PROBE, never the real script in this worktree. The gate resolves its own
      # ROOT from `git rev-parse` on its location, so the real one would find this worktree's built
      # `bin/ssc`, sail past the launcher check and start a 50-minute release gate from inside a
      # unit test. The copy's ROOT is an empty throwaway repo with no launcher, so a PARSED command
      # stops immediately at `run scripts/sbtc "installBin" first` — an unparsed one still says
      # `unrecognised argument` / `usage:`. Two distinct messages, neither of which does any work.
      out=$(cd "$PROBE" && eval "${inv/#tests\/e2e\//$PROBE/tests/e2e/}" 2>&1 </dev/null || true)
      case "$out" in
        *"unrecognised argument"*|*"usage:"*)
          bad "ci.yml invocation is rejected by the gate's own parser:"
          printf '    %s\n' "$inv" | cut -c1-140
          printf '%s\n' "$out" | head -4 | sed 's/^/      /' ;;
        *) ok "ci.yml invocation parses: $(printf '%s' "$inv" | sed 's/.*release-gate\.sh //' | cut -c1-58)" ;;
      esac
    done <<EOF
$invocations
EOF
  fi
else
  bad "ci.yml not found at $CI_YML"
fi

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
