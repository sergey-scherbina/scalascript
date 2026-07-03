package ssc.bridge

import org.scalatest.funsuite.AnyFunSuite
import ssc.*

class FrontendBridgeTest extends AnyFunSuite:

  def run(src: String): Value =
    val prog = FrontendBridge.convertSource(src)
    Runtime.run(Compiler.compile(prog), Array.empty[Value])

  def runStr(src: String): String =
    run(src) match
      case Value.StrV(s) => s
      case Value.IntV(n) => n.toString
      case Value.BoolV(b) => b.toString
      case v             => Show.show(v)

  def capture(src: String): String =
    val out = new java.io.ByteArrayOutputStream
    Console.withOut(out)(run(src))
    out.toString.trim

  test("literal int") {
    assert(run("42") == Value.IntV(42))
  }

  test("literal string") {
    assert(run("\"hello\"") == Value.StrV("hello"))
  }

  test("arithmetic") {
    assert(run("1 + 2 * 3") == Value.IntV(7))
  }

  test("val binding") {
    assert(run("val x = 10\nval y = x + 5\ny") == Value.IntV(15))
  }

  test("def and call") {
    assert(run("def double(x: Int) = x * 2\ndouble(21)") == Value.IntV(42))
  }

  test("if-else") {
    assert(run("if (1 < 2) \"yes\" else \"no\"") == Value.StrV("yes"))
  }

  test("println output") {
    assert(capture("println(\"Hello, World!\")") == "Hello, World!")
  }

  test("var and while loop") {
    val src =
      """var i = 0
        |var s = 0
        |while (i < 5) {
        |  s = s + i
        |  i = i + 1
        |}
        |s""".stripMargin
    assert(run(src) == Value.IntV(10))
  }

  test("recursive def") {
    val src =
      """def fib(n: Int): Int =
        |  if (n <= 1) n else fib(n - 1) + fib(n - 2)
        |fib(10)""".stripMargin
    assert(run(src) == Value.IntV(55))
  }

  test("lambda") {
    val src = "val f = (x: Int) => x + 1\nf(41)"
    assert(run(src) == Value.IntV(42))
  }

  test("match on constructor") {
    val src =
      """val xs = Cons(1, Cons(2, Nil))
        |xs match
        |  case Cons(h, _) => h
        |  case Nil        => 0""".stripMargin
    assert(run(src) == Value.IntV(1))
  }

  test("bool and/or short-circuit") {
    assert(run("true && false") == Value.BoolV(false))
    assert(run("false || true") == Value.BoolV(true))
  }

  test("string interpolation") {
    val src = "val name = \"v2\"\ns\"Hello $name!\""
    assert(run(src) == Value.StrV("Hello v2!"))
  }
