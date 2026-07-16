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

cp "$ROOT/examples/sql-h2-quickstart.ssc" "$sandbox/other/sql-h2-quickstart.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/sql-h2-quickstart.ssc" -o "$sandbox/sql-fence.jar"
sql_fence_expected=$'active users: List(Map(ID -> 1, NAME -> Alice, EMAIL -> alice@example.com), Map(ID -> 2, NAME -> Bob, EMAIL -> bob@example.com))\nusers with id >= 1: List(Map(TOTAL -> 3))'
[[ $(PATH="$clean_path" java -jar "$sandbox/sql-fence.jar") == "$sql_fence_expected" ]]

cp "$ROOT/examples/typed-sql-crud.ssc" "$sandbox/other/typed-sql-crud.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/typed-sql-crud.ssc" -o "$sandbox/typed-sql.jar"
[[ $(PATH="$clean_path" java -jar "$sandbox/typed-sql.jar") == '1/1:Buy oat milk:true' ]]

cp "$ROOT/examples/yaml-parse.ssc" "$sandbox/other/yaml-parse.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/yaml-parse.ssc" -o "$sandbox/yaml.jar"
yaml_expected=$'Type:   YObj\nHost:   localhost\nPort:   8080\nDebug:  true\nTags:   web, api\n\nRound-trip:\ndebug: true\nhost: localhost\nport: 8080\n\nFrom fenced block:\nApp: MyApp'
[[ $(PATH="$clean_path" java -jar "$sandbox/yaml.jar") == "$yaml_expected" ]]

cp "$FIXTURES/dataset-provider.ssc" "$sandbox/other/dataset-provider.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/dataset-provider.ssc" -o "$sandbox/dataset.jar"
dataset_expected=$'6,7,4,5,4,5\n1,2\n3,1,2,2,4\n3,2\n(3, a),(1, b)\n(3, 0),(1, 1),(2, 2),(2, 3)\n(1, 3-1),(0, 2-2)\n(1, 4),(0, 4)\n1,2,2,3\ncount=4 sum=8 avg=2\nmin=1 max=3\ntop=3,2 ordered=1,2\nreduce=8 fold=18 first=Some(3)\ntwos=2\n(List(2, 2), List(3, 1))\n[3,1,2,2]\nMap(3 -> c, 1 -> a, 2 -> z)\n3,1,2\nparallel=333383335000 count=10000'
[[ $(PATH="$clean_path" java -jar "$sandbox/dataset.jar") == "$dataset_expected" ]]

cp "$ROOT/examples/generators.ssc" "$sandbox/other/generators.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/generators.ssc" -o "$sandbox/generator.jar"
generator_expected=$'List(1, 2, 3)\nSome(10)\nSome(20)\nNone\na\nb\nc\nList(2, 4, 6)\nList(0, 1, 1, 2, 3, 5, 8, 13)\nList(30, 40)\nList(1, 10, 2, 20, 3, 30)\nList((1, a), (2, b))\nList((hello, 0), (world, 1), (foo, 2))'
[[ $(PATH="$clean_path" java -jar "$sandbox/generator.jar") == "$generator_expected" ]]

cp "$ROOT/examples/async-demo.ssc" "$sandbox/other/async-demo.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/async-demo.ssc" -o "$sandbox/async.jar"
async_expected=$'6\nList(1, 4, 9, 16)\nafter delay\nList(20, 40, 60)\n56'
[[ $(PATH="$clean_path" java -jar "$sandbox/async.jar") == "$async_expected" ]]

cp "$ROOT/examples/actors-pingpong.ssc" "$sandbox/other/actors-pingpong.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/actors-pingpong.ssc" -o "$sandbox/actors.jar"
actors_expected=$'pong: one\npong: two\npong: three\nafter timeout: None\nbefore timeout: Some(got delivered)\ndone'
[[ $(PATH="$clean_path" java -jar "$sandbox/actors.jar") == "$actors_expected" ]]

cp "$ROOT/examples/actors-typed-remote-spawn.ssc" "$sandbox/other/actors-typed-remote-spawn.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/actors-typed-remote-spawn.ssc" -o "$sandbox/typed-actors.jar"
typed_actors_expected=$'true\ntrue\nlocal ref\nspawnRemote: pong'
[[ $(PATH="$clean_path" java -jar "$sandbox/typed-actors.jar") == "$typed_actors_expected" ]]

