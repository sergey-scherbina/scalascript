#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
FIXTURES="$ROOT/tests/fixtures/v21-native"
REPORT=""
if [[ ${1:-} == "--report" && -n ${2:-} ]]; then
  REPORT=$2
  shift 2
fi
if [[ $# -ne 0 ]]; then
  echo 'usage: v21-jre-module-gate.sh [--report FILE]' >&2
  exit 2
fi
[[ -x "$ROOT/bin/ssc-standard" && -d "$ROOT/bin/lib/standard" ]] || {
  echo 'v21-jre-module-gate: run scripts/sbtc "installBin" first' >&2
  exit 2
}

sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-jre-module.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
slim="$sandbox/slim"
standard="$slim/bin/lib/standard"
mkdir -p "$slim/bin/lib" "$sandbox/toolbin" "$sandbox/scan-classes" "$sandbox/ui"
cp -R "$ROOT/bin/lib/standard" "$slim/bin/lib/standard"
cp "$ROOT/bin/ssc-standard" "$slim/bin/ssc-standard"
chmod +x "$slim/bin/ssc-standard"

# Build a scan-only union JAR. This makes every standard dependency a jdeps
# root, including ServiceLoader providers and JDBC drivers that are not
# statically reachable from StandardMain.
jar_cmd=$(command -v jar)
jdeps_cmd=$(command -v jdeps)
while IFS= read -r jar_file; do
  (cd "$sandbox/scan-classes" && "$jar_cmd" xf "$jar_file")
done < <(find "$standard" -type f -name '*.jar' | LC_ALL=C sort)
rm -f "$sandbox/scan-classes/module-info.class" \
      "$sandbox/scan-classes/META-INF/MANIFEST.MF"
find "$sandbox/scan-classes/META-INF/versions" -name module-info.class \
  -delete 2>/dev/null || true
find "$sandbox/scan-classes/META-INF" -type f \
  \( -name '*.SF' -o -name '*.RSA' -o -name '*.DSA' -o -name '*.EC' \) \
  -delete 2>/dev/null || true
"$jar_cmd" --create --file "$sandbox/standard-scan.jar" --no-manifest \
  -C "$sandbox/scan-classes" .
"$jdeps_cmd" --multi-release base --ignore-missing-deps --recursive \
  -verbose:class "$sandbox/standard-scan.jar" >"$sandbox/standard.jdeps"
runtime_modules=$("$jdeps_cmd" --multi-release base --ignore-missing-deps \
  --print-module-deps "$sandbox/standard-scan.jar" | tr -d '\r\n')

forbidden_refs='scala[.]meta|scala[.]tools|dotty[.]tools|javax[.]tools|java[.]compiler|jdk[.]compiler|ssc[.]bridge|scalascript[.](ast|frontend|interpreter)'
if [[ -z $runtime_modules ]] || grep -Ei "$forbidden_refs" "$sandbox/standard.jdeps" >/dev/null; then
  echo 'v21-jre-module-gate: forbidden standard-tier reference/module' >&2
  exit 1
fi
case ",$runtime_modules," in
  *,java.compiler,*|*,jdk.compiler,*)
    echo "v21-jre-module-gate: compiler module in derived set: $runtime_modules" >&2
    exit 1
    ;;
esac

real_java=$(command -v java)
for compiler_module in java.compiler jdk.compiler; do
  set +e
  "$real_java" --limit-modules "$runtime_modules" \
    --describe-module "$compiler_module" \
    >"$sandbox/$compiler_module.out" 2>"$sandbox/$compiler_module.err"
  module_rc=$?
  set -e
  if [[ $module_rc -eq 0 ]] || ! grep -F "$compiler_module not found" \
      "$sandbox/$compiler_module.out" "$sandbox/$compiler_module.err" >/dev/null; then
    echo "v21-jre-module-gate: $compiler_module remained resolvable" >&2
    exit 1
  fi
done

ln -s "$(command -v bash)" "$sandbox/toolbin/bash"
ln -s "$(command -v dirname)" "$sandbox/toolbin/dirname"
printf '#!/bin/sh\nexec "%s" --limit-modules "%s" "$@"\n' \
  "$real_java" "$runtime_modules" >"$sandbox/toolbin/java"
chmod +x "$sandbox/toolbin/java"
clean_path="$sandbox/toolbin"
for compiler in scala-cli scalac javac; do
  if PATH="$clean_path" command -v "$compiler" >/dev/null 2>&1; then
    echo "v21-jre-module-gate: sanitized PATH contains $compiler" >&2
    exit 1
  fi
done

run_standard() {
  PATH="$clean_path" SSC_NO_CDS=1 "$slim/bin/ssc-standard" "$@"
}

[[ $(run_standard run "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(run_standard run --bytecode "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(run_standard run "$FIXTURES/relative-main.ssc") == '42' ]]
[[ $(run_standard run "$FIXTURES/argv.ssc" -- one two) == $'one\ntwo' ]]
[[ $(run_standard run "$FIXTURES/fs-os-provider.ssc") == $'one-two\nsample.txt\ntrue\nfallback\ntrue\nfalse' ]]
[[ $(run_standard run "$FIXTURES/json-provider.ssc") == $'Ada\n2\ntrue\n1000.01\ntrue\n{"payload":[1,2]}\n[1,2,3]\n{"name":"A\\"B","on":true}' ]]
[[ $(run_standard run "$FIXTURES/http-response-provider.ssc") == $'201\ntext/plain; charset=utf-8\nhello\n{"n":2,"ok":true}\npublic, max-age=60\nv1\nno-store' ]]
[[ $(run_standard run "$FIXTURES/sql-provider.ssc") == $'1\n7\nAda\ntrue' ]]
[[ $(run_standard run "$FIXTURES/state-effect-provider.ssc") == $'17\n20\n2\n101\n101\n2' ]]
ui_output=$(run_standard run "$FIXTURES/ui-provider.ssc" -- "$sandbox/ui")
[[ $ui_output == $'<!doctype html>\n<main class="card" id="app"><h1>Hi &lt;native&gt;</h1>Ada<span>shown</span></main>' ]]

run_standard build-jvm "$FIXTURES/sql-provider.ssc" -o "$sandbox/sql.jar" >/dev/null
[[ $(PATH="$clean_path" java -jar "$sandbox/sql.jar") == $'1\n7\nAda\ntrue' ]]

report_tmp="$sandbox/jre-module.tsv"
{
  printf 'metric\tvalue\n'
  printf 'runtime.modules\t%s\n' "$runtime_modules"
  printf 'java.compiler.available\tfalse\n'
  printf 'jdk.compiler.available\tfalse\n'
  printf 'compiler.commands.hidden\ttrue\n'
  printf 'forbidden.references\t0\n'
  printf 'standard.vm\tpass\n'
  printf 'standard.asm\tpass\n'
  printf 'standard.providers\tfs-os/json/http/sql/ui/state/storage\n'
  printf 'standard.build-jvm\tpass\n'
} >"$report_tmp"
if [[ -n $REPORT ]]; then
  mkdir -p "$(dirname "$REPORT")"
  cp "$report_tmp" "$REPORT"
fi
cat "$report_tmp"
echo 'PASS v21-jre-module-gate'
