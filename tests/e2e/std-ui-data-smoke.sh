#!/usr/bin/env bash
# Tier 5 std/ui (data display) smoke — Table, DataList, DataGrid,
# KeyValue rendered through `ssc render` with markers asserted.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"
DEMO="$ROOT/examples/std-ui/data-demo.ssc"

body=$("$BIN/ssc" render "$DEMO" 2>/dev/null)

fail=0
echo "============================================================"
echo "  std/ui Tier 5 (data display) smoke — INT via ssc render"
echo "============================================================"
echo

declare -A want=(
    ["root__Table"]=2
    ["root__DataList"]=2
    ["root__DataGrid"]=2
    ["root__KeyValue"]=2
    ["num__Table"]=5      # CSS rule + active-issues column header + 4 data cells
    ["filter__DataGrid"]=2
    ["interactive__DataList"]=2
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

# DataGrid's filter JS hook
if echo "$body" | grep -q "querySelectorAll('.root__DataGrid')"; then
    echo "  [PASS] DataGrid filter handler in <script>"
else
    echo "  [FAIL] DataGrid filter handler not in <script>"
    fail=1
fi

echo
if [ $fail -eq 0 ]; then
    echo "Tier 5 data-display components render via the interpreter."
    exit 0
fi
exit 1
