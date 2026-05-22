#!/usr/bin/env bash
# `ssc bundle` smoke — packs a `.ssc` page + its transitive imports into
# a `.sscpkg` zip, unpacks it elsewhere, renders the entry from the
# unpacked tree, and verifies the rendered HTML matches the original
# source byte-for-byte.  Also exercises the external-imports flatten +
# rewrite path (when an entry imports from above its own directory).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

echo "============================================================"
echo "  ssc bundle smoke"
echo "============================================================"
echo

fail=0

# ─── Case 1: in-tree imports — components-demo.ssc ───────────────
echo "Case 1: in-tree imports (examples/components-demo.ssc)"
PKG1="$WORK/components-demo.sscpkg"
"$BIN/ssc" bundle "$ROOT/examples/components-demo.ssc" -o "$PKG1" 2>&1 | sed 's/^/    /'

UNPACK1="$WORK/unpack1"; mkdir -p "$UNPACK1"
unzip -q "$PKG1" -d "$UNPACK1"

if [ ! -f "$UNPACK1/bundle.yaml" ]; then
    echo "  [FAIL] bundle.yaml missing"; fail=1
fi
for required in components-demo.ssc components/page.ssc components/button.ssc components/card.ssc components/alert.ssc components/counter.ssc; do
    if [ ! -f "$UNPACK1/$required" ]; then
        echo "  [FAIL] missing in archive: $required"; fail=1
    fi
done

"$BIN/ssc" render "$UNPACK1/components-demo.ssc" > "$WORK/from-bundle.html"
"$BIN/ssc" render "$ROOT/examples/components-demo.ssc" > "$WORK/from-source.html"
if diff -q "$WORK/from-bundle.html" "$WORK/from-source.html" > /dev/null; then
    echo "  [PASS] roundtrip render matches source ($(wc -c < "$WORK/from-bundle.html") bytes)"
else
    echo "  [FAIL] roundtrip render differs from source"; fail=1
fi

# ─── Case 2: multi-entry — three components, no aggregator ───────
echo
echo "Case 2: multi-entry (card.ssc + button.ssc + alert.ssc)"
PKG2="$WORK/cards.sscpkg"
"$BIN/ssc" bundle \
    "$ROOT/examples/components/card.ssc" \
    "$ROOT/examples/components/button.ssc" \
    "$ROOT/examples/components/alert.ssc" \
    -o "$PKG2" 2>&1 | sed 's/^/    /'

UNPACK2="$WORK/unpack2"; mkdir -p "$UNPACK2"
unzip -q "$PKG2" -d "$UNPACK2"
for required in card.ssc button.ssc alert.ssc bundle.yaml; do
    if [ ! -f "$UNPACK2/$required" ]; then
        echo "  [FAIL] missing: $required"; fail=1
    fi
done
if ! grep -q "card.ssc" "$UNPACK2/bundle.yaml"; then
    echo "  [FAIL] bundle.yaml doesn't list card.ssc as entry"; fail=1
fi
[ $fail -eq 0 ] && echo "  [PASS] three entries packed without aggregator module"

# ─── Case 3: external imports — entry in subdir, import from above
echo
echo "Case 3: external import flatten + rewrite"
SRC3="$WORK/src3"
mkdir -p "$SRC3/shared" "$SRC3/pages"
cat > "$SRC3/shared/layout.ssc" <<'EOF'
---
name: shared-layout
---
# Layout
```scalascript
object Layout:
  def render(title: String, body: String): String =
    html"""<!doctype html><html><head><title>${title}</title></head><body>${raw(body)}</body></html>"""
```
EOF
cat > "$SRC3/pages/index.ssc" <<'EOF'
---
name: index
routes: [{method: GET, path: /, handler: home}]
---
# Index

[Layout](../shared/layout.ssc)

```scalascript
def home(req: Request): Response = Response.html(Layout.render("Home", "<p>hello</p>"))
println("listen")
serve(9999)
```
EOF

PKG3="$WORK/index.sscpkg"
"$BIN/ssc" bundle "$SRC3/pages/index.ssc" -o "$PKG3" 2>&1 | sed 's/^/    /'

UNPACK3="$WORK/unpack3"; mkdir -p "$UNPACK3"
unzip -q "$PKG3" -d "$UNPACK3"

if [ ! -f "$UNPACK3/_external/layout.ssc" ]; then
    echo "  [FAIL] external file not flattened to _external/"; fail=1
fi
if ! grep -q '\[Layout\](_external/layout.ssc)' "$UNPACK3/index.ssc"; then
    echo "  [FAIL] import path inside index.ssc not rewritten to _external/"; fail=1
fi

RENDERED=$("$BIN/ssc" render "$UNPACK3/index.ssc")
EXPECTED='<!doctype html><html><head><title>Home</title></head><body><p>hello</p></body></html>'
if [ "$RENDERED" = "$EXPECTED" ]; then
    echo "  [PASS] external-import bundle renders end-to-end"
else
    echo "  [FAIL] rendered output mismatch"
    echo "         got:      $RENDERED"
    echo "         expected: $EXPECTED"
    fail=1
fi

echo
if [ $fail -eq 0 ]; then
    echo "All ssc bundle cases pass."
    exit 0
fi
exit 1
