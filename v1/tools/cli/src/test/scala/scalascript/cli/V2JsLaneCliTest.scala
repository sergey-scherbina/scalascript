package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** Opt-in v2 JS lane smoke tests through the real installed CLI launcher.
 *
 *  Run with: `sbt cli/assembly installBin "cli/testOnly *V2JsLaneCliTest"`
 */
class V2JsLaneCliTest extends AnyFunSuite:

  private val sscTools: Option[os.Path] =
    val cwd = os.pwd
    Iterator.iterate(cwd)(_ / os.up).take(8)
      .map(_ / "bin" / "ssc-tools")
      .find(os.exists)

  private def requireLauncher(): os.Path = sscTools.getOrElse:
    cancel("bin/ssc-tools not found - run `sbt cli/assembly installBin` first")

  private def requireNode(): Unit =
    val ok = scala.util.Try {
      os.proc("node", "--version").call(check = false, stdout = os.Pipe, stderr = os.Pipe).exitCode == 0
    }.getOrElse(false)
    if !ok then cancel("node not found on PATH")

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

  test("run-js --v2 routes through the v2 CoreIR JS lane"):
    requireNode()
    val sandbox = os.temp.dir(prefix = "ssc-v2-js-lane-")
    try
      val src = sandbox / "hello.ssc"
      os.write(src,
        """# Hello
          |
          |```scalascript
          |println("hello from js")
          |```
          |""".stripMargin)

      val legacy = runSsc(sandbox, "run-js", "hello.ssc")
      val v2     = runSsc(sandbox, "run-js", "--v2", "hello.ssc")

      assert(legacy.exitCode == 0,
        s"legacy run-js failed: exit=${legacy.exitCode}\nstdout=${legacy.out.text()}\nstderr=${legacy.err.text()}")
      assert(v2.exitCode == 0,
        s"run-js --v2 failed: exit=${v2.exitCode}\nstdout=${v2.out.text()}\nstderr=${v2.err.text()}")
      assert(legacy.out.text().trim == "hello from js")
      assert(v2.out.text().trim == "hello from js")

      val argsSrc = sandbox / "args.ssc"
      os.write(argsSrc,
        """# Args
          |
          |```scalascript
          |println(args.length)
          |println(args(0))
          |println(args(1))
          |```
          |""".stripMargin)

      val withArgs = runSsc(sandbox, "run-js", "--v2", "args.ssc", "one", "two")
      assert(withArgs.exitCode == 0,
        s"run-js --v2 argv failed: exit=${withArgs.exitCode}\nstdout=${withArgs.out.text()}\nstderr=${withArgs.err.text()}")
      assert(withArgs.out.text().trim.linesIterator.toList == List("2", "one", "two"))
    finally os.remove.all(sandbox)

  test("run-js --v2 dispatches an imported explicit companion (__mk_method_obj__)"):
    // An imported case class with an explicit companion emits a __mk_method_obj__
    // CoreIR primitive; the v2 JS backend had no lowering for it, so Node threw
    // `unimplemented primitive: __mk_method_obj__` at module load.
    // Regression for v2-js-imported-method-object-primitive.
    requireNode()
    val sandbox = os.temp.dir(prefix = "ssc-v2-js-methodobj-")
    try
      os.write(sandbox / "box.ssc",
        """```scalascript
          |case class Box(value: Int)
          |
          |object Box:
          |  def zero: Box = Box(0)
          |  def of(n: Int): Box = Box(n)
          |  def add(a: Box, b: Int): Box = Box(a.value + b)
          |```
          |""".stripMargin)
      os.write(sandbox / "main.ssc",
        """[Box](box.ssc)
          |
          |```scalascript
          |val z = Box.zero
          |val a = Box.of(5)
          |val b = Box.add(a, 3)
          |println(z.value)
          |println(a.value)
          |println(b.value)
          |```
          |""".stripMargin)
      val res = runSsc(sandbox, "run-js", "--v2", "main.ssc")
      assert(res.exitCode == 0,
        s"run-js --v2 companion failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      assert(res.out.text().trim.linesIterator.toList == List("0", "5", "8"))
    finally os.remove.all(sandbox)
