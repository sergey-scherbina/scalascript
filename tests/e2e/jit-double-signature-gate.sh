#!/usr/bin/env bash
#
# jit-double-signature-gate — a `Double` in a user function's SIGNATURE falls off the JIT.
#
#   ./tests/e2e/jit-double-signature-gate.sh
#
# THIS GATE PINS A DEFECT and goes RED when it is fixed, on purpose.
# v1/runtime/backend/interpreter/BUGS.md `int-a-double-in-a-signature-falls-off-the-jit`.
#
# WHY A PERF GATE IS DEFENSIBLE HERE, when most are not. It asserts a RATIO between two programs
# that differ in nothing but the type, not an absolute time — so host speed, load and CPU model
# largely cancel.
#
# BUT THE FIRST VERSION OF THIS GATE WOULD HAVE LIED, and the numbers are why the design below looks
# the way it does. One measurement gave Long 0.0014 ms against Double 0.8675 ms — ~620x — and a 50x
# floor looked unmissable. Five back-to-back pairs on the same host then gave:
#
#     long      double    ratio
#     0.1854    8.22       44.3x
#     0.0366    5.63      153.8x
#     0.0010    2.27     2270.0x
#     0.0018    0.5418    301.0x
#     0.0143    0.5321     37.2x
#
# The control alone spans 185x. TWO OF THE FIVE FALL BELOW A 50x FLOOR — so a single-run gate would
# have reported "the defect is FIXED" on a defect that is still there, which is the exact false green
# a timing assertion invites and the worst direction to be wrong in.
#
# So: N pairs, and the verdict is taken from the pair whose CONTROL was fastest — the least contended
# one, since contention can only slow a run down. The floor is 10x, against a worst observed run of
# 37x and an expected ~1-3x once the JIT covers the signature; that is 3.7x of headroom below the
# worst real measurement rather than the 1.1x the first version had.
#
# THE `Long` TWIN IS THE CONTROL AND IS CHECKED AS ONE. If both programs are slow the ratio collapses
# toward 1 and would read as "the defect is fixed" — the exact false green a ratio invites. So the
# twin has its own absolute ceiling, generous enough to survive a loaded host but far below the
# Double figure, and the gate says the ratio is meaningless rather than reporting on it when the
# control is slow.
#
# IT IS THE SIGNATURE, NOT THE ARITHMETIC — the entry measured a `sum = sum + 1.0` loop with no call
# at 12 ns per iteration. So the two subjects must differ ONLY in the type, and they are generated
# from one source by substitution rather than written twice, so they cannot drift apart.
#
# `ssc-tools bench` AND NOT `ssc bench`: bench lives in the optional tools tier, and plain
# `bin/ssc bench` answers "requires the optional ScalaScript tools tier" AND EXITS 0. A gate written
# against `bin/ssc` would read that as success and pass while measuring nothing — the same trap
# `check-accepts-what-the-runtime-rejects-gate.sh` records for `check`.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
sandbox="$(mktemp -d "${TMPDIR:-/tmp}/jitdbl.XXXXXX")"
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

RATIO_FLOOR="${JIT_DOUBLE_RATIO_FLOOR:-10}"
CONTROL_CEILING_MS="${JIT_DOUBLE_CONTROL_CEILING_MS:-0.5}"
PAIRS="${JIT_DOUBLE_PAIRS:-3}"

cat > "$sandbox/lng.ssc" <<'EOF'
```scalascript
def idi(x: Long): Long = x
def workload(): Long =
  var sum = 0L
  var i = 0
  while i < 2000 do
    sum = sum + idi(1L)
    i = i + 1
  sum
```
EOF
# Generated from the Long source, never written twice: the two subjects must differ ONLY in the type.
sed -e 's/Long/Double/g' -e 's/0L/0.0/g' -e 's/1L/1.0/g' "$sandbox/lng.ssc" > "$sandbox/dbl.ssc"

bench() {  # $1 = file → prints ms, or nothing
  timeout 400 "$tools" bench --machine --warmup-time 800 --reps 8 "$1" 2>/dev/null \
    | awk '/^BENCH/ { print $3 }' | tail -1
}

# N PAIRS, KEPT AS PAIRS. Taking min(long) and min(double) independently would mix two different
# runs and could pair the quietest control with the noisiest subject, inflating the ratio. Each pair
# is measured together and the pair with the fastest CONTROL wins, because contention only ever
# slows a run down — so the fastest control is the least contended observation, not the luckiest.
lng=""; dbl=""
for _ in $(seq 1 "$PAIRS"); do
  l="$(bench "$sandbox/lng.ssc")"; d="$(bench "$sandbox/dbl.ssc")"
  [ -n "$l" ] && [ -n "$d" ] || continue
  if [ -z "$lng" ] || awk "BEGIN{exit !($l < $lng)}"; then lng="$l"; dbl="$d"; fi
done

if [ -z "$lng" ] || [ -z "$dbl" ]; then
  echo "jit-double-signature: SKIP — bench produced no BENCH line."
  echo "  tools tier absent or bench unavailable here. A SKIP, not a pass: a check that cannot"
  echo "  measure must not report OK. (long='$lng' double='$dbl')"
  exit 0
fi

read -r verdict ratio <<EOF
$(python3 - "$lng" "$dbl" "$RATIO_FLOOR" "$CONTROL_CEILING_MS" <<'PY'
import sys
lng, dbl, floor, ceil = float(sys.argv[1]), float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])
if lng <= 0:
    print("nocontrol 0"); raise SystemExit
if lng > ceil:
    print("slowcontrol %.1f" % (dbl / lng)); raise SystemExit
r = dbl / lng
print(("present " if r >= floor else "fixed ") + "%.1f" % r)
PY
)
EOF

case "$verdict" in
  nocontrol)
    echo "jit-double-signature: FAIL — the Long twin measured 0 ms; no ratio can be formed." >&2
    exit 1 ;;
  slowcontrol)
    echo "jit-double-signature: FAIL — the CONTROL is slow (${lng} ms > ${CONTROL_CEILING_MS} ms)." >&2
    echo "  The Long twin is what makes the ratio mean anything. If BOTH programs are slow the" >&2
    echo "  ratio collapses toward 1 and would read as 'the defect is fixed'. Re-run on a quieter" >&2
    echo "  host, or raise JIT_DOUBLE_CONTROL_CEILING_MS deliberately." >&2
    exit 1 ;;
  fixed)
    echo "  ✓ the Double signature is now within ${ratio}x of the Long twin — THE DEFECT IS FIXED." >&2
    echo "    That is what this gate exists to notice. Close" >&2
    echo "    v1/runtime/backend/interpreter/BUGS.md int-a-double-in-a-signature-falls-off-the-jit" >&2
    echo "    and replace this gate with one asserting the ratio STAYS low; do not lower the floor." >&2
    exit 1 ;;
esac

echo "  ok   control (Long): ${lng} ms, under the ${CONTROL_CEILING_MS} ms ceiling"
echo "  ok   defect present: Double/Long = ${ratio}x, at or above the ${RATIO_FLOOR}x floor"
echo "jit-double-signature-gate: OK (defect still present; control sound)"
