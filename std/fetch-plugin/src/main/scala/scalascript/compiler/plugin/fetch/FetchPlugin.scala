package scalascript.compiler.plugin.fetch

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

class FetchPlugin extends Backend:
  def id:          String = "scalascript-fetch"
  def displayName: String = "Fetch Intrinsics (fetchAction, fetchUrlSignal, incSignal)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = FetchIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("FetchPlugin does not compile — intrinsic provider only")))
