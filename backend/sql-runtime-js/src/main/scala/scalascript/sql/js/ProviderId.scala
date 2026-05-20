package scalascript.sql.js

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
enum ProviderId(val id: String, val urlPrefix: String, val npmPackage: String, val npmVersionRange: String):
  case SqlJs        extends ProviderId("sql.js",      "sqlite:", "sql.js",                  "^1.10.3")
  case DuckDbWasm   extends ProviderId("duckdb-wasm", "duckdb:", "@duckdb/duckdb-wasm",     "^1.28.0")

/** URL → provider dispatch.  Pure: no I/O, no engine init.  Same
 *  semantics as the JS-side `Providers.fromUrl` so build-time and
 *  runtime agree on which URLs route where. */
object ProviderId:

  /** Resolve a `databases:` URL to its provider.
   *
   *  Returns [[Left]] with a human-readable error message for
   *  unrecognised URLs (caller decides whether to surface as a
   *  `Diagnostic` or to throw).  `jdbc:` URLs are recognised but
   *  rejected with the dedicated [[unsupportedJdbcUrl]] message —
   *  the validate-time `UnsupportedJdbcUrl` diagnostic (Phase 6) is
   *  the build-time enforcement; this is the Scala-side phrasing for
   *  any direct caller. */
  def fromUrl(url: String): Either[String, ProviderId] =
    if url == null then Left("URL must be non-null")
    else if url.startsWith("sqlite:")  then Right(SqlJs)
    else if url.startsWith("duckdb:")  then Right(DuckDbWasm)
    else if url.startsWith("jdbc:")    then Left(unsupportedJdbcUrl(url))
    else
      val supported = values.map(_.urlPrefix).mkString(", ")
      Left(s"""No provider matches URL "$url". Supported prefixes: $supported""")

  /** Error message for `jdbc:` URLs on JS-family targets.  Phase 6's
   *  `Diagnostic.UnsupportedJdbcUrl` produces the same phrasing
   *  (test-pinned). */
  def unsupportedJdbcUrl(url: String): String =
    s"""JDBC URL "$url" is not supported on JS-family targets; use sqlite: or duckdb: instead, or run on the JVM target"""

  /** All declared provider ids — useful for Phase 4's `package.json`
   *  emit, which lists deps for every provider referenced by the
   *  module's `databases:` map. */
  val all: Set[ProviderId] = values.toSet
