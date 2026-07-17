package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

/** Tier 4 multi-backend cluster matrix test: a cluster of ONE
 *  JVM-codegen-compiled node and ONE JS-Node-codegen-compiled node, both
 *  emitted from the same `.ssc` source, joined over real WS, converging on
 *  a single Bully leader.  Convergence is asserted by polling
 *  `GET /_ssc-cluster/status` on both ports until the `leader` field on
 *  both nodes is non-empty and equal.
 *
 *  This is the headline Tier 4 deliverable from
 *  [`specs/cluster-codegen-gap.md`](../../../../../../../specs/cluster-codegen-gap.md):
 *  if this passes, multi-backend deployment is real — the peer envelopes
 *  that JVM-codegen and JS-codegen nodes emit on the `_ssc-actors` WS link
 *  are byte-compatible.
 *
 *  Sister tests in this directory:
 *
 *   - [[ClusterBullyStatusConvergenceTest]] — 2 nodes, both running through
 *     `java -jar ssc.jar` (i.e. the interpreter).  Same shape; same status
 *     polling; just no codegen.  This test is its codegen-only counterpart.
 *   - [[MultiNodeClusterTest]] — multi-node print-based convergence
 *     (`println("LEADER:")` line-matched), all through the interpreter.
 *
 *  Build chain for each side:
 *
 *   - **JVM-codegen side:** `ssc compile-jvm --bytecode node.ssc` produces
 *     a `.scjvm` with a base64 classBundle; `ssc link --backend jvm
 *     --bytecode artifacts -o out.jar` packs the bundle + shared runtime
 *     classBundle into one runnable JAR; `java -cp out.jar:scala-stdlib
 *     <moduleId>_sc` runs it.  This is the exact code path
 *     [[JvmBytecodeLinkCliTest]] exercises for run-tests.
 *   - **JS-codegen side:** `ssc compile-js node.ssc` produces a `.scjs`
 *     (text JS, no bytecode); `ssc link --backend js artifacts -o out.js`
 *     concatenates the runtime preamble + module source; `node out.js`
 *     runs it.  This is the exact code path
 *     [[JsRuntimeSeparationTest]] exercises for run-tests.
 *
 *  ## Enabled 2026-06-11 — JVM↔JS Bully convergence works end-to-end
 *
 *  Getting here took fixing four distinct cross-backend layers, each found by
 *  reproducing the multi-process run; all are now landed:
 *
 *  1. **JS-codegen `_runActors` scheduler block (FIXED earlier).** The original
 *     disable reason: a synchronous `while(true)` + `Atomics.wait` scheduler
 *     blocked Node's event loop. Now `async` + `setImmediate` yield
 *     (Async runtime, `async.mjs`).
 *  2. **JVM-codegen WS server didn't echo the subprotocol.** The `/_ssc-actors`
 *     route registered protocols-less, so it never sent `Sec-WebSocket-Protocol`;
 *     the JS `ws` peer client rejected the upgrade. Now
 *     `onWebSocket("/_ssc-actors", protocols = List("ssc-actors-v1"))`
 *     (`JvmGenRuntimeSources`).
 *  3. **JS-codegen WS server didn't echo the subprotocol either** (symmetric).
 *     Now `onWebSocket('/_ssc-actors', [], ['ssc-actors-v1'])(handler)`
 *     (Async runtime, `async.mjs`). With (2)+(3), JS↔JS and JVM↔JS upgrades negotiate
 *     `proto=ssc-actors-v1`.
 *  4. **JS-codegen server hung on non-WebSocket upgrades.** The test's
 *     `java.net.http` client defaults to HTTP/2 and probes cleartext with an
 *     `Upgrade: h2c` header. Node routes *any* `Connection: Upgrade` to the
 *     'upgrade' handler, so `/_ssc-cluster/status` GETs hit `_wsHandleUpgrade`,
 *     matched no WS route, and the socket hung with no response → status polls
 *     timed out (`js=` empty) even though the node was alive and the JVM peer had
 *     converged. Fixed in `JsRuntimeWsServer`: the 'upgrade' listener now serves
 *     any non-`websocket` upgrade as a normal HTTP/1.1 request (via
 *     `http.ServerResponse` over the raw socket), so HTTP/2-preferring clients
 *     fall back to 1.1.
 *
 *  Requires (else cancels gracefully): `sbt cli/assembly` + `sbt installBin`
 *  (the installed `ssc-tools` launcher and compiler jars), `node`, `npm` (`ws`
 *  installed into the sandbox), `scala-cli`, and Scala runtime libraries visible
 *  to the test JVM. */
