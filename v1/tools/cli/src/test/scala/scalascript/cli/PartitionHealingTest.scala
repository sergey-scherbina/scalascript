package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Two-phase partition test:
 *
 *  Phase 1 — same setup as `PartitionTest`: 5 nodes are launched with
 *  partition-shaped peer lists (a,b see only each other; c,d,e see
 *  only each other), every node calls `setQuorumSize(3)`.  Each node
 *  prints `LEADER1:<current>` after Bully has had a chance to run.
 *
 *  Phase 2 — every node then issues `connectNode(url)` against every
 *  peer it didn't previously know.  The partition heals: peers
 *  discover each other, the minority side rejoins, and Bully
 *  re-elects.  Each node prints `LEADER2:<current>`.
 *
 *  Expected:
 *    - LEADER1: minority leaderless, majority on node-e (existing
 *      PartitionTest behaviour).
 *    - LEADER2: every node converges on node-e (lex-greatest of full
 *      membership), proving partition recovery works end-to-end. */
class PartitionHealingTest extends AnyFunSuite with Matchers:

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

  /** Source for one node: phase-1 with `sidePeerUrls`, then after a
   *  fixed delay dial every URL in `crossPeerUrls`, then report
   *  LEADER2. */
  private def nodeSrc(nodeId:        String,
                      port:          Int,
                      sidePeerUrls:  List[String],
                      crossPeerUrls: List[String]): String =
    val sideList = sidePeerUrls.map(u => s"\"$u\"").mkString(", ")
    val sideJoin =
      if sidePeerUrls.isEmpty then "()"
      else s"joinCluster(List($sideList))"
    val crossDials = crossPeerUrls.map(u =>
      s"        connectNode(\"$u\")").mkString("\n")
    s"""---
       |name: node-$nodeId
       |---
       |
       |# Partition-healing node $nodeId
       |
       |```scalascript
       |runActors {
       |  startNode("$nodeId", "ws://127.0.0.1:$port/_ssc-actors")
       |  serveAsync($port)
       |  setReconnectPolicy(300, 1500)
       |  setQuorumSize(3)
       |  setAutoReelect(true)
       |  // Tighten heartbeat so the second-phase election doesn't wait
       |  // out the 30/40s default after the topology changes.
       |  setHeartbeatTimeout(1500, 2500)
       |  spawn { () =>
       |    val s = self()
       |    sendAfter(800, s, "join")
       |    receive { case "join" =>
       |      $sideJoin
       |      sendAfter(4500, s, "elect1")
       |      receive { case "elect1" =>
       |        electLeader()
       |        sendAfter(4500, s, "rep1")
       |        receive { case "rep1" =>
       |          println("LEADER1:" + currentLeader())
       |          sendAfter(500, s, "heal")
       |          receive { case "heal" =>
       |            // Dial across the partition.  All best-effort —
       |            // the reconnect policy + handshake re-trigger
       |            // do the rest.
       |$crossDials
       |            sendAfter(4000, s, "elect2")
       |            receive { case "elect2" =>
       |              electLeader()
       |              sendAfter(4000, s, "rep2")
       |              receive { case "rep2" =>
       |                println("LEADER2:" + currentLeader())
       |                stop()
       |              }
       |            }
       |          }
       |        }
       |      }
       |    }
       |  }
       |}
       |```
       |""".stripMargin

  test("5-node partition heals: leaderless minority rejoins majority on node-e"):
    // Real-WS 5-node election on fixed sendAfter windows — flaky under CI
    // contention; retry the whole scenario (fresh ports) up to 3×.
    ClusterTestSupport.retrying(3)(healScenario())

  private def healScenario(): Unit =
    val jar = requireJar()
    val sandbox = os.pwd / "target" / "ssc-partition-healing"
    os.remove.all(sandbox)
    os.makeDir.all(sandbox)
    val procs    = scala.collection.mutable.ArrayBuffer.empty[Process]
    val outFiles = scala.collection.mutable.ArrayBuffer.empty[java.io.File]
    try
      val nodeIds = List("node-a", "node-b", "node-c", "node-d", "node-e")
      val ports   = nodeIds.map(_ => freePort())
      val urls    = ports.map(p => s"ws://127.0.0.1:$p/_ssc-actors")
      // Side map — same partition as PartitionTest's static split.
      val sides: Map[Int, List[Int]] = Map(
        0 -> List(1),         // a — minority, sees only b
        1 -> List(0),         // b — minority, sees only a
        2 -> List(3, 4),      // c — majority, sees d, e
        3 -> List(2, 4),      // d — majority, sees c, e
        4 -> List(2, 3),      // e — majority, sees c, d
      )

      for ((nid, p), idx) <- nodeIds.zip(ports).zipWithIndex do
        val sideIdxs  = sides(idx)
        val sidePeers = sideIdxs.map(urls(_))
        // Cross-side peers = all OTHER nodes minus our side + self.
        val crossIdxs  = nodeIds.indices.toList.filter(i =>
          i != idx && !sideIdxs.contains(i))
        val crossPeers = crossIdxs.map(urls(_))
        val src        = nodeSrc(nid, p, sidePeers, crossPeers)
        val sscFile    = sandbox / s"node-$nid.ssc"
        os.write(sscFile, src)
        val outFile = (sandbox / s"node-$nid.out").toIO
        val pb = new ProcessBuilder("java", "-jar", jar.toString, "--v1", sscFile.toString)
          .redirectErrorStream(true).redirectOutput(outFile)
        val proc = pb.start()
        procs    += proc
        outFiles += outFile
        Thread.sleep(200)

      // Each child's scripted flow finishes in ~16 s and prints
      // LEADER2; the JDK HTTP server's non-daemon accept thread then
      // keeps the JVM alive forever, so we don't wait for natural
      // exit.  Poll the .out files until every node has LEADER2,
      // then SIGKILL the lot.
      val deadline = System.currentTimeMillis() + 40_000L
      def texts() = outFiles.map(f => scala.io.Source.fromFile(f).mkString)
      while System.currentTimeMillis() < deadline &&
            !texts().forall(_.contains("LEADER2:")) do
        Thread.sleep(500)
      procs.foreach(_.destroyForcibly())

      val results = outFiles.zip(nodeIds).map { (f, nid) =>
        val txt     = scala.io.Source.fromFile(f).mkString
        val leader1 = "LEADER1:(.*)".r.findFirstMatchIn(txt).map(_.group(1).trim).getOrElse("<none>")
        val leader2 = "LEADER2:(.*)".r.findFirstMatchIn(txt).map(_.group(1).trim).getOrElse("<none>")
        info(s"$nid LEADER1=$leader1 LEADER2=$leader2")
        (nid, leader1, leader2)
      }.toList

      // Phase 1: same expectation as PartitionTest.
      results.find(_._1 == "node-a").map(_._2) shouldBe Some("")
      results.find(_._1 == "node-b").map(_._2) shouldBe Some("")
      results.find(_._1 == "node-c").map(_._2) shouldBe Some("node-e")
      results.find(_._1 == "node-d").map(_._2) shouldBe Some("node-e")
      results.find(_._1 == "node-e").map(_._2) shouldBe Some("node-e")

      // Phase 2: every node should now see node-e.
      results.foreach { case (nid, _, leader2) =>
        assert(leader2 == "node-e",
          s"$nid did not converge after healing: leader2=$leader2")
      }
    finally
      procs.foreach(_.destroyForcibly())
