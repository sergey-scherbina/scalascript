package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.JsGen
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets

/** arch-meta-v2 `macro-codegen-backends` (JS) — restricted quoted macros must
 *  produce the same output on the JS backend (via the `MacroCodegen.expand`
 *  pre-codegen pass) as on the interpreter. Covers an `Expr.asValue match`
 *  const-fold and a direct-quote macro. Companion of the JVM test. */
class QuotedMacroJsConformanceTest extends AnyFunSuite with Matchers:

  private val repoRoot = TestPaths.repoRoot

  private val program =
    """
      |inline def label(x: Int): String = ${ labelImpl('x) }
      |def labelImpl(x: Expr[Int])(using q: QuotedContext): Expr[String] =
      |  x.asValue match
      |    case Some(n) => Expr("literal: " + n.toString)
      |    case None    => '{ "dynamic: " + $x.toString }
      |
      |inline def plusOne(x: Int): Int = ${ plusOneImpl('x) }
      |def plusOneImpl(x: Expr[Int])(using q: QuotedContext): Expr[Int] = '{ $x + 1 }
      |
      |println(label(7))
      |println(plusOne(41))
      |""".stripMargin

  private def module = Parser.parse(s"# Test\n\n```scalascript\n$program\n```\n")

  private def interp(): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(module); ps.flush(); buf.toString.trim

  private def has(cmd: String): Boolean = ProcTestUtil.commandOk(cmd)
  private def runProc(cmd: String*): String =
    ProcTestUtil.runOrThrow(cmd*)

  test("interpreter result is the expected baseline"):
    interp() shouldBe "literal: 7\n42"

  test("JS quoted-macro output matches the interpreter"):
    assume(has("node"), "node not available")
    val generated = JsGen.generate(module, baseDir = Some(repoRoot))
    withClue("generated JS must not leak macro constructs:\n") {
      generated should not include "__ssc_macro__"
      generated should not include "QuotedContext"
    }
    val rt  = JsGen.generateRuntime(JsGen.Capability.all)
    val tmp = java.io.File.createTempFile("ssc-macro-", ".js"); tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, (rt + "\n" + generated).getBytes(StandardCharsets.UTF_8))
    runProc("node", tmp.getAbsolutePath) shouldBe interp()
