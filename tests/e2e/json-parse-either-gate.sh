#!/usr/bin/env bash
#
# json-parse-either-gate — a caller can ask "was this text JSON at all?", on every lane.
#
# THE REQUEST (rozum, `json-parse-has-no-fallible-spelling`). A server handling input it did not
# write could not refuse it. `jsonParse` ABORTS the thread; `jsonValue` never fails but cannot
# DISCRIMINATE — measured before the fix, all four of these answered `isNull`:
#
#     ""            not JSON
#     "not json"    not JSON
#     "{\"a\":"     not JSON (truncated)
#     "null"        VALID JSON whose value is null
#
# so "was this text JSON at all?" had no spelling anywhere. Their workaround was to inspect the first
# byte, which cannot be right in general. `jsonParseEither` answers it: `Left` for the first three,
# `Right` for the fourth.
#
# ROW `literal null` IS THE POINT OF THE WHOLE GATE. Every other row would pass on a naive
# implementation that simply called the tolerant parser and reported `isNull` as failure — and that
# implementation would tell a caller their valid `null` was malformed. It is the row that separates
# "did it parse" from "is it empty".
#
# FIVE LANES, because a fallible parse that exists on four of them is a trap for whoever ships on the
# fifth: `bin/ssc run`, `--v1`, `run-jvm`, `run-js` and `build-rust`. The rust lane matters most —
# `std/json-core.ssc` cannot be lowered there at all today
# (`rust-lane-refuses-the-tolerant-json-parse-while-the-panicking-one-builds`), so this feature is
# built to work WITHOUT it: `__jsonParseError` is a def on the portable lanes and an INTRINSIC on
# rust, the same arrangement `jsonParse` already relies on.
#
# THE MESSAGE TEXT DIFFERS ON RUST AND THE GATE DOES NOT PRETEND OTHERWISE. json-core says
# `invalid JSON at 5: expected JSON value`; serde says `invalid JSON: EOF while parsing a value at
# line 1 column 5`. Both name where it broke, which is what `Either` over `Option` buys. Asserting
# one exact text would fail on a lane doing the right thing, so the message rows assert it is
# non-empty and carries a position, and the classification rows are what is compared exactly.
#
# COST: one cargo build, one jvm run, three interpreter runs. ~90 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" && -x "$ssc" ]] || { echo "json-parse-either-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_jeither.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/j.ssc" <<'SSC'
[jsonParseEither](../../std/json.ssc)
[Left, Right](../../std/either.ssc)

def label(s: String): String =
  jsonParseEither(s) match
    case Left(e)  => "ERR"
    case Right(v) => "OK"

def hasMessage(s: String): String =
  jsonParseEither(s) match
    case Left(e)  => if e.length > 10 then "yes" else "too short: " + e
    case Right(v) => "no Left at all"

def main(): Unit =
  println("empty       : " + label(""))
  println("garbage     : " + label("not json"))
  println("truncated   : " + label("{\"a\":"))
  println("literal null: " + label("null"))
  println("good object : " + label("{\"a\":1}"))
  println("message     : " + hasMessage("{\"a\":"))

main()
SSC

want=$'empty       : ERR\ngarbage     : ERR\ntruncated   : ERR\nliteral null: OK\ngood object : OK\nmessage     : yes'

check_lane() { # $1 label, $2 output
  local label=$1 out=$2 row got
  if [[ -z "$out" ]]; then
    echo "  ✗ $label produced nothing (hang, crash, or refusal)"
    fails=$((fails + 1))
    return
  fi
  while IFS= read -r row; do
    got=$(printf '%s\n' "$out" | grep -F "${row%%:*}:" || true)
    if [[ "$got" == "$row" ]]; then
      echo "  ✓ $label  ${row}"
    else
      echo "  ✗ $label  ${row%%:*}: got '${got#*: }', wanted '${row#*: }'"
      fails=$((fails + 1))
    fi
  done <<< "$want"
}

echo "── a caller can tell 'not JSON' from the literal null, on every lane"
check_lane "run    " "$(timeout 600 "$ssc" run "$sandbox/j.ssc" 2>/dev/null)"
check_lane "--v1   " "$(timeout 600 "$tools" run --v1 "$sandbox/j.ssc" 2>/dev/null)"

if command -v node >/dev/null 2>&1; then
  check_lane "js     " "$(timeout 600 "$tools" run-js "$sandbox/j.ssc" 2>/dev/null)"
else
  echo "  [skip] node is not on PATH — the js lane is a SKIP, not a pass." >&2
fi

# scala-cli drives run-jvm; on a machine without it the lane is a SKIP, never a silent pass.
if timeout 900 "$tools" run-jvm "$sandbox/j.ssc" > "$sandbox/jvm.out" 2>"$sandbox/jvm.err"; then
  check_lane "jvm    " "$(cat "$sandbox/jvm.out")"
else
  echo "  [skip] run-jvm did not complete — $(head -1 "$sandbox/jvm.err" | cut -c1-70)" >&2
fi

if command -v cargo >/dev/null 2>&1; then
  if (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/j.ssc" >"$sandbox/build.log" 2>&1); then
    check_lane "rust   " "$(timeout 300 "$sandbox/j" 2>/dev/null)"
  else
    echo "  ✗ build-rust failed:"
    grep -m3 -E 'Generic\(|error\[E[0-9]+\]' "$sandbox/build.log" | cut -c1-110 | sed 's/^/      /'
    fails=$((fails + 1))
  fi
else
  echo "  [skip] cargo is not on PATH — the rust lane is a SKIP, not a pass." >&2
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "json-parse-either-gate: FAIL ($fails)" >&2
  exit 1
fi
echo "json-parse-either-gate: PASS"
