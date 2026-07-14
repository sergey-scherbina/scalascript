#!/usr/bin/env bash
# Control-interoperability conformance harness — portable-VM reference runner.
#
# Runs each measurable-now probe on the portable-VM (`ssc run --bytecode`),
# diffs stdout against expected/, and prints the conformance matrix. Pending
# axes (durable capsule / cross-host / negatives) require the DurableValue codec
# + admission layer (post-X1) and are reported as PENDING, never silently green.
#
# Usage:
#   tests/interop-conformance/run.sh            # uses $SSC or ./bin/ssc
#   SSC=/path/to/ssc tests/interop-conformance/run.sh
#
# Exit code: non-zero if any measurable-now probe regresses.
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
SSC="${SSC:-$ROOT/bin/ssc}"

if [ ! -x "$SSC" ] && ! command -v "$SSC" >/dev/null 2>&1; then
  echo "error: ssc binary not found at '$SSC' (set SSC=/path/to/ssc or run 'sbt installBin')" >&2
  exit 2
fi

pass=0; fail=0; failed_axes=""
printf '%-34s %-10s %s\n' "AXIS" "STATUS" "RESULT"
printf '%-34s %-10s %s\n' "----" "------" "------"

for probe in "$DIR"/probes/*.ssc; do
  base="$(basename "$probe" .ssc)"
  axis="$(sed -n 's/^axis:[[:space:]]*//p' "$probe" | head -1)"
  exp="$DIR/expected/$base.txt"
  # portable-VM: --bytecode is the ASM tier; the default VM agrees. Cache is
  # keyed on source, so no artifact cleanup is needed between distinct probes.
  got="$(timeout 60 "$SSC" run --bytecode "$probe" 2>&1 | sed 's/\x1b\[[0-9;]*m//g')"
  want="$(cat "$exp" 2>/dev/null)"
  if [ "$got" = "$want" ]; then
    printf '%-34s %-10s %s\n' "$axis" "PASS" "$got"
    pass=$((pass+1))
  else
    printf '%-34s %-10s got=[%s] want=[%s]\n' "$axis" "FAIL" "$(echo "$got" | tr '\n' ' ' | head -c 60)" "$want"
    fail=$((fail+1)); failed_axes="$failed_axes $axis"
  fi
done

# Pending axes: enumerated so a reader sees the FULL matrix, not just the green rows.
echo
if [ -d "$DIR/pending" ]; then
  for p in "$DIR"/pending/*.pending; do
    [ -e "$p" ] || continue
    axis="$(sed -n 's/^axis:[[:space:]]*//p' "$p" | head -1)"
    st="$(sed -n 's/^status:[[:space:]]*//p' "$p" | head -1)"
    need="$(sed -n 's/^needs:[[:space:]]*//p' "$p" | head -1)"
    printf '%-40s %-16s %s\n' "${axis:-$(basename "$p")}" "${st:-PENDING}" "${need}"
  done
fi

echo
echo "portable-VM reference row: $pass measurable-now PASS, $fail FAIL.${failed_axes:+  regressed:$failed_axes}"
[ "$fail" -eq 0 ]
