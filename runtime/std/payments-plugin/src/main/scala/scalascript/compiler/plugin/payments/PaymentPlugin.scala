package scalascript.compiler.plugin.payments

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** CLI-time plugin that registers the Payments SPI.
 *  Provides no intrinsics — payment operations are adapter-only runtime concerns. */
class PaymentPlugin extends Backend:
  def id:          String = "scalascript-payments"
  def displayName: String = "Traditional Payments Plugin"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set(Feature.Payments),
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("PaymentPlugin does not compile — runtime adapter only")))
