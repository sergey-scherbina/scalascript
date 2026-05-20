package scalascript.frontend

/** Reactive cell — universal primitive across React (`useState`),
 *  Vue (`ref`), Solid (`createSignal`), Svelte (`let`), and the
 *  in-house Custom runtime.  See `docs/frontend-abstract-model.md`.
 *
 *  Backends implement reads as either "snapshot of current value"
 *  (React — re-render on change) or "subscription" (Solid — only
 *  affected DOM nodes update); either way the user-code surface is
 *  the same: `count()` to read, `count := v` (or `count.set(v)`) to
 *  write. */
trait Signal[T]:
  /** Read the current value AND, if inside a reactive context
   *  (component render / computed body / effect body), subscribe
   *  that context to changes of this signal. */
  def apply(): T

  /** Replace the current value, notifying subscribers. */
  def set(value: T): Unit

  /** Apply a function — atomic in implementations that batch. */
  def update(f: T => T): Unit = set(f(apply()))

  /** Syntactic sugar — `signal := 42` is `signal.set(42)`. */
  inline def `:=`(value: T): Unit = set(value)

/** v1.18 / Phase A2b — minimal **reactive** signal cell that
 *  backends can wire to a JS-side observable.  Sits alongside the
 *  abstract `Signal[T]` trait because the trait alone is not enough
 *  for emit-time codegen: emit needs (a) a stable, JS-safe **name**
 *  to refer to the cell across the bundle and (b) the **initial
 *  value** to embed.
 *
 *  Naming contract: `jsName` becomes the value-cell variable
 *  (`__sig_<jsName>_value`) and the subscriber-Set
 *  (`__sig_<jsName>_subs`) in the emitted JS.  Names must match
 *  `[A-Za-z_][A-Za-z0-9_]*`; the emitter validates this and throws
 *  if not.  Two `ReactiveSignal`s with the same name collide — one
 *  ID per signal across a module is the user's responsibility.
 *
 *  Phase A2b restricts the value type to a primitive JSON literal
 *  (`String | Int | Long | Double | Boolean`) so the initial value
 *  can be embedded into JS without an `upickle`-style serialiser.
 *  Phase A3+ will broaden once we have a portable encoder. */
class ReactiveSignal[T](val jsName: String, initial: T) extends Signal[T]:
  private var _value: T = initial
  override def apply(): T            = _value
  override def set(value: T): Unit   = _value = value

/** v1.18 / Phase A2e — reactive **list** signal.  Backs
 *  `View.ForSignal` and the list-mutation event handlers
 *  (`PushSignalLiteral`, `ClearSignalList`).  Same naming
 *  contract as `ReactiveSignal`: `jsName` becomes the JS-side
 *  identifier the emitter uses for the list cell + its
 *  subscriber set.
 *
 *  Scope for A2e.1: `T` is restricted to a primitive JSON
 *  literal (`String | Int | Long | Double | Boolean`) so the
 *  initial list can be embedded into JS without a portable
 *  serialiser.  Later phases may widen. */
final class ReactiveSignalList[T](val jsName: String, val initial: Seq[T])

/** Memoised derived value.  Re-derived only when one of the
 *  signals it reads from changes.  Maps to React `useMemo`,
 *  Vue `computed`, Solid `createMemo`, Svelte `$:`. */
trait Computed[T]:
  /** Read the current cached value; subscribes the current
   *  reactive context the same way `Signal[T].apply` does. */
  def apply(): T

/** A side action triggered when its tracked signals change.
 *  Maps to React `useEffect`, Vue `watchEffect`, Solid
 *  `createEffect`.  May return a cleanup function that runs
 *  before the next re-execution or on `stop()`. */
trait Effect:
  /** Cancel the effect — runs its current cleanup (if any) and
   *  unsubscribes from all tracked signals.  Idempotent. */
  def stop(): Unit

/** Tree of UI nodes.  Maps to JSX (React, Solid), templates
 *  (Vue, Svelte), or our own DSL builders. */
sealed trait View

