package scalascript.compiler.plugin.ws

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

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

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("WsInterpreterPlugin does not compile — intrinsic provider only")))
