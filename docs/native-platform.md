# Native Platform Support

> **Status: DRAFT** — Architecture decisions are settled; implementation details subject to revision.
> Version: 0.1 — May 2026

---

## 1. Goals

- Allow a single `.ssc` frontend component to target **Web, Desktop, and Mobile** without source changes.
- Support true native binaries (not just web-wrapped apps) for macOS, Linux, Windows, iOS, and Android.
- Add **Kotlin** and **Swift** as first-class codegen backends alongside the existing JS and JVM backends.
- Provide a **unified View IR** whose semantics map cleanly to HTML/DOM, Compose, SwiftUI, GTK, and React Native.
- Define a **full style system** whose properties translate to CSS, Compose `Modifier`, SwiftUI modifiers, GTK CSS, etc.
- Make the toolchain experience friendly: detect missing tools, show a clear list, offer auto-install.
- Keep the import/dependency model declarative; let the target compiler validate types.

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
    ├─ Compilation Backend  ──────────────────────────────────────────────────┐
    │  (js | jvm | kotlin | swift | scala-native | graalvm)                  │
    │  Translates business logic to target language source.                   │
    │  Does NOT validate imported library types — that is the target          │
    │  compiler's job. ScalaScript formats target compiler errors.            │
    │                                                                         │
    └─ Frontend Renderer  ────────────────────────────────────────────────────┤
       (custom | vue | react | solid | electron |                             │
        react-native | compose | swiftui | gtk | javafx)                     │
       Translates View IR to UI code in the target language.                  │
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

---

## 4. Platform ADT

