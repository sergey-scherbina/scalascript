package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JsRuntime}
import scalascript.codegen.JvmGen
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets
import scala.io.Source

/** v1.24 — Extension methods on user-defined case class types.
 *
 *  Verifies that `extension (u: User)` blocks register and dispatch
 *  correctly on all three backends (interpreter, JS codegen, JVM codegen)
 *  for:
 *    1. Simple no-arg extension methods (fullName, isAdmin)
 *    2. Multi-method extension blocks
 *    3. Extensions that take additional arguments (greet(greeting: String))
 *    4. Two different case classes with the same extension method name —
 *       no collision, each routes to its own implementation.
 */
class ExtensionMethodTest extends AnyFunSuite with Matchers:

  // ── helpers ──────────────────────────────────────────────────────────────

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(module(code))
    ps.flush()
    buf.toString.trim

  // ── Interpreter ───────────────────────────────────────────────────────────

  test("Interpreter: no-arg extension methods on User case class"):
    run("""
      case class User(first: String, last: String, role: String)

      extension (u: User)
        def fullName: String = s"${u.first} ${u.last}"
        def isAdmin: Boolean = u.role == "admin"

      val u = User("Alice", "Smith", "admin")
      println(u.fullName)
      println(u.isAdmin)
    """) shouldBe "Alice Smith\ntrue"

  test("Interpreter: multi-method extension block on User"):
    run("""
      case class User(first: String, last: String, role: String)

      extension (u: User)
        def fullName: String = s"${u.first} ${u.last}"
        def isAdmin: Boolean = u.role == "admin"
        def label: String = s"${u.fullName} (${u.role})"

      val u = User("Bob", "Jones", "viewer")
      println(u.fullName)
      println(u.isAdmin)
      println(u.label)
    """) shouldBe "Bob Jones\nfalse\nBob Jones (viewer)"

  test("Interpreter: extension with additional argument on User"):
    run("""
      case class User(first: String, last: String, role: String)

      extension (u: User)
        def greet(greeting: String): String = s"${greeting}, ${u.first}!"

      val u = User("Alice", "Smith", "admin")
      println(u.greet("Hello"))
      println(u.greet("Hi"))
    """) shouldBe "Hello, Alice!\nHi, Alice!"

  test("Interpreter: primitive extension with two additional arguments"):
    run("""
      extension (n: Int)
        def between(lo: Int, hi: Int): Boolean = n >= lo && n <= hi

      println(5.between(1, 10))
      println(12.between(1, 10))
    """) shouldBe "true\nfalse"

  test("Interpreter: no collision between same-named extensions on different case classes"):
    run("""
      case class Dog(name: String)
      case class Cat(name: String)

      extension (d: Dog) def sound: String = "Woof"
      extension (c: Cat) def sound: String = "Meow"

      val dog = Dog("Rex")
      val cat = Cat("Whiskers")
      println(dog.sound)
      println(cat.sound)
    """) shouldBe "Woof\nMeow"

  test("Interpreter: non-admin User isAdmin returns false"):
    run("""
      case class User(first: String, last: String, role: String)

      extension (u: User)
        def fullName: String = s"${u.first} ${u.last}"
        def isAdmin: Boolean = u.role == "admin"

      val u = User("Bob", "Jones", "viewer")
      println(u.fullName)
      println(u.isAdmin)
    """) shouldBe "Bob Jones\nfalse"

  // ── JS codegen shape ──────────────────────────────────────────────────────

  test("JsGen: extension on User case class registers typed extension"):
    val js = JsGen.generate(module("""
      case class User(first: String, last: String, role: String)
      extension (u: User)
        def fullName: String = s"${u.first} ${u.last}"
        def isAdmin: Boolean = u.role == "admin"
      val u = User("Alice", "Smith", "admin")
      println(u.fullName)
      println(u.isAdmin)
    """))
    // Extension functions emitted with receiver type in name
    js should include ("_ext_User_fullName(")
    js should include ("_ext_User_isAdmin(")
    // Registered with type tag so _typeOf(u) => 'User' finds them
    js should include ("_registerExt('fullName'")
    js should include ("_registerExt('isAdmin'")
    js should include ("'User'")

  test("JsGen: extension with arg on User emits correct function"):
    val js = JsGen.generate(module("""
      case class User(first: String, last: String, role: String)
      extension (u: User)
        def greet(greeting: String): String = s"${greeting}, ${u.first}!"
      val u = User("Alice", "Smith", "admin")
      println(u.greet("Hello"))
    """))
    js should include ("_ext_User_greet(")
    js should include ("_registerExt('greet'")

  test("JsGen: two case classes with same extension name emit distinct functions"):
    val js = JsGen.generate(module("""
      case class Dog(name: String)
      case class Cat(name: String)
      extension (d: Dog) def sound: String = "Woof"
      extension (c: Cat) def sound: String = "Meow"
      val dog = Dog("Rex")
      val cat = Cat("Whiskers")
      println(dog.sound)
      println(cat.sound)
    """))
    js should include ("_ext_Dog_sound(")
    js should include ("_ext_Cat_sound(")

  // ── JS execution via node ─────────────────────────────────────────────────

  private def hasNode: Boolean =
    try ProcessBuilder("node", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def runJs(code: String): String =
    val flush = """process.stdout.write(_output.join('\n') + (_output.length ? '\n' : '')); _output = [];"""
    val js    = JsRuntime + "\n" + JsGen.generate(module(code)) + "\n" + flush
    val tmp   = java.io.File.createTempFile("ssc-ext-", ".cjs")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, js.getBytes(StandardCharsets.UTF_8))
    val proc  = ProcessBuilder("node", tmp.getAbsolutePath)
      .redirectErrorStream(true)
      .start()
    val out = Source.fromInputStream(proc.getInputStream).mkString
    proc.waitFor()
    out.trim

  test("JsGen: no-arg extension on User case class runs correctly via node"):
    assume(hasNode, "node not available")
    runJs("""
      case class User(first: String, last: String, role: String)
      extension (u: User)
        def fullName: String = s"${u.first} ${u.last}"
        def isAdmin: Boolean = u.role == "admin"
      val u = User("Alice", "Smith", "admin")
      println(u.fullName)
      println(u.isAdmin)
    """) shouldBe "Alice Smith\ntrue"

  test("JsGen: extension with arg on User runs correctly via node"):
    assume(hasNode, "node not available")
    runJs("""
      case class User(first: String, last: String, role: String)
      extension (u: User)
        def greet(greeting: String): String = s"${greeting}, ${u.first}!"
      val u = User("Alice", "Smith", "admin")
      println(u.greet("Hello"))
    """) shouldBe "Hello, Alice!"

  test("JsGen: no collision between same-named extensions on different case classes via node"):
    assume(hasNode, "node not available")
    runJs("""
      case class Dog(name: String)
      case class Cat(name: String)
      extension (d: Dog) def sound: String = "Woof"
      extension (c: Cat) def sound: String = "Meow"
      val dog = Dog("Rex")
      val cat = Cat("Whiskers")
      println(dog.sound)
      println(cat.sound)
    """) shouldBe "Woof\nMeow"

  // ── JvmGen code shape ─────────────────────────────────────────────────────

  private def jvmCode(code: String): String = JvmGen.generate(module(code))

  test("JvmGen: extension on User case class passes through as Scala 3 extension syntax"):
    val code = jvmCode("""
      case class User(first: String, last: String, role: String)
      extension (u: User)
        def fullName: String = s"${u.first} ${u.last}"
        def isAdmin: Boolean = u.role == "admin"
      val u = User("Alice", "Smith", "admin")
      println(u.fullName)
      println(u.isAdmin)
    """)
    // Extension definition must be emitted (Scala 3 natively handles it)
    code should include ("def fullName")
    code should include ("def isAdmin")
    code should include ("extension")
    // Case class must be emitted
    code should include ("case class User")

  test("JvmGen: extension with arg on User passes through correctly"):
    val code = jvmCode("""
      case class User(first: String, last: String, role: String)
      extension (u: User)
        def greet(greeting: String): String = s"${greeting}, ${u.first}!"
      val u = User("Alice", "Smith", "admin")
      println(u.greet("Hello"))
    """)
    code should include ("def greet")
    code should include ("extension")
    code should include ("case class User")
