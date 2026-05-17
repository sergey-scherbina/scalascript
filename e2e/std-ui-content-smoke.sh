#!/usr/bin/env bash
# Tier 6 std/ui (content / typography) smoke — Code, MarkdownBlock,
# Quote rendered through `ssc render` with markers asserted.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"
DEMO="$ROOT/examples/std-ui/content-demo.ssc"

body=$("$BIN/ssc" render "$DEMO" 2>/dev/null)

fail=0
echo "============================================================"
echo "  std/ui Tier 6 (content / typography) smoke — INT via ssc render"
echo "============================================================"
echo

declare -A want=(
    ["root__Code"]=4         # CSS rule + 2 block snippets + nested rule
    ["inline__Code"]=2       # CSS rule + inline use
    ["root__MarkdownBlock"]=2
    ["root__Quote"]=4        # CSS rule + 3 quote blocks
    ["body__Quote"]=5        # CSS rule, two nested rules, 3 usages — see ≥5
    ["cite__Quote"]=4        # CSS rule + ::before + 2 cite tags (third omitted)
    ["language-scala"]=1
    ["language-bash"]=1
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

# Inline-code snippet should be HTML-escaped (no raw $)
if echo "$body" | grep -q '&quot;Hello, $name!&quot;'; then
    echo "  [PASS] Code block content is HTML-escaped"
else
    echo "  [FAIL] Code block content not properly escaped"
    fail=1
fi

# Citationless quote: third blockquote ends without a <cite>
if echo "$body" | grep -q "An unattributed pull-quote still looks the part."; then
    echo "  [PASS] Citationless quote renders without crashing"
else
    echo "  [FAIL] Citationless quote missing"
    fail=1
fi

echo
if [ $fail -eq 0 ]; then
    echo "Tier 6 content / typography components render via the interpreter."
    exit 0
fi
exit 1
