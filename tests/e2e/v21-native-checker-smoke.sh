#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
CHECK_RUN="$ROOT/bin/lib/native-front/tower/bin/ssc1-check-run.ssc0"
CP="$ROOT/bin/lib/jars/*"

[[ -f "$CHECK_RUN" ]] || {
  echo 'v21-native-checker-smoke: staged checker missing (run scripts/sbtc "installBin")' >&2
  exit 2
}

run_case() {
  local file=$1 want_rc=$2 want_prefix=$3 out rc
  set +e
  out=$(PATH=/usr/bin:/bin "$ROOT/scripts/run-with-timeout" 30 \
    java -Xss512m -cp "$CP" ssc.cli run "$CHECK_RUN" "$file" 2>&1)
  rc=$?
  set -e
  if [[ $rc -ne $want_rc || $out != "$want_prefix"* ]]; then
    printf 'FAIL %s\n  rc:   %s (want %s)\n  out:  %s\n  want: %s*\n' \
      "${file##*/}" "$rc" "$want_rc" "$out" "$want_prefix" >&2
    exit 1
  fi
  printf 'ok   %-36s => %s\n' "${file##*/}" "$want_prefix"
}

FIX="$ROOT/tests/fixtures/v21-native"
run_case "$FIX/checker-valid.ssc"             0 'OK'
run_case "$FIX/checker-invalid-numeric.ssc"   1 'TYPEERR:'
run_case "$FIX/checker-invalid-repeat.ssc"    1 'TYPEERR:'
run_case "$FIX/checker-invalid-bool.ssc"      1 'TYPEERR:'
run_case "$FIX/checker-invalid-condition.ssc" 1 'TYPEERR:'

echo 'PASS v21-native-checker-smoke'
