#!/usr/bin/env bash
# scripts/runtime-bench.sh — Interpreter vs JvmGen vs JsGen runtime benchmark
#
# Runs every workload in bench/corpus/ through three backends and reports
# wall-clock median times in a markdown table.
#
# Usage:
#   scripts/runtime-bench.sh                      # all backends, all workloads
#   scripts/runtime-bench.sh --no-jvm             # skip JVM backend (slow cold)
#   scripts/runtime-bench.sh --no-js              # skip JS backend
#   scripts/runtime-bench.sh arith-loop fib       # specific workloads only
#   scripts/runtime-bench.sh --reps 5             # override rep count (default 7)
#   scripts/runtime-bench.sh --warmup 1           # override warmup count (default 2)
#
# Requirements:
#   - ssc.jar built:   sbt cli/assembly
#   - node on PATH:    brew install node
#   - scala-cli:       brew install scala-cli  (for --jvm)
#
# Output: markdown table to stdout; BASELINE_RUNTIME.md updated when
# --baseline flag is passed.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/v1/tools/cli/target/scala-3.8.3/ssc.jar"
CORPUS="$ROOT/bench/corpus"
BASELINE_OUT="$ROOT/bench/BASELINE_RUNTIME.md"

# ── defaults ────────────────────────────────────────────────────────────────
WARMUP=2
REPS=7
RUN_INTERP=true
RUN_JVM=true
RUN_JS=true
WRITE_BASELINE=false
FILTER_NAMES=()

# ── arg parsing ─────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-jvm)      RUN_JVM=false; shift ;;
    --no-js)       RUN_JS=false; shift ;;
    --no-interp)   RUN_INTERP=false; shift ;;
    --reps)        REPS="$2"; shift 2 ;;
    --warmup)      WARMUP="$2"; shift 2 ;;
    --baseline)    WRITE_BASELINE=true; shift ;;
    --*)           echo "Unknown flag: $1" >&2; exit 1 ;;
    *)             FILTER_NAMES+=("$1"); shift ;;
  esac
done

# ── sanity checks ────────────────────────────────────────────────────────────
if [[ ! -f "$JAR" ]]; then
  echo "ssc.jar not found at $JAR — run 'sbt cli/assembly' first" >&2
  exit 1
fi
if [[ ! -d "$CORPUS" ]]; then
  echo "bench/corpus/ not found at $CORPUS" >&2
  exit 1
fi
if $RUN_JS && ! command -v node &>/dev/null; then
  echo "[warn] node not found — skipping JS backend" >&2
  RUN_JS=false
fi
if $RUN_JVM && ! command -v scala-cli &>/dev/null; then
  echo "[warn] scala-cli not found — skipping JVM backend" >&2
  RUN_JVM=false
fi

SSC="java -jar $JAR"

# ── timing helpers ────────────────────────────────────────────────────────────
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

# Run a command, swallow stdout, print stderr only on failure.
# Returns: wall-clock ms via stdout.
time_cmd() {
  local t0 t1 rc=0
  t0=$(now_ms)
  "$@" >/dev/null 2>/tmp/bench-stderr || rc=$?
  t1=$(now_ms)
  if [[ $rc -ne 0 ]]; then
    cat /tmp/bench-stderr >&2
    echo "FAIL"
    return
  fi
  echo $((t1 - t0))
}

# Median of a space-separated list of numbers.
median() {
  python3 -c "
import sys, statistics
vals = [int(x) for x in sys.argv[1:] if x != 'FAIL']
if not vals:
    print('ERR')
else:
    print(int(statistics.median(vals)))
" "$@"
}

