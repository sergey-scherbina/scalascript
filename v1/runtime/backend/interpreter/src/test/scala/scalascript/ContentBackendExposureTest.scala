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

  private lazy val toMarkdownSource: String =
    os.read(repoRoot / "tests" / "conformance" / "content-to-markdown.ssc")

  private lazy val toMarkdownExpected: String =
    os.read(repoRoot / "tests" / "conformance" / "expected" / "content-to-markdown.txt").stripTrailing

  private lazy val linkedNamespacesSource: String =
    os.read(repoRoot / "tests" / "conformance" / "content-linked-namespaces.ssc")

  private lazy val linkedNamespacesExpected: String =
    os.read(repoRoot / "tests" / "conformance" / "expected" / "content-linked-namespaces.txt").stripTrailing

  private lazy val tablesSource: String =
    os.read(repoRoot / "tests" / "conformance" / "content-tables.ssc")

  private lazy val tablesExpected: String =
    os.read(repoRoot / "tests" / "conformance" / "expected" / "content-tables.txt").stripTrailing

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
    val result = os.proc("scala-cli", "run", "--server=false", tmp.toString).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    assert(result.exitCode == 0, s"scala-cli failed:\n${result.err.text()}")
    result.out.text().stripTrailing shouldBe expected

  test("JS codegen renders std/content values back to Markdown"):
    assume(ProcTestUtil.commandOk("node"), "node not available")
    val module = Parser.parse(toMarkdownSource)
    val runtime = JsGen.generateRuntime(JsGen.detectCapabilities(module, Some(repoRoot)))
    val userCode = JsGen.generate(module, Some(repoRoot))
    val tmp = os.temp(runtime + "\n" + userCode + "\n", suffix = ".cjs", deleteOnExit = true)
    val result = os.proc("node", tmp.toString).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    assert(result.exitCode == 0, s"node failed:\n${result.err.text()}")
    result.out.text().stripTrailing shouldBe toMarkdownExpected

  test("JVM codegen renders std/content values back to Markdown"):
    assume(ProcTestUtil.commandOk("scala-cli"), "scala-cli not available")
    val module = Parser.parse(toMarkdownSource)
    val scala = "//> using scala 3.8.3\n" + JvmGen.generate(module, Some(repoRoot))
    val tmp = os.temp(scala, suffix = ".sc", deleteOnExit = true)
    val result = os.proc("scala-cli", "run", "--server=false", tmp.toString).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    assert(result.exitCode == 0, s"scala-cli failed:\n${result.err.text()}")
    result.out.text().stripTrailing shouldBe toMarkdownExpected

  test("JS codegen exposes directly imported Markdown content namespaces"):
    assume(ProcTestUtil.commandOk("node"), "node not available")
    val module = Parser.parse(linkedNamespacesSource)
    val runtime = JsGen.generateRuntime(JsGen.detectCapabilities(module, Some(repoRoot)))
    val userCode = JsGen.generate(module, Some(repoRoot))
    val tmp = os.temp(runtime + "\n" + userCode + "\n", suffix = ".cjs", deleteOnExit = true)
    val result = os.proc("node", tmp.toString).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    assert(result.exitCode == 0, s"node failed:\n${result.err.text()}")
    result.out.text().stripTrailing shouldBe linkedNamespacesExpected

  test("JVM codegen exposes directly imported Markdown content namespaces"):
    assume(ProcTestUtil.commandOk("scala-cli"), "scala-cli not available")
    val module = Parser.parse(linkedNamespacesSource)
    val scala = "//> using scala 3.8.3\n" + JvmGen.generate(module, Some(repoRoot))
    val tmp = os.temp(scala, suffix = ".sc", deleteOnExit = true)
    val result = os.proc("scala-cli", "run", "--server=false", tmp.toString).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    assert(result.exitCode == 0, s"scala-cli failed:\n${result.err.text()}")
    result.out.text().stripTrailing shouldBe linkedNamespacesExpected

  test("JS codegen exposes Markdown content tables"):
    assume(ProcTestUtil.commandOk("node"), "node not available")
    val module = Parser.parse(tablesSource)
    val runtime = JsGen.generateRuntime(JsGen.detectCapabilities(module, Some(repoRoot)))
    val userCode = JsGen.generate(module, Some(repoRoot))
    val tmp = os.temp(runtime + "\n" + userCode + "\n", suffix = ".cjs", deleteOnExit = true)
    val result = os.proc("node", tmp.toString).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    assert(result.exitCode == 0, s"node failed:\n${result.err.text()}")
    result.out.text().stripTrailing shouldBe tablesExpected

  test("JVM codegen exposes Markdown content tables"):
    assume(ProcTestUtil.commandOk("scala-cli"), "scala-cli not available")
    val module = Parser.parse(tablesSource)
    val scala = "//> using scala 3.8.3\n" + JvmGen.generate(module, Some(repoRoot))
    val tmp = os.temp(scala, suffix = ".sc", deleteOnExit = true)
    val result = os.proc("scala-cli", "run", "--server=false", tmp.toString).call(check = false, stdout = os.Pipe, stderr = os.Pipe)
    assert(result.exitCode == 0, s"scala-cli failed:\n${result.err.text()}")
    result.out.text().stripTrailing shouldBe tablesExpected
