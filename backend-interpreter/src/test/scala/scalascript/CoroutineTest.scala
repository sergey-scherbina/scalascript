package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Tests for v1.9 coroutine primitive — interpreter tier. */
class CoroutineTest extends AnyFunSuite with Matchers:

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── basic create / resume / yield ──────────────────────────────────

  test("single yield then return"):
    run("""
      val co = coroutineCreate[Int, Unit, String] { () =>
        suspend(42)
        "done"
      }
      println(coroutineResume(co, ()))
      println(coroutineResume(co, ()))
    """) shouldBe "Yielded(42)\nReturned(done)"

  test("generator loop — pull three values (explicit mutation)"):
    run("""
      val co = coroutineCreate[Int, Unit, Unit] { () =>
        var n = 0
        suspend(n)
        n = n + 1
        suspend(n)
        n = n + 1
        suspend(n)
      }
      println(coroutineResume(co, ()))
      println(coroutineResume(co, ()))
      println(coroutineResume(co, ()))
    """) shouldBe "Yielded(0)\nYielded(1)\nYielded(2)"

  test("generator loop — pull three values (while loop)"):
    run("""
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

  // ── two-way value passing ────────────────────────────────────────────

  test("ping-pong — resume input becomes suspend return value"):
    run("""
      val co = coroutineCreate[String, String, String] { () =>
        val b = suspend("ping")
        val c = suspend(b + "-pong")
        c + "-final"
      }
      println(coroutineResume(co, ""))
      println(coroutineResume(co, "A"))
      println(coroutineResume(co, "B"))
    """) shouldBe "Yielded(ping)\nYielded(A-pong)\nReturned(B-final)"

  test("accumulator — body uses resumed values"):
    run("""
      val co = coroutineCreate[Int, Int, Int] { () =>
        val a = suspend(0)
        val b = suspend(a * 2)
        val c = suspend(b * 2)
        c * 2
      }
      println(coroutineResume(co, 0))
      println(coroutineResume(co, 3))
      println(coroutineResume(co, 6))
      println(coroutineResume(co, 12))
    """) shouldBe "Yielded(0)\nYielded(6)\nYielded(12)\nReturned(24)"

  // ── error propagation ────────────────────────────────────────────────

  test("body throws — coroutineResume returns Errored"):
    run("""
      val co = coroutineCreate[Unit, Unit, Int] { () =>
        suspend(())
        1 / 0
      }
      println(coroutineResume(co, ()))
      val r = coroutineResume(co, ())
      r match
        case Errored(msg) => println("got error")
        case _            => println("unexpected: " + r)
    """) shouldBe "Yielded(())\ngot error"

  // ── resume-of-completed error ────────────────────────────────────────

  test("resuming a completed coroutine raises error"):
    val ex = intercept[Exception] {
      run("""
        val co = coroutineCreate[Unit, Unit, String] { () => "done" }
        println(coroutineResume(co, ()))
        println(coroutineResume(co, ()))   // should throw
      """)
    }
    ex.getMessage should include ("completed")

  // ── lazy start ───────────────────────────────────────────────────────

  test("body side effects happen after first resume, not at create time"):
    run("""
      var ran = false
      val co = coroutineCreate[Unit, Unit, Unit] { () =>
        ran = true
        suspend(())
      }
      println(ran)                         // false — body not yet started
      println(coroutineResume(co, ()))     // Yielded(()) — body runs now
      println(ran)                         // true
    """) shouldBe "false\nYielded(())\ntrue"

  // ── nested coroutines ────────────────────────────────────────────────

  test("coroutine creates and resumes another coroutine inside body"):
    run("""
      val outer = coroutineCreate[Int, Unit, Unit] { () =>
        val inner = coroutineCreate[Int, Unit, Unit] { () =>
          suspend(10)
          suspend(20)
        }
        val a = coroutineResume(inner, ())
        val b = coroutineResume(inner, ())
        suspend(100)
        suspend(200)
      }
      println(coroutineResume(outer, ()))
      println(coroutineResume(outer, ()))
    """) shouldBe "Yielded(100)\nYielded(200)"

  // ── suspend outside coroutine ─────────────────────────────────────────

  test("suspend outside coroutine raises error"):
    val ex = intercept[Exception] { run("suspend(42)") }
    ex.getMessage should (include("coroutine") or include("generator"))

  // ── coexistence with generator ────────────────────────────────────────

  test("generator suspend and coroutine suspend both work in same program"):
    run("""
      val gen = generator { () =>
        suspend(1)
        suspend(2)
      }
      val co = coroutineCreate[Int, Unit, Unit] { () =>
        suspend(10)
        suspend(20)
      }
      println(gen.next())
      println(coroutineResume(co, ()))
      println(gen.next())
      println(coroutineResume(co, ()))
    """) shouldBe "Some(1)\nYielded(10)\nSome(2)\nYielded(20)"
