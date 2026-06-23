package scalascript.compiler.plugin.payments

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule, ExportedSymbol}

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

  // core-min check-autoload: a file that imports `scalascript.x402.*` auto-loads these preludeSymbols
  // in `ssc check`, even though payments is advanced/opt-in (no manual `--plugin` needed).
  override def providesImports: List[String] = List("scalascript.x402")

  /** core-min-advanced-optin: this plugin DECLARES its prelude name(s) for `ssc check`,
   *  removed from the hardcoded Typer `pluginObjects`/`pluginBuiltins`. For an ADVANCED (opt-in)
   *  plugin these resolve only when the plugin is added (`--plugin`); for an essential plugin they
   *  always resolve (auto-loaded). The names are typer-prelude declarations only. */
  override def preludeSymbols: List[ExportedSymbol] = List(
    ExportedSymbol("Wallets", "Wallets", "def", "Any"),
    ExportedSymbol("X402Client", "X402Client", "def", "Any"),
    ExportedSymbol("X402", "X402", "def", "Any"),
    ExportedSymbol("CardanoFacilitator", "CardanoFacilitator", "def", "Any"),
    ExportedSymbol("PaymentConfig", "PaymentConfig", "def", "Any"),
    ExportedSymbol("DefaultSyncBackend", "DefaultSyncBackend", "def", "Any"),
    ExportedSymbol("basicRequest", "basicRequest", "def", "Any"),
  )

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("PaymentPlugin does not compile — runtime adapter only")))
