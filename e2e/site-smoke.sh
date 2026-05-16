#!/usr/bin/env bash
# Multi-page site smoke — exercises `ssc build` on a realistic three-page
# site (index / about / contact) sharing one Layout component, and
# verifies each rendered page has the right title + active nav link.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"
DIST=$(mktemp -d)
trap 'rm -rf "$DIST"' EXIT

echo "============================================================"
echo "  Site build smoke — examples/site/"
echo "============================================================"
echo
echo "  dist: $DIST"
echo

build_out=$("$BIN/ssc" build "$ROOT/examples/site" "$DIST" 2>&1)
echo "$build_out" | sed 's/^/    /'

fail=0
check() {
    local label="$1"
    local file="$2"
    local title="$3"
    local current_href="$4"

    if [ ! -f "$file" ]; then
        echo "[FAIL] $label: $file not produced"
        fail=1
        return
    fi

    if ! grep -q "<title>$title" "$file"; then
        echo "[FAIL] $label: <title> doesn't contain '$title'"
        fail=1
    fi
    if ! grep -q '<header class="header__Layout">' "$file"; then
        echo "[FAIL] $label: missing scoped <header class=\"header__Layout\">"
        fail=1
    fi
    # The active link gets class="current__Layout"; the others get class="".
    local current_line
    current_line=$(grep -E "href=\"$current_href\".*class=\"current__Layout\"" "$file" || true)
    if [ -z "$current_line" ]; then
        echo "[FAIL] $label: $current_href is not marked current"
        fail=1
    fi
    # Every page must include all three nav links.
    for href in / /about /contact; do
        if ! grep -q "href=\"$href\"" "$file"; then
            echo "[FAIL] $label: missing nav link to $href"
            fail=1
        fi
    done

    if [ $fail -eq 0 ]; then
        echo "[PASS] $label  ($(wc -c < "$file") bytes)"
    fi
}

check "index"   "$DIST/index.html"   "Home — ScalaScript site"     "/"
check "about"   "$DIST/about.html"   "About — ScalaScript site"    "/about"
check "contact" "$DIST/contact.html" "Contact — ScalaScript site"  "/contact"

# Sanity: the three pages share the same byte-perfect Layout chrome,
# so the <header>...</header> block should be identical across all
# three files except for which link is marked current.
extract_header() {
    awk '/<header/{flag=1} flag{print} /<\/header>/{flag=0; exit}' "$1"
}
header_index=$(extract_header   "$DIST/index.html"   | sed 's/class="current__Layout"/class=""/g')
header_about=$(extract_header   "$DIST/about.html"   | sed 's/class="current__Layout"/class=""/g')
header_contact=$(extract_header "$DIST/contact.html" | sed 's/class="current__Layout"/class=""/g')

if [ "$header_index" != "$header_about" ] || [ "$header_index" != "$header_contact" ]; then
    echo "[FAIL] header drift across pages (after normalising 'current')"
    fail=1
fi

echo
if [ $fail -eq 0 ]; then
    echo "All three pages render through the shared Layout."
    exit 0
else
    echo "Site smoke FAILED."
    exit 1
fi
