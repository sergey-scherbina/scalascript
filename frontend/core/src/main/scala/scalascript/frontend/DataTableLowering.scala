package scalascript.frontend

/** Lowers a `View.DataTable` into standard composable IR.
 *
 *  Produces a `<table>` with a static `<thead>` (column headers) and a
 *  reactive `<tbody>` gated by a `ModelView` that fetches via the Remote
 *  signal.  Each row is a `ForModel` iteration emitting `<tr>` cells with
 *  `ModelText` for data columns and `Button` for action columns.
 *
 *  `ModelView` wraps ONLY the `<tbody>` so the header row renders immediately
 *  before the first fetch completes.
 *
 *  Web backends (React, Vue) call `lower()` from their `DataTable` render arm
 *  and recurse into the returned View tree.  Solid and Custom use a direct
 *  imperative DOM implementation instead (no span-wrapper issues, no TDZ).
 *
 *  Non-Remote sources (StaticRows, SignalRows) are stubs — they emit the
 *  header row and an empty body; full rendering will be added in a future phase. */
object DataTableLowering:

  private val noAttrs   = Map.empty[String, AttrValue]
  private val noEvents  = Map.empty[String, EventHandler]

  private def elem(tag: String, children: List[View[?]]): View.Element =
    View.Element(tag, noAttrs, noEvents, children)

  def lower(dt: View.DataTable): View[?] =
    dt.source match
      case TableDataSource.Remote(sig) =>
        val bindingVar = sig.id
        val itemVar    = "row"
        val headerRow  = elem("tr",
          dt.columns.map { c =>
            val widthStyle = c.width.map(w => Map("style" -> AttrValue.Str(s"width:$w"))).getOrElse(Map.empty)
            View.Element("th", widthStyle, noEvents, List(View.Text(() => c.title, Style())))
          })
        val thead = elem("thead", List(headerRow))
        val dataCells = dt.columns.map { c =>
          val widthStyle = c.width.map(w => Map("style" -> AttrValue.Str(s"width:$w"))).getOrElse(Map.empty)
          val inner = c.editAction match
            case Some(ea) => View.EditableCell(itemVar, c.fieldPath, ea)
            case None =>
              if c.kind == ColumnKind.Text then View.ModelText(itemVar, c.fieldPath)
              else View.FormattedField(itemVar, c.fieldPath, c.kind)
          View.Element("td", widthStyle, noEvents, List(inner))
        }
        val actionCells = dt.actions.map(a => elem("td", List(actionButton(a))))
        val bodyRow = elem("tr", dataCells ++ actionCells)
        val tbody = elem("tbody",
          List(View.ModelView(sig, bindingVar,
            View.ForModel(bindingVar, "", itemVar, bodyRow))))
        elem("table", List(thead, tbody))
      case _ =>
        // StaticRows and SignalRows: emit header only as stub; full rendering in Phase 3.
        val headerRow = elem("tr",
          dt.columns.map { c =>
            val widthStyle = c.width.map(w => Map("style" -> AttrValue.Str(s"width:$w"))).getOrElse(Map.empty)
            View.Element("th", widthStyle, noEvents, List(View.Text(() => c.title, Style())))
          })
        val thead = elem("thead", List(headerRow))
        elem("table", List(thead, elem("tbody", Nil)))

  private def actionButton(a: RowActionDef): View.Button = a match
    case RowActionDef.RowDelete(url, idField, tick, headers) =>
      View.Button(View.Text(() => "Delete", Style()),
        EventHandler.DeleteItem(idField, url, tick, headers))
    case RowActionDef.RowPost(label, method, url, payload, tick, headers) =>
      View.Button(View.Text(() => label, Style()),
        EventHandler.ItemAction(method, url, payload, tick, headers))
    case RowActionDef.RowLink(label, signal, fieldPath) =>
      View.Button(View.Text(() => label, Style()),
        EventHandler.SetFieldToSignal(signal, fieldPath))
    case RowActionDef.RowInlineEdit(_, _, _, _, _) =>
      // RowInlineEdit appears on FieldColumnDef.editAction, never in dt.actions;
      // this arm is unreachable but needed for exhaustivity.
      View.Button(View.Text(() => "", Style()), EventHandler.Simple(() => ()))