cp "$ROOT/examples/distributed-join.ssc" "$sandbox/other/distributed-join.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/distributed-join.ssc" -o "$sandbox/distributed-join.jar"
distributed_join_expected=$'o1 | c1 | Ada | 10\no2 | c2 | Bob | 20\no3 | c1 | Ada | 30'
[[ $(PATH="$clean_path" java -jar "$sandbox/distributed-join.jar" -- \
  "$FIXTURES/distributed-orders.csv" "$FIXTURES/distributed-customers.csv") == "$distributed_join_expected" ]]

cp "$ROOT/examples/distributed-log-aggregation.ssc" "$sandbox/other/distributed-log-aggregation.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/distributed-log-aggregation.ssc" -o "$sandbox/distributed-log.jar"
distributed_log_expected=$'payments: 2 errors\nsearch: 1 errors'
[[ $(PATH="$clean_path" java -jar "$sandbox/distributed-log.jar" -- \
  "$FIXTURES/distributed-app.log") == "$distributed_log_expected" ]]

cp "$ROOT/examples/graph-storage-interpreter.ssc" "$sandbox/other/graph-storage-interpreter.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/graph-storage-interpreter.ssc" -o "$sandbox/graph.jar"
[[ $(PATH="$clean_path" java -jar "$sandbox/graph.jar") == 'imports:b.ssc' ]]

cp "$ROOT/examples/dsl-mini-language.ssc" "$sandbox/other/dsl-mini-language.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/dsl-mini-language.ssc" -o "$sandbox/dsl-mini-language.jar"
dsl_mini_language_expected=$'=== success: 2 * x + y ===\nresult: 23\n=== name-resolve error: x + z ===\nPassError(name-resolve, undefined variable: z, <unknown>, 0, 0)\n=== type-check error: x / 0 ===\nPassError(type-check, division by zero, <unknown>, 0, 0)\n=== parse error: 1 @ 2 ===\nPassError(parse, cannot parse atom: 1 @ 2, <unknown>, 0, 0)\n=== pipeline report: 2 * x + y ===\n  [parse] ok\n  [name-resolve] ok\n  [type-check] ok\n  [evaluate] ok'
[[ $(PATH="$clean_path" java -jar "$sandbox/dsl-mini-language.jar") == "$dsl_mini_language_expected" ]]

cp "$ROOT/examples/custom-derives-mirror.ssc" "$sandbox/other/custom-derives-mirror.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/custom-derives-mirror.ssc" -o "$sandbox/custom-derives.jar"
custom_derives_expected=$'Person\nname|age\nString|Int\nname,age'
[[ $(PATH="$clean_path" java -jar "$sandbox/custom-derives.jar") == "$custom_derives_expected" ]]

cp "$ROOT/examples/direct-syntax-demo.ssc" "$sandbox/other/direct-syntax-demo.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/direct-syntax-demo.ssc" -o "$sandbox/direct-syntax.jar"
direct_syntax_expected=$'Some(Profile(User(Alice, 30), functional programmer))\nNone\nNone\nSome(50)\nNone\nS-red, S-blue, M-red, M-blue, L-red, L-blue\nSome(order confirmed)\nNone\nSome(30)\nNone\nSome(60)'
[[ $(PATH="$clean_path" java -jar "$sandbox/direct-syntax.jar") == "$direct_syntax_expected" ]]

cp "$FIXTURES/named-copy.ssc" "$sandbox/other/named-copy.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/named-copy.ssc" -o "$sandbox/named-copy.jar"
named_copy_expected=$'Alice|31|Boston\nAlicia|30|Paris\nBob|40|Boston\n1|9\nRCN\nAlina|30|Kyiv'
[[ $(PATH="$clean_path" java -jar "$sandbox/named-copy.jar") == "$named_copy_expected" ]]

cp "$ROOT/examples/lenses.ssc" "$sandbox/other/lenses.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/lenses.ssc" -o "$sandbox/lenses.jar"
lenses_expected=$'older   : Alice, 31\nrenamed : Bob, 40, Boston\n30\n99\n40\nBoston\nParis\nMain St\nMain St\nBroadway\nSome(Circle(5))\nNone\nCircle(10)\nRect(3, 4)\nSome(Boston)\nNone\nNone\nSome(Profile(Some(Address(Main St, Paris))))\nNone\nSome(Boston)\nList(Alice, Bob, Carol)\nList(31, 26, 36)\nList(TeamMember(anon, 30), TeamMember(anon, 25), TeamMember(anon, 35))'
[[ $(PATH="$clean_path" java -jar "$sandbox/lenses.jar") == "$lenses_expected" ]]

