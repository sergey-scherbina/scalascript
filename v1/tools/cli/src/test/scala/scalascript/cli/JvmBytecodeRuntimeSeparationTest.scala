package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import scalascript.artifact.JvmArtifactIO

/** v2.0 Phase 2 — tests for the split-runtime emit path.
 *
 *  The bytecode linker MVP shipped the full ~180 KB runtime preamble
 *  inside every `.scjvm` artifact's `classBundle`, producing ~500 KB
 *  per-module bundles and a 554 KB JAR for two trivial modules.
 *
 *  Phase 2 factors the preamble into a shared `_runtime.scjvm-runtime`
 *  artifact compiled once per session and reused at link time.  These
 *  tests verify:
 *
 *   1. `compile-jvm --bytecode` produces a module `.scjvm` whose
 *      `classBundle` is at least an order of magnitude smaller than the
 *      legacy 500+ KB number.
 *
 *   2. The shared runtime artifact (`_runtime.scjvm-runtime`) is written
 *      alongside the module artifacts and carries a non-empty classBundle.
 *
 *   3. Re-compiling the same source is a no-op for the runtime artifact
 *      — its sourceHash and capabilities are unchanged so the existing
 *      runtime is reused.
 *
 *   4. Adding a module that needs a new capability triggers runtime
 *      regeneration with the union.
 *
 *   5. End-to-end run: `java -cp out.jar:<stdlib> a_sc` prints the
 *      expected output from the separated module + runtime bundles.
 *
 *  All tests `cancel(...)` when prerequisites (installed `ssc-tools`,
 *  `scala-cli`, Scala 3 runtime libraries) are missing.
 *
 *  Run with: `sbt cli/assembly installBin "cli/testOnly *JvmBytecodeRuntimeSeparation*"`
 */
