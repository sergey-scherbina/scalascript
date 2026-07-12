#!/usr/bin/env bash
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
TOOLS="$ROOT/bin/ssc-tools"
[[ -x $TOOLS ]] || { echo 'v21-explicit-quoted-tools-smoke: run installBin first' >&2; exit 2; }

tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-explicit-quoted.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

"$TOOLS" run --v1 "$ROOT/examples/quoted-macro-constfold.ssc" >"$tmp/constfold.out"
"$TOOLS" run --v1 "$ROOT/examples/quoted-macro-interpreter.ssc" >"$tmp/interpreter.out"
[[ $(cat "$tmp/constfold.out") == $'literal: 7\nliteral: 41' ]]
[[ $(cat "$tmp/interpreter.out") == $'42\nliteral: 7\nx' ]]

echo 'PASS v21-explicit-quoted-tools-smoke (2 exact rows)'
