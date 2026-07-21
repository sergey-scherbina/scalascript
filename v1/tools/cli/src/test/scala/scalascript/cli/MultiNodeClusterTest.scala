package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Real multi-node smoke test: spawns two `ssc.jar` subprocesses on
 *  distinct loopback ports, has them join via the v1.6 actor WS, and
 *  verifies both observe the same Bully-elected leader.
 *
 *  Each node uses `serveAsync(port)` (the non-blocking variant of
 *  `serve` added for cluster scenarios) so the actor scheduler can
 *  drive elections while the WS server runs in the background. */
class MultiNodeClusterTest extends AnyFunSuite:

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

  /** Source for one cluster node.  Calls `serveAsync` (non-blocking)
   *  so the actor scheduler stays alive, then joins ALL the given
   *  peer URLs (failures silent), sleeps for handshake convergence,
   *  elects, prints the leader, and stops. */
  private def nodeSrc(nodeId: String, port: Int, peerUrls: List[String]): String =
    val joinList = peerUrls.map(u => s"\"$u\"").mkString(", ")
    val joinStmt =
      if peerUrls.isEmpty then "()"
      else s"joinCluster(List($joinList))"
    s"""---
       |name: node-$nodeId
       |---
       |
       |# Node $nodeId
       |
       |```scalascript
       |runActors {
       |  startNode("$nodeId", "ws://127.0.0.1:$port/_ssc-actors")
       |  serveAsync($port)
       |  spawn { () =>
       |    val s = self()
       |    // Reconnect failed peers — needed in 5-node tests where
       |    // some seeds aren't bound yet when joinCluster fires.
       |    setReconnectPolicy(300, 1500)
       |    // Give serveAsync a beat to bind the port before peers dial.
       |    sendAfter(800, s, "join")
       |    receive { case "join" =>
       |      $joinStmt
       |      // Initial handshake + peers_resp gossip + any reconnect
       |      // backoff cycles need a generous window.
       |      sendAfter(3000, s, "gossip")
       |      receive { case "gossip" =>
       |        requestGossip()
       |        sendAfter(1500, s, "elect")
       |        receive { case "elect" =>
       |          println("MEMBERS:" + clusterMembers().mkString(","))
       |          electLeader()
       |          // Let Bully envelopes converge.
       |          sendAfter(2000, s, "report")
       |          receive { case "report" =>
       |            println("LEADER:" + currentLeader())
       |            stop()
       |          }
       |        }
       |      }
       |    }
       |  }
       |}
       |```
       |""".stripMargin

  private def spawnNode(jar: os.Path, sandbox: os.Path,
                        nodeId: String, port: Int,
                        peerUrls: List[String],
                        src:      String = ""): (Process, java.io.File) =
    val srcText = if src.nonEmpty then src else nodeSrc(nodeId, port, peerUrls)
    val sscFile = sandbox / s"node-$nodeId.ssc"
    os.write(sscFile, srcText)
    val outFile = (sandbox / s"node-$nodeId.out").toIO
    val pb = new ProcessBuilder("java", "-jar", jar.toString, "--v1", sscFile.toString)
      .redirectErrorStream(true)
      .redirectOutput(outFile)
    // Pass `SSC_CLUSTER_DEBUG=1` via the env to capture per-link
    // connect / handshake traces (see Interpreter.connectPeer);
    // leave unset for normal test runs to keep .out files small.
    if sys.env.contains("SSC_CLUSTER_DEBUG") then
      pb.environment().put("SSC_CLUSTER_DEBUG", sys.env("SSC_CLUSTER_DEBUG"))
    val proc = pb.start()
    (proc, outFile)

  /** Source for a node that elects, prints LEADER1, then sits in an
   *  auto-reelect loop printing LEADER2 once the second election has
   *  converged.  Used by the kill-the-leader failover test. */
  private def failoverNodeSrc(nodeId: String, port: Int, peerUrls: List[String]): String =
    val joinList = peerUrls.map(u => s"\"$u\"").mkString(", ")
    val joinStmt =
      if peerUrls.isEmpty then "()"
      else s"joinCluster(List($joinList))"
    s"""---
       |name: node-$nodeId
       |---
       |
       |# Failover-node $nodeId
       |
       |```scalascript
       |runActors {
       |  startNode("$nodeId", "ws://127.0.0.1:$port/_ssc-actors")
       |  serveAsync($port)
       |  spawn { () =>
       |    val s = self()
       |    setReconnectPolicy(300, 1500)
       |    setAutoReelect(true)
       |    // Tight heartbeat — 1.5 s ping, dead at >2.5 s of silence —
       |    // so the failover test runs in ~10 s instead of 75+ s of
       |    // the default 30/40 s cadence.  Production deployments
       |    // should leave the defaults (or pick tens of seconds).
       |    setHeartbeatTimeout(1500, 2500)
       |    sendAfter(800, s, "join")
       |    receive { case "join" =>
       |      $joinStmt
       |      sendAfter(3000, s, "elect")
       |      receive { case "elect" =>
       |        electLeader()
       |        sendAfter(2500, s, "rep1")
       |        receive { case "rep1" =>
       |          println("LEADER1:" + currentLeader())
       |          // Heartbeat tuned to 1.5 / 2.5 s above — survivors
       |          // detect the killed leader and re-elect within ~5 s.
       |          // 18 s budget here gives headroom for slow CI.
       |          sendAfter(18000, s, "rep2")
       |          receive { case "rep2" =>
       |            println("LEADER2:" + currentLeader())
       |            stop()
       |          }
       |        }
       |      }
       |    }
       |  }
       |}
       |```
       |""".stripMargin

  test("two-node cluster — both observe the same Bully leader"):
    ClusterTestSupport.retrying(3)(twoNodeLeaderScenario())

  private def twoNodeLeaderScenario(): Unit =
    val jar = requireJar()
    val sandbox = os.temp.dir(prefix = "ssc-multinode-")
    var procA: Option[Process] = None
    var procB: Option[Process] = None
    try
      val portA = freePort()
      val portB = freePort()
      val urlA = s"ws://127.0.0.1:$portA/_ssc-actors"
      val urlB = s"ws://127.0.0.1:$portB/_ssc-actors"
      // Bully picks the lex-greatest nodeId; "node-b" > "node-a".
      val (pA, outA) = spawnNode(jar, sandbox, "node-a", portA, peerUrls = List(urlB))
      procA = Some(pA)
      Thread.sleep(500)   // let A bind its port before B dials
      val (pB, outB) = spawnNode(jar, sandbox, "node-b", portB, peerUrls = List(urlA))
      procB = Some(pB)

      // Each node's scheduled flow is ~7.3 s.  Wait extra for slack.
      val gotA = pA.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)
      val gotB = pB.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)
      if !gotA then pA.destroyForcibly()
      if !gotB then pB.destroyForcibly()

      val txtA = scala.io.Source.fromFile(outA).mkString
      val txtB = scala.io.Source.fromFile(outB).mkString
      info(s"node-a out:\n$txtA")
      info(s"node-b out:\n$txtB")

      val leaderA = "LEADER:(.*)".r.findFirstMatchIn(txtA).map(_.group(1).trim)
      val leaderB = "LEADER:(.*)".r.findFirstMatchIn(txtB).map(_.group(1).trim)

      assert(leaderA.nonEmpty, s"node-a never printed LEADER:\n$txtA")
      assert(leaderB.nonEmpty, s"node-b never printed LEADER:\n$txtB")
      assert(leaderA == leaderB,
        s"nodes disagree on leader: a=${leaderA.get}, b=${leaderB.get}")
      // Bully tie-breaker: lex-greatest wins, so leader must be node-b.
      assert(leaderA.contains("node-b"),
        s"expected node-b to win Bully (lex-greatest), got ${leaderA.get}")
    finally
      procA.foreach(_.destroyForcibly())
      procB.foreach(_.destroyForcibly())
      os.remove.all(sandbox)

  test("two-node cluster — spawnRemote starts a registered behavior on peer"):
    ClusterTestSupport.retrying(3)(spawnRemoteScenario())

  private def spawnRemoteScenario(): Unit =
    val jar = requireJar()
    val sandbox = os.temp.dir(prefix = "ssc-remote-spawn-")
    var procA: Option[Process] = None
    var procB: Option[Process] = None
    try
      val portA = freePort()
      val portB = freePort()
      val urlA = s"ws://127.0.0.1:$portA/_ssc-actors"
      val urlB = s"ws://127.0.0.1:$portB/_ssc-actors"
      val srcA =
        s"""---
           |name: remote-spawn-a
           |---
           |
           |# Remote spawn A
           |
           |```scalascript
           |runActors {
           |  startNode("node-a", "$urlA")
           |  serveAsync($portA)
           |  registerBehavior("echo", (arg: Any) =>
           |    receive {
           |      case "ping" =>
           |        println("REMOTE_SPAWN_OK")
           |        stop()
           |    }
           |  )
           |  spawn { () =>
           |    val s = self()
           |    sendAfter(15000, s, "timeout")
           |    receive { case "timeout" =>
           |      println("REMOTE_SPAWN_TIMEOUT")
           |      stop()
           |    }
           |  }
           |}
           |```
           |""".stripMargin
      val srcB =
        s"""---
           |name: remote-spawn-b
           |---
           |
           |# Remote spawn B
           |
           |```scalascript
           |runActors {
           |  startNode("node-b", "$urlB")
           |  serveAsync($portB)
           |  spawn { () =>
           |    val s = self()
           |    setReconnectPolicy(300, 1200)
           |    sendAfter(800, s, "join")
           |    receive { case "join" =>
           |      joinCluster(List("$urlA"))
           |      sendAfter(2500, s, "spawn")
           |      receive { case "spawn" =>
           |        val ref = spawnRemote[String]("node-a", "echo", ())
           |        ref.tell("ping")
           |        sendAfter(1000, s, "done")
           |        receive { case "done" => stop() }
           |      }
           |    }
           |  }
           |}
           |```
           |""".stripMargin

      val (pA, outA) = spawnNode(jar, sandbox, "remote-a", portA, Nil, srcA)
      procA = Some(pA)
      Thread.sleep(500)
      val (pB, outB) = spawnNode(jar, sandbox, "remote-b", portB, Nil, srcB)
      procB = Some(pB)

      val gotA = pA.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)
      val gotB = pB.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)
      if !gotA then pA.destroyForcibly()
      if !gotB then pB.destroyForcibly()

      val txtA = scala.io.Source.fromFile(outA).mkString
      val txtB = scala.io.Source.fromFile(outB).mkString
      info(s"remote-a out:\n$txtA")
      info(s"remote-b out:\n$txtB")

      assert(txtA.contains("REMOTE_SPAWN_OK"),
        s"node-a did not run remote-spawned behavior:\nA:\n$txtA\nB:\n$txtB")
      // REMOTE_SPAWN_TIMEOUT may appear after a successful spawn because
      // serveAsync keeps node-a alive past the 15-second watchdog; only
      // fail if the timeout appeared WITHOUT a preceding OK.
      val okIdx = txtA.indexOf("REMOTE_SPAWN_OK")
      val toIdx = txtA.indexOf("REMOTE_SPAWN_TIMEOUT")
      assert(toIdx < 0 || okIdx < toIdx,
        s"REMOTE_SPAWN_TIMEOUT appeared without a prior REMOTE_SPAWN_OK:\nA:\n$txtA\nB:\n$txtB")
    finally
      procA.foreach(_.destroyForcibly())
      procB.foreach(_.destroyForcibly())
      os.remove.all(sandbox)

  test("five-node cluster — all observe the same Bully leader"):
    ClusterTestSupport.retrying(3)(fiveNodeLeaderScenario())

  private def fiveNodeLeaderScenario(): Unit =
    val jar = requireJar()
    val sandbox = os.pwd / "target" / "ssc-multinode-5"
    os.remove.all(sandbox)
    os.makeDir.all(sandbox)
    val procs = scala.collection.mutable.ArrayBuffer.empty[Process]
    val outFiles = scala.collection.mutable.ArrayBuffer.empty[java.io.File]
    try
      val nodeIds = List("node-a", "node-b", "node-c", "node-d", "node-e")
      val ports   = nodeIds.map(_ => freePort())
      val urls    = ports.map(p => s"ws://127.0.0.1:$p/_ssc-actors")

      // Every node knows every other node's URL — joinCluster is
      // best-effort, so unreachable peers (those not yet started) get
      // skipped silently and re-established by the existing handshake
      // logic when they come online.
      for ((nid, p), idx) <- nodeIds.zip(ports).zipWithIndex do
        val peers = urls.zipWithIndex.collect { case (u, i) if i != idx => u }
        val (proc, out) = spawnNode(jar, sandbox, nid, p, peerUrls = peers)
        procs += proc
        outFiles += out
        Thread.sleep(200)  // stagger starts so binds don't race

      procs.foreach { p =>
        if !p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) then p.destroyForcibly()
      }

      val leaders = outFiles.zip(nodeIds).map { (f, nid) =>
        val txt = scala.io.Source.fromFile(f).mkString
        val m = "LEADER:(.*)".r.findFirstMatchIn(txt).map(_.group(1).trim)
        val mem = "MEMBERS:(.*)".r.findFirstMatchIn(txt).map(_.group(1).trim)
        info(s"$nid leader=${m.getOrElse("<none>")} members=${mem.getOrElse("<none>")}")
        m
      }.toList

      assert(leaders.forall(_.nonEmpty), "some node never printed LEADER:")
      val distinct = leaders.flatten.distinct
      assert(distinct.size == 1, s"nodes disagree: $distinct")
      // Lex-greatest of {node-a..node-e} is node-e.
      assert(distinct.head.contains("node-e"),
        s"expected node-e to win Bully, got ${distinct.head}")
    finally
      procs.foreach(_.destroyForcibly())
      // Keep sandbox for postmortem; lives at cli/target/ssc-multinode-5/.

  test("three-node cluster — surviving nodes re-elect after leader kill"):
    ClusterTestSupport.retrying(3)(threeNodeReelectScenario())

  private def threeNodeReelectScenario(): Unit =
    val jar = requireJar()
    val sandbox = os.pwd / "target" / "ssc-multinode-kill"
    os.remove.all(sandbox)
    os.makeDir.all(sandbox)
    val procs    = scala.collection.mutable.ArrayBuffer.empty[Process]
    val outFiles = scala.collection.mutable.ArrayBuffer.empty[java.io.File]
    try
      val nodeIds = List("node-a", "node-b", "node-c")
      val ports   = nodeIds.map(_ => freePort())
      val urls    = ports.map(p => s"ws://127.0.0.1:$p/_ssc-actors")

      for ((nid, p), idx) <- nodeIds.zip(ports).zipWithIndex do
        val peers = urls.zipWithIndex.collect { case (u, i) if i != idx => u }
        val src   = failoverNodeSrc(nid, p, peers)
        val (proc, out) = spawnNode(jar, sandbox, nid, p, peers, src = src)
        procs    += proc
        outFiles += out
        Thread.sleep(200)

      // Phase 1: wait for everyone to print LEADER1 (~7s of scheduled
      // delays plus stagger; give 18s of headroom for slow CI).
      def readAll(): List[String] = outFiles.map(f =>
        scala.io.Source.fromFile(f).mkString
      ).toList
      val deadline1 = System.currentTimeMillis() + 18_000L
      while System.currentTimeMillis() < deadline1 &&
            !readAll().forall(_.contains("LEADER1:")) do
        Thread.sleep(500)

      val phase1Texts = readAll()
      val leader1s = phase1Texts.map { txt =>
        "LEADER1:(.*)".r.findFirstMatchIn(txt).map(_.group(1).trim)
      }
      assert(leader1s.forall(_.nonEmpty),
        s"some node never printed LEADER1: ${leader1s.zip(nodeIds)}")
      val distinct1 = leader1s.flatten.distinct
      assert(distinct1.size == 1, s"phase-1 nodes disagree: $distinct1")
      val leaderId = distinct1.head
      assert(leaderId.contains("node-c"),
        s"expected node-c to be initial leader (lex-greatest), got $leaderId")

      // Phase 2: SIGKILL the elected leader and observe the survivors
      // print a fresh LEADER2 line that no longer mentions it.
      val killIdx = nodeIds.indexWhere(leaderId.contains)
      assert(killIdx >= 0, s"can't locate leader process for $leaderId")
      procs(killIdx).destroyForcibly()
      info(s"killed leader $leaderId (idx=$killIdx)")

      // Wait for both survivors to settle and print LEADER2.
      // Survivors print LEADER2 ~18 s after their initial LEADER1
      // (sendAfter(18000) above, with the tightened heartbeat) —
      // bound the wait at 32 s.
      val survivorIdxs = nodeIds.indices.filter(_ != killIdx)
      val deadline2 = System.currentTimeMillis() + 32_000L
      def survivorTexts() = survivorIdxs.map(i =>
        scala.io.Source.fromFile(outFiles(i)).mkString
      )
      while System.currentTimeMillis() < deadline2 &&
            !survivorTexts().forall(_.contains("LEADER2:")) do
        Thread.sleep(500)

      survivorIdxs.foreach { i =>
        if !procs(i).waitFor(30, java.util.concurrent.TimeUnit.SECONDS) then
          procs(i).destroyForcibly()
      }

      val leader2s = survivorIdxs.map { i =>
        val txt = scala.io.Source.fromFile(outFiles(i)).mkString
        info(s"${nodeIds(i)} phase-2 tail:\n${txt.linesIterator.toList.takeRight(12).mkString("\n")}")
        "LEADER2:(.*)".r.findFirstMatchIn(txt).map(_.group(1).trim)
      }.toList
      assert(leader2s.forall(_.nonEmpty),
        s"some survivor never printed LEADER2: ${leader2s.zip(survivorIdxs.map(nodeIds(_)))}")
      val distinct2 = leader2s.flatten.distinct
      assert(distinct2.size == 1, s"survivors disagree on new leader: $distinct2")
      val newLeader = distinct2.head
      assert(!newLeader.contains(leaderId.stripPrefix("node-")),
        s"new leader '$newLeader' still references killed node '$leaderId'")
      // node-c killed → lex-greatest of {a,b} is node-b.
      assert(newLeader.contains("node-b"),
        s"expected node-b to win re-election after node-c killed, got $newLeader")
    finally
      procs.foreach(_.destroyForcibly())
