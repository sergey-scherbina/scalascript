package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import scalascript.backend.spi.InterpolatorImpl
import scalascript.compiler.plugin.InterpolatorRegistry
import scalascript.codegen.{JvmGen, JsGen}
import scalascript.parser.Parser

/** arch-dsl-hooks-p1 — JvmGen / JsGen delegation to InterpolatorRegistry.
 *
 *  Verifies that:
 *  - JvmGen calls impl.jvmEmit for registered interpolators.
 *  - JsGen calls impl.jsEmit for registered interpolators.
 *  - Both fall back to raw Scala / _ext_StringContext for unregistered.
 */
class DslHooksCodegenTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  object SqlInterpolator extends InterpolatorImpl:
    override val name           = "sql"
    override val returnTypeName = "SqlQuery"
    override def jvmEmit(parts: List[String], args: List[String]): String =
      val partsStr = parts.map(p => "\"" + p + "\"").mkString(", ")
      s"SqlQuery(List($partsStr), List(${args.mkString(", ")}))"
    override def jsEmit(parts: List[String], args: List[String]): String =
      val partsStr = parts.map(p => "\"" + p + "\"").mkString(", ")
      s"_sql([$partsStr], [${args.mkString(", ")}])"

  override def beforeAll(): Unit =
    InterpolatorRegistry.register(SqlInterpolator)

  override def afterAll(): Unit =
    InterpolatorRegistry.unregister(SqlInterpolator.name)

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def jvm(code: String): String = JvmGen.generate(module(code))
  private def js(code: String):  String = JsGen.generate(module(code))

  // ── JvmGen ──────────────────────────────────────────────────────────

  test("JvmGen: registered interpolator uses jvmEmit instead of StringContext path") {
    val out = jvm("""val q = sql"SELECT * FROM users"""")
    out should include("SqlQuery(")
    out should not include("""sql"SELECT""")
  }

  test("JvmGen: registered interpolator with arg uses jvmEmit with arg string") {
    val out = jvm(
      """val table = "users"
        |val q = sql"SELECT * FROM ${table}"""".stripMargin)
    out should include("SqlQuery(")
    out should include("table")
  }

  test("JvmGen: unregistered interpolator emits raw Scala syntax") {
    val out = jvm("""val q = unknown99"hello world"""")
    out should include("unknown99")
    out should not include("SqlQuery")
  }

  // ── JsGen ───────────────────────────────────────────────────────────

  test("JsGen: registered interpolator uses jsEmit instead of _ext_StringContext path") {
    val out = js("""val q = sql"SELECT * FROM users"""")
    out should include("_sql(")
    out should not include("_ext_StringContext_sql")
  }

  test("JsGen: registered interpolator with arg uses jsEmit with arg string") {
    val out = js(
      """val table = "users"
        |val q = sql"SELECT * FROM ${table}"""".stripMargin)
    out should include("_sql(")
    out should include("table")
  }

  test("JsGen: unregistered interpolator uses _ext_StringContext fallback") {
    val out = js("""val q = unknown99"hello world"""")
    out should include("_ext_StringContext_unknown99")
    out should not include("_sql")
  }

  // ── html built-in stays in registry ─────────────────────────────────

  test("InterpolatorRegistry: html is pre-registered with name html") {
    InterpolatorRegistry.lookup("html").map(_.name) should be(Some("html"))
  }
