#!/usr/bin/env bash
# Every lane-selection flag `run` accepts must be PARSED as a flag, on every CLI entry.
#
# Why this gate exists. `Main.scala`'s `run` parser ends in `case f => fileArgs += f`, so a flag it
# does not know is not rejected — it becomes a FILE PATH. `--interpret` was missing there while
# `StandardMain` had accepted it all along, and the failure surfaced as:
#
#   java.io.FileNotFoundException: native frontend input not found: --interpret
#
# which reads like a missing file, names no flag, and points at the frontend rather than at argument
# parsing. Nothing caught it: it only ever ran on the release qualifier's `vm-exit` check, i.e. on
# the native binary, on a runner, in a workflow that had never once got that far. This gate asks the
# same question in ~2 seconds against the staged launcher.
#
# It asserts the flag is UNDERSTOOD, not merely tolerated: each invocation must print the program's
# real output. A flag silently swallowed would still exit 0 with empty stdout.
#
# BOTH launchers are exercised, and that is the whole point. `bin/ssc` runs StandardMain, which has
# accepted --interpret all along; `bin/ssc-tools` runs `scalascript.cli.ssc` (Main.scala) — the SAME
# entry the native release binary uses. A first draft of this gate tested only `bin/ssc` and passed
# against the unfixed tree, because the subject was reachable without the thing under test. Two
# entries, one flag vocabulary: if they ever disagree again, this fails.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
SSC="$ROOT/bin/ssc"                 # scalascript.cli.StandardMain
TOOLS="$ROOT/bin/ssc-tools"         # scalascript.cli.ssc  <- the native binary's entry
for exe in "$SSC" "$TOOLS"; do
  [[ -x $exe ]] || { echo "run-lane-flags-are-flags: no staged launcher at $exe — run ./install.sh --dev" >&2; exit 2; }
done

tmp=$(mktemp -d "${TMPDIR:-/tmp}/run-lane-flags.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

probe="$tmp/probe.ssc"
cat > "$probe" <<'EOF'
def main(): Unit =
  println(21 * 2)
EOF

WANT=42
failed=0

check() {
  local exe=$1 name=$2; shift 2
  local out rc
  set +e
  out=$("$exe" "$@" "$probe" 2>"$tmp/err"); rc=$?
  set -e
  if [[ $rc -ne 0 ]]; then
    echo "run-lane-flags-are-flags: FAILED '$name' — exit $rc" >&2
    echo "--- argv: $* <probe>" >&2
    sed 's/^/    /' "$tmp/err" >&2
    failed=1
    return
  fi
  if [[ $out != "$WANT" ]]; then
    echo "run-lane-flags-are-flags: FAILED '$name' — wrong stdout" >&2
    echo "--- want: $WANT" >&2
    echo "--- got : $out" >&2
    failed=1
  fi
}

# The pairing matters: --bytecode and --interpret/--vm are opposites, and it was the OPPOSITE of the
# one already covered that went missing. Whenever a lane flag is added, add its partner here too.
for exe in "$SSC" "$TOOLS"; do
  who=$(basename "$exe")
  check "$exe" "$who run --v2 --interpret" run --v2 --interpret
  check "$exe" "$who run --v2 --vm"        run --v2 --vm
  check "$exe" "$who run --v2 --bytecode"  run --v2 --bytecode
  check "$exe" "$who run --interpret"      run --interpret
  check "$exe" "$who run --vm"             run --vm
done

# The class behind the defect, stated as its own check: an unknown flag must NOT be silently opened
# as a file. Until the parser rejects unknown flags this is expected to report the file-shaped
# error; the check pins the CURRENT behaviour so that improving it is a deliberate act.
#
# STDERR only, and stdout must stay clean. `run` used to print this to stdout -- an error in the
# data stream, where a caller capturing program output got the message mixed into it and a caller
# reading stderr saw nothing. Fixed since; asserting the stream here is what stops it coming back.
set +e
unknown_err=$("$TOOLS" run --no-such-flag-xyzzy "$probe" 2>&1 >/dev/null); unknown_rc=$?
unknown_out=$("$TOOLS" run --no-such-flag-xyzzy "$probe" 2>/dev/null)
set -e
if [[ -n $unknown_out ]]; then
  echo "run-lane-flags-are-flags: FAILED 'unknown-flag-stdout' — diagnostics on the DATA stream" >&2
  echo "--- stdout: $unknown_out" >&2
  failed=1
fi
if [[ $unknown_rc -eq 0 ]]; then
  echo "run-lane-flags-are-flags: FAILED 'unknown-flag' — an unknown flag exited 0" >&2
  failed=1
elif [[ $unknown_err != *"--no-such-flag-xyzzy"* ]]; then
  echo "run-lane-flags-are-flags: FAILED 'unknown-flag' — the diagnostic never names the flag" >&2
  echo "--- got: $unknown_err" >&2
  failed=1
fi

if [[ $failed -ne 0 ]]; then
  echo "run-lane-flags-are-flags: FAILED" >&2
  exit 1
fi
echo "run-lane-flags-are-flags: OK (5 lane flags x 2 launchers, unknown flag still refused)"
