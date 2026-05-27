package scalascript.frontend

// ── Reactive primitives ───────────────────────────────────────────────────────

/** Reactive cell — universal primitive across React (`useState`),
 *  Vue (`ref`), Solid (`createSignal`), and the in-house Custom runtime. */
trait Signal[T]:
  def apply(): T
  def set(value: T): Unit
  def update(f: T => T): Unit = set(f(apply()))
  inline def `:=`(value: T): Unit = set(value)

/** Reactive signal with a stable cross-backend identity (`id`) and an
 *  initial value for codegen embedding.
 *
 *  The `id` field was named `jsName` in v0.2; renamed to `id` in v0.3
 *  because it is now the canonical identifier across all backends
 *  (JS, Kotlin, Swift, Scala Native), not just JavaScript. */
class ReactiveSignal[T](val id: String, initial: T) extends Signal[T]:
  private var _value: T = initial
  private val listeners = scala.collection.mutable.LinkedHashMap.empty[Long, T => Unit]
  private var nextListenerId: Long = 0L
  override def apply(): T = synchronized(_value)
  override def set(value: T): Unit =
    val snapshot = synchronized {
      _value = value
      listeners.values.toList
    }
    snapshot.foreach(_(value))

  /** Subscribe to value changes. The callback is invoked after the value is
   *  stored; the returned function removes the subscription. */
  def subscribe(listener: T => Unit): () => Unit =
    val id = synchronized {
      nextListenerId += 1
      listeners(nextListenerId) = listener
      nextListenerId
    }
    () => synchronized { listeners.remove(id); () }

/** Reactive list signal.  Backs `View.ForSignal`.
 *  `id` replaces `jsName` from v0.2 — same naming contract as `ReactiveSignal`. */
final class ReactiveSignalList[T](val id: String, val initial: Seq[T])

/** Memoised derived value. */
trait Computed[T]:
  def apply(): T

/** A side action triggered when its tracked signals change. */
trait Effect:
  def stop(): Unit

// ── Platform ADT ─────────────────────────────────────────────────────────────

enum Platform:
  case Web
  case Desktop(os: DesktopOs = DesktopOs.All)
  case Mobile(os: MobileOs = MobileOs.All)
  case All

enum DesktopOs:
  case MacOS, Windows, Linux, All

enum MobileOs:
  case iOS, Android, All

enum AppFormat:
  case WebSpa
  case ElectronApp
  case SwingApp
  case JavaFxApp
  case ReactNativeBundle
  case ComposeMultiplatform
  case SwiftUIApp
  case ScalaNativeBinary
  case GraalVMNativeImage
  case KotlinAndroidApk

enum CompilationBackend:
  case Js
  case Jvm
  case Kotlin
  case Swift
  case ScalaNative

// ── Style system ──────────────────────────────────────────────────────────────

final case class EdgeInsets(top: Double, right: Double, bottom: Double, left: Double)
object EdgeInsets:
  val zero: EdgeInsets = EdgeInsets(0, 0, 0, 0)
  def all(v: Double): EdgeInsets = EdgeInsets(v, v, v, v)
  def symmetric(h: Double, v: Double): EdgeInsets = EdgeInsets(v, h, v, h)
  def only(top: Double = 0, right: Double = 0, bottom: Double = 0, left: Double = 0): EdgeInsets =
    EdgeInsets(top, right, bottom, left)

enum Dimension:
  case Auto
  case Fixed(value: Double)
  case Fraction(value: Double)
  case Fill

enum Align:
  case Start, Center, End, Stretch, Baseline
enum HAlign:
  case Start, Center, End, Stretch
enum VAlign:
  case Top, Center, Bottom, Stretch
enum Axis:
  case Horizontal, Vertical, Both
enum ContentFit:
  case Contain, Cover, Fill, None
enum Edge:
  case Top, Bottom, Leading, Trailing
object Edge:
  val all: Set[Edge] = Set(Edge.Top, Edge.Bottom, Edge.Leading, Edge.Trailing)

enum GridColumns:
  case Fixed(count: Int)
  case Adaptive(minWidth: Double)
  case Fill

enum FontStyle:
  case Normal, Italic
enum TextAlign:
  case Start, Center, End, Justify
enum TextOverflow:
  case Clip, Ellipsis
enum Overflow:
  case Visible, Hidden, Scroll, Auto
enum BorderLineStyle:
  case Solid, Dashed, Dotted, None
enum TextDecoration:
  case Underline, Strikethrough, Overline

