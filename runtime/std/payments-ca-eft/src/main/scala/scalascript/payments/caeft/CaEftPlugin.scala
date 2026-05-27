package scalascript.payments.caeft

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Backend SPI entry point for the Canada Interac e-Transfer + EFT adapter.
 *
 *  Registers Feature.BankRails capability.  This plugin provides no compiler
 *  intrinsics — CA EFT/Interac operations are runtime adapter concerns, not compile-time.
 *
 *  Registered via META-INF/services/scalascript.backend.spi.Backend.
 *  See docs/international-bank-rails.md §CA_INTERAC.
 */
class CaEftPlugin extends Backend:
  def id:          String = "scalascript-payments-ca-eft"
  def displayName: String = "Canada Interac e-Transfer + EFT Plugin (CPA Standard 005)"
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
    CompileResult.Failed(List(Diagnostic.Generic("CaEftPlugin does not compile — runtime adapter only")))
