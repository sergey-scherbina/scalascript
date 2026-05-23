package scalascript.frontend.toolkit

import scalascript.frontend.{View, AttrValue, EventHandler, Signal}

/** v1.18 / Phase B+ — `Table` widget.  Renders a typed row collection
 *  as an accessible `<table>` with sortable + filterable columns.
 *
 *  Backend-agnostic — same widget tree lowers to React / Vue / Solid /
 *  Custom through the regular View[?] pipeline.  The `rows: Signal[Seq[T]]`
 *  pattern means data changes drive the same fine-grained subscriptions
 *  the SPI uses for `View.For[T]`.
 *
 *  Usage:
 *  ```scala
 *  case class User(id: String, name: String, email: String, age: Int)
 *  val users: Signal[Seq[User]] = ...
 *
 *  Table[User](
 *    rows    = users,
 *    key     = _.id,
 *    columns = Seq(
 *      Column("Name",  u => Tk.text(u.name),                 sortBy = Some(_.name)),
 *      Column("Email", u => Tk.link(u.email, "mailto:...")(Tk.text(u.email))),
 *      Column("Age",   u => Tk.text(u.age.toString),         sortBy = Some(_.age))
 *    )
 *  )
 *  ```
 *
 *  Features in v1:
 *    - Header row from column definitions
 *    - Per-column body cell render (`row => ToolkitNode`)
 *    - Optional sort: clicking the header toggles asc / desc / off
 *    - Optional empty-state slot (`emptyState`) shown when rows is empty
 *    - ARIA: `role="table"`, `role="row"`, `role="cell"`, `aria-sort`
 *
 *  Deferred to v2:
 *    - Filtering UI (caller can pre-filter the `rows` signal)
 *    - Pagination (caller slices the `rows` signal)
 *    - Column resize / reorder
 *    - Virtualisation (large lists)
 *    - Multi-column sort */
final case class TableNode[T](
  rows:       Signal[Seq[T]],
  key:        T => String,
  columns:    Seq[Column[T]],
  /** Active sort state — column index + direction.  Bind to a signal
   *  for user-controlled sort; pass a fixed Signal for static
   *  ordering.  None = no sort (preserve input order). */
  sort:       Option[Signal[TableSort]] = None,
  /** Rendered when `rows()` is empty.  None ⇒ render an empty
   *  body with no special "no rows" indicator. */
  emptyState: Option[ToolkitNode]      = None,
  /** Spec-conforming caption text.  When set, emits a `<caption>`
   *  inside the `<table>` for screen readers. */
  caption:    Option[String]            = None
) extends ToolkitNode

/** Column definition.
 *  @param title     header text
 *  @param render    body cell render function
 *  @param sortBy    optional sort key extractor; presence enables
 *                   click-to-sort on the column header
 *  @param align     cell text alignment (left / center / right)
 *  @param width     optional explicit column width (CSS value) */
final case class Column[T](
  title:  String,
  render: T => ToolkitNode,
  sortBy: Option[T => Comparable[?]] = None,
  align:  ColumnAlign                = ColumnAlign.Left,
  width:  Option[String]             = None
)

enum ColumnAlign:
  case Left, Center, Right

/** Current sort state — column index + direction.  `Off` keeps the
 *  input order (no comparison performed). */
case class TableSort(columnIndex: Int, direction: SortDirection)
enum SortDirection:
  case Asc, Desc, Off

