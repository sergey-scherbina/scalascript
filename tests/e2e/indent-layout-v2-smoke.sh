#!/usr/bin/env bash
# v2-only regression for std/parsing/layout conformance demo files.
#
# The .ssc files also run through the opt-in conformance v2 lane; this shell
# smoke keeps a direct `bin/ssc run --v2` regression for the production path
# that used to crash on tuple accessors after parser operator-precedence
# grouped `~` under `<~`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/../bin/ssc"
CONF="$ROOT/conformance"

run_and_check() {
  local name="$1"
  local file="$2"
  shift 2

  local out
  out=$("$BIN" run --v2 "$file")
  printf '%s\n' "$out"

  local needle
  for needle in "$@"; do
    case "$out" in
      *"$needle"*) ;;
      *)
        echo "indent-layout-v2-smoke FAIL: $name missing '$needle'" >&2
        exit 1
        ;;
    esac
  done
}

run_and_check \
  "indent-config-format" \
  "$CONF/indent-config-format.ssc" \
  "test1: 1 section(s)" \
  "  [database] (3 entries)" \
  "test2: 2 section(s)" \
  "  [server] (2 entries)" \
  "  [database] (2 entries)"

run_and_check \
  "indent-block-statements" \
  "$CONF/indent-block-statements.ssc" \
  "test1: 3 statement(s)" \
  "x = 10" \
  "print x" \
  "test2: 3 statement(s)" \
  "if x == 0:" \
  "  print zero" \
  "print done" \
  "test3: 2 statement(s)" \
  "while ready:" \
  "for item in items:" \
  "  print item"

echo "indent-layout-v2-smoke PASS"
