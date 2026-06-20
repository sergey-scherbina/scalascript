#!/usr/bin/env bash
# Steady-state server memory + GC measurement — real-workload-perf (b)+(c), the
# long-running-server axis neither `scripts/bench wall` (work-time) nor
# tests/perf/coldstart (process startup) covers.
#
# Boots a real `ssc` HTTP server (examples/health-defaults.ssc on the JVM
# interpreter), drives sustained load, and samples RSS over the run — reporting
# the steady-state footprint, the start→end drift (a leak signal), and the GC
# pause count/time under load. Pure bash + the JVM launcher (no scala-cli/bloop).
#
# Usage:
#   sbt cli/assembly                      # once — builds .../ssc.jar
#   tests/perf/serverrss/run.sh [jar=<path>] [port=N] [secs=N] [conc=N] [interval=N]
#     secs=N      load duration, default 30
#     conc=N      concurrent request loops, default 4
#     interval=N  RSS sample interval seconds, default 2
#
# Output: a table + machine-readable SERVERRSS_* tail for regression capture.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
EXAMPLE="$ROOT/examples/health-defaults.ssc"

JAR=""; PORT=8769; SECS=30; CONC=4; INTERVAL=2
for arg in "$@"; do
  case "$arg" in
    jar=*)      JAR="${arg#jar=}" ;;
    port=*)     PORT="${arg#port=}" ;;
    secs=*)     SECS="${arg#secs=}" ;;
    conc=*)     CONC="${arg#conc=}" ;;
    interval=*) INTERVAL="${arg#interval=}" ;;
  esac
done
[ -n "$JAR" ] || JAR="$(find "$ROOT/tools/cli/target" -name 'ssc.jar' 2>/dev/null | head -1 || true)"
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "serverrss: no ssc.jar found. Build it:  sbt cli/assembly  (or pass jar=<path>)" >&2
  exit 1
fi

# PID-based temp path (BSD `mktemp` is finicky about a suffix after the X's).
GCLOG="${TMPDIR:-/tmp}/ssc-serverrss-gc-$$.log"
rm -f "$GCLOG"
SRVPID=""
cleanup() {
  [ -n "$SRVPID" ] && kill -9 "$SRVPID" 2>/dev/null
  pkill -9 -f 'health-defaults\.ssc' 2>/dev/null
  lsof -ti :"$PORT" 2>/dev/null | xargs -r kill -9 2>/dev/null
  rm -f "$GCLOG"
}
trap cleanup EXIT

lsof -ti :"$PORT" 2>/dev/null | xargs -r kill -9 2>/dev/null; sleep 1

echo "server steady-state: java -cp $(basename "$JAR") run health-defaults.ssc on :$PORT"
echo "  load: ${SECS}s, ${CONC} concurrent loops, RSS sampled every ${INTERVAL}s"

# Boot with a GC log (count pauses + total pause time under load). Bounded heap so
# RSS drift is meaningful and not just lazy heap growth toward a 12 GB -Xmx.
unset JDK_JAVA_OPTIONS
java -Xmx512m -Xlog:gc:file="$GCLOG":time,level,tags \
  -cp "$JAR" scalascript.cli.ssc run "$EXAMPLE" >/tmp/ssc-serverrss-boot.log 2>&1 &
SRVPID=$!

deadline=$(( $(date +%s) + 60 ))
up=no
while [ "$(date +%s)" -lt "$deadline" ]; do
  curl -sS -o /dev/null -m 1 "http://localhost:$PORT/_health" 2>/dev/null && { up=yes; break; }
  sleep 1
done
if [ "$up" != yes ]; then
  echo "  [FAIL] server did not start in 60s; boot log:"; tail -8 /tmp/ssc-serverrss-boot.log; exit 1
fi

rss_mb() { ps -o rss= -p "$SRVPID" 2>/dev/null | awk '{print int($1/1024)}'; }
start_rss="$(rss_mb)"
echo "  started · RSS ${start_rss} MB · /_health $(curl -sS -m 3 http://localhost:$PORT/_health)"

# Background load: CONC loops hammering /_health for SECS seconds. Track their PIDs
# so we wait ONLY on them later — bare `wait` would also wait on the server (started
# with &), which runs forever.
load_until=$(( $(date +%s) + SECS ))
loadpids=()
for _ in $(seq 1 "$CONC"); do
  ( while [ "$(date +%s)" -lt "$load_until" ]; do
      curl -sS -o /dev/null -m 2 "http://localhost:$PORT/_health" 2>/dev/null || true
    done ) &
  loadpids+=("$!")
done

# Sample RSS during the load window.
samples=()
while [ "$(date +%s)" -lt "$load_until" ]; do
  r="$(rss_mb)"; [ -n "$r" ] && { samples+=("$r"); printf "  t+%2ds  RSS %4d MB\n" "$(( SECS - (load_until - $(date +%s)) ))" "$r"; }
  sleep "$INTERVAL"
done
wait "${loadpids[@]}" 2>/dev/null   # let the load loops finish (NOT the server)

end_rss="$(rss_mb)"; [ -n "$end_rss" ] && samples+=("$end_rss")
peak="$(printf '%s\n' "${samples[@]}" | sort -n | tail -1)"
delta=$(( end_rss - start_rss ))

# GC summary from the log (Pause lines = stop-the-world collections).
gc_pauses=$(grep -cE 'Pause ' "$GCLOG" 2>/dev/null || echo 0)
gc_ms=$(grep -oE '[0-9]+\.[0-9]+ms' "$GCLOG" 2>/dev/null | awk -F m '{s+=$1} END{printf "%d", s}')
[ -z "$gc_ms" ] && gc_ms=0

echo ""
pct=$(( start_rss > 0 ? (delta * 100) / start_rss : 0 ))
verdict="STABLE"; [ "$pct" -ge 20 ] && verdict="GROWING (possible leak — investigate)"
echo "  ── start ${start_rss} MB → end ${end_rss} MB (Δ ${delta} MB, ${pct}%) · peak ${peak} MB · GC ${gc_pauses} pauses / ${gc_ms} ms ──"
echo "  verdict: ${verdict}"
echo "SERVERRSS_START_MB: ${start_rss}"
echo "SERVERRSS_END_MB: ${end_rss}"
echo "SERVERRSS_PEAK_MB: ${peak}"
echo "SERVERRSS_DELTA_PCT: ${pct}"
echo "SERVERRSS_GC_PAUSES: ${gc_pauses}"
echo "SERVERRSS_GC_MS: ${gc_ms}"
