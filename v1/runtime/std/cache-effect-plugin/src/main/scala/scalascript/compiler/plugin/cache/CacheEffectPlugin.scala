package scalascript.compiler.plugin.cache

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule, ExportedSymbol}

/** The `Cache` effect, extracted from the interpreter core into a ServiceLoader plugin
 *  (core-minimization, polyglot-libraries §2d). Contributes the `runCache { … }` /
 *  `runCacheBypass { … }` block-forms. The memoized thunk is invoked via `BlockContext.applyFn`.
 *  The TTL store is process-local (mirrors the former `interp._cacheStore`), shared across
 *  `runCache` blocks; the per-block variant only selects whether caching is bypassed (mirrors
 *  the former per-thread `_cacheBypass`). */
class CacheEffectPlugin extends Backend:
  def id:          String = "scalascript-cache-effect-interpreter"
  def displayName: String = "Cache effect (Interpreter)"
  def spiVersion:  String = SpiVersion.Current
  def capabilities: Capabilities = Capabilities(
    features = Set.empty, outputs = Set.empty, options = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                       = Set.empty

  /** core-min-prelude-migrate: declare the runner name(s) for `ssc check` (the keystone),
   *  removed from the hardcoded Typer prelude `effectBuiltins`; resolves via the bundled plugin. */
  override def preludeSymbols: List[ExportedSymbol] = List(
    ExportedSymbol("runCache", "runCache", "def", "Any"),
    ExportedSymbol("runCacheBypass", "runCacheBypass", "def", "Any"),
  )
  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("cache-effect-plugin — interpreter only")))

  override def blockForms: Map[String, BlockForm] = Map(
    "runCache"       -> CacheBlockForm(bypass = false),
    "runCacheBypass" -> CacheBlockForm(bypass = true),
  )

/** Process-local TTL store, shared across `runCache` blocks (was `interp._cacheStore`). */
private object CacheStore:
  val store = new java.util.concurrent.ConcurrentHashMap[String, (Long, SpiValue)]()

/** `runCache { body }` (memoize) / `runCacheBypass { body }` (always recompute). */
private case class CacheBlockForm(bypass: Boolean) extends BlockForm:
  def effectName: String = "Cache"
  def newHandler(ctx: BlockContext, args: List[SpiValue]): EffectHandler =
    new CacheHandler(ctx, bypass)

/** `Cache.memoize(key, ttlSeconds)(thunk)` — returns a cached value while it is fresh, else
 *  computes `thunk()` and stores it with a `nowMs + ttlSeconds*1000` expiry. `bypass` always
 *  recomputes (and does not write the store). */
private class CacheHandler(ctx: BlockContext, bypass: Boolean) extends EffectHandler:
  def reply(op: String, args: List[SpiValue]): SpiValue = op match
    case "memoize" => args match
      case List(SpiValue.StrV(key), SpiValue.IntV(ttlSeconds), thunk) =>
        if bypass then ctx.applyFn(thunk, Nil)
        else
          val nowMs = java.lang.System.currentTimeMillis()
          Option(CacheStore.store.get(key)) match
            case Some((expiry, v)) if nowMs < expiry => v
            case _ =>
              val v = ctx.applyFn(thunk, Nil)
              CacheStore.store.put(key, (nowMs + ttlSeconds * 1000L, v))
              v
      case _ => throw new IllegalArgumentException("Cache.memoize(key: String, ttlSeconds: Long)(thunk)")
    case other => throw new IllegalArgumentException(s"Unknown Cache operation: $other")
