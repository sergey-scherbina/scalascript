package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import upickle.default.read as upickleRead
import ujson.Value as JsonValue

/** v2.0 — end-to-end smoke tests for the JS incremental codegen cache.
 *
 *  Cover the three CLI surfaces added for `.scjs` cached-source artifacts:
 *
 *   1. `ssc compile-js <file.ssc>`     → produces `<file>.scjs` with
 *                                        SSCART magic, ABI 2.0, non-empty
 *                                        `jsSource`, 64-char SHA-256
 *                                        sourceHash.
 *   2. `ssc compile-js a.ssc`, `ssc compile-js b.ssc`,
 *      `ssc link --backend js <dir> -o out.js`  → combined JS file is
 *                                        non-empty and contains the
 *                                        expected per-module fragments
 *                                        (`add` from a, `mul` from b).
 *   3. `ssc build --incremental --backend js <dir>` twice in a row:
 *      the second run skips both modules (mtime unchanged on `.scjs`).
 *      Touching one source regenerates only that module's `.scjs`.
 *
 *  Tests spawn the actual `ssc` jar as a subprocess.  When the jar is
 *  missing the tests cancel with a diagnostic message (same pattern as
 *  `JvmIncrementalCliTest` / `V2ArtifactCliTest`).
 *
 *  Run with:  `sbt "cli/testOnly *JsIncremental*"`
 */
