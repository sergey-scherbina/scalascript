#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SSC_TOOLS="$ROOT/bin/ssc-tools"
EXAMPLE="$ROOT/examples/scljet-readonly.ssc"
[[ -x $SSC_TOOLS && -f $EXAMPLE ]] || {
  echo 'scljet-readonly-jvm-vfs-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}

tmp=$(mktemp -d "${TMPDIR:-/tmp}/scljet-readonly-vfs.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

expected=$'512\nList(116)\ntrue\nSome(1)\nList(integer:-2, text:List(72, 105), blob:2)\ntrue\nok'
(
  cd "$tmp"
  PATH=/usr/bin:/bin "$SSC_TOOLS" run --v1 "$EXAMPLE"
) >"$tmp/out" 2>"$tmp/err"

[[ $(cat "$tmp/out") == "$expected" ]]
[[ ! -s $tmp/err ]]
[[ ! -e $tmp/scljet-readonly-example.db ]]

echo 'PASS scljet-readonly-jvm-vfs-smoke (schema + row + public close, real JVM plugin)'
