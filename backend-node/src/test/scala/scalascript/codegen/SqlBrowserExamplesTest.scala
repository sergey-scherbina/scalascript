package scalascript.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize

/** v1.27 Phase 7 — pin the bundled `examples/sql-browser-*.ssc`
 *  shapes against the real Node + npm runtime.  Sources are inlined
 *  verbatim so the test is self-contained — drift between the
 *  on-disk example and the inlined copy is caught at the shape
 *  level (the inlined source must parse + execute under Node).
 *
 *  Mirrors `NodeBackendSqlTest`'s end-to-end harness: compile the
 *  example through NodeBackend, write `main.cjs` + `package.json`
 *  to a stable cache dir, `npm install` (mtime-stamped), `node
 *  main.cjs`, assert stdout.  Skipped gracefully when `node` / `npm`
 *  aren't on PATH. */
class SqlBrowserExamplesTest extends AnyFunSuite:

  private val backend = NodeBackend()

  private def hasNode: Boolean =
    try ProcessBuilder("node", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def hasNpm: Boolean =
    try ProcessBuilder("npm", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def cacheDir(label: String): Path =
    val p = Path.of(sys.props.getOrElse("user.dir", ".")).resolve(s"target/sql-browser-examples-test/$label")
    Files.createDirectories(p)
    p

  private def compileToOutputs(src: String): (String, List[SourceArtifact]) =
    val ir = Normalize(Parser.parse(src))
    backend.compile(ir, BackendOptions()) match
      case CompileResult.TextOutput(code, "javascript", sources) => (code, sources)
      case other => fail(s"expected TextOutput(javascript, …), got: $other")

  private def packageJson(sources: List[SourceArtifact]): String =
    sources.collectFirst { case SourceArtifact("package.json", c) => c }
      .getOrElse(fail(s"expected a package.json in sources, got: ${sources.map(_.name)}"))

  private def runMjs(dir: Path, code: String, pkgJson: String): String =
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
    if code2 != 0 then fail(s"node main.cjs failed (exit $code2).  Output:\n$out")
    out.trim

  // ── examples/sql-browser-sqlite.ssc ────────────────────────────────

  test("examples/sql-browser-sqlite.ssc runs end-to-end under Node"):
    assume(hasNode, "node not available")
    assume(hasNpm,  "npm not available")
    val src =
      """|---
         |databases:
         |  default:
         |    url: "sqlite::memory:"
         |---
         |
         |# Schema
         |
         |```sql
         |CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, active INTEGER NOT NULL)
         |```
         |
         |# Seed
         |
         |```scalascript
         |val newId   = 1
         |val newName = "Alice"
         |```
         |
         |```sql
         |INSERT INTO users(id, name, active) VALUES (${newId}, ${newName}, 1)
         |```
         |
         |```sql
         |INSERT INTO users(id, name, active) VALUES (2, "Bob", 0)
         |```
         |
         |# Active
         |
         |```sql
         |SELECT id, name FROM users WHERE active = 1 ORDER BY id
         |```
         |
         |# Output
         |
         |```scalascript
         |val rows = Active.sql.rows
         |println("active count=" + rows.length)
         |println("first id="   + rows(0)("id"))
         |println("first name=" + rows(0)("name"))
         |```
         |""".stripMargin
    val (code, sources) = compileToOutputs(src)
    val pkg = packageJson(sources)
    val out = runMjs(cacheDir("sqlite"), code, pkg)
    assert(out == "active count=1\nfirst id=1\nfirst name=Alice", s"got:\n$out")

  // ── examples/sql-browser-duckdb.ssc ────────────────────────────────

  test("examples/sql-browser-duckdb.ssc runs end-to-end under Node"):
    assume(hasNode, "node not available")
    assume(hasNpm,  "npm not available")
    val src =
      """|---
         |databases:
         |  default:
         |    url: "sqlite::memory:"
         |  analytics:
         |    url: "duckdb:"
         |---
         |
         |# Schema — transactional (sqlite)
         |
         |```sql
         |CREATE TABLE orders (id INTEGER PRIMARY KEY, sku TEXT NOT NULL, qty INTEGER NOT NULL)
         |```
         |
         |```sql
         |INSERT INTO orders(id, sku, qty) VALUES
         |  (1, "apple", 10),
         |  (2, "apple", 5),
         |  (3, "banana", 7),
         |  (4, "banana", 2),
         |  (5, "cherry", 3)
         |```
         |
         |# Schema — analytical (duckdb)
         |
         |```sql @db=analytics
         |CREATE TABLE events (sku VARCHAR, units INTEGER)
         |```
         |
         |```sql @db=analytics
         |INSERT INTO events VALUES ('apple', 15), ('banana', 9), ('cherry', 3)
         |```
         |
         |# Totals
         |
         |```sql @db=analytics
         |SELECT sku, SUM(units) AS total FROM events GROUP BY sku ORDER BY sku
         |```
         |
         |# Output
         |
         |```scalascript
         |val rows = Totals.sql.rows
         |println("rows=" + rows.length)
         |println(rows(0)("sku") + "=" + rows(0)("total"))
         |println(rows(1)("sku") + "=" + rows(1)("total"))
         |println(rows(2)("sku") + "=" + rows(2)("total"))
         |```
         |""".stripMargin
    val (code, sources) = compileToOutputs(src)
    val pkg = packageJson(sources)
    val out = runMjs(cacheDir("duckdb"), code, pkg)
    assert(out == "rows=3\napple=15\nbanana=9\ncherry=3", s"got:\n$out")
