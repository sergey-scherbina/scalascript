package scalascript.codegen.rust

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** Intrinsic overlay applied for the tui target (`uiTarget == "tui"`).
 *
 *  rust-tui-toolkit S4 — the fetch + DataTable family has no runtime on the
 *  rust *web* path (the SSR `dataTableView` is a stub; `fetchUrlSignal`/
 *  `fetchRowsSource`/`fieldColumn` are unmapped). For the terminal target we
 *  point them at tui-specific runtimes in `tui.rs`, which fetch (blocking GET
 *  via `ureq`), drill the JSON envelope, and render a ratatui `Table` — without
 *  disturbing the web mappings, which keep their stubs.
 *
 *  Applied as an overlay (`RustIntrinsics ++ RustTuiIntrinsics`) only when the
 *  caller asks for the tui target, so a web build never references these
 *  `crate::runtime::tui::*` symbols (tui.rs isn't emitted there). */
val RustTuiIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(
  QualifiedName("fetchUrlSignal")  -> RuntimeCall("crate::runtime::tui::_tui_fetch_url_signal"),
  QualifiedName("fetchRowsSource") -> RuntimeCall("crate::runtime::tui::_tui_fetch_rows_source"),
  QualifiedName("fieldColumn")     -> RuntimeCall("crate::runtime::tui::_tui_field_column"),
  QualifiedName("dataTableView")   -> RuntimeCall("crate::runtime::tui::_tui_data_table_view"),
)
