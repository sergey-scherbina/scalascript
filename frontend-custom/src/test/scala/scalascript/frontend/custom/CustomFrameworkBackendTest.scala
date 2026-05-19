package scalascript.frontend.custom

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class CustomFrameworkBackendTest extends AnyFunSuite:

  test("ServiceLoader discovers CustomFrameworkBackend") {
    FrontendFrameworks.setBackend(null)
    val impls = FrontendFrameworks.all()
    assert(impls.exists(_.name == "custom"),
      s"Expected 'custom' in [${impls.map(_.name).mkString(", ")}]")
  }

  test("name + capabilities + jsDeps") {
    val backend = new CustomFrameworkBackend
    assert(backend.name == "custom")
    assert(backend.capabilities.contains(Capability.SignalState))
    assert(backend.capabilities.contains(Capability.ComponentTree))
    // The whole point: no external npm deps.
    assert(backend.jsDeps.isEmpty)
  }

  test("emit — stub today, throws NotImplementedError") {
    val backend = new CustomFrameworkBackend
    val ex = intercept[NotImplementedError] {
      backend.emit(FrontendModule(Nil, "App", "/"))
    }
    assert(ex.getMessage.contains("Phase A2"))
  }
