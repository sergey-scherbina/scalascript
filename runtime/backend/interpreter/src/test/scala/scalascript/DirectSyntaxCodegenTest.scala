package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JsRuntime, JsRuntimeAsync}
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** Tests for v1.8 direct[M] { ... } do-notation — JvmGen and JsGen codegen phase.
 *  JvmGen tests check the generated Scala contains the expected flatMap shape.
 *  JsGen tests run the generated JS through node (skipped if node unavailable). */
class DirectSyntaxCodegenTest extends AnyFunSuite with Matchers:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  // ── JvmGen code-shape tests ──────────────────────────────────────────

  private def jvmCode(code: String): String =
    JvmGen.generate(module(code))

  test("JvmGen: direct[Option] simple bind emits flatMap"):
    val code = jvmCode("""
      val result = direct[Option] {
        x = Some(40)
        y = Some(2)
        Some(x + y)
      }
      println(result)
    """)
    code should include ("flatMap")
    code should include ("Some(40)")
    code should include ("Some(2)")

  test("JvmGen: direct[Option] emits nested flatMap chain"):
    val code = jvmCode("""
      val r = direct[Option] {
        a = Some(1)
        b = Some(2)
        Some(a + b)
      }
    """)
    // two monadic binds => at least two flatMap calls
    code.split("flatMap").length - 1 should be >= 2

  test("JvmGen: val _ = expr emits flatMap for bind-and-discard"):
    val code = jvmCode("""
      val r = direct[Option] {
        val _ = Some("effect")
        x = Some(42)
        Some(x)
      }
    """)
    code should include ("flatMap")

  test("JvmGen: var inside direct emits mutable var + flatMap"):
    val code = jvmCode("""
      val r = direct[Option] {
        var counter = 0
        x = Some(10)
        Some(counter + x)
      }
    """)
    code should include ("var counter")
    code should include ("flatMap")

  // ── JsGen run-via-node tests ─────────────────────────────────────────

  private def hasNode: Boolean =
    try ProcessBuilder("node", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def runJs(code: String): String =
    val flush = """process.stdout.write(_output.join('\n') + (_output.length ? '\n' : '')); _output = [];"""
    val js   = JsRuntime + "\n" + JsRuntimeAsync + "\n" + JsGen.generate(module(code)) + "\n" + flush
    val tmp  = java.io.File.createTempFile("ssc-direct-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc = ProcessBuilder("node", tmp.getAbsolutePath)
      .redirectErrorStream(true)
      .start()
    val out  = Source.fromInputStream(proc.getInputStream).mkString
    proc.waitFor()
    out.trim

  test("JsGen: direct[Option] — monadic bind with Some"):
    assume(hasNode, "node not available")
    runJs("""
      val result = direct[Option] {
        x = Some(40)
        y = Some(2)
        Some(x + y)
      }
      println(result)
    """) shouldBe "Some(42)"

  test("JsGen: direct[Option] — short-circuits on None"):
    assume(hasNode, "node not available")
    runJs("""
      val result = direct[Option] {
        x = Some(1)
        y = None
        Some(x + y)
      }
      println(result)
    """) shouldBe "None"

  test("JsGen: direct[List] — Cartesian product"):
    assume(hasNode, "node not available")
    runJs("""
      val result = direct[List] {
        x = List(1, 2)
        y = List(10, 20)
        List(x + y)
      }
      println(result)
    """) shouldBe "List(11, 21, 12, 22)"

  test("JsGen: val binding inside direct block is pure (no bind)"):
    assume(hasNode, "node not available")
    runJs("""
      val result = direct[Option] {
        x = Some(10)
        val doubled = x * 2
        Some(doubled)
      }
      println(result)
    """) shouldBe "Some(20)"

  test("JsGen: val _ = expr performs bind-and-discard"):
    assume(hasNode, "node not available")
    runJs("""
      val result = direct[Option] {
        val _ = Some("logged")
        x = Some(42)
        Some(x)
      }
      println(result)
    """) shouldBe "Some(42)"

  test("JsGen: var inside direct block is mutable"):
    assume(hasNode, "node not available")
    runJs("""
      val result = direct[Option] {
        var counter = 0
        x = Some(10)
        counter = counter + x
        Some(counter)
      }
      println(result)
    """) shouldBe "Some(10)"

  test("JsGen: nested direct blocks are independent"):
    assume(hasNode, "node not available")
    runJs("""
      val inner = direct[Option] {
        a = Some(3)
        Some(a + 1)
      }
      val outer = direct[Option] {
        x = inner
        Some(x * 10)
      }
      println(outer)
    """) shouldBe "Some(40)"
