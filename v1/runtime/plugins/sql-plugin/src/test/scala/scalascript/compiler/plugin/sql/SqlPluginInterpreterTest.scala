package scalascript.compiler.plugin.sql

import java.util.UUID

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.testkit.TestInterpreter
import scalascript.transform.{Denormalize, Normalize}

class SqlPluginInterpreterTest extends AnyFunSuite:

  Class.forName("org.h2.Driver")

  test("SQL plugin runs Db.query and Db.execute through interpreter front-matter databases"):
    val result = evalWithSqlPlugin(
      s"""|---
      |databases:
      |  default:
      |    url: "${uniqueDb()}"
      |---
      |
      |# Test
      |
      |```scala
      |Db.execute("default", "CREATE TABLE people (id BIGINT PRIMARY KEY, name VARCHAR(64), active BOOLEAN)", List())
      |Db.execute("default", "INSERT INTO people (id, name, active) VALUES (?, ?, ?)", List(1L, "Ada", true))
      |val rows = Db.query("default", "SELECT name, active FROM people WHERE id = ?", List(1L))
      |val row = rows.head
      |List(row("NAME"), row("ACTIVE"))
      |```
      |""".stripMargin
    )

    assert(result == List("Ada", true))

  test("SQL plugin runs typed Db.insert and Db.update helpers in isolation"):
    val result = evalWithSqlPlugin(
      s"""|---
      |databases:
      |  default:
      |    url: "${uniqueDb()}"
      |---
      |
      |# Test
      |
      |```scala
      |Db.execute("default", "CREATE TABLE people (id BIGINT PRIMARY KEY, name VARCHAR(64), active BOOLEAN)", List())
      |val inserted = Db.insert("default", "people", Map("id" -> 2L, "name" -> "Bob", "active" -> false))
      |val updated = Db.update("default", "people", "id", 2L, Map("id" -> 2L, "name" -> "Bobby", "active" -> true))
      |val rows = Db.query("default", "SELECT name, active FROM people WHERE id = ?", List(2L))
      |val row = rows.head
      |List(inserted, updated, row("NAME"), row("ACTIVE"))
      |```
      |""".stripMargin
    )

    assert(result == List(1L, 1L, "Bobby", true))

  test("SQL plugin owns interpreter sql fenced-block execution"):
    val result = evalWithSqlPlugin(
      s"""|---
      |databases:
      |  default:
      |    url: "${uniqueDb()}"
      |---
      |
      |# Schema
      |
      |```sql
      |CREATE TABLE people (id BIGINT PRIMARY KEY, name VARCHAR(64))
      |```
      |
      |# Seed
      |
      |```scala
      |val personId = 1L
      |val personName = "Ada"
      |```
      |
      |```sql
      |INSERT INTO people (id, name) VALUES ($${personId}, $${personName})
      |```
      |
      |# Read
      |
      |```sql
      |SELECT name FROM people WHERE id = $${personId}
      |```
      |
      |```scala
      |val row = Read.sql.head
      |List(row("NAME"), _sqlBlock_1, _sqlBlock_2 == Read.sql)
      |```
      |""".stripMargin
    )

    assert(result == List("Ada", 1L, true))

  test("SQL fenced-block binds survive Normalize/Denormalize scala block round-trip"):
    val result = evalWithSqlPlugin(
      s"""|---
      |databases:
      |  default:
      |    url: "${uniqueDb()}"
      |---
      |
      |# Schema
      |
      |```sql
      |CREATE TABLE people (id BIGINT PRIMARY KEY, name VARCHAR(64))
      |```
      |
      |# Seed
      |
      |```scala
      |val personId = 1L
      |val personName = "Ada"
      |```
      |
      |```sql
      |INSERT INTO people (id, name) VALUES ($${personId}, $${personName})
      |```
      |
      |# Read
      |
      |```sql
      |SELECT name FROM people WHERE id = $${personId}
      |```
      |
      |```scala
      |val row = Read.sql.head
      |List(row("NAME"), _sqlBlock_1, _sqlBlock_2 == Read.sql)
      |```
      |""".stripMargin,
      roundTripIr = true
    )

    assert(result == List("Ada", 1L, true))

  test("SQL plugin owns interpreter transaction fenced-block execution"):
    val result = evalWithSqlPlugin(
      s"""|---
      |databases:
      |  default:
      |    url: "${uniqueDb()}"
      |---
      |
      |# Schema
      |
      |```sql
      |CREATE TABLE people (id BIGINT PRIMARY KEY, name VARCHAR(64))
      |```
      |
      |# Seed
      |
      |```scala
      |val personId = 1L
      |val personName = "Ada"
      |```
      |
      |# Tx
      |
      |```transaction
      |INSERT INTO people (id, name) VALUES ($${personId}, $${personName});
      |SELECT name FROM people WHERE id = $${personId}
      |```
      |
      |```scala
      |val row = Tx.sql.head
      |List(row("NAME"), _sqlBlock_1 == Tx.sql)
      |```
      |""".stripMargin
    )

    assert(result == List("Ada", true))

  // PostgreSQL LISTEN/NOTIFY receive side (busi df-6). The publish side already
  // works via Db.query("db", "SELECT pg_notify(?,?)", …). The actual notify-receive
  // path needs a live PostgreSQL (H2 has no LISTEN/NOTIFY), so it is verified by
  // busi against real Postgres; here we lock the native registration + the
  // PostgreSQL-only guard, which are what regress under refactors.
  test("Db.pgListen / Db.unlisten / Db.getNotifications natives are registered"):
    assert(SqlIntrinsics.table.contains(scalascript.ir.QualifiedName("Db.pgListen")))
    assert(SqlIntrinsics.table.contains(scalascript.ir.QualifiedName("Db.unlisten")))
    assert(SqlIntrinsics.table.contains(scalascript.ir.QualifiedName("Db.getNotifications")))

  test("Db.getNotifications on a non-PostgreSQL connection fails with a clear PG-only error"):
    val err = intercept[Throwable](evalWithSqlPlugin(
      s"""|---
      |databases:
      |  default:
      |    url: "${uniqueDb()}"
      |---
      |
      |# Test
      |
      |```scala
      |Db.getNotifications("default")
      |```
      |""".stripMargin
    ))
    def chain(t: Throwable): String =
      if t == null then "" else Option(t.getMessage).getOrElse("") + " " + chain(t.getCause)
    assert(chain(err).contains("PostgreSQL"),
      s"expected a clear PostgreSQL-only error, got: ${chain(err)}")

  private def uniqueDb(): String =
    s"jdbc:h2:mem:sql-plugin-${UUID.randomUUID().toString.take(8)};DB_CLOSE_DELAY=-1"

  private def evalWithSqlPlugin(source: String, roundTripIr: Boolean = false): Any =
    val interp = Interpreter(java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
    interp.installPlugins(List(SqlPlugin()))
    val parsed = Parser.parse(source)
    val module = if roundTripIr then Denormalize(Normalize(parsed)) else parsed
    interp.run(module)
    TestInterpreter.unwrap(interp.lastResult)
