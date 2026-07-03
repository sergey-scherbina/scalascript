package scalascript.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize

/** v1.27 Phase 7 — pin `conformance/sql-browser-basic.ssc` stdout
 *  against `conformance/expected/sql-browser-basic.txt` after
 *  compiling through NodeBackend and running under `node`.
 *
 *  Bypasses the cross-backend `conformance/run.sc` harness (which
 *  needs bin/ssc + scala-cli + node + a published runtime).  This
 *  in-process check covers the JS-family path end-to-end through
 *  the NodeBackend pipeline (a representative of the
 *  `backends: [js, node, wasm]` declaration), and runs under plain
 *  `sbt test` with only `node` + `npm` required externally.
 *  Skipped gracefully when those aren't on PATH.
 *
 *  Sources are read from the repo working tree, not inlined: the
 *  conformance file IS the contract surface, drift would silently
 *  break user expectations. */
class SqlBrowserConformanceCaptureTest extends AnyFunSuite:

  private val backend = NodeBackend()

  private def hasNode: Boolean =
    try ProcessBuilder("node", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def hasNpm: Boolean =
    try ProcessBuilder("npm", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  /** Repo-root resolver.  sbt forks tests with `user.dir` set to the
   *  subproject directory (`backend-node/`).  Walk up to find the
   *  conformance/ dir. */
  private lazy val repoRoot: Path =
    var p = Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath
    while p != null && !Files.exists(p.resolve("build.sbt")) do p = p.getParent
    require(p != null, "could not locate repo root (no build.sbt found)")
    p

  private def cacheDir(label: String): Path =
    val p = Path.of(sys.props.getOrElse("user.dir", ".")).resolve(s"target/sql-browser-conformance-test/$label")
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
    out.stripTrailing

  test("conformance/sql-browser-basic.ssc matches expected stdout under NodeBackend + node"):
    assume(hasNode, "node not available")
    assume(hasNpm,  "npm not available")
    val src      = Files.readString(repoRoot.resolve("tests/conformance/sql-browser-basic.ssc"))
    val expected = Files.readString(repoRoot.resolve("tests/conformance/expected/sql-browser-basic.txt")).stripTrailing
    val (code, sources) = compileToOutputs(src)
    val pkg = packageJson(sources)
    val got = runMjs(cacheDir("sql-browser-basic"), code, pkg)
    assert(got == expected,
      s"""conformance/sql-browser-basic.ssc stdout mismatch.
         |--- expected ---
         |$expected
         |--- got ---
         |$got
         |--- end ---""".stripMargin)
