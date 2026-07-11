#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
FIXTURE="$ROOT/tests/fixtures/v21-native/dynamic-tostring.ssc"
EXAMPLE="$ROOT/examples/content-linked-namespaces.ssc"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-native-dynamic-tostring.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

expected=$'7\n1234\n12.5\ntext'
example_expected=$'Minor units (integer count of the smallest unit, e.g. cents)\n1234'

PATH=/usr/bin:/bin "$ROOT/bin/ssc-standard" run --native "$FIXTURE" >"$tmp/vm.out"
PATH=/usr/bin:/bin "$ROOT/bin/ssc-standard" run --native --bytecode "$FIXTURE" >"$tmp/asm.out"
PATH=/usr/bin:/bin "$ROOT/bin/ssc" build-jvm "$FIXTURE" -o "$tmp/app.jar" >/dev/null
PATH=/usr/bin:/bin java -jar "$tmp/app.jar" >"$tmp/artifact.out"

for output in "$tmp/vm.out" "$tmp/asm.out" "$tmp/artifact.out"; do
  [[ $(cat "$output") == "$expected" ]] || {
    echo "v21-native-dynamic-tostring-smoke: unexpected output from $output" >&2
    cat "$output" >&2
    exit 1
  }
done

PATH=/usr/bin:/bin "$ROOT/bin/ssc-standard" run --native "$EXAMPLE" >"$tmp/example-vm.out"
PATH=/usr/bin:/bin "$ROOT/bin/ssc-standard" run --native --bytecode "$EXAMPLE" >"$tmp/example-asm.out"
PATH=/usr/bin:/bin "$ROOT/bin/ssc" build-jvm "$EXAMPLE" -o "$tmp/example.jar" >/dev/null
PATH=/usr/bin:/bin java -jar "$tmp/example.jar" >"$tmp/example-artifact.out"

for output in "$tmp/example-vm.out" "$tmp/example-asm.out" "$tmp/example-artifact.out"; do
  [[ $(cat "$output") == "$example_expected" ]] || {
    echo "v21-native-dynamic-tostring-smoke: unexpected linked-content output from $output" >&2
    cat "$output" >&2
    exit 1
  }
done

echo 'PASS v21-native-dynamic-tostring-smoke'
