package scalascript.compiler.plugin.nfc

import scalascript.backend.spi.*
import scalascript.ir.{NormalizedModule, QualifiedName}

class NfcInterpreterPlugin extends Backend:
  def id:          String = "scalascript-nfc-interpreter"
  def displayName: String = "NFC Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set(Feature.NfcNdef),
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = NfcIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("NfcInterpreterPlugin does not compile - intrinsic provider only")))
