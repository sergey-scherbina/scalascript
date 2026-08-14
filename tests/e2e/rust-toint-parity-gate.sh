#!/usr/bin/env bash
#
# rust-toint-parity-gate — a numeric conversion answers the same on `run` and on `build-rust`, and
# when it cannot, both lanes STOP.
#
# TWO USER REPORTS FROM ROZUM, one line apart in the runtime template:
#
#   toint-on-a-non-integer-diverges          `"abc".toInt` was 0 on build-rust and fatal on run
#   toint-on-a-char-and-tolist-on-a-string   `"ab".toList` was a closure on run and a list on build-rust
#
# Both are the same shape — the same source, the same input, two answers, no diagnostic — and both
# were found by a user whose program had to move lanes. The Rust arms were
# `parse::<i64>().unwrap_or(0)` and `parse::<f64>().unwrap_or(0.0)`, so a non-numeric string became
# a 0 nobody asked for; `run` throws, matching Scala, so `run` is the oracle and the quiet lane is
# the one that moved. `String.toList` had no arm at all in the v2 dispatcher — it had one for a
# list, an Option, a Set, a Map, a LazyList and an ArrayBuffer — so the selection eta-expanded into
# a function value.
#
# THE toDouble ROWS ARE NOT IN EITHER REPORT. The twin sat one line below the reported arm with the
# identical `unwrap_or(0.0)`, and fixing the reported one alone would have left it. It is asserted
# here so the pair stays fixed together.
#
# HALF OF THIS DEFECT IS STILL OPEN, and the rows below say which half by what they ask. The silent
# zero has TWO emission paths, which this gate found by failing:
#
#   receiver's type not statically known   ->  `_to_int(x)`  ->  the runtime template   FIXED
#   walker knows the receiver is a String  ->  `("abc".to_string().parse::<i64>().unwrap_or(0))`
#                                                                  emitted INLINE       OPEN
#
# So `def f(s: String) = s.toInt` stops on both lanes now, and `"abc".toInt` written straight into
# the call still answers 0 on the Rust lane. The inline sites are RustCodeWalk.scala:2559, :2568,
# :2978 and :4372-4373; that file is under another agent's claim today, so the second half stays
# OPEN in the routed entry (BUGS `toint-on-a-non-integer-diverges`) rather than being half-done in
# silence. Every row here is one that passes — a gate that ships red is not a gate — and the open
# half is named in the entry, which is the record people read, not only in a comment.
#
# COST: three cargo builds, measured 9 s standalone on a WARM cargo cache — the crates carry no
# external dependencies, which is why it is nothing like `build-rust-refuses-loudly` (74.8 s, one of
# its crates pulls serde_json). It still runs in `ci.yml` beside that gate rather than on the push
# path, because 9 s is the warm number and a CI runner compiles the crate cold; smoke was hitting
# its own job timeout when cargo gates lived there, and that is not a thing to re-learn.
# Without cargo it SKIPS loudly rather than passing quietly: a rust gate that silently becomes a
# no-op is worse than one that is missing.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "rust-toint-parity-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "${TMPDIR:-/tmp}/rust-toint.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

echo "── a numeric conversion answers the same on both lanes"

# ── the run lane ─────────────────────────────────────────────────────────────────────────────────
run_says() { # $1 name, $2 expected output (newlines as |), $3 source
  local name=$1 want=$2 src=$3 out
  printf '%s\n' "$src" > "$sandbox/$name.ssc"
  out=$(timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1 | head -6 | tr '\n' '|')
  if [[ "$out" == "$want" ]]; then
    echo "  ✓ run  $name: $out"
  else
    echo "  ✗ run  $name: got '$out', wanted '$want'"
    fails=$((fails + 1))
  fi
}

# THE GOOD PATH, and it is one program on purpose: every row that does NOT stop the process shares a
# binary, because each cargo build is ~30 s and only a row that ABORTS needs one of its own.
GOOD='def main(): Unit =
  println("8".toInt)
  println("1.5".toDouble)
  println("abc".charAt(0).toInt)
  println("ab".toList.length)
  println("ab".toList.map(c => c.toInt).sum)
main()'
GOOD_WANT='8|1.5|97|2|195|'

run_says good "$GOOD_WANT" "$GOOD"

# The two that must STOP. `run` names the operation; the exact wording differs between lanes and is
# not asserted — what is asserted is that the program does not continue with a fabricated number.
BAD_INT='def f(s: String): Int = s.toInt
def main(): Unit = println(f("abc"))
main()'
BAD_DBL='def f(s: String): Double = s.toDouble
def main(): Unit = println(f("abc"))
main()'

run_says bad-toint 'ssc: String.toInt: invalid integer|' "$BAD_INT"
run_says bad-todouble 'ssc: For input string: "abc"|' "$BAD_DBL"

echo "── and the same source on the Rust lane"

if ! command -v cargo >/dev/null 2>&1; then
  echo "  [skip] cargo is not on PATH — the Rust half of this gate cannot run."
  echo "         That is a SKIP, not a pass: install a Rust toolchain to get the parity check."
else
  rust_says() { # $1 name, $2 expected stdout (newlines as |), $3 expected exit, $4 source
    local name=$1 want=$2 wantrc=$3 src=$4 out rc
    printf '%s\n' "$src" > "$sandbox/$name.ssc"
    if ! (cd "$sandbox" && timeout 600 "$tools" build-rust "$sandbox/$name.ssc" >"$sandbox/$name.build" 2>&1); then
      echo "  ✗ rust $name: build-rust failed"
      tail -3 "$sandbox/$name.build" | sed 's/^/        /'
      fails=$((fails + 1)); return
    fi
    out=$(cd "$sandbox" && timeout 200 "./$name" 2>/dev/null | head -6 | tr '\n' '|'); rc=$?
    # The binary's own exit code, not the pipeline's — a panic is the POINT of two of these rows.
    (cd "$sandbox" && timeout 200 "./$name" >/dev/null 2>&1); rc=$?
    if [[ "$out" == "$want" && "$rc" -eq "$wantrc" ]]; then
      echo "  ✓ rust $name: '$out' exit=$rc"
    else
      echo "  ✗ rust $name: got '$out' exit=$rc, wanted '$want' exit=$wantrc"
      fails=$((fails + 1))
    fi
  }

  rust_says good "$GOOD_WANT" 0 "$GOOD"

  # THE REPORTED DIVERGENCE, through the path this fix owns. It printed `false` before — the
  # round-trip check `s.toInt.toString == s` answered on this lane and killed the program on the
  # other. A PARAMETER receiver is not incidental: it is what routes through `_to_int`, and the
  # literal spelling still takes the inline path (see the header).
  rust_says bad-toint '' 101 "$BAD_INT"

  # THE TWIN NOBODY REPORTED.
  rust_says bad-todouble '' 101 "$BAD_DBL"
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-toint-parity-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-toint-parity-gate: PASS"
