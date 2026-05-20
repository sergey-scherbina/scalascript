package scalascript.compiler.plugin.uifetch

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

class UiFetchPlugin extends Backend:
  def id:          String = "scalascript-ui-fetch"
  def displayName: String = "UI Fetch Intrinsics (fetchAction, fetchUrlSignal, incSignal)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = UiFetchIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("UiFetchPlugin does not compile — intrinsic provider only")))
