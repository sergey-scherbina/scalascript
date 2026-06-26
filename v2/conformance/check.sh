#!/usr/bin/env bash
# Conformance for the ssc v2 runtime compiler (ssc0 -> ir -> ssc -> cpu).
# Builds one assembly jar, then exercises all three pipeline modes.
# bash 3.2 compatible (no associative arrays).
set -u
cd "$(dirname "$0")/.." || exit 2   # -> v2/

JAR="${TMPDIR:-/tmp}/ssc-conformance.jar"
echo "building ssc ..." >&2
scala-cli --power package src -o "$JAR" -f --assembly --server=false -q >/dev/null 2>&1 \
  || { echo "build failed"; exit 2; }
ssc() { java -jar "$JAR" "$@" 2>/dev/null; }

fail=0
chk() { # mode file want
  got=$(ssc "$1" "$2" | tail -1)
  if [ "$got" = "$3" ]; then printf 'ok   %-26s => %s\n' "$1 ${2##*/}" "$got"
  else printf 'FAIL %-26s got [%s] want [%s]\n' "$1 ${2##*/}" "$got" "$3"; fail=1; fi
}

echo "# ssc0 source -> ir -> run"
chk run examples/fact.ssc0 "120"
chk run examples/map.ssc0  "Cons(2, Cons(4, Cons(6, Nil)))"
chk run examples/tco.ssc0  "500000500000"

echo "# widened primitives (strings, BigInt, maps, Option)"
chk run examples/bigfact.ssc0 "265252859812191058636308480000000"
chk run examples/mapdemo.ssc0 "1"

echo "# multi-file import"
chk run examples/uselib.ssc0 "4950"

echo "# ir bytecode -> run"
chk run-ir conformance/thunk.coreir  "42"
chk run-ir conformance/fact.coreir   "120"
chk run-ir conformance/map.coreir    "Cons(2, Cons(4, Cons(6, Nil)))"
chk run-ir conformance/letrec.coreir "true"
chk run-ir conformance/tco.coreir    "500000500000"

echo "# argv: ssc run <file> ARGS... -> #io.args()"
chkargv() { # want -- file args...
  want=$1; shift 2
  file=$1
  got=$(ssc run "$@" | tail -1)
  if [ "$got" = "$want" ]; then printf 'ok   %-26s => %s\n' "run ${file##*/} [${*:2}]" "$got"
  else printf 'FAIL %-26s got [%s] want [%s]\n' "run ${file##*/} [${*:2}]" "$got" "$want"; fail=1; fi
}
chkargv '"hello"'           -- examples/args.ssc0 hello world
chkargv '"(no args)"'       -- examples/args.ssc0
chkargv '"Hello, Sergiy!"'  -- examples/greet.ssc0 Sergiy

echo "# ssc0 -> ir reproduces the hand-written map def (15-ssc0 acceptance)"
mapdef='(def map (lam 2 (match (local 0) ((arm Nil 0 (ctor Nil)) (arm Cons 2 (ctor Cons (app (local 3) (local 1)) (app (global map) (local 3) (local 0))))))))'
if ssc compile examples/map.ssc0 | grep -qF "$mapdef"; then
  printf 'ok   %-26s => matches conformance/map.coreir\n' "compile map.ssc0"
else
  printf 'FAIL %-26s map def mismatch\n' "compile map.ssc0"; fail=1
fi

exit $fail
