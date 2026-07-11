#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
FIXTURE="$ROOT/tests/fixtures/v21-native/md-interpolator.ssc"
CONTENT="$ROOT/examples/content.ssc"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-native-md.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

expected=$'[Hello, Ada!\n  nested 3\n\nTail]\nsingle-Ada\nlocal:ok\n[]'
clean_path=/usr/bin:/bin

for mode in vm asm; do
  native_args=(run --native)
  standard_args=(run)
  [[ $mode == asm ]] && native_args+=(--bytecode) && standard_args+=(--bytecode)

  PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" "${native_args[@]}" \
    "$FIXTURE" >"$sandbox/full-$mode.out"
  [[ $(cat "$sandbox/full-$mode.out") == "$expected" ]]

  PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" \
    "${standard_args[@]}" "$FIXTURE" >"$sandbox/standard-$mode.out"
  cmp -s "$sandbox/full-$mode.out" "$sandbox/standard-$mode.out"

  PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" \
    "${standard_args[@]}" "$CONTENT" >"$sandbox/content-$mode.out"
done

cmp -s "$sandbox/content-vm.out" "$sandbox/content-asm.out"
grep -Fx 'Name : Alice' "$sandbox/content-vm.out" >/dev/null
grep -Fx 'Language : ScalaScript' "$sandbox/content-vm.out" >/dev/null

PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm "$FIXTURE" \
  -o "$sandbox/md.jar" >/dev/null
PATH="$clean_path" SSC_NO_CDS=1 java -jar "$sandbox/md.jar" \
  >"$sandbox/artifact.out"
cmp -s "$sandbox/full-vm.out" "$sandbox/artifact.out"

PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm "$CONTENT" \
  -o "$sandbox/content.jar" >/dev/null
PATH="$clean_path" SSC_NO_CDS=1 java -jar "$sandbox/content.jar" \
  >"$sandbox/content-artifact.out"
cmp -s "$sandbox/content-vm.out" "$sandbox/content-artifact.out"

echo 'PASS v21-native-md-interpolator-smoke'
