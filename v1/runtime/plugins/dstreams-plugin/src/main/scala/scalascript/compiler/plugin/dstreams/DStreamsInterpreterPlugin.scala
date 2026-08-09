package scalascript.compiler.plugin.dstreams

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Interpreter-only plugin that wires DStream / Pipeline intrinsics.
 *  The native bounded backend (`Backend.Direct` / `Backend.Native`) runs
 *  pipelines synchronously in-process on the JVM interpreter.
 *  Registration: META-INF/services/scalascript.backend.spi.Backend */
class DStreamsInterpreterPlugin extends Backend:
  def id:          String = "scalascript-dstreams-interpreter"
  def displayName: String = "Distributed Streams Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = DStreamsIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic(
      "DStreamsInterpreterPlugin does not compile — intrinsic provider only")))
