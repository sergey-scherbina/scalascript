package scalascript.compiler.plugin.content

import scalascript.backend.spi.*
import scalascript.ir.{NormalizedModule, QualifiedName}

/** Interpreter plugin for Markdown-hosted content intrinsics. */
class ContentInterpreterPlugin extends Backend:
  def id:          String = "scalascript-content-interpreter"
  def displayName: String = "Content Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = ContentIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("ContentInterpreterPlugin does not compile — intrinsic provider only")))
