package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.JvmGen
import scalascript.parser.Parser

import java.nio.charset.StandardCharsets

/** Regression tests for the JvmGen "conditional preamble emission" bug
 *  where modules that pattern-matched on `NodeJoined` / `NodeLeft` or
 *  called bare-name cluster intrinsics (`subscribeClusterEvents()` at
 *  top level) produced `.scjvm` artifacts that couldn't be compiled by
 *  scala-cli (unresolved refs, missing case-class extractors, or
 *  collapsed `serve(port, _TlsConfig)` overload after the linker dedup
 *  pass).
 *
 *  Code-shape tests verify the runtime helpers and the rewrite fire.
 *  Run-via-scala-cli tests confirm the produced source compiles
 *  end-to-end (skipped if scala-cli is not available). */
class JvmGenEffectsRuntimeTest extends AnyFunSuite with Matchers:

  private def module(code: String) =
    Parser.parse(s"# Test\n\n```scalascript\n$code\n```\n")

  private def jvmCode(code: String): String =
    JvmGen.generate(module(code))

  // ── Code-shape tests ───────────────────────────────────────────────

  test("JvmGen: top-level `subscribeClusterEvents()` rewrites to Actor.subscribeClusterEvents()"):
    val code = jvmCode("""
      val sub = subscribeClusterEvents()
      println(sub)
    """)
    // Bare-name call must NOT survive in the emitted source — it has
    // to be rewritten to `Actor.subscribeClusterEvents()` so the
    // runtime `object Actor:` def is what's called.
    code should not include "val sub = subscribeClusterEvents()"
    code should include ("Actor.subscribeClusterEvents()")
    code should include ("object Actor:")

  test("JvmGen: top-level `clusterMembers()` rewrites to Actor.clusterMembers()"):
    val code = jvmCode("""
      val members = clusterMembers()
      println(members.length)
    """)
    code should not include "val members = clusterMembers()"
    code should include ("Actor.clusterMembers()")

  test("JvmGen: pattern match on NodeJoined / NodeLeft pulls in the runtime"):
    // No call to any actor intrinsic — only pattern matches.  Used to
    // not trigger `blocksUseActors`, so `case class NodeJoined` wasn't
    // emitted and scala-cli would error "no pattern match extractor".
    val code = jvmCode("""
      def describe(e: Any): String = e match
        case NodeJoined(id)  => "joined: " + id
        case NodeLeft(id, _) => "left: " + id
        case _               => "?"
      println(describe("foo"))
    """)
    code should include ("case class NodeJoined")
    code should include ("case class NodeLeft")

  test("JvmGen: `serve` is a single def with default TLS arg (linker-dedup-safe)"):
    // Previously emitted as two overloads; the v2.0 linker's
    // same-name dedup pass would drop the 2-arg overload and the
    // remaining 1-arg `serve(port: Int)` would call into a no-longer-
    // present `serve(port, null)` overload.
    val code = jvmCode("""serve(8080)""")
    val serveDefs = "(?m)^def serve\\(".r.findAllMatchIn(code).length
    serveDefs shouldBe 1
    code should include ("tlsCfg: _TlsConfig = null.asInstanceOf[_TlsConfig]")

  test("JvmGen: `serveAsync(port)` pulls in the serve runtime"):
    // A bare `serveAsync(8080)` must trigger `blocksUseRoutes` so the
    // inlined ProxyRuntime (which defines `def serveAsync`) is in
    // scope — otherwise the generated script would call an unresolved
    // `serveAsync` symbol.
    val code = jvmCode("""serveAsync(8080)""")
    code should include ("def serveAsync(")
    // Caller-thread is freed via a virtual thread (Loom).  The
    // implementation in runtime-server-jvm/ProxyRuntime.scala names
    // the thread for ops visibility — assert on the actual launch
    // primitive so a future refactor that drops virtual threads is
    // caught.
    code should include ("Thread.ofVirtual()")
    // Plain `serve` def stays single-occurrence (linker-dedup-safe).
    val serveDefs      = "(?m)^def serve\\(".r.findAllMatchIn(code).length
    val serveAsyncDefs = "(?m)^def serveAsync\\(".r.findAllMatchIn(code).length
    serveDefs      shouldBe 1
    serveAsyncDefs shouldBe 1
    // `serveAsync` survives the codegen pass verbatim (no rewrite to
    // `Actor.serveAsync` — it's a runtime call, not an actor intrinsic).
    code should include ("serveAsync(8080)")

  test("JvmGen: `serveAsync(port, tls(cert, key))` reuses the same TLS arg shape as serve"):
    val code = jvmCode("""serveAsync(8443, tls("cert.pem", "key.pem"))""")
    code should include ("def serveAsync(")
    code should include ("tlsCfg: _TlsConfig = null.asInstanceOf[_TlsConfig]")
    code should include ("""serveAsync(8443, tls("cert.pem", "key.pem"))""")

  test("JvmGen: `onWebSocket` is a single def with default args (linker-dedup-safe)"):
    val code = jvmCode("""
      onWebSocket("/echo") { ws => () }
      serve(8080)
    """)
    val onWsDefs = "(?m)^def onWebSocket\\(".r.findAllMatchIn(code).length
    onWsDefs shouldBe 1
    // Defaulted args present
    code should include ("origins:           List[String] = Nil")
    code should include ("maxConnections:    Int          = 0")

  // ── Run-via-scala-cli tests ────────────────────────────────────────

  private lazy val hasScalaCli: Boolean =
    try ProcessBuilder("scala-cli", "--version").start().waitFor() == 0
    catch case _: Throwable => false

  private def compileWithScalaCli(code: String): Int =
    val sc = jvmCode(code)
    val tmp = java.io.File.createTempFile("ssc-jvmgen-effects-", ".sc")
    tmp.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, sc.getBytes(StandardCharsets.UTF_8))
    val proc = ProcessBuilder("scala-cli", "compile", tmp.getAbsolutePath)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start()
    proc.waitFor()

  test("JvmGen: scala-cli compiles a module with top-level subscribeClusterEvents()"):
    assume(hasScalaCli, "scala-cli not available")
    compileWithScalaCli("""
      val sub = subscribeClusterEvents()
      spawn { () =>
        receive {
          case NodeJoined(id)  => println("joined: " + id)
          case NodeLeft(id, r) => println("left: " + id + " (" + r + ")")
        }
      }
      println("done")
    """) shouldBe 0

  test("JvmGen: scala-cli compiles a module that only pattern-matches NodeJoined/NodeLeft"):
    assume(hasScalaCli, "scala-cli not available")
    compileWithScalaCli("""
      def describe(e: Any): String = e match
        case NodeJoined(id)  => "joined: " + id
        case NodeLeft(id, _) => "left: " + id
        case _               => "?"
      println(describe("foo"))
    """) shouldBe 0

  test("JvmGen: scala-cli compiles a bare `serve(8080)` module"):
    assume(hasScalaCli, "scala-cli not available")
    compileWithScalaCli("""serve(8080)""") shouldBe 0

  test("JvmGen: scala-cli compiles a bare `serveAsync(8080)` module"):
    // The whole point of `serveAsync` (cluster-raft.md §9): a codegen-
    // built node binds a WS port AND keeps running the actor scheduler
    // on the caller thread.  Verify the emitted Scala compiles —
    // signature mismatches in `ProxyRuntime.serveAsync` would surface
    // here as a scala-cli compile failure.
    assume(hasScalaCli, "scala-cli not available")
    compileWithScalaCli("""serveAsync(8080)""") shouldBe 0

  test("JvmGen: scala-cli compiles a `serveAsync(port, tls(...))` module"):
    assume(hasScalaCli, "scala-cli not available")
    compileWithScalaCli("""serveAsync(8443, tls("cert.pem", "key.pem"))""") shouldBe 0
