package scalascript.frontend

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast.{ModelDef, ModelField, ModelFieldType}

class ModelPathResolverTest extends AnyFunSuite:

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
  private val models = List(line, sheet)

  // ── resolve: top-level scalar fields ────────────────────────────────────

  test("resolve: direct scalar field") {
    assert(ModelPathResolver.resolve("id", "BalanceSheet", models) == Right(ModelFieldType.Str))
    assert(ModelPathResolver.resolve("total", "BalanceSheet", models) == Right(ModelFieldType.DblF))
  }

  test("resolve: direct list field") {
    assert(ModelPathResolver.resolve("lines", "BalanceSheet", models) ==
      Right(ModelFieldType.ListOf(ModelFieldType.Nested("AssetLine"))))
  }

  // ── resolve: nested paths ────────────────────────────────────────────────

  test("resolve: path through list to nested model field") {
    assert(ModelPathResolver.resolve("lines.code", "BalanceSheet", models) == Right(ModelFieldType.Str))
    assert(ModelPathResolver.resolve("lines.amount", "BalanceSheet", models) == Right(ModelFieldType.DblF))
  }

  // ── resolve: Optional path ───────────────────────────────────────────────

  test("resolve: optional field") {
    assert(ModelPathResolver.resolve("note", "BalanceSheet", models) ==
      Right(ModelFieldType.Optional(ModelFieldType.Str)))
  }

  // ── resolve: errors ──────────────────────────────────────────────────────

  test("resolve: unknown root model") {
    val result = ModelPathResolver.resolve("id", "NoSuchModel", models)
    assert(result.isLeft)
    assert(result.swap.getOrElse("").contains("NoSuchModel"))
  }

  test("resolve: unknown field") {
    val result = ModelPathResolver.resolve("missing", "BalanceSheet", models)
    assert(result.isLeft)
    assert(result.swap.getOrElse("").contains("missing"))
  }

  test("resolve: field access on scalar") {
    val result = ModelPathResolver.resolve("total.sub", "BalanceSheet", models)
    assert(result.isLeft)
  }

  // ── elementType ──────────────────────────────────────────────────────────

  test("elementType: unwraps ListOf") {
    assert(ModelPathResolver.elementType(ModelFieldType.ListOf(ModelFieldType.Nested("AssetLine"))) ==
      Right(ModelFieldType.Nested("AssetLine")))
  }

  test("elementType: unwraps Optional(ListOf(...))") {
    assert(ModelPathResolver.elementType(
      ModelFieldType.Optional(ModelFieldType.ListOf(ModelFieldType.Str))) ==
      Right(ModelFieldType.Str))
  }

  test("elementType: fails on scalar") {
    assert(ModelPathResolver.elementType(ModelFieldType.Str).isLeft)
  }

  // ── idKey ─────────────────────────────────────────────────────────────────

  test("idKey: found for model with 'code' field") {
    assert(ModelPathResolver.idKey(ModelFieldType.Nested("AssetLine"), models) == Some("code"))
  }

  test("idKey: found for model with 'id' field") {
    assert(ModelPathResolver.idKey(ModelFieldType.Nested("BalanceSheet"), models) == Some("id"))
  }

  test("idKey: None for unknown model type") {
    assert(ModelPathResolver.idKey(ModelFieldType.Nested("Unknown"), models) == None)
  }

  test("idKey: None for scalar type") {
    assert(ModelPathResolver.idKey(ModelFieldType.Str, models) == None)
  }
