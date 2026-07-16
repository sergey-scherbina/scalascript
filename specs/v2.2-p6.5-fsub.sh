#!/usr/bin/env bash
# P6.5 X1 — the DIFFERENTIAL harness for F, the ScalaScript-subset compiler written in the subset.
#
#   F = specs/v2.2-p6.5-fsub.ssc — `compile(src, dq): String -> String`, emitting Core IR that must be
#       BYTE-IDENTICAL to what the reference front (`ssc1-front` + `ssc1-lower`) produces for the same
#       program. Escape-free: the `"` char is the `dq` parameter (P6.6b design).
#
# The oracle is exact. For every corpus program P:
#
#   ref  = ssc1-front(P) + ssc1-lower(P)     via bin/ssc1-run.ssc0     (the trusted reference)
#   mine = F0(P)                             where F0 = driver(ssc1-front(F))
#   ASSERT  mine == ref                      byte-identical
#
# WHY THIS IS THE WHOLE OF X1: if `mine == ref` for every P *including F's own source*, the
# stage1 == stage2 fixpoint follows algebraically --
#   stage1 = C0(F_src) = F(F_src) == ssc1-front(F_src)  =>  C1 == C0  =>  stage2 = C1(F_src) = stage1.
# So X1 is not separate engineering; it is "extend this corpus until it contains F_src". `--self` runs
# that final step once the corpus is green (see specs/v2.2-p6.6-fixpoint.sh for the same wrapping).
#
# Bootstrap is ssc1-front only -- no sbt, no spike (same convention as v2.2-p6.6-fixpoint.sh).
# Prereqs: SSC_JAR = a run-ir-capable ssc kernel jar
#          (build: `scala-cli --power package v2/src --assembly -o /tmp/ssc.jar`)
#          V2_DIR = <repo>/v2
set -u
JAR=${SSC_JAR:?set SSC_JAR to the ssc kernel jar}; V2=${V2_DIR:?set V2_DIR to <repo>/v2}
HERE=$(cd "$(dirname "$0")" && pwd)
FSUB="$HERE/v2.2-p6.5-fsub.ssc"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"; rm -f "$V2/bin/_p65_fsub_drv.ssc0"' EXIT
cd "$V2" || exit 2
run()   { java -Xss512m -jar "$JAR" run "$@" 2>/dev/null; }
runir() { java -Xss512m -jar "$JAR" run-ir "$@" 2>/dev/null; }
fail=0

