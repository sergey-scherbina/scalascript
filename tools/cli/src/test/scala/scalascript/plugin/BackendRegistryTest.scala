package scalascript.compiler.plugin

import scalascript.backend.spi.*
import scalascript.ir.{NormalizedModule, QualifiedName}
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** Exercises ServiceLoader discovery against the bundled backends.
 *  Lives in the `cli` module because that's the only module whose
 *  classpath includes every Backend implementation. */
class BackendRegistryTest extends AnyFunSuite:

  test("ServiceLoader discovers all 4 bundled backends"):
    val ids = BackendRegistry.all.map(_.id).toSet
    val expected = Set("jvm", "js", "scalajs-spa", "int")
    assert(expected.subsetOf(ids), s"expected $expected, got $ids")

  test("lookup by id returns the right backend"):
    assert(BackendRegistry.lookup("jvm").exists(_.displayName.contains("JVM")))
    assert(BackendRegistry.lookup("js").exists(_.displayName.contains("JavaScript")))
    assert(BackendRegistry.lookup("int").exists(_.displayName.contains("Interpreter")))
    assert(BackendRegistry.lookup("scalajs-spa").exists(_.displayName.contains("Scala.js")))

  test("lookup of unknown id returns None"):
    assert(BackendRegistry.lookup("does-not-exist").isEmpty)

  test("interactive subset includes the interpreter"):
    val interactiveIds = BackendRegistry.interactive.map(_.id).toSet
    assert(interactiveIds.contains("int"))

  test("acceptingSource(\"scala\") includes JVM, Interpreter, ScalaJs"):
    val ids = BackendRegistry.acceptingSource("scala").map(_.id).toSet
    assert(ids.contains("jvm"))
    assert(ids.contains("int"))
    assert(ids.contains("scalajs-spa"))
    // JsBackend explicitly does NOT accept "scala" — Scala blocks must
    // go through scalajs-spa via segmented mode (Stage 9 split).
    assert(!ids.contains("js"))

  test("every bundled backend declares spiVersion 0.1.0"):
    val versions = BackendRegistry.all.map(_.spiVersion).distinct
    assert(versions == List("0.1.0"), s"expected one 0.1.0, got $versions")

  test("describe produces a human-readable line per backend"):
    val lines = BackendRegistry.describe.linesIterator.toList
    assert(lines.size >= 4)
    assert(lines.exists(_.contains("jvm")))

  test("BackendRegistry implements PluginRegistry facade"):
    val registry: PluginRegistry = BackendRegistry
    assert(registry.lookup("jvm").exists(_.id == "jvm"))
    assert(registry.listInstalled().exists(_.id == "jvm"))

  test("PluginRegistry install supports direct classpath backend strategy"):
    val registry: PluginRegistry = BackendRegistry
    try
      Await.result(
        registry.install(ClasspathPlugin("scalascript.compiler.plugin.TestInstalledBackend")),
        5.seconds
      )
      assert(registry.lookup("test-installed").exists(_.displayName == "Test Installed Backend"))
      assert(registry.listInstalled().exists(_.id == "test-installed"))
    finally BackendRegistry.reload()

class TestInstalledBackend extends Backend:
  def id: String = "test-installed"
  def displayName: String = "Test Installed Backend"
  def spiVersion: String = SpiVersion.Current
  def capabilities: Capabilities =
    Capabilities(Set.empty, Set(OutputKind.ScalaSource), Set.empty, SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
  def intrinsics: Map[QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String] = Set.empty
  def compile(ir: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.TextOutput("", "scala")
