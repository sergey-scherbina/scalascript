#!/usr/bin/env bash
# P6.6 — the literal self-compilation FIXPOINT (no quine; the compiler reads its source FROM A FILE).
#
#   C_min  = specs/v2.2-p6.6-cmin.L — a compiler `compile(src, dq): String` for language L, WRITTEN IN L,
#            whose whole source is inside L, so it can compile itself. Escape-free: the `"` char is a
#            `dq` PARAMETER (the driver passes it as a string literal), so C_min's source has no
#            `"`-inside-a-string and its own scanStr just compares char code 34.
#
# The bootstrap + fixpoint (Futamura / classic compiler self-host):
#   C0     = driver(ssc1-front(C_min))     the reference front lowers C_min, we wrap it with a
#                                          file-reading `main` (match #io.args() { Cons(path,_) =>
#                                          print(compile(utf8->str(readFile(path)), dq)) }).
#   stage1 = C0(C_min)                     C_min, compiled by the reference, compiles its OWN source.
#   C1     = stage1 + the same file-main   stage1 (C_min-compiled-C_min) made runnable.
#   stage2 = C1(C_min)                     the self-produced compiler compiles C_min's source again.
#   ASSERT   stage1 == stage2              byte-identical → C_min is a fixpoint of its own compilation.
#
# Prereqs (same convention as v2.2-p6.0-spike-verify.sh): SSC_JAR = ssc kernel jar, V2_DIR = <repo>/v2.
set -u
JAR=${SSC_JAR:?set SSC_JAR to the ssc kernel jar}; V2=${V2_DIR:?set V2_DIR to <repo>/v2}
HERE=$(cd "$(dirname "$0")" && pwd)
CMIN="$HERE/v2.2-p6.6-cmin.L"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"; rm -f "$V2/bin/_p66_fix_drv.ssc0"' EXIT
cd "$V2" || exit 2
run()    { java -Xss512m -jar "$JAR" run "$@" 2>/dev/null; }
runir()  { java -Xss512m -jar "$JAR" run-ir "$@" 2>/dev/null; }
fail=0

