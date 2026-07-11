#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-runtime-taxonomy.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

parity="$sandbox/parity.tsv"
sentinel="$sandbox/sentinel.tsv"
manifest="$sandbox/manifest.tsv"
limits="$sandbox/limits.tsv"
report="$sandbox/report.tsv"

printf '%s\n' \
  $'file\tcategory\tvm_rc\tbytecode_rc\tdetail' \
  $'runtime.ssc\tboth-fail\t1\t1\tmissing runtime' \
  $'optional.ssc\tboth-fail\t1\t1\tmissing optional provider' \
  $'tool.ssc\tboth-fail\t1\t1\tbackend syntax' \
  $'ok.ssc\tidentical\t0\t0\t' > "$parity"

printf '%s\n' \
  $'file\treadiness_category\tparity_category\treason' \
  $'tool.ssc\tbackend\tboth-fail\treviewed backend surface' > "$sentinel"

printf '%s\n' \
  $'file\tcategory\tblocker\towner\treason' \
  $'runtime.ssc\tlanguage-runtime\tyes\tcore\tportable runtime gap' \
  $'optional.ssc\toptional-provider\tno\tpdf\topt-in provider gap' \
  $'tool.ssc\ttools-backend\tno\twasm\treviewed backend surface' > "$manifest"

printf '%s\n' \
  $'category\tmax_count' \
  $'example-contract\t0' \
  $'language-runtime\t1' \
  $'optional-provider\t1' \
  $'standard-provider\t0' \
  $'tools-backend\t1' \
  $'blocker-total\t1' \
  $'total\t3' > "$limits"

run_gate() {
  "$ROOT/scripts/v21-runtime-taxonomy" \
    --parity-report "$1" --sentinel-report "$2" --manifest "$3" \
    --limits "$4" --report "$report"
}

run_gate "$parity" "$sentinel" "$manifest" "$limits" > "$sandbox/success.out"
grep -F $'language-runtime\t1' "$sandbox/success.out" >/dev/null
grep -F $'blocker-total\t1' "$sandbox/success.out" >/dev/null
[[ $(awk 'END { print NR }' "$report") -eq 4 ]]

expect_fail() {
  local label=$1 pattern=$2 parity_file=$3 sentinel_file=$4 manifest_file=$5 limits_file=$6
  set +e
  run_gate "$parity_file" "$sentinel_file" "$manifest_file" "$limits_file" \
    >"$sandbox/$label.out" 2>"$sandbox/$label.err"
  local rc=$?
  set -e
  [[ $rc -ne 0 ]]
  grep -F "$pattern" "$sandbox/$label.err" >/dev/null
}

cp "$parity" "$sandbox/unknown-parity.tsv"
printf '%s\n' $'unknown.ssc\tboth-fail\t1\t1\tnew failure' >> "$sandbox/unknown-parity.tsv"
expect_fail unknown 'unclassified both-fail row: unknown.ssc' \
  "$sandbox/unknown-parity.tsv" "$sentinel" "$manifest" "$limits"

cp "$manifest" "$sandbox/duplicate-manifest.tsv"
printf '%s\n' $'runtime.ssc\tlanguage-runtime\tyes\tcore\tduplicate' >> "$sandbox/duplicate-manifest.tsv"
expect_fail duplicate 'duplicate manifest row: runtime.ssc' \
  "$parity" "$sentinel" "$sandbox/duplicate-manifest.tsv" "$limits"

cp "$manifest" "$sandbox/stale-manifest.tsv"
printf '%s\n' $'stale.ssc\tstandard-provider\tyes\tcore\tstale' >> "$sandbox/stale-manifest.tsv"
expect_fail stale 'stale or reclassified manifest row: stale.ssc' \
  "$parity" "$sentinel" "$sandbox/stale-manifest.tsv" "$limits"

sed $'s/optional-provider\tno/optional-provider\tyes/' "$manifest" > "$sandbox/blocker-manifest.tsv"
expect_fail blocker 'invalid blocker decision: optional.ssc=optional-provider/yes' \
  "$parity" "$sentinel" "$sandbox/blocker-manifest.tsv" "$limits"

sed $'s/language-runtime\t1/language-runtime\t0/' "$limits" > "$sandbox/growth-limits.tsv"
expect_fail growth 'category growth: language-runtime=1 > 0' \
  "$parity" "$sentinel" "$manifest" "$sandbox/growth-limits.tsv"

echo 'v2.1 runtime taxonomy smoke: PASS'
