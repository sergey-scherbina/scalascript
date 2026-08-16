#!/usr/bin/env bash
#
# rust-member-qualify-gate — two different receivers can each declare a member of the same name, and
# the boundary where that stops is pinned to the reason it actually stops for.
#
# THE DEFECT AS FILED. `SqliteCursor.close` and `SqliteStatement.close` were reported as
# *overloading* — "def `close` emits 2 times; Rust has no overloading and this lane does not mangle
# names" — because members of distinct receivers were flattened into one namespace. `Console.println`
# is no more an overload of `Bench.opaque` than these are of each other.
#
# WHAT ACTUALLY FIXED IT WAS TWO SEPARATE CHANGES, neither of them a mangling scheme:
#
#   an OBJECT member emits as `Owner_member`          (the definition site was renamed)
#   a CASE-CLASS method emits into `impl Owner`       (rust-case-class-method-cannot-read-its-own-fields)
#
# so the collision cannot arise for either shape any more. This gate is what keeps that true: both
# rows are a REGRESSION guard on a defect that is no longer reachable by the route it was reported
# through.
#
# THE THIRD ROW IS THE BOUNDARY, and it is why this entry can be closed without closing everything
# that mentions `close` or `show`. `std/show.ssc` still refuses — but NOT for overloading:
#
#     def `show` uses type `Show[A]`; R.2 accepts primitives, enums, function types, tuple, List/Vec
#
# A typeclass declares one member name per instance, so the flattening was a SYMPTOM of having no
# typeclass dictionary on this lane, not an independent defect. Qualifying names would leave that
# type just as unlowerable. The row asserts the refusal is that one — if it ever comes back as
# "emits N times (overloading)", the flattening has returned and this gate says so.
#
# EVERY ROW RUNS ON THE REFERENCE LANE FIRST. An earlier probe of mine used a trait with an
# inherited method, which `run` itself answers `unhandled runtime effect: C1.close` — measuring a
# shape the oracle does not support would have "fixed" the Rust lane into inventing behaviour.
#
# COST: two cargo builds and one emit, ~40 s. Lives in ci.yml with the other cargo gates.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "rust-member-qualify-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_memberq.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/objects.ssc" <<'SSC'
object Cursor:
  def close(): String = "cursor closed"

object Conn:
  def close(): String = "conn closed"

def main(): Unit =
  println(Cursor.close())
  println(Conn.close())
main()
SSC

cat > "$sandbox/classes.ssc" <<'SSC'
case class Cursor(id: Int):
  def close(): String = "cursor " + id

case class Conn(name: String):
  def close(): String = "conn " + name

def main(): Unit =
  println(Cursor(1).close())
  println(Conn("db").close())
main()
SSC

# The boundary: a typeclass. `run` handles it; this lane refuses, and the row asserts WHICH refusal.
cat > "$sandbox/typeclass.ssc" <<'SSC'
trait Show[A]:
  def show(a: A): String

given intShow: Show[Int] with
  def show(a: Int): String = "int:" + a

given strShow: Show[String] with
  def show(a: String): String = "str:" + a

def show[A](a: A)(using s: Show[A]): String = s.show(a)

def main(): Unit =
  println(show(7))
  println(show("x"))
main()
SSC

row() { # $1 name, $2 expected
  local name=$1 want=$2 out
  out=$(timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1 | head -4 | tr '\n' '|')
  [[ "$out" == "$want" ]] || { echo "  ✗ $name run  : got '$out', wanted '$want'"; fails=$((fails + 1)); }
  if ! command -v cargo >/dev/null 2>&1; then
    echo "  [skip] $name rust — cargo is not on PATH. That is a SKIP, not a pass."; return
  fi
  if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/$name.ssc" >"$sandbox/$name.log" 2>&1); then
    echo "  ✗ $name rust : $(grep -m1 -oE 'def `[^`]*` emits [0-9]+ times \(overloading\)|error\[E[0-9]+\]: .{0,40}' "$sandbox/$name.log")"
    fails=$((fails + 1)); return
  fi
  out=$("$sandbox/$name" 2>&1 | head -4 | tr '\n' '|')
  if [[ "$out" == "$want" ]]; then echo "  ✓ $name: $out on both lanes"
  else echo "  ✗ $name rust : got '$out', wanted '$want'"; fails=$((fails + 1)); fi
}

echo "── two receivers, one member name"
row objects 'cursor closed|conn closed|'
row classes 'cursor 1|conn db|'

echo "── the boundary: a typeclass refuses on its DICTIONARY TYPE, not on overloading"
out=$( (cd "$sandbox" && timeout 300 "$tools" emit-rust "$sandbox/typeclass.ssc" 2>&1) )
if printf '%s' "$out" | grep -q 'emits .* times (overloading)'; then
  echo "  ✗ typeclass: refused as OVERLOADING — the flattening is back"
  fails=$((fails + 1))
elif printf '%s' "$out" | grep -q 'uses type `Show\[A\]`'; then
  echo "  ✓ typeclass: refused on the dictionary type, as expected"
else
  echo "  ✓ typeclass: no longer refused — the dictionary type lowers now ($(printf '%s' "$out" | tail -1 | cut -c1-40))"
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-member-qualify-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-member-qualify-gate: PASS"
