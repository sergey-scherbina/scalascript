package scalascript

import org.scalatest.funsuite.AnyFunSuite

import scalascript.codegen.JvmGen
import scalascript.parser.Parser

/** v1.26 Phase 6.C — JvmGen Scala-source emission for `sql` fenced
 *  code blocks.  Text-shape assertions only (the emitted code's
 *  scala-cli runtime smoke-test is deferred to Phase 7 once
 *  `backend-sql-runtime` ships as a Maven artifact — see Phase 6.C
 *  milestone for the deferred runtime test). */
class JvmGenSqlBlockTest extends AnyFunSuite {

  private def emit(ssc: String): String =
    JvmGen.generate(Parser.parse(ssc))

  // ── No-sql path: legacy modules emit unchanged ──────────────────────

  test("module without sql blocks emits no JDBC scaffolding") {
    val code = emit(
      """|# Test
         |
         |```scala
         |val x = 1
         |```
         |""".stripMargin
    )
    assert(!code.contains("_ssc_sql_registry"))
    assert(!code.contains("_ssc_sql_resolve"))
    assert(!code.contains("h2database"))
    assert(!code.contains("sqlite-jdbc"))
  }

  // ── `//> using dep` directives for bundled drivers ──────────────────

  test("sql block emits `//> using dep` for H2 and SQLite") {
    val code = emit(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin
    )
    assert(code.contains("""//> using dep "com.h2database:h2:"""))
    assert(code.contains("""//> using dep "org.xerial:sqlite-jdbc:"""))
  }

  test("sql block emits backend-sql-runtime artifact directive") {
    val code = emit(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin
    )
    assert(code.contains("scalascript-backend-sql-runtime"))
  }

  test("sql block emits typed-data runtime and typed Db.query overload") {
    val code = emit(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin
    )
    assert(code.contains("scalascript-backend-typed-data-runtime"))
    assert(code.contains("def query[A](dbName: String, sql: String, params: List[Any])(using scalascript.typeddata.RowCodec[A]): List[A]"))
    assert(code.contains("scalascript.sql.SqlRuntime.query[A](conn, sql, params).toList"))
  }

  // ── Registry + resolver helper ──────────────────────────────────────

  test("databases-less sql module emits empty registry") {
    val code = emit(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin
    )
    assert(code.contains("val _ssc_sql_registry: scalascript.sql.ConnectionRegistry"))
    assert(code.contains("ConnectionRegistry.empty"))
  }

  test("databases entry materialises into a DatabaseSpec list") {
    val code = emit(
      """|---
         |databases:
         |  default:
         |    url: jdbc:h2:mem:dev
         |  reports:
         |    url: jdbc:postgresql://r:5432/w
         |    user: rpt
         |    password: secret
         |---
         |
         |# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin
    )
    assert(code.contains("scalascript.sql.ConnectionRegistry(List("))
    assert(code.contains("""DatabaseSpec("default", "jdbc:h2:mem:dev", None, None, None)"""))
    assert(code.contains("""DatabaseSpec("reports", "jdbc:postgresql://r:5432/w", Some("rpt"), Some("secret"), None)"""))
  }

  test("resolver helper uses summonFrom for the given-Connection override path") {
    val code = emit(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin
    )
    assert(code.contains("def _ssc_sql_resolve(dbName: Option[String]): java.sql.Connection"))
    assert(code.contains("scala.compiletime.summonFrom"))
    assert(code.contains("case c: java.sql.Connection => c"))
    assert(code.contains("""_ssc_sql_registry.connect(dbName.getOrElse("default"))"""))
  }

  // ── Per-block emission ──────────────────────────────────────────────

  test("sql block with no binds emits zero-argument SqlRuntime.execute call") {
    val code = emit(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin
    )
    assert(code.contains("val _sqlBlock_0: scalascript.sql.SqlResult ="))
    assert(code.contains("scalascript.sql.SqlRuntime.execute("))
    assert(code.contains(", Nil)"))
  }

  test("sql block with binds emits the bind expressions verbatim into a List(...)") {
    val code = emit(
      """|# Q
         |
         |```scala
         |val userId = 1L
         |val status = "active"
         |```
         |
         |```sql
         |SELECT name FROM users WHERE id = ${userId} AND status = ${status}
         |```
         |""".stripMargin
    )
    assert(code.contains("WHERE id = ? AND status = ?"))
    assert(code.contains("List(userId, status)"))
  }

  test("multiple sql blocks get sequential _sqlBlock_<N> names") {
    val code = emit(
      """|# Setup
         |
         |```sql
         |CREATE TABLE t (id INT)
         |```
         |
         |```sql
         |INSERT INTO t VALUES (1)
         |```
         |
         |# Query
         |
         |```sql
         |SELECT id FROM t
         |```
         |""".stripMargin
    )
    assert(code.contains("_sqlBlock_0"))
    assert(code.contains("_sqlBlock_1"))
    assert(code.contains("_sqlBlock_2"))
  }

  // ── Section-based binding (Phase C.2 convention shared with Spark) ──

  test("first sql block per section gets a friendly `<sectionId>.sql` alias") {
    val code = emit(
      """|# Users
         |
         |```sql
         |SELECT * FROM users
         |```
         |""".stripMargin
    )
    assert(code.contains("object Users:") || code.contains("object Users {"))
    assert(code.contains("lazy val sql: scalascript.sql.SqlResult = _sqlBlock_0"))
  }

  test("second sql block in the same section does NOT get a duplicate alias") {
    val code = emit(
      """|# Users
         |
         |```sql
         |SELECT id FROM users
         |```
         |
         |```sql
         |SELECT name FROM users
         |```
         |""".stripMargin
    )
    // exactly one `lazy val sql` declaration — second would be a Scala compile error.
    val occurrences = "lazy val sql:".r.findAllIn(code).size
    assert(occurrences == 1, s"expected 1 `lazy val sql:` declaration, got $occurrences")
  }

  // ── @db=name fence attribute ────────────────────────────────────────

  test("`@db=name` on the fence threads through to _ssc_sql_resolve as Some") {
    val code = emit(
      """|---
         |databases:
         |  reports:
         |    url: jdbc:postgresql://r:5432/w
         |---
         |
         |# X
         |
         |```sql @db=reports
         |SELECT 1
         |```
         |""".stripMargin
    )
    assert(code.contains("""_ssc_sql_resolve(Some("reports"))"""))
  }

  test("sql fence without `@db=` resolves with None (registry default applies)") {
    val code = emit(
      """|# X
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin
    )
    assert(code.contains("_ssc_sql_resolve(None)"))
  }