```scala
enum Platform:
  case Web
  case Desktop(os: DesktopOs = DesktopOs.All)
  case Mobile(os: MobileOs = MobileOs.All)
  case All                                      // matches every platform

enum DesktopOs:
  case MacOS, Windows, Linux, All

enum MobileOs:
  case iOS, Android, All

enum AppFormat:
  case WebSpa                 // HTML + JS bundle (current default)
  case ElectronApp            // WebSpa packaged in Electron shell
  case ReactNativeBundle      // Metro / Expo bundle
  case ComposeMultiplatform   // Kotlin + Compose → Desktop + Mobile
  case SwiftUIApp             // Swift Package + SwiftUI
  case ScalaNativeBinary      // Scala Native → LLVM → native binary
  case GraalVMNativeImage     // GraalVM AOT → native binary (with JavaFX)
  case KotlinAndroidApk       // Kotlin → Android APK / AAB

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

### 5.1 Main ADT

All cases use **Scala 3 enum**. Each structural node carries an optional `style: Style` field for direct construction; the `Styled` wrapper enables the modifier-chain API.

```scala
enum View:

  // ── Layout ───────────────────────────────────────────────────────────
  /** Vertical stack.
   *  Web: flex-column div | Compose: Column | SwiftUI: VStack | GTK: GtkBox(VERTICAL) */
  case Column(
    children: Seq[View],
    spacing:  Double   = 0,
    align:    HAlign   = HAlign.Start,
    style:    Style    = Style()
  )

  /** Horizontal stack.
   *  Web: flex-row div | Compose: Row | SwiftUI: HStack | GTK: GtkBox(HORIZONTAL) */
  case Row(
    children: Seq[View],
    spacing:  Double   = 0,
    align:    VAlign   = VAlign.Center,
    style:    Style    = Style()
  )

  /** Z-axis overlay stack.
   *  Web: position:relative + absolute children | Compose: Box | SwiftUI: ZStack | GTK: GtkOverlay */
  case Stack(children: Seq[View], style: Style = Style())

  /** Scrollable container.
   *  Web: overflow:auto div | Compose: LazyColumn/ScrollState | SwiftUI: ScrollView | GTK: GtkScrolledWindow */
  case ScrollView(child: View, axis: Axis = Axis.Vertical, style: Style = Style())

  /** Flexible empty space. Web: flex spacer div | Compose: Spacer | SwiftUI: Spacer */
  case Spacer(size: Option[Double] = None)

  /** Visual separator. Web: <hr> | Compose: Divider | SwiftUI: Divider | GTK: GtkSeparator */
  case Divider(axis: Axis = Axis.Horizontal, style: Style = Style())

  // ── Content ──────────────────────────────────────────────────────────
  /** Static or computed text.
   *  Web: <span> | Compose: Text | SwiftUI: Text | GTK: GtkLabel */
  case Text(content: () => String, style: Style = Style())

  /** Text bound to a reactive signal. Updates without re-rendering parent. */
  case SignalText(signal: ReactiveSignal[?], style: Style = Style())

  /** Image from a URL, bundled asset, or base64 data.
   *  Web: <img> | Compose: Image | SwiftUI: Image | GTK: GtkImage */
  case Image(source: ImageSource, style: Style = Style())

  /** Platform icon (SF Symbol on Apple, Material on Android/Compose, Lucide on web).
   *  Resolved by name at render time; falls back to a placeholder when unknown. */
  case Icon(name: String, style: Style = Style())

  // ── Controls ─────────────────────────────────────────────────────────
  /** Tappable / clickable button.
   *  Web: <button> | Compose: Button | SwiftUI: Button | GTK: GtkButton */
  case Button(
    label:   View,
    action:  EventHandler,
    enabled: () => Boolean = () => true,
    style:   Style         = Style()
  )

  /** Single-line or multiline text field.
   *  Web: <input type="text"> / <textarea>
   *  Compose: TextField | SwiftUI: TextField / SecureField | GTK: GtkEntry */
  case TextInput(
    value:       ReactiveSignal[String],
    placeholder: String  = "",
    multiline:   Boolean = false,
    secure:      Boolean = false,
    style:       Style   = Style()
  )

  /** Boolean toggle.
   *  Web: <input type="checkbox"> | Compose: Switch | SwiftUI: Toggle | GTK: GtkCheckButton */
  case Toggle(checked: ReactiveSignal[Boolean], label: String = "", style: Style = Style())

  /** Continuous range input.
   *  Web: <input type="range"> | Compose: Slider | SwiftUI: Slider | GTK: GtkScale */
  case Slider(
    value: ReactiveSignal[Double],
    min:   Double,
    max:   Double,
    step:  Double = 1.0,
    style: Style  = Style()
  )

  /** Option selector.
   *  Web: <select> | Compose: ExposedDropdownMenuBox | SwiftUI: Picker | GTK: GtkComboBoxText */
  case Picker[T](
    options:     Seq[(String, T)],
    selected:    ReactiveSignal[T],
    placeholder: String = "",
    style:       Style  = Style()
  )

  // ── Navigation ───────────────────────────────────────────────────────
  /** Tab-based navigation.
   *  Web: router tabs | Compose: NavigationBar + HorizontalPager
   *  SwiftUI: TabView | GTK: GtkNotebook */
  case TabBar(tabs: Seq[Tab], current: ReactiveSignal[Int], style: Style = Style())

  /** Push/pop navigation stack.
   *  Web: router | Compose: NavHost | SwiftUI: NavigationStack | GTK: GtkStack */
  case NavigationStack(
    routes:  Map[String, () => View],
    current: ReactiveSignal[String],
    style:   Style = Style()
  )

  // ── Overlays ─────────────────────────────────────────────────────────
  /** Bottom sheet / modal panel.
   *  Web: Portal + fixed overlay | Compose: ModalBottomSheet | SwiftUI: .sheet() | GTK: GtkDialog */
  case Sheet(content: View, isPresented: ReactiveSignal[Boolean])

  /** Alert / confirmation dialog.
   *  Web: <dialog> | Compose: AlertDialog | SwiftUI: .alert() | GTK: GtkMessageDialog */
  case AlertDialog(
    title:       String,
    message:     String,
    buttons:     Seq[AlertButton],
    isPresented: ReactiveSignal[Boolean]
  )

  // ── Reactivity ───────────────────────────────────────────────────────
  case Fragment(children: Seq[View])
  case ComponentInstance[P](component: Component[P], props: P)
  case ShowSignal(cond: ReactiveSignal[Boolean], whenTrue: View, whenFalse: View = Fragment(Nil))
  case Show(cond: () => Boolean, whenTrue: () => View, whenFalse: () => View = () => Fragment(Nil))
  case ForSignal[T](items: ReactiveSignalList[T], itemTemplate: Option[View] = None)
  case For[T](items: () => Seq[T], render: T => View)
  case ItemText  // iteration-value placeholder inside ForSignal.itemTemplate

  // ── Style wrapper ────────────────────────────────────────────────────
  /** Produced by the modifier-chain extension methods (see §6.3).
   *  When merging styles the wrapper's style takes precedence over the child's own style. */
  case Styled(child: View, style: Style)

  // ── Platform-adaptive escape hatch ───────────────────────────────────
  /** Renders the matching branch for the current platform; falls back to `fallback`.
   *  Use sparingly — prefer universal nodes above. */
  case Adaptive(
    web:      Option[View] = None,
    desktop:  Option[View] = None,
    mobile:   Option[View] = None,
    fallback: View         = Fragment(Nil)
  )

  // ── Web-only (deprecated for non-web targets) ────────────────────────
  /** Raw HTML element. Emits a compile warning on non-web targets. */
  @webOnly
  case Element(
    tag:      String,
    attrs:    Map[String, AttrValue],
    events:   Map[String, EventHandler],
    children: Seq[View]
  )

  /** DOM portal — renders children into a different DOM node.
   *  No-op on native targets. */
  @webOnly
  case Portal(target: String, children: Seq[View])

  /** Reactive data table backed by a REST endpoint.
   *  Moved to toolkit layer; retained here temporarily for back-compat. */
  @webOnly
  case FetchTable(
    tableJsName: String,
    fetchUrl:    String,
    deleteUrl:   String,
    tick:        ReactiveSignal[Int]
  )
```

### 5.2 Supporting types

```scala
final case class Tab(label: String, icon: Option[String], content: View)
final case class AlertButton(label: String, action: () => Unit, role: ButtonRole = ButtonRole.Default)

