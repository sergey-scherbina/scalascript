#!/usr/bin/env bash
# v2/scripts/bench.sh — benchmark v2 against the bench/corpus/ programs
# Usage: cd v2 && ./scripts/bench.sh [pattern]
#
# Builds a v2 assembly JAR once, then for each corpus program:
#   1. Extracts the scalascript block from the .ssc file
#   2. Converts Scala 3 indentation to brace style (indent2braces.py)
#   3. Adds a main() wrapper if none exists
#   4. Compiles through ssc1c -> Core IR -> runs on v2 VM
#   5. Times N=20 cold runs, reports median ms/op
#
# Output: a markdown table comparable to bench/BASELINE.md

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
V2_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CORPUS_DIR="$(cd "$V2_DIR/../bench/corpus" && pwd)"
INDENT_PY="$SCRIPT_DIR/indent2braces.py"
REPS="${BENCH_REPS:-20}"
PATTERN="${1:-}"

# ── build JAR ────────────────────────────────────────────────────────────────
JAR="$(mktemp /tmp/v2-bench-XXXXXX.jar)"
trap 'rm -f "$JAR"' EXIT
echo "building v2 jar..." >&2
scala-cli --power package "$V2_DIR/src" -o "$JAR" -f --assembly --server=false -q \
  >/dev/null 2>/dev/null
echo "jar ready: $JAR" >&2

# ── helpers ──────────────────────────────────────────────────────────────────

# Extract the first ```scalascript ... ``` block from a .ssc file
extract_block() {
  awk '/```scalascript/{found=1; next} found && /```/{exit} found{print}' "$1"
}

# Check if src defines def main
has_main() { echo "$1" | grep -q 'def main'; }

# Check if workload takes a Long seed argument
needs_seed() { echo "$1" | grep -qE 'def workload\s*\(\s*seed\s*:'; }

# Check if workload returns Unit (side-effecting, no println wrapper)
returns_unit() { echo "$1" | grep -qE 'def workload\(\)\s*:\s*Unit'; }

# Compile + run one program; print output
run_prog() {
  local src="$1"
  echo "$src" \
    | java -jar "$JAR" run bin/ssc1c.ssc0 /dev/stdin 2>/dev/null \
    | java -jar "$JAR" run-ir /dev/stdin 2>/dev/null
}

# Median of N numbers (one per line)
median() {
  python3 -c "
import sys
nums = sorted(float(x) for x in sys.stdin if x.strip())
n = len(nums)
if n == 0: print('0')
elif n % 2: print(nums[n//2])
else: print((nums[n//2-1]+nums[n//2])/2)
"
}

# Time one run in ms
time_ms() {
  local src="$1"
  python3 -c "
import subprocess, time, sys
src = sys.argv[1]
t0 = time.perf_counter()
subprocess.run(
  ['java', '-jar', sys.argv[2], 'run', 'bin/ssc1c.ssc0', '/dev/stdin'],
  input=src.encode(), capture_output=True
)
# note: this times compile+run together; for a fair comparison use precompiled IR
t1 = time.perf_counter()
print((t1-t0)*1000)
" "$src" "$JAR"
}

# Better: compile once, then time only the run-ir step N times
bench_prog() {
  local src="$1"
  local name="$2"

  # Compile to IR once
  local ir
  ir=$(echo "$src" | java -jar "$JAR" run bin/ssc1c.ssc0 /dev/stdin 2>/dev/null) || {
    echo "SKIP $name (compile error)"
    return
  }

  # Time the run-ir step N times
  local times
  times=$(for _ in $(seq 1 "$REPS"); do
    python3 -c "
import subprocess, time, sys
ir = sys.argv[1]
jar = sys.argv[2]
t0 = time.perf_counter()
subprocess.run(['java','-jar',jar,'run-ir','/dev/stdin'], input=ir.encode(), capture_output=True)
t1 = time.perf_counter()
print((t1-t0)*1000)
" "$ir" "$JAR"
  done)

  local med
  med=$(echo "$times" | median)
  printf "ok  %-30s %7.2f ms\n" "$name" "$med"
}

# ── main loop ─────────────────────────────────────────────────────────────────
echo ""
echo "| Program                        | v2 ms/op |"
echo "|--------------------------------|---------:|"

for sscfile in "$CORPUS_DIR"/*.ssc; do
  prog="$(basename "$sscfile" .ssc)"

  # Apply optional filter
  if [ -n "$PATTERN" ] && ! echo "$prog" | grep -q "$PATTERN"; then
    continue
  fi

  # Extract block
  src=$(extract_block "$sscfile")
  if [ -z "$src" ]; then
    printf "| %-30s | %-8s |\n" "$prog" "SKIP(empty)"
    continue
  fi

  # Convert Scala 3 indentation to brace style
  src=$(echo "$src" | python3 "$INDENT_PY" 2>/dev/null) || {
    printf "| %-30s | %-8s |\n" "$prog" "SKIP(indent)"
    continue
  }

  # Add main wrapper (if not already present)
  if ! has_main "$src"; then
    if needs_seed "$src"; then
      src="$src
def main(): Unit = { val _ = workload(42L); () }"
    elif returns_unit "$src"; then
      src="$src
def main(): Unit = { workload(); () }"
    else
      src="$src
def main(): Unit = { val _ = workload(); () }"
    fi
  fi

  # Compile test (check for unsupported features)
  if ! echo "$src" | java -jar "$JAR" run bin/ssc1c.ssc0 /dev/stdin >/dev/null 2>/dev/null; then
    printf "| %-30s | %-8s |\n" "$prog" "SKIP(ssc1c)"
    continue
  fi

  # Compile to IR
  ir=$(echo "$src" | java -jar "$JAR" run bin/ssc1c.ssc0 /dev/stdin 2>/dev/null)

  # Time run-ir N times
  times=""
  for _ in $(seq 1 "$REPS"); do
    t=$(python3 -c "
import subprocess, time, sys
ir=open('/dev/stdin').read()
jar=sys.argv[1]
t0=time.perf_counter()
subprocess.run(['java','-jar',jar,'run-ir','/dev/stdin'],input=ir.encode(),capture_output=True)
print((time.perf_counter()-t0)*1000)
" "$JAR" <<< "$ir")
    times="$times$t
"
  done

  med=$(echo "$times" | python3 -c "
import sys
nums = sorted(float(x) for x in sys.stdin if x.strip())
n = len(nums)
if n==0: print('N/A')
elif n%2: print(f'{nums[n//2]:.2f}')
else: print(f'{(nums[n//2-1]+nums[n//2])/2:.2f}')
")
  printf "| %-30s | %8s |\n" "$prog" "${med} ms"
done

echo ""
echo "Reps=$REPS, JAR=$JAR"
