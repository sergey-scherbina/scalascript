package scalascript.frontend.toolkit

/** Design-system tokens.  Frozen at app mount time + threaded
 *  through the component tree via implicit `using Theme`.  Widgets
 *  pull colors / spacing / typography from here instead of taking
 *  inline CSS.  Apps swap themes by passing a different `Theme`
 *  to `App(theme = ...)` at the root.
 *
 *  See `docs/specs/frontend-toolkit-spec.md` for the design rationale. */
case class Theme(
  colors:     ColorPalette,
  spacing:    SpacingScale,
  typography: TypographyScale,
  radii:      RadiusScale,
  shadows:    ShadowScale
)

object Theme:
  /** Reference theme.  Neutral, accessible (WCAG AA contrast), light
   *  mode.  Apps that don't supply a theme pick this up. */
  val default: Theme = Theme(
    colors     = ColorPalette.lightDefault,
    spacing    = SpacingScale.default,
    typography = TypographyScale.default,
    radii      = RadiusScale.default,
    shadows    = ShadowScale.default
  )

  /** Reference dark variant.  Same scales as `default`; only colours
   *  flip.  Apps drive light/dark switching by toggling between the
   *  two via a Signal[Theme] passed to App. */
  val dark: Theme = default.copy(colors = ColorPalette.darkDefault)

/** Color tokens.  Six semantic colors + a neutral ramp.  Widgets
 *  reach for `theme.colors.primary` etc. instead of literal hex. */
case class ColorPalette(
  primary:    String,
  secondary:  String,
  success:    String,
  warning:    String,
  danger:     String,
  background: String,
  surface:    String,
  border:     String,
  text:       String,
  textMuted:  String,
  onPrimary:  String,    // text colour on primary background
  neutral:    Map[Int, String]   // 50, 100, 200, ..., 900
)

object ColorPalette:
  val lightDefault: ColorPalette = ColorPalette(
    primary    = "#2563eb",   // blue-600
    secondary  = "#7c3aed",   // violet-600
    success    = "#16a34a",   // green-600
    warning    = "#d97706",   // amber-600
    danger     = "#dc2626",   // red-600
    background = "#ffffff",
    surface    = "#f9fafb",   // slate-50
    border     = "#e5e7eb",   // slate-200
    text       = "#111827",   // slate-900
    textMuted  = "#6b7280",   // slate-500
    onPrimary  = "#ffffff",
    neutral    = Map(
      50  -> "#f9fafb", 100 -> "#f3f4f6", 200 -> "#e5e7eb",
      300 -> "#d1d5db", 400 -> "#9ca3af", 500 -> "#6b7280",
      600 -> "#4b5563", 700 -> "#374151", 800 -> "#1f2937",
      900 -> "#111827"
    )
  )

  val darkDefault: ColorPalette = ColorPalette(
    primary    = "#3b82f6",   // blue-500 (brighter for dark bg)
    secondary  = "#8b5cf6",
    success    = "#22c55e",
    warning    = "#f59e0b",
    danger     = "#ef4444",
    background = "#0f172a",   // slate-900
    surface    = "#1e293b",   // slate-800
    border     = "#334155",   // slate-700
    text       = "#f1f5f9",   // slate-100
    textMuted  = "#94a3b8",   // slate-400
    onPrimary  = "#ffffff",
    neutral    = lightDefault.neutral  // same ramp, used identically
  )

/** Spacing tokens — every margin/padding/gap a widget produces goes
 *  through one of these.  Drives consistency: when a designer says
 *  "more breathing room", you change `md` once and the app obeys. */
case class SpacingScale(
  xs:  Int,  // 4px
  sm:  Int,  // 8px
  md:  Int,  // 16px
  lg:  Int,  // 24px
  xl:  Int,  // 32px
  xxl: Int   // 48px
):
  /** Look up a scale entry by name; used by `Stack(gap = "md")` etc. */
  def named(name: String): Int = name match
    case "xs"  => xs;  case "sm"  => sm;  case "md"  => md
    case "lg"  => lg;  case "xl"  => xl;  case "xxl" => xxl
    case _      => md

object SpacingScale:
  val default: SpacingScale = SpacingScale(4, 8, 16, 24, 32, 48)

/** Typography tokens.  Each entry is a (font-size px, line-height,
 *  font-weight) triple. */
case class TypographyScale(
  heading1: TextStyle,
  heading2: TextStyle,
  heading3: TextStyle,
  heading4: TextStyle,
  body:     TextStyle,
  bodySmall: TextStyle,
  caption:  TextStyle,
  code:     TextStyle
):
  def heading(level: Int): TextStyle = level match
    case 1 => heading1
    case 2 => heading2
    case 3 => heading3
    case _ => heading4

case class TextStyle(
  fontSize:   Int,
  lineHeight: Double,
  fontWeight: Int,
  fontFamily: String = "system-ui, -apple-system, sans-serif"
)

object TypographyScale:
  val default: TypographyScale = TypographyScale(
    heading1 = TextStyle(36, 1.2, 700),
    heading2 = TextStyle(28, 1.25, 700),
    heading3 = TextStyle(22, 1.3, 600),
    heading4 = TextStyle(18, 1.35, 600),
    body     = TextStyle(16, 1.5, 400),
    bodySmall = TextStyle(14, 1.5, 400),
    caption  = TextStyle(12, 1.4, 400),
    code     = TextStyle(14, 1.5, 400,
      fontFamily = "ui-monospace, SFMono-Regular, Menlo, monospace")
  )

/** Border radius scale. */
case class RadiusScale(
  none: Int,  // 0
  sm:   Int,  // 4
  md:   Int,  // 8
  lg:   Int,  // 12
  xl:   Int,  // 16
  full: Int   // 9999 — perfect circle for square inputs
):
  def named(name: String): Int = name match
    case "none" => none; case "sm" => sm; case "md" => md
    case "lg"   => lg;   case "xl" => xl; case "full" => full
    case _       => md

object RadiusScale:
  val default: RadiusScale = RadiusScale(0, 4, 8, 12, 16, 9999)

/** Shadow scale.  Three depths covering most UI needs. */
case class ShadowScale(
  none: String,
  sm:   String,
  md:   String,
  lg:   String
):
  def named(name: String): String = name match
    case "none" => none; case "sm" => sm; case "md" => md; case "lg" => lg
    case _       => none

object ShadowScale:
  val default: ShadowScale = ShadowScale(
    none = "none",
    sm   = "0 1px 2px rgba(0,0,0,0.05)",
    md   = "0 4px 6px rgba(0,0,0,0.1)",
    lg   = "0 10px 15px rgba(0,0,0,0.15)"
  )
