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
# NOT ASSERTED HERE, DELIBERATELY: `timeout`. It is accepted by the type and NOT enforced on this
# lane — `std::process::Command` has none, and honouring it means spawn/poll/kill, a different
# failure contract. It is filed as `rust-exec-ignores-processoptions-timeout` rather than half-done,
# and this gate does not pretend otherwise: a row here would either fail forever or assert the wrong
# thing.
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

main()
SSC

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
rust_out=$("$sandbox/q" 2>/dev/null)

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
if printf '%s\n' "$int_out" | grep -q 'inheritEnv=false     : \[\]\[only\]'; then
  echo "  ✓ the oracle itself is right: the scrub drops the parent's vars and keeps the caller's"
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
