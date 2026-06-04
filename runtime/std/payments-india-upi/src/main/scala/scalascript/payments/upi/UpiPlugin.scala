package scalascript.payments.upi

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Backend SPI entry point for the India UPI (Unified Payments Interface) adapter.
 *
 *  Registers Feature.BankRails capability.  This plugin provides no compiler
 *  intrinsics — UPI operations are runtime adapter concerns, not compile-time.
 *
 *  Protocol: NPCI-licensed aggregator REST API (Razorpay, PayU, JusPay, Cashfree,
 *  PhonePe Business API).  Two flows: push (UPI Pay) and collect (UPI Collect).
 *
 *  Auth: API key (X-Api-Key header) + RSA-SHA256 request signing using merchant
 *  private key.  Webhook verification uses RSA-SHA256 with the aggregator public key.
 *
 *  Registered via META-INF/services/scalascript.backend.spi.Backend.
 *  See docs/specs/international-bank-rails.md §v1.55.6.
 */
class UpiPlugin extends Backend:
  def id:          String = "scalascript-payments-india-upi"
  def displayName: String = "India UPI (Unified Payments Interface) Plugin — NPCI / aggregator"
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
    CompileResult.Failed(List(Diagnostic.Generic("UpiPlugin does not compile — runtime adapter only")))
