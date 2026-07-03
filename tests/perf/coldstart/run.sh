#!/usr/bin/env bash
# Cold-start measurement for the `ssc` CLI — the unmeasured perf axis that
# `scripts/bench wall` deliberately excludes (it reports work-time only).
#
# Measures wall-clock time-to-completion of a FRESH `ssc run hello.ssc` process
# (JVM boot + classload + parse + first eval + teardown) over N runs, dropping
# the worst, reporting the median. Also captures peak RSS of the process.
#
# Pure bash + the JVM launcher — NO scala-cli/bloop, so it can't hang the way the
# cross-backend property test does.
#
# Usage:
#   tests/perf/coldstart/run.sh [jar=<path>] [runs=N] [warmup=K] [extra ssc args...]
#
#   jar=...   path to the launchable ssc.jar (default: auto-find cli assembly)
#   runs=N    measured runs (default 10)
#   warmup=K  warmup runs to prime the OS file cache (default 2, not counted)
#
# Output: a table of per-mode median ms + peak RSS MB, and a machine-readable
#   COLDSTART_MS / COLDSTART_RSS_MB line for CI/regression capture.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
PROG="$HERE/hello.ssc"

JAR=""
RUNS=10
WARMUP=2
EXTRA=()
for arg in "$@"; do
  case "$arg" in
    jar=*)    JAR="${arg#jar=}" ;;
    runs=*)   RUNS="${arg#runs=}" ;;
    warmup=*) WARMUP="${arg#warmup=}" ;;
    *)        EXTRA+=("$arg") ;;
  esac
done

if [[ -z "$JAR" ]]; then
  JAR="$(find "$ROOT/v1/tools/cli/target" -name 'ssc.jar' 2>/dev/null | head -1 || true)"
fi
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "coldstart: no ssc.jar found. Build it first:  sbt cli/assembly" >&2
  echo "  (or pass jar=<path>)" >&2
  exit 1
fi

# millisecond wall clock — portable (date +%s%N is GNU-only; use python3 fallback).
now_ms() {
  if date +%s%N 2>/dev/null | grep -qvE 'N$'; then date +%s%N | cut -b1-13
  else python3 -c 'import time; print(int(time.time()*1000))'; fi
}

# CDS archive for the "with CDS" mode (mirrors what bin/ssc ships).
CDS_JSA="$(mktemp -u /tmp/ssc-coldstart-XXXXXX.jsa)"
CDS_ARGS=(-XX:+IgnoreUnrecognizedVMOptions -XX:+AutoCreateSharedArchive
          -XX:SharedArchiveFile="$CDS_JSA" -Xlog:cds=off -Xlog:cds+dynamic=off)

# Run `ssc run hello.ssc` once with the given extra JVM args; echo "<ms> <peakRSS_kb>".
# Peak RSS via /usr/bin/time -l (macOS) or -v (GNU); falls back to ms only.
measure_once() {
  local t0 t1 rss timefile
  timefile="$(mktemp)"
  t0="$(now_ms)"
  /usr/bin/time -l java "$@" -cp "$JAR" scalascript.cli.ssc run "$PROG" "${EXTRA[@]}" >/dev/null 2>"$timefile" || true
  t1="$(now_ms)"
  rss="$(awk '/maximum resident set size/ {print $1}' "$timefile" 2>/dev/null | head -1)"
  rm -f "$timefile"
  if [[ -n "$rss" ]]; then echo "$(( t1 - t0 )) $(( rss / 1024 ))"; else echo "$(( t1 - t0 )) 0"; fi
}

# Measure a mode: warmups then RUNS measured; prints median ms + peak RSS MB,
# and sets MED_MS / PEAK_RSS_MB.
median() { printf '%s\n' "$@" | sort -n | awk '{a[NR]=$1} END{print (NR%2)?a[(NR+1)/2]:int((a[NR/2]+a[NR/2+1])/2)}'; }
measure_mode() {
  local label="$1"; shift
  local i; for ((i=0; i<WARMUP; i++)); do measure_once "$@" >/dev/null; done
  local ms_list=() rss_list=() ms rss
  for ((i=0; i<RUNS; i++)); do read -r ms rss < <(measure_once "$@"); ms_list+=("$ms"); rss_list+=("$rss"); done
  MED_MS="$(median "${ms_list[@]}")"
  PEAK_RSS_MB="$(( $(printf '%s\n' "${rss_list[@]}" | sort -n | tail -1) / 1024 ))"
  printf "  %-22s median %5d ms · peak RSS %4d MB\n" "$label" "$MED_MS" "$PEAK_RSS_MB"
}

echo "cold-start: java -cp $(basename "$JAR") scalascript.cli.ssc run hello.ssc"
echo "  jar: $JAR ($(( $(wc -c <"$JAR") / 1024 / 1024 )) MB)"
echo "  java: $(java -version 2>&1 | grep -i version | head -1)"
echo "  runs: $RUNS (+$WARMUP warmup), median of measured"
echo ""

measure_mode "baseline (no CDS)"
base_ms="$MED_MS"
measure_mode "AppCDS (as bin/ssc)" "${CDS_ARGS[@]}"
cds_ms="$MED_MS"; cds_rss="$PEAK_RSS_MB"
rm -f "$CDS_JSA"

echo ""
pct=$(( base_ms > 0 ? (base_ms - cds_ms) * 100 / base_ms : 0 ))
echo "  ── baseline ${base_ms} ms → AppCDS ${cds_ms} ms (−${pct}%) · peak RSS ${cds_rss} MB ──"
echo "COLDSTART_MS: ${cds_ms}"
echo "COLDSTART_BASELINE_MS: ${base_ms}"
echo "COLDSTART_RSS_MB: ${cds_rss}"
