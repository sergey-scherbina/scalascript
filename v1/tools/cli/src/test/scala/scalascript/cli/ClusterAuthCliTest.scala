package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Spawn a real ssc.jar node with `SSC_CLUSTER_TOKEN=s3cret` in its
 *  env, then verify the CLI:
 *    - rejects (exit != 0) when no --token is supplied,
 *    - succeeds when --token=s3cret is supplied. */
class ClusterAuthCliTest extends AnyFunSuite:

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

  test("CLI auth: no token rejected (401), --token=<t> succeeds"):
    val jar = requireJar()
    val sandbox = os.pwd / "target" / "ssc-cluster-auth-cli"
    os.remove.all(sandbox)
    os.makeDir.all(sandbox)
    var proc: Option[Process] = None
    try
      val port = freePort()
      // Node script: set the token from env at startup (default behaviour).
      val src = s"""---
                   |name: auth-cli-node
                   |---
                   |
                   |# Auth CLI node
                   |
                   |```scalascript
                   |runActors {
                   |  startNode("auth-cli-node", "ws://127.0.0.1:$port/_ssc-actors")
                   |  serveAsync($port)
                   |  spawn { () =>
                   |    val s = self()
                   |    sendAfter(20000, s, "stop")
                   |    receive { case "stop" => stop() }
                   |  }
                   |}
                   |```
                   |""".stripMargin
      val sscFile = sandbox / "node.ssc"
      os.write(sscFile, src)
      val outFile = (sandbox / "node.out").toIO
      val pb = new ProcessBuilder("java", "-jar", jar.toString, "--v1", sscFile.toString)
        .redirectErrorStream(true).redirectOutput(outFile)
      pb.environment().put("SSC_CLUSTER_TOKEN", "s3cret")
      proc = Some(pb.start())

      // Wait for bind.
      val deadline = System.currentTimeMillis() + 8_000L
      var ready    = false
      while !ready && System.currentTimeMillis() < deadline do
        Thread.sleep(200)
        try { val s = new java.net.Socket("127.0.0.1", port); ready = true; s.close() }
        catch case _: Throwable => ()
      assert(ready, s"node didn't bind port $port in 8 s")
      Thread.sleep(400)

      // 1. No token → must fail.
      val noTokOut = sandbox / "no-token.out"
      val noTokPb = new ProcessBuilder(
        "java", "-jar", jar.toString,
        "cluster", "status", s"http://127.0.0.1:$port"
      ).redirectErrorStream(true).redirectOutput(noTokOut.toIO)
      // Strip the test's own SSC_CLUSTER_TOKEN if it leaks in.
      noTokPb.environment().remove("SSC_CLUSTER_TOKEN")
      val noTokProc = noTokPb.start()
      assert(noTokProc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
      info(s"no-token output:\n${os.read(noTokOut)}")
      assert(noTokProc.exitValue() != 0,
        s"no-token CLI should fail; got exit=${noTokProc.exitValue()}")

      // 2. With --token=s3cret → succeeds.
      val tokOut = sandbox / "with-token.out"
      val tokPb = new ProcessBuilder(
        "java", "-jar", jar.toString,
        "cluster", "status", s"http://127.0.0.1:$port", "--token=s3cret"
      ).redirectErrorStream(true).redirectOutput(tokOut.toIO)
      tokPb.environment().remove("SSC_CLUSTER_TOKEN")
      val tokProc = tokPb.start()
      assert(tokProc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS))
      val tokText = os.read(tokOut)
      info(s"with-token output:\n$tokText")
      assert(tokProc.exitValue() == 0,
        s"--token CLI failed: ${tokText}")
      assert(tokText.contains("node:        auth-cli-node"))
    finally
      proc.foreach(_.destroyForcibly())