class JvmBytecodeRuntimeSeparationTest extends AnyFunSuite:

  // ── Test scaffolding (mirrors JvmBytecodeLinkCliTest) ───────────────────

  private val sscTools: Option[os.Path] =
    val cwd = os.pwd
    Iterator.iterate(cwd)(_ / os.up).take(8)
      .map(_ / "bin" / "ssc-tools")
      .find(os.exists)

  private def requireLauncher(): os.Path = sscTools.getOrElse:
    cancel("bin/ssc-tools not found — run `sbt cli/assembly installBin` first")

  private def requireScalaCli(): Unit =
    val res = scala.util.Try {
      os.proc("scala-cli", "--version").call(check = false, stderr = os.Pipe, stdout = os.Pipe)
    }
    if res.isFailure || !res.toOption.exists(_.exitCode == 0) then
      cancel("`scala-cli` not on PATH — needed for compile-jvm --bytecode")

  private def compilerDriverAvailable: Boolean =
    sscTools.exists { launcher =>
      val jars = launcher / os.up / "lib" / "compiler" / "jars"
      os.isDir(jars) && os.list(jars).exists(_.ext == "jar")
    }

  private def requireCompilerDriver(): Unit =
    if !compilerDriverAvailable then
      cancel("compiler-driver jars not staged (run `sbt cli/assembly installBin`); skipping --bytecode test")

  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    val launcher = requireLauncher()
    val cmd: Seq[os.Shellable] =
      Seq[os.Shellable](launcher.toString) ++
      args.map(a => a: os.Shellable)
    os.proc(cmd).call(cwd = cwd, stdin = "", check = false, stderr = os.Pipe, stdout = os.Pipe)

  private def scalaStdlibClasspath(): Option[String] =
    def codeSource(clazz: Class[?]): Option[os.Path] =
      scala.util.Try {
        os.Path(clazz.getProtectionDomain.getCodeSource.getLocation.toURI)
      }.toOption.filter(os.exists)

    for
      scala3 <- codeSource(classOf[scala.deriving.Mirror])
      scala2 <- codeSource(classOf[scala.Product])
    yield List(scala3, scala2).distinct.mkString(java.io.File.pathSeparator)

  private def requireScalaStdlib(): String = scalaStdlibClasspath().getOrElse:
    cancel("Scala 3 runtime libraries are not visible to the test JVM — skipping JAR-run test")

  // ── Test fixtures ────────────────────────────────────────────────────────

  /** A trivial module: one def + a println.  Needs no extra capabilities. */
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

  /** A second module needing the `serve` capability — adds a route(). */
  private val bSscWithRoute: String =
    """---
      |name: b
      |---
      |
      |# Module B
      |
      |```scalascript
      |route("GET", "/hi") { req => Response.text("hi") }
      |println("b registered route")
      |```
      |""".stripMargin

  /** Read the `classBundle` field of a `.scjvm` and return its base64 length. */
  private def classBundleSize(scjvm: os.Path): Int =
    readJvmArtifact(scjvm).classBundle.map(_.length).getOrElse(0)

  /** Read the `classBundle` field of a `.scjvm-runtime` and return its size. */
  private def runtimeBundleSize(runtime: os.Path): Int =
    readRuntimeArtifact(runtime).classBundle.length

  private def readJvmArtifact(path: os.Path): scalascript.ir.ModuleJvmArtifact =
    JvmArtifactIO.readJvmFile(path).fold(
      err => fail(s"failed to decode $path through JvmArtifactIO: $err"),
      identity)

  private def readRuntimeArtifact(path: os.Path): scalascript.ir.ModuleJvmRuntimeArtifact =
    JvmArtifactIO.readRuntimeFile(path).fold(
      err => fail(s"failed to decode $path through JvmArtifactIO: $err"),
      identity)

  // ── 1. Module .scjvm is small; shared runtime carries the preamble ─────

  test("compile-jvm --bytecode produces a small module .scjvm and a shared _runtime.scjvm-runtime"):
    requireScalaCli()
    requireCompilerDriver()
    val sandbox = os.temp.dir(prefix = "ssc-split-runtime-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)
      os.write(sandbox / "a.ssc", aSscQuiet)

      val res = runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                       "-o", "artifacts/a.scjvm")
      assert(res.exitCode == 0,
        s"compile-jvm --bytecode failed:\nstdout=${res.out.text()}\nstderr=${res.err.text()}")

      val scjvm = artDir / "a.scjvm"
      val rt    = artDir / "_runtime.scjvm-runtime"
      assert(os.exists(scjvm), s"expected $scjvm")
      assert(os.exists(rt),    s"expected $rt — runtime artifact must accompany the module")

      // Compare the two artifacts produced by this run. A fixed byte ceiling
      // goes stale as the shared runtime grows and can pass/fail without saying
      // whether the module duplicated it.
      val moduleCb = classBundleSize(scjvm)
      assert(moduleCb > 0, "expected the module classBundle to be non-empty")

      val rtCb = runtimeBundleSize(rt)
      assert(rtCb >= moduleCb * 10,
        s"expected shared runtime bundle to be at least 10x the module bundle; " +
          s"module=$moduleCb runtime=$rtCb base64 chars")

      val scjvmFileSize = os.size(scjvm)
      val runtimeFileSize = os.size(rt)
      assert(runtimeFileSize >= scjvmFileSize * 10,
        s"expected shared runtime artifact to be at least 10x the module artifact; " +
          s"module=$scjvmFileSize runtime=$runtimeFileSize bytes")
    finally os.remove.all(sandbox)

  // ── 2. Re-compiling the same source is a runtime no-op ──────────────────

  test("re-running compile-jvm --bytecode on unchanged source leaves the runtime untouched"):
    requireScalaCli()
    requireCompilerDriver()
    val sandbox = os.temp.dir(prefix = "ssc-split-runtime-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)
      os.write(sandbox / "a.ssc", aSscQuiet)

      val r1 = runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                      "-o", "artifacts/a.scjvm")
      assert(r1.exitCode == 0, s"first compile failed: ${r1.err.text()}")

      val rt = artDir / "_runtime.scjvm-runtime"
      val rtArt1 = readRuntimeArtifact(rt)
      val rtHash1 = rtArt1.sourceHash
      val rtCaps1 = rtArt1.capabilities.toSet
      val rtMtime1 = os.mtime(rt)

      // Sleep so a stale mtime cache can't confuse us — and rerun.
      Thread.sleep(1100)
      val r2 = runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                      "-o", "artifacts/a.scjvm")
      assert(r2.exitCode == 0, s"second compile failed: ${r2.err.text()}")

      val rtArt2 = readRuntimeArtifact(rt)
      assert(rtArt2.sourceHash == rtHash1, "runtime sourceHash changed across no-op recompile")
      assert(rtArt2.capabilities.toSet == rtCaps1,
        "runtime capabilities changed across no-op recompile")
      assert(os.mtime(rt) == rtMtime1,
        "runtime artifact was rewritten — staleness check is broken")
    finally os.remove.all(sandbox)

  // ── 3. Adding a module with a new capability regenerates the runtime ───

  test("compiling a second module with a new capability widens the runtime's capability union"):
    requireScalaCli()
    requireCompilerDriver()
    val sandbox = os.temp.dir(prefix = "ssc-split-runtime-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      os.write(sandbox / "a.ssc", aSscQuiet)
      val ra = runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                      "-o", "artifacts/a.scjvm")
      assert(ra.exitCode == 0, s"compile a failed: ${ra.err.text()}")

      val rt = artDir / "_runtime.scjvm-runtime"
      val caps1 = readRuntimeArtifact(rt).capabilities.toSet
      // Module `a` has no extra capabilities — minimal preamble.
      assert(!caps1.contains("serve"),
        s"unexpected 'serve' capability in initial runtime: $caps1")

      os.write(sandbox / "b.ssc", bSscWithRoute)
      val rb = runSsc(sandbox, "compile-jvm", "--bytecode", "b.ssc",
                      "-o", "artifacts/b.scjvm")
      assert(rb.exitCode == 0, s"compile b failed: ${rb.err.text()}")

      val caps2 = readRuntimeArtifact(rt).capabilities.toSet
      assert(caps2.contains("serve"),
        s"expected 'serve' capability after compiling module with route(); got: $caps2")
      // The union should be a superset of caps1.
      assert(caps1.subsetOf(caps2), s"capability union shrank: $caps1 -> $caps2")
    finally os.remove.all(sandbox)

  // ── 4. End-to-end run: java -cp out.jar a_sc prints expected output ────

  test("compile-jvm --bytecode + link --bytecode + java -cp run a_sc"):
    requireScalaCli()
    requireCompilerDriver()
    val stdlib = requireScalaStdlib()
    val sandbox = os.temp.dir(prefix = "ssc-split-runtime-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)
      os.write(sandbox / "a.ssc", aSscWithMain)

      assert(runSsc(sandbox, "compile-jvm", "--bytecode", "a.ssc",
                    "-o", "artifacts/a.scjvm").exitCode == 0)

      val outJar = sandbox / "out.jar"
      val rl = runSsc(sandbox, "link", "--backend", "jvm", "--bytecode",
                      "artifacts", "-o", outJar.toString)
      assert(rl.exitCode == 0,
        s"link --bytecode failed:\nstdout=${rl.out.text()}\nstderr=${rl.err.text()}")
      assert(os.exists(outJar))

      val jarSize = os.size(outJar)
      assert(jarSize > 0, "linked JAR must not be empty")

      val runRes = os.proc("java", "-cp", s"$outJar:$stdlib", "a_sc").call(
        check = false, stderr = os.Pipe, stdout = os.Pipe
      )
      assert(runRes.exitCode == 0,
        s"java -cp out.jar a_sc failed: exit=${runRes.exitCode}\nstderr=${runRes.err.text()}")
      val out = runRes.out.text()
      assert(out.contains("a.add(2, 3) = 5"),
        s"expected 'a.add(2, 3) = 5' in stdout; got:\n$out")
    finally os.remove.all(sandbox)

  // ── 5. compile-runtime --capabilities exposes the runtime compile path ──

  test("compile-runtime --capabilities builds a standalone _runtime.scjvm-runtime"):
    requireScalaCli()
    requireCompilerDriver()
    val sandbox = os.temp.dir(prefix = "ssc-split-runtime-")
    try
      val artDir = sandbox / "artifacts"
      os.makeDir.all(artDir)

      val r = runSsc(sandbox, "compile-runtime",
                     "--capabilities", "effects,serve",
                     "--artifact-dir", "artifacts")
      assert(r.exitCode == 0,
        s"compile-runtime failed:\nstdout=${r.out.text()}\nstderr=${r.err.text()}")

      val rt = artDir / "_runtime.scjvm-runtime"
      assert(os.exists(rt))
      val runtime = readRuntimeArtifact(rt)
      val caps = runtime.capabilities.toSet
      assert(caps == Set("effects", "serve"),
        s"expected capabilities {effects, serve}; got: $caps")
      assert(runtime.classBundle.nonEmpty,
        "expected non-empty compiled runtime classBundle")
    finally os.remove.all(sandbox)
