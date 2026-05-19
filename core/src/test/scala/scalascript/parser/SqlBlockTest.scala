package scalascript.parser

import org.scalatest.funsuite.AnyFunSuite
import scalascript.ast
import scalascript.ir
import scalascript.transform.{Normalize, Denormalize}

/** v1.26 Phase 2 — front-end recognition of `sql` fenced code blocks.
 *
 *  Phase-2 contract: a `sql` block is *parameterised opaque exec*
 *  (`Lang.isOpaqueExec` and `Lang.isParameterizedExec` both true).  At
 *  this phase the source survives Parse → Normalize → Denormalize
 *  unchanged with no bind-parameter rewriting — Phase 3 introduces the
 *  `${expr}` → `?` lift.  The block is therefore still routed through
 *  `ir.Content.EmbeddedBlock`, identical to the Node.js path; what
 *  distinguishes `sql` here is only the lang-tag classification.
 *
 *  Capability-level rejection on non-JVM backends
 *  (`Diagnostic.UnknownBlockLanguage`) is already wired generically in
 *  `validate/CapabilityCheck` via `Lang.isOpaqueExec`; an end-to-end
 *  test sits in `CapabilityCheckTest` once the JVM target declares
 *  `sql` in its `blockLanguages`. */
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

  test("sql block: survives Normalize as EmbeddedBlock with source intact") {
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
    val embedded = normalised.sections
      .flatMap(_.content)
      .collectFirst { case eb: ir.Content.EmbeddedBlock => eb }
      .getOrElse(fail("Normalize did not produce an EmbeddedBlock for sql"))
    assert(embedded.language == "sql")
    assert(embedded.source.contains("CREATE TABLE users"))
    assert(embedded.source.contains("VARCHAR(255) UNIQUE"))
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
    // Phase 2 contract: ${expr} occurrences are preserved literally —
    // Phase 3 introduces the bind-parameter rewriter that will lift
    // them out of the source string.
    assert(cb.source.contains("${tenantId}"))
    assert(cb.source.contains("${status}"))
    assert(cb.source.contains("${pageSize}"))
    assert(cb.tree.isEmpty, "Denormalize must not invoke a parser on SQL source")
  }

  test("sql block with no parameters: survives untouched") {
    val src =
      """|# Reports
         |
         |```sql
         |SELECT count(*) FROM events
         |```
         |""".stripMargin
    val module         = Parser.parse(src)
    val redenormalised = Denormalize(Normalize(module))
    val cb = redenormalised.sections
      .flatMap(_.content)
      .collectFirst { case cb: ast.Content.CodeBlock => cb }
      .getOrElse(fail("Denormalize produced no CodeBlock"))
    assert(cb.lang == "sql")
    assert(cb.source.trim == "SELECT count(*) FROM events")
  }
