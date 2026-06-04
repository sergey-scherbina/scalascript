#!/usr/bin/env bash
#
# v2.0 Separate-Compilation Scale Benchmark
# ==========================================
#
# Runs `ssc build --incremental` over the full `std/` tree under five
# representative toggle combinations + measures cold / warm / single-edit
# incremental wall-clock time, output disk size, and "skipped vs compiled"
# counts.
#
# Output is a Markdown-formatted table on stdout, suitable for splicing
# into docs/specs/v2.0-scale-benchmark.md.
#
# Requires:  cli/target/scala-3.8.3/ssc.jar  (run `sbt cli/assembly`)
# Source set: std/  (the in-tree standard library, ~49 .ssc modules)
#
# Usage:  scripts/v2-scale-bench.sh [path-to-ssc.jar]
#
# Notes:
# - Each scenario uses a fresh artifact dir to ensure cold-run honesty.
# - Warm-run is the second invocation against the same artifact dir
#   (every module is up-to-date, so it's the pure overhead of walking +
#   stat-ing + hash-checking).
# - "Touch-one" times the rebuild after `touch`-ing the largest std module
#   (std/actors.ssc).  Mtime alone doesn't invalidate; we rewrite a single
#   trailing byte so the SHA-256 actually changes.
set -euo pipefail

JAR="${1:-cli/target/scala-3.8.3/ssc.jar}"
if [[ ! -f "$JAR" ]]; then
  echo "ssc.jar not found at $JAR — run 'sbt cli/assembly' first" >&2
  exit 1
fi
SRC_DIR="${SRC_DIR:-std}"
if [[ ! -d "$SRC_DIR" ]]; then
  echo "source dir $SRC_DIR not found" >&2
  exit 1
fi

# Largest std module — used for the "touch-one" incremental probe.
TOUCH_FILE="$SRC_DIR/actors.ssc"

# Portable millisecond wall-clock.  BSD date (macOS) has no %N; GNU date
# (Linux, or `gdate` on macOS) does.  Prefer python3 — universal + cheap.
now_ms() {
  python3 -c 'import time; print(int(time.time()*1000))'
}

# Pretty-print a duration in ms as "1234 ms" or "1.2 s".
fmt_ms() {
  local ms=$1
  if [[ $ms -ge 10000 ]]; then
    printf "%.1fs" "$(echo "scale=1; $ms/1000" | bc)"
  else
    printf "%dms" "$ms"
  fi
}

# Total bytes of an artifact dir (du -sk gives KB; multiply by 1024).
dir_bytes() {
  if [[ ! -d "$1" ]]; then echo 0; return; fi
  du -sk "$1" 2>/dev/null | awk '{print $1*1024}'
}

fmt_bytes() {
  local b=$1
  if [[ $b -ge 1048576 ]]; then
    awk -v b=$b 'BEGIN{ printf "%.1fMB", b/1048576 }'
  elif [[ $b -ge 1024 ]]; then
    awk -v b=$b 'BEGIN{ printf "%.1fKB", b/1024 }'
  else
    printf "%dB" "$b"
  fi
}

# Count "[compile] ... OK", "[compile] ... FAIL", and "[skip] ..." from
# a captured stdout dump.  `grep -c` exits 1 on no-match; `|| true` keeps
# `set -e` happy, and awk normalises whitespace.
count_ok()      { grep -E "^\s*\[compile\].*OK\s*$"   "$1" 2>/dev/null | awk 'END{print NR}'; }
count_fail()    { grep -E "^\s*\[compile\].*FAIL\s*$" "$1" 2>/dev/null | awk 'END{print NR}'; }
count_skipped() { grep -E "^\s*\[skip\]"              "$1" 2>/dev/null | awk 'END{print NR}'; }

run_scenario() {
  local label="$1"; shift
  local art_dir="$(mktemp -d -t ssc-bench-XXXXXX)"
  local cold_log="$art_dir/.cold.log"
  local warm_log="$art_dir/.warm.log"
  local edit_log="$art_dir/.edit.log"

  # Cold run.
  local t0=$(now_ms)
  java -jar "$JAR" build --incremental "$SRC_DIR" --artifact-dir "$art_dir" "$@" \
    >"$cold_log" 2>&1 || true
  local t1=$(now_ms)
  local cold_ms=$((t1 - t0))
  local cold_ok=$(count_ok "$cold_log")
  local cold_fail=$(count_fail "$cold_log")
  local cold_skipped=$(count_skipped "$cold_log")

  # Warm run (everything up-to-date).
  t0=$(now_ms)
  java -jar "$JAR" build --incremental "$SRC_DIR" --artifact-dir "$art_dir" "$@" \
    >"$warm_log" 2>&1 || true
  t1=$(now_ms)
  local warm_ms=$((t1 - t0))
  local warm_ok=$(count_ok "$warm_log")
  local warm_fail=$(count_fail "$warm_log")
  local warm_skipped=$(count_skipped "$warm_log")

  # Touch-one incremental.  Append a harmless trailing newline so the
  # SHA-256 changes; then revert.
  local original
  original=$(mktemp)
  cp "$TOUCH_FILE" "$original"
  printf '\n' >>"$TOUCH_FILE"
  t0=$(now_ms)
  java -jar "$JAR" build --incremental "$SRC_DIR" --artifact-dir "$art_dir" "$@" \
    >"$edit_log" 2>&1 || true
  t1=$(now_ms)
  local edit_ms=$((t1 - t0))
  local edit_ok=$(count_ok "$edit_log")
  local edit_fail=$(count_fail "$edit_log")
  local edit_skipped=$(count_skipped "$edit_log")
  cp "$original" "$TOUCH_FILE"
  rm "$original"

  local size_bytes=$(dir_bytes "$art_dir")

  # Compact "ok/skip/fail" triples.
  printf "| %-30s | %6s | %6s | %6s | %2d/%2d/%2d | %2d/%2d/%2d | %2d/%2d/%2d | %s |\n" \
    "$label" \
    "$(fmt_ms $cold_ms)" \
    "$(fmt_ms $warm_ms)" \
    "$(fmt_ms $edit_ms)" \
    "$cold_ok"  "0"            "$cold_fail" \
    "$warm_ok"  "$warm_skipped" "$warm_fail" \
    "$edit_ok"  "$edit_skipped" "$edit_fail" \
    "$(fmt_bytes $size_bytes)"

  rm -rf "$art_dir"
}

echo "# v2.0 Scale Benchmark — std/ ($(find "$SRC_DIR" -name '*.ssc' | wc -l | tr -d ' ') modules)"
echo ""
echo "Each row runs three invocations: cold (empty artifact dir), warm"
echo "(everything up-to-date), and touch-one (largest module's SHA changed)."
echo "Counts are 'compiled / skipped' as reported on stdout."
echo ""
echo "| Scenario                       | Cold   | Warm   | Touch1 | Cold ok/sk/fail | Warm ok/sk/fail | Edit ok/sk/fail | Artifacts |"
echo "|--------------------------------|--------|--------|--------|-----------------|-----------------|-----------------|-----------|"

run_scenario "default (scim + scir only)"
run_scenario "--backend jvm (source)"          --backend jvm
run_scenario "--backend jvm + section=cumul"   --backend jvm --section-cache
run_scenario "--backend jvm + section=iface"   --backend jvm --section-cache=interface
run_scenario "--backend js (source)"           --backend js

echo ""
echo "Hardware: $(uname -srm), JVM $(java -version 2>&1 | head -1 | sed 's/^.*"\(.*\)".*/\1/')"
echo "Date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
