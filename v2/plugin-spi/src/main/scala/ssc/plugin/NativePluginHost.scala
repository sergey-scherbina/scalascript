package ssc.plugin

import scala.jdk.CollectionConverters.*
import ssc.{Done, Runtime, V2PluginRegistry, Value}

/** Deterministic ServiceLoader host for standard-tier native providers. */
object NativePluginHost:
  def loadAll(): Int =
    loadAll(NativeRuntimeConfig())

  def loadAll(config: NativeRuntimeConfig): Int =
    val loader = java.util.ServiceLoader.load(classOf[NativePlugin], Thread.currentThread().getContextClassLoader)
    installProviders(loader.iterator().asScala.toList, config)

  private[plugin] def installProviders(
      providers: List[NativePlugin],
      config: NativeRuntimeConfig = NativeRuntimeConfig()): Int =
    val sorted = providers.sortBy(_.id)
    val duplicateIds = sorted.groupBy(_.id).collect { case (id, xs) if xs.size > 1 => id }.toList.sorted
    if duplicateIds.nonEmpty then
      throw new IllegalStateException(s"duplicate native plugin id(s): ${duplicateIds.mkString(", ")}")

    V2PluginRegistry.clear()
    val owners = collection.mutable.HashMap.empty[(String, String), String]

    def claim(kind: String, name: String, provider: String): Unit =
      owners.get((kind, name)) match
        case Some(previous) =>
          throw new IllegalStateException(
            s"native plugin ownership conflict for $kind '$name': $previous and $provider")
        case None => owners((kind, name)) = provider

    sorted.foreach { provider =>
      val context = new NativePluginContext:
        def argv: List[String] = Runtime.argv
        def databases: Map[String, NativeDatabaseConfig] = config.databases

        def invoke(fn: Value, args: List[Value]): Value = fn match
          case clos: Value.ClosV =>
            if clos.arity >= 0 && clos.arity != args.length then
              throw new IllegalArgumentException(
                s"native callback arity: ${clos.arity} expected, ${args.length} given")
            val env = if args.isEmpty then clos.env else Runtime.extend(clos.env, args.toArray)
            Runtime.run(clos.code, env)
          case Value.ForeignV(obj: Value.NamedMethodObj) =>
            obj.getField("apply") match
              case Some(apply) => invoke(apply, args)
              case None => throw new IllegalArgumentException("native callback value is not callable")
          case _ => throw new IllegalArgumentException("native callback value is not callable")

        def register(name: String)(fn: List[Value] => Value): Unit =
          claim("intrinsic", name, provider.id)
          V2PluginRegistry.register(name, fn)

        def registerGlobal(name: String, arity: Int)(fn: List[Value] => Value): Unit =
          claim("global", name, provider.id)
          V2PluginRegistry.registerGlobal(name,
            Value.ClosV(Runtime.emptyEnv, arity, env => Done(fn(env.toList))))

        def registerValue(name: String, value: Value): Unit =
          claim("global", name, provider.id)
          V2PluginRegistry.registerGlobal(name, value)

        def registerFields(tag: String, fields: Vector[String]): Unit =
          claim("fields", s"$tag/${fields.length}", provider.id)
          V2PluginRegistry.registerFieldNames(tag, fields)

      provider.install(context)
    }
    sorted.size
