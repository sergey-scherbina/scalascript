package scalascript.compiler.plugin.pdf

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

class PdfInterpreterPlugin extends Backend:
  def id:          String = "scalascript-pdf-interpreter"
  def displayName: String = "PDF Generation Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = PdfIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("PdfInterpreterPlugin does not compile — intrinsic provider only")))
