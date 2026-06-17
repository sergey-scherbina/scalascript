package scalascript.payments.paynow

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Backend SPI entry point for the Singapore PayNow adapter.
 *
 *  Registers Feature.BankRails capability.  This plugin provides no compiler
 *  intrinsics — PayNow operations are runtime adapter concerns, not compile-time.
 *
 *  Registered via META-INF/services/scalascript.backend.spi.Backend.
 *  See specs/international-bank-rails.md §v1.56.8.
 */
class PayNowPlugin extends Backend:
  def id:          String = "scalascript-payments-sg-paynow"
  def displayName: String = "Singapore PayNow Plugin (MAS FAST / proxy resolution)"
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
    CompileResult.Failed(List(Diagnostic.Generic("PayNowPlugin does not compile — runtime adapter only")))
