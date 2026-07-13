#!/usr/bin/env bash
# v2-compile smoke for uniml-portable. Runs each probe through the v2 self-hosted
# .ssc pipeline and classifies PASS/FAIL. Currently RED (gap-array, gap-anon);
# goes GREEN as v2 closes the gaps (see specs/uniml-portable-gapmap.md).
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
fail=0
for f in "$DIR"/*.ssc; do
  name="$(basename "$f")"
  out="$(timeout 240 "$ROOT/v2/ssc1" "$f" 2>&1)"
  if printf '%s' "$out" | grep -qE 'unbound global|Exception in thread|IndexOutOfBounds'; then
    echo "FAIL  $name"
    printf '%s\n' "$out" | grep -E 'unbound global|Exception|IndexOutOfBounds|-- \[E[0-9]' | grep -v 'Runtime.scala' | head -1 | sed 's/^/      /'
    fail=1
  else
    echo "PASS  $name"
  fi
done
[ "$fail" = 0 ] && echo "== v2-smoke GREEN ==" || echo "== v2-smoke RED (expected until Phase 3) =="
exit "$fail"
