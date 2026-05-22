#!/usr/bin/env bash
# Tier 3 std/ui (navigation) smoke — renders nav-demo and asserts every
# component's scoped class names appear at least the expected count.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"
DEMO="$ROOT/examples/std-ui/nav-demo.ssc"

body=$("$BIN/ssc" render "$DEMO" 2>/dev/null)

fail=0
echo "============================================================"
echo "  std/ui Tier 3 (navigation) smoke — INT via ssc render"
echo "============================================================"
echo

declare -A want=(
    ["root__NavBar"]=2
    ["root__Breadcrumbs"]=2
    ["root__Tabs"]=3
    ["root__Pagination"]=2
    ["root__Sidebar"]=2
    ["querySelectorAll"]=3
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

# Sanity: the tab strip emits an aria-selected="true" for the active
# tab, and aria-hidden="false" for the matching panel.
for marker in 'aria-selected="true"' 'aria-hidden="false"'; do
    got=$(echo "$body" | grep -c "$marker" || true)
    if [ "$got" -ge 1 ]; then
        echo "  [PASS] $marker  ($got)"
    else
        echo "  [FAIL] $marker  ($got, need ≥ 1)"
        fail=1
    fi
done

echo
if [ $fail -eq 0 ]; then
    echo "Tier 3 navigation components render via the interpreter."
    exit 0
fi
exit 1
