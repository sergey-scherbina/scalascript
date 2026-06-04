package scalascript.cli

import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** ServiceLoader-backed registry of `CliCommand` providers. Discovery is from
 *  `META-INF/services/scalascript.cli.CliCommand`; see docs/specs/cli-command-spi.md. */
object CommandRegistry:

  private lazy val cache: List[CliCommand] =
    ServiceLoader
      .load(classOf[CliCommand], getClass.getClassLoader)
      .iterator()
      .asScala
      .toList

  /** All discovered commands, in ServiceLoader order. */
  def all: List[CliCommand] = cache

  // name + each alias → command; first registration wins on collision.
  private lazy val byName: Map[String, CliCommand] =
    val b = scala.collection.mutable.LinkedHashMap.empty[String, CliCommand]
    for c <- cache; token <- c.name :: c.aliases do
      b.get(token) match
        case Some(existing) =>
          System.err.println(
            s"ssc: duplicate command token '$token' (${existing.getClass.getName} " +
            s"and ${c.getClass.getName}); keeping the first")
        case None => b(token) = c
    b.toMap

  /** Resolve a subcommand token to its command, if registered. */
  def lookup(token: String): Option[CliCommand] = byName.get(token)

  /** Run the command for `token` with `args`, returning the command result when
   *  registered. `None` means no command matched, so callers can supply their
   *  own fallback. */
  def dispatchResult(token: String, args: List[String]): Option[CommandResult] =
    lookup(token).map(_.runResult(args))

  /** Run the command for `token` with `args`. Returns false (without running
   *  anything) if no command is registered, so callers can supply their own
   *  fallback. Used for command-to-command invocation (script runner, jar
   *  launcher, watch shorthand) instead of calling command functions directly.
   *
   *  This compatibility helper intentionally preserves the old Boolean shape;
   *  callers that need exit-code propagation should use `dispatchResult`. */
  def dispatch(token: String, args: List[String]): Boolean =
    dispatchResult(token, args).isDefined
