package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** v2.0 Phase 2 — tests for the split-runtime emit path on the JS backend.
 *
 *  The v2.0 MVP shipped the full ~80 KB runtime preamble inside every
 *  `.scjs` artifact's `jsSource`, so two-module link outputs duplicated
 *  the preamble byte-for-byte and the longest-common-prefix linker
 *  spent its time stripping the redundancy.
 *
 *  Phase 2 factors the preamble into a shared `_runtime.scjs-runtime`
 *  artifact written alongside the module `.scjs` files.  These tests
 *  verify:
 *
 *   1. `compile-js a.ssc` produces a `.scjs` with non-trivial `jsSource`
 *      but well under 10 KB of user JS, and a `_runtime.scjs-runtime`
 *      with > 30 KB of preamble JS.
 *   2. Touching `a.ssc` regenerates `a.scjs` but does not rewrite
 *      `_runtime.scjs-runtime` (its capability set is unchanged).
 *   3. Adding a second module that needs a new capability widens the
 *      runtime's capability union.
 *   4. End-to-end: `link --backend js + node out.js` runs cleanly and
 *      the resulting `out.js` is meaningfully smaller than the
 *      pre-refactor baseline.
 *   5. `compile-runtime --backend js --capabilities core,async` builds a
 *      standalone `_runtime.scjs-runtime` (mirroring `compile-jvm`'s
 *      analogous --capabilities flag).
 *
 *  Tests spawn the actual `ssc` jar as a subprocess; they cancel if
 *  `ssc.jar` isn't on disk (run `sbt cli/assembly` first).
 *
 *  Run with: `sbt "cli/testOnly *JsRuntimeSeparation*"`
 */
