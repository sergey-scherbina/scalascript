package scalascript.compiler.plugin.scljet

import scalascript.backend.spi.*
import scalascript.ir.{NormalizedModule, QualifiedName}

class SclJetVfsInterpreterPlugin extends Backend:
  def id: String = "scalascript-scljet-vfs-interpreter"
  def displayName: String = "SclJet JVM VFS Intrinsics"
  def spiVersion: String = SpiVersion.Current
  def capabilities: Capabilities = Capabilities(Set.empty, Set.empty, Set.empty,
    SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
  def intrinsics: Map[QualifiedName, IntrinsicImpl] = SclJetVfsIntrinsics.table
  def acceptedSources: Set[String] = Set.empty
  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("SclJetVfsInterpreterPlugin is an intrinsic provider only")))
