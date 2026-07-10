#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
STANDARD="$ROOT/bin/lib/standard"
FIXTURES="$ROOT/tests/fixtures/v21-native"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-standard-tier.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

[[ -x "$ROOT/bin/ssc-standard" && -f "$STANDARD/ssc.jar" && -d "$STANDARD/jars" ]]
[[ -f "$STANDARD/native-front/tower/bin/ssc1-run.ssc0" ]]

forbidden='scala(meta|3-compiler)|scala3-compiler|compiler-driver|scalascript-(core|frontend-core|backend-interpreter)|v2-(frontend-bridge|plugin-bridge)'
if find "$STANDARD" -type f -name '*.jar' -exec basename {} \; | grep -Ei "$forbidden" >/dev/null; then
  echo 'v21-standard-tier-smoke: forbidden JAR family in standard layout' >&2
  exit 1
fi

jdeps --multi-release base --ignore-missing-deps -verbose:class "$STANDARD/ssc.jar" \
  >"$sandbox/standard.jdeps"
if grep -Ei 'scala[.]meta|dotty[.]tools|javax[.]tools|scalascript[.](ast|frontend|interpreter)|ssc[.]bridge' \
    "$sandbox/standard.jdeps" >/dev/null; then
  echo 'v21-standard-tier-smoke: forbidden reference in standard entry JAR' >&2
  exit 1
fi

[[ $(SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" run "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" run --bytecode "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
plan=$(SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" info --execution-plan --bytecode)
[[ $plan == *'"tier":"standard"'* && $plan == *'"backend":"asm"'* && $plan == *'"compiler":false'* ]]

SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" build-jvm "$FIXTURES/relative-main.ssc" \
  -o "$sandbox/import.jar" >/dev/null
[[ $(java -jar "$sandbox/import.jar") == '42' ]]

set +e
SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" check "$ROOT/examples/hello.ssc" \
  >"$sandbox/tools.out" 2>"$sandbox/tools.err"
tools_rc=$?
set -e
[[ $tools_rc -ne 0 ]]
grep -F 'requires the optional ScalaScript tools/compatibility tier' "$sandbox/tools.err" >/dev/null

# The pre-cutover compatibility launcher remains green until TI-8.
[[ $(SSC_NO_CDS=1 "$ROOT/bin/ssc" run "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(SSC_NO_CDS=1 "$ROOT/bin/ssc-tools" run --v1 "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" run --v1 "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]

echo 'PASS v21-standard-tier-smoke'
