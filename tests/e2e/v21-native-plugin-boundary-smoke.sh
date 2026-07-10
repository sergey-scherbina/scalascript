#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
JARS="$ROOT/bin/lib/jars"
CP="$JARS/*:$ROOT/bin/lib/ssc.jar"

spi=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-plugin-spi_*.jar' -print -quit)
host=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-host-plugin_*.jar' -print -quit)
crypto=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-crypto-plugin_*.jar' -print -quit)
os=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-os-plugin_*.jar' -print -quit)
fs=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-fs-plugin_*.jar' -print -quit)
json=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-json-plugin_*.jar' -print -quit)
http=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-http-plugin_*.jar' -print -quit)
sql=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-sql-plugin_*.jar' -print -quit)
ui=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-ui-plugin_*.jar' -print -quit)
state=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-state-effect-plugin_*.jar' -print -quit)
for jar_file in "$spi" "$host" "$crypto" "$os" "$fs" "$json" "$http" "$sql" "$ui" "$state"; do
  [[ -n "$jar_file" && -f "$jar_file" ]] || {
    echo 'v21-native-plugin-boundary-smoke: staged native provider jar missing' >&2
    exit 2
  }
done

jar tf "$host" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$crypto" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$os" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$fs" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$json" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$http" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$sql" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$ui" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$state" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null

for jar_file in "$spi" "$host" "$crypto" "$os" "$fs" "$json" "$http" "$sql" "$ui" "$state"; do
  deps=$(jdeps --multi-release base --ignore-missing-deps -verbose:class -cp "$CP" "$jar_file")
  if printf '%s\n' "$deps" | grep -E \
      'scala\.meta|scalascript\.interpreter|scalascript\.ast|scalascript\.plugin\.api|scalascript\.frontend|ssc\.bridge|dotty\.tools|javax\.tools' >/dev/null; then
    echo "forbidden standard-provider dependency in ${jar_file##*/}:" >&2
    printf '%s\n' "$deps" | grep -E \
      'scala\.meta|scalascript\.interpreter|scalascript\.ast|scalascript\.plugin\.api|scalascript\.frontend|ssc\.bridge|dotty\.tools|javax\.tools' >&2
    exit 1
  fi
done

native_refs=$(javap -classpath "$CP" -verbose scalascript.cli.RunNativeV2)
if printf '%s\n' "$native_refs" | grep -E \
    'ssc/bridge/(PluginBridge|FrontendBridge)|scala/meta|scalascript/parser/(SimpleYaml|Parser)|NativeFrontmatter|ssc/Reader' >/dev/null; then
  echo 'RunNativeV2 retains a compatibility frontend, host parser, or textual CoreIR reader reference' >&2
  exit 1
fi
if jar tf "$ROOT/bin/lib/standard/ssc.jar" | grep -F 'scalascript/cli/NativeFrontmatter' >/dev/null; then
  echo 'standard CLI still packages the retired NativeFrontmatter host parser' >&2
  exit 1
fi

classlog=$(mktemp "${TMPDIR:-/tmp}/v21-native-classload.XXXXXX")
standard_classlog=$(mktemp "${TMPDIR:-/tmp}/v21-native-standard-classload.XXXXXX")
json_classlog=$(mktemp "${TMPDIR:-/tmp}/v21-native-json-classload.XXXXXX")
http_classlog=$(mktemp "${TMPDIR:-/tmp}/v21-native-http-classload.XXXXXX")
ui_tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-native-ui.XXXXXX")
sql_vm=$(mktemp "${TMPDIR:-/tmp}/v21-native-sql-vm.XXXXXX")
sql_asm=$(mktemp "${TMPDIR:-/tmp}/v21-native-sql-asm.XXXXXX")
state_vm=$(mktemp "${TMPDIR:-/tmp}/v21-native-state-vm.XXXXXX")
state_asm=$(mktemp "${TMPDIR:-/tmp}/v21-native-state-asm.XXXXXX")
trap 'rm -f "$classlog" "$standard_classlog" "$json_classlog" "$http_classlog" "$sql_vm" "$sql_asm" "$state_vm" "$state_asm"; rm -rf "$ui_tmp"' EXIT HUP INT TERM
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc-standard" run --native \
  "$ROOT/tests/fixtures/v21-native/sql-provider.ssc" >"$standard_classlog" 2>&1
grep -F 'Ada' "$standard_classlog" >/dev/null
if grep -E 'scalascript\.parser\.(SimpleYaml|Parser)|NativeFrontmatter|ssc\.Reader' \
    "$standard_classlog" >/dev/null; then
  echo 'standard native run loaded a retired host parser or textual CoreIR reader' >&2
  grep -E 'scalascript\.parser\.(SimpleYaml|Parser)|NativeFrontmatter|ssc\.Reader' \
    "$standard_classlog" >&2
  exit 1
fi
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/std-crypto.ssc" >"$classlog" 2>&1
grep -F '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/fs-os-provider.ssc" >>"$classlog" 2>&1
grep -F 'one-two' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/json-provider.ssc" >"$json_classlog" 2>&1
grep -F '{"payload":[1,2]}' "$json_classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/http-response-provider.ssc" >"$http_classlog" 2>&1
grep -F 'public, max-age=60' "$http_classlog" >/dev/null
if grep -E 'ujson[.]|upickle[.]|upack[.]' "$json_classlog" "$http_classlog" >/dev/null; then
  echo 'native JSON/HTTP run loaded an external JSON codec class' >&2
  grep -E 'ujson[.]|upickle[.]|upack[.]' "$json_classlog" "$http_classlog" >&2
  exit 1
fi
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/sql-provider.ssc" >>"$classlog" 2>&1
grep -F 'Ada' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/ui-provider.ssc" -- "$ui_tmp" >>"$classlog" 2>&1
grep -F 'Hi &lt;native&gt;' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/state-effect-provider.ssc" >>"$classlog" 2>&1
grep -F '101' "$classlog" >/dev/null
if grep -E 'ssc\.bridge\.(PluginBridge|FrontendBridge)|scala\.meta\.' \
    "$classlog" >/dev/null; then
  echo 'native run loaded a compatibility bridge or Scalameta class' >&2
  grep -E 'ssc\.bridge\.(PluginBridge|FrontendBridge)|scala\.meta\.' \
    "$classlog" >&2
  exit 1
fi

# Faithful source-order regressions on both standard backends.  Keep the vals in
# the fixtures: in the broken lowerer `rows` ran before the DDL/DML and `inside`
# escaped its runState thunk as an unbound global.
PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/sql-provider.ssc" >"$sql_vm"
PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native --bytecode \
  "$ROOT/tests/fixtures/v21-native/sql-provider.ssc" >"$sql_asm"
cmp -s "$sql_vm" "$sql_asm"
[[ $(cat "$sql_vm") == $'1\n7\nAda\ntrue' ]]

PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/state-effect-provider.ssc" >"$state_vm"
PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native --bytecode \
  "$ROOT/tests/fixtures/v21-native/state-effect-provider.ssc" >"$state_asm"
cmp -s "$state_vm" "$state_asm"
[[ $(cat "$state_vm") == $'17\n20\n2\n101\n101\n2' ]]

echo 'PASS v21-native-plugin-boundary-smoke'
