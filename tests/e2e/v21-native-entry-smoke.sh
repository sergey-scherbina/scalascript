#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
FIXTURES="$ROOT/tests/fixtures/v21-native"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-native-entry.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
mkdir -p "$sandbox/java-tmp"

[[ -x "$ROOT/bin/ssc" ]] || {
  echo 'v21-native-entry-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}
[[ -f "$ROOT/bin/lib/native-front/tower/bin/ssc1-run.ssc0" ]] || {
  echo 'v21-native-entry-smoke: staged native frontend missing' >&2
  exit 2
}
[[ -f "$ROOT/bin/lib/native-front/runtime/std/os.ssc" ]] || {
  echo 'v21-native-entry-smoke: staged std modules missing' >&2
  exit 2
}

clean_path='/usr/bin:/bin'
if PATH="$clean_path" command -v scala-cli >/dev/null 2>&1; then
  echo 'v21-native-entry-smoke: sanitized PATH unexpectedly contains scala-cli' >&2
  exit 1
fi

run_native() {
  PATH="$clean_path" JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$sandbox/java-tmp" SSC_NO_CDS=1 \
    "$ROOT/bin/ssc" run --native "$@"
}

[[ $(run_native "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(run_native "$FIXTURES/relative-main.ssc") == '42' ]]
[[ $(run_native "$FIXTURES/multi-first.ssc" "$FIXTURES/multi-second.ssc") == $'first\nsecond' ]]
[[ $(run_native "$FIXTURES/argv.ssc" -- one two) == $'one\ntwo' ]]
[[ $(run_native "$FIXTURES/std-import.ssc") == 'std-import-ok' ]]
[[ $(run_native "$FIXTURES/std-crypto.ssc") == '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824' ]]
fs_os_expected=$'one-two\nsample.txt\ntrue\nfallback\ntrue\nfalse'
[[ $(run_native "$FIXTURES/fs-os-provider.ssc") == "$fs_os_expected" ]]
json_expected=$'Ada\n2\ntrue\n1000.01\ntrue\n{"payload":[1,2]}\n[1,2,3]\n{"name":"A\\"B","on":true}'
[[ $(run_native "$FIXTURES/json-provider.ssc") == "$json_expected" ]]
http_response_expected=$'201\ntext/plain; charset=utf-8\nhello\n{"n":2,"ok":true}\npublic, max-age=60\nv1\nno-store'
[[ $(run_native "$FIXTURES/http-response-provider.ssc") == "$http_response_expected" ]]
sql_expected=$'1\n7\nAda\ntrue'
[[ $(run_native "$FIXTURES/sql-provider.ssc") == "$sql_expected" ]]
ui_expected=$'<!doctype html>\n<main class="card" id="app"><h1>Hi &lt;native&gt;</h1>Ada<span>shown</span></main>'
ui_vm="$sandbox/ui-vm"
ui_asm="$sandbox/ui-asm"
[[ $(run_native "$FIXTURES/ui-provider.ssc" -- "$ui_vm") == "$ui_expected" ]]
[[ $(<"$ui_vm/index.html") == "$ui_expected" ]]
state_expected=$'17\n20\n2\n101\n101\n2'
[[ $(run_native "$FIXTURES/state-effect-provider.ssc") == "$state_expected" ]]
[[ $(run_native "$FIXTURES/prefix-postfix.ssc") == $'true\n-1\n-2' ]]
interpolation_expected=$'Squares: 1, 4, 9, 16, 25\nWrapped: 1-4-9-16-25!'
[[ $(run_native "$FIXTURES/interpolation-expression.ssc") == "$interpolation_expected" ]]
[[ $(run_native "$FIXTURES/multiline-function-param.ssc") == '[typed]' ]]
[[ $(run_native "$FIXTURES/nested-tuple-pattern.ssc") == $'left\nleft+right' ]]
[[ $(run_native "$FIXTURES/numeric-separator.ssc") == '10000' ]]
triple_string_expected=$'first line\n"quoted" line\nlast line'
[[ $(run_native "$FIXTURES/triple-quoted-string.ssc") == "$triple_string_expected" ]]
enum_boundary_expected=$'Red\nBox(7)'
[[ $(run_native "$FIXTURES/enum-case-class-boundary.ssc") == "$enum_boundary_expected" ]]
[[ $(run_native "$FIXTURES/multiline-tuple-lambda.ssc") == '11' ]]
guard_expected=$'negative\nzero\nsmall\nlarge'
[[ $(run_native "$FIXTURES/binder-match-guard.ssc") == "$guard_expected" ]]
constructor_guard_expected=$'enough\nlow\nmissing'
[[ $(run_native "$FIXTURES/constructor-match-guard.ssc") == "$constructor_guard_expected" ]]
[[ $(run_native "$FIXTURES/extension-declaration.ssc") == 'extension-header-ok' ]]
[[ $(run_native "$FIXTURES/list-append.ssc") == '1,2,3,4' ]]
[[ $(run_native "$FIXTURES/symbolic-extension-operators.ssc") == 'symbolic-operators-ok' ]]
pattern_alternative_expected=$'hit\nhit\nmiss'
[[ $(run_native "$FIXTURES/constructor-pattern-alternative.ssc") == "$pattern_alternative_expected" ]]
ui_fetch_json_expected=$'body:{"name":"Acme \\"HQ\\"","n":5}\nfetch-json:ok'
[[ $(run_native "$ROOT/examples/ui-fetch-json.ssc") == "$ui_fetch_json_expected" ]]
index_expected=$'ScalaScript 0.1 is running!\nSquares: 1, 4, 9, 16, 25'
[[ $(run_native "$ROOT/examples/index.ssc") == "$index_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(run_native --bytecode "$FIXTURES/prefix-postfix.ssc") == $'true\n-1\n-2' ]]
[[ $(run_native --bytecode "$FIXTURES/interpolation-expression.ssc") == "$interpolation_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/multiline-function-param.ssc") == '[typed]' ]]
[[ $(run_native --bytecode "$FIXTURES/nested-tuple-pattern.ssc") == $'left\nleft+right' ]]
[[ $(run_native --bytecode "$FIXTURES/numeric-separator.ssc") == '10000' ]]
[[ $(run_native --bytecode "$FIXTURES/triple-quoted-string.ssc") == "$triple_string_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/enum-case-class-boundary.ssc") == "$enum_boundary_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/multiline-tuple-lambda.ssc") == '11' ]]
[[ $(run_native --bytecode "$FIXTURES/binder-match-guard.ssc") == "$guard_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/constructor-match-guard.ssc") == "$constructor_guard_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/extension-declaration.ssc") == 'extension-header-ok' ]]
[[ $(run_native --bytecode "$FIXTURES/list-append.ssc") == '1,2,3,4' ]]
[[ $(run_native --bytecode "$FIXTURES/symbolic-extension-operators.ssc") == 'symbolic-operators-ok' ]]
[[ $(run_native --bytecode "$FIXTURES/constructor-pattern-alternative.ssc") == "$pattern_alternative_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/ui-fetch-json.ssc") == "$ui_fetch_json_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/index.ssc") == "$index_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/fs-os-provider.ssc") == "$fs_os_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/json-provider.ssc") == "$json_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/http-response-provider.ssc") == "$http_response_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/sql-provider.ssc") == "$sql_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/ui-provider.ssc" -- "$ui_asm") == "$ui_expected" ]]
[[ $(<"$ui_asm/index.html") == "$ui_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/state-effect-provider.ssc") == "$state_expected" ]]

