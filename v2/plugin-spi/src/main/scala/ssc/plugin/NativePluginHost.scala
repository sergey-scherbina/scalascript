package ssc.plugin

import scala.jdk.CollectionConverters.*
import ssc.{Done, Runtime, V2PluginRegistry, Value}

/** Deterministic ServiceLoader host for standard-tier native providers. */
object NativePluginHost:
  def loadAll(): Int =
    val loader = java.util.ServiceLoader.load(classOf[NativePlugin], Thread.currentThread().getContextClassLoader)
    installProviders(loader.iterator().asScala.toList)

  private[plugin] def installProviders(providers: List[NativePlugin]): Int =
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
