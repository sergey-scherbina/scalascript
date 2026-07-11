#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
FIXTURE="$ROOT/tests/fixtures/v21-native/content-provider.ssc"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-native-content.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

expected=$'Details\n1\n```yaml @id=payload\nanswer: 42\n```'

PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native "$FIXTURE" >"$tmp/full-vm.out"
PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native --bytecode "$FIXTURE" >"$tmp/full-asm.out"
PATH=/usr/bin:/bin "$ROOT/bin/ssc-standard" run "$FIXTURE" >"$tmp/standard-vm.out"
PATH=/usr/bin:/bin "$ROOT/bin/ssc-standard" run --bytecode "$FIXTURE" >"$tmp/standard-asm.out"

for output in "$tmp/full-vm.out" "$tmp/full-asm.out" "$tmp/standard-vm.out" "$tmp/standard-asm.out"; do
  [[ $(cat "$output") == "$expected" ]] || {
    echo "v21-native-content-smoke: unexpected output from $output" >&2
    cat "$output" >&2
    exit 1
  }
done

PATH=/usr/bin:/bin "$ROOT/bin/ssc" build-jvm "$FIXTURE" -o "$tmp/content.jar" >/dev/null
jar tf "$tmp/content.jar" | grep -Fx 'META-INF/scalascript/content.bin' >/dev/null
unzip -p "$tmp/content.jar" META-INF/scalascript/artifact.properties \
  | grep -Fx 'content.count=3' >/dev/null
PATH=/usr/bin:/bin java -jar "$tmp/content.jar" >"$tmp/artifact.out"
[[ $(cat "$tmp/artifact.out") == "$expected" ]]

content_jar=$(find "$ROOT/bin/lib/standard/jars" -maxdepth 1 \
  -name 'scalascript-v2-native-content-plugin_*.jar' -print -quit)
[[ -n "$content_jar" && -f "$content_jar" ]]
deps=$(jdeps --multi-release base --ignore-missing-deps -verbose:class \
  -cp "$ROOT/bin/lib/standard/jars/*:$ROOT/bin/lib/standard/ssc.jar" "$content_jar")
if printf '%s\n' "$deps" | grep -E \
    'scala[.]meta|scalascript[.](ast|parser|interpreter|plugin[.]api|frontend)|ssc[.]bridge|org[.]commonmark|com[.]vladsch[.]flexmark' >/dev/null; then
  echo 'v21-native-content-smoke: content provider retains a forbidden parser/bridge dependency' >&2
  exit 1
fi

PATH=/usr/bin:/bin JAVA_TOOL_OPTIONS=-verbose:class "$ROOT/bin/ssc-standard" run "$FIXTURE" \
  >"$tmp/classload.out" 2>&1
grep -F 'Imported structural content.' "$tmp/classload.out" >/dev/null || true
if grep -E 'scala[.]meta[.]|scalascript[.]parser[.]Parser|org[.]commonmark|com[.]vladsch[.]flexmark' \
    "$tmp/classload.out" >/dev/null; then
  echo 'v21-native-content-smoke: standard content run loaded a forbidden parser' >&2
  exit 1
fi

echo 'PASS v21-native-content-smoke'
