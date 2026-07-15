package ssc.plugin

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import ssc.{Emit, PortableEffects, Runtime, Show, Value}

/** Runtime entry contract for a persisted direct-ASM ScalaScript artifact.
 *
 * This lives beside the core-free native plugin host so generated classes do
 * not depend on the v1 CLI, frontend bridge, interpreter, or compiler graph.
 */
object NativeArtifactRuntime:
  def initialize(rawArgs: Array[String]): Unit =
    val args = rawArgs.toList match
      case "--" :: rest => rest
      case rest         => rest
    Runtime.argv = args
    // Concurrent-safe (see Runtime.compileWithGlobals): `@`-cell first-touch writes race with
    // concurrent handler-thread reads in the artifact/ASM lane. TrieMap keeps reads lock-free.
    Emit.globalsRef = scala.collection.concurrent.TrieMap.empty[String, Value]
    NativePluginHost.loadAll(loadConfig())

  /** Same observable final-value contract as the standard native CLI lane. */
  def report(rawResult: Value): Unit =
    PortableEffects.completeManaged(rawResult) match
      case Value.UnitV => ()
      case op @ Value.DataV("Op", fields) if Runtime.isAutoThreadOp(op) =>
        val label = fields.headOption.collect { case Value.StrV(s) => s }.getOrElse("<unknown>")
        throw new RuntimeException(s"unhandled runtime effect: $label")
      case Value.DataV("Stub", fields) =>
        val label = fields.headOption.collect { case Value.StrV(s) => s }.getOrElse("<unknown>")
        throw new RuntimeException(s"unresolved runtime dispatch: $label")
      case other => println(Show.show(other))

  private def loadConfig(): NativeRuntimeConfig =
    val resource = Option(getClass.getClassLoader.getResourceAsStream(
      "META-INF/scalascript/artifact.properties"))
    resource match
      case None => NativeRuntimeConfig()
      case Some(stream) =>
        val props = new Properties()
        val reader = new InputStreamReader(stream, StandardCharsets.UTF_8)
        try props.load(reader)
        finally reader.close()

        val count = Option(props.getProperty("database.count"))
          .flatMap(_.toIntOption).getOrElse(0)
        val databases = (0 until count).map { index =>
          def required(field: String): String =
            Option(props.getProperty(s"database.$index.$field")).getOrElse {
              throw new IllegalStateException(
                s"artifact metadata is missing database.$index.$field")
            }
          def optional(field: String): Option[String] =
            if required(s"$field.present").toBoolean then Some(required(field)) else None

          required("name") -> NativeDatabaseConfig(
            url = required("url"),
            user = optional("user"),
            password = optional("password"),
            driver = optional("driver"))
        }.toMap
        NativeRuntimeConfig(databases, loadContent())

  private def loadContent(): List[NativeContentModule] =
    Option(getClass.getClassLoader.getResourceAsStream("META-INF/scalascript/content.bin")) match
      case None => Nil
      case Some(stream) =>
        try NativeContentCodec.decode(stream.readAllBytes())
        finally stream.close()
