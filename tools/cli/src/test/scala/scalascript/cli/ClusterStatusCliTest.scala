package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Spawns a real `ssc.jar` node and exercises `ssc cluster status <url>`
 *  end-to-end: jar #1 binds the WS server and registers
 *  /_ssc-cluster/status; jar #2 issues `cluster status` against #1 and
 *  the test asserts the human-readable output mentions the live node id
 *  and protocol. */
class ClusterStatusCliTest extends AnyFunSuite:

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

  test("ssc cluster status against a running node"):
    val jar = requireJar()
    val sandbox = os.pwd / "target" / "ssc-cluster-cli"
    os.remove.all(sandbox)
    os.makeDir.all(sandbox)
    var proc: Option[Process] = None
    try
      val port = freePort()
      // The node script: bind the WS server + register
      // /_ssc-cluster/status, claim self as leader, then sit in a
      // receive that we never satisfy so the process stays alive
      // long enough for the CLI to hit it.
      val src = s"""---
                   |name: status-cli-node
                   |---
                   |
                   |# Status CLI node
                   |
                   |```scalascript
                   |runActors {
                   |  startNode("status-cli-node", "ws://127.0.0.1:$port/_ssc-actors")
                   |  serveAsync($port)
                   |  spawn { () =>
                   |    val s = self()
                   |    sendAfter(300, s, "elect")
                   |    receive { case "elect" =>
                   |      electLeader()
                   |      sendAfter(20000, s, "stop")
                   |      receive { case "stop" => stop() }
                   |    }
                   |  }
                   |}
                   |```
                   |""".stripMargin
      val sscFile = sandbox / "node.ssc"
      os.write(sscFile, src)
      val outFile = (sandbox / "node.out").toIO
      val pb = new ProcessBuilder("java", "-jar", jar.toString, sscFile.toString)
        .redirectErrorStream(true).redirectOutput(outFile)
      proc = Some(pb.start())

      // Wait for the server to bind.  The status endpoint is registered
      // synchronously inside startNode → first poll succeeds within 2 s.
      val deadline = System.currentTimeMillis() + 8_000L
      var ready    = false
      while !ready && System.currentTimeMillis() < deadline do
        Thread.sleep(200)
        try
          val s = new java.net.Socket("127.0.0.1", port)
          ready = true
          s.close()
        catch case _: Throwable => ()
      assert(ready, s"node didn't bind port $port in 8 s")
      // Allow the elect → leader path a couple hundred ms.
      Thread.sleep(800)

      // Run `ssc cluster status http://127.0.0.1:<port>` and capture stdout.
      val cliOut = sandbox / "cli.out"
      val cliPb = new ProcessBuilder(
        "java", "-jar", jar.toString,
        "cluster", "status", s"http://127.0.0.1:$port"
      ).redirectErrorStream(true).redirectOutput(cliOut.toIO)
      val cliProc = cliPb.start()
      val finished = cliProc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
      if !finished then cliProc.destroyForcibly()
      assert(finished, "ssc cluster status timed out")
      assert(cliProc.exitValue() == 0,
        s"ssc cluster status exited ${cliProc.exitValue()}; output:\n${os.read(cliOut)}")

      val out = os.read(cliOut)
      info(s"cli output:\n$out")
      assert(out.contains("node:        status-cli-node"))
      assert(out.contains("protocol:    bully"))
      // leader should be self once electLeader fired
      assert(out.contains("leader:      status-cli-node"))
      assert(out.contains("drainingSelf: false"))
    finally
      proc.foreach(_.destroyForcibly())
