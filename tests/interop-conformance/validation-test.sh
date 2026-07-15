#!/usr/bin/env bash
# Negative regression cases for the catalog validator. All mutations are made
# in a temporary copy so this script is safe to run from any checkout.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/interop-catalog-validation.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

pass=0

fresh_case() {
  name="$1"
  case_dir="$tmp/$name"
  mkdir -p "$case_dir"
  cp -R "$DIR/." "$case_dir/"
  printf '%s' "$case_dir"
}

expect_rejected() {
  name="$1"
  case_dir="$2"
  if "$case_dir/run.sh" --validate >"$tmp/$name.out" 2>&1; then
    echo "FAIL $name: malformed catalog was accepted" >&2
    cat "$tmp/$name.out" >&2
    exit 1
  fi
  grep -q "catalog validation: FAIL" "$tmp/$name.out" || {
    echo "FAIL $name: rejection did not come from catalog validation" >&2
    cat "$tmp/$name.out" >&2
    exit 1
  }
  echo "PASS $name"
  pass=$((pass + 1))
}

"$DIR/run.sh" --validate >/dev/null

case_dir="$(fresh_case duplicate-vector)"
duplicate="$(sed -n '2p' "$case_dir/vectors.tsv")"
printf '%s\n' "$duplicate" >> "$case_dir/vectors.tsv"
expect_rejected duplicate-vector "$case_dir"

case_dir="$(fresh_case omitted-vector)"
awk -F '\t' '$1 != "10"' "$case_dir/vectors.tsv" \
  > "$case_dir/vectors.tsv.next"
mv "$case_dir/vectors.tsv.next" "$case_dir/vectors.tsv"
rm "$case_dir/pending/10-raw-foreignv-reject.pending"
expect_rejected omitted-vector "$case_dir"

case_dir="$(fresh_case duplicate-lane)"
duplicate="$(sed -n '2p' "$case_dir/lanes.tsv")"
printf '%s\n' "$duplicate" >> "$case_dir/lanes.tsv"
expect_rejected duplicate-lane "$case_dir"

case_dir="$(fresh_case swapped-lane-adapter)"
awk -F '\t' 'BEGIN { OFS = "\t" } $1 == "portable-vm" { $2 = "ssc-asm" } { print }' \
  "$case_dir/lanes.tsv" > "$case_dir/lanes.tsv.next"
mv "$case_dir/lanes.tsv.next" "$case_dir/lanes.tsv"
expect_rejected swapped-lane-adapter "$case_dir"

case_dir="$(fresh_case empty-ready-lane)"
awk -F '\t' 'BEGIN { OFS = "\t" } $1 == "portable-vm" { $4 = "unused-capability" } { print }' \
  "$case_dir/lanes.tsv" > "$case_dir/lanes.tsv.next"
mv "$case_dir/lanes.tsv.next" "$case_dir/lanes.tsv"
expect_rejected empty-ready-lane "$case_dir"

case_dir="$(fresh_case missing-probe)"
rm "$case_dir/probes/01-one-shot-resume.ssc"
expect_rejected missing-probe "$case_dir"

case_dir="$(fresh_case orphan-expected)"
cp "$case_dir/expected/01-one-shot-resume.txt" \
  "$case_dir/expected/99-orphan.txt"
expect_rejected orphan-expected "$case_dir"

case_dir="$(fresh_case mismatched-frontmatter)"
sed 's/^axis: one-shot-resume$/axis: wrong-axis/' \
  "$case_dir/probes/01-one-shot-resume.ssc" \
  > "$case_dir/probes/01-one-shot-resume.ssc.next"
mv "$case_dir/probes/01-one-shot-resume.ssc.next" \
  "$case_dir/probes/01-one-shot-resume.ssc"
expect_rejected mismatched-frontmatter "$case_dir"

case_dir="$(fresh_case missing-pending-record)"
rm "$case_dir/pending/10-raw-foreignv-reject.pending"
expect_rejected missing-pending-record "$case_dir"

echo "catalog validator negative cases: $pass PASS"
