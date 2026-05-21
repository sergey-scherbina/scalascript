package scalascript.sql

import java.sql.{Connection, DriverManager}

/** Runtime configuration of one named JDBC connection — the
 *  serialisable shape the front-matter `databases:` map flattens
 *  into.  No coupling to `ast` or `ir`: callers (interpreter,
 *  JvmGen-emitted code) convert their respective `Manifest`
 *  records to this shape before handing them to
 *  `ConnectionRegistry`.
 *
 *  String values may carry `${env:NAME}` or `${file:PATH}` references
 *  that are resolved at the moment the connection opens — not at
 *  construction time.  See `EnvResolver`. */
final case class DatabaseSpec(
  name:     String,
  url:      String,
  user:     Option[String]  = None,
  password: Option[String]  = None,
  driver:   Option[String]  = None
)

/** Resolves `${scheme:ref}` secret references in connection-config strings.
 *
 *  Built-in schemes:
 *  - `${env:NAME}`  — value of environment variable NAME.
 *  - `${file:PATH}` — contents of PATH, whitespace-trimmed (Docker /
 *    Kubernetes secret volume mounts).
 *
 *  Additional schemes are provided by [[SecretResolver]] plugins
 *  discovered via `java.util.ServiceLoader` at runtime (e.g. Vault,
 *  AWS Secrets Manager).  An unknown scheme raises `UnknownScheme`.
 *
 *  Multiple references in one string are supported.  Plain `$` and
 *  unrelated `${…}` patterns are left untouched.  Resolution is
 *  deferred to the moment the connection opens. */
object EnvResolver:
  import scala.jdk.CollectionConverters.*

  // Unified pattern: ${scheme:ref}
  private val RefPattern =
    """\$\{([A-Za-z][A-Za-z0-9_-]*):([^}]+)\}""".r

  // SecretResolver plugins, loaded once on first use.
  private lazy val plugins: Map[String, SecretResolver] =
    java.util.ServiceLoader.load(classOf[SecretResolver])
      .iterator.asScala
      .map(r => r.scheme -> r)
      .toMap

  /** Expand every `${scheme:ref}` in `template`.
   *  `envLookup` defaults to the process environment and is
   *  injected by tests. */
  def resolve(
    template:  String,
    configKey: String,
    dbName:    String,
    envLookup: String => Option[String] = name => sys.env.get(name)
  ): String =
    RefPattern.replaceAllIn(template, m =>
      val scheme = m.group(1)
      val ref    = m.group(2)
      val value  = scheme match
        case "env"  =>
          envLookup(ref).getOrElse(throw MissingEnv(ref, configKey, dbName))
        case "file" =>
          val f = java.io.File(ref)
          if !f.exists() || !f.canRead then throw MissingFile(ref, configKey, dbName)
          scala.io.Source.fromFile(f).mkString.strip()
        case "sops" =>
          SopsSecrets.get(ref).getOrElse(throw MissingSops(ref, configKey, dbName))
        case "config" =>
          // Cross-reference into the global config tree.
          // Import is late to avoid circular dependency: sql-runtime → config-runtime
          scalascript.config.ConfigRegistry.lookup(ref)
            .getOrElse(throw UnknownScheme("config", configKey, dbName))
        case s =>
          plugins.get(s) match
            case Some(r) => r.resolve(ref)
            case None    => throw UnknownScheme(s, configKey, dbName)
      java.util.regex.Matcher.quoteReplacement(value)
    )

/** Raised when a `${env:NAME}` reference in a connection-config string
 *  does not resolve. */
final class MissingEnv(val variable: String, val configKey: String, val dbName: String)
    extends RuntimeException(
      s"environment variable `$variable` referenced from databases.$dbName.$configKey is not set"
    )

/** Raised when a `${file:PATH}` reference in a connection-config string
 *  cannot be read (file missing or permission denied). */
final class MissingFile(val path: String, val configKey: String, val dbName: String)
    extends RuntimeException(
      s"secret file `$path` referenced from databases.$dbName.$configKey does not exist or is not readable"
    )

/** Raised when a `${sops:KEY}` reference is used but no YAML was piped
 *  to stdin (or the key is absent from the piped document). */
final class MissingSops(val key: String, val configKey: String, val dbName: String)
    extends RuntimeException(
      s"sops key `$key` referenced from databases.$dbName.$configKey was not found " +
      s"— pipe a decrypted YAML document to ssc: `sops -d secrets.enc.yaml | ssc myapp.ssc`"
    )

/** Raised when a `${scheme:ref}` reference uses a scheme that has no
 *  built-in handler and no registered [[SecretResolver]] plugin. */
final class UnknownScheme(val scheme: String, val configKey: String, val dbName: String)
    extends RuntimeException(
      s"no SecretResolver registered for scheme `$scheme` " +
      s"(referenced from databases.$dbName.$configKey); " +
      s"built-in schemes: `env`, `file`, `sops`, `config`"
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
    val rawUrl = EnvResolver.resolve(spec.url, "url", spec.name, envLookup)
    val url    = scalascript.db.DbUrl.toJdbc(rawUrl)
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
