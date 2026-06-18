package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.JvmGen
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** arch-meta-v2 macro-codegen-backends (cross-module) — a macro DEFINED in an
 *  imported module and CALLED from a consumer must produce the same output on
 *  the JVM backend as on the interpreter. Exercises the assembled-block
 *  expansion pass (`MacroCodegen.expandUnits`, Approach B): the imported macro
 *  defs are stripped and the consumer's call site is folded after JvmGen inlines
 *  the import. */
class QuotedMacroCrossModuleJvmTest extends AnyFunSuite with Matchers:

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

  /** Writes lib + consumer into a temp dir; returns (consumerPath, baseDir). */
  private def fixture(): (os.Path, os.Path) =
    val dir = os.temp.dir(prefix = "ssc-xmacro-")
    os.write(dir / "lib.ssc", libSrc)
    val consumer = dir / "consumer.ssc"
    os.write(consumer, consumerSrc("lib.ssc"))
    (consumer, dir)

  private def has(cmd: String): Boolean = ProcTestUtil.commandOk(cmd)
  private def runProc(cmd: String*): String =
    val p   = ProcessBuilder(cmd*).start()
    val out = Source.fromInputStream(p.getInputStream).mkString
    val err = Source.fromInputStream(p.getErrorStream).mkString
    if ProcTestUtil.awaitExit(p) != 0 then fail(s"${cmd.head} failed:\n$err"); out.trim

  test("interpreter: cross-module macro call resolves the imported macro"):
    val (consumer, dir) = fixture()
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps, baseDir = Some(dir)).run(Parser.parse(os.read(consumer)))
    ps.flush()
    buf.toString.trim shouldBe "literal: 7"

  test("JVM: cross-module macro is expanded + stripped (matches interpreter)"):
    assume(has("scala-cli"), "scala-cli not available")
    val (consumer, dir) = fixture()
    val module    = Parser.parse(os.read(consumer))
    val generated = JvmGen.generate(module, baseDir = Some(dir))
    withClue("generated JVM must not leak macro constructs:\n") {
      generated should not include "__ssc_macro__"
      generated should not include "QuotedContext"
    }
    val tmp = java.io.File.createTempFile("ssc-xmacro-", ".sc"); tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath,
      ("//> using scala 3.8.3\n" + generated).getBytes(StandardCharsets.UTF_8))
    runProc("scala-cli", "run", "--server=false", tmp.getAbsolutePath) shouldBe "literal: 7"
