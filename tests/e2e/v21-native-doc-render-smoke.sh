#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
FIXTURE="$ROOT/tests/fixtures/v21-native/doc-render.ssc"
SHADOW_FIXTURE="$ROOT/tests/fixtures/v21-native/doc-render-shadow.ssc"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-native-doc-render.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

expected=$'alpha\n2\ntrue\nList(x, y)\nSome(z)\nomega'
clean_path=/usr/bin:/bin

for mode in vm asm; do
  args=(run --native)
  [[ $mode == asm ]] && args+=(--bytecode)
  PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" "${args[@]}" "$FIXTURE" \
    >"$sandbox/full-$mode.out"
  [[ $(cat "$sandbox/full-$mode.out") == "$expected" ]]

  standard_args=(run)
  [[ $mode == asm ]] && standard_args+=(--bytecode)
  PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" \
    "${standard_args[@]}" "$FIXTURE" >"$sandbox/standard-$mode.out"
  cmp -s "$sandbox/full-$mode.out" "$sandbox/standard-$mode.out"

  PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" "${args[@]}" \
    "$SHADOW_FIXTURE" >"$sandbox/shadow-$mode.out"
  [[ $(cat "$sandbox/shadow-$mode.out") == 'local:ab' ]]
done

PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm "$FIXTURE" \
  -o "$sandbox/doc-render.jar" >/dev/null
PATH="$clean_path" SSC_NO_CDS=1 java -jar "$sandbox/doc-render.jar" \
  >"$sandbox/artifact.out"
cmp -s "$sandbox/full-vm.out" "$sandbox/artifact.out"

host_jar=$(find "$ROOT/bin/lib/standard/jars" -maxdepth 1 \
  -name 'scalascript-v2-native-host-plugin_*.jar' -print -quit)
[[ -n $host_jar && -f $host_jar ]]
deps=$(jdeps --multi-release base --ignore-missing-deps -verbose:class \
  -cp "$ROOT/bin/lib/standard/jars/*:$ROOT/bin/lib/standard/ssc.jar" "$host_jar")
if printf '%s\n' "$deps" | grep -E \
    'scala[.]meta|scalascript[.](ast|parser|interpreter)|ssc[.]bridge|org[.]commonmark|com[.]vladsch[.]flexmark|ujson|upickle' >/dev/null; then
  echo 'v21-native-doc-render-smoke: host provider retains a forbidden compatibility/parser dependency' >&2
  exit 1
fi

PATH="$clean_path" JAVA_TOOL_OPTIONS=-verbose:class SSC_NO_CDS=1 \
  "$ROOT/bin/ssc-standard" run "$FIXTURE" >"$sandbox/classload.out" 2>&1
if grep -E \
    'scala[.]meta[.]|scalascript[.]parser[.]Parser|ssc[.]bridge[.]PluginBridge|org[.]commonmark|com[.]vladsch[.]flexmark|ujson[.]|upickle[.]' \
    "$sandbox/classload.out" >/dev/null; then
  echo 'v21-native-doc-render-smoke: standard run loaded a forbidden compatibility/parser class' >&2
  exit 1
fi

echo 'PASS v21-native-doc-render-smoke'
