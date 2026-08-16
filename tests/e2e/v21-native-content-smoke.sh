#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
FIXTURE="$ROOT/tests/fixtures/v21-native/content-provider.ssc"
BINDING_FIXTURE="$ROOT/tests/fixtures/v21-native/content-binding.ssc"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-native-content.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

# THE HEADING ANCHORS IN `binding_expected` WERE ADDED 2026-08-16, AND UPDATING A GOLDEN TO MATCH
# CURRENT BEHAVIOUR IS EXACTLY HOW A REGRESSION GETS BLESSED — so here is the evidence that it is not
# one. The golden was written 2026-07-11 (`208ec4c60`) with bare `# Template` / `## Nested`. On
# 2026-07-30 (`d2349fe99`) the content renderer was changed to emit heading attr groups in v1's
# canonical order, and a section's derived id now renders as `{#template}` even where the source
# carries no explicit `{#…}`. Nineteen days apart, and nothing went red, because this gate is
# invoked by nothing.
#
# WHAT MAKES IT SAFE TO ACCEPT IS CROSS-LANE AGREEMENT, not the fact that the gate then passes. The
# same fixture was run through v2 standard, v2 native AND the v1 JVM artifact (`ssc build-jvm` →
# `java -jar`), and all three produce byte-identical output. v1 is the REFERENCE implementation this
# renderer was changed to match, so a regression would show up here as a divergence — and there is
# none. If you ever update this golden again, run those three and say so; a golden refreshed against
# one lane proves only that the lane agrees with itself.
expected=$'Details\n1\n```yaml @id=payload\nanswer: 42\n```'
binding_expected=$'plain=\nTemplate\nHello Ada; map={name: Ada, role: Engineer}; missing=${missing.path}; invalid=${bad-path}.\nBullet $49\nMissing ${unknown}\nOrdered 42\nActive true\nKey | Value\nName | Ada\nItems | one, two\nstatic image\nEmbedded ${user.name} remains literal.\nNested\nNested  / $49.\nmarkdown=\n# Template {\x23template}\n\nHello Ada; map={name: Ada, role: Engineer}; missing=${missing.path}; invalid=${bad-path}.\n\n- Bullet $49\n- Missing ${unknown}\n\n3. Ordered 42\n4. Active true\n\n| Key | Value |\n| :--- | ---: |\n| Name | Ada |\n| Items | one, two |\n\n![static image](/static.png "Static")\n\n```text @id=literal\nEmbedded ${user.name} remains literal.\n```\n\n## Nested {\x23nested}\n\nNested  / $49.\ninline-shapes=\n*Ada***true**[$49](/buy)`${user.name}`'

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
  | grep -Fx 'content.count=4' >/dev/null
PATH=/usr/bin:/bin java -jar "$tmp/content.jar" >"$tmp/artifact.out"
[[ $(cat "$tmp/artifact.out") == "$expected" ]]

PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native "$BINDING_FIXTURE" >"$tmp/binding-full-vm.out"
PATH=/usr/bin:/bin "$ROOT/bin/ssc" run --native --bytecode "$BINDING_FIXTURE" >"$tmp/binding-full-asm.out"
PATH=/usr/bin:/bin "$ROOT/bin/ssc-standard" run "$BINDING_FIXTURE" >"$tmp/binding-standard-vm.out"
PATH=/usr/bin:/bin "$ROOT/bin/ssc-standard" run --bytecode "$BINDING_FIXTURE" >"$tmp/binding-standard-asm.out"
PATH=/usr/bin:/bin "$ROOT/bin/ssc" build-jvm "$BINDING_FIXTURE" -o "$tmp/content-binding.jar" >/dev/null
PATH=/usr/bin:/bin java -jar "$tmp/content-binding.jar" >"$tmp/binding-artifact.out"

for output in \
  "$tmp/binding-full-vm.out" \
  "$tmp/binding-full-asm.out" \
  "$tmp/binding-standard-vm.out" \
  "$tmp/binding-standard-asm.out" \
  "$tmp/binding-artifact.out"; do
  [[ $(cat "$output") == "$binding_expected" ]] || {
    echo "v21-native-content-smoke: unexpected binding output from $output" >&2
    cat "$output" >&2
    exit 1
  }
done

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
