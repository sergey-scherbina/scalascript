#!/usr/bin/env bash
# P6.0 end-to-end verifier. Prereqs:
#   1) run the uniml spec to emit projections:
#      cd uniml && SPIKE_OUT=<dir> sbt "uniml/testOnly scalascript.uniml.spike.ScalaSpikeSpec"
#   2) SSC_JAR = ssc kernel jar, V2_DIR = <worktree>/v2, SPIKE_OUT = emission dir, SSC_STD set.
# For each toy compares  ssc1-front→run-ir  vs  UniML-projection→ssc1-lower→run-ir
# (both the executed value and the byte-exact Core IR).
set -u
JAR=${SSC_JAR:?}; V2=${V2_DIR:?}; OUT=${SPIKE_OUT:?}
cd "$V2" || exit 2
fail=0
for proj in "$OUT"/*.proj; do
  name=$(basename "$proj" .proj); toy="$OUT/$name.toy.ssc"
  refir=$(java -Xss512m -jar "$JAR" run bin/ssc1-run.ssc0 "$toy" 2>/dev/null)
  ref=$(printf '%s' "$refir" | java -Xss512m -jar "$JAR" run-ir /dev/stdin 2>/dev/null)
  drv="bin/_spike_$name.ssc0"
  printf 'import "../lib/ssc1-lower.ssc0"\ndef main = () => #io.print(#coreir.encode(lowerProg(%s)))\n' "$(cat "$proj")" > "$drv"
  myir=$(java -Xss512m -jar "$JAR" run "$drv" 2>/dev/null)
  mine=$(printf '%s' "$myir" | java -Xss512m -jar "$JAR" run-ir /dev/stdin 2>/dev/null)
  rm -f "$drv"
  [ "$myir" = "$refir" ] && s="CoreIR≡" || s="CoreIR≠"
  if [ "$ref" = "$mine" ] && [ -n "$mine" ]; then echo "ok   $name → $mine ($s)"; else echo "FAIL $name mine=[$mine] ref=[$ref] $s"; fail=1; fi
done
exit $fail
