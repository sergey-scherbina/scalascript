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
final class ReactiveSignal[T](val jsName: String, initial: T) extends Signal[T]:
  private var _value: T = initial
  override def apply(): T            = _value
  override def set(value: T): Unit   = _value = value

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
   *  `ReactiveSignal[String]`.  Backends generate a subscription
   *  so the DOM text updates whenever the signal is set.  Use
   *  `TextNode` for static / snapshot-only text. */
  final case class SignalText(signal: ReactiveSignal[String]) extends View

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
   *  subtree without re-rendering the parent. */
  final case class Show(
      cond:     () => Boolean,
      whenTrue: () => View,
      whenFalse: () => View = () => Fragment(Nil)
  ) extends View

  /** Repeated sub-trees — `items.map(item => view(item))`.  Same
   *  optimisation opportunity as Show: backends can patch
   *  insertions / deletions / reorders without full re-render. */
  final case class For[T](
      items:  () => Seq[T],
      render: T => View
  ) extends View

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
