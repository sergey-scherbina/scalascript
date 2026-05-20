package sscplugin.request

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Interpreter-only plugin that wires request-validation intrinsics via NativeImpl.
 *  Registration: META-INF/services/scalascript.backend.spi.Backend */
class RequestInterpreterPlugin extends Backend:
  def id:          String = "scalascript-request-interpreter"
  def displayName: String = "Request Validation Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = RequestIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("RequestInterpreterPlugin does not compile — intrinsic provider only")))
