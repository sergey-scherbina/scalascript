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
