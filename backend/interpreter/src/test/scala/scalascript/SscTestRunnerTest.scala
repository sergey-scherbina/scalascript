package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, Value, Computation}
import scalascript.parser.Parser

/** Tests for the v0.9 `ssc test` runner mechanics:
 *  - `injectGlobal` makes a value available to user code before run()
 *  - The `test(name, thunk)` registration pattern works end-to-end
 *  - invoke(thunk, Nil) correctly calls a zero-arg lambda
 */
class SscTestRunnerTest extends AnyFunSuite with Matchers:

  private def makeInterp(): Interpreter =
    Interpreter(out = java.io.PrintStream(java.io.OutputStream.nullOutputStream))

  // ── injectGlobal ────────────────────────────────────────────────────

  test("injectGlobal makes a value visible to user code") {
    val tests = scala.collection.mutable.ArrayBuffer.empty[(String, Value)]
    val interp = makeInterp()
    interp.injectGlobal("test",
      Value.NativeFnV("test", Computation.pureFn {
        case List(Value.StringV(name), thunk) =>
          tests += (name -> thunk)
          Value.UnitV
        case _ => Value.UnitV
      })
    )
    val src = "# T\n\n```scala\ntest(\"hello\", () => true)\n```\n"
    interp.run(Parser.parse(src))
    tests.size shouldBe 1
    tests.head._1 shouldBe "hello"
  }

  test("injected test thunk returning true evaluates to BoolV(true)") {
    val tests = scala.collection.mutable.ArrayBuffer.empty[(String, Value)]
    val interp = makeInterp()
    interp.injectGlobal("test",
      Value.NativeFnV("test", Computation.pureFn {
        case List(Value.StringV(name), thunk) =>
          tests += (name -> thunk)
          Value.UnitV
        case _ => Value.UnitV
      })
    )
    val src = "# T\n\n```scala\ntest(\"two plus two\", () => 2 + 2 == 4)\n```\n"
    interp.run(Parser.parse(src))
    tests.size shouldBe 1
    interp.invoke(tests.head._2, Nil) shouldBe Value.BoolV(true)
  }

  test("injected test thunk returning false evaluates to BoolV(false)") {
    val tests = scala.collection.mutable.ArrayBuffer.empty[(String, Value)]
    val interp = makeInterp()
    interp.injectGlobal("test",
      Value.NativeFnV("test", Computation.pureFn {
        case List(Value.StringV(name), thunk) =>
          tests += (name -> thunk)
          Value.UnitV
        case _ => Value.UnitV
      })
    )
    val src = "# T\n\n```scala\ntest(\"always fails\", () => 1 == 2)\n```\n"
    interp.run(Parser.parse(src))
    interp.invoke(tests.head._2, Nil) shouldBe Value.BoolV(false)
  }

  test("multiple tests are collected in order") {
    val tests = scala.collection.mutable.ArrayBuffer.empty[(String, Value)]
    val interp = makeInterp()
    interp.injectGlobal("test",
      Value.NativeFnV("test", Computation.pureFn {
        case List(Value.StringV(name), thunk) =>
          tests += (name -> thunk)
          Value.UnitV
        case _ => Value.UnitV
      })
    )
    val src =
      "# T\n\n```scala\n" +
      "test(\"a\", () => true)\n" +
      "test(\"b\", () => false)\n" +
      "test(\"c\", () => true)\n" +
      "```\n"
    interp.run(Parser.parse(src))
    tests.map(_._1) shouldBe Seq("a", "b", "c")
    interp.invoke(tests(0)._2, Nil) shouldBe Value.BoolV(true)
    interp.invoke(tests(1)._2, Nil) shouldBe Value.BoolV(false)
    interp.invoke(tests(2)._2, Nil) shouldBe Value.BoolV(true)
  }

  test("test thunks can reference defs defined in the same module") {
    val tests = scala.collection.mutable.ArrayBuffer.empty[(String, Value)]
    val interp = makeInterp()
    interp.injectGlobal("test",
      Value.NativeFnV("test", Computation.pureFn {
        case List(Value.StringV(name), thunk) =>
          tests += (name -> thunk)
          Value.UnitV
        case _ => Value.UnitV
      })
    )
    val src =
      "# T\n\n```scala\n" +
      "def greet(name: String): String = \"Hello, \" + name + \"!\"\n" +
      "test(\"greet works\", () => greet(\"World\") == \"Hello, World!\")\n" +
      "```\n"
    interp.run(Parser.parse(src))
    interp.invoke(tests.head._2, Nil) shouldBe Value.BoolV(true)
  }

  // ── exportedGlobals after injectGlobal ──────────────────────────────

  test("exportedGlobals reflects injected values after run") {
    val interp = makeInterp()
    interp.injectGlobal("__testMarker", Value.StringV("v0.9"))
    interp.run(Parser.parse("# T\n"))
    interp.exportedGlobals.get("__testMarker") shouldBe Some(Value.StringV("v0.9"))
  }
