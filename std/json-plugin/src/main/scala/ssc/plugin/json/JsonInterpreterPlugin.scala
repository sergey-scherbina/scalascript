package ssc.plugin.json

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Interpreter-only plugin that wires JSON intrinsics via NativeImpl.
 *  Registration: META-INF/services/scalascript.backend.spi.Backend */
class JsonInterpreterPlugin extends Backend:
  def id:          String = "scalascript-json-interpreter"
  def displayName: String = "JSON Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = JsonIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("JsonInterpreterPlugin does not compile — intrinsic provider only")))