cp "$ROOT/examples/graph-rdf4j-http-storage.ssc" "$sandbox/other/graph-rdf4j-http-storage.ssc"
PATH="$clean_path" SSC_NO_CDS=1 "$ROOT/bin/ssc" build-jvm \
  "$sandbox/other/graph-rdf4j-http-storage.ssc" -o "$sandbox/graph-rdf4j.jar"
set +e
PATH="$clean_path" java -jar "$sandbox/graph-rdf4j.jar" \
  >"$sandbox/graph-rdf4j.out" 2>"$sandbox/graph-rdf4j.err"
graph_rdf_rc=$?
set -e
[[ $graph_rdf_rc -ne 0 ]]
[[ $(cat "$sandbox/graph-rdf4j.out") == 'Stored two books.' ]]
grep -F 'unhandled runtime effect: Sparql.select' \
  "$sandbox/graph-rdf4j.err" >/dev/null

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
  "$sandbox/sql-fence.jar" >"$sandbox/sql-fence.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/typed-sql.jar" >"$sandbox/typed-sql.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/yaml.jar" >"$sandbox/yaml.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/dataset.jar" >"$sandbox/dataset.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/generator.jar" >"$sandbox/generator.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/async.jar" >"$sandbox/async.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/actors.jar" >"$sandbox/actors.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/distributed-join.jar" >"$sandbox/distributed.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/graph.jar" >"$sandbox/graph.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/dsl-mini-language.jar" >"$sandbox/dsl-mini-language.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/custom-derives.jar" >"$sandbox/custom-derives.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/direct-syntax.jar" >"$sandbox/direct-syntax.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/named-copy.jar" >"$sandbox/named-copy.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps -verbose:class \
  "$sandbox/lenses.jar" >"$sandbox/lenses.jdeps"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/a/app.jar" >"$sandbox/app.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/sql.jar" >"$sandbox/sql.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/sql-fence.jar" >"$sandbox/sql-fence.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/typed-sql.jar" >"$sandbox/typed-sql.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/yaml.jar" >"$sandbox/yaml.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/dataset.jar" >"$sandbox/dataset.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/generator.jar" >"$sandbox/generator.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/async.jar" >"$sandbox/async.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/actors.jar" >"$sandbox/actors.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/distributed-join.jar" >"$sandbox/distributed.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/graph.jar" >"$sandbox/graph.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/dsl-mini-language.jar" >"$sandbox/dsl-mini-language.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/custom-derives.jar" >"$sandbox/custom-derives.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/direct-syntax.jar" >"$sandbox/direct-syntax.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/named-copy.jar" >"$sandbox/named-copy.modules"
"$jdeps_cmd" --multi-release base --ignore-missing-deps --print-module-deps \
  "$sandbox/lenses.jar" >"$sandbox/lenses.modules"

