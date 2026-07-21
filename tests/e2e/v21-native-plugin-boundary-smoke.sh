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
scljet_vfs_host=$(find "$JARS" -maxdepth 1 -name 'scalascript-scljet-vfs-host_*.jar' -print -quit)
scljet_vfs=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-scljet-vfs-plugin_*.jar' -print -quit)
json=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-json-plugin_*.jar' -print -quit)
http=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-http-fast-plugin_*.jar' -print -quit)
http_engine=$(find "$JARS" -maxdepth 1 -name 'scalascript-http-fast-engine_*.jar' -print -quit)
sql=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-sql-plugin_*.jar' -print -quit)
ui=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-ui-plugin_*.jar' -print -quit)
state=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-state-effect-plugin_*.jar' -print -quit)
effects=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-effect-runners-plugin_*.jar' -print -quit)
storage=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-storage-effect-plugin_*.jar' -print -quit)
reactive=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-reactive-plugin_*.jar' -print -quit)
yaml=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-yaml-plugin_*.jar' -print -quit)
content=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-content-plugin_*.jar' -print -quit)
dataset=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-dataset-plugin_*.jar' -print -quit)
generator=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-generator-plugin_*.jar' -print -quit)
actors=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-actors-plugin_*.jar' -print -quit)
distributed=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-distributed-plugin_*.jar' -print -quit)
graph=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-graph-plugin_*.jar' -print -quit)
optics=$(find "$JARS" -maxdepth 1 -name 'scalascript-v2-native-optics-plugin_*.jar' -print -quit)
for jar_file in "$spi" "$host" "$crypto" "$os" "$fs" "$scljet_vfs_host" "$scljet_vfs" "$json" "$http" "$http_engine" "$sql" "$ui" "$state" "$effects" "$storage" "$reactive" "$yaml" "$content" "$dataset" "$generator" "$actors" "$distributed" "$graph" "$optics"; do
  [[ -n "$jar_file" && -f "$jar_file" ]] || {
    echo 'v21-native-plugin-boundary-smoke: staged native provider jar missing' >&2
    exit 2
  }
done

jar tf "$host" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$crypto" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$os" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$fs" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$scljet_vfs" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
if jar tf "$scljet_vfs_host" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null; then
  echo 'v21-native-plugin-boundary-smoke: SclJet VFS host must not be a native provider' >&2
  exit 1
fi
jar tf "$json" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$http" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
if jar tf "$http_engine" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null; then
  echo 'v21-native-plugin-boundary-smoke: HTTP fast engine must not be a native provider' >&2
  exit 1
fi
jar tf "$sql" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$ui" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$state" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$effects" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$storage" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$reactive" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$yaml" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$content" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$dataset" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$generator" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$actors" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$distributed" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$graph" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null
jar tf "$optics" | grep -Fx 'META-INF/services/ssc.plugin.NativePlugin' >/dev/null

for jar_file in "$spi" "$host" "$crypto" "$os" "$fs" "$scljet_vfs_host" "$scljet_vfs" "$json" "$http" "$http_engine" "$sql" "$ui" "$state" "$effects" "$storage" "$reactive" "$yaml" "$content" "$dataset" "$generator" "$actors" "$distributed" "$graph" "$optics"; do
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
yaml_classlog=$(mktemp "${TMPDIR:-/tmp}/v21-native-yaml-classload.XXXXXX")
content_classlog=$(mktemp "${TMPDIR:-/tmp}/v21-native-content-classload.XXXXXX")
ui_tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-native-ui.XXXXXX")
sql_vm=$(mktemp "${TMPDIR:-/tmp}/v21-native-sql-vm.XXXXXX")
sql_asm=$(mktemp "${TMPDIR:-/tmp}/v21-native-sql-asm.XXXXXX")
state_vm=$(mktemp "${TMPDIR:-/tmp}/v21-native-state-vm.XXXXXX")
state_asm=$(mktemp "${TMPDIR:-/tmp}/v21-native-state-asm.XXXXXX")
distributed_vm=$(mktemp "${TMPDIR:-/tmp}/v21-native-distributed-vm.XXXXXX")
distributed_asm=$(mktemp "${TMPDIR:-/tmp}/v21-native-distributed-asm.XXXXXX")
distributed_log_vm=$(mktemp "${TMPDIR:-/tmp}/v21-native-distributed-log-vm.XXXXXX")
distributed_log_asm=$(mktemp "${TMPDIR:-/tmp}/v21-native-distributed-log-asm.XXXXXX")
graph_vm=$(mktemp "${TMPDIR:-/tmp}/v21-native-graph-vm.XXXXXX")
graph_asm=$(mktemp "${TMPDIR:-/tmp}/v21-native-graph-asm.XXXXXX")
optics_vm=$(mktemp "${TMPDIR:-/tmp}/v21-native-optics-vm.XXXXXX")
optics_asm=$(mktemp "${TMPDIR:-/tmp}/v21-native-optics-asm.XXXXXX")
storage_path="$ui_tmp/storage.json"
trap 'rm -f "$classlog" "$standard_classlog" "$json_classlog" "$http_classlog" "$yaml_classlog" "$content_classlog" "$sql_vm" "$sql_asm" "$state_vm" "$state_asm" "$distributed_vm" "$distributed_asm" "$distributed_log_vm" "$distributed_log_asm" "$graph_vm" "$graph_asm" "$optics_vm" "$optics_asm"; rm -rf "$ui_tmp"' EXIT HUP INT TERM
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc-standard" run --native \
  "$ROOT/tests/fixtures/v21-native/sql-provider.ssc" >"$standard_classlog" 2>&1
