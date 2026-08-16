#!/usr/bin/env bash
#
# rust-type-pattern-local-val-gate — a type pattern discriminates wherever the value came from.
#
# THE REPORT (rozum, `rust-type-pattern-on-a-local-val-matches-anything`, `impact: blocks`). A reader
# accepting either `[…]` or `{"rooms": […]}` answered "no such room" for every room in a file that
# plainly listed them. Nothing failed — the answer was simply wrong, so it reached a running server
# and was found later by comparing two implementations byte for byte.
#
# THE DISCRIMINATOR WAS WHERE THE VALUE CAME FROM, AND NOTHING ELSE. The same match expression is
# correct against a PARAMETER and wrong against a local `val`:
#
#     interpreter                      rust lane (before)
#     array via parameter : catch-all  array via parameter : catch-all
#     array via local val : catch-all  array via local val : MAP arm      <- wrong
#     array, binding used : catch-all  array, binding used : MAP, no key  <- wrong
#     object via parameter: MAP arm    object via parameter: MAP arm
#
# `renderMatch` asked whether the subject was an `Any` by looking at the def's PARAMETERS only, so a
# local was never one; the typed path then dropped the `case m: Map[String, Any]` ascription and left
# `match parsed { m => …, other => … }`, whose first arm matches everything.
#
# THIS GATE COMPARES THE TWO LANES ROW BY ROW rather than asserting four literals, because a change
# that moved BOTH lanes together would still be a defect and four hard-coded strings would not see
# it. `run` is the oracle here: it agreed with the reporter's expectation before the fix and must
# keep agreeing after.
#
# ROW 1 AND ROW 4 ARE THE CONTROL, and they are the reason the fix cannot be "make every match use
# the Any path": they were already correct, so a change that breaks them trades one wrong answer for
# another. Row 3 exists because the reporter found it made the wrong answer PLAUSIBLE — `m.get(…)`
# on an array just returns nothing, so the object branch fails quietly instead of loudly.
#
# COST: one cargo build plus one interpreter run, ~60 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" && -x "$ssc" ]] || { echo "rust-type-pattern-local-val-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }

if ! command -v cargo >/dev/null 2>&1; then
  echo "rust-type-pattern-local-val-gate: [skip] cargo is not on PATH. That is a SKIP, not a pass." >&2
  exit 0
fi

# Inside examples/ so the std import stays relative — an absolute import path drops what it names on
# this lane (rust-absolute-import-path-inlines-nothing).
sandbox=$(mktemp -d "$ROOT/examples/_typat.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/r.ssc" <<'SSC'
[jsonParse](../../std/json.ssc)

def viaParam(v: Any): String =
  v match
    case m: Map[String, Any] => "MAP arm"
    case other               => "catch-all arm"

def viaLocal(): String =
  val parsed = jsonParse("[{\"name\":\"a\"}]")
  parsed match
    case m: Map[String, Any] => "MAP arm"
    case other               => "catch-all arm"

def viaLocalUsingTheBinding(): String =
  val parsed = jsonParse("[{\"name\":\"a\"}]")
  parsed match
    case m: Map[String, Any] => m.get("rooms").map(v => "MAP with key").getOrElse("MAP, no key")
    case other               => "catch-all arm"

def main(): Unit =
  println("array via parameter : " + viaParam(jsonParse("[{\"name\":\"a\"}]")))
  println("array via local val : " + viaLocal())
  println("array, binding used : " + viaLocalUsingTheBinding())
  println("object via parameter: " + viaParam(jsonParse("{\"name\":\"a\"}")))

main()
SSC

int_out=$(timeout 600 "$ssc" run "$sandbox/r.ssc" 2>/dev/null)
if [[ -z "$int_out" ]]; then
  echo "  ✗ the interpreter produced nothing — the oracle is unusable, so this gate cannot decide" >&2
  exit 1
fi

if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/r.ssc" >"$sandbox/build.log" 2>&1); then
  echo "  ✗ build-rust failed: $(grep -m1 -E 'error\[E[0-9]+\]|^error|Generic\(' "$sandbox/build.log" | cut -c1-100)" >&2
  exit 1
fi
rust_out=$("$sandbox/r" 2>/dev/null)

echo "── the same match expression, four subjects, two lanes"
while IFS= read -r want; do
  label=${want%% :*}
  got=$(printf '%s\n' "$rust_out" | grep -F "$label" || true)
  if [[ "$got" == "$want" ]]; then
    echo "  ✓ ${want}"
  else
    echo "  ✗ ${label}: rust '${got#*: }', interpreter '${want#*: }'"
    fails=$((fails + 1))
  fi
done <<< "$int_out"

# The interpreter is the oracle, but it must still be saying the RIGHT thing — a lane that agreed
# because both had regressed would pass every row above. An array is not a Map on any lane.
if printf '%s\n' "$int_out" | grep -q 'array via local val : catch-all arm'; then
  echo "  ✓ the oracle itself is right: an array does not take the Map arm"
else
  echo "  ✗ the interpreter no longer answers 'catch-all arm' for an array — the oracle regressed,"
  echo "    so agreement between the lanes proves nothing here"
  fails=$((fails + 1))
fi

# rustc SAW this defect and the build swallowed it: a dropped ascription leaves a bind-all first arm,
# so every later arm is dead. Nothing in this repository reads generated-code warnings yet
# (`generated-rust-unreachable-pattern-is-an-unread-diagnostic`), so assert it here for this crate.
if grep -q 'unreachable pattern' "$sandbox/build.log"; then
  echo "  ✗ rustc reports 'unreachable pattern' in the generated crate — an arm was made irrefutable"
  fails=$((fails + 1))
else
  echo "  ✓ rustc reports no unreachable pattern in the generated crate"
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-type-pattern-local-val-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-type-pattern-local-val-gate: PASS"
