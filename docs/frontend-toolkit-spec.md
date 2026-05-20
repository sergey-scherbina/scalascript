# Frontend Toolkit — High-Level Declarative UI Spec

This document specifies a **high-level declarative UI toolkit** that
sits on top of the framework-agnostic `frontend-core` SPI.

  - **Low level** (already exists): `View.Element("div", attrs,
    events, children)`, `Signal[T]`, `Component[P]`, four backends
    (Custom / React / Vue / Solid).  Reactivity primitives.
  - **High level** (this spec): `Stack { Text("Hello"); Button(...) }`
    — semantic widgets.  No HTML / CSS literals in user code.  Same
    user-facing surface lowers to any backend.

The toolkit is **the next layer up** — what a real application
author writes against, not what a framework integrator implements.

## The contract

The toolkit exposes a fixed vocabulary of **semantic widgets** that
ScalaScript code uses declaratively.  Every widget lowers to a
backend-agnostic `View` tree through a pure `widget.toView(theme)`
function — backends never see toolkit nodes, only the primitives
they already know.

```
   .ssc user code                        backends (existing)
   ─────────────────                     ───────────────────
   Stack(direction = Vertical) {         View.Element("div", ...) ──┐
     Heading(1)("Welcome")        ─→     View.Element("h1", ...)    │ react / vue
     TextField(name, "Name")             View.Element("input", ...) │ solid / custom
     Button("Save", onSave)              View.Element("button", ...)│
   }                                                                ▼
                                         frameworkSpi.emit(module)
```

The user writes once; the build picks the backend.

## Goals + non-goals

### Goals

  1. **Backend-agnostic** — same toolkit source runs on every
     `FrontendFrameworkSpi` impl.
  2. **Declarative composition** — widgets compose via function
     calls + block syntax; no imperative DOM manipulation in user
     code.
  3. **Built-in accessibility** — widgets emit correct ARIA roles +
     keyboard handlers by default; opt-out for advanced cases.
  4. **Theming via tokens** — design system primitives
     (colors / spacing / typography / radii) drive every widget;
     swapping themes is a single config change.
  5. **Composable into apps** — Form / Modal / List / Router are
     real building blocks, not stubs.
  6. **Reactive by default** — every value-binding (input value,
     visibility, list items) takes a `Signal[T]`; the toolkit
     handles subscription glue.

### Non-goals

  1. **CSS-in-Scala**.  Widgets accept theme tokens + a small
     `Style` builder (`padding`, `bg`, `radius`); they don't try to
     be Tailwind / Emotion replacements.
  2. **DOM-level escape hatches** — the toolkit is for the 90% case.
     For exotic needs, drop down to raw `View.Element(...)`.
  3. **Animations + transitions** — defer to Phase 2.
  4. **Drag-and-drop / virtualization / WebGL** — deferred.
  5. **Pre-built brand themes** — the spec ships one neutral
     reference theme; design teams override.

## The toolkit, in five layers

### Layer 1 — Layout primitives

The minimum a real app needs to lay out content responsively.
Implemented over flexbox + grid; no toolkit-side margin collapse
weirdness.

```scala
Stack(direction = Vertical, gap = 16, align = Center) {
  child1
  child2
}

Grid(columns = 12, gap = 8) {
  Cell(span = 6) { ... }
  Cell(span = 6) { ... }
}

Box(padding = 16, bg = surface, radius = md) { child }
Spacer(grow = true)             // flexible filler
Center { child }                // single-child centering
Divider(orientation = Horizontal)
Scroll(direction = Vertical) { longList }
```

### Layer 2 — Typography

```scala
Heading(level = 1)("Welcome")            // h1
Heading(level = 2, weight = Semibold)("Sub-section")

Text("Some body copy", variant = Body)
Text("Caption text", variant = Caption, color = muted)

Paragraph { Text("Mixed "); Strong("emphasis"); Text(" inline.") }

Code("ssc compile app.ssc", language = "bash")

Link("Documentation", href = "/docs", external = false)
```

