package scalascript.compiler.plugin.yaml

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

class YamlInterpreterPlugin extends Backend:
  def id:          String = "scalascript-yaml-interpreter"
  def displayName: String = "YAML Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = YamlIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("YamlInterpreterPlugin does not compile — intrinsic provider only")))
