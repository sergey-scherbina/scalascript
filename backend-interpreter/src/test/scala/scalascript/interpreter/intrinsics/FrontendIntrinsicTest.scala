package scalascript.interpreter

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir.QualifiedName
import scalascript.backend.spi.NativeImpl
import scalascript.parser.Parser

/** Unit smoke for the v1.18 / Phase A7 `setFrontendFramework(name)`
 *  intrinsic.  Confirms:
 *
 *  - The intrinsic is registered in `InterpreterIntrinsics` (so the
 *    interpreter routes the call instead of treating it as an
 *    undefined symbol).
 *  - Invoking it with a valid name flips
 *    `FrontendFrameworks.selectedName` to that choice.
 *  - Invoking it with an unknown name throws (loud failure beats
 *    silent fallback when the user explicitly asked).
 *
 *  Each test resets the registry afterwards so test ordering doesn't
 *  leak state. */
class FrontendIntrinsicTest extends AnyFunSuite:

  // The intrinsic is invoked after `Interpreter.installNativeIntrinsics`
  // unwraps `Value.StringV(s)` to a raw `String`; pass primitives here.
  private def callSetFrontendFramework(name: String): Unit =
    val impl = InterpreterIntrinsics(QualifiedName("setFrontendFramework"))
      .asInstanceOf[NativeImpl]
    impl.eval(null, List(name))

  test("setFrontendFramework intrinsic is registered") {
    assert(InterpreterIntrinsics.contains(QualifiedName("setFrontendFramework")))
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

  test("setFrontendFramework — end-to-end via Interpreter.run") {
    try
      // Use a `react` literal so the intrinsic actually flips the
      // registry — verifies the pattern matches the post-unwrap
      // primitive String, not a leftover Value wrapper.
      Interpreter().run(Parser.parse("""# T
```scala
setFrontendFramework("solid")
```
"""))
      assert(scalascript.frontend.FrontendFrameworks.selectedName == Some("solid"))
    finally
      scalascript.frontend.FrontendFrameworks.setBackend(null)
  }

  test("setFrontendFramework requires exactly one string arg") {
    try
      val impl = InterpreterIntrinsics(QualifiedName("setFrontendFramework"))
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
