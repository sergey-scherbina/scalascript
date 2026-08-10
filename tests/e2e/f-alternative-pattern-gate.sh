#!/usr/bin/env bash
#
# f-alternative-pattern-gate — `case A | B =>` must answer for BOTH tags, on every front.
#
# WHAT THIS CAUGHT. F's `parseArmBody` reads the token after a pattern tag as the arrow and skips
# it. For `case A | B` that token is the PIPE, so the body was parsed starting at `B => …` and the
# second alternative never became an arm. One cause, two symptoms that look unrelated:
#
#   case A | B => "x"            A answers "x";  B dies `match: no arm for B/0`
#   case A | B =>                even A dies, `app: not a function: 0 … <closure/0>` — `B =>`
#     <indented block>           followed by a block reads as a TRAILING BLOCK ARGUMENT, so the
#                                arm body became an application instead of the block
#
# The second symptom is the expensive one and it names nothing useful. It took five scljet corpus
# rows, `tests/e2e/bytecode-fallback-visible.sh`, and the `ci.yml` job running
# `v2-f-nested-bytecode-fast-path.sh` — all from one `case TableLeafPage | IndexLeafPage =>` with a
# block body in `scljet/btree.ssc` (v2/BUGS.md scljet-app-not-a-function-after-the-concat-fix).
#
# THE BLOCK-BODY ROW IS THE POINT. A single-expression alternative fails only on the SECOND tag,
# which is easy to miss in a corpus where the first tag is the common case; the block form fails on
# the FIRST tag too. A gate with only the single-expression row would have gone green while scljet
# stayed broken, so both shapes are here and both are required.
#
# THE FRONTS ARE COMPARED, not just F. The defect was found because `legacy` ran the same file
# correctly — a differential, not an oracle-free assertion — so this keeps that structure: the
# frozen answer is Scala's, `int` confirms it, and every front must reproduce it.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
. "$SCRIPT_DIR/lib/ssc-usable.sh"
ssc_usable_or_skip "f-alternative-pattern-gate" "$ssc"

work=$(mktemp -d "${TMPDIR:-/tmp}/f-alt-pattern.XXXXXX") || exit 1
trap 'rm -rf "$work"' EXIT
subject="$work/alt.ssc"

cat > "$subject" <<'SSC'
sealed trait Kind
case object LeafA extends Kind
case object LeafB extends Kind
case object Other extends Kind

def single(k: Kind): String = k match
  case LeafA | LeafB => "leaf"
  case Other => "other"

def blocky(k: Kind): String = k match
  case LeafA | LeafB =>
    val tag = "le"
    tag + "af"
  case Other => "other"

def three(k: Kind): String = k match
  case LeafA | LeafB | Other => "any"

def main() =
  println(single(LeafA))
  println(single(LeafB))
  println(single(Other))
  println(blocky(LeafA))
  println(blocky(LeafB))
  println(blocky(Other))
  println(three(Other))
SSC

expected=$(cat <<'EOF'
leaf
leaf
other
leaf
leaf
other
any
EOF
)

fail=0
# The front is a separate parameter rather than an `env VAR=v -- cmd` prefix: BSD `env` rejects the
# `--` after an assignment ("env: --: No such file or directory"), which made every front row fail
# for a shell reason. A gate that fails identically in BOTH states proves nothing, so this is the
# form that keeps the two distinguishable.
run_lane() {  # run_lane <label> <front|-> <command…>
  local label=$1 front=$2; shift 2
  local out
  if [ "$front" = "-" ]; then
    out=$(SSC_NO_BUILD_CHECK=1 timeout 240 "$@" "$subject" 2>&1)
  else
    out=$(SSC_FRONT="$front" SSC_NO_BUILD_CHECK=1 timeout 240 "$@" "$subject" 2>&1)
  fi
  if [ -z "$out" ]; then
    echo "FAIL $label: produced NO output — the lane did not run, which is not agreement"
    fail=1; return
  fi
  if [ "$out" = "$expected" ]; then echo "ok   $label"; return; fi
  echo "FAIL $label: differs from the frozen answer"
  diff <(printf '%s\n' "$expected") <(printf '%s\n' "$out") | sed 's/^/       /'
  fail=1
}

if [ -x "$tools" ]; then
  run_lane "int      " - "$tools" run --v1
else
  echo "note: $tools absent — the oracle lane was not measured"
fi
run_lane "v2/F     " F      "$ssc" run --v2
run_lane "v2/legacy" legacy "$ssc" run --v2

if [ "$fail" -ne 0 ]; then
  echo
  echo "An alternative pattern is not answering for every tag it names."
  echo "  a SECOND-tag failure  -> the alternative list is being truncated after the first tag"
  echo "  a FIRST-tag failure   -> the arm body was parsed from the leftover \`| B =>\`, so a block"
  echo "                           body became an application (\`app: not a function\`)"
  echo "F parses this in parseCtorArm1/parseAltArm (specs/v2.2-p6.5-fsub.ssc)."
  exit 1
fi
echo "PASS f-alternative-pattern-gate: 7 rows agree on every measured front"