enum FontWeight:
  case Thin, ExtraLight, Light, Regular, Medium, SemiBold, Bold, ExtraBold, Black
  case Custom(value: Int)

enum Cursor:
  case Default, Pointer, Text, Grab, Grabbing, NotAllowed, Crosshair

enum Color:
  case Hex(value: String)
  case Rgb(r: Int, g: Int, b: Int)
  case Rgba(r: Int, g: Int, b: Int, a: Double)
  case Named(name: String)
  /** Cross-platform semantic token — resolves against the ambient Theme.
   *  Canonical tokens: background, surface, foreground, primary, onPrimary,
   *  secondary, onSecondary, error, onError, border, muted, accent. */
  case System(token: String)
  case Transparent

final case class BorderRadius(topLeft: Double, topRight: Double, bottomRight: Double, bottomLeft: Double)
object BorderRadius:
  val zero: BorderRadius = BorderRadius(0, 0, 0, 0)
  def all(r: Double): BorderRadius = BorderRadius(r, r, r, r)

final case class Shadow(
  color:   Color,
  offsetX: Double = 0,
  offsetY: Double = 4,
  blur:    Double = 8,
  spread:  Double = 0
)

enum Transform:
  case Rotate(degrees: Double)
  case Scale(x: Double, y: Double)
  case Translate(x: Double, y: Double)
  case SkewX(degrees: Double)
  case SkewY(degrees: Double)

final case class Transition(
  curve:    Curve  = Curve.EaseInOut,
  duration: Double = 300,
  delay:    Double = 0
)

enum Curve:
  case Linear
  case EaseIn
  case EaseOut
  case EaseInOut
  case Spring(stiffness: Double = 300, damping: Double = 30)

enum SwipeDirection:
  case Left, Right, Up, Down, Horizontal, Vertical, Any

enum Gesture:
  case Tap(handler: EventHandler)
  case LongPress(handler: EventHandler, minDuration: Double = 500)
  case Swipe(direction: SwipeDirection, handler: EventHandler)
  case Drag(handler: EventHandler)

enum A11yRole:
  case Button, Link, Image, Heading, TextField, List, ListItem
  case Switch, Slider, Tab, TabList, Alert, Dialog, None

enum A11yTrait:
  case Selected, Disabled, Bold, Italic, Placeholder, SearchField, StartsMediaSession

enum LiveRegion:
  case Polite, Assertive

final case class A11y(
  label:      Option[String]     = None,
  hint:       Option[String]     = None,
  role:       Option[A11yRole]   = None,
  traits:     Set[A11yTrait]     = Set.empty,
  focusable:  Option[Boolean]    = None,
  focusOrder: Option[Int]        = None,
  liveRegion: Option[LiveRegion] = None
)

final case class LayoutStyle(
  padding:    EdgeInsets        = EdgeInsets.zero,
  margin:     EdgeInsets        = EdgeInsets.zero,
  width:      Dimension         = Dimension.Auto,
  height:     Dimension         = Dimension.Auto,
  minWidth:   Option[Double]    = None,
  maxWidth:   Option[Double]    = None,
  minHeight:  Option[Double]    = None,
  maxHeight:  Option[Double]    = None,
  flex:       Option[Double]    = None,
  flexShrink: Option[Double]    = None,
  flexBasis:  Option[Dimension] = None,
  alignSelf:  Option[Align]     = None,
  gap:        Option[Double]    = None,
  zIndex:     Option[Int]       = None
)

final case class TextStyle(
  fontSize:       Option[Double]      = None,
  fontWeight:     Option[FontWeight]  = None,
  fontFamily:     Option[String]      = None,
  fontStyle:      FontStyle           = FontStyle.Normal,
  lineHeight:     Option[Double]      = None,
  letterSpacing:  Option[Double]      = None,
  textDecoration: Set[TextDecoration] = Set.empty,
  textAlign:      TextAlign           = TextAlign.Start,
  textOverflow:   TextOverflow        = TextOverflow.Clip,
  maxLines:       Option[Int]         = None,
  foreground:     Option[Color]       = None
)

final case class DecorationStyle(
  background:   Option[Color]   = None,
  borderColor:  Option[Color]   = None,
  borderWidth:  Option[Double]  = None,
  borderRadius: BorderRadius    = BorderRadius.zero,
  borderStyle:  BorderLineStyle = BorderLineStyle.Solid
)

final case class EffectsStyle(
  shadow:   Option[Shadow] = None,
  opacity:  Double         = 1.0,
  overflow: Overflow       = Overflow.Visible,
  cursor:   Option[Cursor] = None
)

