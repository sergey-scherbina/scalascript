package scalascript

import org.scalatest.funsuite.AnyFunSuite

import scalascript.codegen.JsGen
import scalascript.parser.Parser

/** v1.27 Phase 3 — JsGen JS-source emission for `sql` fenced code
 *  blocks.  Text-shape assertions only; the end-to-end execution path
 *  is exercised by `backendSqlRuntimeJs/SqlRuntimeJsNodeTest` against
 *  the real sql.js + DuckDB-Wasm packages. */
class JsGenSqlBlockTest extends AnyFunSuite:

  private def emit(ssc: String): String =
    JsGen.generate(Parser.parse(ssc))

  // ── No-sql path: legacy modules emit unchanged ──────────────────────

  test("module without sql blocks emits no SQL runtime preamble"):
    val code = emit(
      """|# Test
         |
         |```scalascript
         |val x = 1
         |```
         |""".stripMargin)
    assert(!code.contains("_ssc_sql_registry"))
    assert(!code.contains("_ssc_sql_resolve"))
    assert(!code.contains("SqlRuntimeJs"))
    assert(!code.contains("class ConnectionRegistry"))

  // ── Runtime preamble appears when sql block is present ─────────────

  test("sql block triggers sql-runtime.mjs inlined preamble"):
    val code = emit(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin)
    // Preamble surface
    assert(code.contains("class ConnectionRegistry"))
    assert(code.contains("function execute"))
    assert(code.contains("function makeRow"))
    assert(code.contains("SqlJsProvider"))
    assert(code.contains("DuckDbWasmProvider"))
    // SqlRuntimeJs namespace
    assert(code.contains("const SqlRuntimeJs"))
    // ESM `export` keywords stripped (classic-script compatible)
    assert(!code.contains("export class ConnectionRegistry"))
    assert(!code.contains("export function execute"))

  test("sql block wraps user body in async IIFE"):
    val code = emit(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin)
    assert(code.contains("(async () => {"))
    assert(code.contains("})().catch("))

  // ── Empty / populated databases: registry materialisation ──────────

  test("module with sql but no front-matter databases → empty registry init"):
    val code = emit(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin)
    assert(code.contains("new ConnectionRegistry({})"))
    assert(code.contains("_ssc_sql_resolve"))
    assert(code.contains("_ssc_sql_connections"))

  test("front-matter databases entries land in the registry"):
    val code = emit(
      """|---
         |databases:
         |  default:   { url: "sqlite::memory:" }
         |  analytics: { url: "duckdb:" }
         |---
         |# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin)
    // Names + URLs spliced into the ConnectionRegistry constructor
    assert(code.contains("\"default\""))
    assert(code.contains("\"analytics\""))
    assert(code.contains("sqlite::memory:"))
    assert(code.contains("duckdb:"))

  test("databases entries preserve ${env:NAME} markers verbatim"):
    val code = emit(
      """|---
         |databases:
         |  audit: { url: "sqlite:${env:AUDIT_PATH}" }
         |---
         |# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin)
    // Env resolution is a runtime step in sql-runtime.mjs; emit must
    // contain the literal marker.
    assert(code.contains("sqlite:${env:AUDIT_PATH}"))

  // ── _sqlBlock_<N> per-block emission ────────────────────────────────

  test("sql block emits `const _sqlBlock_0 = await SqlRuntimeJs.execute(...)`"):
    val code = emit(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin)
    assert(code.contains("const _sqlBlock_0"))
    assert(code.contains("await SqlRuntimeJs.execute"))
    assert(code.contains("await _ssc_sql_resolve(undefined)"))
    // No-binds case → empty array argument
    assert(code.contains("[]"))

  test("sql block with ${expr} interpolations becomes ?-template + bind list"):
    val code = emit(
      """|# Q
         |
         |```sql
         |SELECT * FROM users WHERE id = ${userId} AND age > ${minAge}
         |```
         |""".stripMargin)
    // Two ? placeholders in the SQL literal
    val sqlLitMatches = "id = \\? AND age > \\?".r.findAllIn(code).length
    assert(sqlLitMatches >= 1, s"expected ?-templated SQL, got:\n$code")
    // Bind expression text appears in the binds array
    assert(code.contains("userId"))
    assert(code.contains("minAge"))

  test("sequential sql blocks get sequential `_sqlBlock_<N>` numbering"):
    val code = emit(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |
         |```sql
         |SELECT 2
         |```
         |
         |```sql
         |SELECT 3
         |```
         |""".stripMargin)
    assert(code.contains("_sqlBlock_0"))
    assert(code.contains("_sqlBlock_1"))
    assert(code.contains("_sqlBlock_2"))

  // ── Section alias (first-only) ──────────────────────────────────────

  test("first sql block in a section binds `<sectionId>.sql`; second does not redefine"):
    val code = emit(
      """|# Users
         |
         |```sql
         |SELECT name FROM users
         |```
         |
         |```sql
         |SELECT email FROM users
         |```
         |""".stripMargin)
    // First-block alias present
    assert(code.contains("Users.sql = _sqlBlock_0"))
    // Second block does not produce a `.sql =` reassignment
    assert(!code.contains("Users.sql = _sqlBlock_1"))

  // ── @db=name threading ─────────────────────────────────────────────

  test("`@db=name` attribute threads through to _ssc_sql_resolve"):
    val code = emit(
      """|# Q
         |
         |```sql @db=analytics
         |SELECT 1
         |```
         |""".stripMargin)
    assert(code.contains("await _ssc_sql_resolve(\"analytics\")"))
    // The default-name case should not appear in this emission.
    assert(!code.contains("_ssc_sql_resolve(undefined)"))

  test("absent @db= falls back to undefined → resolver default"):
    val code = emit(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin)
    assert(code.contains("_ssc_sql_resolve(undefined)"))
