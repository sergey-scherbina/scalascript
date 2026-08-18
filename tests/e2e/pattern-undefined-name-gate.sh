#!/usr/bin/env bash
# An UNDEFINED name in a pattern must mean the same thing on every lane. Today it means three
# different things, and this gate pins that so the next change to any lane is visible.
#
#     x match
#       case Nope => …     // `Nope` is defined nowhere
#       case _    => …
#
#     int     unknown constructor 'Nope' in a pattern    REFUSED with a position (since 2026-08-18)
#     native  NO MATCH                             silent, exit 0
#     js      ReferenceError: Nope is not defined  throws, exit 1
#
# Scala 3 refuses to compile this — `not found: value Nope`. The name is capitalised, so it is a
# stable-identifier pattern rather than a binding; no lane resolves it and each invents its own
# answer for the unresolvable case. Two lanes lose the arm with no diagnostic, which is the quiet
# failure: a renamed or mistyped constructor simply stops matching. The third turns a compile error
# into a run-time crash in whichever branch reaches it first.
#
# WHY THIS PINS THE DEFECT INSTEAD OF ASSERTING THE FIX. The wanted behaviour is a compile error on
# all three lanes, and no lane produces it, so a gate asserting that is red on arrival and cannot
# land. Freezing what the lanes do today is not endorsement — it is what makes a CHANGE visible.
# Every row below is declared KNOWN-RED against the wanted behaviour, and the gate fails in BOTH
# directions: a lane that stops matching this row is either fixed or newly broken, and either way
# somebody must look. If a lane starts rejecting the program at compile time, DELETE its row rather
# than editing the expectation to match — that row becoming wrong is the point of it existing.
#
# Root entry: `an-undefined-name-in-a-pattern-means-three-different-things` (BUGS.md, lane multi).
# The sibling `multi-name-val-binds-garbage-and-says-nothing` is the same shape on a different
# construct: a form no corpus covers, each lane answering differently, and the quiet lanes the
# dangerous ones.
#
# The probe is ASCII on purpose. This was found while checking whether `case Ⅷ =>` still diverges
# across lanes — it does not, and never did — and the ASCII control is what carried the defect. A
# Unicode probe here would suggest the cause is the alphabet. It is not: it is resolution.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/bin"

# Refusing beats skipping: a gate that passes when it could not run is worse than no gate.
for launcher in ssc ssc-tools jssc; do
  if [ ! -x "$BIN/$launcher" ]; then
    echo "FAIL: $BIN/$launcher is missing — run ./install.sh --dev first"
    exit 1
  fi
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SRC="$WORK/pattern-undefined-name.ssc"

cat > "$SRC" <<'EOF'
def main() =
  val x = 1
  x match
    case Nope => println("BOUND, value=" + Nope)
    case _ => println("NO MATCH")
EOF

# The CONTROL, and it is not decoration. A lowercase name in the same position is a BINDING and must
# match anything — so if this row ever fails, the capitalisation rule itself has moved and the rows
# below are measuring something other than what they claim.
CTRL="$WORK/pattern-lowercase-binds.ssc"
cat > "$CTRL" <<'EOF'
def main() =
  val x = 1
  x match
    case nope => println("BOUND, value=" + nope)
EOF

fails=0
# `label` distinguishes the control from the frozen defect. Printing KNOWN-RED for the control would
# read as "a lowercase name binding is also broken", which is the opposite of what it proves.
check() {                     # check <label> <lane> <expected-substring> <actual>
  local label="$1" lane="$2" want="$3" got="$4"
  if grep -qF -- "$want" <<<"$got"; then
    echo "  $label $lane: $want"
  else
    echo "  FAIL $lane: expected to contain [$want]"
    echo "        got: $(head -3 <<<"$got" | tr '\n' ' ')"
    echo "        If this lane now REJECTS the program at compile time, that is the fix —"
    echo "        delete this lane's row from the gate instead of loosening it."
    fails=$((fails + 1))
  fi
}

export SSC_NO_BUILD_CHECK=1

echo "control — a lowercase name in pattern position still BINDS:"
ctrl_out="$("$BIN/ssc-tools" run --v1 "$CTRL" 2>&1)"
check "OK       " "int" "BOUND, value=1" "$ctrl_out"

echo "an undefined CAPITALISED name in pattern position:"
# THE int ROW IS THE FIX NOW, not a freeze. Its KNOWN-RED row was deleted 2026-08-18, exactly as
# the header instructs — the lane refuses with the same sentence v3's front uses, and this row
# asserts the fix stays: the message, and that "NO MATCH" does NOT print (the arm must not fall
# through to the wildcard on its way to the refusal). The other two rows remain KNOWN-RED freezes.
# Safety was measured, not argued: the full conformance corpus on the int lane with the refusal in
# place — no case lost, so no working program resolves nothing in a capitalised pattern.
int_out="$("$BIN/ssc-tools" run --v1 "$SRC" 2>&1)"
check "OK       " "int" "unknown constructor 'Nope' in a pattern" "$int_out"
if grep -qF "NO MATCH" <<<"$int_out"; then
  echo "  FAIL int: printed NO MATCH — the arm fell through before the refusal fired"
  fails=$((fails + 1))
fi

nat_out="$("$BIN/ssc" run "$SRC" 2>&1)"
check "KNOWN-RED" "native" "NO MATCH" "$nat_out"

js_out="$("$BIN/jssc" "$SRC" 2>&1)"
check "KNOWN-RED" "js" "ReferenceError: Nope is not defined" "$js_out"

# The DIVERGENCE itself, stated as its own assertion rather than left implicit in three rows. If a
# future change made every lane throw, the rows above would still pass one by one while the thing
# this gate exists to report — that the lanes disagree — had been resolved without anybody noticing.
if grep -qF "NO MATCH" <<<"$js_out"; then
  echo "  NOTE js now agrees with int and native — the divergence is gone."
  echo "       Re-read the entry: this gate should be deleted or rewritten, not left passing."
  fails=$((fails + 1))
fi

if [ "$fails" -ne 0 ]; then
  echo "pattern-undefined-name-gate: FAIL ($fails)"
  exit 1
fi
echo "pattern-undefined-name-gate: OK — three lanes, three meanings, all still as recorded"
