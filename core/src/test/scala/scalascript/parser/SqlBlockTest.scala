package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast
import scalascript.ir
import scalascript.transform.{Normalize, Denormalize}

/** v1.26 Phase 2 + Phase 3 — front-end recognition of `sql` fenced
 *  code blocks and bind-parameter extraction.
 *
 *  Contract: a `sql` block is *parameterised opaque exec*
 *  (`Lang.isOpaqueExec` and `Lang.isParameterizedExec` both true).
 *  Parser preserves the source verbatim — including `${expr}` and
 *  `$$` markers.  `Normalize` runs `SqlBindRewriter` and emits an
 *  `ir.Content.SqlBlock(source, binds, …)` with the original source
 *  intact and the bind expressions extracted in occurrence order.
 *  `Denormalize` round-trips the block by reusing the preserved
 *  source — the `${expr}` syntax reappears verbatim. */
class SqlBlockTest extends AnyFunSuite:

  private def firstBlock(src: String): ast.Content.CodeBlock =
    val module = Parser.parse(src)
    module.sections
      .flatMap(_.content)
      .collectFirst { case cb: ast.Content.CodeBlock => cb }
      .getOrElse(fail("no CodeBlock parsed"))

  test("sql block: lang tag preserved verbatim from the fence") {
    val src =
      """|# Queries
         |
         |```sql
         |SELECT id, name FROM users WHERE id = ${userId}
         |```
         |""".stripMargin
    val cb = firstBlock(src)
    assert(cb.lang == "sql")
    assert(cb.source.trim == "SELECT id, name FROM users WHERE id = ${userId}")
    // No scalameta parse on SQL — Phase 3 will populate bind exprs in IR.
    assert(cb.tree.isEmpty)
  }

  test("sql lang classification: opaque-exec + parameterised, not string/parseable") {
    val src =
      """|# Queries
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin
    val cb = firstBlock(src)
    assert(ast.Lang.isSql(cb.lang))
    assert(ast.Lang.isOpaqueExec(cb.lang))
    assert(ast.Lang.isParameterizedExec(cb.lang))
    assert(!ast.Lang.isStringBlock(cb.lang))
    assert(!ast.Lang.isParseable(cb.lang))
    assert(!ast.Lang.isNode(cb.lang))
  }

  test("sql block: Normalize routes through SqlBlock with source intact") {
    val src =
      """|# Schema
         |
         |```sql
         |CREATE TABLE users (
         |  id    BIGINT PRIMARY KEY,
         |  name  VARCHAR(120) NOT NULL,
         |  email VARCHAR(255) UNIQUE
         |)
         |```
         |""".stripMargin
    val module     = Parser.parse(src)
    val normalised = Normalize(module)
    val sql = normalised.sections
      .flatMap(_.content)
      .collectFirst { case sb: ir.Content.SqlBlock => sb }
      .getOrElse(fail("Normalize did not produce a SqlBlock"))
    assert(sql.source.contains("CREATE TABLE users"))
    assert(sql.source.contains("VARCHAR(255) UNIQUE"))
    assert(sql.binds.isEmpty, "DDL has no bind parameters")
    assert(sql.dbName.isEmpty, "Phase 3 — default connection until Phase 5 wires `@db=`")
  }

  test("sql block: Normalize extracts ${expr} into the bind list in order") {
    val src =
      """|# Queries
         |
         |```sql
         |SELECT id, name, email FROM users
         |WHERE tenant_id = ${tenantId} AND status = ${status}
         |LIMIT ${pageSize}
         |```
         |""".stripMargin
    val module     = Parser.parse(src)
    val normalised = Normalize(module)
    val sql = normalised.sections
      .flatMap(_.content)
      .collectFirst { case sb: ir.Content.SqlBlock => sb }
      .getOrElse(fail("Normalize did not produce a SqlBlock"))
    assert(sql.binds == List("tenantId", "status", "pageSize"))
    // Source is preserved verbatim for round-trip.
    assert(sql.source.contains("${tenantId}"))
    assert(sql.source.contains("${status}"))
    assert(sql.source.contains("${pageSize}"))
  }

  test("sql block: round-trips through Normalize → Denormalize unchanged") {
    val src =
      """|# Queries
         |
         |```sql
         |SELECT id, name, email FROM users
         |WHERE tenant_id = ${tenantId} AND status = ${status}
         |LIMIT ${pageSize}
         |```
         |""".stripMargin
    val module         = Parser.parse(src)
    val redenormalised = Denormalize(Normalize(module))
    val cb = redenormalised.sections
      .flatMap(_.content)
      .collectFirst { case cb: ast.Content.CodeBlock => cb }
      .getOrElse(fail("Denormalize produced no CodeBlock"))
    assert(cb.lang == "sql")
    // The original `${expr}` literals reappear verbatim — Denormalize
    // emits the SqlBlock's `source` field, not its `sqlWithQ` form.
    assert(cb.source.contains("${tenantId}"))
    assert(cb.source.contains("${status}"))
    assert(cb.source.contains("${pageSize}"))
    assert(cb.tree.isEmpty, "Denormalize must not invoke a parser on SQL source")
  }

  test("sql block with no parameters: SqlBlock with empty bind list") {
    val src =
      """|# Reports
         |
         |```sql
         |SELECT count(*) FROM events
         |```
         |""".stripMargin
    val module     = Parser.parse(src)
    val normalised = Normalize(module)
    val sql = normalised.sections
      .flatMap(_.content)
      .collectFirst { case sb: ir.Content.SqlBlock => sb }
      .getOrElse(fail("Normalize did not produce a SqlBlock"))
    assert(sql.source.trim == "SELECT count(*) FROM events")
    assert(sql.binds.isEmpty)
  }

  test("malformed sql falls back to EmbeddedBlock (front-end stays robust)") {
    // Lone `$` is a static error in the rewriter, but Normalize catches
    // it and falls back to EmbeddedBlock so the rest of the pipeline
    // doesn't crash on a single bad block.  CapabilityCheck still
    // surfaces UnknownBlockLanguage on non-JVM backends; the execution
    // layer re-runs the rewriter for a precise diagnostic.
    val src =
      """|# Bad
         |
         |```sql
         |SELECT * FROM t WHERE price = $5
         |```
         |""".stripMargin
    val normalised = Normalize(Parser.parse(src))
    val isEmbedded = normalised.sections
      .flatMap(_.content)
      .exists {
        case _: ir.Content.SqlBlock      => false
        case _: ir.Content.EmbeddedBlock => true
        case _                           => false
      }
    assert(isEmbedded, "malformed sql must fall back to EmbeddedBlock, not crash")
  }
