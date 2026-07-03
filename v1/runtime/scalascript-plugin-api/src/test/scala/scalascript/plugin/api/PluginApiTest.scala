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

  test("PluginContext exposes capability traits from NativeContext"):
    val native = new TestNativeContext
    val ctx = PluginContext.fromNative(native)
    ctx.featureSet("x", "y")
    ctx.setHttpTimeout(1234L)
    assert(ctx.featureGet("x").contains("y"))
    assert(ctx.httpTimeoutMs == 1234L)
    assert(ctx.storageFieldName("User", "userId") == "userId")

  test("PluginContext.RemoteCap exposes remote handler dispatch"):
    val native = new TestNativeContext
    val ctx = PluginContext.fromNative(native)
    assert(ctx.remoteHandlers.isEmpty)
    assert(ctx.invokeRemoteHandler("x", ()).isLeft)

  test("PluginNative.eval builds a NativeImpl from typed PluginContext"):
    import scalascript.backend.spi.{NativeImpl, IntrinsicImpl}
    val impl: IntrinsicImpl = PluginNative.eval((ctx: StorageCap) => ctx) { (storage, args) =>
      storage.featureSet("seen", args.headOption.map(_.unwrap).getOrElse(()))
      PluginComputation.pure(PluginValue.wrap(storage.featureGet("seen").getOrElse(())))
    }
    val native = new TestNativeContext
    val result = impl.asInstanceOf[NativeImpl].eval(native, List("ok"))
    assert(result == "ok")

  test("PluginNative.evalLegacy wraps a legacy body with capability-typed context"):
    import scalascript.backend.spi.{NativeImpl, IntrinsicImpl}
    val impl: IntrinsicImpl = PluginNative.evalLegacy { (ctx, args) =>
      ctx.featureSet("leg", args.headOption.getOrElse("?"))
      ctx.featureGet("leg").getOrElse(())
    }
    val native = new TestNativeContext
    val result = impl.asInstanceOf[NativeImpl].eval(native, List("ok"))
    assert(result == "ok")

  test("classpath boundary: scalascript.interpreter.Value is NOT accessible from plugin-api"):
    // scalascript-plugin-api must NOT depend on scalascript-core (interpreter internals).
    // This test catches accidental dependency creep at CI time.
    val cl = getClass.getClassLoader
    val found = try { cl.loadClass("scalascript.interpreter.Value$"); true }
                catch case _: ClassNotFoundException => false
    assert(!found, "scalascript.interpreter.Value leaked into scalascript-plugin-api classpath")

class TestNativeContext extends scalascript.backend.spi.NativeContext:
  private val state = scala.collection.mutable.Map.empty[String, Any]
  def out: java.io.PrintStream = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())
  def err: java.io.PrintStream = new java.io.PrintStream(java.io.OutputStream.nullOutputStream())
  override def featureGet(key: String): Option[Any] = state.get(key)
  override def featureSet(key: String, value: Any): Unit = state.update(key, value)
  override def featureRemove(key: String): Option[Any] = state.remove(key)
  override def setHttpTimeout(ms: Long): Unit =
    featureSet(scalascript.backend.spi.NativeContextFeatureKeys.HttpTimeoutMs, ms)
  override def httpTimeoutMs: Long =
    featureGet(scalascript.backend.spi.NativeContextFeatureKeys.HttpTimeoutMs)
      .collect { case n: Long => n }
      .getOrElse(super.httpTimeoutMs)