### Layer 3 — Input controls

Every input that holds a value takes a `Signal[T]` — the toolkit
wires `value`, `onChange`, and `aria-*` attributes consistently.

```scala
val name = signal("")
val agreed = signal(false)
val plan = signal("free")
val volume = signal(50)

TextField(
  value       = name,
  label       = "Full name",
  placeholder = "Jane Doe",
  required    = true,
  validate    = v => if v.length < 2 then Some("too short") else None
)

TextArea(value = bio, label = "Bio", rows = 5)

Checkbox(checked = agreed, label = "I accept the terms")

Switch(on = darkMode, label = "Dark mode")

RadioGroup(value = plan, label = "Plan",
  options = List("free" -> "Free", "pro" -> "Pro", "enterprise" -> "Enterprise"))

Select(value = country, label = "Country", options = countries)

Slider(value = volume, min = 0, max = 100, step = 5)

NumberField(value = age, min = 0, max = 120)

DatePicker(value = birthday)

Form(onSubmit = saveProfile) {
  TextField(name, "Name", required = true)
  Button("Save", kind = Primary, formSubmit = true)
}
```

### Layer 4 — Action + display

```scala
Button("Save", onClick = save, kind = Primary, size = md)
Button("Cancel", onClick = cancel, kind = Subtle)
Button.Icon(icon = "trash", onClick = delete, label = "Delete row")

Icon(name = "check", size = sm)

Image(src = "/avatar.png", alt = "Avatar", radius = full)
Avatar(name = "Jane Doe", src = avatarUrl)  // initials fallback

Badge("3", variant = Notification)
Tag("pro", variant = Subtle)

Alert(severity = Warning, title = "Heads up") {
  Text("This will overwrite existing data.")
}

Progress(value = uploaded, max = 100, label = "Upload")
Spinner(size = lg)

Tooltip("Save to your library") {
  Button("Save", onClick = save)
}
```

### Layer 5 — Containers + composition

```scala
Card(
  header = Heading(2)("Account"),
  footer = Button("Edit", onClick = openEditor)
) {
  Text("alice@example.com")
}

Modal(open = showConfirm,
  title = "Delete project?",
  onClose = () => showConfirm := false
) {
  Text("This cannot be undone.")
  Stack(direction = Horizontal, gap = 8, align = End) {
    Button("Cancel", onClick = () => showConfirm := false, kind = Subtle)
    Button("Delete",  onClick = doDelete,                   kind = Danger)
  }
}

Accordion {
  Section("Profile") { profileForm }
  Section("Billing") { billingForm }
}

Tabs(active = activeTab) {
  Tab("overview")(content1)
  Tab("usage")(content2)
  Tab("settings")(content3)
}

Drawer(open = navOpen, side = Left) { sidebar }
Sheet(open = pickerOpen, side = Bottom) { picker }     // mobile-style
```

Data display:

```scala
Table(rows = users, key = _.id) {
  Column("Name",   row => Text(row.name))
  Column("Email",  row => Link(row.email, href = s"mailto:${row.email}"))
  Column("Status", row => Badge(row.status))
}

List(items = todos, key = _.id) { item =>
  Stack(direction = Horizontal, gap = 8) {
    Checkbox(checked = item.done, onChange = toggle(item.id))
    Text(item.title)
  }
}

Tree(root = fileSystem, expanded = expanded) { node =>
  Stack(direction = Horizontal) {
    Icon(if node.isDir then "folder" else "file")
    Text(node.name)
  }
}
```

### Layer 6 — Async + state patterns

```scala
Async(load = fetchUser(userId)) {
  case Loading       => Spinner(size = lg)
  case Failed(err)   => Alert(severity = Error)(Text(err.message))
  case Loaded(user)  => UserCard(user)
}

Empty(when = items.isEmpty, message = "No items yet") {
  ItemList(items)
}

ErrorBoundary(onError = log) {
  RiskyComponent()
}
```

### Layer 7 — Navigation + routing

