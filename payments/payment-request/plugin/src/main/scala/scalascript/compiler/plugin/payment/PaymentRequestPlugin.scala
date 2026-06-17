package scalascript.compiler.plugin.payment

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Interpreter-only plugin that wires Payment Request API intrinsics.
 *  Registration: META-INF/services/scalascript.backend.spi.Backend */
class PaymentRequestPlugin extends Backend:
  def id:          String = "scalascript-payment-request-interpreter"
  def displayName: String = "Payment Request Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = PaymentRequestIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("PaymentRequestPlugin does not compile — intrinsic provider only")))
