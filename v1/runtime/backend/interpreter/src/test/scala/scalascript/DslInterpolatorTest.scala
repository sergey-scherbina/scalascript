package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JsRuntime}
import scalascript.codegen.JvmGen
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** v1.20 Phase 1 — user-defined string interpolators on all three backends. */
class DslInterpolatorTest extends AnyFunSuite with Matchers:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  // ── Interpreter ──────────────────────────────────────────────────────────

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(module(code))
    ps.flush()
    buf.toString.trim

  test("Interpreter: user-defined interpolator is called with StringContext + args"):
    captured("""
      extension (sc: StringContext) def sql(args: Any*): String =
        sc.parts(0) + args(0) + sc.parts(1) + args(1) + sc.parts(2)
      val name  = "users"
      val limit = 10
      println(sql"SELECT * FROM $name LIMIT $limit")
    """) shouldBe "SELECT * FROM users LIMIT 10"

  test("Interpreter: zero-arg user-defined interpolator (no splices)"):
    captured("""
      extension (sc: StringContext) def greet(args: Any*): String =
        sc.parts(0)
      println(greet"hello world")
    """) shouldBe "hello world"

  test("Interpreter: interpolator accesses parts and args by index"):
    captured("""
      extension (sc: StringContext) def fmt(args: Any*): String =
        sc.parts(0) + args(0) + sc.parts(1) + args(1) + sc.parts(2)
      println(fmt"a=${"X"} b=${"Y"} done")
    """) shouldBe "a=X b=Y done"

  // ── JsGen (via node) ─────────────────────────────────────────────────────

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runJs(code: String): String =
    val flush = """process.stdout.write(_output.join('\n') + (_output.length ? '\n' : '')); _output = [];"""
    val js    = JsRuntime + "\n" + JsGen.generate(module(code)) + "\n" + flush
    val tmp   = java.io.File.createTempFile("ssc-dsl-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc  = ProcessBuilder("node", tmp.getAbsolutePath)
      .redirectErrorStream(true)
      .start()
    val out = Source.fromInputStream(proc.getInputStream).mkString
    ProcTestUtil.awaitExit(proc)
    out.trim

  test("JsGen: user-defined interpolator emits _ext_StringContext_prefix(_sc([...]), ...) call"):
    val js = JsGen.generate(module("""
      extension (sc: StringContext) def sql(args: Any*): String = "x"
      val t = "users"
      println(sql"SELECT $t")
    """))
    js should include ("_sc(")
    js should include ("_ext_StringContext_sql(")

  test("JsGen: user-defined interpolator runs correctly via node"):
    assume(hasNode, "node not available")
    runJs("""
      extension (sc: StringContext) def sql(args: Any*): String =
        val parts = sc.parts
        var r = ""
        var i = 0
        while i < parts.length do
          r = r + parts(i)
          if i < args.length then r = r + args(i)
          i = i + 1
        r
      val tbl = "orders"
      println(sql"SELECT * FROM $tbl WHERE id > ${42}")
    """) shouldBe "SELECT * FROM orders WHERE id > 42"

  test("JsGen: zero-arg interpolator via node"):
    assume(hasNode, "node not available")
    runJs("""
      extension (sc: StringContext) def tag(args: Any*): String = "<" + sc.parts(0) + ">"
      println(tag"div")
    """) shouldBe "<div>"

  // ── JvmGen code-shape ────────────────────────────────────────────────────

  private def jvmCode(code: String): String = JvmGen.generate(module(code))

  test("JvmGen: user-defined interpolator — passes through as Scala 3 native interpolation"):
    val code = jvmCode("""
      extension (sc: StringContext) def sql(args: Any*): String = "x"
      val t = "users"
      val q = sql"SELECT $t"
      println(q)
    """)
    // JvmGen emits the extension method def and lets Scala 3 desugar the interpolation natively.
    code should include ("def sql(")
    // The interpolation is preserved verbatim (Scala 3 desugars it at compile time).
    code should include ("sql\"")
