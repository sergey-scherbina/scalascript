package scalascript.codegen

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.*
import scalascript.parser.Parser
import scalascript.transform.Normalize

/** Dedicated conformance runner for the `node` backend target.
 *
 *  Addresses the MILESTONES.md v1.25 follow-up: "Dedicated conformance
 *  fixtures for the `node` target — requires backend-specific golden
 *  outputs."  Rather than extending the cross-backend `conformance/run.sc`
 *  harness (which pairs one expected file per fixture across *all*
 *  backends), this in-process runner gates every fixture on the `node`
 *  backend exclusively and compares against `conformance/expected/node-*.txt`.
 *
 *  Skipped gracefully when `node` is not on PATH. */
class NodeConformanceCaptureTest extends AnyFunSuite:

  private val backend = NodeBackend()

  private def hasNode: Boolean =
    try ProcessBuilder("node", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private lazy val repoRoot: Path =
    var p = Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath
    while p != null && !Files.exists(p.resolve("build.sbt")) do p = p.getParent
    require(p != null, "could not locate repo root (no build.sbt found)")
    p

  private def workDir(label: String): Path =
    val p = Path.of(sys.props.getOrElse("user.dir", ".")).resolve(s"target/node-conformance-test/$label")
    Files.createDirectories(p)
    p

  private def compileToOutputs(src: String): (String, List[SourceArtifact]) =
    val ir = Normalize(Parser.parse(src))
    backend.compile(ir, BackendOptions()) match
      case CompileResult.TextOutput(code, "javascript", sources) => (code, sources)
      case other => fail(s"expected TextOutput(javascript, …), got: $other")

  private def runNode(dir: Path, code: String, sources: List[SourceArtifact]): String =
    val codeWithExit =
      code + "\nsetTimeout(() => { if (typeof process !== 'undefined') process.exit(0); }, 5000);\n"
    Files.writeString(dir.resolve("main.cjs"), codeWithExit, StandardCharsets.UTF_8)
    sources.collectFirst { case SourceArtifact("package.json", c) => c }.foreach { pkgJson =>
      Files.writeString(dir.resolve("package.json"), pkgJson, StandardCharsets.UTF_8)
      val stamp    = dir.resolve(".npm-install-stamp")
      val pkgMtime = Files.getLastModifiedTime(dir.resolve("package.json")).toMillis
      val installed = Files.exists(dir.resolve("node_modules")) &&
                      Files.exists(stamp) &&
                      Files.getLastModifiedTime(stamp).toMillis >= pkgMtime
      if !installed then
        val inst = ProcessBuilder("npm", "install", "--no-audit", "--no-fund", "--silent")
          .directory(dir.toFile).redirectErrorStream(true).start()
        val instOut  = new String(inst.getInputStream.readAllBytes(), "UTF-8")
        val instCode = inst.waitFor()
        assert(instCode == 0, s"npm install failed (exit $instCode):\n$instOut")
        Files.writeString(stamp, "ok")
    }
    val run = ProcessBuilder("node", "main.cjs")
      .directory(dir.toFile).redirectErrorStream(true).start()
    val out      = new String(run.getInputStream.readAllBytes(), "UTF-8")
    val exitCode = run.waitFor()
    if exitCode != 0 then fail(s"node main.cjs failed (exit $exitCode).  Output:\n$out")
    out.stripTrailing

  private def runConformance(name: String): Unit =
    assume(hasNode, "node not available")
    val src      = Files.readString(repoRoot.resolve(s"tests/conformance/$name.ssc"))
    val expected = Files.readString(repoRoot.resolve(s"tests/conformance/expected/$name.txt")).stripTrailing
    val (code, sources) = compileToOutputs(src)
    val got = runNode(workDir(name), code, sources)
    assert(got == expected,
      s"""conformance/$name.ssc stdout mismatch.
         |--- expected ---
         |$expected
         |--- got ---
         |$got
         |--- end ---""".stripMargin)

  test("conformance/node-basic.ssc matches expected stdout"):
    runConformance("node-basic")
