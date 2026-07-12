#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SSC_TOOLS="$ROOT/bin/ssc-tools"
DUMPER="$ROOT/tests/tools/scljet-corpus-dump.ssc"
CORRUPT_CHECKER="$ROOT/tests/tools/scljet-corrupt-check.ssc"
FUZZ_CHECKER="$ROOT/tests/tools/scljet-fuzz-check.ssc"
FIXTURES="$ROOT/tests/fixtures/scljet/m2"
[[ -x $SSC_TOOLS && -f $DUMPER && -f $CORRUPT_CHECKER && -f $FUZZ_CHECKER && -f $FIXTURES/manifest.tsv ]] || {
  echo 'scljet-m2-corpus-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}

while IFS=$'\t' read -r id path expected _; do
  [[ $id == id ]] && continue
  actual=$(/usr/bin/shasum -a 256 "$ROOT/$path" | awk '{print $1}')
  [[ $actual == "$expected" ]] || {
    echo "fixture hash mismatch: $id" >&2
    exit 1
  }
done < "$FIXTURES/manifest.tsv"

while IFS=$'\t' read -r id path expected _; do
  [[ $id == id ]] && continue
  actual=$(/usr/bin/shasum -a 256 "$ROOT/$path" | awk '{print $1}')
  [[ $actual == "$expected" ]] || {
    echo "corrupt fixture hash mismatch: $id" >&2
    exit 1
  }
done < "$FIXTURES/corrupt-manifest.tsv"

tmp=$(mktemp -d "${TMPDIR:-/tmp}/scljet-m2-corpus.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
(
  cd "$ROOT"
  PATH=/usr/bin:/bin "$SSC_TOOLS" run --v1 "$DUMPER"
) >"$tmp/actual" 2>"$tmp/err"

[[ ! -s $tmp/err ]]
diff -u "$FIXTURES/oracle-storage.txt" "$tmp/actual"

(
  cd "$ROOT"
  PATH=/usr/bin:/bin "$SSC_TOOLS" run --v1 "$CORRUPT_CHECKER"
) >"$tmp/corrupt-actual" 2>"$tmp/corrupt-err"
[[ ! -s $tmp/corrupt-err ]]
diff -u "$FIXTURES/corrupt-errors.txt" "$tmp/corrupt-actual"

/usr/bin/python3 - "$FIXTURES/valid/page-512.db" "$tmp" <<'PY'
import pathlib, sys
source = pathlib.Path(sys.argv[1]).read_bytes()
out = pathlib.Path(sys.argv[2])
for index in range(32):
    value = bytearray(source)
    offset = (index * 73) % len(value)
    value[offset] ^= 1 << (index % 8)
    (out / f"scljet-fuzz-{index}.db").write_bytes(value)
PY
(
  cd "$tmp"
  PATH=/usr/bin:/bin "$SSC_TOOLS" run --v1 "$FUZZ_CHECKER"
) >"$tmp/fuzz-actual" 2>"$tmp/fuzz-err"
[[ ! -s $tmp/fuzz-err ]]
[[ $(cat "$tmp/fuzz-actual") == "32:30:2" ]]

echo 'PASS scljet-m2-corpus-smoke (17 valid + 5 corrupt pinned files, 595 exact lines + 32 bounded mutations)'
