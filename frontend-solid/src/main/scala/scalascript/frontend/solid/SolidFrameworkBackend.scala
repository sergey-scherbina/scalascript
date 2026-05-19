package scalascript.frontend.solid

import scalascript.frontend.*

/** Solid frontend backend — lowers primitives to idiomatic
 *  SolidJS 1.x:
 *
 *    - `Signal[T]` → `createSignal`
 *    - `Computed[T]` → `createMemo`
 *    - `Effect` → `createEffect`
 *    - `Component[P]` → function (runs ONCE — fine-grained reactivity)
 *    - `View.Show` → `<Show when={...}>`
 *    - `View.For` → `<For each={...}>`
 *
 *  STUB.  v1.18 / Phase A4 ships the real `emit`. */
final class SolidFrameworkBackend extends FrontendFrameworkSpi:

  override def name: String = "solid"

  override def capabilities: Set[Capability] = Set(
    Capability.ComponentTree,
    Capability.SignalState,
    Capability.ComputedDerived,
    Capability.EffectLifecycle,
    Capability.DomRefs,
    Capability.Context,
    Capability.Portals,
    Capability.Untrack
  )

  override def jsDeps: List[JsDep] = List(
    JsDep("solid-js",     "^1.8.0", "solid-js"),
    JsDep("solid-js/web", "^1.8.0", "solid-js/web")
  )

  override def emit(module: FrontendModule): EmittedSpa =
    throw new NotImplementedError(
      "SolidFrameworkBackend.emit — v1.18 / Phase A4."
    )