object View:
  /** A real DOM element — `div`, `button`, `span`, ... */
  final case class Element(
      tag:      String,
      attrs:    Map[String, AttrValue],
      events:   Map[String, EventHandler],
      children: Seq[View]
  ) extends View

  /** A text node.  `value` is a thunk so backends that support
   *  fine-grained subscriptions (Solid / Custom) can re-evaluate
   *  it on signal change without re-rendering the whole parent.
   *  Pull-based backends (React) call it once per render. */
  final case class TextNode(value: () => String) extends View

  /** v1.18 / Phase A2b — text node whose value is bound to a
   *  reactive signal.  Any `ReactiveSignal[?]` works — the
   *  backend stringifies on read (JS `textContent =` coerces
   *  numbers / booleans automatically).  Use `TextNode` for
   *  static / snapshot-only text. */
  final case class SignalText(signal: ReactiveSignal[?]) extends View

  /** Logical grouping with no DOM wrapper.  Like React `<>...</>`
   *  or Vue / Solid Fragment. */
  final case class Fragment(children: Seq[View]) extends View

  /** A component invocation.  Backends instantiate the component
   *  with the props and render its body in their own framework
   *  semantics. */
  final case class ComponentInstance[P](
      component: Component[P],
      props:     P
  ) extends View

  /** Conditional sub-tree — `if (cond()) view else otherView`.
   *  Backends with fine-grained subscriptions can swap the
   *  subtree without re-rendering the parent.
   *
   *  **Snapshot semantics.**  `cond` is evaluated at emit time;
   *  subsequent signal changes do NOT swap the rendered subtree.
   *  Use `ShowSignal` for reactive show/hide. */
  final case class Show(
      cond:     () => Boolean,
      whenTrue: () => View,
      whenFalse: () => View = () => Fragment(Nil)
  ) extends View

  /** v1.18 / Phase A2d — reactive conditional sub-tree.  `cond`
   *  is a `ReactiveSignal[Boolean]`; backends generate a
   *  subscription so the subtree swaps whenever the signal
   *  changes.
   *
   *  Lowerings per backend:
   *    - Custom: subscribe-and-swap via `__ssc_signals[...].subs`
   *    - React : ternary inside `render()` — useState change
   *              triggers component re-render
   *    - Solid : `createEffect` that swaps DOM children
   *    - Vue   : ternary inside `render()` — proxy change
   *              triggers component re-render */
  final case class ShowSignal(
      cond:      ReactiveSignal[Boolean],
      whenTrue:  View,
      whenFalse: View = Fragment(Nil)
  ) extends View

  /** Repeated sub-trees — `items.map(item => view(item))`.  Same
   *  optimisation opportunity as Show: backends can patch
   *  insertions / deletions / reorders without full re-render. */
  final case class For[T](
      items:  () => Seq[T],
      render: T => View
  ) extends View

  /** v1.18 / Phase A2e — reactive **repeated** sub-trees backed
   *  by a `ReactiveSignalList[T]`.  Backends subscribe to the
   *  list signal and re-render children when it changes.
   *
   *  Scope: each item is rendered as
   *  `<tag attrs>${String(item)}</tag>` — enough for the canonical
   *  todo-list demo.  Richer per-item templates (nested elements,
   *  per-item events) need either compile-time inlining of a
   *  `T => View` JS template or a runtime view DSL, both of which
   *  are deferred to a follow-up phase.
   *
   *  Lowerings per backend:
   *    - Custom: wrapper span + subscription that wipes-and-rebuilds
   *      children on each list change (no keyed reconciliation yet)
   *    - React : `array.map(item => createElement(tag, { key }, item))`
   *      inside `render()`; React's diff handles reuse
   *    - Solid : `createEffect` that wipes-and-rebuilds (no native
   *      `<For>` because solid-js/h is broken in this project; same
   *      story as `SignalText`)
   *    - Vue   : `this.<jsName>.map(item => h(tag, { key }, item))`
   *      inside `render()` arrow */
  final case class ForSignal[T](
      items: ReactiveSignalList[T],
      tag:   String                  = "li",
      attrs: Map[String, AttrValue]  = Map.empty,
      // v1.18 / Phase A2e.2 — optional richer per-item template.
      // When `None`, the simple `<tag attrs>String(item)</tag>` shape
      // (A2e) is emitted; when `Some(template)`, the backend walks
      // the template and replaces every `View.ItemText` /
      // `EventHandler.RemoveSelfFromList` hole with code that reads
      // the iteration value / index in the emitted JS.  `tag` + `attrs`
      // are IGNORED when `itemTemplate` is set — the template's root
      // element fully determines per-item shape.
      itemTemplate: Option[View]     = None
  ) extends View

  /** v1.18 / Phase A2e.2 — placeholder for the iteration value
   *  inside a `ForSignal.itemTemplate`.  Each backend replaces it
   *  with `String(<iteration-variable>)` when it walks the template
   *  at emit time.  Outside a `ForSignal` template, this is a static
   *  empty string (backends emit a literal "" — no runtime error,
   *  just a no-op so misuse is graceful instead of crashing). */
  case object ItemText extends View

  /** v1.18 / Phase A6 — render a subtree into a different DOM
   *  location instead of the current parent.  Canonical use is
   *  modal layers, tooltips, toasts — UI that visually belongs
   *  outside its logical parent's DOM tree.
   *
   *  `target` is a DOM selector (`#modal-root`, `body`, ...) — the
   *  emitter does NOT validate it at compile time; the runtime
   *  fails loudly if the element is missing.
   *
   *  Per-backend lowerings:
   *    - Custom: `document.querySelector(target).appendChild(...)`
   *      instead of appending to the local parent (imperative DOM,
   *      matches Custom's existing style)
   *    - React : wraps the rendered children in
   *      `ReactDOM.createPortal(child, document.querySelector(target))`
   *    - Solid : imperative `document.querySelector(target).appendChild(...)`
   *      (matches Solid's hand-written-imperative pattern; we don't
   *      wire `solid-js/web`'s `<Portal>` because it requires JSX)
   *    - Vue   : `h(Teleport, { to: target }, [...children])` */
  final case class Portal(target: String, children: Seq[View]) extends View

