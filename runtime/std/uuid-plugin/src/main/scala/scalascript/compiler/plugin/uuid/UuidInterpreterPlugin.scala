package scalascript.compiler.plugin.uuid

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

class UuidInterpreterPlugin extends Backend:
  def id:          String = "scalascript-uuid-interpreter"
  def displayName: String = "UUID Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = UuidIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("UuidInterpreterPlugin does not compile — intrinsic provider only")))