  // ── transaction block emission ──────────────────────────────────────

  test("transaction block emits withTransaction wrapping multiple SqlRuntime.execute calls") {
    val code = emit(
      """|# Transfer
         |
         |```transaction
         |INSERT INTO a VALUES (1);
         |INSERT INTO b VALUES (2)
         |```
         |""".stripMargin
    )
    assert(code.contains("_ssc_sql_registry.withTransaction(\"default\")"))
    assert(code.contains("_ssc_tx_conn"))
    assert(code.contains("List("))
    assert(code.contains("scalascript.sql.SqlRuntime.execute(_ssc_tx_conn"))
    assert(code.contains("INSERT INTO a VALUES (1)"))
    assert(code.contains("INSERT INTO b VALUES (2)"))
  }

  test("transaction block with binds splices expressions into each statement") {
    val code = emit(
      """|# Tx
         |
         |```transaction
         |UPDATE a SET v = ${x};
         |UPDATE b SET v = ${y}
         |```
         |""".stripMargin
    )
    assert(code.contains("UPDATE a SET v = ?"))
    assert(code.contains("UPDATE b SET v = ?"))
    assert(code.contains("List(x)"))
    assert(code.contains("List(y)"))
  }

  test("transaction block uses `@db=name` attribute in withTransaction call") {
    val code = emit(
      """|---
         |databases:
         |  ops:
         |    url: jdbc:postgresql://h/db
         |---
         |
         |# Tx
         |
         |```transaction @db=ops
         |DELETE FROM tmp
         |```
         |""".stripMargin
    )
    assert(code.contains("_ssc_sql_registry.withTransaction(\"ops\")"))
  }

  test("transaction block increments sqlBlockCounter (triggers sql preamble emission)") {
    val code = emit(
      """|# X
         |
         |```transaction
         |INSERT INTO t VALUES (1)
         |```
         |""".stripMargin
    )
    assert(code.contains("_ssc_sql_registry"))
    assert(code.contains("val _sqlBlock_0"))
  }

  // ── ${env:NAME} preserved through to DatabaseSpec ───────────────────

  test("`${env:NAME}` references in databases survive into the emitted DatabaseSpec") {
    // Resolution is a runtime concern (EnvResolver inside ConnectionRegistry).
    // Codegen just forwards the string verbatim — the emitted Scala string
    // literal carries the `${env:HOST}` marker as data.
    val code = emit(
      """|---
         |databases:
         |  reports:
         |    url: "jdbc:postgresql://${env:HOST}/db"
         |    user: "${env:USER}"
         |---
         |
         |# X
         |
         |```sql @db=reports
         |SELECT 1
         |```
         |""".stripMargin
    )
    val hostMarker = "$" + "{env:HOST}"
    val userMarker = "$" + "{env:USER}"
    assert(code.contains(hostMarker), s"missing $hostMarker")
    assert(code.contains(userMarker), s"missing $userMarker")
  }

