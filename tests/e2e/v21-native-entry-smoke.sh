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
ui_fetch_json_expected=$'body:{"name":"Acme \\"HQ\\"","n":5}\nfetch-json:ok'
[[ $(run_native "$ROOT/examples/ui-fetch-json.ssc") == "$ui_fetch_json_expected" ]]
index_expected=$'ScalaScript 0.1 is running!\nSquares: 1, 4, 9, 16, 25'
[[ $(run_native "$ROOT/examples/index.ssc") == "$index_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(run_native --bytecode "$FIXTURES/prefix-postfix.ssc") == $'true\n-1\n-2' ]]
[[ $(run_native --bytecode "$FIXTURES/interpolation-expression.ssc") == "$interpolation_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/multiline-function-param.ssc") == '[typed]' ]]
[[ $(run_native --bytecode "$ROOT/examples/ui-fetch-json.ssc") == "$ui_fetch_json_expected" ]]
[[ $(run_native --bytecode "$ROOT/examples/index.ssc") == "$index_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/fs-os-provider.ssc") == "$fs_os_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/json-provider.ssc") == "$json_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/http-response-provider.ssc") == "$http_response_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/sql-provider.ssc") == "$sql_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/ui-provider.ssc" -- "$ui_asm") == "$ui_expected" ]]
[[ $(<"$ui_asm/index.html") == "$ui_expected" ]]
[[ $(run_native --bytecode "$FIXTURES/state-effect-provider.ssc") == "$state_expected" ]]
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
run_native "$ROOT/examples/imports.ssc" >"$sandbox/sentinel.out" 2>"$sandbox/sentinel.err"
sentinel_rc=$?
set -e
[[ $sentinel_rc -ne 0 ]]
grep -F 'parser sentinel _err' "$sandbox/sentinel.err" >/dev/null

set +e
run_native "$ROOT/examples/components-demo.ssc" >"$sandbox/components.out" 2>"$sandbox/components.err"
components_rc=$?
set -e
[[ $components_rc -ne 0 ]]
grep -F 'parser sentinel _err' "$sandbox/components.err" >/dev/null
if grep -E 'File name too long|StackOverflowError' "$sandbox/components.err" >/dev/null; then
  echo 'components native regression: recursive prose import or frontend stack overflow' >&2
  exit 1
fi

leaked=$(find "$sandbox/java-tmp" -mindepth 1 -maxdepth 1 \
  \( -name 'sscpkg-*' -o -name 'ssc-v2-plugins*' \) -print -quit)
if [[ -n "$leaked" ]]; then
  echo "native entry temp tree leaked after CLI exit: $leaked" >&2
  exit 1
fi

echo 'v2.1 native entry smoke: PASS'