enum ButtonRole:   case Default, Cancel, Destructive
enum Axis:         case Horizontal, Vertical, Both
enum HAlign:       case Start, Center, End, Stretch
enum VAlign:       case Top, Center, Bottom, Stretch
enum ContentFit:   case Contain, Cover, Fill, None

enum ImageSource:
  case Url(href: String)
  case Asset(name: String)                         // file under assets/
  case Base64(data: String, mime: String)
```

---

## 6. Style System

### 6.1 Style record

```scala
final case class Style(
  // ── Layout ───────────────────────────────────────────────────────────
  padding:       EdgeInsets          = EdgeInsets.zero,
  margin:        EdgeInsets          = EdgeInsets.zero,
  width:         Dimension           = Dimension.Auto,
  height:        Dimension           = Dimension.Auto,
  minWidth:      Option[Double]      = None,
  maxWidth:      Option[Double]      = None,
  minHeight:     Option[Double]      = None,
  maxHeight:     Option[Double]      = None,
  flex:          Option[Double]      = None,   // grow factor
  flexShrink:    Option[Double]      = None,
  flexBasis:     Option[Dimension]   = None,
  alignSelf:     Option[Align]       = None,
  gap:           Option[Double]      = None,
  zIndex:        Option[Int]         = None,

  // ── Color ────────────────────────────────────────────────────────────
  background:    Option[Color]       = None,
  foreground:    Option[Color]       = None,   // text / icon tint

  // ── Typography ───────────────────────────────────────────────────────
  fontSize:      Option[Double]      = None,
  fontWeight:    Option[FontWeight]  = None,
  fontFamily:    Option[String]      = None,
  fontStyle:     FontStyle           = FontStyle.Normal,
  lineHeight:    Option[Double]      = None,
  letterSpacing: Option[Double]      = None,
  textDecoration: Set[TextDecoration] = Set.empty,
  textAlign:     TextAlign           = TextAlign.Start,
  textOverflow:  TextOverflow        = TextOverflow.Clip,
  maxLines:      Option[Int]         = None,

  // ── Border ───────────────────────────────────────────────────────────
  borderColor:   Option[Color]       = None,
  borderWidth:   Option[Double]      = None,
  borderRadius:  BorderRadius        = BorderRadius.zero,
  borderStyle:   BorderLineStyle     = BorderLineStyle.Solid,

  // ── Shadow ───────────────────────────────────────────────────────────
  shadow:        Option[Shadow]      = None,

  // ── Effects ──────────────────────────────────────────────────────────
  opacity:       Double              = 1.0,
  overflow:      Overflow            = Overflow.Visible,
  cursor:        Option[Cursor]      = None,   // web-only; ignored on native

  // ── Transform ────────────────────────────────────────────────────────
  transform:     List[Transform]     = Nil,

  // ── Platform overrides (escape hatches) ──────────────────────────────
  web:           Map[String, String] = Map.empty,  // raw CSS properties
  native:        Map[String, String] = Map.empty   // platform-specific hints
)

object Style:
  val empty: Style = Style()
```

### 6.2 Supporting types

```scala
final case class EdgeInsets(top: Double, right: Double, bottom: Double, left: Double)
object EdgeInsets:
  val zero = EdgeInsets(0, 0, 0, 0)
  def all(v: Double)              = EdgeInsets(v, v, v, v)
  def symmetric(h: Double, v: Double) = EdgeInsets(v, h, v, h)
  def only(top: Double = 0, right: Double = 0, bottom: Double = 0, left: Double = 0) =
    EdgeInsets(top, right, bottom, left)

enum Dimension:
  case Auto
  case Fixed(value: Double)
  case Fraction(value: Double)  // fraction of parent, 0..1
  case Fill                     // take all available space

enum Align:       case Start, Center, End, Stretch, Baseline
enum FontStyle:   case Normal, Italic
enum TextAlign:   case Start, Center, End, Justify
enum TextOverflow: case Clip, Ellipsis
enum Overflow:    case Visible, Hidden, Scroll, Auto
enum BorderLineStyle: case Solid, Dashed, Dotted, None

enum TextDecoration:
  case Underline, Strikethrough, Overline

enum FontWeight:
  case Thin, ExtraLight, Light, Regular, Medium, SemiBold, Bold, ExtraBold, Black
  case Custom(value: Int)  // 100–900

enum Cursor:  // web-only
  case Default, Pointer, Text, Grab, Grabbing, NotAllowed, Crosshair

enum Color:
  case Hex(value: String)
  case Rgb(r: Int, g: Int, b: Int)
  case Rgba(r: Int, g: Int, b: Int, a: Double)
  case Named(name: String)    // "red", "blue"
  case System(token: String)  // "systemBackground", "onSurface", "label" — platform tokens
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

Modifier methods return `View.Styled`. When the receiver is already `View.Styled`, the new style is merged over the existing one (the outermost call wins for each property).

