package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.JvmGen
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** Merged mutual-TCO codegen (allocation-free dispatch loop for uniform-signature
 *  cliques) must produce output identical to the interpreter on the JVM backend,
 *  for several shapes (Boolean / String / 2-param Int) at depths that would blow
 *  the JVM stack without trampolining. */
class MutualTcoCrossBackendTest extends AnyFunSuite with Matchers:

  private val program =
    """
      |def isEven(n: Int): Boolean = if n == 0 then true else isOdd(n - 1)
      |def isOdd(n: Int): Boolean = if n == 0 then false else isEven(n - 1)
      |def ping(n: Int): String = if n == 0 then "ping" else pong(n - 1)
      |def pong(n: Int): String = if n == 0 then "pong" else pang(n - 1)
      |def pang(n: Int): String = if n == 0 then "pang" else ping(n - 1)
      |def evenSum(n: Int, acc: Int): Int = if n == 0 then acc else oddSum(n - 1, acc + n)
      |def oddSum(n: Int, acc: Int): Int = if n == 0 then acc else evenSum(n - 1, acc)
      |println(isEven(100000))
      |println(isOdd(100000))
      |println(ping(99998))
      |println(evenSum(100, 0))
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
    interp() shouldBe "true\nfalse\npang\n2550"

  test("JVM merged mutual-TCO matches the interpreter"):
    assume(has("scala-cli"), "scala-cli not available")
    val tmp = java.io.File.createTempFile("ssc-mtco-", ".sc"); tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath,
      ("//> using scala 3.8.3\n" + JvmGen.generate(module)).getBytes(StandardCharsets.UTF_8))
    runProc("scala-cli", "run", tmp.getAbsolutePath) shouldBe interp()