```scala
Router {
  Route("/")             { HomePage() }
  Route("/users/:id")    { params => UserPage(params("id")) }
  Route("/settings/*")   { SettingsLayout() }  // nested
}

NavBar {
  Link("Home", "/")
  Link("Users", "/users")
  Spacer(grow = true)
  Avatar(name = "Jane")
}

Breadcrumbs {
  Crumb("Home", "/")
  Crumb("Users", "/users")
  Crumb("Jane")
}
```

## Theming

A `Theme` is a record of design tokens — pure data, frozen at app
mount time, threaded through the component tree via context.

```scala
case class Theme(
  colors:     ColorPalette,
  spacing:    SpacingScale,
  typography: TypographyScale,
  radii:      RadiusScale,
  shadows:    ShadowScale,
  motion:     MotionScale
)

case class ColorPalette(
  primary:   Color,
  secondary: Color,
  success:   Color,
  warning:   Color,
  danger:    Color,
  neutral:   Map[Int, Color],  // 50, 100, 200, ..., 900
  surface:   Color,
  background: Color,
  onPrimary: Color,
  onSurface: Color
)

case class SpacingScale(
  xs: Int,  // 4
  sm: Int,  // 8
  md: Int,  // 16
  lg: Int,  // 24
  xl: Int,  // 32
  xxl: Int  // 48
)
```

The toolkit ships **one reference theme** (neutral, accessible, light
+ dark variants).  Apps swap with one call:

```scala
App(theme = Theme.darkOcean) {
  HomePage()
}
```

Widgets read tokens via implicit `using Theme`:

```scala
// inside Button.toView(theme):
View.Element("button",
  attrs = Map(
    "style" -> s"background: ${theme.colors.primary}; padding: ${theme.spacing.sm}px"
  ),
  ...)
```

## Accessibility

Built-in.  Every widget that needs ARIA emits it:

  - `Button` → `role="button"`, `aria-disabled` when disabled
  - `Checkbox` → `role="checkbox"`, `aria-checked`, paired `<label>`
  - `Modal` → `role="dialog"`, `aria-modal`, focus trap, ESC handler
  - `Tabs` → `role="tablist"`, `role="tab"`, `aria-selected`,
    arrow-key navigation
  - `Alert` → `role="alert"` (or `role="status"` for low-priority)
  - `TextField` → `<label for="..."` ↔ `id="..."` binding,
    `aria-invalid` when validation fails, `aria-describedby` on the
    helper text

User-supplied widgets that need ARIA roles use the same API:

```scala
Box(role = "navigation", ariaLabel = "Main menu") { ... }
```

## How widgets lower to the SPI

Each widget is a Scala function `WidgetName(params) =>
ToolkitNode`.  A pure `lower(node, theme): View` walks the toolkit
tree and produces a `View`:

```scala
// Toolkit-level — what the user writes
case class StackNode(
  direction: StackDirection,
  gap:       Int,
  align:     Alignment,
  children:  Seq[ToolkitNode]
) extends ToolkitNode

// Lowering — toolkit → View (SPI)
def lower(n: ToolkitNode, theme: Theme): View = n match
  case StackNode(dir, gap, align, kids) =>
    val css = stackCss(dir, gap, align, theme)
    View.Element("div",
      attrs    = Map("style" -> AttrValue.Str(css)),
      events   = Map.empty,
      children = kids.map(lower(_, theme))
    )
  case ButtonNode(label, onClick, kind, ...) =>
    View.Element("button",
      attrs  = Map(
        "style" -> AttrValue.Str(buttonCss(kind, theme)),
        "type"  -> AttrValue.Str("button"),
        "role"  -> AttrValue.Str("button")),
      events = Map("click" -> EventHandler.Simple(onClick)),
      children = Seq(View.TextNode(() => label))
    )
  // ...
```

Backends never see `StackNode` — only `View.Element("div", ...)`.
The toolkit is a **shape-preserving translation layer**; new
backends don't have to learn about it.

## Reactive bindings

Every value-binding accepts a `Signal[T]`:

