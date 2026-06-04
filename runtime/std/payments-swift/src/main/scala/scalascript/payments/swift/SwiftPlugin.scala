package scalascript.payments.swift

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Backend SPI entry point for the SWIFT payments adapter.
 *
 *  Registers Feature.BankRails capability for SWIFT_MT103 and SWIFT_PACS008 rails.
 *  This plugin provides no compiler intrinsics — SWIFT operations are runtime adapter concerns.
 *
 *  Registered via META-INF/services/scalascript.backend.spi.Backend.
 *  See docs/specs/international-bank-rails.md §8 v1.55.1.
 */
class SwiftPlugin extends Backend:
  def id:          String = "scalascript-payments-swift"
  def displayName: String = "SWIFT Bank Rails Plugin (MT103 + ISO 20022 pacs.008)"
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
    CompileResult.Failed(List(Diagnostic.Generic("SwiftPlugin does not compile — runtime adapter only")))
