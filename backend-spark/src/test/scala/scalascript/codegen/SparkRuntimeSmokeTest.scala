package scalascript.codegen

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser

/** Runtime smoke-test for the Spark backend: invokes `scala-cli compile`
 *  against the source `SparkGen` emits, with the real `_2.13` Spark JARs
 *  resolved by Coursier.  Catches the class of regressions that the
 *  structural `SparkGenTest` assertions miss — "the source looks right
 *  via `code.contains(...)` but the Scala 3 compiler rejects it".
 *
 *  **Opt-in.**  By default the suite cancels every test: real
 *  `scala-cli compile` invocations are slow (10–60s per file on a warm
 *  Coursier cache, longer cold) and need network on first run.
 *  Two gates must both be satisfied to actually run:
 *
 *    1. Environment variable `RUN_SPARK_INTEGRATION=1` is set.
 *    2. `scala-cli` is on `PATH`.
 *
 *  Invoke locally:
 *
 *    RUN_SPARK_INTEGRATION=1 sbt "backendSpark/testOnly *SparkRuntimeSmoke*"
 *
 *  Coverage scope is the **working subset** under Scala 3 + Spark 2.13
 *  (see SPEC § 9.5 "Scala 3 / Spark 2.13 interop"): examples that
 *  stay on primitive `Encoder`s, SQL `VALUES` seeding, and manual
 *  Java-`UDF1` registration.  Examples exercising the broken paths
 *  (`Dataset[CaseClass]`, `@SqlFn` auto-emit) are intentionally not
 *  in scope — those become covered once Phase E (encoder derivation)
 *  ships.
 */
