#!/usr/bin/env bash
# Run every Core IR conformance fixture against the K1 kernel and check results.
# bash 3.2 compatible (no associative arrays). Run from anywhere.
set -u
cd "$(dirname "$0")/.." || exit 2   # -> ssc2/

fail=0
check() {
  f=$1; want=$2
  got=$(scala-cli run kernel --server=false --quiet -- run "conformance/$f.coreir" 2>/dev/null | tail -1)
  if [ "$got" = "$want" ]; then
    printf 'ok   %-7s => %s\n' "$f" "$got"
  else
    printf 'FAIL %-7s => got [%s] want [%s]\n' "$f" "$got" "$want"; fail=1
  fi
}

check thunk  "42"
check fact   "120"
check map    "Cons(2, Cons(4, Cons(6, Nil)))"
check letrec "true"
check tco    "500000500000"

exit $fail
