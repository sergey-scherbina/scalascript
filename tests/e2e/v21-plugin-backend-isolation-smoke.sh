#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
JARS="$ROOT/bin/lib/standard/jars"
fixture="$ROOT/tests/conformance/v2-self-hosted-markdown-core.ssc"
sql_fixture="$ROOT/tests/fixtures/v21-native/sql-provider.ssc"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/ssc-v21-plugin-isolation.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

"$ROOT/scripts/v21-core-dependency-gate" --strict-parsers \
  --report "$tmp/dependencies.tsv" >/dev/null

if find "$JARS" -maxdepth 1 -type f \( \
    -name 'ujson_*.jar' -o -name 'upickle-core_*.jar' -o \
    -name 'upack_*.jar' -o -name 'geny_*.jar' -o \
    -name 'scalascript-wire-core_*.jar' \) | grep . >/dev/null; then
  echo 'closed standard layout retains the optional SQL wire/parser family' >&2
  exit 1
fi

for parser in json-core yaml-core markdown-core; do
  source="$ROOT/v1/runtime/std/$parser.ssc"
  if grep -En '^[[:space:]]*extern[[:space:]]+def|java[.]util[.]regex|matchPrefix' \
      "$source" >/dev/null; then
    echo "pure parser uses a host intrinsic/regex route: $parser" >&2
    exit 1
  fi
done

PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class SSC_NO_CDS=1 \
  "$ROOT/bin/ssc-standard" run --native "$fixture" >"$tmp/vm.log" 2>&1
grep -F 'H1@1(Pricing|#pricing route=/pricing)' "$tmp/vm.log" >/dev/null
if grep -Ei 'class,load] org[.]objectweb[.]asm|class,load] (ujson|upickle|upack)[.]' \
    "$tmp/vm.log" >/dev/null; then
  echo 'native VM loaded a backend ASM or external parser/codec class' >&2
  exit 1
fi

PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class SSC_NO_CDS=1 \
  "$ROOT/bin/ssc-standard" run --native --bytecode "$fixture" >"$tmp/asm.log" 2>&1
grep -F 'H1@1(Pricing|#pricing route=/pricing)' "$tmp/asm.log" >/dev/null
grep -Ei 'class,load] org[.]objectweb[.]asm' "$tmp/asm.log" >/dev/null
if grep -Ei 'class,load] (ujson|upickle|upack)[.]' "$tmp/asm.log" >/dev/null; then
  echo 'direct ASM execution loaded an external parser/codec class' >&2
  exit 1
fi

PATH=/usr/bin:/bin SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" run --native \
  "$sql_fixture" >"$tmp/sql-vm.out"
PATH=/usr/bin:/bin SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" run --native --bytecode \
  "$sql_fixture" >"$tmp/sql-asm.out"
cmp -s "$tmp/sql-vm.out" "$tmp/sql-asm.out"
[[ $(cat "$tmp/sql-vm.out") == $'1\n7\nAda\ntrue' ]]

echo 'PASS v21-plugin-backend-isolation-smoke'
