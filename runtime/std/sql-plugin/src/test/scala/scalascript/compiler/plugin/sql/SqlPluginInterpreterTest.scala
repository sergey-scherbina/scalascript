package scalascript.compiler.plugin.sql

import java.util.UUID

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser
import scalascript.testkit.TestInterpreter

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

  private def uniqueDb(): String =
    s"jdbc:h2:mem:sql-plugin-${UUID.randomUUID().toString.take(8)};DB_CLOSE_DELAY=-1"

  private def evalWithSqlPlugin(source: String): Any =
    val interp = Interpreter(java.io.PrintStream(java.io.OutputStream.nullOutputStream()))
    interp.installPlugins(List(SqlPlugin()))
    interp.run(Parser.parse(source))
    TestInterpreter.unwrap(interp.lastResult)
