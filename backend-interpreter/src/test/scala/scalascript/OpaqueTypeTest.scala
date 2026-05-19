package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.typer.Typer

/** v1.24 — Opaque type tests.
 *
 *  Opaque types have zero runtime overhead: their underlying representation
 *  is the same as the aliased type.  At type-check time they are treated as
 *  distinct nominal types outside the defining scope, so assigning a raw
 *  String where UserId is expected is a type error. */
class OpaqueTypeTest extends AnyFunSuite with Matchers:

  // ── helpers ─────────────────────────────────────────────────────────────────

  private def run(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  private def moduleOf(code: String): scalascript.ast.Module =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  // ── 1. Basic opaque type — construct via companion apply ─────────────────────

  test("opaque type — construct via companion apply and use in function"):
    run("""
      opaque type UserId = String

      object UserId:
        def apply(s: String): UserId = s

      def greet(id: UserId): String = s"Hello $id"

      val id: UserId = UserId("alice")
      println(greet(id))
    """) shouldBe "Hello alice"

  // ── 2. Opaque type — wrapped value can be printed ────────────────────────────

  test("opaque type — wrapped value prints as underlying type"):
    run("""
      opaque type Email = String

      object Email:
        def apply(s: String): Email = s

      val e: Email = Email("user@example.com")
      println(e)
    """) shouldBe "user@example.com"

  // ── 3. Opaque type — comparison works via underlying rep ─────────────────────

  test("opaque type — equality uses underlying representation"):
    run("""
      opaque type Tag = String

      object Tag:
        def apply(s: String): Tag = s

      val t1: Tag = Tag("scala")
      val t2: Tag = Tag("scala")
      println(t1 == t2)
    """) shouldBe "true"

  // ── 4. Opaque type — unapply companion method defined and callable ──────────

  test("opaque type — unapply companion method is callable"):
    run("""
      opaque type UserId = String

      object UserId:
        def apply(s: String): UserId = s
        def unapply(id: UserId): Option[String] = Some(id)

      val id: UserId = UserId("bob")
      val result = UserId.unapply(id)
      println(result)
    """) shouldBe "Some(bob)"

  // ── 5. Opaque type — auto-generated companion (no explicit object) ──────────

  test("opaque type — auto-generated companion apply works"):
    run("""
      opaque type Score = Int

      val s: Score = Score(42)
      println(s)
    """) shouldBe "42"

  // ── 6. Type checker — raw String assignment to opaque type is a type error ───

  test("opaque type — raw String literal assigned to opaque type is a type error"):
    val typed = Typer.typeCheck(moduleOf("""
      opaque type UserId = String

      val bad: UserId = "raw string"
    """))
    assert(typed.hasErrors,
      "assigning a raw String to an opaque UserId should produce a type error")
    val msgs = typed.errors.map(_.msg).mkString(" | ")
    assert(msgs.contains("UserId") || msgs.contains("mismatch"),
      s"expected a type-mismatch error mentioning UserId; got: $msgs")

  // ── 7. Type checker — companion apply result is acceptable ─────────────────

  test("opaque type — companion-constructed value passes type check"):
    val typed = Typer.typeCheck(moduleOf("""
      opaque type UserId = String

      object UserId:
        def apply(s: String): UserId = s

      val id: UserId = UserId("alice")
    """))
    // The companion call returns SType.Any (object apply not deeply tracked),
    // which is compatible with everything — no errors expected.
    assert(!typed.hasErrors,
      s"expected no type errors; got:\n${typed.errors.map(_.show).mkString("\n")}")

  // ── 8. Opaque type — multiple opaque types don't interfere ───────────────────

  test("opaque type — multiple distinct opaque types coexist"):
    run("""
      opaque type Name = String
      opaque type Role = String

      object Name:
        def apply(s: String): Name = s
      object Role:
        def apply(s: String): Role = s

      def describe(n: Name, r: Role): String = s"$n is $r"

      val n: Name = Name("Alice")
      val r: Role = Role("admin")
      println(describe(n, r))
    """) shouldBe "Alice is admin"