# ── workload discovery ───────────────────────────────────────────────────────
WORKLOADS=()
while IFS= read -r -d '' f; do
  name="$(basename "$f" .ssc)"
  if [[ ${#FILTER_NAMES[@]} -eq 0 ]] || printf '%s\n' "${FILTER_NAMES[@]}" | grep -qx "$name"; then
    WORKLOADS+=("$f")
  fi
done < <(find "$CORPUS" -name '*.ssc' -print0 | sort -z)

if [[ ${#WORKLOADS[@]} -eq 0 ]]; then
  echo "No workloads found in $CORPUS" >&2; exit 1
fi

echo ""
echo "ScalaScript runtime benchmark — interpreter vs JvmGen vs JsGen"
echo "$(printf '=%.0s' {1..65})"
echo "Corpus: $(printf '%s ' "${WORKLOADS[@]}" | xargs -I{} basename {} .ssc)"
echo "Warmup: ${WARMUP}, Reps: ${REPS}"
echo ""

# ── pre-warm artifact caches ─────────────────────────────────────────────────
# Run each backend once before timing so artifact caches are hot.
# (JvmGen caches the generated Scala; JsGen caches the JS bundle.)
echo "Pre-warming artifact caches..."
for f in "${WORKLOADS[@]}"; do
  name="$(basename "$f" .ssc)"
  printf "  %-22s" "$name"
  if $RUN_INTERP; then
    $SSC run "$f" >/dev/null 2>&1 || true
    printf " interp✓"
  fi
  if $RUN_JVM; then
    $SSC run-jvm "$f" >/dev/null 2>&1 || true
    printf " jvm✓"
  fi
  if $RUN_JS; then
    $SSC run-js "$f" >/dev/null 2>&1 || true
    printf " js✓"
  fi
  echo ""
done
echo ""

# ── measurement ───────────────────────────────────────────────────────────────
declare -A RESULTS_INTERP RESULTS_JVM RESULTS_JS

for f in "${WORKLOADS[@]}"; do
  name="$(basename "$f" .ssc)"
  printf "  %-22s" "$name"

  if $RUN_INTERP; then
    times=()
    for _ in $(seq 1 $WARMUP); do $SSC run "$f" >/dev/null 2>&1 || true; done
    for _ in $(seq 1 $REPS); do
      t=$(time_cmd $SSC run "$f")
      times+=("$t")
    done
    RESULTS_INTERP["$name"]=$(median "${times[@]}")
    printf " interp=%sms" "${RESULTS_INTERP[$name]}"
  fi

  if $RUN_JVM; then
    times=()
    for _ in $(seq 1 $WARMUP); do $SSC run-jvm "$f" >/dev/null 2>&1 || true; done
    for _ in $(seq 1 $REPS); do
      t=$(time_cmd $SSC run-jvm "$f")
      times+=("$t")
    done
    RESULTS_JVM["$name"]=$(median "${times[@]}")
    printf " jvm=%sms" "${RESULTS_JVM[$name]}"
  fi

  if $RUN_JS; then
    times=()
    for _ in $(seq 1 $WARMUP); do $SSC run-js "$f" >/dev/null 2>&1 || true; done
    for _ in $(seq 1 $REPS); do
      t=$(time_cmd $SSC run-js "$f")
      times+=("$t")
    done
    RESULTS_JS["$name"]=$(median "${times[@]}")
    printf " js=%sms" "${RESULTS_JS[$name]}"
  fi

  echo ""
done

# ── table ─────────────────────────────────────────────────────────────────────
build_table() {
  local header="| Workload |"
  local sep="|---|"
  $RUN_INTERP && { header+=" Interpreter ms |"; sep+="---:|"; }
  $RUN_JVM    && { header+=" JvmGen ms |";     sep+="---:|"; }
  $RUN_JS     && { header+=" JsGen ms |";      sep+="---:|"; }
  $RUN_INTERP && $RUN_JVM && { header+=" JVM/Interp |"; sep+="---:|"; }
  $RUN_INTERP && $RUN_JS  && { header+=" JS/Interp |";  sep+="---:|"; }

  echo ""
  echo "$header"
  echo "$sep"

  for f in "${WORKLOADS[@]}"; do
    local name
    name="$(basename "$f" .ssc)"
    local row="| \`$name\` |"
    local ti=${RESULTS_INTERP[$name]:-ERR}
    local tj=${RESULTS_JVM[$name]:-ERR}
    local ts=${RESULTS_JS[$name]:-ERR}

    $RUN_INTERP && row+=" $ti |"
    $RUN_JVM    && row+=" $tj |"
    $RUN_JS     && row+=" $ts |"

    if $RUN_INTERP && $RUN_JVM && [[ "$ti" =~ ^[0-9]+$ ]] && [[ "$tj" =~ ^[0-9]+$ ]]; then
      row+=" $(python3 -c "print(f'{$tj/$ti:.2f}x')") |"
    elif $RUN_INTERP && $RUN_JVM; then
      row+=" ERR |"
    fi

    if $RUN_INTERP && $RUN_JS && [[ "$ti" =~ ^[0-9]+$ ]] && [[ "$ts" =~ ^[0-9]+$ ]]; then
      row+=" $(python3 -c "print(f'{$ts/$ti:.2f}x')") |"
    elif $RUN_INTERP && $RUN_JS; then
      row+=" ERR |"
    fi

    echo "$row"
  done
}

TABLE=$(build_table)
echo "$TABLE"
echo ""
echo "Hardware: $(uname -srm), JVM $(java -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')"
echo "Date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "Notes: warmup=${WARMUP} reps=${REPS}; JVM/JS times include ssc.jar startup"
echo "       JvmGen warms the .scjvm artifact cache; cold JVM compile >> warm times"

if $WRITE_BASELINE; then
  {
    echo "# Runtime Benchmark Baseline"
    echo ""
    echo "Generated $(date -u +%Y-%m-%dT%H:%M:%SZ). Warmup=${WARMUP}, reps=${REPS}."
    echo "All times include ssc.jar process startup. JVM/JS artifact caches are warm."
    echo ""
    echo "$TABLE"
    echo ""
    echo "Hardware: $(uname -srm), JVM $(java -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/')"
  } >"$BASELINE_OUT"
  echo ""
  echo "Baseline written to bench/BASELINE_RUNTIME.md"
fi
