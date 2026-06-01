package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JvmGen}
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** exact-numerics v1.64.6 — the std `money.ssc` module must produce identical
 *  output on the interpreter, JVM (scala-cli), and JS (node). */
class MoneyCrossBackendTest extends AnyFunSuite with Matchers:

  private lazy val moneySrc: String =
    os.read(TestPaths.repoRoot / "runtime" / "std" / "money.ssc")

  // A driver exercising allocation, arithmetic, minor units, and formatting.
  private val driver =
    """
      |println(formatMoney(money("1.5", "USD")))
      |println(formatMoney(plus(money("10.00","USD"), money("3.50","USD"))))
      |println(minorUnits(money("12.34","USD")))
      |println(allocate(money("0.05","USD"), List(1,1,1)).map(formatMoney))
      |println(distribute(money("100.00","USD"), 3).map(formatMoney))
      |""".stripMargin

  // Strip the `package:` frontmatter so the module lowers as a flat program —
  // this test targets the numeric/Money lowering, not JsGen's multi-block
  // package-namespace wrapping (a separate concern).
  private def moduleSrc =
    moneySrc.linesIterator.filterNot(_.startsWith("package:")).mkString("\n") +
      "\n## Driver\n\n```scalascript\n" + driver + "\n```\n"

  private def interp(): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(moduleSrc))
    ps.flush(); buf.toString.trim

  private def has(cmd: String): Boolean = ProcTestUtil.commandOk(cmd)

  private def runProc(cmd: String*): String =
    val proc = ProcessBuilder(cmd*).start()
    val out  = Source.fromInputStream(proc.getInputStream).mkString
    val err  = Source.fromInputStream(proc.getErrorStream).mkString
    if ProcTestUtil.awaitExit(proc) != 0 then fail(s"${cmd.head} failed:\n$err")
    out.trim

  test("money.ssc — JVM output matches the interpreter"):
    assume(has("scala-cli"), "scala-cli not available")
    val tmp = java.io.File.createTempFile("ssc-money-", ".sc"); tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath,
      ("//> using scala 3.8.3\n" + JvmGen.generate(Parser.parse(moduleSrc))).getBytes(StandardCharsets.UTF_8))
    runProc("scala-cli", "run", tmp.getAbsolutePath) shouldBe interp()

  test("money.ssc — JS output matches the interpreter"):
    assume(has("node"), "node not available")
    val tmp = java.io.File.createTempFile("ssc-money-", ".cjs"); tmp.deleteOnExit()
    val runtime = JsGen.generateRuntime(JsGen.Capability.all)
    java.nio.file.Files.write(tmp.toPath,
      (runtime + "\n" + JsGen.generate(Parser.parse(moduleSrc))).getBytes(StandardCharsets.UTF_8))
    runProc("node", tmp.getAbsolutePath) shouldBe interp()
