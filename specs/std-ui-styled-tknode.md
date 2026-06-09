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

> **As built (deviates from the enum/case-class sketch below).** Two interpreter
> limitations forced a simpler shape, landed 2026-06-09:
> 1. The interpreter matches **case classes** across module boundaries but **not enum
>    cases** — so tokens are **strings** (`"surface"`, `"md"`, `"smd"`), not enums.
>    `lower.ssc` resolves them against the Theme exactly the same way.
> 2. Partial named-arg construction of a case class with defaults **mis-binds
>    positionally**, so `Style` is a **`Map[String, String]`** prop bag, not a defaulted
>    case class. `styled(["bg" -> "surface", "radius" -> "md", "paddingX" -> "16",
>    "paddingY" -> "smd"])(child)`.
>
> Keys: `bg fg border` (colour token), `radius` (sm/md/lg/full), `paddingX paddingY
> marginTop marginRight marginBottom marginLeft` (space token OR a bare px number),
> `font` (body/heading/caption). Colours stay token-only (so `Theme.dark` works);
> spacing is token-first with a bare-number px escape. The enum sketch below records the
> original intent; the string keys are the 1:1 runtime equivalent.

```scalascript
// Token references — symbolic, resolved against the active Theme in lower.ssc.
enum ColorToken  { case Primary, OnPrimary, Secondary, Surface, OnSurface,
                   case Background, Muted, Danger, Success, Warning }
// SpacingScale gains an intermediate step Smd = 12 (busi's most frequent gap/pad,
// missing in the default scale between Sm=8 and Md=16). Additive — Md stays 16 so no
// existing user shifts. Default theme: Xs=4, Sm=8, Smd=12, Md=16, Lg=24, Xl=32, Xxl=48.
enum SpaceToken  { case Xs, Sm, Smd, Md, Lg, Xl, Xxl }
enum RadiusToken { case Sm, Md, Lg, Full }
enum FontToken   { case Body, Heading, Caption }   // Caption = small text (badges/tags)

// Length value — a token OR a raw px. COLORS are token-only; LENGTHS may be raw px.
// Policy: SPACING is **token-first** — most padding/margin must be sp(token) so it
// rescales under mobileTheme's denser SpacingScale (mobile Sm=18 vs default Sm=8); raw
// px does NOT rescale. px(n) is the escape only for genuine one-offs (the 2px badge
// inset) and for sizing breakpoints (see §5 box) that cannot be tokenized. See §2.1.
enum Space { case Tok(t: SpaceToken); case Px(n: Int) }
def sp(t: SpaceToken): Space = Space.Tok(t)   // preferred: sp(SpaceToken.Smd)  // = 12
def px(n: Int): Space        = Space.Px(n)     // escape: px(2) one-offs only

case class BorderStyle(width: Int, color: ColorToken)   // color token-only

// Per-axis padding, per-side margin — real CSS is asymmetric (padding:12px 16px,
// margin-bottom:8px). A single uniform field cannot express it. All optional.
case class Style(
  bg:       Option[ColorToken]  = None,
  fg:       Option[ColorToken]  = None,
  border:   Option[BorderStyle] = None,
  radius:   Option[RadiusToken] = None,
  paddingX: Option[Space]       = None,   // left+right
  paddingY: Option[Space]       = None,   // top+bottom
  marginTop:    Option[Space]   = None,
  marginRight:  Option[Space]   = None,
  marginBottom: Option[Space]   = None,
  marginLeft:   Option[Space]   = None,
  font:     Option[FontToken]   = None
)

// Styled container node — wraps children with a token-resolved Style.
case class StyledNode(style: Style, children: List[TkNode]) extends TkNode

// Constructor (display.ssc):
def styled(style: Style, children: TkNode*): TkNode = StyledNode(style, children.toList)
```

`lower.ssc` resolves a `Style` into the web representation: `bg = Some(Surface)` →
`background:${theme.colors.surface}`, `radius = Some(Md)` → `border-radius:${theme.radii.md}px`,
`paddingY = Some(sp(Sm))` → `padding-top/bottom:${theme.spacing.sm}px`, `paddingX = Some(px(16))`
→ `padding-left/right:16px`, `marginBottom = Some(sp(Sm))` → `margin-bottom:${theme.spacing.sm}px`.
A native lowering resolves the same `Style` to native attributes.

This is the **only** sanctioned escape hatch for custom-styled chrome; screens should
not author raw `element(... style:"...")`.

### 2.1 Spacing policy: token-first, `px` for one-offs (audit finding)

busi's inline-CSS audit shows asymmetric, off-scale spacing everywhere:
`padding:12px 16px`, `padding:8px 12px`, `padding:2px 8px` (badge), and directional
`margin-bottom:8px` / `margin-right:8px`. Three consequences, all folded into the model
above:

1. **Per-axis / per-side**, not one uniform token (`padding:12px 16px` ≠ symmetric).
2. **Spacing is token-first, not px-first.** Tokens rescale under `mobileTheme`'s denser
   `SpacingScale` (mobile `Sm`=18 vs default `Sm`=8); a raw `px` does **not** rescale, so
   px-spacing breaks compact/mobile density. The bulk of padding/margin must stay
   `sp(token)`. `px(n)` is reserved for genuine one-offs (the `2px` badge inset) and for
   sizing breakpoints (§5 `box`), which cannot be tokenized at all (`768`/`1280` caps).
