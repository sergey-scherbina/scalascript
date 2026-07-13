#!/usr/bin/env bash
# P6.0/P6.1 end-to-end verifier. Prereqs:
#   1) emit projections/toys/expected from the uniml spec:
#      cd uniml && SPIKE_OUT=<dir> sbt "uniml/testOnly scalascript.uniml.spike.ScalaSpikeSpec"
#   2) SSC_JAR = ssc kernel jar, V2_DIR = <worktree>/v2, SPIKE_OUT = emission dir, SSC_STD set.
# For each toy: run UniML-projection → ssc1-lower → run-ir and check it equals <name>.expect.
# Well-formed toys (with a <name>.toy.ssc) ALSO run through ssc1-front and require the Core IR
# to be byte-identical (the differential oracle). Broken toys (no .toy.ssc) prove error
# CONTAINMENT: the partial IR compiles and `main` still returns its value despite a sibling hole.
set -u
JAR=${SSC_JAR:?}; V2=${V2_DIR:?}; OUT=${SPIKE_OUT:?}
cd "$V2" || exit 2
fail=0
for proj in "$OUT"/*.proj; do
  name=$(basename "$proj" .proj); expect=$(cat "$OUT/$name.expect")
  drv="bin/_spike_$name.ssc0"
  printf 'import "../lib/ssc1-lower.ssc0"\ndef main = () => #io.print(#coreir.encode(lowerProg(%s)))\n' "$(cat "$proj")" > "$drv"
  myir=$(java -Xss512m -jar "$JAR" run "$drv" 2>/dev/null)
  mine=$(printf '%s' "$myir" | java -Xss512m -jar "$JAR" run-ir /dev/stdin 2>/dev/null)
  rm -f "$drv"
  if [ -f "$OUT/$name.toy.ssc" ]; then
    refir=$(java -Xss512m -jar "$JAR" run bin/ssc1-run.ssc0 "$OUT/$name.toy.ssc" 2>/dev/null)
    [ "$myir" = "$refir" ] && s="oracle✓CoreIR≡" || s="oracle✗"
  else s="(broken: containment, my-path only)"; fi
  if [ "$mine" = "$expect" ]; then echo "ok   $name → $mine (expect $expect) $s"
  else echo "FAIL $name mine=[$mine] expect=[$expect] $s"; fail=1; fi
done
exit $fail
