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

  // Plugin-registry snapshot taken once after loadAll (beforeAll); restored before EACH
  // conformance test so runtime registrations (databases, cells, namespaces, dataset/effect
  // executors) a case installs cannot leak into the NEXT test's shared JVM. Mirrors BatchCli.
  // Without it, a case running a stateful runtime (async / dataset / distributed) order-
  // dependently poisoned a LATER pure test (e.g. html-dsl) — FrontendBridge.resetState() alone
  // did not cover the V2PluginRegistry runtime state.
  private var registrySnap: V2PluginRegistry.Snapshot = null

  // Tests that spawn non-daemon threads (actors, async), require network,
  // need external storage, or are JS/browser-only.
  private val skipSet: Set[String] = Set(
    // actors — launch non-daemon thread pools, JVM hangs
    "actors-bounded-mailbox", "actors-cluster-discovery", "actors-cluster-isdown",
    "actors-cluster-self-health", "actors-distributed-basic", "actors-global-registry",
    "actors-leader-protocol", "actors-phi-accrual", "actors-process-info",
    "actors-supervision", "cluster-connect",
    // async / coroutines — long-poll, may hang
    "async-parallel-io", "async-recv-from",
    "coroutine-basic", "coroutine-error",
    // dataset / distributed — free-monad executor → infinite loop
    "dataset-agg", "dataset-error", "dataset-from-file", "dataset-from-generator",
    "dataset-groupBy", "dataset-map-filter", "dataset-of", "dataset-parallel-jvm", "dataset-reduce", "dataset-shape", "dataset-zip",
    "distributed-failure-partial", "distributed-failure-retry",
    // network / external services
    "http-client", "ws-client",
    // NOTE: webauthn-server-verify is PURE crypto (challenge + garbage-reject, no network) — it passes on
    // v2 via FrontendBridge, so it is intentionally NOT skipped (verified byte-exact vs expected/).
    "mcp-client-invoke", "mcp-server-resource", "mcp-server-tool",
    // storage (filesystem, not available in batch test)
    // JS / browser-only
    // NOTE (2026-07-11 QA un-skip): js-applyunary-effect-cps / js-cps-intrinsic-rewrite /
    // js-crypto-extern-standalone now execute byte-exact on the v2 VM via FrontendBridge
    // (audited vs expected/), so they are UN-SKIPPED here for extra v2-VM coverage; the JS
    // lane still owns them too.
    "sql-browser-basic", "node-basic", "dsl-multi-pass",
    // tkv2-typed-client-derived is backends:[js]: its `@side=client` block calls
    // `awaitClient(...)`, a JS-lane intrinsic, so the v2 VM (which this harness
    // runs) reports "unbound global: awaitClient". The JS lane covers it
    // (JsGenTypedRouteClientTest). Same rationale as the js-* cases above.
    "tkv2-typed-client-derived",
    // UI / signals / content toolkit (requires frontend runtime)
    // NOTE (2026-07-11 QA un-skip): most content-* now render byte-exact on the v2 VM via
    // FrontendBridge (frontend runtime on the Test cp; audited vs expected/) and are UN-SKIPPED.
    // content-linked-namespaces stays skipped — it passes in isolation but FAILS in the
    // sequential harness (cross-test namespace/state dependency). `signals` needs the reactive
    // frontend runtime. std-ui-jobpanel needs the nativeui frontend intrinsic
    // (__ssc_nativeui_v1.fetchActionWith), absent from this harness's cp — same as the other
    // std-ui-* below; it was missing from the skipSet (a pre-existing red) → added here.
    "content-linked-namespaces", "signals", "std-ui-jobpanel",
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
      // Match `ssc run` (RunV2.run): print the program's final top-level value
      // unless it is Unit. Without this, a case whose last statement is a
      // non-Unit expression (e.g. graph-edge-display ending in Graph.putEdge →
      // StoredEdge) produced no output here while `bin/ssc run` printed it.
      Runtime.run(Compiler.compile(prog), Array.empty[Value]) match
        case Value.UnitV => ()
        case other       => println(Show.show(other))
    } finally
      System.setOut(saved)
      ps.flush()
    baos.toString("UTF-8").stripTrailing()

  // Register plugins once for the entire suite.
  override def beforeAll(): Unit =
    super.beforeAll()
    PluginBridge.loadAll()
    registrySnap = V2PluginRegistry.snapshot()

  test("v2 quoted macro interpreter helper globals run computed bodies") {
    FrontendBridge.resetState()
    val src =
      """inline def plusOne(x: Int): Int = ${ plusOneImpl('x) }
        |
        |def plusOneImpl(x: Expr[Int])(using q: QuotedContext): Expr[Int] =
        |  '{ $x + 1 }
        |
        |inline def literalLabel(x: Int): String = ${ literalLabelImpl('x) }
        |
        |def literalLabelImpl(x: Expr[Int])(using q: QuotedContext): Expr[String] =
        |  "literal: " + x.asValue.getOrElse("?")
        |
        |inline def termName(x: Int): String = ${ termNameImpl('x) }
        |
        |def termNameImpl(x: Expr[Int])(using q: QuotedContext): Expr[String] =
        |  x.asTerm.name
        |
        |println(plusOne(41))
        |println(literalLabel(7))
        |println(termName(5))
        |""".stripMargin
    assert(capture(src, None) == "42\nliteral: 7\nx")
  }

  test("v2 preserves positional constructor args when named args are mixed in") {
    FrontendBridge.resetState()
    val src =
      """case class AgentEvent(kind: String, text: String = "", stop: String = "")
        |
        |val event = AgentEvent("TextDelta", text = "hello")
        |println(event.kind + ":" + event.text + ":" + event.stop)
        |""".stripMargin
    assert(capture(src, None) == "TextDelta:hello:")
  }

  test("v2 dispatches AgentSchemaInstance.decode method body") {
    FrontendBridge.resetState()
    val src =
      """case class AgentSchemaInstance(parametersJson: String, decodeAny: String => Any):
        |  def decode(argsJson: String): Any =
        |    decodeAny(argsJson)
        |
        |val schema = AgentSchemaInstance("{}", argsJson => "decoded:" + argsJson)
        |println(schema.decode("payload"))
        |""".stripMargin
    assert(capture(src, None) == "decoded:payload")
  }

  test("v2 cluster stdlib import exposes actor capability globals") {
    FrontendBridge.resetState()
    val src =
      """# Cluster capability import
        |
        |[ClusterCapability, SeedResolver, codeIdentity, clusterOf, assertCodeIdentity](../runtime/std/cluster/index.ssc)
        |
        |```scala
        |runActors {
        |  startNode("demo-node")
        |  val seeds = SeedResolver.staticList(List("ws://seed:9100/_ssc-actors"))
        |  val cluster = clusterOf(seeds)
        |  val identity = codeIdentity()
        |
        |  println(cluster.localNodeId)
        |  println(cluster.resolveSeeds().head)
        |  println(identity.algorithm)
        |  println(identity.digest.length)
        |  assertCodeIdentity(identity)
        |}
        |```
        |""".stripMargin

    assert(capture(src, Some(new File(repoRoot, "examples"))) ==
      "demo-node\nws://seed:9100/_ssc-actors\nsha256\n64")
  }

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
              V2PluginRegistry.restore(registrySnap)  // isolate runtime registrations per test
              val src      = scala.io.Source.fromFile(f).mkString
              val dir      = Some(f.getParentFile)
              val expected = scala.io.Source.fromFile(expectedFile).mkString.stripTrailing()

              val resultF = Future { capture(src, dir) }
              val got = try Await.result(resultF, testTimeout)
                        catch case _: java.util.concurrent.TimeoutException =>
                          "(timeout)"

              assert(got == expected, s"\n--- expected ---\n$expected\n--- got ---\n$got")
            }
