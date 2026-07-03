package ${packageName}

import scalascript.backend.spi.*
import scalascript.ir.{NormalizedModule, QualifiedName}

class ${Name} extends Backend:
  def id: String = "${name}"
  def displayName: String = "${Name}"
  def spiVersion: String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs = Set.empty,
    options = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics: Map[QualifiedName, IntrinsicImpl] = ${Name}Intrinsics.table
  def acceptedSources: Set[String] = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("${Name} is an intrinsic provider, not a compiler backend")))
