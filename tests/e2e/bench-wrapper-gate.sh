#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/ssc-bench-wrapper.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/bin"
export BENCH_TEST_LOG="$TMP/sbt.log"

cat > "$TMP/bin/sbt" <<'FAKE_SBT'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$BENCH_TEST_LOG"
if [[ "$*" == *'-lp'* ]]; then
  printf '%s\n' \
    '[info] scalascript.bench.ParserBench.parseActors' \
    '[info] scalascript.bench.InterpreterBench.arithLoop' \
    '[info] scalascript.bench.ParserBench.parseActors'
fi
FAKE_SBT
chmod +x "$TMP/bin/sbt"

fail_compare() {
  local label="$1"
  local expected="$2"
  local got="$3"
  printf '%s mismatch\nexpected=%s\ngot=%s\n' "$label" "$expected" "$got" >&2
  exit 1
}

run_bench() {
  PATH="$TMP/bin:$PATH" BENCH_WI=1 BENCH_MI=1 BENCH_F=1 \
    "$ROOT/scripts/bench" "$@"
}

compiler_classes='(ParserBench|TyperBench|UnifyBench|SsccFormatCompilerBench)'
for method in parseActors typeActors unifyDeep; do
  : > "$BENCH_TEST_LOG"
  run_bench compile "$method"
  got="$(sed -n '1p' "$BENCH_TEST_LOG")"
  expected="compilerBench/Jmh/run -wi 1 -i 1 -f 1 .*${compiler_classes}.*${method}.*"
  [[ "$got" == "$expected" ]] || fail_compare "compile $method command" "$expected" "$got"
done

: > "$BENCH_TEST_LOG"
run_bench compile-profile parseActors
got="$(sed -n '1p' "$BENCH_TEST_LOG")"
expected="compilerBench/Jmh/run -wi 1 -i 1 -f 1 -prof gc -prof \"jfr:configName=profile\" .*${compiler_classes}.*parseActors.*"
[[ "$got" == "$expected" ]] || fail_compare 'compile-profile command' "$expected" "$got"

: > "$BENCH_TEST_LOG"
run_bench profile arithLoop
got="$(sed -n '1p' "$BENCH_TEST_LOG")"
expected='interpreterBench/Jmh/run -wi 1 -i 1 -f 1 -prof gc -prof "jfr:configName=profile" .*InterpreterBench.*arithLoop.*'
[[ "$got" == "$expected" ]] || fail_compare 'interpreter profile command' "$expected" "$got"

: > "$BENCH_TEST_LOG"
got_list="$(run_bench list)"
got_command="$(sed -n '1p' "$BENCH_TEST_LOG")"
expected_command='interpreterBench/Jmh/run -lp .* compilerBench/Jmh/run -lp .*'
[[ "$got_command" == "$expected_command" ]] || \
  fail_compare 'list command' "$expected_command" "$got_command"

expected_list="$(printf '%s\n' \
  '[info] scalascript.bench.InterpreterBench.arithLoop' \
  '[info] scalascript.bench.ParserBench.parseActors')"
[[ "$got_list" == "$expected_list" ]] || fail_compare 'list output' "$expected_list" "$got_list"

printf 'bench-wrapper-gate: compiler/profile routing + combined unique list PASS\n'
