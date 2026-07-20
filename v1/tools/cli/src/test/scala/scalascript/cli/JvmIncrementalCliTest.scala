package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.artifact.JvmArtifactIO
import upickle.default.read as upickleRead
import ujson.Value as JsonValue

/** v2.0 — end-to-end smoke tests for the JVM incremental codegen cache.
 *
 *  Cover the three CLI surfaces added for `.scjvm` cached-source artifacts:
 *
 *   1. `ssc compile-jvm <file.ssc>`     → produces `<file>.scjvm` with
 *                                         SSCART magic, ABI 2.0, non-empty
 *                                         `scalaSource`.
 *   2. `ssc compile-jvm a.ssc`, `ssc compile-jvm b.ssc`,
 *      `ssc link --backend jvm <dir> -o out.jar`  → combined JAR is built
 *                                         from textually-concatenated
 *                                         per-module sources.
 *   3. `ssc build --incremental --backend jvm <dir>` twice in a row:
 *      the second run skips both modules (mtime unchanged on `.scjvm`).
 *      Touching one source regenerates only that module's `.scjvm`.
 *   4. `ssc run-jvm <file.ssc>` invalidates a source-fresh `.scjvm`
 *      when its JVM codegen cache key is old.
 *
 *  Tests spawn the actual `ssc` jar as a subprocess.  When the jar is missing
 *  the tests cancel with a diagnostic message (same pattern as
 *  `RegistrySubprocessTest` and `V2ArtifactCliTest`).
 *
 *  Run with:  `sbt "cli/testOnly *JvmIncremental*"`
 */
