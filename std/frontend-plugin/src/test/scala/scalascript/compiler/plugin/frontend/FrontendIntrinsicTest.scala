package scalascript.compiler.plugin.frontend

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir.QualifiedName
import scalascript.backend.spi.NativeImpl
import scalascript.interpreter.InterpretError

/** Unit smoke for the v1.18 / Phase A7 `setFrontendFramework(name)` intrinsic.
 *
 *  Each test resets the registry afterwards so test ordering doesn't leak state. */
class FrontendIntrinsicTest extends AnyFunSuite:

  // The intrinsic is invoked after `Interpreter.installNativeIntrinsics`
  // unwraps `Value.StringV(s)` to a raw `String`; pass primitives here.
  private def callSetFrontendFramework(name: String): Unit =
    val impl = FrontendIntrinsics.table(QualifiedName("setFrontendFramework"))
      .asInstanceOf[NativeImpl]
    impl.eval(null, List(name))

  test("setFrontendFramework intrinsic is registered") {
    assert(FrontendIntrinsics.table.contains(QualifiedName("setFrontendFramework")))
  }

  test("setFrontendFramework('react') selects react") {
    try
      callSetFrontendFramework("react")
      assert(scalascript.frontend.FrontendFrameworks.selectedName == Some("react"))
    finally
      scalascript.frontend.FrontendFrameworks.setBackend(null)
  }

  test("setFrontendFramework('custom') selects custom") {
    try
      callSetFrontendFramework("custom")
      assert(scalascript.frontend.FrontendFrameworks.selectedName == Some("custom"))
    finally
      scalascript.frontend.FrontendFrameworks.setBackend(null)
  }

  test("setFrontendFramework throws on unknown name") {
    try
      val ex = intercept[IllegalStateException] {
        callSetFrontendFramework("ember")
      }
      assert(ex.getMessage.contains("ember"))
    finally
      scalascript.frontend.FrontendFrameworks.setBackend(null)
  }

  test("setFrontendFramework requires exactly one string arg") {
    try
      val impl = FrontendIntrinsics.table(QualifiedName("setFrontendFramework"))
        .asInstanceOf[NativeImpl]
      assertThrows[InterpretError] {
        impl.eval(null, List(42L))
      }
      assertThrows[InterpretError] {
        impl.eval(null, Nil)
      }
    finally
      scalascript.frontend.FrontendFrameworks.setBackend(null)
  }
