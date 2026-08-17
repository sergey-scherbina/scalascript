#!/usr/bin/env bash
#
# rust-match-arm-braces-gate — a match arm holding more than one statement is emitted as a Rust
# BLOCK, and the crate parses.
#
# THE DEFECT (rust-multi-statement-match-arm-emitted-without-braces). A def declared to return
# `Any` whose body is a `match` renders each arm through `renderValueTail`, whose contract is to
# hand back a STATEMENT SEQUENCE — correct for every other caller, because they splice it somewhere
# already bracketed (an `if` branch, a fn body). A match arm is the one caller that splices it
# straight after `=>`, where Rust wants an expression:
#
#     def label(k: String): Any =
#       pick(k) match { case "a" => val n = 10 ; n + 1 ; … }
#
#     "a" => let n = 10i64;                       // <- not Rust
#     error: expected expression, found `let` statement
#
# A PARSE error, so it is not one bad line: rustc stops, and every other diagnostic in the crate
# disappears behind it. That is what makes this worth a gate of its own rather than a corpus row.
#
# WHAT THE ROWS ARE FOR.
#   * `tail` is the minimum: fourteen lines, no case classes, no `Any` scrutinee. The `Any` RETURN
#     is the whole trigger, and this row is the one that went from an unparseable crate to `11`.
#   * `guarded` is the same mechanism spelled differently — Int scrutinee, three arms, one of them
#     guarded — because a fix that braces one arm shape and not another would still pass `tail`.
#   * `anyctor` is the SECOND defect, which the first one hid
#     (`rust-any-returning-call-scrutinee-keeps-the-typed-match-path`). Once the arm parses, a match
#     whose scrutinee is a CALL to a def declared `Any` was still on the typed path, so its struct
#     pattern read `expected Value, found Ok` — E0308. Note this row's def returns `String`, so the
#     brace fix is not involved in it: the two defects are isolated from each other, not stacked.
#   * `typed` is the ANTI-ROW. An ordinary enum match already arrived braced (`renderTerm` renders
#     a Scala block as a Rust block), so it must emit and answer exactly as it did before. Without
#     it, "always brace the arm" would double-brace the common path and this gate would not notice.
#
# The expected answers come from the reference lanes, which is why every row runs all three: what
# these programs SHOULD print is `run` and `--v1`'s answer, not this file's opinion.
#
# COST: four cargo builds, ~80 s. Lives in ci.yml with the other cargo gates.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "rust-match-arm-braces-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_armbraces.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

# The reduction. `Any` return + a match body + two statements in an arm; nothing else is needed.
cat > "$sandbox/tail.ssc" <<'SSC'
def pick(k: String): String = k

def label(k: String): Any =
  pick(k) match {
    case "a" =>
      val n = 10
      n + 1
    case _ =>
      val m = 20
      m + 2
  }

def main(): Unit =
  println(label("a"))
  println(label("z"))
main()
SSC

# Same mechanism, different arm spellings: an Int scrutinee, a guard, and a catch-all.
cat > "$sandbox/guarded.ssc" <<'SSC'
def classify(n: Int): Any =
  n match {
    case 0 =>
      val z = "zero"
      z
    case k if k > 10 =>
      val big = "big"
      big + "!"
    case _ =>
      val s = "small"
      s
  }

def main(): Unit =
  println(classify(0))
  println(classify(42))
  println(classify(3))
main()
SSC

# The second defect: the scrutinee is a call to an `Any`-declared def, the arms are case classes.
cat > "$sandbox/anyctor.ssc" <<'SSC'
case class Ok(value: Int, next: Int)
case class Err(message: String)

def step(n: Int): Any =
  if n > 2 then Err("too big") else Ok(n, n + 1)

def label(n: Int): String =
  step(n) match {
    case Ok(value, next) =>
      val total = value + next
      "ok " + total
    case Err(message) =>
      val m = message
      "err " + m
  }

def main(): Unit =
  println(label(1))
  println(label(5))
main()
SSC

# The anti-row: a typed enum match, already braced before the fix, must be untouched by it.
cat > "$sandbox/typed.ssc" <<'SSC'
enum Shape:
  case Circle(r: Int)
  case Rect(w: Int, h: Int)

def area(s: Shape): Int =
  s match {
    case Shape.Circle(r) =>
      val d = r * 2
      d * d
    case Shape.Rect(w, h) =>
      val half = w / 2
      half * h
  }

def main(): Unit =
  println(area(Shape.Circle(5)))
  println(area(Shape.Rect(8, 3)))
main()
SSC

lane_says() { # $1 label, $2 want, $3.. command
  local label=$1 want=$2; shift 2
  local out
  out=$(timeout 200 "$@" 2>&1 | head -6 | tr '\n' '|')
  if [[ "$out" == "$want" ]]; then
    echo "  ✓ $label: $out"
  else
    echo "  ✗ $label: got '$out', wanted '$want'"
    fails=$((fails + 1))
  fi
}

row() { # $1 case name, $2 expected output
  local name=$1 want=$2
  echo "── $name"
  lane_says "run   " "$want" "$ssc" run "$sandbox/$name.ssc"
  lane_says "--v1  " "$want" "$tools" run --v1 "$sandbox/$name.ssc"
  if ! command -v cargo >/dev/null 2>&1; then
    echo "  [skip] cargo is not on PATH — the Rust row cannot run. That is a SKIP, not a pass."
  elif ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/$name.ssc" >"$sandbox/$name.log" 2>&1); then
    echo "  ✗ rust  : build failed: $(grep -m1 -E 'error\[E[0-9]+\]|^error' "$sandbox/$name.log")"
    fails=$((fails + 1))
  else
    lane_says "rust  " "$want" "$sandbox/$name"
  fi
}

row tail     '11|22|'
row guarded  'zero|big!|small|'
row anyctor  'ok 3|err too big|'
row typed    '100|12|'

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-match-arm-braces-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-match-arm-braces-gate: PASS"
