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
# A CARGO CRATE, NOT A BARE `rustc`, and the date is the whole story. `4a7746ae8` (2026-08-16)
# made the Rust lane's BigInt arbitrary precision and moved `check.sh` beside this file to cargo,
# because the generated code now says `num_bigint::BigInt` and "a crate is the only way rustc takes
# a dependency". THIS gate was not moved with it, so `rustc -Awarnings gen.rs` started failing with
# `E0433: use of unresolved module or unlinked crate num_bigint` — and nothing noticed, because this
# gate is invoked by nothing. Measured green at 53 s a few hours BEFORE that commit and red after it.
# Same crate shape as `check.sh`: built once in `$TMP/crate`, so the dependency compiles once.
crate_init() {
  [ -f "$TMP/crate/Cargo.toml" ] && return 0
  mkdir -p "$TMP/crate/src"
  cat > "$TMP/crate/Cargo.toml" <<'TOML'
[package]
name = "gen"
version = "0.0.0"
edition = "2021"

[dependencies]
num-bigint = "0.4"

[profile.release]
overflow-checks = false

[[bin]]
name = "gen"
path = "src/main.rs"
TOML
}
run_rust() {
  crate_init
  cp "$1" "$TMP/crate/src/main.rs"
  (cd "$TMP/crate" && cargo build --release --quiet)
  "$TMP/crate/target/release/gen"
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
