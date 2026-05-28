package scalascript.backend.spi

/** Stable identity for code that participates in a ScalaScript cluster.
 *
 *  `digest` is the lowercase SHA-256 hex of the canonical artifact bytes.
 *  Interpreted `.ssc` modules use source bytes when available; precompiled
 *  `.sscc` modules use the canonical `.sscc` payload bytes.
 */
final case class CodeIdentity(
    algorithm: String,
    digest:    String,
    format:    String,
    module:    Option[String] = None
):
  def sameCodeAs(other: CodeIdentity): Boolean =
    algorithm == other.algorithm && digest == other.digest && format == other.format

object CodeIdentity:
  val Empty: CodeIdentity = CodeIdentity("sha256", "", "unknown", None)
