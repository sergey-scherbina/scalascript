package scalascript.compiler.plugin.fs

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

class FsInterpreterPlugin extends Backend:
  def id:          String = "scalascript-fs-interpreter"
  def displayName: String = "Filesystem Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = FsIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("FsInterpreterPlugin does not compile — intrinsic provider only")))
