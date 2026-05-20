package scalascript.sql.js

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import org.scalatest.funsuite.AnyFunSuite

/** v1.27 Phase 2 — end-to-end Node tests against real sql.js and
 *  DuckDB-Wasm npm packages.
 *
 *  Strategy:
 *  1. Resolve `sql-runtime.mjs` from the main classpath.
 *  2. Resolve `package.json` + `*.test.mjs` from the test classpath.
 *  3. Materialise everything into a stable on-disk dir under
 *     `target/sql-js-node-test/` (so `node_modules/` survives across
 *     runs — `npm install` is cached and fast on the second run).
 *  4. Run `npm install` if `node_modules/` is missing or `package.json`
 *     changed since the last install.
 *  5. Run `node --test --test-force-exit *.test.mjs` and assert exit
 *     code 0.
 *  6. Skip gracefully (via `assume`) when node / npm aren't on PATH.
 */
class SqlRuntimeJsNodeTest extends AnyFunSuite:

  private def hasNode: Boolean =
    try ProcessBuilder("node", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def hasNpm: Boolean =
    try ProcessBuilder("npm", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private lazy val workDir: Path =
    val p = Path.of(sys.props.getOrElse("user.dir", ".")).resolve("target/sql-js-node-test")
    Files.createDirectories(p)
    p

  private def copyResource(resourcePath: String, dest: Path): Unit =
    val cl = Thread.currentThread().getContextClassLoader
    val in = Option(cl.getResourceAsStream(resourcePath))
      .orElse(Option(getClass.getResourceAsStream("/" + resourcePath)))
      .getOrElse(throw new IllegalStateException(s"Resource not found: $resourcePath"))
    try Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING)
    finally in.close()

  private def materialise(): Unit =
    // Runtime source (from main resources of this module).
    copyResource("scalascript/sql/js/sql-runtime.mjs", workDir.resolve("sql-runtime.mjs"))
    // Test scaffolding (from test resources).
    copyResource("scalascript/sql/js/package.json",   workDir.resolve("package.json"))
    copyResource("scalascript/sql/js/sql-js.test.mjs", workDir.resolve("sql-js.test.mjs"))
    copyResource("scalascript/sql/js/duckdb.test.mjs", workDir.resolve("duckdb.test.mjs"))

  private def npmInstallIfNeeded(): Unit =
    val nodeModules = workDir.resolve("node_modules")
    val stamp       = workDir.resolve(".npm-install-stamp")
    val pkgJson     = workDir.resolve("package.json")
    val pkgMtime    = Files.getLastModifiedTime(pkgJson).toMillis
    val stampOk     = Files.exists(nodeModules) && Files.exists(stamp) &&
                      Files.getLastModifiedTime(stamp).toMillis >= pkgMtime
    if !stampOk then
      val proc = ProcessBuilder("npm", "install", "--no-audit", "--no-fund", "--silent")
        .directory(workDir.toFile)
        .redirectErrorStream(true)
        .start()
      val out  = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val code = proc.waitFor()
      assert(code == 0, s"npm install failed (exit $code):\n$out")
      Files.writeString(stamp, "ok")

  test("Node: sql.js + DuckDB-Wasm runtime end-to-end (16 cases)"):
    assume(hasNode, "node not available")
    assume(hasNpm,  "npm not available")
    materialise()
    npmInstallIfNeeded()
    val proc = ProcessBuilder("node", "--test", "--test-force-exit",
                              "sql-js.test.mjs", "duckdb.test.mjs")
      .directory(workDir.toFile)
      .redirectErrorStream(true)
      .start()
    val out  = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val code = proc.waitFor()
    if code != 0 then
      fail(s"node --test failed (exit $code).  Output:\n$out")
    // Sanity: assert we actually ran the expected number of tests.
    val passLine = out.linesIterator.find(_.contains("pass ")).getOrElse("")
    assert(passLine.contains("pass 16"), s"expected 16 passing tests, got: $passLine\n$out")
