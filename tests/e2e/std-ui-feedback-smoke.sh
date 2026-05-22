#!/usr/bin/env bash
# Tier 4 std/ui (feedback / overlays) smoke — renders feedback-demo and
# asserts every component's scoped class names appear at least the
# expected count.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"
DEMO="$ROOT/examples/std-ui/feedback-demo.ssc"

body=$("$BIN/ssc" render "$DEMO" 2>/dev/null)

fail=0
echo "============================================================"
echo "  std/ui Tier 4 (feedback) smoke — INT via ssc render"
echo "============================================================"
echo

declare -A want=(
    ["root__Spinner"]=3       # 3 size variants visible
    ["root__ProgressBar"]=4   # 3 instances + CSS
    ["root__Skeleton"]=4      # 3 placeholders + CSS
    ["backdrop__Modal"]=2     # 1 instance + CSS
    ["backdrop__Drawer"]=2    # 1 instance + CSS
    ["stack__Toast"]=2        # 1 prerender stub + CSS
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

# JS glue for the interactive parts.
for marker in 'window.Modal' 'window.Drawer' 'window.Toast'; do
    got=$(echo "$body" | grep -c "$marker" || true)
    if [ "$got" -ge 1 ]; then
        echo "  [PASS] $marker"
    else
        echo "  [FAIL] $marker not in <script>"
        fail=1
    fi
done

echo
if [ $fail -eq 0 ]; then
    echo "Tier 4 feedback components render via the interpreter."
    exit 0
fi
exit 1
