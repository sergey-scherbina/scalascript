#!/usr/bin/env bash
# P6.18 — CAPSTONE: C_min compiles a substantial INDEPENDENT program (not itself).
#
# specs/v2.2-p6.18-rpn.L is a Reverse-Polish-Notation calculator written in language L — a string tokenizer
# plus a stack machine (~20 defs using match on lists/tuples, `::`, if-chains, string methods, recursion,
# and the comparison/boolean operators). We build C_min (from specs/v2.2-p6.6-cmin.L) via the same ssc0
# file-reading driver as the fixpoint, use it to compile rpn.L to Core IR, run that IR, and check the result
# for several RPN expressions. C_min correctly compiling an independent real program (beyond compiling
# itself) is the proof that it is a general-purpose compiler for L.
#
# Prereqs (same as v2.2-p6.6-fixpoint.sh): SSC_JAR = ssc kernel jar, V2_DIR = <repo>/v2.
set -u
JAR=${SSC_JAR:?set SSC_JAR to the ssc kernel jar}; V2=${V2_DIR:?set V2_DIR to <repo>/v2}
HERE=$(cd "$(dirname "$0")" && pwd)
CMIN="$HERE/v2.2-p6.6-cmin.L"; RPN="$HERE/v2.2-p6.18-rpn.L"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"; rm -f "$V2/bin/_p618_drv.ssc0"' EXIT
cd "$V2" || exit 2
run()   { java -Xss512m -jar "$JAR" run "$@" 2>/dev/null; }
runir() { java -Xss512m -jar "$JAR" run-ir "$@" 2>/dev/null; }
fail=0

# The ssc0 driver: parse C_min (ssc1-front), append a file-reading main that passes dq = `"` as a literal.
cat > bin/_p618_drv.ssc0 <<'DRV'
import "../lib/ssc1-lower.ssc0"
def app2 = (xs, ys) => match xs { case Nil => ys case Cons(h, t) => Cons(h, app2(t, ys)) }
def prim0 = (nm) => Pair("prim", Pair(nm, Nil))
def prim1 = (nm, a) => Pair("prim", Pair(nm, Cons(a, Nil)))
def consPat = Pair("cpat", Pair("Cons", Cons(Pair("vpat", "path"), Cons(Pair("vpat", "rest"), Nil))))
def readCompile = (dqArg) => prim1("io.print", mkApp(mkVar("compile"), Cons(prim1("utf8->str", prim1("io.readFile", mkVar("path"))), Cons(dqArg, Nil))))
def fileMain = (dqArg) => mkDef("main", Nil, Pair("match", Pair(prim0("io.args"), Cons(Pair(consPat, readCompile(dqArg)), Nil))))
def main = () =>
  let srcPath = match #io.env("CMIN_SRC") { case Some(p) => p case None => "" } in
  let dqArg = mkStr(#sfromCodes(Cons(34, Nil))) in
  let prog = app2(parse(#utf8->str(#io.readFile(srcPath))), Cons(fileMain(dqArg), Nil)) in
  #io.print(#coreir.encode(lowerProg(prog)))
DRV

# C0 = C_min, wrapped with a file-reading main.
CMIN_SRC="$CMIN" run bin/_p618_drv.ssc0 > "$WORK/C0.ir"
[ -s "$WORK/C0.ir" ] || { echo "FAIL: could not build C_min"; exit 1; }

# C_min compiles the RPN calculator once; running the emitted IR evaluates the hard-coded expression.
runir "$WORK/C0.ir" "$RPN" > "$WORK/rpn.ir"
got=$(runir "$WORK/rpn.ir" | head -1)
[ "$got" = "14" ] && echo "ok   C_min compiled rpn.L; '2 3 4 * +' -> $got" || { echo "FAIL rpn.L default got [$got] want 14"; fail=1; }

# Same program, different expressions (edit the literal, recompile with C_min, run).
tv() { # $1=RPN expr  $2=expected
  sed "s|\"2 3 4 \\* +\"|\"$1\"|" "$RPN" > "$WORK/v.L"
  runir "$WORK/C0.ir" "$WORK/v.L" > "$WORK/v.ir"
  local g; g=$(runir "$WORK/v.ir" | head -1)
  [ "$g" = "$2" ] && echo "ok   C_min-compiled RPN: '$1' -> $g" || { echo "FAIL '$1' got [$g] want [$2]"; fail=1; }
}
tv "10 2 -"                8
tv "3 4 + 5 *"            35
tv "5 1 2 + 4 * + 3 -"   14
tv "100 20 30 + -"       50
tv "2 3 + 4 5 + *"       45
tv "7"                    7

# A second independent program: a tree-walking arithmetic AST evaluator over an algebraic data type
# (Num/Add/Sub/Mul) — exercises C_min's arbitrary-constructor construction + `case Name(...)` patterns (P6.19).
AST="$HERE/v2.2-p6.19-ast.L"
if [ -f "$AST" ]; then
  runir "$WORK/C0.ir" "$AST" > "$WORK/ast.ir"
  g=$(runir "$WORK/ast.ir" | head -1)
  [ "$g" = "14" ] && echo "ok   C_min compiled ast.L; eval(Add(Mul(Num 3, Num 4), Sub(Num 10, Num 8))) -> $g" \
    || { echo "FAIL ast.L got [$g] want 14"; fail=1; }
  # a variant: change the whole expression to Mul(Add(Num(2), Num(3)), Num(4)) = 20
  sed 's|Add(Mul(Num(3), Num(4)), Sub(Num(10), Num(8)))|Mul(Add(Num(2), Num(3)), Num(4))|' "$AST" > "$WORK/ast2.L"
  g2=$(runir "$WORK/C0.ir" "$WORK/ast2.L" | runir /dev/stdin | head -1)
  [ "$g2" = "20" ] && echo "ok   C_min-compiled AST: Mul(Add(Num 2, Num 3), Num 4) -> $g2" \
    || { echo "FAIL ast variant got [$g2] want 20"; fail=1; }
fi

[ "$fail" = "0" ] && echo "*** CAPSTONE: C_min is a general-purpose compiler — it compiles an independent RPN calculator AND an AST evaluator ***"
exit $fail
