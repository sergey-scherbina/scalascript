package mybackend

import scalascript.backend.spi.*
import scalascript.ir

/** A no-op Backend implementation: every compile() returns the same
 *  canned string regardless of input.  Proves the in-process /
 *  ServiceLoader path end-to-end.
 *
 *  Pair this class with src/main/resources/META-INF/services/
 *  scalascript.backend.spi.Backend listing "mybackend.HelloBackend"
 *  on one line; that's all `BackendRegistry` needs to discover it. */
class HelloBackend extends Backend:
  def id:          String = "hello"
  def displayName: String = "Hello, World"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set(OutputKind.ExecutionResult),
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current)
  )

  def intrinsics:      Map[ir.QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                          = Set.empty

  def compile(module: ir.NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.TextOutput(
      code     = "hello, world",
      language = "text",
      sources  = Nil
    )
