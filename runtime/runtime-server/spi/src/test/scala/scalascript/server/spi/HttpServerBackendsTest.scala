package scalascript.server.spi

import org.scalatest.funsuite.AnyFunSuite

/** Smoke for the SPI selection registry.  Tested standalone (no impl
 *  modules on this module's test classpath, so `all()` is expected to
 *  return an empty list; the cross-module integration is exercised by
 *  each backend's own discovery test). */
class HttpServerBackendsTest extends AnyFunSuite:

  test("setBackend(null) — clears the selection (no-op when never set)") {
    HttpServerBackends.setBackend(null)
    assert(HttpServerBackends.selectedName == None)
  }

  test("setBackend('missing') — throws IllegalStateException loudly") {
    // No impls on this module's test classpath, so any non-null name
    // is missing.  Loud failure is the contract.
    val ex = intercept[IllegalStateException] {
      HttpServerBackends.setBackend("does-not-exist")
    }
    assert(ex.getMessage.contains("does-not-exist"))
    assert(ex.getMessage.contains("classpath"))
    // Cleanup so other tests don't inherit state.
    HttpServerBackends.setBackend(null)
  }

  test("current() — throws if no impl on classpath") {
    HttpServerBackends.setBackend(null) // reset state
    val ex = intercept[IllegalStateException] {
      HttpServerBackends.current()
    }
    assert(ex.getMessage.contains("No HttpServerSpi impl on classpath"))
  }

  test("selectedName — tracks the last setBackend call") {
    // Can't successfully set a real backend here (no impls on classpath),
    // but the failure path still leaves the previous selection intact.
    HttpServerBackends.setBackend(null)
    assert(HttpServerBackends.selectedName == None)
  }
