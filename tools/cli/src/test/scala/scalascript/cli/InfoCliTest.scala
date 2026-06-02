package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import upickle.default.read as upickleRead
import ujson.Value as JsonValue

/** v2.0 — end-to-end smoke tests for `ssc info <artifact>`.
 *
 *  Covers the four supported artifact extensions plus the failure
 *  modes (missing file, unknown extension) and the `--json` flag.
 *
 *  Tests spawn the actual `ssc` jar as a subprocess (same pattern as
 *  `V2ArtifactCliTest` / `JvmIncrementalCliTest`).  When the jar is
 *  missing the tests cancel with a diagnostic.
 *
 *  Run with:  `sbt "cli/testOnly *Info*"`
 */
class InfoCliTest extends AnyFunSuite:

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
      cwd   = cwd,
      stdin  = "",
      check = false,
      stderr = os.Pipe,
      stdout = os.Pipe
    )

  /** Source for a module exporting `add`. */
  private val aSsc: String =
    """---
      |name: a
      |---
      |
      |# Module A
      |
      |```scalascript
      |def add(x: Int, y: Int): Int = x + y
      |```
      |""".stripMargin

  /** Generate all four artifact types for `a.ssc` into `sandbox`. */
  private def emitAllArtifacts(sandbox: os.Path): (os.Path, os.Path, os.Path, os.Path) =
    val src = sandbox / "a.ssc"
    os.write(src, aSsc)
    assert(runSsc(sandbox, "emit-interface", "a.ssc").exitCode == 0)
    assert(runSsc(sandbox, "emit-ir",        "a.ssc").exitCode == 0)
    assert(runSsc(sandbox, "compile-jvm",    "a.ssc").exitCode == 0)
    assert(runSsc(sandbox, "compile-js",     "a.ssc").exitCode == 0)
    (sandbox / "a.scim", sandbox / "a.scir", sandbox / "a.scjvm", sandbox / "a.scjs")

  // ── 1. info on .scim ──────────────────────────────────────────────────────

  test("info <.scim> — exits 0 and reports envelope plus at least one export"):
    val sandbox = os.temp.dir(prefix = "ssc-info-")
    try
      val (scim, _, _, _) = emitAllArtifacts(sandbox)
      val res = runSsc(sandbox, "info", scim.toString)
      assert(res.exitCode == 0,
        s"info failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      val out = res.out.text()
      assert(out.contains("magic: SSCART"),     s"missing 'magic: SSCART' in:\n$out")
      assert(out.contains("abiVersion: 2.0"),    s"missing 'abiVersion: 2.0' in:\n$out")
      assert(out.contains("format: .scim"),      s"missing format header in:\n$out")
      assert(out.contains("exports: "),          s"missing exports count in:\n$out")
      // At least one export — `add` is the only def in `aSsc`.
      assert(out.contains("add"),                s"expected 'add' in exports list:\n$out")
    finally os.remove.all(sandbox)

  // ── 2. info on .scjvm ─────────────────────────────────────────────────────

  test("info <.scjvm> — reports a non-zero scalaSourceBytes count"):
    val sandbox = os.temp.dir(prefix = "ssc-info-")
    try
      val (_, _, scjvm, _) = emitAllArtifacts(sandbox)
      val res = runSsc(sandbox, "info", scjvm.toString)
      assert(res.exitCode == 0,
        s"info failed: exit=${res.exitCode}\nstderr=${res.err.text()}")
      val out = res.out.text()
      assert(out.contains("format: .scjvm"), s"missing scjvm format header:\n$out")
      // Match a line like `scalaSourceBytes: 1234` with a positive number.
      val rx = """scalaSourceBytes:\s+(\d+)""".r
      val n  = rx.findFirstMatchIn(out).map(_.group(1).toInt).getOrElse(0)
      assert(n > 0, s"expected scalaSourceBytes > 0; got $n. Full output:\n$out")
    finally os.remove.all(sandbox)

  // ── 3. info on .scjs ──────────────────────────────────────────────────────

  test("info <.scjs> — reports a non-zero jsSourceBytes count"):
    val sandbox = os.temp.dir(prefix = "ssc-info-")
    try
      val (_, _, _, scjs) = emitAllArtifacts(sandbox)
      val res = runSsc(sandbox, "info", scjs.toString)
      assert(res.exitCode == 0,
        s"info failed: exit=${res.exitCode}\nstderr=${res.err.text()}")
      val out = res.out.text()
      assert(out.contains("format: .scjs"), s"missing scjs format header:\n$out")
      val rx = """jsSourceBytes:\s+(\d+)""".r
      val n  = rx.findFirstMatchIn(out).map(_.group(1).toInt).getOrElse(0)
      assert(n > 0, s"expected jsSourceBytes > 0; got $n. Full output:\n$out")
    finally os.remove.all(sandbox)

  // ── 4. info on .scir ──────────────────────────────────────────────────────

  test("info <.scir> — reports the section count"):
    val sandbox = os.temp.dir(prefix = "ssc-info-")
    try
      val (_, scir, _, _) = emitAllArtifacts(sandbox)
      val res = runSsc(sandbox, "info", scir.toString)
      assert(res.exitCode == 0,
        s"info failed: exit=${res.exitCode}\nstderr=${res.err.text()}")
      val out = res.out.text()
      assert(out.contains("format: .scir"), s"missing scir format header:\n$out")
      val rx = """sections:\s+(\d+)""".r
      val n  = rx.findFirstMatchIn(out).map(_.group(1).toInt).getOrElse(-1)
      assert(n >= 1, s"expected at least one section; got $n. Full output:\n$out")
    finally os.remove.all(sandbox)

  // ── 5. nonexistent file ───────────────────────────────────────────────────

  test("info nonexistent.scim — non-zero exit with a clear error"):
    val sandbox = os.temp.dir(prefix = "ssc-info-")
    try
      val res = runSsc(sandbox, "info", "does-not-exist.scim")
      assert(res.exitCode != 0,
        s"expected non-zero exit; got exit=${res.exitCode}\nstdout=${res.out.text()}")
      val combined = res.out.text() + res.err.text()
      assert(combined.toLowerCase.contains("not found") ||
             combined.toLowerCase.contains("no such file"),
        s"expected a 'file not found' error; got:\n$combined")
    finally os.remove.all(sandbox)

  // ── 6. unknown extension ──────────────────────────────────────────────────

  test("info file.txt — non-zero exit with a clear 'unsupported extension' error"):
    val sandbox = os.temp.dir(prefix = "ssc-info-")
    try
      val txt = sandbox / "file.txt"
      os.write(txt, "just some text")
      val res = runSsc(sandbox, "info", txt.toString)
      assert(res.exitCode != 0,
        s"expected non-zero exit for unsupported extension; got ${res.exitCode}")
      val combined = res.out.text() + res.err.text()
      assert(combined.toLowerCase.contains("unsupported") ||
             combined.toLowerCase.contains("expected"),
        s"expected an 'unsupported extension' diagnostic; got:\n$combined")
    finally os.remove.all(sandbox)

  // ── 7. --json mode produces parseable JSON with the full envelope ─────────

  test("info <.scim> --json — emits parseable JSON containing the envelope"):
    val sandbox = os.temp.dir(prefix = "ssc-info-")
    try
      val (scim, _, _, _) = emitAllArtifacts(sandbox)
      val res = runSsc(sandbox, "info", scim.toString, "--json")
      assert(res.exitCode == 0,
        s"info --json failed: exit=${res.exitCode}\nstderr=${res.err.text()}")
      val out = res.out.text().trim
      // Must parse as JSON.
      val json = upickleRead[JsonValue](out)
      // And must carry the envelope fields verbatim.
      assert(json("magic").str == "SSCART",   s"magic mismatch in JSON: ${json("magic")}")
      assert(json("abiVersion").str == "2.0", s"abiVersion mismatch: ${json("abiVersion")}")
      // exports is an array, including `add`.
      val exportNames = json("exports").arr.map(_("name").str).toList
      assert(exportNames.contains("add"),
        s"expected 'add' in JSON exports; got: ${exportNames.mkString(", ")}")
    finally os.remove.all(sandbox)
