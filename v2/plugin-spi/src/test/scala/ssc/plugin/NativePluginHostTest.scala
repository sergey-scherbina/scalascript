package ssc.plugin

import org.scalatest.funsuite.AnyFunSuite
import scala.jdk.CollectionConverters.*
import ssc.Value

class NativePluginHostTest extends AnyFunSuite:
  private final class Provider(val id: String, name: String, value: Long) extends NativePlugin:
    def install(context: NativePluginContext): Unit =
      context.registerValue(name, Value.IntV(value))

  test("legacy and profiled providers coexist through the real ServiceLoader host path") {
    val providers = java.util.ServiceLoader
      .load(classOf[NativePlugin], Thread.currentThread().getContextClassLoader)
      .iterator().asScala.toVector.sortBy(_.id)

    assert(providers.map(_.id) == Vector("service-legacy", "service-profiled"))
    assert(providers.head.capabilityDeclaration.isEmpty)
    assert(providers.last.capabilityDeclaration.exists(_.pluginId == "service-profiled"))
    assert(NativePluginHost.loadAll() == 2)
    assert(ssc.V2PluginRegistry.lookupGlobal("serviceProfiled").contains(Value.IntV(2)))
  }

  test("capability declaration remains a concrete interface default for old providers") {
    val method = classOf[NativePlugin].getMethod("capabilityDeclaration")
    assert(!java.lang.reflect.Modifier.isAbstract(method.getModifiers))
    assert(!classOf[JavaLegacyServicePlugin].getDeclaredMethods.exists(
      _.getName == "capabilityDeclaration"))
    assert(new JavaLegacyServicePlugin().capabilityDeclaration.isEmpty)
  }

  test("providers install in stable id order") {
    val seen = collection.mutable.ArrayBuffer.empty[String]
    def provider(id0: String) = new NativePlugin:
      def id: String = id0
      def install(context: NativePluginContext): Unit = seen += id0

    assert(NativePluginHost.installProviders(List(provider("z"), provider("a"))) == 2)
    assert(seen.toList == List("a", "z"))
  }

  test("duplicate ownership names both providers") {
    val err = intercept[IllegalStateException] {
      NativePluginHost.installProviders(List(
        Provider("first", "same", 1),
        Provider("second", "same", 2)
      ))
    }
    assert(err.getMessage.contains("first and second"))
    assert(err.getMessage.contains("global 'same'"))
  }

  test("duplicate provider ids fail before installation") {
    val err = intercept[IllegalStateException] {
      NativePluginHost.installProviders(List(
        Provider("duplicate", "one", 1),
        Provider("duplicate", "two", 2)
      ))
    }
    assert(err.getMessage.contains("duplicate native plugin id(s): duplicate"))
  }

  test("provider callbacks run through the host trampoline with bounded errors") {
    var result: Value = Value.UnitV
    val callback = Value.ClosV(ssc.Runtime.emptyEnv, 1, env => ssc.Done(env.last))
    val provider = new NativePlugin:
      def id: String = "callback"
      def install(context: NativePluginContext): Unit =
        result = context.invoke(callback, List(Value.StrV("ok")))
        val error = intercept[IllegalArgumentException](context.invoke(Value.IntV(1), Nil))
        assert(error.getMessage == "native callback value is not callable")

    NativePluginHost.installProviders(List(provider))
    assert(result == Value.StrV("ok"))
  }

  test("tag-qualified apply and method hooks are isolated and snapshot-safe") {
    val provider = new NativePlugin:
      def id: String = "tagged"
      def install(context: NativePluginContext): Unit =
        context.registerTaggedApply("PortableBox") {
          case Value.DataV("PortableBox", fields) :: Nil => fields.head
          case _ => fail("unexpected tagged apply arguments")
        }
        context.registerTaggedMethod("PortableBox", "set") {
          case Value.DataV("PortableBox", _) :: value :: Nil => Value.DataV("PortableBox", Vector(value))
          case _ => fail("unexpected tagged method arguments")
        }

    NativePluginHost.installProviders(List(provider))
    val box = Value.DataV("PortableBox", Vector(Value.IntV(1)))
    assert(ssc.Runtime.applyFallback(box, Array.empty) == ssc.Done(Value.IntV(1)))
    assert(ssc.Prims.methodOp("set", box, List(Value.IntV(2))) ==
      Value.DataV("PortableBox", Vector(Value.IntV(2))))
    assert(ssc.V2PluginRegistry.lookupTaggedMethod("OtherBox", "set").isEmpty)

    val snapshot = ssc.V2PluginRegistry.snapshot()
    ssc.V2PluginRegistry.clear()
    assert(ssc.V2PluginRegistry.lookupTaggedApply("PortableBox").isEmpty)
    ssc.V2PluginRegistry.restore(snapshot)
    assert(ssc.Runtime.applyFallback(box, Array.empty) == ssc.Done(Value.IntV(1)))
  }

  test("MapV preserves insertion order and keeps identity equality") {
    val first = Value.MapV.from(List(
      Value.StrV("b") -> Value.IntV(2),
      Value.StrV("a") -> Value.IntV(1)))
    val sameContents = Value.MapV.from(first.entries)

    assert(first.entries.keys.toList == List(Value.StrV("b"), Value.StrV("a")))
    assert(first ne sameContents)
    assert(first != sameContents)
    assert(ssc.Show.show(first) == "Map(\"b\" -> 2, \"a\" -> 1)")
  }

  test("providers see immutable native database configuration") {
    var seen = Map.empty[String, NativeDatabaseConfig]
    val provider = new NativePlugin:
      def id: String = "db-config"
      def install(context: NativePluginContext): Unit = seen = context.databases
    val config = NativeRuntimeConfig(Map(
      "default" -> NativeDatabaseConfig(
        "jdbc:h2:mem:test",
        user = Some("sa"),
        password = Some(""),
        driver = Some("org.h2.Driver"))))

    NativePluginHost.installProviders(List(provider), config)

    assert(seen == config.databases)
  }

  test("providers see immutable structural content and artifact codec is deterministic") {
    val document = Value.DataV("DocumentContent", Vector(
      Value.DataV("MapV", Vector(Value.MapV.empty)),
      Value.DataV("Some", Vector(Value.StrV("Title"))),
      Value.DataV("None", Vector.empty), Value.MapV.empty,
      Value.DataV("Nil", Vector.empty), Value.DataV("Nil", Vector.empty)))
    val module = NativeContentModule(
      "main.ssc", explicitRoot = true, List("std/money.ssc"), "main", document)
    val encoded = NativeContentCodec.encode(List(module))
    assert(java.util.Arrays.equals(encoded, NativeContentCodec.encode(List(module))))
    val decoded = NativeContentCodec.decode(encoded)
    assert(decoded.map(item => (item.source, item.explicitRoot, item.directImports, item.namespace)) ==
      List((module.source, module.explicitRoot, module.directImports, module.namespace)))
    assert(ssc.Show.show(decoded.head.document) == ssc.Show.show(module.document))

    var seen = List.empty[NativeContentModule]
    val provider = new NativePlugin:
      def id: String = "content-config"
      def install(context: NativePluginContext): Unit = seen = context.contentModules
    NativePluginHost.installProviders(
      List(provider), NativeRuntimeConfig(contentModules = List(module)))
    assert(seen == List(module))
  }

  test("effect scope is nested and always restored") {
    var observed = List.empty[Value]
    val provider = new NativePlugin:
      def id: String = "effect-scope"
      def install(context: NativePluginContext): Unit =
        val outer: (String, List[Value]) => Value = (_, _) => Value.IntV(1)
        val inner: (String, List[Value]) => Value = (_, _) => Value.IntV(2)
        context.withEffect("Test")(outer) {
          observed :+= ssc.V2EffectContext.peek("Test").get("read", Nil)
          context.withEffect("Test")(inner) {
            observed :+= ssc.V2EffectContext.peek("Test").get("read", Nil)
            Value.UnitV
          }
          observed :+= ssc.V2EffectContext.peek("Test").get("read", Nil)
          Value.UnitV
        }
        intercept[RuntimeException] {
          context.withEffect("Boom")(outer) { throw new RuntimeException("boom") }
        }

    NativePluginHost.installProviders(List(provider))

    assert(observed == List(Value.IntV(1), Value.IntV(2), Value.IntV(1)))
    assert(ssc.V2EffectContext.peek("Test").isEmpty)
    assert(ssc.V2EffectContext.peek("Boom").isEmpty)
  }

  test("artifact runtime initializes argv and preserves unresolved-result failures") {
    val previous = ssc.Runtime.argv
    try
      NativeArtifactRuntime.initialize(Array("--", "one", "two"))
      assert(ssc.Runtime.argv == List("one", "two"))

      val error = intercept[RuntimeException] {
        NativeArtifactRuntime.report(Value.DataV("Stub", Vector(Value.StrV("missing"))))
      }
      assert(error.getMessage == "unresolved runtime dispatch: missing")
    finally ssc.Runtime.argv = previous
  }
