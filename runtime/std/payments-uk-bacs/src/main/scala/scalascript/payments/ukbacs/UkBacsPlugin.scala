package scalascript.payments.ukbacs

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Backend SPI entry point for the UK BACS Direct Debit adapter.
 *
 *  Registers Feature.BankRails capability.  This plugin provides no compiler
 *  intrinsics — BACS operations are runtime adapter concerns, not compile-time.
 *
 *  Registered via META-INF/services/scalascript.backend.spi.Backend.
 *
 *  See docs/specs/international-bank-rails.md §v1.55.4 for spec.
 */
class UkBacsPlugin extends Backend:
  def id:          String = "scalascript-payments-uk-bacs"
  def displayName: String = "UK BACS Direct Debit Plugin (Standard-18, AUDDIS/ARUDD, 3-day cycle)"
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
    CompileResult.Failed(List(Diagnostic.Generic("UkBacsPlugin does not compile — runtime adapter only")))
