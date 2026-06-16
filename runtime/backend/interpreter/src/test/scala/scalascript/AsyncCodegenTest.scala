package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JsRuntime, JsRuntimeAsync}
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** Tests for v1.11 coroutine-based Async — JvmGen and JsGen codegen phase.
 *  JvmGen tests check generated Scala contains expected runtime and call shapes.
 *  JvmGen run tests compile+run with scala-cli (skipped if not available).
 *  JsGen tests run through node (skipped if node unavailable). */
class AsyncCodegenTest extends AnyFunSuite with Matchers:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  // ── JvmGen code-shape tests ──────────────────────────────────────────

  private def jvmCode(code: String): String =
    JvmGen.generate(module(code))

  test("JvmGen: _runAsync uses coroutine scheduler in generated code"):
    val code = jvmCode("""
      val r = runAsync { Async.delay(1) }
      println(r)
    """)
    code should include ("_runAsync")
    code should include ("_CoHandle")
    code should include ("_coHandleTL")

  test("JvmGen: Async methods check _coHandleTL in generated code"):
    val code = jvmCode("""
      val r = runAsync { Async.async(() => 42) }
      println(r)
    """)
    code should include ("_coHandleTL.get()")
    code should include ("_AsyncIO")
    code should include ("_DelayIO")

  test("JvmGen: _driveAsyncCo scheduler is present in generated code"):
    val code = jvmCode("""
      val r = runAsync { Async.delay(1) }
      println(r)
    """)
    code should include ("_driveAsyncCo")
    code should include ("_DelayIO")
    code should include ("_AwaitIO")
    code should include ("_ParallelIO")

  // ── JvmGen run-via-scala-cli tests ───────────────────────────────────

  private lazy val hasScalaCli: Boolean = ProcTestUtil.commandOk("scala-cli")

  private def runJvm(code: String): String =
    val sc = jvmCode(code)
    val tmp = java.io.File.createTempFile("ssc-async-", ".sc")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, sc.getBytes(StandardCharsets.UTF_8))
    val proc = ProcessBuilder("scala-cli", "run", "--server=false", tmp.getAbsolutePath)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
    val out = Source.fromInputStream(proc.getInputStream).mkString
    ProcTestUtil.awaitExit(proc)
    out.trim

  test("JvmGen: runAsync sequential await"):
    assume(hasScalaCli, "scala-cli not available")
    runJvm("""
      val r = runAsync {
        val a = Async.async(() => 1)
        val b = Async.async(() => 2)
        Async.await(a) + Async.await(b)
      }
      println(r)
    """) shouldBe "3"

  test("JvmGen: runAsync parallel keeps declared order"):
    assume(hasScalaCli, "scala-cli not available")
    runJvm("""
      val r = runAsync {
        Async.parallel(List(() => 10, () => 20, () => 30))
      }
      println(r)
    """) shouldBe "List(10, 20, 30)"

  test("JvmGen: runAsync delay"):
    assume(hasScalaCli, "scala-cli not available")
    runJvm("""
      runAsync {
        Async.delay(1)
        println("after-delay")
      }
    """) shouldBe "after-delay"

  // ── JsGen run-via-node tests ─────────────────────────────────────────

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runJs(code: String): String =
    val flush = """process.stdout.write(_output.join('\n') + (_output.length ? '\n' : '')); _output = [];"""
    val js    = JsRuntime + "\n" + JsRuntimeAsync + "\n" + JsGen.generate(module(code)) + "\n" + flush
    val tmp   = java.io.File.createTempFile("ssc-async-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc  = ProcessBuilder("node", tmp.getAbsolutePath)
      .redirectErrorStream(true)
      .start()
    val out = Source.fromInputStream(proc.getInputStream).mkString
    ProcTestUtil.awaitExit(proc)
    out.trim

  test("JsGen: runAsync sequential await"):
    assume(hasNode, "node not available")
    runJs("""
      val r = runAsync {
        val a = Async.async(() => 1)
        val b = Async.async(() => 2)
        Async.await(a) + Async.await(b)
      }
      println(r)
    """) shouldBe "3"

  test("JsGen: runAsync parallel keeps declared order"):
    assume(hasNode, "node not available")
    runJs("""
      val r = runAsync {
        Async.parallel(List(() => 10, () => 20, () => 30))
      }
      println(r)
    """) shouldBe "List(10, 20, 30)"

  test("JsGen: runAsync delay"):
    assume(hasNode, "node not available")
    runJs("""
      runAsync {
        Async.delay(1)
        println("after-delay")
      }
    """) shouldBe "after-delay"

  test("JsGen: runAsync effectful map callback"):
    assume(hasNode, "node not available")
    runJs("""
      def doubled(n: Int): Int =
        Async.await(Async.async(() => n * 2))
      val r = runAsync {
        List(1, 2, 3, 4).map(doubled)
      }
      println(r)
    """) shouldBe "List(2, 4, 6, 8)"
