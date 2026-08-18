#!/usr/bin/env bash
#
# rust-any-list-boundary-gate — a list crossing into `List[Any]` works from BOTH sides.
#
# The coercion for a `Vec<Value>` parameter emitted `.into_iter().map(Value::from)`, which assumes
# the expression already IS a sequence. It is, when the call site writes `count(List(4, 5))`. It is
# NOT when the list arrives out of an `Any` — `case Node(kids) => count(kids)` binds `kids` to a
# `Value` that HOLDS a list, and `error[E0599]: Value is not an iterator` is what rustc said. That
# was 27 errors across five std modules, the largest single class behind the parenless-member
# refusal (build-rust-drops-defs-it-cannot-lower-without-saying-so).
#
# `ssc_val_list` is implemented for `Value` AND for `Vec<T: Into<Value>>`, so one emission serves
# both — the same property that lets `ssc_int` be emitted without knowing which side of the
# boundary an expression was on.
#
# BOTH SIDES ARE ROWS HERE ON PURPOSE. A fix that only narrowed the `Value` would break the literal
# call, and the literal call is the one every existing golden exercises — so a green on it alone
# proves nothing about the half that was broken.
#
# COMPARED AGAINST `run`: a wrong Value variant still compiles and still prints a number.
#
# COST: one cargo build plus one interpreter run, ~40 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" && -x "$ssc" ]] || { echo "rust-any-list-boundary-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }
if ! command -v cargo >/dev/null 2>&1; then
  echo "rust-any-list-boundary-gate: [skip] cargo is not on PATH. That is a SKIP, not a pass." >&2
  exit 0
fi

sandbox=$(mktemp -d "$ROOT/examples/_anylist.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/l.ssc" <<'SSC'
case class Node(kids: Any)

def count(xs: List[Any]): Int = xs.length

def go(n: Any): Int = n match
  case Node(kids) => count(kids)
  case _ => -1

def main(): Unit =
  println("from-any: " + go(Node(List(1, 2, 3))))
  println("literal: " + count(List(4, 5)))

main()
SSC

want=$(timeout 600 "$ssc" run "$sandbox/l.ssc" 2>/dev/null)
[[ -z "$want" ]] && { echo "  ✗ the interpreter produced nothing — the oracle is unusable" >&2; exit 1; }

echo "── a list crossing into List[Any], from both sides"
if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/l.ssc" >"$sandbox/build.log" 2>&1); then
  echo "  ✗ build-rust failed — a list out of an Any does not reach a List[Any] parameter:"
  grep -m3 -E 'error\[E[0-9]+\]|Generic\(' "$sandbox/build.log" | cut -c1-110 | sed 's/^/      /'
  exit 1
fi
got=$(timeout 300 "$sandbox/l" 2>/dev/null)

while IFS= read -r row; do
  mine=$(printf '%s\n' "$got" | grep -F "${row%%:*}:" || true)
  if [[ "$mine" == "$row" ]]; then echo "  ✓ $row"
  else echo "  ✗ ${row%%:*}: rust '${mine#*: }', interpreter '${row#*: }'"; fails=$((fails + 1)); fi
done <<< "$want"

# The oracle must still answer 3 from inside the `Any` — a lane that lost the list and answered 0
# or -1 would agree with a rust binary that had lost it the same way.
if printf '%s\n' "$want" | grep -q 'from-any: 3'; then
  echo "  ✓ the oracle itself is right: the list survives the Any and still has three elements"
else
  echo "  ✗ the interpreter no longer answers 3 — the oracle regressed"; fails=$((fails + 1))
fi

echo
if [[ "$fails" -ne 0 ]]; then echo "rust-any-list-boundary-gate: FAIL ($fails)" >&2; exit 1; fi
echo "rust-any-list-boundary-gate: PASS"