```scala
extension (v: View)
  // Layout
  def padding(all: Double): View                        = styled(_.copy(padding = EdgeInsets.all(all)))
  def padding(h: Double, vert: Double): View            = styled(_.copy(padding = EdgeInsets.symmetric(h, vert)))
  def padding(e: EdgeInsets): View                      = styled(_.copy(padding = e))
  def margin(all: Double): View                         = styled(_.copy(margin = EdgeInsets.all(all)))
  def margin(e: EdgeInsets): View                       = styled(_.copy(margin = e))
  def frame(w: Double, h: Double): View                 = styled(_.copy(width = Dimension.Fixed(w), height = Dimension.Fixed(h)))
  def width(d: Dimension): View                         = styled(_.copy(width = d))
  def height(d: Dimension): View                        = styled(_.copy(height = d))
  def fill: View                                        = styled(_.copy(width = Dimension.Fill, height = Dimension.Fill))
  def fillWidth: View                                   = styled(_.copy(width = Dimension.Fill))
  def fillHeight: View                                  = styled(_.copy(height = Dimension.Fill))
  def flex(factor: Double = 1): View                    = styled(_.copy(flex = Some(factor)))
  def minWidth(v: Double): View                         = styled(_.copy(minWidth = Some(v)))
  def maxWidth(v: Double): View                         = styled(_.copy(maxWidth = Some(v)))
  def zIndex(z: Int): View                              = styled(_.copy(zIndex = Some(z)))

  // Color
  def background(c: Color): View                       = styled(_.copy(background = Some(c)))
  def foreground(c: Color): View                       = styled(_.copy(foreground = Some(c)))

  // Typography
  def fontSize(s: Double): View                        = styled(_.copy(fontSize = Some(s)))
  def fontWeight(w: FontWeight): View                  = styled(_.copy(fontWeight = Some(w)))
  def bold: View                                       = fontWeight(FontWeight.Bold)
  def light: View                                      = fontWeight(FontWeight.Light)
  def italic: View                                     = styled(_.copy(fontStyle = FontStyle.Italic))
  def underline: View                                  = styled(s => s.copy(textDecoration = s.textDecoration + TextDecoration.Underline))
  def strikethrough: View                              = styled(s => s.copy(textDecoration = s.textDecoration + TextDecoration.Strikethrough))
  def textAlign(a: TextAlign): View                    = styled(_.copy(textAlign = a))
  def lineHeight(h: Double): View                      = styled(_.copy(lineHeight = Some(h)))
  def letterSpacing(s: Double): View                   = styled(_.copy(letterSpacing = Some(s)))
  def lineLimit(n: Int): View                          = styled(_.copy(maxLines = Some(n)))

  // Border
  def border(color: Color, width: Double = 1): View    = styled(_.copy(borderColor = Some(color), borderWidth = Some(width)))
  def cornerRadius(r: Double): View                    = styled(_.copy(borderRadius = BorderRadius.all(r)))

  // Shadow / effects
  def shadow(s: Shadow): View                          = styled(_.copy(shadow = Some(s)))
  def shadow(blur: Double = 8, color: Color = Color.Rgba(0, 0, 0, 0.15)): View =
    styled(_.copy(shadow = Some(Shadow(color, blur = blur))))
  def opacity(v: Double): View                         = styled(_.copy(opacity = v))
  def clip: View                                       = styled(_.copy(overflow = Overflow.Hidden))

  // Transform
  def rotate(deg: Double): View                        = appendTransform(Transform.Rotate(deg))
  def scale(f: Double): View                           = appendTransform(Transform.Scale(f))
  def translate(x: Double, y: Double): View            = appendTransform(Transform.Translate(x, y))

  // Platform overrides
  def css(prop: String, value: String): View           = styled(s => s.copy(web = s.web + (prop -> value)))
  def nativeHint(key: String, value: String): View     = styled(s => s.copy(native = s.native + (key -> value)))

  private def styled(f: Style => Style): View = v match
    case View.Styled(child, s) => View.Styled(child, f(s))
    case other                 => View.Styled(other, f(Style()))

  private def appendTransform(t: Transform): View = v match
    case View.Styled(child, s) => View.Styled(child, s.copy(transform = s.transform :+ t))
    case other                 => View.Styled(other, Style(transform = List(t)))
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
| `foreground` | `color: #333` | `.color(Color(...))` | `.foregroundColor(.gray)` | `gtk_widget_override_color` |
| `width(Fill)` | `width: 100%` | `Modifier.fillMaxWidth()` | `.frame(maxWidth: .infinity)` | `gtk_widget_set_hexpand(TRUE)` |

---

## 7. Reactive Primitives — Breaking Changes

### 7.1 `jsName` → `id`

`ReactiveSignal.jsName` is renamed to `id`. The identifier is now the canonical name used across all codegen backends (JS, Kotlin, Swift, Scala Native).

```scala
// Before
class ReactiveSignal[T](val jsName: String, initial: T) extends Signal[T]

// After
class ReactiveSignal[T](val id: String, initial: T) extends Signal[T]

final class ReactiveSignalList[T](val id: String, val initial: Seq[T])

final class FetchUrlSignal(
  id:           String,
  val fetchUrl: String,
  val tickId:   String    // was: tickJsName
) extends ReactiveSignal[String](id, "")
```

### 7.2 `DomRef` → `WidgetRef`