# ── the ssc0 driver ───────────────────────────────────────────────────────────────────────────
# `#`-prims are ssc0, not the subset, so the file reading is a DRIVER wrapping F's pure
# `compile: (String, String) -> String`. dq = `"` is built via #sfromCodes(Cons(34,Nil)) and passed in,
# so F's own source never contains a `"`-inside-a-string. Identical in shape to the P6.6 driver.
cat > bin/_p65_fsub_drv.ssc0 <<'DRV'
import "../lib/ssc1-lower.ssc0"
def app2 = (xs, ys) => match xs { case Nil => ys case Cons(h, t) => Cons(h, app2(t, ys)) }
def prim0 = (nm) => Pair("prim", Pair(nm, Nil))
def prim1 = (nm, a) => Pair("prim", Pair(nm, Cons(a, Nil)))
def consPat = Pair("cpat", Pair("Cons", Cons(Pair("vpat", "path"), Cons(Pair("vpat", "rest"), Nil))))
def readCompile = (dqArg) => prim1("io.print", mkApp(mkVar("compile"), Cons(prim1("utf8->str", prim1("io.readFile", mkVar("path"))), Cons(dqArg, Nil))))
def fileMain = (dqArg) => mkDef("main", Nil, Pair("match", Pair(prim0("io.args"), Cons(Pair(consPat, readCompile(dqArg)), Nil))))
def main = () =>
  let srcPath = match #io.env("FSUB_SRC") { case Some(p) => p case None => "" } in
  let fsubSrc = #utf8->str(#io.readFile(srcPath)) in
  let dqArg = mkStr(#sfromCodes(Cons(34, Nil))) in
  let prog = app2(parse(fsubSrc), Cons(fileMain(dqArg), Nil)) in
  #io.print(#coreir.encode(lowerProg(prog)))
DRV

# ── F0 = driver(ssc1-front(F)) : F, made runnable on the VM ───────────────────────────────────
FSUB_SRC="$FSUB" run bin/_p65_fsub_drv.ssc0 > "$WORK/F0.ir"
[ -s "$WORK/F0.ir" ] || { echo "FAIL: could not bootstrap F0 from $FSUB"; FSUB_SRC="$FSUB" java -jar "$JAR" run bin/_p65_fsub_drv.ssc0 2>&1 | head -5; exit 1; }
echo "ok   F0 bootstrapped ($(wc -c < "$WORK/F0.ir") bytes)"

# ── the differential oracle ───────────────────────────────────────────────────────────────────
# d <name> <program-source>: F(P) must be byte-identical to ssc1-front(P).
d() {
  printf '%s\n' "$2" > "$WORK/p.ssc"
  run bin/ssc1-run.ssc0 "$WORK/p.ssc" > "$WORK/ref.ir"
  runir "$WORK/F0.ir" "$WORK/p.ssc" > "$WORK/mine.ir"
  if [ ! -s "$WORK/ref.ir" ]; then echo "SKIP $1  (reference produced nothing)"; return; fi
  if cmp -s "$WORK/mine.ir" "$WORK/ref.ir"; then
    echo "ok   $1 -> CoreIR == ssc1-front ($(wc -c < "$WORK/mine.ir") bytes)"
  else
    echo "FAIL $1  CoreIR != ssc1-front"
    python3 - "$WORK/mine.ir" "$WORK/ref.ir" <<'PY'
import sys
a=open(sys.argv[1]).read(); b=open(sys.argv[2]).read()
i=0
while i<min(len(a),len(b)) and a[i]==b[i]: i+=1
print('       first divergence at byte', i, 'of', len(a), '(mine) /', len(b), '(ref)')
print('       mine: ...'+repr(a[max(0,i-40):i+60]))
print('       ref : ...'+repr(b[max(0,i-40):i+60]))
PY
    fail=1
  fi
}

echo "--- differential corpus: F(P) vs ssc1-front(P) ---"
d lit        'def main(): Int = 42'
d add        'def main(): Int = 1 + 2'
d sub        'def main(): Int = 7 - 3'
d mul        'def main(): Int = 6 * 7'
d prec       'def main(): Int = 1 + 2 * 3'
d prec2      'def main(): Int = 2 * 3 + 4 * 5'
d paren      'def main(): Int = (1 + 2) * 3'
d assoc      'def main(): Int = 10 - 3 - 2'
d notype     'def main() = 1 + 2'
d comment    'def main(): Int = 1 + 2 // trailing comment'
d twodefs    'def g(a: Int, b: Int): Int = a + b
def main(): Int = g(1, 2)'
d locals     'def g(a: Int, b: Int): Int = a - b
def main(): Int = g(9, 4)'
d call0      'def g(): Int = 7
def main(): Int = g()'
d ifthen     'def main(): Int = if 1 < 2 then 3 else 4'
d cmp_gt     'def g(a: Int, b: Int): Boolean = a > b
def main(): Boolean = g(1, 2)'
d cmp_ge     'def g(a: Int, b: Int): Boolean = a >= b
def main(): Boolean = g(1, 2)'
d cmp_le     'def g(a: Int, b: Int): Boolean = a <= b
def main(): Boolean = g(1, 2)'
d eq         'def g(a: Int, b: Int): Boolean = a == b
def main(): Boolean = g(1, 2)'
d neq        'def g(a: Int, b: Int): Boolean = a != b
def main(): Boolean = g(1, 2)'
d andand     'def g(a: Boolean, b: Boolean): Boolean = a && b
def main(): Boolean = g(true, false)'
d oror       'def g(a: Boolean, b: Boolean): Boolean = a || b
def main(): Boolean = g(true, false)'
d boollit    'def main(): Boolean = true'
d rec        'def f(x: Int): Int = if x < 1 then 1 else x * f(x - 1)
def main(): Int = f(5)'
d threedefs  'def sq(n: Int): Int = n * n
def inc(n: Int): Int = n + 1
def main(): Int = inc(sq(4))'
d nestcall   'def add(a: Int, b: Int): Int = a + b
def main(): Int = add(add(1, 2), add(3, 4))'
d mixprec    'def f(a: Int, b: Int): Boolean = a + 1 < b * 2 && a > 0
def main(): Boolean = f(1, 5)'
d strlit     'def main(): String = "hi"'
d strcat_pp  'def g(a: String, b: String): String = a ++ b
def main(): String = g("x", "y")'
d strcat_lit 'def main(): String = "a" + "b"'
d strcat_var 'def g(a: String): String = a + "b"
def main(): String = g("x")'
d strcat_int 'def g(n: Int): String = "v" + n
def main(): String = g(5)'
d strcat_nest 'def g(a: String): String = "x" + a + "y"
def main(): String = g("q")'
d plus_ints  'def g(a: Int, b: Int): Int = a + b
def main(): Int = g(1, 2)'
d len_lit    'def main(): Int = "ab".length'
d len_var    'def h(s: String): Int = s.length
def main(): Int = h("ab")'
d len_concat 'def h(s: String): Int = (s ++ s).length
def main(): Int = h("ab")'
d len_sub    'def h(s: String): Int = s.substring(0, 1).length
def main(): Int = h("ab")'
d charAt     'def g(s: String, i: Int): Int = s.charAt(i)
def main(): Int = g("ab", 0)'
d charAt_lit 'def main(): Int = "ab".charAt(0)'
d substring  'def k(s: String, a: Int, b: Int): String = s.substring(a, b)
def main(): String = k("abcd", 1, 3)'
d streq      'def g(a: String, b: String): Boolean = a == b
def main(): Boolean = g("a", "a")'
d strchain   'def g(s: String, i: Int): Boolean = s.charAt(i) >= 97 && s.charAt(i) <= 122
def main(): Boolean = g("ab", 0)'

if [ "${1:-}" = "--self" ]; then
  echo "--- X1: F compiles its OWN source ---"
  run bin/ssc1-run.ssc0 "$FSUB" > "$WORK/selfref.ir"
  runir "$WORK/F0.ir" "$FSUB" > "$WORK/stage1.ir"
  if cmp -s "$WORK/stage1.ir" "$WORK/selfref.ir"; then
    echo "ok   *** F(F_src) == ssc1-front(F_src) -- the fixpoint follows ***"
  else
    echo "FAIL F cannot yet compile its own source byte-identically (expected until the corpus grows)"
    fail=1
  fi
fi

exit $fail
