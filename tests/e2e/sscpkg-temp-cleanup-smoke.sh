#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/sscpkg-cleanup.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
mkdir -p "$sandbox/java-tmp"

if [[ ! -x "$ROOT/bin/ssc" ]]; then
  echo 'sscpkg-temp-cleanup-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
fi

JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$sandbox/java-tmp" SSC_NO_CDS=1 \
  "$ROOT/bin/ssc" run "$ROOT/examples/hello.ssc" \
  >"$sandbox/stdout" 2>"$sandbox/stderr"

grep -F 'Hello, World!' "$sandbox/stdout" >/dev/null

leaked=$(find "$sandbox/java-tmp" -mindepth 1 -maxdepth 1 -name 'sscpkg-*' -print -quit)
if [[ -n "$leaked" ]]; then
  echo "sscpkg temp tree leaked after CLI exit: $leaked" >&2
  exit 1
fi

echo 'sscpkg temp cleanup smoke: PASS'