`DomRef` is renamed to `WidgetRef`. A deprecated type alias is provided for the transition period.

```scala
final class WidgetRef(val id: String)

@deprecated("use WidgetRef", "v1.31")
type DomRef = WidgetRef
```

Signal-to-platform mapping:

| ScalaScript | JS / Custom | Compose | SwiftUI | GTK |
|------------|-------------|---------|---------|-----|
| `ReactiveSignal[T](id, v)` | `let <id> = <v>` + subs set | `var <id> by mutableStateOf(<v>)` | `@State var <id> = <v>` | mutable var + update callback |
| `signal.set(v)` | `__setSignal("<id>", v)` | `<id> = v` | `<id> = v` | `<id> = v; gtk_widget_queue_draw(...)` |

---

## 8. Import / Dependency Model

Imports are **declarative and untyped from ScalaScript's perspective**. The target compiler validates them. ScalaScript formats target compiler errors with source position and context.

### 8.1 Frontmatter

```yaml
---
name: my-app
dependencies:
  - jvm:    "org.typelevel::cats-core:2.10.0"
  - jvm:    "io.circe::circe-core:0.14.0"
  - kotlin: "androidx.compose.material3:material3:1.2.0"
  - kotlin: "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.0"
  - npm:    "axios@1.6.0"
  - npm:    "@react-navigation/native@6.0.0"
  - swift:  "github.com/Alamofire/Alamofire 5.9.0"
  - swift:  "github.com/onevcat/Kingfisher 7.10.0"
  - native: "gtk-4.0"       # pkg-config name → -lgtk-4
  - native: "libsodium"
---
```

### 8.2 Inline declarations (in code)

```
dep "jvm:org.typelevel::cats-core:2.10.0"
dep "npm:axios@1.6.0"
dep "kotlin:androidx.compose.material3:material3:1.2.0"
dep "swift:github.com/Alamofire/Alamofire 5.9.0"
dep "native:gtk-4.0"
```

### 8.3 Backend resolution

Each compilation backend injects the appropriately-scoped dependencies into the target build system:

| Dep prefix | Injected into |
|-----------|--------------|
| `jvm:` | `//> using dep` (scala-cli) or `build.gradle.kts` |
| `kotlin:` | `build.gradle.kts` `implementation(...)` |
| `npm:` | `package.json` `dependencies` |
| `swift:` | `Package.swift` `.package(url:, from:)` |
| `native:` | linker flags via `pkg-config` / `nativeConfig` |

---

## 9. Frontend SPI

### 9.1 EmittedArtifact ADT

```scala
enum EmittedArtifact:
  case Spa(js: String, html: String, css: String)
  case NativeApp(
    sources:     Map[String, String],       // relative path → generated source
    resources:   Map[String, Array[Byte]],  // relative path → binary asset
    buildScript: String,                    // shell commands to build the final artifact
    manifest:    AppManifest,
    format:      AppFormat,
    target:      Platform
  )

final case class AppManifest(
  bundleId:    String,
  displayName: String,
  version:     String,
  minOs:       Map[Platform, String] = Map.empty   // e.g. Mobile(iOS) → "16.0"
)

// Backward-compat alias
type EmittedSpa = EmittedArtifact.Spa
```

### 9.2 SPI traits

```scala
/** Base SPI — web-only backends implement this (unchanged). */
trait FrontendFrameworkSpi:
  def name: String
  def capabilities: Set[Capability]
  def jsDeps: List[JsDep]
  def emit(module: FrontendModule): EmittedArtifact.Spa
  def supportedPlatforms: Set[Platform] = Set(Platform.Web)

/** Extension for backends that target native platforms. */
trait NativeFrontendSpi extends FrontendFrameworkSpi:
  override def supportedPlatforms: Set[Platform]
  def emitNative(module: FrontendModule, platform: Platform): EmittedArtifact.NativeApp

/** Backend that handles both web and native from one implementation. */
trait UniversalFrontendSpi extends NativeFrontendSpi:
  final def emitForPlatform(module: FrontendModule, platform: Platform): EmittedArtifact =
    platform match
      case Platform.Web => emit(module)
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
```

### 9.4 Capability enum — additions

```scala
enum Capability:
  // Existing web capabilities
  case ComponentTree, SignalState, ComputedDerived, EffectLifecycle
  case DomRefs, Context, Portals, Suspense, Untrack, TwoWayBinding
  // Universal view sets
  case NativeLayout      // Column / Row / Stack / ScrollView / Spacer / Divider
  case NativeControls    // Button / TextInput / Toggle / Slider / Picker / Image / Icon
  case NativeNavigation  // TabBar / NavigationStack
  case NativeOverlays    // Sheet / AlertDialog
  // Platform capabilities (accessed via declared deps)
  case Camera
  case Biometrics
  case PushNotifications
  case LocalStorage      // UserDefaults / SharedPreferences / localStorage
  case Geolocation
  case Haptics
  case DeepLinks
  case BackgroundTasks
  case FileSystem
```

---

## 10. Compilation Backends

### 10.1 Overview

