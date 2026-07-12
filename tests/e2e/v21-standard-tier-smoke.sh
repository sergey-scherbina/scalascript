#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
STANDARD="$ROOT/bin/lib/standard"
FIXTURES="$ROOT/tests/fixtures/v21-native"
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-standard-tier.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

[[ -x "$ROOT/bin/ssc" && -x "$ROOT/bin/ssc-standard" && -x "$ROOT/bin/ssc-tools" ]]
[[ -f "$STANDARD/ssc.jar" && -d "$STANDARD/jars" ]]
[[ -f "$STANDARD/native-front/tower/bin/ssc1-run.ssc0" ]]
for launcher in "$ROOT/bin/ssc" "$ROOT/bin/ssc-standard"; do
  grep -F 'lib/standard/jars/*' "$launcher" >/dev/null
  grep -F 'scalascript.cli.StandardMain' "$launcher" >/dev/null
  if grep -F 'scalascript.cli.ssc' "$launcher" >/dev/null; then
    echo "v21-standard-tier-smoke: standard launcher references tools main: $launcher" >&2
    exit 1
  fi
done
grep -F 'lib/jars/*' "$ROOT/bin/ssc-tools" >/dev/null
grep -F 'scalascript.cli.ssc' "$ROOT/bin/ssc-tools" >/dev/null

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

[[ $(SSC_NO_CDS=1 "$ROOT/bin/ssc" run "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(SSC_NO_CDS=1 "$ROOT/bin/ssc" run --bytecode "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" run "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
yaml_expected=$'Type:   YObj\nHost:   localhost\nPort:   8080\nDebug:  true\nTags:   web, api\n\nRound-trip:\ndebug: true\nhost: localhost\nport: 8080\n\nFrom fenced block:\nApp: MyApp'
[[ $(SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" run "$ROOT/examples/yaml-parse.ssc") == "$yaml_expected" ]]
[[ $(SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" run --bytecode "$ROOT/examples/yaml-parse.ssc") == "$yaml_expected" ]]
plan=$(SSC_NO_CDS=1 "$ROOT/bin/ssc" info --execution-plan --bytecode)
[[ $plan == *'"tier":"standard"'* && $plan == *'"backend":"asm"'* && $plan == *'"compiler":false'* ]]

SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm "$FIXTURES/relative-main.ssc" \
  -o "$sandbox/import.jar" >/dev/null
[[ $(java -jar "$sandbox/import.jar") == '42' ]]

for surface in check compat v1 compile; do
  case "$surface" in
    check) args=(check "$ROOT/examples/hello.ssc") ;;
    compat) args=(run --compat-frontend "$ROOT/examples/hello.ssc") ;;
    v1) args=(run --v1 "$ROOT/examples/hello.ssc") ;;
    compile) args=(compile-jvm "$ROOT/examples/hello.ssc") ;;
  esac
  set +e
  SSC_NO_CDS=1 "$ROOT/bin/ssc" "${args[@]}" \
    >"$sandbox/$surface.out" 2>"$sandbox/$surface.err"
  tools_rc=$?
  set -e
  [[ $tools_rc -ne 0 && ! -s "$sandbox/$surface.out" ]]
  grep -F 'requires the optional ScalaScript tools/compatibility tier' \
    "$sandbox/$surface.err" >/dev/null
  grep -F 'run ssc-tools explicitly' "$sandbox/$surface.err" >/dev/null
done

[[ $(SSC_NO_CDS=1 "$ROOT/bin/ssc-tools" run --v1 "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(SSC_NO_CDS=1 "$ROOT/bin/ssc-tools" run --compat-frontend "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]

echo 'PASS v21-standard-tier-smoke'
