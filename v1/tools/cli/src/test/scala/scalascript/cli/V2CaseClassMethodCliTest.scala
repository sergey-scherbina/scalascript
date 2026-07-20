package scalascript.cli

import org.scalatest.funsuite.AnyFunSuite

/** v2 case-class method smoke tests through the real assembled CLI jar.
 *
 *  Run with: `sbt cli/assembly "cli/testOnly *V2CaseClassMethodCliTest"`
 */
class V2CaseClassMethodCliTest extends AnyFunSuite:

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
    // The default `run` reaches the native frontend, which requires a staged
    // installation and reads its layout from `ssc.lib.path` — the property
    // `bin/ssc` sets and a bare `java -jar` does not. Without it the CLI exits 1
    // with "native frontend requires a staged installation", which is a harness
    // gap, not a product defect. CI stages the tree in the same step that
    // assembles the jar (ci.yml: `cli/assembly installBin`).
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

  test("case-class methods can read fields and ordinary parameters under default v2 runner"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-case-class-method-basic-")
    try
      os.write(sandbox / "case-class-method-basic.ssc",
        """# Case-class methods
          |
          |```scalascript
          |case class Box(value: Int):
          |  def inc(by: Int): Int = value + by
          |  def label: String = "v=" + value
          |
          |val b = Box(41)
          |println(b.inc(1))
          |println(b.label)
          |```
          |""".stripMargin)

      val res = runSsc(sandbox, "run", "case-class-method-basic.ssc")
      assert(res.exitCode == 0,
        s"default v2 case-class method run failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      assert(res.out.text().trim.linesIterator.toList == List("42", "v=41"))
    finally os.remove.all(sandbox)

  test("case-class method dispatch is tag-aware and fields keep precedence"):
    val sandbox = os.temp.dir(prefix = "ssc-v2-case-class-method-dispatch-")
    try
      os.write(sandbox / "case-class-method-dispatch.ssc",
        """# Case-class method dispatch
          |
          |```scalascript
          |case class User(name: String)
          |
          |case class Node(address: String):
          |  def name: String = "node:" + address
          |
          |case class A(x: Int):
          |  def show: String = "A" + x
          |
          |case class B(x: Int):
          |  def show: String = "B" + x
          |
          |println(User("Ada").name)
          |println(Node("worker@host").name)
          |println(A(1).show)
          |println(B(2).show)
          |```
          |""".stripMargin)

      val res = runSsc(sandbox, "run", "case-class-method-dispatch.ssc")
      assert(res.exitCode == 0,
        s"default v2 case-class method dispatch run failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      assert(res.out.text().trim.linesIterator.toList == List("Ada", "node:worker@host", "A1", "B2"))
    finally os.remove.all(sandbox)

  test("std mapreduce Cluster.close executes without a stub under default v2 runner"):
    val repoRoot = os.pwd
    val sandbox = os.temp.dir(prefix = "ssc-v2-cluster-close-", dir = repoRoot / "target")
    try
      os.write(sandbox / "cluster-close.ssc",
        """# Cluster close
          |
          |```scalascript
          |import std.mapreduce.*
          |
          |val cluster = Cluster(List())
          |cluster.close()
          |println("closed")
          |```
          |""".stripMargin)

      val res = runSsc(repoRoot, "run", (sandbox / "cluster-close.ssc").toString)
      assert(res.exitCode == 0,
        s"default v2 cluster close run failed: exit=${res.exitCode}\nstdout=${res.out.text()}\nstderr=${res.err.text()}")
      assert(!res.out.text().contains("Stub(\"Cluster.close\")"))
      assert(res.out.text().trim == "closed")
    finally os.remove.all(sandbox)