final case class Style(
  layout:     LayoutStyle             = LayoutStyle(),
  text:       TextStyle               = TextStyle(),
  decoration: DecorationStyle         = DecorationStyle(),
  effects:    EffectsStyle            = EffectsStyle(),
  transform:  List[Transform]         = Nil,
  animation:  Option[Transition]      = None,
  gestures:   List[Gesture]           = Nil,
  a11y:       A11y                    = A11y(),
  web:        Map[String, String]     = Map.empty,
  native:     Map[String, String]     = Map.empty
)
object Style:
  val empty: Style = Style()

// ── Supporting View types ─────────────────────────────────────────────────────

final case class Tab(label: String, icon: Option[String], content: View[?])
enum ButtonRole:
  case Default, Cancel, Destructive
final case class AlertButton(label: String, action: () => Unit, role: ButtonRole = ButtonRole.Default)

enum ImageSource:
  case Url(href: String)
  case Asset(name: String)
  case Base64(data: String, mime: String)

// ── View IR ───────────────────────────────────────────────────────────────────

/** Unified View IR.  `A` carries the typed witness for cases that produce
 *  a value (e.g. `Picker[T]`, `For[T]`); use `View[?]` when the type
 *  parameter is irrelevant.
 *
 *  Migrated from `sealed trait View` in v0.3.  Call sites that accepted
 *  `View` should be updated to `View[?]`. */
