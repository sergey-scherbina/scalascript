package scalascript.compiler.plugin.graph

import scalascript.backend.spi.*
import scalascript.ir.{NormalizedModule, QualifiedName}

class GraphPlugin extends Backend:
  def id:          String = "scalascript-graph"
  def displayName: String = "Graph intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = GraphIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("GraphPlugin does not compile — intrinsic provider only")))
