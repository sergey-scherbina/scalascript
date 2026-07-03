package scalascript.compiler.plugin.smtp

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

class SmtpInterpreterPlugin extends Backend:
  def id:          String = "scalascript-smtp-interpreter"
  def displayName: String = "SMTP Submission Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = SmtpIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("SmtpInterpreterPlugin does not compile — intrinsic provider only")))
