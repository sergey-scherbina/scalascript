package scalascript.sql

import java.sql.{Connection, DriverManager}

/** Runtime configuration of one named JDBC connection — the
 *  serialisable shape the front-matter `databases:` map flattens
 *  into.  No coupling to `ast` or `ir`: callers (interpreter,
 *  JvmGen-emitted code) convert their respective `Manifest`
 *  records to this shape before handing them to
 *  `ConnectionRegistry`.
 *
 *  String values may carry `${env:NAME}` references that are resolved
 *  at the moment the connection actually opens — not at construction
 *  time.  See `EnvResolver`. */
final case class DatabaseSpec(
  name:     String,
  url:      String,
  user:     Option[String]  = None,
  password: Option[String]  = None,
  driver:   Option[String]  = None
)

/** Resolves `${env:NAME}` references in connection-config strings
 *  against the process environment.  Two distinct error types so
 *  call sites can surface the right context:
 *
 *    - `MissingEnv(varName, configKey, dbName)` — env var not set;
 *      the user needs to add it to their shell / `.env`.
 *    - `MalformedEnvRef` — never thrown today (unbalanced `${env:`
 *      is currently passed through unchanged); placeholder for
 *      future stricter resolution.
 *
 *  Resolution is repeated for every reference in the string — multiple
 *  `${env:NAME}` substitutions per URL are supported.  Plain `$` and
 *  unrelated `${...}` patterns are left untouched. */
object EnvResolver:

  private val Pattern = """\$\{env:([A-Za-z_][A-Za-z0-9_]*)\}""".r

  /** Expand every `${env:NAME}` in `template` using the supplied
   *  lookup (defaults to the process environment).  Missing vars
   *  raise `MissingEnv` with `configKey` / `dbName` context. */
  def resolve(
    template: String,
    configKey: String,
    dbName:   String,
    lookup:   String => Option[String] = name => sys.env.get(name)
  ): String =
    Pattern.replaceAllIn(template, m =>
      val name = m.group(1)
      lookup(name) match
        case Some(v) =>
          // Backslashes inside an env value would otherwise be
          // interpreted as regex backreferences by `replaceAllIn`.
          java.util.regex.Matcher.quoteReplacement(v)
        case None =>
          throw MissingEnv(name, configKey, dbName)
    )

/** Raised when a `${env:NAME}` reference in a connection-config string
 *  does not resolve.  Names the variable, the field within the
 *  database entry, and the database alias so users can fix the
 *  problem without grepping. */
final class MissingEnv(val variable: String, val configKey: String, val dbName: String)
    extends RuntimeException(
      s"environment variable `$variable` referenced from databases.$dbName.$configKey is not set"
    )

/** Raised when an `sql` block resolves to a database name that has
 *  no entry in the registry.  `available` lists the configured
 *  names so a typo is easy to spot. */
final class UnknownDatabase(val name: String, val available: Iterable[String])
    extends RuntimeException(
      s"no JDBC connection named `$name` declared in front-matter `databases:` " +
        s"— have [${available.mkString(", ")}]"
    )

/** Caches named JDBC connections for the lifetime of a module run.
 *
 *  Lazy: a connection is opened the first time its name is requested
 *  and reused for every subsequent `sql` block targeting it.  Thread-
 *  safe — internal map is guarded by a synchronized block.  Idempotent
 *  on `close()`.
 *
 *  Driver registration: relies on the JDBC `ServiceLoader` mechanism
 *  for bundled (H2, SQLite) and `dep:`-imported drivers.  If a
 *  `DatabaseSpec` carries an explicit `driver` class name we
 *  belt-and-braces `Class.forName` it before opening, so environments
 *  where the service loader is disabled (some JLink images) still
 *  work. */
final class ConnectionRegistry(specs: Iterable[DatabaseSpec], envLookup: String => Option[String]):

  private val byName: Map[String, DatabaseSpec] =
    specs.map(s => s.name -> s).toMap

  private val cache = scala.collection.mutable.Map.empty[String, Connection]

  /** Convenience: build a registry against the process environment. */
  def this(specs: Iterable[DatabaseSpec]) =
    this(specs, name => sys.env.get(name))

  /** Resolve `dbName` to a (cached) open `Connection`.  First call
   *  per name opens the connection; subsequent calls return the same
   *  instance.  Raises `UnknownDatabase` if the name is not in the
   *  registry, `MissingEnv` if a `${env:NAME}` resolution fails. */
  def connect(dbName: String): Connection =
    synchronized {
      cache.getOrElse(dbName, {
        val spec = byName.getOrElse(
          dbName,
          throw UnknownDatabase(dbName, byName.keys)
        )
        val conn = open(spec)
        cache.update(dbName, conn)
        conn
      })
    }

  /** Open a brand-new connection without caching.  Useful for
   *  short-lived per-request connections in server scenarios.  The
   *  caller owns the result and must close it. */
  def fresh(dbName: String): Connection =
    val spec = byName.getOrElse(
      dbName,
      throw UnknownDatabase(dbName, byName.keys)
    )
    open(spec)

  /** Close every cached connection.  Idempotent.  Subsequent
   *  `connect` calls reopen on demand. */
  def close(): Unit =
    synchronized {
      cache.values.foreach { c =>
        try c.close()
        catch case _: Throwable => () // best-effort cleanup
      }
      cache.clear()
    }

  /** Names of every declared database — useful for diagnostics. */
  def names: Set[String] = byName.keySet

  private def open(spec: DatabaseSpec): Connection =
    spec.driver.foreach(Class.forName)
    val url = EnvResolver.resolve(spec.url, "url", spec.name, envLookup)
    (spec.user, spec.password) match
      case (Some(u), Some(p)) =>
        DriverManager.getConnection(
          url,
          EnvResolver.resolve(u, "user",     spec.name, envLookup),
          EnvResolver.resolve(p, "password", spec.name, envLookup))
      case (Some(u), None) =>
        DriverManager.getConnection(
          url,
          EnvResolver.resolve(u, "user", spec.name, envLookup),
          null)
      case (None, _) =>
        DriverManager.getConnection(url)

object ConnectionRegistry:
  /** Empty registry — useful for tests that only use `given Connection`
   *  overrides and don't rely on front-matter `databases:`. */
  val empty: ConnectionRegistry = ConnectionRegistry(Nil)
