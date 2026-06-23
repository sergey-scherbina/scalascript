package scalascript.compiler.plugin.streams

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule, ExportedSymbol}

/** Interpreter-only plugin that wires backpressured stream intrinsics via NativeImpl.
 *  Registration: META-INF/services/scalascript.backend.spi.Backend */
class StreamsInterpreterPlugin extends Backend:
  def id:          String = "scalascript-streams-interpreter"
  def displayName: String = "Streams Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = StreamsIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  /** core-min-prelude-migrate (streams) + core-min-advanced-optin: `runStream` (the runner) and the
   *  `Stream` object were the LAST effect-runner names hardcoded in the Typer prelude (coremin-stream-
   *  migrate); `Source` is the streams namespace object, moved off the hardcoded `pluginBuiltins`
   *  (coremin-advanced-optin). All are DECLARED here for `ssc check`. The streams plugin is essential/
   *  bundled (installBin stages it), so `BackendRegistry.inProcess` loads these in production. The
   *  runtime STAYS in core — `runStream`'s Free-monad driver + `tryStreamEmitWhileFast` FastTier +
   *  `installStreamGlobal` are interpreter-coupled (a `BlockForm` only sees `SpiValue`, no AST). The
   *  typer does not enforce effect discharge, so plain `Any` declarations suffice. */
  override def preludeSymbols: List[ExportedSymbol] = List(
    ExportedSymbol("runStream", "runStream", "def",    "Any"),
    ExportedSymbol("Stream",    "Stream",    "object", "Any"),
    ExportedSymbol("Source",    "Source",    "def",    "Any"),
  )

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("StreamsInterpreterPlugin does not compile — intrinsic provider only")))
