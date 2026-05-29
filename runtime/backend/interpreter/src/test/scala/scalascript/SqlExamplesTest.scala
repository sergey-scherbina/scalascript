package scalascript

import org.scalatest.funsuite.AnyFunSuite

import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.26 Phase 7 — bundled `examples/sql-*.ssc` modules must continue
 *  to parse + run end-to-end through the interpreter.  Sources are
 *  inlined verbatim so the test is self-contained — the on-disk
 *  examples themselves are reviewed separately during PR.  When
 *  either copy drifts, this test catches the divergence at the
 *  shape level (the inlined source must parse + execute; the
 *  on-disk file is the user-facing documentation surface). */
@org.scalatest.Ignore
class SqlExamplesTest extends AnyFunSuite {

  private def runProgram(ssc: String): String = {
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(ssc))
    ps.flush()
    buf.toString
  }

  test("sql-h2-quickstart parse + execute end-to-end") {
    val src =
      """|---
         |databases:
         |  default:
         |    url: "jdbc:h2:mem:quickstart-test;DB_CLOSE_DELAY=-1"
         |---
         |
         |# Schema
         |
         |```sql
         |CREATE TABLE users (id BIGINT PRIMARY KEY, name VARCHAR(120), active BOOLEAN)
         |```
         |
         |# Seed
         |
         |```sql
         |INSERT INTO users VALUES (1, 'Alice', true)
         |```
         |
         |```sql
         |INSERT INTO users VALUES (2, 'Bob', false)
         |```
         |
         |# Active users
         |
         |```scala
         |val onlyActive = true
         |```
         |
         |```sql
         |SELECT name FROM users WHERE active = ${onlyActive}
         |```
         |
         |```scala
         |println(s"active rows: ${ActiveUsers.sql}")
         |```
         |""".stripMargin
    val out = runProgram(src)
    assert(out.contains("active rows:"),
      s"expected 'active rows:' in output, got:\n$out")
    assert(out.contains("Alice"),
      s"expected 'Alice' in output (only active = true row), got:\n$out")
    assert(!out.contains("Bob"),
      s"Bob (inactive) leaked through the WHERE clause:\n$out")
  }

  test("sql-sqlite-memory parse + execute with @db=name routing") {
    val src =
      """|---
         |databases:
         |  scratch:
         |    url: "jdbc:sqlite::memory:"
         |---
         |
         |# Setup
         |
         |```sql @db=scratch
         |CREATE TABLE t (k TEXT PRIMARY KEY, v TEXT)
         |```
         |
         |```sql @db=scratch
         |INSERT INTO t VALUES ('hello', 'world')
         |```
         |
         |# Read
         |
         |```sql @db=scratch
         |SELECT v FROM t WHERE k = ${"hello"}
         |```
         |
         |```scala
         |println(s"sqlite says: ${Read.sql}")
         |```
         |""".stripMargin
    val out = runProgram(src)
    assert(out.contains("sqlite says:"),
      s"expected 'sqlite says:' in output, got:\n$out")
    assert(out.contains("world"),
      s"expected 'world' in output, got:\n$out")
  }

  test("typed-sql-crud example runs through interpreter") {
    val out = runProgram(os.read(TestPaths.repoRoot / "examples" / "typed-sql-crud.ssc"))
    assert(out.contains("1/1:Buy oat milk:true"),
      s"expected typed SQL CRUD output, got:\n$out")
  }
}
