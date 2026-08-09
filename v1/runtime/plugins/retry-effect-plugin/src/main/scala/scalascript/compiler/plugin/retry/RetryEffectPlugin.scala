package scalascript.compiler.plugin.retry

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule, ExportedSymbol}

/** The `Retry` effect, extracted from the interpreter core into a ServiceLoader plugin
 *  (core-minimization, polyglot-libraries §2d). Contributes the `runRetry { … }` /
 *  `runRetryNoSleep { … }` block-forms. Like `State`, it re-invokes a ScalaScript closure
 *  (the retried thunk) via `BlockContext.applyFn`, so the plugin never touches interpreter
 *  internals. The block variant only selects whether to sleep between attempts; the attempt
 *  count and delay travel in with the `Retry.attempt(n, delayMs)(thunk)` op. */
class RetryEffectPlugin extends Backend:
  def id:          String = "scalascript-retry-effect-interpreter"
  def displayName: String = "Retry effect (Interpreter)"
  def spiVersion:  String = SpiVersion.Current
  def capabilities: Capabilities = Capabilities(
    features = Set.empty, outputs = Set.empty, options = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                       = Set.empty

  /** core-min-prelude-migrate: declare the runner name(s) for `ssc check` (the keystone),
   *  removed from the hardcoded Typer prelude `effectBuiltins`; resolves via the bundled plugin. */
  override def preludeSymbols: List[ExportedSymbol] = List(
    ExportedSymbol("runRetry", "runRetry", "def", "Any"),
    ExportedSymbol("runRetryNoSleep", "runRetryNoSleep", "def", "Any"),
  )
  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("retry-effect-plugin — interpreter only")))

  override def blockForms: Map[String, BlockForm] = Map(
    "runRetry"        -> RetryBlockForm(sleep = true),
    "runRetryNoSleep" -> RetryBlockForm(sleep = false),
  )

/** `runRetry { body }` (real sleep) / `runRetryNoSleep { body }` (test handler, no sleep). */
private case class RetryBlockForm(sleep: Boolean) extends BlockForm:
  def effectName: String = "Retry"
  def newHandler(ctx: BlockContext, args: List[SpiValue]): EffectHandler =
    new RetryHandler(ctx, sleep)

/** `Retry.attempt(n, delayMs)(thunk)` — runs `thunk` up to `n + 1` times, returning the first
 *  success; if every attempt throws, rethrows the last error. Sleeps `delayMs` between attempts
 *  only when the block variant enabled it. */
private class RetryHandler(ctx: BlockContext, sleep: Boolean) extends EffectHandler:
  def reply(op: String, args: List[SpiValue]): SpiValue = op match
    case "attempt" => args match
      case List(SpiValue.IntV(n), SpiValue.IntV(delayMs), thunk) =>
        var lastErr: Throwable = null
        var result: SpiValue   = SpiValue.UnitV
        var attempt            = 0L
        var succeeded          = false
        while attempt <= n && !succeeded do
          try
            result    = ctx.applyFn(thunk, Nil)
            succeeded = true
          catch case e: Throwable =>
            lastErr  = e
            attempt += 1
            if attempt <= n && sleep && delayMs > 0 then Thread.sleep(delayMs)
        if succeeded then result else throw lastErr
      case _ => throw new IllegalArgumentException("Retry.attempt(n: Int, delayMs: Long)(thunk)")
    case other => throw new IllegalArgumentException(s"Unknown Retry operation: $other")