enum View[+A]:

  // ── Layout ─────────────────────────────────────────────────────────────────
  case Column(children: Seq[View[?]], spacing: Double = 0, align: HAlign = HAlign.Start, style: Style = Style()) extends View[Nothing]
  case Row(children: Seq[View[?]], spacing: Double = 0, align: VAlign = VAlign.Center, style: Style = Style()) extends View[Nothing]
  case Stack(children: Seq[View[?]], style: Style = Style()) extends View[Nothing]
  case ScrollView(child: View[?], axis: Axis = Axis.Vertical, style: Style = Style()) extends View[Nothing]
  case Spacer(size: Option[Double] = None) extends View[Nothing]
  case Divider(axis: Axis = Axis.Horizontal, style: Style = Style()) extends View[Nothing]

  // ── Content ─────────────────────────────────────────────────────────────────
  case Text(content: () => String, style: Style = Style()) extends View[Nothing]
  case SignalText(signal: ReactiveSignal[?], style: Style = Style()) extends View[Nothing]
  case Image(source: ImageSource, style: Style = Style()) extends View[Nothing]
  case Icon(name: String, style: Style = Style()) extends View[Nothing]

  // ── Controls ────────────────────────────────────────────────────────────────
  case Button(label: View[?], action: EventHandler, enabled: () => Boolean = () => true, style: Style = Style()) extends View[Nothing]
  case TextInput(value: ReactiveSignal[String], placeholder: String = "", multiline: Boolean = false, secure: Boolean = false, style: Style = Style()) extends View[Nothing]
  case Toggle(checked: ReactiveSignal[Boolean], label: String = "", style: Style = Style()) extends View[Nothing]
  case Slider(value: ReactiveSignal[Double], min: Double, max: Double, step: Double = 1.0, style: Style = Style()) extends View[Nothing]
  case Picker[T](options: Seq[(String, T)], selected: ReactiveSignal[T], placeholder: String = "", style: Style = Style()) extends View[T]

  // ── Virtualized lists ───────────────────────────────────────────────────────
  case LazyList[T](items: () => Seq[T], render: T => View[?], itemHeight: Option[Double] = None, style: Style = Style()) extends View[T]
  case LazyGrid[T](items: () => Seq[T], render: T => View[?], columns: GridColumns = GridColumns.Fixed(2), spacing: Double = 8, style: Style = Style()) extends View[T]

  // ── Navigation ──────────────────────────────────────────────────────────────
  case TabBar(tabs: Seq[Tab], current: ReactiveSignal[Int], style: Style = Style()) extends View[Nothing]
  case NavigationStack(routes: Map[String, () => View[?]], current: ReactiveSignal[String], style: Style = Style()) extends View[Nothing]

  // ── Overlays ────────────────────────────────────────────────────────────────
  case Sheet(content: View[?], isPresented: ReactiveSignal[Boolean]) extends View[Nothing]
  case AlertDialog(title: String, message: String, buttons: Seq[AlertButton], isPresented: ReactiveSignal[Boolean]) extends View[Nothing]

  // ── Forms ───────────────────────────────────────────────────────────────────
  case Form(child: View[?], onSubmit: EventHandler, style: Style = Style()) extends View[Nothing]
  case FormField[T](label: String, value: ReactiveSignal[T], validate: T => Option[String] = (_: T) => None, style: Style = Style()) extends View[T]

  // ── Mobile / platform UX ────────────────────────────────────────────────────
  case SafeArea(child: View[?], edges: Set[Edge] = Edge.all) extends View[Nothing]
  case KeyboardAvoiding(child: View[?]) extends View[Nothing]

  // ── Animations ──────────────────────────────────────────────────────────────
  case Animated(child: View[?], transition: Transition, style: Style = Style()) extends View[Nothing]

  // ── Reactivity ──────────────────────────────────────────────────────────────
  case Fragment(children: Seq[View[?]]) extends View[Nothing]
  case ComponentInstance[P](component: Component[P], props: P) extends View[P]
  case ShowSignal(cond: ReactiveSignal[Boolean], whenTrue: View[?], whenFalse: View[?] = Fragment(Nil)) extends View[Nothing]
  case Show(cond: () => Boolean, whenTrue: () => View[?], whenFalse: () => View[?] = () => Fragment(Nil)) extends View[Nothing]
  case ForSignal[T](items: ReactiveSignalList[T], tag: String = "li", attrs: Map[String, AttrValue] = Map.empty, itemTemplate: Option[View[?]] = None) extends View[T]
  case For[T](items: () => Seq[T], render: T => View[?]) extends View[T]
  case ItemText extends View[Nothing]

  // ── Style wrapper ───────────────────────────────────────────────────────────
  case Styled(child: View[?], style: Style) extends View[Nothing]

  // ── Platform-adaptive escape hatch ──────────────────────────────────────────
  case Adaptive(web: Option[View[?]] = None, desktop: Option[View[?]] = None, mobile: Option[View[?]] = None, fallback: View[?] = Fragment(Nil)) extends View[Nothing]

  // ── Web-only cases ───────────────────────────────────────────────────────────
  // Codegen contract: using these in a non-web target build emits a compile error.

  /** Raw HTML element.  Compile error on non-web targets. */
  case Element(tag: String, attrs: Map[String, AttrValue], events: Map[String, EventHandler], children: Seq[View[?]]) extends View[Nothing]

  /** DOM portal.  Compile error on non-web targets. */
  case Portal(target: String, children: Seq[View[?]]) extends View[Nothing]

  /** Reactive data table backed by a REST endpoint.
   *  @deprecated Use the `fetchTable` toolkit helper instead; this case will be removed in P2
   *  when web renderers are updated to handle the new semantic View cases. */
  @deprecated("Use the fetchTable toolkit helper; View.FetchTable will be removed in P2.", "v0.3")
  case FetchTable(tableId: String, fetchUrl: String, deleteUrl: String, tick: ReactiveSignal[Int]) extends View[Nothing]

  /** Static text node — internal use by web renderers.  Produced by the
   *  toolkit lowering pass; app code should use `View.Text` instead. */
  case TextNode(value: () => String) extends View[Nothing]

// ── View modifier DSL ─────────────────────────────────────────────────────────

