package scalascript.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize

/** v1.27 Phase 4 — NodeBackend wiring for `sql` fenced blocks.
 *
 *  Three end-to-end cases (sqlite in-mem, sqlite file, duckdb in-mem)
 *  plus a `package.json` shape assertion.  End-to-end means: compile
 *  the IR, write `main.mjs` + `package.json` to a stable cache dir,
 *  `npm install` (mtime-stamped so subsequent runs reuse
 *  `node_modules/`), run `node main.mjs`, assert stdout.
 *
 *  Skipped gracefully when `node` / `npm` aren't on PATH. */
class NodeBackendSqlTest extends AnyFunSuite:

  private val backend = NodeBackend()

  private def hasNode: Boolean =
    try ProcessBuilder("node", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def hasNpm: Boolean =
    try ProcessBuilder("npm", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  /** Shared cache dir keyed by the set of npm deps the test expects.
   *  Same `package.json` ⇒ same dir ⇒ single `npm install` across the
   *  whole suite. */
  private def cacheDir(label: String): Path =
    val p = Path.of(sys.props.getOrElse("user.dir", ".")).resolve(s"target/node-backend-sql-test/$label")
    Files.createDirectories(p)
    p

  private def compileToOutputs(src: String): (String, List[SourceArtifact]) =
    val ir = Normalize(Parser.parse(src))
    backend.compile(ir, BackendOptions()) match
      case CompileResult.TextOutput(code, "javascript", sources) => (code, sources)
      case other => fail(s"expected TextOutput(javascript, …), got: $other")

  private def runMjs(dir: Path, code: String, pkgJson: String): String =
    // Force termination 5 seconds after the synchronous script
    // finishes — covers WASM init + sql.js DB load + DuckDB worker
    // spin-up + the actual SQL.  DuckDB Worker / sql.js DB keep
    // handles open, so without this the Node event loop would idle
    // indefinitely.  Production NodeBackend output doesn't want this
    // default (HTTP servers must keep running), so it lives in the
    // test harness only.
    val codeWithExit =
      code + "\nsetTimeout(() => { if (typeof process !== 'undefined') process.exit(0); }, 5000);\n"
    Files.writeString(dir.resolve("main.cjs"), codeWithExit, StandardCharsets.UTF_8)
    Files.writeString(dir.resolve("package.json"), pkgJson, StandardCharsets.UTF_8)
    val stamp    = dir.resolve(".npm-install-stamp")
    val pkgMtime = Files.getLastModifiedTime(dir.resolve("package.json")).toMillis
    val installed = Files.exists(dir.resolve("node_modules")) &&
                    Files.exists(stamp) &&
                    Files.getLastModifiedTime(stamp).toMillis >= pkgMtime
    if !installed then
      val inst = ProcessBuilder("npm", "install", "--no-audit", "--no-fund", "--silent")
        .directory(dir.toFile)
        .redirectErrorStream(true)
        .start()
      val instOut = new String(inst.getInputStream.readAllBytes(), "UTF-8")
      val instCode = inst.waitFor()
      assert(instCode == 0, s"npm install failed (exit $instCode):\n$instOut")
      Files.writeString(stamp, "ok")
    val run = ProcessBuilder("node", "main.cjs")
      .directory(dir.toFile)
      .redirectErrorStream(true)
      .start()
    val out  = new String(run.getInputStream.readAllBytes(), "UTF-8")
    val code2 = run.waitFor()
    if code2 != 0 then fail(s"node main.mjs failed (exit $code2):\n$out")
    out.trim

  private def packageJson(sources: List[SourceArtifact]): String =
    sources.collectFirst { case SourceArtifact("package.json", c) => c }
      .getOrElse(fail(s"expected a package.json in sources, got: ${sources.map(_.name)}"))

  // ── package.json shape — pure unit test ────────────────────────────

  test("no sql block → no package.json artifact"):
    val (_, sources) = compileToOutputs(
      """|# Test
         |
         |```scalascript
         |val x = 1
         |```
         |""".stripMargin)
    assert(sources.forall(_.name != "package.json"))

  test("sql with sqlite database → package.json lists only sql.js dep"):
    val (_, sources) = compileToOutputs(
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
    val pkg = packageJson(sources)
    assert(pkg.contains("\"sql.js\""))
    assert(!pkg.contains("@duckdb/duckdb-wasm"))
    assert(!pkg.contains("web-worker"))
    // CJS bundle (matches the JsRuntime's `require(...)` calls).
    assert(pkg.contains("\"main\": \"main.cjs\""))

  test("sql with duckdb database → package.json lists duckdb + web-worker, not sql.js"):
    val (_, sources) = compileToOutputs(
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
    val pkg = packageJson(sources)
    assert(pkg.contains("@duckdb/duckdb-wasm"))
    assert(pkg.contains("web-worker"))
    assert(!pkg.contains("\"sql.js\""))

  test("sql with both providers → package.json lists both + web-worker"):
    val (_, sources) = compileToOutputs(
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
    val pkg = packageJson(sources)
    assert(pkg.contains("sql.js"))
    assert(pkg.contains("@duckdb/duckdb-wasm"))
    assert(pkg.contains("web-worker"))

  test("sql with no databases declared → package.json lists every provider (annotation fallback)"):
    val (_, sources) = compileToOutputs(
      """|# Q
         |
         |```sql
         |SELECT 1
         |```
         |""".stripMargin)
    val pkg = packageJson(sources)
    // Without a databases: declaration, the module relies on the
    // `@sscBrowserSqlConnection` annotation override (Phase 6).  Until
    // we can statically tell which provider the annotation points at,
    // ship both deps so `npm install` covers any choice.
    assert(pkg.contains("sql.js"))
    assert(pkg.contains("@duckdb/duckdb-wasm"))

  // ── End-to-end: real Node + npm + sql.js + DuckDB-Wasm ─────────────

  test("end-to-end: sqlite in-memory CRUD round-trip under node"):
    assume(hasNode, "node not available")
    assume(hasNpm,  "npm not available")
    // Each sql block lives in its own section so each gets a
    // dedicated `<sectionId>.sql` alias.  Without this, the section's
    // alias would point at the FIRST sql block's result — usually a
    // CREATE returning `{ kind: 'update' }` rather than the SELECT.
    val src =
      """|---
         |databases:
         |  default: { url: "sqlite::memory:" }
         |---
         |# Setup
         |
         |```sql
         |CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT)
         |```
         |
         |# Seed
         |
         |```sql
         |INSERT INTO users(id, name) VALUES (1, "alice"), (2, "bob")
         |```
         |
         |# Query
         |
         |```sql
         |SELECT name FROM users ORDER BY id
         |```
         |
         |# Output
         |
         |```scalascript
         |println(Query.sql.rows.length)
         |println(Query.sql.rows(0)("name"))
         |println(Query.sql.rows(1)("name"))
         |```
         |""".stripMargin
    val (code, sources) = compileToOutputs(src)
    val pkg = packageJson(sources)
    val out = runMjs(cacheDir("sqlite-inmem"), code, pkg)
    assert(out == "2\nalice\nbob", s"got:\n$out")

  test("end-to-end: duckdb in-memory aggregation under node"):
    assume(hasNode, "node not available")
    assume(hasNpm,  "npm not available")
    val src =
      """|---
         |databases:
         |  analytics: { url: "duckdb:" }
         |---
         |# Schema
         |
         |```sql @db=analytics
         |CREATE TABLE events(category VARCHAR, amount INTEGER)
         |```
         |
         |# Seed
         |
         |```sql @db=analytics
         |INSERT INTO events VALUES ('a', 10), ('a', 20), ('b', 5)
         |```
         |
         |# Totals
         |
         |```sql @db=analytics
         |SELECT category, SUM(amount) AS total FROM events GROUP BY category ORDER BY category
         |```
         |
         |# Output
         |
         |```scalascript
         |val rs = Totals.sql.rows
         |println(rs.length)
         |println(rs(0)("category") + "=" + rs(0)("total"))
         |println(rs(1)("category") + "=" + rs(1)("total"))
         |```
         |""".stripMargin
    val (code, sources) = compileToOutputs(src)
    val pkg = packageJson(sources)
    val out = runMjs(cacheDir("duckdb-inmem"), code, pkg)
    assert(out == "2\na=30\nb=5", s"got:\n$out")

  test("end-to-end: sql binds (${expr}) evaluate in the surrounding scope"):
    assume(hasNode, "node not available")
    assume(hasNpm,  "npm not available")
    val src =
      """|---
         |databases:
         |  default: { url: "sqlite::memory:" }
         |---
         |# Setup
         |
         |```sql
         |CREATE TABLE t(id INTEGER, label TEXT)
         |```
         |
         |# Seed
         |
         |```scalascript
         |val minId = 10
         |val tag   = "hi"
         |```
         |
         |```sql
         |INSERT INTO t VALUES (${minId + 5}, ${tag})
         |```
         |
         |# Query
         |
         |```sql
         |SELECT id, label FROM t WHERE id > ${minId}
         |```
         |
         |# Output
         |
         |```scalascript
         |val row = Query.sql.rows(0)
         |println(row("id"))
         |println(row("label"))
         |```
         |""".stripMargin
    val (code, sources) = compileToOutputs(src)
    val pkg = packageJson(sources)
    val out = runMjs(cacheDir("sqlite-binds"), code, pkg)
    assert(out == "15\nhi", s"got:\n$out")
