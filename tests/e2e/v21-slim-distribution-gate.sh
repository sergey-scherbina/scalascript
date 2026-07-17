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

fail_check() {
  local name="$1"
  shift
  printf 'v21-slim-distribution-gate: FAIL [%s]\n' "$name" >&2
  if [[ $# -gt 0 ]]; then
    printf '  %s\n' "$*" >&2
  fi
  exit 1
}

expect_eq() {
  local name="$1"
  local expected="$2"
  local actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    return
  fi

  local expected_file="$sandbox/expected.txt"
  local actual_file="$sandbox/actual.txt"
  printf '%s\n' "$expected" >"$expected_file"
  printf '%s\n' "$actual" >"$actual_file"
  printf 'v21-slim-distribution-gate: FAIL [%s]\n' "$name" >&2
  printf '  expected=%q\n' "$expected" >&2
  printf '  actual=%q\n' "$actual" >&2
  diff -u "$expected_file" "$actual_file" >&2 || true
  exit 1
}

expect_command() {
  local name="$1"
  local expected="$2"
  shift 2
  local stderr_file="$sandbox/command.stderr"
  local actual
  local rc
  set +e
  actual="$("$@" 2>"$stderr_file")"
  rc=$?
  set -e
  if [[ $rc -ne 0 ]]; then
    printf 'v21-slim-distribution-gate: FAIL [%s] exit expected=0 actual=%s\n' "$name" "$rc" >&2
    printf '  command:' >&2
    printf ' %q' "$@" >&2
    printf '\n  stderr:\n' >&2
    sed 's/^/    /' "$stderr_file" >&2
    exit 1
  fi
  expect_eq "$name" "$expected" "$actual"
}

# Same as expect_command, but compares the output as a SET of lines.
#
# Use ONLY where the program genuinely does not order its output, and say why at
# the call site. `actors-provider.ssc` prints from two unsynchronised actors: the
# worker prints when the scheduler runs it, while main prints after a *different*
# actor's reply arrives. Nothing sequences those two, so asserting a total order
# asserts something the program never promised — and it duly flaked in CI
# (`expected=$'worker: one\nSome(root: reply)'` / `actual=$'Some(root: reply)\nworker: one'`).
# Set semantics keeps everything the gate actually cares about: both lines, exactly
# once each. It does NOT weaken the check into "some output appeared".
expect_command_unordered() {
  local name="$1"
  local expected="$2"
  shift 2
  local stderr_file="$sandbox/command.stderr"
  local actual
  local rc
  set +e
  actual="$("$@" 2>"$stderr_file")"
  rc=$?
  set -e
  if [[ $rc -ne 0 ]]; then
    printf 'v21-slim-distribution-gate: FAIL [%s] exit expected=0 actual=%s\n' "$name" "$rc" >&2
    printf '  command:' >&2
    printf ' %q' "$@" >&2
    printf '\n  stderr:\n' >&2
    sed 's/^/    /' "$stderr_file" >&2
    exit 1
  fi
  expect_eq "$name (unordered)" \
    "$(printf '%s\n' "$expected" | LC_ALL=C sort)" \
    "$(printf '%s\n' "$actual" | LC_ALL=C sort)"
}

expect_nonzero() {
  local name="$1"
  local actual="$2"
  if [[ $actual -eq 0 ]]; then
    fail_check "$name" "expected a non-zero exit, actual=0"
  fi
}

expect_file_contains() {
  local name="$1"
  local needle="$2"
  local file="$3"
  if grep -F "$needle" "$file" >/dev/null; then
    return
  fi
  printf 'v21-slim-distribution-gate: FAIL [%s]\n' "$name" >&2
  printf '  expected file to contain=%q\n' "$needle" >&2
  printf '  actual file %s:\n' "$file" >&2
  sed 's/^/    /' "$file" >&2
  exit 1
}

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
if [[ ! -f "$standard/ssc.jar" || ! -d "$standard/jars" || ! -d "$standard/native-front" ]]; then
  fail_check "standard layout present" "expected ssc.jar, jars/, and native-front/ under $standard"
fi
if [[ -e "$slim/bin/lib/compiler" || -e "$slim/bin/lib/jars" || -e "$slim/bin/lib/ssc.jar" ]]; then
  fail_check "tools layout removed" "compiler/, lib/jars/, or lib/ssc.jar survived under $slim/bin/lib"
fi

forbidden_names='scalameta|scala3-compiler|scala3-interfaces|tasty-core|scala-asm|compiler-driver|scalascript-(core|frontend-core|backend-interpreter)|v2-(frontend-bridge|plugin-bridge)'
forbidden_jar_matches=$(find "$slim" -type f -name '*.jar' -exec basename {} \; | grep -Ei "$forbidden_names" || true)
if [[ -n $forbidden_jar_matches ]]; then
  fail_check "forbidden JAR names" "survivors: $forbidden_jar_matches"
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
forbidden_ref_matches=$(grep -Ei "$forbidden_refs" "$sandbox/standard.jdeps" || true)
if [[ -n $forbidden_ref_matches ]]; then
  fail_check "forbidden standard references" "$forbidden_ref_matches"
fi

run_standard() {
  PATH="$clean_path" SSC_NO_CDS=1 "$slim/bin/ssc" "$@"
}

expect_command "hello vm" 'Hello, World!' run_standard run "$ROOT/examples/hello.ssc"
expect_command "hello asm" 'Hello, World!' run_standard run --bytecode "$ROOT/examples/hello.ssc"
expect_command "relative import vm" '42' run_standard run "$FIXTURES/relative-main.ssc"
expect_command "argv vm" $'one\ntwo' run_standard run "$FIXTURES/argv.ssc" -- one two
expect_command "fs/os provider vm" $'one-two\nsample.txt\ntrue\nfallback\ntrue\nfalse' \
  run_standard run "$FIXTURES/fs-os-provider.ssc"
expect_command "json provider vm" \
  $'Ada\n2\ntrue\n1000.01\ntrue\n{"payload":[1,2]}\n[1,2,3]\n{"name":"A\\"B","on":true}' \
  run_standard run "$FIXTURES/json-provider.ssc"
expect_command "http response provider vm" \
  $'201\ntext/plain; charset=utf-8\nhello\n{"n":2,"ok":true}\npublic, max-age=60\nv1\nno-store' \
  run_standard run "$FIXTURES/http-response-provider.ssc"
expect_command "sql provider vm" $'1\n7\nAda\ntrue' run_standard run "$FIXTURES/sql-provider.ssc"
sql_quickstart_expected=$'active users: List(Map(ID -> 1, NAME -> Alice, EMAIL -> alice@example.com), Map(ID -> 2, NAME -> Bob, EMAIL -> bob@example.com))\nusers with id >= 1: List(Map(TOTAL -> 3))'
expect_command "SQL quickstart vm" "$sql_quickstart_expected" \
  run_standard run "$ROOT/examples/sql-h2-quickstart.ssc"
expect_command "SQL quickstart asm" "$sql_quickstart_expected" \
  run_standard run --bytecode "$ROOT/examples/sql-h2-quickstart.ssc"
expect_command "typed SQL CRUD vm" '1/1:Buy oat milk:true' \
  run_standard run "$ROOT/examples/typed-sql-crud.ssc"
expect_command "typed SQL CRUD asm" '1/1:Buy oat milk:true' \
  run_standard run --bytecode "$ROOT/examples/typed-sql-crud.ssc"
expect_command "state/effect provider vm" $'17\n20\n2\n101\n101\n2' \
  run_standard run "$FIXTURES/state-effect-provider.ssc"
lenses_expected=$'older   : Alice, 31\nrenamed : Bob, 40, Boston\n30\n99\n40\nBoston\nParis\nMain St\nMain St\nBroadway\nSome(Circle(5))\nNone\nCircle(10)\nRect(3, 4)\nSome(Boston)\nNone\nNone\nSome(Profile(Some(Address(Main St, Paris))))\nNone\nSome(Boston)\nList(Alice, Bob, Carol)\nList(31, 26, 36)\nList(TeamMember(anon, 30), TeamMember(anon, 25), TeamMember(anon, 35))'
expect_command "lenses vm" "$lenses_expected" run_standard run "$ROOT/examples/lenses.ssc"
expect_command "lenses asm" "$lenses_expected" \
  run_standard run --bytecode "$ROOT/examples/lenses.ssc"
dataset_expected=$'6,7,4,5,4,5\n1,2\n3,1,2,2,4\n3,2\n(3, a),(1, b)\n(3, 0),(1, 1),(2, 2),(2, 3)\n(1, 3-1),(0, 2-2)\n(1, 4),(0, 4)\n1,2,2,3\ncount=4 sum=8 avg=2\nmin=1 max=3\ntop=3,2 ordered=1,2\nreduce=8 fold=18 first=Some(3)\ntwos=2\n(List(2, 2), List(3, 1))\n[3,1,2,2]\nMap(3 -> c, 1 -> a, 2 -> z)\n3,1,2\nparallel=333383335000 count=10000'
expect_command "dataset provider vm" "$dataset_expected" run_standard run "$FIXTURES/dataset-provider.ssc"
expect_command "dataset provider asm" "$dataset_expected" \
  run_standard run --bytecode "$FIXTURES/dataset-provider.ssc"
generator_expected=$'List(1, 2, 3)\nSome(10)\nSome(20)\nNone\na\nb\nc\nList(2, 4, 6)\nList(0, 1, 1, 2, 3, 5, 8, 13)\nList(30, 40)\nList(1, 10, 2, 20, 3, 30)\nList((1, a), (2, b))\nList((hello, 0), (world, 1), (foo, 2))'
expect_command "generators example vm" "$generator_expected" run_standard run "$ROOT/examples/generators.ssc"
expect_command "generators example asm" "$generator_expected" \
  run_standard run --bytecode "$ROOT/examples/generators.ssc"
generator_provider_expected=$'Some(1)\nSome(2)\nNone\nList(0, 1, 2, 3, 4)\nList(1, 10, 2, 20)\nList((x, 0), (y, 1))'
expect_command "generator provider vm" "$generator_provider_expected" \
  run_standard run "$FIXTURES/generator-provider.ssc"
expect_command "generator provider asm" "$generator_provider_expected" \
  run_standard run --bytecode "$FIXTURES/generator-provider.ssc"
async_expected=$'6\nList(1, 4, 9, 16)\nafter delay\nList(20, 40, 60)\n56'
expect_command "async example vm" "$async_expected" run_standard run "$ROOT/examples/async-demo.ssc"
expect_command "async example asm" "$async_expected" \
  run_standard run --bytecode "$ROOT/examples/async-demo.ssc"
async_provider_expected=$'3\nList(30, 20, 10)\n8\nafter-zero'
expect_command "async provider vm" "$async_provider_expected" run_standard run "$FIXTURES/async-provider.ssc"
expect_command "async provider asm" "$async_provider_expected" \
  run_standard run --bytecode "$FIXTURES/async-provider.ssc"
actors_expected=$'pong: one\npong: two\npong: three\nafter timeout: None\nbefore timeout: Some(got delivered)\ndone'
expect_command "actors pingpong vm" "$actors_expected" run_standard run "$ROOT/examples/actors-pingpong.ssc"
expect_command "actors pingpong asm" "$actors_expected" \
  run_standard run --bytecode "$ROOT/examples/actors-pingpong.ssc"
typed_actors_expected=$'true\ntrue\nlocal ref\nspawnRemote: pong'
expect_command "typed actors vm" "$typed_actors_expected" \
  run_standard run "$ROOT/examples/actors-typed-remote-spawn.ssc"
expect_command "typed actors asm" "$typed_actors_expected" \
  run_standard run --bytecode "$ROOT/examples/actors-typed-remote-spawn.ssc"
# Unordered: two unsynchronised actors print these lines — see expect_command_unordered.
actors_provider_expected=$'worker: one\nSome(root: reply)'
expect_command_unordered "actors provider vm" "$actors_provider_expected" \
  run_standard run "$FIXTURES/actors-provider.ssc"
expect_command_unordered "actors provider asm" "$actors_provider_expected" \
  run_standard run --bytecode "$FIXTURES/actors-provider.ssc"
distributed_join_expected=$'o1 | c1 | Ada | 10\no2 | c2 | Bob | 20\no3 | c1 | Ada | 30'
expect_command "distributed join vm" "$distributed_join_expected" \
  run_standard run "$ROOT/examples/distributed-join.ssc" -- \
  "$FIXTURES/distributed-orders.csv" "$FIXTURES/distributed-customers.csv"
expect_command "distributed join asm" "$distributed_join_expected" \
  run_standard run --bytecode "$ROOT/examples/distributed-join.ssc" -- \
  "$FIXTURES/distributed-orders.csv" "$FIXTURES/distributed-customers.csv"
distributed_log_expected=$'payments: 2 errors\nsearch: 1 errors'
expect_command "distributed log vm" "$distributed_log_expected" \
  run_standard run "$ROOT/examples/distributed-log-aggregation.ssc" -- "$FIXTURES/distributed-app.log"
expect_command "distributed log asm" "$distributed_log_expected" \
  run_standard run --bytecode "$ROOT/examples/distributed-log-aggregation.ssc" -- \
  "$FIXTURES/distributed-app.log"
expect_command "graph storage vm" 'imports:b.ssc' \
  run_standard run "$ROOT/examples/graph-storage-interpreter.ssc"
expect_command "graph storage asm" 'imports:b.ssc' \
  run_standard run --bytecode "$ROOT/examples/graph-storage-interpreter.ssc"
for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args=(--bytecode)
  set +e
  run_standard run "${mode_args[@]}" "$ROOT/examples/graph-rdf4j-http-storage.ssc" \
    >"$sandbox/graph-rdf4j.$mode.out" 2>"$sandbox/graph-rdf4j.$mode.err"
  graph_rdf_rc=$?
  set -e
  expect_nonzero "graph RDF4J $mode rejects absent provider" "$graph_rdf_rc"
  expect_eq "graph RDF4J $mode preserves prefix output" 'Stored two books.' \
    "$(cat "$sandbox/graph-rdf4j.$mode.out")"
  expect_file_contains "graph RDF4J $mode names missing effect" \
    'unhandled runtime effect: Sparql.select' "$sandbox/graph-rdf4j.$mode.err"
done
yaml_expected=$'Type:   YObj\nHost:   localhost\nPort:   8080\nDebug:  true\nTags:   web, api\n\nRound-trip:\ndebug: true\nhost: localhost\nport: 8080\n\nFrom fenced block:\nApp: MyApp'
expect_command "YAML example vm" "$yaml_expected" run_standard run "$ROOT/examples/yaml-parse.ssc"
expect_command "YAML example asm" "$yaml_expected" \
  run_standard run --bytecode "$ROOT/examples/yaml-parse.ssc"
expect_command "UI provider vm" \
  $'<!doctype html>\n<main class="card" id="app"><h1>Hi &lt;native&gt;</h1>Ada<span>shown</span></main>' \
  run_standard run "$FIXTURES/ui-provider.ssc" -- "$sandbox/ui"

if ! run_standard build-jvm "$FIXTURES/relative-main.ssc" -o "$sandbox/import.jar" >/dev/null; then
  fail_check "build-jvm standard tier" "failed to build $sandbox/import.jar"
fi
expect_command "build-jvm artifact output" '42' env PATH="$clean_path" java -jar "$sandbox/import.jar"

set +e
run_standard run --v1 "$ROOT/examples/hello.ssc" \
  >"$sandbox/tools.out" 2>"$sandbox/tools.err"
tools_rc=$?
set -e
expect_nonzero "standard tier rejects --v1" "$tools_rc"
expect_file_contains "standard tier rejection diagnostic" \
  'requires the optional ScalaScript tools/compatibility tier' "$sandbox/tools.err"

set +e
PATH="$clean_path" SSC_NO_CDS=1 JAVA_TOOL_OPTIONS=-verbose:class \
  "$slim/bin/ssc" run "$ROOT/examples/hello.ssc" \
  >"$sandbox/classload.log" 2>&1
classload_rc=$?
set -e
if [[ $classload_rc -ne 0 ]]; then
  fail_check "classload probe exits zero" "exit=$classload_rc; log: $(tail -n 40 "$sandbox/classload.log")"
fi
forbidden_class_matches=$(
  grep -Ei 'scala[.]meta|dotty[.]tools|ssc[.]bridge|scalascript[.](ast|frontend|interpreter)' \
    "$sandbox/classload.log" || true
)
if [[ -n $forbidden_class_matches ]]; then
  fail_check "forbidden compatibility class loaded" "$forbidden_class_matches"
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
