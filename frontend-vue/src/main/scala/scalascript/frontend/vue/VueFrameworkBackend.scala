package scalascript.frontend.vue

import scalascript.frontend.*

/** Vue frontend backend — lowers primitives to Vue 3:
 *
 *    - `Signal[T]` → `ref`
 *    - `Computed[T]` → `computed`
 *    - `Effect` → `watchEffect`
 *    - `Component[P]` → `defineComponent({ setup, render })`
 *    - `View.Element` → `h(tag, props, children)` render-function call
 *
 *  STUB.  v1.18 / Phase A5 ships the real `emit`. */
final class VueFrameworkBackend extends FrontendFrameworkSpi:

  override def name: String = "vue"

  override def capabilities: Set[Capability] = Set(
    Capability.ComponentTree,
    Capability.SignalState,
    Capability.ComputedDerived,
    Capability.EffectLifecycle,
    Capability.DomRefs,
    Capability.Context,
    Capability.Portals,
    Capability.Suspense,
    Capability.TwoWayBinding
  )

  override def jsDeps: List[JsDep] = List(
    JsDep("vue", "^3.4.0", "vue")
  )

  override def emit(module: FrontendModule): EmittedSpa =
    throw new NotImplementedError(
      "VueFrameworkBackend.emit — v1.18 / Phase A5."
    )
