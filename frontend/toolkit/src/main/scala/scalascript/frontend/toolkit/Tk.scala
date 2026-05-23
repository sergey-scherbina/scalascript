package scalascript.frontend.toolkit

import scalascript.frontend.Signal

/** Fluent facade — the entry point user code reaches for.  All
 *  widgets are exposed as plain functions that return
 *  `ToolkitNode`s, ready to compose:
 *
 *  ```scala
 *  import scalascript.frontend.toolkit.Tk.*
 *
 *  val name  = signal("")
 *  val agreed = signal(false)
 *
 *  val app = stack(direction = Vertical, gap = 16,
 *    children = Seq(
 *      heading(1, "Welcome"),
 *      textField(value = name, label = Some("Name"), required = true),
 *      checkbox(checked = agreed, label = "I accept the terms"),
 *      button("Save", onClick = () => save(name(), agreed()))
 *    ))
 *
 *  // Compile to View[?] via lower(app, Theme.default)
 *  ```
 *
 *  `Tk` exists alongside the case-class node constructors because
 *  it keeps user code import-light + lets us evolve the case-class
 *  signatures (rename / split parameters) without breaking source. */
object Tk:

  // ─── Layout ─────────────────────────────────────────────────────

  def stack(
    direction: StackDirection  = StackDirection.Vertical,
    gap:       Int             = 0,
    align:     Alignment       = Alignment.Stretch,
    children:  Seq[ToolkitNode]
  ): ToolkitNode = StackNode(direction, gap, align, children)

  def vstack(gap: Int = 0, align: Alignment = Alignment.Stretch)(children: ToolkitNode*): ToolkitNode =
    StackNode(StackDirection.Vertical, gap, align, children)

  def hstack(gap: Int = 0, align: Alignment = Alignment.Stretch)(children: ToolkitNode*): ToolkitNode =
    StackNode(StackDirection.Horizontal, gap, align, children)

  def box(
    padding: Int             = 0,
    bg:      Option[String]  = None,
    radius:  Option[String]  = None,
    border:  Option[String]  = None,
    shadow:  Option[String]  = None,
    width:   Option[String]  = None
  )(child: ToolkitNode): ToolkitNode =
    BoxNode(padding, bg, radius, border, shadow, width, child)

  def spacer(grow: Boolean = false, size: Int = 0): ToolkitNode =
    SpacerNode(grow, size)

  def divider(orientation: StackDirection = StackDirection.Horizontal): ToolkitNode =
    DividerNode(orientation)

  // ─── Typography ────────────────────────────────────────────────

  def text(
    s:       String,
    variant: TextVariant      = TextVariant.Body,
    color:   Option[String]   = None,
    weight:  Option[Int]      = None
  ): ToolkitNode = TextNode(s, variant, color, weight)

  def heading(level: Int, s: String): ToolkitNode = HeadingNode(level, s)

  def code(s: String): ToolkitNode = TextNode(s, TextVariant.Code)

  // ─── Inputs ─────────────────────────────────────────────────────

  def button(
    label:      String,
    onClick:    () => Unit,
    kind:       ButtonKind  = ButtonKind.Primary,
    size:       WidgetSize  = WidgetSize.Md,
    disabled:   Boolean      = false,
    formSubmit: Boolean      = false
  ): ToolkitNode = ButtonNode(label, onClick, kind, size, disabled, formSubmit)

  def textField(
    value:       Signal[String],
    label:       Option[String]   = None,
    placeholder: Option[String]   = None,
    required:    Boolean           = false,
    disabled:    Boolean           = false,
    size:        WidgetSize        = WidgetSize.Md,
    inputType:   String            = "text",
    error:       Option[Signal[Option[String]]] = None
  ): ToolkitNode = TextFieldNode(value, label, placeholder, required, disabled, size, inputType, error)

  def checkbox(
    checked:  Signal[Boolean],
    label:    String,
    disabled: Boolean = false
  ): ToolkitNode = CheckboxNode(checked, label, disabled)

  // ─── Display ───────────────────────────────────────────────────

  /** Status / error banner.
   *
   *  Note: `alert` shadows `org.scalatest.Alerting.alert` when both
   *  are in scope (e.g. a ScalaTest suite that does `import Tk.*`).
   *  Inside a test class, write `Tk.alert(...)` explicitly, or use
   *  the [[notice]] alias below. */
  def alert(
    severity: AlertSeverity,
    title:    Option[String] = None
  )(child: ToolkitNode): ToolkitNode =
    AlertNode(severity, title, child)

  /** Alias for [[alert]] — present so user code can avoid the
   *  ScalaTest `alert` collision without writing `Tk.alert` every
   *  time. */
  def notice(
    severity: AlertSeverity,
    title:    Option[String] = None
  )(child: ToolkitNode): ToolkitNode =
    AlertNode(severity, title, child)

  // ─── Containers ────────────────────────────────────────────────

  def card(
    header: Option[ToolkitNode] = None,
    footer: Option[ToolkitNode] = None
  )(body: ToolkitNode): ToolkitNode =
    CardNode(header, footer, body)

  // ─── Form ──────────────────────────────────────────────────────

  /** Fluent entry point for the `Form` widget.  The `build` callback
   *  receives a fresh `FormContext` for registering fields and
   *  returns the form body (typically a `Stack` of inputs + a submit
   *  button).  See `Form.scala` for the full API. */
  def form(onSubmit: FormContext => Unit)(build: FormContext => ToolkitNode): ToolkitNode =
    FormNode(onSubmit, build)

  /** Overload that uses a caller-supplied `FormContext` instead of
   *  the fresh one allocated inside lowering.  Useful when outer
   *  code (tests, parent components, navigation controllers) needs
   *  to observe field values or drive submit / reset from outside
   *  the form's render scope. */
  def formWithContext(ctx: FormContext, onSubmit: FormContext => Unit)(
    build: FormContext => ToolkitNode
  ): ToolkitNode = FormNode(onSubmit, build, Some(ctx))

  // ─── Routing ───────────────────────────────────────────────────

  /** Construct a router from a varargs route table.  `currentPath`
   *  is the source of truth for which route renders; `notFound`
   *  is the fallback when nothing matches. */
  def router(currentPath: Signal[String], notFound: ToolkitNode)(routes: Route*): ToolkitNode =
    RouterNode(routes.toList, currentPath, notFound)

  /** Define a single route — `path` may contain `:name`
   *  placeholders (e.g. `/users/:id`); `render` receives the
   *  extracted params at match time. */
  def route(path: String)(render: Map[String, String] => ToolkitNode): Route =
    Route(path, render)

  /** Navigation link.  When `currentPath` is supplied, clicking
   *  routes in-app (signal write + preventDefault); otherwise the
   *  browser handles the navigation. */
  def link(to: String, currentPath: Option[Signal[String]] = None)(child: ToolkitNode): ToolkitNode =
    LinkNode(to, child, currentPath)

  // ─── Table ─────────────────────────────────────────────────────

  /** Typed data table.  Pass a `Signal[Seq[T]]` for rows + a key
   *  extractor + a column list.  Optional `sort` signal enables
   *  click-to-sort headers; `emptyState` is shown when the rows
   *  signal is empty.  See `Table.scala` for full docs. */
  def table[T](
    rows:       Signal[Seq[T]],
    key:        T => String,
    columns:    Seq[Column[T]],
    sort:       Option[Signal[TableSort]] = None,
    emptyState: Option[ToolkitNode]       = None,
    caption:    Option[String]            = None
  ): ToolkitNode = TableNode(rows, key, columns, sort, emptyState, caption)

  /** Construct a Column with the common defaults — `align = Left`,
   *  no sort, no fixed width. */
  def column[T](title: String)(render: T => ToolkitNode): Column[T] =
    Column(title, render)

  /** Construct a sortable Column — `sortBy` extracts the sort key
   *  (must be a `Comparable[?]`, e.g. `String`, `Int`, `Long`). */
  def sortableColumn[T](title: String, sortBy: T => Comparable[?])(render: T => ToolkitNode): Column[T] =
    Column(title, render, sortBy = Some(sortBy))

  // ─── Widgets v2 pack ───────────────────────────────────────────

  /** Range slider — `<input type="range">` two-way bound to a
   *  `Signal[Double]`. */
  def slider(
    value:    Signal[Double],
    min:      Double,
    max:      Double,
    step:     Double         = 1.0,
    label:    Option[String] = None,
    disabled: Boolean        = false
  ): ToolkitNode = SliderNode(value, min, max, step, label, disabled)

  /** Tabbed container.  `entries` is varargs to read naturally at the
   *  call site: `Tk.tabs(activeTab)(Tk.tab("a","A",bodyA), Tk.tab("b","B",bodyB))`. */
  def tabs(active: Signal[String])(entries: TabEntry*): ToolkitNode =
    TabsNode(active, entries)

  /** One tab definition for `Tk.tabs`. */
  def tab(id: String, label: String, content: ToolkitNode): TabEntry =
    TabEntry(id, label, content)

  /** Modal dialog with backdrop.  `open` drives visibility; `onClose`
   *  fires on backdrop click + Escape key.  Empty Fragment when closed. */
  def modal(
    open:    Signal[Boolean],
    title:   Option[String] = None,
    onClose: () => Unit     = () => ()
  )(child: ToolkitNode): ToolkitNode =
    ModalNode(open, title, onClose, child)

  /** Slide-out drawer anchored to one edge of the viewport. */
  def drawer(
    open:    Signal[Boolean],
    side:    DrawerSide      = DrawerSide.Left,
    onClose: () => Unit      = () => ()
  )(child: ToolkitNode): ToolkitNode =
    DrawerNode(open, side, onClose, child)

  /** Native HTML tooltip — wraps `child` in `<span title="...">`. */
  def tooltip(text: String)(child: ToolkitNode): ToolkitNode =
    TooltipNode(text, child)

  /** Small pill / badge. */
  def badge(content: String, variant: BadgeVariant = BadgeVariant.Default): ToolkitNode =
    BadgeNode(content, variant)

  /** Circular avatar — image when `src` is supplied, initials
   *  fallback otherwise. */
  def avatar(name: String, src: Option[String] = None, size: Int = 40): ToolkitNode =
    AvatarNode(name, src, size)

  /** Placeholder icon — emits `<span data-icon="$name">`. */
  def icon(name: String, size: WidgetSize = WidgetSize.Md): ToolkitNode =
    IconNode(name, size)

  /** CSS-only loading spinner. */
  def spinner(size: WidgetSize = WidgetSize.Md): ToolkitNode =
    SpinnerNode(size)

  /** Determinate `<progress>` indicator. */
  def progress(value: Signal[Double], max: Double, label: Option[String] = None): ToolkitNode =
    ProgressNode(value, max, label)

  // ─── FormInputs pack ───────────────────────────────────────────

  /** Typed dropdown.  `options` are `(value, label)` pairs.  `toKey`
   *  defaults to `_.toString` — override for non-trivial value
   *  types where `toString` isn't stable. */
  def select[T](
    value:       Signal[T],
    options:     Seq[(T, String)],
    label:       Option[String]    = None,
    placeholder: Option[String]    = None,
    required:    Boolean           = false,
    disabled:    Boolean           = false,
    size:        WidgetSize        = WidgetSize.Md,
    toKey:       T => String       = (t: T) => t.toString,
    error:       Option[Signal[Option[String]]] = None
  ): ToolkitNode = SelectNode(value, options, toKey, label, placeholder,
    required, disabled, size, error)

  /** Typed radio-button group. */
  def radioGroup[T](
    value:       Signal[T],
    options:     Seq[(T, String)],
    label:       Option[String]      = None,
    orientation: RadioOrientation    = RadioOrientation.Vertical,
    required:    Boolean             = false,
    disabled:    Boolean             = false,
    toKey:       T => String         = (t: T) => t.toString,
    error:       Option[Signal[Option[String]]] = None
  ): ToolkitNode = RadioGroupNode(value, options, toKey, label, required,
    disabled, orientation, error)

  /** Multi-line text input. */
  def textarea(
    value:       Signal[String],
    label:       Option[String]  = None,
    placeholder: Option[String]  = None,
    rows:        Int             = 4,
    required:    Boolean         = false,
    disabled:    Boolean         = false,
    size:        WidgetSize      = WidgetSize.Md,
    maxLength:   Option[Int]     = None,
    error:       Option[Signal[Option[String]]] = None
  ): ToolkitNode = TextareaNode(value, label, placeholder, rows, required,
    disabled, size, maxLength, error)

  /** ISO-8601 (`YYYY-MM-DD`) date input. */
  def datePicker(
    value:    Signal[String],
    label:    Option[String]  = None,
    required: Boolean         = false,
    disabled: Boolean         = false,
    size:     WidgetSize      = WidgetSize.Md,
    min:      Option[String]  = None,
    max:      Option[String]  = None,
    error:    Option[Signal[Option[String]]] = None
  ): ToolkitNode = DatePickerNode(value, label, required, disabled, size,
    min, max, error)

  /** Numeric input bound to a `Signal[Double]`. */
  def numberInput(
    value:    Signal[Double],
    label:    Option[String]  = None,
    required: Boolean         = false,
    disabled: Boolean         = false,
    size:     WidgetSize      = WidgetSize.Md,
    min:      Option[Double]  = None,
    max:      Option[Double]  = None,
    step:     Double          = 1.0,
    error:    Option[Signal[Option[String]]] = None
  ): ToolkitNode = NumberInputNode(value, label, required, disabled, size,
    min, max, step, error)
