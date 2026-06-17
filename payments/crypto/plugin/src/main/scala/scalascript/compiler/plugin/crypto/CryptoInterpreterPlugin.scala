package scalascript.compiler.plugin.crypto

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

class CryptoInterpreterPlugin extends Backend:
  def id:          String = "scalascript-crypto-interpreter"
  def displayName: String = "Crypto Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = CryptoIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("CryptoInterpreterPlugin does not compile — intrinsic provider only")))