| Backend | Generates | Primary use |
|---------|-----------|-------------|
| `js` | `.js` (CJS / ESM) | Web, React Native, Electron |
| `jvm` | `.scala` (Scala 3) | Server, GraalVM native-image |
| `kotlin` | `.kt` | Compose Multiplatform, Android, Kotlin/Native |
| `swift` | `.swift` | SwiftUI (macOS + iOS) |
| `scala-native` | `.scala` + `@extern` C bindings | GTK (Linux), Cocoa (macOS), Win32 |
| `graalvm` | `.scala` + native-image config | Standalone desktop binary with JavaFX |

### 10.2 Kotlin backend

Generates idiomatic Kotlin. Business logic maps naturally; reactive signals become `mutableStateOf`; `Component[P]` becomes a `@Composable` function when the Compose renderer is active.

```
// ScalaScript                         // Generated Kotlin
val count = Signal(0)           →      var count by mutableStateOf(0)
fun greet(name: String) = ...   →      fun greet(name: String): String = ...
route("GET", "/items") { ... }  →      // Ktor: get("/items") { ... }
```

### 10.3 Swift backend

Generates idiomatic Swift. Reactive signals become `@State` or `@Published` properties; `Component[P]` becomes a SwiftUI `View` struct with a `body` property.

```
// ScalaScript                         // Generated Swift
val count = Signal(0)           →      @State var count: Int = 0
fun greet(name: String) = ...   →      func greet(name: String) -> String { ... }
route("GET", "/items") { ... }  →      // Vapor: app.get("items") { req in ... }
```

### 10.4 Scala Native backend

Extends the JVM backend. Adds `@extern` C-binding objects for the target UI toolkit, appropriate `nativeConfig` (linker flags, GC), and platform entry points (`main` C-style or platform app delegate).

```scala
// Generated for GTK target
import scala.scalanative.unsafe.*
@extern object Gtk:
  def gtk_init(argc: Ptr[Int], argv: Ptr[Ptr[CString]]): Unit = extern
  def gtk_box_new(orientation: Int, spacing: Int): Ptr[Byte]  = extern
  def gtk_label_new(text: CString): Ptr[Byte]                 = extern
  def gtk_button_new_with_label(label: CString): Ptr[Byte]    = extern
  ...
```

### 10.5 GraalVM backend

Extends the JVM backend. Generates a native-image `reflect-config.json`, `resource-config.json`, and a build script that calls `native-image`. JavaFX UI is the primary renderer target; it is the most mature JVM-based cross-platform desktop option.

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
| `Text` | `<span>` | `Text(...)` | `Text(...)` | `gtk_label_new(...)` |
| `Button` | `<button>` | `Button(onClick) { }` | `Button { }` | `gtk_button_new_with_label(...)` |
| `TextInput` | `<input type="text">` | `TextField(...)` | `TextField(...)` | `gtk_entry_new()` |
| `Toggle` | `<input type="checkbox">` | `Switch(...)` | `Toggle(...)` | `gtk_check_button_new()` |
| `Slider` | `<input type="range">` | `Slider(...)` | `Slider(...)` | `gtk_scale_new(...)` |
| `TabBar` | router tabs | `NavigationBar` + `HorizontalPager` | `TabView` | `gtk_notebook_new()` |
| `Image` | `<img>` | `Image(...)` | `Image(...)` | `gtk_image_new_from_file(...)` |
| `ScrollView` | `overflow:auto div` | `LazyColumn` / `ScrollState` | `ScrollView` | `gtk_scrolled_window_new()` |
| `Sheet` | Portal + fixed overlay | `ModalBottomSheet { }` | `.sheet(isPresented:) { }` | `GtkDialog` |
| `AlertDialog` | `<dialog>` | `AlertDialog(...)` | `.alert(...)` | `GtkMessageDialog` |

### 11.3 Compose Multiplatform

> **What is Compose Multiplatform?**
> JetBrains' extension of Jetpack Compose (Google's declarative UI toolkit for Android) to Desktop (macOS, Windows, Linux), iOS, and Web. One Kotlin codebase; each platform uses the Compose compiler plugin on the appropriate Kotlin target (JVM for Desktop, Kotlin/Native for iOS, Kotlin/Wasm for Web). This is the strongest cross-platform story today for Kotlin codebases.

The `compose` renderer generates `@Composable` functions from the View IR. `ReactiveSignal[T]` maps to `mutableStateOf`. The Kotlin backend handles all surrounding build infrastructure (Gradle KMP project, `commonMain` / `androidMain` / `iosMain` source sets).

---

## 12. Toolchain Management

When a required tool is not found, `ssc` presents a structured interactive prompt rather than a raw error.

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
  │  → App Store: https://apps.apple.com/app/xcode/id497799835      │
  │  → or: xcode-select --install  (command-line tools only)         │
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
| Xcode | App Store (manual prompt) | — | — |

CLI command for explicit toolchain management:

```bash
ssc toolchain check                        # check all installed tools
ssc toolchain check --target mobile-ios    # check only what this target needs
ssc toolchain install --target mobile-ios  # auto-install for target
ssc toolchain list                         # show installed versions
```

