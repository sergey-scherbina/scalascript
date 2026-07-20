package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Program argv smoke tests through the real assembled CLI jar.
 *
 *  Run with: `sbt cli/assembly "cli/testOnly *V2RunArgvCliTest"`
 */
class V2RunArgvCliTest extends AnyFunSuite:

  private val sscJar: Option[os.Path] =
    val cwd = os.pwd

    def jarUnder(root: os.Path): os.Path =
      root / "cli" / "target" / "scala-3.8.3" / "ssc.jar"

    List(jarUnder(cwd), jarUnder(cwd / os.up)).find(os.exists)

  private def requireJar(): os.Path = sscJar.getOrElse:
    cancel("ssc.jar not found - run `sbt cli/assembly` first")

  /** Repo root that holds the staged `bin/lib` tree, derived from the jar location:
   *  `<root>/v1/tools/cli/target/scala-3.8.3/ssc.jar`. */
  private def stagedRoot(jar: os.Path): os.Path = jar / os.up / os.up / os.up / os.up / os.up / os.up

  private def runSsc(cwd: os.Path, args: String*): os.CommandResult =
    val jar = requireJar()
    // `run --v2` reaches the native frontend, which requires a staged installation and
    // reads its layout from `ssc.lib.path` — the property `bin/ssc` sets and a bare
    // `java -jar` does not. Without it the CLI exits 1 with "native frontend requires a
    // staged installation", which is a harness gap, not a product defect. CI stages the
    // tree in the same step that assembles the jar (ci.yml: `cli/assembly installBin`).
    val root = stagedRoot(jar)
    if !os.exists(root / "bin" / "lib" / "native-front") then
      cancel(s"staged bin/lib/native-front not found under $root - run `sbt installBin` first")
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

  private def assertArgvOutput(res: os.CommandResult, label: String): Unit =
    assert(res.exitCode == 0,
      s"$label failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
    assert(res.out.text().trim.linesIterator.toList == List("2", "one", "two"))

  test("run -- separator forwards argv to v2 VM runners"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-run-argv-")
    try
      os.write(sandbox / "args.ssc",
        """# Args
          |
          |```scalascript
          |println(args.length)
          |println(args(0))
          |println(args(1))
          |```
          |""".stripMargin)

      assertArgvOutput(
        runSsc(sandbox, "run", "--v2", "args.ssc", "--", "one", "two"),
        "run --v2 argv"
      )
      assertArgvOutput(
        runSsc(sandbox, "run", "args.ssc", "--", "one", "two"),
        "default run argv"
      )
      assertArgvOutput(
        runSsc(sandbox, "run", "--bytecode", "args.ssc", "--", "one", "two"),
        "run --bytecode argv"
      )
    finally os.remove.all(sandbox)

  test("run --v2 keeps multi-file positionals before separator as source files"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-run-multifile-")
    try
      os.write(sandbox / "first.ssc",
        """# First
          |
          |```scalascript
          |println("first")
          |```
          |""".stripMargin)
      os.write(sandbox / "second.ssc",
        """# Second
          |
          |```scalascript
          |println("second")
          |```
          |""".stripMargin)

      val res = runSsc(sandbox, "run", "--v2", "first.ssc", "second.ssc")
      assert(res.exitCode == 0,
        s"run --v2 multi-file failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      assert(res.out.text().trim.linesIterator.toList == List("first", "second"))
    finally os.remove.all(sandbox)
