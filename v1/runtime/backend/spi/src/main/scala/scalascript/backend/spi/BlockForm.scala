package scalascript.backend.spi

/** A `keyword { body }` *effect-runner* block-form a plugin contributes (e.g. `runLogger`,
 *  `runRandom`, `runActors`), so feature runners can live in plugins instead of being
 *  hardcoded in the interpreter's `EvalRuntime`. See `specs/polyglot-libraries.md §2d`.
 *
 *  The interpreter owns the effect trampoline (`EffectHandlers.runWithHandler`); a plugin
 *  supplies only a stateful per-entry handler whose `reply(op, args)` returns the value to
 *  resume the body with — it never touches the interpreter's `Computation`. Values cross the
 *  boundary as the typed, host-neutral [[SpiValue]] (the interp converts its runtime `Value`
 *  to/from `SpiValue`), so the contract is type-safe and the plugin stays decoupled. */
trait BlockForm:
  /** The `Perform` effect tag this runner intercepts within the body (e.g. `"Logger"`). */
  def effectName: String

  /** A fresh stateful handler for one block entry. `args` are the head-clause argument values
   *  (e.g. a seed for `runRandomSeeded(seed) { … }`); `ctx` exposes the runtime sinks a handler
   *  may need (stdout, …). */
  def newHandler(ctx: BlockContext, args: List[SpiValue]): EffectHandler

  /** Combine the body's result with the handler's final state. Default: just the body result.
   *  Override for collectors like `runLoggerToList { … }` → `(result, collectedLogs)`. */
  def result(bodyResult: SpiValue, @scala.annotation.unused handler: EffectHandler): SpiValue = bodyResult

/** Replies to one effect operation. The interpreter resumes the body with the returned value
 *  (one-shot). A handler is stateful (created per block entry by `BlockForm.newHandler`). */
trait EffectHandler:
  def reply(op: String, args: List[SpiValue]): SpiValue

/** Runtime sinks a block-form handler may consult, supplied by the interpreter at the call site. */
trait BlockContext:
  /** The interpreter's standard-output sink (e.g. for `runLogger` to write log lines). */
  def out: java.io.PrintStream

  /** Apply a ScalaScript function value (received as a `SpiValue.Opaque` closure) to `args`,
   *  returning its result. Lets a handler invoke a closure passed as an effect-op argument — e.g.
   *  `State.modify(f)`, or a retry/cache thunk. Defaulted to throw so the contract stays
   *  backward-compatible: hosts that can run ScalaScript closures (the interpreter) override it;
   *  hosts that can't, and handlers that never call it, are unaffected. */
  def applyFn(@scala.annotation.unused fn: SpiValue, @scala.annotation.unused args: List[SpiValue]): SpiValue =
    throw new UnsupportedOperationException("BlockContext.applyFn is not supported by this host")

  /** Build a host record/instance value — a named type with string-keyed fields — and return it as
   *  an opaque [[SpiValue]] the host round-trips unchanged. Lets a handler reply with a typed record
   *  the SPI's primitive cases can't express, e.g. `Http.get` → a `Response { status, headers, body }`
   *  the body then field-accesses (`resp.status`). Defaulted to throw so the contract stays
   *  backward-compatible: a host that has a record value (the interpreter → `Value.InstanceV`)
   *  overrides it; handlers that never call it are unaffected. */
  def makeRecord(@scala.annotation.unused typeName: String,
                 @scala.annotation.unused fields: List[(String, SpiValue)]): SpiValue =
    throw new UnsupportedOperationException("BlockContext.makeRecord is not supported by this host")

  /** Read an execution-scoped feature-local value set by the host (e.g. the `httpClient(baseUrl)`
   *  form sets `scalascript.http.baseUrl`). Lets a handler read host config a runner needs — the Http
   *  effect handler reads the base URL / timeout / retry settings this way. Defaulted to `None` so a
   *  host with no feature-local store, and handlers that never call it, are unaffected. */
  def featureLocal(@scala.annotation.unused key: String): Option[Any] = None
