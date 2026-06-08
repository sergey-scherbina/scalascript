#!/usr/bin/env bash
#
# tests/rust-build-smoke.sh — end-to-end smoke test for the rust target.
#
# For every fixture under examples/rust/*.ssc:
#   1. Build it via `ssc build-rust` (sbt-driven from this repo).
#   2. Run the produced binary.
#   3. Compare stdout against the expected line declared in the
#      .ssc-level `expected:` marker (or a default).
#
# Behaviour when cargo is missing:
#   The script prints a skip line and exits 0 — never fails CI on a
#   host without rust toolchain.  CI lanes that *do* have cargo should
#   gate on this script's exit code.
#
# Usage:
#   bash tests/rust-build-smoke.sh
#   bash tests/rust-build-smoke.sh --verbose
#
# Exit codes:
#   0  — all fixtures built and ran successfully, OR cargo not present.
#   1  — at least one fixture failed.

set -u

verbose=0
for arg in "$@"; do
  case "$arg" in
    -v|--verbose) verbose=1 ;;
    *) echo "rust-build-smoke: unknown flag '$arg'" >&2; exit 1 ;;
  esac
done

log()  { echo "rust-build-smoke: $*"; }
vlog() { [[ "$verbose" == "1" ]] && echo "rust-build-smoke: $*" || true; }

# Cargo presence — skip cleanly if absent.
if ! command -v cargo >/dev/null 2>&1; then
  log "cargo not on PATH — skipping (install via 'brew install rust' or https://www.rust-lang.org/tools/install)"
  exit 0
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixtures_dir="$repo_root/examples/rust"

if [[ ! -d "$fixtures_dir" ]]; then
  log "no examples/rust/ directory — nothing to smoke"
  exit 0
fi

shopt -s nullglob
fixtures=("$fixtures_dir"/*.ssc)
shopt -u nullglob

if (( ${#fixtures[@]} == 0 )); then
  log "no examples/rust/*.ssc fixtures — nothing to smoke"
  exit 0
fi

# Fixture → expected stdout (first line).  Keep in sync with the .ssc.
declare -A expected
expected["hello.ssc"]="Hello from Rust"
expected["mixed.ssc"]="Hello via rust block"
expected["fib.ssc"]="55"
expected["string-interp.ssc"]="Hello, Sergiy — age 42"
expected["while-fib.ssc"]="55"
expected["mutable-counter.ssc"]="5"
expected["shape-match.ssc"]="28.259999999999998"
expected["higher-order.ssc"]="42"
expected["for-yield.ssc"]="4"
expected["fs-roundtrip.ssc"]="true"
expected["crypto-sha256.ssc"]="2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
expected["base64-roundtrip.ssc"]="true"
expected["json-roundtrip.ssc"]="{\"x\":1,\"y\":[true,null,\"hi\"]}"
expected["effect-runtime.ssc"]="8"
expected["process-env.ssc"]="Hello, World"

workdir="$(mktemp -d -t ssc-rust-smoke-XXXXXX)"
trap 'rm -rf "$workdir"' EXIT

failed=0
for fixture in "${fixtures[@]}"; do
  name="$(basename "$fixture")"
  stem="${name%.ssc}"
  want="${expected[$name]:-}"
  if [[ -z "$want" ]]; then
    log "skip $name (no expected stdout registered in the smoke script)"
    continue
  fi

  log "build $name"
  bin="$workdir/$stem"
  log_file="$workdir/$stem.log"

  # sbt -batch keeps the cli/runMain dispatch quiet enough for CI.
  if ! (
    cd "$repo_root" && \
    sbt -batch -error "cli/runMain scalascript.cli.ssc build-rust $fixture -o $bin" \
        >"$log_file" 2>&1
  ); then
    log "FAIL: $name — build-rust exited non-zero"
    cat "$log_file" >&2
    failed=1
    continue
  fi

  if [[ ! -x "$bin" ]]; then
    log "FAIL: $name — expected binary at $bin not produced"
    cat "$log_file" >&2
    failed=1
    continue
  fi

  got="$("$bin" 2>&1 | head -1 || true)"
  if [[ "$got" != "$want" ]]; then
    log "FAIL: $name — stdout mismatch"
    log "  want: $want"
    log "  got:  $got"
    failed=1
    continue
  fi

  vlog "ok   $name → '$got'"
  log "PASS $name"
done

if (( failed != 0 )); then
  log "one or more rust smoke fixtures failed"
  exit 1
fi

log "all rust smoke fixtures green (${#fixtures[@]} fixture(s))"
exit 0
