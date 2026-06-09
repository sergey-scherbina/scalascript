# `std.ui` — Token-aware Styled `TkNode` + Status Primitives

Status: **planned**. Tracked as `ui-styled-tknode` milestone in SPRINT.md.
Origin: busi UI proposals (P2) — `busi/docs/scalascript-ui-proposals.md`; refined in
rozum (`scalascript` room, 2026-06-09). std/ui surface: `runtime/std/ui/nodes.ssc`,
`lower.ssc`, `theme.ssc`, `display.ssc`.

---

## 1. Motivation and the architectural decision

busi screens that need custom chrome drop to raw `element("div", ["style" -> CSS])`
with hardcoded hex:

```scalascript
val cardStyle  = "border:1px solid #e2e8f0;border-radius:8px;padding:12px 16px;..."
val badgeStyle = "font-size:11px;font-weight:600;background:" + statusColor + ";..."
```

The moment a screen does this it **falls out of the theme system** — `Theme.dark` /
`Theme.mobile` no longer apply — and, critically, it is **not portable to native
frontends**. In ScalaScript, `lower.ssc` lowers the backend-agnostic `TkNode` tree
into `element("div", ["style" -> CSS])`; **CSS strings are already the web-lowered
representation**. Authoring against `element(... style:"...")` bypasses `TkNode` and
hard-bakes web output. busi's frontends are web React **and** macOS/iOS SwiftUI
(`tests/phase79-multi-org-native-swiftui`), so this is a real regression vector, not
theoretical.

**Decision (agreed with busi):** do *not* add a CSS-string `style(border=px(...),
bg=#hex)` builder. Instead:

1. Add **status/composite primitives** at the `TkNode` level so screens never drop to
   raw `element()` for common chrome.
2. Add a **token-aware `Style` descriptor** carried on a styled `TkNode`, whose fields
   reference `Theme` tokens (not hex/px literals). `lower` resolves it per target —
   CSS for web today, and native lowerings get correct theming for free when they land.

This fixes dark/mobile theming *and* keeps native parity.

---

## 2. Style descriptor

A pure-data descriptor of the visual properties that screens actually inline today.
Every field is a `Theme`-token reference resolved at lower time. **Layout is out of
scope** (see §5) — it belongs to the layout combinators.

```scalascript
// Token references — symbolic, resolved against the active Theme in lower.ssc.
enum ColorToken  { case Primary, OnPrimary, Secondary, Surface, OnSurface,
                   case Background, Muted, Danger, Success, Warning }
enum SpaceToken  { case Xs, Sm, Md, Lg, Xl, Xxl }
enum RadiusToken { case Sm, Md, Lg, Full }
enum FontToken   { case Body, Heading }

case class BorderStyle(width: Int, color: ColorToken)

// All fields optional (Option) — only set what the screen overrides.
case class Style(
  bg:      Option[ColorToken]  = None,
  fg:      Option[ColorToken]  = None,
  border:  Option[BorderStyle] = None,
  radius:  Option[RadiusToken] = None,
  padding: Option[SpaceToken]  = None,
  margin:  Option[SpaceToken]  = None,
  font:    Option[FontToken]   = None
)

// Styled container node — wraps children with a token-resolved Style.
case class StyledNode(style: Style, children: List[TkNode]) extends TkNode

// Constructor (display.ssc):
def styled(style: Style, children: TkNode*): TkNode = StyledNode(style, children.toList)
```

`lower.ssc` resolves a `Style` into the web representation:
`bg = Some(Surface)` → `background:${theme.colors.surface}`, `radius = Some(Md)` →
`border-radius:${theme.radii.md}px`, `padding = Some(Sm)` → `padding:${theme.spacing.sm}px`,
etc. A native lowering resolves the same tokens to native attributes.

This is the **only** sanctioned escape hatch for custom-styled chrome; screens should
not author raw `element(... style:"...")`.

---

## 3. Status / composite primitives

New `TkNode`s + constructors in `display.ssc`, lowered in `lower.ssc`.

