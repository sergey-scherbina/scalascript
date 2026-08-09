package scalascript.compiler.plugin.ws

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule, ExportedSymbol}

/** Interpreter-only plugin that wires WebSocket intrinsics via NativeImpl.
 *  Registration: META-INF/services/scalascript.backend.spi.Backend */
class WsInterpreterPlugin extends Backend:
  def id:          String = "scalascript-ws-interpreter"
  def displayName: String = "WebSocket Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = WsIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  /** core-min-advanced-optin: this plugin DECLARES its prelude name(s) for `ssc check`,
   *  removed from the hardcoded Typer `pluginObjects`/`pluginBuiltins`. For an ADVANCED (opt-in)
   *  plugin these resolve only when the plugin is added (`--plugin`); for an essential plugin they
   *  always resolve (auto-loaded). The names are typer-prelude declarations only. */
  override def preludeSymbols: List[ExportedSymbol] = List(
    ExportedSymbol("setHttpServerBackend", "setHttpServerBackend", "def", "Any"),
  )

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("WsInterpreterPlugin does not compile — intrinsic provider only")))
