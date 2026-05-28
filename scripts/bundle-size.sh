#!/usr/bin/env bash
# scripts/bundle-size.sh — report generated JS and JVM bundle sizes per corpus file.
#
# Usage:
#   ./scripts/bundle-size.sh
#   ./scripts/bundle-size.sh arith-loop hello-world
#
# Requires:
#   ssc    — ScalaScript CLI (bin/ssc or on PATH or SSC env var)
#   gzip   — for compressed-size reporting
#   wc     — standard POSIX
#
# Output: markdown table to stdout + appends a date-stamped entry to bench/BUNDLE_SIZES.md
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORPUS_DIR="$ROOT/bench/corpus"
TMP_OUT="$ROOT/bench/.bundle-tmp"
BUNDLE_LOG="$ROOT/bench/BUNDLE_SIZES.md"
SSC="${SSC:-${ROOT}/bin/ssc}"

if ! command -v "$SSC" &>/dev/null && ! [ -f "$SSC" ]; then
  echo "[ERROR] ssc not found. Build with 'sbt cli/stage' or set SSC env var." >&2
  exit 1
fi

mkdir -p "$TMP_OUT"

FILTER=("$@")

# ── helpers ──────────────────────────────────────────────────────────────────

bytes_gz() {
  gzip -c "$1" | wc -c | tr -d ' '
}

bytes_raw() {
  wc -c < "$1" | tr -d ' '
}

# ── header ───────────────────────────────────────────────────────────────────

echo ""
echo "| Corpus file | JS raw (B) | JS gz (B) | JVM raw (B) | JVM gz (B) |"
echo "|---|---:|---:|---:|---:|"

declare -A JS_RAW JS_GZ JVM_RAW JVM_GZ NAMES

for ssc_file in "$CORPUS_DIR"/*.ssc; do
  name="$(basename "$ssc_file" .ssc)"

  # Apply filter if args given
  if [ "${#FILTER[@]}" -gt 0 ]; then
    match=0
    for f in "${FILTER[@]}"; do [ "$f" = "$name" ] && match=1; done
    [ "$match" -eq 0 ] && continue
  fi

  js_out="$TMP_OUT/$name.js"
  jvm_out="$TMP_OUT/$name.sc"

  # Generate JS
  "$SSC" compile --target js  "$ssc_file" -o "$js_out"  2>/dev/null || touch "$js_out"
  # Generate JVM (Scala source)
  "$SSC" compile --target jvm "$ssc_file" -o "$jvm_out" 2>/dev/null || touch "$jvm_out"

  JS_RAW[$name]="$(bytes_raw "$js_out")"
  JS_GZ[$name]="$(bytes_gz "$js_out")"
  JVM_RAW[$name]="$(bytes_raw "$jvm_out")"
  JVM_GZ[$name]="$(bytes_gz "$jvm_out")"
  NAMES[$name]=1

  echo "| \`$name\` | ${JS_RAW[$name]} | ${JS_GZ[$name]} | ${JVM_RAW[$name]} | ${JVM_GZ[$name]} |"
done

# ── append to log ─────────────────────────────────────────────────────────────

DATE="$(date +%Y-%m-%d)"
{
  echo ""
  echo "## $DATE"
  echo ""
  echo "| Corpus file | JS raw (B) | JS gz (B) | JVM raw (B) | JVM gz (B) |"
  echo "|---|---:|---:|---:|---:|"
  for name in "${!NAMES[@]}"; do
    echo "| \`$name\` | ${JS_RAW[$name]} | ${JS_GZ[$name]} | ${JVM_RAW[$name]} | ${JVM_GZ[$name]} |"
  done
} >> "$BUNDLE_LOG"

echo ""
echo "Results appended to bench/BUNDLE_SIZES.md"

# ── cleanup ───────────────────────────────────────────────────────────────────
rm -rf "$TMP_OUT"
