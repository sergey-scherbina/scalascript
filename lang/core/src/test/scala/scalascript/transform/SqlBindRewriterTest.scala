package scalascript.transform

import org.scalatest.funsuite.AnyFunSuite

/** Pins the lexer-level rewriter for `sql` fenced blocks (SPEC.md § 3.3.1).
 *
 *  Two flavours of placeholder share the same scanner — JDBC's `?` and
 *  Spark SQL's `:bind<N>` — so the tests cover the general `rewrite`
 *  function via both convenience entry points.  The result type
 *  pairs the rewritten SQL with the ordered list of expression
 *  source strings; consumers (JDBC runtime, SparkGen) re-parse each
 *  fragment back to a Scala expression at codegen time. */
class SqlBindRewriterTest extends AnyFunSuite:

  // ── Happy path ────────────────────────────────────────────────────────────

  test("no interpolation — SQL passes through unchanged") {
    val r = SqlBindRewriter.rewriteJdbc("SELECT * FROM users")
    assert(r.sql == "SELECT * FROM users")
    assert(r.binds.isEmpty)
  }

  test("single bind — JDBC ? placeholder") {
    val r = SqlBindRewriter.rewriteJdbc(
      "SELECT * FROM users WHERE id = ${userId}"
    )
    assert(r.sql == "SELECT * FROM users WHERE id = ?")
    assert(r.binds == List("userId"))
  }

  test("single bind — Spark SQL :bind0 placeholder") {
    val r = SqlBindRewriter.rewriteSparkSql(
      "SELECT * FROM users WHERE id = ${userId}"
    )
    assert(r.sql == "SELECT * FROM users WHERE id = :bind0")
    assert(r.binds == List("userId"))
  }

  test("multiple binds — ordered list, sequential names") {
    val r = SqlBindRewriter.rewriteSparkSql(
      "SELECT id FROM users WHERE tenant = ${tenantId} AND status = ${status} LIMIT ${n}"
    )
    assert(r.sql == "SELECT id FROM users WHERE tenant = :bind0 AND status = :bind1 LIMIT :bind2")
    assert(r.binds == List("tenantId", "status", "n"))
  }

  test("expression bodies — Map indexing, function calls, blocks") {
    // These all parse as a single bind despite containing braces.
    val r = SqlBindRewriter.rewriteJdbc(
      """|UPDATE x SET v = ${m("k")}
         |WHERE id = ${ if active then primary else secondary }
         |  AND tag = ${ { val t = "x"; t * 2 } }""".stripMargin
    )
    assert(r.sql ==
      """|UPDATE x SET v = ?
         |WHERE id = ?
         |  AND tag = ?""".stripMargin)
    assert(r.binds == List(
      """m("k")""",
      "if active then primary else secondary",
      """{ val t = "x"; t * 2 }"""
    ))
  }

  test("expression trimmed of leading / trailing whitespace") {
    val r = SqlBindRewriter.rewriteJdbc("WHERE x = ${   spacedExpr   }")
    assert(r.binds == List("spacedExpr"))
  }

  // ── Escapes ───────────────────────────────────────────────────────────────

  test("$$ produces a literal $ in the SQL with no bind") {
    val r = SqlBindRewriter.rewriteJdbc("WHERE price > $$10.00")
    assert(r.sql == "WHERE price > $10.00")
    assert(r.binds.isEmpty)
  }

  test("$$ adjacent to a real ${} — both work") {
    val r = SqlBindRewriter.rewriteSparkSql("WHERE p > $$${threshold}")
    assert(r.sql == "WHERE p > $:bind0")
    assert(r.binds == List("threshold"))
  }

  // ── Lexer-level: braces inside Scala string literals don't close ─────────

  test("} inside Scala string literal inside the expression doesn't prematurely close") {
    val r = SqlBindRewriter.rewriteJdbc("""WHERE x = ${ "value contains }" + suffix }""")
    assert(r.sql == "WHERE x = ?")
    assert(r.binds == List(""""value contains }" + suffix"""))
  }

  test("} inside single-quoted char literal doesn't prematurely close") {
    val r = SqlBindRewriter.rewriteJdbc("WHERE c = ${ if x == '}' then 1 else 0 }")
    assert(r.sql == "WHERE c = ?")
    assert(r.binds == List("if x == '}' then 1 else 0"))
  }

  // ── SPEC § 3.3.1: lexer-level means quotes around ${} are NOT consumed ───

  test("${} inside SQL string literal — lexer-level rewrite preserves quotes") {
    // Pinning the documented behaviour: the rewriter does NOT know about
    // SQL string boundaries.  '${name}' becomes ':bind0' (literally, with
    // the quotes preserved in the rewritten SQL).  Spark SQL / JDBC then
    // treats that as a literal string, not as a parameter — the user
    // should write ${name} without quotes for binding.
    val r = SqlBindRewriter.rewriteJdbc("WHERE name = '${name}'")
    assert(r.sql == "WHERE name = '?'")
    assert(r.binds == List("name"))
  }

  // ── Error paths ──────────────────────────────────────────────────────────

  test("unterminated ${ raises RewriteError with position") {
    val ex = intercept[SqlBindRewriter.RewriteError] {
      SqlBindRewriter.rewriteJdbc("WHERE x = ${unclosed")
    }
    assert(ex.message.contains("unterminated"))
    assert(ex.position == 10)
  }

  test("empty ${} raises RewriteError") {
    val ex = intercept[SqlBindRewriter.RewriteError] {
      SqlBindRewriter.rewriteJdbc("WHERE x = ${  }")
    }
    assert(ex.message.contains("empty"))
  }

  test("bare $ not followed by { and not doubled raises RewriteError") {
    val ex = intercept[SqlBindRewriter.RewriteError] {
      SqlBindRewriter.rewriteJdbc("WHERE x = $foo")
    }
    assert(ex.message.contains("bare '$'"))
  }

  // ── splitStatements ───────────────────────────────────────────────────────

  test("splitStatements — single statement, no semicolon") {
    assert(SqlBindRewriter.splitStatements("SELECT 1") == List("SELECT 1"))
  }

  test("splitStatements — two statements separated by semicolon") {
    val stmts = SqlBindRewriter.splitStatements(
      "INSERT INTO t VALUES (1);\nUPDATE t SET v = 2"
    )
    assert(stmts == List("INSERT INTO t VALUES (1)", "UPDATE t SET v = 2"))
  }

  test("splitStatements — trailing semicolon produces no empty fragment") {
    val stmts = SqlBindRewriter.splitStatements("DELETE FROM t;")
    assert(stmts == List("DELETE FROM t"))
  }

  test("splitStatements — semicolons inside ${} interpolation are not split points") {
    val src   = "CALL p(${if ok then 1; else 0}); UPDATE done SET n = 1"
    val stmts = SqlBindRewriter.splitStatements(src)
    assert(stmts.length == 2)
    assert(stmts(0) == "CALL p(${if ok then 1; else 0})")
    assert(stmts(1) == "UPDATE done SET n = 1")
  }

  test("splitStatements — blank-only source returns empty list") {
    assert(SqlBindRewriter.splitStatements("   ") == Nil)
  }

  test("splitStatements — three statements, each with binds") {
    val src = "INSERT INTO a VALUES (${x});\nINSERT INTO b VALUES (${y});\nSELECT * FROM a"
    val stmts = SqlBindRewriter.splitStatements(src)
    assert(stmts.length == 3)
    assert(stmts(0).contains("${x}"))
    assert(stmts(1).contains("${y}"))
    assert(stmts(2) == "SELECT * FROM a")
  }
