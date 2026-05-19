package scalascript.frontend.react

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class ReactFrameworkBackendTest extends AnyFunSuite:

  test("ServiceLoader discovers ReactFrameworkBackend") {
    FrontendFrameworks.setBackend(null)
    val impls = FrontendFrameworks.all()
    assert(impls.exists(_.name == "react"),
      s"Expected 'react' in [${impls.map(_.name).mkString(", ")}]")
  }

  test("name + capabilities + jsDeps") {
    val backend = new ReactFrameworkBackend
    assert(backend.name == "react")
    assert(backend.capabilities.contains(Capability.Suspense))
    assert(backend.capabilities.contains(Capability.Portals))
    val depNames = backend.jsDeps.map(_.npmName).toSet
    assert(depNames.contains("react"))
    assert(depNames.contains("react-dom"))
  }

  test("emit — stub today, throws NotImplementedError") {
    val backend = new ReactFrameworkBackend
    val ex = intercept[NotImplementedError] {
      backend.emit(FrontendModule(Nil, "App", "/"))
    }
    assert(ex.getMessage.contains("Phase A3"))
  }
