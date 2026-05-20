package scalascript.sql.js

import java.io.ByteArrayOutputStream

/** Codegen helper for JS-family backends.  Phase 3 (backend-js),
 *  Phase 4 (backend-node), Phase 5 (backend-wasm) all consume the
 *  same bundle preamble: the `sql-runtime.mjs` source verbatim,
 *  followed by a small registry-init block derived from the
 *  module's `manifest.databases:` map.
 *
 *  Kept in `backend-sql-runtime-js` so all three consumers share one
 *  source of truth; the .mjs source itself lives in
 *  `src/main/resources/scalascript/sql/js/sql-runtime.mjs`.
 *
 *  Spec: docs/browser-sql.md § "Module layout", § "Runtime contract".
 */
object SqlRuntimeJsEmit:

  /** Resource path of the bundled JS runtime, relative to the
   *  classpath root.  Exposed for tests and for backends that prefer
   *  to load via their own resource-resolution. */
  val ResourcePath: String =
    "scalascript/sql/js/sql-runtime.mjs"

  /** The full `sql-runtime.mjs` source, read once from the classpath
   *  resource.  Backends prepend this to every emitted bundle that
   *  contains sql blocks. */
  lazy val runtimeSource: String =
    val cl  = Thread.currentThread().getContextClassLoader
    val res = Option(cl.getResourceAsStream(ResourcePath))
      .orElse(Option(getClass.getResourceAsStream("/" + ResourcePath)))
      .getOrElse(throw new IllegalStateException(
        s"sql-runtime.mjs missing from classpath at $ResourcePath. " +
        s"Did backend-sql-runtime-js fail to package resources?"))
    try
      val buf = new ByteArrayOutputStream()
      val tmp = new Array[Byte](8192)
      var n   = res.read(tmp)
      while n > 0 do
        buf.write(tmp, 0, n)
        n = res.read(tmp)
      new String(buf.toByteArray, java.nio.charset.StandardCharsets.UTF_8)
    finally
      res.close()

  /** Emit the registry-init JS statement for a manifest's `databases:`
   *  list.  Produces:
   *
   *  {{{
   *  const _ssc_sql_registry = new ConnectionRegistry({
   *    "default":   { url: "sqlite::memory:" },
   *    "analytics": { url: "duckdb:" },
   *  })
   *  const _ssc_sql_connections = Object.create(null)
   *  async function _ssc_sql_resolve(dbName) {
   *    const name = dbName ?? "default"
   *    if (name in _ssc_sql_connections) return await _ssc_sql_connections[name]
   *    return _ssc_sql_registry.connect(name)
   *  }
   *  }}}
   *
   *  When `databases` is empty, emits an empty-registry initializer
   *  so the `@sscBrowserSqlConnection` annotation path still works
   *  standalone.  All `ConnectionRegistry` / provider references are
   *  resolved from the runtime source (which must precede this in
   *  the bundle).
   *
   *  `databases` is the list of (name, fields) pairs as parsed from
   *  the YAML front-matter — same shape `manifest.databases` carries
   *  on the JVM side.  Field keys recognised: `url` (required),
   *  `user`, `password`, `driver` (forwarded verbatim).  Unknown keys
   *  are silently dropped — the v1.26 frontmatter parser already
   *  validates the schema. */
  def emitRegistryInit(databases: Seq[DatabaseEntry]): String =
    val entries = databases.map { d =>
      val fields = Seq(
        "url"      -> Some(d.url),
        "user"     -> d.user,
        "password" -> d.password,
        "driver"   -> d.driver,
      ).collect {
        case (k, Some(v)) => s"""    $k: ${jsString(v)}"""
      }.mkString(",\n")
      s"""  ${jsString(d.name)}: {
         |$fields
         |  }""".stripMargin
    }.mkString(",\n")

    val body = if entries.isEmpty then "{}" else s"{\n$entries\n}"

    s"""const _ssc_sql_registry = new ConnectionRegistry($body)
       |const _ssc_sql_connections = Object.create(null)
       |async function _ssc_sql_resolve(dbName) {
       |  const name = dbName ?? "default"
       |  if (name in _ssc_sql_connections) return await _ssc_sql_connections[name]
       |  return _ssc_sql_registry.connect(name)
       |}
       |""".stripMargin

  /** Convenience: full preamble = `sql-runtime.mjs` source + registry
   *  init for the given databases.  Phase 3+ backends call this once
   *  per module (only if the module has any sql blocks). */
  def emitPreamble(databases: Seq[DatabaseEntry]): String =
    runtimeSource + "\n" + emitRegistryInit(databases)

  /** Minimal record shape carrying the database fields the emit helper
   *  needs.  Backends keep their own `Manifest.databases` ADTs (which
   *  reference `ir` types we deliberately don't depend on); they
   *  project to `DatabaseEntry` at emit time. */
  final case class DatabaseEntry(
      name:     String,
      url:      String,
      user:     Option[String] = None,
      password: Option[String] = None,
      driver:   Option[String] = None,
  )

  /** Escape a string for inclusion as a double-quoted JS literal.
   *  Handles \, ", \n, \r, \t, and control characters via \uXXXX.
   *  Deliberately conservative — the emitted preamble is read by
   *  V8 / SpiderMonkey, not by a permissive YAML parser. */
  private[js] def jsString(s: String): String =
    val sb = new StringBuilder(s.length + 2)
    sb.append('"')
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      c match
        case '\\' => sb.append("\\\\")
        case '"'  => sb.append("\\\"")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case ch if ch < 0x20 || ch == 0x7f =>
          sb.append("\\u%04x".format(ch.toInt))
        case ch =>
          sb.append(ch)
      i += 1
    sb.append('"')
    sb.toString
