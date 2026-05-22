package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** v2.0 — `ssc build --incremental` unified diagnostic output.
 *
 *  Battle-test gap: when one module in a multi-module build fails, the
 *  per-module summary (`[compile] foo.ssc ... FAIL`) lands on **stdout**
 *  but the root cause (YAML diagnostic, parse error position, etc.)
 *  lands on **stderr**.  CI scripts that pipe only stdout to a log see
 *  the failure marker but not why.
 *
 *  Decision: progress + per-module results + per-module failure causes
 *  all go to stdout (cargo-build / npm-run-build style).  Only truly
 *  catastrophic errors (missing src-dir, cycle detection) stay on
 *  stderr.  See the task brief for rationale.
 *
 *  This suite pins the unified behaviour.  Run with:
 *      sbt "cli/testOnly *BuildUnifiedDiag*"
 */
class BuildUnifiedDiagOutputTest extends AnyFunSuite:

  // ── ssc jar discovery (mirrors V2ArtifactCliTest) ────────────────────────

  private val sscJar: Option[os.Path] =
    val cwd = os.pwd
    def jarUnder(root: os.Path): os.Path =
      root / "cli" / "target" / "scala-3.8.3" / "ssc.jar"
    def findCanonicalRepo(p: os.Path): Option[os.Path] =
      val parts = p.segments.toList
      val idx = parts.lastIndexOf(".claude")
      if idx >= 0 && idx + 1 < parts.length && parts(idx + 1) == "worktrees" then
        Some(os.Path("/" + parts.take(idx).mkString("/")))
      else None
    val candidates = List(
      jarUnder(cwd),
      jarUnder(cwd / os.up)
    ) ++ findCanonicalRepo(cwd).map(jarUnder).toList
    candidates.find(os.exists)

  private def requireJar(): os.Path = sscJar.getOrElse:
    cancel("ssc.jar not found — run `sbt cli/assembly` first")

  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    val jar = requireJar()
    val cmd: Seq[os.Shellable] = Seq[os.Shellable]("java", "-jar", jar.toString) ++
      args.map(a => a: os.Shellable)
    os.proc(cmd).call(
      cwd    = cwd,
      check  = false,
      stderr = os.Pipe,
      stdout = os.Pipe
    )

  // ── Fixture: one good module + one bad (unquoted colon in YAML) ──────────

  private val goodSsc =
    """---
      |name: good
      |---
      |
      |# Good Module
      |
      |```scalascript
      |def add(a: Int, b: Int): Int = a + b
      |```
      |""".stripMargin

  /** A module whose YAML front-matter has an unquoted colon in the
   *  `description:` value — SnakeYAML rejects this with
   *  "mapping values are not allowed here". */
  private val badYamlSsc =
    """---
      |name: bad
      |description: v1.20.1 std/parsing — parser error recovery: skip-to-sync-token
      |---
      |
      |# Bad Module
      |
      |```scalascript
      |def noop(): Unit = ()
      |```
      |""".stripMargin

  // ── 1. unified output: failure cause lands on stdout under FAIL line ─────

  test("build --incremental — YAML failure cause appears on STDOUT under FAIL line"):
    val sandbox = os.temp.dir(prefix = "ssc-unified-diag-")
    try
      val srcDir = sandbox / "src"
      os.makeDir.all(srcDir)
      os.write(srcDir / "good.ssc", goodSsc)
      os.write(srcDir / "bad.ssc",  badYamlSsc)

      val res    = runSsc(sandbox, "build", "--incremental", "--backend", "jvm", "src")
      val stdout = res.out.text()
      val stderr = res.err.text()

      // (a) build fails with non-zero exit
      assert(res.exitCode != 0,
        s"expected non-zero exit on YAML failure; got ${res.exitCode}\n" +
        s"stdout=$stdout\nstderr=$stderr")

      // (b) the FAIL marker for bad.ssc is on stdout
      val failLineRe = """\[compile\]\s+bad\.ssc\s+\.\.\.\s+FAIL""".r
      assert(failLineRe.findFirstIn(stdout).isDefined,
        s"expected '[compile] bad.ssc ... FAIL' on stdout; stdout was:\n$stdout")

      // (c) the YAML cause is ALSO on stdout (unified output)
      assert(stdout.contains("YAML front-matter"),
        s"expected YAML front-matter diagnostic on stdout; stdout was:\n$stdout")
      assert(stdout.contains("mapping values are not allowed here"),
        s"expected SnakeYAML 'mapping values are not allowed here' on stdout; " +
        s"stdout was:\n$stdout")

      // (d) the cause is indented under the FAIL line.  The first line
      // of the captured cause is the YAML header; assert it appears
      // with at least 2 leading spaces of extra indent (i.e. more
      // indented than the `[compile]` line which already has 2).
      val causeLine = stdout.linesIterator
        .find(_.contains("YAML front-matter"))
        .getOrElse(fail(s"no YAML line found in stdout:\n$stdout"))
      assert(causeLine.startsWith("    "),
        s"expected cause line to be indented under FAIL; got: '$causeLine'")

      // (e) stderr must NOT contain the per-module YAML detail (it was
      // moved to stdout).  stderr is allowed to be empty or contain
      // only catastrophic-error markers — never the per-module cause.
      assert(!stderr.contains("YAML front-matter"),
        s"stderr should not carry the per-module YAML detail anymore; " +
        s"stderr was:\n$stderr")
      assert(!stderr.contains("mapping values are not allowed here"),
        s"stderr should not carry the per-module YAML detail anymore; " +
        s"stderr was:\n$stderr")
    finally os.remove.all(sandbox)

  // ── 2. good module in same build still succeeds, summary on stdout ──────

  test("build --incremental — good module still compiles even when sibling fails"):
    val sandbox = os.temp.dir(prefix = "ssc-unified-diag-")
    try
      val srcDir = sandbox / "src"
      os.makeDir.all(srcDir)
      os.write(srcDir / "good.ssc", goodSsc)
      os.write(srcDir / "bad.ssc",  badYamlSsc)

      val res    = runSsc(sandbox, "build", "--incremental", "--backend", "jvm", "src")
      val stdout = res.out.text()

      // Final summary line is on stdout and reports 1 failure.
      assert(stdout.contains("Done:"),
        s"expected final 'Done:' summary on stdout; got:\n$stdout")
      assert("""\b1\s+failed""".r.findFirstIn(stdout).isDefined,
        s"expected '1 failed' in summary; got:\n$stdout")
      // good.ssc compiled successfully.
      val artDir = srcDir / ".ssc-artifacts"
      assert(os.exists(artDir / "good.scjvm"),
        s"expected good.scjvm to be written even when bad.ssc failed; " +
        s"got dir contents: " +
        (if os.exists(artDir) then os.list(artDir).map(_.last).mkString(", ") else "(missing)"))
    finally os.remove.all(sandbox)
