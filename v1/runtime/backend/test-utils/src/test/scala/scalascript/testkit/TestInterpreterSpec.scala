package scalascript.testkit

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.backend.spi.*
import scalascript.ir.{NormalizedModule, QualifiedName}

class TestInterpreterSpec extends AnyFunSuite with Matchers:
  private object EchoPlugin extends Backend:
    def id: String = "test-echo-plugin"
    def displayName: String = "Test Echo Plugin"
    def spiVersion: String = SpiVersion.Current
    def capabilities: Capabilities = Capabilities(
      features = Set.empty,
      outputs = Set.empty,
      options = Set.empty,
      spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
    )
    def intrinsics: Map[QualifiedName, IntrinsicImpl] =
      Map(QualifiedName("testEcho") -> NativeImpl((_, args) => args.headOption.getOrElse("")))
    def acceptedSources: Set[String] = Set.empty
    def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
      CompileResult.Failed(List(Diagnostic.Generic("test-only intrinsic provider")))

  test("installs only explicitly supplied plugin intrinsics") {
    val interp = TestInterpreter(List(EchoPlugin))

    interp.eval("""testEcho("ok")""") shouldBe "ok"
  }
