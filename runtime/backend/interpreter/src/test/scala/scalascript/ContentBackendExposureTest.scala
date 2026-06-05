package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.codegen.{JsGen, JvmGen}
import scalascript.parser.Parser

class ContentBackendExposureTest extends AnyFunSuite with Matchers:

  private lazy val repoRoot: os.Path =
    val cwd = os.pwd
    Iterator.iterate(cwd)(_ / os.up)
      .takeWhile(p => p != p / os.up)
      .find(p => os.exists(p / "build.sbt"))
      .getOrElse(cwd)

  private lazy val source: String =
    os.read(repoRoot / "tests" / "conformance" / "content-introspection.ssc")

  private lazy val expected: String =
    os.read(repoRoot / "tests" / "conformance" / "expected" / "content-introspection.txt").stripTrailing

  test("JS codegen exposes std/content helpers from Markdown content"):
    assume(ProcTestUtil.commandOk("node"), "node not available")
    val module = Parser.parse(source)
    val runtime = JsGen.generateRuntime(JsGen.detectCapabilities(module, Some(repoRoot)))
    val userCode = JsGen.generate(module, Some(repoRoot))
    val tmp = os.temp(runtime + "\n" + userCode + "\n", suffix = ".cjs", deleteOnExit = true)
    val result = os.proc("node", tmp.toString).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    assert(result.exitCode == 0, s"node failed:\n${result.err.text()}")
    result.out.text().stripTrailing shouldBe expected

  test("JVM codegen exposes std/content helpers from Markdown content"):
    assume(ProcTestUtil.commandOk("scala-cli"), "scala-cli not available")
    val module = Parser.parse(source)
    val scala = "//> using scala 3.8.3\n" + JvmGen.generate(module, Some(repoRoot))
    val tmp = os.temp(scala, suffix = ".sc", deleteOnExit = true)
    val result = os.proc("scala-cli", "run", tmp.toString).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    assert(result.exitCode == 0, s"scala-cli failed:\n${result.err.text()}")
    result.out.text().stripTrailing shouldBe expected
