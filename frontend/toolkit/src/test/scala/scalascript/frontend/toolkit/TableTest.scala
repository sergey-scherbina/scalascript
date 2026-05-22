package scalascript.frontend.toolkit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.frontend.{View, AttrValue, EventHandler, ReactiveSignal}

/** v1.18 — Table widget tests.  Covers header rendering, body cells,
 *  sort state transitions, empty state, caption, ARIA, and theme
 *  variants.  Backend-agnosticity is checked as part of the
 *  ToolkitTest smoke (Table emits only HTML primitives, no framework
 *  identifiers). */
class TableTest extends AnyFunSuite with Matchers:

  private val theme = Theme.default

  // Test fixture
  private case class User(id: String, name: String, age: Int)
  private val sample = Seq(
    User("u1", "Charlie", 33),
    User("u2", "Alice",   28),
    User("u3", "Bob",     45)
  )

  private def rowsSignal(xs: Seq[User]) =
    new ReactiveSignal[Seq[User]]("rows", xs)

  private def cols: Seq[Column[User]] = Seq(
    Column("Name", u => TextNode(u.name), sortBy = Some(_.name)),
    Column("Age",  u => TextNode(u.age.toString), sortBy = Some(u => u.age: Integer),
           align = ColumnAlign.Right)
  )

  // ─── Basic rendering ──────────────────────────────────────────

  test("Table: renders <table> with role=table and theme font"):
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols)
    Toolkit.lower(node, theme) match
      case View.Element("table", attrs, _, _) =>
        attrs("role").asInstanceOf[AttrValue.Str].value shouldBe "table"
        val style = attrs("style").asInstanceOf[AttrValue.Str].value
        style should include ("border-collapse: collapse")
        style should include (theme.typography.body.fontFamily)
      case other => fail(s"got $other")

  test("Table: header has thead with one <th> per column"):
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols)
    val view = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val thead = view.children.collectFirst {
      case e: View.Element if e.tag == "thead" => e
    }.getOrElse(fail("no thead"))
    val tr = thead.children.head.asInstanceOf[View.Element]
    tr.tag shouldBe "tr"
    tr.children.length shouldBe 2
    tr.children.foreach { c =>
      c.asInstanceOf[View.Element].tag shouldBe "th"
    }

  test("Table: body has one <tr> per row + correct data-row-key"):
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols)
    val view = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val tbody = view.children.collectFirst {
      case e: View.Element if e.tag == "tbody" => e
    }.getOrElse(fail("no tbody"))
    tbody.children.length shouldBe sample.length
    val keys = tbody.children.map { c =>
      c.asInstanceOf[View.Element].attrs("data-row-key")
       .asInstanceOf[AttrValue.Str].value
    }
    keys should contain allOf ("u1", "u2", "u3")

  test("Table: body cells render via column.render function"):
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols)
    val view = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val tbody = view.children.collect {
      case e: View.Element if e.tag == "tbody" => e
    }.head
    val firstRow = tbody.children.head.asInstanceOf[View.Element]
    firstRow.children.length shouldBe 2
    firstRow.children.head.asInstanceOf[View.Element].tag shouldBe "td"
    // Reach into the lowered Text node — should contain "Charlie"
    val nameTd = firstRow.children.head.asInstanceOf[View.Element]
    extractText(nameTd) should include ("Charlie")

  // ─── ARIA + accessibility ─────────────────────────────────────

  test("Table: cells have role=cell"):
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols)
    val view = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val tds = collectTags(view, "td")
    tds should not be empty
    tds.foreach { td =>
      td.attrs.get("role") match
        case Some(AttrValue.Str("cell")) => succeed
        case other                       => fail(s"td missing role=cell: $other")
    }

  test("Table: header cells have role=columnheader"):
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols)
    val view = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val ths = collectTags(view, "th")
    ths.foreach { th =>
      th.attrs("role").asInstanceOf[AttrValue.Str].value shouldBe "columnheader"
    }

  test("Table: caption emits <caption> for screen readers"):
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols,
      caption = Some("User list"))
    val view = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val cap = view.children.collectFirst {
      case e: View.Element if e.tag == "caption" => e
    }.getOrElse(fail("no caption"))
    extractText(cap) should include ("User list")

  // ─── Sort behaviour ───────────────────────────────────────────

  test("Table: no sort signal ⇒ rows preserve input order"):
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols)
    val keys = rowKeys(Toolkit.lower(node, theme))
    keys shouldBe Seq("u1", "u2", "u3")  // Charlie, Alice, Bob

  test("Table: ascending sort by name reorders rows"):
    val sort = new ReactiveSignal[TableSort]("sort", TableSort(0, SortDirection.Asc))
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols,
      sort = Some(sort))
    val keys = rowKeys(Toolkit.lower(node, theme))
    keys shouldBe Seq("u2", "u3", "u1")  // Alice, Bob, Charlie

  test("Table: descending sort reverses order"):
    val sort = new ReactiveSignal[TableSort]("sort", TableSort(0, SortDirection.Desc))
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols,
      sort = Some(sort))
    val keys = rowKeys(Toolkit.lower(node, theme))
    keys shouldBe Seq("u1", "u3", "u2")  // Charlie, Bob, Alice

  test("Table: sort by numeric column uses numeric ordering"):
    val sort = new ReactiveSignal[TableSort]("sort", TableSort(1, SortDirection.Asc))
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols,
      sort = Some(sort))
    val keys = rowKeys(Toolkit.lower(node, theme))
    keys shouldBe Seq("u2", "u1", "u3")  // 28, 33, 45

  test("Table: SortDirection.Off keeps input order"):
    val sort = new ReactiveSignal[TableSort]("sort", TableSort(0, SortDirection.Off))
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols,
      sort = Some(sort))
    val keys = rowKeys(Toolkit.lower(node, theme))
    keys shouldBe Seq("u1", "u2", "u3")

  test("Table: sort = Asc puts aria-sort=ascending on active header"):
    val sort = new ReactiveSignal[TableSort]("sort", TableSort(0, SortDirection.Asc))
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols,
      sort = Some(sort))
    val view = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val ths  = collectTags(view, "th")
    ths.head.attrs("aria-sort").asInstanceOf[AttrValue.Str].value shouldBe "ascending"
    // Inactive column has aria-sort=none
    ths(1).attrs("aria-sort").asInstanceOf[AttrValue.Str].value shouldBe "none"

  test("Table: sortable header is clickable + cycles state"):
    val sort = new ReactiveSignal[TableSort]("sort", TableSort(-1, SortDirection.Off))
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols,
      sort = Some(sort))
    val view = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val firstTh = collectTags(view, "th").head
    firstTh.events.get("click") match
      case Some(EventHandler.Simple(fn)) =>
        fn()
        sort().columnIndex shouldBe 0
        sort().direction   shouldBe SortDirection.Asc
      case other => fail(s"expected click handler, got $other")

  test("Table: clicking active asc header advances to desc"):
    val sort = new ReactiveSignal[TableSort]("sort", TableSort(0, SortDirection.Asc))
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols,
      sort = Some(sort))
    val firstTh = collectTags(
      Toolkit.lower(node, theme).asInstanceOf[View.Element], "th").head
    firstTh.events("click") match
      case EventHandler.Simple(fn) => fn()
      case _                        => fail("no click")
    sort().direction shouldBe SortDirection.Desc

  test("Table: clicking active desc header turns sort off"):
    val sort = new ReactiveSignal[TableSort]("sort", TableSort(0, SortDirection.Desc))
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols,
      sort = Some(sort))
    val firstTh = collectTags(
      Toolkit.lower(node, theme).asInstanceOf[View.Element], "th").head
    firstTh.events("click") match
      case EventHandler.Simple(fn) => fn()
      case _                        => fail("no click")
    sort().direction shouldBe SortDirection.Off

  test("Table: column with no sortBy is not click-sortable"):
    val noSortCols = Seq(Column[User]("Name", u => TextNode(u.name)))
    val sort = new ReactiveSignal[TableSort]("sort", TableSort(-1, SortDirection.Off))
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = noSortCols,
      sort = Some(sort))
    val firstTh = collectTags(
      Toolkit.lower(node, theme).asInstanceOf[View.Element], "th").head
    firstTh.events.get("click") shouldBe None
    firstTh.attrs.get("aria-sort") shouldBe None

  // ─── Empty state ──────────────────────────────────────────────

  test("Table: emptyState renders when rows is empty"):
    val node = TableNode(rows = rowsSignal(Seq.empty), key = _.id, columns = cols,
      emptyState = Some(TextNode("No users yet.")))
    val view = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val tbody = view.children.collectFirst {
      case e: View.Element if e.tag == "tbody" => e
    }.getOrElse(fail("no tbody"))
    tbody.children.length shouldBe 1
    val td = tbody.children.head.asInstanceOf[View.Element]
      .children.head.asInstanceOf[View.Element]
    td.attrs("colspan").asInstanceOf[AttrValue.Num].value shouldBe 2.0
    extractText(td) should include ("No users yet.")

  test("Table: no emptyState ⇒ tbody is empty when rows empty"):
    val node = TableNode(rows = rowsSignal(Seq.empty), key = _.id, columns = cols)
    val view = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val tbody = view.children.collectFirst {
      case e: View.Element if e.tag == "tbody" => e
    }.getOrElse(fail("no tbody"))
    tbody.children shouldBe empty

  // ─── Column align + width ─────────────────────────────────────

  test("Table: column align=Right propagates to td text-align"):
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols)
    val view = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val tbody = view.children.collect {
      case e: View.Element if e.tag == "tbody" => e
    }.head
    val firstRow = tbody.children.head.asInstanceOf[View.Element]
    // Second column ("Age") was defined with ColumnAlign.Right
    val ageTd = firstRow.children(1).asInstanceOf[View.Element]
    ageTd.attrs("style").asInstanceOf[AttrValue.Str].value should include ("text-align: right")

  test("Table: column width adds CSS width to th + td"):
    val widthCols = Seq(Column[User]("Name", u => TextNode(u.name), width = Some("200px")))
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = widthCols)
    val view = Toolkit.lower(node, theme).asInstanceOf[View.Element]
    val th = collectTags(view, "th").head
    th.attrs("style").asInstanceOf[AttrValue.Str].value should include ("width: 200px")
    val tds = collectTags(view, "td")
    tds.head.attrs("style").asInstanceOf[AttrValue.Str].value should include ("width: 200px")

  // ─── Defensive / edge cases ───────────────────────────────────

  test("Table: out-of-range sort column index falls back to input order"):
    val sort = new ReactiveSignal[TableSort]("sort", TableSort(42, SortDirection.Asc))
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols,
      sort = Some(sort))
    val keys = rowKeys(Toolkit.lower(node, theme))
    keys shouldBe Seq("u1", "u2", "u3")

  test("Table: facade Tk.table + Tk.column compose"):
    import Tk.*
    val rows = rowsSignal(sample)
    val tree = table(
      rows    = rows,
      key     = _.id,
      columns = Seq(
        column[User]("Name")(u => text(u.name)),
        sortableColumn[User]("Age", _.age: Integer)(u => text(u.age.toString))
      )
    )
    val view = Toolkit.lower(tree, theme).asInstanceOf[View.Element]
    view.tag shouldBe "table"
    collectTags(view, "th").length shouldBe 2
    collectTags(view, "tr").length shouldBe 4  // 1 header + 3 body

  test("Table: dark theme uses dark border colour"):
    val node = TableNode(rows = rowsSignal(sample), key = _.id, columns = cols)
    val view = Toolkit.lower(node, Theme.dark).asInstanceOf[View.Element]
    val thead = view.children.collectFirst {
      case e: View.Element if e.tag == "thead" => e
    }.getOrElse(fail("no thead"))
    val style = thead.attrs("style").asInstanceOf[AttrValue.Str].value
    style should include (Theme.dark.colors.surface)

  // ─── helpers ──────────────────────────────────────────────────

  private def extractText(v: View): String = v match
    case View.Element(_, _, _, kids)   => kids.map(extractText).mkString
    case View.TextNode(thunk)          =>
      try thunk() catch case _: Throwable => ""
    case _                              => ""

  /** Depth-first collect every View.Element with the given tag. */
  private def collectTags(v: View, tag: String): Seq[View.Element] = v match
    case e @ View.Element(t, _, _, kids) =>
      val here = if t == tag then Seq(e) else Seq.empty
      here ++ kids.flatMap(collectTags(_, tag))
    case _ => Seq.empty

  private def rowKeys(v: View): Seq[String] =
    val view = v.asInstanceOf[View.Element]
    val tbody = view.children.collectFirst {
      case e: View.Element if e.tag == "tbody" => e
    }.get
    tbody.children.collect {
      case e: View.Element =>
        e.attrs.get("data-row-key").collect {
          case AttrValue.Str(s) => s
        }
    }.flatten
