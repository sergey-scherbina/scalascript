package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.{Interpreter, InterpretError}
import scalascript.parser.Parser
import scalascript.codegen.{JsGen, JsRuntime, JsRuntimeAsync}

import java.nio.charset.StandardCharsets
import scala.io.Source

/** v1.24 — Named function arguments: complete coverage across all 3 backends.
 *
 *  Tests cover:
 *  - Out-of-order named args
 *  - Mix of positional + named args
 *  - Named args with default parameter interaction (skip defaults by name)
 *  - Error on unknown argument name
 *  - Interpreter and JsGen backends
 */
class NamedArgTest extends AnyFunSuite with Matchers:

  // ─── Interpreter helpers ──────────────────────────────────────────

  private def captured(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    Interpreter(ps).run(module)
    ps.flush()
    buf.toString.trim

  private def interpreterError(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val module = Parser.parse(src)
    try
      Interpreter(ps).run(module)
      "no error"
    catch
      case e: InterpretError => e.getMessage
      case e: Exception      => e.getMessage

  // ─── JS helpers ───────────────────────────────────────────────────

  private def moduleOf(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def hasNode: Boolean =
    try ProcessBuilder("node", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def runJs(code: String): String =
    val flush = """process.stdout.write(_output.join('\n') + (_output.length ? '\n' : '')); _output = [];"""
    val js    = JsRuntime + "\n" + JsRuntimeAsync + "\n" + JsGen.generate(moduleOf(code)) + "\n" + flush
    val tmp   = java.io.File.createTempFile("ssc-namedarg-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc  = ProcessBuilder("node", tmp.getAbsolutePath)
      .redirectErrorStream(true)
      .start()
    val out   = Source.fromInputStream(proc.getInputStream).mkString
    proc.waitFor()
    out.trim

  // ─── Interpreter: basic named args ────────────────────────────────

  test("interpreter: named args in declaration order"):
    captured("""
      def greet(name: String, greeting: String): String = s"$greeting, $name!"
      println(greet(name = "Alice", greeting = "Hello"))
    """) shouldBe "Hello, Alice!"

  test("interpreter: named args out of order"):
    captured("""
      def greet(name: String, greeting: String): String = s"$greeting, $name!"
      println(greet(greeting = "Hi", name = "Bob"))
    """) shouldBe "Hi, Bob!"

  test("interpreter: fully reordered named args — 3 params"):
    captured("""
      def createUser(name: String, age: Int, role: String): String =
        s"$name/$age/$role"
      println(createUser(age = 25, name = "Bob", role = "admin"))
    """) shouldBe "Bob/25/admin"

  test("interpreter: mixed positional + named args"):
    captured("""
      def createUser(name: String, age: Int, role: String): String =
        s"$name/$age/$role"
      println(createUser("Carol", age = 28, role = "user"))
    """) shouldBe "Carol/28/user"

  test("interpreter: named args with default — skip trailing default"):
    captured("""
      def createUser(name: String, age: Int, role: String = "user"): String =
        s"$name/$age/$role"
      println(createUser(name = "Alice", age = 30))
    """) shouldBe "Alice/30/user"

  test("interpreter: named args with defaults — supply non-trailing by name"):
    captured("""
      def info(a: Int = 1, b: Int = 2, c: Int = 3): String =
        s"$a,$b,$c"
      println(info(b = 99))
    """) shouldBe "1,99,3"

  test("interpreter: named args — fully reordered with default"):
    captured("""
      def createUser(name: String, age: Int, role: String = "user"): String =
        s"$name/$age/$role"
      println(createUser(age = 25, name = "Bob", role = "admin"))
    """) shouldBe "Bob/25/admin"

  test("interpreter: positional + named + skipped default"):
    captured("""
      def f(x: Int, y: Int, z: Int = 0): String = s"$x/$y/$z"
      println(f(1, z = 10, y = 2))
    """) shouldBe "1/2/10"

  // ─── Interpreter: error cases ─────────────────────────────────────

  test("interpreter: unknown arg name raises error"):
    val msg = interpreterError("""
      def greet(name: String): String = name
      println(greet(nme = "Alice"))
    """)
    msg should include ("nme")

  // ─── JsGen backend: named args ────────────────────────────────────

  test("JsGen: named args in declaration order"):
    assume(hasNode, "node not available")
    runJs("""
      def greet(name: String, greeting: String): String = greeting + ", " + name + "!"
      println(greet(name = "Alice", greeting = "Hello"))
    """) shouldBe "Hello, Alice!"

  test("JsGen: named args out of order"):
    assume(hasNode, "node not available")
    runJs("""
      def greet(name: String, greeting: String): String = greeting + ", " + name + "!"
      println(greet(greeting = "Hi", name = "Bob"))
    """) shouldBe "Hi, Bob!"

  test("JsGen: fully reordered named args — 3 params"):
    assume(hasNode, "node not available")
    runJs("""
      def createUser(name: String, age: Int, role: String): String =
        name + "/" + age.toString + "/" + role
      println(createUser(age = 25, name = "Bob", role = "admin"))
    """) shouldBe "Bob/25/admin"

  test("JsGen: mixed positional + named args"):
    assume(hasNode, "node not available")
    runJs("""
      def createUser(name: String, age: Int, role: String): String =
        name + "/" + age.toString + "/" + role
      println(createUser("Carol", age = 28, role = "user"))
    """) shouldBe "Carol/28/user"

  test("JsGen: named args with default — skip trailing default"):
    assume(hasNode, "node not available")
    runJs("""
      def createUser(name: String, age: Int, role: String = "user"): String =
        name + "/" + age.toString + "/" + role
      println(createUser(name = "Alice", age = 30))
    """) shouldBe "Alice/30/user"

  test("JsGen: named args — fully reordered with default"):
    assume(hasNode, "node not available")
    runJs("""
      def createUser(name: String, age: Int, role: String = "user"): String =
        name + "/" + age.toString + "/" + role
      println(createUser(age = 25, name = "Bob", role = "admin"))
    """) shouldBe "Bob/25/admin"