```scalascript
// badge — extend existing BadgeNode variants to status semantics.
// variant is a RUNTIME value (see §4), not a compile-time-only enum.
def badge(content: String, variant: String = "neutral"): TkNode
// variants: "success" | "danger" | "warning" | "info" | "neutral"
// each maps to ColorToken (success→Success, danger→Danger, warning→Warning,
// info→Primary, neutral→Muted) — themed, not hex.

// tag / pill — small labelled token chips.
def tag(content: String, variant: String = "neutral"): TkNode
def pill(content: String, variant: String = "neutral"): TkNode

// kpiCard — the label + value card busi rebuilds across screens.
def kpiCard(label: String, value: TkNode): TkNode

// tabBar — pill tab bar (busi hand-extracted this into web/ui.ssc; promote it).
case class Tab(href: String, label: String, key: String)
def tabBar(active: Signal[String], tabs: List[Tab]): TkNode
```

New `TkNode` cases: `TagNode(content, variant)`, `PillNode(content, variant)`,
`KpiCardNode(label, value)`, `TabBarNode(active, tabs)`. `BadgeNode` already exists —
only its `lower` variant table and constructor default change.

---

## 4. Reactive variants (agreed with busi)

Status comes from runtime data (`status` from a fetch result), not a literal. The
variant argument is therefore an ordinary **runtime value**, resolved on every signal
re-render — there is no compile-time-only enum. This must map directly:

```scalascript
badge(label, if statusSig() == "overdue" then "danger" else "success")
```

Reactivity is provided by the surrounding `computedSignal` / signal re-render, not by
the primitive. The same applies to `tag`/`pill` variants and `tabBar(active)`. The
`String` variant API satisfies this by construction (a runtime expression yields the
String); the `lower` variant table maps unknown variants to `neutral` rather than
failing.

---

## 5. Out of scope (layout stays in combinators)

`Style` deliberately omits layout: `display`, `flex`/`flex:1`/grow, `gap`,
`align-items`, `justify-content`, `flex-direction`. These are the job of the layout
combinators (`hstack`/`vstack` already carry `gap`; add `grow` where needed), not the
style descriptor. Mixing layout into `Style` would re-introduce the CSS-soup coupling
this spec removes. (Scope confirmed with busi from their inline-CSS audit: only
`bg, fg, border, radius, padding, margin, font` belong in `Style`.)

Also out of scope: arbitrary CSS pass-through, pseudo-selectors (`:hover`),
animations, media queries.

---

## 6. Backend policy

| Backend | Style resolution | Primitives |
|---|---|---|
| JVM interp / emit-spa (web) | `Style` → CSS string in `lower.ssc` (token→theme value) | lower to `element("div"/"span", themed CSS)` |
| JS / Node | same `lower.ssc` output (shared lowering) | same |
| Native (swiftui/swing/javafx) | `Style` tokens → native style attrs (future lowering) | semantic nodes map to native widgets |

The win: because screens author `StyledNode` + primitives (not CSS), a native lowering
gets themed output without touching any screen.

---

## 7. Non-goals (v1)

- A full native lowering of `Style` — this spec makes screens *portable*; the actual
  swiftui/swing `Style` resolver is separate, sequenced after the web lowering lands.
- Per-component theme overrides beyond the token set.
- Replacing the existing layout combinators.

---

## 8. busi adoption

Replace `web/ui.ssc` local helpers and inline-CSS cards/badges with `std/ui`
primitives; move `tabBar` upstream; use `styled(...)` for the few genuinely custom
nodes. Removes the color-literal sprawl (`#0f172a`×5, `#e2e8f0`, `#dc2626`, …) and makes
dark/mobile actually work on busi screens.

---

## 9. Verify

- `badge("Paid", "success")` lowers to a span whose background resolves to
  `defaultTheme.colors.success`, and to `darkTheme.colors.success` under `darkTheme`.
- `styled(Style(bg = Some(ColorToken.Surface), radius = Some(RadiusToken.Md)), …)`
  emits no hex literal — only theme-resolved values; re-themes under `darkTheme`.
- Runtime variant: `badge(l, if s() == "x" then "danger" else "neutral")` renders the
  correct color per `s()` value across re-renders.
- `tabBar(activeSig, tabs)` highlights the tab whose `key == activeSig()`.
- Grep gate: target busi screens contain zero `"style" ->` hex literals after migration
  (tracked busi-side).
- Example `examples/ui-styled-primitives.ssc` runs under `ssc run`.
