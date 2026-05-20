package scalascript.sql.js

import org.scalatest.funsuite.AnyFunSuite

/** v1.27 Phase 2 — bundle preamble emit: resource load + registry-init JS. */
class SqlRuntimeJsEmitTest extends AnyFunSuite:

  // ── resource source ────────────────────────────────────────────────

  test("runtimeSource: loads sql-runtime.mjs from classpath"):
    val src = SqlRuntimeJsEmit.runtimeSource
    assert(src.nonEmpty)
    // sanity: covers the core exports the codegen relies on
    assert(src.contains("export class ConnectionRegistry"))
    assert(src.contains("export async function execute"))
    assert(src.contains("export function makeRow"))
    assert(src.contains("export const Providers"))
    assert(src.contains("export const SqlJsProvider"))
    assert(src.contains("export const DuckDbWasmProvider"))

  test("runtimeSource: idempotent across calls (lazy val)"):
    assert(SqlRuntimeJsEmit.runtimeSource eq SqlRuntimeJsEmit.runtimeSource)

  test("ResourcePath: matches the actual resource location"):
    assert(SqlRuntimeJsEmit.ResourcePath == "scalascript/sql/js/sql-runtime.mjs")
    val cl = Thread.currentThread().getContextClassLoader
    assert(cl.getResource(SqlRuntimeJsEmit.ResourcePath) != null)

  // ── registry init JS ───────────────────────────────────────────────

  test("emitRegistryInit: empty list → empty-registry initializer"):
    val js = SqlRuntimeJsEmit.emitRegistryInit(Nil)
    assert(js.contains("new ConnectionRegistry({})"))
    assert(js.contains("_ssc_sql_connections"))
    assert(js.contains("_ssc_sql_resolve"))

  test("emitRegistryInit: single entry, url only"):
    val js = SqlRuntimeJsEmit.emitRegistryInit(Seq(
      SqlRuntimeJsEmit.DatabaseEntry("default", "sqlite::memory:")
    ))
    assert(js.contains("\"default\""))
    assert(js.contains("url: \"sqlite::memory:\""))
    assert(!js.contains("user:"))
    assert(!js.contains("password:"))

  test("emitRegistryInit: full record (url + user + password + driver)"):
    val js = SqlRuntimeJsEmit.emitRegistryInit(Seq(
      SqlRuntimeJsEmit.DatabaseEntry(
        "reports", "duckdb:./events.duckdb",
        user = Some("svc"), password = Some("hunter2"), driver = Some("duckdb-wasm"),
      )
    ))
    assert(js.contains("\"reports\""))
    assert(js.contains("url: \"duckdb:./events.duckdb\""))
    assert(js.contains("user: \"svc\""))
    assert(js.contains("password: \"hunter2\""))
    assert(js.contains("driver: \"duckdb-wasm\""))

  test("emitRegistryInit: multiple entries → both present"):
    val js = SqlRuntimeJsEmit.emitRegistryInit(Seq(
      SqlRuntimeJsEmit.DatabaseEntry("default",   "sqlite::memory:"),
      SqlRuntimeJsEmit.DatabaseEntry("analytics", "duckdb:"),
    ))
    assert(js.contains("\"default\""))
    assert(js.contains("\"analytics\""))
    assert(js.contains("sqlite::memory:"))
    assert(js.contains("duckdb:"))

  test("emitRegistryInit: ${env:NAME} markers preserved verbatim (resolved at runtime)"):
    val js = SqlRuntimeJsEmit.emitRegistryInit(Seq(
      SqlRuntimeJsEmit.DatabaseEntry("audit", "sqlite:${env:AUDIT_PATH}")
    ))
    // The bundle is what runs in the JS env; env resolution is a
    // runtime step (resolveEnvRefs in sql-runtime.mjs).  Bundle text
    // must contain the literal marker.
    assert(js.contains("sqlite:${env:AUDIT_PATH}"))

  // ── jsString escapes ───────────────────────────────────────────────

  test("jsString: simple ASCII"):
    assert(SqlRuntimeJsEmit.jsString("hello") == "\"hello\"")

  test("jsString: quote and backslash escape"):
    assert(SqlRuntimeJsEmit.jsString("a\"b\\c") == "\"a\\\"b\\\\c\"")

  test("jsString: control chars → \\uXXXX"):
    assert(SqlRuntimeJsEmit.jsString("") == "\"\\u0001\"")

  test("jsString: newline / tab escape"):
    assert(SqlRuntimeJsEmit.jsString("a\nb\tc") == "\"a\\nb\\tc\"")

  // ── full preamble ──────────────────────────────────────────────────

  test("emitPreamble: runtime source then registry init"):
    val pre = SqlRuntimeJsEmit.emitPreamble(Seq(
      SqlRuntimeJsEmit.DatabaseEntry("default", "sqlite::memory:")
    ))
    assert(pre.contains("export class ConnectionRegistry"))
    val ridx = pre.indexOf("new ConnectionRegistry({")
    assert(ridx > 0)
    // runtime export must precede the init usage
    val eidx = pre.indexOf("export class ConnectionRegistry")
    assert(eidx < ridx)
