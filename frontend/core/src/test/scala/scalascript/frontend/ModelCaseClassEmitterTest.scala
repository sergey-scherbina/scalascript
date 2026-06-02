package scalascript.frontend

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast.{ModelDef, ModelField, ModelFieldType}

class ModelCaseClassEmitterTest extends AnyFunSuite:

  private val line  = ModelDef("AssetLine", List(
    ModelField("code",   ModelFieldType.Str),
    ModelField("amount", ModelFieldType.DblF),
  ))
  private val sheet = ModelDef("BalanceSheet", List(
    ModelField("id",    ModelFieldType.Str),
    ModelField("total", ModelFieldType.DblF),
    ModelField("lines", ModelFieldType.ListOf(ModelFieldType.Nested("AssetLine"))),
    ModelField("note",  ModelFieldType.Optional(ModelFieldType.Str)),
  ))

  // ── emitClass ─────────────────────────────────────────────────────────────

  test("emitClass: scalar fields") {
    val src = ModelCaseClassEmitter.emitClass(line)
    assert(src.contains("case class AssetLine("))
    assert(src.contains("code: String"))
    assert(src.contains("amount: Double"))
    assert(src.contains("extends scalascript.frontend.SscModel"))
  }

  test("emitClass: List and Option fields") {
    val src = ModelCaseClassEmitter.emitClass(sheet)
    assert(src.contains("lines: List[AssetLine]"))
    assert(src.contains("note: Option[String]"))
  }

  test("emitClass: with package prefix") {
    val src = ModelCaseClassEmitter.emitClass(line, Some("com.example"))
    assert(src.startsWith("package com.example"))
    assert(src.contains("case class AssetLine("))
  }

  // ── emitAll ───────────────────────────────────────────────────────────────

  test("emitAll: emits both models with SscModel import") {
    val src = ModelCaseClassEmitter.emitAll(List(line, sheet))
    assert(src.contains("import scalascript.frontend.SscModel"))
    assert(src.contains("case class AssetLine("))
    assert(src.contains("case class BalanceSheet("))
    assert(src.contains("extends SscModel"))
  }

  test("emitAll: with package") {
    val src = ModelCaseClassEmitter.emitAll(List(line), Some("myapp.models"))
    assert(src.startsWith("package myapp.models"))
  }

  // ── SscModel marker trait ─────────────────────────────────────────────────

  test("SscModel: trait accessible at runtime (isInstanceOf check)") {
    // We can't compile emitted source in a unit test, but we can verify
    // the trait is accessible and usable for pattern matching.
    val model = new SscModel {}
    assert(model.isInstanceOf[SscModel])
  }
