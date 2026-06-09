package scalascript.compiler.plugin.os

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

class OsInterpreterPlugin extends Backend:
  def id:          String = "scalascript-os-interpreter"
  def displayName: String = "OS/Process Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = OsIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("OsInterpreterPlugin does not compile — intrinsic provider only")))
