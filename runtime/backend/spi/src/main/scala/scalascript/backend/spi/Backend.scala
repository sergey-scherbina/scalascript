package scalascript.backend.spi

import scalascript.ir.{NormalizedModule, NormalizedBlock, SymbolRef, Value, QualifiedName}

/** The Backend trait — produces target-platform output from normalised IR.
 *  See docs/backend-spi.md §4.2.
 *
 *  Implementations register via `META-INF/services/scalascript.backend.spi.Backend`
 *  (Stage 5) for in-process, or via `plugin.yaml` with a subprocess
 *  executable (Stage 6) for out-of-process. */
trait Backend:
  def id: String                                    // e.g. "jvm", "js", "wasm"
  def displayName: String                           // human-friendly
  def spiVersion: String                            // SPI version this plugin was built against
  def capabilities: Capabilities
  def intrinsics: Map[QualifiedName, IntrinsicImpl] // §8 — platform operations
  def acceptedSources: Set[String]                  // §9 — canonical source-language names this target can embed
  def sqlBlockRunner: Option[SqlBlockRunner] = None  // interpreter `sql` fenced-block executor, when provided
  def markupCodec: Option[scalascript.markup.MarkupCodec] = None  // xml"..." codec; None means Feature.Markup unsupported

  /** Runtime helpers this backend ships alongside the intrinsic
   *  dispatch — typically per-intrinsic strings concatenated.  Core
   *  prepends this before user code at emit time.  Stage 5+/A.6 (Б-2).
   *
   *  Example: a backend that maps `extern def serve(port)` to a
   *  runtime helper `_serve(port)` returns the helper's source in
   *  `runtimePreamble`.  Default empty — backends without
   *  intrinsic-shipped runtime helpers override nothing. */
  def runtimePreamble: String = ""

  /** Interpolators provided by this backend.
   *  Registered in `InterpolatorRegistry` when the backend is loaded.
   *  Default empty — most backends contribute no custom interpolators. */
  def interpolators: List[InterpolatorImpl] = Nil

  /** One-shot compilation. */
  def compile(ir: NormalizedModule, opts: BackendOptions): CompileResult

/** A backend that supports a stateful session — used by REPL / interpreter
 *  and by the serve runtime for incremental compilation across edits. */
trait InteractiveBackend extends Backend:
  def openSession(opts: BackendOptions): Session

trait Session extends AutoCloseable:
  def feed(block: NormalizedBlock): CompileResult
  def invokeHandler(handlerRef: SymbolRef, args: List[Value]): Value
  def close(): Unit
