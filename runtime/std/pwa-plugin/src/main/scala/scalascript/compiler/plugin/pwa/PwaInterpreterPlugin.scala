package scalascript.compiler.plugin.pwa

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

class PwaInterpreterPlugin extends Backend:
  def id:          String = "scalascript-pwa-interpreter"
  def displayName: String = "PWA Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = PwaIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("PwaInterpreterPlugin does not compile — intrinsic provider only")))
