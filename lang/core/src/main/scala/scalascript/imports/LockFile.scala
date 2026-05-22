package scalascript.imports

import java.security.MessageDigest
import scala.util.Try
import scala.jdk.CollectionConverters.*

/** `ssc.lock` — integrity manifest for URL and dep imports.
 *
 *  Stored as YAML alongside the entry-point `.ssc` file:
 *
 *  ```yaml
 *  version: 1
 *  imports:
 *    https://example.com/lib.ssc:
 *      sha256: abc123...
 *      fetchedAt: "2026-05-18"
 *    dep:org.example/lib:1.2:
 *      resolvedUrl: https://packages.example.com/ssc/org.example/lib/1.2.ssc
 *      sha256: def456...
 *      fetchedAt: "2026-05-18"
 *  ```
 *
 *  Design (v1.19 §10 hard-no):
 *  - If `ssc.lock` exists but a URL is absent → build error
 *  - If `ssc.lock` is absent → build error (run `ssc lock` first)
 *  - Hash mismatch → build error
 */
case class LockFile(entries: Map[String, LockFile.Entry]):

  /** Verify `content` against the recorded SHA-256 for `url`.
   *  Returns `Right(())` on match, `Left(msg)` on mismatch or absent. */
  def check(url: String, content: Array[Byte]): Either[String, Unit] =
    entries.get(url) match
      case None =>
        Left(s"URL not in ssc.lock: $url\nRun `ssc lock` to pin it.")
      case Some(e) =>
        val actual = LockFile.sha256hex(content)
        if actual == e.sha256 then Right(())
        else Left(s"Integrity check failed for $url\n  expected: ${e.sha256}\n  actual:   $actual")

  /** Return a new `LockFile` with `url` → `content` recorded (or updated). */
  def pin(url: String, content: Array[Byte], resolvedUrl: Option[String] = None): LockFile =
    val entry = LockFile.Entry(
      sha256      = LockFile.sha256hex(content),
      fetchedAt   = java.time.LocalDate.now.toString,
      resolvedUrl = resolvedUrl
    )
    copy(entries = entries + (url -> entry))

  def isEmpty: Boolean = entries.isEmpty

object LockFile:

  case class Entry(
    sha256:      String,
    fetchedAt:   String,
    resolvedUrl: Option[String] = None
  )

  val empty: LockFile = LockFile(Map.empty)

  private val yaml = org.yaml.snakeyaml.Yaml()

  def read(path: os.Path): Try[LockFile] = Try {
    if !os.exists(path) then
      throw new java.io.FileNotFoundException(s"No ssc.lock at $path — run `ssc lock` first.")
    val raw = Option(yaml.load[java.util.Map[String, Any]](os.read(path)))
      .map(_.asScala.toMap).getOrElse(Map.empty)
    val imports: Map[String, Entry] = raw.get("imports") match
      case Some(m: java.util.Map[?, ?]) =>
        m.asScala.collect {
          case (url: String, e: java.util.Map[?, ?]) =>
            val em = e.asInstanceOf[java.util.Map[String, Any]].asScala
            url -> Entry(
              sha256      = em.get("sha256").map(_.toString).getOrElse(""),
              fetchedAt   = em.get("fetchedAt").map(_.toString).getOrElse(""),
              resolvedUrl = em.get("resolvedUrl").map(_.toString)
            )
        }.toMap
      case _ => Map.empty
    LockFile(imports)
  }

  def write(lock: LockFile, path: os.Path): Unit =
    os.makeDir.all(path / os.up)
    os.write.over(path, toYaml(lock))

  def toYaml(lock: LockFile): String =
    val sb = new StringBuilder("version: 1\nimports:\n")
    lock.entries.toList.sortBy(_._1).foreach { case (url, e) =>
      sb.append(s"  ${yamlQuote(url)}:\n")
      sb.append(s"    sha256: ${e.sha256}\n")
      sb.append(s"    fetchedAt: \"${e.fetchedAt}\"\n")
      e.resolvedUrl.foreach { r =>
        sb.append(s"    resolvedUrl: ${yamlQuote(r)}\n")
      }
    }
    sb.toString

  def sha256hex(content: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(content).map("%02x".format(_)).mkString

  private def yamlQuote(s: String): String =
    if s.contains(':') || s.contains('#') || s.contains('"') || s.contains('\'')
    then s""""${s.replace("\\", "\\\\").replace("\"", "\\\"")}""""
    else s
