#!/usr/bin/env bash
# P6.0/P6.1/P6.2 end-to-end verifier. Prereqs:
#   1) emit projections/toys from the uniml spec:
#      cd uniml && SPIKE_OUT=<dir> sbt "uniml/testOnly scalascript.uniml.spike.ScalaSpikeSpec"
#   2) SSC_JAR = ssc kernel jar, V2_DIR = <worktree>/v2, SPIKE_OUT = emission dir, SSC_STD set.
# Each toy runs UniML-projection → ssc1-lower → run-ir. Well-formed toys (with a <name>.toy.ssc)
# require the Core IR to be BYTE-IDENTICAL to what ssc1-front produces (the differential oracle) —
# this proves the parse (precedence, associativity, operator lexing) matches exactly. Broken toys
# (no .toy.ssc, a <name>.expect instead) prove error CONTAINMENT: the partial IR compiles and
# `main` still returns its value despite a sibling hole.
set -u
JAR=${SSC_JAR:?}; V2=${V2_DIR:?}; OUT=${SPIKE_OUT:?}
cd "$V2" || exit 2
fail=0
for proj in "$OUT"/*.proj; do
  name=$(basename "$proj" .proj)
  drv="bin/_spike_$name.ssc0"
  printf 'import "../lib/ssc1-lower.ssc0"\ndef main = () => #io.print(#coreir.encode(lowerProg(%s)))\n' "$(cat "$proj")" > "$drv"
  myir=$(java -Xss512m -jar "$JAR" run "$drv" 2>/dev/null)
  mine=$(printf '%s' "$myir" | java -Xss512m -jar "$JAR" run-ir /dev/stdin 2>/dev/null)
  rm -f "$drv"
  if [ -f "$OUT/$name.toy.ssc" ]; then
    refir=$(java -Xss512m -jar "$JAR" run bin/ssc1-run.ssc0 "$OUT/$name.toy.ssc" 2>/dev/null)
    if [ "$myir" = "$refir" ] && [ -n "$myir" ]; then echo "ok   $name → run-ir=$mine  CoreIR≡ssc1-front"
    else echo "FAIL $name  CoreIR≠ssc1-front (run-ir mine=$mine)"; fail=1; fi
  else
    expect=$(cat "$OUT/$name.expect")
    if [ "$mine" = "$expect" ]; then echo "ok   $name → run-ir=$mine (containment, expect $expect)"
    else echo "FAIL $name mine=[$mine] expect=[$expect]"; fail=1; fi
  fi
done
exit $fail
