package scalascript.frontend.vue

import org.scalatest.funsuite.AnyFunSuite
import scalascript.frontend.*

class VueFrameworkBackendTest extends AnyFunSuite:

  test("ServiceLoader discovers VueFrameworkBackend") {
    FrontendFrameworks.setBackend(null)
    val impls = FrontendFrameworks.all()
    assert(impls.exists(_.name == "vue"),
      s"Expected 'vue' in [${impls.map(_.name).mkString(", ")}]")
  }

  test("name + capabilities + jsDeps") {
    val backend = new VueFrameworkBackend
    assert(backend.name == "vue")
    assert(backend.capabilities.contains(Capability.TwoWayBinding))
    assert(backend.capabilities.contains(Capability.Suspense))
    val depNames = backend.jsDeps.map(_.npmName).toSet
    assert(depNames.contains("vue"))
  }

  test("emit — stub today, throws NotImplementedError") {
    val backend = new VueFrameworkBackend
    val ex = intercept[NotImplementedError] {
      backend.emit(FrontendModule(Nil, "App", "/"))
    }
    assert(ex.getMessage.contains("Phase A5"))
  }
