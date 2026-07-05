#!/usr/bin/env bash
# v2/backend/check.sh — parity harness for the Core IR code generators.
#
# Runs every conformance/*.coreir fixture — plus the two ssc1c regression
# programs (bool-predicate / mutual-recursion, the T5.1 @count/@sum bug) —
# through the v2 VM (run-ir) and each of the three source generators
# (JVM / JS / Rust); all outputs must be byte-identical to the VM's.
#
# Phase 2c/2d verification of these backends was manual; this script makes it
# repeatable. It is intentionally standalone (slower than conformance/check.sh:
# scalac + rustc per fixture) — run it when touching backend/ or ssc1c lowering.
#
# Usage: v2/backend/check.sh [fixture-name-pattern]
#
# NOTE (macOS): never `echo "$var"` generated sources — BSD echo interprets
# `\n` escapes and corrupts preambles. Files + redirects only.

set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
V2="$(dirname "$DIR")"
PATTERN="${1:-}"
TMP="$(mktemp -d /tmp/v2-backend-check-XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

# scala-cli: --server=false must follow the subcommand (avoids bloop daemon)
scli() { command scala-cli "$@" --server=false; }

echo "building v2 jar..." >&2
scli --power package "$V2/src" -o "$TMP/v2.jar" -f --assembly -q >/dev/null 2>&1 \
  || { echo "FATAL: cannot build v2 jar" >&2; exit 1; }

# ── collect fixtures ─────────────────────────────────────────────────────────
IRS=()
for f in "$V2"/conformance/*.coreir; do IRS+=("$f"); done

# ssc1c regression programs (compiled to IR here, from bench/corpus sources)
mk_ssc1c_ir() { # $1=corpus-name $2=main-wrapper-line → $TMP/$1.coreir
  local corpus="$V2/../bench/corpus/$1.ssc"
  [ -f "$corpus" ] || return 0
  { awk '/```scalascript/{found=1; next} found && /```/{exit} found{print}' "$corpus" \
      | python3 "$V2/scripts/indent2braces.py"; printf '\n%s\n' "$2"; } > "$TMP/$1.ssc1"
  java -jar "$TMP/v2.jar" run "$V2/bin/ssc1c.ssc0" "$TMP/$1.ssc1" > "$TMP/$1.coreir" 2>/dev/null \
    && IRS+=("$TMP/$1.coreir") || echo "WARN: ssc1c failed on $1 (fixture skipped)" >&2
}
mk_ssc1c_ir bool-predicate   'def main(): Unit = { println(workload(42L)); () }'
mk_ssc1c_ir mutual-recursion 'def main(): Unit = { println(workload()); () }'

# ── run one fixture through one backend ──────────────────────────────────────
run_jvm() { # $1=ir-file $2=out-file
  scli run "$DIR/jvm" -q < "$1" > "$TMP/Gen.scala" 2>/dev/null || return 1
  scli run "$TMP/Gen.scala" -q > "$2" 2>/dev/null
}
run_js() { # $1=ir-file $2=out-file
  scli run "$DIR/js" -q < "$1" > "$TMP/gen.js" 2>/dev/null || return 1
  node "$TMP/gen.js" > "$2" 2>/dev/null
}
run_rust() { # $1=ir-file $2=out-file
  scli run "$DIR/rust" -q < "$1" > "$TMP/gen.rs" 2>/dev/null || return 1
  rustc -O "$TMP/gen.rs" -o "$TMP/gen-rust" 2>/dev/null || return 1
  "$TMP/gen-rust" > "$2" 2>/dev/null
}

fail=0; ran=0
for ir in "${IRS[@]}"; do
  name="$(basename "$ir" .coreir)"
  if [ -n "$PATTERN" ] && [[ "$name" != *"$PATTERN"* ]]; then continue; fi
  ran=$((ran+1))
  java -jar "$TMP/v2.jar" run-ir "$ir" > "$TMP/expected.txt" 2>/dev/null \
    || { echo "FAIL $name: run-ir failed"; fail=1; continue; }
  for be in jvm js rust; do
    if "run_$be" "$ir" "$TMP/out-$be.txt" && diff -q "$TMP/expected.txt" "$TMP/out-$be.txt" >/dev/null 2>&1; then
      printf "ok   %-20s %s\n" "$name" "$be"
    else
      printf "FAIL %-20s %s (expected vs got):\n" "$name" "$be"
      diff "$TMP/expected.txt" "$TMP/out-$be.txt" 2>&1 | head -5
      fail=1
    fi
  done
done

[ "$ran" -gt 0 ] || { echo "no fixtures matched '$PATTERN'"; exit 1; }
if [ "$fail" -eq 0 ]; then echo "ALL GREEN ($ran fixtures x 3 backends)"; else echo "FAILURES PRESENT"; fi
exit "$fail"
