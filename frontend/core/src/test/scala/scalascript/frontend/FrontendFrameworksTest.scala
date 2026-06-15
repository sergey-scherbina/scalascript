package scalascript.frontend

import org.scalatest.funsuite.AnyFunSuite

/** Smoke for the SPI selection registry.  Mirrors the
 *  `HttpServerBackendsTest` pattern from v1.17.6.  No impl
 *  modules are on this module's test classpath, so `all()`
 *  returns empty unless a backend is registered programmatically;
 *  per-backend discovery is exercised by each impl module's own
 *  test. */
class FrontendFrameworksTest extends AnyFunSuite:

  test("setBackend(null) — clears the selection (no-op when never set)") {
    FrontendFrameworks.setBackend(null)
    assert(FrontendFrameworks.selectedName == None)
  }

  test("setBackend('missing') — throws IllegalStateException loudly") {
    val ex = intercept[IllegalStateException] {
      FrontendFrameworks.setBackend("does-not-exist")
    }
    assert(ex.getMessage.contains("does-not-exist"))
    assert(ex.getMessage.contains("classpath"))
    FrontendFrameworks.setBackend(null)
  }

  test("current() — throws if no impl on classpath") {
    FrontendFrameworks.setBackend(null)
    val ex = intercept[IllegalStateException] {
      FrontendFrameworks.current()
    }
    assert(ex.getMessage.contains("No FrontendFrameworkSpi impl on classpath"))
  }

  test("register(impl) — programmatic registration lets current() resolve") {
    FrontendFrameworks.setBackend(null)
    val fake = FakeBackend("test-fake")
    FrontendFrameworks.register(fake)
    try
      val resolved = FrontendFrameworks.current()
      assert(resolved.name == "test-fake")
    finally
      // No public unregister; reset by selecting back to null so
      // other tests in this suite still see deterministic state.
      FrontendFrameworks.setBackend(null)
  }

  test("setBackend by name — picks among multiple programmatic regs") {
    FrontendFrameworks.setBackend(null)
    val a = FakeBackend("a-backend")
    val b = FakeBackend("b-backend")
    FrontendFrameworks.register(a)
    FrontendFrameworks.register(b)
    FrontendFrameworks.setBackend("b-backend")
    val resolved = FrontendFrameworks.current()
    assert(resolved.name == "b-backend")
    FrontendFrameworks.setBackend(null)
  }

  test("Capability enum has the documented core + extension cases") {
    // The 5-core / 6-extension promise from
    // specs/frontend-abstract-model.md — guard against accidental
    // additions or removals during the early phases.
    val all = Capability.values.toSet
    assert(all.contains(Capability.ComponentTree))
    assert(all.contains(Capability.SignalState))
    assert(all.contains(Capability.ComputedDerived))
    assert(all.contains(Capability.EffectLifecycle))
    assert(all.contains(Capability.DomRefs))
    assert(all.contains(Capability.Context))
    assert(all.contains(Capability.Portals))
    assert(all.contains(Capability.Suspense))
    assert(all.contains(Capability.Untrack))
    assert(all.contains(Capability.TwoWayBinding))
    assert(all.contains(Capability.NfcNdef))
    assert(all.contains(Capability.NfcTagTech))
    assert(all.contains(Capability.NfcCardEmulation))
  }

private final case class FakeBackend(name: String) extends FrontendFrameworkSpi:
  override def capabilities: Set[Capability]                   = Set.empty
  override def jsDeps:       List[JsDep]                       = Nil
  override def emit(module: FrontendModule): EmittedSpa        =
    EmittedSpa(js = "", html = "", css = "")
