package scalascript.yaml

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*

class YamlParserTest extends AnyFunSuite with Matchers:

  private def map(yaml: String): Map[String, Any] =
    YamlParser.load(yaml).asInstanceOf[java.util.Map[String, Any]].asScala.toMap

  private def list(yaml: String): List[Any] =
    YamlParser.load(yaml).asInstanceOf[java.util.List[Any]].asScala.toList

  // ── Scalar coercion ───────────────────────────────────────────────────────

  test("scalar: null") {
    YamlParser.load("null") shouldBe (null: Any)
    YamlParser.load("~")    shouldBe (null: Any)
    YamlParser.load("")     shouldBe (null: Any)
  }

  test("scalar: boolean") {
    YamlParser.load("true")  shouldBe java.lang.Boolean.TRUE
    YamlParser.load("false") shouldBe java.lang.Boolean.FALSE
  }

  test("scalar: integers") {
    YamlParser.load("42")  shouldBe java.lang.Integer.valueOf(42)
    YamlParser.load("-7")  shouldBe java.lang.Integer.valueOf(-7)
    YamlParser.load("3000000000") shouldBe java.lang.Long.valueOf(3000000000L)
  }

  test("scalar: double") {
    YamlParser.load("3.14") shouldBe java.lang.Double.valueOf(3.14)
  }

  test("scalar: plain string preserved (version)") {
    YamlParser.load("1.0.0") shouldBe "1.0.0"
    YamlParser.load("^1.0") shouldBe "^1.0"
  }

  test("scalar: double-quoted value parsed correctly") {
    val m = map("label: \"simple value\"")
    m("label") shouldBe "simple value"
  }

  test("scalar: single-quoted with '' escape (in map value)") {
    val m = map("note: 'it''s fine'")
    m("note") shouldBe "it's fine"
  }

  // ── Block mapping ─────────────────────────────────────────────────────────

  test("block map: simple key-value pairs") {
    val m = map("name: hello\nversion: 1.0.0\nport: 8080")
    m("name")    shouldBe "hello"
    m("version") shouldBe "1.0.0"
    m("port")    shouldBe java.lang.Integer.valueOf(8080)
  }

  test("block map: nested") {
    val m = map("server:\n  host: localhost\n  port: 9090")
    val server = m("server").asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    server("host") shouldBe "localhost"
    server("port") shouldBe java.lang.Integer.valueOf(9090)
  }

  test("block map: bare key (null value)") {
    val m = map("name: foo\nempty:")
    m("name")  shouldBe "foo"
    m("empty") shouldBe (null: Any)
  }

  test("block map: quoted key with colon (URL)") {
    val yaml = "version: 1\nimports:\n  \"https://example.com/lib.ssc\":\n    sha256: abc123\n"
    val m    = map(yaml)
    m("version") shouldBe java.lang.Integer.valueOf(1)
    val imports  = m("imports").asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    val entry    = imports("https://example.com/lib.ssc").asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    entry("sha256") shouldBe "abc123"
  }

  test("block map: inline comments stripped") {
    val m = map("host: localhost  # the server\nport: 9090 # number")
    m("host") shouldBe "localhost"
    m("port") shouldBe java.lang.Integer.valueOf(9090)
  }

  test("block map: quoted value with colon") {
    val m = map("""description: "foo: bar baz"""")
    m("description") shouldBe "foo: bar baz"
  }

  // ── Block sequence ────────────────────────────────────────────────────────

  test("block sequence: strings") {
    val l = list("- foo\n- bar\n- baz")
    l shouldBe List("foo", "bar", "baz")
  }

  test("block sequence: nested in map") {
    val m = map("exports:\n  - FooClass\n  - BarClass\n")
    val exports = m("exports").asInstanceOf[java.util.List[Any]].asScala.toList
    exports shouldBe List("FooClass", "BarClass")
  }

  test("block sequence of mappings (routes pattern)") {
    val yaml =
      """|routes:
         |  - method: GET
         |    path: /api/todos
         |    handler: listTodos
         |  - method: POST
         |    path: /api/todos
         |    handler: createTodo
         |""".stripMargin
    val m      = map(yaml)
    val routes = m("routes").asInstanceOf[java.util.List[Any]].asScala.toList
    routes should have size 2
    val r0 = routes(0).asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    r0("method")  shouldBe "GET"
    r0("path")    shouldBe "/api/todos"
    r0("handler") shouldBe "listTodos"
    val r1 = routes(1).asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    r1("method")  shouldBe "POST"
  }

  // ── Flow collections ──────────────────────────────────────────────────────

  test("flow sequence as value") {
    val m = map("kind: [library, plugin]")
    val kind = m("kind").asInstanceOf[java.util.List[Any]].asScala.toList
    kind shouldBe List("library", "plugin")
  }

  test("flow mapping root (JSON document)") {
    val m = YamlParser.load("""{"key": "value", "num": 42}""")
              .asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    m("key") shouldBe "value"
    m("num") shouldBe java.lang.Integer.valueOf(42)
  }

  test("nested flow in block map") {
    val yaml =
      """|dependencies:
         |  foo: "^1.0.0"
         |  bar: ">=2.0"
         |""".stripMargin
    val m    = map(yaml)
    val deps = m("dependencies").asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    deps("foo") shouldBe "^1.0.0"
    deps("bar") shouldBe ">=2.0"
  }

  // ── Round-trip with LockFile format ───────────────────────────────────────

  test("LockFile-style YAML parses correctly") {
    val yaml =
      """|version: 1
         |imports:
         |  "https://example.com/a.ssc":
         |    sha256: deadbeef
         |    fetchedAt: "2026-05-18"
         |  "dep:org.example/lib:1.2":
         |    sha256: cafebabe
         |    fetchedAt: "2026-05-18"
         |    resolvedUrl: "https://packages.example.com/lib.ssc"
         |""".stripMargin
    val m       = map(yaml)
    m("version") shouldBe java.lang.Integer.valueOf(1)
    val imports = m("imports").asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    imports should have size 2
    val e1 = imports("https://example.com/a.ssc").asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    e1("sha256") shouldBe "deadbeef"
    val e2 = imports("dep:org.example/lib:1.2").asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    e2("resolvedUrl") shouldBe "https://packages.example.com/lib.ssc"
  }

  // ── Edge cases ────────────────────────────────────────────────────────────

  test("blank lines and comment-only lines are skipped") {
    val yaml = "\n# comment\nname: foo\n\n# another\nport: 3000\n"
    val m = map(yaml)
    m("name") shouldBe "foo"
    m("port") shouldBe java.lang.Integer.valueOf(3000)
  }

  test("empty document returns null") {
    YamlParser.load("") shouldBe (null: Any)
    YamlParser.load("  ") shouldBe (null: Any)
    YamlParser.load("# just a comment\n") shouldBe (null: Any)
  }
