#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SSC_TOOLS="$ROOT/bin/ssc-tools"
DUMPER="$ROOT/tests/tools/scljet-corpus-dump.ssc"
CORRUPT_CHECKER="$ROOT/tests/tools/scljet-corrupt-check.ssc"
TRAVERSE_CHECKER="$ROOT/tests/tools/scljet-corrupt-traverse.ssc"
FUZZ_CHECKER="$ROOT/tests/tools/scljet-fuzz-check.ssc"
FIXTURES="$ROOT/tests/fixtures/scljet/m2"
[[ -x $SSC_TOOLS && -f $DUMPER && -f $CORRUPT_CHECKER && -f $TRAVERSE_CHECKER && -f $FUZZ_CHECKER && -f $FIXTURES/manifest.tsv ]] || {
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

# Deterministic bounded fuzz corpus (identical inputs for every tier).
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

# M2d requires VM/ASM parity: the same pure `.ssc` reader must produce the exact
# reference dump and localized diagnostics on every interpreter execution tier.
#   default  — bytecode VM + fast tier + javac JIT (the tier ordinary runs use)
#   asm      — the ASM JIT backend
#   fallback — pure tree-walk (bytecode JIT and fast tier disabled)
run_tier() {
  local tier=$1 env_prefix=$2

  # 1. Byte-for-value equal reads of the whole valid corpus.
  (
    cd "$ROOT"
    env $env_prefix PATH=/usr/bin:/bin "$SSC_TOOLS" run --v1 "$DUMPER"
  ) >"$tmp/actual" 2>"$tmp/err"
  [[ ! -s $tmp/err ]] || { echo "dump stderr on tier $tier:" >&2; cat "$tmp/err" >&2; exit 1; }
  diff -u "$FIXTURES/oracle-storage.txt" "$tmp/actual" || { echo "dump diverged on tier $tier" >&2; exit 1; }

  # 2. Corrupt files fail safely with localized diagnostics.
  (
    cd "$ROOT"
    env $env_prefix PATH=/usr/bin:/bin "$SSC_TOOLS" run --v1 "$CORRUPT_CHECKER"
  ) >"$tmp/corrupt-actual" 2>"$tmp/corrupt-err"
  [[ ! -s $tmp/corrupt-err ]] || { echo "corrupt stderr on tier $tier:" >&2; cat "$tmp/corrupt-err" >&2; exit 1; }
  diff -u "$FIXTURES/corrupt-errors.txt" "$tmp/corrupt-actual" || { echo "corrupt diverged on tier $tier" >&2; exit 1; }

  # 2b. User-table overflow-chain corruptions the open-time check cannot catch:
  # they are accepted at open and fail only during forward table traversal.
  (
    cd "$ROOT"
    env $env_prefix PATH=/usr/bin:/bin "$SSC_TOOLS" run --v1 "$TRAVERSE_CHECKER"
  ) >"$tmp/traverse-actual" 2>"$tmp/traverse-err"
  [[ ! -s $tmp/traverse-err ]] || { echo "traverse stderr on tier $tier:" >&2; cat "$tmp/traverse-err" >&2; exit 1; }
  diff -u "$FIXTURES/corrupt-traversal-errors.txt" "$tmp/traverse-actual" || { echo "traverse diverged on tier $tier" >&2; exit 1; }

  # 3. Bounded fuzz mutations: 32 checked, 30 rejected, 2 legally accepted.
  (
    cd "$tmp"
    env $env_prefix PATH=/usr/bin:/bin "$SSC_TOOLS" run --v1 "$FUZZ_CHECKER"
  ) >"$tmp/fuzz-actual" 2>"$tmp/fuzz-err"
  [[ ! -s $tmp/fuzz-err ]] || { echo "fuzz stderr on tier $tier:" >&2; cat "$tmp/fuzz-err" >&2; exit 1; }
  [[ $(cat "$tmp/fuzz-actual") == "32:30:2" ]] || { echo "fuzz result changed on tier $tier: $(cat "$tmp/fuzz-actual")" >&2; exit 1; }

  echo "  tier $tier: dump + corrupt + traverse + fuzz identical to reference oracle"
}

run_tier default ""
run_tier asm "SSC_JIT_BACKEND=asm"
run_tier fallback "SSC_JIT_BYTECODE=off SSC_FASTTIER=off"

echo 'PASS scljet-m2-corpus-smoke (25 valid + 33 corrupt pinned files [30 open-time + 3 overflow-traversal], 643 exact lines + 3 traversal diagnostics + 32 bounded mutations; VM/ASM/fallback tiers identical)'
