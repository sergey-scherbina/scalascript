package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JvmGen}
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** enum-value-support — enum value references / matching / `.values` must
 *  produce identical output on interpreter, JVM (scala-cli), and JS (node). */
class EnumCrossBackendTest extends AnyFunSuite with Matchers:

  private val program =
    """
      |enum Element:
      |  case Asset, Liability, Equity, Income, Expense
      |enum Side:
      |  case Debit, Credit
      |def normalSide(e: Element): Side = e match
      |  case Asset   => Debit
      |  case Expense => Debit
      |  case _       => Credit
      |def label(s: Side): String = s match
      |  case Debit  => "Dr"
      |  case Credit => "Cr"
      |println(label(normalSide(Asset)))
      |println(label(normalSide(Income)))
      |println(Side.values.length)
      |""".stripMargin

  private def module = Parser.parse(s"# Test\n\n```scalascript\n$program\n```\n")

  private def interp(): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(module); ps.flush(); buf.toString.trim

  private def has(cmd: String): Boolean = ProcTestUtil.commandOk(cmd)
  private def runProc(cmd: String*): String =
    val p = ProcessBuilder(cmd*).start()
    val out = Source.fromInputStream(p.getInputStream).mkString
    val err = Source.fromInputStream(p.getErrorStream).mkString
    if ProcTestUtil.awaitExit(p) != 0 then fail(s"${cmd.head} failed:\n$err"); out.trim

  test("interpreter result is the expected baseline"):
    interp() shouldBe "Dr\nCr\n2"

  test("JVM matches the interpreter"):
    assume(has("scala-cli"), "scala-cli not available")
    val tmp = java.io.File.createTempFile("ssc-enum-", ".sc"); tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath,
      ("//> using scala 3.8.3\n" + JvmGen.generate(module)).getBytes(StandardCharsets.UTF_8))
    runProc("scala-cli", "run", "--server=false", tmp.getAbsolutePath) shouldBe interp()

  test("JS matches the interpreter"):
    assume(has("node"), "node not available")
    val tmp = java.io.File.createTempFile("ssc-enum-", ".cjs"); tmp.deleteOnExit()
    val rt  = JsGen.generateRuntime(JsGen.Capability.all)
    java.nio.file.Files.write(tmp.toPath,
      (rt + "\n" + JsGen.generate(module)).getBytes(StandardCharsets.UTF_8))
    runProc("node", tmp.getAbsolutePath) shouldBe interp()
