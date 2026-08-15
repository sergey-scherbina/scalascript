#!/usr/bin/env bash
#
# rust-any-map-literal-gate — a literal declared at an `Any` container is lifted AS IT IS BUILT, so a
# heterogeneous one exists at all; and a literal declared at anything else is untouched.
#
# THE DEFECT. `val m: Map[String, Any] = Map("a" -> "x", "b" -> 1)` did not compile. The annotation
# was applied to the FINISHED value — `(…).into_iter().map(|(k, v)| (k, Value::from(v))).collect()` —
# which works only if the literal could be built first. A heterogeneous one cannot:
#
#     error[E0308]: mismatched types    expected `String`, found `i64`
#
# because the first insert fixes the element type. The message names the wrong element and never
# mentions `Any`, which is what made this hard to read from the report.
#
# THE FIX IS WHERE THE LITERAL IS BUILT, not after it: each value goes through `Value::from` as the
# insert is emitted. That is the only site that can work for the heterogeneous case, and it is why
# the row order below starts with it.
#
# THE THIRD ROW IS THE ONE MY OWN FIRST ATTEMPT BROKE. `val a: Any = List(1, 2)` is a List literal at
# a SCALAR `Value` annotation: it must take the ordinary path and still get the whole-value wrapper.
# Deciding "was this lifted?" from the shape of the right-hand side instead of from the branch that
# actually ran drops that wrapper and emits E0308. The row exists because the mistake was made.
#
# THE READ SIDE IS HERE TOO, added when it turned out to be the next defect in the same boundary:
# with the map correctly built as `HashMap<_, Value>`, `m.getOrElse("k", "?")` emitted
# `unwrap_or("?".to_string())` and rustc answered `expected Value, found String`. The default is now
# emitted as `$d.into()`, which is TOTAL — the identity when the types already agree — so the
# plainly-typed and Int-valued maps below must answer exactly as before.
#
# THE LAST TWO ROWS ARE THE ANTI-ROWS. `renderLetBinding` is on the path of EVERY local in the
# repository, so a rule that widened beyond the `Any` boundary would be measured in goldens rather
# than in a probe. A plainly-typed `Map[String, String]` and `List[Int]` must emit exactly as before.
#
# COST: ten cargo builds, ~2 min. Lives in ci.yml with the other cargo gates.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "rust-any-map-literal-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_anymap.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

write_case() { # $1 name, $2 body (with \n escapes)
  printf 'def main(): Unit =\n%b\nmain()\n' "$2" > "$sandbox/$1.ssc"
}

#            name          body                                                              expected
write_case het         '  val m: Map[String, Any] = Map("a" -> "x", "b" -> 1)\n  println(m.size)'
write_case homo        '  val m: Map[String, Any] = Map("k" -> "hello")\n  println(m.size)'
write_case listany     '  val xs: List[Any] = List(1, "two")\n  println(xs.length)'
write_case scalarlist  '  val a: Any = List(1, 2)\n  println("ok")'
write_case scalar      '  val a: Any = 5\n  println(a)'
write_case plainmap    '  val m: Map[String, String] = Map("k" -> "v")\n  println(m.size)'
write_case plainlist   '  val xs: List[Int] = List(1, 2, 3)\n  println(xs.length)'
# The READ side of the same boundary. Both keys on purpose: the present one takes the value out of
# the map, the absent one takes the DEFAULT, and only the second is the path that was broken.
write_case anyread     '  val m: Map[String, Any] = Map("k" -> "hello")\n  println(m.getOrElse("k", "?"))\n  println(m.getOrElse("nope", "?"))'
write_case plainread   '  val m: Map[String, String] = Map("k" -> "v")\n  println(m.getOrElse("k", "?"))\n  println(m.getOrElse("nope", "?"))'
write_case intread     '  val m: Map[String, Int] = Map("k" -> 7)\n  println(m.getOrElse("k", 0))\n  println(m.getOrElse("nope", 0))'

row() { # $1 name, $2 expected output
  local name=$1 want=$2 out
  out=$(timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1 | head -4 | tr '\n' '|')
  if [[ "$out" != "$want" ]]; then
    echo "  ✗ $name run  : got '$out', wanted '$want'"; fails=$((fails + 1))
  fi
  out=$(timeout 200 "$tools" run --v1 "$sandbox/$name.ssc" 2>&1 | head -4 | tr '\n' '|')
  if [[ "$out" != "$want" ]]; then
    echo "  ✗ $name --v1 : got '$out', wanted '$want'"; fails=$((fails + 1))
  fi
  if ! command -v cargo >/dev/null 2>&1; then
    echo "  [skip] $name rust — cargo is not on PATH. That is a SKIP, not a pass."
    return
  fi
  if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/$name.ssc" >"$sandbox/$name.log" 2>&1); then
    echo "  ✗ $name rust : build failed: $(grep -m1 -E 'error\[E[0-9]+\]|^error' "$sandbox/$name.log")"
    fails=$((fails + 1)); return
  fi
  out=$("$sandbox/$name" 2>&1 | head -4 | tr '\n' '|')
  if [[ "$out" == "$want" ]]; then
    echo "  ✓ $name: $out on all three lanes"
  else
    echo "  ✗ $name rust : got '$out', wanted '$want'"; fails=$((fails + 1))
  fi
}

echo "── a literal at an Any container is lifted as it is built"
row het        '2|'
row homo       '1|'
row listany    '2|'

echo "── a literal at a SCALAR Any still gets the whole-value wrapper"
row scalarlist 'ok|'
row scalar     '5|'

echo "── reading it back: the default is lifted too, whatever the map holds"
row anyread    'hello|?|'
row plainread  'v|?|'
row intread    '7|0|'

echo "── the anti-rows: a plainly-typed local is untouched"
row plainmap   '1|'
row plainlist  '3|'

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-any-map-literal-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-any-map-literal-gate: PASS"
