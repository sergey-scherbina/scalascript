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
  echo 'usage: v21-build-jvm-release-gate.sh [--report FILE]' >&2
  exit 2
fi

[[ -x "$ROOT/bin/ssc" ]] || {
  echo 'v21-build-jvm-release-gate: run scripts/sbtc "installBin" first' >&2
  exit 2
}

sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-build-jvm-release.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
mkdir -p "$sandbox/toolbin" "$sandbox/a/src" "$sandbox/b/src" "$sandbox/other"
for tool in bash dirname java; do
  target=$(command -v "$tool")
  ln -s "$target" "$sandbox/toolbin/$tool"
done
clean_path="$sandbox/toolbin"
for compiler in scala-cli scalac javac; do
  if PATH="$clean_path" command -v "$compiler" >/dev/null 2>&1; then
    echo "v21-build-jvm-release-gate: sanitized PATH contains $compiler" >&2
    exit 1
  fi
done

for side in a b; do
  cp "$FIXTURES/argv.ssc" "$sandbox/$side/src/argv.ssc"
  cp "$FIXTURES/std-crypto.ssc" "$sandbox/$side/src/std-crypto.ssc"
  (
    cd "$sandbox/$side/src"
    PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
      argv.ssc std-crypto.ssc -o "$sandbox/$side/app.jar"
  )
done
cmp -s "$sandbox/a/app.jar" "$sandbox/b/app.jar"

expected=$'one\ntwo\n2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824'
[[ $(PATH="$clean_path" java -jar "$sandbox/a/app.jar" -- one two) == "$expected" ]]

cp "$ROOT/examples/hello.ssc" "$sandbox/other/hello.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/hello.ssc" -o "$sandbox/hello.jar"
[[ $(PATH="$clean_path" java -jar "$sandbox/hello.jar") == 'Hello, World!' ]]

cp "$FIXTURES/relative-main.ssc" "$sandbox/other/relative-main.ssc"
cp "$FIXTURES/relative-helper.ssc" "$sandbox/other/relative-helper.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/relative-main.ssc" -o "$sandbox/import.jar"
[[ $(PATH="$clean_path" java -jar "$sandbox/import.jar") == '42' ]]

cp "$FIXTURES/sql-provider.ssc" "$sandbox/other/sql-provider.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/sql-provider.ssc" -o "$sandbox/sql.jar"
[[ $(PATH="$clean_path" java -jar "$sandbox/sql.jar") == $'1\n7\nAda\ntrue' ]]

cp "$ROOT/examples/yaml-parse.ssc" "$sandbox/other/yaml-parse.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/yaml-parse.ssc" -o "$sandbox/yaml.jar"
yaml_expected=$'Type:   YObj\nHost:   localhost\nPort:   8080\nDebug:  true\nTags:   web, api\n\nRound-trip:\ndebug: true\nhost: localhost\nport: 8080\n\nFrom fenced block:\nApp: MyApp'
[[ $(PATH="$clean_path" java -jar "$sandbox/yaml.jar") == "$yaml_expected" ]]

cp "$FIXTURES/dataset-provider.ssc" "$sandbox/other/dataset-provider.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/dataset-provider.ssc" -o "$sandbox/dataset.jar"
dataset_expected=$'6,7,4,5,4,5\n1,2\n3,1,2,2,4\n3,2\n(3, a),(1, b)\n(3, 0),(1, 1),(2, 2),(2, 3)\nPair(1, 3-1),Pair(0, 2-2)\n(1, 4),(0, 4)\n1,2,2,3\ncount=4 sum=8 avg=2\nmin=1 max=3\ntop=3,2 ordered=1,2\nreduce=8 fold=18 first=Some(3)\ntwos=2\n(List(2, 2), List(3, 1))\n[3,1,2,2]\nMap(3 -> c, 1 -> a, 2 -> z)\n3,1,2\nparallel=333383335000 count=10000'
[[ $(PATH="$clean_path" java -jar "$sandbox/dataset.jar") == "$dataset_expected" ]]

cp "$ROOT/examples/generators.ssc" "$sandbox/other/generators.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/generators.ssc" -o "$sandbox/generator.jar"
generator_expected=$'List(1, 2, 3)\nSome(10)\nSome(20)\nNone\na\nb\nc\nList(2, 4, 6)\nList(0, 1, 1, 2, 3, 5, 8, 13)\nList(30, 40)\nList(1, 10, 2, 20, 3, 30)\nList((1, a), (2, b))\nList((hello, 0), (world, 1), (foo, 2))'
[[ $(PATH="$clean_path" java -jar "$sandbox/generator.jar") == "$generator_expected" ]]

