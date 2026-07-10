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
[[ $(run_native "$FIXTURES/prefix-postfix.ssc") == $'true\n-1\n-2' ]]
[[ $(run_native --bytecode "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(run_native --bytecode "$FIXTURES/prefix-postfix.ssc") == $'true\n-1\n-2' ]]
[[ $(run_native --bytecode "$FIXTURES/fs-os-provider.ssc") == "$fs_os_expected" ]]
[[ $(PATH="$clean_path" JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$sandbox/java-tmp" SSC_NO_CDS=1 \
  "$ROOT/bin/ssc" run --compat-frontend "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]

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