/** v1.18 / Phase A6 — a handle on a DOM element that user JS
 *  code can read after mount.  Each backend wires the ref so that
 *  after the element is mounted, the JS-side variable named
 *  `jsName` holds the underlying DOM node.
 *
 *  Naming contract: `jsName` must match `[A-Za-z_][A-Za-z0-9_]*`
 *  and is the exact identifier used in the emitted JS — pick a
 *  name you'll reference from your imperative JS (focus, measure,
 *  third-party-lib integration, ...).
 *
 *  Per-backend lowerings:
 *    - Custom: emits `let <jsName>;` at top of `mount()` and
 *      assigns `<jsName> = element;` right after `createElement`
 *    - React : emits `const <jsName> = React.useRef(null);`
 *      hoisted at the top of `App()` and passes `ref: <jsName>`
 *      in the element's `createElement` props.  Access the node
 *      via `<jsName>.current` (idiomatic React).
 *    - Solid : same imperative-DOM style as Custom (`let <jsName>;`
 *      + `<jsName> = el;` after createElement) — matches Solid's
 *      hand-written JSX-free output
 *    - Vue   : emits `const <jsName> = ref(null);` in setup() and
 *      passes `ref: <jsName>` in the `h()` props; access via
 *      `<jsName>.value` */
final class DomRef(val jsName: String)

/** Attribute value on a DOM element.  String / Boolean / Int for
 *  literals; `() => …` thunks for dynamic interpolation that
 *  participates in the reactivity system. */
sealed trait AttrValue
object AttrValue:
  final case class Str(value: String)                  extends AttrValue
  final case class Bool(value: Boolean)                extends AttrValue
  final case class Num(value: Double)                  extends AttrValue
  final case class Dynamic[T](read: () => T)           extends AttrValue
  case object Absent                                   extends AttrValue

  /** v1.18 / Phase A6 — bind a `DomRef` to this element.  The
   *  attribute key is conventionally `"ref"` but the emitter
   *  doesn't enforce that; only the `RefBinding` shape matters.
   *  Multiple `RefBinding`s on the same element bind the same
   *  underlying node to multiple refs (each gets the assignment). */
  final case class RefBinding(ref: DomRef)             extends AttrValue

  /** Reactive attribute — emits the signal's JS variable name so
   *  React/Vue re-evaluates the prop on every state change.  Use for
   *  `checked`/`value` bindings that must round-trip with signal state.
   *  Non-reactive backends fall back to snapshotting the initial value. */
  final case class Reactive(signal: ReactiveSignal[?])  extends AttrValue

/** An event handler — `() => Unit` for the simple case, with
 *  optional `Event` argument for keyboard / mouse / form events
 *  that need the raw `Event`. */
