package scalascript.compiler.plugin.bench

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

class BenchInterpreterPlugin extends Backend:
  def id:          String = "scalascript-bench-interpreter"
  def displayName: String = "Bench Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = BenchIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("BenchInterpreterPlugin does not compile — intrinsic provider only")))
