package scalascript.frontend.solid

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class SolidFrameworkBackendTest extends AnyFunSuite:

  test("ServiceLoader discovers SolidFrameworkBackend") {
    FrontendFrameworks.setBackend(null)
    val impls = FrontendFrameworks.all()
    assert(impls.exists(_.name == "solid"),
      s"Expected 'solid' in [${impls.map(_.name).mkString(", ")}]")
  }

  test("name + capabilities + jsDeps") {
    val backend = new SolidFrameworkBackend
    assert(backend.name == "solid")
    assert(backend.capabilities.contains(Capability.Untrack))
    assert(backend.capabilities.contains(Capability.SignalState))
    val depNames = backend.jsDeps.map(_.npmName).toSet
    assert(depNames.contains("solid-js"))
  }

  test("emit — stub today, throws NotImplementedError") {
    val backend = new SolidFrameworkBackend
    val ex = intercept[NotImplementedError] {
      backend.emit(FrontendModule(Nil, "App", "/"))
    }
    assert(ex.getMessage.contains("Phase A4"))
  }
