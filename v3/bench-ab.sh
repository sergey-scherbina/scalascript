#!/usr/bin/env bash
# A/B the v3 executor against itself with a MATCHED CONTROL, for the question this repo keeps
# needing answered: did a change to the executor move the clock, on a host shared with other agents?
#
#   v3/bench-ab.sh                      # default: ON vs --no-optimize (the J1d shape)
#   OFF_ARGS=--no-specialize v3/bench-ab.sh
#   PAIRS=25 v3/bench-ab.sh arith-loop
#
# WHY IT IS SHAPED LIKE THIS, all three parts paid for by a measurement that went wrong first:
#
# 1. EVERY PAIR MEASURES BOTH the control (ON vs ON) and the experiment (ON vs OFF), back to back.
#    The first version ran the whole control for a workload and THEN the whole experiment; the
#    control saw load 7 and the experiment saw load 13, so it could not speak for the experiment's
#    conditions, which is the only job a control has.
#
# 2. ORDER IS ROTATED inside each pair, so a monotone drift in load cancels across pairs instead of
#    being charged to whichever arm happens to run second.
#
# 3. THE STATISTIC IS THE SIGN TEST over pairs, printed beside the control's. A mean or a median
#    RATIO is not usable here: measured on this host under load, the CONTROL — identical code both
#    sides — produced per-pair ratios from 0.548 to 2.096 and absolute times from 56 to 352 ms on
#    one workload. Any single ratio from such a run is noise. One load spike moves a mean and cannot
#    move more than one pair's sign.
#
# READ THE CONTROL FIRST, ALWAYS. If the control is far from n/2, the host was not steady enough and
# the experiment column means nothing however good it looks. If the control is near n/2 and the
# experiment is not, that is a result. In a genuinely quiet window (load ~6) this harness resolved
# the control to within 3 % — so the often-quoted "~2x floor" is a property of a LOADED host, not of
# the method. The blocker on measuring a 15-20 % effect here is finding a quiet window, not the
# instrument.
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2
PAIRS=${PAIRS:-15}
OFF_ARGS=${OFF_ARGS:---no-optimize}
WORKLOADS=${*:-"arith-loop nested-loop list-fold recursion-fib"}

[ -x v3/ssc3 ] || { echo "bench-ab: no v3/ssc3 — build it first" >&2; exit 2; }

one() { v3/ssc3 bench $2 "bench/corpus/$1.ssc" 2>/dev/null | awk -F': ' '/BENCH_MS/{print $2}'; }
lt()  { awk -v a="$1" -v b="$2" 'BEGIN{exit !(a<b)}'; }

echo "bench-ab: ON vs '$OFF_ARGS', $PAIRS pairs, control interleaved"
for wl in $WORKLOADS; do
  [ -f "bench/corpus/$wl.ssc" ] || { echo "=== $wl — no such workload, skipped"; continue; }
  cw=0; ew=0; n=0
  echo "=== $wl   load at start: $(uptime | sed 's/.*averages: //')"
  for i in $(seq 1 "$PAIRS"); do
    if (( i % 2 == 1 )); then
      c1=$(one "$wl" ""); c2=$(one "$wl" ""); e1=$(one "$wl" ""); e2=$(one "$wl" "$OFF_ARGS")
    else
      c2=$(one "$wl" ""); c1=$(one "$wl" ""); e2=$(one "$wl" "$OFF_ARGS"); e1=$(one "$wl" "")
    fi
    for v in "$c1" "$c2" "$e1" "$e2"; do
      [ -n "$v" ] || { echo "  EMPTY READING at pair $i — aborting $wl"; continue 2; }
    done
    n=$((n+1))
    lt "$c1" "$c2" && cw=$((cw+1))
    lt "$e1" "$e2" && ew=$((ew+1))
    awk -v c1="$c1" -v c2="$c2" -v e1="$e1" -v e2="$e2" -v i="$i" \
      'BEGIN{printf "  pair %-2d  ctl %7.2f/%7.2f = %.3f    exp ON %7.2f  OFF %7.2f = %.3f\n", i, c1, c2, c1/c2, e1, e2, e1/e2}'
  done
  echo "  --> CONTROL (ON vs ON):  $cw of $n     EXPERIMENT (ON vs OFF): $ew of $n"
  echo "      read the control first: far from $((n/2)) means the host was not steady and the"
  echo "      experiment column means nothing however good it looks."
done
echo "load at end: $(uptime | sed 's/.*averages: //')"
