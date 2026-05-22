package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Spawn a real ssc.jar node, drive an event (electLeader self-claim),
 *  then run `ssc cluster events <url>` and assert the LeaderElected
 *  JSON line appears in the dumped ring buffer. */
class ClusterEventsCliTest extends AnyFunSuite:

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

  test("ssc cluster events dumps the recent-events ring buffer"):
    val jar = requireJar()
    val sandbox = os.pwd / "target" / "ssc-cluster-events-cli"
    os.remove.all(sandbox)
    os.makeDir.all(sandbox)
    var proc: Option[Process] = None
    try
      val port = freePort()
      val src = s"""---
                   |name: events-cli-node
                   |---
                   |
                   |# Events CLI node
                   |
                   |```scalascript
                   |runActors {
                   |  startNode("events-cli-node", "ws://127.0.0.1:$port/_ssc-actors")
                   |  serveAsync($port)
                   |  spawn { () =>
                   |    val s = self()
                   |    sendAfter(200, s, "elect")
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

      // Wait for the WS server to bind + the elect to fire.
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
      Thread.sleep(700)

      val cliOut = sandbox / "events.out"
      val cliPb = new ProcessBuilder(
        "java", "-jar", jar.toString,
        "cluster", "events", s"http://127.0.0.1:$port"
      ).redirectErrorStream(true).redirectOutput(cliOut.toIO)
      val cliProc = cliPb.start()
      assert(cliProc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
      assert(cliProc.exitValue() == 0,
        s"ssc cluster events exited ${cliProc.exitValue()}: ${os.read(cliOut)}")

      val text = os.read(cliOut)
      info(s"events output:\n$text")
      assert(text.contains("\"type\":\"LeaderElected\""),
        "expected a LeaderElected event in the dumped ring buffer")
      assert(text.contains("\"nodeId\":\"events-cli-node\""))
    finally
      proc.foreach(_.destroyForcibly())
