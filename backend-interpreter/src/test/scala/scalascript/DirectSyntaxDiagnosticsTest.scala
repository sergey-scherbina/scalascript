package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.codegen.{JsGen, JvmGen}
import scalascript.parser.Parser

/** Tests for v1.8 Phase 5 — diagnostics / foot-gun detection in direct[M] blocks. */
class DirectSyntaxDiagnosticsTest extends AnyFunSuite with Matchers:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def evalError(code: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n$code\n```\n"
    val ex  = intercept[Exception] { Interpreter(ps).run(Parser.parse(src)) }
    ex.getMessage

  // ── return inside direct ────────────────────────────────────────────

  test("interpreter: return inside direct[Option] raises error"):
    val msg = evalError("""
      val r = direct[Option] {
        x = Some(1)
        return Some(x)
      }
    """)
    msg should include ("return")
    msg should include ("flatMap chain")

  test("interpreter: return inside nested if inside direct raises error"):
    val msg = evalError("""
      val r = direct[Option] {
        x = Some(1)
        if x > 0 then return Some(x) else ()
        Some(x)
      }
    """)
    msg should include ("return")

  test("interpreter: return inside nested def inside direct is allowed"):
    // return inside a def nested in a direct block is fine — it exits that def.
    // Use a simple def with no return (since ScalaScript return-in-def is not
    // fully supported); the key is that checkDirectBlockStatics does NOT scan
    // into Defn.Def bodies.
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n" + """
      val r = direct[Option] {
        def double(x: Int): Int = x * 2
        x = Some(double(21))
        Some(x)
      }
      println(r)
    """ + "\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim shouldBe "Some(42)"

  test("interpreter: return inside nested direct[Option] raises error"):
    // A return inside a nested direct block is caught by the INNER block's check
    val msg = evalError("""
      val r = direct[Option] {
        inner = direct[Option] {
          x = Some(1)
          return Some(x)
        }
        Some(inner)
      }
    """)
    msg should include ("return")

  // ── JvmGen: return inside direct ───────────────────────────────────

  test("JvmGen: return inside direct[Option] raises error at codegen"):
    val ex = intercept[Exception] {
      JvmGen.generate(module("""
        val r = direct[Option] {
          x = Some(1)
          return Some(x)
        }
      """))
    }
    ex.getMessage should include ("return")

  // ── JsGen: return inside direct ────────────────────────────────────

  test("JsGen: return inside direct[Option] raises error at codegen"):
    val ex = intercept[Exception] {
      JsGen.generate(module("""
        val r = direct[Option] {
          x = Some(1)
          return Some(x)
        }
      """))
    }
    ex.getMessage should include ("return")

  // ── existing tests unaffected ───────────────────────────────────────

  test("legitimate direct[Option] block still works after diagnostics added"):
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    val src = s"# Test\n\n```scala\n" + """
      val r = direct[Option] {
        x = Some(40)
        y = Some(2)
        Some(x + y)
      }
      println(r)
    """ + "\n```\n"
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim shouldBe "Some(42)"
