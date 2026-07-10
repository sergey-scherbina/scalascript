#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
report=$(mktemp "${TMPDIR:-/tmp}/ssc-v21-core-dependency-gate.XXXXXX")
strict_report=$(mktemp "${TMPDIR:-/tmp}/ssc-v21-core-dependency-gate-strict.XXXXXX")
strict_err=$(mktemp "${TMPDIR:-/tmp}/ssc-v21-core-dependency-gate-strict-err.XXXXXX")
trap 'rm -f "$report" "$strict_report" "$strict_err"' EXIT HUP INT TERM

"$ROOT/scripts/v21-core-dependency-gate" --self-test
"$ROOT/scripts/v21-core-dependency-gate" --report "$report"

head -1 "$report" | grep -Fx $'layer\troot\tdependency\tparser_codec_edge\tpolicy' >/dev/null
grep -F $'seed\tscalascript-v2-core_' "$report" >/dev/null
grep -F $'pure-core\tscalascript-v2-native-plugin-spi_' "$report" >/dev/null
grep -F $'backend-plugin\tscalascript-v2-jvm-bytecode_' "$report" >/dev/null
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

set +e
"$ROOT/scripts/v21-core-dependency-gate" --strict-parsers \
  --report "$strict_report" >/dev/null 2>"$strict_err"
strict_rc=$?
set -e
if [[ $strict_rc -ne 1 ]]; then
  echo "v21-core-dependency-gate-smoke: strict parser gate returned $strict_rc, expected 1" >&2
  cat "$strict_err" >&2
  exit 1
fi
if grep -F 'feature-plugin root scalascript-v2-native-json-plugin_' "$strict_err" >/dev/null; then
  echo 'v21-core-dependency-gate-smoke: native JSON provider still owns an external codec' >&2
  exit 1
fi
grep -F 'sql-plugin dependency ujson_3-' "$strict_err" >/dev/null
grep -F 'sql-plugin dependency upickle-core_3-' "$strict_err" >/dev/null
grep -F 'sql-plugin dependency upack_3-' "$strict_err" >/dev/null
grep -F 'sql-plugin dependency scalascript-wire-core_' "$strict_err" >/dev/null
if awk -F '\t' '$4 == 1 && $1 != "feature-plugin" { bad = 1 } END { exit bad }' "$strict_report"; then
  :
else
  echo 'v21-core-dependency-gate-smoke: strict parser edge escaped plugin ownership' >&2
  exit 1
fi

echo 'PASS v21-core-dependency-gate-smoke'
