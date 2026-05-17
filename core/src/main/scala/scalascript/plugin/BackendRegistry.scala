package scalascript.plugin

import scalascript.backend.spi.{Backend, InteractiveBackend}
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** Central registry for `Backend` plugins.
 *
 *  Per docs/backend-spi.md §12.1:  in-process discovery uses
 *  `java.util.ServiceLoader`.  Each backend module ships a
 *  `META-INF/services/scalascript.backend.spi.Backend` file listing
 *  its `Backend` implementation class.
 *
 *  Stage 5.2: ServiceLoader-only discovery from the bundled CLI
 *  classpath.  Stage 6 adds plugin-dir / `--plugin` flag paths plus
 *  per-plugin classloader isolation. */
object BackendRegistry:

  /** Force-reload from the current thread's ContextClassLoader.  Tests
   *  use this if they install plugins via classloader after startup. */
  def reload(): Unit = ()  // ServiceLoader.iterator is lazy; nothing cached yet

  /** Every backend the runtime can see, in classpath / META-INF order. */
  lazy val all: List[Backend] =
    ServiceLoader
      .load(classOf[Backend])
      .iterator
      .asScala
      .toList

  /** Look up a backend by its declared id (`"jvm"`, `"js"`, `"int"`, …). */
  def lookup(id: String): Option[Backend] = all.find(_.id == id)

  /** Backends that declare they can embed a given source language
   *  (`"scala"`, `"html"`, …).  Used by the CLI when the user picks
   *  a target and core needs to verify the chosen target accepts
   *  every foreign-language fence the module contains. */
  def acceptingSource(language: String): List[Backend] =
    all.filter(_.acceptedSources.contains(language))

  /** All interactive backends — the subset used by `ssc serve` and
   *  future REPL modes. */
  def interactive: List[InteractiveBackend] =
    all.collect { case b: InteractiveBackend => b }

  /** One-line description per backend, intended for `--list-backends`. */
  def describe: String =
    if all.isEmpty then "(no backends registered)"
    else
      all
        .map(b => f"${b.id}%-14s ${b.displayName}  [spi=${b.spiVersion}]")
        .mkString("\n")
