package scalascript.compiler.plugin.mcp

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Interpreter-only plugin that wires MCP intrinsics via NativeImpl.
 *  Registration: META-INF/services/scalascript.backend.spi.Backend */
class McpInterpreterPlugin extends Backend:
  def id:          String = "scalascript-mcp-interpreter"
  def displayName: String = "MCP Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = McpIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("McpInterpreterPlugin does not compile — intrinsic provider only")))
