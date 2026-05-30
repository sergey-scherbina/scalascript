package scalascript.cli

import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** ServiceLoader-backed registry of `CliCommand` providers. Discovery is from
 *  `META-INF/services/scalascript.cli.CliCommand`; see docs/cli-command-spi.md. */
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
