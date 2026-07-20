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
# K62.3 (fixed 2026-07-20): the kernel runs the pipeline on an `onSizedStack` WORKER thread
# (Main.scala:26), so the old `-Xss512m` was a DEAD flag here — it sizes the MAIN thread, never the
# worker — and F0-bootstrap (compiling F's ~222 KB source) overflowed the 64 MB worker default →
# StackOverflow → this gate LIED RED on a stock jar. The worker is sized ONLY by `-Dssc.stackSize`
# (bytes), which onSizedStack reads. 1 GiB covers F compiling its own source with margin; override
# via SSC_STACK if ever needed, but the DEFAULT must pass with no manual env — that is the fix.
SSC_STACK=${SSC_STACK:-1073741824}
JVM="-Dssc.stackSize=$SSC_STACK"
run()   { java $JVM -jar "$JAR" run "$@" 2>/dev/null; }
runir() { java $JVM -jar "$JAR" run-ir "$@" 2>/dev/null; }
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
def readCompile = (dqArg, bsArg) => prim1("io.print", mkApp(mkVar("compile"), Cons(prim1("utf8->str", prim1("io.readFile", mkVar("path"))), Cons(dqArg, Cons(bsArg, Nil)))))
def fileMain = (dqArg, bsArg) => mkDef("main", Nil, Pair("match", Pair(prim0("io.args"), Cons(Pair(consPat, readCompile(dqArg, bsArg)), Nil))))
def main = () =>
  let srcPath = match #io.env("FSUB_SRC") { case Some(p) => p case None => "" } in
  let fsubSrc = #utf8->str(#io.readFile(srcPath)) in
  let dqArg = mkStr(#sfromCodes(Cons(34, Nil))) in
  let bsArg = mkStr(#sfromCodes(Cons(92, Nil))) in
  let prog = app2(parse(fsubSrc), Cons(fileMain(dqArg, bsArg), Nil)) in
  #io.print(#coreir.encode(lowerProg(prog)))
DRV

# ── F0 = driver(ssc1-front(F)) : F, made runnable on the VM ───────────────────────────────────
FSUB_SRC="$FSUB" run bin/_p65_fsub_drv.ssc0 > "$WORK/F0.ir"
[ -s "$WORK/F0.ir" ] || { echo "FAIL: could not bootstrap F0 from $FSUB"; FSUB_SRC="$FSUB" java $JVM -jar "$JAR" run bin/_p65_fsub_drv.ssc0 2>&1 | head -5; exit 1; }
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
d ctor_cons  'def main(): List[Int] = Cons(1, Nil)'
d ctor_nil   'def main(): List[Int] = Nil'
d ctor_tup   'def main(): (Int, Int) = (1, 2)'
d consinfix  'def main(): List[Int] = 1 :: 2 :: Nil'
d m_cons     'def g(xs: List[Int]): Int = xs match { case Cons(h, t) => h case Nil => 0 }
def main(): Int = g(Nil)'
d m_consinfix 'def g(xs: List[Int]): Int = xs match { case h :: t => h case _ => 0 }
def main(): Int = g(Nil)'
d m_tuple    'def g(p: (Int, Int)): Int = p match { case (a, b) => a }
def main(): Int = g((1, 2))'
d m_wild     'def g(n: Int): Int = n match { case 0 => 10 case _ => 99 }
def main(): Int = g(1)'
d m_intchain 'def g(n: Int): Int = n match { case 0 => 10 case 1 => 20 case _ => 99 }
def main(): Int = g(1)'
d m_nestenv  'def g(xs: List[Int], k: Int): Int = xs match { case Cons(h, t) => h + k case Nil => k }
def main(): Int = g(Nil, 3)'
d m_exprscrut 'def g(a: Int, b: Int): Int = (a + b) match { case 0 => 1 case _ => 2 }
def main(): Int = g(1, 2)'
d m_rec      'def len(xs: List[Int]): Int = xs match { case Nil => 0 case Cons(h, t) => 1 + len(t) }
def main(): Int = len(Cons(7, Cons(8, Nil)))'
d m_tupsnd   'def snd(p: (Int, Int)): Int = p match { case (a, b) => b }
def main(): Int = snd((1, 2))'
d m_consenv  'def sum(xs: List[Int]): Int = xs match { case Nil => 0 case h :: t => h + sum(t) }
def main(): Int = sum(1 :: 2 :: 3 :: Nil)'
d val1       'def f(a: Int, b: Int): Int = { val x = a + b  x * 2 }
def main(): Int = f(1, 2)'
d val2       'def f(a: Int, b: Int): Int = { val x = a + b  val y = x + a  y }
def main(): Int = f(10, 20)'
d val_typed  'def f(a: Int): Int = { val x: Int = a + 1  x }
def main(): Int = f(1)'
d val_match  'def f(a: Int): Int = { val x = a + 1  x match { case 0 => 1 case _ => x } }
def main(): Int = f(1)'
d val_str    'def g(s: String): String = { val t = s ++ "!"  t }
def main(): String = g("ab")'
d lam1       'def ap(f: Int => Int, x: Int): Int = f(x)
def main(): Int = ap((y: Int) => y + 1, 5)'
d lam_notype 'def ap(f: Int => Int, x: Int): Int = f(x)
def main(): Int = ap(y => y + 1, 5)'
d lam_cap    'def mk(k: Int): Int => Int = (y: Int) => y + k
def main(): Int = mk(3)(4)'
d lam2       'def ap2(f: (Int, Int) => Int): Int = f(1, 2)
def main(): Int = ap2((a: Int, b: Int) => a + b)'
d lam_local  'def ap(f: Int => Int, x: Int): Int = f(x)
def main(): Int = ap(y => y * 2, 5)'
d ty_generic 'def f(m: Map[String, Int], k: Int): Int = k
def main(): Int = 0'
d ty_nested  'def f(m: Map[String, List[Int]], k: Int): Int = k
def main(): Int = 0'
d ty_tuplist 'def f(xs: List[(Int, Int)], k: Int): Int = k
def main(): Int = 0'
d cc_decl    'case class P(x: Int, y: Int)'
d cc_nomain  'case class P(x: Int)'
d cc_pat     'case class P(x: Int, y: Int)
def g(p: P): Int = p match { case P(a, b) => a + b }'
d cc_two     'case class P(x: Int)
case class Q(x: Int)'
d cc_interl  'case class P(x: Int)
def helper(n: Int): Int = n + 1
case class Q(y: Int)'
d cc_after   'def helper(n: Int): Int = n + 1
case class P(x: Int)'
d cc_types   'case class M(n: Int, s: String, xs: List[Int])'
d cc_tuptype 'case class G(p: (Int, Int))'
d cc_3       'case class T(a: Int, b: String, c: Int)'
d cc_ctor    'case class P(x: Int, y: Int)
def main(): Int = P(1, 2).x'
d cc_field2  'case class P(x: Int, y: Int)
def main(): Int = P(1, 2).y'
d cc_match   'case class P(x: Int, y: Int)
def g(p: P): Int = p match { case P(a, b) => a + b }
def main(): Int = g(P(1, 2))'
d cc_shared  'case class P(x: Int)
case class Q(x: Int)
def main(): Int = P(1).x + Q(2).x'
d cc_unknown 'def g(o: Int): Int = o.zzz
def main(): Int = 0'
d cc_nest    'case class P(x: Int, y: Int)
def main(): Int = P(P(1, 2).x, 3).y'
d cc_builtin 'case class P(x: Int)
def main(): List[Int] = Cons(1, Nil)'
d tup_fst    'def main(): Int = (1, 2)._1'
d tup_snd    'def main(): Int = (1, 2)._2'
d tup_param  'def g(p: (Int, Int)): Int = p._1 + p._2
def main(): Int = g((3, 4))'
d tup_nest   'def main(): Int = ((1, 2), 3)._1._2'
d tl_expr    'println(42)'
d tl_val_main 'val a = 10
def main(): Int = a'
d tl_val_fwd 'def main(): Int = a
val a = 10'
d tl_def_val_expr 'def g(n: Int): Int = n + 1
val x = 5
println(g(x))'
d tl_two_vals 'val a = 1
val b = 2
println(a + b)'
d tl_cc_val_expr 'case class P(x: Int)
val p = P(3)
println(p.x)'
d tl_val_str 'val s = "hi"
println(s)'
d tl_expr_only_multi 'println(1)
println(2)'
d flt_lit    'def main(): Double = 1.0'
d flt_pi     'def main(): Double = 3.14159'
d flt_half   'def main(): Double = 0.5'
d flt_mul    'def main(): Double = 2.0 * 3.14'
d flt_int_mix 'def main(): Double = 1.5 + 2'
d flt_tenpt0 'def main(): Double = 10.0'
d bl_int     'def f(n: Int): Int = n match
  case 0 => 1
  case _ => 2
def main(): Int = f(0)'
d bl_ctor    'def f(xs: List[Int]): Int = xs match
  case Cons(h, t) => h
  case Nil => 0
def main(): Int = f(Nil)'
d bl_wild    'def f(xs: List[Int]): Int = xs match
  case Cons(_, _) => 1
  case _ => 0
def main(): Int = f(Nil)'
d bl_then_def 'def f(n: Int): Int = n match
  case 0 => 1
  case _ => 2
def g(n: Int): Int = n + 1
def main(): Int = f(0) + g(0)'
d bl_braced_still 'def f(n: Int): Int = n match { case 0 => 1 case _ => 2 }
def main(): Int = f(0)'
d cc_extends 'case class Circle(r: Int) extends Shape
def main(): Int = Circle(5).r'
d trait_alone 'sealed trait Shape
def main(): Int = 1'
d sealed_family 'sealed trait Shape
case class Circle(r: Int) extends Shape
case class Rect(w: Int, h: Int) extends Shape
def area(s: Shape): Int = s match
  case Circle(r) => r
  case Rect(w, h) => w
def main(): Int = area(Circle(5))'
d trait_plain 'trait Named
case class P(n: Int) extends Named
def main(): Int = P(1).n'
d list_lit3  'def main(): List[Int] = List(1, 2, 3)'
d list_empty 'def main(): List[Int] = List()'
d list_one   'def main(): List[Int] = List(5)'
d list_cc    'case class P(x: Int)
def main(): List[P] = List(P(1), P(2))'
d list_tl    'val xs = List(1, 2, 3)
println(xs)'
d si_mid     'def f(name: String): String = s"Hello, $name!"
def main(): String = f("x")'
d si_brace   'def f(name: String): String = s"Hello, ${name}!"
def main(): String = f("x")'
d si_single  'def f(a: String): String = s"$a"
def main(): String = f("q")'
d si_empty   'def f(a: String): String = s""
def main(): String = f("q")'
d si_plain   'def main(): String = s"hello"'
d si_two     'def f(a: String, b: String): String = s"$a$b"
def main(): String = f("x", "y")'
d si_expr    'def f(n: Int): String = s"v=${n + 1}"
def main(): String = f(5)'
d si_dotlit  'def f(a: String): String = s"$a.b"
def main(): String = f("q")'
d si_tl      'val name = "World"
println(s"Hi $name")'
d neg_lit    'def main(): Int = -7'
d neg_var    'def f(x: Int): Int = -x
def main(): Int = f(3)'
d neg_float  'def main(): Double = -7.5'
d neg_inbin  'def main(): Int = 1 + -2'
d neg_paren  'def f(a: Int, b: Int): Int = -(a + b)
def main(): Int = f(1, 2)'
d neg_call   'def g(x: Int): Int = x
def f(x: Int): Int = -g(x)
def main(): Int = f(3)'
d triple_plain 'def main(): String = """plain"""'
d triple_nl  'def main(): String = """line1
line2"""'
d triple_q   'def main(): String = """say "hi" done"""'
d triple_tl  'val page = """<h1>Hi</h1>
<p>x</p>"""
println(page)'
d null_lit   'def main(): String = { val x: String = null  x }'
d null_ret   'def f(): String = null
def main(): String = f()'
d cc_fill    'def main(): Int = Array.fill(3)(7).sum'
d cc_tab     'def main(): String = Array.tabulate(4)(i => i * i).mkString(",")'
d cc_listfill 'val filled = List.fill(4)(0)
println(filled)'
d cc_range   'def main(): String = Array.range(0, 5).mkString("-")'
d si_nest_str 'val xs = List(1, 2, 3)
println(s"Squares: ${xs.mkString(", ")}")'
d si_nest_arg 'def f(o: Option[String]): String = s"v=${o.getOrElse("none")}"
def main(): String = f(None)'
d brk_lit    'def main(): List[Int] = [1, 2, 3]'
d brk_empty  'def main(): List[Int] = []'
d brk_str    'val rows = ["a", "b"]
println(rows)'
d brk_ml     'val cols = [
  1,
  2,
  3
]
println(cols)'
d brk_arg    'def f(xs: List[Int]): Int = xs.length
def main(): Int = f([10, 20])'

if [ "${1:-}" = "--self" ]; then
  echo "--- X1: F compiles its OWN source ---"
  # Step 1 -- the differential oracle applied to F's OWN source. This is the load-bearing claim;
  # everything below follows from it.
  run bin/ssc1-run.ssc0 "$FSUB" > "$WORK/selfref.ir"
  runir "$WORK/F0.ir" "$FSUB" > "$WORK/stage1.ir"
  if cmp -s "$WORK/stage1.ir" "$WORK/selfref.ir"; then
    echo "ok   F(F_src) == ssc1-front(F_src) byte-identical ($(wc -c < "$WORK/stage1.ir") bytes)"
  else
    echo "FAIL F cannot yet compile its own source byte-identically"
    python3 - "$WORK/stage1.ir" "$WORK/selfref.ir" <<'DIFFPY'
import sys
a=open(sys.argv[1]).read(); b=open(sys.argv[2]).read()
i=0
while i<min(len(a),len(b)) and a[i]==b[i]: i+=1
print('       first divergence at byte', i, 'of', len(a), '(mine) /', len(b), '(ref)')
j=b.rfind('(def ',0,i)
print('       in ref def:', b[j:j+40].split()[1] if j>0 else '?')
print('       mine: ...'+repr(a[max(0,i-60):i+60]))
print('       ref : ...'+repr(b[max(0,i-60):i+60]))
DIFFPY
    exit 1
  fi

  # Step 2 -- the fixpoint. stage1 is F-compiled-by-F; wrap it in the SAME file-reading main that C0
  # got, giving C1, then have C1 compile F's source again. stage1 == stage2 is the literal
  # self-compilation fixed point -- no quine, the source comes from a FILE.
  #
  # The driver supplies BOTH the main def and the entry that calls it. F's source is main-less, so
  # F faithfully emits `(entry (lit unit))` for it (that is what the reference emits, and byte-
  # identity is the oracle) -- wrapping therefore has to replace the entry, exactly as C0 got when
  # lowerProg saw the appended fileMain. Splicing the def alone leaves a compiler that never runs.
  python3 - "$WORK/F0.ir" "$WORK/stage1.ir" "$WORK/C1.ir" <<'SPLICEPY'
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
open(sys.argv[3],'w').write(s[:k]+' '+md+') (entry (app (global main))))')
SPLICEPY

  # C1 must be a WORKING compiler -- and not merely "it runs": byte-identical to the reference too.
  printf 'def f(x: Int): Int = if x < 1 then 1 else x * f(x - 1)\ndef main(): Int = f(5)\n' > "$WORK/t.ssc"
  run bin/ssc1-run.ssc0 "$WORK/t.ssc" > "$WORK/t.ref"
  runir "$WORK/C1.ir" "$WORK/t.ssc" > "$WORK/t.mine"
  if cmp -s "$WORK/t.mine" "$WORK/t.ref"; then
    g1=$(runir "$WORK/t.mine" | head -1)
    if [ "$g1" = "120" ]; then echo "ok   C1 (the self-produced compiler) is byte-identical to the reference AND its IR runs -> $g1"
    else echo "FAIL C1's emitted IR ran to [$g1], want 120"; fail=1; fi
  else
    echo "FAIL C1 is not a faithful compiler"; fail=1
  fi

  # stage2 = C1(F_src)
  runir "$WORK/C1.ir" "$FSUB" > "$WORK/stage2.ir"
  if cmp -s "$WORK/stage1.ir" "$WORK/stage2.ir"; then
    echo "ok   *** X1 FIXPOINT: stage1 == stage2 (byte-identical, $(wc -c < "$WORK/stage2.ir") bytes) ***"
  else
    echo "FAIL fixpoint: stage1 != stage2"; fail=1
  fi
fi

exit $fail
