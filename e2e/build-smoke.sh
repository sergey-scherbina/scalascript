#!/usr/bin/env bash
# `ssc build` smoke — copies one .ssc page (components-demo.ssc) + its
# imported components into a fresh src tree, runs `ssc build`, and
# verifies the resulting dist/index.html matches what `ssc render`
# produces for the same input.  Together these prove that the batch
# walker, the path-mapping (`/` → `index.html`), and the headless
# render path all agree on a real-world page.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"

SRC=$(mktemp -d)
DIST=$(mktemp -d)
trap 'rm -rf "$SRC" "$DIST"' EXIT

# Stage a minimal source tree: the page + its imported components.
cp "$ROOT/examples/components-demo.ssc" "$SRC/components-demo.ssc"
mkdir -p "$SRC/components"
cp "$ROOT/examples/components/"*.ssc "$SRC/components/"

echo "============================================================"
echo "  ssc build smoke — components-demo.ssc"
echo "============================================================"
echo
echo "  src:  $SRC"
echo "  dist: $DIST"
echo

build_out=$("$BIN/ssc" build "$SRC" "$DIST" 2>&1)
echo "$build_out" | sed 's/^/    /'

if [ ! -f "$DIST/index.html" ]; then
    echo
    echo "[FAIL] expected $DIST/index.html, not produced"
    exit 1
fi

"$BIN/ssc" render "$SRC/components-demo.ssc" > "$DIST/.expected.html" 2>/dev/null

if diff -q "$DIST/index.html" "$DIST/.expected.html" > /dev/null; then
    rm -f "$DIST/.expected.html"
    echo
    echo "[PASS] dist/index.html byte-matches 'ssc render'"
    echo "       ($(wc -c < "$DIST/index.html") bytes)"
    exit 0
fi
echo
echo "[FAIL] dist/index.html differs from 'ssc render' output"
diff "$DIST/index.html" "$DIST/.expected.html" | head -40
exit 1
