# Native Platform Support

> **Status: DRAFT v0.2 — Candidate** — Architecture decisions are settled; implementation details subject to revision.
> Version: 0.2 — May 2026

---

## 1. Goals

- Allow a single `.ssc` frontend component to target **Web, Desktop, and Mobile** without source changes.
- Support true native binaries (not just web-wrapped apps) for macOS, Linux, Windows, iOS, and Android.
- Add **Kotlin** and **Swift** as first-class codegen backends alongside the existing JS and JVM backends.
- Provide a **unified View IR** (`enum View[+A]`) whose semantics map cleanly to HTML/DOM, Compose, SwiftUI, GTK, and React Native.
- Provide a **full style system** (colors, typography, layout, borders, shadows, animations, gestures, a11y) that translates to CSS, Compose `Modifier`, SwiftUI modifiers, GTK CSS, etc.
- Accessible-first: accessibility metadata is a first-class field on every View node.
- Make the toolchain experience friendly: detect missing tools, show a clear list, offer auto-install.
- Keep the import/dependency model declarative; let the target compiler validate types.
- **Reuse, don't duplicate**: native is a mapping layer for `httpGet`/`Db.query`/`loadConfig` and the existing toolkit — not a parallel API.

## 2. Non-Goals

- A universal layout engine (we map View IR nodes to each platform's own engine).
- Replacing platform SDKs — native platform capabilities are exposed as declared dependencies.
- AI at compile time or runtime.
- Runtime cross-platform compatibility (compiled artifacts target one platform per build).

---

## 3. Architecture

```
.ssc source
    │
    ▼
Parser → AST (Module)
    │
    ├─ Compilation Backend  ─────────────────────────────────────────────────────┐
    │  (js | jvm | kotlin | swift | scala-native | graalvm)                     │
    │  Translates business logic to target language source.                      │
    │  Standard intrinsics (httpGet, Db.query, loadConfig) are emitted           │
    │  as platform-idiomatic calls — fetch / Ktor / URLSession / libcurl.        │
    │  Does NOT validate imported library types — that is the target             │
    │  compiler's job. ScalaScript formats target compiler errors.               │
    │                                                                            │
    └─ Frontend Renderer  ───────────────────────────────────────────────────────┤
       (custom | vue | react | solid | electron |                               │
        react-native | compose | swiftui | gtk | javafx)                        │
       Translates View IR to UI code in the target language.                     │
                                                                                 ▼
                               target compiler / bundler / linker
                               (kotlinc / swiftc / scalac / node /
                                native-image / clang+llvm)
                                               │
                               ┌───────────────┴────────────────┐
                               │                                │
                          JS bundle              native binary / APK / .app
                          + HTML shell           + Compose / SwiftUI / GTK UI
```

> **Note**: `--target` is already parsed at `GlobalFlags` in `cli/Main.scala` and stored in `ActiveFlags`. This spec wires up dispatch to the build pipeline — it does not add a new flag.

---

## 4. Platform ADT

```scala
enum Platform:
  case Web
  case Desktop(os: DesktopOs = DesktopOs.All)
  case Mobile(os: MobileOs = MobileOs.All)
  case All                                       // matches every platform

enum DesktopOs:
  case MacOS, Windows, Linux, All

enum MobileOs:
  case iOS, Android, All

enum AppFormat:
  case WebSpa                  // HTML + JS bundle (current default)
  case ElectronApp             // WebSpa packaged in Electron shell
  case ReactNativeBundle       // Metro / Expo bundle
  case ComposeMultiplatform    // Kotlin + Compose → Desktop + Mobile
  case SwiftUIApp              // Swift Package + SwiftUI
  case ScalaNativeBinary       // Scala Native → LLVM → native binary
  case GraalVMNativeImage      // GraalVM AOT → native binary (with JavaFX)
  case KotlinAndroidApk        // Kotlin → Android APK / AAB

enum CompilationBackend:
  case Js
  case Jvm
  case Kotlin
  case Swift
  case ScalaNative
  case GraalVM
```

---

## 5. View IR

### 5.1 Type parameter rationale

`View` is a **covariant generic enum** so that type-bearing cases (`For[T]`, `ForSignal[T]`, `Picker[T]`, `ComponentInstance[P]`, `FormField[T]`, `LazyList[T]`, `LazyGrid[T]`) can carry typed witnesses, while monomorphic cases extend `View[Nothing]`.

Downstream code uses `View[?]` (wildcard) wherever the type parameter is irrelevant — e.g. `children: Seq[View[?]]`, `child: View[?]`.

**Migration note**: the current codebase uses `sealed trait View` to allow per-case type parameters. `enum View[+A]` replaces it. Affected call sites: code generator lowering, all four web frontend backends (`custom`/`react`/`solid`/`vue`), and `FrontendModule.components: List[ComponentDef]` (the `body: Any => View` field becomes `body: Any => View[?]`).

### 5.2 Main ADT

```scala
enum View[+A]:

  // ── Layout ───────────────────────────────────────────────────────────────────
  /** Vertical stack.
   *  Web: flex-column div | Compose: Column | SwiftUI: VStack | GTK: GtkBox(VERTICAL) */
  case Column(
    children: Seq[View[?]],
    spacing:  Double         = 0,
    align:    HAlign         = HAlign.Start,
    style:    Style          = Style()
  ) extends View[Nothing]

  /** Horizontal stack.
   *  Web: flex-row div | Compose: Row | SwiftUI: HStack | GTK: GtkBox(HORIZONTAL) */
  case Row(
    children: Seq[View[?]],
    spacing:  Double         = 0,
    align:    VAlign         = VAlign.Center,
    style:    Style          = Style()
  ) extends View[Nothing]

  /** Z-axis overlay stack.
   *  Web: position:relative + absolute children | Compose: Box | SwiftUI: ZStack | GTK: GtkOverlay */
  case Stack(children: Seq[View[?]], style: Style = Style()) extends View[Nothing]

  /** Scrollable container.
   *  Web: overflow:auto div | Compose: LazyColumn/ScrollState | SwiftUI: ScrollView | GTK: GtkScrolledWindow */
  case ScrollView(child: View[?], axis: Axis = Axis.Vertical, style: Style = Style()) extends View[Nothing]

  case Spacer(size: Option[Double] = None) extends View[Nothing]
  case Divider(axis: Axis = Axis.Horizontal, style: Style = Style()) extends View[Nothing]

  // ── Content ──────────────────────────────────────────────────────────────────
  /** Web: <span> | Compose: Text | SwiftUI: Text | GTK: GtkLabel */
  case Text(content: () => String, style: Style = Style()) extends View[Nothing]
  case SignalText(signal: ReactiveSignal[?], style: Style = Style()) extends View[Nothing]

  /** Web: <img> | Compose: Image | SwiftUI: Image | GTK: GtkImage */
  case Image(source: ImageSource, style: Style = Style()) extends View[Nothing]

  /** Platform icon — SF Symbol (Apple), Material (Android/Compose), Lucide (web).
   *  Falls back to a placeholder when the name is unknown on the target. */
  case Icon(name: String, style: Style = Style()) extends View[Nothing]

  // ── Controls ─────────────────────────────────────────────────────────────────
  /** Web: <button> | Compose: Button | SwiftUI: Button | GTK: GtkButton */
  case Button(
    label:   View[?],
    action:  EventHandler,
    enabled: () => Boolean = () => true,
    style:   Style         = Style()
  ) extends View[Nothing]

  /** Web: <input>/<textarea> | Compose: TextField | SwiftUI: TextField/SecureField | GTK: GtkEntry */
  case TextInput(
    value:       ReactiveSignal[String],
    placeholder: String  = "",
    multiline:   Boolean = false,
    secure:      Boolean = false,
    style:       Style   = Style()
  ) extends View[Nothing]

  /** Web: <input type="checkbox"> | Compose: Switch | SwiftUI: Toggle | GTK: GtkCheckButton */
  case Toggle(checked: ReactiveSignal[Boolean], label: String = "", style: Style = Style()) extends View[Nothing]

  /** Web: <input type="range"> | Compose: Slider | SwiftUI: Slider | GTK: GtkScale */
  case Slider(
    value: ReactiveSignal[Double],
    min:   Double,
    max:   Double,
    step:  Double = 1.0,
    style: Style  = Style()
  ) extends View[Nothing]

  /** Option selector — NET-NEW (replaces toolkit SelectNode[T]).
   *  Web: <select> | Compose: ExposedDropdownMenuBox | SwiftUI: Picker | GTK: GtkComboBoxText */
  case Picker[T](
    options:     Seq[(String, T)],
    selected:    ReactiveSignal[T],
    placeholder: String = "",
    style:       Style  = Style()
  ) extends View[T]

  // ── Virtualized lists ─────────────────────────────────────────────────────────
  /** Efficiently renders large datasets. Use instead of For+Column for long lists.
   *  Web: virtual-scroll div | Compose: LazyColumn | SwiftUI: List | RN: FlatList | GTK: GtkListBox */
  case LazyList[T](
    items:      () => Seq[T],
    render:     T => View[?],
    itemHeight: Option[Double] = None,   // hint; None = variable height
    style:      Style          = Style()
  ) extends View[T]

  /** Web: CSS grid + virtual scroll | Compose: LazyVerticalGrid | SwiftUI: LazyVGrid | RN: FlatList numColumns */
  case LazyGrid[T](
    items:   () => Seq[T],
    render:  T => View[?],
    columns: GridColumns = GridColumns.Fixed(2),
    spacing: Double      = 8,
    style:   Style       = Style()
  ) extends View[T]

  // ── Navigation ───────────────────────────────────────────────────────────────
  case TabBar(tabs: Seq[Tab], current: ReactiveSignal[Int], style: Style = Style()) extends View[Nothing]

  case NavigationStack(
    routes:  Map[String, () => View[?]],
    current: ReactiveSignal[String],
    style:   Style = Style()
  ) extends View[Nothing]

  // ── Overlays ─────────────────────────────────────────────────────────────────
  case Sheet(content: View[?], isPresented: ReactiveSignal[Boolean]) extends View[Nothing]

  case AlertDialog(
    title:       String,
    message:     String,
    buttons:     Seq[AlertButton],
    isPresented: ReactiveSignal[Boolean]
  ) extends View[Nothing]

  // ── Forms ─────────────────────────────────────────────────────────────────────
  /** Provides submit context for nested FormField nodes.
   *  Web: <form> | Compose: custom dispatch | SwiftUI: Form {} | RN: custom state */
  case Form(
    child:    View[?],
    onSubmit: EventHandler,
    style:    Style = Style()
  ) extends View[Nothing]

  /** Type-safe field bound to a signal; self-registers validation with the parent Form.
   *  Web: <label>+<input> with error span | Compose: OutlinedTextField | SwiftUI: TextField in Section */
  case FormField[T](
    label:    String,
    value:    ReactiveSignal[T],
    validate: T => Option[String] = (_: T) => None,
    style:    Style               = Style()
  ) extends View[T]

  // ── Mobile / platform UX ─────────────────────────────────────────────────────
  /** Padding that respects device safe-area insets (notch, home indicator, status bar).
   *  Web: env(safe-area-inset-*) | Compose: safeContentPadding() | SwiftUI: .safeAreaInset() | RN: SafeAreaView */
  case SafeArea(child: View[?], edges: Set[Edge] = Edge.all) extends View[Nothing]

  /** Shifts layout up when the software keyboard is shown.
   *  Web: no-op | Compose: imePadding() | SwiftUI: automatic | RN: KeyboardAvoidingView | GTK: no-op */
  case KeyboardAvoiding(child: View[?]) extends View[Nothing]

  // ── Animations ───────────────────────────────────────────────────────────────
  /** Wrap any View to apply entrance/exit/in-place transition animation. */
  case Animated(child: View[?], transition: Transition, style: Style = Style()) extends View[Nothing]

  // ── Reactivity ───────────────────────────────────────────────────────────────
  case Fragment(children: Seq[View[?]]) extends View[Nothing]
  case ComponentInstance[P](component: Component[P], props: P) extends View[P]
  case ShowSignal(cond: ReactiveSignal[Boolean], whenTrue: View[?], whenFalse: View[?] = Fragment(Nil)) extends View[Nothing]
  case Show(cond: () => Boolean, whenTrue: () => View[?], whenFalse: () => View[?] = () => Fragment(Nil)) extends View[Nothing]
  case ForSignal[T](items: ReactiveSignalList[T], itemTemplate: Option[View[?]] = None) extends View[T]
  case For[T](items: () => Seq[T], render: T => View[?]) extends View[T]
  case ItemText extends View[Nothing]   // iteration-value placeholder inside ForSignal.itemTemplate

  // ── Style wrapper ─────────────────────────────────────────────────────────────
  /** Produced by modifier-chain extension methods (see §6.3). */
  case Styled(child: View[?], style: Style) extends View[Nothing]

  // ── Platform-adaptive escape hatch ────────────────────────────────────────────
  case Adaptive(
    web:      Option[View[?]] = None,
    desktop:  Option[View[?]] = None,
    mobile:   Option[View[?]] = None,
    fallback: View[?]         = Fragment(Nil)
  ) extends View[Nothing]

  // ── Web-only cases ────────────────────────────────────────────────────────────
  // Using any of these on a non-web target emits a compile error.
  // FetchTable and FetchUrlSignal live in frontend/core (not in the toolkit).

  /** Raw HTML element. Compile error on non-web targets. */
  case Element(
    tag:      String,
    attrs:    Map[String, AttrValue],
    events:   Map[String, EventHandler],
    children: Seq[View[?]]
  ) extends View[Nothing]

  /** DOM portal. No-op on non-web targets (compile warning). */
  case Portal(target: String, children: Seq[View[?]]) extends View[Nothing]

  /** Reactive data table backed by a REST endpoint.
   *  Lives in frontend/core. Deprecated for non-web targets. */
  case FetchTable(
    tableJsName: String,
    fetchUrl:    String,
    deleteUrl:   String,
    tick:        ReactiveSignal[Int]
  ) extends View[Nothing]
```

### 5.3 Supporting types

```scala
final case class Tab(label: String, icon: Option[String], content: View[?])
final case class AlertButton(label: String, action: () => Unit, role: ButtonRole = ButtonRole.Default)

enum ButtonRole:   case Default, Cancel, Destructive
enum Axis:         case Horizontal, Vertical, Both
enum HAlign:       case Start, Center, End, Stretch
enum VAlign:       case Top, Center, Bottom, Stretch
enum ContentFit:   case Contain, Cover, Fill, None
enum Edge:         case Top, Bottom, Leading, Trailing
object Edge:
  val all: Set[Edge] = Set(Top, Bottom, Leading, Trailing)

enum GridColumns:
  case Fixed(count: Int)
  case Adaptive(minWidth: Double)
  case Fill

enum ImageSource:
  case Url(href: String)
  case Asset(name: String)                        // file under assets/
  case Base64(data: String, mime: String)
```

---

## 6. Style System

### 6.1 Style record

```scala
final case class Style(
  // ── Layout ───────────────────────────────────────────────────────────────────
  padding:        EdgeInsets           = EdgeInsets.zero,
  margin:         EdgeInsets           = EdgeInsets.zero,
  width:          Dimension            = Dimension.Auto,
  height:         Dimension            = Dimension.Auto,
  minWidth:       Option[Double]       = None,
  maxWidth:       Option[Double]       = None,
  minHeight:      Option[Double]       = None,
  maxHeight:      Option[Double]       = None,
  flex:           Option[Double]       = None,
  flexShrink:     Option[Double]       = None,
  flexBasis:      Option[Dimension]    = None,
  alignSelf:      Option[Align]        = None,
  gap:            Option[Double]       = None,
  zIndex:         Option[Int]          = None,

  // ── Color ─────────────────────────────────────────────────────────────────────
  background:     Option[Color]        = None,
  foreground:     Option[Color]        = None,

  // ── Typography ────────────────────────────────────────────────────────────────
  fontSize:       Option[Double]       = None,
  fontWeight:     Option[FontWeight]   = None,
  fontFamily:     Option[String]       = None,
  fontStyle:      FontStyle            = FontStyle.Normal,
  lineHeight:     Option[Double]       = None,
  letterSpacing:  Option[Double]       = None,
  textDecoration: Set[TextDecoration]  = Set.empty,
  textAlign:      TextAlign            = TextAlign.Start,
  textOverflow:   TextOverflow         = TextOverflow.Clip,
  maxLines:       Option[Int]          = None,

  // ── Border ────────────────────────────────────────────────────────────────────
  borderColor:    Option[Color]        = None,
  borderWidth:    Option[Double]       = None,
  borderRadius:   BorderRadius         = BorderRadius.zero,
  borderStyle:    BorderLineStyle      = BorderLineStyle.Solid,

  // ── Shadow ────────────────────────────────────────────────────────────────────
  shadow:         Option[Shadow]       = None,

  // ── Effects ───────────────────────────────────────────────────────────────────
  opacity:        Double               = 1.0,
  overflow:       Overflow             = Overflow.Visible,
  cursor:         Option[Cursor]       = None,   // web-only; ignored on native

  // ── Transform ─────────────────────────────────────────────────────────────────
  transform:      List[Transform]      = Nil,

  // ── Animations ────────────────────────────────────────────────────────────────
  animation:      Option[Transition]   = None,

  // ── Gestures ──────────────────────────────────────────────────────────────────
  gestures:       List[Gesture]        = Nil,

  // ── Accessibility ─────────────────────────────────────────────────────────────
  a11y:           A11y                 = A11y(),

  // ── Platform overrides ────────────────────────────────────────────────────────
  web:            Map[String, String]  = Map.empty,   // raw CSS properties
  native:         Map[String, String]  = Map.empty    // platform-specific hints
)

object Style:
  val empty: Style = Style()
```

### 6.2 Supporting types

```scala
final case class EdgeInsets(top: Double, right: Double, bottom: Double, left: Double)
object EdgeInsets:
  val zero = EdgeInsets(0, 0, 0, 0)
  def all(v: Double)                  = EdgeInsets(v, v, v, v)
  def symmetric(h: Double, v: Double) = EdgeInsets(v, h, v, h)
  def only(top: Double = 0, right: Double = 0, bottom: Double = 0, left: Double = 0) =
    EdgeInsets(top, right, bottom, left)

enum Dimension:
  case Auto
  case Fixed(value: Double)
  case Fraction(value: Double)   // fraction of parent, 0..1
  case Fill                      // take all available space

enum Align:           case Start, Center, End, Stretch, Baseline
enum FontStyle:       case Normal, Italic
enum TextAlign:       case Start, Center, End, Justify
enum TextOverflow:    case Clip, Ellipsis
enum Overflow:        case Visible, Hidden, Scroll, Auto
enum BorderLineStyle: case Solid, Dashed, Dotted, None
enum TextDecoration:  case Underline, Strikethrough, Overline

enum FontWeight:
  case Thin, ExtraLight, Light, Regular, Medium, SemiBold, Bold, ExtraBold, Black
  case Custom(value: Int)   // 100–900

enum Cursor:   // web-only
  case Default, Pointer, Text, Grab, Grabbing, NotAllowed, Crosshair

enum Color:
  case Hex(value: String)
  case Rgb(r: Int, g: Int, b: Int)
  case Rgba(r: Int, g: Int, b: Int, a: Double)
  case Named(name: String)
  /** Cross-platform semantic token — "primary", "onSurface", "label", "systemBackground", etc.
   *  Resolved by each renderer against the active theme / platform color system. */
  case System(token: String)
  case Transparent

final case class BorderRadius(topLeft: Double, topRight: Double, bottomRight: Double, bottomLeft: Double)
object BorderRadius:
  val zero = BorderRadius(0, 0, 0, 0)
  def all(r: Double) = BorderRadius(r, r, r, r)

final case class Shadow(
  color:   Color,
  offsetX: Double = 0,
  offsetY: Double = 4,
  blur:    Double = 8,
  spread:  Double = 0
)

enum Transform:
  case Rotate(degrees: Double)
  case Scale(x: Double, y: Double = x)
  case Translate(x: Double, y: Double)
  case SkewX(degrees: Double)
  case SkewY(degrees: Double)
```

### 6.3 Modifier-chain extension methods

```scala
extension (v: View[?])
  // Layout
  def padding(all: Double): View[?]                    = styled(_.copy(padding = EdgeInsets.all(all)))
  def padding(h: Double, vert: Double): View[?]        = styled(_.copy(padding = EdgeInsets.symmetric(h, vert)))
  def padding(e: EdgeInsets): View[?]                  = styled(_.copy(padding = e))
  def margin(all: Double): View[?]                     = styled(_.copy(margin = EdgeInsets.all(all)))
  def margin(e: EdgeInsets): View[?]                   = styled(_.copy(margin = e))
  def frame(w: Double, h: Double): View[?]             = styled(_.copy(width = Dimension.Fixed(w), height = Dimension.Fixed(h)))
  def width(d: Dimension): View[?]                     = styled(_.copy(width = d))
  def height(d: Dimension): View[?]                    = styled(_.copy(height = d))
  def fill: View[?]                                    = styled(_.copy(width = Dimension.Fill, height = Dimension.Fill))
  def fillWidth: View[?]                               = styled(_.copy(width = Dimension.Fill))
  def fillHeight: View[?]                              = styled(_.copy(height = Dimension.Fill))
  def flex(factor: Double = 1): View[?]                = styled(_.copy(flex = Some(factor)))
  def minWidth(v: Double): View[?]                     = styled(_.copy(minWidth = Some(v)))
  def maxWidth(v: Double): View[?]                     = styled(_.copy(maxWidth = Some(v)))
  def zIndex(z: Int): View[?]                          = styled(_.copy(zIndex = Some(z)))

  // Color
  def background(c: Color): View[?]                   = styled(_.copy(background = Some(c)))
  def foreground(c: Color): View[?]                   = styled(_.copy(foreground = Some(c)))

  // Typography
  def fontSize(s: Double): View[?]                    = styled(_.copy(fontSize = Some(s)))
  def fontWeight(w: FontWeight): View[?]              = styled(_.copy(fontWeight = Some(w)))
  def bold: View[?]                                   = fontWeight(FontWeight.Bold)
  def light: View[?]                                  = fontWeight(FontWeight.Light)
  def italic: View[?]                                 = styled(_.copy(fontStyle = FontStyle.Italic))
  def underline: View[?]                              = styled(s => s.copy(textDecoration = s.textDecoration + TextDecoration.Underline))
  def strikethrough: View[?]                          = styled(s => s.copy(textDecoration = s.textDecoration + TextDecoration.Strikethrough))
  def textAlign(a: TextAlign): View[?]                = styled(_.copy(textAlign = a))
  def lineLimit(n: Int): View[?]                      = styled(_.copy(maxLines = Some(n)))

  // Border / shape
  def border(color: Color, width: Double = 1): View[?] = styled(_.copy(borderColor = Some(color), borderWidth = Some(width)))
  def cornerRadius(r: Double): View[?]                  = styled(_.copy(borderRadius = BorderRadius.all(r)))

  // Shadow / effects
  def shadow(s: Shadow): View[?]                      = styled(_.copy(shadow = Some(s)))
  def shadow(blur: Double = 8, color: Color = Color.Rgba(0, 0, 0, 0.15)): View[?] =
    styled(_.copy(shadow = Some(Shadow(color, blur = blur))))
  def opacity(v: Double): View[?]                     = styled(_.copy(opacity = v))
  def clip: View[?]                                   = styled(_.copy(overflow = Overflow.Hidden))

  // Transform
  def rotate(deg: Double): View[?]                    = appendTransform(Transform.Rotate(deg))
  def scale(f: Double): View[?]                       = appendTransform(Transform.Scale(f))
  def translate(x: Double, y: Double): View[?]        = appendTransform(Transform.Translate(x, y))

  // Animation
  def animated(t: Transition = Transition()): View[?] = styled(_.copy(animation = Some(t)))
  def transition(enter: Transition, exit: Transition = enter): View[?] =
    View.Animated(v, enter)

  // Gestures
  def onTap(h: EventHandler): View[?]                         = gesture(Gesture.Tap(h))
  def onLongPress(h: EventHandler, ms: Double = 500): View[?] = gesture(Gesture.LongPress(h, ms))
  def onSwipe(dir: SwipeDirection, h: EventHandler): View[?]  = gesture(Gesture.Swipe(dir, h))

  // Accessibility
  def accessibilityLabel(l: String): View[?]          = styled(s => s.copy(a11y = s.a11y.copy(label = Some(l))))
  def accessibilityHint(h: String): View[?]           = styled(s => s.copy(a11y = s.a11y.copy(hint = Some(h))))
  def accessibilityRole(r: A11yRole): View[?]         = styled(s => s.copy(a11y = s.a11y.copy(role = Some(r))))
  def focusOrder(n: Int): View[?]                     = styled(s => s.copy(a11y = s.a11y.copy(focusOrder = Some(n))))

  // Platform overrides
  def css(prop: String, value: String): View[?]       = styled(s => s.copy(web = s.web + (prop -> value)))
  def nativeHint(key: String, value: String): View[?] = styled(s => s.copy(native = s.native + (key -> value)))

  private def styled(f: Style => Style): View[?] = v match
    case View.Styled(child, s) => View.Styled(child, f(s))
    case other                 => View.Styled(other, f(Style()))

  private def appendTransform(t: Transform): View[?] = v match
    case View.Styled(child, s) => View.Styled(child, s.copy(transform = s.transform :+ t))
    case other                 => View.Styled(other, Style(transform = List(t)))

  private def gesture(g: Gesture): View[?] = v match
    case View.Styled(child, s) => View.Styled(child, s.copy(gestures = s.gestures :+ g))
    case other                 => View.Styled(other, Style(gestures = List(g)))
```

### 6.4 Style → platform mapping

| Style property | CSS | Compose Modifier | SwiftUI modifier | GTK |
|----------------|-----|-----------------|-----------------|-----|
| `padding` | `padding: 16px` | `.padding(16.dp)` | `.padding(16)` | `gtk_widget_set_margin_*` |
| `background` | `background: #fff` | `.background(Color(...))` | `.background(.white)` | CSS provider |
| `cornerRadius` | `border-radius: 8px` | `.clip(RoundedCornerShape(8.dp))` | `.cornerRadius(8)` | CSS provider |
| `fontSize` | `font-size: 18px` | `.fontSize(18.sp)` | `.font(.system(size: 18))` | `PangoFontDescription` |
| `bold` | `font-weight: bold` | `.fontWeight(FontWeight.Bold)` | `.bold()` | `PANGO_WEIGHT_BOLD` |
| `shadow` | `box-shadow: ...` | `.shadow(...)` | `.shadow(...)` | CSS provider |
| `opacity` | `opacity: 0.5` | `.alpha(0.5f)` | `.opacity(0.5)` | `gtk_widget_set_opacity` |
| `foreground` | `color: #333` | `.color(Color(...))` | `.foregroundColor(.gray)` | CSS override |
| `width(Fill)` | `width: 100%` | `.fillMaxWidth()` | `.frame(maxWidth: .infinity)` | `gtk_widget_set_hexpand(TRUE)` |
| `animation` | CSS `transition: all Xms` | `animate*(state, animationSpec)` | `.animation(.easeOut, value: state)` | Cairo transition |

### 6.4a Theme integration

`Style` resolves color and typography tokens against the ambient `Theme` (the `using Theme` pattern already in `frontend/toolkit/Theme.scala`). Precedence: **literal value > theme token > platform default**.

```scala
// Color.System tokens resolve against Theme:
Style(background = Some(Color.System("surface")))
// → CSS: var(--color-surface, #fff) | Compose: MaterialTheme.colorScheme.surface
// → SwiftUI: Color(.systemBackground) | GTK: CSS --theme-surface

// None fields fall back to theme:
Style(fontSize = None)  // → Theme.typography.body.size (if set), else platform default
```

Naming conflict: the existing `Theme.TextStyle` (in `frontend/toolkit/Theme.scala`) is the sub-record for typography; `Style` supersedes it as the top-level styling API. `TextStyle` is retained inside `Theme` as an internal type but is no longer part of the public View IR surface.

### 6.5 Accessibility

A11y metadata lives in `Style.a11y: A11y` — not in separate View cases — so it can be set via the modifier chain.

```scala
final case class A11y(
  label:      Option[String]      = None,    // screen-reader description
  hint:       Option[String]      = None,    // longer explanation
  role:       Option[A11yRole]    = None,
  traits:     Set[A11yTrait]      = Set.empty,
  focusable:  Option[Boolean]     = None,
  focusOrder: Option[Int]         = None,
  liveRegion: Option[LiveRegion]  = None     // announce dynamic updates
)

enum A11yRole:
  case Button, Link, Image, Heading, TextField, List, ListItem
  case Switch, Slider, Tab, TabList, Alert, Dialog, None

enum A11yTrait:
  case Selected, Disabled, Bold, Italic, Placeholder, SearchField, StartsMediaSession

enum LiveRegion:
  case Polite, Assertive
```

| A11y field | Web | Compose | SwiftUI | GTK | RN |
|-----------|-----|---------|---------|-----|----|
| `label` | `aria-label` | `Modifier.semantics { contentDescription }` | `.accessibilityLabel(...)` | `gtk_accessible_update_property` | `accessibilityLabel` |
| `role` | `role="button"` | `Modifier.semantics { role = Role.Button }` | `.accessibilityAddTraits(.isButton)` | `GTK_ACCESSIBLE_ROLE_BUTTON` | `accessibilityRole` |
| `hint` | `aria-describedby` | `Modifier.semantics { stateDescription }` | `.accessibilityHint(...)` | `GTK_ACCESSIBLE_PROPERTY_DESCRIPTION` | `accessibilityHint` |
| `liveRegion(Polite)` | `aria-live="polite"` | `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` | `.accessibilityAddTraits(.updatesFrequently)` | `GTK_ACCESSIBLE_STATE_LIVE` | `accessibilityLiveRegion` |

### 6.6 Animations

```scala
final case class Transition(
  curve:    Curve  = Curve.EaseInOut,
  duration: Double = 300,   // milliseconds
  delay:    Double = 0
)

enum Curve:
  case Linear
  case EaseIn
  case EaseOut
  case EaseInOut
  case Spring(stiffness: Double = 300, damping: Double = 30)

/** Animated value that can be driven to a target with a Transition. */
final class Animatable[T](val id: String, val initial: T):
  def animateTo(target: T, transition: Transition = Transition()): Unit
```

`View.Animated(child, transition)` applies the transition on every state change that triggers a re-render of `child`. Platform mapping:

| Renderer | Mechanism |
|----------|-----------|
| Web (any) | CSS `transition` property |
| Compose | `animateXxxAsState` / `animateContentSize` |
| SwiftUI | `.animation(.easeOut(duration: t.duration/1000), value: state)` |
| GTK | GLib `GtkPropertyAnimation` (4.10+) / Cairo manual |
| RN | `Animated.timing` / `react-native-reanimated` |

### 6.7 Gestures

Gestures live in `Style.gestures: List[Gesture]` and are added via modifier methods (see §6.3).

```scala
enum Gesture:
  case Tap(handler: EventHandler)
  case LongPress(handler: EventHandler, minDuration: Double = 500)
  case Swipe(direction: SwipeDirection, handler: EventHandler)
  case Drag(handler: EventHandler)   // DragHandler with dx/dy provided by EventHandler.WithEvent

enum SwipeDirection:
  case Left, Right, Up, Down, Horizontal, Vertical, Any
```

| Gesture | Web | Compose | SwiftUI | GTK | RN |
|---------|-----|---------|---------|-----|----|
| `Tap` | `onclick` | `Modifier.clickable` | `.onTapGesture` | `GtkGestureClick` | `TouchableOpacity` |
| `LongPress` | `oncontextmenu` (heuristic) | `Modifier.combinedClickable(onLongClick)` | `.onLongPressGesture` | `GtkGestureLongPress` | `LongPressGestureHandler` |
| `Swipe` | `touchstart` + `touchend` delta | `detectHorizontalDragGestures` | `DragGesture` with translation | `GtkGestureSwipe` | `PanResponder` |
| `Drag` | `ondrag` / pointer events | `detectDragGestures` | `.gesture(DragGesture())` | `GtkGestureDrag` | `PanResponder` |

---

## 7. Reactive Primitives — Breaking Changes

### 7.1 `jsName` → `id` (four sites)

The `jsName` field is renamed to `id` on all four types in `frontend/core/src/main/scala/scalascript/frontend/Primitives.scala`. The identifier is now the canonical cross-backend name.

```scala
// Before
class ReactiveSignal[T](val jsName: String, initial: T) extends Signal[T]
class ReactiveSignalList[T](val jsName: String, val initial: Seq[T])
class FetchUrlSignal(jsName: String, val fetchUrl: String, val tickJsName: String)
  extends ReactiveSignal[String](jsName, "")
final class DomRef(val jsName: String)

// After
class ReactiveSignal[T](val id: String, initial: T) extends Signal[T]
class ReactiveSignalList[T](val id: String, val initial: Seq[T])
class FetchUrlSignal(id: String, val fetchUrl: String, val tickId: String)
  extends ReactiveSignal[String](id, "")
final class WidgetRef(val id: String)

@deprecated("use WidgetRef", "v1.31")
type DomRef = WidgetRef
```

Deprecated `jsName` val accessors are retained for one minor version:

```scala
class ReactiveSignal[T](val id: String, ...):
  @deprecated("use id", "v1.31") def jsName: String = id
```

### 7.2 Web-only cases — convention

The `@webOnly` annotation is **not** introduced as a Scala annotation type. Instead, `View.Element`, `View.Portal`, and `View.FetchTable` are documented in a "Web-only cases" subsection (§5.2). The codegen raises a compile error when it encounters these cases during a non-web target build.

### 7.3 Signal → platform mapping

| ScalaScript | JS / custom | Compose | SwiftUI | GTK |
|------------|-------------|---------|---------|-----|
| `ReactiveSignal[T](id, v)` | `let <id> = <v>` + subscriber set | `var <id> by mutableStateOf(<v>)` | `@State var <id> = <v>` | mutable var + update callback |
| `signal.set(v)` | `__setSignal("<id>", v)` | `<id> = v` | `<id> = v` | `<id> = v; gtk_widget_queue_draw(...)` |

### 7.4 Capability enum additions

New capabilities in `enum Capability` (no conflicts with existing values):

```scala
// Existing web capabilities (unchanged):
// ComponentTree, SignalState, ComputedDerived, EffectLifecycle,
// DomRefs, Context, Portals, Suspense, Untrack, TwoWayBinding

// New universal view capabilities:
case NativeLayout       // Column / Row / Stack / ScrollView / Spacer / Divider
case NativeControls     // Button / TextInput / Toggle / Slider / Picker / Image / Icon
case NativeNavigation   // TabBar / NavigationStack
case NativeOverlays     // Sheet / AlertDialog
case NativeForms        // Form / FormField
case LazyCollections    // LazyList / LazyGrid

// Platform feature capabilities (via declared dependencies):
case Camera
case Biometrics
case PushNotifications
case LocalStorage
case Geolocation
case Haptics
case DeepLinks
case BackgroundTasks
case FileSystem
```

---

## 8. Import / Dependency Model

### 8.1 Breaking change: `dependencies` becomes a list

`Manifest.dependencies` changes from `Map[String, String]` (name → version-constraint) to `List[String]` (scheme-prefixed gav-or-spec strings). Valid schemes: `jvm`, `kotlin`, `npm`, `swift`, `native`.

```yaml
dependencies:
  - jvm:    "org.typelevel::cats-core:2.10.0"
  - jvm:    "io.circe::circe-core:0.14.0"
  - kotlin: "androidx.compose.material3:material3:1.2.0"
  - kotlin: "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.0"
  - npm:    "axios@1.6.0"
  - npm:    "@react-navigation/native@6.0.0"
  - swift:  "github.com/Alamofire/Alamofire 5.9.0"
  - native: "gtk-4.0"
```

When the parser encounters the old Map shape, it emits:

```
error: 'dependencies' must be a list of scheme-prefixed strings.
  Example: "- jvm: org.typelevel::cats-core:2.10.0"
  Hint:    run 'ssc migrate-deps <file>' or see MIGRATION.md §1.
```

### 8.2 Inline declarations

```
dep "jvm:org.typelevel::cats-core:2.10.0"
dep "npm:axios@1.6.0"
dep "kotlin:androidx.compose.material3:material3:1.2.0"
dep "swift:github.com/Alamofire/Alamofire 5.9.0"
dep "native:gtk-4.0"
```

### 8.3 Backend resolution

| Dep prefix | Injected into |
|-----------|--------------|
| `jvm:` | `//> using dep` (scala-cli) or `build.gradle.kts` |
| `kotlin:` | `build.gradle.kts` `implementation(...)` |
| `npm:` | `package.json` `dependencies` |
| `swift:` | `Package.swift` `.package(url:, from:)` |
| `native:` | linker flags via `pkg-config` / `nativeConfig` |

### 8.4 Schema updates required

`schemas/frontmatter.yaml` currently enforces `additionalProperties: false`. To land the new keys:

- Change `dependencies` type from `object` to `array of string` with pattern `^(jvm|kotlin|npm|swift|native):.+$`.
- Add `app`, `native`, `assets` as optional object keys.
- `validate-frontmatter.scala`: add list validation for `dependencies` matching the same pattern.

Schema update is a prerequisite for P0 (see §23).

---

## 9. Frontend SPI

### 9.1 Backward-compatible wrapping

`EmittedSpa` stays unchanged — all four existing web backends (`custom`/`react`/`solid`/`vue`) continue to override `def emit(...): EmittedSpa` without modification.

`EmittedArtifact` is a new **sum-type wrapper** introduced for native output. Web backends produce `EmittedArtifact.Spa(emit(module))` via the helper in `UniversalFrontendSpi`.

```scala
// Unchanged — existing backends implement this:
trait FrontendFrameworkSpi:
  def name: String
  def capabilities: Set[Capability]
  def jsDeps: List[JsDep]
  def emit(module: FrontendModule): EmittedSpa
  def supportedPlatforms: Set[Platform] = Set(Platform.Web)

// New enum wrapping EmittedSpa:
enum EmittedArtifact:
  case Spa(spa: EmittedSpa)
  case NativeApp(
    sources:     Map[String, String],
    resources:   Map[String, Array[Byte]],
    buildScript: String,
    manifest:    AppManifest,
    format:      AppFormat,
    target:      Platform
  )

// Deprecated alias for one version:
@deprecated("use EmittedArtifact.Spa", "v1.31")
type EmittedSpaArtifact = EmittedArtifact.Spa
```

### 9.2 Native SPI extensions

```scala
// Extension for native-capable backends:
trait NativeFrontendSpi extends FrontendFrameworkSpi:
  override def supportedPlatforms: Set[Platform]
  def emitNative(module: FrontendModule, platform: Platform): EmittedArtifact.NativeApp

// Convenience — handles both web and native:
trait UniversalFrontendSpi extends NativeFrontendSpi:
  final def emitForPlatform(module: FrontendModule, platform: Platform): EmittedArtifact =
    platform match
      case Platform.Web => EmittedArtifact.Spa(emit(module))
      case p            => emitNative(module, p)
```

### 9.3 FrontendModule

```scala
final case class FrontendModule(
  components:     List[ComponentDef],
  entryPoint:     String,
  initialRoute:   String,
  targetPlatform: Platform            = Platform.Web,
  appManifest:    Option[AppManifest] = None
)

final case class AppManifest(
  bundleId:    String,
  displayName: String,
  version:     String,
  minOs:       Map[Platform, String] = Map.empty
)
```

---

## 10. Compilation Backends

| Backend | Generates | Primary use |
|---------|-----------|-------------|
| `js` | `.js` (CJS / ESM) | Web, React Native, Electron |
| `jvm` | `.scala` (Scala 3) | Server, GraalVM native-image |
| `kotlin` | `.kt` | Compose Multiplatform, Android, Kotlin/Native |
| `swift` | `.swift` | SwiftUI (macOS + iOS) |
| `scala-native` | `.scala` + `@extern` C bindings | GTK (Linux), Cocoa (macOS), Win32 |
| `graalvm` | `.scala` + native-image config | Standalone desktop binary with JavaFX |

### 10.1 Kotlin backend

```
// ScalaScript                         // Generated Kotlin
val count = Signal(0)           →      var count by mutableStateOf(0)
fun greet(name: String) = ...   →      fun greet(name: String): String = ...
route("GET", "/items") { ... }  →      // Ktor: get("/items") { ... }
```

### 10.2 Swift backend

```
// ScalaScript                         // Generated Swift
val count = Signal(0)           →      @State var count: Int = 0
fun greet(name: String) = ...   →      func greet(name: String) -> String { ... }
route("GET", "/items") { ... }  →      // Vapor: app.get("items") { req in ... }
```

### 10.3 Scala Native backend

Extends the JVM backend. Adds `@extern` C-binding objects for the target UI toolkit, `nativeConfig` (linker flags, GC), and platform entry points.

```scala
// Generated for GTK target
import scala.scalanative.unsafe.*
@extern object Gtk:
  def gtk_init(argc: Ptr[Int], argv: Ptr[Ptr[CString]]): Unit = extern
  def gtk_box_new(orientation: Int, spacing: Int): Ptr[Byte]  = extern
  def gtk_label_new(text: CString): Ptr[Byte]                 = extern
  def gtk_button_new_with_label(label: CString): Ptr[Byte]    = extern
```

### 10.4 GraalVM backend

Extends the JVM backend. Generates `reflect-config.json`, `resource-config.json`, and a build script that calls `native-image`. Primary renderer target: JavaFX.

---

## 11. Frontend Renderers

### 11.1 Renderer matrix

| Renderer | Backend | Platform(s) | Format |
|----------|---------|-------------|--------|
| `custom` | js | Web | WebSpa |
| `vue` | js | Web | WebSpa |
| `react` | js | Web | WebSpa |
| `solid` | js | Web | WebSpa |
| `electron` | js | Desktop (all) | ElectronApp |
| `react-native` | js | Mobile (iOS + Android) | ReactNativeBundle |
| `compose` | kotlin | Desktop (all) + Mobile (all) | ComposeMultiplatform |
| `swiftui` | swift | Desktop (macOS) + Mobile (iOS) | SwiftUIApp |
| `gtk` | scala-native | Desktop (Linux, macOS) | ScalaNativeBinary |
| `javafx` | graalvm | Desktop (all) | GraalVMNativeImage |

### 11.2 View IR → renderer mapping

| View | Web (DOM) | Compose | SwiftUI | GTK |
|------|-----------|---------|---------|-----|
| `Column` | `<div style="flex-direction:column">` | `Column { }` | `VStack { }` | `gtk_box_new(VERTICAL, 0)` |
| `Row` | `<div style="flex-direction:row">` | `Row { }` | `HStack { }` | `gtk_box_new(HORIZONTAL, 0)` |
| `Stack` | `position:relative` + children | `Box { }` | `ZStack { }` | `GtkOverlay` |
| `Text` | `<span>` | `Text(...)` | `Text(...)` | `gtk_label_new(...)` |
| `Button` | `<button>` | `Button(onClick) { }` | `Button { }` | `gtk_button_new_with_label(...)` |
| `TextInput` | `<input type="text">` | `TextField(...)` | `TextField(...)` | `gtk_entry_new()` |
| `Toggle` | `<input type="checkbox">` | `Switch(...)` | `Toggle(...)` | `gtk_check_button_new()` |
| `Slider` | `<input type="range">` | `Slider(...)` | `Slider(...)` | `gtk_scale_new(...)` |
| `Picker[T]` | `<select>` | `ExposedDropdownMenuBox` | `Picker { }` | `gtk_combo_box_text_new()` |
| `LazyList[T]` | virtual-scroll div | `LazyColumn { items(list) }` | `List(list) { }` | `GtkListBox` |
| `LazyGrid[T]` | CSS grid + virtual scroll | `LazyVerticalGrid` | `LazyVGrid` | `GtkGridView` |
| `TabBar` | router tabs | `NavigationBar` + `HorizontalPager` | `TabView` | `gtk_notebook_new()` |
| `Image` | `<img>` | `Image(...)` | `Image(...)` | `gtk_image_new_from_file(...)` |
| `ScrollView` | `overflow:auto div` | `LazyColumn` / `ScrollState` | `ScrollView` | `gtk_scrolled_window_new()` |
| `Sheet` | Portal + fixed overlay | `ModalBottomSheet { }` | `.sheet(isPresented:) { }` | `GtkDialog` |
| `AlertDialog` | `<dialog>` | `AlertDialog(...)` | `.alert(...)` | `GtkMessageDialog` |
| `Form` | `<form onsubmit>` | custom state + submit | `Form { }` | `GtkGrid` form layout |
| `FormField[T]` | `<label>` + `<input>` + error span | `OutlinedTextField` | `TextField` in `Section` | `GtkEntry` + `GtkLabel` |
| `SafeArea` | `env(safe-area-inset-*)` | `safeContentPadding()` | `.safeAreaInset(...)` | no-op |
| `KeyboardAvoiding` | no-op | `imePadding()` | automatic | no-op |

### 11.3 Compose Multiplatform

JetBrains' extension of Jetpack Compose to Desktop (macOS, Windows, Linux), iOS, and Web. One Kotlin codebase; each platform uses the Compose compiler plugin on the appropriate Kotlin target (JVM for Desktop, Kotlin/Native for iOS, Kotlin/Wasm for Web). The `compose` renderer generates `@Composable` functions from the View IR. `ReactiveSignal[T]` maps to `mutableStateOf`.

### 11.4 EventHandler native lowering

All 9 `EventHandler` cases from `frontend/core/src/main/scala/scalascript/frontend/Primitives.scala`:

| `EventHandler` case | Web | Compose | SwiftUI | GTK | RN |
|---|---|---|---|---|---|
| `Simple(fn)` | `onclick = () => fn()` | `onClick = { fn() }` | `Button { fn() }` | `g_signal_connect("clicked", fn)` | `onPress={fn}` |
| `WithEvent(fn)` | `onclick = e => fn(e)` | `onClick = { e -> fn(e) }` | `Button { fn($0) }` | callback with event | `onPress={e => fn(e)}` |
| `SetSignalLiteral(id, v)` | `__setSignal(id, v)` | `<id>.value = v` | `<id> = v` | `<id> = v; queue_draw` | `setId(v)` |
| `IncrementSignal(id)` | `__setSignal(id, <id>+1)` | `<id>++` | `<id> += 1` | `<id>++` | `setId(n => n+1)` |
| `ToggleSignal(id)` | `__setSignal(id, !<id>)` | `<id> = !<id>` | `<id>.toggle()` | `<id> = !<id>` | `setId(n => !n)` |
| `PushSignalLiteral(list, v)` | `<list>.push(v)` | `<list> += v` | `<list>.append(v)` | `g_list_append` | `setList(xs => [...xs,v])` |
| `ClearSignalList(list)` | `<list>.length = 0` | `<list>.clear()` | `<list>.removeAll()` | `g_list_free` | `setList([])` |
| `RemoveSelfFromList(list, i)` | `<list>.splice(i,1)` | `<list>.removeAt(i)` | `<list>.remove(at:)` | `g_list_remove` | filter by index |
| `InputChange(id)` | `oninput = e => setId(e.target.value)` | `onValueChange = { v -> id = v }` | `TextField($id)` | `g_signal_connect("changed")` | `onChangeText={setId}` |
| `FetchAction(method,url,...)` | `await fetch(url, {method})` | `httpClient.<method>(url)` | `URLSession.shared.data(...)` | `curl_easy_perform` | `await fetch(url)` |

---

## 12. Toolchain Management

When a required tool is not found, `ssc` presents a structured interactive prompt.

```
$ ssc build --target mobile-ios my-app.ssc

⚠  Missing toolchain for target: mobile-ios

   Required tools:
   ┌──────────────────────────────────────────────────────────────────┐
   │  [✓] JDK 21             /usr/lib/jvm/temurin-21                 │
   │  [✓] Node.js 20         /usr/local/bin/node                     │
   │  [✗] Kotlin 2.0         not found                               │
   │  [✗] Kotlin/Native      not found                               │
   │  [✗] Xcode 15+          not found (required for iOS signing)    │
   └──────────────────────────────────────────────────────────────────┘

Options:
  [1]  Show manual installation instructions for each missing tool
  [2]  Auto-install everything possible (Coursier, Homebrew, mise, apt, scoop…)
  [3]  Auto-install what can be automated; show manual steps for the rest
  [4]  Cancel

> 3

  ✓  Kotlin 2.0.0       installed via Coursier    (12 MB)
  ✓  Kotlin/Native       installed via Coursier    (185 MB)

  Requires manual installation:
  ┌──────────────────────────────────────────────────────────────────┐
  │  Xcode 15+                                                       │
  │  → App Store: apps.apple.com/app/xcode/id497799835              │
  │  → or: xcode-select --install  (command-line tools only)        │
  └──────────────────────────────────────────────────────────────────┘

  After installing Xcode, re-run:
    ssc build --target mobile-ios my-app.ssc
```

### Auto-install strategy by platform

| Tool | macOS | Linux | Windows |
|------|-------|-------|---------|
| Kotlin / Scala Native / GraalVM | Coursier | Coursier | Coursier |
| Node.js | mise / Homebrew | mise / apt / dnf | mise / scoop |
| Android SDK | `sdkmanager` (auto) | `sdkmanager` (auto) | `sdkmanager` (auto) |
| GTK | Homebrew | apt / dnf | vcpkg / scoop |
| LLVM / Clang | Xcode CLT | apt / dnf | Visual Studio Build Tools |
| Xcode | App Store (manual) | — | — |

```bash
ssc toolchain check                        # check all installed tools
ssc toolchain check --target mobile-ios    # check only what this target needs
ssc toolchain install --target mobile-ios  # auto-install for target
ssc toolchain list                         # show installed versions
```

---

## 13. CLI Extensions

### 13.1 New commands and flags

```bash
# compile — codegen only; does not invoke the target build system
ssc compile --backend kotlin   --target mobile-android  my-app.ssc
ssc compile --backend swift    --target desktop-macos   my-app.ssc
ssc compile --backend js       --frontend react-native  my-app.ssc

# build — compile + invoke target build system
ssc build --target web                 my-app.ssc
ssc build --target desktop             my-app.ssc   # → Electron (default)
ssc build --target desktop-macos       my-app.ssc   # → SwiftUI .app
ssc build --target desktop-linux       my-app.ssc   # → GTK binary
ssc build --target desktop-windows     my-app.ssc   # → JavaFX / GraalVM binary
ssc build --target mobile              my-app.ssc   # → Compose (iOS + Android)
ssc build --target mobile-ios          my-app.ssc   # → SwiftUI .app
ssc build --target mobile-android      my-app.ssc   # → Compose APK

# override renderer
ssc build --target mobile-android --frontend react-native  my-app.ssc

# dev run with hot-reload
ssc run --target desktop-electron      my-app.ssc
ssc run --target mobile-ios            my-app.ssc   # → xcrun simctl
ssc run --target mobile-android        my-app.ssc   # → adb + emulator

# existing commands — unchanged
ssc run-js    my-app.ssc
ssc serve     my-app.ssc
ssc check     my-app.ssc
ssc compile   my-app.ssc   # defaults to --backend js
```

> `--target` is already parsed in `GlobalFlags` and stored in `ActiveFlags.current.target` (cli/Main.scala). Existing `--frontend` flag and `validFrontendNames` / `applyFrontendBackend` helpers are reused.

### 13.2 Default backend / renderer selection

| `--target` | Default backend | Default renderer |
|-----------|----------------|-----------------|
| `web` | `js` | `custom` |
| `desktop` | `js` | `electron` |
| `desktop-macos` | `swift` | `swiftui` |
| `desktop-linux` | `scala-native` | `gtk` |
| `desktop-windows` | `graalvm` | `javafx` |
| `mobile` | `kotlin` | `compose` |
| `mobile-ios` | `swift` | `swiftui` |
| `mobile-android` | `kotlin` | `compose` |

### 13.3 Simulator / emulator integration

```bash
# iOS Simulator — requires Xcode / xcrun
ssc run --target mobile-ios my-app.ssc
# 1. xcrun simctl list devices → pick first booted or boot "iPhone 15 Pro"
# 2. build → xcrun simctl install booted <app.app>
# 3. xcrun simctl launch booted <bundle-id>
# 4. hot-reload via active renderer (Metro fast refresh / SwiftUI #Preview)

# Android Emulator — requires Android SDK / adb
ssc run --target mobile-android my-app.ssc
# 1. adb devices → use running emulator or prompt
# 2. gradle assembleDebug
# 3. adb install -r app-debug.apk
# 4. adb shell am start -n <package>/<activity>
# 5. hot-reload via Compose hot-reload-runtime or Metro fast refresh
```

### 13.4 Hot reload per renderer

| Renderer | Hot reload mechanism |
|----------|---------------------|
| `custom / react / solid / vue` | Vite HMR (existing) |
| `electron` | Electron + Vite HMR |
| `react-native` | Metro fast refresh |
| `compose` | `compose-hot-reload-runtime` (JetBrains, experimental) |
| `swiftui` | SwiftUI `#Preview` pipeline in dev mode |
| `gtk` | Incremental recompile + re-exec (~3–8 s) |
| `javafx` | JVM classloader reload (limited) |

---

## 14. Frontmatter Extensions

```yaml
---
name: my-app
version: 1.0.0
frontend: custom           # web renderer — existing; maps to --frontend flag
targets: [jvm, js, native, kotlin, swift]

# Application bundle metadata (new — requires schema update)
app:
  bundle-id:    com.example.myapp
  display-name: My App
  version:      1.0.0
  icon:         assets/icon.png     # source ≥1024×1024; per-platform sizes generated
  splash:       assets/splash.png   # mobile splash screen

# Native build config (new — requires schema update)
native:
  desktop:
    format:       electron
    min-macos:    "12.0"
    min-windows:  "10"
    min-linux:    "ubuntu-22.04"
  mobile:
    format:       compose
    min-ios:      "16.0"
    min-android:  26    # API level

# Cross-target dependencies — BREAKING: list, not map (see §8)
dependencies:
  - jvm:    "org.typelevel::cats-core:2.10.0"
  - kotlin: "androidx.compose.material3:material3:1.2.0"
  - npm:    "axios@1.6.0"
  - swift:  "github.com/Alamofire/Alamofire 5.9.0"
  - native: "gtk-4.0"

# SQL databases — existing field (DatabaseDecl); unchanged shape
databases:
  - name:   app
    url:    "sqlite:app.db"
    driver: sqlite

# Asset declarations (new — requires schema update)
assets:
  fonts: [assets/fonts/Inter.ttf]
  icons: assets/icons/
---
```

---

## 15. Standard Intrinsics on Native

The following `std/` intrinsics are already cross-platform APIs. Native platform support means each compilation backend emits idiomatic code for the same source expression — **no new `Http`, `Sql`, or `Config` objects are introduced**.

### 15.1 HTTP — `std/http.ssc`

```
httpGet(url: String, headers: Map[String, String] = Map.empty): Response
httpPost(url: String, body: String, headers: Map[String, String] = Map.empty): Response
httpPut(url: String, body: String, headers: Map[String, String] = Map.empty): Response
httpPatch(url: String, body: String, headers: Map[String, String] = Map.empty): Response
httpDelete(url: String, headers: Map[String, String] = Map.empty): Response
```

API is synchronous at the source level; each backend rewrites to its platform-appropriate async form.

| Backend | Emits |
|---------|-------|
| `js` (web) | `await fetch(url, {method, headers})` |
| `js` (Electron / Node) | `await fetch(url)` (Node 18+ builtin) |
| `js` (React Native) | `await fetch(url)` (RN polyfill) |
| `kotlin` | `runBlocking { httpClient.get(url) { headers {...} } }` (Ktor) |
| `swift` | `try await URLSession.shared.data(from: URL(string: url)!)` |
| `scala-native` | `curl_easy_perform` via `libcurl @extern` bindings |
| `graalvm` / `jvm` | `java.net.http.HttpClient.newHttpClient().send(...)` |

The existing `FetchUrlSignal` remains in `frontend/core` as the reactive signal wrapper around `httpGet`.

### 15.2 SQL — `std/sql-plugin`

```
Db.query(dbName: String, sql: String, params: Any*): List[Map[String, Any]]
Db.execute(dbName: String, sql: String, params: Any*): Int   // rows affected
DriverManager.getConnection(url: String, ...): Connection
```

Fenced blocks:

```
val todos = sql"""SELECT id, text FROM todos WHERE done = ${false}"""
```

Database connections are declared in the existing `databases:` frontmatter (`Manifest.databases: List[DatabaseDecl]`). The `dbName` parameter matches the `name:` field.

| Backend | Driver |
|---------|--------|
| `js` (web) | `@sqlite.org/sqlite-wasm` (SQLite WASM) |
| `js` (Electron / Node) | `better-sqlite3` |
| `js` (React Native) | `react-native-sqlite-storage` |
| `kotlin` (Android) | Room persistence library |
| `kotlin` (Desktop) | `org.xerial:sqlite-jdbc`; `org.duckdb:duckdb_jdbc` for DuckDB |
| `swift` | `SQLite3` system framework; `GRDB.swift` |
| `scala-native` | `libsqlite3` via `@extern` |
| `graalvm` / `jvm` | `org.xerial:sqlite-jdbc`; `org.duckdb:duckdb_jdbc` |

### 15.3 Config — `backend/config-runtime`

```scala
loadConfig[T](path: String = "config.yaml")(using Config[T]): T
// type Config[A] = ConfigDecoder[A]  — derives via Scala 3 derivation
```

Sidecar `.conf`/`.yaml`/`.json`/`.hocon` beside the `.ssc` file is also picked up automatically by `loadSidecarConfig` in the CLI. `JsConfigEmitter` serialises the config into `window.__ssc_config` for browser targets.

| Backend | Config source |
|---------|---------------|
| `js` (web) | `window.__ssc_config` injected at build time |
| `js` (Electron) | App-local file in `app.getPath('userData')` |
| `kotlin` (Android) | `assets/config.yaml` + `SharedPreferences` for user overrides |
| `swift` (iOS / macOS) | App bundle resource + `UserDefaults` |
| `scala-native` | Filesystem read from exe-adjacent directory |
| `graalvm` / `jvm` | Classpath resource + `-Dssc.config.path` system property |

---

## 16. Asset Pipeline

### 16.1 Directory layout

```
assets/
├── icons/                 # source icons — ssc generates required sizes per platform
│   └── icon.png           # must be ≥ 1024×1024 px
├── fonts/
│   └── Inter.ttf
├── images/
│   └── logo.png
└── splash.png             # mobile splash screen
```

`ImageSource.Asset("logo.png")` resolves via the platform asset loader:

| Platform | Resolution |
|----------|------------|
| Web | URL-import from `assets/images/logo.png` |
| React Native | `require('./assets/images/logo.png')` |
| Compose | `painterResource(R.drawable.logo)` |
| SwiftUI | `Image("logo")` from bundle |
| GTK | `gtk_image_new_from_resource("/org/app/images/logo.png")` |
| GraalVM | Classpath resource |

### 16.2 Icon generation

`ssc build` auto-generates platform icon sets from `app.icon`:

| Platform | Sizes generated |
|----------|----------------|
| Web (PWA) | 192 px, 512 px |
| Electron | 16, 32, 256, 512 px (`.ico` / `.icns` / `.png`) |
| Android | `mipmap-*` (48, 72, 96, 144, 192, 512 px) |
| iOS | `AppIcon.appiconset` (20, 29, 40, 58, 60, 76, 80, 87, 120, 167, 180, 1024 px) |
| macOS | `.icns` (16, 32, 64, 128, 256, 512, 1024 px) |
| GTK | `.desktop` entry + hicolor theme (16, 32, 48, 64, 128, 256 px) |

### 16.3 Per-platform packager mapping

| Renderer | Asset integration |
|----------|-----------------|
| Electron | `extraResources` in `electron-builder.yml` |
| Compose | `composeApp/.../resources/` directories |
| SwiftUI | Xcode bundle `Resources/` |
| React Native | Metro bundler + asset registry |
| GTK | `gresource` XML + `glib-compile-resources` |
| GraalVM / JavaFX | `resource-config.json` for `native-image` |

---

## 17. Mobile UX & Lifecycle

### 17.1 Component lifecycle hooks

```scala
trait LifecycleHooks:
  def onMount():     Unit = ()   // component attached to view tree
  def onUnmount():   Unit = ()   // component removed from view tree
  def onAppear():    Unit = ()   // entered visible area (nav push / tab switch)
  def onDisappear(): Unit = ()   // left visible area
  def onPause():     Unit = ()   // app backgrounded (mobile)
  def onResume():    Unit = ()   // app foregrounded
  def onLowMemory(): Unit = ()   // system memory pressure
```

| Hook | Web | Compose | SwiftUI | React Native |
|------|-----|---------|---------|-------------|
| `onMount` | `connectedCallback` | `LaunchedEffect(Unit)` | `.onAppear` (first) | `useEffect(,[])` |
| `onUnmount` | `disconnectedCallback` | `DisposableEffect { onDispose }` | `.onDisappear` (final) | `useEffect` cleanup |
| `onAppear` | `IntersectionObserver` | `LifecycleOwner.ON_START` | `.onAppear` | `useFocusEffect` |
| `onDisappear` | `IntersectionObserver` | `LifecycleOwner.ON_STOP` | `.onDisappear` | `useFocusEffect` cleanup |
| `onPause` | `visibilitychange` | `LifecycleOwner.ON_PAUSE` | `scenePhase == .background` | `AppState "background"` |
| `onResume` | `visibilitychange` | `LifecycleOwner.ON_RESUME` | `scenePhase == .active` | `AppState "active"` |
| `onLowMemory` | N/A | `ComponentActivity.onLowMemory` | N/A | `AppRegistry.setWarnHandler` |

### 17.2 Safe area and keyboard

`View.SafeArea(child, edges)` pads the child by the device's safe-area insets (notch, home indicator, status bar). `View.KeyboardAvoiding(child)` shifts layout when the software keyboard appears.

The `Style` modifier `.safeAreaPadding(edges)` is a shorthand for `View.SafeArea`.

---

## 18. Forms

`View.Form(child, onSubmit)` establishes a submit scope. Nested `View.FormField[T]` nodes register their `validate` function with the enclosing `Form`.

```
Form(
  child = Column(Seq(
    FormField("Name",  nameSignal,  n => if n.isEmpty then Some("Required") else None),
    FormField("Email", emailSignal, e => if !e.contains("@") then Some("Invalid email") else None),
    Button(Text("Submit"), Form.submit)   // built-in EventHandler
  )),
  onSubmit = Simple(() => submitToServer(nameSignal.get, emailSignal.get))
)
```

`Form.submit` validates all registered fields; if any fail, it focuses the first invalid one and suppresses `onSubmit`. `Form.focusFirstInvalid` is also available as a standalone `EventHandler`.

| Concept | Web | Compose | SwiftUI | React Native |
|---------|-----|---------|---------|-------------|
| `Form` | `<form>` | custom state + submit dispatch | `Form { }` | `ScrollView` + state |
| `FormField[T]` | `<label>` + `<input>` + `<span class="error">` | `OutlinedTextField(isError, supportingText)` | `TextField` in `Section` | `TextInput` + `Text` |
| validation display | inline error span | `supportingText` | `.overlay(Text(...))` | `Text` below field |
| focus-first-invalid | `element.focus()` | `FocusRequester.requestFocus()` | `@FocusState` + `.focused` | `ref.current.focus()` |

---

## 19. Hot Reload & Dev Loop

```bash
ssc run --target <target> my-app.ssc
```

| Target / Renderer | Hot reload | Dev server |
|---|---|---|
| `web` (any renderer) | Vite HMR | `ssc serve` |
| `desktop-electron` | Electron + Vite HMR | `ssc serve` |
| `mobile` (React Native) | Metro fast refresh | Metro dev server |
| `mobile` (Compose) | `compose-hot-reload-runtime` (experimental) | Gradle dev mode |
| `desktop-macos` (SwiftUI) | `#Preview` pipeline | No |
| `desktop-linux` (GTK) | Incremental recompile + re-exec | No |
| `desktop-windows` (JavaFX) | JVM classloader reload (limited) | No |

### iOS/Android launch flow

```bash
# iOS
ssc run --target mobile-ios my-app.ssc
# 1. probe xcrun simctl list — pick/boot "iPhone 15 Pro"
# 2. ssc build --target mobile-ios
# 3. xcrun simctl install booted <app.app>
# 4. xcrun simctl launch booted <bundle-id>

# Android
ssc run --target mobile-android my-app.ssc
# 1. adb devices — use running emulator or prompt to start one
# 2. gradle assembleDebug
# 3. adb install -r app-debug.apk
# 4. adb shell am start -n <package>/<activity>
```

---

## 20. Testing Strategy

### 20.1 View IR snapshot tests

Serialize any `View[?]` to a canonical indented string; diff against `.snap` gold files. Renderer-agnostic and fast.

```
Column(spacing=8) [style: padding=16]
  Text("Hello")
  Button(enabled=true) [style: cornerRadius=8]
    label: Text("Click")
    action: SetSignalLiteral(id="count", value=0)
```

Gold files: `tests/__golden__/ir/<case>.snap`.

### 20.2 Per-renderer screenshot tests

Render gold fixture components via each headless renderer; compare via SSIM ≥ 0.95 at 1× and 2× density.

| Renderer | Headless strategy |
|----------|------------------|
| `web` | Playwright screenshot |
| `react-native` | Jest + RN Testing Library; visual via Maestro |
| `compose-desktop` | `ComposeDesktopTestUtil` |
| `swiftui-macos` | `XCTest + XCTAttachment` snapshot |
| `gtk` | Xvfb + `scrot` in CI |
| `javafx` | TestFX + offscreen renderer |

Gold PNGs: `tests/__golden__/<renderer>/<case>.png`.

CI matrix: `web`, `electron`, `react-native`, `compose-android`, `compose-desktop`, `swiftui-macos`, `gtk-linux`, `javafx-windows`.

---

## 21. Migration Plan

### Breaking changes in one commit

Three breaking changes land together so codebase is never in a half-migrated state:

1. `sealed trait View` → `enum View[+A]` (all codegen + frontend backends)
2. `jsName` → `id` on four types in `Primitives.scala`
3. `Manifest.dependencies: Map[String, String]` → `List[String]`

### Codemod

`scripts/migrate-native-platform.scala` (to be written):

- Rewrites `dependencies: {name: version}` map → `- jvm: "name:version"` list (keys without a scheme default to `jvm:`).
- Renames `.jsName` → `.id` and `.tickJsName` → `.tickId` in `.ssc` and generated Scala source.
- Reports files it could not rewrite automatically.

```bash
ssc migrate-deps my-app.ssc           # rewrite deps format
ssc migrate-deps examples/            # rewrite entire directory
```

### Deprecated aliases (one minor version)

```scala
class ReactiveSignal[T](val id: String, ...):
  @deprecated("use id", "v1.31") def jsName: String = id

@deprecated("use WidgetRef", "v1.31") type DomRef = WidgetRef
@deprecated("use EmittedArtifact.Spa", "v1.31") type EmittedSpaArtifact = EmittedArtifact.Spa
```

### Schema files

`schemas/frontmatter.yaml` must be updated before the impl lands (P0 prerequisite):

- `dependencies`: change from `object` to `array of string` with pattern `^(jvm|kotlin|npm|swift|native):.+$`.
- Add `app`, `native`, `assets` as optional top-level object keys.
- `validate-frontmatter.scala`: add list validation for `dependencies`.

### MIGRATION.md entry (v1.31)

Step-by-step: run `ssc migrate-deps`, rename `jsName` refs, update frontmatter, rebuild. Include the codemod invocation and a checklist of affected `examples/` files.

---

## 22. Roadmap

| Phase | Deliverable | Complexity | Est. |
|-------|-------------|------------|------|
| **P0 — Spec & migration** | Schema update (`schemas/frontmatter.yaml`), `validate-frontmatter.scala` extension, `scripts/migrate-native-platform.scala` codemod, `MIGRATION.md` entry | Low | 1 w |
| **P1 — IR Foundation** | `enum View[+A]`, Style system, `WidgetRef`, `id` rename (4 sites), Platform enum, `NativeFrontendSpi`, `EmittedArtifact`, `--target` dispatch in CLI | Medium | 3–4 w |
| **P2 — Web renderer update** | Custom / Vue / React / Solid updated for new `View[+A]` and `Style`; deprecated shims | Medium | 2 w |
| **P2 — Toolchain UX** | `ssc toolchain check/install`, interactive prompt, auto-install via Coursier / mise / Homebrew / apt / scoop | Low | 1 w |
| **P2 — Std intrinsics on native** | `httpGet` / `Db.query` / `loadConfig` — native backend mappings (Ktor, URLSession, libcurl, Room, GRDB, libsqlite3) | Medium | 2 w |
| **P3 — Electron** | `electron` renderer, `--target desktop`, `electron-main.js` generation + packaging | Low | 1–2 w |
| **P3 — React Native** | `react-native` renderer (JS backend), `--target mobile`, Expo project generation | Medium | 2–3 w |
| **P4 — Kotlin backend** | Kotlin codegen, Compose Multiplatform renderer, `--target mobile-android`, `--target desktop` | High | 4–6 w |
| **P5 — Swift backend** | Swift codegen, SwiftUI renderer, `--target mobile-ios`, `--target desktop-macos` | High | 4–6 w |
| **P6 — GraalVM** | `native-image` pipeline, JavaFX renderer, `--target desktop-windows` | Medium | 3–4 w |
| **P7 — Scala Native** | LLVM pipeline, GTK renderer (Linux), Cocoa / AppKit (macOS), `--target desktop-linux` | Very high | 6–8 w |

---

## 23. Open Questions

- **`Color.System` token vocabulary**: need to standardise the cross-platform token set (Material You / Cupertino / Fluent Design mapping). A dedicated `color-tokens.md` spec.
- **Hot reload fidelity**: Compose hot-reload-runtime is experimental; SwiftUI `#Preview` works best in Xcode (not CLI). Production-grade `ssc run` hot reload for native targets may need platform tooling wrappers.
- **View IR snapshot serialisation format**: exact canonical string format for `.snap` files needs a formal grammar to avoid renderer-specific drift.
- **`Animatable[T]` generation**: cross-backend code generation for `animateTo` calls requires careful handling of each platform's animation scheduler (JS rAF / Compose coroutine / SwiftUI `withAnimation` / GTK event loop).
- **MILESTONES.md integration**: each phase above needs a corresponding milestone entry before implementation starts (per AGENTS.md workflow).
