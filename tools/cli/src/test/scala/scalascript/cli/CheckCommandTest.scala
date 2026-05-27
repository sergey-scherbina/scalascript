package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Integration tests for `ssc check` — the standalone type-checker command.
 *
 *  Tests cover:
 *    - exit codes: 0 (clean), 1 (type error), 2 (parse error), 3 (file not found)
 *    - human-readable diagnostics on stderr with filename + line/col
 *    - `--json` mode: valid JSON, correct error fields
 *    - `--quiet` mode: no output, correct exit code
 *    - directory mode: recursively finds *.ssc, aggregates results
 *    - multiple files: only error files reported
 *    - `elapsed_ms` in JSON: non-negative integer
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

  /** A minimal well-typed `.ssc` file. */
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

  /** A `.ssc` file with a type error: Int assigned to a String-typed binding. */
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

  /** A `.ssc` file with a malformed code block (will not parse). */
  private def writeMalformedFixture(dir: os.Path, name: String = "broken.ssc"): os.Path =
    val src =
      """# Broken
        |
        |```scalascript
        |val x = {{{
        |```
        |""".stripMargin
    val p = dir / name
    os.write(p, src)
    p

  // ── Tests ─────────────────────────────────────────────────────────────────

  // ── 1. Clean file: exit 0, contains "OK" ─────────────────────────────────
  test("clean file: exit 0 and prints 'OK' to stdout"):
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

  // ── 2. Type error: exit 1 ─────────────────────────────────────────────────
  test("type error file: exit 1"):
    val sandbox = os.temp.dir(prefix = "ssc-check-bad-")
    try
      val fixture = writeBadFixture(sandbox)
      val res     = runSsc(sandbox, "check", fixture.last)
      assert(res.exitCode == 1,
        s"expected exit 1 on type error; got ${res.exitCode}")
    finally os.remove.all(sandbox)

  // ── 3. Errors go to stderr, not stdout ───────────────────────────────────
  test("type errors written to stderr, not stdout"):
    val sandbox = os.temp.dir(prefix = "ssc-check-stderr-")
    try
      val fixture = writeBadFixture(sandbox)
      val res     = runSsc(sandbox, "check", fixture.last)
      val stderr  = res.err.text()
      val stdout  = res.out.text()
      assert(stderr.contains("error"),
        s"expected 'error' in stderr; got:\nstdout=$stdout\nstderr=$stderr")
      assert(!stdout.contains("error"),
        s"error details must NOT appear on stdout; got:\nstdout=$stdout\nstderr=$stderr")
    finally os.remove.all(sandbox)

  // ── 4. Diagnostic includes filename ──────────────────────────────────────
  test("type error: diagnostic includes filename"):
    val sandbox = os.temp.dir(prefix = "ssc-check-fname-")
    try
      val fixture = writeBadFixture(sandbox)
      val res     = runSsc(sandbox, "check", fixture.last)
      val err     = res.err.text()
      assert(err.contains("bad.ssc"),
        s"expected filename 'bad.ssc' in stderr; got:\n$err")
    finally os.remove.all(sandbox)

  // ── 5. No args: usage message + exit 1 ───────────────────────────────────
  test("no args: usage message to stderr and exit 1"):
    val sandbox = os.temp.dir(prefix = "ssc-check-noargs-")
    try
      val res = runSsc(sandbox, "check")
      val err = res.err.text()
      assert(res.exitCode == 1,
        s"expected exit 1 with no args; got ${res.exitCode}")
      assert(err.toLowerCase.contains("usage"),
        s"expected usage message in stderr; got:\n$err")
    finally os.remove.all(sandbox)

  // ── 6. Multiple clean files: exit 0, both names in stdout ────────────────
  test("multiple clean files: exit 0, both filenames in stdout"):
    val sandbox = os.temp.dir(prefix = "ssc-check-multi-ok-")
    try
      val f1  = writeGoodFixture(sandbox, "a.ssc")
      val f2  = writeGoodFixture(sandbox, "b.ssc")
      val res = runSsc(sandbox, "check", f1.last, f2.last)
      val out = res.out.text()
      val err = res.err.text()
      assert(res.exitCode == 0,
        s"expected exit 0; got ${res.exitCode}\nstdout=$out\nstderr=$err")
      assert(out.contains("a.ssc") && out.contains("b.ssc"),
        s"expected both filenames in stdout; got:\n$out")
    finally os.remove.all(sandbox)

  // ── 7. Mixed files: exit 1, good file OK, bad file errors ────────────────
  test("mixed files: exit 1, good file reports OK, bad file reported in stderr"):
    val sandbox = os.temp.dir(prefix = "ssc-check-multi-bad-")
    try
      val good = writeGoodFixture(sandbox, "good.ssc")
      val bad  = writeBadFixture(sandbox, "bad.ssc")
      val res  = runSsc(sandbox, "check", good.last, bad.last)
      val out  = res.out.text()
      val err  = res.err.text()
      assert(res.exitCode == 1,
        s"expected exit 1; got ${res.exitCode}\nstdout=$out\nstderr=$err")
      assert(out.contains("good.ssc") && out.contains("OK"),
        s"expected good.ssc: OK in stdout; got:\n$out")
      assert(err.contains("bad.ssc"),
        s"expected bad.ssc error in stderr; got:\n$err")
    finally os.remove.all(sandbox)

  // ── 8. File not found: exit 3 ────────────────────────────────────────────
  test("file not found: exit 3"):
    val sandbox = os.temp.dir(prefix = "ssc-check-notfound-")
    try
      val res = runSsc(sandbox, "check", "nonexistent.ssc")
      assert(res.exitCode == 3,
        s"expected exit 3 for missing file; got ${res.exitCode}")
    finally os.remove.all(sandbox)

  // ── 9. --json clean file: valid JSON with empty errors array ─────────────
  test("--json clean file: valid JSON, errors empty"):
    val sandbox = os.temp.dir(prefix = "ssc-check-json-ok-")
    try
      val fixture = writeGoodFixture(sandbox)
      val res     = runSsc(sandbox, "check", "--json", fixture.last)
      val out     = res.out.text().trim
      val err     = res.err.text()
      assert(res.exitCode == 0,
        s"expected exit 0; got ${res.exitCode}\nout=$out\nerr=$err")
      assert(out.contains("\"errors\":[]"),
        s"""expected "errors":[] in JSON; got:\n$out""")
      assert(out.contains("\"file\""),
        s"""expected "file" key in JSON; got:\n$out""")
    finally os.remove.all(sandbox)

  // ── 10. --json type error: JSON contains error fields ─────────────────────
  test("--json type error: JSON contains line, col, severity, message"):
    val sandbox = os.temp.dir(prefix = "ssc-check-json-bad-")
    try
      val fixture = writeBadFixture(sandbox)
      val res     = runSsc(sandbox, "check", "--json", fixture.last)
      val out     = res.out.text().trim
      assert(res.exitCode == 1,
        s"expected exit 1; got ${res.exitCode}")
      assert(out.contains("\"severity\":\"error\""),
        s"""expected "severity":"error" in JSON; got:\n$out""")
      assert(out.contains("\"line\""),
        s"""expected "line" key in JSON; got:\n$out""")
      assert(out.contains("\"col\""),
        s"""expected "col" key in JSON; got:\n$out""")
      assert(out.contains("\"message\""),
        s"""expected "message" key in JSON; got:\n$out""")
    finally os.remove.all(sandbox)

  // ── 11. --json elapsed_ms is present and non-negative ─────────────────────
  test("--json elapsed_ms is present and non-negative"):
    val sandbox = os.temp.dir(prefix = "ssc-check-json-elapsed-")
    try
      val fixture = writeGoodFixture(sandbox)
      val res     = runSsc(sandbox, "check", "--json", fixture.last)
      val out     = res.out.text().trim
      val pat = """"elapsed_ms":(\d+)""".r
      val matched = pat.findFirstMatchIn(out)
      assert(matched.isDefined,
        s"""expected "elapsed_ms":<number> in JSON; got:\n$out""")
      val ms = matched.get.group(1).toLong
      assert(ms >= 0, s"elapsed_ms must be non-negative; got $ms")
    finally os.remove.all(sandbox)

  // ── 12. --quiet type error: exit 1, no stdout, no stderr ──────────────────
  test("--quiet with type errors: exit 1, no stdout or stderr"):
    val sandbox = os.temp.dir(prefix = "ssc-check-quiet-bad-")
    try
      val fixture = writeBadFixture(sandbox)
      val res     = runSsc(sandbox, "check", "--quiet", fixture.last)
      val out     = res.out.text()
      val err     = res.err.text()
      assert(res.exitCode == 1,
        s"expected exit 1 with --quiet on type error; got ${res.exitCode}")
      assert(out.isEmpty,
        s"expected no stdout with --quiet; got:\n$out")
      assert(err.isEmpty,
        s"expected no stderr with --quiet; got:\n$err")
    finally os.remove.all(sandbox)

  // ── 13. --quiet clean file: exit 0, no output ─────────────────────────────
  test("--quiet clean file: exit 0, no output"):
    val sandbox = os.temp.dir(prefix = "ssc-check-quiet-ok-")
    try
      val fixture = writeGoodFixture(sandbox)
      val res     = runSsc(sandbox, "check", "--quiet", fixture.last)
      val out     = res.out.text()
      val err     = res.err.text()
      assert(res.exitCode == 0,
        s"expected exit 0 with --quiet on clean file; got ${res.exitCode}")
      assert(out.isEmpty,
        s"expected no stdout with --quiet; got:\n$out")
      assert(err.isEmpty,
        s"expected no stderr with --quiet; got:\n$err")
    finally os.remove.all(sandbox)

  // ── 14. Directory mode: finds all .ssc files recursively ──────────────────
  test("directory mode: recursively checks all .ssc files, all clean"):
    val sandbox = os.temp.dir(prefix = "ssc-check-dir-ok-")
    try
      val sub = sandbox / "sub"
      os.makeDir(sub)
      writeGoodFixture(sandbox, "root.ssc")
      writeGoodFixture(sub, "nested.ssc")
      val res = runSsc(sandbox, "check", sandbox.toString)
      val out = res.out.text()
      val err = res.err.text()
      assert(res.exitCode == 0,
        s"expected exit 0 for dir with clean files; got ${res.exitCode}\nout=$out\nerr=$err")
      assert(out.contains("OK"),
        s"expected OK output for dir check; got:\n$out")
    finally os.remove.all(sandbox)

  // ── 15. Directory with one error file: exit 1 ─────────────────────────────
  test("directory with one error file: exit 1, error file in stderr"):
    val sandbox = os.temp.dir(prefix = "ssc-check-dir-bad-")
    try
      writeGoodFixture(sandbox, "clean.ssc")
      writeBadFixture(sandbox, "dirty.ssc")
      val res = runSsc(sandbox, "check", sandbox.toString)
      val out = res.out.text()
      val err = res.err.text()
      assert(res.exitCode == 1,
        s"expected exit 1; got ${res.exitCode}\nout=$out\nerr=$err")
      assert(err.contains("dirty.ssc"),
        s"expected dirty.ssc in stderr; got:\n$err")
    finally os.remove.all(sandbox)

  // ── 16. --json directory: JSON array with per-file results ────────────────
  test("--json directory mode: emits JSON array with per-file results"):
    val sandbox = os.temp.dir(prefix = "ssc-check-json-dir-")
    try
      writeGoodFixture(sandbox, "f1.ssc")
      writeBadFixture(sandbox, "f2.ssc")
      val res = runSsc(sandbox, "check", "--json", sandbox.toString)
      val out = res.out.text().trim
      assert(out.startsWith("["),
        s"expected JSON array; got:\n$out")
      assert(out.contains("f1.ssc") && out.contains("f2.ssc"),
        s"expected both files in JSON array; got:\n$out")
    finally os.remove.all(sandbox)

  // ── 17. Parse/malformed .ssc: non-zero exit ────────────────────────────────
  test("malformed .ssc: non-zero exit code"):
    val sandbox = os.temp.dir(prefix = "ssc-check-parse-err-")
    try
      val fixture = writeMalformedFixture(sandbox)
      val res     = runSsc(sandbox, "check", fixture.last)
      assert(res.exitCode != 0,
        s"expected non-zero exit on parse error; got ${res.exitCode}")
    finally os.remove.all(sandbox)

  // ── 18. --quiet --json: no stderr, exit code reflects errors ──────────────
  test("--quiet --json: no stderr, only exit code"):
    val sandbox = os.temp.dir(prefix = "ssc-check-quiet-json-")
    try
      val fixture = writeBadFixture(sandbox)
      val res     = runSsc(sandbox, "check", "--quiet", "--json", fixture.last)
      val err     = res.err.text()
      assert(res.exitCode == 1,
        s"expected exit 1; got ${res.exitCode}")
      assert(err.isEmpty,
        s"expected no stderr in --quiet mode; got:\n$err")
    finally os.remove.all(sandbox)
