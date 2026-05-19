package scalascript.sql

import java.util.UUID
import org.scalatest.funsuite.AnyFunSuite

/** v1.26 Phase 5 — JDBC connection registry: env var resolution,
 *  lazy-open + cache, identity on second `connect`, error surfaces. */
class ConnectionRegistryTest extends AnyFunSuite:

  // ── EnvResolver ─────────────────────────────────────────────────────

  test("EnvResolver: no `${env:…}` markers → string passes through") {
    val out = EnvResolver.resolve("jdbc:h2:mem:foo", "url", "x", _ => None)
    assert(out == "jdbc:h2:mem:foo")
  }

  test("EnvResolver: single ${env:NAME} substitution") {
    val out = EnvResolver.resolve(
      "jdbc:postgresql://${env:HOST}:5432/app",
      "url", "x",
      { case "HOST" => Some("db.internal"); case _ => None }
    )
    assert(out == "jdbc:postgresql://db.internal:5432/app")
  }

  test("EnvResolver: multiple substitutions") {
    val out = EnvResolver.resolve(
      "jdbc:postgresql://${env:H}:${env:P}/${env:D}",
      "url", "x",
      Map("H" -> "h", "P" -> "5432", "D" -> "app").get
    )
    assert(out == "jdbc:postgresql://h:5432/app")
  }

  test("EnvResolver: missing var → MissingEnv with helpful message") {
    val ex = intercept[MissingEnv](
      EnvResolver.resolve("${env:NOPE}", "password", "reports", _ => None)
    )
    assert(ex.variable  == "NOPE")
    assert(ex.configKey == "password")
    assert(ex.dbName    == "reports")
    assert(ex.getMessage.contains("databases.reports.password"))
    assert(ex.getMessage.contains("NOPE"))
  }

  test("EnvResolver: env value with regex-special chars is escaped, not interpreted") {
    val out = EnvResolver.resolve(
      "header=${env:S}",
      "header", "x",
      Map("S" -> "$1 backslash:\\ end").get
    )
    assert(out == "header=$1 backslash:\\ end")
  }

  test("EnvResolver: plain `${...}` without `env:` prefix is left alone") {
    val out = EnvResolver.resolve("hello ${world}", "x", "y", _ => None)
    assert(out == "hello ${world}")
  }

  // ── ConnectionRegistry: H2 in-memory ────────────────────────────────

  Class.forName("org.h2.Driver")

  private def h2Url(): String =
    s"jdbc:h2:mem:registry-${UUID.randomUUID().toString.take(8)};DB_CLOSE_DELAY=-1"

  test("connect by name opens an H2 connection") {
    val reg = ConnectionRegistry(List(DatabaseSpec("default", h2Url())))
    try
      val c = reg.connect("default")
      assert(!c.isClosed)
      // Smoke-test that the connection works.
      val rs = c.createStatement().executeQuery("SELECT 1")
      assert(rs.next() && rs.getInt(1) == 1)
    finally reg.close()
  }

  test("connect is cached — same instance on second call") {
    val reg = ConnectionRegistry(List(DatabaseSpec("default", h2Url())))
    try
      val c1 = reg.connect("default")
      val c2 = reg.connect("default")
      assert(c1 eq c2, "second connect must return the cached connection")
    finally reg.close()
  }

  test("fresh always opens a new connection (not cached)") {
    val reg = ConnectionRegistry(List(DatabaseSpec("default", h2Url())))
    try
      val c1 = reg.fresh("default")
      val c2 = reg.fresh("default")
      assert(!(c1 eq c2), "fresh must not return a cached connection")
      c1.close()
      c2.close()
    finally reg.close()
  }

  test("close releases cached connections and is idempotent") {
    val reg = ConnectionRegistry(List(DatabaseSpec("default", h2Url())))
    val c   = reg.connect("default")
    assert(!c.isClosed)
    reg.close()
    assert(c.isClosed, "close() must close cached connections")
    reg.close() // idempotent — must not throw
  }

  test("connect after close reopens a fresh connection") {
    val url = h2Url()
    val reg = ConnectionRegistry(List(DatabaseSpec("default", url)))
    val c1  = reg.connect("default")
    reg.close()
    val c2  = reg.connect("default")
    assert(!(c1 eq c2))
    assert(!c2.isClosed)
    reg.close()
  }

  test("unknown database name → UnknownDatabase with available names listed") {
    val reg = ConnectionRegistry(
      List(DatabaseSpec("default", h2Url()), DatabaseSpec("reports", h2Url()))
    )
    try
      val ex = intercept[UnknownDatabase](reg.connect("nope"))
      assert(ex.name == "nope")
      assert(ex.available.toSet == Set("default", "reports"))
      assert(ex.getMessage.contains("default"))
      assert(ex.getMessage.contains("reports"))
    finally reg.close()
  }

  test("${env:NAME} in URL resolved at connect time") {
    val url = h2Url()
    val reg = new ConnectionRegistry(
      List(DatabaseSpec("default", "${env:JDBC_URL}")),
      envLookup = { case "JDBC_URL" => Some(url); case _ => None }
    )
    try
      val c = reg.connect("default")
      assert(!c.isClosed)
    finally reg.close()
  }

  test("missing env var surfaces MissingEnv on first connect") {
    val reg = new ConnectionRegistry(
      List(DatabaseSpec("default", "${env:NEVER_SET}")),
      envLookup = _ => None
    )
    try
      val ex = intercept[MissingEnv](reg.connect("default"))
      assert(ex.variable == "NEVER_SET")
      assert(ex.dbName   == "default")
    finally reg.close()
  }

  test("registry exposes declared names for diagnostics") {
    val reg = ConnectionRegistry(
      List(DatabaseSpec("a", h2Url()), DatabaseSpec("b", h2Url()))
    )
    assert(reg.names == Set("a", "b"))
  }

  test("empty registry has no names and connect raises UnknownDatabase") {
    assert(ConnectionRegistry.empty.names.isEmpty)
    val ex = intercept[UnknownDatabase](ConnectionRegistry.empty.connect("default"))
    assert(ex.name == "default")
    assert(ex.available.isEmpty)
  }
