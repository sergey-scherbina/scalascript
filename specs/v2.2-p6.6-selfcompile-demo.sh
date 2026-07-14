#!/usr/bin/env bash
# P6.6a — a self-host compiler READS ITS SOURCE FROM A FILE (no quine, no hard-coded source).
#
# The self-host compilers (selfhost-*) are pure `compile: String -> String` functions. This script wraps
# one in an ssc0 DRIVER — exactly the way v2/bin/ssc1-run.ssc0 does it — that reads a source file whose
# path is the first CLI arg (#io.args -> #io.readFile -> #utf8->str), calls `compile`, and prints the
# emitted Core IR. Then it compiles a sample object-language program FROM A FILE and runs the result.
#
# Prereqs: SSC_JAR (ssc kernel jar), V2_DIR (<worktree>/v2), SPIKE_OUT (dir with selfhost-str.proj, emitted
# by `cd uniml && SPIKE_OUT=<dir> sbt "uniml/testOnly scalascript.uniml.spike.ScalaSpikeSpec"`).
set -eu
JAR=${SSC_JAR:?}; V2=${V2_DIR:?}; OUT=${SPIKE_OUT:?}
PROJ=$(cat "$OUT/selfhost-str.proj")
cd "$V2"
drv="bin/_p66_selfcompile_driver.ssc0"

# The driver: drop the compiler's hard-coded `main`, append a file-reading `main`
#   match #io.args() { case Cons(path,_) => #io.print(compile(#utf8->str(#io.readFile(path)))) }
cat > "$drv" <<DRIVER
import "../lib/ssc1-lower.ssc0"
def dropLast = (xs) => match xs { case Cons(h, t) => (match t { case Nil => Nil case _ => Cons(h, dropLast(t)) }) case Nil => Nil }
def app2 = (xs, ys) => match xs { case Nil => ys case Cons(h, t) => Cons(h, app2(t, ys)) }
def prim0 = (nm) => Pair("prim", Pair(nm, Nil))
def prim1 = (nm, a) => Pair("prim", Pair(nm, Cons(a, Nil)))
def consPat = Pair("cpat", Pair("Cons", Cons(Pair("vpat", "path"), Cons(Pair("vpat", "rest"), Nil))))
def readCompile = prim1("io.print", mkApp(mkVar("compile"), Cons(prim1("utf8->str", prim1("io.readFile", mkVar("path"))), Nil)))
def fileMain = mkDef("main", Nil, Pair("match", Pair(prim0("io.args"), Cons(Pair(consPat, readCompile), Nil))))
def prog = app2(dropLast($PROJ), Cons(fileMain, Nil))
def main = () => #io.print(#coreir.encode(lowerProg(prog)))
DRIVER

# Stage 1: run the driver -> C0 = the compiler, as executable Core IR, with a file-reading entry.
java -Xss512m -jar "$JAR" run "$drv" 2>/dev/null > "$OUT/C0.ir"
rm -f "$drv"

# A sample object-language program, in a FILE.
printf 'def dbl(s) = s + s def ln(s) = s.length def main() = ln(dbl("ab"))' > "$OUT/prog.L"

# Stage 2: run C0 giving it the source FILE as an arg -> C0 reads it and emits Core IR for prog.L.
java -Xss512m -jar "$JAR" run-ir "$OUT/C0.ir" "$OUT/prog.L" 2>/dev/null > "$OUT/prog.ir"

# Stage 3: run the emitted Core IR -> the program's result.
got=$(java -Xss512m -jar "$JAR" run-ir "$OUT/prog.ir" 2>/dev/null | head -1)
if [ "$got" = "4" ]; then
  echo "ok  selfhost-str READ prog.L from a file, compiled it, and the result runs -> $got (ln(dbl(\"ab\")) = 4)"
  exit 0
else
  echo "FAIL got [$got] expected 4"; exit 1
fi
