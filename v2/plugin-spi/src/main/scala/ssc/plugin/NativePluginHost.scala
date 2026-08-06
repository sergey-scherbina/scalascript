package ssc.plugin

import scala.jdk.CollectionConverters.*
import ssc.{Done, Runtime, V2EffectContext, V2PluginRegistry, Value}

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
        override def sourceText: Option[String] = Runtime.sourceText
        def databases: Map[String, NativeDatabaseConfig] = config.databases
        def contentModules: List[NativeContentModule] = config.contentModules
        override def declaredRoutes: List[NativeRouteDecl] = config.routes

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

        // Plugin registrations first — that is what this method was introduced for and what other
        // plugins rely on — then the PROGRAM's own globals, so a plugin can call a user function by
        // name. A widening, not a change: every name that resolved before resolves to the same value.
        // THREE places a name can live, and the order is by how specific each is.
        //   1. plugin registrations — what this method was introduced for, and what other plugins
        //      rely on, so it keeps winning;
        //   2. `Emit.globalsRef` — the BYTECODE tier's program globals, filled by the generated
        //      `install()`. This is the DEFAULT tier: `bin/ssc file.ssc` runs it;
        //   3. the VM tier's maps, for `--interpret` / `--vm`.
        // Measured while closing native-fm-routes: consulting only (1) resolved nothing, and only
        // (1)+(3) resolved eight maps of prelude names and never the user's own `def`.
        // A widening, not a change: every name that resolved before resolves to the same value.
        override def resolveGlobal(name: String): Option[Value] =
          V2PluginRegistry.lookupGlobal(name)
            .orElse(ssc.Emit.globalsRef.get(name))
            .orElse(V2PluginRegistry.lookupProgramGlobal(name))

        def withEffect(effectTag: String)(handler: (String, List[Value]) => Value)(body: => Value): Value =
          V2EffectContext.push(effectTag, handler)
          try body
          finally V2EffectContext.pop(effectTag)

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

        def registerTaggedApply(tag: String)(fn: List[Value] => Value): Unit =
          claim("tagged-apply", tag, provider.id)
          V2PluginRegistry.registerTaggedApply(tag, fn)

        def registerTaggedMethod(tag: String, name: String)(fn: List[Value] => Value): Unit =
          claim("tagged-method", s"$tag.$name", provider.id)
          V2PluginRegistry.registerTaggedMethod(tag, name, fn)

        def registerFields(tag: String, fields: Vector[String]): Unit =
          claim("fields", s"$tag/${fields.length}", provider.id)
          V2PluginRegistry.registerFieldNames(tag, fields)

      provider.install(context)
    }
    sorted.size
