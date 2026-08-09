package scalascript.compiler.plugin.tcp

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

class TcpInterpreterPlugin extends Backend:
  def id:          String = "scalascript-tcp-interpreter"
  def displayName: String = "Raw TCP Socket Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = TcpIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("TcpInterpreterPlugin does not compile — intrinsic provider only")))
