#!/usr/bin/env bash
# Tier 8 std/ui (theming) smoke — Theme tokens, ThemeProvider override,
# ThemeToggle JS, Reset rules rendered via `ssc render`.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"
DEMO="$ROOT/examples/std-ui/theming-demo.ssc"

body=$("$BIN/ssc" render "$DEMO" 2>/dev/null)

fail=0
echo "============================================================"
echo "  std/ui Tier 8 (theming) smoke — INT via ssc render"
echo "============================================================"
echo

# Light/dark token blocks
if echo "$body" | grep -q "^:root {"; then
    echo "  [PASS] Theme.css emits :root token block"
else
    echo "  [FAIL] :root token block missing"
    fail=1
fi
if echo "$body" | grep -q '\[data-theme="dark"\]'; then
    echo "  [PASS] Theme.css emits [data-theme=\"dark\"] override"
else
    echo "  [FAIL] dark override missing"
    fail=1
fi
if echo "$body" | grep -q "prefers-color-scheme: dark"; then
    echo "  [PASS] prefers-color-scheme media query present"
else
    echo "  [FAIL] media-query fallback missing"
    fail=1
fi

# Individual core tokens
for tok in "--ui-color-primary: #2563eb" \
           "--ui-color-bg: #ffffff" \
           "--ui-radius-md: 6px" \
           "--ui-space-3: 0.75rem"; do
    if echo "$body" | grep -q -- "$tok"; then
        echo "  [PASS] token: $tok"
    else
        echo "  [FAIL] token missing: $tok"
        fail=1
    fi
done

# Dark override values
if echo "$body" | grep -q -- "--ui-color-primary: #60a5fa"; then
    echo "  [PASS] dark override sets primary to #60a5fa"
else
    echo "  [FAIL] dark primary override missing"
    fail=1
fi

# ThemeProvider inline override
if echo "$body" | grep -q 'class="root__ThemeProvider".*--ui-color-primary: #a855f7'; then
    echo "  [PASS] ThemeProvider applies inline tokens"
else
    echo "  [FAIL] ThemeProvider inline tokens missing"
    fail=1
fi

# ThemeToggle button + boot script
if echo "$body" | grep -q 'class="root__ThemeToggle"'; then
    echo "  [PASS] ThemeToggle button rendered"
else
    echo "  [FAIL] ThemeToggle button missing"
    fail=1
fi
if echo "$body" | grep -q "localStorage.getItem('ssc-theme')"; then
    echo "  [PASS] ThemeToggle boot script in <head>"
else
    echo "  [FAIL] ThemeToggle boot script missing"
    fail=1
fi
if echo "$body" | grep -q "localStorage.setItem('ssc-theme'"; then
    echo "  [PASS] ThemeToggle persists choice"
else
    echo "  [FAIL] ThemeToggle persistence missing"
    fail=1
fi

# Reset signature
if echo "$body" | grep -q "box-sizing: border-box"; then
    echo "  [PASS] Reset includes box-sizing fix"
else
    echo "  [FAIL] Reset box-sizing missing"
    fail=1
fi

echo
if [ $fail -eq 0 ]; then
    echo "Tier 8 theming primitives render via the interpreter."
    exit 0
fi
exit 1
