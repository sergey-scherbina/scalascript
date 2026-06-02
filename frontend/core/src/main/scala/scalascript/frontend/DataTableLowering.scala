package scalascript.frontend

/** Lowers a `View.DataTable` into standard composable IR.
 *
 *  Produces a `<table>` with a static `<thead>` (column headers) and a
 *  reactive `<tbody>` gated by a `ModelView` that fetches via `dt.signal`.
 *  Each row is a `ForModel` iteration emitting `<tr>` cells with
 *  `ModelText` for data columns and `Button` for action columns.
 *
 *  `ModelView` wraps ONLY the `<tbody>` so the header row renders immediately
 *  before the first fetch completes.
 *
 *  Web backends (React, Vue) call `lower()` from their `DataTable` render arm
 *  and recurse into the returned View tree.  Solid and Custom use a direct
 *  imperative DOM implementation instead (no span-wrapper issues, no TDZ). */
object DataTableLowering:

  private val noAttrs   = Map.empty[String, AttrValue]
  private val noEvents  = Map.empty[String, EventHandler]

  private def elem(tag: String, children: List[View[?]]): View.Element =
    View.Element(tag, noAttrs, noEvents, children)

  def lower(dt: View.DataTable): View[?] =
    val bindingVar = dt.signal.id
    val itemVar    = "row"
    val headerRow  = elem("tr",
      dt.columns.map(c => elem("th", List(View.Text(() => c.title, Style())))))
    val thead = elem("thead", List(headerRow))
    val dataCells = dt.columns.map(c => elem("td", List(View.ModelText(itemVar, c.fieldPath))))
    val actionCells = dt.actions.map(a => elem("td", List(actionButton(a))))
    val bodyRow = elem("tr", dataCells ++ actionCells)
    val tbody = elem("tbody",
      List(View.ModelView(dt.signal, bindingVar,
        View.ForModel(bindingVar, "", itemVar, bodyRow))))
    elem("table", List(thead, tbody))

  private def actionButton(a: RowActionDef): View.Button = a match
    case RowActionDef.RowDelete(url, idField, tick, headers) =>
      View.Button(View.Text(() => "Delete", Style()),
        EventHandler.DeleteItem(idField, url, tick, headers))
    case RowActionDef.RowPost(label, method, url, bodyField, tick, headers) =>
      View.Button(View.Text(() => label, Style()),
        EventHandler.ItemAction(method, url, bodyField, tick, headers))
    case RowActionDef.RowLink(label, signal, fieldPath) =>
      View.Button(View.Text(() => label, Style()),
        EventHandler.SetFieldToSignal(signal, fieldPath))
