package scalascript.uniml.bench

import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations.*
import scalascript.uniml.*
import scalascript.uniml.dialect.scalascript.SpikeDialect
import scalascript.uniml.ssc.SscCompose

/** SSC3-M: UniML's arm of the front-end measurement.
  *
  * Settings mirror v1's `ParserBench` exactly — 3 warmup x 5 measurement x 1 fork, average time in
  * milliseconds — so the two are comparable despite living in different builds
  * (`specs/uniml-ssc3-frontend.md` §4.2b explains why they cannot share one).
  *
  * TWO arms, because they answer different questions and §4.2 conflated them:
  *   - [[parseComposed]] is what v1's `ParserBench` does: the whole literate `.ssc`, markdown and
  *     all. This is the apples-to-apples ratio.
  *   - [[parseDialect]] is the bare ScalaScript dialect over the same bytes, which is what §4.2
  *     measured back when the dialect was NOT lossless. Kept so the two readings are comparable
  *     across that change.
  *
  * A number here is meaningless without the parse's STATUS. §4.2's first reading was 14.18 MB/s
  * and was measuring a parse that bailed out with 85 diagnostics — it rewarded the parser for not
  * working. `sbt "unimlBench/Jmh/run -i 1 -wi 0 -f 1 .*describe.*"` prints the shape of both
  * parses; run it beside any timing.
  */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class SpikeParserBench:

  // Same file v1's ParserBench reads, found the same way — walk up until it appears, so the bench
  // does not depend on which directory sbt happened to start in.
  private val actorsSsc: String = SpikeParserBench.readActors

  @Benchmark
  def parseComposed(): SscCompose.Composed = SscCompose.parse(actorsSsc)

  @Benchmark
  def parseDialect(): ParseResult =
    UniML.parse(SourceInput.fromString(SourceId("bench:actors"), actorsSsc), SpikeDialect)

object SpikeParserBench:

  // java.nio rather than os-lib: UniML carries no third-party dependency (charter invariant I-1),
  // and a benchmark is not a reason to open that door.
  def readActors: String =
    val relative = java.nio.file.Paths.get("v1", "runtime", "std", "actors.ssc")
    val start = java.nio.file.Paths.get(sys.props("user.dir")).toAbsolutePath.normalize()
    Iterator.iterate(start)(_.getParent).takeWhile(_ != null).take(6)
      .map(_.resolve(relative))
      .find(java.nio.file.Files.isRegularFile(_))
      .map(p => new String(java.nio.file.Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8))
      .getOrElse(sys.error("SSC3-M: cannot find v1/runtime/std/actors.ssc — the bench must measure the SAME file as v1's ParserBench"))

  /** Not a benchmark: prints what each parse actually produced, so a timing is never read without
    * the status beside it. Run with `sbt "unimlBench/runMain scalascript.uniml.bench.Describe"`. */
  def describe(): String =
    val src = readActors
    val composed = SscCompose.parse(src)
    val dialect = UniML.parse(SourceInput.fromString(SourceId("bench:actors"), src), SpikeDialect)
    def nodes(n: UniNode): Int = n match
      case b: UniNode.Branch => 1 + b.edges.map(e => nodes(e.child)).sum
      case _                 => 1
    val composedNodes = nodes(composed.root)
    val dialectNodes = dialect.roots.map(nodes).sum
    s"""SSC3-M input: ${src.length} chars
       |composed  status=${composed.status} diagnostics=${composed.diagnostics.size} nodes=$composedNodes
       |dialect   status=${dialect.status} diagnostics=${dialect.diagnostics.size} nodes=$dialectNodes""".stripMargin

object Describe:
  def main(args: Array[String]): Unit = println(SpikeParserBench.describe())

/** SSC3-M's second owed number: how much heap the tree HOLDS, not how much it allocates.
  *
  * `-prof gc` answers allocation rate, which is a different question — a parser can churn and
  * retain little. This measures the live set: settle the heap, read it, build the tree, keep a
  * strong reference across a second settle, read again.
  *
  * Deliberately crude and deliberately repeated. A single reading is a hypothesis: `System.gc()` is
  * advisory and the JVM may not have finished. Five rounds are printed so a reader can see the
  * spread rather than trust one number, and the tree is kept alive past the final read with
  * `identityHashCode` so nothing can be collected early.
  */
object Retained:
  private def settle(): Long =
    var i = 0
    while i < 6 do { System.gc(); Thread.sleep(150); i += 1 }
    val rt = Runtime.getRuntime
    rt.totalMemory - rt.freeMemory

  /** Hold `k` trees and report the live set. */
  private def holdK(src: String, k: Int): Long =
    val before = settle()
    val kept = Array.fill(k)(SscCompose.parse(src))
    val after = settle()
    // Keep every tree reachable PAST the second reading, or the JVM may collect them first.
    if kept.map(t => System.identityHashCode(t)).sum == Int.MinValue then println("unreachable")
    after - before

  def main(args: Array[String]): Unit =
    val src = SpikeParserBench.readActors
    println(s"source ${src.length} chars")
    // The DIFFERENCE between holding 10 trees and holding 1 is nine trees' worth, with the fixed
    // overhead — classloading, JIT structures, the parser's own tables — cancelled out. A single
    // reading cannot separate those from the tree, which is why the naive version of this probe
    // spread 11.8x to 47.7x across five rounds and could not be reported.
    var round = 0
    while round < 5 do
      val one = holdK(src, 1)
      val ten = holdK(src, 10)
      val perTree = (ten - one) / 9.0
      println(f"round $round%d  1-tree ${one / 1024.0}%8.1f KiB   10-tree ${ten / 1024.0}%9.1f KiB   per-tree ${perTree / 1024.0}%7.1f KiB = ${perTree / src.length}%5.2f x source")
      round += 1