3. **The scale gains `Smd`=12** — busi's single most frequent value (gap ×17, padding ×6)
   was missing between `Sm`=8 and `Md`=16, which would have forced `px(12)` on every
   screen and defeated rescaling. Additive (`Md` stays 16, no user shifts). Now busi's
   real gap set 4/8/12/16/24 = `Xs/Sm/Smd/Md/Lg` is fully tokenized.

Colors keep no `px`/hex escape — they must stay tokens so `Theme.dark` works.

### 2.2 Typography is token, never `px`

Font sizing in `Style` is `font: Option[FontToken]` only — never a px length. Screen text
uses `Body`/`Heading`; small primitive text (badge/tag captions at 11–12px) resolves
through the new `FontToken.Caption` / `TypographyScale.caption` item, **baked into the
badge/tag/pill lowering** (not author-set). So `11/12/14px` font literals disappear into
themed typography, and `mobileTheme` can scale them like any other token.

### 2.3 Theme additions required

This milestone extends `theme.ssc` (additive, named-arg construction keeps the 3 built-in
themes safe):
- `SpacingScale` gains field `smd` (default 12; mobile ~24; dark 12). `SpaceToken.Smd`.
- `TypographyScale` gains `caption: TypographyItem` (default ~12px; mobile larger).
  `FontToken.Caption`.

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

## 5. Sizing — a layout combinator, not `Style`

busi's audit found `max-width` (×2, content-width cap) and `height` (×3) with no home:
not in `Style`, not in the layout combinators. Sizing is a box concern, so it lives in
layout (`layout.ssc`), keeping `Style` to chrome only:

```scalascript
// box — a sizing wrapper around children. All optional; px lengths (portable).
case class BoxNode(maxWidth: Option[Int], width: Option[Int],
                   height: Option[Int], children: List[TkNode]) extends TkNode
def box(maxWidth: Int = 0, width: Int = 0, height: Int = 0)(children: TkNode*): TkNode
```

`maxWidth` caps content width (the common page-centering case); `height` fixes a panel.
Lowers to `max-width`/`width`/`height` px on web, native size constraints elsewhere.
This is what lets screens stop dropping to raw `element()` for width caps.

## 6. Out of scope (layout stays in combinators)

`Style` deliberately omits flow-layout: `display`, `flex`/`flex:1`/grow, `gap`,
`align-items`, `justify-content`, `flex-direction`. These are the job of the layout
combinators (`hstack`/`vstack` already carry `gap`; add `grow` where needed), not the
style descriptor. Mixing layout into `Style` would re-introduce the CSS-soup coupling
this spec removes.

**Baked into primitives, not `Style` (audit minor findings):** `text-decoration:none`
is baked into `tabBar`/link/button lowerings (links should not show default underlines);
`cursor:pointer` is baked into interactive primitives. These are not author-facing knobs.
`box-shadow` (×1 occurrence) is deferred — if elevation becomes common, add an
`elevation: Option[ElevationToken]` to `Style` later rather than a raw shadow string.

Also out of scope: arbitrary CSS pass-through, pseudo-selectors (`:hover`),
animations, media queries.

---

## 7. Backend policy

| Backend | Style resolution | Primitives |
|---|---|---|
| JVM interp / emit-spa (web) | `Style` → CSS string in `lower.ssc` (token→theme value) | lower to `element("div"/"span", themed CSS)` |
| JS / Node | same `lower.ssc` output (shared lowering) | same |
| Native (swiftui/swing/javafx) | `Style` tokens → native style attrs (future lowering) | semantic nodes map to native widgets |

The win: because screens author `StyledNode` + primitives (not CSS), a native lowering
gets themed output without touching any screen.

---

## 8. Non-goals (v1)

- A full native lowering of `Style` — this spec makes screens *portable*; the actual
  swiftui/swing `Style` resolver is separate, sequenced after the web lowering lands.
- Per-component theme overrides beyond the token set.
- Replacing the existing layout combinators.

---

## 9. busi adoption

Replace `web/ui.ssc` local helpers and inline-CSS cards/badges with `std/ui`
primitives; move `tabBar` upstream; use `styled(...)` for the few genuinely custom
nodes. Removes the color-literal sprawl (`#0f172a`×5, `#e2e8f0`, `#dc2626`, …) and makes
dark/mobile actually work on busi screens.

---

## 10. Verify

- `badge("Paid", "success")` lowers to a span whose background resolves to
  `defaultTheme.colors.success`, and to `darkTheme.colors.success` under `darkTheme`.
- `styled(Style(bg = Some(ColorToken.Surface), radius = Some(RadiusToken.Md)), …)`
  emits no hex literal — only theme-resolved values; re-themes under `darkTheme`.
- Asymmetric spacing: `styled(Style(paddingY = Some(px(2)), paddingX = Some(sp(SpaceToken.Sm))), …)`
  lowers to `padding:2px 8px` (badge case); `marginBottom = Some(sp(Sm))` → `margin-bottom:8px`.
- `box(maxWidth = 960)(…)` caps content width; emits `max-width:960px`, no raw `element()`.
- Runtime variant: `badge(l, if s() == "x" then "danger" else "neutral")` renders the
  correct color per `s()` value across re-renders.
- `tabBar(activeSig, tabs)` highlights the tab whose `key == activeSig()`.
- Grep gate: target busi screens contain zero `"style" ->` hex literals after migration
  (tracked busi-side).
- Example `examples/ui-styled-primitives.ssc` runs under `ssc run`.
