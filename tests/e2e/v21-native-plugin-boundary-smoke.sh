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
if printf '%s\n' "$native_refs" | grep -E 'ssc/bridge/(PluginBridge|FrontendBridge)|scala/meta' >/dev/null; then
  echo 'RunNativeV2 retains a compatibility-frontend/plugin reference' >&2
  exit 1
fi

classlog=$(mktemp "${TMPDIR:-/tmp}/v21-native-classload.XXXXXX")
ui_tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-native-ui.XXXXXX")
trap 'rm -f "$classlog"; rm -rf "$ui_tmp"' EXIT HUP INT TERM
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/std-crypto.ssc" >"$classlog" 2>&1
grep -F '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/fs-os-provider.ssc" >>"$classlog" 2>&1
grep -F 'one-two' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/json-provider.ssc" >>"$classlog" 2>&1
grep -F '{"payload":[1,2]}' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/http-response-provider.ssc" >>"$classlog" 2>&1
grep -F 'public, max-age=60' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/sql-provider.ssc" >>"$classlog" 2>&1
grep -F 'Ada' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/ui-provider.ssc" -- "$ui_tmp" >>"$classlog" 2>&1
grep -F 'Hi &lt;native&gt;' "$classlog" >/dev/null
PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc" run --native \
  "$ROOT/tests/fixtures/v21-native/state-effect-provider.ssc" >>"$classlog" 2>&1
grep -F '202' "$classlog" >/dev/null
if grep -E 'ssc\.bridge\.(PluginBridge|FrontendBridge)|scala\.meta\.' "$classlog" >/dev/null; then
  echo 'native crypto run loaded compatibility bridge/Scalameta classes' >&2
  grep -E 'ssc\.bridge\.(PluginBridge|FrontendBridge)|scala\.meta\.' "$classlog" >&2
  exit 1
fi

echo 'PASS v21-native-plugin-boundary-smoke'
