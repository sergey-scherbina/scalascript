package ssc.bridge

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterAll
import ssc.*
import java.io.{ByteArrayOutputStream, File, PrintStream}
import scala.concurrent.{Future, Await}
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

// Runs tests/conformance/ through v2 FrontendBridge and compares stdout
// against tests/conformance/expected/. Skips actor/network/dataset tests.
// Run with: sbt v2FrontendBridge/test  (timeout per test: 15 seconds)
class V2ConformanceTest extends AnyFunSuite, BeforeAndAfterAll:

  // Locate the repo root by walking up from cwd to find build.sbt.
  private val repoRoot: File =
    Iterator.iterate(new File(".").getAbsoluteFile)(_.getParentFile)
      .takeWhile(f => f != null && f.getParentFile != f)
      .find(f => new File(f, "build.sbt").exists())
      .getOrElse(new File(".").getAbsoluteFile)

  private val conformanceDir  = new File(repoRoot, "tests/conformance")
  private val expectedDir     = new File(conformanceDir, "expected")

  // Tests that spawn non-daemon threads (actors, async), require network,
  // need external storage, or are JS/browser-only.
  private val skipSet: Set[String] = Set(
    // actors — launch non-daemon thread pools, JVM hangs
    "actors-bounded-mailbox", "actors-cluster-discovery", "actors-cluster-isdown",
    "actors-cluster-self-health", "actors-distributed-basic", "actors-global-registry",
    "actors-leader-protocol", "actors-phi-accrual", "actors-process-info",
    "actors-supervision", "cluster-connect",
    // async / coroutines — long-poll, may hang
    "async", "async-parallel", "async-parallel-io", "async-recv-from",
    "coroutine-basic", "coroutine-error",
    // dataset / distributed — free-monad executor → infinite loop
    "dataset-agg", "dataset-error", "dataset-from-file", "dataset-from-generator",
    "dataset-groupBy", "dataset-map-filter", "dataset-of", "dataset-parallel-int",
    "dataset-parallel-jvm", "dataset-reduce", "dataset-shape", "dataset-sortBy",
    "dataset-top", "dataset-union-intersect", "dataset-zip",
    "distributed-failure-partial", "distributed-failure-retry",
    "distributed-heterogeneous", "distributed-map", "distributed-shuffle",
    // network / external services
    "http-client", "tls-smoke", "ws-client", "rest-validate",
    // NOTE: webauthn-server-verify is PURE crypto (challenge + garbage-reject, no network) — it passes on
    // v2 via FrontendBridge, so it is intentionally NOT skipped (verified byte-exact vs expected/).
    "mcp-client-invoke", "mcp-server-resource", "mcp-server-tool",
    // storage (filesystem, not available in batch test)
    "storage",
    // JS / browser-only
    "js-applyunary-effect-cps", "js-cps-intrinsic-rewrite", "js-crypto-extern-standalone",
    "sql-browser-basic", "node-basic", "dsl-multi-pass",
    // UI / signals / content toolkit (requires frontend runtime)
    "content", "content-introspection", "content-linked-namespaces",
    "content-tables", "content-to-markdown",
    "signals", "html-dsl",
    "std-ui-aggregator", "std-ui-extended", "std-ui-extended-b", "std-ui-extended-c",
    "std-ui-extended-d", "std-ui-i18n",
  )

  private val testTimeout = 15.seconds

  // Capture stdout produced by the v2 runtime when executing src.
  private def capture(src: String, dir: Option[File]): String =
    val baos = new ByteArrayOutputStream
    val ps   = new PrintStream(baos, /*autoFlush=*/true, "UTF-8")
    val saved = System.out
    System.setOut(ps)
    try Console.withOut(ps) {
      val prog = FrontendBridge.convertSource(src, dir)
      Runtime.run(Compiler.compile(prog), Array.empty[Value])
    } finally
      System.setOut(saved)
      ps.flush()
    baos.toString("UTF-8").stripTrailing()

  // Register plugins once for the entire suite.
  override def beforeAll(): Unit =
    super.beforeAll()
    PluginBridge.loadAll()

  // Dynamically build one test per conformance file that has an expected file.
  locally:
    if !conformanceDir.exists() then
      test("conformance-dir-missing") { fail(s"$conformanceDir not found") }
    else
      val sscFiles = conformanceDir.listFiles()
        .filter(_.getName.endsWith(".ssc"))
        .sortBy(_.getName)

      for f <- sscFiles do
        val slug = f.getName.stripSuffix(".ssc")
        val expectedFile = new File(expectedDir, s"$slug.txt")
        if expectedFile.exists() then
          if skipSet.contains(slug) then
            ignore(s"v2-conformance: $slug") {}
          else
            test(s"v2-conformance: $slug") {
              FrontendBridge.resetState()
              val src      = scala.io.Source.fromFile(f).mkString
              val dir      = Some(f.getParentFile)
              val expected = scala.io.Source.fromFile(expectedFile).mkString.stripTrailing()

              val resultF = Future { capture(src, dir) }
              val got = try Await.result(resultF, testTimeout)
                        catch case _: java.util.concurrent.TimeoutException =>
                          "(timeout)"

              assert(got == expected, s"\n--- expected ---\n$expected\n--- got ---\n$got")
            }
