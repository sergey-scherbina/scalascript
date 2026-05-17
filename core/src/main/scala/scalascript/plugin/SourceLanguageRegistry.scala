package scalascript.plugin

import scalascript.backend.spi.SourceLanguage
import java.util.ServiceLoader
import scala.jdk.CollectionConverters.*

/** Central registry for `SourceLanguage` plugins (docs/backend-spi.md §9).
 *
 *  Mirrors `BackendRegistry`: ServiceLoader-based discovery from the
 *  classpath, plus extension hooks for `--plugin <jar>` /
 *  `--plugin-dir <dir>`.
 *
 *  Stage 9 scope:  registry surface + skeleton.  Parser doesn't yet
 *  consult this for `scalascript`/`ssc` blocks (host language stays in
 *  core) or for `html` / `css` / `scala` blocks (still hardcoded in
 *  JvmGen / JsGen / Interpreter — Stage 9 follow-up extracts them).
 *  Third-party plugins claiming new fence tags can register today and
 *  the BackendRegistry-style API works; consumption is the missing
 *  piece. */
object SourceLanguageRegistry:

  private val extraJars = scala.collection.mutable.ListBuffer.empty[os.Path]
  private var cache: List[SourceLanguage] = null

  def reload(): Unit =
    cache = null

  def addPluginJar(jar: os.Path): Unit =
    extraJars += jar
    cache = null

  def all: List[SourceLanguage] =
    if cache == null then
      val loader =
        if extraJars.isEmpty then classOf[SourceLanguage].getClassLoader
        else
          val urls = extraJars.map(_.toIO.toURI.toURL).toArray
          new java.net.URLClassLoader(urls, classOf[SourceLanguage].getClassLoader)
      cache = ServiceLoader
        .load(classOf[SourceLanguage], loader)
        .iterator
        .asScala
        .toList
    cache

  /** Look up by canonical name OR any alias.  Returns the first match
   *  in classpath order. */
  def lookup(language: String): Option[SourceLanguage] =
    all.find(sl => sl.canonicalName == language || sl.aliases.contains(language))

  /** Set of fence tags the registered plugins claim to handle. */
  def knownLanguages: Set[String] =
    all.flatMap(sl => sl.canonicalName :: sl.aliases.toList).toSet

  /** One-line description per plugin, for `--list-source-languages`. */
  def describe: String =
    if all.isEmpty then "(no source-language plugins registered)"
    else
      all
        .map(sl => f"${sl.canonicalName}%-14s ${sl.displayName}  [spi=${sl.spiVersion}]")
        .mkString("\n")
