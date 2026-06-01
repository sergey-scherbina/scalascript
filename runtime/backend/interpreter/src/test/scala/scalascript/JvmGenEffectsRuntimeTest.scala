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

  private def jvmCodeDoc(source: String): String =
    JvmGen.generate(Parser.parse(source))

  private def tailFile(f: java.io.File, n: Int = 80): String =
    if f.exists() then
      val lines = java.nio.file.Files.readAllLines(f.toPath).toArray.toList.map(String.valueOf)
      lines.takeRight(n).mkString("\n")
    else ""

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

  test("JvmGen: top-level `setClusterAuthToken()` rewrites to Actor.setClusterAuthToken()"):
    val code = jvmCode("""
      setClusterAuthToken("s3cret")
      println("done")
    """)
    code should include ("""Actor.setClusterAuthToken("s3cret")""")
    code should include ("def setClusterAuthToken(token: Any)")
    code should include ("case \"setClusterAuthToken\"")

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

  test("JvmGen: HTTP server runtime registers OpenAPI defaults"):
    val code = jvmCode("""serve(8080)""")
    code should include ("def _registerOpenApiDefaults()")
    code should include ("/_openapi.json")
    code should include ("/_swagger")

  test("JvmGen: front-matter routes pass responseType metadata to OpenAPI"):
    val code = jvmCodeDoc("""---
      |routes:
      |  - method: GET
      |    path: /users/:id
      |    handler: getUser
      |apiClients:
      |  - name: Api
      |    endpoints:
      |      - name: getUser
      |        method: GET
      |        path: /users/:id
      |        request: Unit
      |        response: User
      |---
      |
      |# API
      |
      |```scalascript
      |case class User(id: String)
      |def getUser(req: Request): Response = Response.json(User("u1"))
      |serve(8080)
      |```
      |""".stripMargin)
    code should include ("""_ssc_route_response("GET", "/users/:id", "User")""")

  test("JvmGen: raw front-matter routes keep generic OpenAPI fallback"):
    val code = jvmCodeDoc("""---
      |routes:
      |  - method: GET
      |    path: /users/:id
      |    handler: getUser
      |---
      |
      |# API
      |
      |```scalascript
      |case class User(id: String)
      |def getUser(req: Request): Response = Response.json(User("u1"))
      |serve(8080)
      |```
      |""".stripMargin)
    code should include ("""route("GET", "/users/:id")""")
    code should not include """_ssc_route_response("GET", "/users/:id""""

  test("JvmGen: @openapi route annotation emits runtime metadata marker"):
    val code = jvmCodeDoc("""# API
      |
      |[route, serve, Response, Request](std/http.ssc)
      |[openapi](std/openapi.ssc)
      |
      |```scalascript
      |@openapi(summary = "Get user", description = "Fetch a user.", tags = List("users"), deprecated = true)
      |route("GET", "/users/:id") { req => Response.text(req.params("id")) }
      |serve(8080)
      |```
      |""".stripMargin)
    code should include ("""openapi("Get user", "Fetch a user.", List("users"), true, List())""")
    code should include ("""route("GET", "/users/:id")""")

  test("JvmGen: @openapi security metadata and openApiSecurity emit runtime calls"):
    val code = jvmCodeDoc("""# API
      |
      |[route, serve, Response, Request](std/http.ssc)
      |[openapi, openApiSecurity](std/openapi.ssc)
      |
      |```scalascript
      |openApiSecurity("bearerAuth", "bearer", "JWT")
      |@openapi(security = List("bearerAuth"))
      |route("DELETE", "/users/:id") { req => Response.status(204) }
      |serve(8080)
      |```
      |""".stripMargin)
    code should include ("""openApiSecurity("bearerAuth", "bearer", "JWT")""")
    code should include ("""openapi("", "", List(), false, List("bearerAuth"))""")

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

  // ── /_ssc-cluster/* operational routes ─────────────────────────────
  // The interpreter auto-registers five Bearer-gated HTTP endpoints on
  // `startNode`; the JVM codegen path must do the same so the CLI ops
  // commands (`ssc cluster status / drain / events / step-down /
  // metrics-prom`) reach codegen-built nodes.  See
  // docs/cluster-codegen-gap.md gap #3.
  test("JvmGen: startNode emits the five /_ssc-cluster/* route registrations"):
    val code = jvmCode("""
      runActors {
        startNode("n1", "ws://127.0.0.1:0/_ssc-actors")
      }
    """)
    // All five register-helpers are defined in the runtime…
    code should include ("def _registerClusterStatusRoute()")
    code should include ("def _registerClusterDrainRoute()")
    code should include ("def _registerClusterEventsRoute()")
    code should include ("def _registerClusterStepDownRoute()")
    code should include ("def _registerClusterMetricsPromRoute()")
    // …and all five are invoked inside the startNode handler.
    code should include ("_registerClusterStatusRoute()")
    code should include ("_registerClusterDrainRoute()")
    code should include ("_registerClusterEventsRoute()")
    code should include ("_registerClusterStepDownRoute()")
    code should include ("_registerClusterMetricsPromRoute()")
    // Each helper installs the right HTTP method + path via `route()`.
    code should include ("route(\"GET\", path)")
    code should include ("route(\"POST\", path)")
    code should include ("\"/_ssc-cluster/status\"")
    code should include ("\"/_ssc-cluster/drain\"")
    code should include ("\"/_ssc-cluster/events\"")
    code should include ("\"/_ssc-cluster/step-down\"")
    code should include ("\"/_ssc-cluster/metrics-prom\"")
    // Bearer-token gate is shared across all five.
    code should include ("def _clusterAuthReject(req: Request)")
    code should include ("SSC_CLUSTER_TOKEN")
    // Idempotency guard: subsequent `startNode` calls must be no-ops.
    code should include ("_routes.exists(r => r.method ==")
    // Event ring buffer prerequisite for /_ssc-cluster/events.
    code should include ("_clusterEventLog")
    code should include ("_recordEventLog")

  test("JvmGen: scala-cli compiles a startNode+serveAsync module with cluster routes"):
    // End-to-end: the emitted Scala compiles with scala-cli.  Mirrors
    // the existing `serveAsync(8080)` e2e — failures here surface as
    // type errors in the embedded route handlers, escape-sequence
    // mismatches, or unresolved references between the emitted
    // _registerCluster* helpers and the inlined REST runtime.
    assume(hasScalaCli, "scala-cli not available")
    compileWithScalaCli("""
      runActors {
        startNode("n1", "ws://127.0.0.1:0/_ssc-actors")
        serveAsync(0)
      }
    """) shouldBe 0

  // ── Run-via-scala-cli tests ────────────────────────────────────────

  private lazy val hasScalaCli: Boolean = ProcTestUtil.commandOk("scala-cli")

  private def compileWithScalaCli(code: String): Int =
    val sc = jvmCode(code)
    val tmp = java.io.File.createTempFile("ssc-jvmgen-effects-", ".sc")
    val err = java.io.File.createTempFile("ssc-jvmgen-effects-", ".err")
    tmp.deleteOnExit()
    err.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, sc.getBytes(StandardCharsets.UTF_8))
    val proc = ProcessBuilder("scala-cli", "compile", tmp.getAbsolutePath)
      .redirectError(err)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .start()
    val exitCode = ProcTestUtil.awaitExit(proc)
    if exitCode != 0 then
      val msg = scala.io.Source.fromFile(err).mkString
      println(s"scala-cli compile failed for ${tmp.getAbsolutePath}:\n$msg")
    exitCode

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

  // ── /_ssc-cluster/* — end-to-end e2e ───────────────────────────────
  // Build a codegen-emitted node with scala-cli, spawn it, curl each
  // of the five routes, and assert the wire shape matches what the
  // CLI ops commands parse.  If this drifts, `ssc cluster status` /
  // `drain` / `events` / `step-down` / `metrics-prom` break against
  // codegen-built nodes silently.
  private def pickFreePort(): Int =
    val s = new java.net.ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p

  private def httpGet(url: String, headers: Map[String, String] = Map.empty): (Int, String) =
    val client = java.net.http.HttpClient.newHttpClient()
    val rb = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
    headers.foreach((k, v) => rb.header(k, v))
    val resp = client.send(rb.GET().build(),
      java.net.http.HttpResponse.BodyHandlers.ofString())
    (resp.statusCode(), resp.body())

  private def httpPost(url: String, body: String, headers: Map[String, String] = Map.empty): (Int, String) =
    val client = java.net.http.HttpClient.newHttpClient()
    val rb = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
    headers.foreach((k, v) => rb.header(k, v))
    val resp = client.send(
      rb.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
      java.net.http.HttpResponse.BodyHandlers.ofString())
    (resp.statusCode(), resp.body())

  /** Pick a free port, write & run the codegen bundle, poll until the
   *  HTTP listener is up, run the `body` against it, then kill the
   *  child.  Returns the body's result.  Skips the test if `scala-cli`
   *  isn't available. */
  private def withRunningNode[A](
      scriptBody: Int => String)(body: Int => A): A =
    assume(hasScalaCli, "scala-cli not available")
    val port = pickFreePort()
    val sc   = jvmCode(scriptBody(port))
    val tmp  = java.io.File.createTempFile("ssc-jvmgen-cluster-e2e-", ".sc")
    val out  = java.io.File.createTempFile("ssc-jvmgen-cluster-e2e-", ".out")
    val err  = java.io.File.createTempFile("ssc-jvmgen-cluster-e2e-", ".err")
    tmp.deleteOnExit()
    out.deleteOnExit()
    err.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, sc.getBytes(StandardCharsets.UTF_8))
    val proc = ProcessBuilder("scala-cli", "run", tmp.getAbsolutePath)
      .redirectOutput(out)
      .redirectError(err)
      .start()
    try
      // Poll for the listener — `serveAsync` returns immediately but
      // the bind happens on a virtual thread; scala-cli may also need
      // a cold compile when the generated runtime changes.
      val deadline = System.currentTimeMillis() + 60_000L
      var up = false
      while !up && System.currentTimeMillis() < deadline do
        try
          val s = new java.net.Socket()
          s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 250)
          s.close()
          up = true
        catch case _: Throwable => Thread.sleep(250)
      if !up then
        def tail(f: java.io.File): String =
          if f.exists() then
            val lines = java.nio.file.Files.readAllLines(f.toPath).toArray.toList.map(String.valueOf)
            lines.takeRight(80).mkString("\n")
          else ""
        fail(s"node didn't bind 127.0.0.1:$port within 60 s\nstdout:\n${tail(out)}\nstderr:\n${tail(err)}")
      body(port)
    finally
      proc.destroyForcibly()
      proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS): @scala.annotation.unused

  /** `.ssc` snippet that keeps the JVM-codegen actor scheduler alive
   *  long enough to receive HTTP requests.  Uses a periodic timer
   *  (`sendInterval`) so `_timers.nonEmpty` stays true and the
   *  scheduler loop doesn't exit — the captured-once `_isDistributed`
   *  flag in the emitted runtime is `false` at scheduler-loop entry
   *  (set lazily inside `Actor.startNode` after the val is bound),
   *  so we can't rely on the distributed clause to hold the loop.
   *  See MILESTONES.md — this is a known follow-up. */
  private def aliveBody(setup: String): String = s"""
    runActors {
      $setup
      spawn { () =>
        val s = self()
        sendInterval(500L, s, "tick")
        receive { case "tick" => () }
        receive { case "tick" => () }  // never reached if test kills us first
      }
    }
  """

  test("JvmGen e2e: GET /_ssc-cluster/status returns the documented JSON shape"):
    withRunningNode { port =>
      aliveBody(s"""
        startNode("e2e-status", "ws://127.0.0.1:$port/_ssc-actors")
        electLeader()
        serveAsync($port)
      """)
    } { port =>
      val (status, body) = httpGet(s"http://127.0.0.1:$port/_ssc-cluster/status")
      status shouldBe 200
      body should include ("\"nodeId\":\"e2e-status\"")
      body should include ("\"leader\":\"e2e-status\"")
      body should include ("\"protocol\":\"bully\"")
      body should include ("\"members\":[]")
      body should include ("\"drainingSelf\":false")
      body should include ("\"drainingPeers\":[]")
      body should include ("\"raftTerm\":0")
      body should include ("\"raftState\":\"follower\"")
    }

  test("JvmGen e2e: GET /_openapi.json exposes user routes"):
    withRunningNode { port =>
      s"""
        route("GET", "/hello/:name") { req => Response.json(Map("ok" -> true)) }
        serve($port)
      """
    } { port =>
      val (status, body) = httpGet(s"http://127.0.0.1:$port/_openapi.json")
      status shouldBe 200
      body should include ("\"openapi\": \"3.1.0\"")
      body should include ("\"/hello/{name}\"")
      body should include ("\"get\"")
      body should not include "\"/_health\""
    }

  test("JvmGen e2e: GET /_openapi.json exposes front-matter response schema"):
    assume(hasScalaCli, "scala-cli not available")
    val port = pickFreePort()
    val sc = jvmCodeDoc(s"""---
      |routes:
      |  - method: GET
      |    path: /users/:id
      |    handler: getUser
      |apiClients:
      |  - name: Api
      |    endpoints:
      |      - name: getUser
      |        method: GET
      |        path: /users/:id
      |        request: Unit
      |        response: User
      |---
      |
      |# API
      |
      |```scalascript
      |case class User(id: String)
      |def getUser(req: Request): Response = Response.json(User(req.params("id")))
      |serve($port)
      |```
      |""".stripMargin)
    val tmp  = java.io.File.createTempFile("ssc-jvmgen-openapi-schema-", ".sc")
    val out  = java.io.File.createTempFile("ssc-jvmgen-openapi-schema-", ".out")
    val err  = java.io.File.createTempFile("ssc-jvmgen-openapi-schema-", ".err")
    tmp.deleteOnExit(); out.deleteOnExit(); err.deleteOnExit()
    java.nio.file.Files.write(tmp.toPath, sc.getBytes(StandardCharsets.UTF_8))
    val proc = ProcessBuilder("scala-cli", "run", tmp.getAbsolutePath)
      .redirectOutput(out)
      .redirectError(err)
      .start()
    try
      val deadline = System.currentTimeMillis() + 60_000L
      var up = false
      while !up && System.currentTimeMillis() < deadline do
        try
          val s = new java.net.Socket()
          s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 250)
          s.close()
          up = true
        catch case _: Throwable => Thread.sleep(250)
      if !up then fail(s"schema test server did not bind\nstdout:\n${tailFile(out)}\nstderr:\n${tailFile(err)}")
      val (status, body) = httpGet(s"http://127.0.0.1:$port/_openapi.json")
      status shouldBe 200
      body should include (""""/users/{id}"""")
      body should include (""""content": { "application/json": { "schema": {"type":"object"} } }""")
    finally
      proc.destroyForcibly()
      proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS): @scala.annotation.unused

  test("JvmGen e2e: POST /_ssc-cluster/drain toggles drainingSelf"):
    withRunningNode { port =>
      aliveBody(s"""
        startNode("e2e-drain", "ws://127.0.0.1:$port/_ssc-actors")
        serveAsync($port)
      """)
    } { port =>
      val (s1, b1) = httpPost(s"http://127.0.0.1:$port/_ssc-cluster/drain", "")
      s1 shouldBe 200
      b1 should include ("\"drainingSelf\":true")

      val (sStat, bStat) = httpGet(s"http://127.0.0.1:$port/_ssc-cluster/status")
      sStat shouldBe 200
      bStat should include ("\"drainingSelf\":true")

      val (s2, b2) = httpPost(s"http://127.0.0.1:$port/_ssc-cluster/drain",
        "{\"enabled\":false}")
      s2 shouldBe 200
      b2 should include ("\"drainingSelf\":false")
    }

  test("JvmGen e2e: GET /_ssc-cluster/events returns a JSON array"):
    withRunningNode { port =>
      aliveBody(s"""
        startNode("e2e-events", "ws://127.0.0.1:$port/_ssc-actors")
        electLeader()      // emit a LeaderElected event
        serveAsync($port)
      """)
    } { port =>
      val (status, body) = httpGet(s"http://127.0.0.1:$port/_ssc-cluster/events")
      status shouldBe 200
      body.startsWith("[") shouldBe true
      body.endsWith("]")   shouldBe true
      // electLeader on a single node fires a LeaderElected event.
      body should include ("\"type\":\"LeaderElected\"")
      body should include ("\"nodeId\":\"e2e-events\"")
    }

  test("JvmGen e2e: POST /_ssc-cluster/step-down works on leader, 409 on follower"):
    withRunningNode { port =>
      aliveBody(s"""
        startNode("e2e-stepdown", "ws://127.0.0.1:$port/_ssc-actors")
        electLeader()
        serveAsync($port)
      """)
    } { port =>
      val (s1, b1) = httpPost(s"http://127.0.0.1:$port/_ssc-cluster/step-down", "")
      s1 shouldBe 200
      b1 should include ("\"steppedDown\":true")
      b1 should include ("\"nodeId\":\"e2e-stepdown\"")

      // Now we're a follower — second call should 409.
      val (s2, b2) = httpPost(s"http://127.0.0.1:$port/_ssc-cluster/step-down", "")
      s2 shouldBe 409
      b2 should include ("\"error\":\"not_leader\"")
    }

  test("JvmGen e2e: GET /_ssc-cluster/metrics-prom returns Prometheus exposition format"):
    withRunningNode { port =>
      aliveBody(s"""
        startNode("e2e-metrics", "ws://127.0.0.1:$port/_ssc-actors")
        clusterMetricSet("requests_total", 42.0)
        serveAsync($port)
      """)
    } { port =>
      val (status, body) = httpGet(s"http://127.0.0.1:$port/_ssc-cluster/metrics-prom")
      status shouldBe 200
      body should include ("# TYPE requests_total gauge")
      body should include ("requests_total{nodeId=\"e2e-metrics\"} 42.0")
    }
