package scalascript.config

/** Ergonomic accessor for a merged config tree.
 *  Used by the interpreter's `config` intrinsic and by generated code.
 *
 *  {{{
 *  val port   = config.getInt("server.port").getOrElse(8080)
 *  val host   = config.getString("server.host").getOrElse("localhost")
 *  val enable = config.getBool("features.dark-mode").getOrElse(false)
 *  }}}
 */
final class ConfigAccessor(private val root: ConfigValue):

  def getString(path: String): Option[String]  = root.get(path).flatMap(_.getString)
  def getInt(path: String): Option[Int]         = root.get(path).flatMap(_.getInt)
  def getDouble(path: String): Option[Double]   = root.get(path).flatMap(_.getDouble)
  def getBool(path: String): Option[Boolean]    = root.get(path).flatMap(_.getBool)
  def getValue(path: String): Option[ConfigValue] = root.get(path)

  def require[A](path: String, get: String => Option[A], typeName: String): A =
    root.get(path) match
      case None     => throw ConfigError.MissingKey(path)
      case Some(cv) => get(path) match
        case Some(v) => v
        case None    => throw ConfigError.TypeError(path, typeName, cv.getClass.getSimpleName)

  def requireString(path: String): String  = getString(path).getOrElse(throw ConfigError.MissingKey(path))
  def requireInt(path: String): Int         = getInt(path).getOrElse(throw ConfigError.MissingKey(path))
  def requireBool(path: String): Boolean    = getBool(path).getOrElse(throw ConfigError.MissingKey(path))

  /** Decode a section of the config as type `T`.
   *  {{{
   *  case class Srv(port: Int, host: String) derives Config
   *  val srv = acc[Srv]("server")
   *  }}}
   */
  def apply[A](section: String)(using ConfigDecoder[A]): Either[ConfigError, A] =
    root.get(section) match
      case None     => Left(ConfigError.MissingKey(section))
      case Some(cv) => ConfigDecoder[A].decode(cv)

  /** Decode the entire config root as type `T`. */
  def as[A](using ConfigDecoder[A]): Either[ConfigError, A] =
    ConfigDecoder[A].decode(root)

  /** Sub-accessor rooted at `section`. Returns an accessor over the subtree, or empty if not found. */
  def section(name: String): ConfigAccessor =
    new ConfigAccessor(root.get(name).getOrElse(ConfigValue.empty))

  /** Raw underlying value — for tests and serialization. */
  def raw: ConfigValue = root

object ConfigAccessor:
  val empty: ConfigAccessor = new ConfigAccessor(ConfigValue.empty)
  def fromRegistry(): ConfigAccessor = new ConfigAccessor(ConfigRegistry.get)
