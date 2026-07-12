package scalascript.uniml.dialect.json

import org.scalatest.funsuite.AnyFunSuite
import scalascript.uniml.*

final class JsonProjectionSpec extends AnyFunSuite:
  private val source = SourceId("memory:projection.json")

  test("projects nested ordered values while preserving exact scalar lexemes") {
    val result = project("{\"s\":\"a\\n\\u0041\",\"n\":1.2500e+02,\"a\":[true,null,{\"x\":false}]}")
    assert(result.diagnostics.isEmpty)

    val root = result.value.get.asInstanceOf[JsonValue.ObjectValue]
    assert(root.members.map(_.name) == Vector("s", "n", "a"))
    assert(root.members(0).value == JsonValue.StringValue("a\nA", "\"a\\n\\u0041\""))
    assert(root.members(1).value == JsonValue.NumberValue("1.2500e+02"))
    val array = root.members(2).value.asInstanceOf[JsonValue.ArrayValue]
    assert(array.values.head == JsonValue.BooleanValue(true))
    assert(array.values(1) == JsonValue.NullValue)
  }

  test("preserves duplicate members and requires an explicit map policy") {
    val result = project("{\"a\":1,\"a\":2,\"b\":3}")
    val root = result.value.get.asInstanceOf[JsonValue.ObjectValue]

    assert(root.members.map(_.name) == Vector("a", "a", "b"))
    assert(root.members.collect { case JsonMember("a", _, JsonValue.NumberValue(value), _) => value } == Vector("1", "2"))
    assert(result.diagnostics.map(_.code) == Vector("uniml.json.duplicate-key"))
    assert(JsonProjection.objectMap(root, DuplicateKeyPolicy.Reject).isLeft)
    assert(JsonProjection.objectMap(root, DuplicateKeyPolicy.FirstWins).toOption.get("a") == JsonValue.NumberValue("1"))
    assert(JsonProjection.objectMap(root, DuplicateKeyPolicy.LastWins).toOption.get("a") == JsonValue.NumberValue("2"))
  }

  test("combines escaped surrogate pairs and warns for unpaired escape code units") {
    val paired = project("\"\\uD834\\uDD1E\"")
    val pairedValue = paired.value.get.asInstanceOf[JsonValue.StringValue]
    assert(pairedValue.value == "𝄞")
    assert(paired.diagnostics.isEmpty)

    val unpaired = project("\"\\uDEAD\"")
    assert(unpaired.value.nonEmpty)
    assert(unpaired.diagnostics.map(_.code) == Vector("uniml.json.unpaired-surrogate"))
    assert(unpaired.diagnostics.head.severity == Severity.Warning)
  }

  test("decodes every short escape and treats escaped-equivalent keys as duplicates") {
    val escaped = project("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"")
    assert(escaped.value.contains(JsonValue.StringValue("\"\\/\b\f\n\r\t", "\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"")))

    val duplicate = project("{\"a\":1,\"\\u0061\":2}")
    val root = duplicate.value.get.asInstanceOf[JsonValue.ObjectValue]
    assert(root.members.map(_.name) == Vector("a", "a"))
    assert(duplicate.diagnostics.map(_.code) == Vector("uniml.json.duplicate-key"))
  }

  test("refuses semantic projection of an incomplete parse") {
    val parsed = Json.parse(SourceInput.fromString(source, "[1,]"))
    val projected = Json.project(parsed)
    assert(projected.value.isEmpty)
    assert(projected.diagnostics.exists(_.code == "uniml.json.trailing-comma"))
  }

  test("agrees with ujson on representative unique-key semantic inputs") {
    val inputs = Vector(
      "null",
      "true",
      "-12.5e2",
      "\"hello\\nworld\"",
      "[1,false,null,\"x\"]",
      "{\"a\":1,\"b\":[2,3]}",
    )
    inputs.foreach { text =>
      val ours = project(text).value.get
      val oracle = ujson.read(text)
      assert(toComparable(ours) == oracle.render(), text)
    }
  }

  private def project(text: String): JsonProjectionResult =
    Json.project(Json.parse(SourceInput.fromString(source, text)))

  private def toComparable(value: JsonValue): String = value match
    case JsonValue.ObjectValue(members) =>
      members.map(member => ujson.Str(member.name).render() + ":" + toComparable(member.value)).mkString("{", ",", "}")
    case JsonValue.ArrayValue(values) => values.map(toComparable).mkString("[", ",", "]")
    case JsonValue.StringValue(decoded, _) => ujson.Str(decoded).render()
    case JsonValue.NumberValue(lexeme) => ujson.Num(lexeme.toDouble).render()
    case JsonValue.BooleanValue(value) => value.toString
    case JsonValue.NullValue           => "null"
