#!/usr/bin/env bash
#
# rust-mkstring-parity-gate — mkString answers the same on both lanes, for every element type.
#
# TWO DEFECTS, AND THE SECOND IS THE ONE THAT PRINTS WRONG ANSWERS.
#
# 1. `xs.mkString(sep)` lowered to Rust's `xs.join(sep)`, which is defined for `Vec<String>` and for
#    nothing else. `List(1,2,3).mkString(",")` — five lines, `1,2,3` under `run` — did not build:
#    `error[E0599]: the method 'join' exists for struct 'Vec<i64>', but its trait bounds were not
#    satisfied`. Invisible in the programs people write first, because the String case works.
#
# 2. The THREE-argument form was silently wrong rather than unsupported: the separator was read with
#    `headOption`, so `mkString("[", ",", "]")` emitted `join("[")` and printed `a[b[c`. It compiled.
#    That is the row to watch — a build-only check passes on it.
#
# COMPARED AGAINST `run`, ROW BY ROW, not against literals: a change that moved both lanes together
# is still a defect, and hard-coded strings would not see it. The Double row is here because Rust's
# `Display` and this language's own rendering could disagree about `2.0` — measured, both print `2`,
# and this row is what keeps that true.
#
# THE ROWS THIS GATE DOES NOT HAVE, and why — both are separate, still-open gaps, and including them
# would make this gate measure someone else's defect:
#   * `xs.mkString` written with NO parentheses is refused by the no-paren member rule (`mkString` is
#     in CollectionOnlyMembers), so it never reaches the lowering at all.
#   * `List[String]()` — a typed empty list literal — is "calls List[String] which has no resolvable
#     name". The empty-list case is therefore untested here.
#
# COST: one cargo build plus one interpreter run, ~40 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
ssc="${SSC_BIN:-$ROOT/bin/ssc}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$tools" && -x "$ssc" ]] || { echo "rust-mkstring-parity-gate: no launcher — run ./install.sh --dev" >&2; exit 2; }

if ! command -v cargo >/dev/null 2>&1; then
  echo "rust-mkstring-parity-gate: [skip] cargo is not on PATH. That is a SKIP, not a pass." >&2
  exit 0
fi

sandbox=$(mktemp -d "$ROOT/examples/_mkstr.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/m.ssc" <<'SSC'
def main(): Unit =
  println("ints   : " + List(1, 2, 3).mkString(","))
  println("strs   : " + List("a", "b").mkString(","))
  println("bools  : " + List(true, false).mkString(","))
  println("dbls   : " + List(1.5, 2.0).mkString(","))
  println("nosep  : " + List("x", "y").mkString(""))
  println("wrapped: " + List("a", "b", "c").mkString("[", ",", "]"))
  println("single : " + List(7).mkString(","))

main()
SSC

int_out=$(timeout 600 "$ssc" run "$sandbox/m.ssc" 2>/dev/null)
if [[ -z "$int_out" ]]; then
  echo "  ✗ the interpreter produced nothing — the oracle is unusable, so this gate cannot decide" >&2
  exit 1
fi

if ! (cd "$sandbox" && timeout 900 "$tools" build-rust "$sandbox/m.ssc" >"$sandbox/build.log" 2>&1); then
  echo "  ✗ build-rust failed — mkString does not lower for some element type:"
  grep -m3 -E 'Generic\(|error\[E[0-9]+\]' "$sandbox/build.log" | cut -c1-120 | sed 's/^/      /'
  exit 1
fi
rust_out=$("$sandbox/m" 2>/dev/null)

echo "── mkString answers the same on both lanes"
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

# The oracle must still be right, or two regressed lanes would agree on every row above. The
# three-argument row is the one that was silently wrong, so it is the one pinned by value.
if printf '%s\n' "$int_out" | grep -q 'wrapped: \[a,b,c\]'; then
  echo "  ✓ the oracle itself is right: mkString(pre, sep, post) wraps once and separates between"
else
  echo "  ✗ the interpreter no longer answers '[a,b,c]' for mkString(\"[\", \",\", \"]\") — the oracle"
  echo "    regressed, so agreement between the lanes proves nothing here"
  fails=$((fails + 1))
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "rust-mkstring-parity-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "rust-mkstring-parity-gate: PASS"
