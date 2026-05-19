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
   *  so the actor scheduler stays alive, then optionally joins a
   *  peer, sleeps, elects, prints the leader, and stops. */
  private def nodeSrc(nodeId: String, port: Int, peerUrl: Option[String]): String =
    val joinStmt = peerUrl match
      case Some(u) => s"""joinCluster(List("$u"))"""
      case None    => "()"
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
       |    // Give serveAsync a beat to bind the port before peers dial.
       |    sendAfter(800, s, "join")
       |    receive { case "join" =>
       |      $joinStmt
       |      // Give the WS handshake + peers_resp gossip a beat to settle.
       |      sendAfter(1500, s, "elect")
       |      receive { case "elect" =>
       |        electLeader()
       |        // Let Bully envelopes converge.
       |        sendAfter(1500, s, "report")
       |        receive { case "report" =>
       |          println("LEADER:" + currentLeader())
       |          stop()
       |        }
       |      }
       |    }
       |  }
       |}
       |```
       |""".stripMargin

  private def spawnNode(jar: os.Path, sandbox: os.Path,
                        nodeId: String, port: Int,
                        peerUrl: Option[String]): (Process, java.io.File) =
    val src = nodeSrc(nodeId, port, peerUrl)
    val sscFile = sandbox / s"node-$nodeId.ssc"
    os.write(sscFile, src)
    val outFile = (sandbox / s"node-$nodeId.out").toIO
    val pb = new ProcessBuilder("java", "-jar", jar.toString, sscFile.toString)
      .redirectErrorStream(true)
      .redirectOutput(outFile)
    val proc = pb.start()
    (proc, outFile)

  test("two-node cluster — both observe the same Bully leader"):
    val jar = requireJar()
    val sandbox = os.temp.dir(prefix = "ssc-multinode-")
    var procA: Option[Process] = None
    var procB: Option[Process] = None
    try
      val portA = freePort()
      val portB = freePort()
      // Bully picks the lex-greatest nodeId; "node-b" > "node-a".
      val (pA, outA) = spawnNode(jar, sandbox, "node-a", portA, peerUrl = None)
      procA = Some(pA)
      Thread.sleep(500)   // let A bind its port before B dials
      val (pB, outB) = spawnNode(jar, sandbox, "node-b", portB,
                                  peerUrl = Some(s"ws://127.0.0.1:$portA/_ssc-actors"))
      procB = Some(pB)

      // Both nodes print after ~3.8 s of scheduled work then `stop()`.
      // Wait a bit longer; kill if still running.
      val gotA = pA.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
      val gotB = pB.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
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