class JvmIncrementalCliTest extends AnyFunSuite:

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

  /** Some tests rely on the `scala-cli` binary being on PATH (for the JAR
   *  packaging step).  When it isn't, cancel rather than fail — local dev
   *  setups vary. */
  private def requireScalaCli(): Unit =
    val res = scala.util.Try {
      os.proc("scala-cli", "--version").call(check = false, stderr = os.Pipe, stdout = os.Pipe)
    }
    if res.isFailure || !res.toOption.exists(_.exitCode == 0) then
      cancel("`scala-cli` not on PATH — needed for link --backend jvm -o foo.jar")

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

  // ── 1. compile-jvm → .scjvm ──────────────────────────────────────────────

  test("compile-jvm produces a valid .scjvm with SSCART magic, ABI 2.0, non-empty scalaSource"):
    val sandbox = os.temp.dir(prefix = "ssc-jvminc-")
    try
      val src = sandbox / "a.ssc"
      os.write(src, aSsc())

      val res = runSsc(sandbox, "compile-jvm", "a.ssc")
      assert(res.exitCode == 0,
        s"compile-jvm failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")

      val scjvm = sandbox / "a.scjvm"
      assert(os.exists(scjvm), s"expected $scjvm to be written; got dir: ${os.list(sandbox).mkString(", ")}")

      val json = upickleRead[JsonValue](os.read(scjvm))
      assert(json("magic").str == "SSCART", s"magic mismatch: ${json("magic")}")
      assert(json("abiVersion").str == "2.0", s"abiVersion mismatch: ${json("abiVersion")}")

      val scalaSource = json("scalaSource").str
      assert(scalaSource.nonEmpty, "expected non-empty scalaSource in .scjvm")
      // Sanity: the JVM backend emits `def add(...)` somewhere in the source.
      assert(scalaSource.contains("def add"),
        s"expected `def add` in emitted scalaSource; got:\n${scalaSource.take(200)}...")

      val sourceHash = json("sourceHash").str
      assert(sourceHash.nonEmpty, "expected non-empty sourceHash")
      assert(sourceHash.length == 64,
        s"expected SHA-256 hex digest (64 chars), got ${sourceHash.length}: $sourceHash")
    finally os.remove.all(sandbox)

  test("run-jvm regenerates a source-fresh .scjvm when the JVM codegen cache key is old"):
    requireScalaCli()
    val sandbox = os.temp.dir(prefix = "ssc-jvminc-")
    try
      val src = sandbox / "cache.ssc"
      os.write(src,
        """# Cache
          |
          |```scalascript
          |println("cache-ok")
          |```
          |""".stripMargin)

      val first = runSsc(sandbox, "run-jvm", "cache.ssc")
      assert(first.exitCode == 0,
        s"first run-jvm failed: exit=${first.exitCode}\nstdout=${first.out.text()}\nstderr=${first.err.text()}")

      val scjvm = sandbox / ".ssc-artifacts" / "cache.scjvm"
      assert(os.exists(scjvm), s"expected run-jvm to write $scjvm")
      val original = JvmArtifactIO.readJvmFile(scjvm).fold(err => fail(err), identity)
      // The subprocess `run-jvm` writes `codegenVersion` using the *jar's* build
      // stamp (`compilerBuildStamp` = jar mtime-size). `hasCurrentCodegenVersion`
      // computed in THIS test JVM cannot be used as the oracle: the test JVM loads
      // `JvmArtifactIO` from the compiled classes dir (not the jar), so its
      // "current" stamp is derived from the .class file, never matching the jar's.
      // Use the fresh key the subprocess itself just wrote as the oracle instead —
      // that is the jar's own notion of "current" and the value regeneration must
      // restore.
      val jarFreshVersion = original.codegenVersion
      assert(jarFreshVersion.startsWith("jvm-codegen-") && jarFreshVersion != "jvm-codegen-old",
        s"initial run-jvm artifact should carry a fresh JVM codegen key, got '$jarFreshVersion'")

      val stale = original.copy(codegenVersion = "jvm-codegen-old")
      JvmArtifactIO.writeJvmFile(stale, scjvm)
      val staleOnDisk = JvmArtifactIO.readJvmFile(scjvm).fold(err => fail(err), identity)
      assert(staleOnDisk.codegenVersion == "jvm-codegen-old",
        "test setup failed to write a stale JVM codegen key")

      val second = runSsc(sandbox, "run-jvm", "cache.ssc")
      assert(second.exitCode == 0,
        s"second run-jvm failed: exit=${second.exitCode}\nstdout=${second.out.text()}\nstderr=${second.err.text()}")

      val regenerated = JvmArtifactIO.readJvmFile(scjvm).fold(err => fail(err), identity)
      // run-jvm saw a source-fresh but stale-codegen artifact and regenerated it:
      // the key is back to the jar's fresh value, no longer the injected stale one.
      assert(regenerated.codegenVersion == jarFreshVersion,
        s"run-jvm reused a source-fresh but old-codegen .scjvm; got '${regenerated.codegenVersion}', expected '$jarFreshVersion'")
    finally os.remove.all(sandbox)

  // ── 2. compile-jvm a.ssc + b.ssc, then link --backend jvm -o out.jar ─────

  test("link --backend jvm packages two .scjvm artifacts into a non-empty JAR"):
    requireScalaCli()
    val sandbox = os.temp.dir(prefix = "ssc-jvminc-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      // Module A first — emit interface + .scjvm.
      val aSrc = sandbox / "a.ssc"
      os.write(aSrc, aSsc())
      assert(runSsc(sandbox, "emit-interface", "a.ssc", "-o", "artifacts/a.scim").exitCode == 0,
        "emit-interface for a failed")
      val ca = runSsc(sandbox, "compile-jvm", "a.ssc", "-o", "artifacts/a.scjvm")
      assert(ca.exitCode == 0, s"compile-jvm a failed: ${ca.err.text()}")
      assert(os.exists(artDir / "a.scjvm"))

      // Module B — refers to its own local `mul`, doesn't actually need a's
      // interface, but we exercise the --iface-dir path anyway.
      val bSrc = sandbox / "b.ssc"
      os.write(bSrc, bSsc)
      val cb = runSsc(sandbox, "compile-jvm", "b.ssc",
        "--iface-dir", "artifacts", "-o", "artifacts/b.scjvm")
      assert(cb.exitCode == 0, s"compile-jvm b failed: ${cb.err.text()}")
      assert(os.exists(artDir / "b.scjvm"))

      // Link the two .scjvm into a JAR via scala-cli package.
      val outJar = sandbox / "out.jar"
      val rl = runSsc(sandbox, "link", "--backend", "jvm", "artifacts", "-o", outJar.toString)
      assert(rl.exitCode == 0,
        s"link --backend jvm failed: exit=${rl.exitCode}\nstdout=${rl.out.text()}\nstderr=${rl.err.text()}")
      assert(os.exists(outJar), s"expected $outJar to be produced")
      assert(os.size(outJar) > 0, "expected non-empty JAR")
    finally os.remove.all(sandbox)

  /** A second cheaper variant that verifies link --backend jvm without scala-cli
   *  by writing the combined source to a .scala file (no external dependency). */
  test("link --backend jvm with -o out.scala concatenates per-module sources"):
    val sandbox = os.temp.dir(prefix = "ssc-jvminc-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      os.write(sandbox / "a.ssc", aSsc())
      os.write(sandbox / "b.ssc", bSsc)

      assert(runSsc(sandbox, "compile-jvm", "a.ssc", "-o", "artifacts/a.scjvm").exitCode == 0)
      assert(runSsc(sandbox, "compile-jvm", "b.ssc", "-o", "artifacts/b.scjvm").exitCode == 0)

      val outScala = sandbox / "out.scala"
      val rl = runSsc(sandbox, "link", "--backend", "jvm", "artifacts", "-o", outScala.toString)
      assert(rl.exitCode == 0,
        s"link --backend jvm -o .scala failed: ${rl.err.text()}")
      assert(os.exists(outScala))
      val combined = os.read(outScala)
      assert(combined.contains("def add"),
        s"expected `def add` (from a) in combined source; got:\n${combined.take(300)}")
      assert(combined.contains("def mul"),
        s"expected `def mul` (from b) in combined source; got:\n${combined.take(300)}")
    finally os.remove.all(sandbox)

  // ── 3. build --incremental --backend jvm idempotency + per-file rebuild ──

  test("build --incremental --backend jvm is idempotent; touching a source regenerates only that .scjvm"):
    val sandbox = os.temp.dir(prefix = "ssc-jvminc-")
    try
      val srcDir = sandbox / "src"
      os.makeDir.all(srcDir)
      val aSrc = srcDir / "a.ssc"
      val bSrc = srcDir / "b.ssc"
      os.write(aSrc, aSsc())
      os.write(bSrc, bSsc)

      val artDir = srcDir / ".ssc-artifacts"

      // First build — emits .scim + .scir + .scjvm for both modules.
      val r1 = runSsc(sandbox, "build", "--incremental", "--backend", "jvm", "src")
      assert(r1.exitCode == 0,
        s"first build failed: ${r1.err.text()}")
      val aScjvm = artDir / "a.scjvm"
      val bScjvm = artDir / "b.scjvm"
      assert(os.exists(aScjvm) && os.exists(bScjvm),
        s"expected both .scjvm files after first build; got: ${os.list(artDir).mkString(", ")}")

      val aMtime1 = os.mtime(aScjvm)
      val bMtime1 = os.mtime(bScjvm)

      // Filesystem mtime resolution varies; sleep long enough to detect a
      // rewrite that should not happen.
      Thread.sleep(1100)

      // Second build — no source changes.  Both .scjvm mtimes should be
      // untouched and the output should announce skips.
      val r2 = runSsc(sandbox, "build", "--incremental", "--backend", "jvm", "src")
      assert(r2.exitCode == 0, s"second build failed: ${r2.err.text()}")
      val r2out = r2.out.text()
      assert(r2out.contains("[skip]"),
        s"expected second build to skip unchanged modules; output:\n$r2out")
      assert(os.mtime(aScjvm) == aMtime1,
        s"a.scjvm mtime changed despite no source change: $aMtime1 → ${os.mtime(aScjvm)}")
      assert(os.mtime(bScjvm) == bMtime1, "b.scjvm mtime changed despite no source change")

      // Touch a's source so its SHA-256 changes; b should stay skipped.
      os.write.over(aSrc, aSsc(extra = "\n## New section\n\nMore prose.\n"))
      Thread.sleep(1100)

      val r3 = runSsc(sandbox, "build", "--incremental", "--backend", "jvm", "src")
      assert(r3.exitCode == 0, s"third build failed: ${r3.err.text()}")
      assert(os.mtime(aScjvm) > aMtime1,
        s"a.scjvm should have been regenerated; mtime $aMtime1 → ${os.mtime(aScjvm)}")
      assert(os.mtime(bScjvm) == bMtime1,
        s"b.scjvm should NOT have been regenerated (b's source unchanged); mtime $bMtime1 → ${os.mtime(bScjvm)}")
    finally os.remove.all(sandbox)
