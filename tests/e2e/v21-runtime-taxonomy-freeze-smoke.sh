#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-runtime-freeze.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

expected="$sandbox/expected.tsv"
taxonomy="$sandbox/taxonomy.tsv"
report="$sandbox/report.tsv"
cp "$ROOT/tests/fixtures/v21-runtime-taxonomy/release-freeze.tsv" "$expected"
printf 'file\tcategory\tblocker\towner\treason\tparity_detail\n' > "$taxonomy"

run_gate() {
  "$ROOT/scripts/v21-runtime-taxonomy-freeze" \
    --taxonomy-report "$1" --expected "$2" --report "$report"
}
run_gate "$taxonomy" "$expected" > "$sandbox/pass.out"
grep -F $'total\t0' "$report" >/dev/null

expect_fail() {
  local label=$1 pattern=$2 taxonomy_file=$3 expected_file=$4
  set +e
  run_gate "$taxonomy_file" "$expected_file" >"$sandbox/$label.out" 2>"$sandbox/$label.err"
  local rc=$?
  set -e
  [[ $rc -ne 0 ]]
  grep -F "$pattern" "$sandbox/$label.err" >/dev/null
}

cp "$taxonomy" "$sandbox/grow.tsv"
printf 'optional.ssc\toptional-provider\tno\tprovider\tstale\tboth-fail\n' >> "$sandbox/grow.tsv"
expect_fail grow 'freeze drift: optional-provider=1 expected 0' "$sandbox/grow.tsv" "$expected"

cp "$taxonomy" "$sandbox/blocker.tsv"
printf 'runtime.ssc\tlanguage-runtime\tyes\tcore\tregression\tboth-fail\n' >> "$sandbox/blocker.tsv"
expect_fail blocker 'freeze drift: blocker-total=1 expected 0' "$sandbox/blocker.tsv" "$expected"

cp "$expected" "$sandbox/duplicate.tsv"
printf 'total\t0\n' >> "$sandbox/duplicate.tsv"
expect_fail duplicate 'duplicate freeze metric: total' "$taxonomy" "$sandbox/duplicate.tsv"

echo 'v2.1 runtime taxonomy freeze smoke: PASS'