  // ── v1.30 Phase 4 — @side=client / @side=server codegen ────────────

  test("@side=server sql block emits server JDBC code (existing behaviour)") {
    val code = emit(
      """|# Q
         |
         |```sql @side=server
         |SELECT 1
         |```
         |""".stripMargin
    )
    assert(code.contains("_sqlBlock_0"), "server block must emit _sqlBlock_0")
    assert(code.contains("scalascript.sql.SqlRuntime.execute("))
    assert(!code.contains("_ssc_client_sql_js"), "@side=server module without frontend should not emit _ssc_client_sql_js")
  }

  test("@side=client sql block does NOT emit server JDBC _sqlBlock_N") {
    val code = emit(
      """|---
         |frontend: react
         |databases:
         |  local:
         |    url: "sqlite-opfs:./cache.db"
         |---
         |# Setup
         |
         |```sql @side=client @db=local
         |CREATE TABLE IF NOT EXISTS cache (key TEXT PRIMARY KEY)
         |```
         |""".stripMargin
    )
    assert(!code.contains("val _sqlBlock_0"), "@side=client must NOT emit server _sqlBlock_0")
    assert(!code.contains("SqlRuntime.execute("), "@side=client must NOT emit JDBC execute call")
  }

  test("@side=client sql block emits _ssc_client_sql_js val in frontend modules") {
    val code = emit(
      """|---
         |frontend: react
         |databases:
         |  local:
         |    url: "sqlite-opfs:./cache.db"
         |---
         |# Setup
         |
         |```sql @side=client @db=local
         |CREATE TABLE IF NOT EXISTS cache (key TEXT PRIMARY KEY)
         |```
         |""".stripMargin
    )
    assert(code.contains("val _ssc_client_sql_js"), "must emit _ssc_client_sql_js")
    assert(code.contains("(async function()"), "must contain async IIFE in embedded JS")
    assert(code.contains("CREATE TABLE IF NOT EXISTS cache"), "SQL source must be in client JS")
  }

  test("frontend module with no @side=client sql emits empty _ssc_client_sql_js") {
    val code = emit(
      """|---
         |frontend: react
         |databases:
         |  server:
         |    url: "sqlite:./data.db"
         |---
         |# Setup
         |
         |```sql @side=server @db=server
         |SELECT 1
         |```
         |""".stripMargin
    )
    assert(code.contains("val _ssc_client_sql_js"), "frontend module always emits _ssc_client_sql_js")
    // The val should be an empty string literal when no client blocks
    assert(code.contains("_ssc_client_sql_js: String = \"\""), "no client blocks → empty string val")
  }

  test("mixed @side=client + @side=server blocks: server → JDBC, client → browser JS") {
    val code = emit(
      """|---
         |frontend: react
         |databases:
         |  local:
         |    url: "sqlite-opfs:./cache.db"
         |  server:
         |    url: "sqlite:./data.db"
         |---
         |# Server query
         |
         |```sql @side=server @db=server
         |SELECT id, text FROM notes ORDER BY id
         |```
         |
         |# Client setup
         |
         |```sql @side=client @db=local
         |CREATE TABLE IF NOT EXISTS cache (key TEXT PRIMARY KEY)
         |```
         |""".stripMargin
    )
    // Server block → ordinary JDBC emit
    assert(code.contains("val _sqlBlock_0"), "server block must be _sqlBlock_0")
    assert(code.contains("SELECT id, text FROM notes ORDER BY id"))
    // Client block → browser JS bundle
    assert(code.contains("_ssc_client_sql_js"))
    assert(code.contains("CREATE TABLE IF NOT EXISTS cache"))
    // Server JDBC count should be 1, client should NOT be _sqlBlock_1
    assert(!code.contains("_sqlBlock_1"), "client block must not increment server counter")
  }

  test("_ssc_ui_emit_to_dir and _ssc_ui_emit_to_tempdir append _ssc_client_sql_js to app.js") {
    val code = emit(
      """|---
         |frontend: react
         |databases:
         |  local:
         |    url: "sqlite-opfs:./cache.db"
         |---
         |# Setup
         |
         |```sql @side=client @db=local
         |CREATE TABLE IF NOT EXISTS cache (key TEXT PRIMARY KEY)
         |```
         |""".stripMargin
    )
    assert(code.contains("_ssc_client_sql_js.nonEmpty"), "ui helpers must check client JS")
    assert(code.contains("_emitted.js + \"\\n\" + _ssc_client_sql_js"), "must append client JS to app.js")
  }
}