sealed trait EventHandler
object EventHandler:
  /** Plain `() => Unit`.  By far the common case (e.g. button
   *  click that bumps a counter). */
  final case class Simple(action: () => Unit) extends EventHandler

  /** Receives the framework-native event object as `Any` (raw
   *  pass-through; the user code casts if it needs specific
   *  fields like `e.target.value`). */
  final case class WithEvent(action: Any => Unit) extends EventHandler

  /** v1.18 / Phase A2c — set a reactive signal to a literal
   *  value on the event.  Translatable to JS: emit generates an
   *  `addEventListener` that calls `__setSignal(name, value)`.
   *  The value's runtime type must match one of the backend's
   *  supported JS-literal types (String / Int / Long / Double /
   *  Boolean for the custom backend). */
  final case class SetSignalLiteral(signal: ReactiveSignal[?], value: Any) extends EventHandler

  /** v1.18 / Phase A2c — increment a numeric reactive signal by
   *  `by` (default 1).  Canonical counter wiring.  Translatable
   *  to JS: emit generates an `addEventListener` that reads the
   *  current cell value, adds `by`, and calls `__setSignal`. */
  final case class IncrementSignal(signal: ReactiveSignal[Int], by: Int = 1) extends EventHandler

  /** v1.18 / Phase A2d — flip a Boolean reactive signal.  Pairs
   *  with `ShowSignal` for click-to-toggle / show-hide UIs. */
  final case class ToggleSignal(signal: ReactiveSignal[Boolean]) extends EventHandler

  /** v1.18 / Phase A2e — append a literal value to the end of a
   *  reactive list signal.  Canonical "add todo" wiring.  Value's
   *  runtime type must match one of the JS-literal types
   *  (String / Int / Long / Double / Boolean). */
  final case class PushSignalLiteral[T](list: ReactiveSignalList[T], value: T) extends EventHandler

  /** v1.18 / Phase A2e — replace a reactive list signal's value
   *  with the empty list.  Canonical "clear all" wiring. */
  final case class ClearSignalList[T](list: ReactiveSignalList[T]) extends EventHandler

  /** v1.18 / Phase A2e.2 — remove the current iteration's entry
   *  from a reactive list.  Only meaningful inside a
   *  `ForSignal.itemTemplate`; backends use the iteration index
   *  they're tracking anyway (React `array.map(item, index)`,
   *  Custom + Solid `let i = 0; for (...) { i++ }`, Vue same).
   *  Outside an item template the handler emits an inert
   *  `addEventListener` that does nothing — graceful no-op rather
   *  than an emit-time crash. */
  final case class RemoveSelfFromList[T](list: ReactiveSignalList[T]) extends EventHandler

  /** Text-input change handler — keeps a `ReactiveSignal[String]` in sync
   *  with a text input on every keystroke.
   *  React emitter: `'onChange': (e) => setter(e.target.value)`.
   *  Non-React backends treat it as an untranslatable JVM closure. */
  final case class InputChange(signal: ReactiveSignal[String]) extends EventHandler

  /** REST fetch on click: POST/PUT/DELETE `url` with `body` signal value as request body,
   *  then increment `onSuccessTick` to trigger dependent `FetchUrlSignal` re-fetches. */
  final case class FetchAction(
    method:        String,
    url:           String,
    body:          ReactiveSignal[String],
    onSuccessTick: ReactiveSignal[Int]
  ) extends EventHandler

/** Signal backed by a REST GET fetch.  React emitter issues `fetch(fetchUrl)` on mount
 *  and re-fetches whenever the `tickJsName` signal increments. */
final class FetchUrlSignal(
    jsName:         String,
    val fetchUrl:   String,
    val tickJsName: String
) extends ReactiveSignal[String](jsName, "")

/** Composable UI unit — a function from props to a View that
 *  may close over signals + effects.  Backends interpret the
 *  body differently:
 *
 *    - React: re-runs on every state change (signals lower to
 *      `useState`; the whole body re-executes).
 *    - Solid: runs ONCE; signal subscriptions wire DOM nodes
 *      directly; the function body doesn't re-execute.
 *    - Vue: similar to React but with proxy-based dep tracking.
 *    - Custom: signal subscriptions trigger fine-grained patches;
 *      function body runs once.
 *
 *  User code is the same across all backends; the semantic
 *  difference of "does my closure see live state or render-time
 *  state" is backend-specific.  See `docs/frontend-abstract-model.md`
 *  "Semantic gotchas" for the honest list. */
trait Component[P]:
  def render(props: P): View

/** Capability flag — used by feature-gating in the lowering pass
 *  + by backends to declare what they support.  Mirrors the
 *  pattern from `scalascript.server.spi.Capability`.
 *
 *  Backends declare via `FrontendFrameworkSpi.capabilities`;
 *  user code that uses an unsupported capability fails at compile
 *  time with a pointer to which backends DO support it. */
enum Capability:
  /** Universal — every backend supports these. */
  case ComponentTree
  case SignalState
  case ComputedDerived
  case EffectLifecycle
  /** Extensions — most production backends support; Custom may
   *  approximate. */
  case DomRefs
  case Context
  case Portals
  case Suspense
  case Untrack
  case TwoWayBinding
