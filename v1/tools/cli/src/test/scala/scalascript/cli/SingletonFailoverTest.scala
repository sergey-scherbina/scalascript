package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Spawns a 3-node cluster wiring `Singleton.use` on every node, lets
 *  Bully elect node-c as initial leader, has any node send a "tick" so
 *  the counter on node-c logs `COUNTER:1`, then SIGKILLs node-c.
 *  Survivors should re-elect (node-b wins), migrate the singleton, and
 *  log a fresh `COUNTER:1` (starts from zero on the new owner).
 *
 *  Validates the cluster-wide singleton's failover semantics: at-most-
 *  one liveness with a brief mid-failover gap, no state migration. */
class SingletonFailoverTest extends AnyFunSuite:

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

  /** Node script: joins the cluster, wires Singleton.use, fires a
   *  burst of Singleton.send calls so each surviving instance sees
   *  ticks. */
  private def nodeSrc(nodeId: String, port: Int, peerUrls: List[String],
                      stdRepoRoot: String): String =
    val joinList = peerUrls.map(u => s"\"$u\"").mkString(", ")
    val joinStmt =
      if peerUrls.isEmpty then "()"
      else s"joinCluster(List($joinList))"
    // Relative path back up to runtime/std/cluster/singleton.ssc — imports
    // must be relative to the .ssc file's directory, and our scripts
    // live at <repo>/tools/cli/target/ssc-singleton-failover/.  Four `..`
    // hops up gets us to the repo root.
    val singletonPath = "../../../../runtime/std/cluster/singleton.ssc"
    val _ = stdRepoRoot
    s"""---
       |name: node-$nodeId
       |---
       |
       |# Singleton-failover node $nodeId
       |
       |[Singleton]($singletonPath)
       |
       |```scalascript
       |runActors {
       |  startNode("$nodeId", "ws://127.0.0.1:$port/_ssc-actors")
       |  serveAsync($port)
       |  setReconnectPolicy(300, 1500)
       |  setAutoReelect(true)
       |  setHeartbeatTimeout(1500, 2500)
       |
       |  // Register the singleton.  Bootstrap path on the elected
       |  // leader logs each tick; non-leaders' supervisors stay idle.
       |  Singleton.use("global-counter", { () =>
       |    spawn { () =>
       |      var n: Int = 0
       |      while true do
       |        receive {
       |          case "tick" =>
       |            n = n + 1
       |            println("COUNTER:$nodeId:" + n)
       |        }
       |    }
       |  })
       |
       |  spawn { () =>
       |    val s = self()
       |    sendAfter(800, s, "join")
       |    receive { case "join" =>
       |      $joinStmt
       |      sendAfter(3000, s, "elect")
       |      receive { case "elect" =>
       |        electLeader()
       |        sendAfter(2000, s, "tick1")
       |        receive { case "tick1" =>
       |          // Pre-kill tick.  Whichever node is leader logs
       |          // COUNTER:<leader>:1.
       |          val ok1 = Singleton.send("global-counter", "tick")
       |          println("SENT1:" + ok1)
       |          // Sit through the leader-kill + re-elect + singleton
       |          // migration window.  30 s gives slow / GC-thrashing CI headroom
       |          // for the survivor to win re-election and spawn the migrated
       |          // instance before this tick lands (was 18 s; under a 56%-GC
       |          // 2-hour CI run migration overran it — ci-singleton-failover-window).
       |          sendAfter(30000, s, "tick2")
       |          receive { case "tick2" =>
       |            val ok2 = Singleton.send("global-counter", "tick")
       |            println("SENT2:" + ok2)
       |            sendAfter(1500, s, "tick3")
       |            receive { case "tick3" =>
       |              val ok3 = Singleton.send("global-counter", "tick")
       |              println("SENT3:" + ok3)
       |              stop()
       |            }
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
                        stdRepoRoot: String): (Process, java.io.File) =
    val src = nodeSrc(nodeId, port, peerUrls, stdRepoRoot)
    val sscFile = sandbox / s"node-$nodeId.ssc"
    os.write(sscFile, src)
    val outFile = (sandbox / s"node-$nodeId.out").toIO
    val pb = new ProcessBuilder("java", "-jar", jar.toString, "--v1", sscFile.toString)
      .redirectErrorStream(true)
      .redirectOutput(outFile)
      // Run under the canonical repo root so the relative module
      // path `std/cluster/singleton.ssc` resolves.
      .directory(new java.io.File(stdRepoRoot))
    pb.environment().put("SSC_CLUSTER_DEBUG", "1")
    val proc = pb.start()
    (proc, outFile)

  test("Singleton migrates on leader-kill, new instance receives ticks"):
    // Real-WS 3-node kill/re-elect/migrate on fixed sendAfter windows —
    // flaky under CI contention; retry the whole scenario up to 3×.
    ClusterTestSupport.retrying(3)(failoverScenario())

  private def failoverScenario(): Unit =
    val jar = requireJar()
    // Resolve the worktree (or canonical) repo root that owns `runtime/std/`
    // so the node processes can import `runtime/std/cluster/singleton.ssc`
    // on a relative path.  Walk up from os.pwd looking for a sibling
    // `runtime/std/cluster/singleton.ssc`.
    val stdRepoRoot: String =
      def findStd(p: os.Path, depth: Int): Option[os.Path] =
        if depth == 0 then None
        else if os.exists(p / "runtime" / "std" / "cluster" / "singleton.ssc") then Some(p)
        else findStd(p / os.up, depth - 1)
      findStd(os.pwd, 6).map(_.toString)
        .getOrElse(fail("can't locate runtime/std/cluster/singleton.ssc above os.pwd"))
    val sandbox = os.pwd / "target" / "ssc-singleton-failover"
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
        val (proc, out) = spawnNode(jar, sandbox, nid, p, peers, stdRepoRoot)
        procs    += proc
        outFiles += out
        Thread.sleep(200)

      // Wait for pre-kill SENT1 to print on every node.
      def readAll(): List[String] = outFiles.map(f =>
        scala.io.Source.fromFile(f).mkString
      ).toList
      val deadline1 = System.currentTimeMillis() + 18_000L
      while System.currentTimeMillis() < deadline1 &&
            !readAll().forall(_.contains("SENT1:")) do
        Thread.sleep(500)

      val phase1 = readAll()
      assert(phase1.forall(_.contains("SENT1:true")),
        s"SENT1 didn't propagate to all nodes: ${phase1.map(_.contains("SENT1:true"))}")
      // node-c is the initial leader (lex-greatest), so the counter
      // line should be on node-c's output.
      val nodecOut = phase1(2)
      assert(nodecOut.contains("COUNTER:node-c:1"),
        s"expected COUNTER:node-c:1 on node-c, got:\n$nodecOut")

      // Kill node-c (the elected leader).
      procs(2).destroyForcibly()
      info("killed node-c")

      // Wait for both survivors to migrate the singleton and log a
      // fresh COUNTER line.
      // >= tick2 window (30 s) + migration + tick delivery, with GC-thrash margin.
      val deadline2 = System.currentTimeMillis() + 50_000L
      def survivorTexts() = List(0, 1).map(i =>
        scala.io.Source.fromFile(outFiles(i)).mkString
      )
      while System.currentTimeMillis() < deadline2 &&
            !survivorTexts().exists(_.contains("COUNTER:node-b:1")) do
        Thread.sleep(500)

      val nodebOut = survivorTexts()(1)
      info(s"node-b output tail:\n${nodebOut.linesIterator.toList.takeRight(15).mkString("\n")}")
      assert(nodebOut.contains("COUNTER:node-b:1"),
        "node-b never spawned its migrated singleton instance")
      // Counter started fresh: COUNTER:node-b:1, not :2 — confirms
      // no-state-migration semantics.
      assert(!nodebOut.contains("COUNTER:node-b:0"))
    finally
      procs.foreach(_.destroyForcibly())
