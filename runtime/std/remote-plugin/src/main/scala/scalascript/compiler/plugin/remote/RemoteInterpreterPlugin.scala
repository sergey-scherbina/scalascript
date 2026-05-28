package scalascript.compiler.plugin.remote

import scalascript.backend.spi.*
import scalascript.ir.{NormalizedModule, QualifiedName}

/** Interpreter plugin for std.remote intrinsics. */
class RemoteInterpreterPlugin extends Backend:
  def id:          String = "scalascript-remote-interpreter"
  def displayName: String = "Remote Registry Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = RemoteIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("RemoteInterpreterPlugin does not compile — intrinsic provider only")))
