package scalascript.compiler.plugin.swing

import scalascript.backend.spi.*
import scalascript.ir.{NormalizedModule, QualifiedName}

/** Interpreter-side Swing plugin placeholder.
 *
 *  The JVM desktop dev path currently lives in `ssc run-jvm --frontend swing`.
 *  Plain `ssc run --frontend swing` stays on the interpreter path and will be
 *  wired here once Swing UI intrinsics are defined.
 */
class SwingInterpreterPlugin extends Backend:
  def id:          String = "scalascript-swing-interpreter"
  def displayName: String = "Swing Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = SwingIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("SwingInterpreterPlugin does not compile - intrinsic provider only")))
