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
#
# BOTH LANES ARE BOUNDED, and that is not defensive tidiness — it is required as of 2026-08-10.
# This gate's oversized subject is `scljet-hello`, and until today it failed in SECONDS: the
# fallback fired and the VM lane it fell back to died immediately on `app: not a function`
# (v2/BUGS.md scljet-app-not-a-function-after-the-concat-fix). Fixing that made the program RUN —
# and running is where the cost is. Measured the same day: 39 s on the legacy front, over 1800 s on
# F, which is the front `ssc run` uses by default. Unbounded, this gate would run it TWICE and hang
# for over an hour.
#
# A timeout is reported as a NAMED failure, never as a pass. "Slow" and "correct" are different
# answers and a gate that conflates them is how a perf wall hides behind a green row.
BC_FALLBACK_TIMEOUT=${BC_FALLBACK_TIMEOUT:-420}
# `FRONT` selects the front for one subject. Default is empty = whatever `ssc run` picks (F today),
# because that is the lane users get. The oversized subject overrides it to `legacy` and says why.
run_lanes() {
  local f="$1"
  vm_out=$(SSC_FRONT=${FRONT:-} timeout "$BC_FALLBACK_TIMEOUT" "$ssc" run "$f" 2>"$sandbox/vm.err"); vm_rc=$?
  bc_out=$(SSC_FRONT=${FRONT:-} timeout "$BC_FALLBACK_TIMEOUT" "$ssc" run --bytecode "$f" 2>"$sandbox/bc.err"); bc_rc=$?
  bc_err=$(cat "$sandbox/bc.err")
}

# timed_out <label> — true when either lane hit the cap, and says which.
timed_out() {
  local label="$1"
  if [[ $vm_rc -eq 124 || $bc_rc -eq 124 ]]; then
    echo "FAIL [$label] exceeded ${BC_FALLBACK_TIMEOUT}s (vm_rc=$vm_rc bc_rc=$bc_rc) — a TIMEOUT, not a verdict."
    echo "     The F front costs >46x the legacy front on this subject (39s vs >1800s, measured"
    echo "     2026-08-10). See v2/BUGS.md f-front-compile-cost-7x-on-scljet. Raising"
    echo "     BC_FALLBACK_TIMEOUT hides it; it is not a fix."
    return 0
  fi
  return 1
}

# expect_marker <label> <file> — the program is too large for one class: fallback, announced.
expect_marker() {
  local label="$1" f="$2"
  run_lanes "$f"
  if timed_out "$label"; then fails=$((fails+1)); return; fi
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
  if timed_out "$label"; then fails=$((fails+1)); return; fi
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

# THE OVERSIZED CASE IS GONE, and this is the commit that removed it. `ssc/gen/Entry` now spills
# into `Entry$1`, `Entry$2`, … at 12 000 methods a class, so the constant pool per class is bounded
# and `ClassTooLargeException` is unreachable BY CONSTRUCTION — not merely absent for the examples
# that happen to be in the corpus today. `scljet-hello` compiles and runs on the bytecode lane; it
# used to fall back, and asserting that it still does would be asserting the bug.
#
# So the subject flips from `expect_marker` to `expect_no_marker`. That is a WEAKER gate than it
# looks: what it now pins is that the biggest program in the corpus really does compile, which is
# the property the split has to keep.
#
# RUNS ON THE DEFAULT FRONT AS OF 2026-08-13 — the override is gone, and the line above that said to
# delete it "when the F cost is fixed" is the reason. It read: 39 s on legacy against >1800 s on F for
# this file, so a default-front run reported a TIMEOUT and tested nothing about the split.
# `f-front-compile-cost-7x-on-scljet` was that cost — generated bytecode asked for every prim BY NAME,
# 161 M ConcurrentHashMap lookups in one run — and `ee53eff5d` caches the resolved slot per call site.
# Re-measured here on the front `ssc run` actually picks:
#
#     ssc run              20 s      ssc run --bytecode   20 s      outputs identical, no marker
#
# So this subject now covers the lane users get rather than a lane chosen to dodge a perf wall.
# Keep the cap: it is what turns a regression of that fix into a NAMED failure instead of an hour.
oversized="$ROOT/examples/scljet-hello.ssc"
if [[ -f "$oversized" ]]; then
  expect_no_marker scljet-hello "$oversized"
else
  echo "SKIP scljet-hello: $oversized not present"
fi

# AND THE MARKER STILL HAS TO WORK, which is what this gate is actually for — a silent fallback is
# what let `bc-parity-sweep` certify VM-against-VM parity. With the class-size route closed, the
# remaining source is `Unsupported`: a construct the backend cannot compile at all. Left UNWIRED
# deliberately rather than guessed at — naming a construct here that JvmByteGen later learns to
# compile would turn this into a gate that fails for being fixed, which is the failure mode the
# oversized subject just demonstrated. Whoever adds it should pick the construct from
# `throw new Unsupported` in JvmByteGen and pin it with a comment saying it is expected to age.
echo "note: the marker's remaining source is Unsupported, not class size — no subject pinned yet"


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
