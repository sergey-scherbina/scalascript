package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.JsGen
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets

/** arch-meta-v2 macro-codegen-backends (cross-module, JS) — a macro DEFINED in
 *  an imported module and CALLED from a consumer must produce the same output on
 *  the JS backend as on the interpreter. The JS path uses the entry-hook
 *  (`MacroCodegen.expand(module, baseDir)`) to seed the call table from the
 *  consumer's local `.ssc` imports + `genImport` strips the imported macro defs.
 *  Companion of `QuotedMacroCrossModuleJvmTest`. */
class QuotedMacroCrossModuleJsTest extends AnyFunSuite with Matchers:

  private val libSrc =
    """# Lib
      |
      |```scalascript
      |inline def label(x: Int): String = ${ labelImpl('x) }
      |def labelImpl(x: Expr[Int])(using q: QuotedContext): Expr[String] =
      |  x.asValue match
      |    case Some(n) => Expr("literal: " + n.toString)
      |    case None    => '{ "dynamic: " + $x.toString }
      |```
      |""".stripMargin

  private def consumerSrc(libName: String) =
    s"""# Consumer
       |
       |[label, labelImpl]($libName)
       |
       |```scalascript
       |println(label(7))
       |```
       |""".stripMargin

  private def fixture(): (os.Path, os.Path) =
    val dir = os.temp.dir(prefix = "ssc-xmacro-js-")
    os.write(dir / "lib.ssc", libSrc)
    val consumer = dir / "consumer.ssc"
    os.write(consumer, consumerSrc("lib.ssc"))
    (consumer, dir)

  private def has(cmd: String): Boolean = ProcTestUtil.commandOk(cmd)
  private def runProc(cmd: String*): String =
    ProcTestUtil.runOrThrow(cmd*)

  test("interpreter: cross-module macro call resolves the imported macro"):
    val (consumer, dir) = fixture()
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps, baseDir = Some(dir)).run(Parser.parse(os.read(consumer)))
    ps.flush()
    buf.toString.trim shouldBe "literal: 7"

  test("JS: cross-module macro is expanded + stripped (matches interpreter)"):
    assume(has("node"), "node not available")
    val (consumer, dir) = fixture()
    val module    = Parser.parse(os.read(consumer))
    val generated = JsGen.generate(module, baseDir = Some(dir))
    withClue("generated JS must not leak macro constructs:\n") {
      generated should not include "__ssc_macro__"
      generated should not include "QuotedContext"
    }
    val rt  = JsGen.generateRuntime(JsGen.Capability.all)
    val tmp = java.io.File.createTempFile("ssc-xmacro-js-", ".js"); tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, (rt + "\n" + generated).getBytes(StandardCharsets.UTF_8))
    runProc("node", tmp.getAbsolutePath) shouldBe "literal: 7"