---

## 13. CLI Extensions

### 13.1 New flags and commands

```bash
# compile — codegen only; does not invoke the target build system
ssc compile --backend kotlin   --target mobile-android  my-app.ssc
ssc compile --backend swift    --target desktop-macos   my-app.ssc
ssc compile --backend js       --frontend react-native  my-app.ssc

# build — compile + invoke target build system (Gradle / xcodebuild / npm / ...)
ssc build --target web                 my-app.ssc   # → WebSpa (default)
ssc build --target desktop             my-app.ssc   # → Electron (default desktop)
ssc build --target desktop-macos       my-app.ssc   # → SwiftUI .app
ssc build --target desktop-linux       my-app.ssc   # → GTK binary
ssc build --target desktop-windows     my-app.ssc   # → JavaFX / GraalVM binary
ssc build --target mobile              my-app.ssc   # → Compose (iOS + Android)
ssc build --target mobile-ios          my-app.ssc   # → SwiftUI .app / Compose iOS
ssc build --target mobile-android      my-app.ssc   # → Compose APK

# override frontend renderer
ssc build --target mobile-android --frontend react-native  my-app.ssc
ssc build --target mobile-android --frontend compose       my-app.ssc

# dev run with hot-reload where supported
ssc run --target desktop-electron      my-app.ssc   # Electron + hot reload
ssc run --target mobile-ios            my-app.ssc   # Expo / iOS Simulator
ssc run --target mobile-android        my-app.ssc   # Android Emulator

# existing commands — unchanged
ssc run-js    my-app.ssc
ssc serve     my-app.ssc
ssc check     my-app.ssc
ssc compile   my-app.ssc   # still defaults to --backend js
```

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

---

## 14. Frontmatter Extensions

```yaml
---
name: my-app
version: 1.0.0
frontend: custom        # web renderer (existing)
targets: [jvm, js, native, kotlin, swift]

# Application bundle metadata (new)
app:
  bundle-id:    com.example.myapp
  display-name: My App
  version:      1.0.0
  icon:         assets/icon.png

# Native build configuration (new)
native:
  desktop:
    format:       electron         # electron | graalvm | scala-native
    min-macos:    "12.0"
    min-windows:  "10"
    min-linux:    "ubuntu-22.04"
  mobile:
    format:       compose          # compose | react-native | swiftui
    min-ios:      "16.0"
    min-android:  26               # API level

# Cross-target dependencies (new)
dependencies:
  - jvm:    "org.typelevel::cats-core:2.10.0"
  - kotlin: "androidx.compose.material3:material3:1.2.0"
  - npm:    "axios@1.6.0"
  - swift:  "github.com/Alamofire/Alamofire 5.9.0"
  - native: "gtk-4.0"
---
```

---

## 15. Platform Services

Universal platform APIs available across Web, Desktop, and Mobile targets. Each service is expressed as a built-in intrinsic in ScalaScript; the compilation backend generates the appropriate platform call.

### 15.1 Config

Application configuration loaded from bundled files or platform settings stores.

```scala
object Config:
  def load[T](path: String): T
  def get(key: String): Option[String]
  def getOrElse(key: String, default: String): String
```

Frontmatter:

```yaml
config:
  file: config.yaml          # bundled default config
  schema: Config             # optional ScalaScript type for compile-time shape check
  allow-env-override: true   # SSC_<KEY> env vars override individual keys
```

Platform mapping:

| Backend | Config source |
|---------|---------------|
| `js` (web) | Bundled JSON loaded at startup; `window.__ssc_config` injection |
| `js` (Electron) | `config.yaml` in `app.getPath('userData')` |
| `kotlin` (Android) | `assets/config.yaml` + `SharedPreferences` for user overrides |
| `swift` (iOS / macOS) | App bundle `config.yaml` + `UserDefaults` for user overrides |
| `scala-native` | File system read from exe-adjacent directory |
| `graalvm` / `jvm` | Classpath resource + `-Dssc.config.path` system property |

### 15.2 Fetch

Cross-platform HTTP client. The `Http` intrinsic generates idiomatic async HTTP code in the target language. The existing `FetchUrlSignal` is the reactive signal wrapper around `Http.get`; it keeps its surface area but now delegates to the platform-neutral backend.

```scala
object Http:
  def get(url: String, headers: Map[String, String] = Map.empty): HttpResponse
  def post(url: String, body: String, headers: Map[String, String] = Map.empty): HttpResponse
  def put(url: String, body: String, headers: Map[String, String] = Map.empty): HttpResponse
  def delete(url: String, headers: Map[String, String] = Map.empty): HttpResponse
  def request(req: HttpRequest): HttpResponse

final case class HttpRequest(
  method:  String,
  url:     String,
  headers: Map[String, String] = Map.empty,
  body:    Option[String]      = None
)

final case class HttpResponse(
  status:  Int,
  headers: Map[String, String],
  body:    String
)
```

Platform mapping:

