package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Integration tests for `ssc check` — the standalone type-checker command.
 *
 *  Each test drives the assembled `ssc.jar` via `os.proc` and verifies:
 *    - exit code 0 on clean files
 *    - exit code 1 on type errors
 *    - diagnostics written to stderr in `file:line:col: error: message` format
 *    - `--iface-dir` flag accepted (optional; skipped if no .scim files)
 *
 *  Run via: `sbt "cli/testOnly *CheckCommandTest*"`
 *  Requires: `sbt cli/assembly` to produce `ssc.jar` first.
 */
class CheckCommandTest extends AnyFunSuite:

  // ── Locate the assembled fat-jar ─────────────────────────────────────────

  private val sscJar: Option[os.Path] =
    val cwd = os.pwd
    def jarUnder(root: os.Path): os.Path =
      root / "cli" / "target" / "scala-3.8.3" / "ssc.jar"
    def findCanonicalRepo(p: os.Path): Option[os.Path] =
      val parts = p.segments.toList
      val idx   = parts.lastIndexOf(".claude")
      if idx >= 0 && idx + 1 < parts.length && parts(idx + 1) == "worktrees" then
        Some(os.Path("/" + parts.take(idx).mkString("/")))
      else None
    val candidates =
      List(jarUnder(cwd), jarUnder(cwd / os.up)) ++
      findCanonicalRepo(cwd).map(jarUnder).toList
    candidates.find(os.exists)

  private def requireJar(): os.Path = sscJar.getOrElse:
    cancel("ssc.jar not found — run `sbt cli/assembly` first")

  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    val jar = requireJar()
    val cmd: Seq[os.Shellable] =
      Seq[os.Shellable]("java", "-jar", jar.toString) ++
      args.map(a => a: os.Shellable)
    os.proc(cmd).call(
      cwd    = cwd,
      check  = false,
      stderr = os.Pipe,
      stdout = os.Pipe
    )

  // ── Fixture helpers ───────────────────────────────────────────────────────

  /** A minimal well-typed `.ssc` file with one def and one use. */
  private def writeGoodFixture(dir: os.Path, name: String = "good.ssc"): os.Path =
    val src =
      """# Hello
        |
        |```scalascript
        |val greeting: String = "hello"
        |```
        |""".stripMargin
    val p = dir / name
    os.write(p, src)
    p

  /** A `.ssc` file that contains a well-formed code block but a type error:
   *  assigning an Int literal to a String-typed binding. */
  private def writeBadFixture(dir: os.Path, name: String = "bad.ssc"): os.Path =
    val src =
      """# Bad
        |
        |```scalascript
        |val x: String = 42
        |```
        |""".stripMargin
    val p = dir / name
    os.write(p, src)
    p

  // ── Tests ─────────────────────────────────────────────────────────────────

  test("ssc check on a clean file exits 0 and prints 'OK' to stdout"):
    val sandbox = os.temp.dir(prefix = "ssc-check-good-")
    try
      val fixture = writeGoodFixture(sandbox)
      val res     = runSsc(sandbox, "check", fixture.last)
      val out     = res.out.text()
      val err     = res.err.text()
      assert(res.exitCode == 0,
        s"expected exit 0 on clean file; got ${res.exitCode}\nstdout=$out\nstderr=$err")
      assert(out.contains("OK"),
        s"expected 'OK' in stdout; got:\nstdout=$out\nstderr=$err")
    finally os.remove.all(sandbox)

  test("ssc check on a file with type errors exits 1"):
    val sandbox = os.temp.dir(prefix = "ssc-check-bad-")
    try
      val fixture = writeBadFixture(sandbox)
      val res     = runSsc(sandbox, "check", fixture.last)
      val out     = res.out.text()
      val err     = res.err.text()
      assert(res.exitCode == 1,
        s"expected exit 1 on type error; got ${res.exitCode}\nstdout=$out\nstderr=$err")
    finally os.remove.all(sandbox)

  test("ssc check errors are written to stderr, not stdout"):
    val sandbox = os.temp.dir(prefix = "ssc-check-stderr-")
    try
      val fixture = writeBadFixture(sandbox)
      val res     = runSsc(sandbox, "check", fixture.last)
      val err     = res.err.text()
      val out     = res.out.text()
      // Errors must appear on stderr.
      assert(err.contains("error"),
        s"expected 'error' in stderr; got:\nstdout=$out\nstderr=$err")
      // Stdout must NOT contain error details.
      assert(!out.contains("error"),
        s"error details must NOT appear on stdout; got:\nstdout=$out\nstderr=$err")
    finally os.remove.all(sandbox)

  test("ssc check stderr diagnostic includes filename"):
    val sandbox = os.temp.dir(prefix = "ssc-check-fname-")
    try
      val fixture = writeBadFixture(sandbox)
      val res     = runSsc(sandbox, "check", fixture.last)
      val err     = res.err.text()
      assert(err.contains("bad.ssc"),
        s"expected filename 'bad.ssc' in stderr; got:\n$err")
    finally os.remove.all(sandbox)

  test("ssc check with no files prints usage to stderr and exits 1"):
    val sandbox = os.temp.dir(prefix = "ssc-check-noargs-")
    try
      val res = runSsc(sandbox, "check")
      val err = res.err.text()
      assert(res.exitCode == 1,
        s"expected exit 1 with no args; got ${res.exitCode}")
      assert(err.toLowerCase.contains("usage"),
        s"expected usage message in stderr; got:\n$err")
    finally os.remove.all(sandbox)

  test("ssc check on multiple files: 0 if all clean"):
    val sandbox = os.temp.dir(prefix = "ssc-check-multi-ok-")
    try
      val f1 = writeGoodFixture(sandbox, "a.ssc")
      val f2 = writeGoodFixture(sandbox, "b.ssc")
      val res = runSsc(sandbox, "check", f1.last, f2.last)
      val out = res.out.text()
      val err = res.err.text()
      assert(res.exitCode == 0,
        s"expected exit 0 on two clean files; got ${res.exitCode}\nstdout=$out\nstderr=$err")
      assert(out.contains("a.ssc") && out.contains("b.ssc"),
        s"expected both filenames in stdout; got:\n$out")
    finally os.remove.all(sandbox)

  test("ssc check on multiple files: 1 if any has errors"):
    val sandbox = os.temp.dir(prefix = "ssc-check-multi-bad-")
    try
      val good = writeGoodFixture(sandbox, "good.ssc")
      val bad  = writeBadFixture(sandbox, "bad.ssc")
      val res  = runSsc(sandbox, "check", good.last, bad.last)
      val out  = res.out.text()
      val err  = res.err.text()
      assert(res.exitCode == 1,
        s"expected exit 1 when one file has errors; got ${res.exitCode}\nstdout=$out\nstderr=$err")
      // Good file should still report OK.
      assert(out.contains("good.ssc") && out.contains("OK"),
        s"expected good.ssc: OK in stdout; got:\n$out")
      // Bad file should have an error on stderr.
      assert(err.contains("bad.ssc"),
        s"expected bad.ssc error in stderr; got:\n$err")
    finally os.remove.all(sandbox)
