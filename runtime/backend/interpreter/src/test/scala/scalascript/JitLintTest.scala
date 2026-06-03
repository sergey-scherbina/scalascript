package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.vm.jit.{JitLint, JitBailReason}
import scalascript.parser.Parser

/** Lint fixtures. Each test exercises exactly one category of `JitBailReason`
 *  so a regression in the classifier shows up as a precise test failure.
 *
 *  Convention: every fixture defines `def f(...) = ...` so we can look
 *  up the report for "f" without colliding with stdlib intrinsics
 *  (which all start with `_ssc_` or are not top-level FunVs). */
class JitLintTest extends AnyFunSuite with Matchers:

  private def lintFor(code: String): JitLintReportLookup =
    val src = s"# Test\n\n```scalascript\n$code\n```\n"
    val module = Parser.parse(src)
    val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    val interp = Interpreter(devNull)
    interp.runSections(module)
    new JitLintReportLookup(JitLint.lintInterpreter(interp))

  private final class JitLintReportLookup(reports: List[scalascript.interpreter.vm.jit.JitLintReport]):
    def forDef(name: String): scalascript.interpreter.vm.jit.JitLintReport =
      reports.find(_.defName == name).getOrElse(
        fail(s"No report for def '$name'. Available: ${reports.map(_.defName).mkString(", ")}"))

  // ── happy path ────────────────────────────────────────────────────

  test("def f(x: Int) = x + 1 — should JIT cleanly"):
    val r = lintFor("def f(x: Int): Int = x + 1\nf(3)").forDef("f")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("ADT match with guard — should JIT (walkArmAsIfBranch path)"):
    val r = lintFor(
      """sealed trait E
        |case class A(n: Int) extends E
        |def f(e: E): Int = e match
        |  case A(n) if n > 0 => n
        |  case A(n)          => -n
        |f(A(3))""".stripMargin
    ).forDef("f")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  // ── one cliff per category ────────────────────────────────────────

  test("non-extract guard (Int match) reports PatternGuard"):
    val r = lintFor(
      """def f(x: Int): Int = x match
        |  case n if n > 0 => n
        |  case n          => -n
        |f(3)""".stripMargin
    ).forDef("f")
    r.willJit shouldBe false
    r.bailReasons should contain (JitBailReason.PatternGuard)

  test("vararg param reports VarargParam"):
    val r = lintFor(
      """def f(xs: Int*): Int = xs.sum
        |f(1, 2, 3)""".stripMargin
    ).forDef("f")
    r.bailReasons should contain (JitBailReason.VarargParam)

  test("try/catch body reports TryCatch"):
    val r = lintFor(
      """def f(x: Int): Int =
        |  try x + 1
        |  catch case _: Exception => 0
        |f(3)""".stripMargin
    ).forDef("f")
    r.bailReasons should contain (JitBailReason.TryCatch)

  test("bool-returning body reports BoolBody"):
    val r = lintFor(
      """def f(x: Int): Boolean = x > 0
        |f(3)""".stripMargin
    ).forDef("f")
    r.willJit shouldBe false
    r.bailReasons should contain (JitBailReason.BoolBody)

  test("zero-param function reports ZeroParams"):
    val r = lintFor(
      """def f(): Int = 42
        |f()""".stripMargin
    ).forDef("f")
    r.willJit shouldBe false
    r.bailReasons should contain (JitBailReason.ZeroParams)

  test("three-param function reports TooManyParams"):
    val r = lintFor(
      """def f(a: Int, b: Int, c: Int): Int = a + b + c
        |f(1, 2, 3)""".stripMargin
    ).forDef("f")
    r.willJit shouldBe false
    r.bailReasons should contain (JitBailReason.TooManyParams(3))

  // ── multi-def module ──────────────────────────────────────────────

  test("multiple defs — JITable one stays JITable alongside bails"):
    val r = lintFor(
      """def jitable(x: Int): Int = x + 1
        |def withGuard(x: Int): Int = x match
        |  case n if n > 0 => n
        |  case n          => -n
        |def withVararg(xs: Int*): Int = xs.sum
        |def withTry(x: Int): Int =
        |  try x / 2
        |  catch case _: Exception => -1
        |def withBool(x: Int): Boolean = x > 0
        |def withZero(): Int = 0
        |def withThree(a: Int, b: Int, c: Int): Int = a + b + c
        |jitable(3)""".stripMargin
    )
    r.forDef("jitable").willJit shouldBe true
    r.forDef("withGuard").bailReasons should contain (JitBailReason.PatternGuard)
    r.forDef("withTry").bailReasons should contain (JitBailReason.TryCatch)
    r.forDef("withVararg").bailReasons should contain (JitBailReason.VarargParam)
    r.forDef("withBool").bailReasons should contain (JitBailReason.BoolBody)
    r.forDef("withZero").bailReasons should contain (JitBailReason.ZeroParams)
    r.forDef("withThree").bailReasons should contain (JitBailReason.TooManyParams(3))

  // ── recursive ADT eval ─────────────────────────────────────────────

  test("recursive ADT eval ObjToLong — should JIT (arm self-calls)"):
    val code =
      """sealed trait Expr
        |case class Num(n: Int) extends Expr
        |case class Add(l: Expr, r: Expr) extends Expr
        |case class Mul(l: Expr, r: Expr) extends Expr
        |def eval(e: Expr): Int = e match
        |  case Num(n)    => n
        |  case Add(l, r) => eval(l) + eval(r)
        |  case Mul(l, r) => eval(l) * eval(r)
        |eval(Num(1))""".stripMargin
    val r = lintFor(code).forDef("eval")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("recursive ADT eval — JIT direct interface evaluates tree correctly"):
    val src = s"# Test\n\n```scalascript\n${
      """sealed trait Expr
        |case class Num(n: Int) extends Expr
        |case class Add(l: Expr, r: Expr) extends Expr
        |case class Mul(l: Expr, r: Expr) extends Expr
        |def eval(e: Expr): Int = e match
        |  case Num(n)    => n
        |  case Add(l, r) => eval(l) + eval(r)
        |  case Mul(l, r) => eval(l) * eval(r)
        |def build(d: Int): Expr =
        |  if d <= 0 then Num(1)
        |  else Add(build(d - 1), Mul(build(d - 1), Num(2)))
        |val tree = build(3)
        |eval(tree)""".stripMargin}\n```\n"
    val module = scalascript.parser.Parser.parse(src)
    val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    val interp = Interpreter(devNull)
    interp.runSections(module)
    import scalascript.interpreter.Value
    import scalascript.interpreter.vm.jit.{JitBackend, JitGlobals, ObjToLong}
    val evalFun = interp.exportedGlobals("eval")
    val jitR = JitBackend.default.tryCompile(evalFun.asInstanceOf[Value.FunV], interp)
    assert(jitR != null, "eval should JIT-compile")
    assert(jitR.direct != null, "eval JitResult should have a direct interface")
    assert(jitR.direct.isInstanceOf[ObjToLong], s"direct should be ObjToLong, got ${jitR.direct.getClass.getName}")
    val tree = interp.exportedGlobals("tree")
    assert(tree.isInstanceOf[Value.InstanceV], "tree should be an InstanceV")
    val result = JitGlobals.withInterp(interp) {
      jitR.direct.asInstanceOf[ObjToLong].apply(tree.asInstanceOf[AnyRef])
    }
    // build(3) = Add(build(2), Mul(build(2), Num(2))); eval(build(2))=9 → 9 + 9*2 = 27
    assert(result == 27L, s"eval(build(3)) via JIT should be 27, got $result")

  // ── 2-param recursive ADT eval (gEval) ───────────────────────────

  test("gEval (scale: Int, e: Expr) — should JIT (2-param arm self-calls)"):
    val r = lintFor(
      """sealed trait Expr
        |case class Num(n: Int) extends Expr
        |case class Add(l: Expr, r: Expr) extends Expr
        |case class Mul(l: Expr, r: Expr) extends Expr
        |def gEval(scale: Int, e: Expr): Int = e match
        |  case Num(n)    => n * scale
        |  case Add(l, r) => gEval(scale, l) + gEval(scale, r)
        |  case Mul(l, r) => gEval(scale, l) * gEval(scale, r)
        |gEval(2, Num(1))""".stripMargin
    ).forDef("gEval")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  test("gEval — JIT direct interface evaluates correctly"):
    val src = s"# Test\n\n```scalascript\n${
      """sealed trait Expr
        |case class Num(n: Int) extends Expr
        |case class Add(l: Expr, r: Expr) extends Expr
        |case class Mul(l: Expr, r: Expr) extends Expr
        |def gEval(scale: Int, e: Expr): Int = e match
        |  case Num(n)    => n * scale
        |  case Add(l, r) => gEval(scale, l) + gEval(scale, r)
        |  case Mul(l, r) => gEval(scale, l) * gEval(scale, r)
        |val tree = Add(Num(1), Mul(Num(2), Num(3)))
        |gEval(2, tree)""".stripMargin}\n```\n"
    val module = scalascript.parser.Parser.parse(src)
    val devNull = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    val interp = Interpreter(devNull)
    interp.runSections(module)
    import scalascript.interpreter.Value
    import scalascript.interpreter.vm.jit.{JitBackend, JitGlobals}
    import scalascript.interpreter.vm.jit.LongObjToLong
    val gEvalFun = interp.exportedGlobals("gEval")
    val jitR = JitBackend.default.tryCompile(gEvalFun.asInstanceOf[Value.FunV], interp)
    assert(jitR != null, "gEval should JIT-compile")
    assert(jitR.direct != null, "gEval JitResult should have a direct interface")
    assert(jitR.direct.isInstanceOf[LongObjToLong], s"direct should be LongObjToLong, got ${jitR.direct.getClass.getName}")
    val tree = interp.exportedGlobals("tree")
    assert(tree.isInstanceOf[Value.InstanceV], "tree should be an InstanceV")
    // gEval(2, Add(Num(1), Mul(Num(2), Num(3)))) = (1*2) + (2*2)*(3*2) = 2 + 24 = 26
    val result = JitGlobals.withInterp(interp) {
      jitR.direct.asInstanceOf[LongObjToLong].apply(2L, tree.asInstanceOf[AnyRef])
    }
    assert(result == 26L, s"gEval(2, Add(Num(1),Mul(Num(2),Num(3)))) via JIT should be 26, got $result")

  // ── fallback ──────────────────────────────────────────────────────

  test("function with no detectable cliff but JIT bail reports UnknownShape"):
    // `def f(x: String) = x.length` — JIT won't handle String params today,
    // but the AST walk sees only Term.Select; classifier falls through.
    val r = lintFor(
      """def f(x: String): Int = x.length
        |f("hi")""".stripMargin
    ).forDef("f")
    r.willJit shouldBe false
    r.bailReasons should not be empty
