#!/usr/bin/env bash
#
# v21 typeclass dictionary smoke — the same two programs on the VM and the direct-ASM lane must
# print the frozen output, agree with each other, leak no `Stub`/`sentinel`, and say NOTHING on
# stderr. That last one is a real assertion, not tidiness: a clean run of a supported program is
# silent, so anything on stderr is the toolchain telling you something you have not read.
#
# ── 2026-08-14: THIS GATE FAILED FOR DAYS WITHOUT PRINTING ONE CHARACTER ──────────────────────────
#
# Every assertion below used to be a bare `[[ … ]]` under `set -e`, with both streams redirected
# into a `$tmp` that the EXIT trap deletes. So a failure was: exit 1, no stdout, no stderr, and the
# evidence removed on the way out. Measured in the orphan drain (tests/BUGS.md
# `orphaned-e2e-gates-52`): rc=1 and an EMPTY log, which is the least actionable failure a gate can
# produce — it costs a full re-run under `bash -x` just to learn WHICH line failed.
#
# It was right, too. The line that fired is `[[ ! -s …/focused.vm.err ]]`, and what was in that file
# is a real coverage gap nobody had seen:
#
#   ssc: F did not lower this file; compiled with the default front instead — … [match: no arm for Cons/2]
#
# Both programs — the fixture AND `examples/typeclass.ssc` — are declined by the F front and quietly
# compiled by the reference front instead. `ssc info --front-report` agrees: `GAP  match: no arm for
# Cons/2`. Filed as `f-front-cannot-lower-a-typeclass-dictionary-cons-arm`.
#
# So the fix here is NOT to relax the assertion. It is that a gate must say what it saw:
#   * an ERR trap names the line and the command that failed, for all of them at once;
#   * the stderr assertions PRINT the stderr they refuse to accept;
#   * the output assertions print a diff rather than "false".
# The `$tmp` teardown now runs after that reporting, so the evidence outlives the failure.
#
# NOTE, because it looks like a false positive and is not: a STALE toolchain also trips the stderr
# assertion, since `ssc` warns about it there. That is the right polarity — a verdict obtained with a
# launcher built from other sources is not a verdict — and now that the gate prints what it saw, the
# reader is told to rebuild instead of being handed a bare exit 1.
set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/v21-typeclass-dictionary.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

# Fires for every `set -e` death below, so an assertion that is a bare test still reports WHERE.
trap 'rc=$?; printf "FAIL v21-typeclass-dictionary-smoke: line %s: %s\n" "$LINENO" "$BASH_COMMAND" >&2; exit $rc' ERR

run_native() {
  PATH=/usr/bin:/bin SSC_NO_CDS=1 "$ROOT/bin/ssc-standard" run --native "$@"
}

# A silent run is the contract; print what broke it, because the message IS the finding.
expect_silent() {  # expect_silent <label> <errfile>
  [[ -s "$2" ]] || return 0
  printf 'FAIL %s: the run wrote to stderr, and a clean run of a supported program does not.\n' "$1" >&2
  sed 's/^/       | /' "$2" >&2
  exit 1        # exit, not return: the helper has already said everything the ERR trap would add
}

expect_output() {  # expect_output <label> <outfile> <expected>
  [[ $(<"$2") == "$3" ]] && return 0
  printf 'FAIL %s: output differs from the frozen expectation.\n' "$1" >&2
  diff <(printf '%s\n' "$3") "$2" | sed 's/^/       | /' >&2 || true
  exit 1        # exit, not return: see above
}

focused_expected=$'int\n0\n7\nstring\n[]\nleft-right\nint|string\n17\n21'
typeclass_expected=$'Int    : Int(42)\nBool   : yes\nString : \'hello\'\nsummon : Int(99)\n1 == 1  : true\n1 == 2  : false\nhi == hi: true\nhi == ho: false\n3 < 7   : true\n5 > 2   : true\nmin(3,7): 3\nmax(3,7): 7\nsorted  : 1, 2, 3, 5, 8, 9\nsum    : 15\nconcat : hello, world!\nrepeat : abababab\ndoubled: 2, 4, 6, 8, 10\nsquared: 1, 4, 9, 16, 25'

for mode in vm asm; do
  mode_args=()
  [[ $mode == asm ]] && mode_args+=(--bytecode)
  run_native "${mode_args[@]}" \
    "$ROOT/tests/fixtures/v21-native/typeclass-dictionary.ssc" \
    >"$tmp/focused.$mode.out" 2>"$tmp/focused.$mode.err"
  expect_output "focused/$mode" "$tmp/focused.$mode.out" "$focused_expected"
  expect_silent "focused/$mode" "$tmp/focused.$mode.err"

  run_native "${mode_args[@]}" "$ROOT/examples/typeclass.ssc" \
    >"$tmp/typeclass.$mode.out" 2>"$tmp/typeclass.$mode.err"
  expect_output "typeclass/$mode" "$tmp/typeclass.$mode.out" "$typeclass_expected"
  expect_silent "typeclass/$mode" "$tmp/typeclass.$mode.err"
done

cmp "$tmp/focused.vm.out" "$tmp/focused.asm.out"
cmp "$tmp/typeclass.vm.out" "$tmp/typeclass.asm.out"
! rg -q 'Stub|sentinel' "$tmp/focused.vm.out" "$tmp/typeclass.vm.out"

echo 'PASS v21-typeclass-dictionary-smoke'
