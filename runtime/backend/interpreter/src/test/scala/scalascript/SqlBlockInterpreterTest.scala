package scalascript

import java.util.UUID

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scalascript.interpreter.{InterpretError, Interpreter}
import scalascript.parser.Parser

/** v1.26 Phase 6.B — end-to-end interpreter execution of `sql` fenced
 *  code blocks.
 *
 *  Each test feeds an `.ssc` source through `Parser.parse` →
 *  `Interpreter.run`.  H2 is the bundled in-memory driver; the
 *  per-test database URL is unique so cases don't bleed state into
 *  each other.
 *
 *  Two connection-resolution paths are covered:
 *    - **Registry path** (front-matter `databases:`) — the canonical
 *      production shape.
 *    - **Override path** — a global `Connection` bound via
 *      `DriverManager.getConnection(...)`, intrinsic-mediated. */
class SqlBlockInterpreterTest extends AnyFunSuite with Matchers:

  Class.forName("org.h2.Driver")

  private def uniqueDb(): String =
    s"jdbc:h2:mem:int-${UUID.randomUUID().toString.take(8)};DB_CLOSE_DELAY=-1"

  private def runProgram(ssc: String): java.io.ByteArrayOutputStream =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(ssc))
    ps.flush()
    buf

  // ── Registry path ───────────────────────────────────────────────────

  test("registry path: front-matter `databases:` + DDL + INSERT + SELECT with binds") {
    val url = uniqueDb()
    val ssc =
      s"""|---
          |databases:
          |  default:
          |    url: $url
          |---
          |
          |# Schema
          |
          |```sql
          |CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL)
          |```
          |
          |# Seed
          |
          |```scala
          |val newId = 1L
          |val newName = "Alice"
          |```
          |
          |```sql
          |INSERT INTO users (id, name) VALUES ($${newId}, $${newName})
          |```
          |
          |# Query
          |
          |```sql
          |SELECT name FROM users WHERE id = $${newId}
          |```
          |
          |```scala
          |val rows = Query.sql
          |val row  = rows.head
          |println(row("NAME"))
          |```
          |""".stripMargin
    val out = runProgram(ssc).toString.trim
    out shouldBe "Alice"
  }

  test("registry path: SELECT result bound at `<sectionId>.sql` and as `_sqlBlock_<N>`") {
    val url = uniqueDb()
    val ssc =
      s"""|---
          |databases:
          |  default:
          |    url: $url
          |---
          |
          |# Schema
          |
          |```sql
          |CREATE TABLE t (id INT)
          |```
          |
          |```sql
          |INSERT INTO t VALUES (42)
          |```
          |
          |# Read
          |
          |```sql
          |SELECT id FROM t
          |```
          |
          |```scala
          |// Both surfaces resolve the same SELECT result.
          |val viaSection = Read.sql
          |val viaOrdinal = _sqlBlock_2  // 0 = CREATE, 1 = INSERT, 2 = SELECT
          |println(viaSection == viaOrdinal)
          |```
          |""".stripMargin
    runProgram(ssc).toString.trim shouldBe "true"
  }

  // ── Override path ───────────────────────────────────────────────────

  test("override path: `val Connection = DriverManager.getConnection(...)` beats registry") {
    val overrideUrl = uniqueDb()
    val ssc =
      s"""|# Setup
          |
          |```scala
          |val Connection: Connection = DriverManager.getConnection("$overrideUrl")
          |```
          |
          |# Schema
          |
          |```sql
          |CREATE TABLE flag (x INT)
          |```
          |
          |```sql
          |INSERT INTO flag VALUES (7)
          |```
          |
          |# Read
          |
          |```sql
          |SELECT x FROM flag
          |```
          |
          |```scala
          |val row = Read.sql.head
          |println(row("X"))
          |```
          |""".stripMargin
    runProgram(ssc).toString.trim shouldBe "7"
  }

  test("override path: 3-arg DriverManager.getConnection (url, user, password) also works") {
    val url = uniqueDb()
    val ssc =
      s"""|# Setup
          |
          |```scala
          |val Connection: Connection =
          |  DriverManager.getConnection("$url", "sa", "")
          |```
          |
          |# Read
          |
          |```sql
          |SELECT 1 AS one
          |```
          |
          |```scala
          |val row = Read.sql.head
          |println(row("ONE"))
          |```
          |""".stripMargin
    runProgram(ssc).toString.trim shouldBe "1"
  }

  // ── DDL / DML return shapes ─────────────────────────────────────────

  test("UPDATE statement returns the affected-row count as IntV") {
    val url = uniqueDb()
    val ssc =
      s"""|---
          |databases:
          |  default:
          |    url: $url
          |---
          |
          |# Schema
          |
          |```sql
          |CREATE TABLE t (id INT, n INT)
          |```
          |
          |```sql
          |INSERT INTO t VALUES (1, 10)
          |```
          |
          |```sql
          |INSERT INTO t VALUES (2, 20)
          |```
          |
          |# Mutate
          |
          |```sql
          |UPDATE t SET n = n + 1
          |```
          |
          |```scala
          |println(Mutate.sql)
          |```
          |""".stripMargin
    runProgram(ssc).toString.trim shouldBe "2"
  }

  test("interpreter Db.query[A] projects row maps into case-class instances") {
    val url = uniqueDb()
    val ssc =
      s"""|---
          |databases:
          |  default:
          |    url: $url
          |---
          |
          |# Model
          |
          |```scala
          |case class Person(id: Int, name: String, active: Boolean)
          |```
          |
          |# Schema
          |
          |```sql
          |CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(64), active BOOLEAN)
          |```
          |
          |```sql
          |INSERT INTO people VALUES (1, 'Alice', true)
          |```
          |
          |# Read
          |
          |```scala
          |val people = Db.query[Person]("default", "SELECT id AS id, name AS name, active AS active FROM people", [])
          |val p = people.head
          |println(p.name + ":" + p.active + ":" + p)
          |```
          |""".stripMargin
    runProgram(ssc).toString.trim shouldBe "Alice:true:Person(1, Alice, true)"
  }

  test("interpreter Db.insert and Db.update encode case-class instances") {
    val url = uniqueDb()
    val ssc =
      s"""|---
          |databases:
          |  default:
          |    url: $url
          |---
          |
          |# Model
          |
          |```scala
          |case class Person(id: Int, name: String, active: Boolean)
          |```
          |
          |# Schema
          |
          |```sql
          |CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(64), active BOOLEAN)
          |```
          |
          |# Write
          |
          |```scala
          |val inserted = Db.insert("default", "people", Person(1, "Alice", true))
          |val updated = Db.update("default", "people", "id", 1, Person(1, "Alicia", false))
          |val people = Db.query[Person]("default", "SELECT id AS id, name AS name, active AS active FROM people", [])
          |println(s"$${inserted}/$${updated}:$${people.head.name}:$${people.head.active}")
          |```
          |""".stripMargin
    runProgram(ssc).toString.trim shouldBe "1/1:Alicia:false"
  }

  test("interpreter typed SQL honors schema annotations and defaults") {
    val url = uniqueDb()
    val ssc =
      s"""|---
          |databases:
          |  default:
          |    url: $url
          |---
          |
          |# Model
          |
          |```scala
          |@rejectUnknown
          |case class Person(
          |  @key id: Int,
          |  @fieldName("display_name") @aliases("name") displayName: String,
          |  active: Boolean = true
          |)
          |```
          |
          |# Schema
          |
          |```sql
          |CREATE TABLE people (id INT PRIMARY KEY, display_name VARCHAR(64), active BOOLEAN)
          |```
          |
          |```sql
          |INSERT INTO people (id, display_name, active) VALUES (1, 'Ada', false)
          |```
          |
          |# Read + Write
          |
          |```scala
          |val fromCanonical = Db.query[Person]("default", "SELECT id AS id, display_name AS display_name FROM people WHERE id = 1", [])
          |val inserted = Db.insert("default", "people", Person(2, "Grace", true))
          |val fromAlias = Db.query[Person]("default", "SELECT id AS id, display_name AS name FROM people WHERE id = 2", [])
          |println(s"$${fromCanonical.head.displayName}:$${fromCanonical.head.active}:$${inserted}:$${fromAlias.head.displayName}:$${fromAlias.head.active}")
          |```
          |""".stripMargin
    runProgram(ssc).toString.trim shouldBe "Ada:true:1:Grace:true"
  }

  test("interpreter typed SQL rejects unknown columns when annotated") {
    val url = uniqueDb()
    val ssc =
      s"""|---
          |databases:
          |  default:
          |    url: $url
          |---
          |
          |```scala
          |@rejectUnknown
          |case class Person(id: Int, name: String)
          |```
          |
          |```sql
          |CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(64), extra VARCHAR(64))
          |```
          |
          |```sql
          |INSERT INTO people VALUES (1, 'Ada', 'nope')
          |```
          |
          |```scala
          |Db.query[Person]("default", "SELECT id AS id, name AS name, extra AS extra FROM people", [])
          |```
          |""".stripMargin
    val ex = intercept[InterpretError](runProgram(ssc))
    ex.getMessage.toLowerCase.should(include("$.extra: unknown column 'extra'"))
  }

  test("interpreter typed SQL honors front-matter schema metadata") {
    val url = uniqueDb()
    val ssc =
      s"""|---
          |databases:
          |  default:
          |    url: $url
          |schemas:
          |  Person:
          |    rejectUnknown: true
          |    fields:
          |      id:
          |        key: true
          |      displayName:
          |        name: display_name
          |        aliases: [name]
          |      active:
          |        default: true
          |---
          |
          |# Model
          |
          |```scala
          |case class Person(id: Int, displayName: String, active: Boolean)
          |```
          |
          |# Schema
          |
          |```sql
          |CREATE TABLE people (id INT PRIMARY KEY, display_name VARCHAR(64), active BOOLEAN)
          |```
          |
          |```sql
          |INSERT INTO people (id, display_name, active) VALUES (1, 'Ada', false)
          |```
          |
          |# Read + Write
          |
          |```scala
          |val fromCanonical = Db.query[Person]("default", "SELECT id AS id, display_name AS display_name FROM people WHERE id = 1", [])
          |val inserted = Db.insert("default", "people", Person(2, "Grace", true))
          |val fromAlias = Db.query[Person]("default", "SELECT id AS id, display_name AS name FROM people WHERE id = 2", [])
          |println(s"$${fromCanonical.head.displayName}:$${fromCanonical.head.active}:$${inserted}:$${fromAlias.head.displayName}:$${fromAlias.head.active}")
          |```
          |""".stripMargin
    runProgram(ssc).toString.trim shouldBe "Ada:true:1:Grace:true"
  }

  test("interpreter typed SQL rejects unknown columns from front-matter schema") {
    val url = uniqueDb()
    val ssc =
      s"""|---
          |databases:
          |  default:
          |    url: $url
          |schemas:
          |  Person:
          |    rejectUnknown: true
          |    fields:
          |      id: id
          |      name: name
          |---
          |
          |```scala
          |case class Person(id: Int, name: String)
          |```
          |
          |```sql
          |CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(64), extra VARCHAR(64))
          |```
          |
          |```sql
          |INSERT INTO people VALUES (1, 'Ada', 'nope')
          |```
          |
          |```scala
          |Db.query[Person]("default", "SELECT id AS id, name AS name, extra AS extra FROM people", [])
          |```
          |""".stripMargin
    val ex = intercept[InterpretError](runProgram(ssc))
    ex.getMessage.toLowerCase.should(include("$.extra: unknown column 'extra'"))
  }
