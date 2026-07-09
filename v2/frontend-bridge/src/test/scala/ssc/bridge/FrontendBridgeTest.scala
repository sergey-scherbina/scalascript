package ssc.bridge

import org.scalatest.funsuite.AnyFunSuite
import ssc.*

class FrontendBridgeTest extends AnyFunSuite:

  private var bridgeRuntimeLoaded = false

  private def ensureBridgeRuntime(): Unit =
    if !bridgeRuntimeLoaded then
      PluginBridge.loadAll()
      bridgeRuntimeLoaded = true

  def run(src: String): Value =
    ensureBridgeRuntime()
    val prog = FrontendBridge.convertSource(src)
    Runtime.run(Compiler.compile(prog), Array.empty[Value])

  def runCore(entry: Term): Value =
    ensureBridgeRuntime()
    Runtime.run(Compiler.compile(Program(Nil, entry)), Array.empty[Value])

  def dynamicArith(op: String, lhs: Term, rhs: Term): Term =
    Term.Let(
      List(Term.Lit(Const.CStr(op))),
      Term.Prim("__arith__", List(Term.Local(0), lhs, rhs)))

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

  test("non-literal __arith__ map plus tuple uses arithOp semantics") {
    val pair = Term.Prim("__arith__", List(
      Term.Lit(Const.CStr("->")),
      Term.Lit(Const.CStr("id")),
      Term.Lit(Const.CStr("demo"))))
    val out = runCore(dynamicArith("+", Term.Prim("__mk_map__", Nil), pair))

    val map = out.asInstanceOf[Value.ForeignV].h
      .asInstanceOf[collection.mutable.Map[Value, Value]]
    assert(map(Value.StrV("id")) == Value.StrV("demo"))
  }

  test("non-literal __arith__ char comparisons use codepoint semantics") {
    val arith = Prims.resolve("__arith__")

    assert(arith(List(Value.StrV(">"), Value.StrV("b"), Value.IntV('a'.toLong))) ==
      Value.BoolV(true))
    assert(arith(List(Value.StrV("<="), Value.IntV('a'.toLong), Value.StrV("b"))) ==
      Value.BoolV(true))
  }

  test("non-literal __arith__ preserves table-only fallback cases") {
    val arith = Prims.resolve("__arith__")

    val dec = arith(List(
      Value.StrV("+"),
      Value.ForeignV(new java.math.BigDecimal("1.25")),
      Value.IntV(2)))
    assert(dec.asInstanceOf[Value.ForeignV].h
      .asInstanceOf[java.math.BigDecimal].toPlainString == "3.25")

    assert(arith(List(Value.StrV("!"), Value.DataV("ActorRef", Vector(Value.IntV(1))), Value.StrV("msg"))) ==
      Value.UnitV)
    assert(arith(List(Value.StrV("Logger"), Value.StrV("effect"), Value.UnitV)) ==
      Value.UnitV)
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

  test("markdown standard scala fence is runnable when it is the document source") {
    val src =
      """# Standard Scala block
        |
        |```scala
        |println("scala-block-ok")
        |```
        |""".stripMargin

    assert(capture(src) == "scala-block-ok")
  }

  test("markdown standard scala multi-fence document runs all fences in order") {
    val src =
      """# Standard Scala blocks
        |
        |```scala
        |println("scala-block-1")
        |```
        |
        |```scala
        |println("scala-block-2")
        |```
        |""".stripMargin

    assert(capture(src) == "scala-block-1\nscala-block-2")
  }

  test("standard scala string takeWhile supports char predicates") {
    val src =
      """# Standard Scala string predicate
        |
        |```scala
        |println("Circle(3)".takeWhile(_ != '('))
        |```
        |""".stripMargin

    assert(capture(src) == "Circle")
  }

  test("standard scala f interpolator applies format specs") {
    val src =
      """# Standard Scala f interpolation
        |
        |```scala
        |println(f"${"x"}%-4s=${1.234}%.1f")
        |```
        |""".stripMargin

    assert(capture(src) == "x   =1.2")
  }

  test("markdown ssc fence alias is runnable") {
    val src =
      """# ScalaScript alias block
        |
        |```ssc
        |println("ssc-block-ok")
        |```
        |""".stripMargin

    assert(capture(src) == "ssc-block-ok")
  }

  test("markdown scala fence stays illustrative in mixed scalascript document") {
    val src =
      """# Mixed blocks
        |
        |```scalascript
        |println("real-code")
        |```
        |
        |```scala
        |println("illustrative-code")
        |```
        |""".stripMargin

    assert(capture(src) == "real-code")
  }

  test("markdown scala fence runs in mixed document with explicit frontmatter opt-in") {
    val src =
      """---
        |name: mixed-runnable
        |runScalaFences: true
        |---
        |
        |# Mixed runnable blocks
        |
        |```scala
        |println("scala-before")
        |```
        |
        |```scalascript
        |println("scalascript-middle")
        |```
        |
        |```scala
        |println("scala-after")
        |```
        |""".stripMargin

    assert(capture(src) == "scala-before\nscalascript-middle\nscala-after")
  }

  test("guarded constructor pattern falls through to later matching case") {
    val src =
      """val left = List(5)
        |val right = List(2, 8)
        |(left, right) match
        |  case (ah :: at, bh :: _) if ah <= bh => println("left head " + ah)
        |  case (_, bh :: bt) => println("right head " + bh)
        |""".stripMargin

    assert(capture(src) == "right head 2")
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

  test("list literal after spaced infix operator") {
    val src =
      """val head = [1]
        |val xs = head ++ [2]
        |xs""".stripMargin
    assert(run(src) ==
      Value.DataV("Cons", Vector(Value.IntV(1),
        Value.DataV("Cons", Vector(Value.IntV(2), Value.DataV("Nil", Vector.empty))))))
  }
