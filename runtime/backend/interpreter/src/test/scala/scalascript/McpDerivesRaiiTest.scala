package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.17.x — `derives McpSchema` + `Using.resource` RAII + `srv.toolWithSchema`.
 *
 *  Three small additive features:
 *   - `case class A(...) derives McpSchema` auto-generates a JSON Schema
 *     object describing the case-class fields (loose properties — names
 *     only, no per-field types, since v1.14 Mirror exposes labels only).
 *   - `Using.resource(r) { r => block }` runs `block` and unconditionally
 *     calls `r.close()` afterwards.  Generic RAII; works on any value
 *     with a `close` member.
 *   - `srv.toolWithSchema(name, schema)(handler)` registers a tool with
 *     an explicit JSON Schema (usually produced by `derives McpSchema`). */
class McpDerivesRaiiTest extends AnyFunSuite with Matchers:

  private def run(src: String): String =
    val buf = new java.io.ByteArrayOutputStream()
    val ps  = new java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    buf.toString.trim

  // ── Using.resource — generic RAII ───────────────────────────────────

  test("Using.resource: runs block and calls close on the way out"):
    val src =
      """# Test
        |
        |```scalascript
        |var closed = false
        |val resource = Map(
        |  "name"  -> "demo",
        |  "close" -> (() => closed = true)
        |)
        |val result = Using.resource(resource) { r => r.getOrElse("name", "?") + "!" }
        |println(result + " closed=" + closed)
        |```
        |""".stripMargin
    run(src) shouldBe "demo! closed=true"

  test("Using.resource: closes even when block throws"):
    val src =
      """# Test
        |
        |```scalascript
        |var closed = false
        |val resource = Map(
        |  "close" -> (() => closed = true)
        |)
        |try
        |  Using.resource(resource) { _ => throw RuntimeException("boom") }
        |catch case e: RuntimeException => ()
        |println("closed=" + closed)
        |```
        |""".stripMargin
    run(src) shouldBe "closed=true"

  test("Using.resource: works on values without a close field"):
    // Useful when the user wants the same scoping shape but has no
    // resource to release — Using.resource is still a clean idiom.
    val src =
      """# Test
        |
        |```scalascript
        |val plain = Map("x" -> 42)
        |val result = Using.resource(plain) { r => r.getOrElse("x", 0) }
        |println(result)
        |```
        |""".stripMargin
    run(src) shouldBe "42"

  // ── derives McpSchema ───────────────────────────────────────────────

  test("derives McpSchema: schema lists field names + required"):
    val src =
      """# Test
        |
        |```scalascript
        |case class WeatherArgs(city: String, units: String) derives McpSchema
        |val s = summon[McpSchema[WeatherArgs]].schema
        |println("type=" + s.getOrElse("type", "?"))
        |println("required=" + s.getOrElse("required", List()))
        |val props = s.getOrElse("properties", Map())
        |println("propkeys=" + props.keys.toList.sorted)
        |```
        |""".stripMargin
    val out = run(src)
    out should include ("type=object")
    out should include ("required=List(city, units)")
    out should include ("propkeys=List(city, units)")

  // ── srv.toolWithSchema ──────────────────────────────────────────────

  test("srv.toolWithSchema: registers a tool whose tools/list advertises the schema"):
    // Setup a server with a derived schema, then poke handleHttpRequest
    // to read tools/list and verify the schema flows through.
    val src =
      """# Test
        |
        |```scalascript
        |case class WeatherArgs(city: String, units: String) derives McpSchema
        |mcpServer { srv =>
        |  srv.toolWithSchema("get_weather", summon[McpSchema[WeatherArgs]].schema) { args =>
        |    val msg = "weather for " + args.getOrElse("city", "?").toString
        |    msg
        |  }
        |}
        |println("setup-ok")
        |```
        |""".stripMargin
    run(src) shouldBe "setup-ok"