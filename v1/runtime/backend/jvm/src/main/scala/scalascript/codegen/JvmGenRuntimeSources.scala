package scalascript.codegen

/** Process-level memo for the JvmGen runtime-source preamble constants
 *  (jvmgen-codegen-time). These vals (`effectsRuntime`, `commonRuntime`,
 *  `serveRuntime`, …) are process-invariant — pure string literals + `stripMargin`,
 *  or classpath-resource loads keyed only by name — but they live in traits mixed
 *  into `JvmGen`, and `JvmGen.generate` allocates a fresh instance per call. Without
 *  this, every `generate` re-runs `stripMargin` over ~180 KB and re-loads runtime
 *  source resources from the classpath — measured ~43 % of jvmGen codegen time.
 *  The by-name `compute` runs once per key (first instance); later instances get the
 *  cached string. Benign data race just recomputes a deterministic constant once. */
private[codegen] object JvmGenRuntimeCache:
  private val cache = new java.util.concurrent.ConcurrentHashMap[String, String]()
  def memo(key: String)(compute: => String): String =
    val hit = cache.get(key)
    if hit ne null then hit
    else
      val v    = compute
      val prev = cache.putIfAbsent(key, v)
      if prev ne null then prev else v

/** Large embedded runtime-source string constants emitted into the generated
 *  Scala preamble (stub-serve, filesystem, generator, effects free-monad, and
 *  reactive-signal runtimes). Pure `String` data — no generator state — lifted
 *  out of JvmGen to keep the generator navigable. Self-typed only for
 *  package-consistency with the other JvmGen mixins; reads nothing from `self`.
 *  (`logger`/`common`/`serveRuntime` stay in JvmGen — they call loader methods.) */
private[codegen] trait JvmGenRuntimeSources:
  self: JvmGen =>

  private[codegen] val stubServeRuntime: String = JvmGenRuntimeCache.memo("stubServeRuntime"):
    JvmRuntimeResource.load("stubServeRuntime")

  private[codegen] val fsRuntime: String = JvmGenRuntimeCache.memo("fsRuntime"):
    JvmRuntimeResource.load("fsRuntime")

  /** std.process — ProcessOptions/ProcessResult/exec (ProcessBuilder). Supplied
   *  by the runtime (types are not inlined from the import on JVM), appended
   *  unconditionally like fsRuntime to both the inline preamble and the shared
   *  `_ssc_runtime` object so `exec` resolves in the split --bytecode path. */
  private[codegen] val processRuntime: String = JvmGenRuntimeCache.memo("processRuntime"):
    JvmRuntimeResource.load("processRuntime")

  private[codegen] val generatorRuntime: String = JvmGenRuntimeCache.memo("generatorRuntime"):
    JvmRuntimeResource.load("generatorRuntime")

  /** Free-Monad runtime for algebraic effects. Mirrors the interpreter and JS
   *  backend: Pure values are plain Scala values, Perform/FlatMap are case
   *  classes, _bind is constant-time, _run / _handle right-associate
   *  FlatMaps in a while-loop (stack-safe in bind-chain depth). */
  private[codegen] val effectsRuntime: String = JvmGenRuntimeCache.memo("effectsRuntime"):
    JvmRuntimeResource.load("effectsRuntime1") +
    JvmRuntimeResource.load("effectsRuntime2") +
    JvmRuntimeResource.load("effectsRuntime3") +
    JvmRuntimeResource.load("effectsRuntime4") +
    JvmRuntimeResource.load("effectsRuntime5") +
    JvmRuntimeResource.load("effectsRuntime6") +
    JvmRuntimeResource.load("effectsRuntime7")

  /** Reactive runtime — same push-model as the interpreter and JsGen.
   *  Signals are mutable cells with a subscriber set; reads inside an
   *  active effect / computed register a mutual subscription; writes
   *  queue subscribers into a LinkedHashSet and a scheduled flush
   *  drains it so each effect runs at most once per synchronous
   *  transaction (dedupes the diamond). */
  private[codegen] val reactiveRuntime: String = JvmGenRuntimeCache.memo("reactiveRuntime"):
    JvmRuntimeResource.load("reactiveRuntime")

