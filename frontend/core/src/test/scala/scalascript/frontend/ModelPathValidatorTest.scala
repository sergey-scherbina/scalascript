package scalascript.frontend

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast.{ModelDef, ModelField, ModelFieldType}

class ModelPathValidatorTest extends AnyFunSuite:

  // ── Fixtures ──────────────────────────────────────────────────────────────

  private val lineDef = ModelDef("AssetLine", List(
    ModelField("code",   ModelFieldType.Str),
    ModelField("amount", ModelFieldType.DblF)
  ))

  private val sheetDef = ModelDef("BalanceSheet", List(
    ModelField("id",    ModelFieldType.Str),
    ModelField("total", ModelFieldType.DblF),
    ModelField("lines", ModelFieldType.ListOf(ModelFieldType.Nested("AssetLine"))),
    ModelField("note",  ModelFieldType.Optional(ModelFieldType.Str))
  ))

  private val employeeDef = ModelDef("Employee", List(
    ModelField("id",         ModelFieldType.Str),
    ModelField("name",       ModelFieldType.Str),
    ModelField("department", ModelFieldType.Str),
    ModelField("salary",     ModelFieldType.DblF)
  ))

  private val models = List(lineDef, sheetDef, employeeDef)

  private val bsTick = ReactiveSignal[Int]("bsTick", 0)
  private val bsSig  = new FetchJsonSignal("bs", "/api/bs", bsTick.id, "BalanceSheet")
  private val empTick = ReactiveSignal[Int]("empTick", 0)
  private val empSig  = new FetchJsonSignal("employees", "/api/employees", empTick.id, "Employee")

  // ── validate returns Nil when models is empty ─────────────────────────────

  test("validate: empty models list → no errors regardless of view") {
    val view = View.ModelText("bs", "badField")
    assert(ModelPathValidator.validate(view, Nil).isEmpty)
  }

  // ── ModelText: var not in scope → no error ────────────────────────────────

  test("validate: ModelText var not in ctx → skipped silently") {
    // 'bs' is not introduced by any enclosing ModelView — skip
    val view = View.ModelText("bs", "nonexistent")
    assert(ModelPathValidator.validate(view, models).isEmpty)
  }

  // ── ModelText: valid path inside ModelView ────────────────────────────────

  test("validate: ModelText valid path inside ModelView → no error") {
    val view = View.ModelView(bsSig, "bs",
      View.ModelText("bs", "total"))
    assert(ModelPathValidator.validate(view, models).isEmpty)
  }

  test("validate: ModelText valid nested path inside ModelView → no error") {
    val view = View.ModelView(bsSig, "bs",
      View.ModelText("bs", "id"))
    assert(ModelPathValidator.validate(view, models).isEmpty)
  }

  // ── ModelText: invalid path inside ModelView ──────────────────────────────

  test("validate: ModelText invalid field inside ModelView → error") {
    val view = View.ModelView(bsSig, "bs",
      View.ModelText("bs", "nonexistent"))
    val errors = ModelPathValidator.validate(view, models)
    assert(errors.size == 1)
    assert(errors.head.node == "ModelText")
    assert(errors.head.varName == "bs")
    assert(errors.head.path == "nonexistent")
    assert(errors.head.message.contains("not found"))
  }

  // ── ForModel: invalid fieldPath ───────────────────────────────────────────

  test("validate: ForModel invalid fieldPath → error") {
    val view = View.ModelView(bsSig, "bs",
      View.ForModel("bs", "badField", "line",
        View.ModelText("line", "code")))
    val errors = ModelPathValidator.validate(view, models)
    assert(errors.nonEmpty)
    assert(errors.exists(e => e.node == "ForModel" && e.path == "badField"))
  }

  test("validate: ForModel non-list field → error") {
    // 'total' is a Double, not a list
    val view = View.ModelView(bsSig, "bs",
      View.ForModel("bs", "total", "item",
        View.ModelText("item", "code")))
    val errors = ModelPathValidator.validate(view, models)
    assert(errors.nonEmpty)
    assert(errors.exists(_.node == "ForModel"))
  }

  // ── ForModel: valid list field, item var propagated ───────────────────────

  test("validate: ForModel valid path + ModelText in item scope → no error") {
    val view = View.ModelView(bsSig, "bs",
      View.ForModel("bs", "lines", "line",
        View.ModelText("line", "code")))
    assert(ModelPathValidator.validate(view, models).isEmpty)
  }

  test("validate: ForModel valid path + ModelText invalid field in item scope → error") {
    val view = View.ModelView(bsSig, "bs",
      View.ForModel("bs", "lines", "line",
        View.ModelText("line", "nosuchfield")))
    val errors = ModelPathValidator.validate(view, models)
    assert(errors.size == 1)
    assert(errors.head.node == "ModelText")
    assert(errors.head.varName == "line")
    assert(errors.head.path == "nosuchfield")
  }

  // ── Column/Row containers pass context through ────────────────────────────

  test("validate: errors inside Column children are collected") {
    val view = View.ModelView(bsSig, "bs",
      View.Column(List(
        View.ModelText("bs", "total"),
        View.ModelText("bs", "bad1"),
        View.ModelText("bs", "bad2")
      ), 8, HAlign.Start, Style()))
    val errors = ModelPathValidator.validate(view, models)
    assert(errors.size == 2)
  }

  // ── RawText FetchUrlSignal: no type → vars not in scope ──────────────────

  test("validate: plain FetchUrlSignal (RawText) gives no binding → ModelText skipped") {
    val rawSig = FetchUrlSignal("raw", "/api/data", "tick")
    val view = View.ModelView(rawSig, "data",
      View.ModelText("data", "anyField"))
    // 'data' bound from a RawText signal — no type known — silently skipped
    assert(ModelPathValidator.validate(view, models).isEmpty)
  }

  // ── DataTable: typed signal validates row paths ──────────────────────────

  test("validate: DataTable valid typed columns and actions → no error") {
    val view = View.DataTable(
      empSig,
      columns = List(
        FieldColumnDef("Name", "name",
          editAction = Some(RowActionDef.RowInlineEdit("PATCH", "/api/employees", "id", empTick))),
        FieldColumnDef("Department", "department"),
        FieldColumnDef("Salary", "salary")
      ),
      actions = List(
        RowActionDef.RowDelete("/api/employees/delete", "id", empTick),
        RowActionDef.RowPost("Promote", "POST", "/api/employees/promote", "id", empTick),
        RowActionDef.RowLink("Select", ReactiveSignal[String]("selected", ""), "name")
      )
    )

    assert(ModelPathValidator.validate(view, models).isEmpty)
  }

  test("validate: DataTable raw fetch signal remains permissive") {
    val rawSig = FetchUrlSignal("rows", "/api/rows", "tick")
    val view = View.DataTable(
      rawSig,
      columns = List(FieldColumnDef("Anything", "not.a.real.path")),
      actions = List(RowActionDef.RowDelete("/api/delete", "missingId", empTick))
    )

    assert(ModelPathValidator.validate(view, models).isEmpty)
  }

  test("validate: DataTable invalid typed column/action paths → errors") {
    val view = View.DataTable(
      empSig,
      columns = List(
        FieldColumnDef("Bad column", "missingColumn",
          editAction = Some(RowActionDef.RowInlineEdit("PATCH", "/api/employees", "missingId", empTick)))
      ),
      actions = List(
        RowActionDef.RowDelete("/api/employees/delete", "deleteMissing", empTick),
        RowActionDef.RowPost("Promote", "POST", "/api/employees/promote", "postMissing", empTick),
        RowActionDef.RowLink("Select", ReactiveSignal[String]("selected", ""), "linkMissing")
      )
    )

    val errors = ModelPathValidator.validate(view, models)
    assert(errors.size == 5)
    assert(errors.exists(e => e.node == "DataTableColumn" && e.path == "missingColumn"))
    assert(errors.exists(e => e.node == "DataTableInlineEdit" && e.path == "missingId"))
    assert(errors.exists(e => e.node == "DataTableRowDelete" && e.path == "deleteMissing"))
    assert(errors.exists(e => e.node == "DataTableRowPost" && e.path == "postMissing"))
    assert(errors.exists(e => e.node == "DataTableRowLink" && e.path == "linkMissing"))
  }

  // ── validateModule ────────────────────────────────────────────────────────

  test("validateModule: empty models → no errors") {
    val module = FrontendModule(
      components   = List(ComponentDef("Main", Nil, _ => View.ModelText("x", "bad"))),
      entryPoint   = "Main",
      initialRoute = "/",
      models       = Nil
    )
    assert(ModelPathValidator.validateModule(module).isEmpty)
  }

  test("validateModule: valid module → no errors") {
    val view = View.ModelView(bsSig, "bs", View.ModelText("bs", "total"))
    val module = FrontendModule(
      components   = List(ComponentDef("Main", Nil, _ => view)),
      entryPoint   = "Main",
      initialRoute = "/",
      models       = models
    )
    assert(ModelPathValidator.validateModule(module).isEmpty)
  }

  test("validateModule: invalid path → error collected across components") {
    val bad = View.ModelView(bsSig, "bs", View.ModelText("bs", "missing"))
    val module = FrontendModule(
      components   = List(ComponentDef("Main", Nil, _ => bad)),
      entryPoint   = "Main",
      initialRoute = "/",
      models       = models
    )
    val errors = ModelPathValidator.validateModule(module)
    assert(errors.nonEmpty)
  }
