package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.JvmGen
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** arch-meta-v2-p5 Track A — A1a: `summon[Mirror.Of[T]]` must expose the same
 *  product metadata (label / elemLabels / elemTypes / isProduct) on the JVM
 *  backend as it does on the interpreter. Foundation for A1b (custom derives)
 *  and A1c (stdlib structural derives). */
class MirrorOfJvmConformanceTest extends AnyFunSuite with Matchers:

  private val program =
    """
      |case class Person(name: String, age: Int)
      |val m = summon[Mirror.Of[Person]]
      |println(m.label)
      |println(m.elemLabels.mkString("|"))
      |println(m.elemTypes.mkString("|"))
      |println(m.isProduct)
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
    interp() shouldBe "Person\nname|age\nString|Int\ntrue"

  test("JVM summon[Mirror.Of[T]] matches the interpreter"):
    assume(has("scala-cli"), "scala-cli not available")
    val tmp = java.io.File.createTempFile("ssc-mirror-", ".sc"); tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath,
      ("//> using scala 3.8.3\n" + JvmGen.generate(module)).getBytes(StandardCharsets.UTF_8))
    runProc("scala-cli", "run", "--server=false", tmp.getAbsolutePath) shouldBe interp()
