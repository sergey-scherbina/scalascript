#!/usr/bin/env bash
#
# js-std-process-gate — importing std/process on the js lane produces a bundle that PARSES.
#
# THE DEFECT WAS A SyntaxError, which is worse than a wrong answer because nothing runs at all:
#
#     [exec, ProcessOptions](std/process.ssc)
#     SyntaxError: Identifier 'exec' has already been declared
#
# `JsRuntimeFs.source` defines `function exec` in the preamble, and the import shim emitted
# `const exec = std.process.exec` beside it. `JsGen.declaredBindings` exists precisely to suppress
# that — it lists every std/fs name for exactly this reason — and `exec` was missing from it.
# `__spawnPid` was missing too, and is newer: the detached-spawn work added it to the preamble
# without adding it here, so this trap bit twice in one week.
#
# THE ROWS ARE THE THREE THINGS THAT NOW WORK, and the reason each is here rather than a single
# "it runs" check: `exec` proves the collision is gone, `stdin` proves the option added for
# `process-needs-a-stdin-pipe` actually reaches a child on THIS lane (it was written blind, because
# the lane could not run), and `spawn` proves `__spawnPid` resolves to the preamble function rather
# than to the shim's `const`.
#
# WHAT THIS GATE DELIBERATELY DOES NOT ASSERT: `cwd`, `env`, `timeout`, `inheritEnv`. They are
# accepted and dropped on this lane — `spawnSync` is called without an options object — and that is
# open as `js-exec-ignores-every-processoptions-field-but-stdin`. Measured here for the first time
# rather than read off the source, because until the SyntaxError was fixed the lane could not run at
# all and the entry was an inference. A row for them would fail forever; naming them is the honest
# alternative to a green that means less than it looks.
#
# COST: two node runs, ~10 s. No cargo, no sbt.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" && -x "$ssc" ]] || { echo "js-std-process-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }

if ! command -v node >/dev/null 2>&1; then
  echo "js-std-process-gate: [skip] node is not on PATH. That is a SKIP, not a pass." >&2
  exit 0
fi

sandbox=$(mktemp -d "$ROOT/examples/_jsproc.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/j.ssc" <<'SSC'
[exec, spawn, ProcessOptions](../../std/process.ssc)

def main(): Unit =
  val a = exec("echo", List("ran"), ProcessOptions())
  println("exec runs    : " + a.stdout.trim)
  val b = exec("cat", List(), ProcessOptions(None, Map(), None, true, Some("piped")))
  println("stdin reaches: " + b.stdout.trim)
  val c = spawn("sleep", List("2"), ProcessOptions())
  println("spawn pid    : " + (c.pid > 0))

main()
SSC

js_out=$(timeout 300 "$tools" run-js "$sandbox/j.ssc" 2>&1)
int_out=$(timeout 300 "$ssc" run "$sandbox/j.ssc" 2>/dev/null)

if printf '%s\n' "$js_out" | grep -q 'SyntaxError'; then
  echo "  ✗ the emitted bundle does not PARSE — a preamble name was redeclared by an import shim:"
  printf '%s\n' "$js_out" | grep -m2 -E 'SyntaxError|already been declared' | sed 's/^/      /'
  fails=$((fails + 1))
fi
if [[ -z "$int_out" ]]; then
  echo "  ✗ the interpreter produced nothing — the oracle is unusable, so this gate cannot decide" >&2
  exit 1
fi

echo "── std/process on the js lane: the bundle parses and the three fixed pieces work"
while IFS= read -r want; do
  label=${want%%:*}
  got=$(printf '%s\n' "$js_out" | grep -F "$label:" || true)
  if [[ "$got" == "$want" ]]; then
    echo "  ✓ ${want}"
  else
    echo "  ✗ ${label}: js '${got#*: }', interpreter '${want#*: }'"
    fails=$((fails + 1))
  fi
done <<< "$int_out"

# The oracle must still be right — two lanes that had both regressed would agree on every row above.
if printf '%s\n' "$int_out" | grep -q 'stdin reaches: piped'; then
  echo "  ✓ the oracle itself is right: a child does receive what stdin was given"
else
  echo "  ✗ the interpreter no longer answers 'piped' — the oracle regressed, so agreement between"
  echo "    the lanes proves nothing here"
  fails=$((fails + 1))
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "js-std-process-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "js-std-process-gate: PASS"
