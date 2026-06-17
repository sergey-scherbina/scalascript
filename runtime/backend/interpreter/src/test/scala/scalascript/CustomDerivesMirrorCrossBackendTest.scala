package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JvmGen}
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** arch-meta-v2-p5 Track A — user-defined typeclass derivation through the
 *  runtime `Mirror` (`object Csv: def derived(m: Mirror)`) must produce
 *  identical output on the interpreter, the JVM backend (scala-cli), and the
 *  JS backend (node). Mirrors `examples/custom-derives-mirror.ssc`.
 *
 *  CONFORMANCE BAR (2026-06-17): all three backends now agree. Custom
 *  `derives` was interpreter-only; A1b (JVM) and A2 (JS) brought it to the
 *  generated backends — JvmGen strips the clause + synthesizes `given TC[T] =
 *  TC.derived(summon[Mirror.Of[T]])`; JsGen registers a lazy custom-derives
 *  given in `_ssc_givens` and routes the summon through `_resolveGiven`.
 *  See `specs/arch-metaprogramming-v2.md` §4b Track A. (A1c — stdlib structural
 *  `derives Eq/Show/...` on the generated backends — remains a follow-up.) */
class CustomDerivesMirrorCrossBackendTest extends AnyFunSuite with Matchers:

  private val program =
    """
      |trait Csv[A]:
      |  def header: String
      |
      |case class CsvInstance(header: String) extends Csv[Any]
      |
      |object Csv:
      |  def derived(m: Mirror): Csv[Any] =
      |    CsvInstance(m.elemLabels.mkString(","))
      |
      |case class Person(name: String, age: Int) derives Csv
      |val csv = summon[Csv[Person]]
      |println(csv.header)
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
    interp() shouldBe "name,age"

  // arch-meta-v2 §4b Track A1b — custom derives synthesis on JVM (landed).
  test("JVM matches the interpreter"):
    assume(has("scala-cli"), "scala-cli not available")
    val tmp = java.io.File.createTempFile("ssc-csv-", ".sc"); tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath,
      ("//> using scala 3.8.3\n" + JvmGen.generate(module)).getBytes(StandardCharsets.UTF_8))
    runProc("scala-cli", "run", "--server=false", tmp.getAbsolutePath) shouldBe interp()

  // arch-meta-v2 §4b Track A2 — custom derives synthesis on JS (landed).
  test("JS matches the interpreter"):
    assume(has("node"), "node not available")
    val tmp = java.io.File.createTempFile("ssc-csv-", ".cjs"); tmp.deleteOnExit()
    val rt  = JsGen.generateRuntime(JsGen.Capability.all)
    java.nio.file.Files.write(tmp.toPath,
      (rt + "\n" + JsGen.generate(module)).getBytes(StandardCharsets.UTF_8))
    runProc("node", tmp.getAbsolutePath) shouldBe interp()
