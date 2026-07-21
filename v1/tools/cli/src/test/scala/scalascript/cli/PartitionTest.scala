package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Network-partition simulation via static peer-URL lists.  Five
 *  nodes split into:
 *    - minority: {a, b}   — each knows only the other
 *    - majority: {c, d, e} — each knows only the other two
 *
 *  Every node sets `setQuorumSize(3)`.  Expected outcomes:
 *    - minority side: each node sees ≤2 visible (peers+self), below
 *      quorum, so `electLeader()` is a no-op and `currentLeader()`
 *      stays "".  No split-brain.
 *    - majority side: each node sees 3 visible, meets quorum,
 *      Bully elects node-e (lex-greatest).
 *
 *  This proves `setQuorumSize` actually guards split-brain rather
 *  than just logging.  Doesn't simulate iptables-level dropping
 *  (would require root + platform-specific shims); the static URL
 *  lists give the same observable effect — neither half can see
 *  the other. */
class PartitionTest extends AnyFunSuite with Matchers:

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

  private def nodeSrc(nodeId: String, port: Int, peerUrls: List[String]): String =
    val joinList = peerUrls.map(u => s"\"$u\"").mkString(", ")
    val joinStmt =
      if peerUrls.isEmpty then "()"
      else s"joinCluster(List($joinList))"
    s"""---
       |name: node-$nodeId
       |---
       |
       |# Partition-node $nodeId
       |
       |```scalascript
       |runActors {
       |  startNode("$nodeId", "ws://127.0.0.1:$port/_ssc-actors")
       |  serveAsync($port)
       |  setReconnectPolicy(300, 1500)
       |  // Quorum = 3 of 5; minority side (2 nodes) won't reach it.
       |  setQuorumSize(3)
       |  spawn { () =>
       |    val s = self()
       |    sendAfter(800, s, "join")
       |    receive { case "join" =>
       |      $joinStmt
       |      sendAfter(4500, s, "elect")
       |      receive { case "elect" =>
       |        electLeader()
       |        sendAfter(4500, s, "report")
       |        receive { case "report" =>
       |          println("MEMBERS:" + clusterMembers().mkString(","))
       |          println("LEADER:" + currentLeader())
       |          stop()
       |        }
       |      }
       |    }
       |  }
       |}
       |```
       |""".stripMargin

  test("5-node static partition: quorum=3 leaves minority leaderless, majority elects node-e"):
    // Real-WS 5-node election on fixed sendAfter windows — flaky under CI
    // contention; retry the whole scenario (fresh ports) up to 3×.
    ClusterTestSupport.retrying(3)(partitionScenario())

  private def partitionScenario(): Unit =
    val jar = requireJar()
    val sandbox = os.pwd / "target" / "ssc-partition"
    os.remove.all(sandbox)
    os.makeDir.all(sandbox)
    val procs    = scala.collection.mutable.ArrayBuffer.empty[Process]
    val outFiles = scala.collection.mutable.ArrayBuffer.empty[java.io.File]
    try
      val nodeIds = List("node-a", "node-b", "node-c", "node-d", "node-e")
      val ports   = nodeIds.map(_ => freePort())
      val urls    = ports.map(p => s"ws://127.0.0.1:$p/_ssc-actors")
      // Partition map: each node's index → list of OTHER nodes on the
      // same side.  {a=0, b=1} on the minority side; {c=2, d=3, e=4}
      // on the majority side.
      val sides = Map(
        0 -> List(1),         // a sees only b
        1 -> List(0),         // b sees only a
        2 -> List(3, 4),      // c sees d, e
        3 -> List(2, 4),      // d sees c, e
        4 -> List(2, 3),      // e sees c, d
      )
      for ((nid, p), idx) <- nodeIds.zip(ports).zipWithIndex do
        val peerIdxs = sides(idx)
        val peers    = peerIdxs.map(urls(_))
        val src      = nodeSrc(nid, p, peers)
        val sscFile  = sandbox / s"node-$nid.ssc"
        os.write(sscFile, src)
        val outFile  = (sandbox / s"node-$nid.out").toIO
        val pb = new ProcessBuilder("java", "-jar", jar.toString, "--v1", sscFile.toString)
          .redirectErrorStream(true).redirectOutput(outFile)
        val proc = pb.start()
        procs    += proc
        outFiles += outFile
        Thread.sleep(200)

      procs.foreach { p =>
        if !p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) then
          p.destroyForcibly()
      }

      val results = outFiles.zip(nodeIds).map { (f, nid) =>
        val txt = scala.io.Source.fromFile(f).mkString
        val leader  = "LEADER:(.*)".r.findFirstMatchIn(txt).map(_.group(1).trim).getOrElse("<none>")
        val members = "MEMBERS:(.*)".r.findFirstMatchIn(txt).map(_.group(1).trim).getOrElse("<none>")
        info(s"$nid leader=$leader members=$members")
        (nid, leader, members)
      }.toList

      // Minority side (a, b) — leader stays "" because of quorum gate.
      results.find(_._1 == "node-a").map(_._2) shouldBe Some("")
      results.find(_._1 == "node-b").map(_._2) shouldBe Some("")
      // Majority side (c, d, e) — Bully elects node-e (lex-greatest).
      results.find(_._1 == "node-c").map(_._2) shouldBe Some("node-e")
      results.find(_._1 == "node-d").map(_._2) shouldBe Some("node-e")
      results.find(_._1 == "node-e").map(_._2) shouldBe Some("node-e")
    finally
      procs.foreach(_.destroyForcibly())
