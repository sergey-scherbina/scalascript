package scalascript.compiler.plugin.mime

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

class MimeInterpreterPlugin extends Backend:
  def id:          String = "scalascript-mime-interpreter"
  def displayName: String = "MIME Assembly Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = MimeIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("MimeInterpreterPlugin does not compile — intrinsic provider only")))
