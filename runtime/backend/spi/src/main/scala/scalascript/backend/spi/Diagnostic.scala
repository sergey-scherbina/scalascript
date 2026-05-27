package scalascript.backend.spi

import scalascript.ir.QualifiedName

/** Closed sum-type of diagnostics produced by core validation passes
 *  and by backends.  CLI / serve mode render these for the user;
 *  `CompileResult.Failed` is the carrier when validation refuses
 *  to invoke compilation. */
enum Diagnostic:
  case Unsupported(feature: Feature, backend: String)
  case UnknownIntrinsic(name: QualifiedName, backend: String)
  case UnknownBlockLanguage(language: String)
  case Generic(message: String, source: Option[String] = None)
  // v1.27 Phase 6 — JS-family targets (js / node / wasm) declare
  // `sql` in `blockLanguages` but accept only `sqlite:` / `duckdb:`
  // URLs (handled by `backend-sql-runtime-js`).  A `jdbc:*` URL in a
  // module that compiles for one of those targets surfaces as this
  // build-time diagnostic.  Carries `db` (front-matter entry name),
  // `url` (the offending JDBC URL verbatim), and `backend` (target
  // id) so the renderer can point the user at the JVM target or at
  // changing the URL scheme.  JVM / interpreter targets accept jdbc:
  // URLs natively, so this diagnostic never fires for them.
  case UnsupportedJdbcUrl(db: String, url: String, backend: String)
  // v1.30 — a `sql` block carrying `@side=client` references a
  // `databases:` entry whose URL scheme is not supported in the
  // browser (e.g. `h2:`, `postgres:`).  Only JS-supported schemes
  // (`sqlite:`, `sqlite-opfs:`, `duckdb:`) are allowed on the client side.
  case UnsupportedClientSideDbUrl(db: String, url: String, block: String)
  // v1.55.4 — a `xml"..."` interpolation whose static parts (joined with
  // placeholder text for dynamic holes) do not form well-formed XML.
  // `message` is the parse-error description; `line` / `col` are
  // 1-indexed positions within the reconstructed candidate string so the
  // user can locate the broken construct.
  case XmlParseError(message: String, line: Int, col: Int)
