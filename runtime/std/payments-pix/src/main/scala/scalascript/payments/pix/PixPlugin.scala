package scalascript.payments.pix

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Backend SPI entry point for the Pix instant payments adapter.
 *
 *  Registers Feature.BankRails capability.  This plugin provides no compiler
 *  intrinsics — Pix operations are runtime adapter concerns, not compile-time.
 *
 *  Registered via META-INF/services/scalascript.backend.spi.Backend.
 */
class PixPlugin extends Backend:
  def id:          String = "scalascript-payments-pix"
  def displayName: String = "Pix Instant Payments Plugin (Brazil)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set(Feature.BankRails),
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("PixPlugin does not compile — runtime adapter only")))
