package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** v2 actor-runtime smoke tests through the real assembled CLI jar.
 *
 *  These tests intentionally spawn `ssc.jar` instead of calling the bridge
 *  in-process: the regression was a production-only silent success where the
 *  default / --v2 fat-jar path exited 0 before a scheduled actor message fired.
 *
 *  Run with: `sbt cli/assembly "cli/testOnly *V2ActorCliTest"`
 */
class V2ActorCliTest extends AnyFunSuite:

  private val sscJar: Option[os.Path] =
    val cwd = os.pwd

    def jarUnder(root: os.Path): os.Path =
      root / "cli" / "target" / "scala-3.8.3" / "ssc.jar"

    def findCanonicalRepo(p: os.Path): Option[os.Path] =
      val parts = p.segments.toList
      val idx = parts.lastIndexOf(".claude")
      if idx >= 0 && idx + 1 < parts.length && parts(idx + 1) == "worktrees" then
        Some(os.Path("/" + parts.take(idx).mkString("/")))
      else None

    val candidates = List(
      jarUnder(cwd),
      jarUnder(cwd / os.up)
    ) ++ findCanonicalRepo(cwd).map(jarUnder).toList

    candidates.find(os.exists)

  private def requireJar(): os.Path = sscJar.getOrElse:
    cancel("ssc.jar not found — run `sbt cli/assembly` first")

  /** Repo root that holds the staged `bin/lib` tree, derived from the jar location:
   *  `<root>/v1/tools/cli/target/scala-3.8.3/ssc.jar`. */
  private def stagedRoot(jar: os.Path): os.Path = jar / os.up / os.up / os.up / os.up / os.up / os.up

  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    val jar = requireJar()
    // The default / `--v2` runner reaches the native frontend, which requires a
    // staged installation and reads its layout from `ssc.lib.path` — the property
    // `bin/ssc` sets and a bare `java -jar` does not. Without it the CLI exits 1
    // with "native frontend requires a staged installation", a harness gap rather
    // than a product defect. CI stages the tree in the same step that assembles
    // the jar (ci.yml: `cli/assembly installBin`).
    val root = stagedRoot(jar)
    if !os.exists(root / "bin" / "lib" / "native-front") then
      cancel(s"staged bin/lib/native-front not found under $root — run `sbt installBin` first")
    val cmd: Seq[os.Shellable] = Seq[os.Shellable](
      "java", s"-Dssc.lib.path=$root", "-jar", jar.toString) ++
      args.map(a => a: os.Shellable)
    os.proc(cmd).call(
      cwd = cwd,
      check = false,
      stderr = os.Pipe,
      stdout = os.Pipe,
      timeout = 15000
    )

  test("default and --v2 deliver sendAfter actor timers"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-actors-")
    try
      val src = sandbox / "sendafter.ssc"
      os.write(src,
        """runActors {
          |  val me = spawn { () =>
          |    val pid = self()
          |    sendAfter(10, pid, "hello")
          |    receive { case msg => println("got: " + msg) }
          |  }
          |}
          |""".stripMargin)

      val defaultRun = runSsc(sandbox, "sendafter.ssc")
      val explicitV2 = runSsc(sandbox, "--v2", "sendafter.ssc")

      assert(defaultRun.exitCode == 0,
        s"default v2 run failed: exit=${defaultRun.exitCode}\nstdout=${defaultRun.out.text()}\nstderr=${defaultRun.err.text()}")
      assert(explicitV2.exitCode == 0,
        s"--v2 run failed: exit=${explicitV2.exitCode}\nstdout=${explicitV2.out.text()}\nstderr=${explicitV2.err.text()}")

      assert(defaultRun.out.text().trim == "got: hello",
        s"default v2 silently lost the scheduled actor message; stdout=${defaultRun.out.text()} stderr=${defaultRun.err.text()}")
      assert(explicitV2.out.text().trim == "got: hello",
        s"--v2 silently lost the scheduled actor message; stdout=${explicitV2.out.text()} stderr=${explicitV2.err.text()}")
    finally os.remove.all(sandbox)
