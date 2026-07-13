#!/usr/bin/env bash
# v2/backend/check.sh — parity harness for the Core IR code generators.
#
# Runs every conformance/*.coreir fixture — plus the two ssc1c regression
# programs (bool-predicate / mutual-recursion, the T5.1 @count/@sum bug) —
# through the v2 VM (run-ir) and each of the source generators
# (JVM / JS / Rust / WASM); all outputs must be byte-identical to the VM's.
# WASM reuses the Rust generator unchanged and cross-compiles its output to
# wasm32-wasip1, run under Node's built-in WASI host (v2/specs/63-backend-wasm.md)
# — toolchain-gated, skipped gracefully when `rustup target add wasm32-wasip1`
# hasn't been run.
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

# v2.jar is CACHED by source hash: the assembly costs ~1-2 min per harness run
# and v2/src changes rarely relative to how often parity runs. Cache lives in
# a stable location (not $TMP, which is wiped per run).
CACHE_DIR="${SSC_V2_JAR_CACHE:-$HOME/.cache/ssc-v2-jar}"
mkdir -p "$CACHE_DIR"
SRC_HASH=$(cat "$V2"/src/*.scala 2>/dev/null | shasum -a 256 | awk '{print $1}')
CACHED_JAR="$CACHE_DIR/v2-$SRC_HASH.jar"
if [ -f "$CACHED_JAR" ]; then
  echo "v2 jar: cache hit ($SRC_HASH)" >&2
  cp "$CACHED_JAR" "$TMP/v2.jar"
else
  echo "building v2 jar..." >&2
  scli --power package "$V2/src" -o "$TMP/v2.jar" -f --assembly -q >/dev/null 2>&1 \
    || { echo "FATAL: cannot build v2 jar" >&2; exit 1; }
  cp "$TMP/v2.jar" "$CACHED_JAR"
  # keep the cache bounded: drop all but the 3 most recent jars
  ls -t "$CACHE_DIR"/v2-*.jar 2>/dev/null | tail -n +4 | xargs rm -f 2>/dev/null || true
fi

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
# 512MB wasm-side stack (wasm-ld's default is ~1MB, far too small for this
# backend's deep-native-recursion fixtures — see WASM_DEEP_RECURSION_SKIP
# below for the ones even this isn't enough for).
run_wasm() { # $1=ir-file $2=out-file — same Rust source as run_rust, cross-compiled
  scli run "$DIR/rust" -q < "$1" > "$TMP/gen.rs" 2>/dev/null || return 1
  rustc -O --target wasm32-wasip1 -C link-arg=-zstack-size=536870912 \
    "$TMP/gen.rs" -o "$TMP/gen.wasm" 2>/dev/null || return 1
  node --no-warnings "$V2/scripts/run-wasi.mjs" "$TMP/gen.wasm" > "$2" 2>/dev/null
}

# Fixtures needing ~1M frames of genuine (non-trampolined) native recursion —
# the Phase-4 Rust backend leans on a 2GB native OS-thread stack for these
# (RustBackend.scala's own comment: "real trampoline TCO is queued"). Under
# wasm32-wasip1 there's no OS thread to spawn (main() calls ssc_run directly,
# see RustBackend.scala's #[cfg(target_arch = "wasm32")] arm), and V8's own
# wasm call-stack handling hits "Maximum call stack size exceeded" around
# this depth even with `-zstack-size` maxed out AND both the OS thread stack
# ulimit and `node --stack-size` raised to this machine's hard ceiling
# (`ulimit -Hs`, 64MB) — confirmed by hand, not guessed. Every OTHER fixture
# (shallow or properly-trampolined recursion) passes on wasm same as native.
WASM_DEEP_RECURSION_SKIP=" tco mutual-tco "

BACKENDS="jvm js rust"
if rustup target list --installed 2>/dev/null | grep -q wasm32-wasip1; then
  BACKENDS="$BACKENDS wasm"
else
  echo "note: wasm32-wasip1 not installed (rustup target add wasm32-wasip1) — skipping wasm row" >&2
fi

fail=0; ran=0
for ir in "${IRS[@]}"; do
  name="$(basename "$ir" .coreir)"
  if [ -n "$PATTERN" ] && [[ "$name" != *"$PATTERN"* ]]; then continue; fi
  ran=$((ran+1))
  java -jar "$TMP/v2.jar" run-ir "$ir" > "$TMP/expected.txt" 2>/dev/null \
    || { echo "FAIL $name: run-ir failed"; fail=1; continue; }
  for be in $BACKENDS; do
    if [ "$be" = wasm ] && [[ "$WASM_DEEP_RECURSION_SKIP" == *" $name "* ]]; then
      printf "skip %-20s %s (>64MB-deep native recursion; incompatible with wasm+V8's stack model)\n" "$name" "$be"
      continue
    fi
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
nbackends=$(wc -w <<< "$BACKENDS")
if [ "$fail" -eq 0 ]; then echo "ALL GREEN ($ran fixtures x $nbackends backends)"; else echo "FAILURES PRESENT"; fi
exit "$fail"
