package ssc.plugin

import ssc.{Emit, Runtime, Show, Value}

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
    Emit.globalsRef = collection.mutable.HashMap.empty[String, Value]
    NativePluginHost.loadAll()

  /** Same observable final-value contract as the standard native CLI lane. */
  def report(result: Value): Unit = result match
    case Value.UnitV => ()
    case op @ Value.DataV("Op", fields) if Runtime.isAutoThreadOp(op) =>
      val label = fields.headOption.collect { case Value.StrV(s) => s }.getOrElse("<unknown>")
      throw new RuntimeException(s"unhandled runtime effect: $label")
    case Value.DataV("Stub", fields) =>
      val label = fields.headOption.collect { case Value.StrV(s) => s }.getOrElse("<unknown>")
      throw new RuntimeException(s"unresolved runtime dispatch: $label")
    case other => println(Show.show(other))
