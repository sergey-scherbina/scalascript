#!/usr/bin/env bash
# Recursive `ssc build` smoke — proves that nested page hierarchies
# (pages/, pages/blog/) render with each `.ssc` mapped to a file
# matching its `routes:` path, while non-page subdirectories
# (`components/`, dotfile dirs, `target/`, etc.) are NOT walked for
# page emission but still recursed for the asset pipeline.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"
SRC=$(mktemp -d)
DIST=$(mktemp -d)
trap 'rm -rf "$SRC" "$DIST"' EXIT

mkdir -p "$SRC/components" "$SRC/blog" "$SRC/.cache" "$SRC/target"

# Shared layout component (must be in components/ to be skipped from
# page walking; still imported by the pages).
cat > "$SRC/components/layout.ssc" <<'EOF'
---
name: layout
---

# Layout

```scalascript
object Layout:
  def render(title: String, body: String): String =
    html"""<!doctype html><html><head><title>${title}</title></head><body>${raw(body)}</body></html>"""
```
EOF

# Top-level home page
cat > "$SRC/index.ssc" <<'EOF'
---
name: home
routes: [{method: GET, path: /, handler: home}]
---

# Home

[Layout](./components/layout.ssc)

```scalascript
def home(req: Request): Response =
  Response.html(Layout.render("Home", "<h1>Home</h1>"))
println("listening")
serve(9999)
```
EOF

# Nested blog pages, importing layout one level up
cat > "$SRC/blog/post1.ssc" <<'EOF'
---
name: post1
routes: [{method: GET, path: /blog/post1, handler: post1}]
---

# Post 1

[Layout](../components/layout.ssc)

```scalascript
def post1(req: Request): Response =
  Response.html(Layout.render("Post 1", "<h1>First post</h1>"))
println("listening")
serve(9999)
```
EOF

cat > "$SRC/blog/post2.ssc" <<'EOF'
---
name: post2
routes: [{method: GET, path: /blog/post2, handler: post2}]
---

# Post 2

[Layout](../components/layout.ssc)

```scalascript
def post2(req: Request): Response =
  Response.html(Layout.render("Post 2", "<h1>Second post</h1>"))
println("listening")
serve(9999)
```
EOF

# Stuff that MUST be skipped from page walking:
#   .cache/    dotfile dir
#   target/    tooling output
cat > "$SRC/.cache/skipme.ssc" <<'EOF'
---
routes: [{method: GET, path: /should-not-appear, handler: skip}]
---
# Skip
```scalascript
def skip(req: Request): Response = Response.text("BUG")
println("listening")
serve(9999)
```
EOF
cat > "$SRC/target/also-skip.ssc" <<'EOF'
---
routes: [{method: GET, path: /also-not, handler: skip}]
---
# Skip
```scalascript
def skip(req: Request): Response = Response.text("BUG")
println("listening")
serve(9999)
```
EOF

# An asset that should be mirrored
echo "/* asset */" > "$SRC/extra.css"

echo "============================================================"
echo "  Recursive ssc build smoke"
echo "============================================================"
echo
"$BIN/ssc" build "$SRC" "$DIST" 2>&1 | sed 's/^/    /'

fail=0
for want in index.html blog/post1.html blog/post2.html extra.css; do
    if [ ! -f "$DIST/$want" ]; then
        echo "[FAIL] expected $want in dist, missing"
        fail=1
    fi
done
for unwanted in should-not-appear.html also-not.html; do
    if [ -f "$DIST/$unwanted" ]; then
        echo "[FAIL] $unwanted should NOT have been emitted (came from skipped dir)"
        fail=1
    fi
done
# components/ contents should NOT be rendered as a page, but the .ssc
# file is NOT in the asset pipeline either (asset walker skips .ssc).
if [ -f "$DIST/components/layout.html" ]; then
    echo "[FAIL] components/layout.html got rendered — components/ should be skipped"
    fail=1
fi
if [ -f "$DIST/components/layout.ssc" ]; then
    echo "[FAIL] components/layout.ssc copied as an asset — .ssc files should never be asset-mirrored"
    fail=1
fi

echo
if [ $fail -eq 0 ]; then
    echo "[PASS] recursive build — nested pages rendered, skip dirs respected"
    exit 0
fi
exit 1
