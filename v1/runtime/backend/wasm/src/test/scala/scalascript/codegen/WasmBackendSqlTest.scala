package scalascript.codegen

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize

/** v1.27 Phase 5 — WasmBackend wiring for `sql` fenced blocks.
 *
 *  Asserts the JS-shim asset bundle (`sql-runtime.mjs` +
 *  `sql-registry.mjs` + `package.json`) that the Wasm backend emits
 *  when a module has sql blocks.  Mirrors `NodeBackendSqlTest`'s
 *  package.json-shape assertions; end-to-end execution of sql blocks
 *  through the Wasm shim is left to Phase 7 (`SqlBrowserExamplesTest`
 *  / `SqlBrowserConformanceCaptureTest`).
 *
 *  No `npm install` here — these are pure codegen-shape unit tests
 *  that don't depend on a Node toolchain. */
class WasmBackendSqlTest extends AnyFunSuite with Matchers:

  private val backend = WasmBackend()

  private def compile(src: String): List[Segment] =
    val ir = Normalize(Parser.parse(src))
    backend.compile(ir, BackendOptions()) match
      case CompileResult.Segmented(segs) => segs
      case other                         => fail(s"expected Segmented(...), got: $other")

  private def assetContent(segs: List[Segment], name: String): String =
    segs.collectFirst { case Segment.Asset(n, bytes, _) if n == name => new String(bytes, "UTF-8") }
      .getOrElse(fail(s"expected an asset named '$name', got: ${segs.collect { case Segment.Asset(n, _, _) => n }}"))

  // ── no sql block → no Phase-5 assets ─────────────────────────────────

  test("no sql block → no Phase-5 assets emitted (Segmented(Nil) for empty module)"):
    val segs = compile(
      """|# Test
         |
         |```html
         |<h1>Hello</h1>
         |```
         |""".stripMargin)
    // html-only module: no compilable scala/scalascript blocks, no sql blocks
    // → Segmented(Nil) is the expected empty result.
    segs shouldBe Nil

  // ── package.json shape — per-provider gating ─────────────────────────

  test("sql with sqlite database → package.json lists only sql.js dep"):
    val segs = compile(
      """|---
         |databases:
         |  default: { url: "sqlite::memory:" }
         |---
         |# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin)
    val pkg = assetContent(segs, "package.json")
    pkg should include("\"sql.js\"")
    pkg should not include "@duckdb/duckdb-wasm"
    pkg should not include "web-worker"
    // ESM bundle (matches the Scala.js Wasm shim, which is itself an
    // ES module — different from NodeBackend's CJS choice).
    pkg should include("\"type\": \"module\"")

  test("sql with duckdb database → package.json lists duckdb + web-worker, not sql.js"):
    val segs = compile(
      """|---
         |databases:
         |  analytics: { url: "duckdb:" }
         |---
         |# Q
         |
         |```sql @db=analytics
         |SELECT 1
         |```
         |""".stripMargin)
    val pkg = assetContent(segs, "package.json")
    pkg should include("@duckdb/duckdb-wasm")
    pkg should include("web-worker")
    pkg should not include "\"sql.js\""

  test("sql with both providers → package.json lists both + web-worker"):
    val segs = compile(
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
    val pkg = assetContent(segs, "package.json")
    pkg should include("sql.js")
    pkg should include("@duckdb/duckdb-wasm")
    pkg should include("web-worker")

  test("sql with no databases declared → package.json lists every provider"):
    val segs = compile(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin)
    val pkg = assetContent(segs, "package.json")
    // Without a databases: declaration, the module relies on the
    // `@sscBrowserSqlConnection` annotation override (Phase 6).  Until
    // we can statically tell which provider the annotation points at,
    // ship both deps so `npm install` covers any choice.
    pkg should include("sql.js")
    pkg should include("@duckdb/duckdb-wasm")

  // ── sql-runtime.mjs + sql-registry.mjs shape ─────────────────────────

  test("sql block → sql-runtime.mjs asset ships the bundled JS source verbatim"):
    val segs = compile(
      """|---
         |databases:
         |  default: { url: "sqlite::memory:" }
         |---
         |# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin)
    val rt = assetContent(segs, "sql-runtime.mjs")
    // Source-of-truth check: must contain ConnectionRegistry / execute
    // names that backends call into from the registry preamble.
    rt should include("ConnectionRegistry")
    rt should include("execute")

  test("sql block → sql-registry.mjs initialises the named connections from front-matter"):
    val segs = compile(
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
    val reg = assetContent(segs, "sql-registry.mjs")
    reg should include("_ssc_sql_registry")
    reg should include("\"default\"")
    reg should include("\"analytics\"")
    reg should include("sqlite::memory:")
    reg should include("duckdb:")

  test("sql block but empty databases: → sql-registry.mjs ships an empty-registry init"):
    val segs = compile(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin)
    val reg = assetContent(segs, "sql-registry.mjs")
    reg should include("_ssc_sql_registry")
    // No declared connections → registry constructed from an empty
    // object literal `{}`.
    reg should include("new ConnectionRegistry({})")
