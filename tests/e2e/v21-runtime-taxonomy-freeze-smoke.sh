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
for n in 1 2 3 4 5 6 7; do
  printf 'optional%s.ssc\toptional-provider\tno\toptional\treviewed\tboth-fail\n' "$n" >> "$taxonomy"
done
for n in 1 2 3 4 5 6; do
  printf 'tool%s.ssc\ttools-backend\tno\ttool\treviewed\tboth-fail\n' "$n" >> "$taxonomy"
done

run_gate() {
  "$ROOT/scripts/v21-runtime-taxonomy-freeze" \
    --taxonomy-report "$1" --expected "$2" --report "$report"
}
run_gate "$taxonomy" "$expected" > "$sandbox/pass.out"
grep -F $'total\t13' "$report" >/dev/null

expect_fail() {
  local label=$1 pattern=$2 taxonomy_file=$3 expected_file=$4
  set +e
  run_gate "$taxonomy_file" "$expected_file" >"$sandbox/$label.out" 2>"$sandbox/$label.err"
  local rc=$?
  set -e
  [[ $rc -ne 0 ]]
  grep -F "$pattern" "$sandbox/$label.err" >/dev/null
}

sed '/optional7[.]ssc/d' "$taxonomy" > "$sandbox/shrink.tsv"
expect_fail shrink 'freeze drift: optional-provider=6 expected 7' "$sandbox/shrink.tsv" "$expected"

cp "$taxonomy" "$sandbox/grow.tsv"
printf 'tool7.ssc\ttools-backend\tno\ttool\treviewed\tboth-fail\n' >> "$sandbox/grow.tsv"
expect_fail grow 'freeze drift: tools-backend=7 expected 6' "$sandbox/grow.tsv" "$expected"

sed 's/optional1.ssc\toptional-provider\tno/optional1.ssc\toptional-provider\tyes/' \
  "$taxonomy" > "$sandbox/blocker.tsv"
expect_fail blocker 'freeze drift: blocker-total=1 expected 0' "$sandbox/blocker.tsv" "$expected"

cp "$expected" "$sandbox/duplicate.tsv"
printf 'total\t13\n' >> "$sandbox/duplicate.tsv"
expect_fail duplicate 'duplicate freeze metric: total' "$taxonomy" "$sandbox/duplicate.tsv"

echo 'v2.1 runtime taxonomy freeze smoke: PASS'