class ClusterMultiBackendMatrixTest extends AnyFunSuite:

  // ── Installed distribution ───────────────────────────────────────────

  private def requireLauncher(): os.Path =
    StagedCliTestSupport.toolsLauncher.getOrElse:
      cancel("bin/ssc-tools not found — run `sbt cli/assembly installBin` first")

  private def requireScalaCli(): Unit =
    val res = scala.util.Try {
      os.proc("scala-cli", "--version").call(
        check = false, stderr = os.Pipe, stdout = os.Pipe)
    }
    if res.isFailure || !res.toOption.exists(_.exitCode == 0) then
      cancel("`scala-cli` not on PATH — needed for compile-jvm --bytecode")

  private def requireNode(): Unit =
    val res = scala.util.Try {
      os.proc("node", "--version").call(
        check = false, stderr = os.Pipe, stdout = os.Pipe)
    }
    if res.isFailure || !res.toOption.exists(_.exitCode == 0) then
      cancel("`node` not on PATH — needed for the JS-codegen side")

  private def requireScalaStdlib(): String =
    StagedCliTestSupport.scalaRuntimeClasspath.getOrElse:
      cancel("Scala runtime libraries are not visible to the test JVM — needed to run the JVM-codegen JAR")

  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort finally s.close()

  /** Long-running cluster-node source — identical across JVM and JS
   *  codegen.  Calls `serveAsync(port)` so the WS + HTTP server can run
   *  alongside the actor scheduler, joins the given peer seed, waits a
   *  beat for handshake completion, fires `electLeader()` and then sits
   *  in a long-armed `receive` keeping the scheduler alive.
   *
   *  The same `.ssc` body is emitted to each backend so any
   *  byte-compatibility issue between JVM and JS peer envelopes — JSON
   *  shape, framing, handshake order — surfaces as a convergence failure
   *  rather than as a source-level difference. */
  private def nodeSrc(nodeId: String, port: Int, peerUrl: String): String =
    s"""---
       |name: $nodeId
       |---
       |
       |# Bully convergence node $nodeId
       |
       |```scalascript
       |runActors {
       |  startNode("$nodeId", "ws://127.0.0.1:$port/_ssc-actors")
       |  serveAsync($port)
       |  spawn { () =>
       |    val s = self()
       |    setReconnectPolicy(300L, 1500L)
       |    sendAfter(500L, s, "join")
       |    receive { case "join" =>
       |      joinCluster(List("$peerUrl"))
       |      sendAfter(1500L, s, "elect")
       |      receive { case "elect" =>
       |        electLeader()
       |        sendAfter(30000L, s, "stop")
       |        receive { case "stop" => stop() }
       |      }
       |    }
       |  }
       |}
       |```
       |""".stripMargin

  /** Compile + link the JVM-codegen side: `compile-jvm --bytecode` then
   *  `link --backend jvm --bytecode` against the same artifact dir, then
   *  return the path to a runnable JAR. */
  private def compileJvmSide(
      sandbox: os.Path, launcher: os.Path, src: String, nodeId: String): os.Path =
    val sscFile = sandbox / s"$nodeId.ssc"
    os.write(sscFile, src)
    val artifactDir = sandbox / "artifacts-jvm"
    os.makeDir.all(artifactDir)
    val cmpRes = StagedCliTestSupport.runTools(
      launcher,
      sandbox,
      args = Seq(
        "compile-jvm", "--bytecode", sscFile.toString,
        "-o", (artifactDir / s"$nodeId.scjvm").toString))
    assert(cmpRes.exitCode == 0,
      s"compile-jvm --bytecode failed for $nodeId: exit=${cmpRes.exitCode}\n" +
        s"stdout=${cmpRes.out.text()}\nstderr=${cmpRes.err.text()}")
    val outJar = sandbox / s"$nodeId.jar"
    val linkRes = StagedCliTestSupport.runTools(
      launcher,
      sandbox,
      args = Seq(
        "link", "--backend", "jvm", "--bytecode",
        artifactDir.toString, "-o", outJar.toString))
    assert(linkRes.exitCode == 0,
      s"link --backend jvm --bytecode failed for $nodeId: exit=${linkRes.exitCode}\n" +
        s"stdout=${linkRes.out.text()}\nstderr=${linkRes.err.text()}")
    outJar

  /** Compile + link the JS-codegen side: `compile-js` then
   *  `link --backend js` against the same artifact dir, then return the
   *  path to a runnable `out.js` bundle. */
  private def compileJsSide(
      sandbox: os.Path, launcher: os.Path, src: String, nodeId: String): os.Path =
    val sscFile = sandbox / s"$nodeId.ssc"
    os.write(sscFile, src)
    val artifactDir = sandbox / "artifacts-js"
    os.makeDir.all(artifactDir)
    val cmpRes = StagedCliTestSupport.runTools(
      launcher,
      sandbox,
      args = Seq(
        "compile-js", sscFile.toString,
        "-o", (artifactDir / s"$nodeId.scjs").toString))
    assert(cmpRes.exitCode == 0,
      s"compile-js failed for $nodeId: exit=${cmpRes.exitCode}\n" +
        s"stdout=${cmpRes.out.text()}\nstderr=${cmpRes.err.text()}")
    val outJs = sandbox / s"$nodeId.js"
    val linkRes = StagedCliTestSupport.runTools(
      launcher,
      sandbox,
      args = Seq(
        "link", "--backend", "js",
        artifactDir.toString, "-o", outJs.toString))
    assert(linkRes.exitCode == 0,
      s"link --backend js failed for $nodeId: exit=${linkRes.exitCode}\n" +
        s"stdout=${linkRes.out.text()}\nstderr=${linkRes.err.text()}")
    outJs

  /** Spawn the JVM-codegen JAR as `java -cp out.jar:scala-stdlib <main>`. */
  private def spawnJvmNode(
      jar:      os.Path,
      stdlibCp: String,
      nodeId:   String,
      logFile:  java.io.File): Process =
    val mainCls = s"${nodeId.replace('-', '_')}_sc"
    val pb = new ProcessBuilder(
        "java", "-cp", s"$jar${java.io.File.pathSeparator}$stdlibCp", mainCls)
      .redirectErrorStream(true)
      .redirectOutput(logFile)
    pb.start()

  /** Ensure the JS-codegen sandbox can `require('ws')` from inside the
   *  worker thread `connectNode` spawns.  The emitted bundle doesn't
   *  ship a `package.json` (cluster is not "sql"), so we install the
   *  package on the spot.  Idempotent — re-uses an existing
   *  `node_modules/ws` if present. */
  private def requireWsModule(sandbox: os.Path): Unit =
    val nm = sandbox / "node_modules" / "ws"
    if os.exists(nm) then return
    val pkgJson = sandbox / "package.json"
    if !os.exists(pkgJson) then
      os.write(pkgJson, """{"name":"ssc-matrix","private":true,"dependencies":{"ws":"^8.0.0"}}""")
    val inst = os.proc("npm", "install", "--no-audit", "--no-fund", "--silent").call(
      cwd = sandbox, check = false, stderr = os.Pipe, stdout = os.Pipe)
    if inst.exitCode != 0 then
      cancel(s"npm install ws failed:\nstdout=${inst.out.text()}\nstderr=${inst.err.text()}")

  /** Spawn the JS-codegen bundle as `node out.js`.  Runs with the
   *  sandbox as the working directory so Node's CJS resolver finds
   *  `node_modules/ws` installed by `requireWsModule`. */
  private def spawnJsNode(jsFile: os.Path, logFile: java.io.File): Process =
    val pb = new ProcessBuilder("node", jsFile.toString)
      .directory(jsFile.toIO.getParentFile)
      .redirectErrorStream(true)
      .redirectOutput(logFile)
    pb.start()

  // ── HTTP polling helpers (same shape as `ClusterBullyStatusConvergenceTest`)

  private def jsonStringField(body: String, key: String): String =
    val needle = "\"" + key + "\":\""
    val i = body.indexOf(needle)
    if i < 0 then ""
    else
      val start = i + needle.length
      val end   = body.indexOf("\"", start)
      if end < 0 then "" else body.substring(start, end)

  private def jsonStringArray(body: String, key: String): List[String] =
    val needle = "\"" + key + "\":["
    val i = body.indexOf(needle)
    if i < 0 then Nil
    else
      val start = i + needle.length
      val end   = body.indexOf("]", start)
      if end < 0 then Nil
      else
        val inner = body.substring(start, end).trim
        if inner.isEmpty then Nil
        else inner.split(",").toList
          .map(_.trim.stripPrefix("\"").stripSuffix("\""))

  private val http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(2))
    .build()

  private def fetchStatus(port: Int): Option[String] =
    val req = HttpRequest.newBuilder()
      .uri(URI.create(s"http://127.0.0.1:$port/_ssc-cluster/status"))
      .timeout(Duration.ofSeconds(2))
      .GET()
      .build()
    try
      val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
      if resp.statusCode() == 200 then Some(resp.body()) else None
    catch case _: Throwable => None

  // ── The test ─────────────────────────────────────────────────────────
  //
  // `ignore(...)` instead of `test(...)`.
  //
  // ### History — JS-scheduler block (FIXED in this commit's parent change)
  //
  // The scheduler bug described in the doc comment above (synchronous
  // `_runActors` with `_asyncSleep` → `Atomics.wait` blocking Node's
  // event loop) has been fixed: `_runActors` is now `async`, sleeps via
  // `await new Promise(r => setTimeout(r, ms))`, and yields to the
  // event loop via `setImmediate` between peer-busy ticks.  With this
  // single-node tests pass and `/_ssc-cluster/*` HTTP routes remain
  // reachable while actors stay blocked on long-armed `receive`s — the
  // condition for entering this test in the first place.
  //
  // ### Why still `ignore(...)`
  //
  // Re-enabling the test surfaces TWO additional issues that are
  // outside the scope of the scheduler fix:
  //
  //   1. **`require('ws')` in JS-codegen's outbound `connectNode`
  //      worker.**  The emitted bundle doesn't ship a `package.json`
  //      (cluster modules aren't sql), so a plain `node out.js` in a
  //      sandbox dir without `node_modules/ws` fails with
  //      `Error: connectNode requires the ws npm package`.  Workaround
  //      verified in this commit: `requireWsModule(sandbox)` below
  //      `npm install`s `ws` into the sandbox before spawning.
  //   2. **JVM↔JS peer-envelope mismatch on the `ssc-actors-v1`
  //      subprotocol.**  After (1) is worked around, JS-codegen
  //      `connectNode` dials with `['ssc-actors-v1']`; JVM-codegen's
  //      WS upgrade reply does not echo a matching subprotocol, and
  //      the `ws` client errors with "Server sent no subprotocol",
  //      closing the connection.  Bully envelopes can't flow until
  //      the handshake completes, so convergence times out.  Cross-
  //      backend envelope reconciliation is a separate Tier 4 task
  //      (`specs/cluster-codegen-gap.md` §"open issues" mentions it).
  //
  // Once (2) is resolved (and assuming `npm` remains a reasonable
  // test dependency, or the JS bundle ships its own `package.json`
  // for cluster modules), flip this back to `test(...)`.  The
  // scaffolding (npm install, sandbox cwd) is already in place.
  test("JVM-codegen + JS-codegen nodes converge on a Bully leader"):
    val launcher  = requireLauncher()
    requireScalaCli()
    requireNode()
    val stdlibCp  = requireScalaStdlib()
    val sandbox   = os.temp.dir(prefix = "ssc-mb-matrix-")
    var procJvm: Option[Process]      = None
    var procJs:  Option[Process]      = None
    try
      val portJvm = freePort()
      val portJs  = freePort()
      val urlJvm  = s"ws://127.0.0.1:$portJvm/_ssc-actors"
      val urlJs   = s"ws://127.0.0.1:$portJs/_ssc-actors"

      // node-aaa (JVM-codegen) and node-bbb (JS-codegen).  Bully picks
      // the lex-greatest nodeId → both nodes should agree on "node-bbb"
      // as the leader.
      val jvmSrc = nodeSrc("node-aaa", portJvm, peerUrl = urlJs)
      val jsSrc  = nodeSrc("node-bbb", portJs,  peerUrl = urlJvm)

      val jvmJarPath = compileJvmSide(sandbox, launcher, jvmSrc, "node-aaa")
      val jsJsPath   = compileJsSide (sandbox, launcher, jsSrc,  "node-bbb")
      // JS-codegen's `connectNode` worker dials peers via `require('ws')`.
      // Install the package alongside the bundle so the cluster handshake
      // succeeds.  Cancels the test on `npm install` failure (offline, no
      // npm on PATH, etc.) rather than failing — matches the
      // requireScalaCli/requireNode pattern above.
      requireWsModule(sandbox)

      val jvmLog = (sandbox / "node-aaa.out").toIO
      val jsLog  = (sandbox / "node-bbb.out").toIO

      // Spawn JVM first; stagger so its port is bound before JS dials.
      procJvm = Some(spawnJvmNode(jvmJarPath, stdlibCp, "node-aaa", jvmLog))
      Thread.sleep(500)
      procJs  = Some(spawnJsNode(jsJsPath, jsLog))

      // Wait for both HTTP ports to bind.  10 s cap.
      def httpReady(port: Int): Boolean =
        try { val s = new java.net.Socket("127.0.0.1", port); s.close(); true }
        catch case _: Throwable => false
      val bindDeadline = System.currentTimeMillis() + 10_000L
      while (!httpReady(portJvm) || !httpReady(portJs)) &&
            System.currentTimeMillis() < bindDeadline do
        Thread.sleep(200)
      assert(httpReady(portJvm),
        s"JVM-codegen node never bound HTTP port $portJvm — log:\n" +
        s"${scala.io.Source.fromFile(jvmLog).mkString}")
      assert(httpReady(portJs),
        s"JS-codegen node never bound HTTP port $portJs — log:\n" +
        s"${scala.io.Source.fromFile(jsLog).mkString}")

      // Poll /_ssc-cluster/status on each node until both agree on a
      // non-empty leader.  Budget mirrors ClusterBullyStatusConvergenceTest
      // — first electLeader() fires ~2 s into node lifetime; Bully
      // envelopes converge within tens of ms once both peers are
      // connected.  12 s gives slack for slow CI.
      val convergeDeadline = System.currentTimeMillis() + 12_000L
      var lastJvm: String = ""
      var lastJs:  String = ""
      var leaderJvm:   String = ""
      var leaderJs:    String = ""
      var membersJvm:  List[String] = Nil
      var membersJs:   List[String] = Nil
      var protocolJvm: String = ""
      var protocolJs:  String = ""
      var converged = false

      while !converged && System.currentTimeMillis() < convergeDeadline do
        Thread.sleep(200)
        val rJvm = fetchStatus(portJvm)
        val rJs  = fetchStatus(portJs)
        rJvm.foreach(lastJvm = _)
        rJs.foreach(lastJs = _)
        if rJvm.isDefined && rJs.isDefined then
          leaderJvm   = jsonStringField(lastJvm, "leader")
          leaderJs    = jsonStringField(lastJs,  "leader")
          membersJvm  = jsonStringArray(lastJvm, "members")
          membersJs   = jsonStringArray(lastJs,  "members")
          protocolJvm = jsonStringField(lastJvm, "protocol")
          protocolJs  = jsonStringField(lastJs,  "protocol")
          if leaderJvm.nonEmpty && leaderJvm == leaderJs then converged = true

      info(s"JVM-codegen (node-aaa) status: $lastJvm")
      info(s"JS-codegen  (node-bbb) status: $lastJs")
      if !converged then
        info(s"JVM-codegen (node-aaa) stdout:\n" +
             scala.io.Source.fromFile(jvmLog).mkString)
        info(s"JS-codegen  (node-bbb) stdout:\n" +
             scala.io.Source.fromFile(jsLog).mkString)

      assert(converged,
        s"nodes never converged on a single non-empty leader within 12 s; " +
        s"jvm=$leaderJvm, js=$leaderJs")

      // Bully tie-breaker: lex-greatest wins ⇒ "node-bbb" (JS side).
      assert(leaderJvm == "node-bbb",
        s"expected node-bbb to win Bully (lex-greatest), got '$leaderJvm'")
      assert(protocolJvm == "bully",
        s"protocol on JVM-codegen side: '$protocolJvm'")
      assert(protocolJs == "bully",
        s"protocol on JS-codegen side: '$protocolJs'")

      // Membership: each node's `members` list contains the cross-backend
      // peer's id (not its own).
      assert(membersJvm.contains("node-bbb"),
        s"node-aaa (JVM) members should include node-bbb (JS), " +
        s"got: $membersJvm")
      assert(membersJs.contains("node-aaa"),
        s"node-bbb (JS) members should include node-aaa (JVM), " +
        s"got: $membersJs")
    finally
      procJvm.foreach(_.destroyForcibly())
      procJs .foreach(_.destroyForcibly())
      procJvm.foreach(_.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
      procJs .foreach(_.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
      os.remove.all(sandbox)
