#!/usr/bin/env bash
# Focused compatibility gate for FrontendBridge's private handler-decision
# markers. Source backends without typed handler dispatch must preserve the
# historical ordinary-partial-function behavior: selected is Unit and terminal
# miss is the backend's normal exhaustive-match failure.

set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
TMP="$(mktemp -d /tmp/v2-handler-markers-XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

scli() { command scala-cli "$@" --server=false; }

cat > "$TMP/selected.coreir" <<'EOF'
(program (defs) (entry (prim __handler_dispatch_selected__ (lit unit))))
EOF
cat > "$TMP/miss.coreir" <<'EOF'
(program (defs) (entry (prim __handler_dispatch_miss__ (lit unit))))
EOF

generate_jvm()  { scli run "$DIR/jvm"  -q < "$1" > "$2"; }
generate_js()   { scli run "$DIR/js"   -q < "$1" > "$2"; }
generate_rust() { scli run "$DIR/rust" -q < "$1" > "$2"; }

run_jvm()  { scli run "$1" -q; }
run_js()   { node "$1"; }
run_rust() {
  rustc -Awarnings "$1" -o "$TMP/marker-rust"
  "$TMP/marker-rust"
}

for backend in jvm js rust; do
  case "$backend" in
    jvm)  ext=scala ;;
    js)   ext=js ;;
    rust) ext=rs ;;
  esac

  selected="$TMP/selected.$ext"
  miss="$TMP/miss.$ext"
  "generate_$backend" "$TMP/selected.coreir" "$selected" 2> "$TMP/$backend-generate-selected.err"
  "generate_$backend" "$TMP/miss.coreir" "$miss" 2> "$TMP/$backend-generate-miss.err"

  "run_$backend" "$selected" > "$TMP/$backend-selected.out" 2> "$TMP/$backend-selected.err"
  test ! -s "$TMP/$backend-selected.out"

  if "run_$backend" "$miss" > "$TMP/$backend-miss.out" 2> "$TMP/$backend-miss.err"; then
    echo "FAIL $backend: terminal miss unexpectedly succeeded" >&2
    exit 1
  fi
  combined="$TMP/$backend-miss-combined.txt"
  cat "$TMP/$backend-miss.out" "$TMP/$backend-miss.err" > "$combined"
  grep -F "match: no matching case" "$combined" >/dev/null
  if grep -Eiq "unknown primitive|unsupported primitive" "$combined"; then
    echo "FAIL $backend: marker escaped as a public/unknown primitive" >&2
    cat "$combined" >&2
    exit 1
  fi
  echo "ok $backend handler marker fallback"
done

echo "ALL GREEN (handler markers: jvm js rust)"
