#!/usr/bin/env bash
#
# rust-case-class-method-gate — a method on a case class can read its own fields and call its own
# siblings, on the Rust lane, and answers what the reference lanes answer.
#
# THE DEFECT. A method in a `case class` body reached the emitter as an ORDINARY TOP-LEVEL DEF —
# `contentDefs` collects defs with a DEEP `.collect`, and a class body is just more tree — so it
# came out as a free function with its receiver nowhere:
#
#     case class P(x: Int, y: Int):
#       def shifted(d: Int): P = P(x + d, y)
#
#     pub fn shifted(d: i64) -> P { P { x: (x + d), y: y } }   // <- `x`, `y` bind to nothing
#     error[E0425]: cannot find value `x` in this scope
#
# Two Ints and one method: the simplest data type the language has did not compile. The CALL side
# was never wrong — it already emitted `P{..}.shifted(5)`, method syntax — so only the definition
# side moved, into `impl P` with `&self`.
#
# WHY THE ROWS RUN THE PROGRAM INSTEAD OF READING THE CRATE. A method that reads the WRONG field
# compiles perfectly well. `shifted`/`sum` are chosen so that every field is read and the two
# answers differ (6 and 3 for P(1,2)); swapping `x` and `y` changes them. The reference lanes are
# in every row because "what should this answer" is their answer, not mine.
#
# THE SIBLING ROW IS NOT A VARIATION, it is the second half of the mechanism. Fields are bound as
# locals and siblings as closures capturing `&self`, so `twice()` calling `bump()` — the spelling
# `std/http.ssc` uses for `withSession` -> `withHeader` — keeps its call site untouched.
#
# THE LAST ROW IS THE ANTI-ROW: a case class with NO methods must emit exactly as before. It carries
# the whole corpus, since almost every case class in std is this shape, and an `impl` block appearing
# where there are no methods would be a syntax error at best.
#
# COST: three cargo builds, ~60 s. Lives in ci.yml with the other cargo gates.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "rust-case-class-method-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_ccmethod.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/fields.ssc" <<'SSC'
case class P(x: Int, y: Int):
  def shifted(d: Int): P = P(x + d, y)
  def sum(): Int = x + y

def main(): Unit =
  val p: P = P(1, 2)
  println(p.shifted(5).x)
  println(p.shifted(5).y)
  println(p.sum())
main()
SSC

cat > "$sandbox/sibling.ssc" <<'SSC'
case class R(v: Int):
  def bump(): R = R(v + 1)
  def twice(): R = bump().bump()

def main(): Unit =
  println(R(1).twice().v)
  println(R(10).bump().v)
main()
SSC

# No methods at all — the shape almost every case class in the corpus has.
cat > "$sandbox/plain.ssc" <<'SSC'
case class Q(a: Int, b: String)

def main(): Unit =
  val q: Q = Q(7, "hi")
  println(q.a)
  println(q.b)
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

row fields  '6|2|3|'
row sibling '3|11|'
row plain   '7|hi|'

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-case-class-method-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-case-class-method-gate: PASS"
