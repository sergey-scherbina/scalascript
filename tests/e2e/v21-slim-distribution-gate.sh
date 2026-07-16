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
  echo 'usage: v21-slim-distribution-gate.sh [--report FILE]' >&2
  exit 2
fi
[[ -x "$ROOT/bin/ssc-standard" && -d "$ROOT/bin/lib/standard" ]] || {
  echo 'v21-slim-distribution-gate: run scripts/sbtc "installBin" first' >&2
  exit 2
}

sandbox=$(mktemp -d "${TMPDIR:-/tmp}/v21-slim-distribution.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
slim="$sandbox/slim"
mkdir -p "$slim/bin" "$sandbox/toolbin" "$sandbox/ui"
cp -R "$ROOT/bin/lib" "$slim/bin/lib"
cp "$ROOT/bin/ssc" "$slim/bin/ssc"
cp "$ROOT/bin/ssc-standard" "$slim/bin/ssc-standard"
chmod +x "$slim/bin/ssc" "$slim/bin/ssc-standard"

# Delete the complete compatibility/tools surface from the copied install.
rm -rf "$slim/bin/lib/jars" \
       "$slim/bin/lib/compiler" \
       "$slim/bin/lib/native-front" \
       "$slim/bin/lib/ssc.jar" \
       "$slim/bin/ssc-tools"

for tool in bash dirname java; do
  ln -s "$(command -v "$tool")" "$sandbox/toolbin/$tool"
done
clean_path="$sandbox/toolbin"
for compiler in scala-cli scalac javac; do
  if PATH="$clean_path" command -v "$compiler" >/dev/null 2>&1; then
    echo "v21-slim-distribution-gate: sanitized PATH contains $compiler" >&2
    exit 1
  fi
done

standard="$slim/bin/lib/standard"
[[ -f "$standard/ssc.jar" && -d "$standard/jars" && -d "$standard/native-front" ]]
[[ ! -e "$slim/bin/lib/compiler" && ! -e "$slim/bin/lib/jars" && ! -e "$slim/bin/lib/ssc.jar" ]]

forbidden_names='scalameta|scala3-compiler|scala3-interfaces|tasty-core|scala-asm|compiler-driver|scalascript-(core|frontend-core|backend-interpreter)|v2-(frontend-bridge|plugin-bridge)'
if find "$slim" -type f -name '*.jar' -exec basename {} \; | grep -Ei "$forbidden_names" >/dev/null; then
  echo 'v21-slim-distribution-gate: forbidden tools/compatibility JAR survived deletion' >&2
  exit 1
fi

# Merge every standard JAR into a scan-only archive so ServiceLoader providers
# and driver classes are roots too. Starting jdeps only from ssc.jar misses
# dynamically loaded dependency families (the TI-8 audit caught H2's optional
# javax.tools SourceCompiler this way).
scan_classes="$sandbox/scan-classes"
mkdir -p "$scan_classes"
jar_cmd=$(command -v jar)
while IFS= read -r jar_file; do
  (cd "$scan_classes" && "$jar_cmd" xf "$jar_file")
done < <(find "$standard" -type f -name '*.jar' | LC_ALL=C sort)
rm -f "$scan_classes/module-info.class" "$scan_classes/META-INF/MANIFEST.MF"
find "$scan_classes/META-INF/versions" -name module-info.class -delete 2>/dev/null || true
find "$scan_classes/META-INF" -type f \
  \( -name '*.SF' -o -name '*.RSA' -o -name '*.DSA' -o -name '*.EC' \) \
  -delete 2>/dev/null || true
"$jar_cmd" --create --file "$sandbox/standard-scan.jar" --no-manifest \
  -C "$scan_classes" .
jdeps --multi-release base --ignore-missing-deps --recursive -verbose:class \
  "$sandbox/standard-scan.jar" >"$sandbox/standard.jdeps"
forbidden_refs='scala[.]meta|scala[.]tools|dotty[.]tools|javax[.]tools|java[.]compiler|jdk[.]compiler|ssc[.]bridge|scalascript[.](ast|frontend|interpreter)'
if grep -Ei "$forbidden_refs" "$sandbox/standard.jdeps" >/dev/null; then
  echo 'v21-slim-distribution-gate: forbidden standard-tier reference/module' >&2
  exit 1
fi

run_standard() {
  PATH="$clean_path" SSC_NO_CDS=1 "$slim/bin/ssc" "$@"
}

[[ $(run_standard run "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(run_standard run --bytecode "$ROOT/examples/hello.ssc") == 'Hello, World!' ]]
[[ $(run_standard run "$FIXTURES/relative-main.ssc") == '42' ]]
[[ $(run_standard run "$FIXTURES/argv.ssc" -- one two) == $'one\ntwo' ]]
[[ $(run_standard run "$FIXTURES/fs-os-provider.ssc") == $'one-two\nsample.txt\ntrue\nfallback\ntrue\nfalse' ]]
[[ $(run_standard run "$FIXTURES/json-provider.ssc") == $'Ada\n2\ntrue\n1000.01\ntrue\n{"payload":[1,2]}\n[1,2,3]\n{"name":"A\\"B","on":true}' ]]
[[ $(run_standard run "$FIXTURES/http-response-provider.ssc") == $'201\ntext/plain; charset=utf-8\nhello\n{"n":2,"ok":true}\npublic, max-age=60\nv1\nno-store' ]]
[[ $(run_standard run "$FIXTURES/sql-provider.ssc") == $'1\n7\nAda\ntrue' ]]
sql_quickstart_expected=$'active users: List(Map(ID -> 1, NAME -> Alice, EMAIL -> alice@example.com), Map(ID -> 2, NAME -> Bob, EMAIL -> bob@example.com))\nusers with id >= 1: List(Map(TOTAL -> 3))'
[[ $(run_standard run "$ROOT/examples/sql-h2-quickstart.ssc") == "$sql_quickstart_expected" ]]
[[ $(run_standard run --bytecode "$ROOT/examples/sql-h2-quickstart.ssc") == "$sql_quickstart_expected" ]]
[[ $(run_standard run "$ROOT/examples/typed-sql-crud.ssc") == '1/1:Buy oat milk:true' ]]
[[ $(run_standard run --bytecode "$ROOT/examples/typed-sql-crud.ssc") == '1/1:Buy oat milk:true' ]]
[[ $(run_standard run "$FIXTURES/state-effect-provider.ssc") == $'17\n20\n2\n101\n101\n2' ]]
lenses_expected=$'older   : Alice, 31\nrenamed : Bob, 40, Boston\n30\n99\n40\nBoston\nParis\nMain St\nMain St\nBroadway\nSome(Circle(5))\nNone\nCircle(10)\nRect(3, 4)\nSome(Boston)\nNone\nNone\nSome(Profile(Some(Address(Main St, Paris))))\nNone\nSome(Boston)\nList(Alice, Bob, Carol)\nList(31, 26, 36)\nList(TeamMember(anon, 30), TeamMember(anon, 25), TeamMember(anon, 35))'
[[ $(run_standard run "$ROOT/examples/lenses.ssc") == "$lenses_expected" ]]
[[ $(run_standard run --bytecode "$ROOT/examples/lenses.ssc") == "$lenses_expected" ]]
dataset_expected=$'6,7,4,5,4,5\n1,2\n3,1,2,2,4\n3,2\n(3, a),(1, b)\n(3, 0),(1, 1),(2, 2),(2, 3)\n(1, 3-1),(0, 2-2)\n(1, 4),(0, 4)\n1,2,2,3\ncount=4 sum=8 avg=2\nmin=1 max=3\ntop=3,2 ordered=1,2\nreduce=8 fold=18 first=Some(3)\ntwos=2\n(List(2, 2), List(3, 1))\n[3,1,2,2]\nMap(3 -> c, 1 -> a, 2 -> z)\n3,1,2\nparallel=333383335000 count=10000'
[[ $(run_standard run "$FIXTURES/dataset-provider.ssc") == "$dataset_expected" ]]
[[ $(run_standard run --bytecode "$FIXTURES/dataset-provider.ssc") == "$dataset_expected" ]]
generator_expected=$'List(1, 2, 3)\nSome(10)\nSome(20)\nNone\na\nb\nc\nList(2, 4, 6)\nList(0, 1, 1, 2, 3, 5, 8, 13)\nList(30, 40)\nList(1, 10, 2, 20, 3, 30)\nList((1, a), (2, b))\nList((hello, 0), (world, 1), (foo, 2))'
[[ $(run_standard run "$ROOT/examples/generators.ssc") == "$generator_expected" ]]
[[ $(run_standard run --bytecode "$ROOT/examples/generators.ssc") == "$generator_expected" ]]
generator_provider_expected=$'Some(1)\nSome(2)\nNone\nList(0, 1, 2, 3, 4)\nList(1, 10, 2, 20)\nList((x, 0), (y, 1))'
[[ $(run_standard run "$FIXTURES/generator-provider.ssc") == "$generator_provider_expected" ]]
[[ $(run_standard run --bytecode "$FIXTURES/generator-provider.ssc") == "$generator_provider_expected" ]]
async_expected=$'6\nList(1, 4, 9, 16)\nafter delay\nList(20, 40, 60)\n56'
[[ $(run_standard run "$ROOT/examples/async-demo.ssc") == "$async_expected" ]]
[[ $(run_standard run --bytecode "$ROOT/examples/async-demo.ssc") == "$async_expected" ]]
async_provider_expected=$'3\nList(30, 20, 10)\n8\nafter-zero'
[[ $(run_standard run "$FIXTURES/async-provider.ssc") == "$async_provider_expected" ]]
[[ $(run_standard run --bytecode "$FIXTURES/async-provider.ssc") == "$async_provider_expected" ]]
actors_expected=$'pong: one\npong: two\npong: three\nafter timeout: None\nbefore timeout: Some(got delivered)\ndone'
[[ $(run_standard run "$ROOT/examples/actors-pingpong.ssc") == "$actors_expected" ]]
[[ $(run_standard run --bytecode "$ROOT/examples/actors-pingpong.ssc") == "$actors_expected" ]]
typed_actors_expected=$'true\ntrue\nlocal ref\nspawnRemote: pong'
[[ $(run_standard run "$ROOT/examples/actors-typed-remote-spawn.ssc") == "$typed_actors_expected" ]]
[[ $(run_standard run --bytecode "$ROOT/examples/actors-typed-remote-spawn.ssc") == "$typed_actors_expected" ]]
actors_provider_expected=$'worker: one\nSome(root: reply)'
[[ $(run_standard run "$FIXTURES/actors-provider.ssc") == "$actors_provider_expected" ]]
[[ $(run_standard run --bytecode "$FIXTURES/actors-provider.ssc") == "$actors_provider_expected" ]]
distributed_join_expected=$'o1 | c1 | Ada | 10\no2 | c2 | Bob | 20\no3 | c1 | Ada | 30'
[[ $(run_standard run "$ROOT/examples/distributed-join.ssc" -- \
  "$FIXTURES/distributed-orders.csv" "$FIXTURES/distributed-customers.csv") == "$distributed_join_expected" ]]
[[ $(run_standard run --bytecode "$ROOT/examples/distributed-join.ssc" -- \
  "$FIXTURES/distributed-orders.csv" "$FIXTURES/distributed-customers.csv") == "$distributed_join_expected" ]]
distributed_log_expected=$'payments: 2 errors\nsearch: 1 errors'
[[ $(run_standard run "$ROOT/examples/distributed-log-aggregation.ssc" -- \
  "$FIXTURES/distributed-app.log") == "$distributed_log_expected" ]]
[[ $(run_standard run --bytecode "$ROOT/examples/distributed-log-aggregation.ssc" -- \
  "$FIXTURES/distributed-app.log") == "$distributed_log_expected" ]]
[[ $(run_standard run "$ROOT/examples/graph-storage-interpreter.ssc") == 'imports:b.ssc' ]]
[[ $(run_standard run --bytecode "$ROOT/examples/graph-storage-interpreter.ssc") == 'imports:b.ssc' ]]
for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args=(--bytecode)
  set +e
  run_standard run "${mode_args[@]}" "$ROOT/examples/graph-rdf4j-http-storage.ssc" \
    >"$sandbox/graph-rdf4j.$mode.out" 2>"$sandbox/graph-rdf4j.$mode.err"
  graph_rdf_rc=$?
  set -e
  [[ $graph_rdf_rc -ne 0 ]]
  [[ $(cat "$sandbox/graph-rdf4j.$mode.out") == 'Stored two books.' ]]
  grep -F 'unhandled runtime effect: Sparql.select' \
    "$sandbox/graph-rdf4j.$mode.err" >/dev/null
done
yaml_expected=$'Type:   YObj\nHost:   localhost\nPort:   8080\nDebug:  true\nTags:   web, api\n\nRound-trip:\ndebug: true\nhost: localhost\nport: 8080\n\nFrom fenced block:\nApp: MyApp'
[[ $(run_standard run "$ROOT/examples/yaml-parse.ssc") == "$yaml_expected" ]]
[[ $(run_standard run --bytecode "$ROOT/examples/yaml-parse.ssc") == "$yaml_expected" ]]
ui_output=$(run_standard run "$FIXTURES/ui-provider.ssc" -- "$sandbox/ui")
[[ $ui_output == $'<!doctype html>\n<main class="card" id="app"><h1>Hi &lt;native&gt;</h1>Ada<span>shown</span></main>' ]]

run_standard build-jvm "$FIXTURES/relative-main.ssc" -o "$sandbox/import.jar" >/dev/null
[[ $(PATH="$clean_path" java -jar "$sandbox/import.jar") == '42' ]]

set +e
run_standard run --v1 "$ROOT/examples/hello.ssc" \
  >"$sandbox/tools.out" 2>"$sandbox/tools.err"
tools_rc=$?
set -e
[[ $tools_rc -ne 0 ]]
grep -F 'requires the optional ScalaScript tools/compatibility tier' "$sandbox/tools.err" >/dev/null

PATH="$clean_path" SSC_NO_CDS=1 JAVA_TOOL_OPTIONS=-verbose:class \
  "$slim/bin/ssc" run "$ROOT/examples/hello.ssc" \
  >"$sandbox/classload.log" 2>&1
if grep -Ei 'scala[.]meta|dotty[.]tools|ssc[.]bridge|scalascript[.](ast|frontend|interpreter)' \
    "$sandbox/classload.log" >/dev/null; then
  echo 'v21-slim-distribution-gate: forbidden compatibility class loaded' >&2
  exit 1
fi

jar_count=$(find "$standard" -type f -name '*.jar' | wc -l | tr -d ' ')
class_count=0
while IFS= read -r jar_file; do
  count=$(jar tf "$jar_file" | grep -c '[.]class$' || true)
  class_count=$((class_count + count))
done < <(find "$standard" -type f -name '*.jar' | LC_ALL=C sort)
standard_bytes=$(find "$standard" -type f -exec wc -c {} \; | awk '{sum += $1} END {print sum + 0}')
report_tmp="$sandbox/slim.tsv"
{
  printf 'metric\tvalue\n'
  printf 'standard.jar.count\t%s\n' "$jar_count"
  printf 'standard.class.count\t%s\n' "$class_count"
  printf 'standard.bytes\t%s\n' "$standard_bytes"
  printf 'default.launcher\tstandard\n'
  printf 'tools.present\tfalse\n'
  printf 'compiler.commands.hidden\ttrue\n'
  printf 'forbidden.references\t0\n'
  printf 'standard.vm\tpass\n'
  printf 'standard.asm\tpass\n'
  printf 'standard.providers\tfs-os/json/http/sql/ui/state/effect-runners/storage/reactive/yaml/content/dataset/generator/actors/distributed/graph/optics\n'
  printf 'standard.build-jvm\tpass\n'
} >"$report_tmp"
if [[ -n $REPORT ]]; then
  mkdir -p "$(dirname "$REPORT")"
  cp "$report_tmp" "$REPORT"
fi
cat "$report_tmp"
echo 'PASS v21-slim-distribution-gate'
