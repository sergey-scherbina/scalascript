package scalascript.compiler.plugin.frontend

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ir.QualifiedName
import scalascript.backend.spi.{NativeContext, NativeImpl}
import scalascript.interpreter.InterpretError

import java.io.{OutputStream, PrintStream}

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

  test("componentScope compatibility intrinsic invokes its thunk exactly once") {
    val bodyToken = new Object()
    var calls = 0
    val sink = new PrintStream(OutputStream.nullOutputStream())
    val context = new NativeContext:
      def out: PrintStream = sink
      def err: PrintStream = sink
      override def invokeCallback(fn: Any, args: List[Any]): Any =
        assert(fn != null)
        assert(args.isEmpty)
        calls += 1
        "scoped-result"

    val impl = FrontendIntrinsics.table(QualifiedName("componentScope"))
      .asInstanceOf[NativeImpl]
    assert(impl.eval(context, List("counter__a", bodyToken)) == "scoped-result")
    assert(calls == 1)
  }
