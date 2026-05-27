package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterEach
import scalascript.interpreter.Interpreter
import scalascript.typer.Typer
import scala.compiletime.uninitialized

/** Benchmark: watch reload cycle on a multi-section script must stay < 100 ms.
 *
 *  Mirrors the incremental path taken by `ssc watch` and `ssc watch-bench`:
 *    1. Cold: `ParseCache.getOrParse` → `typeCheckIncrementalModule` → `runWithCheckpoints`
 *    2. Hot:  mutate last section on disk, then repeat the above using
 *             `runSectionsIncremental` — only the changed suffix is re-evaluated.
 *
 *  The test captures 7 hot-cycle wall-clock measurements and asserts that
 *  the median is below TARGET_MS.  It is skipped on JVMs that are too slow
 *  to warm up within a generous 5x multiplier (CI guard). */
class WatchCycleBenchTest extends AnyFunSuite with BeforeAndAfterEach:

  private val TARGET_MS  = 100L
  private val CYCLES     = 7
  private val CI_FACTOR  = 5   // skip rather than fail on slow CI JVMs

  private var tmpDir:  os.Path = uninitialized
  private var tmpFile: os.Path = uninitialized

  override def beforeEach(): Unit =
    tmpDir  = os.temp.dir(prefix = "ssc-watch-bench-", deleteOnExit = true)
    tmpFile = tmpDir / "bench.ssc"
    ParseCache.clear()

  override def afterEach(): Unit =
    ParseCache.clear()

  // ── fixture ────────────────────────────────────────────────────────────

  private val baseSrc =
    """|# Lib
       |```scala
       |def greet(n: String) = s"Hello $n"
       |def square(x: Int)   = x * x
       |def cube(x: Int)     = x * square(x)
       |```
       |
       |# Math
       |```scala
       |val result = (1 to 50).map(square).sum
       |val cubes  = (1 to 20).map(cube).toList
       |```
       |
       |# Output
       |```scala
       |println(greet("world"))
       |println(s"sum-of-squares(1..50) = $result")
       |```
       |""".stripMargin

  /** Append a unique comment to the last section so mtime + content hash change. */
  private def mutate(cycle: Int): Unit =
    val marker = s"// bench-cycle-$cycle"
    val src    = os.read(tmpFile)
    val idx    = src.lastIndexOf("```")
    val next   =
      if idx >= 0 then src.take(idx) + marker + "\n" + src.drop(idx)
      else src + s"\n# Extra $cycle\n```scala\n$marker\n```\n"
    Thread.sleep(10) // ensure mtime advances so ParseCache sees the change
    os.write.over(tmpFile, next)

  // ── helpers ────────────────────────────────────────────────────────────

  private def devNull = new java.io.PrintStream(
    new java.io.ByteArrayOutputStream(), true, "UTF-8")

  // ── tests ──────────────────────────────────────────────────────────────

  test("watch cycle median < 100 ms (incremental path)"):
    os.write(tmpFile, baseSrc)

    val interp      = Interpreter(out = devNull, baseDir = Some(tmpDir))
    var checkpoints = interp.runWithCheckpoints(ParseCache.getOrParse(tmpFile))
    val (_, snaps0) = Typer.typeCheckIncrementalModule(
      ParseCache.getOrParse(tmpFile), Nil)
    var prevSnapshots = snaps0

    val samples = (1 to CYCLES).map { cycle =>
      mutate(cycle)
      val t0 = System.nanoTime()

      val module = ParseCache.getOrParse(tmpFile)
      val (_, newSnapshots) = Typer.typeCheckIncrementalModule(module, prevSnapshots)
      val prevHashes = prevSnapshots.map(_.sectionHash)
      val currHashes = newSnapshots.map(_.sectionHash)
      val firstChanged = currHashes.zipWithIndex.collectFirst {
        case (h, idx) if prevHashes.lift(idx).forall(_ != h) => idx
      }.getOrElse(module.sections.length)
      checkpoints   = interp.runSectionsIncremental(module.sections, firstChanged, checkpoints)
      prevSnapshots = newSnapshots

      (System.nanoTime() - t0) / 1_000_000L
    }.toVector

    val sorted = samples.sorted
    val p50    = sorted(sorted.length / 2)
    val maxMs  = sorted.last

    println(s"watch-cycle: cycles=$CYCLES p50=${p50}ms max=${maxMs}ms target=${TARGET_MS}ms")

    assume(p50 < TARGET_MS * CI_FACTOR,
      s"JVM too slow for benchmark (p50=${p50}ms, skip threshold=${TARGET_MS * CI_FACTOR}ms)")

    assert(p50 < TARGET_MS,
      s"watch cycle p50 ${p50}ms ≥ target ${TARGET_MS}ms (max=${maxMs}ms)")

  test("ParseCache avoids re-parse on unchanged content"):
    os.write(tmpFile, baseSrc)
    val m1 = ParseCache.getOrParse(tmpFile)
    val m2 = ParseCache.getOrParse(tmpFile)
    assert(m1 eq m2, "expected cache hit: same Module reference for unchanged file")

  test("watch cycle: only changed suffix is re-evaluated"):
    os.write(tmpFile, baseSrc)
    val buf = new java.io.ByteArrayOutputStream()
    val interp = Interpreter(out = new java.io.PrintStream(buf, true, "UTF-8"),
                             baseDir = Some(tmpDir))

    val module0 = ParseCache.getOrParse(tmpFile)
    var checkpoints = interp.runWithCheckpoints(module0)
    val (_, prevSnapshots) = Typer.typeCheckIncrementalModule(module0, Nil)
    buf.reset()

    mutate(1)
    val module1 = ParseCache.getOrParse(tmpFile)
    val (_, snaps1) = Typer.typeCheckIncrementalModule(module1, prevSnapshots)
    val prevHashes = prevSnapshots.map(_.sectionHash)
    val currHashes = snaps1.map(_.sectionHash)
    val firstChanged = currHashes.zipWithIndex.collectFirst {
      case (h, idx) if prevHashes.lift(idx).forall(_ != h) => idx
    }.getOrElse(module1.sections.length)

    // Only the last section changed → firstChanged should be last section (2)
    assert(firstChanged >= module1.sections.length - 1,
      s"expected only last section changed, got firstChanged=$firstChanged")

    checkpoints = interp.runSectionsIncremental(module1.sections, firstChanged, checkpoints)
    val out = buf.toString("UTF-8")
    // The earlier sections (Lib + Math) were skipped; Output section re-ran
    assert(out.contains("Hello world"), s"output section should re-run: $out")
