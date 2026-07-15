package scalascript.compiler.plugin.scljetjdbc

import scalascript.interpreter.Value

import java.nio.file.{Files, Path, Paths}
import java.sql.{Connection, Driver, DriverPropertyInfo, SQLException, SQLFeatureNotSupportedException}
import java.util.Properties
import java.util.logging.Logger

/** `java.sql.Driver` for `jdbc:scljet:<target>` — the JVM lane of
 *  `specs/scljet-jdbc.md`.  Registered via `META-INF/services/java.sql.Driver`
 *  and a static `DriverManager.registerDriver`, so
 *  `DriverManager.getConnection("jdbc:scljet:…")` works with no caller changes.
 *
 *  It never intercepts `jdbc:sqlite:` — a distinct scheme, so the Xerial driver
 *  is untouched.  Every call delegates to the pure engine via [[ScljetEngine]]. */
final class ScljetDriver extends Driver:
  import ScljetDriver.*

  def acceptsURL(url: String): Boolean = url != null && url.startsWith(Prefix)

  def connect(url: String, info: Properties): Connection =
    if !acceptsURL(url) then return null   // JDBC contract: not our URL
    ScljetEngine.ensureLoaded()
    val parsed = parseUrl(url)
    val (initialImage, durablePath, readOnly) = openTarget(parsed)
    val connValue = ScljetEngine.call("jdbcOpen", initialImage)
    val state = ScljetConnectionState(url, connValue, durablePath, readOnly)
    ScljetConnection.make(state)

  def getMajorVersion: Int = ScljetVersion.Major
  def getMinorVersion: Int = ScljetVersion.Minor
  def jdbcCompliant(): Boolean = false

  def getPropertyInfo(url: String, info: Properties): Array[DriverPropertyInfo] =
    KnownParams.map { case (key, desc) =>
      val p = DriverPropertyInfo(key, null)
      p.description = desc
      p
    }.toArray

  def getParentLogger(): Logger =
    throw SQLFeatureNotSupportedException("scljet JDBC: java.util.logging is not used")

object ScljetDriver:
  val Prefix = "jdbc:scljet:"
  private val DefaultPageSize = 4096

  private val KnownParams: Seq[(String, String)] = Seq(
    "mode"         -> "ro | rw | rwc (default) | memory",
    "journal"      -> "delete | truncate | persist | memory | wal | off",
    "sync"         -> "off | normal | full (default) | extra",
    "busy_timeout" -> "busy timeout in milliseconds",
    "cache_pages"  -> "page cache size (pages)",
    "page_size"    -> "page size for a newly created database (power of two, 512..65536)",
    "vfs"          -> "image (default) | jvm",
  )

  // Register on class-load so `DriverManager.getConnection` finds us even
  // without ServiceLoader (belt and braces alongside META-INF/services).
  try java.sql.DriverManager.registerDriver(new ScljetDriver)
  catch case e: SQLException => System.err.println(s"[scljet-jdbc] driver registration failed: ${e.getMessage}")

  final case class ParsedUrl(target: String, params: Map[String, String])

  def parseUrl(url: String): ParsedUrl =
    val rest = url.substring(Prefix.length)
    val qIdx = rest.indexOf('?')
    val (target, query) = if qIdx < 0 then (rest, "") else (rest.substring(0, qIdx), rest.substring(qIdx + 1))
    val params = if query.isEmpty then Map.empty[String, String]
      else query.split("&").iterator.flatMap { kv =>
        val eq = kv.indexOf('=')
        if eq < 0 then None else Some(kv.substring(0, eq) -> kv.substring(eq + 1))
      }.toMap
    ParsedUrl(target, params)

  private def pageSize(params: Map[String, String]): Int =
    params.get("page_size").flatMap(s => s.toIntOption).getOrElse(DefaultPageSize)

  /** Resolve a target to (initial image Value, optional durable file, readOnly). */
  private def openTarget(parsed: ParsedUrl): (Value, Option[Path], Boolean) =
    val readOnlyParam = parsed.params.get("mode").contains("ro")
    parsed.target match
      case ":memory:" =>
        (ScljetEngine.emptyImage(pageSize(parsed.params)), None, readOnlyParam)

      case t if t.startsWith("classpath:") =>
        val res = t.substring("classpath:".length)
        val stream = Option(getClass.getClassLoader.getResourceAsStream(res))
          .getOrElse(throw SQLException(s"scljet JDBC: classpath resource not found: $res", "08001"))
        val bytes = try stream.readAllBytes() finally stream.close()
        (ScljetEngine.byteSlice(bytes), None, true) // classpath images are read-only

      case t =>
        val pathStr = if t.startsWith("file:") then t.substring("file:".length) else t
        val path = Paths.get(pathStr).toAbsolutePath.normalize()
        if Files.exists(path) then
          val image = ScljetEngine.byteSlice(Files.readAllBytes(path))
          (image, if readOnlyParam then None else Some(path), readOnlyParam)
        else if readOnlyParam then
          throw SQLException(s"scljet JDBC: read-only file does not exist: $path", "08001")
        else
          // Create-on-open: fresh empty image, durable to this path.
          val image = ScljetEngine.emptyImage(pageSize(parsed.params))
          Option(path.getParent).foreach(p => if !Files.exists(p) then Files.createDirectories(p))
          (image, Some(path), false)