| Backend | HTTP client |
|---------|------------|
| `js` (web) | Browser `fetch()` API |
| `js` (Electron / Node) | Node.js `fetch()` / `https` module |
| `js` (React Native) | React Native `fetch()` |
| `kotlin` | Ktor Client (`HttpClient`) |
| `swift` | `URLSession` (`async`/`await`) |
| `scala-native` | libcurl via `@extern` bindings |
| `graalvm` / `jvm` | `java.net.http.HttpClient` |

### 15.3 SQL

Embedded SQL access for local databases. `SQLite` is the universal default; `DuckDB` is the analytics alternative for Desktop. Server-side SQL (PostgreSQL, MySQL) is available via declared `jvm:`/`kotlin:` JDBC dependencies — no special intrinsic needed.

```scala
object Sql:
  def open(path: String, driver: SqlDriver = SqlDriver.SQLite): SqlConnection
  def openDefault(driver: SqlDriver = SqlDriver.SQLite): SqlConnection

enum SqlDriver:
  case SQLite
  case DuckDB

final class SqlConnection:
  def query(sql: String, params: Any*): List[Map[String, Any]]
  def exec(sql: String, params: Any*): Int   // rows affected
  def transaction[T](block: SqlConnection => T): T
  def close(): Unit
```

Usage example:

```
val db = Sql.open("todos.db")
db.exec("CREATE TABLE IF NOT EXISTS todos (id INTEGER PRIMARY KEY, text TEXT, done BOOLEAN)")
val todos = db.query("SELECT * FROM todos WHERE done = ?", false)
```

Platform mapping:

| Backend | Library |
|---------|---------|
| `js` (web) | `@sqlite.org/sqlite-wasm` (SQLite compiled to WASM) |
| `js` (Electron / Node) | `better-sqlite3` |
| `js` (React Native) | `react-native-sqlite-storage` |
| `kotlin` (Android) | Room persistence library (SQLite) |
| `kotlin` (Desktop) | SQLite JDBC driver; `org.duckdb:duckdb_jdbc` for DuckDB |
| `swift` (iOS / macOS) | `SQLite3` system framework / `GRDB.swift` |
| `scala-native` | `libsqlite3` via `@extern` bindings |
| `graalvm` / `jvm` | `org.xerial:sqlite-jdbc` / `org.duckdb:duckdb_jdbc` |

Dependencies are injected automatically when `Sql` is used; no manual `dep` declaration required. Frontmatter override:

```yaml
sql:
  driver: sqlite           # sqlite | duckdb
  migrations: migrations/  # directory of numbered .sql files applied in order on open
```

---

## 16. Roadmap

| Phase | Deliverable | Complexity | Est. |
|-------|-------------|------------|------|
| **P1 — IR Foundation** | View ADT (Scala 3 enum), Style system, WidgetRef, `id` rename, Platform enum, NativeFrontendSpi, EmittedArtifact, `--target` CLI flag skeleton | Medium | 3–4 w |
| **P2 — Web renderer update** | Custom / Vue / React / Solid updated for new View ADT and Style; backward-compat shims | Medium | 2 w |
| **P2 — Toolchain UX** | `ssc toolchain check/install`, interactive prompt, auto-install via Coursier / mise / Homebrew / apt / scoop | Low | 1 w |
| **P2 — Platform Services** | `Config`, `Http`, `Sql` intrinsics for JS/JVM targets; platform mapping for web and Electron | Medium | 2 w |
| **P3 — Electron** | `electron` renderer, `--target desktop`, generated `electron-main.js` + packaging config | Low | 1–2 w |
| **P3 — React Native** | `react-native` renderer (JS backend), `--target mobile`, Expo project generation | Medium | 2–3 w |
| **P4 — Kotlin backend** | Kotlin codegen, Compose Multiplatform renderer, `--target mobile-android`, `--target desktop`; Config/Http/Sql mapped to Ktor / Room | High | 4–6 w |
| **P5 — Swift backend** | Swift codegen, SwiftUI renderer, `--target mobile-ios`, `--target desktop-macos`; Config/Http/Sql mapped to URLSession / SQLite3 | High | 4–6 w |
| **P6 — GraalVM** | `native-image` pipeline, JavaFX renderer, `--target desktop-windows`; JDBC-based Config/Sql | Medium | 3–4 w |
| **P7 — Scala Native** | LLVM pipeline, GTK renderer (Linux), Cocoa / AppKit renderer (macOS), `--target desktop-linux`; libcurl + libsqlite3 bindings | Very high | 6–8 w |

---

## 17. Open Questions

- **Asset pipeline**: how are `assets/` files declared, validated, and packaged in native bundles (icons, fonts, images). Needs a dedicated spec.
- **Navigation / deep links**: URL schemes for mobile deep links and universal links; how `NavigationStack.current` maps to platform routing.
- **Color.System token set**: standardise the cross-platform token vocabulary (Material You / Cupertino / Fluent Design).
- **Hot reload for native**: Compose and SwiftUI both support it natively — surface in `ssc run` for dev experience.
- **Snapshot / screenshot testing**: strategy for View IR snapshot tests and pixel-level screenshot comparisons per renderer.
- **MILESTONES.md integration**: each phase above needs a corresponding milestone entry before implementation starts.