class JsIncrementalCliTest extends AnyFunSuite:

  // ── ssc jar discovery (mirrors JvmIncrementalCliTest) ────────────────────

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

  /** Invoke the ssc CLI in `cwd` with the given args. */
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
  private def aSsc(extra: String = ""): String =
    s"""---
       |name: a
       |---
       |
       |# Module A
       |
       |```scalascript
       |def add(x: Int, y: Int): Int = x + y
       |```
       |$extra
       |""".stripMargin

  /** Source for a module exporting `mul`. */
  private val bSsc: String =
    """---
      |name: b
      |---
      |
      |# Module B
      |
      |```scalascript
      |def mul(x: Int, y: Int): Int = x * y
      |```
      |""".stripMargin

  // ── 1. compile-js → .scjs ────────────────────────────────────────────────

  test("compile-js produces a valid .scjs with SSCART magic, ABI 2.0, non-empty jsSource"):
    val sandbox = os.temp.dir(prefix = "ssc-jsinc-")
    try
      val src = sandbox / "a.ssc"
      os.write(src, aSsc())

      val res = runSsc(sandbox, "compile-js", "a.ssc")
      assert(res.exitCode == 0,
        s"compile-js failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")

      val scjs = sandbox / "a.scjs"
      assert(os.exists(scjs), s"expected $scjs to be written; got dir: ${os.list(sandbox).mkString(", ")}")

      val json = upickleRead[JsonValue](os.read(scjs))
      assert(json("magic").str == "SSCART", s"magic mismatch: ${json("magic")}")
      assert(json("abiVersion").str == "2.0", s"abiVersion mismatch: ${json("abiVersion")}")

      val jsSource = json("jsSource").str
      assert(jsSource.nonEmpty, "expected non-empty jsSource in .scjs")
      // Sanity: JsGen emits `function add(...)` or `const add =` somewhere.
      assert(jsSource.contains("add"),
        s"expected `add` symbol in emitted jsSource; got first 200 chars:\n${jsSource.take(200)}...")

      val sourceHash = json("sourceHash").str
      assert(sourceHash.nonEmpty, "expected non-empty sourceHash")
      assert(sourceHash.length == 64,
        s"expected SHA-256 hex digest (64 chars), got ${sourceHash.length}: $sourceHash")
    finally os.remove.all(sandbox)

  // ── 2. compile-js a.ssc + b.ssc, then link --backend js -o out.js ────────

  test("link --backend js concatenates two .scjs artifacts into a non-empty JS file"):
    val sandbox = os.temp.dir(prefix = "ssc-jsinc-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      // Module A.
      val aSrc = sandbox / "a.ssc"
      os.write(aSrc, aSsc())
      assert(runSsc(sandbox, "emit-interface", "a.ssc", "-o", "artifacts/a.scim").exitCode == 0,
        "emit-interface for a failed")
      val ca = runSsc(sandbox, "compile-js", "a.ssc", "-o", "artifacts/a.scjs")
      assert(ca.exitCode == 0, s"compile-js a failed: ${ca.err.text()}")
      assert(os.exists(artDir / "a.scjs"))

      // Module B — exercise the --iface-dir path even though b's local
      // `mul` doesn't need a's interface.
      val bSrc = sandbox / "b.ssc"
      os.write(bSrc, bSsc)
      val cb = runSsc(sandbox, "compile-js", "b.ssc",
        "--iface-dir", "artifacts", "-o", "artifacts/b.scjs")
      assert(cb.exitCode == 0, s"compile-js b failed: ${cb.err.text()}")
      assert(os.exists(artDir / "b.scjs"))

      // Link the two .scjs into a single .js file (no `node` needed —
      // we just assert on the textual output so the test runs in any env).
      val outJs = sandbox / "out.js"
      val rl = runSsc(sandbox, "link", "--backend", "js", "artifacts", "-o", outJs.toString)
      assert(rl.exitCode == 0,
        s"link --backend js failed: exit=${rl.exitCode}\nstdout=${rl.out.text()}\nstderr=${rl.err.text()}")
      assert(os.exists(outJs), s"expected $outJs to be produced")
      assert(os.size(outJs) > 0, "expected non-empty JS file")
      val combined = os.read(outJs)
      // Each module's distinct fragment must appear in the combined output.
      // JsGen emits either `function add(...)` (top-level def) or `const add = (...) =>`
      // depending on scope; whatever the shape, the literal symbol `add` survives.
      assert(combined.contains("add"),
        s"expected `add` (from a) in combined JS; got first 300 chars:\n${combined.take(300)}")
      assert(combined.contains("mul"),
        s"expected `mul` (from b) in combined JS; got first 300 chars:\n${combined.take(300)}")
      // The runtime preamble must still be present (longest-common-prefix
      // should have lifted it once into the combined output).
      assert(combined.contains("scalascript JS runtime") || combined.contains("_println"),
        s"expected JS runtime preamble somewhere in combined output; got first 300 chars:\n${combined.take(300)}")
    finally os.remove.all(sandbox)

  // ── 3. build --incremental --backend js idempotency + per-file rebuild ───

  test("build --incremental --backend js is idempotent; touching a source regenerates only that .scjs"):
    val sandbox = os.temp.dir(prefix = "ssc-jsinc-")
    try
      val srcDir = sandbox / "src"
      os.makeDir.all(srcDir)
      val aSrc = srcDir / "a.ssc"
      val bSrc = srcDir / "b.ssc"
      os.write(aSrc, aSsc())
      os.write(bSrc, bSsc)

      val artDir = srcDir / ".ssc-artifacts"

      // First build — emits .scim + .scir + .scjs for both modules.
      val r1 = runSsc(sandbox, "build", "--incremental", "--backend", "js", "src")
      assert(r1.exitCode == 0,
        s"first build failed: ${r1.err.text()}")
      val aScjs = artDir / "a.scjs"
      val bScjs = artDir / "b.scjs"
      assert(os.exists(aScjs) && os.exists(bScjs),
        s"expected both .scjs files after first build; got: ${os.list(artDir).mkString(", ")}")

      val aMtime1 = os.mtime(aScjs)
      val bMtime1 = os.mtime(bScjs)

      // Filesystem mtime resolution varies; sleep long enough to detect a
      // rewrite that should not happen.
      Thread.sleep(1100)

      // Second build — no source changes.  Both .scjs mtimes should be
      // untouched and the output should announce skips.
      val r2 = runSsc(sandbox, "build", "--incremental", "--backend", "js", "src")
      assert(r2.exitCode == 0, s"second build failed: ${r2.err.text()}")
      val r2out = r2.out.text()
      assert(r2out.contains("[skip]"),
        s"expected second build to skip unchanged modules; output:\n$r2out")
      assert(os.mtime(aScjs) == aMtime1,
        s"a.scjs mtime changed despite no source change: $aMtime1 → ${os.mtime(aScjs)}")
      assert(os.mtime(bScjs) == bMtime1, "b.scjs mtime changed despite no source change")

      // Touch a's source so its SHA-256 changes; b should stay skipped.
      os.write.over(aSrc, aSsc(extra = "\n## New section\n\nMore prose.\n"))
      Thread.sleep(1100)

      val r3 = runSsc(sandbox, "build", "--incremental", "--backend", "js", "src")
      assert(r3.exitCode == 0, s"third build failed: ${r3.err.text()}")
      assert(os.mtime(aScjs) > aMtime1,
        s"a.scjs should have been regenerated; mtime $aMtime1 → ${os.mtime(aScjs)}")
      assert(os.mtime(bScjs) == bMtime1,
        s"b.scjs should NOT have been regenerated (b's source unchanged); mtime $bMtime1 → ${os.mtime(bScjs)}")
    finally os.remove.all(sandbox)