forbidden='scala[./](meta|tools)|dotty[./]tools|scala3-compiler|compiler-driver|javax[./]tools|java[.]compiler|jdk[.]compiler|ssc[./]bridge|scalascript[./](ast|frontend|interpreter)'
if grep -Ei "$forbidden" "$sandbox/entries" "$sandbox/entry.javap" \
    "$sandbox/app.jdeps" "$sandbox/sql.jdeps" "$sandbox/sql-fence.jdeps" \
    "$sandbox/typed-sql.jdeps" \
    "$sandbox/yaml.jdeps" "$sandbox/dataset.jdeps" \
    "$sandbox/generator.jdeps" "$sandbox/async.jdeps" "$sandbox/app.modules" \
    "$sandbox/sql.modules" "$sandbox/sql-fence.modules" "$sandbox/typed-sql.modules" \
    "$sandbox/yaml.modules" "$sandbox/dataset.modules" "$sandbox/generator.modules" \
    "$sandbox/async.modules" "$sandbox/actors.jdeps" "$sandbox/actors.modules" \
    "$sandbox/distributed.jdeps" "$sandbox/distributed.modules" \
    "$sandbox/graph.jdeps" "$sandbox/graph.modules" \
    "$sandbox/dsl-mini-language.jdeps" "$sandbox/dsl-mini-language.modules" \
    "$sandbox/custom-derives.jdeps" "$sandbox/custom-derives.modules" \
    "$sandbox/direct-syntax.jdeps" "$sandbox/direct-syntax.modules" \
    "$sandbox/named-copy.jdeps" "$sandbox/named-copy.modules" \
    "$sandbox/lenses.jdeps" "$sandbox/lenses.modules" >/dev/null; then
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
sql_fence_modules=$(tr -d '\r\n' <"$sandbox/sql-fence.modules")
typed_sql_modules=$(tr -d '\r\n' <"$sandbox/typed-sql.modules")
yaml_modules=$(tr -d '\r\n' <"$sandbox/yaml.modules")
dataset_modules=$(tr -d '\r\n' <"$sandbox/dataset.modules")
generator_modules=$(tr -d '\r\n' <"$sandbox/generator.modules")
async_modules=$(tr -d '\r\n' <"$sandbox/async.modules")
actors_modules=$(tr -d '\r\n' <"$sandbox/actors.modules")
distributed_modules=$(tr -d '\r\n' <"$sandbox/distributed.modules")
graph_modules=$(tr -d '\r\n' <"$sandbox/graph.modules")
dsl_mini_language_modules=$(tr -d '\r\n' <"$sandbox/dsl-mini-language.modules")
custom_derives_modules=$(tr -d '\r\n' <"$sandbox/custom-derives.modules")
direct_syntax_modules=$(tr -d '\r\n' <"$sandbox/direct-syntax.modules")
named_copy_modules=$(tr -d '\r\n' <"$sandbox/named-copy.modules")
lenses_modules=$(tr -d '\r\n' <"$sandbox/lenses.modules")
report_tmp="$sandbox/release.tsv"
{
  printf 'metric\tvalue\n'
  printf 'artifact.sha256\t%s\n' "$artifact_hash"
  printf 'artifact.bytes\t%s\n' "$artifact_bytes"
  printf 'artifact.reproducible\ttrue\n'
  printf 'artifact.source-path-independent\ttrue\n'
  printf 'app.modules\t%s\n' "$app_modules"
  printf 'sql.modules\t%s\n' "$sql_modules"
  printf 'sql-fence.modules\t%s\n' "$sql_fence_modules"
  printf 'typed-sql.modules\t%s\n' "$typed_sql_modules"
  printf 'yaml.modules\t%s\n' "$yaml_modules"
  printf 'dataset.modules\t%s\n' "$dataset_modules"
  printf 'generator.modules\t%s\n' "$generator_modules"
  printf 'async.modules\t%s\n' "$async_modules"
  printf 'actors.modules\t%s\n' "$actors_modules"
  printf 'distributed.modules\t%s\n' "$distributed_modules"
  printf 'graph.modules\t%s\n' "$graph_modules"
  printf 'dsl-mini-language.modules\t%s\n' "$dsl_mini_language_modules"
  printf 'custom-derives.modules\t%s\n' "$custom_derives_modules"
  printf 'direct-syntax.modules\t%s\n' "$direct_syntax_modules"
  printf 'named-copy.modules\t%s\n' "$named_copy_modules"
  printf 'lenses.modules\t%s\n' "$lenses_modules"
  printf 'compiler.commands.hidden\ttrue\n'
  printf 'forbidden.references\t0\n'
  printf 'hello.output\tHello, World!\n'
  printf 'import.output\t42\n'
  printf 'sql.output\t1/7/Ada/true\n'
  printf 'sql-fence.output\tactive-users/headcount/exact\n'
  printf 'typed-sql.output\t1/1:Buy oat milk:true\n'
  printf 'yaml.output\tType/Host/Port/Debug/Tags/Round-trip/App\n'
  printf 'dataset.output\tlocal/parallel/exact\n'
  printf 'generator.output\tpull/combinators/cancellation/exact\n'
  printf 'async.output\tsequential/parallel/nested/exact\n'
  printf 'actors.output\tmailbox/timeout/typed-loopback/exact\n'
  printf 'distributed.output\tlocal-map/shuffle-group/exact\n'
  printf 'graph.output\tlocal-property/rdf-boundary/exact\n'
  printf 'dsl-mini-language.output\t13-lines/exact\n'
  printf 'custom-derives.output\tMirror/derived/exact\n'
  printf 'direct-syntax.output\tOption/List/nested/exact\n'
  printf 'named-copy.output\tlabels/order/single-evaluation/exact\n'
  printf 'lenses.output\tLens/Optional/Traversal/Prism/23-lines/exact\n'
} >"$report_tmp"

if [[ -n $REPORT ]]; then
  mkdir -p "$(dirname "$REPORT")"
  cp "$report_tmp" "$REPORT"
fi
cat "$report_tmp"
echo 'PASS v21-build-jvm-release-gate'
