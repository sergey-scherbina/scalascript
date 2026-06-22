package scalascript.compiler.plugin.http

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** Interpreter-only plugin that wires HTTP intrinsics via NativeImpl.
 *  Registration: META-INF/services/scalascript.backend.spi.Backend */
class HttpInterpreterPlugin extends Backend:
  def id:          String = "scalascript-http-interpreter"
  def displayName: String = "HTTP Intrinsics (Interpreter)"
  def spiVersion:  String = SpiVersion.Current

  def capabilities: Capabilities = Capabilities(
    features = Set.empty,
    outputs  = Set.empty,
    options  = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current),
  )

  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = HttpIntrinsics.table
  def acceptedSources: Set[String]                       = Set.empty

  /** The Http effect runner, extracted from interpreter core (core-min §2d). Both keywords share
   *  one `BlockForm`: `runHttp { … }` (real I/O) and `runHttpStub(routes) { … }` (stub) — the handler
   *  branches on whether config args carry a routes map. `httpClient(baseUrl)` stays a core form. */
  override def blockForms: Map[String, BlockForm] = Map(
    "runHttp"     -> HttpEffectRunner,
    "runHttpStub" -> HttpEffectRunner,
  )

  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("HttpInterpreterPlugin does not compile — intrinsic provider only")))
