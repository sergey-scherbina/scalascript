package scalascript.db

/** Canonical URL scheme for a `databases:` front-matter entry.
 *
 *  Users write the canonical prefix in `.ssc` front-matter; each backend
 *  translates it to whatever form it natively needs.  Adding support for a
 *  new database means adding one row here — no per-backend changes required.
 *
 *  `jdbcPrefix`  — JVM/JDBC form: canonical path is appended verbatim.
 *  `jsPrefix`    — JS-native form if the scheme is supported in browser/Node/Wasm;
 *                  `None` means JVM-only. */
enum DbScheme(
  val prefix:    String,
  val jdbcPrefix: String,
  val jsPrefix:  Option[String]
):
  case Sqlite     extends DbScheme("sqlite:",      "jdbc:sqlite:",  Some("sqlite:"))
  case SqliteOpfs extends DbScheme("sqlite-opfs:", "jdbc:sqlite:",  Some("sqlite-opfs:"))
  case DuckDb     extends DbScheme("duckdb:",      "jdbc:duckdb:",  Some("duckdb:"))
  case H2       extends DbScheme("h2:",       "jdbc:h2:",           None)
  case Postgres extends DbScheme("postgres:", "jdbc:postgresql:",   None)
  case Mysql    extends DbScheme("mysql:",    "jdbc:mysql:",        None)
  case Mssql    extends DbScheme("mssql:",    "jdbc:sqlserver:",    None)

object DbUrl:

  /** Canonical scheme for `url`, or `None` if the URL is already in JDBC
   *  form (`jdbc:…`) or uses an unrecognised prefix. */
  def schemeOf(url: String): Option[DbScheme] =
    if url == null || url.startsWith("jdbc:") then None
    else DbScheme.values.find(s => url.startsWith(s.prefix))

  /** Translate a canonical URL to JDBC form.
   *  Already-`jdbc:` URLs are returned unchanged. */
  def toJdbc(url: String): String =
    schemeOf(url) match
      case Some(s) => s.jdbcPrefix + url.stripPrefix(s.prefix)
      case None    => url

  /** Translate a canonical URL to its JS-native form.
   *  Returns `url` unchanged when already in native form or not JS-supported. */
  def toNative(url: String): String =
    schemeOf(url) match
      case Some(s) => s.jsPrefix.map(_ + url.stripPrefix(s.prefix)).getOrElse(url)
      case None    => url

  /** True if `url` can run on JS-family (browser / Node / Wasm) targets. */
  def isJsSupported(url: String): Boolean =
    schemeOf(url).exists(_.jsPrefix.isDefined) || {
      // A jdbc: URL is never JS-supported; an unrecognised URL — unknown.
      // We conservatively return false (will surface as UnsupportedDbUrl).
      false
    }

  /** All supported canonical prefixes — for error messages and validation. */
  def supportedPrefixes: Seq[String] = DbScheme.values.map(_.prefix).toSeq
