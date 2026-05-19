package scalascript.frontend.react

import scalascript.frontend.*

/** React frontend backend — lowers primitives to idiomatic
 *  React 18+ JS:
 *
 *    - `Signal[T]` → `useState`
 *    - `Computed[T]` → `useMemo`
 *    - `Effect` → `useEffect`
 *    - `Component[P]` → function component
 *    - `View.Element` → `React.createElement(...)`
 *    - `View.Show` / `View.For` → conditional / map JSX expressions
 *
 *  STUB.  v1.18 / Phase A3 ships the real `emit`. */
final class ReactFrameworkBackend extends FrontendFrameworkSpi:

  override def name: String = "react"

  override def capabilities: Set[Capability] = Set(
    Capability.ComponentTree,
    Capability.SignalState,
    Capability.ComputedDerived,
    Capability.EffectLifecycle,
    Capability.DomRefs,
    Capability.Context,
    Capability.Portals,
    Capability.Suspense
  )

  override def jsDeps: List[JsDep] = List(
    JsDep("react",     "^18.3.0", "react"),
    JsDep("react-dom", "^18.3.0", "react-dom/client")
  )

  override def emit(module: FrontendModule): EmittedSpa =
    throw new NotImplementedError(
      "ReactFrameworkBackend.emit — v1.18 / Phase A3."
    )
