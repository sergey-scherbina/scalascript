package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Spawn a real ssc.jar node that self-elects, then run
 *  `ssc cluster step-down <url>` against it and verify the response
 *  reports a successful step-down + subsequent `ssc cluster status`
 *  shows `leader=""`. */
class ClusterStepDownCliTest extends AnyFunSuite:

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

  test("ssc cluster step-down on the elected leader; status shows no leader"):
    val jar = requireJar()
    val sandbox = os.pwd / "target" / "ssc-step-down-cli"
    os.remove.all(sandbox)
    os.makeDir.all(sandbox)
    var proc: Option[Process] = None
    try
      val port = freePort()
      val src = s"""---
                   |name: step-down-cli-node
                   |---
                   |
                   |# Step-down CLI node
                   |
                   |```scalascript
                   |runActors {
                   |  startNode("step-down-cli-node", "ws://127.0.0.1:$port/_ssc-actors")
                   |  serveAsync($port)
                   |  spawn { () =>
                   |    val s = self()
                   |    sendAfter(300, s, "elect")
                   |    receive { case "elect" =>
                   |      electLeader()
                   |      sendAfter(30000, s, "stop")
                   |      receive { case "stop" => stop() }
                   |    }
                   |  }
                   |}
                   |```
                   |""".stripMargin
      val sscFile = sandbox / "node.ssc"
      os.write(sscFile, src)
      val outFile = (sandbox / "node.out").toIO
      val pb = new ProcessBuilder("java", "-jar", jar.toString, "--v1", sscFile.toString)
        .redirectErrorStream(true).redirectOutput(outFile)
      proc = Some(pb.start())

      // Wait for port + first election to land.
      val deadline = System.currentTimeMillis() + 8_000L
      var ready = false
      while !ready && System.currentTimeMillis() < deadline do
        Thread.sleep(200)
        try { val s = new java.net.Socket("127.0.0.1", port); ready = true; s.close() }
        catch case _: Throwable => ()
      assert(ready, "node didn't bind in 8 s")
      Thread.sleep(800) // let elect fire

      // 1. step-down — should succeed (we ARE the leader).
      val sdOut = sandbox / "step-down.out"
      val sdPb = new ProcessBuilder("java", "-jar", jar.toString,
        "cluster", "step-down", s"http://127.0.0.1:$port"
      ).redirectErrorStream(true).redirectOutput(sdOut.toIO)
      val sdProc = sdPb.start()
      assert(sdProc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
      val sdText = os.read(sdOut)
      info(s"step-down output:\n$sdText")
      assert(sdProc.exitValue() == 0,
        s"step-down should have exited 0, got ${sdProc.exitValue()}: $sdText")
      assert(sdText.contains("\"steppedDown\":true"))

      // 2. status — leader should be empty after step-down.
      val statusOut = sandbox / "status.out"
      val statusPb = new ProcessBuilder("java", "-jar", jar.toString,
        "cluster", "status", s"http://127.0.0.1:$port"
      ).redirectErrorStream(true).redirectOutput(statusOut.toIO)
      val statusProc = statusPb.start()
      assert(statusProc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
      val statusText = os.read(statusOut)
      info(s"status output:\n$statusText")
      assert(statusText.contains("leader:      <none>"),
        s"leader should be empty after step-down: $statusText")

      // 3. step-down again — now we're NOT leader, should 409.
      val sd2Out = sandbox / "step-down-2.out"
      val sd2Pb = new ProcessBuilder("java", "-jar", jar.toString,
        "cluster", "step-down", s"http://127.0.0.1:$port"
      ).redirectErrorStream(true).redirectOutput(sd2Out.toIO)
      val sd2Proc = sd2Pb.start()
      assert(sd2Proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
      info(s"second step-down output:\n${os.read(sd2Out)}")
      assert(sd2Proc.exitValue() != 0,
        "second step-down should fail because node is no longer leader")
    finally
      proc.foreach(_.destroyForcibly())
