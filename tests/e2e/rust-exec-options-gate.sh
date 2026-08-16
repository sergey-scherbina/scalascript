#!/usr/bin/env bash
#
# rust-exec-options-gate — `exec` obeys ProcessOptions on the rust lane, or says it does not.
#
# THE DEFECT WAS SILENCE. `_exec` took `_opts: O` and threw it away; the comment beside it said
# "cwd/env/timeout aren't applied yet", which reads as a known gap from inside the repository and as
# nothing at all from outside it. A program that set `cwd` or scrubbed the environment got it obeyed
# under `run` and IGNORED under `build-rust` — no error, no warning, a child running in the wrong
# directory with the wrong environment. Measured before the fix:
#
#     run          cwd honoured: true
#     build-rust   cwd honoured: false     <- compiled, ran, wrong answer
#
# Found while sizing two rozum feature reports (`process-needs-a-detached-spawn`,
# `process-needs-a-stdin-pipe`) that both want to EXTEND `ProcessOptions` on this lane. Adding a
# field to a record the lane ignores would have been write-only metadata, so this came first.
#
# ROW 4 IS THE ONE THAT ORDERS THE IMPLEMENTATION. `inheritEnv = false` must scrub the parent
# environment BEFORE the caller's `env` is applied, never after: the point of the flag is that the
# child sees only what was listed. Clearing afterwards throws those away too, and the row would then
# print `[][]` instead of `[][only]` — a difference invisible to any check that only asks whether
# `$HOME` is gone.
#
# COMPARED AGAINST `run` ROW BY ROW, not against literals: a change that moved both lanes together
# is still a defect.
#
# `timeout` WAS the one option this gate deliberately did not assert, because it was accepted and
# not enforced. It is enforced now (`rust-exec-ignores-processoptions-timeout`), and rows 5-8 cover
# it. Measured before that fix, `exec("sleep 5", timeout = 600ms)` returned after the FULL five
# seconds with exit code 0 while `run` killed at 600 ms and answered -1 — not merely unenforced, but
# reporting SUCCESS for a call the caller had bounded.
#
# ROW 8 IS THE ONE THAT WOULD HANG RATHER THAN FAIL. 300 KB of child output is several times a pipe
# buffer, so an implementation that reads the pipes on the polling thread deadlocks: the read cannot
# return until the child exits, and the child cannot exit until someone reads. Both JVM lanes carry
# that lesson in their own comments; this row is what keeps the rust one honest. The whole gate runs
# under the caller's timeout for the same reason a hang is not a red without one.
#
# ROW 7 IS THE MATRIX CELL A ONE-CELL FIX LEAVES BEHIND: `stdin` AND `timeout` together. The stdin
# path spawns too, so the first version of the fix returned from it and silently dropped the
# timeout.
#
# COST: one cargo build plus one interpreter run, ~40 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" && -x "$ssc" ]] || { echo "rust-exec-options-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }

if ! command -v cargo >/dev/null 2>&1; then
  echo "rust-exec-options-gate: [skip] cargo is not on PATH. That is a SKIP, not a pass." >&2
  exit 0
fi

sandbox=$(mktemp -d "$ROOT/examples/_execopt.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

# Positional, not named: `ProcessOptions(cwd = Some(…))` does not build on this lane at all
# (E0063 — a named argument does not fill the defaulted fields), which is its own open entry,
# `rust-named-ctor-args-drop-the-defaulted-fields`. Using it here would measure that instead.
cat > "$sandbox/q.ssc" <<'SSC'
[exec, ProcessOptions](../../std/process.ssc)

def main(): Unit =
  val a = exec("pwd", List(), ProcessOptions())
  println("default cwd non-empty: " + (a.stdout.length > 0))
  val b = exec("pwd", List(), ProcessOptions(Some("/tmp"), Map(), None, true))
  println("cwd honoured         : " + b.stdout.trim.endsWith("tmp"))
  val c = exec("sh", List("-c", "echo $SSC_PROBE"), ProcessOptions(None, Map("SSC_PROBE" -> "yes"), None, true))
  println("env honoured         : " + (c.stdout.trim == "yes"))
  val d = exec("sh", List("-c", "echo [$HOME][$SSC_PROBE]"), ProcessOptions(None, Map("SSC_PROBE" -> "only"), None, false))
  println("inheritEnv=false     : " + d.stdout.trim)
  val e = exec("sleep", List("5"), ProcessOptions(None, Map(), Some(600), true, None))
  println("timeout expired      : " + e.exitCode)
  val f = exec("echo", List("quick"), ProcessOptions(None, Map(), Some(5000), true, None))
  println("timeout under limit  : " + f.exitCode + " " + f.stdout.trim)
  val g = exec("cat", List(), ProcessOptions(None, Map(), Some(5000), true, Some("both\n")))
  println("stdin plus timeout   : " + g.exitCode + " " + g.stdout.trim)
  val h = exec("sh", List("-c", "yes hello | head -c 300000"), ProcessOptions(None, Map(), Some(9000), true, None))
  println("big output no deadlk : " + h.exitCode + " " + h.stdout.length)

main()
SSC

# Every run is under a `timeout`, because rows 5-8 are the shapes that HANG rather than fail when
# they are wrong, and a gate that never finishes is not a red.
int_out=$(timeout 600 "$ssc" run "$sandbox/q.ssc" 2>/dev/null)
if [[ -z "$int_out" ]]; then
  echo "  ✗ the interpreter produced nothing — the oracle is unusable, so this gate cannot decide" >&2
  exit 1
fi

if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/q.ssc" >"$sandbox/build.log" 2>&1); then
  echo "  ✗ build-rust failed:"
  grep -m3 -E 'Generic\(|error\[E[0-9]+\]' "$sandbox/build.log" | cut -c1-120 | sed 's/^/      /'
  exit 1
fi
rust_out=$(timeout 300 "$sandbox/q" 2>/dev/null)

echo "── exec obeys ProcessOptions, on both lanes"
while IFS= read -r want; do
  label=${want%%:*}
  got=$(printf '%s\n' "$rust_out" | grep -F "$label:" || true)
  if [[ "$got" == "$want" ]]; then
    echo "  ✓ ${want}"
  else
    echo "  ✗ ${label}: rust '${got#*: }', interpreter '${want#*: }'"
    fails=$((fails + 1))
  fi
done <<< "$int_out"

# The oracle must still be right — two lanes that had both regressed would agree on every row above.
# `[][only]` pins BOTH halves of the scrub: the inherited value is gone AND the listed one survived.
if printf '%s\n' "$int_out" | grep -q 'inheritEnv=false     : \[\]\[only\]' &&
   printf '%s\n' "$int_out" | grep -q 'timeout expired      : -1'; then
  echo "  ✓ the oracle itself is right: the scrub keeps only the caller's vars, and a timeout is -1"
else
  echo "  ✗ the interpreter no longer answers '[][only]' — the oracle regressed, so agreement"
  echo "    between the lanes proves nothing here"
  fails=$((fails + 1))
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-exec-options-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-exec-options-gate: PASS"