assert_frontmatter_failure() {
  local name=$1
  shift
  set +e
  run_native "$@" >"$sandbox/$name.out" 2>"$sandbox/$name.err"
  local rc=$?
  set -e
  [[ $rc -ne 0 ]]
  [[ ! -s "$sandbox/$name.out" ]]
}

assert_frontmatter_failure yaml-duplicate "$FIXTURES/yaml-duplicate-frontmatter.ssc"
grep -E 'yaml-duplicate-frontmatter[.]ssc:4:5: duplicate mapping key' \
  "$sandbox/yaml-duplicate.err" >/dev/null
assert_frontmatter_failure yaml-anchor "$FIXTURES/yaml-anchor-frontmatter.ssc"
grep -F 'anchors, aliases, and tags are not supported' "$sandbox/yaml-anchor.err" >/dev/null
assert_frontmatter_failure yaml-missing-url "$FIXTURES/yaml-missing-url.ssc"
grep -F "native database 'default'" "$sandbox/yaml-missing-url.err" >/dev/null
grep -F 'requires a non-empty url' "$sandbox/yaml-missing-url.err" >/dev/null
assert_frontmatter_failure yaml-conflict \
  "$FIXTURES/yaml-conflict-a.ssc" "$FIXTURES/yaml-conflict-b.ssc"
