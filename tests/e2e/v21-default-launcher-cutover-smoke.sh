#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
FIXTURES="$ROOT/tests/fixtures/v21-native"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-default-launcher.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
prefix="$sandbox/prefix"

[[ -x "$ROOT/bin/ssc" && -x "$ROOT/bin/ssc-tools" ]] || {
  echo 'v21-default-launcher-cutover-smoke: run scripts/sbtc "installBin" first' >&2
  exit 2
}
grep -F 'lib/standard/jars/*' "$ROOT/install.sh" >/dev/null
grep -F 'scalascript.cli.StandardMain' "$ROOT/install.sh" >/dev/null

SSC_NO_CDS=1 "$ROOT/bin/ssc-tools" install --prefix "$prefix" \
  >"$sandbox/install.out"

for launcher in "$prefix/bin/ssc" "$prefix/bin/ssc-standard"; do
  [[ -x $launcher ]]
  grep -F 'bin/lib/standard/jars/*' "$launcher" >/dev/null
  grep -F 'scalascript.cli.StandardMain' "$launcher" >/dev/null
  if grep -F 'scalascript.cli.ssc' "$launcher" >/dev/null; then
    echo "v21-default-launcher-cutover-smoke: installed standard launcher references tools main: $launcher" >&2
    exit 1
  fi
done
grep -F 'bin/lib/jars/*' "$prefix/bin/ssc-tools" >/dev/null
grep -F 'scalascript.cli.ssc' "$prefix/bin/ssc-tools" >/dev/null

installed="$prefix/bin/ssc"
tools="$prefix/bin/ssc-tools"
[[ $(SSC_NO_CDS=1 "$installed" run "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(SSC_NO_CDS=1 "$installed" run --bytecode "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(SSC_NO_CDS=1 "$installed" "$FIXTURES/relative-main.ssc") == '42' ]]
SSC_NO_CDS=1 "$installed" build-jvm "$FIXTURES/relative-main.ssc" \
  -o "$sandbox/app.jar" >/dev/null
[[ $(java -jar "$sandbox/app.jar") == '42' ]]

for flag in --v1 --compat-frontend; do
  set +e
  SSC_NO_CDS=1 "$installed" run "$flag" "$ROOT/examples/hello.ssc" \
    >"$sandbox/${flag#--}.out" 2>"$sandbox/${flag#--}.err"
  rc=$?
  set -e
  [[ $rc -ne 0 && ! -s "$sandbox/${flag#--}.out" ]]
  grep -F 'run ssc-tools explicitly' "$sandbox/${flag#--}.err" >/dev/null
done

[[ $(SSC_NO_CDS=1 "$tools" run --v1 "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(SSC_NO_CDS=1 "$tools" run --compat-frontend "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]

echo 'PASS v21-default-launcher-cutover-smoke'