grep -F 'Ada' "$standard_classlog" >/dev/null
if grep -E 'scalascript\.parser\.Parser|NativeFrontmatter|ssc\.Reader' \
    "$standard_classlog" >/dev/null; then
  echo 'standard native run loaded a retired host parser or textual CoreIR reader' >&2
  grep -E 'scalascript\.parser\.Parser|NativeFrontmatter|ssc\.Reader' \
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
PATH=/usr/bin:/bin SSC_STORAGE_PATH="$storage_path" JAVA_TOOL_OPTIONS=-verbose:class \
  "$ROOT/bin/ssc" run --native "$ROOT/examples/storage-demo.ssc" >>"$classlog" 2>&1
grep -F 'Some(hello world)' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/examples/signals-demo.ssc" >>"$classlog" 2>&1
grep -F 'n=4 sq=16 cube=64' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/dataset-provider.ssc" >>"$classlog" 2>&1
grep -F 'parallel=333383335000 count=10000' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/examples/generators.ssc" >>"$classlog" 2>&1
grep -F 'List((hello, 0), (world, 1), (foo, 2))' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/examples/async-demo.ssc" >>"$classlog" 2>&1
grep -F 'List(20, 40, 60)' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc-standard" run \
  "$ROOT/examples/actors-pingpong.ssc" >>"$classlog" 2>&1
grep -F 'pong: three' "$classlog" >/dev/null
grep -F 'before timeout: Some(got delivered)' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc-standard" run \
  "$ROOT/examples/distributed-join.ssc" -- \
  "$ROOT/tests/fixtures/v21-native/distributed-orders.csv" \
  "$ROOT/tests/fixtures/v21-native/distributed-customers.csv" >>"$classlog" 2>&1
grep -F 'o3 | c1 | Ada | 30' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc-standard" run \
  "$ROOT/examples/graph-storage-interpreter.ssc" >>"$classlog" 2>&1
grep -F 'imports:b.ssc' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc-standard" run \
  "$ROOT/examples/lenses.ssc" >>"$standard_classlog" 2>&1
grep -F 'List(TeamMember(anon, 30), TeamMember(anon, 25), TeamMember(anon, 35))' \
  "$standard_classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/examples/yaml-parse.ssc" >"$yaml_classlog" 2>&1
grep -F 'App: MyApp' "$yaml_classlog" >/dev/null
if grep -E 'org\.yaml\.snakeyaml|com\.fasterxml\.jackson|io\.circe' \
    "$yaml_classlog" >/dev/null; then
  echo 'native YAML run loaded an external parser/codec class' >&2
  exit 1
fi
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc-standard" run \
  "$ROOT/tests/fixtures/v21-native/content-provider.ssc" >"$content_classlog" 2>&1
grep -F 'Imported structural content.' "$content_classlog" >/dev/null || \
  grep -F 'Details' "$content_classlog" >/dev/null
if grep -E 'org\.commonmark|com\.vladsch\.flexmark|scalascript\.parser\.Parser' \
    "$content_classlog" >/dev/null; then
  echo 'native content run loaded a retired host Markdown/content parser' >&2
  exit 1
