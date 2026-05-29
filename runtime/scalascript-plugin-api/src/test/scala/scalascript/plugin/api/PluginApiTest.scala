package scalascript.plugin.api

import org.scalatest.funsuite.AnyFunSuite

/** arch-stable-spi-p1 — Plugin API module compilation tests.
 *
 *  Verifies that `scalascript-plugin-api` exports the expected stable surface
 *  and that a minimal `NativeImpl`-style consumer compiles against it. */
class PluginApiTest extends AnyFunSuite:

  test("PluginValue.wrap / unwrap round-trip"):
    val v: PluginValue = PluginValue.wrap(42L)
    assert(v.unwrap == 42L)

  test("PluginValue.wrap preserves strings"):
    val v: PluginValue = PluginValue.wrap("hello")
    assert(v.unwrap == "hello")

  test("PluginError.apply creates an error from message"):
    val e: PluginError = PluginError("bad arg")
    assert(e.message == "bad arg")

  test("PluginError.wrap preserves the original Throwable"):
    val t = new IllegalArgumentException("nope")
    val e: PluginError = PluginError.wrap(t)
    assert(e.unwrap eq t)

  test("PluginComputation.pure wraps a PluginValue"):
    val v  = PluginValue.wrap(99L)
    val pc = PluginComputation.pure(v)
    assert(pc.unwrap == 99L)

  test("JsonCodec.parseString parses a JSON object"):
    val result = JsonCodec.parseString("""{"key":"value"}""")
    assert(result.isRight)
    result.foreach { v =>
      assert(v("key").str == "value")
    }

  test("JsonCodec.parseString returns Left on malformed JSON"):
    val result = JsonCodec.parseString("{bad}")
    assert(result.isLeft)

  test("JsonCodec.stringify produces JSON string"):
    val v = JsonCodec.obj("x" -> ujson.Num(1.0))
    assert(JsonCodec.stringify(v) == """{"x":1}""")

  test("JsonCodec helpers build well-typed values"):
    assert(JsonCodec.str("hi").value == "hi")
    assert(JsonCodec.num(3.14).value == 3.14)
    assert(JsonCodec.True  == ujson.True)
    assert(JsonCodec.Null  == ujson.Null)

  test("PluginContext is a stable type alias for NativeContext"):
    // Verifies the type is accessible and compatible with NativeContext.
    def acceptNativeContext(ctx: scalascript.backend.spi.NativeContext): Unit = ()
    def usePluginContext(ctx: PluginContext): Unit = acceptNativeContext(ctx)
    // If this compiles, the type alias is correctly defined.
    assert(usePluginContext != null)
    succeed

  test("NativeImpl accepts PluginContext"):
    import scalascript.backend.spi.{NativeImpl, IntrinsicImpl}
    val impl: IntrinsicImpl = NativeImpl((ctx: PluginContext, args: List[Any]) =>
      val _ = ctx
      PluginValue.wrap(args.headOption.getOrElse(()))
    )
    assert(impl.isInstanceOf[NativeImpl])
