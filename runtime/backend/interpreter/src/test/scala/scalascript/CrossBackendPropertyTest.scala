package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.{JsGen, JvmGen}
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source
import scala.util.Random

/** Cross-backend property/differential test (xbackend-property-equivalence, slice 1).
 *
 *  The conformance suite checks ~127 FIXED programs agree across interp/JVM/JS. This GENERATES
 *  programs over the core deterministic-`Int` subset (arithmetic, `val`, `if`, user functions) from
 *  seeds and asserts `interp == JS(node)` (plus a small JVM/scala-cli sample) — turning the
 *  one-source-many-targets guarantee from fixed cases into generated coverage. Any divergence on a
 *  legitimate in-range program is a real cross-backend bug; the failing seed + program is printed.
 *
 *  Slice 1 scope: the core fully-supported subset, with results bounded to all backends' safe int
 *  range (|v| < 1e8 — under 32-bit `Int`, 53-bit JS double, 64-bit interp `Long`) so numeric-overflow
 *  edge cases (a documented platform difference) don't false-fail. Broadening the generator
 *  (collections / ADTs / pattern matching / effects) + the overflow dimension are follow-on slices.
 */
class CrossBackendPropertyTest extends AnyFunSuite:

  private val ArithOps = Vector("+", "-", "*")
  private val CmpOps   = Vector("<", ">", "<=", ">=", "==", "!=")

  /** A well-typed deterministic `Int` expression of bounded depth over `vars` + `fns`. */
  private def genExpr(rng: Random, depth: Int, vars: Seq[String], fns: Seq[String]): String =
    if depth <= 0 || rng.nextInt(3) == 0 then
      if vars.nonEmpty && rng.nextBoolean() then vars(rng.nextInt(vars.size))
      else rng.nextInt(6).toString
    else
      val choice = rng.nextInt(if fns.nonEmpty then 4 else 3)
      choice match
        case 0 =>
          s"(${genExpr(rng, depth-1, vars, fns)} ${ArithOps(rng.nextInt(ArithOps.size))} ${genExpr(rng, depth-1, vars, fns)})"
        case 1 =>
          val cond = s"${genExpr(rng, depth-1, vars, fns)} ${CmpOps(rng.nextInt(CmpOps.size))} ${genExpr(rng, depth-1, vars, fns)}"
          s"(if $cond then ${genExpr(rng, depth-1, vars, fns)} else ${genExpr(rng, depth-1, vars, fns)})"
        case 2 =>
          genExpr(rng, depth-1, vars, fns)
        case _ =>
          s"${fns(rng.nextInt(fns.size))}(${genExpr(rng, depth-1, vars, fns)})"

  /** Dispatch across program KINDS so the generator exercises more of the language than arithmetic.
   *  Every kind prints a deterministic `Int`/`String` — NOT a `Double` (formatting differs across
   *  backends) and NOT a `Set`/`Map` (iteration order legitimately differs); `List` is ordered, safe. */
  private val Kinds = 12
  private def genProgram(seed: Int): String =
    val rng = new Random(seed.toLong)
    (seed % Kinds) match
      case 0  => genArithProgram(rng)
      case 1  => genListProgram(rng)
      case 2  => genMatchProgram(rng)
      case 3  => genEnumProgram(rng)
      case 4  => genStringProgram(rng)
      case 5  => genCaseClassProgram(rng)
      case 6  => genOptionProgram(rng)
      case 7  => genEitherProgram(rng)
      case 8  => genClosureProgram(rng)
      case 9  => genNestedCollProgram(rng)
      case 10 => genStringOpsProgram(rng)
      case _  => genEffectProgram(rng)

  /** Closures as values + HOF application/composition. */
  private def genClosureProgram(rng: Random): String =
    val a = rng.nextInt(6); val b = rng.nextInt(8); val c = rng.nextInt(8); val d = rng.nextInt(8)
    s"val f = (x: Int) => x * 2 + $a\nval g = (x: Int) => x - 1\n" +
      s"println(f(g($b)) + List($c, $d).map(f).sum)\n"

  /** Nested `List[List[Int]]` — flatten + map-of-lengths. */
  private def genNestedCollProgram(rng: Random): String =
    def row = (0 until (1 + rng.nextInt(3))).map(_ => rng.nextInt(7)).mkString(", ")
    val xss = s"List(List($row), List($row), List($row))"
    s"val xss = $xss\nprintln(xss.flatten.sum + xss.map(ys => ys.length).sum)\n"

  /** Richer String ops — length / take / drop (all deterministic). */
  private def genStringOpsProgram(rng: Random): String =
    val word = Vector("hello", "world", "scala", "effect", "abcdef")(rng.nextInt(5))
    val n = rng.nextInt(7); val m = rng.nextInt(7)
    s"""val s = "$word"\nprintln(s.length + s.take($n).length + s.drop($m).length)\n"""

  private def genArithProgram(rng: Random): String =
    val sb  = new StringBuilder
    val nFns    = rng.nextInt(3)            // 0–2 helper functions
    val fnNames = (0 until nFns).map(i => s"f$i")
    for i <- 0 until nFns do
      sb.append(s"def f$i(x: Int): Int = ${genExpr(rng, 2, Seq("x"), fnNames.take(i))}\n")
    val nVals    = 1 + rng.nextInt(3)       // 1–3 vals
    val valNames = (0 until nVals).map(i => s"v$i")
    for i <- 0 until nVals do
      sb.append(s"val v$i = ${genExpr(rng, 2, valNames.take(i), fnNames)}\n")
    sb.append(s"println(${genExpr(rng, 3, valNames, fnNames)})\n")
    sb.toString

  /** `List[Int]` operations producing an `Int` — ordered, deterministic. */
  private def genListProgram(rng: Random): String =
    val n  = 3 + rng.nextInt(4)
    val xs = (0 until n).map(_ => rng.nextInt(10)).mkString(", ")
    val k  = rng.nextInt(6)
    val op = rng.nextInt(8) match
      case 0 => "sum"
      case 1 => "length"
      case 2 => "max"
      case 3 => "min"
      case 4 => s"filter(e => e > $k).length"
      case 5 => "map(e => e * 2).sum"
      case 6 => "foldLeft(0)((a, b) => a + b)"
      case _ => s"count(e => e > $k)"
    s"val xs = List($xs)\nprintln(xs.$op)\n"

  /** `Int` `match` with a wildcard. */
  private def genMatchProgram(rng: Random): String =
    val n = rng.nextInt(5)
    s"val n = $n\nprintln(n match\n  case 0 => 10\n  case 1 => 21\n  case 2 => 32\n  case _ => 99\n)\n"

  /** Enum ADT + exhaustive `match`. */
  private def genEnumProgram(rng: Random): String =
    val pick = Vector("Red", "Green", "Blue")
    val a = pick(rng.nextInt(3)); val b = pick(rng.nextInt(3))
    "enum Color:\n  case Red, Green, Blue\n" +
      "def rank(c: Color): Int = c match\n  case Red => 1\n  case Green => 2\n  case Blue => 3\n" +
      s"println(rank($a) + rank($b) * 10)\n"

  /** String concatenation + `.length`. */
  private def genStringProgram(rng: Random): String =
    val parts = (0 until (2 + rng.nextInt(3))).map(_ => "\"" + ("ab".repeat(1 + rng.nextInt(3))) + "\"")
    val s = parts.mkString(" + ")
    if rng.nextBoolean() then s"val s = $s\nprintln(s.length)\n"
    else s"val s = $s\nprintln(s)\n"

  /** Case-class construction + field access. */
  private def genCaseClassProgram(rng: Random): String =
    val x = rng.nextInt(50); val y = rng.nextInt(50)
    "case class Point(x: Int, y: Int)\n" +
      s"val p = Point($x, $y)\nprintln(p.x + p.y * 2)\n"

  /** `Option[Int]` — construct via a branch, then `map`/`getOrElse`. */
  private def genOptionProgram(rng: Random): String =
    val v = rng.nextInt(20); val d = rng.nextInt(10); val some = rng.nextBoolean()
    val o = if some then s"Some($v)" else "None"
    s"val o: Option[Int] = $o\nprintln(o.map(e => e + 1).getOrElse($d))\n"

  /** `Either[String, Int]` — construct via a branch, then `match`. */
  private def genEitherProgram(rng: Random): String =
    val v = rng.nextInt(20); val right = rng.nextBoolean()
    val e = if right then s"Right($v)" else "Left(\"err\")"
    s"val e: Either[String, Int] = $e\n" +
      "println(e match\n  case Right(n) => n * 2\n  case Left(_) => -1\n)\n"

  /** Algebraic EFFECTS — sub-dispatch across shapes that stress the SEPARATE CPS codegen on each
   *  backend (JvmGenCpsTransform / JsGenCpsCodegen / interp): one-shot, arg-carrying, two-op, and
   *  MULTI-SHOT. The one-shot/arg/two-op use the INLINE `println(handle(...))` form on purpose — the
   *  regression guard for BUGS.md `jvmgen-handle-in-arg-position` (found by this test, now fixed; the
   *  fix should hold for every effectful call-arg, which these verify). All print a deterministic Int. */
  private def genEffectProgram(rng: Random): String = rng.nextInt(10) match
    case 8 => // COMPOSITION: handle result feeds MAIN-PATH arithmetic — `val r = handle(...); println(r * 2 + base)`.
      // `r` is the Any-typed `_handle` result; `r * 2 + base` must route through `_binOp` (else `Any * 2`
      // won't compile under scala-cli). High-signal probe for an emit-routing gap on handle-result vals.
      val n = 1 + rng.nextInt(6); val k = 1 + rng.nextInt(4)
      val a = rng.nextInt(7); val b = rng.nextInt(7); val c = rng.nextInt(7)
      "effect Counter:\n  def tick(): Int\n" +
        "def loop(n: Int): Int ! Counter =\n  var acc = 0\n  var i = 0\n  while i < n do\n" +
        "    acc = acc + Counter.tick()\n    i = i + 1\n  acc\n" +
        s"val base = List($a, $b, $c).sum\n" +
        s"val r = handle(loop($n)) {\n  case Counter.tick(resume) => resume($k)\n}\n" +
        "println(r * 2 + base)\n"
    case 9 => // COMPOSITION: TWO handle results combined in a binop — `val r1 = handle(...); val r2 = handle(...); println(r1 + r2)`.
      // Both operands are Any-typed `_handle` results; `r1 + r2` must route through `_binOp`.
      val n = 1 + rng.nextInt(5); val k = 1 + rng.nextInt(4)
      val n2 = 1 + rng.nextInt(5); val k2 = 1 + rng.nextInt(4)
      "effect Counter:\n  def tick(): Int\n" +
        "def loop(n: Int): Int ! Counter =\n  var acc = 0\n  var i = 0\n  while i < n do\n" +
        "    acc = acc + Counter.tick()\n    i = i + 1\n  acc\n" +
        s"val r1 = handle(loop($n)) {\n  case Counter.tick(resume) => resume($k)\n}\n" +
        s"val r2 = handle(loop($n2)) {\n  case Counter.tick(resume) => resume($k2)\n}\n" +
        "println(r1 + r2)\n"
    case 7 => // RETURN-CLAUSE deep handler: `case Log.emit(resume) => v :: resume(())` + `case Return(_)
      // => List()` accumulates a List, printed via a 0-arg collection method (routes through _anyCall0).
      val n = 1 + rng.nextInt(5); val v = 1 + rng.nextInt(5)
      val agg = Vector("length", "sum")(rng.nextInt(2))
      "effect Log:\n  def emit(): Int\n" +
        "def loop(n: Int): Int ! Log =\n  var i = 0\n  while i < n do\n    Log.emit()\n    i = i + 1\n  0\n" +
        s"val xs = handle(loop($n)) {\n  case Log.emit(resume) => $v :: resume(())\n  case Return(_) => List()\n}\n" +
        s"println(xs.$agg)\n"
    case 6 => // MULTI-SHOT nondeterminism: `val all = handle(prog()){…}; println(all.sum)` — regression
      // guard for jvmgen-multishot-handle-result-any (a 0-arg collection method on the Any-typed
      // `handle(...)` result now routes through `_anyCall0`). Result = sum over all path values.
      val a = 1 + rng.nextInt(3); val b = 1 + rng.nextInt(3)
      val agg = Vector("sum", "max", "min", "length")(rng.nextInt(4))
      "multi effect NonDet:\n  def choose(opts: List[Int]): Int\n" +
        "def prog(): Int ! NonDet =\n" +
        s"  val x = NonDet.choose(List($a, ${a + 1}))\n  val y = NonDet.choose(List($b, ${b + 5}))\n  x + y\n" +
        "val all = handle(prog()) {\n  case NonDet.choose(opts, resume) => opts.flatMap(o => resume(o))\n}\n" +
        s"println(all.$agg)\n"
    case 4 => // conditional resume: `if k > t then resume(k) else resume(0)` — comparison on an
      // Any-typed handler op-arg inside an `if` condition (stresses emitCaseBody's control-flow path)
      val n = 2 + rng.nextInt(5); val t = rng.nextInt(3)
      "effect Reader:\n  def ask(k: Int): Int\n" +
        "def loop(n: Int): Int ! Reader =\n  var acc = 0\n  var i = 0\n  while i < n do\n" +
        "    acc = acc + Reader.ask(i)\n    i = i + 1\n  acc\n" +
        s"println(handle(loop($n)) {\n  case Reader.ask(k, resume) => if k > $t then resume(k) else resume(0)\n})\n"
    case 5 => // MATCH in handler body, with arithmetic in an arm (`k match { case 0 => …; case _ => resume(k * m) }`)
      val n = 2 + rng.nextInt(5); val m = 1 + rng.nextInt(4)
      "effect Reader:\n  def ask(k: Int): Int\n" +
        "def loop(n: Int): Int ! Reader =\n  var acc = 0\n  var i = 0\n  while i < n do\n" +
        "    acc = acc + Reader.ask(i)\n    i = i + 1\n  acc\n" +
        s"println(handle(loop($n)) {\n  case Reader.ask(k, resume) => k match\n    case 0 => resume(0)\n    case _ => resume(k * $m)\n})\n"
    case 0 => // one-shot: result n*k
      val n = 1 + rng.nextInt(6); val k = 1 + rng.nextInt(4)
      "effect Counter:\n  def tick(): Int\n" +
        "def loop(n: Int): Int ! Counter =\n  var acc = 0\n  var i = 0\n  while i < n do\n" +
        "    acc = acc + Counter.tick()\n    i = i + 1\n  acc\n" +
        s"println(handle(loop($n)) {\n  case Counter.tick(resume) => resume($k)\n})\n"
    case 1 => // arg-carrying: resume(k * mult), result sum_{i<n} i*mult
      val n = 1 + rng.nextInt(6); val m = 1 + rng.nextInt(5)
      "effect Reader:\n  def ask(k: Int): Int\n" +
        "def loop(n: Int): Int ! Reader =\n  var acc = 0\n  var i = 0\n  while i < n do\n" +
        "    acc = acc + Reader.ask(i)\n    i = i + 1\n  acc\n" +
        s"println(handle(loop($n)) {\n  case Reader.ask(k, resume) => resume(k * $m)\n})\n"
    case 2 => // two-op-arg effect: Combine.mix(a, b) → a*b + c
      val n = 1 + rng.nextInt(6); val c = rng.nextInt(4)
      "effect Combine:\n  def mix(a: Int, b: Int): Int\n" +
        "def loop(n: Int): Int ! Combine =\n  var acc = 0\n  var i = 0\n  while i < n do\n" +
        "    acc = acc + Combine.mix(i, i + 1)\n    i = i + 1\n  acc\n" +
        s"println(handle(loop($n)) {\n  case Combine.mix(a, b, resume) => resume(a * b + $c)\n})\n"
    case _ => // BLOCK handler body: `{ val r = resume(k); r }` — exercises emitCaseBody's Block + val.
      val n = 1 + rng.nextInt(6); val k = 1 + rng.nextInt(4)
      "effect Counter:\n  def tick(): Int\n" +
        "def loop(n: Int): Int ! Counter =\n  var acc = 0\n  var i = 0\n  while i < n do\n" +
        "    acc = acc + Counter.tick()\n    i = i + 1\n  acc\n" +
        s"println(handle(loop($n)) {\n  case Counter.tick(resume) =>\n    val r = resume($k)\n    r\n})\n"

  // Effect-result × main-path COMPOSITIONS: an Any-typed `handle(...)` result used in a non-arithmetic
  // main-path context. Found a cluster of JVM-only bugs (interp + JS ran them), all fixed
  // (jvmgen-handle-result-mainpath): result fed into a `match` arm, an `if`/comparison condition, a
  // function-call arg, a fresh `List(r, r).sum`, and a tuple-accessor `t._1 + t._2` (the last two via
  // Any-taint propagation: a val bound to an Any-derived expression is itself Any-typed), plus
  // multi-shot `.sum` arithmetic and nested handles.
  test("effect-result main-path composition cross-backend"):
    assume(has("node") && has("scala-cli"), "node/scala-cli not available")
    val counter = "effect Counter:\n  def tick(): Int\n" +
      "def loop(n: Int): Int ! Counter =\n  var acc = 0\n  var i = 0\n  while i < n do\n" +
      "    acc = acc + Counter.tick()\n    i = i + 1\n  acc\n"
    val oneShot = "val r = handle(loop(3)) { case Counter.tick(resume) => resume(2) }\n"  // r = 6
    val shapes = Seq(
      "result-into-match" -> (counter + oneShot + "println(r match { case 0 => 100\n  case _ => r * 2 })\n"),
      "result-in-if-cmp"  -> (counter + oneShot + "println(if r > 5 then r * 10 else 0)\n"),
      "result-fn-arg"     -> (counter + oneShot + "def dbl(x: Int): Int = x * 2\nprintln(dbl(r) + 1)\n"),
      "result-in-list-sum"-> (counter + oneShot + "println(List(r, r, r).sum)\n"),
      "result-in-tuple"   -> (counter + oneShot + "val t = (r, r + 1)\nprintln(t._1 + t._2)\n"),
      "multishot-arith"   -> ("multi effect NonDet:\n  def choose(opts: List[Int]): Int\n" +
        "def prog(): Int ! NonDet =\n  val x = NonDet.choose(List(1, 2))\n  x\n" +
        "val all = handle(prog()) { case NonDet.choose(opts, resume) => opts.flatMap(o => resume(o)) }\n" +
        "println(all.sum * 2 + 1)\n"),
      "nested-handles"    -> ("effect Counter:\n  def tick(): Int\neffect Reader:\n  def ask(): Int\n" +
        "def prog(): Int ! Counter =\n  Counter.tick() + Reader.ask()\n" +
        "val r = handle(handle(prog()) { case Reader.ask(resume) => resume(10) }) { case Counter.tick(resume) => resume(5) }\n" +
        "println(r)\n"),
    )
    for (label, prog) <- shapes do
      val m   = module(prog)
      val exp = interp(m)
      assert(runJs(m)  == exp, s"JS diverged on '$label': interp=[$exp] js=[${runJs(m)}]")
      assert(runJvm(m) == exp, s"JVM diverged on '$label': interp=[$exp] jvm=[${runJvm(m)}]")

  // Partial application of a CURRIED def (`def add(a)(b); val f = add(3); f(4)`). JsGen flattens curried
  // params to `function add(a, b)`, so an under-applied JS call produced NaN (`3 + undefined`); interp +
  // JVM handled it. Fixed by auto-currying multi-clause defs (the `_curry` runtime helper + a guard).
  // (jvmgen-js-curried-partial.)
  test("curried partial application cross-backend"):
    assume(has("node") && has("scala-cli"), "node/scala-cli not available")
    val shapes = Seq(
      "2clause-full"    -> "def add(a: Int)(b: Int): Int = a + b\nprintln(add(1)(2))\n",
      "2clause-partial" -> "def add(a: Int)(b: Int): Int = a + b\nval f = add(3)\nprintln(f(4) + add(1)(2))\n",
      "3clause-full"    -> "def add3(a: Int)(b: Int)(c: Int): Int = a + b + c\nprintln(add3(1)(2)(3))\n",
      "3clause-partial" -> "def add3(a: Int)(b: Int)(c: Int): Int = a + b + c\nval g = add3(1)(2)\nprintln(g(3) + add3(4)(5)(6))\n",
    )
    for (label, prog) <- shapes do
      val m   = module(prog)
      val exp = interp(m)
      assert(runJs(m)  == exp, s"JS diverged on '$label': interp=[$exp] js=[${runJs(m)}]")
      assert(runJvm(m) == exp, s"JVM diverged on '$label': interp=[$exp] jvm=[${runJvm(m)}]")

  // Effects in HOF/closure positions + non-effect main-path edge cases. `perform` inside `.map` /
  // `.foldLeft` closures threads correctly; closures-returning-closures, nested for-yield, recursion,
  // and string interpolation all agree cross-backend. (NOTE: `perform` inside a `for … do` loop is a
  // known open divergence — interp-vs-js-vs-jvm — tracked separately as effect-perform-in-fordo.)
  test("effects-in-hof and main-path edge cases cross-backend"):
    assume(has("node") && has("scala-cli"), "node/scala-cli not available")
    val shapes = Seq(
      "perform-in-map" -> ("effect Reader:\n  def ask(k: Int): Int\n" +
        "def prog(): Int ! Reader =\n  List(1, 2, 3).map(x => x + Reader.ask(x)).sum\n" +
        "println(handle(prog()) { case Reader.ask(k, resume) => resume(k * 10) })\n"),
      "perform-in-foldleft" -> ("effect Reader:\n  def ask(k: Int): Int\n" +
        "def prog(): Int ! Reader =\n  List(1, 2, 3).foldLeft(0)((a, x) => a + Reader.ask(x))\n" +
        "println(handle(prog()) { case Reader.ask(k, resume) => resume(k * 2) })\n"),
      "closure-ret-closure" -> "def mk(n: Int): Int => Int = (x: Int) => x + n\nval f = mk(10)\nprintln(f(5) + mk(100)(1))\n",
      "for-yield-nested" -> "val xs = for\n  x <- List(1, 2, 3)\n  y <- List(10, 20)\nyield x * y\nprintln(xs.sum)\n",
      "recursion-fib" -> "def fib(n: Int): Int =\n  if n < 2 then n else fib(n - 1) + fib(n - 2)\nprintln(fib(10))\n",
      "string-interp" -> "val n = 5\nprintln(s\"n=$n sq=${n * n}\")\n",
    )
    for (label, prog) <- shapes do
      val m   = module(prog)
      val exp = interp(m)
      assert(runJs(m)  == exp, s"JS diverged on '$label': interp=[$exp] js=[${runJs(m)}]")
      assert(runJvm(m) == exp, s"JVM diverged on '$label': interp=[$exp] jvm=[${runJvm(m)}]")

  private def module(program: String) = Parser.parse(s"# Gen\n\n```scalascript\n$program\n```\n")

  private def interp(m: scalascript.ast.Module): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(m); ps.flush(); buf.toString.trim

  private def has(cmd: String): Boolean = ProcTestUtil.commandOk(cmd)
  private def runProc(cmd: String*): String =
    val p   = ProcessBuilder(cmd*).start()
    val out = Source.fromInputStream(p.getInputStream).mkString
    val err = Source.fromInputStream(p.getErrorStream).mkString
    if ProcTestUtil.awaitExit(p) != 0 then fail(s"${cmd.head} failed:\n$err")
    out.trim

  private def runJs(m: scalascript.ast.Module): String =
    val tmp = java.io.File.createTempFile("ssc-prop-", ".cjs"); tmp.deleteOnExit()
    val rt  = JsGen.generateRuntime(JsGen.Capability.all)
    java.nio.file.Files.write(tmp.toPath, (rt + "\n" + JsGen.generate(m)).getBytes(StandardCharsets.UTF_8))
    runProc("node", tmp.getAbsolutePath)

  private def runJvm(m: scalascript.ast.Module): String =
    val tmp = java.io.File.createTempFile("ssc-prop-", ".sc"); tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath,
      ("//> using scala 3.8.3\n" + JvmGen.generate(m)).getBytes(StandardCharsets.UTF_8))
    runProc("scala-cli", "run", tmp.getAbsolutePath)

  /** Compare the interp output unless it is a numeric value OUTSIDE all backends' safe int range
   *  (then skip — overflow is a documented platform difference). Non-numeric output (strings) is
   *  always compared. */
  private def comparable(s: String): Boolean =
    s.toLongOption match
      case Some(v) => math.abs(v) < 100000000L
      case None    => true

  test("generated programs (12 kinds incl. effects + main-path): interp == JS(node) over 72 seeds"):
    assume(has("node"), "node not available")
    var checked = 0; var skipped = 0
    // 0..71 covers all 12 kinds; +155/191 reach effect sub-shape 9 (two-handle-result binop composition).
    for seed <- (0 until 72) ++ Seq(155, 191) do
      val prog = genProgram(seed)
      val m    = module(prog)
      val exp  = try interp(m) catch case _: Throwable => null
      if exp == null || !comparable(exp) then skipped += 1
      else
        val js = runJs(m)
        assert(js == exp,
          s"\nJS diverged from interp on seed $seed:\n--- program ---\n$prog--- interp: [$exp] | js: [$js] ---")
        checked += 1
    info(s"interp==JS: checked $checked generated programs, skipped $skipped (out-of-range/interp-error)")
    assert(checked >= 30, s"too few programs exercised ($checked) — generator may be degenerate")

  test("generated programs (all 12 kinds + effect shapes): interp == JVM(scala-cli)"):
    assume(has("scala-cli"), "scala-cli not available")
    var checked = 0
    // seeds 0..11 = one program of each kind (incl. closures/nested-coll/strings main-path + effects);
    // 23/35/47/59/71 + 155/191 are also the effect kind (seed%12==11) so every effect SHAPE (one-shot /
    // arg-carrying / two-op / multi-shot / return-clause / handle-result composition) gets a JVM scala-cli
    // run — the regression check that jvmgen-handle-in-arg-position & jvmgen-multishot-handle-result-any
    // stay fixed, and that handle-result arithmetic (r*2+base, r1+r2) routes through _binOp.
    for seed <- (0 until Kinds) ++ List(23, 35, 47, 59, 71, 155, 191) do
      val prog = genProgram(seed)
      val m    = module(prog)
      val exp  = try interp(m) catch case _: Throwable => null
      if exp != null && comparable(exp) then
        assert(runJvm(m) == exp, s"\nJVM diverged from interp on seed=$seed:\n$prog--- interp: [$exp] ---")
        checked += 1
    info(s"interp==JVM: checked $checked generated programs (all kinds + effect shapes)")

  // Deterministic guard for return-clause handlers (`case Eff.op(resume) => v :: resume(()); case
  // Return(_) => List()`) over different control-flow shapes of the handled program. Found two bugs:
  //   - jvmgen-returnclause-effect-in-recursion: a recursive effectful `go(n-1)` failed JVM scala-cli
  //     (Any-bound `_t3` passed to an `Int` param) — fixed via localDefSigs callee-cast.
  //   - interp-returnclause-effect-in-while: an effect performed in a `while` loop threw in interp
  //     (fast-while ran the perform eagerly) — fixed by bailing the fast-while to the trampoline when
  //     the body performs an effect op with no active resolver.
  // Each shape emits `xs.length` (= number of emits). interp/JS are baselines; JVM via scala-cli.
  test("effect return-clause cross-backend (direct / recursion / while)"):
    assume(has("node") && has("scala-cli"), "node/scala-cli not available")
    val effDecl = "effect Log:\n  def emit(): Int\n"
    val handler = "val xs = handle(prog()) {\n  case Log.emit(resume) => 7 :: resume(())\n  case Return(_) => List()\n}\nprintln(xs.length)\n"
    val shapes = Seq(
      "direct-single" -> "def prog(): Int ! Log =\n  Log.emit()\n  0\n",
      "direct-two"    -> "def prog(): Int ! Log =\n  Log.emit()\n  Log.emit()\n  0\n",
      "recursion"     -> "def go(n: Int): Int ! Log =\n  if n <= 0 then 0\n  else\n    Log.emit()\n    go(n - 1)\ndef prog(): Int ! Log =\n  go(3)\n",
      "while-loop"    -> "def prog(): Int ! Log =\n  var i = 0\n  while i < 3 do\n    Log.emit()\n    i = i + 1\n  0\n",
    )
    for (label, progDecl) <- shapes do
      val m   = module(effDecl + progDecl + handler)
      val exp = interp(m)
      assert(runJs(m)  == exp, s"JS diverged on '$label': interp=[$exp] js=[${runJs(m)}]")
      assert(runJvm(m) == exp, s"JVM diverged on '$label': interp=[$exp] jvm=[${runJvm(m)}]")