grep -F "conflicting native database 'default'" "$sandbox/yaml-conflict.err" >/dev/null

http_port=$((32000 + ($$ % 10000)))
[[ $(run_native "$FIXTURES/http-server-provider.ssc" -- "$http_port") == $'203\npong:/ping' ]]
[[ $(run_native --bytecode "$FIXTURES/http-server-provider.ssc" -- "$((http_port + 1))") == $'203\npong:/ping' ]]
[[ $(PATH="$clean_path" JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$sandbox/java-tmp" SSC_NO_CDS=1 \
  "$ROOT/bin/ssc" run --compat-frontend "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]

set +e
run_native "$FIXTURES/http-server-feature-unavailable.ssc" >"$sandbox/http-server.out" 2>"$sandbox/http-server.err"
http_server_rc=$?
set -e
[[ $http_server_rc -ne 0 ]]
grep -F 'native HTTP server unavailable: useGzip requires the standard server-host SPI' \
  "$sandbox/http-server.err" >/dev/null

set +e
run_native "$ROOT/examples/imports.ssc" >"$sandbox/imports.out" 2>"$sandbox/imports.err"
imports_rc=$?
set -e
[[ $imports_rc -ne 0 ]]
# Multiline-lambda layout now parses this document completely; the remaining
# standard gap is the explicit math provider, not a parser sentinel/host crash.
grep -F 'unbound global: math' "$sandbox/imports.err" >/dev/null

set +e
run_native "$ROOT/examples/components-demo.ssc" >"$sandbox/components.out" 2>"$sandbox/components.err"
components_rc=$?
set -e
[[ $components_rc -ne 0 ]]
# Triple-quoted component templates now parse completely. The remaining gap is
# ordinary extension/runtime resolution, not a parser sentinel or host crash.
grep -F 'unbound global: s' "$sandbox/components.err" >/dev/null
if grep -E 'File name too long|StackOverflowError' "$sandbox/components.err" >/dev/null; then
  echo 'components native regression: recursive prose import or frontend stack overflow' >&2
  exit 1
fi

for source in dsl-mini-language.ssc graph-fullstack-rdf.ssc; do
  set +e
  run_native "$ROOT/examples/$source" >"$sandbox/$source.out" 2>"$sandbox/$source.err"
  source_rc=$?
  set -e
  [[ $source_rc -ne 0 ]]
  grep -F "$source" "$sandbox/$source.err" >/dev/null
  grep -F 'parser sentinel _err' "$sandbox/$source.err" >/dev/null
  if grep -E 'match: no arm|NoSuchFileException|StackOverflowError' "$sandbox/$source.err" >/dev/null; then
    echo "$source native frontend leaked a host exception" >&2
    exit 1
  fi
done

leaked=$(find "$sandbox/java-tmp" -mindepth 1 -maxdepth 1 \
  \( -name 'sscpkg-*' -o -name 'ssc-v2-plugins*' \) -print -quit)
if [[ -n "$leaked" ]]; then
  echo "native entry temp tree leaked after CLI exit: $leaked" >&2
  exit 1
fi

echo 'v2.1 native entry smoke: PASS'
