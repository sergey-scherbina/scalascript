package scalascript.compiler.plugin

import org.yaml.snakeyaml.Yaml
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Filesystem-based registry mirror that maps short package names to
 *  download URLs.  Enables `ssc plugin install redis` instead of
 *  specifying a full HTTPS URL.
 *
 *  Registry file format (`~/.scalascript/registry.yaml`):
 *  {{{
 *  packages:
 *    org.example.redis:
 *      url:         https://releases.example.com/redis-1.2.sscpkg
 *      version:     1.2.0
 *      description: Redis client for ScalaScript
 *    redis:                      # short-name alias → same entry
 *      url:         https://releases.example.com/redis-1.2.sscpkg
 *      version:     1.2.0
 *  }}}
 *
 *  Multiple registry files are merged; later files win on id collision. */
object LocalRegistry:

  case class Entry(
    id:          String,
    url:         String,
    version:     String        = "",
    description: String        = "",
  )

  /** Default registry search paths.  The user-writable file comes last
   *  so it can override built-in entries. */
  def defaultRegistryPaths: List[os.Path] =
    List(os.home / ".scalascript" / "registry.yaml")

  /** Resolve a name (full id or short alias) to a registry `Entry`.
   *  Returns `None` if the name is not found in any registry file. */
  def resolve(name: String, paths: List[os.Path] = defaultRegistryPaths): Option[Entry] =
    loadAll(paths).find(e => e.id == name)

  /** Load all entries from all registry files, merged in order. */
  def loadAll(paths: List[os.Path] = defaultRegistryPaths): List[Entry] =
    paths
      .filter(os.exists)
      .flatMap(p => parseFile(p).getOrElse(Nil))
      .foldLeft(Map.empty[String, Entry]) { (acc, e) => acc + (e.id -> e) }
      .values
      .toList
      .sortBy(_.id)

  /** Parse one registry YAML file. */
  def parseFile(path: os.Path): Try[List[Entry]] = Try {
    val raw = new Yaml().load[java.util.Map[String, Any]](os.read(path))
    if raw == null then Nil
    else {
      val m = raw.asScala.toMap
      m.get("packages") match
        case Some(pm: java.util.Map[?, ?]) =>
          pm.asScala.toList.flatMap { case (idKey, value) =>
            val id = idKey.toString
            value match
              case vm: java.util.Map[?, ?] =>
                val fields = vm.asInstanceOf[java.util.Map[String, Any]].asScala
                val url    = fields.get("url").map(_.toString).getOrElse("")
                if url.isEmpty then Nil
                else List(Entry(
                  id          = id,
                  url         = url,
                  version     = fields.get("version").map(_.toString).getOrElse(""),
                  description = fields.get("description").map(_.toString).getOrElse(""),
                ))
              case _ => Nil
          }
        case _ => Nil
    }
  }

  /** Serialise a list of entries to YAML text suitable for writing to
   *  a registry file (e.g. after `ssc plugin registry add`). */
  def toYaml(entries: List[Entry]): String =
    if entries.isEmpty then "packages: {}\n"
    else
      val sb = new StringBuilder("packages:\n")
      entries.sortBy(_.id).foreach { e =>
        sb ++= s"  ${e.id}:\n"
        sb ++= s"    url: ${e.url}\n"
        if e.version.nonEmpty  then sb ++= s"    version: ${e.version}\n"
        if e.description.nonEmpty then sb ++= s"    description: \"${e.description}\"\n"
      }
      sb.toString
