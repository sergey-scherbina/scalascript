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
