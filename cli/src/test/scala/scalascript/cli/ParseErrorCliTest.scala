package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** v2.0 — surface scalameta parse errors with line/col/snippet from the CLI.
 *
 *  This test drives the assembled `ssc.jar` via `os.proc` (mirroring the
 *  pattern in `V2RealStdModulesTest`) and asserts that a `compile-jvm` run
 *  against a fixture `.ssc` with a deliberate syntax error inside its
 *  `scalascript` block:
 *
 *    1. exits non-zero,
 *    2. writes a `error: failed to parse scalascript block in <file>:<L>:<C>`
 *       header to stderr (or stdout),
 *    3. includes the offending line text in the diagnostic.
 *
 *  Before this milestone, the CLI emitted only `Failed to parse scalascript
 *  code block` — no position, no snippet.  This test pins the new format so
 *  regressions are caught.
 */
class ParseErrorCliTest extends AnyFunSuite:

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

  /** Fixture: a tiny `.ssc` with a single deliberately-malformed scalascript
   *  block.  The malformed `def g(` is on line 3 of the block body. */
  private def writeBadFixture(dir: os.Path, name: String = "bad.ssc"): os.Path =
    val src =
      """# Bad
        |
        |```scalascript
        |val a = 1
        |val b = 2
        |def g(: Int = 3
        |val c = 4
        |```
        |""".stripMargin
    val p = dir / name
    os.write(p, src)
    p

  test("compile-jvm on a .ssc with a syntax error → structured diagnostic"):
    val sandbox = os.temp.dir(prefix = "ssc-parse-err-")
    try
      val fixture = writeBadFixture(sandbox)
      val res     = runSsc(sandbox, "compile-jvm", fixture.last)
      val out     = res.out.text()
      val err     = res.err.text()
      val combined = out + err

      // 1. Non-zero exit.
      assert(res.exitCode != 0,
        s"expected non-zero exit on parse failure; got ${res.exitCode}\nstdout=$out\nstderr=$err")

      // 2. Structured header with file:line:col.  We don't pin the column
      //    (scalameta's exact pos may shift); we only check the shape.
      val headerRe = """error: failed to parse scalascript block in \S+:\d+:\d+""".r
      assert(headerRe.findFirstIn(combined).isDefined,
        s"expected structured 'error: failed to parse ... :line:col' header; got:\n$combined")

      // 3. Line:col must reference the malformed line.  The fixture puts
      //    the bad token on line 3 of the BLOCK body.
      assert(combined.contains(":3:"),
        s"expected ':3:' (line=3) reference in diagnostic; got:\n$combined")

      // 4. Offending line text appears in the diagnostic (snippet).
      assert(combined.contains("def g(: Int = 3"),
        s"expected the offending line text 'def g(: Int = 3' in the snippet; got:\n$combined")

      // 5. Caret marker (`^` line) appears too.
      assert(combined.linesIterator.exists(_.trim == "^"),
        s"expected a `^` caret line in the snippet; got:\n$combined")

      // 6. No stale .scjvm should be written.
      assert(!os.exists(sandbox / "bad.scjvm"),
        s"bad.scjvm should not exist after parse failure; sandbox=$sandbox")
    finally os.remove.all(sandbox)

  test("emit-interface on bad .ssc → structured diagnostic"):
    // Same fixture, different CLI surface.  Confirms the diagnostic is
    // wired through every entry point that loads a `.ssc`.
    val sandbox = os.temp.dir(prefix = "ssc-parse-err-iface-")
    try
      val fixture = writeBadFixture(sandbox)
      val res     = runSsc(sandbox, "emit-interface", fixture.last)
      val combined = res.out.text() + res.err.text()
      assert(res.exitCode != 0,
        s"expected non-zero exit; got ${res.exitCode}\n$combined")
      val headerRe = """error: failed to parse scalascript block in \S+:\d+:\d+""".r
      assert(headerRe.findFirstIn(combined).isDefined,
        s"emit-interface must emit the structured header; got:\n$combined")
      assert(combined.contains("def g(: Int = 3"),
        s"emit-interface must include the offending line; got:\n$combined")
    finally os.remove.all(sandbox)

  test("emit-ir on bad .ssc → structured diagnostic"):
    val sandbox = os.temp.dir(prefix = "ssc-parse-err-ir-")
    try
      val fixture = writeBadFixture(sandbox)
      val res     = runSsc(sandbox, "emit-ir", fixture.last)
      val combined = res.out.text() + res.err.text()
      assert(res.exitCode != 0,
        s"expected non-zero exit; got ${res.exitCode}\n$combined")
      val headerRe = """error: failed to parse scalascript block in \S+:\d+:\d+""".r
      assert(headerRe.findFirstIn(combined).isDefined,
        s"emit-ir must emit the structured header; got:\n$combined")
    finally os.remove.all(sandbox)
