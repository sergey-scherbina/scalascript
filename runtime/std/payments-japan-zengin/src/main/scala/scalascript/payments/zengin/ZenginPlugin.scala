package scalascript.payments.zengin

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Backend SPI entry point for the Japan Zengin bank rails adapter.
 *
 *  Registers Feature.BankRails capability.  This plugin provides no compiler
 *  intrinsics — Zengin operations are runtime adapter concerns, not compile-time.
 *
 *  Registered via META-INF/services/scalascript.backend.spi.Backend.
 */
class ZenginPlugin extends Backend:
  def id:          String = "scalascript-payments-japan-zengin"
  def displayName: String = "Japan Zengin Bank Rails Plugin (Zengin 21 flat-file, half-width kana)"
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
    CompileResult.Failed(List(Diagnostic.Generic("ZenginPlugin does not compile — runtime adapter only")))
