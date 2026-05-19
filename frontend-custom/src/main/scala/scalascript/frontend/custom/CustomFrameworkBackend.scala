package scalascript.frontend.custom

import scalascript.frontend.*

/** Default frontend backend — no external npm runtime.  Lowers
 *  the abstract primitives onto a tiny in-house JS runtime
 *  (signals via subscriber-Set, direct DOM ops, fine-grained
 *  patches).  Target bundle size: 3-5 KB gzipped.
 *
 *  STUB.  v1.18 / Phase A1 ships the SPI registration only.
 *  Real `emit` impl lands in Phase A2; see
 *  `docs/frontend-framework-spi-plan.md`. */
final class CustomFrameworkBackend extends FrontendFrameworkSpi:

  override def name: String = "custom"

  override def capabilities: Set[Capability] = Set(
    Capability.ComponentTree,
    Capability.SignalState,
    Capability.ComputedDerived,
    Capability.EffectLifecycle,
    Capability.DomRefs,
    Capability.Context,
    Capability.Untrack
  )

  override def jsDeps: List[JsDep] = Nil // zero npm deps — that's the whole point

  override def emit(module: FrontendModule): EmittedSpa =
    throw new NotImplementedError(
      "CustomFrameworkBackend.emit — v1.18 / Phase A2 will implement.  " +
      "Today the backend exists only as an SPI registration so the " +
      "discovery + selection plumbing can be tested end-to-end."
    )
