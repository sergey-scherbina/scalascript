#!/usr/bin/env bash
# Tier 2 std/ui (layout) smoke — renders the layout-demo and asserts
# every primitive's scoped class names appear at least once on the page.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"
DEMO="$ROOT/examples/std-ui/layout-demo.ssc"

body=$("$BIN/ssc" render "$DEMO" 2>/dev/null)

fail=0
echo "============================================================"
echo "  std/ui Tier 2 (layout) smoke — INT via ssc render"
echo "============================================================"
echo

declare -A want=(
    ["root__Stack"]=2          # 1 CSS rule + 1 instance
    ["root__Row"]=3            # 1 CSS rule + 2 instances (wrap + spaced)
    ["root__Grid"]=2           # CSS + 1 grid instance (media-query line doesn't reuse the class name)
    ["root__Container"]=2      # 1 CSS rule + 1 instance
    ["plain__Divider"]=2       # 1 CSS rule + 1 plain instance
    ["labeled__Divider"]=4     # 1 .labeled rule + 1 ::before/::after rule + 2 instances (>= because there are 4 labeled)
    ["root__Spacer"]=3         # 1 CSS rule + 2 instances (auto + 2em)
)

for marker in "${!want[@]}"; do
    got=$(echo "$body" | grep -c "$marker" || true)
    exp="${want[$marker]}"
    if [ "$got" -ge "$exp" ]; then
        echo "  [PASS] $marker  ($got, ≥ $exp)"
    else
        echo "  [FAIL] $marker  ($got, need ≥ $exp)"
        fail=1
    fi
done

echo
if [ $fail -eq 0 ]; then
    echo "Tier 2 layout primitives render via the interpreter."
    exit 0
fi
exit 1
