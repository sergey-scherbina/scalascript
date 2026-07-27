#!/usr/bin/env bash
# bytecode-fallback-visible — `run --bytecode` must never pass a VM run off as a bytecode run.
#
# BUGS.md `scljet-jdbc-facade-bytecode-class-too-large`. The v2 JVM bytecode backend emits one
# monolithic `ssc/gen/Entry`; large programs overflow the JVM class-size limit. `RunNativeV2` then
# falls back to the VM at LINK time, which is correct — no side effect has run yet. It used to do so
# SILENTLY, and that silence had a second victim: `scripts/bc-parity-sweep` diffs `run` against
# `run --bytecode`, so a fallback made it compare the VM against ITSELF and record `identical` —
# a certified parity result for a program the bytecode backend never compiled.
#
# The asserts below are therefore two-sided ON PURPOSE. A test that only checked "the marker appears
# on scljet-hello" would still pass if the marker were printed unconditionally, which would send
# every case to the skip bucket and hide real bytecode regressions instead. So we also assert a
# program that genuinely DOES compile to bytecode prints NO marker.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="$ROOT/bin/ssc"
MARKER='ssc: --bytecode fell back to the VM lane'
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/bytecode-fallback.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM
fails=0

if [[ ! -x "$ssc" ]]; then
  echo "SKIP bytecode-fallback-visible: $ssc not built (run scripts/sbtc installBin)"
  exit 0
fi

# run_lanes <file> — fills $vm_out/$bc_out/$bc_err/$bc_rc for one program.
run_lanes() {
  local f="$1"
  vm_out=$("$ssc" run "$f" 2>"$sandbox/vm.err")
  bc_out=$("$ssc" run --bytecode "$f" 2>"$sandbox/bc.err"); bc_rc=$?
  bc_err=$(cat "$sandbox/bc.err")
}

# expect_marker <label> <file> — the program is too large for one class: fallback, announced.
expect_marker() {
  local label="$1" f="$2"
  run_lanes "$f"
  if [[ $bc_rc -ne 0 ]]; then
    echo "FAIL [$label] --bytecode exited $bc_rc; the link-time fallback is supposed to keep it 0"
    echo "     stderr: $bc_err"; fails=$((fails+1)); return
  fi
  if ! grep -qF "$MARKER" <<<"$bc_err"; then
    echo "FAIL [$label] fell back to the VM without saying so — bc-parity-sweep will read this as parity"
    echo "     stderr was: ${bc_err:-<empty>}"; fails=$((fails+1)); return
  fi
  if [[ "$vm_out" != "$bc_out" ]]; then
    echo "FAIL [$label] fallback output differs from the VM lane it fell back to"; fails=$((fails+1)); return
  fi
  echo "ok   [$label] fallback announced: $(grep -F "$MARKER" <<<"$bc_err" | head -1)"
}

# expect_no_marker <label> <file> — a program the backend really compiles must stay unannounced.
expect_no_marker() {
  local label="$1" f="$2"
  run_lanes "$f"
  if [[ $bc_rc -ne 0 ]]; then
    echo "FAIL [$label] --bytecode exited $bc_rc"; echo "     stderr: $bc_err"; fails=$((fails+1)); return
  fi
  if grep -qF "$MARKER" <<<"$bc_err"; then
    echo "FAIL [$label] claims a fallback for a program that fits — the marker would be meaningless"
    echo "     stderr: $bc_err"; fails=$((fails+1)); return
  fi
  echo "ok   [$label] compiled on the bytecode lane, no marker"
}

cat >"$sandbox/small.ssc" <<'SSC'
val xs = List(1, 2, 3)
println(xs.map(x => x * 2).sum)
SSC

expect_no_marker small-program "$sandbox/small.ssc"

# The oversized case is the one the BUGS entry names. Skip rather than fail if the example is gone,
# so this gate cannot go red for a reason that has nothing to do with the fallback.
oversized="$ROOT/examples/scljet-hello.ssc"
if [[ -f "$oversized" ]]; then
  expect_marker scljet-hello "$oversized"
else
  echo "SKIP scljet-hello: $oversized not present"
fi

# The sweep must classify the fallback as a skip, not as `identical`. This is the assert that
# actually protects the parity number; the marker is only the mechanism it rides on.
if [[ -f "$oversized" && $fails -eq 0 ]]; then
  if grep -qF "$MARKER" "$ROOT/scripts/bc-parity-sweep"; then
    echo "ok   [sweep-wired] bc-parity-sweep keys on the same marker string"
  else
    echo "FAIL [sweep-wired] bc-parity-sweep does not match the marker — fallbacks will be recorded as parity"
    fails=$((fails+1))
  fi
fi

if [[ $fails -ne 0 ]]; then
  echo "bytecode-fallback-visible: $fails failure(s)"
  exit 1
fi
echo "bytecode-fallback-visible: all checks passed"
