package scalascript.cli

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

import org.scalatest.funsuite.AnyFunSuite

/** v2.0 Phase 3 — direct compiler-driver tests.
 *
 *  Verifies that [[Scala3Driver.compile]] + [[JvmBytecode.compileAndPackDirect]]
 *  produce a structurally-correct classBundle, and (advisory only) logs
 *  the wall-clock delta against the legacy `scala-cli` subprocess path.
 *
 *  The test cancels (does NOT fail) when prerequisites are missing:
 *
 *   - `scala3-library_3` / `scala-library` JARs absent from both the
 *     JVM classpath AND the Coursier cache — happens in stripped-down
 *     CI environments.
 *   - `scala-cli` not on PATH — the comparative-perf assertion is
 *     skipped (the direct-driver assertions still run).
 *
 *  Run with:  `sbt "cli/testOnly *JvmDirectDriver*"`
 */
class JvmDirectDriverTest extends AnyFunSuite:

  /** A small self-contained Scala 3 source — no SSC frontend, no
   *  preamble, no runtime — just enough that the Driver has real work
   *  to do (resolve `Int`, `String`, `println` from the stdlib). */
  private val smallSource: String =
    """def add(x: Int, y: Int): Int = x + y
      |def greet(name: String): String = "Hello, " + name + "!"
      |println(greet("world"))
      |println("add(2, 3) = " + add(2, 3))
      |""".stripMargin

  /** Pull every entry name out of a base64-encoded ZIP. */
  private def listBundleEntries(b64: String): List[String] =
    val bytes = Base64.getDecoder.decode(b64)
    val zis   = new ZipInputStream(new ByteArrayInputStream(bytes))
    try
      val out = scala.collection.mutable.ListBuffer.empty[String]
      var e   = zis.getNextEntry
      while e != null do
        out += e.getName
        zis.closeEntry()
        e = zis.getNextEntry
      out.toList
    finally zis.close()

  /** Is scala-cli available?  Used to gate the comparative-perf test. */
  private def scalaCliAvailable: Boolean = JvmBytecode.scalaCliAvailable

  /** The in-process driver needs the staged compiler classloader
   *  (`<ssc.lib.path>/bin/lib/compiler/jars/`), populated by `installBin`.
   *  Configure this test JVM with the same root as the installed launcher
   *  before CompilerLoader's lazy service is initialised. */
  private def compilerDriverAvailable: Boolean =
    StagedCliTestSupport.compilerDriverAvailable

  private def requireCompilerDriver(): Unit =
    if !compilerDriverAvailable then
      cancel("compiler-driver jars not staged (run `sbt cli/assembly installBin`); skipping in-process driver test")
    StagedCliTestSupport.configureInstalledLibPath().getOrElse:
      cancel("installed ssc root not found after compiler-driver check")

  // ── 1. In-process driver compiles + produces a non-empty classBundle ─

  test("Scala3Driver compiles a small fixture in-process"):
    requireCompilerDriver()
    val t0 = System.nanoTime()
    val result = JvmBytecode.compileAndPackDirect(
      scalaSource   = smallSource,
      classpathDirs = Nil,
      scriptName    = "directA"
    )
    val elapsedMs = (System.nanoTime() - t0) / 1_000_000

    result match
      case Left(err) =>
        // The stdlib-not-found path is a *cancel*, not a failure.
        if err.contains("could not locate scala3-library") then
          cancel(s"Scala 3 stdlib JARs not findable on this host; skipping. Diagnostic: $err")
        else fail(s"compileAndPackDirect failed unexpectedly:\n$err")

      case Right(b64) =>
        assert(b64.nonEmpty, "expected non-empty classBundle")
        val entries = listBundleEntries(b64)
        info(s"Scala3Driver compile took ${elapsedMs}ms, produced ${entries.size} entries")
        info(s"entries: ${entries.mkString(", ")}")

        val classEntries = entries.filter(_.endsWith(".class"))
        assert(classEntries.nonEmpty,
          s"expected at least one .class entry; got: ${entries.mkString(", ")}")

        // The `directA` script name should produce `directA_sc.class` —
        // same FQN scheme as the scala-cli path (`<scriptName>_sc`),
        // so the linker treats the two backends interchangeably.
        assert(classEntries.exists(_.endsWith("directA_sc.class")),
          s"expected `directA_sc.class` (script wrapper); got: ${classEntries.mkString(", ")}")

        // `.tasty` files must also ship — downstream modules need them
        // to resolve cross-module references at compile time.
        val tastyEntries = entries.filter(_.endsWith(".tasty"))
        assert(tastyEntries.nonEmpty,
          s"expected at least one .tasty entry; got: ${entries.mkString(", ")}")

  // ── 2. Direct path and scala-cli path produce structurally-identical bundles ─

  test("Scala3Driver and scala-cli produce a structurally compatible classBundle"):
    requireCompilerDriver()
    if !scalaCliAvailable then
      cancel("scala-cli not on PATH — can't compare against subprocess path")

    val directResult = JvmBytecode.compileAndPackDirect(
      scalaSource   = smallSource,
      classpathDirs = Nil,
      scriptName    = "cmpA"
    )
    val direct = directResult match
      case Right(b64) => b64
      case Left(err)  =>
        if err.contains("could not locate scala3-library") then
          cancel(s"Scala 3 stdlib JARs not findable on this host; skipping. Diagnostic: $err")
        else fail(s"compileAndPackDirect failed:\n$err")

    val cliResult = JvmBytecode.compileAndPackScalaCli(
      scalaSource   = smallSource,
      classpathDirs = Nil,
      scriptName    = "cmpA"
    )
    val cli = cliResult match
      case Right(b64) => b64
      case Left(err)  => fail(s"compileAndPackScalaCli failed:\n$err")

    val directEntries = listBundleEntries(direct).filter(_.endsWith(".class")).toSet
    val cliEntries    = listBundleEntries(cli).filter(_.endsWith(".class")).toSet

    info(s"direct .class entries: ${directEntries.toList.sorted.mkString(", ")}")
    info(s"scala-cli .class entries: ${cliEntries.toList.sorted.mkString(", ")}")

    // Same script-wrapper class on both paths.  We don't assert FULL set
    // equality because the two backends differ in synthetic inner-class
    // emission shapes (one may emit `cmpA_sc$package$.class`, the other
    // may inline statics differently), but the principal wrapper must
    // match.
    assert(directEntries.exists(_.endsWith("cmpA_sc.class")),
      s"direct path missing cmpA_sc.class; got ${directEntries.mkString(", ")}")
    assert(cliEntries.exists(_.endsWith("cmpA_sc.class")),
      s"scala-cli path missing cmpA_sc.class; got ${cliEntries.mkString(", ")}")

  // ── 3. Wall-clock comparison (advisory, never fails on regression) ──

  test("Scala3Driver is faster than scala-cli on a small fixture (advisory log only)"):
    requireCompilerDriver()
    if !scalaCliAvailable then
      cancel("scala-cli not on PATH — can't compare timings")

    // Warm-up both paths once so JIT and Coursier resolution don't
    // skew the measurement.
    JvmBytecode.compileAndPackDirect(smallSource, Nil, "warmDirect")
    JvmBytecode.compileAndPackScalaCli(smallSource, Nil, "warmCli")

    def time(label: String, f: => Either[String, String]): Long =
      val t0 = System.nanoTime()
      val r  = f
      val ms = (System.nanoTime() - t0) / 1_000_000
      r match
        case Right(_) => info(s"$label: ${ms}ms (ok)")
        case Left(e)  => info(s"$label: ${ms}ms (FAILED: ${e.take(120)})")
      ms

    val directMs = time("Scala3Driver.compile",
      JvmBytecode.compileAndPackDirect(smallSource, Nil, "perfDirect"))
    val cliMs    = time("scala-cli subprocess",
      JvmBytecode.compileAndPackScalaCli(smallSource, Nil, "perfCli"))

    info(f"Phase 3 perf delta: direct ${directMs}ms vs scala-cli ${cliMs}ms " +
         f"(${cliMs.toDouble / math.max(directMs, 1).toDouble}%.1fx)")
    // Intentionally no `assert` — perf on CI varies; the log line above
    // is the artifact we care about.
