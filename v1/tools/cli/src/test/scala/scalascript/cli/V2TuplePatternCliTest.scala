package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** v2 tuple-pattern smoke tests through the real installed CLI launcher.
 *
 *  Run with: `sbt cli/assembly installBin "cli/testOnly *V2TuplePatternCliTest"`
 */
class V2TuplePatternCliTest extends AnyFunSuite:

  // Require BOTH the launcher script AND its backing jar. A bare `sbt test` (without
  // `cli/assembly installBin` first) leaves `bin/ssc` on disk but no
  // `bin/lib/standard/ssc.jar`, so the launcher would fail to load
  // `scalascript.cli.StandardMain` (ClassNotFoundException) and the test would fail HARD
  // instead of skipping. Mirror the sibling CLI tests (AutoResolveCliTest etc.), which
  // `cancel` when the assembled jar is absent. No-op in CI, where the "Compile and assemble
  // ssc.jar" step runs `sbt compile cli/assembly installBin` before "Test via sbt".
  private def launcherReady(sscBin: os.Path): Boolean =
    os.exists(sscBin) && os.exists(sscBin / os.up / "lib" / "standard" / "ssc.jar")

  private val sscLauncher: Option[os.Path] =
    val cwd = os.pwd
    Iterator.iterate(cwd)(_ / os.up).take(8)
      .map(_ / "bin" / "ssc")
      .find(launcherReady)

  private def requireLauncher(): os.Path = sscLauncher.getOrElse:
    cancel("assembled bin/ssc launcher not found - run `sbt cli/assembly installBin` first")

  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    val launcher = requireLauncher()
    val cmd: Seq[os.Shellable] = Seq[os.Shellable](launcher.toString) ++
      args.map(a => a: os.Shellable)
    os.proc(cmd).call(
      cwd = cwd,
      check = false,
      stderr = os.Pipe,
      stdout = os.Pipe,
      timeout = 15000
    )

  test("typed tuple patterns bind names under default v2 runner"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-tuple-pattern-")
    try
      os.write(sandbox / "tuple-pattern.ssc",
        """# Tuple pattern
          |
          |```scalascript
          |val pair: Any = ("ada", 1)
          |
          |val word =
          |  pair match
          |    case (w: String, _: Int) => w
          |    case _ => "fallback"
          |
          |println(word)
          |```
          |""".stripMargin)

      val res = runSsc(sandbox, "run", "tuple-pattern.ssc")
      assert(res.exitCode == 0,
        s"default v2 tuple-pattern run failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      assert(res.out.text().trim == "ada")
    finally os.remove.all(sandbox)

  test("nested tuple patterns inside constructors bind names under default v2 runner"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-nested-tuple-pattern-")
    try
      os.write(sandbox / "nested-tuple-pattern.ssc",
        """# Nested tuple pattern
          |
          |```scalascript
          |val hit: Option[Any] = Some(("ada", 1))
          |
          |val count =
          |  hit match
          |    case Some((_, found: Int)) => found
          |    case _ => 0
          |
          |println(count)
          |```
          |""".stripMargin)

      val res = runSsc(sandbox, "run", "nested-tuple-pattern.ssc")
      assert(res.exitCode == 0,
        s"default v2 nested tuple-pattern run failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      assert(res.out.text().trim == "1")
    finally os.remove.all(sandbox)

  test("tuple val destructuring preserves wildcard field positions under default v2 runner"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-tuple-val-destructure-")
    try
      os.write(sandbox / "tuple-val-destructure.ssc",
        """# Tuple val destructuring
          |
          |```scalascript
          |val (_, _, name, _) = ("customer", "c1", "Ada", "")
          |println(name)
          |```
          |""".stripMargin)

      val res = runSsc(sandbox, "run", "tuple-val-destructure.ssc")
      assert(res.exitCode == 0,
        s"default v2 tuple-val destructuring run failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      assert(res.out.text().trim == "Ada")
    finally os.remove.all(sandbox)

  test("map-reduce worker calls are not hoisted before handler registration"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-mapreduce-hoist-")
    try
      os.write(sandbox / "mapreduce-hoist.ssc",
        """# MapReduce hoist
          |
          |[NamedHandler, HandlerRegistry](std/mapreduce/handlers.ssc)
          |
          |[FlatMapOp, MapOp, FilterOp, Stage, WorkerProtocol](std/mapreduce/distributed.ssc)
          |
          |```scalascript
          |HandlerRegistry.clear()
          |HandlerRegistry.register(NamedHandler("emit", (line: String) => List(line)))
          |HandlerRegistry.register(NamedHandler("tag", (w: String) => (w, 1)))
          |HandlerRegistry.register(NamedHandler("keep", (_: Any) => true))
          |
          |println(HandlerRegistry.registeredNames().length)
          |
          |val stage = Stage(List(
          |  FlatMapOp("emit"),
          |  MapOp("tag"),
          |  FilterOp("keep")
          |))
          |
          |val out = WorkerProtocol.applyStage(stage, List("ada"))
          |println(out.length)
          |```
          |""".stripMargin)

      val res = runSsc(sandbox, "run", "mapreduce-hoist.ssc")
      assert(res.exitCode == 0,
        s"default v2 map-reduce hoist run failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      assert(res.out.text().trim.linesIterator.toList == List("3", "1"))
    finally os.remove.all(sandbox)
