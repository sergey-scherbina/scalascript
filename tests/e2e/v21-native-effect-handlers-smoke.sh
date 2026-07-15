#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-native-effect-handlers.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

run_native() {
  PATH=/usr/bin:/bin SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" run --native "$@"
}

lower_native_source() {
  PATH=/usr/bin:/bin SSC_NO_CDS=1 java -Xss512m \
    -cp "$ROOT/bin/lib/standard/jars/*" ssc.cli run \
    "$ROOT/v2/bin/ssc1c.ssc0" "$@"
}

run_native_failure() {
  local label=$1 out=$2 err=$3 expected=$4
  shift 4
  local rc
  set +e
  run_native "$@" >"$out" 2>"$err"
  rc=$?
  set -e
  if [[ $rc -eq 0 || -s $out || $(<"$err") != "$expected" ]]; then
    printf 'FAIL %s\n  rc: %s\n  stdout: %s\n  stderr: %s\n  expected nonzero, empty stdout, exact stderr: %s\n' \
      "$label" "$rc" "$(<"$out")" "$(<"$err")" "$expected" >&2
    exit 1
  fi
}

fixture="$ROOT/tests/fixtures/v21-native/effect-handlers.ssc"
expected=$'owned\n7\n5\n6\n41\n33\n42\nList(2, 12)'

for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args+=(--bytecode)
  run_native "${mode_args[@]}" "$fixture" >"$tmp/$mode.out" 2>"$tmp/$mode.err"
  [[ $(<"$tmp/$mode.out") == "$expected" ]]
  [[ ! -s "$tmp/$mode.err" ]]
done

cmp "$tmp/vm.out" "$tmp/asm.out"
! rg -q 'Stub|unhandled runtime effect|unbound global' "$tmp/vm.out" "$tmp/asm.out"

residual_fixture="$ROOT/tests/fixtures/v21-native/effect-handler-residual-forwarding.ssc"
for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args+=(--bytecode)
  run_native "${mode_args[@]}" "$residual_fixture" \
    >"$tmp/residual.$mode.out" 2>"$tmp/residual.$mode.err"
  [[ $(<"$tmp/residual.$mode.out") == '108' ]]
  [[ ! -s "$tmp/residual.$mode.err" ]]
done
cmp "$tmp/residual.vm.out" "$tmp/residual.asm.out"

# The installed standard tier runs the checked-in self-hosted lowerer. Inspect
# its CoreIR as well as executing it: simple handler partial functions must keep
# a direct root Match, while a guarded total catch-all has selected-only root
# metadata because its synthetic terminal miss is unreachable.
simple_residual="$ROOT/tests/interop-conformance/probes/19-residual-forwarding-nested-handlers.ssc"
lower_native_source "$simple_residual" \
  >"$tmp/residual-simple.coreir" 2>"$tmp/residual-simple.err"
[[ ! -s "$tmp/residual-simple.err" ]]
rg -Fq \
  '(def inner (lam 0 (prim effect.handle (app (global prog)) (lam 1 (match (local 0)' \
  "$tmp/residual-simple.coreir"

lower_native_source "$residual_fixture" \
  >"$tmp/residual-total.coreir" 2>"$tmp/residual-total.err"
[[ ! -s "$tmp/residual-total.err" ]]
rg -Fq '(def inner (lam 0 (prim effect.handle' "$tmp/residual-total.coreir"
rg -Fq '__handler_dispatch_selected__' "$tmp/residual-total.coreir"
! rg -Fq '__handler_dispatch_miss__' "$tmp/residual-total.coreir"

one_shot_fixture="$ROOT/tests/fixtures/v21-native/effect-one-shot-violation.ssc"
one_shot_expected='error [ONESHOT_VIOLATION]: One-shot violation: One.op resumed more than once'
for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args+=(--bytecode)
  run_native_failure "one-shot $mode" \
    "$tmp/one-shot.$mode.out" "$tmp/one-shot.$mode.err" "$one_shot_expected" \
    "${mode_args[@]}" "$one_shot_fixture"
done
cmp "$tmp/one-shot.vm.err" "$tmp/one-shot.asm.err"

# Inspect the native lowerer's CoreIR, not only the final behavior: plain and
# multi declarations must select distinct primitives while retaining the same
# three-field Op protocol at runtime.
set +e
SSC_DUMP_IR=One_op run_native --bytecode "$one_shot_fixture" \
  >"$tmp/one-shot.dump.out" 2>"$tmp/one-shot.dump.err"
one_shot_dump_rc=$?
set -e
[[ $one_shot_dump_rc -ne 0 ]]
rg -Fq 'Prim(effect.perform.oneshot)' "$tmp/one-shot.dump.err"
! rg -Fq 'Prim(effect.perform){' "$tmp/one-shot.dump.err"

multi_shot_fixture="$ROOT/tests/fixtures/v21-native/effect-multi-shot-resume.ssc"
for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args+=(--bytecode)
  run_native "${mode_args[@]}" "$multi_shot_fixture" \
    >"$tmp/multi-shot.$mode.out" 2>"$tmp/multi-shot.$mode.err"
  [[ $(<"$tmp/multi-shot.$mode.out") == '3' ]]
  [[ ! -s "$tmp/multi-shot.$mode.err" ]]
done
cmp "$tmp/multi-shot.vm.out" "$tmp/multi-shot.asm.out"

SSC_DUMP_IR=Many_op run_native --bytecode "$multi_shot_fixture" \
  >"$tmp/multi-shot.dump.out" 2>"$tmp/multi-shot.dump.err"
[[ $(<"$tmp/multi-shot.dump.out") == '3' ]]
rg -Fq 'Prim(effect.perform){' "$tmp/multi-shot.dump.err"
! rg -Fq 'Prim(effect.perform.oneshot)' "$tmp/multi-shot.dump.err"

algebraic_expected=$'List(Hello, World!)\n0\n1\n[LOG] before increment\n[LOG] after increment\n10\n11\nList(11, 21, 12, 22, 13, 23)\ndone\n(42, List((info, step 1), (info, step 2)))\nList(1, 2)'
for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args+=(--bytecode)
  run_native "${mode_args[@]}" "$ROOT/examples/algebraic-effects.ssc" \
    >"$tmp/algebraic.$mode.out" 2>"$tmp/algebraic.$mode.err"
  [[ $(<"$tmp/algebraic.$mode.out") == "$algebraic_expected" ]]
  [[ ! -s "$tmp/algebraic.$mode.err" ]]
done
cmp "$tmp/algebraic.vm.out" "$tmp/algebraic.asm.out"

nested_fixture="$ROOT/tests/fixtures/v21-native/effect-runners-nested.ssc"
nested_expected=$'((7, List((info, inner))), List((info, outer-before), (info, outer-after)))\nList(1, 2)\nList(9)'
for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args+=(--bytecode)
  run_native "${mode_args[@]}" "$nested_fixture" \
    >"$tmp/nested.$mode.out" 2>"$tmp/nested.$mode.err"
  [[ $(<"$tmp/nested.$mode.out") == "$nested_expected" ]]
  [[ ! -s "$tmp/nested.$mode.err" ]]
done
cmp "$tmp/nested.vm.out" "$tmp/nested.asm.out"

echo 'PASS v21-native-effect-handlers-smoke'