object TableNode:
  def lower[T](n: TableNode[T], theme: Theme): View[?] =
    val tableStyle =
      s"border-collapse: collapse; width: 100%; " +
      s"font-family: ${theme.typography.body.fontFamily}; " +
      s"font-size: ${theme.typography.body.fontSize}px;"
    val children = scala.collection.mutable.ArrayBuffer.empty[View[?]]

    // Optional caption — accessibility benefit; doesn't render
    // visually by default but screen readers announce it.
    n.caption.foreach { c =>
      children += View.Element("caption",
        attrs    = Map("style" -> AttrValue.Str(
          s"text-align: left; padding: ${theme.spacing.sm}px; " +
          s"color: ${theme.colors.textMuted}; " +
          s"font-size: ${theme.typography.caption.fontSize}px;")),
        events   = Map.empty,
        children = Seq(View.TextNode(() => c)))
    }

    // Header row — one <th> per column, optional click-to-sort.
    val headerCells = n.columns.zipWithIndex.map { (col, idx) =>
      lowerHeaderCell(col, idx, n.sort, theme)
    }
    children += View.Element("thead",
      attrs    = Map("style" -> AttrValue.Str(
        s"background: ${theme.colors.surface}; " +
        s"border-bottom: 1px solid ${theme.colors.border};")),
      events   = Map.empty,
      children = Seq(View.Element("tr",
        attrs    = Map("role" -> AttrValue.Str("row")),
        events   = Map.empty,
        children = headerCells)))

    // Body rows — pre-sorted snapshot of `rows()` per current
    // `sort` state.  Inside SPI backends with fine-grained
    // reactivity (Solid / Custom), the surrounding `View.For` (if
    // we wrap one later) handles patching; for now we render a
    // snapshot.
    val data = sortedRows(n)
    val bodyChildren =
      if data.isEmpty then n.emptyState match
        case Some(state) =>
          Seq(View.Element("tr",
            attrs = Map.empty, events = Map.empty,
            children = Seq(View.Element("td",
              attrs = Map(
                "colspan" -> AttrValue.Num(n.columns.length.toDouble),
                "style"   -> AttrValue.Str(
                  s"text-align: center; padding: ${theme.spacing.lg}px; " +
                  s"color: ${theme.colors.textMuted};")),
              events = Map.empty,
              children = Seq(Toolkit.lower(state, theme))))))
        case None => Nil
      else data.map { row =>
        View.Element("tr",
          attrs    = Map(
            "role" -> AttrValue.Str("row"),
            "data-row-key" -> AttrValue.Str(n.key(row))),
          events   = Map.empty,
          children = n.columns.map(col => lowerBodyCell(col, row, theme)))
      }
    children += View.Element("tbody",
      attrs    = Map.empty, events = Map.empty,
      children = bodyChildren)

    View.Element(
      tag    = "table",
      attrs  = Map(
        "role"  -> AttrValue.Str("table"),
        "style" -> AttrValue.Str(tableStyle)
      ),
      events   = Map.empty,
      children = children.toSeq
    )

  /** Render one header cell.  When `col.sortBy` is set + the table
   *  has a `sort` signal, the cell becomes click-to-sort with
   *  `aria-sort` reflecting the current state. */
  private def lowerHeaderCell[T](
    col:   Column[T],
    idx:   Int,
    sort:  Option[Signal[TableSort]],
    theme: Theme
  ): View[?] =
    val (ariaSort, indicator, clickHandler) = (sort, col.sortBy) match
      case (Some(sig), Some(_)) =>
        val s = try sig() catch case _: Throwable =>
                  TableSort(-1, SortDirection.Off)
        val isActive = s.columnIndex == idx
        val (label, next) = (isActive, s.direction) match
          case (true,  SortDirection.Asc)  => (" ▲", TableSort(idx, SortDirection.Desc))
          case (true,  SortDirection.Desc) => (" ▼", TableSort(idx, SortDirection.Off))
          case _                            => ("",   TableSort(idx, SortDirection.Asc))
        val aria = (isActive, s.direction) match
          case (true,  SortDirection.Asc)  => "ascending"
          case (true,  SortDirection.Desc) => "descending"
          case _                            => "none"
        (Some(aria), label, Some(() => sig.set(next)))
      case _ => (None, "", None)

    val attrs = scala.collection.mutable.Map[String, AttrValue](
      "role"  -> AttrValue.Str("columnheader"),
      "style" -> AttrValue.Str(headerStyle(col, theme, sortable = clickHandler.isDefined))
    )
    ariaSort.foreach(s => attrs("aria-sort") = AttrValue.Str(s))
    if clickHandler.isDefined then
      attrs("tabindex") = AttrValue.Num(0.0)

    val events =
      clickHandler.map(h => Map("click" -> EventHandler.Simple(h)))
        .getOrElse(Map.empty[String, EventHandler])

    View.Element(
      tag      = "th",
      attrs    = attrs.toMap,
      events   = events,
      children = Seq(View.TextNode(() => col.title + indicator))
    )

  private def lowerBodyCell[T](col: Column[T], row: T, theme: Theme): View[?] =
    val alignCss = col.align match
      case ColumnAlign.Left   => "left"
      case ColumnAlign.Center => "center"
      case ColumnAlign.Right  => "right"
    val widthCss = col.width.map(w => s" width: $w;").getOrElse("")
    val style =
      s"padding: ${theme.spacing.sm}px ${theme.spacing.md}px; " +
      s"text-align: $alignCss; " +
      s"border-bottom: 1px solid ${theme.colors.border};$widthCss"
    View.Element(
      tag      = "td",
      attrs    = Map(
        "role"  -> AttrValue.Str("cell"),
        "style" -> AttrValue.Str(style)
      ),
      events   = Map.empty,
      children = Seq(Toolkit.lower(col.render(row), theme))
    )

  private def headerStyle[T](col: Column[T], theme: Theme, sortable: Boolean): String =
    val alignCss = col.align match
      case ColumnAlign.Left   => "left"
      case ColumnAlign.Center => "center"
      case ColumnAlign.Right  => "right"
    val widthCss = col.width.map(w => s" width: $w;").getOrElse("")
    val cursorCss = if sortable then " cursor: pointer; user-select: none;" else ""
    s"padding: ${theme.spacing.sm}px ${theme.spacing.md}px; " +
    s"text-align: $alignCss; font-weight: 600; " +
    s"color: ${theme.colors.text};$widthCss$cursorCss"

  /** Apply the current sort to the row snapshot.  Defensive — bad
   *  state (column index out of range, sortBy missing) falls back
   *  to the unsorted input. */
  private def sortedRows[T](n: TableNode[T]): Seq[T] =
    val data = try n.rows() catch case _: Throwable => Seq.empty[T]
    n.sort match
      case None => data
      case Some(sig) =>
        val s = try sig() catch case _: Throwable =>
                  TableSort(-1, SortDirection.Off)
        if s.direction == SortDirection.Off then data
        else if s.columnIndex < 0 || s.columnIndex >= n.columns.length then data
        else n.columns(s.columnIndex).sortBy match
          case None         => data
          case Some(extract) =>
            val cmp = Ordering.fromLessThan[T] { (a, b) =>
              val ka = extract(a).asInstanceOf[Comparable[Any]]
              val kb = extract(b).asInstanceOf[Comparable[Any]]
              ka.compareTo(kb) < 0
            }
            s.direction match
              case SortDirection.Asc  => data.sorted(using cmp)
              case SortDirection.Desc => data.sorted(using cmp.reverse)
              case SortDirection.Off  => data
