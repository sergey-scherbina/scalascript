#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SSC_TOOLS="$ROOT/bin/ssc-tools"
EXAMPLE="$ROOT/examples/scljet-jvm-vfs.ssc"
[[ -x $SSC_TOOLS && -f $EXAMPLE ]] || {
  echo 'v21-explicit-scljet-jvm-vfs-tools-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-explicit-scljet-vfs.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

expected=$'ok\nok\nok\n5\nok\nList(0, 0, 10, 20, 30)\nok\nok\nok\nok\nok'
(
  cd "$tmp"
  PATH=/usr/bin:/bin "$SSC_TOOLS" run --v1 "$EXAMPLE"
) >"$tmp/out" 2>"$tmp/err"

[[ $(cat "$tmp/out") == "$expected" ]]
[[ ! -s $tmp/err ]]
[[ ! -e $tmp/scljet-jvm-vfs-example.db ]]

echo 'PASS v21-explicit-scljet-jvm-vfs-tools-smoke (1 exact row, v1 JVM host plugin)'
