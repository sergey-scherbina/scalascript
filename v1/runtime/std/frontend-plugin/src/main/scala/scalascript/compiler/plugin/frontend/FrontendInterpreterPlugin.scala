package scalascript.compiler.plugin.frontend

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Interpreter-only plugin that wires frontend-framework intrinsics via NativeImpl.
 *  Registration: META-INF/services/scalascript.backend.spi.Backend */
class FrontendInterpreterPlugin extends Backend:
  def id:          String = "scalascript-frontend-interpreter"
  def displayName: String = "Frontend Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = FrontendIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("FrontendInterpreterPlugin does not compile — intrinsic provider only")))
