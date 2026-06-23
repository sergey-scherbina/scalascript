package scalascript.backend.spi

import scalascript.ir.{NormalizedModule, NormalizedBlock, SymbolRef, Value, QualifiedName}

/** The Backend trait — produces target-platform output from normalised IR.
 *  See specs/backend-spi.md §4.2.
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
  def sqlBlockRunner: Option[SqlBlockRunner] = None       // interpreter `sql` fenced-block executor, when provided
  def graphqlBlockRunner: Option[GraphQLBlockRunner] = None // interpreter `graphql` fenced-block executor, when provided
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

  /** Source preprocessors provided by this backend.
   *  Registered in `PreprocessorRegistry` when the backend is loaded.
   *  Default empty — most backends contribute no custom preprocessors. */
  def preprocessors: List[Preprocessor] = Nil

  /** Compile-time string-interpolator checks provided by this backend.
   *  Registered in `InterpolatorCheckRegistry` when the backend is loaded.
   *  Default empty — most backends contribute no custom checks. */
  def interpolatorChecks: List[InterpolatorCheck] = Nil

  /** Effect-runner block-forms this plugin contributes (`keyword { body }`), keyed by keyword
   *  (e.g. `"runLogger"`). Lets feature runners live in plugins instead of being hardcoded in
   *  the interpreter. Default empty. See `specs/polyglot-libraries.md §2d`. */
  def blockForms: Map[String, BlockForm] = Map.empty

  /** Public symbols this plugin contributes to the `ssc check` PRELUDE — names + type
   *  signatures (`ExportedSymbol.tpe` is an `SType.show` string). Lets `ssc check` resolve
   *  AND type-check calls to a plugin's intrinsics / objects / effect-runners without the
   *  names being hardcoded in the Typer prelude. A symbol with `tpe == "Any"` degrades to the
   *  old names-only behaviour. Default empty. See `specs/core-min-prelude-spi.md`. */
  def preludeSymbols: List[scalascript.ir.ExportedSymbol] = Nil

  /** Import-namespace prefixes this plugin owns (e.g. `"scalascript.x402"`). When a `.ssc` file
   *  `import`s one of these, `ssc check` auto-loads this plugin's [[preludeSymbols]] even if the
   *  plugin is bundled-but-opt-in (advanced tier, not auto-loaded) — so advanced names resolve
   *  for the file that clearly intends them, without a manual `--plugin`. A file import matches a
   *  prefix `p` when it equals `p` or starts with `p + "."`. Default empty (no auto-load). */
  def providesImports: List[String] = Nil

  /** One-shot compilation. */
  def compile(ir: NormalizedModule, opts: BackendOptions): CompileResult

/** Intrinsic-only backend provider whose entries are safe to overlay onto
 *  concrete generated backends. This lets std plugins own extern
 *  implementations without hardcoding their QualifiedNames into backend-core
 *  intrinsic maps. */
trait TargetedIntrinsicProvider extends Backend:
  def targetBackendIds: Set[String]

/** Backend that can compile with a registry-composed intrinsic/runtime overlay.
 *  A plain delegating wrapper cannot change `this.intrinsics` inside an
 *  existing backend instance, so generated backends that want plugin overlays
 *  implement this hook. */
trait IntrinsicOverlayAwareBackend extends Backend:
  def compileWithOverlay(
      ir: NormalizedModule,
      opts: BackendOptions,
      intrinsics: Map[QualifiedName, IntrinsicImpl],
      runtimePreamble: String
  ): CompileResult

/** A backend that supports a stateful session — used by REPL / interpreter
 *  and by the serve runtime for incremental compilation across edits. */
trait InteractiveBackend extends Backend:
  def openSession(opts: BackendOptions): Session

trait Session extends AutoCloseable:
  def feed(block: NormalizedBlock): CompileResult
  def invokeHandler(handlerRef: SymbolRef, args: List[Value]): Value
  def close(): Unit
