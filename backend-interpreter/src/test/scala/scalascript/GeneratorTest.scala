package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Unit tests for v1.10 Generator — pull-based lazy streams. */
class GeneratorTest extends AnyFunSuite with Matchers:

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    Interpreter(ps).run(module)
    ps.flush()
    buf.toString.trim

  test("generator — toList collects all values"):
    captured("""
      val g = generator { () =>
        suspend(1)
        suspend(2)
        suspend(3)
      }
      println(g.toList)
    """) shouldBe "List(1, 2, 3)"

  test("generator — next() returns Some then None"):
    captured("""
      val g = generator { () =>
        suspend(10)
        suspend(20)
      }
      println(g.next())
      println(g.next())
      println(g.next())
    """) shouldBe "Some(10)\nSome(20)\nNone"

  test("generator — foreach iterates all"):
    captured("""
      val g = generator { () =>
        suspend("a")
        suspend("b")
        suspend("c")
      }
      g.foreach { v => println(v) }
    """) shouldBe "a\nb\nc"

  test("generator — map transforms values"):
    captured("""
      val g = generator { () =>
        suspend(1)
        suspend(2)
        suspend(3)
      }
      println(g.map(_ * 10).toList)
    """) shouldBe "List(10, 20, 30)"

  test("generator — filter skips non-matching"):
    captured("""
      val g = generator { () =>
        suspend(1)
        suspend(2)
        suspend(3)
        suspend(4)
      }
      println(g.filter(_ % 2 == 0).toList)
    """) shouldBe "List(2, 4)"

  test("generator — take(n) limits output"):
    captured("""
      val g = generator { () =>
        var i = 0
        while i < 100 do
          suspend(i)
          i = i + 1
      }
      println(g.take(5).toList)
    """) shouldBe "List(0, 1, 2, 3, 4)"

  test("generator — drop(n) skips first n"):
    captured("""
      val g = generator { () =>
        suspend(10)
        suspend(20)
        suspend(30)
        suspend(40)
      }
      println(g.drop(2).toList)
    """) shouldBe "List(30, 40)"

  test("generator — map.filter pipeline"):
    captured("""
      val g = generator { () =>
        var i = 1
        while i <= 6 do
          suspend(i)
          i = i + 1
      }
      println(g.map(_ * 3).filter(_ % 2 == 0).take(3).toList)
    """) shouldBe "List(6, 12, 18)"

  test("generator — infinite stream with take"):
    captured("""
      val fibs = generator { () =>
        var a = 0
        var b = 1
        while true do
          suspend(a)
          val t = a + b
          a = b
          b = t
      }
      println(fibs.take(8).toList)
    """) shouldBe "List(0, 1, 1, 2, 3, 5, 8, 13)"

  test("generator — empty generator returns empty list"):
    captured("""
      val g = generator { () => () }
      println(g.toList)
    """) shouldBe "List()"

  test("generator — flatMap expands each element"):
    captured("""
      val g = generator { () =>
        suspend(1)
        suspend(2)
        suspend(3)
      }
      println(g.flatMap { n => generator { () =>
        suspend(n)
        suspend(n * 10)
      }}.toList)
    """) shouldBe "List(1, 10, 2, 20, 3, 30)"

  test("generator — zip pairs elements, stops at shorter"):
    captured("""
      val a = generator { () => suspend(1); suspend(2); suspend(3) }
      val b = generator { () => suspend("x"); suspend("y") }
      println(a.zip(b).toList)
    """) shouldBe "List((1, x), (2, y))"

  test("generator — zipWithIndex pairs values with indices"):
    captured("""
      val g = generator { () =>
        suspend("a")
        suspend("b")
        suspend("c")
      }
      println(g.zipWithIndex.toList)
    """) shouldBe "List((a, 0), (b, 1), (c, 2))"

  test("generator — flatMap on infinite stream with take"):
    captured("""
      val nats = generator { () =>
        var i = 1
        while true do
          suspend(i)
          i = i + 1
      }
      println(nats.flatMap { n => generator { () => suspend(n); suspend(-n) }}.take(6).toList)
    """) shouldBe "List(1, -1, 2, -2, 3, -3)"
