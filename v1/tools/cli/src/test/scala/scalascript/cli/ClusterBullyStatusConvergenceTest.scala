package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

/** Real-WS 2-node Bully convergence test, asserted via `GET
 *  /_ssc-cluster/status` polling.
 *
 *  Sister test to [[MultiNodeClusterTest]] — that test asserts
 *  convergence by reading `println("LEADER:...")` lines from each
 *  node's stdout, then stops each node.  This test instead asserts
 *  via the new operational HTTP endpoint that landed in v1.23
 *  (`/_ssc-cluster/status`): both nodes stay alive long enough for an
 *  external observer to poll their status JSON and witness agreement.
 *
 *  The task that motivated this test
 *  (`specs/cluster-raft.md` §9 "Multi-process integration test —
 *  current state") explicitly calls out polling `/_ssc-cluster/status`
 *  on each node and verifying they agree on a single non-empty
 *  `leader` field.  The earlier println-based test predates the
 *  cluster-status route landing on the JVM and Node codegen backends. */
class ClusterBullyStatusConvergenceTest extends AnyFunSuite:

  private val sscJar: Option[os.Path] =
    val cwd = os.pwd
    def jarUnder(root: os.Path): os.Path =
      root / "cli" / "target" / "scala-3.8.3" / "ssc.jar"
    def findCanonicalRepo(p: os.Path): Option[os.Path] =
      val parts = p.segments.toList
      val idx   = parts.lastIndexOf(".claude")
      if idx >= 0 && idx + 1 < parts.length && parts(idx + 1) == "worktrees" then
        Some(os.Path("/" + parts.take(idx).mkString("/")))
      else None
    val candidates =
      List(jarUnder(cwd), jarUnder(cwd / os.up)) ++
      findCanonicalRepo(cwd).map(jarUnder).toList
    candidates.find(os.exists)

  private def requireJar(): os.Path = sscJar.getOrElse:
    cancel("ssc.jar not found — run `sbt cli/assembly` first")

  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort finally s.close()

  /** Long-running cluster node source.  Calls `serveAsync` so the WS
   *  server runs on a background virtual thread, joins the given peer
   *  seeds, elects a Bully leader, then sits in a long `receive` that
   *  keeps the actor scheduler alive (so /_ssc-cluster/status keeps
   *  reflecting live cluster state) until the test kills the process.
   *
   *  Bully election fires `electLeader()` after a short delay so both
   *  nodes have completed their initial handshakes.  After election
   *  the spawned actor blocks for 30 s before stopping — well beyond
   *  the test's 5 s polling window. */
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
       |    setReconnectPolicy(300, 1500)
       |    // Bind first; let serveAsync's virtual thread come up before
       |    // peers dial.  joinCluster is best-effort, so a too-early
       |    // attempt is forgiven by setReconnectPolicy.
       |    sendAfter(500, s, "join")
       |    receive { case "join" =>
       |      joinCluster(List("$peerUrl"))
       |      sendAfter(1500, s, "elect")
       |      receive { case "elect" =>
       |        electLeader()
       |        // Stay alive so the test can poll /_ssc-cluster/status.
       |        // 30 s is well above the 5 s polling window; the test
       |        // SIGKILLs us anyway in its `finally` block.
       |        sendAfter(30000, s, "stop")
       |        receive { case "stop" => stop() }
       |      }
       |    }
       |  }
       |}
       |```
       |""".stripMargin

  private def spawnNode(jar: os.Path, sandbox: os.Path,
                        nodeId: String, port: Int, peerUrl: String)
                      : (Process, java.io.File) =
    val sscFile = sandbox / s"$nodeId.ssc"
    os.write(sscFile, nodeSrc(nodeId, port, peerUrl))
    val outFile = (sandbox / s"$nodeId.out").toIO
    val pb = new ProcessBuilder("java", "-jar", jar.toString, "--v1", sscFile.toString)
      .redirectErrorStream(true)
      .redirectOutput(outFile)
    val proc = pb.start()
    (proc, outFile)

  // Lightweight JSON field extractor — same shape as the in-tree CLI
  // status command.  We only need `leader` (string), `protocol`
  // (string), and `members` (string array).
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
        else inner.split(",").toList.map(_.trim.stripPrefix("\"").stripSuffix("\""))

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

  test("2-node Bully cluster converges on a single leader (status endpoint)"):
    val jar = requireJar()
    val sandbox = os.temp.dir(prefix = "ssc-bully-status-")
    var procA: Option[Process] = None
    var procB: Option[Process] = None
    try
      val portA = freePort()
      val portB = freePort()
      val urlA  = s"ws://127.0.0.1:$portA/_ssc-actors"
      val urlB  = s"ws://127.0.0.1:$portB/_ssc-actors"

      // node-aaa < node-bbb lex; Bully should pick node-bbb as leader.
      val (pA, outA) = spawnNode(jar, sandbox, "node-aaa", portA, peerUrl = urlB)
      procA = Some(pA)
      Thread.sleep(300)  // small stagger so A's port is bound before B dials
      val (pB, outB) = spawnNode(jar, sandbox, "node-bbb", portB, peerUrl = urlA)
      procB = Some(pB)

      // Wait for both HTTP ports to bind.  Cap at 10 s — the existing
      // ClusterStatusCliTest sees first-bind within 2 s.
      def httpReady(port: Int): Boolean =
        try
          val s = new java.net.Socket("127.0.0.1", port); s.close(); true
        catch case _: Throwable => false
      val bindDeadline = System.currentTimeMillis() + 10_000L
      while (!httpReady(portA) || !httpReady(portB)) &&
            System.currentTimeMillis() < bindDeadline do
        Thread.sleep(200)
      assert(httpReady(portA), s"node-aaa never bound HTTP port $portA")
      assert(httpReady(portB), s"node-bbb never bound HTTP port $portB")

      // Poll /_ssc-cluster/status on each node until both agree on a
      // non-empty leader.  The nodeSrc schedule above:
      //   - 0.5 s sendAfter "join"
      //   - 1.5 s sendAfter "elect"
      // → first electLeader() fires ~2.0 s into node lifetime.  Bully
      // envelopes converge within tens of ms once both peers are
      // connected.  Budget: 12 s total from bind (slack for slow CI).
      val convergeDeadline = System.currentTimeMillis() + 12_000L
      var lastA: String = ""
      var lastB: String = ""
      var leaderA: String = ""
      var leaderB: String = ""
      var membersA: List[String] = Nil
      var membersB: List[String] = Nil
      var protocolA: String = ""
      var protocolB: String = ""
      var converged = false

      while !converged && System.currentTimeMillis() < convergeDeadline do
        Thread.sleep(200)
        val rA = fetchStatus(portA)
        val rB = fetchStatus(portB)
        rA.foreach(lastA = _)
        rB.foreach(lastB = _)
        if rA.isDefined && rB.isDefined then
          leaderA   = jsonStringField(lastA, "leader")
          leaderB   = jsonStringField(lastB, "leader")
          membersA  = jsonStringArray(lastA, "members")
          membersB  = jsonStringArray(lastB, "members")
          protocolA = jsonStringField(lastA, "protocol")
          protocolB = jsonStringField(lastB, "protocol")
          if leaderA.nonEmpty && leaderA == leaderB then converged = true

      info(s"node-aaa status: $lastA")
      info(s"node-bbb status: $lastB")
      if !converged then
        // Dump child logs to aid diagnosis of cross-process failures.
        info(s"node-aaa stdout:\n${scala.io.Source.fromFile(outA).mkString}")
        info(s"node-bbb stdout:\n${scala.io.Source.fromFile(outB).mkString}")

      assert(converged,
        s"nodes never converged on a single non-empty leader within 12 s; " +
        s"a=$leaderA, b=$leaderB")

      // Bully tie-breaker: highest nodeId wins.
      assert(leaderA == "node-bbb",
        s"expected node-bbb to win Bully (lex-greatest), got '$leaderA'")
      assert(protocolA == "bully", s"protocol on node-aaa: '$protocolA'")
      assert(protocolB == "bully", s"protocol on node-bbb: '$protocolB'")

      // Membership: each node's `members` list contains its peer's id
      // (not its own).  node-aaa sees {node-bbb}; node-bbb sees {node-aaa}.
      assert(membersA.contains("node-bbb"),
        s"node-aaa members should include node-bbb, got: $membersA")
      assert(membersB.contains("node-aaa"),
        s"node-bbb members should include node-aaa, got: $membersB")
    finally
      procA.foreach(_.destroyForcibly())
      procB.foreach(_.destroyForcibly())
      procA.foreach(_.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
      procB.foreach(_.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
      os.remove.all(sandbox)
