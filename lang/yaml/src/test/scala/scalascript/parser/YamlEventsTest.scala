package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters.*

class YamlEventsTest extends AnyFunSuite:

  /** Parse `src` with YamlEvents, emit back to text, then verify SimpleYaml
   *  produces the same result as parsing the original `src`. */
  private def roundtrip(src: String): Unit =
    val events  = YamlEvents.parse(src)
    val emitted = YamlEvents.emit(events)
    val original = SimpleYaml.load[Any](src)
    val rebuilt  = SimpleYaml.load[Any](emitted)
    assert(yamlEq(original, rebuilt), s"round-trip mismatch\noriginal:\n$src\nemitted:\n$emitted")

  /** Structural equality for SimpleYaml results (Map/List/scalar). */
  private def yamlEq(a: Any, b: Any): Boolean = (a, b) match
    case (null, null)   => true
    case (null, _) | (_, null) => false
    case (ma: java.util.Map[?, ?], mb: java.util.Map[?, ?]) =>
      val ka = ma.keySet.asScala.map(_.toString).toSet
      val kb = mb.keySet.asScala.map(_.toString).toSet
      ka == kb && ka.forall(k => yamlEq(ma.get(k), mb.get(k)))
    case (la: java.util.List[?], lb: java.util.List[?]) =>
      la.size == lb.size && la.asScala.zip(lb.asScala).forall((x, y) => yamlEq(x, y))
    case _ => a.toString == b.toString

  // ─── fromAny round-trip ───────────────────────────────────────────────────

  test("fromAny: null scalar") {
    val events  = YamlEvents.fromAny(null)
    val emitted = YamlEvents.emit(events)
    assert(emitted.contains("null"))
  }

  test("fromAny: integer") {
    val events  = YamlEvents.fromAny(42: java.lang.Integer)
    val emitted = YamlEvents.emit(events)
    assert(emitted.contains("42"))
  }

  test("fromAny: boolean") {
    val events  = YamlEvents.fromAny(java.lang.Boolean.TRUE)
    val emitted = YamlEvents.emit(events)
    assert(emitted.contains("true"))
  }

  test("fromAny: flat map") {
    import java.util.LinkedHashMap
    val m = new LinkedHashMap[String, Any]()
    m.put("name", "my-module"); m.put("version", "1.0.0")
    val events  = YamlEvents.fromAny(m)
    val emitted = YamlEvents.emit(events)
    val result  = SimpleYaml.load[java.util.Map[String, Any]](emitted)
    assert(result.get("name") == "my-module")
    assert(result.get("version") == "1.0.0")
  }

  test("fromAny: list of strings") {
    import java.util.ArrayList
    val l = new ArrayList[Any](); l.add("jvm"); l.add("js")
    val events  = YamlEvents.fromAny(l)
    val emitted = YamlEvents.emit(events)
    val result  = SimpleYaml.load[java.util.List[Any]](emitted)
    assert(result.size == 2)
    assert(result.get(0) == "jvm")
    assert(result.get(1) == "js")
  }

  test("fromAny: nested map") {
    import java.util.{LinkedHashMap, ArrayList}
    val exports = new ArrayList[Any](); exports.add("Foo"); exports.add("Bar")
    val m = new LinkedHashMap[String, Any]()
    m.put("name", "test"); m.put("exports", exports)
    val events  = YamlEvents.fromAny(m)
    val emitted = YamlEvents.emit(events)
    val result  = SimpleYaml.load[java.util.Map[String, Any]](emitted)
    assert(result.get("name") == "test")
    val expList = result.get("exports").asInstanceOf[java.util.List[?]]
    assert(expList.get(0) == "Foo"); assert(expList.get(1) == "Bar")
  }

  test("fromAny: string needing quoting") {
    val events  = YamlEvents.fromAny("hello: world")
    val emitted = YamlEvents.emit(events)
    assert(emitted.contains("\"hello: world\"") || emitted.contains("'hello: world'"))
    val result  = SimpleYaml.load[Any](emitted)
    assert(result == "hello: world")
  }

  test("fromAny: multi-line string → literal style") {
    val events = YamlEvents.fromAny("line1\nline2\nline3")
    // must use literal style so the value is faithfully preserved
    val scalar = events.collectFirst { case s: YScalar => s }.get
    assert(scalar.style == YScalarStyle.Literal)
    assert(scalar.value.contains("line1"))
    assert(scalar.value.contains("line2"))
  }

  // ─── parse + emit round-trips ─────────────────────────────────────────────

  test("parse/emit: simple key-value map") {
    roundtrip("name: my-module\nversion: 1.0.0\n")
  }

  test("parse/emit: quoted string values") {
    roundtrip("version: \"1.0.0\"\ndescription: 'A test'\n")
  }

  test("parse/emit: block sequence") {
    roundtrip("exports:\n  - Foo\n  - Bar\n  - Baz\n")
  }

  test("parse/emit: flow sequence") {
    roundtrip("backends: [jvm, js]\n")
  }

  test("parse/emit: flow mapping") {
    roundtrip("opts: {timeout: 30, retry: 3}\n")
  }

  test("parse/emit: nested block mapping") {
    roundtrip("graphs:\n  kg:\n    model: rdf\n    backend: rdf4j-memory\n")
  }

  test("parse/emit: folded scalar") {
    roundtrip("description: >\n  This is a long\n  description.\n")
  }

  test("parse/emit: literal scalar") {
    roundtrip("description: |\n  Line one.\n  Line two.\n")
  }

  test("parse/emit: mixed nesting") {
    roundtrip(
      """name: mixed
        |exports:
        |  - Foo
        |  - Bar
        |backends: [jvm, js]
        |graphs:
        |  kg:
        |    model: rdf
        |    backend: rdf4j-memory
        |""".stripMargin)
  }

  test("parse/emit: null value") {
    roundtrip("key: null\n")
  }

  test("parse/emit: boolean values") {
    roundtrip("enabled: true\ndisabled: false\n")
  }

  test("parse/emit: integer values") {
    roundtrip("count: 42\nport: 8080\n")
  }

  test("parse/emit: explicit document markers") {
    val src    = "---\nname: mod\n...\n"
    val events = YamlEvents.parse(src)
    assert(events.contains(YDocumentStart(explicit = true)))
    assert(events.contains(YDocumentEnd(explicit = true)))
    val emitted = YamlEvents.emit(events)
    val result  = SimpleYaml.load[java.util.Map[String, Any]](emitted.stripPrefix("---\n").stripSuffix("...\n").stripSuffix("..."))
    assert(result.get("name") == "mod")
  }

  test("parse/emit: anchor on scalar value") {
    // Standard inline anchor: value is anchored on the same line
    val src    = "base: &base shared\ncopy: *base\n"
    val events = YamlEvents.parse(src)
    assert(events.exists { case YScalar(_, _, Some("base"), _) => true; case _ => false })
    assert(events.exists { case YAlias("base") => true; case _ => false })
    val emitted = YamlEvents.emit(events)
    assert(emitted.contains("shared"))
  }

  test("parse/emit: YAML directive") {
    val src    = "%YAML 1.2\n---\nname: mod\n"
    val events = YamlEvents.parse(src)
    assert(events.exists { case YDirective("YAML", "1.2") => true; case _ => false })
  }

  // ─── Real frontmatter samples ─────────────────────────────────────────────

  test("parse/emit: auth-full frontmatter sample") {
    roundtrip("name: auth-full\nversion: 1.0.0\ndescription: End-to-end demo.\n")
  }

  test("parse/emit: graph-fullstack-rdf frontmatter sample") {
    roundtrip(
      """name: graph-fullstack-rdf
        |version: 1.0.0
        |description: >
        |  Full-stack RDF graph example demonstrating Phase 6 graph storage.
        |graphs:
        |  kg:
        |    model: rdf
        |    side: server
        |    backend: rdf4j-memory
        |""".stripMargin)
  }

  test("parse/emit: frontmatter with flow sequence and nested map") {
    roundtrip(
      """name: demo
        |version: 1.0.0
        |backends: [jvm, js]
        |exports:
        |  - Foo
        |  - Bar
        |dependencies:
        |  scalascript-std: 0.1.0
        |""".stripMargin)
  }

  // ─── Event structure correctness ──────────────────────────────────────────

  test("parse: stream/document wrapper is always present") {
    val events = YamlEvents.parse("name: x\n")
    assert(events.head == YStreamStart)
    assert(events.last == YStreamEnd)
    assert(events.contains(YDocumentStart(false)))
    assert(events.contains(YDocumentEnd(false)))
  }

  test("parse: simple map emits MappingStart/End") {
    val events = YamlEvents.parse("a: 1\nb: 2\n")
    assert(events.contains(YMappingStart()))
    assert(events.contains(YMappingEnd))
    val scalars = events.collect { case YScalar(v, _, _, _) => v }
    assert(scalars.contains("a")); assert(scalars.contains("1"))
    assert(scalars.contains("b")); assert(scalars.contains("2"))
  }

  test("parse: flow sequence emits SequenceStart(flow=true)") {
    val events = YamlEvents.parse("items: [a, b, c]\n")
    assert(events.exists { case YSequenceStart(true, _, _) => true; case _ => false })
    val scalars = events.collect { case YScalar(v, _, _, _) => v }
    assert(scalars.contains("a")); assert(scalars.contains("b")); assert(scalars.contains("c"))
  }

  test("parse: block sequence emits SequenceStart(flow=false)") {
    val events = YamlEvents.parse("items:\n  - a\n  - b\n")
    assert(events.exists { case YSequenceStart(false, _, _) => true; case _ => false })
  }

  test("parse: literal scalar style preserved") {
    val events = YamlEvents.parse("desc: |\n  line one\n  line two\n")
    val scalar = events.collectFirst { case s @ YScalar(_, YScalarStyle.Literal, _, _) => s }
    assert(scalar.isDefined)
    assert(scalar.get.value.contains("line one"))
  }

  test("parse: folded scalar style preserved") {
    val events = YamlEvents.parse("desc: >\n  folded text here\n")
    val scalar = events.collectFirst { case s @ YScalar(_, YScalarStyle.Folded, _, _) => s }
    assert(scalar.isDefined)
    assert(scalar.get.value.contains("folded text"))
  }

  test("parse: double-quoted scalar style preserved") {
    val events = YamlEvents.parse("version: \"1.0.0\"\n")
    val scalar = events.collectFirst { case s @ YScalar(_, YScalarStyle.DoubleQuoted, _, _) => s }
    assert(scalar.isDefined)
    assert(scalar.get.value == "1.0.0")
  }
