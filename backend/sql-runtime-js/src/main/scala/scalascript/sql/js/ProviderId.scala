package scalascript.sql.js

import scalascript.db.DbScheme

/** Provider identifiers for browser-side SQL execution.
 *
 *  Mirrors the dispatch table in `sql-runtime.mjs`'s `Providers.fromUrl`.
 *  The Scala-side view exists for build-time decisions: backend-js /
 *  backend-node / backend-wasm consult [[ProviderId.fromUrl]] over a
 *  module's `databases:` entries to decide which npm package deps to
 *  emit (`sql.js`, `@duckdb/duckdb-wasm`) and which to skip.
 *
 *  Spec: docs/browser-sql.md § "URL → provider dispatch".
 */
enum ProviderId(val id: String, val scheme: DbScheme, val npmPackage: String, val npmVersionRange: String):
  /** The URL prefix this provider handles — kept for stable API surface. */
  def urlPrefix: String = scheme.prefix

  case SqlJs      extends ProviderId("sql.js",       DbScheme.Sqlite,     "sql.js",                 "^1.10.3")
  case SqliteWasm extends ProviderId("sqlite-wasm",  DbScheme.SqliteOpfs, "@sqlite.org/sqlite-wasm", "^3.46.0")
  case DuckDbWasm extends ProviderId("duckdb-wasm",  DbScheme.DuckDb,     "@duckdb/duckdb-wasm",     "^1.28.0")

/** URL → provider dispatch.  Pure: no I/O, no engine init.  Same
 *  semantics as the JS-side `Providers.fromUrl` so build-time and
 *  runtime agree on which URLs route where. */
object ProviderId:

  /** Resolve a `databases:` URL to its provider.
   *
   *  Returns [[Left]] with a human-readable error message for
   *  unrecognised or JVM-only URLs (caller decides whether to surface
   *  as a `Diagnostic` or to throw).  `jdbc:` URLs get the dedicated
   *  [[unsupportedJdbcUrl]] phrasing; all other unsupported URLs get a
   *  generic message listing the supported prefixes. */
  def fromUrl(url: String): Either[String, ProviderId] =
    if url == null  then Left("URL must be non-null")
    else if url.isEmpty then Left("URL must be non-empty")
    else if url.startsWith("jdbc:") then Left(unsupportedJdbcUrl(url))
    else
      values.find(p => url.startsWith(p.scheme.prefix)) match
        case Some(p) => Right(p)
        case None    =>
          val supported = values.map(_.scheme.prefix).mkString(", ")
          Left(s"""URL "$url" is not supported on JS-family targets; use $supported instead, or run on the JVM target""")

  /** Error message for `jdbc:` URLs on JS-family targets. */
  def unsupportedJdbcUrl(url: String): String =
    val supported = values.map(_.scheme.prefix).mkString(", ")
    s"""JDBC URL "$url" is not supported on JS-family targets; use $supported instead, or run on the JVM target"""

  /** All declared provider ids — useful for Phase 4's `package.json`
   *  emit, which lists deps for every provider referenced by the
   *  module's `databases:` map. */
  val all: Set[ProviderId] = values.toSet
