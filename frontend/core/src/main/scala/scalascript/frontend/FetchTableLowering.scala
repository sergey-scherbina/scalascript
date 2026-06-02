package scalascript.frontend

import scala.annotation.nowarn

/** Semantic lowering of the deprecated `View.FetchTable` node.
 *
 *  Converts a `View.FetchTable` into standard View IR using `FetchJsonSignal` +
 *  `ModelView` + `ForModel(bindingVar, "", ...)` (empty `fieldPath` = iterate the
 *  binding var directly as a list) + `View.Button` with `EventHandler.DeleteItem`.
 *
 *  Web backends (React, Vue, Solid, Custom) call `lower()` from their `FetchTable`
 *  emit branch and then recurse into the returned view.  Swing/JavaFX keep their
 *  existing synchronous `View.FetchTable` implementation until a separate task
 *  threads async-fetch semantics through those runtimes.
 *
 *  The `FetchTableRow` model describes the expected JSON shape: `{id, text}`. */
object FetchTableLowering:

  /** Synthetic model type name for FetchTable row data (`[{id, text}]`). */
  val RowModelType = "FetchTableRow"

  /** Lower `ft` to a `ModelView` + `ForModel` + `Button(DeleteItem)` tree. */
  @nowarn("cat=deprecation")
  def lower(ft: View.FetchTable): View[?] =
    val sig = FetchJsonSignal(ft.tableId, ft.fetchUrl, ft.tick.id, RowModelType, ft.headers.map(_.id))
    val rowTemplate = View.Row(
      children = List(
        View.ModelText("row", "text"),
        View.Button(
          label   = View.Text(() => "Delete", Style()),
          action  = EventHandler.DeleteItem("id", ft.deleteUrl, ft.tick, ft.headers),
          enabled = () => true,
          style   = Style()
        )
      ),
      spacing = 8,
      align   = VAlign.Center,
      style   = Style()
    )
    View.ModelView(
      signal     = sig,
      bindingVar = ft.tableId,
      template   = View.ForModel(ft.tableId, "", "row", rowTemplate)
    )