jar_cmd=$(command -v jar)
javap_cmd=$(command -v javap)
jdeps_cmd=$(command -v jdeps)
"$jar_cmd" tf "$sandbox/a/app.jar" >"$sandbox/entries"
LC_ALL=C sort -c "$sandbox/entries"
"$javap_cmd" -classpath "$sandbox/a/app.jar" -v -l ssc.gen.Entry >"$sandbox/entry.javap"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/a/app.jar" >"$sandbox/app.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/sql.jar" >"$sandbox/sql.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/yaml.jar" >"$sandbox/yaml.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/dataset.jar" >"$sandbox/dataset.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/generator.jar" >"$sandbox/generator.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/a/app.jar" >"$sandbox/app.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/sql.jar" >"$sandbox/sql.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/yaml.jar" >"$sandbox/yaml.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/dataset.jar" >"$sandbox/dataset.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/generator.jar" >"$sandbox/generator.modules"

forbidden='scala[./](meta|tools)|dotty[./]tools|scala3-compiler|compiler-driver|javax[./]tools|java[.]compiler|jdk[.]compiler|ssc[./]bridge|scalascript[./](ast|frontend|interpreter)'
if grep -Ei "$forbidden" "$sandbox/entries" "$sandbox/entry.javap" \
    "$sandbox/app.jdeps" "$sandbox/sql.jdeps" "$sandbox/yaml.jdeps" "$sandbox/dataset.jdeps" \
    "$sandbox/generator.jdeps" "$sandbox/app.modules" "$sandbox/sql.modules" \
    "$sandbox/yaml.modules" "$sandbox/dataset.modules" "$sandbox/generator.modules" >/dev/null; then
  echo 'v21-build-jvm-release-gate: forbidden standard-tier entry/reference/module' >&2
  exit 1
fi
if grep -F "$ROOT" "$sandbox/entry.javap" >/dev/null; then
  echo 'v21-build-jvm-release-gate: absolute checkout path in generated class' >&2
  exit 1
fi

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}
artifact_hash=$(hash_file "$sandbox/a/app.jar")
artifact_bytes=$(wc -c <"$sandbox/a/app.jar" | tr -d ' ')
app_modules=$(tr -d '\r\n' <"$sandbox/app.modules")
sql_modules=$(tr -d '\r\n' <"$sandbox/sql.modules")
yaml_modules=$(tr -d '\r\n' <"$sandbox/yaml.modules")
dataset_modules=$(tr -d '\r\n' <"$sandbox/dataset.modules")
generator_modules=$(tr -d '\r\n' <"$sandbox/generator.modules")
report_tmp="$sandbox/release.tsv"
{
  printf 'metric\tvalue\n'
  printf 'artifact.sha256\t%s\n' "$artifact_hash"
  printf 'artifact.bytes\t%s\n' "$artifact_bytes"
  printf 'artifact.reproducible\ttrue\n'
  printf 'artifact.source-path-independent\ttrue\n'
  printf 'app.modules\t%s\n' "$app_modules"
  printf 'sql.modules\t%s\n' "$sql_modules"
  printf 'yaml.modules\t%s\n' "$yaml_modules"
  printf 'dataset.modules\t%s\n' "$dataset_modules"
  printf 'generator.modules\t%s\n' "$generator_modules"
  printf 'compiler.commands.hidden\ttrue\n'
  printf 'forbidden.references\t0\n'
  printf 'hello.output\tHello, World!\n'
  printf 'import.output\t42\n'
  printf 'sql.output\t1/7/Ada/true\n'
  printf 'yaml.output\tType/Host/Port/Debug/Tags/Round-trip/App\n'
  printf 'dataset.output\tlocal/parallel/exact\n'
  printf 'generator.output\tpull/combinators/cancellation/exact\n'
} >"$report_tmp"

if [[ -n $REPORT ]]; then
  mkdir -p "$(dirname "$REPORT")"
  cp "$report_tmp" "$REPORT"
fi
cat "$report_tmp"
echo 'PASS v21-build-jvm-release-gate'
