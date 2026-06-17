package scalascript.payments.ukfps

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Backend SPI entry point for the UK Faster Payments Service adapter.
 *
 *  Registers Feature.BankRails capability.  This plugin provides no compiler
 *  intrinsics — UK FPS operations are runtime adapter concerns, not compile-time.
 *
 *  Registered via META-INF/services/scalascript.backend.spi.Backend.
 *  See specs/international-bank-rails.md §v1.55.3.
 */
class UkFpsPlugin extends Backend:
  def id:          String = "scalascript-payments-uk-fps"
  def displayName: String = "UK Faster Payments Service Plugin (Pay.UK / Open Banking)"
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
    CompileResult.Failed(List(Diagnostic.Generic("UkFpsPlugin does not compile — runtime adapter only")))