extension (v: View[?])
  // Layout
  def padding(all: Double): View[?]             = styled(s => s.copy(layout = s.layout.copy(padding = EdgeInsets.all(all))))
  def padding(h: Double, vert: Double): View[?] = styled(s => s.copy(layout = s.layout.copy(padding = EdgeInsets.symmetric(h, vert))))
  def padding(e: EdgeInsets): View[?]           = styled(s => s.copy(layout = s.layout.copy(padding = e)))
  def margin(all: Double): View[?]              = styled(s => s.copy(layout = s.layout.copy(margin = EdgeInsets.all(all))))
  def margin(e: EdgeInsets): View[?]            = styled(s => s.copy(layout = s.layout.copy(margin = e)))
  def frame(w: Double, h: Double): View[?]      = styled(s => s.copy(layout = s.layout.copy(width = Dimension.Fixed(w), height = Dimension.Fixed(h))))
  def width(d: Dimension): View[?]              = styled(s => s.copy(layout = s.layout.copy(width = d)))
  def height(d: Dimension): View[?]             = styled(s => s.copy(layout = s.layout.copy(height = d)))
  def fill: View[?]                             = styled(s => s.copy(layout = s.layout.copy(width = Dimension.Fill, height = Dimension.Fill)))
  def fillWidth: View[?]                        = styled(s => s.copy(layout = s.layout.copy(width = Dimension.Fill)))
  def fillHeight: View[?]                       = styled(s => s.copy(layout = s.layout.copy(height = Dimension.Fill)))
  def flex(factor: Double = 1): View[?]         = styled(s => s.copy(layout = s.layout.copy(flex = Some(factor))))
  def minWidth(v2: Double): View[?]             = styled(s => s.copy(layout = s.layout.copy(minWidth = Some(v2))))
  def maxWidth(v2: Double): View[?]             = styled(s => s.copy(layout = s.layout.copy(maxWidth = Some(v2))))
  def zIndex(z: Int): View[?]                   = styled(s => s.copy(layout = s.layout.copy(zIndex = Some(z))))

  // Color
  def background(c: Color): View[?] = styled(s => s.copy(decoration = s.decoration.copy(background = Some(c))))
  def foreground(c: Color): View[?] = styled(s => s.copy(text = s.text.copy(foreground = Some(c))))

  // Typography
  def fontSize(sz: Double): View[?]    = styled(s => s.copy(text = s.text.copy(fontSize = Some(sz))))
  def fontWeight(w: FontWeight): View[?] = styled(s => s.copy(text = s.text.copy(fontWeight = Some(w))))
  def bold: View[?]                    = fontWeight(FontWeight.Bold)
  def light: View[?]                   = fontWeight(FontWeight.Light)
  def italic: View[?]                  = styled(s => s.copy(text = s.text.copy(fontStyle = FontStyle.Italic)))
  def underline: View[?]               = styled(s => s.copy(text = s.text.copy(textDecoration = s.text.textDecoration + TextDecoration.Underline)))
  def strikethrough: View[?]           = styled(s => s.copy(text = s.text.copy(textDecoration = s.text.textDecoration + TextDecoration.Strikethrough)))
  def textAlign(a: TextAlign): View[?] = styled(s => s.copy(text = s.text.copy(textAlign = a)))
  def lineLimit(n: Int): View[?]       = styled(s => s.copy(text = s.text.copy(maxLines = Some(n))))

  // Border / shape
  def border(color: Color, width: Double = 1): View[?] = styled(s => s.copy(decoration = s.decoration.copy(borderColor = Some(color), borderWidth = Some(width))))
  def cornerRadius(r: Double): View[?]                  = styled(s => s.copy(decoration = s.decoration.copy(borderRadius = BorderRadius.all(r))))

  // Shadow / effects
  def shadow(sh: Shadow): View[?]                                                         = styled(s => s.copy(effects = s.effects.copy(shadow = Some(sh))))
  def shadow(blur: Double = 8, color: Color = Color.Rgba(0, 0, 0, 0.15)): View[?]        = styled(s => s.copy(effects = s.effects.copy(shadow = Some(Shadow(color, blur = blur)))))
  def opacity(v2: Double): View[?]                                                         = styled(s => s.copy(effects = s.effects.copy(opacity = v2)))
  def clip: View[?]                                                                        = styled(s => s.copy(effects = s.effects.copy(overflow = Overflow.Hidden)))

  // Transform
  def rotate(deg: Double): View[?]              = appendTransform(Transform.Rotate(deg))
  def scale(f: Double): View[?]                 = appendTransform(Transform.Scale(f, f))
  def scale(x: Double, y: Double): View[?]      = appendTransform(Transform.Scale(x, y))
  def translate(x: Double, y: Double): View[?]  = appendTransform(Transform.Translate(x, y))

  // Animation
  def animated(t: Transition = Transition()): View[?]                 = styled(_.copy(animation = Some(t)))
  def transition(t: Transition): View[?] = View.Animated(v, t)

  // Gestures
  def onTap(h: EventHandler): View[?]                          = gesture(Gesture.Tap(h))
  def onLongPress(h: EventHandler, ms: Double = 500): View[?]  = gesture(Gesture.LongPress(h, ms))
  def onSwipe(dir: SwipeDirection, h: EventHandler): View[?]   = gesture(Gesture.Swipe(dir, h))

  // Accessibility
  def accessibilityLabel(l: String): View[?]  = styled(s => s.copy(a11y = s.a11y.copy(label = Some(l))))
  def accessibilityHint(h: String): View[?]   = styled(s => s.copy(a11y = s.a11y.copy(hint = Some(h))))
  def accessibilityRole(r: A11yRole): View[?] = styled(s => s.copy(a11y = s.a11y.copy(role = Some(r))))
  def focusOrder(n: Int): View[?]             = styled(s => s.copy(a11y = s.a11y.copy(focusOrder = Some(n))))

  // Platform overrides
  def css(prop: String, value: String): View[?]        = styled(s => s.copy(web = s.web + (prop -> value)))
  def nativeHint(key: String, value: String): View[?]  = styled(s => s.copy(native = s.native + (key -> value)))

  private def styled(f: Style => Style): View[?] = v match
    case View.Styled(child, s) => View.Styled(child, f(s))
    case other                 => View.Styled(other, f(Style()))

  private def appendTransform(t: Transform): View[?] = v match
    case View.Styled(child, s) => View.Styled(child, s.copy(transform = s.transform :+ t))
    case other                 => View.Styled(other, Style(transform = List(t)))

  private def gesture(g: Gesture): View[?] = v match
    case View.Styled(child, s) => View.Styled(child, s.copy(gestures = s.gestures :+ g))
    case other                 => View.Styled(other, Style(gestures = List(g)))