class SparkRuntimeSmokeTest extends AnyFunSuite:

  // sparkVersion is no longer used directly — Phase E moved dep
  // declarations into `//> using` directives baked into the source.
  // The name is left as a private val to flag the historical pin.
  @annotation.unused
  private val sparkVersion = SparkGen.DefaultVersion

  private def integrationEnabled: Boolean =
    sys.env.get("RUN_SPARK_INTEGRATION").contains("1")

  private def scalaCliOnPath: Boolean =
    sys.process.Process(List("which", "scala-cli")).! == 0

  /** Gate helper.  Tests call this first; if the integration mode isn't
   *  enabled (or scala-cli is unavailable), the test cancels.  Cancel
   *  rather than fail so a default `sbt test` invocation stays green. */
  private def requireIntegration(): Unit =
    if !integrationEnabled then
      cancel("RUN_SPARK_INTEGRATION=1 not set — opt in to invoke scala-cli + Spark JARs")
    if !scalaCliOnPath then
      cancel("scala-cli not on PATH — install via https://scala-cli.virtuslab.org/")

  /** Generate Spark Scala source from an .ssc file under `examples/`,
   *  write to a deterministic temp path, then invoke `scala-cli
   *  compile` with the real `_2.13` Spark dep coords.  Exit code 0
   *  = the emitted source is acceptable to Scala 3.8 against the
   *  Spark 4.0.0 binary surface. */
  private def compileExample(rel: String): Unit =
    requireIntegration()
    val repoRoot = locateRepoRoot()
    val src      = repoRoot / "examples" / rel
    assert(os.exists(src), s"fixture missing: $src")
    val module   = Parser.parse(os.read(src))
    val code     = SparkGen.generate(module, baseDir = Some(src / os.up))
    val hash     = java.lang.Integer.toHexString(code.hashCode & 0x7fffffff)
    val out      = os.Path(s"/tmp/ssc-smoke-${rel.replaceAll("[^a-zA-Z0-9]", "_")}-$hash.scala")
    os.write.over(out, code)
    // Phase E (v1.25 § 9.5): the emitted source carries `//> using
    // scala`, `//> using dep`, and `//> using javaOpt` directives —
    // scala-cli reads everything from the file itself, so the test
    // invocation is identical to what a user runs locally:
    //   scala-cli compile <file>
    val cmd = List("scala-cli", "compile", out.toString)
    val res = sys.process.Process(cmd).!
    assert(res == 0,
      s"scala-cli compile of generated $rel source failed (exit $res); inspect $out")

  /** Walk up from cwd looking for the repo root — handles both running
   *  in the main checkout and inside a `.claude/worktrees/<branch>`
   *  worktree, where `examples/` is at the worktree root not cwd. */
  private def locateRepoRoot(): os.Path =
    def hasExamples(p: os.Path): Boolean = os.exists(p / "examples" / "word-count.ssc")
    LazyList
      .iterate(os.pwd)(_ / os.up)
      .takeWhile(p => p.toString != "/")
      .find(hasExamples)
      .getOrElse(fail(s"could not locate repo root from ${os.pwd}"))

  // ── Working-subset examples (Scala 3 + Spark 2.13 compatible) ────────────

  test("word-count.ssc compiles under scala-cli + Spark _2.13") {
    compileExample("word-count.ssc")
  }

  test("spark-sql-demo.ssc compiles under scala-cli + Spark _2.13") {
    compileExample("spark-sql-demo.ssc")
  }

  test("spark-config-demo.ssc compiles under scala-cli + Spark _2.13") {
    compileExample("spark-config-demo.ssc")
  }

  test("spark-encoder-demo.ssc compiles under Phase E derivation") {
    // Phase E demo — exercises `Dataset[CaseClass]` via the inline
    // `SscSparkEncoders.derived[User]` given.  If this test passes,
    // the Mirror-based encoder derivation is wired end-to-end.
    compileExample("spark-encoder-demo.ssc")
  }

  test("spark-nested-demo.ssc compiles under Phase E Option + nested derivation") {
    // Exercises the recursive `aenc_Option[U]` + `aenc_Product[T]`
    // path: `Person` has an `Option[Int]` field plus a nested
    // `Address` case class.  Both resolve via `summonInline` during
    // derivation — if this test passes, the recursive Mirror walk
    // works for both Option and nested case classes.
    compileExample("spark-nested-demo.ssc")
  }

  test("spark-collections-demo.ssc compiles under Phase E collection derivation") {
    // Exercises `aenc_Seq`/`aenc_List`/`aenc_Map` — Post has a Seq
    // of String tags, a List of Int scores, and a Map<String,String>
    // meta field.  If this compiles, IterableEncoder + MapEncoder
    // routing through `summonInline[AgnosticEncoder[t]]` is wired
    // correctly for all three collection categories.
    compileExample("spark-collections-demo.ssc")
  }

  test("spark-tuple-demo.ssc compiles under Phase E tuple-as-field support") {
    // Tuples (Tuple2, Tuple3, ...) work for free under Phase E —
    // Scala 3 synthesises Mirror.ProductOf[(A, B, ...)] and the
    // existing `aenc_Product[T <: Product]` given handles them.
    // No tuple-specific shim code, just verify the path holds.
    compileExample("spark-tuple-demo.ssc")
  }

  test("spark-udf-demo.ssc compiles under scala-cli + Spark _2.13") {
    // This example uses the *manual* Java `UDF1` registration form
    // documented in SPEC § 9.5 — not the `@SqlFn` auto-emit (blocked
    // on Phase E encoder derivation).  Once Phase E lands, swap the
    // example back to `@SqlFn` and tighten this test.
    compileExample("spark-udf-demo.ssc")
  }

  // ── Lakehouse formats — gated separately per format ──────────────────────
  //
  // Each lakehouse format (Delta / Iceberg / Hudi) ships its own runtime
  // JARs through `//> using dep`; resolving them adds 50-150MB to a cold
  // Coursier cache.  Many CI environments pre-cache only the base Spark
  // dep set, so each format gets a separate opt-in gate
  // (`RUN_SPARK_DELTA=1`, `RUN_SPARK_ICEBERG=1`, `RUN_SPARK_HUDI=1`) on
  // top of the base `RUN_SPARK_INTEGRATION=1` gate.  A missing format
  // JAR never breaks unrelated smoke tests.

  private def requireDelta(): Unit =
    requireIntegration()
    if !sys.env.get("RUN_SPARK_DELTA").contains("1") then
      cancel("RUN_SPARK_DELTA=1 not set — opt in to resolve the Delta Lake runtime JAR")

  test("spark-delta-demo.ssc compiles under scala-cli + Spark _2.13") {
    // Lakehouse track L.2 — exercises the auto-emitted Delta dep
    // (`io.delta:delta-spark_2.13:<v>`) and the SparkSession.builder()
    // config wiring (`spark.sql.extensions` + `spark.sql.catalog.spark_catalog`).
    // If this test passes, the .format("delta") path in user `.ssc` source
    // compiles end-to-end without any front-matter or CLI override.
    requireDelta()
    compileExample("spark-delta-demo.ssc")
  }

  // ── Phase F: Structured Streaming smoke tests ────────────────────────────

  test("spark-streaming-rate-console.ssc compiles under Phase F.2") {
    // Smallest end-to-end streaming example: rate source -> console
    // sink with a 1-second processing-time trigger.  Exercises the
    // auto-emitted `awaitTermination()` shim, the streaming imports
    // (Trigger, OutputMode), and the unmodified passthrough of
    // `spark.readStream` / `.writeStream` from user code.  No
    // external deps beyond spark-core / spark-sql.
    compileExample("spark-streaming-rate-console.ssc")
  }

  test("spark-streaming-file-parquet.ssc compiles under Phase F.3") {
    // Streaming with a file sink — exercises the
    // checkpointLocation guidance comment emission AND the
    // suppression-when-already-present path (the example does
    // set `checkpointLocation` so the hint stays out of the
    // generated source).  The user code uses StructType / IntegerType
    // / StringType etc., which the existing `sparkImports` already
    // brings in via `types._`.
    compileExample("spark-streaming-file-parquet.ssc")
  }