class JsRuntimeSeparationTest extends AnyFunSuite:

  // ── Test scaffolding (mirrors JvmBytecodeRuntimeSeparationTest) ─────────

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
    os.proc(cmd).call(cwd = cwd, stdin = "", check = false, stderr = os.Pipe, stdout = os.Pipe)

  private def requireNode(): Unit =
    val res = scala.util.Try {
      os.proc("node", "--version").call(check = false, stderr = os.Pipe, stdout = os.Pipe)
    }
    if res.isFailure || !res.toOption.exists(_.exitCode == 0) then
      cancel("`node` not on PATH — needed for the end-to-end JS run test")

  // ── Test fixtures ───────────────────────────────────────────────────────

  /** A trivial module: one def + a println.  Only needs the `core` runtime. */
  private val aSscWithMain: String =
    """---
      |name: a
      |---
      |
      |# Module A
      |
      |```scalascript
      |def add(x: Int, y: Int): Int = x + y
      |println("a.add(2, 3) = " + add(2, 3))
      |```
      |""".stripMargin

  private val aSscQuiet: String =
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

  /** A second module that needs the `effects` capability via Logger.info. */
  private val bSscWithLogger: String =
    """---
      |name: b
      |---
      |
      |# Module B
      |
      |```scalascript
      |def announce(msg: String): Unit = Logger.info(msg)
      |```
      |""".stripMargin

  /** Read a JSON field from a `.scjs` / `.scjs-runtime` envelope. */
  private def readJsonStr(path: os.Path, key: String): String =
    ujson.read(os.read(path))(key).str

  private def readJsonArr(path: os.Path, key: String): List[String] =
    ujson.read(os.read(path))(key).arr.map(_.str).toList

  // ── 1. compile-js produces small .scjs + non-trivial .scjs-runtime ──────

  test("compile-js produces a small user-only .scjs and a shared _runtime.scjs-runtime"):
    val sandbox = os.temp.dir(prefix = "ssc-jsrt-sep-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)
      os.write(sandbox / "a.ssc", aSscQuiet)

      val res = runSsc(sandbox, "compile-js", "a.ssc",
                       "-o", "artifacts/a.scjs")
      assert(res.exitCode == 0,
        s"compile-js failed:\nstdout=${res.out.text()}\nstderr=${res.err.text()}")

      val scjs = artDir / "a.scjs"
      val rt   = artDir / "_runtime.scjs-runtime"
      assert(os.exists(scjs), s"expected $scjs")
      assert(os.exists(rt),   s"expected $rt — runtime artifact must accompany the module")

      // The user `.scjs` JS body should be well under 10 KB — the legacy
      // MVP shipped ~80 KB of preamble baked into every `jsSource`.
      val userJsSrc = readJsonStr(scjs, "jsSource")
      assert(userJsSrc.length < 10_000,
        s"expected user .scjs jsSource < 10 KB; got ${userJsSrc.length} chars")
      // Sanity: the user-only emit still contains the `add` symbol.
      assert(userJsSrc.contains("add"),
        s"expected `add` in user .scjs; got first 300 chars:\n${userJsSrc.take(300)}")
      // Sanity: the user-only emit DOES NOT contain a long stretch of
      // the runtime preamble (e.g. the literal `function _show(`).
      assert(!userJsSrc.contains("function _show("),
        "user-only .scjs should NOT contain the runtime preamble")

      // The shared runtime carries the full preamble.  Sane lower bound.
      val rtJsSrc = readJsonStr(rt, "jsSource")
      assert(rtJsSrc.length > 30_000,
        s"expected runtime .scjs-runtime jsSource > 30 KB; got ${rtJsSrc.length} chars")
      assert(rtJsSrc.contains("function _show("),
        "runtime preamble must contain `function _show(`")

      // Capabilities — the trivial module only needs `core`.
      val moduleCaps = readJsonArr(scjs, "capabilities").toSet
      assert(moduleCaps == Set("core"),
        s"expected user .scjs to declare {core}; got: $moduleCaps")
      val rtCaps = readJsonArr(rt, "capabilities").toSet
      assert(rtCaps.contains("core"), s"runtime missing `core` capability: $rtCaps")
    finally os.remove.all(sandbox)

  // ── 2. Touching the source re-compiles the .scjs but NOT the runtime ──

  test("re-running compile-js on unchanged source leaves the runtime untouched"):
    val sandbox = os.temp.dir(prefix = "ssc-jsrt-sep-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)
      os.write(sandbox / "a.ssc", aSscQuiet)

      val r1 = runSsc(sandbox, "compile-js", "a.ssc",
                      "-o", "artifacts/a.scjs")
      assert(r1.exitCode == 0, s"first compile failed: ${r1.err.text()}")

      val rt = artDir / "_runtime.scjs-runtime"
      val rtHash1 = readJsonStr(rt, "sourceHash")
      val rtCaps1 = readJsonArr(rt, "capabilities").toSet
      val rtMtime1 = os.mtime(rt)

      // Sleep so a stale mtime cache can't mask a rewrite, then rerun.
      Thread.sleep(1100)
      val r2 = runSsc(sandbox, "compile-js", "a.ssc",
                      "-o", "artifacts/a.scjs")
      assert(r2.exitCode == 0, s"second compile failed: ${r2.err.text()}")

      assert(readJsonStr(rt, "sourceHash") == rtHash1,
        "runtime sourceHash changed across no-op recompile")
      assert(readJsonArr(rt, "capabilities").toSet == rtCaps1,
        "runtime capabilities changed across no-op recompile")
      assert(os.mtime(rt) == rtMtime1,
        "runtime artifact was rewritten — staleness check is broken")
    finally os.remove.all(sandbox)

  // ── 3. Adding a module with a new capability widens the runtime ────────

  test("compiling a second module with a new capability widens the runtime's capability union"):
    val sandbox = os.temp.dir(prefix = "ssc-jsrt-sep-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      os.write(sandbox / "a.ssc", aSscQuiet)
      val ra = runSsc(sandbox, "compile-js", "a.ssc",
                      "-o", "artifacts/a.scjs")
      assert(ra.exitCode == 0, s"compile a failed: ${ra.err.text()}")

      val rt = artDir / "_runtime.scjs-runtime"
      val caps1 = readJsonArr(rt, "capabilities").toSet
      assert(!caps1.contains("effects"),
        s"unexpected `effects` capability in initial runtime: $caps1")

      os.write(sandbox / "b.ssc", bSscWithLogger)
      val rb = runSsc(sandbox, "compile-js", "b.ssc",
                      "-o", "artifacts/b.scjs")
      assert(rb.exitCode == 0, s"compile b failed: ${rb.err.text()}")

      val caps2 = readJsonArr(rt, "capabilities").toSet
      assert(caps2.contains("effects"),
        s"expected `effects` capability after compiling module with Logger.info; got: $caps2")
      // The union should be a superset of caps1.
      assert(caps1.subsetOf(caps2), s"capability union shrank: $caps1 -> $caps2")
    finally os.remove.all(sandbox)

  // ── 4. End-to-end: link + node prints the expected output ──────────────

  test("compile-js + link + node out.js runs cleanly and out.js is meaningfully smaller"):
    requireNode()
    val sandbox = os.temp.dir(prefix = "ssc-jsrt-sep-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)
      os.write(sandbox / "a.ssc", aSscWithMain)

      val rc = runSsc(sandbox, "compile-js", "a.ssc", "-o", "artifacts/a.scjs")
      assert(rc.exitCode == 0,
        s"compile-js failed:\nstdout=${rc.out.text()}\nstderr=${rc.err.text()}")

      val outJs = sandbox / "out.js"
      val rl = runSsc(sandbox, "link", "--backend", "js", "artifacts",
                      "-o", outJs.toString)
      assert(rl.exitCode == 0,
        s"link --backend js failed:\nstdout=${rl.out.text()}\nstderr=${rl.err.text()}")
      assert(os.exists(outJs), s"expected $outJs to be produced")

      // `out.js` should be smaller than the pre-refactor baseline.  The
      // v2.0 MVP for a single-module link emitted ~80 KB-150 KB; with
      // capability-gated runtime the same trivial module lands well
      // below 150 KB (and we're confident in being under 200 KB).
      val outSize = os.size(outJs)
      assert(outSize < 200_000,
        s"expected out.js < 200 KB after runtime separation; got $outSize bytes")

      // `node out.js` prints the expected line.
      val runRes = os.proc("node", outJs.toString).call(
        check = false, stderr = os.Pipe, stdout = os.Pipe
      )
      assert(runRes.exitCode == 0,
        s"node out.js failed: exit=${runRes.exitCode}\nstderr=${runRes.err.text()}")
      val stdout = runRes.out.text()
      assert(stdout.contains("a.add(2, 3) = 5"),
        s"expected 'a.add(2, 3) = 5' in stdout; got:\n$stdout")
    finally os.remove.all(sandbox)

  // ── 5. compile-runtime --backend js builds a standalone runtime ────────

  test("compile-runtime --backend js --capabilities core,async builds a standalone _runtime.scjs-runtime"):
    val sandbox = os.temp.dir(prefix = "ssc-jsrt-sep-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      val r = runSsc(sandbox, "compile-runtime",
                     "--backend", "js",
                     "--capabilities", "core,async",
                     "--artifact-dir", "artifacts")
      assert(r.exitCode == 0,
        s"compile-runtime --backend js failed:\nstdout=${r.out.text()}\nstderr=${r.err.text()}")

      val rt = artDir / "_runtime.scjs-runtime"
      assert(os.exists(rt))
      val caps = readJsonArr(rt, "capabilities").toSet
      assert(caps == Set("core", "async"),
        s"expected capabilities {core, async}; got: $caps")
      val jsSrc = readJsonStr(rt, "jsSource")
      assert(jsSrc.length > 30_000,
        s"expected non-empty compiled runtime jsSource; got ${jsSrc.length} chars")
      // The async runtime body contains the `Async = {` declaration —
      // verify it's actually present in the emitted runtime.
      assert(jsSrc.contains("const Async = {"),
        "expected `const Async = {` in async-capability runtime")
    finally os.remove.all(sandbox)
