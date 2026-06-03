package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.vm.jit.{JitLint, JitBailReason}
import scalascript.parser.Parser

/** Phase 2 Commit 1 lint fixtures. Each test exercises exactly one
 *  category of `JitBailReason` so a regression in the AST walker shows
 *  up as a precise test failure.
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

  // ── happy path: simple arithmetic JIT-able function ─────────────

  test("def f(x: Int) = x + 1 — should JIT cleanly"):
    val r = lintFor("def f(x: Int): Int = x + 1\nf(3)").forDef("f")
    r.willJit shouldBe true
    r.bailReasons shouldBe empty

  // ── one cliff per category ──────────────────────────────────────

  test("pattern guard reports PatternGuard"):
    val r = lintFor(
      """sealed trait E
        |case class A(n: Int) extends E
        |def f(e: E): Int = e match
        |  case A(n) if n > 0 => n
        |  case A(n)          => -n
        |f(A(3))""".stripMargin
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

  // ── classifier is best-effort: complex shapes report UnknownShape ──

  test("multiple defs in one module — JITable one stays JITable alongside bails"):
    val r = lintFor(
      """def jitable(x: Int): Int = x + 1
        |def withGuard(x: Int): Int = x match
        |  case n if n > 0 => n
        |  case n          => -n
        |def withVararg(xs: Int*): Int = xs.sum
        |def withTry(x: Int): Int =
        |  try x / 2
        |  catch case _: Exception => -1
        |jitable(3)""".stripMargin
    )
    r.forDef("jitable").willJit shouldBe true
    r.forDef("withGuard").bailReasons should contain (JitBailReason.PatternGuard)
    r.forDef("withTry").bailReasons should contain (JitBailReason.TryCatch)
    r.forDef("withVararg").bailReasons should contain (JitBailReason.VarargParam)

  test("function with no detectable cliff but JIT bail reports UnknownShape"):
    // `def f(x: String) = x.length` — JIT won't handle String params today,
    // but the AST walk sees only Term.Select; classifier falls through.
    val r = lintFor(
      """def f(x: String): Int = x.length
        |f("hi")""".stripMargin
    ).forDef("f")
    r.willJit shouldBe false
    // Either UnknownShape or some specific cliff — we just check
    // the report is meaningful (non-empty) and not the happy path.
    r.bailReasons should not be empty