fi
if grep -E 'ssc\.bridge\.(PluginBridge|FrontendBridge)|scala\.meta\.' \
    "$classlog" "$standard_classlog" "$yaml_classlog" "$content_classlog" >/dev/null; then
  echo 'native run loaded a compatibility bridge or Scalameta class' >&2
  grep -E 'ssc\.bridge\.(PluginBridge|FrontendBridge)|scala\.meta\.' \
    "$classlog" "$yaml_classlog" >&2
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

PATH=/usr/bin:/bin "$ROOT/bin/ssc-standard" run \
  "$ROOT/examples/sql-h2-quickstart.ssc" >"$sql_vm"
PATH=/usr/bin:/bin "$ROOT/bin/ssc-standard" run --bytecode \
  "$ROOT/examples/sql-h2-quickstart.ssc" >"$sql_asm"
cmp -s "$sql_vm" "$sql_asm"
[[ $(cat "$sql_vm") == $'active users: List(Map(ID -> 1, NAME -> Alice, EMAIL -> alice@example.com), Map(ID -> 2, NAME -> Bob, EMAIL -> bob@example.com))\nusers with id >= 1: List(Map(TOTAL -> 3))' ]]

PATH=/usr/bin:/bin "$ROOT/bin/ssc-standard" run \
  "$ROOT/examples/typed-sql-crud.ssc" >"$sql_vm"
PATH=/usr/bin:/bin "$ROOT/bin/ssc-standard" run --bytecode \
  "$ROOT/examples/typed-sql-crud.ssc" >"$sql_asm"
cmp -s "$sql_vm" "$sql_asm"
[[ $(cat "$sql_vm") == '1/1:Buy oat milk:true' ]]

PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/state-effect-provider.ssc" >"$state_vm"
PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native --bytecode \
  "$ROOT/tests/fixtures/v21-native/state-effect-provider.ssc" >"$state_asm"
cmp -s "$state_vm" "$state_asm"
[[ $(cat "$state_vm") == $'17\n20\n2\n101\n101\n2' ]]

PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native \
  "$ROOT/examples/distributed-join.ssc" -- \
  "$ROOT/tests/fixtures/v21-native/distributed-orders.csv" \
  "$ROOT/tests/fixtures/v21-native/distributed-customers.csv" >"$distributed_vm"
PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native --bytecode \
  "$ROOT/examples/distributed-join.ssc" -- \
  "$ROOT/tests/fixtures/v21-native/distributed-orders.csv" \
  "$ROOT/tests/fixtures/v21-native/distributed-customers.csv" >"$distributed_asm"
cmp -s "$distributed_vm" "$distributed_asm"
[[ $(cat "$distributed_vm") == $'o1 | c1 | Ada | 10\no2 | c2 | Bob | 20\no3 | c1 | Ada | 30' ]]

PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native \
  "$ROOT/examples/distributed-log-aggregation.ssc" -- \
  "$ROOT/tests/fixtures/v21-native/distributed-app.log" >"$distributed_log_vm"
PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native --bytecode \
  "$ROOT/examples/distributed-log-aggregation.ssc" -- \
  "$ROOT/tests/fixtures/v21-native/distributed-app.log" >"$distributed_log_asm"
cmp -s "$distributed_log_vm" "$distributed_log_asm"
[[ $(cat "$distributed_log_vm") == $'payments: 2 errors\nsearch: 1 errors' ]]

PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native \
  "$ROOT/examples/graph-storage-interpreter.ssc" >"$graph_vm"
PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native --bytecode \
  "$ROOT/examples/graph-storage-interpreter.ssc" >"$graph_asm"
cmp -s "$graph_vm" "$graph_asm"
[[ $(cat "$graph_vm") == 'imports:b.ssc' ]]

PATH=/usr/bin:/bin "$ROOT/bin/ssc-standard" run \
  "$ROOT/examples/lenses.ssc" >"$optics_vm"
PATH=/usr/bin:/bin "$ROOT/bin/ssc-standard" run --bytecode \
  "$ROOT/examples/lenses.ssc" >"$optics_asm"
cmp -s "$optics_vm" "$optics_asm"
[[ $(wc -l <"$optics_vm" | tr -d ' ') == 23 ]]
grep -Fx 'Some(Circle(5))' "$optics_vm" >/dev/null
grep -Fx 'List(TeamMember(anon, 30), TeamMember(anon, 25), TeamMember(anon, 35))' \
  "$optics_vm" >/dev/null

echo 'PASS v21-native-plugin-boundary-smoke'
