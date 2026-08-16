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

for mode in vm asm; do
  content_args=(run)
  [[ $mode == asm ]] && content_args+=(--bytecode)
  PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" \
    "${content_args[@]}" "$ROOT/examples/content.ssc" \
    >"$sandbox/content-$mode.out"
done
cmp -s "$sandbox/content-vm.out" "$sandbox/content-asm.out"
if grep -F 'NativeDoc(' "$sandbox/content-vm.out" >/dev/null; then
  echo 'v21-native-doc-render-smoke: nested document leaked its runtime tag' >&2
  exit 1
fi
grep -Fx '=== Fruits ===' "$sandbox/content-vm.out" >/dev/null
grep -Fx '=== Numbers ===' "$sandbox/content-vm.out" >/dev/null

PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$ROOT/examples/content.ssc" -o "$sandbox/content.jar" >/dev/null
PATH="$clean_path" SSC_NO_CDS=1 java -jar "$sandbox/content.jar" \
  >"$sandbox/content-artifact.out"
cmp -s "$sandbox/content-vm.out" "$sandbox/content-artifact.out"

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

# `ujson.`/`upickle.` ARE NO LONGER FORBIDDEN AT RUNTIME, and the dates are the whole argument.
# This list was written 2026-07-11 (44f2c227e). On 2026-07-31 (f154c0ccb) MCP joined the standard
# graph by decision — `build.sbt` stages `scalascript-v2-native-mcp-plugin` plus its managed
# classpath into `bin/lib/standard/jars/`, and that classpath is ujson/upickle/upack/geny. So a
# standard run loads `ujson.Readable` and `ujson.Value` immediately after
# `ssc.plugin.mcp.McpNativePlugin`, and this gate has been RED against a correct build for twenty
# days — unnoticed, because the gate is invoked by nothing. It is the failure mode the gate itself
# is about, one level up.
#
# THE JDEPS CHECK ABOVE KEEPS ITS ujson BAN, and the asymmetry is deliberate: the host PROVIDER jar
# must not itself depend on a JSON parser (it does not), while the standard RUN may legitimately
# load one that a standard-surface plugin brought. Banning the class load conflates "the tier stays
# compiler-free" with "the tier contains no third-party library at all", and only the first is the
# invariant this gate was written for.
PATH="$clean_path" JAVA_TOOL_OPTIONS=-verbose:class SSC_NO_CDS=1 \
  "$ROOT/bin/ssc-standard" run "$FIXTURE" >"$sandbox/classload.out" 2>&1
if grep -E \
    'scala[.]meta[.]|scalascript[.]parser[.]Parser|ssc[.]bridge[.]PluginBridge|org[.]commonmark|com[.]vladsch[.]flexmark' \
    "$sandbox/classload.out" >/dev/null; then
  echo 'v21-native-doc-render-smoke: standard run loaded a forbidden compatibility/parser class' >&2
  exit 1
fi

echo 'PASS v21-native-doc-render-smoke'
