package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Conformance tests for v1.15 — checked errors via `throws`. */
class ThrowsTest extends AnyFunSuite with Matchers:

  private val repoRoot = os.pwd / os.up

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  private def capturedWithStd(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src =
      s"""# Test
         |
         |[raise, rethrow, parseInt, parseLong, parseDouble, requireNonNull, divideOrError](std/error-handling.ssc)
         |
         |```scala
         |$code
         |```
         |""".stripMargin
    Interpreter(ps, baseDir = Some(repoRoot)).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── Left / Right constructors ─────────────────────────────────────

  test("Left constructor produces isLeft=true, isRight=false"):
    captured("""
      val e = Left("oops")
      println(e.isLeft)
      println(e.isRight)
    """) shouldBe "true\nfalse"

  test("Right constructor produces isRight=true, isLeft=false"):
    captured("""
      val ok = Right(42)
      println(ok.isRight)
      println(ok.isLeft)
    """) shouldBe "true\nfalse"

  // ── Either methods ────────────────────────────────────────────────

  test("Right.getOrElse returns the value"):
    captured("""
      val r = Right(10)
      println(r.getOrElse(0))
    """) shouldBe "10"

  test("Left.getOrElse returns the default"):
    captured("""
      val l = Left("bad")
      println(l.getOrElse(99))
    """) shouldBe "99"

  test("Right.map transforms the value"):
    captured("""
      val r = Right(5)
      println(r.map((n: Int) => n * 2).getOrElse(0))
    """) shouldBe "10"

  test("Left.map passes through"):
    captured("""
      val l = Left("err")
      println(l.map((n: Int) => n * 2).isLeft)
    """) shouldBe "true"

  test("Right.flatMap chains"):
    captured("""
      val r = Right(3)
      val r2 = r.flatMap((n: Int) => Right(n + 7))
      println(r2.getOrElse(0))
    """) shouldBe "10"

  test("Left.flatMap short-circuits"):
    captured("""
      val l = Left("bail")
      val r2 = l.flatMap((n: Int) => Right(n + 7))
      println(r2.isLeft)
    """) shouldBe "true"

  test("Right.fold applies right function"):
    captured("""
      val r = Right(4)
      println(r.fold((e: Any) => -1, (n: Int) => n * 10))
    """) shouldBe "40"

  test("Left.fold applies left function"):
    captured("""
      val l = Left("bad")
      println(l.fold((e: Any) => e.toString + "!", (n: Int) => n))
    """) shouldBe "bad!"

  test("Right.toOption returns Some"):
    captured("""
      val r = Right(7)
      r.toOption match
        case Some(v) => println(v)
        case None    => println("none")
    """) shouldBe "7"

  test("Left.toOption returns None"):
    captured("""
      val l = Left("err")
      l.toOption match
        case Some(v) => println(v)
        case None    => println("none")
    """) shouldBe "none"

  // ── Auto-Right wrapping (returnsThrows) ───────────────────────────

  test("throws-typed function — bare value auto-wraps in Right"):
    captured("""
      def double(n: Int): Int throws RuntimeException = n * 2
      val r = double(5)
      println(r.isRight)
      println(r.getOrElse(0))
    """) shouldBe "true\n10"

  test("throws-typed function — explicit Left passes through"):
    captured("""
      def safe(n: Int): Int throws String =
        if n < 0 then Left("negative")
        else n * 2
      val ok  = safe(3)
      val bad = safe(-1)
      println(ok.isRight)
      println(bad.isLeft)
    """) shouldBe "true\ntrue"

  test("throws-typed function — explicit Right passes through unchanged"):
    captured("""
      def wrap(n: Int): Int throws String = Right(n + 1)
      val r = wrap(10)
      println(r.getOrElse(0))
    """) shouldBe "11"

  // ── Term.Throw ────────────────────────────────────────────────────

  test("throw RuntimeException propagates as ScriptException"):
    an[Exception] should be thrownBy captured("""
      throw RuntimeException("boom")
    """)

  test("throw custom exception value propagates"):
    an[Exception] should be thrownBy captured("""
      case class AppError(msg: String)
      throw AppError("something wrong")
    """)

  // ── Term.Try ──────────────────────────────────────────────────────

  test("try block without throw returns body value"):
    captured("""
      val r = try 42 catch { case _: Any => -1 }
      println(r)
    """) shouldBe "42"

  test("try-catch catches ScriptException"):
    captured("""
      val r = try
        throw RuntimeException("oops")
        99
      catch
        case e: RuntimeException => -1
      println(r)
    """) shouldBe "-1"

  test("try-catch binds exception value"):
    captured("""
      val r = try
        throw RuntimeException("hello error")
        ""
      catch
        case e: RuntimeException => e.message
      println(r)
    """) shouldBe "hello error"

  test("try-finally runs cleanup"):
    captured("""
      var cleaned = false
      try
        cleaned = true
        42
      finally
        println("finally")
      println(cleaned)
    """) shouldBe "finally\ntrue"

  test("try-catch-finally: catch fires, finally runs"):
    captured("""
      val r = try
        throw RuntimeException("err")
        0
      catch
        case e: RuntimeException => 99
      finally
        println("cleanup")
      println(r)
    """) shouldBe "cleanup\n99"

  // ── attemptCatch ──────────────────────────────────────────────────

  test("attemptCatch wraps success in Right"):
    captured("""
      val r = attemptCatch(() => 42)
      println(r.isRight)
      println(r.getOrElse(0))
    """) shouldBe "true\n42"

  test("attemptCatch wraps exception in Left"):
    captured("""
      val r = attemptCatch(() => throw RuntimeException("bad"))
      println(r.isLeft)
    """) shouldBe "true"

  // ── stdlib shims ──────────────────────────────────────────────────

  test("parseInt success — Right(n)"):
    capturedWithStd("""
      val r = parseInt("42")
      println(r.isRight)
      println(r.getOrElse(0))
    """) shouldBe "true\n42"

  test("parseInt failure — Left"):
    capturedWithStd("""
      val r = parseInt("abc")
      println(r.isLeft)
    """) shouldBe "true"

  test("parseDouble success — Right(d)"):
    capturedWithStd("""
      val r = parseDouble("3.14")
      println(r.isRight)
    """) shouldBe "true"

  test("parseDouble failure — Left"):
    capturedWithStd("""
      val r = parseDouble("bad")
      println(r.isLeft)
    """) shouldBe "true"

  test("requireNonNull on non-null — Right"):
    capturedWithStd("""
      val r = requireNonNull("hello")
      println(r.isRight)
    """) shouldBe "true"

  test("requireNonNull on null — Left"):
    capturedWithStd("""
      val r = requireNonNull(null)
      println(r.isLeft)
    """) shouldBe "true"

  test("divideOrError success — Right(n)"):
    capturedWithStd("""
      val r = divideOrError(10, 2)
      println(r.getOrElse(-1))
    """) shouldBe "5"

  test("divideOrError by zero — Left"):
    capturedWithStd("""
      val r = divideOrError(10, 0)
      println(r.isLeft)
    """) shouldBe "true"

  // ── raise / rethrow ───────────────────────────────────────────────

  test("raise wraps error in Left"):
    capturedWithStd("""
      val r = raise(RuntimeException("bad"))
      println(r.isLeft)
    """) shouldBe "true"

  test("rethrow unwraps Right"):
    capturedWithStd("""
      val v = rethrow(Right(42))
      println(v)
    """) shouldBe "42"

  test("rethrow on Left throws"):
    an[Exception] should be thrownBy capturedWithStd("""
      rethrow(Left(RuntimeException("error")))
    """)