# The ssc0 driver: parse C_min (ssc1-front), append a file-reading main that passes dq = `"` as a
# STRING LITERAL (built in the driver via #sfromCodes(Cons(34,Nil)); #coreir.encode escapes it to \" and
# run-ir reads it back as one quote). No `"`-inside-a-string ever appears in C_min's own source.
cat > bin/_p66_fix_drv.ssc0 <<'DRV'
import "../lib/ssc1-lower.ssc0"
def app2 = (xs, ys) => match xs { case Nil => ys case Cons(h, t) => Cons(h, app2(t, ys)) }
def prim0 = (nm) => Pair("prim", Pair(nm, Nil))
def prim1 = (nm, a) => Pair("prim", Pair(nm, Cons(a, Nil)))
def consPat = Pair("cpat", Pair("Cons", Cons(Pair("vpat", "path"), Cons(Pair("vpat", "rest"), Nil))))
def readCompile = (dqArg) => prim1("io.print", mkApp(mkVar("compile"), Cons(prim1("utf8->str", prim1("io.readFile", mkVar("path"))), Cons(dqArg, Nil))))
def fileMain = (dqArg) => mkDef("main", Nil, Pair("match", Pair(prim0("io.args"), Cons(Pair(consPat, readCompile(dqArg)), Nil))))
def main = () =>
  let srcPath = match #io.env("CMIN_SRC") { case Some(p) => p case None => "" } in
  let cminSrc = #utf8->str(#io.readFile(srcPath)) in
  let dqArg = mkStr(#sfromCodes(Cons(34, Nil))) in
  let prog = app2(parse(cminSrc), Cons(fileMain(dqArg), Nil)) in
  #io.print(#coreir.encode(lowerProg(prog)))
DRV

# C0 = the reference front's C_min, wrapped with a file-reading main.
CMIN_SRC="$CMIN" run bin/_p66_fix_drv.ssc0 > "$WORK/C0.ir"
[ -s "$WORK/C0.ir" ] || { echo "FAIL: could not bootstrap C0 from $CMIN"; CMIN_SRC="$CMIN" java -jar "$JAR" run bin/_p66_fix_drv.ssc0 2>&1 | head -4; exit 1; }

# Conformance: C0 must compile a spread of L programs to Core IR that runs to the right value.
t() { printf '%s' "$3" > "$WORK/p.L"
  runir "$WORK/C0.ir" "$WORK/p.L" > "$WORK/p.ir"
  local got; got=$(runir "$WORK/p.ir" | head -1)
  if [ "$got" = "$2" ]; then echo "ok   L:$1 -> $got"
  else echo "FAIL L:$1 got [$got] want [$2]"; fail=1; fi; }
t arith     14      'def main() = 2 + 3 * 4'
t call      7       'def add(a, b) = a + b def main() = add(3, 4)'
t if        1       'def main() = if 2 < 3 then 1 else 0'
t rec       120     'def fac(n) = if n < 1 then 1 else n * fac(n - 1) def main() = fac(5)'
t bool      1       'def not(b) = if b then 0 else 1 def main() = not(false)'
t strlen    5       'def main() = "hello".length'
t concat    '"abcd"' 'def main() = "ab" ++ "cd"'
t charAt    98      'def main() = "abc".charAt(1)'
t substr    '"bc"'  'def main() = "abcd".substring(1, 3)'
t streq     1       'def main() = if "def" == "def" then 1 else 0'
t matchlist 3       'def len(xs) = xs match { case Nil => 0 case Cons(h, t) => 1 + len(t) } def main() = len(Cons(7, Cons(8, Cons(9, Nil))))'
t matchtup  30      'def add(p) = p match { case (a, b) => a + b } def main() = add((10, 20))'
t valblock  40      'def f(a, b) = { val x = a + b  val y = x + a  y } def main() = f(10, 20)'
t valblockstr '"ab!"' 'def g(s) = { val t = s ++ "!"  t } def main() = g("ab")'
t cmp       1       'def main() = if 5 > 3 then (if 3 >= 3 then (if 2 <= 2 then 1 else 0) else 0) else 0'
t andor     1       'def inR(c) = c >= 97 && c <= 122 def main() = if inR(100) || false then 1 else 0'
t wildcard  99      'def f(xs) = xs match { case Cons(h, t) => h case _ => 99 } def main() = f(Nil)'
t wildonly  5       'def f(x) = x match { case _ => 5 } def main() = f(3)'
t litpat    20      'def f(n) = n match { case 0 => 10 case 1 => 20 case _ => 99 } def main() = f(1)'
t litdef    99      'def f(n) = n match { case 0 => 10 case 1 => 20 case _ => 99 } def main() = f(7)'
t consinfix 6       'def sum(xs) = xs match { case Nil => 0 case h :: t => h + sum(t) } def main() = sum(1 :: 2 :: 3 :: Nil)'
t ctorpat   14      'def eval(e) = e match { case Num(v) => v case Add(l, r) => eval(l) + eval(r) case Mul(l, r) => eval(l) * eval(r) case _ => 0 } def main() = eval(Add(Num(2), Mul(Num(3), Num(4))))'
t ctor3     6       'def s3(t) = t match { case Tri(a, b, c) => a + b + c case _ => 0 } def main() = s3(Tri(1, 2, 3))'
t mixedtup  105     'def f(t) = t match { case (0, v) => v case (1, w) => w + 100 case _ => 0 } def main() = f((1, 5))'
t mixedtup0 8       'def f(t) = t match { case (0, v) => v * 2 case _ => 0 } def main() = f((0, 4))'
t comment    7       'def add(a, b) = a + b def main() = add(3, 4) // trailing comment skipped to EOL'

echo "--- self-compilation fixpoint ---"
# stage1 = C0(C_min): C_min compiles its own source.
runir "$WORK/C0.ir" "$CMIN" > "$WORK/stage1.ir"
[ -s "$WORK/stage1.ir" ] || { echo "FAIL: stage1 empty"; exit 1; }
python3 -c "s=open('$WORK/stage1.ir').read();import sys;sys.exit(0 if s.count('(')==s.count(')') and s.count('(')>0 else 1)" \
  && echo "ok   stage1 is balanced Core IR ($(wc -c < "$WORK/stage1.ir") bytes)" || { echo "FAIL stage1 unbalanced"; fail=1; }

# C1 = stage1 made runnable: splice the reference front's file-main (already correctly lowered) into
# stage1's def list. This is the same wrapping C0 got — only now around the self-produced compiler.
python3 - "$WORK/C0.ir" "$WORK/stage1.ir" "$WORK/C1.ir" <<'PY'
import sys
c0=open(sys.argv[1]).read(); i=c0.index('(def main '); d=0; j=i
while j<len(c0):
    if c0[j]=='(': d+=1
    elif c0[j]==')':
        d-=1
        if d==0: j+=1; break
    j+=1
md=c0[i:j]
s=open(sys.argv[2]).read(); k=s.rindex(') (entry')
open(sys.argv[3],'w').write(s[:k]+' '+md+' '+s[k:])
PY

# C1 must itself be a WORKING compiler (proves C_min compiled itself correctly, not just to legal text).
printf 'def fac(n) = if n < 1 then 1 else n * fac(n - 1) def main() = fac(5)' > "$WORK/t.L"
g1=$(runir "$WORK/C1.ir" "$WORK/t.L" | runir /dev/stdin | head -1)
[ "$g1" = "120" ] && echo "ok   C1 (self-produced compiler) compiles fac(5) -> 120" || { echo "FAIL C1 fac got [$g1]"; fail=1; }

# stage2 = C1(C_min): the self-produced compiler compiles C_min's source.
runir "$WORK/C1.ir" "$CMIN" > "$WORK/stage2.ir"
if cmp -s "$WORK/stage1.ir" "$WORK/stage2.ir"; then
  echo "ok   *** FIXPOINT: stage1 == stage2 (byte-identical, $(wc -c < "$WORK/stage2.ir") bytes) ***"
else
  echo "FAIL fixpoint: stage1 != stage2"; fail=1
fi
exit $fail
