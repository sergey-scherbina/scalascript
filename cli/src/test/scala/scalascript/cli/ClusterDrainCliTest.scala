package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Spawn a real ssc.jar node, run `ssc cluster drain <url>` against
 *  it, then `ssc cluster status` to confirm the drain flag flipped.
 *  Mirrors ClusterStatusCliTest's shape. */
class ClusterDrainCliTest extends AnyFunSuite:

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

  test("ssc cluster drain toggles the node's drain flag"):
    val jar = requireJar()
    val sandbox = os.pwd / "target" / "ssc-cluster-drain-cli"
    os.remove.all(sandbox)
    os.makeDir.all(sandbox)
    var proc: Option[Process] = None
    try
      val port = freePort()
      val src = s"""---
                   |name: drain-cli-node
                   |---
                   |
                   |# Drain CLI node
                   |
                   |```scalascript
                   |runActors {
                   |  startNode("drain-cli-node", "ws://127.0.0.1:$port/_ssc-actors")
                   |  serveAsync($port)
                   |  spawn { () =>
                   |    val s = self()
                   |    sendAfter(25000, s, "stop")
                   |    receive { case "stop" => stop() }
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

      // Wait for the WS server to bind.
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
      Thread.sleep(400)

      // Enable drain via CLI.
      val drainOut = sandbox / "drain.out"
      val drainPb = new ProcessBuilder(
        "java", "-jar", jar.toString,
        "cluster", "drain", s"http://127.0.0.1:$port"
      ).redirectErrorStream(true).redirectOutput(drainOut.toIO)
      val drainProc = drainPb.start()
      assert(drainProc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS),
        "ssc cluster drain timed out")
      assert(drainProc.exitValue() == 0,
        s"ssc cluster drain exited ${drainProc.exitValue()}; output:\n${os.read(drainOut)}")
      val drainText = os.read(drainOut)
      info(s"drain output:\n$drainText")
      assert(drainText.contains("enabled drain"))
      assert(drainText.contains("\"drainingSelf\":true"))

      // Verify via status that drainingSelf=true.
      val statusOut = sandbox / "status.out"
      val statusPb = new ProcessBuilder(
        "java", "-jar", jar.toString,
        "cluster", "status", s"http://127.0.0.1:$port"
      ).redirectErrorStream(true).redirectOutput(statusOut.toIO)
      val statusProc = statusPb.start()
      assert(statusProc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
      assert(statusProc.exitValue() == 0)
      val statusText = os.read(statusOut)
      info(s"status output:\n$statusText")
      assert(statusText.contains("drainingSelf: true"))

      // Disable drain.
      val drainOffOut = sandbox / "drain-off.out"
      val drainOffPb = new ProcessBuilder(
        "java", "-jar", jar.toString,
        "cluster", "drain", s"http://127.0.0.1:$port", "--off"
      ).redirectErrorStream(true).redirectOutput(drainOffOut.toIO)
      val drainOffProc = drainOffPb.start()
      assert(drainOffProc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
      assert(drainOffProc.exitValue() == 0)
      val drainOffText = os.read(drainOffOut)
      info(s"drain --off output:\n$drainOffText")
      assert(drainOffText.contains("disabled drain"))
      assert(drainOffText.contains("\"drainingSelf\":false"))
    finally
      proc.foreach(_.destroyForcibly())
