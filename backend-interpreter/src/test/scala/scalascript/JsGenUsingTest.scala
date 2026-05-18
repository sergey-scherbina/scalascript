package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JsRuntime, JsRuntimeAsync, JvmGen}
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** JsGen tests for v1.13 Phase 2 — `using` auto-resolution and context-bound dispatch. */
class JsGenUsingTest extends AnyFunSuite with Matchers:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def hasNode: Boolean =
    try ProcessBuilder("node", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def runJs(code: String): String =
    val flush = """process.stdout.write(_output.join('\n') + (_output.length ? '\n' : '')); _output = [];"""
    val js    = JsRuntime + "\n" + JsRuntimeAsync + "\n" + JsGen.generate(module(code)) + "\n" + flush
    val tmp   = java.io.File.createTempFile("ssc-using-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc  = ProcessBuilder("node", tmp.getAbsolutePath)
      .redirectErrorStream(true)
      .start()
    val out   = Source.fromInputStream(proc.getInputStream).mkString
    proc.waitFor()
    out.trim

  // ── explicit using (already worked before v1.13) ─────────────────────

  test("JsGen: explicit using — single given resolved"):
    assume(hasNode, "node not available")
    runJs("""
      trait Show[A]:
        def show(a: A): String
      given showInt: Show[Int] with
        def show(a: Int): String = "int:" + a
      def display(x: Int)(using s: Show[Int]): String = s.show(x)
      println(display(42)(using showInt))
    """) shouldBe "int:42"

  // ── auto-resolution of using params ──────────────────────────────────

  test("JsGen: auto-resolve using — given picked from registry"):
    assume(hasNode, "node not available")
    runJs("""
      trait Show[A]:
        def show(a: A): String
      given showInt: Show[Int] with
        def show(a: Int): String = "int:" + a
      def display[A](x: A)(using s: Show[A]): String = s.show(x)
      println(display(42)(using showInt))
    """) shouldBe "int:42"

  // ── context bounds ────────────────────────────────────────────────────

  test("JsGen: context bound [A: Show] auto-resolves given"):
    assume(hasNode, "node not available")
    runJs("""
      trait Show[A]:
        def show(a: A): String
      given showStr: Show[String] with
        def show(a: String): String = "[" + a + "]"
      def display[A: Show](x: A): String = summon[Show[A]].show(x)
      println(display("hello"))
    """) shouldBe "[hello]"

  test("JsGen: context bound Monoid — combineAll folds with given"):
    assume(hasNode, "node not available")
    runJs("""
      trait Monoid[A]:
        def empty: A
        def combine(a: A, b: A): A
      given intMonoid: Monoid[Int] with
        def empty: Int = 0
        def combine(a: Int, b: Int): Int = a + b
      def combineAll[A: Monoid](xs: List[A]): A =
        xs.foldLeft(summon[Monoid[A]].empty)((acc, x) => summon[Monoid[A]].combine(acc, x))
      println(combineAll(List(1, 2, 3, 4)))
    """) shouldBe "10"

  test("JsGen: summon — retrieves given by type"):
    assume(hasNode, "node not available")
    runJs("""
      trait Printer[A]:
        def print(a: A): String
      given intPrinter: Printer[Int] with
        def print(a: Int): String = "<<" + a + ">>"
      println(summon[Printer[Int]].print(99))
    """) shouldBe "<<99>>"

  // ── HKT Functor — typeclass-extension conformance ────────────────────

  test("JsGen: Functor[List] fmap via given extension"):
    assume(hasNode, "node not available")
    runJs("""
      trait Functor[F[_]]:
        extension [A](fa: F[A]) def fmap[B](f: A => B): F[B]
      given listFunctor: Functor[List] with
        extension [A](fa: List[A]) def fmap[B](f: A => B): List[B] = fa.map(f)
      println(List(1, 2, 3).fmap(_ * 2))
    """) shouldBe "List(2, 4, 6)"

  // ── JvmGen code-shape tests ──────────────────────────────────────────

  private def jvmCode(code: String): String =
    JvmGen.generate(module(code))

  test("JvmGen: using param preserved in generated code"):
    val code = jvmCode("""
      trait Show[A]:
        def show(a: A): String
      given showInt: Show[Int] with
        def show(a: Int): String = "int:" + a
      def display[A](x: A)(using s: Show[A]): String = s.show(x)
      println(display(42)(using showInt))
    """)
    code should include ("using")
    code should include ("Show")

  test("JvmGen: context bound [A: Show] emitted verbatim"):
    val code = jvmCode("""
      trait Show[A]:
        def show(a: A): String
      given showStr: Show[String] with
        def show(a: String): String = "[" + a + "]"
      def display[A: Show](x: A): String = summon[Show[A]].show(x)
      println(display("hi"))
    """)
    code should include (": Show")
    code should include ("summon")

  test("JvmGen: cross-file trait import uses Scala import, not val alias"):
    val dir = os.temp.dir(deleteOnExit = true)
    try
      os.write(dir / "functor.ssc", """|---
          |name: functor
          |package: mylib.functor
          |---
          |# Functor
          |```scalascript
          |trait Functor[F[_]]:
          |  extension [A](fa: F[A]) def map[B](f: A => B): F[B]
          |```
          |""".stripMargin)
      val consumerSrc = """|# Consumer
          |
          |[Functor](functor.ssc)
          |
          |```scalascript
          |trait Traversable[T[_]] extends Functor[T]
          |```
          |""".stripMargin
      val m    = Parser.parse(consumerSrc)
      val code = JvmGen.generate(m, baseDir = Some(dir))
      code should include ("import mylib.functor")
      code should not include "val Functor ="
    finally os.remove.all(dir)
