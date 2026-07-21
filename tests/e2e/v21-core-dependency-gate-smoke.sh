#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
report=$(mktemp "${TMPDIR:-/tmp}/ssc-v21-core-dependency-gate.XXXXXX")
strict_report=$(mktemp "${TMPDIR:-/tmp}/ssc-v21-core-dependency-gate-strict.XXXXXX")
trap 'rm -f "$report" "$strict_report"' EXIT HUP INT TERM

"$ROOT/scripts/v21-core-dependency-gate" --self-test
"$ROOT/scripts/v21-core-dependency-gate" --report "$report"

head -1 "$report" | grep -Fx $'layer\troot\tdependency\tparser_codec_edge\tpolicy' >/dev/null
grep -F $'seed\tscalascript-v2-core_' "$report" >/dev/null
grep -F $'pure-core\tscalascript-v2-native-plugin-spi_' "$report" >/dev/null
grep -F $'backend-plugin\tscalascript-v2-jvm-bytecode_' "$report" >/dev/null
grep -F $'feature-plugin\tscalascript-scljet-vfs-host_' "$report" >/dev/null
grep -F $'feature-plugin\tscalascript-v2-native-scljet-vfs-plugin_' "$report" >/dev/null
grep -F $'feature-plugin\tscalascript-v2-native-json-plugin_' "$report" >/dev/null
grep -F $'tools/compat\t(migration-full-layout)' "$report" >/dev/null || \
  test -d "$ROOT/bin/lib/standard/jars"
if grep -F $'unclassified\t' "$report" >/dev/null; then
  echo 'v21-core-dependency-gate-smoke: closed standard layout has unclassified JARs' >&2
  exit 1
fi
if grep -E $'^(seed|pure-core)\t[^\t]+\t[^\t]+\t1\t' "$report" >/dev/null; then
  echo 'v21-core-dependency-gate-smoke: seed/pure core contains a parser/codec edge' >&2
  exit 1
fi

"$ROOT/scripts/v21-core-dependency-gate" --strict-parsers \
  --report "$strict_report" >/dev/null
if awk -F '\t' '$4 == 1 { bad = 1 } END { exit bad }' "$strict_report"; then
  :
else
  echo 'v21-core-dependency-gate-smoke: strict standard layout retains a parser/codec edge' >&2
  exit 1
fi

echo 'PASS v21-core-dependency-gate-smoke'
