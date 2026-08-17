#!/usr/bin/env bash
#
# v2-entry-no-double-main-gate — a file that ends in `main()` runs `main` ONCE on every front.
#
# THE DEFECT (v2-front-fallback-runs-the-program-twice). The legacy front builds its entry as
# `Seq(top-level exprs…, main() if a zero-arg main exists)`. Those two halves landed at different
# times and were never reconciled: the appended call is the older one, and T5.7 later made top-level
# EXPRESSION statements run (they used to be silently dropped). From then on a file ending in
# `main()` — which most .ssc files in this repository do — got the explicit call from the statements
# AND the appended one, and `main` ran TWICE.
#
# WHY IT LOOKED LIKE SOMETHING ELSE. F is the default front and F guards this (`callsMain`), so the
# doubling needed BOTH an F decline and an explicit `main()`. It was first read as "the fallback runs
# the program twice". The 2x2 says otherwise and is why this gate has four rows:
#
#            trailing main()   no trailing main()
#   F front        once              once
#   legacy         TWICE             once
#
# `println` is only the visible half. The same entry runs whatever else the program does, so a file
# write or an HTTP call doubled the same way, with a stderr notice that said the program "still ran
# correctly".
#
# THE FRONT IS SELECTED, NOT PROVOKED. `SSC_FRONT=legacy` (RunNativeV2.frontIsF) picks the legacy
# front directly. The first version of this gate reached it through an F coverage gap instead — a row
# that would have gone quiet the day F learned that construct, testing nothing and saying PASS.
#
# THE `no-trailing` ROWS ARE THE ANTI-ROWS: with no explicit call, the appended one is what runs the
# program at all, and a fix that dropped it unconditionally would print NOTHING. They fail loudly in
# that case rather than passing on a technicality.
#
# COST: six runs, no cargo, ~15 s.
set -uo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
ssc="${SSC:-$ROOT/bin/ssc}"
tools="${SSC_TOOLS:-$ROOT/bin/ssc-tools}"
fails=0
export SSC_NO_BUILD_CHECK=1

[[ -x "$ssc" ]] || { echo "v2-entry-no-double-main-gate: no launcher at $ssc — run ./install.sh --dev" >&2; exit 2; }

sandbox=$(mktemp -d "$ROOT/examples/_entrymain.XXXXXX")
trap 'rm -rf "$sandbox"' EXIT HUP INT TERM

cat > "$sandbox/trailing.ssc" <<'SSC'
def main(): Unit =
  println("a")
  println("b")
main()
SSC

# The anti-row source: no explicit call, so the entry the front APPENDS is the only thing that runs.
cat > "$sandbox/no-trailing.ssc" <<'SSC'
def main(): Unit =
  println("a")
  println("b")
SSC

says() { # $1 label, $2 want, $3 env-prefix ("" or "legacy"), $4 file
  local label=$1 want=$2 front=$3 file=$4 out
  if [[ -n $front ]]; then
    out=$(SSC_FRONT="$front" timeout 200 "$ssc" run "$sandbox/$file" 2>/dev/null | head -8 | tr '\n' '|')
  else
    out=$(timeout 200 "$ssc" run "$sandbox/$file" 2>/dev/null | head -8 | tr '\n' '|')
  fi
  if [[ "$out" == "$want" ]]; then
    echo "  ✓ $label: $out"
  else
    echo "  ✗ $label: got '$out', wanted '$want'"
    fails=$((fails + 1))
  fi
}

echo "── trailing main() — the defect"
says "F front" 'a|b|' ""       trailing.ssc
says "legacy " 'a|b|' "legacy" trailing.ssc

echo "── no trailing main() — the anti-row: the appended entry is all there is"
says "F front" 'a|b|' ""       no-trailing.ssc
says "legacy " 'a|b|' "legacy" no-trailing.ssc

echo "── the reference lane answers the same"
ref=$(timeout 200 "$tools" run --v1 "$sandbox/trailing.ssc" 2>/dev/null | head -8 | tr '\n' '|')
if [[ "$ref" == 'a|b|' ]]; then
  echo "  ✓ --v1 trailing: $ref"
else
  echo "  ✗ --v1 trailing: got '$ref', wanted 'a|b|'"
  fails=$((fails + 1))
fi

echo
if [[ "$fails" -ne 0 ]]; then
  echo "v2-entry-no-double-main-gate: FAIL ($fails row(s))" >&2
  exit 1
fi
echo "v2-entry-no-double-main-gate: PASS"
