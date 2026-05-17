#!/usr/bin/env bash
# Tier 7 std/ui (widgets) smoke — Avatar, Badge, Tooltip, Accordion,
# Dropdown rendered through `ssc render` with markers asserted.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"
DEMO="$ROOT/examples/std-ui/widgets-demo.ssc"

body=$("$BIN/ssc" render "$DEMO" 2>/dev/null)

fail=0
echo "============================================================"
echo "  std/ui Tier 7 (widgets) smoke — INT via ssc render"
echo "============================================================"
echo

declare -A want=(
    ["root__Avatar"]=5            # CSS rule + 4 avatars
    ["init__Avatar"]=4            # CSS rule + 3 initials spans
    ["img__Avatar"]=2             # CSS rule + 1 image
    ["root__Badge"]=2
    ["info__Badge"]=2
    ["success__Badge"]=2
    ["warn__Badge"]=2
    ["danger__Badge"]=2
    ["root__Tooltip"]=3           # CSS + 2 uses
    ["root__Accordion"]=2
    ["item__Accordion"]=5         # CSS rules + 3 details
    ["single__Accordion"]=2       # Class on root + JS selector (no CSS rule)
    ["root__Dropdown"]=3          # CSS + DOM + JS selector
    ["open__Dropdown"]=3          # JS toggling references (classList ops)
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

# Initials derivation
if echo "$body" | grep -q '<span class="init__Avatar">AC</span>'; then
    echo "  [PASS] Avatar initials derived correctly (AC)"
else
    echo "  [FAIL] Avatar initials not 'AC'"
    fail=1
fi

# Tooltip text in data-tip
if echo "$body" | grep -q 'data-tip="The spacing between specific letter pairs."'; then
    echo "  [PASS] Tooltip data-tip set"
else
    echo "  [FAIL] Tooltip data-tip missing"
    fail=1
fi

# Accordion JS hook
if echo "$body" | grep -q "querySelectorAll('.single__Accordion')"; then
    echo "  [PASS] Accordion single-open JS"
else
    echo "  [FAIL] Accordion JS missing"
    fail=1
fi

# Dropdown JS hook
if echo "$body" | grep -q "querySelectorAll('.root__Dropdown')"; then
    echo "  [PASS] Dropdown toggle JS"
else
    echo "  [FAIL] Dropdown JS missing"
    fail=1
fi

echo
if [ $fail -eq 0 ]; then
    echo "Tier 7 widgets render via the interpreter."
    exit 0
fi
exit 1
