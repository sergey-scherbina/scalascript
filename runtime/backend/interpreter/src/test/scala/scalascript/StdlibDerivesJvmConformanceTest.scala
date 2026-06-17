package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JvmGen}
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** arch-meta-v2-p5 Track A — A1c: stdlib structural `derives Eq/Show/Hash/Order`
 *  must produce identical output on the interpreter, the JVM backend, and the JS
 *  backend.  These typeclasses define no `derived`; the generated backends
 *  synthesize structural instances (JVM: Scala `Product`; JS: `_ssc_struct*`
 *  runtime helpers) mirroring the interpreter's `DerivesRuntime`. */
class StdlibDerivesJvmConformanceTest extends AnyFunSuite with Matchers:

  private val repoRoot = TestPaths.repoRoot

  private val program =
    """
      |case class Person(name: String, age: Int) derives Eq, Show, Hash, Order
      |val p1 = Person("Alice", 30)
      |val p2 = Person("Bob", 25)
      |println(summon[Eq[Person]].eqv(p1, p1))
      |println(summon[Eq[Person]].eqv(p1, p2))
      |println(summon[Show[Person]].show(p1))
      |println(summon[Order[Person]].compare(p1, p2) < 0)
      |println(summon[Hash[Person]].hash(p1) == summon[Hash[Person]].hash(p1))
      |""".stripMargin

  private def module = Parser.parse(
    s"""# Test
       |
       |[Eq, eqv, neqv](std/eq.ssc)
       |[Show, show](std/show.ssc)
       |[Hash, hash](std/hash.ssc)
       |[Order, compare](std/order.ssc)
       |
       |```scalascript
       |$program
       |```
       |""".stripMargin)

  private def interp(): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps, baseDir = Some(repoRoot)).run(module); ps.flush(); buf.toString.trim

  private def has(cmd: String): Boolean = ProcTestUtil.commandOk(cmd)
  private def runProc(cmd: String*): String =
    val p = ProcessBuilder(cmd*).start()
    val out = Source.fromInputStream(p.getInputStream).mkString
    val err = Source.fromInputStream(p.getErrorStream).mkString
    if ProcTestUtil.awaitExit(p) != 0 then fail(s"${cmd.head} failed:\n$err"); out.trim

  test("interpreter result is the expected baseline"):
    interp() shouldBe "true\nfalse\nPerson(name=Alice, age=30)\ntrue\ntrue"

  test("JVM stdlib derives matches the interpreter"):
    assume(has("scala-cli"), "scala-cli not available")
    val tmp = java.io.File.createTempFile("ssc-stdderives-", ".sc"); tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath,
      ("//> using scala 3.8.3\n" + JvmGen.generate(module, baseDir = Some(repoRoot))).getBytes(StandardCharsets.UTF_8))
    runProc("scala-cli", "run", "--server=false", tmp.getAbsolutePath) shouldBe interp()

  test("JS stdlib derives matches the interpreter"):
    assume(has("node"), "node not available")
    val tmp = java.io.File.createTempFile("ssc-stdderives-", ".cjs"); tmp.deleteOnExit()
    val rt  = JsGen.generateRuntime(JsGen.Capability.all)
    java.nio.file.Files.write(tmp.toPath,
      (rt + "\n" + JsGen.generate(module, baseDir = Some(repoRoot))).getBytes(StandardCharsets.UTF_8))
    runProc("node", tmp.getAbsolutePath) shouldBe interp()
