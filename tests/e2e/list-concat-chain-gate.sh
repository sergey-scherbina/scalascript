#!/usr/bin/env bash
#
# list-concat-chain-gate — `a ++ b ++ c` on LISTS must answer a list, on every lane.
#
# WHAT THIS CAUGHT. `List(1,2) ++ List(3) ++ List(4,5)` printed
#
#     int              List(1, 2, 3, 4, 5)
#     v2 / --bytecode  (1, List(2, 3), 4, List(5))          <- silently, exit 0
#
# and had been doing so for as long as the typed emission has existed. Nothing failed at the
# concat: the wrong value flowed on, and the only reason anybody looked is that four scljet corpus
# cases eventually called `.isEmpty` on one of these tuples and the missing-method sentinel had
# just been made fatal (v2/BUGS.md corpus-contract-scljet-jdbc-v2-timeout).
#
# THE CAUSE IS A TYPE THE FRONT RECOVERS AND A PRIM THAT TRUSTS IT. F types `++` by the shape of
# its LEFT operand's IR, and one of the prefixes it reads as "String" is `++` itself
# (`isConcatCode`, specs/v2.2-p6.5-fsub.ssc) — true when the operands are strings, false for every
# other `++`-able type. So the OUTER concat of a three-way chain lowers to `(prim sconcat …)`. The
# prim then met two lists: a v2 list is `DataV("Cons", [head, tail])`, a two-field DataV, which its
# tuple arm read as a pair and concatenated field-wise.
#
# WHY A CHAIN AND NOT A PAIR. `a ++ b` alone lowers to the dynamic `(prim __arith__ "++")`, which
# is correct. It takes three operands before the front has a `++` on its left to mis-type — which
# is why no two-operand test anywhere in this repository could see it, and why the subject here is
# a CHAIN.
#
# METHOD. One subject file, seven rows, three lanes. The int lane is the oracle AND is itself
# checked against a frozen expectation, because an oracle that can drift silently is not one. Then
# every other lane must equal it, line for line.
#
# IT MUST NOT GO QUIET. A lane that produces no output at all fails the gate rather than being
# skipped past; the whole point is that the wrong answer here is a plausible-looking value, not a
# crash, so "nothing came back" must never read as agreement.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
ssc_usable_or_skip "list-concat-chain-gate" "$ssc"

work=$(mktemp -d "${TMPDIR:-/tmp}/list-concat-chain.XXXXXX") || exit 1
trap 'rm -rf "$work"' EXIT
subject="$work/chain.ssc"

# Seven rows. Rows 3 and 5 are the CONTROLS: strings and tuples already chained correctly, so a
# fix that repairs lists by breaking either of those is caught here and not in the corpus.
cat > "$subject" <<'SSC'
def main() =
  val a = List(1, 2)
  val b = List(3)
  val c = List(4, 5)
  val d = List(6)
  println(a ++ b ++ c)
  println(a ++ b ++ c ++ d)
  println("x" ++ "y" ++ "z")
  val s = List("p", "q")
  println(s ++ List("r") ++ List("s"))
  println((1, 2) ++ (3, 4) ++ (5, 6))
  println(a ++ Nil ++ b)
  println(Nil ++ a ++ b)
SSC

# The frozen answer — Scala's, and what the int lane printed on 2026-08-09. It is written out
# rather than taken from a lane so that "all lanes agree" cannot be satisfied by all lanes being
# wrong together.
expected=$(cat <<'EOF'
List(1, 2, 3, 4, 5)
List(1, 2, 3, 4, 5, 6)
xyz
List(p, q, r, s)
(1, 2, 3, 4, 5, 6)
List(1, 2, 3)
List(1, 2, 3)
EOF
)

fail=0
run_lane() {  # run_lane <label> <command…>
  local label=$1; shift
  local out
  out=$(SSC_NO_BUILD_CHECK=1 timeout 240 "$@" "$subject" 2>&1 |
          grep -v 'fell back to the VM lane')
  if [ -z "$out" ]; then
    echo "FAIL $label: produced NO output — the lane did not run, which is not agreement"
    fail=1
    return
  fi
  if [ "$out" = "$expected" ]; then
    echo "ok   $label"
    return
  fi
  echo "FAIL $label: differs from the frozen answer"
  diff <(printf '%s\n' "$expected") <(printf '%s\n' "$out") | sed 's/^/       /'
  fail=1
}

# The int lane first: it is the oracle, so if IT has drifted every later comparison is meaningless
# and the gate should say which one broke.
if [ -x "$tools" ]; then
  run_lane "int      " "$tools" run --v1
else
  echo "note: $tools absent — the oracle lane was not measured"
fi
run_lane "v2       " "$ssc" run --v2
run_lane "bytecode " "$ssc" run --bytecode

# The js lane earns its place here by having failed a DIFFERENT row from the other three: lists and
# strings were right and `(1,2) ++ (3,4)` printed the STRING `(1, 2)(3, 4)`, because `$arith` had a
# list arm and no tuple arm. Row 5 is not decoration — it is the only row that was ever red here.
# `run-js --v2`, not `bin/jssc`: that wrapper is `emit-js | node`, the deprecated v1 hybrid whose
# own help says to use this instead, and it fails row 3 for an unrelated reason
# (v2/BUGS.md js-string-concat-chain-answers-a-tuple).
if [ -x "$tools" ] && command -v node >/dev/null 2>&1; then
  run_lane "js       " "$tools" run-js --v2
  # THE v1 JS LANE TOO, added 2026-08-17, and the note above was wrong about what it is. It called
  # `bin/jssc` "the deprecated v1 hybrid" and left the whole v1 emitter unmeasured — but `run-js`
  # without `--v2` and `emit-js` are that same emitter, and `emit-js` is how the CONFORMANCE suite
  # defines its js column (`tests/conformance/run.sc`). So the lane this gate skipped is the lane
  # 211 corpus cases are scored on.
  #
  # It skipped it because row 3 was RED there — `"x" ++ "y" ++ "z"` answered `(x, y, z)`, a tuple —
  # which is exactly what a gate should be reporting rather than routing around. Fixed in
  # `core-collections.mjs` (v2/BUGS.md `js-string-concat-chain-answers-a-tuple`); all seven rows
  # agree here now, so adding the lane costs nothing and closes the hole that let the defect live
  # for eight days with a green gate beside it.
  run_lane "js-v1    " "$tools" run-js
else
  echo "note: run-js not measured (needs the optional tools tier and node)"
fi

if [ "$fail" -ne 0 ]; then
  echo
  echo "A \`++\` chain is answering the wrong SHAPE on some lane. Which rows failed says which copy:"
  echo "  rows 1/2/4/6/7 (lists)  -> \`sconcat\`'s Data++Data arm is reading a Cons CELL as a pair"
  echo "                             (v2/src/Runtime.scala, twin in v2/backend/jvm/JvmBackend.scala)"
  echo "  row 5 (tuples)          -> the lane has no TUPLE arm and fell through to string concat"
  echo "                             (v2/backend/js/JsBackend.scala, \`\$arith\` and \`sconcat\`)"
  echo "  row 3 (strings)         -> a string chain is being read as a tuple concat"
  echo "Each lane here has failed a DIFFERENT row, so do not assume one cause fixes all of them."
  exit 1
fi
echo "PASS list-concat-chain-gate: 7 rows agree on every measured lane"
