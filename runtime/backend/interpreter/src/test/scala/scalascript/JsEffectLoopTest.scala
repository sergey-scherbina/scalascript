package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.codegen.{JsGen, JsRuntime, JsRuntimeAsync}
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** effect-cps-loops-js — a custom effect `perform` inside a `var`+`while` loop now
 *  lowers correctly on the JS backend. `JsGenCpsCodegen` keeps the CPS `var`s as
 *  mutable arrow-function params (mutable in JS), threads each effectful assign
 *  through `_bind`, and lowers a `Term.While` to a trampolined recursive helper so
 *  the body's `perform`s run via the Free-monad interpreter. Run via node. */
class JsEffectLoopTest extends AnyFunSuite:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runJs(code: String): String =
    val flush = """process.stdout.write(_output.join('\n') + (_output.length ? '\n' : '')); _output = [];"""
    val js    = JsRuntime + "\n" + JsRuntimeAsync + "\n" + JsGen.generate(module(code)) + "\n" + flush
    val tmp   = java.io.File.createTempFile("ssc-js-effloop-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc  = ProcessBuilder("node", tmp.getAbsolutePath).redirectErrorStream(true).start()
    val out   = Source.fromInputStream(proc.getInputStream).mkString
    ProcTestUtil.awaitExit(proc)
    out.trim

  private val loopBody =
    """
      effect Bump:
        def tick(): Int
      def loop(n: Int): Int ! Bump =
        var acc = 0
        var i = 0
        while i < n do
          acc = acc + Bump.tick()
          i = i + 1
        acc
    """

  // base-runtime-cps-hofs — WITHOUT the async runtime bundled, the base CPS-aware _seqForeach / _seqExists
  // (core-collections.mjs) must still sequence a Free-returning callback: an effectful `foreach` body, or
  // a predicate that reads a field/enum inside a handler (which compiles to `_bind(...)`). Before the fix
  // the sync base versions silently dropped a foreach body's effects and `exists` returned a truthy Free
  // instead of a boolean. (Pairs with busi tests/v2/obligations_all.ssc, which runs base-only via emit-js.)
  private def runJsBaseOnly(code: String): String =
    val flush = """process.stdout.write(_output.join('\n') + (_output.length ? '\n' : '')); _output = [];"""
    val js    = JsRuntime + "\n" + JsGen.generate(module(code)) + "\n" + flush
    val tmp   = java.io.File.createTempFile("ssc-js-base-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc  = ProcessBuilder("node", tmp.getAbsolutePath).redirectErrorStream(true).start()
    val out   = Source.fromInputStream(proc.getInputStream).mkString
    ProcTestUtil.awaitExit(proc)
    out.trim

  test("JsGen base runtime: effectful foreach sequences its body (no async runtime bundled)"):
    assume(hasNode, "node not available")
    val out = runJsBaseOnly("""
      effect MyLog:
        def log(msg: String): Unit
      def program(): Unit ! MyLog =
        List("a", "b", "c").foreach(x => MyLog.log(x))
      def run(): List[String] =
        handle(program()) {
          case MyLog.log(msg, resume) => msg :: resume(())
          case Return(_) => List()
        }
      println(run())
    """)
    assert(out == "List(a, b, c)", s"expected List(a, b, c) (foreach body effects threaded), got '$out'")

  test("JsGen: one-shot effect performed in a while-loop compiles + runs"):
    assume(hasNode, "node not available")
    val out = runJs(loopBody + """
      def run(): Int =
        handle(loop(5)) {
          case Bump.tick(resume) => resume(1)
        }
      println(run())
    """)
    assert(out == "5", s"expected 5 (1 tick × 5 iterations), got '$out'")

  test("JsGen: a longer-running perform loop accumulates correctly"):
    assume(hasNode, "node not available")
    val out = runJs(loopBody + """
      def run(): Int =
        handle(loop(10)) {
          case Bump.tick(resume) => resume(3)
        }
      println(run())
    """)
    assert(out == "30", s"expected 30 (3 ticks × 10 iterations), got '$out'")

  // ── self-handling CPS fn run at the value boundary (js-self-handling-cps-fn-run) ──
  // A function that handles its own effects but is CPS-emitted returns a lazy
  // `_FlatMap` on JS; a value-position call (`println(workload())`) must be `_run`
  // or it prints `[object Object]`. genApply wraps effectful-fn calls in `_run`.

  test("JsGen: a self-handling CPS fn (no loop) runs at the value boundary"):
    assume(hasNode, "node not available")
    val out = runJs("""
      multi effect NonDet:
        def choose(options: List[Int]): Int
      def program(): Int ! NonDet =
        val a = NonDet.choose(List(1, 2, 3))
        a
      def workload(): Int =
        val all = handle(program()) {
          case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))
        }
        all.length
      println(workload())
    """)
    assert(out == "3", s"expected 3 (3 choices), got '$out'")

  test("JsGen: multi-shot effect handled inside a while-loop runs end-to-end"):
    assume(hasNode, "node not available")
    val out = runJs("""
      multi effect NonDet:
        def choose(options: List[Int]): Int
      def program(): Int ! NonDet =
        val a = NonDet.choose(List(1, 2, 3))
        val b = NonDet.choose(List(10, 20))
        a + b
      def workload(): Long =
        var total = 0L
        var i = 0
        while i < 2 do
          val all = handle(program()) {
            case NonDet.choose(opts, resume) => opts.flatMap(opt => resume(opt))
          }
          total = total + all.foldLeft(0L)((acc, x) => acc + x.toLong)
          i = i + 1
        total
      println(workload())
    """)
    // 6 combinations sum to 102 per iteration × 2 = 204.
    assert(out == "204", s"expected 204, got '$out'")

  // ── handler return clause (effect-handler-return-clause-codegen, JS slice) ──
  // A handler with a `case Return(x) => expr` arm lowers to `_handleWithReturn`,
  // which maps the body's pure completion (and each resumed continuation) through
  // retMap — enabling deep-handler accumulation `msg :: resume(())`.

  test("JsGen return clause: code-shape emits _handleWithReturn + retMap"):
    val js = JsGen.generate(module("""
      effect MyLog:
        def log(msg: String): Unit
      def program(): Unit ! MyLog =
        MyLog.log("Hello")
      def run(): List[String] =
        handle(program()) {
          case MyLog.log(msg, resume) => msg :: resume(())
          case Return(_) => List()
        }
    """))
    assert(js.contains("_handleWithReturn("), s"expected _handleWithReturn in:\n$js")
    assert(js.contains("(_rv) =>"), s"expected retMap lambda in:\n$js")

  test("JsGen return clause: deep-handler accumulation collects messages"):
    assume(hasNode, "node not available")
    val out = runJs("""
      effect MyLog:
        def log(msg: String): Unit
      def program(): Unit ! MyLog =
        MyLog.log("Hello")
        MyLog.log("World!")
      def run(): List[String] =
        handle(program()) {
          case MyLog.log(msg, resume) => msg :: resume(())
          case Return(_) => List()
        }
      println(run())
    """)
    assert(out == "List(Hello, World!)", s"expected List(Hello, World!), got '$out'")

  test("JsGen return clause: binds + maps the completion value (tail-position resume)"):
    assume(hasNode, "node not available")
    val out = runJs("""
      effect Ask:
        def ask(): String
      def program(): String ! Ask =
        val x = Ask.ask()
        x
      def run(): List[String] =
        handle(program()) {
          case Ask.ask(resume) => resume("hi")
          case Return(x) => List(x, x)
        }
      println(run())
    """)
    assert(out == "List(hi, hi)", s"expected List(hi, hi), got '$out'")
