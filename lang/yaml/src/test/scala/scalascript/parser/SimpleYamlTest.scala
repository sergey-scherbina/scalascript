package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters.*

class SimpleYamlTest extends AnyFunSuite with Matchers:

  private def loadMap(src: String): Map[String, Any] =
    SimpleYaml.load[java.util.Map[String, Any]](src).asScala.toMap

  // ── scalars ───────────────────────────────────────────────────────────────

  test("parse null") {
    assert(SimpleYaml.load[Any]("null") == null)
    assert(SimpleYaml.load[Any]("~")    == null)
  }

  test("parse boolean") {
    SimpleYaml.load[Any]("true")  shouldBe java.lang.Boolean.TRUE
    SimpleYaml.load[Any]("false") shouldBe java.lang.Boolean.FALSE
    SimpleYaml.load[Any]("True")  shouldBe java.lang.Boolean.TRUE
  }

  test("parse integer") {
    SimpleYaml.load[Any]("42")  shouldBe (42: java.lang.Integer)
    SimpleYaml.load[Any]("-7")  shouldBe (-7: java.lang.Integer)
  }

  test("parse double") {
    SimpleYaml.load[Any]("3.14")  shouldBe (3.14: java.lang.Double)
    SimpleYaml.load[Any]("1.0e2") shouldBe (100.0: java.lang.Double)
  }

  test("parse plain string") {
    SimpleYaml.load[Any]("hello") shouldBe "hello"
  }

  test("parse double-quoted string with escapes") {
    SimpleYaml.load[Any]("\"hello\\nworld\"") shouldBe "hello\nworld"
    SimpleYaml.load[Any]("\"tab\\there\"")    shouldBe "tab\there"
  }

  test("parse single-quoted string") {
    SimpleYaml.load[Any]("'it''s'") shouldBe "it's"
  }

  // ── block mappings ─────────────────────────────────────────────────────────

  test("parse flat block map") {
    val m = loadMap("a: 1\nb: hello\nc: true")
    m("a") shouldBe (1: java.lang.Integer)
    m("b") shouldBe "hello"
    m("c") shouldBe java.lang.Boolean.TRUE
  }

  test("parse nested block map") {
    val yaml = """
      |outer:
      |  inner: value
      |  num: 42
      |""".stripMargin
    val m = loadMap(yaml)
    val inner = m("outer").asInstanceOf[java.util.Map[String, Any]].asScala
    inner("inner") shouldBe "value"
    inner("num")   shouldBe (42: java.lang.Integer)
  }

  test("block map with quoted keys containing colons") {
    val yaml = """
      |"https://example.com/lib.ssc":
      |  sha256: abc123
      |  fetchedAt: "2026-05-18"
      |""".stripMargin
    val m = loadMap(yaml)
    m should contain key "https://example.com/lib.ssc"
    val entry = m("https://example.com/lib.ssc").asInstanceOf[java.util.Map[String, Any]].asScala
    entry("sha256") shouldBe "abc123"
  }

  // ── block sequences ────────────────────────────────────────────────────────

  test("parse block sequence") {
    val yaml = "- alpha\n- beta\n- gamma"
    val list = SimpleYaml.load[java.util.List[Any]](yaml).asScala.toList
    list shouldBe List("alpha", "beta", "gamma")
  }

  test("parse sequence of maps") {
    val yaml = "- id: a\n  url: http://a\n- id: b\n  url: http://b"
    val list = SimpleYaml.load[java.util.List[Any]](yaml).asScala.toList
    list should have size 2
    list(0).asInstanceOf[java.util.Map[String, Any]].asScala("id") shouldBe "a"
    list(1).asInstanceOf[java.util.Map[String, Any]].asScala("url") shouldBe "http://b"
  }

  // ── flow structures ────────────────────────────────────────────────────────

  test("parse flow sequence inline") {
    val m = loadMap("targets: [jvm, js, wasm]")
    val list = m("targets").asInstanceOf[java.util.List[Any]].asScala.toList
    list shouldBe List("jvm", "js", "wasm")
  }

  test("parse flow map inline") {
    val m = loadMap("deps: {a: 1, b: 2}")
    val inner = m("deps").asInstanceOf[java.util.Map[String, Any]].asScala
    inner("a") shouldBe (1: java.lang.Integer)
    inner("b") shouldBe (2: java.lang.Integer)
  }

  // ── comments ──────────────────────────────────────────────────────────────

  test("comments are stripped") {
    val yaml = "key: value # this is a comment\nother: 42 # another"
    val m = loadMap(yaml)
    m("key")   shouldBe "value"
    m("other") shouldBe (42: java.lang.Integer)
  }

  test("hash inside quoted string is not a comment") {
    val m = loadMap("key: \"color #fff\"")
    m("key") shouldBe "color #fff"
  }

  // ── null / empty input ────────────────────────────────────────────────────

  test("empty input returns null") {
    assert(SimpleYaml.load[Any]("") == null)
    assert(SimpleYaml.load[Any]("  \n  ") == null)
  }

  test("null yaml value") {
    val m = loadMap("present: value\nmissing:")
    assert(m("missing") == null)
  }

  // ── literal block scalar ──────────────────────────────────────────────────

  test("parse literal block scalar (|)") {
    val yaml = "text: |\n  line one\n  line two"
    val m = loadMap(yaml)
    m("text").asInstanceOf[String] should (include("line one") and include("line two"))
  }

  // ── lockfile / frontmatter shapes ─────────────────────────────────────────

  test("lock file shape") {
    val yaml =
      """version: 1
        |imports:
        |  "https://example.com/lib.ssc":
        |    sha256: abc123
        |    fetchedAt: "2026-05-18"
        |  "dep:org.example/lib:1.2":
        |    resolvedUrl: "https://pkg.example.com/lib.ssc"
        |    sha256: def456
        |    fetchedAt: "2026-05-18"
        |""".stripMargin
    val root = loadMap(yaml)
    root("version") shouldBe (1: java.lang.Integer)
    val imports = root("imports").asInstanceOf[java.util.Map[String, Any]].asScala
    imports should have size 2
    imports("https://example.com/lib.ssc")
      .asInstanceOf[java.util.Map[String, Any]].asScala("sha256") shouldBe "abc123"
  }

  test("plugin.yaml shape") {
    val yaml =
      """id: my-plugin
        |displayName: My Plugin
        |spiVersion: "0.1"
        |protocol: stdio-json
        |executable: ./bin/my-plugin
        |roles:
        |  - backend
        |backend:
        |  features:
        |    - codegen
        |  outputs:
        |    - jvm
        |""".stripMargin
    val m = loadMap(yaml)
    m("id")       shouldBe "my-plugin"
    m("protocol") shouldBe "stdio-json"
    val roles = m("roles").asInstanceOf[java.util.List[Any]].asScala.toList
    roles shouldBe List("backend")
    val backend = m("backend").asInstanceOf[java.util.Map[String, Any]].asScala
    val features = backend("features").asInstanceOf[java.util.List[Any]].asScala.toList
    features shouldBe List("codegen")
  }
