package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Unit smoke for the `emit-spa --frontend <name>` flag's CLI-side
 *  helper.  v1.18 / Phase A7.
 *
 *  Confirms:
 *
 *  - `validFrontendNames` covers exactly the four bundled backends.
 *  - `applyFrontendBackend(name)` flips the `FrontendFrameworks`
 *    registry choice for downstream codegen.
 *  - `applyFrontendBackend("nope")` throws (defense — the CLI's arg
 *    parser validates upstream against `validFrontendNames`, but
 *    `FrontendFrameworks.setBackend` raises if no impl with that
 *    name is on the classpath). */
class FrontendBackendSelectionTest extends AnyFunSuite:

  test("validFrontendNames lists exactly the four bundled backends") {
    assert(validFrontendNames == Set("custom", "react", "solid", "vue"))
  }

  test("applyFrontendBackend('react') selects react") {
    try
      applyFrontendBackend("react")
      assert(scalascript.frontend.FrontendFrameworks.selectedName == Some("react"))
    finally
      scalascript.frontend.FrontendFrameworks.setBackend(null)
  }

  test("applyFrontendBackend('custom') selects custom") {
    try
      applyFrontendBackend("custom")
      assert(scalascript.frontend.FrontendFrameworks.selectedName == Some("custom"))
    finally
      scalascript.frontend.FrontendFrameworks.setBackend(null)
  }

  test("applyFrontendBackend throws on unknown name") {
    try
      val ex = intercept[IllegalStateException] {
        applyFrontendBackend("svelte")
      }
      assert(ex.getMessage.contains("svelte"))
    finally
      scalascript.frontend.FrontendFrameworks.setBackend(null)
  }

  test("all four bundled backends round-trip through applyFrontendBackend") {
    try
      for name <- List("custom", "react", "solid", "vue") do
        applyFrontendBackend(name)
        assert(scalascript.frontend.FrontendFrameworks.selectedName == Some(name))
        assert(scalascript.frontend.FrontendFrameworks.current().name == name)
    finally
      scalascript.frontend.FrontendFrameworks.setBackend(null)
  }
