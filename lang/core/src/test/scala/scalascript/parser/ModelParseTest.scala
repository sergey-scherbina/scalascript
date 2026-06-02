package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast.{ModelDef, ModelField, ModelFieldType}

class ModelParseTest extends AnyFunSuite:

  private def parse(src: String) =
    val mod = Parser.parse(s"# Title\n\n$src")
    mod.manifest.get.models

  // ── @model annotation form ──────────────────────────────────────────────

  test("@model case class: simple scalar fields") {
    val models = parse(
      """```scala
        |@model
        |case class User(id: String, age: Int, score: Double, active: Boolean)
        |```""".stripMargin
    )
    assert(models.map(_.name) == List("User"))
    val fields = models.head.fields
    assert(fields == List(
      ModelField("id",     ModelFieldType.Str),
      ModelField("age",    ModelFieldType.IntF),
      ModelField("score",  ModelFieldType.DblF),
      ModelField("active", ModelFieldType.BoolF),
    ))
  }

  test("@model case class: nested and list types") {
    val models = parse(
      """```scala
        |@model
        |case class Line(code: String, amount: Double)
        |@model
        |case class Sheet(id: String, lines: List[Line])
        |```""".stripMargin
    )
    assert(models.map(_.name) == List("Line", "Sheet"))
    val sheetFields = models(1).fields
    assert(sheetFields(1).tpe == ModelFieldType.ListOf(ModelFieldType.Nested("Line")))
  }

  test("@model case class: Optional field") {
    val models = parse(
      """```scala
        |@model
        |case class Record(id: String, note: Option[String])
        |```""".stripMargin
    )
    val fields = models.head.fields
    assert(fields(1).tpe == ModelFieldType.Optional(ModelFieldType.Str))
  }

  // ── textual `model case class` form (preprocessor) ──────────────────────

  test("textual 'model case class' preprocesses correctly") {
    val models = parse(
      """```scala
        |model case class Product(id: String, price: Double)
        |```""".stripMargin
    )
    assert(models.map(_.name) == List("Product"))
    assert(models.head.fields.map(_.name) == List("id", "price"))
  }

  // ── identifyingField heuristic ───────────────────────────────────────────

  test("identifyingField: id field") {
    val m = ModelDef("Foo", List(ModelField("id", ModelFieldType.Str), ModelField("name", ModelFieldType.Str)))
    assert(m.identifyingField == Some("id"))
  }

  test("identifyingField: code field") {
    val m = ModelDef("Foo", List(ModelField("code", ModelFieldType.Str), ModelField("name", ModelFieldType.Str)))
    assert(m.identifyingField == Some("code"))
  }

  test("identifyingField: seq field") {
    val m = ModelDef("Foo", List(ModelField("seq", ModelFieldType.IntF)))
    assert(m.identifyingField == Some("seq"))
  }

  test("identifyingField: docId field") {
    val m = ModelDef("Foo", List(ModelField("docId", ModelFieldType.Str)))
    assert(m.identifyingField == Some("docId"))
  }

  test("identifyingField: none found") {
    val m = ModelDef("Foo", List(ModelField("name", ModelFieldType.Str)))
    assert(m.identifyingField == None)
  }

  // ── multiple models in one block ─────────────────────────────────────────

  test("multiple @model declarations in one code block") {
    val models = parse(
      """```scala
        |@model
        |case class A(id: String)
        |@model
        |case class B(id: String, a: A)
        |@model
        |case class C(items: List[B])
        |```""".stripMargin
    )
    assert(models.map(_.name) == List("A", "B", "C"))
  }

  // ── no models → empty list ───────────────────────────────────────────────

  test("manifest.models is empty when no @model declarations") {
    val mod = Parser.parse("# Title\n\n```scala\nval x = 1\n```")
    assert(mod.manifest.forall(_.models == Nil))
  }
