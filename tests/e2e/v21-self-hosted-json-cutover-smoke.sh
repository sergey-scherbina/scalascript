#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
FIXTURES="$ROOT/tests/fixtures/v21-native"
expected="$FIXTURES/json-cutover.expected"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/ssc-v21-json-cutover.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

run_native() {
  PATH=/usr/bin:/bin SSC_NO_CDS=1 "$ROOT/bin/ssc" run --native "$@"
}

run_native "$FIXTURES/json-cutover.ssc" >"$tmp/vm.out"
run_native --bytecode "$FIXTURES/json-cutover.ssc" >"$tmp/asm.out"
cmp -s "$tmp/vm.out" "$tmp/asm.out"
diff -u "$expected" "$tmp/vm.out"

for lane in vm asm; do
  args=()
  [[ $lane == asm ]] && args+=(--bytecode)
  set +e
  run_native "${args[@]}" "$FIXTURES/json-cutover-invalid.ssc" \
    >"$tmp/$lane.invalid.out" 2>"$tmp/$lane.invalid.err"
  rc=$?
  set -e
  [[ $rc -ne 0 ]]
  grep -F 'invalid JSON at 1: unterminated object' "$tmp/$lane.invalid.err" >/dev/null
done

json_jar=$(find "$ROOT/bin/lib/standard/jars" -maxdepth 1 \
  -name 'scalascript-v2-native-json-plugin_*.jar' -print -quit)
jdeps --multi-release base --ignore-missing-deps -verbose:class \
  -cp "$ROOT/bin/lib/standard/jars/*" "$json_jar" >"$tmp/json.jdeps"
if grep -E 'ujson|upickle|upack|geny' "$tmp/json.jdeps" >/dev/null; then
  echo 'v21-self-hosted-json-cutover: native JSON bridge retains an external codec edge' >&2
  exit 1
fi

PATH=/usr/bin:/bin SSC_NO_CDS=1 JAVA_TOOL_OPTIONS=-verbose:class \
  "$ROOT/bin/ssc-standard" run "$FIXTURES/json-cutover.ssc" \
  >"$tmp/classload.out" 2>&1
if grep -E 'ujson[.]|upickle[.]|upack[.]|geny[.]' "$tmp/classload.out" >/dev/null; then
  echo 'v21-self-hosted-json-cutover: JSON run loaded an external codec class' >&2
  exit 1
fi

cp -R "$ROOT/bin" "$tmp/bin"
find "$tmp/bin/lib/standard/jars" -maxdepth 1 -type f \
  \( -name 'ujson_*.jar' -o -name 'upickle-core_*.jar' \
     -o -name 'upack_*.jar' -o -name 'geny_*.jar' \) -delete
PATH=/usr/bin:/bin SSC_NO_CDS=1 "$tmp/bin/ssc-standard" run \
  "$FIXTURES/json-cutover.ssc" >"$tmp/deleted-json.out"
PATH=/usr/bin:/bin SSC_NO_CDS=1 "$tmp/bin/ssc-standard" run \
  "$FIXTURES/http-response-provider.ssc" >"$tmp/deleted-http.out"
diff -u "$expected" "$tmp/deleted-json.out"
grep -Fx '{"n":2,"ok":true}' "$tmp/deleted-http.out" >/dev/null

echo 'PASS v21-self-hosted-json-cutover-smoke'