```scala
val count = signal(0)
val visible = signal(true)
val items = signal(List.empty[Todo])

Heading(1) { Text(count().toString) }     // re-renders on count change
Show(visible) { ImportantBanner() }       // toggles
For(items)   { todo => TodoRow(todo) }    // diffs the list

// Two-way binding through .set or :=
TextField(value = name)                   // signal both reads + sets
Checkbox(checked = agreed)
```

Inside a `Form`, validation state is reactive:

```scala
val email = signal("")
val emailError = computed:
  if !email().contains("@") then Some("Must contain @") else None

TextField(value = email, label = "Email", error = emailError)
```

## Form binding pattern

`Form` is the most opinionated widget.  It centralises:

  - submit handling (form-level + per-field validation gates it)
  - per-field error rendering (drives `TextField.error` automatically)
  - keyboard semantics (Enter submits the focused field's form)
  - disabled-while-submitting state

```scala
Form { form =>
  val name  = form.field[String]("name",  required = true, minLength = 2)
  val email = form.field[String]("email", required = true,
                                  validate = isEmail)
  val plan  = form.field[String]("plan",  default = "free")

  TextField(name,  "Name")
  TextField(email, "Email")
  Select(plan,     "Plan", options = plans)

  form.onSubmit { values =>
    api.createAccount(values("name"), values("email"), values("plan"))
  }

  Button("Create account", formSubmit = true, kind = Primary)
}
```

## What ships in v1 vs v2

### v1 (initial implementation)

Core widgets enough for a real CRUD form:

  - Layout: `Stack`, `Box`, `Grid`, `Spacer`, `Divider`, `Center`
  - Typography: `Heading`, `Text`, `Paragraph`, `Link`, `Code`
  - Input: `Button`, `TextField`, `TextArea`, `Checkbox`, `Switch`,
    `RadioGroup`, `Select`, `Form`
  - Display: `Image`, `Icon`, `Badge`, `Alert`, `Spinner`, `Progress`
  - Containers: `Card`, `Modal`, `Tabs`
  - State: `Show`, `For`, `Async`, `Empty`
  - Theme: one reference + dark variant + token API

### v2 — landed in Phase B+ / Phase B++

  - **Forms + validation** — `Form`, `FormField[T]`, `FormContext`,
    `Validators` (required, minLength, maxLength, pattern, email, and).
  - **Routing** — `Router`, `Route`, `Link` with `:name` params, query
    + trailing-slash normalisation, SPA + plain-anchor link modes.
  - **v2 widget pack** — `Slider`, `Tabs`, `Modal`, `Drawer`,
    `Tooltip`, `Badge`, `Avatar`, `Icon`, `Spinner`, `Progress`.
  - **Table** — typed `Table[T]` with click-to-sort, ARIA, caption,
    empty-state slot.  (Filtering + pagination still caller-side via
    pre-sliced `Signal[Seq[T]]`; virtualisation deferred.)
  - **FormInputs pack** — `Select[T]`, `RadioGroup[T]`, `Textarea`,
    `DatePicker`, `NumberInput`.

### Still deferred to a later iteration

  - `ColorPicker`, `TimePicker`, multi-select, combobox/autocomplete,
    file upload.
  - `Table` v2: filtering UI, pagination control, virtualisation,
    column resize/reorder.
  - `Tree`, `Accordion`, `Sheet`, `Popover`.
  - Nested-route lazy loading.
  - `Toast` / `Notification` queue.
  - Animations + transitions (CSS-only is OK in user code today).
  - i18n integration (RTL + locale-aware formatters — see decision §5).
  - DnD primitives.

## Spec compliance — what the SPI promises the toolkit

The toolkit assumes only `View` + `Signal` + `Component` from the
SPI.  No backend-specific behaviour is encoded.  In particular:

  - `For[T]` works regardless of whether the backend re-renders or
    fine-grain-patches.
  - `Show` works regardless of whether the backend swaps the
    subtree or re-renders.
  - `EventHandler.Simple` and `EventHandler.WithEvent` are the only
    handler shapes the toolkit produces — backends already handle
    both per the SPI contract.

If a future backend declares `Capability.X = false`, only the
specific advanced widgets requiring X fail compile-time — the
toolkit core works.

## Code organisation

```
frontend-toolkit/             (new module)
  src/main/scala/scalascript/frontend/toolkit/
    Toolkit.scala             — sealed trait ToolkitNode + lowering
    Theme.scala               — design tokens
    layout/Stack.scala         — Stack, Box, Grid, Spacer, ...
    typography/Text.scala      — Heading, Text, Paragraph, ...
    input/Button.scala         — Button + variants
    input/TextField.scala      — TextField + validation
    input/Form.scala           — Form binding
    display/Alert.scala        — Alert, Spinner, ...
    containers/Card.scala      — Card, Modal, Tabs
    state/Show.scala           — Show, For, Async, Empty
```

User code imports:

```scala
import scalascript.frontend.toolkit.*
import scalascript.frontend.toolkit.Theme

App(theme = Theme.default) {
  HomePage()
}
```

## Migration path

  1. **Phase 1 ✓ Spec freeze** (this doc).  Landed.
  2. **Phase 2 ✓ Skeleton + Stack/Text/Button**.  Phase B — the
     primitive layer landed with 25 tests in `ToolkitTest`.
  3. **Phase 3 ✓ Inputs + Form** (~26 tests).  Form widget with
     validators, 26 FormTest cases.  Locked in the Signal binding
     pattern: each input takes a `Signal[T]` + optional
     `Signal[Option[String]]` for errors.
  4. **Phase 4 ✓ Containers + state widgets**.  Card landed in
     Phase B; Modal/Drawer/Tabs/Tooltip/Badge/Avatar/Icon/Spinner/
     Progress landed in Phase B+ via the Widgets v2 pack.
  5. **Phase 5 ✓ Theme + reference theme + dark variant**.  `Theme.default`
     + `Theme.dark` ship; every widget reads colour / spacing /
     typography / radius / shadow tokens through `Theme`.
  6. **Phase 6 ✓ Routing + data widgets**.  Phase B+ added `Router`,
     `Route`, `Link`, `Table` with click-to-sort.  Phase B++ added
     `Select`, `RadioGroup`, `Textarea`, `DatePicker`, `NumberInput`.
  7. **Phase 7 (next)** — Reference example app (full SPA exercising
     forms + routing + table + widgets); deeper SSR support; the
     remaining deferred widgets (ColorPicker, TimePicker, combobox).

## Design decisions (formerly open questions)

After Phase B's first-cut implementation landed, the five open
questions resolve as follows.  Decisions backed by concrete tradeoffs
the implementation surfaced — not just preferences.

### 1. Children: varargs `Seq[ToolkitNode]` (decided)

**Decision**: every widget that takes children accepts a plain
`Seq[ToolkitNode]` (or curried `(ToolkitNode*)` in the `Tk` facade).

**Rationale**: the thunk variant `=> Seq[ToolkitNode]` would defer
evaluation, but the toolkit lowers to `View` eagerly anyway — the
deferral happens at the SPI layer where `View.For[T]` and
`View.Show` already accept thunks.  Adding another layer of
laziness in the toolkit would be pure ceremony.  Backend-specific
fine-grained subscriptions (Solid) get the deferral they need
through `View.For` and `View.ShowSignal` — both already part of
the SPI.

**User code**:
```scala
vstack(gap = 16)(
  heading(1, "App"),
  textField(name, label = Some("Name")),
  button("Save", onClick = save))    // children are *direct*, no thunk
```

For dynamic / reactive list children, use the SPI's `View.For` via
`Toolkit.lift(...)` (planned helper) or wrap in `RawViewNode(View.For(...))`
directly until the toolkit ships its own `for_(items)(render)`
widget in v2.

### 2. Slots: named parameters (decided)

**Decision**: optional regions (Card header / footer, Tabs panels,
Form actions, etc.) are exposed as named `Option[ToolkitNode]`
parameters — not as a separate `Slot(...)` marker.

**Rationale**: ScalaScript's parameter syntax IS the slot syntax.
A `Card(header = ..., footer = ..., body = ...)` reads cleanly,
sorts well in IDEs, and round-trips through `case class .copy`
naturally.  `Slot` markers add a layer of indirection for zero
gain over named params.

**Counterexample**: when a slot would need named children with
distinct types (e.g. `Tabs(tab1 = ..., tab2 = ...)` doesn't
generalise) we use a `Seq[Entry]` parameter with a typed `Entry`
case class — see `TabsNode(active, tabs: Seq[TabEntry])`.

### 3. Style escape hatch: raw `style: String` allowed (decided)

**Decision**: widgets accept an optional raw `style: String` that
gets appended to the toolkit's generated CSS.  Defaults to None.
No lint warning in v1 — design teams enforce token discipline
through review, not the compiler.

**Rationale**: every real app eventually needs to drop down to
CSS for one off-spec thing.  Forcing it through a sanctioned
escape hatch keeps the code grep-able and discoverable; banning
it pushes people to wrap widgets in raw `View.Element` which is
strictly worse.

**Phase 2** may add `Theme.lintStrict = true` that warns on `style:
String` usage in `Box`, `Stack`, etc.  Deferred.

### 4. Server-side rendering: pure lowering, guaranteed (decided)

**Decision**: `Toolkit.lower(node, theme)` is a pure function over
`(ToolkitNode, Theme) → View`.  No DOM access, no async work, no
implicit framework calls.  SSR on the JVM calls `lower` and feeds
the resulting `View` to a JVM-side renderer (planned, v2).

**Implementation invariant** (enforced by code review, not type
system): no widget's `lower` method may reference
`scalajs.dom.*`, `org.scalajs.dom.*`, or any framework runtime.
Test suite includes a "lowering output contains no framework
names" smoke check.

### 5. i18n: raw strings + caller-side `t(...)` (decided)

**Decision**: every text-bearing widget (`Text`, `Heading`, `Button`
label, `TextField` label / placeholder, `Alert` title, etc.)
accepts a plain `String`.  Translation is the caller's job —
wrap your literal in `t("welcome_heading")` from the i18n module
of your choice.

**Rationale**: the toolkit shouldn't bake in an i18n API choice
(message keys, ICU MessageFormat, gettext-style, etc.) — there's
no consensus.  Raw strings + wrap-where-needed is the most
flexible composition.

**When frontend i18n lands** (v1.19+, separate module), the toolkit
gains an OPTIONAL `import scalascript.frontend.toolkit.i18n.given`
import that adds an implicit `String → I18nString` conversion +
a `Signal[Locale]`-aware text node.  Strictly additive — existing
code keeps working.

## Migration path

  1. **Phase 1 — Spec freeze** ✓ (this doc).
  2. **Phase 2 — Skeleton + core widgets** ✓ (Stack / Box / Text /
     Heading / Button / TextField / Checkbox / Alert / Card,
     `Theme.default` + `Theme.dark`, 25 tests).
  3. **Phase 3 — Forms + validation** — `Form` container with
     field registration + built-in validators (required,
     minLength, maxLength, pattern, email) + per-field error
     surfacing.
  4. **Phase 4 — v2 widgets** — Slider, Tabs, Modal, Drawer,
     Tooltip, Badge, Avatar, Icon, Spinner, Progress.
  5. **Phase 5 — Routing** — Router + Route + Link + path-param
     matching + nested routes.
  6. **Phase 6 — Data display** — Table (sortable, filterable,
     paginated), List with virtualisation hooks, Tree.
  7. **Phase 7 — Examples** — non-trivial reference apps.

## Status

  - Spec: this doc.  Open questions resolved.
  - Phase 2: shipped (frontend-toolkit module + 25 tests, v1.18 Phase B).
  - Phases 3 / 4 / 5: landing in parallel sub-iterations.