// ── Attribute values ──────────────────────────────────────────────────────────

sealed trait AttrValue
object AttrValue:
  final case class Str(value: String)                  extends AttrValue
  final case class Bool(value: Boolean)                extends AttrValue
  final case class Num(value: Double)                  extends AttrValue
  final case class Dynamic[T](read: () => T)           extends AttrValue
  case object Absent                                   extends AttrValue
  final case class RefBinding(ref: WidgetRef)          extends AttrValue
  final case class Reactive(signal: ReactiveSignal[?]) extends AttrValue

// ── Event handlers ────────────────────────────────────────────────────────────

sealed trait EventHandler
object EventHandler:
  final case class Simple(action: () => Unit)                                                                         extends EventHandler
  final case class WithEvent(action: Any => Unit)                                                                     extends EventHandler
  final case class SetSignalLiteral(signal: ReactiveSignal[?], value: Any)                                            extends EventHandler
  final case class IncrementSignal(signal: ReactiveSignal[Int], by: Int = 1)                                          extends EventHandler
  final case class ToggleSignal(signal: ReactiveSignal[Boolean])                                                      extends EventHandler
  final case class PushSignalLiteral[T](list: ReactiveSignalList[T], value: T)                                        extends EventHandler
  final case class ClearSignalList[T](list: ReactiveSignalList[T])                                                    extends EventHandler
  final case class RemoveSelfFromList[T](list: ReactiveSignalList[T])                                                 extends EventHandler
  final case class InputChange(signal: ReactiveSignal[String])                                                        extends EventHandler
  final case class FetchAction(method: String, url: String, body: ReactiveSignal[String],
                               onSuccessTick: ReactiveSignal[Int], clearBody: Boolean = false)                        extends EventHandler

// ── Reactive URL signal ───────────────────────────────────────────────────────

/** Signal backed by a REST GET fetch.
 *  `id` and `tickId` replace `jsName` / `tickJsName` from v0.2. */
final class FetchUrlSignal(
    id2:          String,
    val fetchUrl: String,
    val tickId:   String
) extends ReactiveSignal[String](id2, "")

// ── Widget ref (formerly DomRef) ──────────────────────────────────────────────

/** Handle on a rendered element that platform code can access after mount.
 *  Renamed from `DomRef` in v0.3 — `WidgetRef` is backend-agnostic
 *  (works on web DOM, Compose, SwiftUI, GTK, etc.). */
final class WidgetRef(val id: String)

/** Backward-compat alias — `DomRef` is deprecated, use `WidgetRef`. */
@deprecated("Use WidgetRef instead of DomRef — renamed in v0.3 for cross-backend clarity.", "v0.3")
type DomRef = WidgetRef

// ── Component ─────────────────────────────────────────────────────────────────

trait Component[P]:
  def render(props: P): View[?]

// ── Capabilities ──────────────────────────────────────────────────────────────

enum Capability:
  // Universal — every backend supports these
  case ComponentTree
  case SignalState
  case ComputedDerived
  case EffectLifecycle
  // Web extensions
  case DomRefs
  case Context
  case Portals
  case Suspense
  case Untrack
  case TwoWayBinding
  // Platform feature capabilities — on native targets gate manifest permission injection;
  // on web these are informational only (browser enforces its own permission model).
  case Camera
  case Biometrics
  case PushNotifications
  case LocalStorage
  case Geolocation
  case Haptics
  case DeepLinks
  case BackgroundTasks
  case FileSystem
