package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JsRuntime, JsRuntimeAsync}
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** Tests for v1.9 coroutine primitive — JvmGen and JsGen codegen phase.
 *  JvmGen tests check the generated Scala contains the expected runtime and call shapes.
 *  JsGen tests run the generated JS through node (skipped if node unavailable). */
class CoroutineCodegenTest extends AnyFunSuite with Matchers:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  // ── JvmGen code-shape tests ──────────────────────────────────────────

  private def jvmCode(code: String): String =
    JvmGen.generate(module(code))

  test("JvmGen: coroutineCreate call is present in generated code"):
    val code = jvmCode("""
      val co = coroutineCreate[Int, Unit, String] { () =>
        suspend(42)
        "done"
      }
      println(coroutineResume(co, ()))
    """)
    code should include ("coroutineCreate")

  test("JvmGen: coroutineResume call is present in generated code"):
    val code = jvmCode("""
      val co = coroutineCreate[Int, Unit, String] { () => "x" }
      println(coroutineResume(co, ()))
    """)
    code should include ("coroutineResume")

  test("JvmGen: Yielded / Returned / Errored ADT is in the preamble"):
    val code = jvmCode("""
      val co = coroutineCreate[Int, Unit, String] { () =>
        suspend(1)
        "done"
      }
      println(coroutineResume(co, ()))
    """)
    code should include ("case class Yielded")
    code should include ("case class Returned")
    code should include ("case class Errored")

  test("JvmGen: coroutineCreate / coroutineResume defs are in the preamble"):
    val code = jvmCode("""
      val co = coroutineCreate[Int, Unit, Unit] { () => suspend(0) }
      println(coroutineResume(co, ()))
    """)
    code should include ("def coroutineCreate")
    code should include ("def coroutineResume")

  // ── JsGen run-via-node tests ─────────────────────────────────────────

  private def hasNode: Boolean = ProcTestUtil.commandOk("node")

  private def runJs(code: String): String =
    val flush = """process.stdout.write(_output.join('\n') + (_output.length ? '\n' : '')); _output = [];"""
    val js    = JsRuntime + "\n" + JsRuntimeAsync + "\n" + JsGen.generate(module(code)) + "\n" + flush
    val tmp   = java.io.File.createTempFile("ssc-coroutine-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc  = ProcessBuilder("node", tmp.getAbsolutePath)
      .redirectErrorStream(true)
      .start()
    val out = Source.fromInputStream(proc.getInputStream).mkString
    ProcTestUtil.awaitExit(proc)
    out.trim

  test("JsGen: single yield then return"):
    assume(hasNode, "node not available")
    runJs("""
      val co = coroutineCreate[Int, Unit, String] { () =>
        suspend(42)
        "done"
      }
      println(coroutineResume(co, ()))
      println(coroutineResume(co, ()))
    """) shouldBe "Yielded(42)\nReturned(done)"

  test("JsGen: generator loop via while"):
    assume(hasNode, "node not available")
    runJs("""
      val co = coroutineCreate[Int, Unit, Unit] { () =>
        var n = 0
        while n < 3 do
          suspend(n)
          n = n + 1
      }
      println(coroutineResume(co, ()))
      println(coroutineResume(co, ()))
      println(coroutineResume(co, ()))
      println(coroutineResume(co, ()))
    """) shouldBe "Yielded(0)\nYielded(1)\nYielded(2)\nReturned(())"

  test("JsGen: ping-pong two-way value passing"):
    assume(hasNode, "node not available")
    runJs("""
      val co = coroutineCreate[String, String, String] { () =>
        val b = suspend("ping")
        val c = suspend(b + "-pong")
        c + "-final"
      }
      println(coroutineResume(co, ""))
      println(coroutineResume(co, "A"))
      println(coroutineResume(co, "B"))
    """) shouldBe "Yielded(ping)\nYielded(A-pong)\nReturned(B-final)"

  test("JsGen: error propagation returns Errored"):
    assume(hasNode, "node not available")
    // Use null.toString() to trigger a TypeError in JS
    // (JavaScript does not throw on integer division by zero)
    runJs("""
      val co = coroutineCreate[Unit, Unit, String] { () =>
        suspend(())
        val x: String = null
        x.length.toString
      }
      println(coroutineResume(co, ()))
      val r = coroutineResume(co, ())
      r match
        case Errored(msg) => println("got error")
        case _            => println("unexpected: " + r)
    """) shouldBe "Yielded(())\ngot error"

  test("JsGen: multiple sequential yields"):
    assume(hasNode, "node not available")
    runJs("""
      val co = coroutineCreate[Int, Unit, String] { () =>
        suspend(1)
        suspend(2)
        suspend(3)
        "done"
      }
      println(coroutineResume(co, ()))
      println(coroutineResume(co, ()))
      println(coroutineResume(co, ()))
      println(coroutineResume(co, ()))
    """) shouldBe "Yielded(1)\nYielded(2)\nYielded(3)\nReturned(done)"

  test("JsGen: nested coroutines"):
    assume(hasNode, "node not available")
    runJs("""
      val outer = coroutineCreate[Int, Unit, String] { () =>
        val inner = coroutineCreate[Int, Unit, String] { () =>
          suspend(99)
          "inner-done"
        }
        val step = coroutineResume(inner, ())
        step match
          case Yielded(v) =>
            suspend(v)
            ()
          case _ => ()
        "outer-done"
      }
      println(coroutineResume(outer, ()))
      println(coroutineResume(outer, ()))
    """) shouldBe "Yielded(99)\nReturned(outer-done)"

  test("JsGen: throw new RuntimeException — Errored captures message"):
    assume(hasNode, "node not available")
    runJs("""
      val co = coroutineCreate[Unit, Unit, Int] { () =>
        suspend(())
        throw new RuntimeException("oops")
      }
      println(coroutineResume(co, ()))
      println(coroutineResume(co, ()))
    """) shouldBe "Yielded(())\nErrored(oops)"

  test("JsGen: body side effects happen after first resume, not at create time"):
    assume(hasNode, "node not available")
    runJs("""
      var ran = false
      val co = coroutineCreate[Unit, Unit, Unit] { () =>
        ran = true
        suspend(())
      }
      println(ran)
      println(coroutineResume(co, ()))
      println(ran)
    """) shouldBe "false\nYielded(())\ntrue"

  test("JsGen: generator and coroutine both work in same program"):
    assume(hasNode, "node not available")
    runJs("""
      val gen = generator { () =>
        suspend(1)
        suspend(2)
      }
      val co = coroutineCreate[Int, Unit, Unit] { () =>
        suspend(10)
        suspend(20)
      }
      println(gen.nextOpt())
      println(coroutineResume(co, ()))
      println(gen.nextOpt())
      println(coroutineResume(co, ()))
    """) shouldBe "Some(1)\nYielded(10)\nSome(2)\nYielded(20)"
