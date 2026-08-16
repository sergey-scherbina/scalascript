#!/usr/bin/env bash
#
# rust-json-core-gate — three lowerings that `std/json-core.ssc` and its neighbours need, each one
# a shape any program can write and each measured on its own program.
#
# WHERE THESE CAME FROM. `build-rust-drops-defs-it-cannot-lower-without-saying-so` is open on a
# long tail: with the walker's parenless-member refusal lifted, five std modules reach rustc with
# 380 errors between them. These three are the classes that were shared rather than local, found by
# grouping that output. The refusal itself is NOT lifted here — doing that alone turns five modules
# from REFUSED into BADRUST, which the survey gate refuses and is right to: a refusal is a message
# the user can act on and bad generated code is not.
#
# 1. AN OPTION-RETURNING DEF, MAPPED. `_normSegments(rel).map(parts => …)` inside a function
#    declared `Option[String]` took the LIST lowering — `.into_iter().map(…).collect::<Vec<_>>()` —
#    because the Option recogniser knew literals and locals but not a CALL to a def whose signature
#    already says `Option[T]`. rustc: `expected Option<String>, found Vec<String>`. Nothing is
#    inferred by the fix; the declaration is read. This one alone made `std/fs.ssc` compile.
#
# 2. `Nil` IN AN EXPRESSION. `loop(source, offset, Nil)` emitted the bare word and rustc said
#    `cannot find value Nil in this scope` — 12 times in std/yaml-core, 3 in std/json-core, 31 in
#    std/content-core. The PATTERN side has had `Nil` since the list vocabulary landed; the term
#    side never did, and the two positions are written by the same author in the same file.
#
# 3. A MATCH ARM IN A `Value` TAIL POSITION. An `Any`-returning def whose body is a match was
#    handled by wrapping each rendered arm in `Value::from(…)`. That is not the same as rendering
#    the arm AS a tail: an arm that is `if p then Err{…} else <call returning Any>` has two branches
#    of different Rust types, so the `if` fails to typecheck before any wrapper can apply —
#    `expected JsonCoreErr, found Value`. The arm now renders through `renderValueTail`, which
#    pushes the lift into each branch.
#
# EVERY ROW COMPARES ANSWERS ACROSS THE THREE LANES, not compilation: each of these has a shape that
# could compile and mean something else — a Vec where an Option was meant, an empty list that is not
# the one passed, a branch lifted twice.
#
# COST: three cargo builds, ~45 s. Lives in ci.yml with the other cargo gates.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" ]] || { echo "rust-json-core-gate: no launcher at $tools — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_jsoncore.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

# 1. an Option-returning def, mapped — the shape from std/fs.ssc's `resolveWithin`
cat > "$sandbox/optmap.ssc" <<'SSC'
def firstLong(xs: List[String]): Option[String] =
  if xs.isEmpty then None else Some(xs.head)

def label(xs: List[String]): Option[String] =
  firstLong(xs).map(s => "[" + s + "]")

def main(): Unit =
  println(label(List("a", "b")).getOrElse("-"))
  println(label(List()).getOrElse("-"))
main()
SSC

# 2. `Nil` as a value, passed to a recursive accumulator — the shape from json-core's parse loops
cat > "$sandbox/nilvalue.ssc" <<'SSC'
def collect(n: Int, acc: List[Int]): List[Int] =
  if n <= 0 then acc else collect(n - 1, n :: acc)

def main(): Unit =
  println(collect(3, Nil).length)
  println(collect(0, Nil).length)
main()
SSC

# 3. a match arm whose body is an `if` with branches of two different types, in an `Any` tail
cat > "$sandbox/tailarm.ssc" <<'SSC'
case class Ok(value: Int)
case class Err(message: String)

def step(tag: String, n: Int): Any =
  tag match
    case "ok" => if n > 0 then Ok(n) else Err("not positive")
    case _    => Err("unknown tag")

// EXTRACTOR patterns, not `case o: Ok` — a typed binding against an `Any` deliberately stays a
// `Value` on this lane, so `o.value` is `no field value on type Value`. That is a different gap and
// this probe must not reach its subject through it.
def describe(a: Any): String =
  a match
    case Ok(v)  => "ok:" + v
    case Err(m) => "err:" + m
    case _      => "?"

def main(): Unit =
  println(describe(step("ok", 7)))
  println(describe(step("ok", 0)))
  println(describe(step("nope", 1)))
main()
SSC

row() { # $1 name, $2 expected
  local name=$1 want=$2 out
  out=$(timeout 200 "$ssc" run "$sandbox/$name.ssc" 2>&1 | head -4 | tr '\n' '|')
  [[ "$out" == "$want" ]] || { echo "  ✗ $name run  : got '$out', wanted '$want'"; fails=$((fails + 1)); }
  out=$(timeout 200 "$tools" run --v1 "$sandbox/$name.ssc" 2>&1 | head -4 | tr '\n' '|')
  [[ "$out" == "$want" ]] || { echo "  ✗ $name --v1 : got '$out', wanted '$want'"; fails=$((fails + 1)); }
  if ! command -v cargo >/dev/null 2>&1; then
    echo "  [skip] $name rust — cargo is not on PATH. That is a SKIP, not a pass."; return
  fi
  if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/$name.ssc" >"$sandbox/$name.log" 2>&1); then
    echo "  ✗ $name rust : build failed: $(grep -m1 -E 'error\[E[0-9]+\]|^error' "$sandbox/$name.log")"
    fails=$((fails + 1)); return
  fi
  out=$("$sandbox/$name" 2>&1 | head -4 | tr '\n' '|')
  if [[ "$out" == "$want" ]]; then echo "  ✓ $name: $out on all three lanes"
  else echo "  ✗ $name rust : got '$out', wanted '$want'"; fails=$((fails + 1)); fi
}

echo "── an Option-returning def is an Option when something maps over it"
row optmap  '[a]|-|'

echo "── Nil is a value, not only a pattern"
row nilvalue '3|0|'

echo "── a match arm in an Any tail lifts INSIDE its if, not around it"
row tailarm 'ok:7|err:not positive|err:unknown tag|'

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-json-core-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-json-core-gate: PASS"
