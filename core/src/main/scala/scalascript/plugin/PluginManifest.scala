package scalascript.plugin

import org.yaml.snakeyaml.Yaml
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Success, Failure}

/** Parsed `plugin.yaml` manifest declaring an out-of-process plugin.
 *  See docs/backend-spi.md §12.2.
 *
 *  Stage 6.2 supports the Backend role's fields; SourceLanguage role
 *  fields parse but are not yet consumed by the registry. */
case class PluginManifest(
  id:              String,
  displayName:     String,
  spiVersion:      String,
  protocol:        String,                  // "stdio-json" | "stdio-msgpack"
  executable:      String,
  args:            List[String]  = Nil,
  roles:           List[String]  = List("backend"),
  // Backend role
  features:        Set[String]   = Set.empty,
  outputs:         Set[String]   = Set.empty,
  acceptedSources: Set[String]   = Set.empty,
  // SourceLanguage role
  canonicalName:   Option[String] = None,
  aliases:         Set[String]   = Set.empty,
  /** Plugin declares `openSession` support.  Stage 6+/B. */
  interactive:     Boolean      = false,
  // Originating manifest path — useful for resolving relative `executable`.
  manifestPath:    Option[os.Path] = None
):
  def isBackend:        Boolean = roles.contains("backend")
  def isSourceLanguage: Boolean = roles.contains("source-language")

  /** Resolve the `executable` field.  If the YAML entry contains a
   *  path separator (`./bin/foo`, `/usr/local/bin/foo`, `foo/bar`),
   *  resolve it against the manifest's directory; otherwise treat it
   *  as a bare command name and let `ProcessBuilder` find it on
   *  `$PATH` (e.g. `executable: scala-cli`, `python3`, `bash`). */
  def executablePath: String =
    if executable.contains('/') then
      os.Path(executable, manifestPath.map(_ / os.up).getOrElse(os.pwd)).toString
    else executable


object PluginManifest:

  def parseFile(path: os.Path): Try[PluginManifest] =
    Try(parseString(os.read(path)).get).map(_.copy(manifestPath = Some(path)))

  def parseString(yaml: String): Try[PluginManifest] = Try {
    val raw = new Yaml().load[java.util.Map[String, Any]](yaml)
    val asScala = Option(raw)
      .map(_.asScala.toMap)
      .getOrElse(throw RuntimeException("empty plugin.yaml"))

    def require[A](key: String): A =
      asScala.getOrElse(key, throw RuntimeException(s"plugin.yaml: missing required field '$key'"))
        .asInstanceOf[A]

    def optString(key: String): Option[String] =
      asScala.get(key).map(_.toString)

    def optList(key: String): List[String] =
      asScala.get(key) match
        case Some(l: java.util.List[?]) => l.asScala.map(_.toString).toList
        case _                          => Nil

    // Backend / source-language nested keys, when present
    val backendBlock = asScala.get("backend").collect {
      case m: java.util.Map[?, ?] => m.asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    }.getOrElse(Map.empty)
    val sourceBlock = asScala.get("sourceLanguage").collect {
      case m: java.util.Map[?, ?] => m.asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    }.getOrElse(Map.empty)

    def block(b: Map[String, Any], k: String): List[String] = b.get(k) match
      case Some(l: java.util.List[?]) => l.asScala.map(_.toString).toList
      case _                          => Nil

    PluginManifest(
      id              = require[String]("id"),
      displayName     = optString("displayName").getOrElse(require[String]("id")),
      spiVersion      = require[String]("spiVersion"),
      protocol        = require[String]("protocol"),
      executable      = require[String]("executable"),
      args            = optList("args"),
      roles           = optList("roles") match
                          case Nil => List("backend")
                          case rs  => rs,
      features        = block(backendBlock, "features").toSet,
      outputs         = block(backendBlock, "outputs").toSet,
      acceptedSources = block(backendBlock, "acceptedSources").toSet,
      canonicalName   = sourceBlock.get("canonicalName").map(_.toString),
      aliases         = block(sourceBlock, "aliases").toSet,
      interactive     = asScala.get("interactive").exists {
                          case b: java.lang.Boolean => b.booleanValue()
                          case s: String            => s == "true"
                          case _                    => false
                        }
    )
  }

  /** Default search paths for plugin.yaml discovery.  Per spec §12.2:
   *
   *    - `$SCALASCRIPT_PLUGIN_PATH` — colon-separated directory list
   *    - `~/.scalascript/plugins/` */
  def defaultSearchPaths: List[os.Path] =
    val envPath = sys.env.get("SCALASCRIPT_PLUGIN_PATH").getOrElse("")
    val envDirs = envPath.split(":").filter(_.nonEmpty).map(p => os.Path(p, os.pwd)).toList
    val homePath = os.home / ".scalascript" / "plugins"
    val homeDirs = if os.exists(homePath) then List(homePath) else Nil
    envDirs ++ homeDirs

  /** Walk every search path for one-level-deep `plugin.yaml` files. */
  def discover(searchPaths: List[os.Path] = defaultSearchPaths): List[PluginManifest] =
    searchPaths.flatMap { dir =>
      if !os.isDir(dir) then Nil
      else
        os.list(dir).flatMap { entry =>
          val yamlPath =
            if os.isDir(entry) then entry / "plugin.yaml"
            else if entry.last == "plugin.yaml" then entry
            else os.root  // sentinel that doesn't exist
          if os.exists(yamlPath) then
            parseFile(yamlPath) match
              case Success(m)   => List(m)
              case Failure(err) =>
                System.err.println(s"[plugin] failed to load $yamlPath: ${err.getMessage}")
                Nil
          else Nil
        }
    }
