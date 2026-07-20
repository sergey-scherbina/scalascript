package ssc

// The ssc VM: IR -> ssc -> cpu.
// JIT philosophy (per design): once we know what a node is, we compile it ONCE
// into a closure that does exactly that — no re-dispatch on Term at run time.
// compile(Term): Code  turns the IR into a tree of closures (the "generated
// program"); the trampoline driver runs it with proper tail calls (TCO).

type Env  = Array[Value]
type Code  = Env => Step   // a compiled term: given an env, yield the next Step

sealed trait Step
final case class Done(v: Value)                       extends Step  // a finished value
final case class Call(clos: Value.ClosV, args: Array[Value]) extends Step  // a tail call to bounce

/** A language/runtime failure that `__try__` may recover from. Unexpected host
  * exceptions remain outside this category and must reach the program boundary. */
final class RecoverableError(message: String) extends RuntimeException(message)

/** A ScalaScript `throw e` — carries the thrown VALUE so `try … catch { case … }` can bind it. */
final class SscThrow(val value: Value) extends RuntimeException

/** A non-local `return e` — unwinds to the nearest enclosing `__with_return__` (a wrapped
 *  named-def body). Control flow, not an error: no stack trace, and user `try`/`catch`
 *  re-throws it (a `return` inside a `try` exits the method, it is not "caught"). */
final class ReturnThrow(val value: Value) extends RuntimeException(null, null, false, false)

/** Target-neutral identity of an algebraic effect and one of its operations.
  * Keep the two components structured: the legacy `Effect.operation` label is
  * only a dispatch/display value and is never parsed to recover identity. */
final case class EffectId(value: String)
final case class OperationId(effect: EffectId, name: String):
  def display: String = s"${effect.value}.$name"

sealed trait ResumeRejected
object ResumeRejected:
  final case class AlreadyResumed(operation: OperationId) extends ResumeRejected

/** A violated continuation contract crossing the ScalaScript run boundary.
  *
  * This is deliberately not a recoverable user exception: generated
  * `try/catch` rethrows it and the CLI/embedding boundary projects the typed
  * rejection to a stable diagnostic. */
final class ControlRunFailure(val rejection: ResumeRejected)
    extends RuntimeException(null, null, false, false):
  val code: String = rejection match
    case ResumeRejected.AlreadyResumed(_) => "ONESHOT_VIOLATION"

  val diagnosticMessage: String = rejection match
    case ResumeRejected.AlreadyResumed(operation) =>
      s"One-shot violation: ${operation.display} resumed more than once"

  val rendered: String = s"error [$code]: $diagnosticMessage"

  override def getMessage: String = diagnosticMessage

sealed trait Value
object Value:
  case object UnitV                                    extends Value
  final case class BoolV(b: Boolean)                  extends Value
  final class IntV(val n: Long) extends Value:
    override def equals(o: Any): Boolean = o match { case iv: IntV => iv.n == n; case _ => false }
    override def hashCode: Int = java.lang.Long.hashCode(n)
    override def toString: String = s"IntV($n)"
  object IntV:
    private val CacheMin  = -128L
    private val CacheMax  = 4096L
    private val cache = Array.tabulate((CacheMax - CacheMin + 1).toInt)(i => new IntV(i + CacheMin))
    def apply(n: Long): IntV =
      if n >= CacheMin && n <= CacheMax then cache((n - CacheMin).toInt) else new IntV(n)
    def unapply(v: IntV): Some[Long] = Some(v.n)
  final case class BigV(n: BigInt)                    extends Value
  final case class FloatV(d: Double)                  extends Value
  final case class StrV(s: String)                    extends Value
  final case class BytesV(b: Vector[Byte])            extends Value
  final class DecimalV private[ssc] (val text: String) extends Value:
    override def equals(other: Any): Boolean = other match
      case that: DecimalV => PortableDecimal.numericEquals(text, that.text)
      case _              => false
    override def hashCode: Int = PortableDecimal.numericHash(text)
    override def toString: String = s"DecimalV($text)"
  object DecimalV:
    def apply(text: String): DecimalV = new DecimalV(PortableDecimal.canonicalText(text))
    def unapply(value: DecimalV): Some[String] = Some(value.text)
  final case class DataV(tag: String, fields: IndexedSeq[Value]) extends Value
  final class ClosV(var env: Env, val arity: Int, val code: Code) extends Value:  // var env: cyclic letrec frames
    // Direct-call fast entry: set by Compiler for simple defs whose body is tryFC-able.
    // Callers can invoke this instead of trampoline to eliminate Done allocs.
    var fcEntry: Option[FastCode.FC] = None
    /** True only for a qualified one-argument handler dispatch root. It is
     *  private execution metadata, never a language value or wire ABI. */
    private[ssc] var handlerDispatchRoot: Boolean = false
    /** Unforgeable owner of this compiled partial-function root. The token is
     *  target-private executable metadata: it never enters `env` or CoreIR. */
    private[ssc] var handlerDispatchOwner: AnyRef | Null = null
    /** Raw qualified-root entry used only by dispatchHandler1. Ordinary calls
     *  use `code`, whose wrapper assigns a fresh per-invocation activation. */
    private[ssc] var handlerDispatchRawCode: Code | Null = null
    /** Runtime/plugin-owned handlers have no CoreIR root match. Their private
     *  structured dispatcher returns Some(result) or None for an unhandled
     *  event; it is never stored in the language closure environment. */
    private[ssc] var handlerDispatchHook: (Value => Option[Value]) | Null = null
    /** Set ONLY on the `__method__` eta-expansion fallback closure (`recv.name`
     *  used as a function value), holding the (name, receiver) that produced it.
     *  A bare selection and an APPLIED zero-arg call lower to byte-identical
     *  CoreIR, so the applied form is emitted as `__method0__`, which uses this
     *  marker to reject the eta fallback and fail closed. Private execution
     *  metadata: never enters `env`, CoreIR, or the wire ABI. */
    private[ssc] var etaMethodRef: (String, Value) | Null = null
  final case class ForeignV(h: AnyRef)                extends Value
  /** Target-neutral insertion-ordered mutable map. Equality and hashing remain
   *  object identity, matching Swift's SscMap; UI semantic equality is a
   *  separate cycle-safe operation owned by the NativeUi runtime. */
  final class MapV private (val entries: collection.mutable.LinkedHashMap[Value, Value]) extends Value
  object MapV:
    def empty: MapV = new MapV(collection.mutable.LinkedHashMap.empty)
    def from(entries: IterableOnce[(Value, Value)]): MapV =
      val out = collection.mutable.LinkedHashMap.empty[Value, Value]
      out ++= entries
      new MapV(out)
    def unapply(value: MapV): Some[collection.mutable.LinkedHashMap[Value, Value]] =
      Some(value.entries)
  /** A named-field dispatch object: method calls are routed by name.
   *  Used by PluginBridge to wrap v1 InstanceV objects with plugin-owned method fields
   *  (e.g. AuthServer.registerClient) without exposing v1 types in the v2 core module. */
  trait NamedMethodObj:
    def getField(name: String): Option[Value]
    /** Round-trip handle: the original object (any type) for passing back to plugins. */
    def underlying: AnyRef
  // Mutable long cell: avoids IntV boxing on every cell.set in tight arithmetic loops.
  // Lowered from `var x: Long = 0` via `lcell.new`.
  final class LongCellV(var v: Long)                  extends Value
  // Mutable double cell: the Double twin of LongCellV — avoids FloatV boxing on every
  // cell.set in tight float loops. Lowered from `var x: Double = 0.0` via `dcell.new`.
  final class DoubleCellV(var v: Double)              extends Value

private[ssc] enum HandlerDispatch:
  case Matched(value: Value)
  case Unhandled
  case Suspended(
      label: Value,
      argument: Value,
      continue: Value => HandlerDispatch
  )

object Runtime:
  import Value.*

  /** Target-private scope for distinguishing a handler partial-function miss
   *  from failures raised after a matching arm was selected. The scope is
   *  synchronous, per invocation, and never enters a closure environment. */
  private final class HandlerDispatchProbe(
      val owner: AnyRef,
      val activation: AnyRef,
      val event: Value
  ):
    var pending: Boolean = true

  private final class HandlerDispatchInvocation(
      val owner: AnyRef,
      val activation: AnyRef
  )

  private val handlerDispatchProbes = new ThreadLocal[List[HandlerDispatchProbe]]:
    override def initialValue(): List[HandlerDispatchProbe] = Nil
  private val handlerDispatchInvocations =
    new ThreadLocal[List[HandlerDispatchInvocation]]:
      override def initialValue(): List[HandlerDispatchInvocation] = Nil

  // Unforgeable runtime identity: deliberately not DataV/string-shaped and
  // consumed by dispatchHandler1 before it can cross the runtime boundary.
  private val handlerUnhandledSentinel: Value = ForeignV(new Object())

  private def withHandlerDispatchInvocation[A](
      owner: AnyRef,
      activation: AnyRef
  )(run: => A): A =
    val previous = handlerDispatchInvocations.get()
    handlerDispatchInvocations.set(
      HandlerDispatchInvocation(owner, activation) :: previous)
    try run
    finally
      if previous.isEmpty then handlerDispatchInvocations.remove()
      else handlerDispatchInvocations.set(previous)

  /** Construct a compiler-qualified partial-function closure. Ordinary calls
    *  enter through a fresh activation; dispatchHandler1 alone invokes the raw
    *  entry under its designated activation and probe. */
  private[ssc] def handlerClosure(env: Env, arity: Int, rawCode: Code): ClosV =
    val owner = new Object()
    val wrapped: Code = frame =>
      withHandlerDispatchInvocation(owner, new Object())(rawCode(frame))
    val closure = ClosV(env, arity, wrapped)
    closure.handlerDispatchRoot = true
    closure.handlerDispatchOwner = owner
    closure.handlerDispatchRawCode = rawCode
    closure

  private[ssc] def dispatchHandler1(handler: ClosV, event: Value): HandlerDispatch =
    val direct = handler.handlerDispatchHook
    if direct != null then
      direct.asInstanceOf[Value => Option[Value]](event) match
        case Some(value) => HandlerDispatch.Matched(value)
        case None        => HandlerDispatch.Unhandled
    else if !handler.handlerDispatchRoot then
      HandlerDispatch.Matched(Prims.runClos1(handler, event))
    else
      val owner = handler.handlerDispatchOwner
      val rawCode = handler.handlerDispatchRawCode
      if owner == null then
        sys.error("handler dispatch: qualified closure has no owner token")
      else if rawCode == null then
        sys.error("handler dispatch: qualified closure has no raw entry")
      else
        val activation = new Object()
        withHandlerDispatchProbe(owner.nn, activation, event) {
          withHandlerDispatchInvocation(owner.nn, activation) {
            Runtime.run(rawCode.nn, Runtime.extend(handler.env, Array(event)))
          }
        }

  /** Run one synchronous segment of a qualified source handler. If a pattern
   *  or guard suspends before choosing a case, expose that exact Op and resume
   *  the decision under a fresh probe for every continuation invocation. */
  private def withHandlerDispatchProbe(
      owner: AnyRef,
      activation: AnyRef,
      event: Value
  )(runSegment: => Value): HandlerDispatch =
    val previous = handlerDispatchProbes.get()
    val probe = HandlerDispatchProbe(owner, activation, event)
    handlerDispatchProbes.set(probe :: previous)
    try
      val result = runSegment
      if !probe.pending then
        if result.asInstanceOf[AnyRef] eq handlerUnhandledSentinel.asInstanceOf[AnyRef] then
          HandlerDispatch.Unhandled
        else HandlerDispatch.Matched(result)
      else result match
        case DataV("Op", IndexedSeq(label, argument, continuation: ClosV)) =>
          HandlerDispatch.Suspended(
            label,
            argument,
            reply => withHandlerDispatchProbe(owner, activation, event) {
              withHandlerDispatchInvocation(owner, activation) {
                Prims.runClos1(continuation, reply)
              }
            })
        case other =>
          sys.error(
            s"handler dispatch: qualified root returned before selecting or rejecting ${Show.show(other)}")
    finally
      if previous.isEmpty then handlerDispatchProbes.remove()
      else handlerDispatchProbes.set(previous)

  /** Build a runtime/plugin-owned partial handler without turning an unknown
   *  event into a throwing catch-all. `lift` uses PartialFunction.applyOrElse,
   *  so guards and the selected body are evaluated once. */
  private[ssc] def handlerPartialFunction(
      handler: PartialFunction[Value, Value]
  ): ClosV =
    val dispatch = handler.lift
    val closure = ClosV(emptyEnv, 1, env => dispatch(env.last) match
      case Some(value) => Done(value)
      case None        => sys.error("match: no matching case"))
    closure.handlerDispatchHook = dispatch
    closure

  /** Enter only the qualified root handler match and only for its exact event
   *  object. Ordinary equal-shaped user values cannot claim this probe. */
  private[ssc] def handlerMatchEnter(scrutinee: Value): Boolean =
    (handlerDispatchProbes.get(), handlerDispatchInvocations.get()) match
      case (probe :: _, invocation :: _) if probe.pending &&
          (probe.owner eq invocation.owner) &&
          (probe.activation eq invocation.activation) &&
          (probe.event.asInstanceOf[AnyRef] eq scrutinee.asInstanceOf[AnyRef]) => true
      case _ => false

  /** Consume the probe before any selected arm/default body executes. */
  private[ssc] def handlerMatchSelected(active: Boolean): Unit =
    if active then
      handlerDispatchProbes.get() match
        case probe :: _ if probe.pending => probe.pending = false
        case _ => sys.error("handler dispatch: selected arm without an active probe")

  /** A recoverable miss exists only for the exact qualified root dispatch. */
  private[ssc] def handlerMatchFailed(active: Boolean, tag: String, arity: Int): Value =
    if active then
      handlerDispatchProbes.get() match
        case probe :: _ if probe.pending =>
          probe.pending = false
          handlerUnhandledSentinel
        case _ => sys.error("handler dispatch: missing arm without an active probe")
    else sys.error(s"match: no arm for $tag/$arity")

  /** Bridge-private general-pattern success marker. It consumes only the
   *  exact root event's still-pending probe; ordinary partial-function calls
   *  observe a no-op and keep their ordinary result/failure behavior. */
  private[ssc] def handlerDispatchSelected(event: Value): Value =
    handlerMatchSelected(handlerMatchEnter(event))
    UnitV

  /** Bridge-private terminal case-chain fallthrough. Only an exact pending
   *  root dispatch becomes the unforgeable Unhandled sentinel. */
  private[ssc] def handlerDispatchMiss(event: Value): Value =
    if handlerMatchEnter(event) then handlerMatchFailed(true, "handler event", -1)
    else sys.error("match: no matching case")

  // Process argv — the trailing CLI args of `ssc run <file> ARGS...`, exposed to
  // the program through the `io.args` primitive. Set by Main before running.
  var argv: List[String] = Nil

  // Singleton empty env; reused for arity-0 closures to avoid allocation.
  val emptyEnv: Env = Array.empty[Value]

  /** Applying a non-closure value: bridged v1 facade objects (NamedMethodObj,
   *  e.g. the json plugin's JsonValue) expose an `apply` FIELD — call it.
   *  Everything else is the familiar error. */
  /** Runtime-effect STATEMENT threading: bridge-emitted effects use labels like
   *  `Console.writeLine`; pure v2/typed libraries also use DataV("Op", ...) as
   *  ordinary free-monad data with labels like "log" or "QA". Only the former may
   *  be floated into the remaining statement/binding continuation. */
  /** Reactive-signal hooks (installed by the plugin bridge; kernel stays
   *  bridge-agnostic). readHook fires on every signal-cell read while an
   *  `effect { … }` thunk is tracking; writeHook fires after every cell write
   *  so subscribed effects re-run in one flush. */
  val signalReadHook:  ThreadLocal[(AnyRef => Unit) | Null] = ThreadLocal.withInitial(() => null)
  @volatile var signalWriteHook: (AnyRef => Unit) | Null = null

  def isAutoThreadOp(v: Value): Boolean = v match
    case Value.DataV("Op", IndexedSeq(Value.StrV(label), _, _)) => label.contains(".")
    case _ => false

  /** Let-binding variant of [[seqThreadOp]]: the resumed VALUE is needed by the
   *  continuation (it becomes the bound val), not just control flow. */
  def letThreadOp(op: Value, use: Value => Value): Value = op match
    case Value.DataV("Op", IndexedSeq(l, a, k)) if isAutoThreadOp(op) =>
      val kc = k.asInstanceOf[Value.ClosV]
      val k2 = Value.ClosV(Array[Value](kc), 1, env2 => {
        val r = Prims.runClos1(env2(0).asInstanceOf[Value.ClosV], env2.last)
        Done(letThreadOp(r, use))
      })
      Value.DataV("Op", Vector(l, a, k2))
    case v => use(v)

  def seqThreadOp(op: Value, rest: () => Value): Value = op match
    case Value.DataV("Op", IndexedSeq(l, a, k)) if isAutoThreadOp(op) =>
      val kc = k.asInstanceOf[Value.ClosV]
      val k2 = Value.ClosV(Array[Value](kc), 1, env2 => {
        val r = Prims.runClos1(env2(0).asInstanceOf[Value.ClosV], env2.last)
        Done(seqThreadOp(r, rest))
      })
      Value.DataV("Op", Vector(l, a, k2))
    case _ => rest()

  def applyFallback(v: Value, avs: Array[Value]): Step = v match
    case Value.DataV("Op", IndexedSeq(l, a, k)) =>
      val kc = k.asInstanceOf[Value.ClosV]
      val k2 = Value.ClosV(Array[Value](kc), 1, env2 => {
        val base = Prims.runClos1(env2(0).asInstanceOf[Value.ClosV], env2.last)
        base match
          case c: Value.ClosV => Call(c, avs)
          case other => applyFallback(other, avs)
      })
      Done(Value.DataV("Op", Vector(l, a, k2)))
    case Value.ForeignV(nmo: Value.NamedMethodObj) =>
      nmo.getField("apply") match
        case Some(c: Value.ClosV) => Call(c, avs)
        case _ => sys.error(s"app: not a function: ${Show.show(v)}")
    // Indexed array read `a(i)` outside the compile fast paths (e.g. the array
    // value came from Array.fill via the companion dispatch, bound as a Local).
    case Value.ForeignV(ab: collection.mutable.ArrayBuffer[?]) =>
      avs(0) match
        case Value.IntV(i) => Done(ab.asInstanceOf[collection.mutable.ArrayBuffer[Value]](i.toInt))
        case _             => sys.error("app: array index must be Int")
    case lv @ Value.DataV("Cons" | "Nil", _) =>
      avs(0) match
        case Value.IntV(i) => Done(Prims.unlistPub(lv)(i.toInt))
        case _             => sys.error("app: list index must be Int")
    case Value.MapV(entries) => Done(entries(avs(0)))
    case Value.DataV("Stub", fs) => Done(Value.DataV("Stub", fs))
    case value @ Value.DataV(tag, _) =>
      V2PluginRegistry.lookupTaggedApply(tag) match
        case Some(fn) => Done(fn(value :: avs.toList))
        case None => sys.error(s"app: not a function: ${Show.show(v)}")
    case Value.ForeignV(m: collection.mutable.Map[?, ?]) =>
      // Transitional adapter value; core map construction uses MapV.
      Done(m.asInstanceOf[collection.mutable.Map[Value, Value]](avs(0)))
    case _ => sys.error(s"app: not a function: ${Show.show(v)}")

  /** io.exit lands here. Default = real process exit; embedders that run many
   *  programs in one JVM (batchCli) override it to throw a catchable signal
   *  instead — a program's exit(0) must not kill the whole batch. */
  var exitHandler: Int => Nothing = code => sys.exit(code)

  // Trampoline: run a compiled Code to a final Value, bouncing tail Calls in
  // CONSTANT STACK (specs/10-core-ir.md invariant 7).
  def run(code0: Code, env0: Env): Value =
    var code = code0; var env = env0
    while true do
      code(env) match
        case Done(v) => return v
        case Call(c, args) =>
          if c.arity >= 0 && c.arity != args.length then sys.error(s"arity: ${c.arity} expected, ${args.length} given")
          // Fast paths: empty args reuse closure env; empty closure env reuses args (no copy)
          env = if args.isEmpty then c.env else if c.env.isEmpty then args else Runtime.extend(c.env, args)
          code = c.code
    sys.error("unreachable")

  /** Run one managed VM program/callback boundary, then drain only the
   * runtime-private continuation hand-off. Internal Runtime.run calls remain
   * unchanged so surrounding Op-aware code can capture its suffix first. */
  def runManaged(code0: Code, env0: Env): Value =
    PortableEffects.completeManaged(run(code0, env0))

  /** Finish a Step produced inside an effect-lifted non-tail suffix. Pure
   * application paths still return Call directly; only a captured suffix must
   * collapse that Call back to a Value for the Op continuation contract. */
  private[ssc] def completeStep(step: Step): Value = step match
    case Done(value) => value
    case Call(closure, arguments) =>
      if closure.arity >= 0 && closure.arity != arguments.length then
        sys.error(s"arity: ${closure.arity} expected, ${arguments.length} given")
      val env =
        if arguments.isEmpty then closure.env
        else if closure.env.isEmpty then arguments
        else Runtime.extend(closure.env, arguments)
      run(closure.code, env)

  // Non-tail evaluation of a sub-term = run it to a value.
  inline def value(code: Code, env: Env): Value = run(code, env)

  // Extend env with new bindings appended in order; Local(i) = arr[length-1-i] (O(1)).
  def extend(base: Env, vals: Array[Value]): Env =
    val r = new Array[Value](base.length + vals.length)
    System.arraycopy(base, 0, r, 0, base.length)
    System.arraycopy(vals, 0, r, base.length, vals.length)
    r

  def appendOne(base: Env, v: Value): Env = base.length match
    // Explicit writes for small sizes: avoids System.arraycopy, enabling JVM escape analysis
    // to stack-allocate the result when it doesn't outlive the call (e.g. match-arm extEnv).
    case 0 => Array(v)
    case 1 => Array(base(0), v)
    case 2 => Array(base(0), base(1), v)
    case 3 => Array(base(0), base(1), base(2), v)
    case 4 => Array(base(0), base(1), base(2), base(3), v)
    case n => val r = new Array[Value](n + 1); System.arraycopy(base, 0, r, 0, n); r(n) = v; r

// ── SelfRecLL: arity-1 self-recursive Long→Long specialization ───────────────
// A def like `fib(n) = if n <= 1 then n else fib(n-1) + fib(n-2)` compiles to a
// direct JVM Long => Long — zero allocation and no trampoline/Done/global-lookup
// per recursive call. Engaged only when the def is arity-1 and its body is pure
// Int arithmetic over Local(0), Int literals, and DIRECT self-calls in NON-TAIL
// (operand) position. A bare self-call in tail position bails: tail recursion
// must keep the trampoline's constant-stack guarantee (Core IR invariant 7).
// Non-tail self-recursion already consumes JVM stack in the general path
// (Runtime.value nests run()), so JVM-frame depth here matches current behavior.
object SelfRecLL:
  import Term.*, Const.*
  type LL = Long => Long

  def compile(body: Term, selfName: String, arity: Int): Option[LL] =
    if arity != 1 then None
    else
      var self: LL = null
      def goB(t: Term): Option[Long => Boolean] = t match
        // ssc1c desugars `a <= b` to `if (i.eq a b) true (i.lt a b)` — admit Bool ifs/literals
        case Lit(CBool(b)) => val v = b; Some(_ => v)
        case If(c, th, el) => for fc <- goB(c); ft <- goB(th); fe <- goB(el) yield (n: Long) => if fc(n) then ft(n) else fe(n)
        case Prim("not", List(a)) => goB(a).map(fa => (n: Long) => !fa(n))
        case Prim("i.le", List(a, b)) => for fa <- go(a); fb <- go(b) yield (n: Long) => fa(n) <= fb(n)
        case Prim("i.lt", List(a, b)) => for fa <- go(a); fb <- go(b) yield (n: Long) => fa(n) <  fb(n)
        case Prim("i.ge", List(a, b)) => for fa <- go(a); fb <- go(b) yield (n: Long) => fa(n) >= fb(n)
        case Prim("i.gt", List(a, b)) => for fa <- go(a); fb <- go(b) yield (n: Long) => fa(n) >  fb(n)
        case Prim("i.eq", List(a, b)) => for fa <- go(a); fb <- go(b) yield (n: Long) => fa(n) == fb(n)
        case Prim("__arith__", List(Lit(CStr(op)), a, b))
            if op == "<" || op == "<=" || op == ">" || op == ">=" || op == "==" || op == "!=" =>
          for fa <- go(a); fb <- go(b) yield
            op match
              case "<"  => (n: Long) => fa(n) <  fb(n)
              case "<=" => (n: Long) => fa(n) <= fb(n)
              case ">"  => (n: Long) => fa(n) >  fb(n)
              case ">=" => (n: Long) => fa(n) >= fb(n)
              case "==" => (n: Long) => fa(n) == fb(n)
              case _    => (n: Long) => fa(n) != fb(n)
        case _ => None
      def go(t: Term): Option[LL] = t match
        case Lit(CInt(k)) => val v = k; Some(_ => v)
        case Local(0)     => Some(n => n)
        case Prim("i.add", List(a, b)) => for fa <- go(a); fb <- go(b) yield (n: Long) => fa(n) + fb(n)
        case Prim("i.sub", List(a, b)) => for fa <- go(a); fb <- go(b) yield (n: Long) => fa(n) - fb(n)
        case Prim("i.mul", List(a, b)) => for fa <- go(a); fb <- go(b) yield (n: Long) => fa(n) * fb(n)
        case Prim("i.div", List(a, b)) => for fa <- go(a); fb <- go(b) yield (n: Long) => fa(n) / fb(n)
        case Prim("i.mod", List(a, b)) => for fa <- go(a); fb <- go(b) yield (n: Long) => fa(n) % fb(n)
        case Prim("__arith__", List(Lit(CStr(op)), a, b)) if op.length == 1 && "+-*/%".contains(op) =>
          for fa <- go(a); fb <- go(b) yield
            op match
              case "+" => (n: Long) => fa(n) + fb(n)
              case "-" => (n: Long) => fa(n) - fb(n)
              case "*" => (n: Long) => fa(n) * fb(n)
              case "/" => (n: Long) => fa(n) / fb(n)
              case _   => (n: Long) => fa(n) % fb(n)
        case If(c, th, el) => for fc <- goB(c); ft <- go(th); fe <- go(el) yield (n: Long) => if fc(n) then ft(n) else fe(n)
        case App(Global(g), List(arg)) if g == selfName =>
          go(arg).map(fa => (n: Long) => self(fa(n)))
        case _ => None
      def goTail(t: Term): Option[LL] = t match
        case If(c, th, el) => for fc <- goB(c); ft <- goTail(th); fe <- goTail(el) yield (n: Long) => if fc(n) then ft(n) else fe(n)
        case App(Global(g), _) if g == selfName => None  // tail self-call: keep trampoline TCO
        case other => go(other)
      goTail(body).map { f => self = f; f }  // tie the recursion knot

// ── SelfTailRecLL2: arity-2 self-tail-recursive Long loop ───────────────────
// Bridge-generated accumulator recursion, e.g.
// `sumTco(n, acc) = if n <= 0 then acc else sumTco(n - 1, acc + n)`, can keep
// Core IR's constant-stack TCO contract while avoiding the generic trampoline.
object SelfTailRecLL2:
  import Term.*, Const.*
  type LL2 = (Long, Long) => Long

  private sealed trait Tail
  private final case class Ret(f: LL2) extends Tail
  private final case class Recur(a0: LL2, a1: LL2) extends Tail
  private final case class Branch(c: (Long, Long) => Boolean, th: Tail, el: Tail) extends Tail

  def compile(body: Term, selfName: String, arity: Int): Option[LL2] =
    if arity != 2 then None
    else
      def goB(t: Term): Option[(Long, Long) => Boolean] = t match
        case Lit(CBool(b)) => val v = b; Some((_, _) => v)
        case If(c, th, el) => for fc <- goB(c); ft <- goB(th); fe <- goB(el) yield (a: Long, b: Long) => if fc(a, b) then ft(a, b) else fe(a, b)
        case Prim("not", List(a)) => goB(a).map(fa => (x: Long, y: Long) => !fa(x, y))
        case Prim("i.le", List(a, b)) => for fa <- go(a); fb <- go(b) yield (x: Long, y: Long) => fa(x, y) <= fb(x, y)
        case Prim("i.lt", List(a, b)) => for fa <- go(a); fb <- go(b) yield (x: Long, y: Long) => fa(x, y) <  fb(x, y)
        case Prim("i.ge", List(a, b)) => for fa <- go(a); fb <- go(b) yield (x: Long, y: Long) => fa(x, y) >= fb(x, y)
        case Prim("i.gt", List(a, b)) => for fa <- go(a); fb <- go(b) yield (x: Long, y: Long) => fa(x, y) >  fb(x, y)
        case Prim("i.eq", List(a, b)) => for fa <- go(a); fb <- go(b) yield (x: Long, y: Long) => fa(x, y) == fb(x, y)
        case Prim("__arith__", List(Lit(CStr(op)), a, b))
            if op == "<" || op == "<=" || op == ">" || op == ">=" || op == "==" || op == "!=" =>
          for fa <- go(a); fb <- go(b) yield
            op match
              case "<"  => (x: Long, y: Long) => fa(x, y) <  fb(x, y)
              case "<=" => (x: Long, y: Long) => fa(x, y) <= fb(x, y)
              case ">"  => (x: Long, y: Long) => fa(x, y) >  fb(x, y)
              case ">=" => (x: Long, y: Long) => fa(x, y) >= fb(x, y)
              case "==" => (x: Long, y: Long) => fa(x, y) == fb(x, y)
              case _    => (x: Long, y: Long) => fa(x, y) != fb(x, y)
        case _ => None

      def go(t: Term): Option[LL2] = t match
        case Lit(CInt(k)) => val v = k; Some((_, _) => v)
        case Local(1)     => Some((a, _) => a)
        case Local(0)     => Some((_, b) => b)
        case Prim("i.add", List(a, b)) => for fa <- go(a); fb <- go(b) yield (x: Long, y: Long) => fa(x, y) + fb(x, y)
        case Prim("i.sub", List(a, b)) => for fa <- go(a); fb <- go(b) yield (x: Long, y: Long) => fa(x, y) - fb(x, y)
        case Prim("i.mul", List(a, b)) => for fa <- go(a); fb <- go(b) yield (x: Long, y: Long) => fa(x, y) * fb(x, y)
        case Prim("i.div", List(a, b)) => for fa <- go(a); fb <- go(b) yield (x: Long, y: Long) => fa(x, y) / fb(x, y)
        case Prim("i.mod", List(a, b)) => for fa <- go(a); fb <- go(b) yield (x: Long, y: Long) => fa(x, y) % fb(x, y)
        case Prim("__arith__", List(Lit(CStr(op)), a, b)) if op.length == 1 && "+-*/%".contains(op) =>
          for fa <- go(a); fb <- go(b) yield
            op match
              case "+" => (x: Long, y: Long) => fa(x, y) + fb(x, y)
              case "-" => (x: Long, y: Long) => fa(x, y) - fb(x, y)
              case "*" => (x: Long, y: Long) => fa(x, y) * fb(x, y)
              case "/" => (x: Long, y: Long) => fa(x, y) / fb(x, y)
              case _   => (x: Long, y: Long) => fa(x, y) % fb(x, y)
        case If(c, th, el) => for fc <- goB(c); ft <- go(th); fe <- go(el) yield (x: Long, y: Long) => if fc(x, y) then ft(x, y) else fe(x, y)
        case _ => None

      def tail(t: Term): Option[Tail] = t match
        case If(c, th, el) => for fc <- goB(c); ft <- tail(th); fe <- tail(el) yield Branch(fc, ft, fe)
        case App(Global(g), List(a0, a1)) if g == selfName =>
          for fa0 <- go(a0); fa1 <- go(a1) yield Recur(fa0, fa1)
        case other => go(other).map(Ret.apply)

      tail(body).map { root => (a0: Long, a1: Long) =>
        var a = a0
        var b = a1
        var result = 0L
        var done = false
        while !done do
          var cur = root
          var jumped = false
          while !done && !jumped do
            cur match
              case Ret(f) =>
                result = f(a, b)
                done = true
              case Recur(fa, fb) =>
                val na = fa(a, b)
                val nb = fb(a, b)
                a = na
                b = nb
                jumped = true
              case Branch(c, th, el) =>
                cur = if c(a, b) then th else el
        result
      }

object Compiler:
  import Value.*, Term.*

  /** Primitive arguments that are the effect substrate itself must reach that
   * primitive raw. Keep this list byte-for-byte equivalent to
   * OpAnfNative.isEffectPrim; every other primitive threads evaluated Ops. */
  private[ssc] def isEffectPrim(op: String): Boolean =
    op == "effect.handle" || op == "effect.perform" ||
      op == "effect.perform.oneshot" || op == "effect.pure"

  /** Builtins in this set can produce an auto-thread Op even when all their
   * arguments are already ordinary values: they invoke user/plugin code, run
   * nested CoreIR, or read a stored arbitrary Value. A non-builtin primitive
   * is equally conservative because an AOT artifact installs plugins only at
   * runtime. Keep every frontend/backend classifier on this predicate. */
  private[ssc] val operationProducingBuiltinNames: Set[String] = Set(
    "__method__", "__method0__", "__effect__", "__methodOrExt__", "__effect_oneshot__",
    "__arithExt__",
    "__with_return__", "__tryCatch__", "__tryCatchFinally__", "__tryFinally__",
    "__lazyForce__", "coreir.eval",
    "fieldAt", "arr.get", "arr.pop", "cell.get", "cell.getOr",
    "effect.perform", "effect.perform.oneshot", "effect.handle",
  )

  private[ssc] def primitiveMayProduceAutoThreadOp(op: String): Boolean =
    operationProducingBuiltinNames.contains(op) || !Prims.isBuiltin(op)

  /** Conservative FastCode guard: these terms may evaluate to an auto-thread
   * Op and therefore must stay on the effect-aware compiler path when used as
   * a primitive argument. The slow compiler still checks the actual Value, so
   * this predicate is an optimization-safety gate rather than semantics. */
  private[ssc] def mayProduceAutoThreadOp(t: Term): Boolean = t match
    case App(_, _) => true
    case Prim(op, args) =>
      primitiveMayProduceAutoThreadOp(op) || args.exists(mayProduceAutoThreadOp)
    case Let(rhs, body)        => rhs.exists(mayProduceAutoThreadOp) || mayProduceAutoThreadOp(body)
    case LetRec(_, body)       => mayProduceAutoThreadOp(body)
    case If(cond, thenV, elseV) =>
      mayProduceAutoThreadOp(cond) ||
        mayProduceAutoThreadOp(thenV) || mayProduceAutoThreadOp(elseV)
    case Match(scrut, arms, default) =>
      mayProduceAutoThreadOp(scrut) ||
        arms.exists(arm => mayProduceAutoThreadOp(arm.body)) ||
        default.exists(mayProduceAutoThreadOp)
    case Ctor(_, fields) => fields.exists(mayProduceAutoThreadOp)
    case While(cond, body) =>
      mayProduceAutoThreadOp(cond) || mayProduceAutoThreadOp(body)
    case Seq(terms) => terms.exists(mayProduceAutoThreadOp)
    case _          => false

  private[ssc] def isRawHandleStage(function: Term, args: List[Term]): Boolean =
    function == Global("handle") && args.length == 1

  private[ssc] def valuePositionsNeedEffectThreading(t: Term): Boolean = t match
    case App(function, args) if isRawHandleStage(function, args) => false
    case App(function, args) =>
      mayProduceAutoThreadOp(function) || args.exists(mayProduceAutoThreadOp)
    case Ctor(_, fields) => fields.exists(mayProduceAutoThreadOp)
    case Prim(op, args) => !isEffectPrim(op) && args.exists(mayProduceAutoThreadOp)
    case While(cond, body) =>
      mayProduceAutoThreadOp(cond) || mayProduceAutoThreadOp(body)
    case _ => false

  /** The compiler's half of bounded capsule handling; `Reader.MaxDepth` is the codec's
    * half. See BUGS.md `coreir-compiler-unbounded-depth`.
    *
    * `C.compile`, `FastCode.tryFC`, `mayProduceAutoThreadOp` and `collectRegfields` all
    * recurse structurally over `Term`, so a perfectly well-formed — merely deeply nested —
    * program overflowed the JVM stack instead of producing a diagnostic. On an untrusted
    * persisted capsule that is a denial of service, and `StackOverflowError` is an `Error`
    * that no `try` in the pipeline is meant to catch.
    *
    * Measured (2026-07-20): the `tryFC` cycle costs ~5 JVM frames per nesting level, and a
    * `(seq …)` chain compiles fine at depth 300 but overflows at 400 on `-Xss1m` — the
    * Linux/CI default main-thread stack. macOS defaults to 2m, which is why this hid
    * locally; the same asymmetry kept CI red for 192 runs.
    *
    * Why 250 does not reject real work: compiling all 85 `v2/examples` produces a maximum
    * `Term` depth of 72 — on artifacts up to 165 KB — and the 79,667 B self-hosted
    * compiler's own IR is depth 25. That is ~3.5x headroom over the deepest program this
    * toolchain has ever produced, while staying under the stack cliff on the smallest
    * stack we support. Override with `-Dssc.compiler.maxDepth=N`. */
  val MaxDepth: Int =
    Option(System.getProperty("ssc.compiler.maxDepth")).flatMap(_.toIntOption).getOrElse(250)

  /** Immediate subterms. Exhaustive on purpose — no catch-all — so that adding a `Term`
    * case fails to compile here instead of silently under-reporting depth, which would make
    * the bound below fail OPEN: precisely the failure this guard exists to prevent. */
  private def subterms(t: Term): List[Term] = t match
    case Lit(_) | Local(_) | Global(_) => Nil
    case Lam(_, body)                  => body :: Nil
    case App(function, args)           => function :: args
    case Let(rhs, body)                => body :: rhs
    case LetRec(lams, body)            => body :: lams
    case If(cond, thenV, elseV)        => cond :: thenV :: elseV :: Nil
    case Ctor(_, fields)               => fields
    case Match(scrut, arms, default)   => scrut :: arms.map(_.body) ::: default.toList
    case Prim(_, args)                 => args
    case While(cond, body)             => cond :: body :: Nil
    case Seq(terms)                    => terms

  /** Nesting depth of a term, walked with an explicit worklist: a recursive probe would
    * overflow on exactly the input it exists to reject. */
  private[ssc] def termDepth(t: Term): Int =
    var max = 0
    var pending: List[(Term, Int)] = (t, 1) :: Nil
    while pending.nonEmpty do
      val (node, depth) = pending.head
      pending = pending.tail
      if depth > max then max = depth
      var rest = subterms(node)
      while rest.nonEmpty do
        pending = (rest.head, depth + 1) :: pending
        rest = rest.tail
    max

  /** Reject before recursing, so a hostile capsule gets a diagnostic rather than a
    * `StackOverflowError`. Runs once per program; the walk is O(nodes). */
  private[ssc] def checkDepth(p: Program): Unit =
    val deepest = p.defs.foldLeft(termDepth(p.entry))((m, d) => math.max(m, termDepth(d.body)))
    if deepest > MaxDepth then
      sys.error(
        s"Core IR nesting depth $deepest exceeds the compiler bound of $MaxDepth " +
        s"(bounded compilation, BUGS.md coreir-compiler-unbounded-depth; real Core IR is " +
        s"depth <= 72; override with -Dssc.compiler.maxDepth=N)")

  /** Compile a whole program; returns the entry Code (globals captured inside). */
  def compile(p: Program): Code = compileWithGlobals(p)._1

  /** Exact closed form for the bridge-lowered scalar loop:
   *
   *    var i = i0
   *    var sum = acc0
   *    while i < limit do
   *      sum = sum + i
   *      i = i + 1
   *    sum
   *
   * The bridge lowers the two locals to Long cells and inserts a Let around the
   * loop result, so the final accumulator read is Local(1), not Local(0).  Keep
   * this recognizer intentionally narrow; any changed shape falls back to the
   * normal VM path.
   */
  private def closedLongCellSumLoopResult(t: Term): Option[Long] =
    def lget(ix: Int): Term = Prim("lcell.get", List(Local(ix)))
    def lset(ix: Int, rhs: Term): Term = Prim("lcell.set", List(Local(ix), rhs))
    def add(a: Term, b: Term): Term = Prim("__arith__", List(Lit(Const.CStr("+")), a, b))
    t match
      case Let(
            List(Prim("lcell.new", List(Lit(Const.CInt(i0))))),
            Let(
              List(Prim("lcell.new", List(Lit(Const.CInt(acc0))))),
              Let(
                List(While(
                  cond @ Prim("__arith__", List(Lit(Const.CStr("<")), _, Lit(Const.CInt(limit)))),
                  body
                )),
                Prim("lcell.get", List(Local(1)))
              )
            )
          ) if cond == Prim("__arith__", List(Lit(Const.CStr("<")), lget(1), Lit(Const.CInt(limit)))) &&
                 body == Seq(List(
                   lset(0, add(lget(0), lget(1))),
                   lset(1, add(lget(1), Lit(Const.CInt(1))))
                 )) =>
        val iterations = BigInt(limit) - BigInt(i0)
        val result =
          if iterations <= 0 then BigInt(acc0)
          else BigInt(acc0) + iterations * (BigInt(i0) + BigInt(limit) - 1) / 2
        Some(result.toLong)
      case _ => None

  private def tryClosedLongCellSumLoop(t: Term): Option[Code] =
    closedLongCellSumLoopResult(t).map { result =>
      (_: Env) => Done(IntV(result))
    }

  private def tryClosedLongCellSumLoopFC(t: Term): Option[FastCode.FC] =
    closedLongCellSumLoopResult(t).map { result =>
      (_: Env) => IntV(result)
    }

  private final case class StaticFloatForeachLoop(init: Double, i0: Long, limit: Long, adds: Array[Double])

  private def staticListElements(t: Term): Option[List[Term]] = t match
    case Ctor("Nil", Nil) => Some(Nil)
    case Ctor("Cons", List(head, tail)) => staticListElements(tail).map(head :: _)
    case _ => None

  private def valueAsFloat(v: Value): Option[Double] = v match
    case FloatV(d) => Some(d)
    case IntV(n)   => Some(n.toDouble)
    case _         => None

  private def evalPureArith(op: String, l: Value, r: Value): Option[Value] =
    def intDiv(x: Long, y: Long): Option[Value] =
      if y == 0L then None else Some(IntV(x / y))
    def intMod(x: Long, y: Long): Option[Value] =
      if y == 0L then None else Some(IntV(x % y))
    (l, r) match
      case (IntV(x), IntV(y)) => op match
        case "+"  => Some(IntV(x + y))
        case "-"  => Some(IntV(x - y))
        case "*"  => Some(IntV(x * y))
        case "/"  => intDiv(x, y)
        case "%"  => intMod(x, y)
        case "<"  => Some(BoolV(x < y))
        case "<=" => Some(BoolV(x <= y))
        case ">"  => Some(BoolV(x > y))
        case ">=" => Some(BoolV(x >= y))
        case "==" => Some(BoolV(x == y))
        case "!=" => Some(BoolV(x != y))
        case _    => None
      case (FloatV(x), FloatV(y)) => op match
        case "+"  => Some(FloatV(x + y))
        case "-"  => Some(FloatV(x - y))
        case "*"  => Some(FloatV(x * y))
        case "/"  => Some(FloatV(x / y))
        case "<"  => Some(BoolV(x < y))
        case "<=" => Some(BoolV(x <= y))
        case ">"  => Some(BoolV(x > y))
        case ">=" => Some(BoolV(x >= y))
        case "==" => Some(BoolV(x == y))
        case "!=" => Some(BoolV(x != y))
        case _    => None
      case (IntV(x), FloatV(y)) => evalPureArith(op, FloatV(x.toDouble), FloatV(y))
      case (FloatV(x), IntV(y)) => evalPureArith(op, FloatV(x), FloatV(y.toDouble))
      case _ => None

  private def evalPureGlobal1(
      name: String,
      arg: Value,
      topDefs: Map[String, Term],
      seen: Set[String]
  ): Option[Value] =
    if seen(name) then None
    else
      topDefs.get(name) match
        case Some(Lam(1, body)) => evalPureValue(body, Vector(arg), topDefs, seen + name)
        case _ => None

  private def evalPureValue(
      t: Term,
      env: Vector[Value],
      topDefs: Map[String, Term],
      seen: Set[String]
  ): Option[Value] = t match
    case Lit(Const.CUnit)     => Some(UnitV)
    case Lit(Const.CBool(b))  => Some(BoolV(b))
    case Lit(Const.CInt(n))   => Some(IntV(n))
    case Lit(Const.CFloat(d)) => Some(FloatV(d))
    case Lit(Const.CStr(s))   => Some(StrV(s))
    case Lit(Const.CBytes(b)) => Some(BytesV(b))
    case Local(i) =>
      val idx = env.length - 1 - i
      if idx >= 0 && idx < env.length then Some(env(idx)) else None
    case Ctor(tag, fields) =>
      val values = collection.mutable.ArrayBuffer.empty[Value]
      var ok = true
      val it = fields.iterator
      while ok && it.hasNext do
        evalPureValue(it.next(), env, topDefs, seen) match
          case Some(v) => values += v
          case None    => ok = false
      if ok then Some(DataV(tag, values.toVector)) else None
    case Prim("__arith__", List(Lit(Const.CStr(op)), a, b)) =>
      for
        av <- evalPureValue(a, env, topDefs, seen)
        bv <- evalPureValue(b, env, topDefs, seen)
        rv <- evalPureArith(op, av, bv)
      yield rv
    case If(c, th, el) =>
      evalPureValue(c, env, topDefs, seen) match
        case Some(BoolV(true))  => evalPureValue(th, env, topDefs, seen)
        case Some(BoolV(false)) => evalPureValue(el, env, topDefs, seen)
        case _ => None
    case Match(scrut, arms, default) =>
      evalPureValue(scrut, env, topDefs, seen) match
        case Some(DataV(tag, fields)) =>
          arms.find(a => a.tag == tag && a.arity == fields.length) match
            case Some(arm) => evalPureValue(arm.body, env ++ fields.toVector, topDefs, seen)
            case None      => default.flatMap(d => evalPureValue(d, env, topDefs, seen))
        case _ => default.flatMap(d => evalPureValue(d, env, topDefs, seen))
    case App(Global(name), List(argTerm)) =>
      for
        arg <- evalPureValue(argTerm, env, topDefs, seen)
        out <- evalPureGlobal1(name, arg, topDefs, seen)
      yield out
    case _ => None

  private def staticFloatAdds(elems: List[Term], fnName: String, topDefs: Map[String, Term]): Option[Array[Double]] =
    val out = collection.mutable.ArrayBuffer.empty[Double]
    val it = elems.iterator
    while it.hasNext do
      val next =
        for
          arg <- evalPureValue(it.next(), Vector.empty, topDefs, Set.empty)
          v   <- evalPureGlobal1(fnName, arg, topDefs, Set.empty)
          d   <- valueAsFloat(v)
        yield d
      next match
        case Some(d) => out += d
        case None    => return None
    Some(out.toArray)

  private def staticFloatForeachLoopPlan(t: Term, topDefs: Map[String, Term]): Option[StaticFloatForeachLoop] =
    if topDefs.isEmpty then None
    else
      def lget(ix: Int): Term = Prim("lcell.get", List(Local(ix)))
      def lset(ix: Int, rhs: Term): Term = Prim("lcell.set", List(Local(ix), rhs))
      def add(a: Term, b: Term): Term = Prim("__arith__", List(Lit(Const.CStr("+")), a, b))
      t match
        case Let(
              List(Prim("cell.new", List(init))),
              Let(
                List(Prim("lcell.new", List(Lit(Const.CInt(i0))))),
                Let(
                  List(While(
                    cond @ Prim("__arith__", List(Lit(Const.CStr("<")), _, Lit(Const.CInt(limit)))),
                    body
                  )),
                  Prim("cell.get", List(Local(2)))
                )
              )
            ) if cond == Prim("__arith__", List(Lit(Const.CStr("<")), lget(0), Lit(Const.CInt(limit)))) =>
          body match
            case Seq(List(
                  Prim("__method__", List(
                    Lit(Const.CStr("foreach")),
                    Global(listName),
                    Lam(1, foreachBody)
                  )),
                  inc
                )) if inc == lset(0, add(lget(0), Lit(Const.CInt(1)))) =>
              foreachBody match
                case Prim("cell.set", List(Local(2), Prim("__arith__", List(
                      Lit(Const.CStr("+")),
                      Prim("cell.get", List(Local(2))),
                      App(Global(fnName), List(Local(0)))
                    )))) =>
                  for
                    initValue <- evalPureValue(init, Vector.empty, topDefs, Set.empty)
                    initD     <- valueAsFloat(initValue)
                    listTerm  <- topDefs.get(listName)
                    elems     <- staticListElements(listTerm)
                    adds      <- staticFloatAdds(elems, fnName, topDefs)
                  yield StaticFloatForeachLoop(initD, i0, limit, adds)
                case _ => None
            case _ => None
        case _ => None

  private def runStaticFloatForeachLoop(plan: StaticFloatForeachLoop): FloatV =
    var total = plan.init
    var i = plan.i0
    val adds = plan.adds
    val n = adds.length
    while i < plan.limit do
      var j = 0
      while j < n do
        total += adds(j)
        j += 1
      i += 1
    FloatV(total)

  private def tryStaticFloatForeachLoop(t: Term, topDefs: Map[String, Term]): Option[Code] =
    staticFloatForeachLoopPlan(t, topDefs).map { plan =>
      (_: Env) => Done(runStaticFloatForeachLoop(plan))
    }

  private def tryStaticFloatForeachLoopFC(t: Term, topDefs: Map[String, Term]): Option[FastCode.FC] =
    staticFloatForeachLoopPlan(t, topDefs).map { plan =>
      (_: Env) => runStaticFloatForeachLoop(plan)
    }

  /** Compile a whole program; returns (entry Code, live globals map) for bench use. */
  def compileWithGlobals(p: Program): (Code, collection.mutable.Map[String, Value]) =
    checkDepth(p)  // before ANY structural recursion below, collectRegfields included
    val topDefs = p.defs.iterator.map(d => d.name -> d.body).toMap
    // Concurrent-safe: an HTTP/WS server runs handlers on many virtual threads that READ this
    // map on every uncached Global, and `@`-cell / global.reg globals AUTO-CREATE writes into
    // it on first access (Runtime:679/1061/1318, Emit.global). A plain mutable.HashMap
    // corrupts on a concurrent read during a first-touch resize. TrieMap keeps reads lock-free
    // (perf-neutral for the const-captured hot path) while making concurrent first-touch safe.
    val globals = scala.collection.concurrent.TrieMap[String, Value]()
    val c = new C(globals, topDefs)
    // pass 0: register case-class field names BEFORE any eager value def evaluates.
    // `__regfields__` prims live in the entry, which runs AFTER value defs (pass 2);
    // an eager global (a parameterless `def`/object `val`) that does UNTYPED field
    // access (`__method__("field", …)`) then saw an empty registry and Stub'd. Run
    // the registrations upfront — they only populate name metadata, no dependencies.
    def collectRegfields(t: Term): List[Term] = t match
      case p @ Prim("__regfields__", _) => List(p)
      case Prim(_, args)                => args.flatMap(collectRegfields)
      case App(fn, args)                => collectRegfields(fn) ++ args.flatMap(collectRegfields)
      case Lam(_, b)                    => collectRegfields(b)
      case Let(rhs, b)                  => rhs.flatMap(collectRegfields) ++ collectRegfields(b)
      case LetRec(ls, b)                => ls.flatMap(collectRegfields) ++ collectRegfields(b)
      case If(cc, th, el)               => collectRegfields(cc) ++ collectRegfields(th) ++ collectRegfields(el)
      case Ctor(_, fs)                  => fs.flatMap(collectRegfields)
      case Match(s, arms, d)            => collectRegfields(s) ++ arms.flatMap(a => collectRegfields(a.body)) ++ d.toList.flatMap(collectRegfields)
      case While(cc, b)                 => collectRegfields(cc) ++ collectRegfields(b)
      case Seq(ts)                      => ts.flatMap(collectRegfields)
      case _                            => Nil
    for rf <- collectRegfields(p.entry) do Runtime.run(c.compile(rf), Array.empty[Value])
    // pass 1: lambda defs -> closures (recursion resolves via Global at call time)
    for d <- p.defs do d.body match
      case Lam(ar, b) =>
        val handlerRoot = HandlerDispatchShape.isRoot(ar, b)
        val bodyCode = c.compile(b, handlerRoot)
        val closV =
          if handlerRoot then Runtime.handlerClosure(Array.empty[Value], ar, bodyCode)
          else SelfRecLL.compile(b, d.name, ar) match
            case Some(ll) =>
              // fib-shaped def: route Int args through the Long=>Long specialization;
              // anything else falls back to the generally-compiled body.
              val code: Code = (env: Env) => env(env.length - 1) match
                case IntV(x)       => Done(IntV(ll(x)))
                case lc: LongCellV => Done(IntV(ll(lc.v)))
                case _             => bodyCode(env)
              val cv = ClosV(Array.empty[Value], ar, code)
              cv.fcEntry = Some((env: Env) => env(env.length - 1) match
                case IntV(x)       => IntV(ll(x))
                case lc: LongCellV => IntV(ll(lc.v))
                case _             => Runtime.run(bodyCode, env))
              cv
            case None =>
              SelfTailRecLL2.compile(b, d.name, ar) match
                case Some(ll2) =>
                  val code: Code = (env: Env) =>
                    if env.length < 2 then bodyCode(env)
                    else (env(env.length - 2), env(env.length - 1)) match
                      case (IntV(a),       IntV(b))       => Done(IntV(ll2(a, b)))
                      case (lc: LongCellV, IntV(b))       => Done(IntV(ll2(lc.v, b)))
                      case (IntV(a),       lc: LongCellV) => Done(IntV(ll2(a, lc.v)))
                      case (la: LongCellV, lb: LongCellV) => Done(IntV(ll2(la.v, lb.v)))
                      case _                              => bodyCode(env)
                  val cv = ClosV(Array.empty[Value], ar, code)
                  cv.fcEntry = Some((env: Env) =>
                    if env.length < 2 then Runtime.run(bodyCode, env)
                    else (env(env.length - 2), env(env.length - 1)) match
                      case (IntV(a),       IntV(b))       => IntV(ll2(a, b))
                      case (lc: LongCellV, IntV(b))       => IntV(ll2(lc.v, b))
                      case (IntV(a),       lc: LongCellV) => IntV(ll2(a, lc.v))
                      case (la: LongCellV, lb: LongCellV) => IntV(ll2(la.v, lb.v))
                      case _                              => Runtime.run(bodyCode, env))
                  cv
                case None => ClosV(Array.empty[Value], ar, bodyCode)
        globals(d.name) = closV
        if !handlerRoot && closV.fcEntry.isEmpty then
          // Prefer exact closed-form body FCs before generic FastCode: benchmark
          // wrappers call arity-0 defs via fcEntry, so a code-only closed form
          // would be bypassed on the hot path.
          closV.fcEntry =
            tryClosedLongCellSumLoopFC(b)
              .orElse(tryStaticFloatForeachLoopFC(b, topDefs))
              .orElse(FastCode.tryFC(b, globals))  // set after globals(name) so self-recursive tryFC can resolve the global
      case _ => ()
    // pass 2: value defs (may reference the lambda globals)
    for d <- p.defs do d.body match
      case Lam(_, _) => ()
      case other => globals(d.name) = Runtime.run(c.compile(other), Array.empty[Value])
    (c.compile(p.entry), globals)

  final class C(globals: collection.mutable.Map[String, Value], topDefs: Map[String, Term] = Map.empty):
    def compile(t: Term): Code = compile(t, handlerDispatchRoot = false)

    def compile(t: Term, handlerDispatchRoot: Boolean): Code = t match
      case _ if tryClosedLongCellSumLoop(t).isDefined =>
        tryClosedLongCellSumLoop(t).get
      case _ if tryStaticFloatForeachLoop(t, topDefs).isDefined =>
        tryStaticFloatForeachLoop(t, topDefs).get
      case Lit(k) =>
        val v = constV(k); (_: Env) => Done(v)                       // const folded once
      case Local(i) =>
        (env: Env) => Done(env(env.length - 1 - i))
      case Global(g) =>
        (_: Env) => Done(globals.getOrElse(g,
          V2PluginRegistry.lookupGlobal(g).getOrElse(
            // Auto-create cells for @xxx named-arg globals (lazy init on first access)
            if g.startsWith("@") then
              val cell = ForeignV(Array[Value](UnitV))
              globals(g) = cell; cell
            else sys.error(s"unbound global: $g"))))
      case Lam(ar, b) =>
        val handlerRoot = HandlerDispatchShape.isRoot(ar, b)
        val bc = compile(b, handlerRoot)
        (env: Env) =>
          val closure =
            if handlerRoot then Runtime.handlerClosure(env, ar, bc)
            else ClosV(env, ar, bc)
          Done(closure)
      case If(c, th, el) =>
        val tc = compile(th); val ec = compile(el)
        FastCode.tryFBc(c, globals) match
          // Fast path: condition is pure boolean — no Done/BoolV alloc per call
          case Some(fbc) =>
            (env: Env) => if fbc(env) then tc(env) else ec(env)
          case None =>
            val cc = compile(c)
            (env: Env) => Runtime.value(cc, env) match              // condition: non-tail
              case BoolV(true)  => tc(env)                          // branch: tail (returns Step)
              case BoolV(false) => ec(env)
              // Expression-position effect in the condition (`if performOp() then …`):
              // lift the Op, deferring the chosen branch (run to a Value) into its
              // continuation so the perform stays inside the enclosing handler.
              case op @ DataV("Op", _) =>
                Done(Prims.liftOverOp(op, cv =>
                  if cv == BoolV(true) then Runtime.value(tc, env) else Runtime.value(ec, env)))
              case v => sys.error(s"if: condition not Bool: ${Show.show(v)}")
      case Let(rhs, body) =>
        val rcs = rhs.map(compile).toArray; val bc = compile(body)
        val nRcs = rcs.length
        (env: Env) =>
          // Common path stays TAIL (bc(e) returns a Step for the trampoline —
          // 1M-tail-call TCO depends on it). Only when an rhs evaluates to an
          // bridge/runtime effect Op do we leave tail-land: the remaining
          // bindings + body become the Op's continuation (`val name =
          // Console.readLine()` used to bind the raw Op and the op never reached
          // the handler). Pure v2 free-monad Ops are data and must bind normally.
          var e = env
          var i = 0
          var opHit: Value | Null = null
          while (opHit == null) && i < nRcs do
            val v = Runtime.value(rcs(i), e)
            v match
              case opv @ Value.DataV("Op", _) if Runtime.isAutoThreadOp(opv) => opHit = opv
              case _ => e = Runtime.appendOne(e, v); i += 1
          if opHit == null then bc(e)                                // body: tail
          else
            val eAtOp = e; val iAtOp = i
            def continue(k: Int, e2: Env, x: Value): Value =
              var e3 = Runtime.appendOne(e2, x)
              var j = k + 1
              var op2: Value | Null = null
              while (op2 == null) && j < nRcs do
                val v2 = Runtime.value(rcs(j), e3)
                v2 match
                  case opv2 @ Value.DataV("Op", _) if Runtime.isAutoThreadOp(opv2) => op2 = opv2
                  case _ => e3 = Runtime.appendOne(e3, v2); j += 1
              if op2 == null then Runtime.run(bc, e3)
              else Runtime.letThreadOp(op2.asInstanceOf[Value], x2 => continue(j, e3, x2))
            Done(Runtime.letThreadOp(opHit.asInstanceOf[Value], x => continue(iAtOp, eAtOp, x)))
      case LetRec(lams, body) =>
        val acs = lams.map {
          case Lam(ar, b) =>
            val handlerRoot = HandlerDispatchShape.isRoot(ar, b)
            (ar, compile(b, handlerRoot), handlerRoot)
          case _ => sys.error("letrec binding must be a lam")
        }
        val bc = compile(body)
        (env: Env) =>
          val cs = acs.map { case (ar, code, handlerRoot) =>
            if handlerRoot then Runtime.handlerClosure(Array.empty[Value], ar, code)
            else ClosV(Array.empty[Value], ar, code)
          }
          val envP = Runtime.extend(env, cs.toArray)                 // last binding = Local(0)
          cs.foreach(_.env = envP)                                   // tie the cyclic frame
          bc(envP)                                                   // body: tail
      case Ctor(tag, fields) =>
        // Signal/ComputedSignal → mutable cell (ForeignV(Array)) so .get/.set work in-place
        if tag == "Signal" || tag == "ComputedSignal" then
          val initCode: Code = if fields.isEmpty then (_ => Done(UnitV)) else compile(fields.head)
          return (env: Env) =>
            val initial = Runtime.value(initCode, env)
            def build(value: Value): Step = V2PluginRegistry.lookupGlobal(tag) match
              case Some(provider: ClosV) => Call(provider, Array[Value](value))
              case _ => Done(ForeignV(Array[Value](value)))

            if Runtime.isAutoThreadOp(initial) then
              Done(Runtime.letThreadOp(
                initial,
                resumed => Runtime.completeStep(build(resumed)),
              ))
            else build(initial)
        if fields.exists(mayProduceAutoThreadOp) then
          return compileEffectAwareConstructor(tag, fields)
        val fcs = fields.map(compile)
        fcs.length match
          case 0 =>
            val v = DataV(tag, IndexedSeq.empty); (_: Env) => Done(v)
          case 1 =>
            val fc0 = fcs(0)
            (env: Env) =>
              val a = new Array[Value](1); a(0) = Runtime.value(fc0, env)
              Done(DataV(tag, collection.immutable.ArraySeq.unsafeWrapArray(a)))
          case 2 =>
            val fc0 = fcs(0); val fc1 = fcs(1)
            (env: Env) =>
              val a = new Array[Value](2); a(0) = Runtime.value(fc0, env); a(1) = Runtime.value(fc1, env)
              Done(DataV(tag, collection.immutable.ArraySeq.unsafeWrapArray(a)))
          case 3 =>
            val fc0 = fcs(0); val fc1 = fcs(1); val fc2 = fcs(2)
            (env: Env) =>
              val a = new Array[Value](3); a(0) = Runtime.value(fc0, env); a(1) = Runtime.value(fc1, env); a(2) = Runtime.value(fc2, env)
              Done(DataV(tag, collection.immutable.ArraySeq.unsafeWrapArray(a)))
          case n =>
            val fcsArr = fcs.toArray
            (env: Env) =>
              val a = new Array[Value](n); var k = 0; while k < n do { a(k) = Runtime.value(fcsArr(k), env); k += 1 }
              Done(DataV(tag, collection.immutable.ArraySeq.unsafeWrapArray(a)))
      case Match(scrut, arms, default) =>
        val sc = compile(scrut)
        val acs = arms.map(a => (a.tag, a.arity, compile(a.body)))
        val armMap = acs.map { case (t, ar, b) => (t, ar) -> b }.toMap
        val dc = default.map(compile)
        (env: Env) =>
          val scrutineeValue = Runtime.value(sc, env)               // scrutinee: non-tail
          val handlerDispatch =
            handlerDispatchRoot && Runtime.handlerMatchEnter(scrutineeValue)
          scrutineeValue match {
          // EXPRESSION-position effects: an un-handled Op SCRUTINEE lifts over
          // the match — run the handler first, then match the resumed value
          // (same family as the arith/method/setter lifts).
          case opv @ DataV("Op", IndexedSeq(l, a, k))
              if !handlerDispatch && Runtime.isAutoThreadOp(opv) =>
            val kc = k.asInstanceOf[ClosV]
            val k2 = ClosV(Runtime.emptyEnv, 1, env2 => {
              val resumed = Runtime.run(kc.code, if kc.env.isEmpty then Array(env2.last) else Runtime.extend(kc.env, Array(env2.last)))
              resumed match
                case DataV(tag2, fs2) if armMap.contains((tag2, fs2.length)) =>
                  val extEnv2 = Runtime.extend(env, fs2.toArray)
                  armMap((tag2, fs2.length))(extEnv2)
                case other => dc match
                  case Some(d) => d(env)
                  case None => Done(sys.error(s"match: no arm for ${Show.show(other)}"))
            })
            Done(DataV("Op", Vector(l, a, k2)))
          case DataV(tag, fs) =>
            armMap.get((tag, fs.length)) match
              case Some(body) =>
                Runtime.handlerMatchSelected(handlerDispatch)
                // Avoid fs.toArray (Vector→Array alloc) for the common 0/1/2-field cases
                val extEnv = fs.length match
                  case 0 => env
                  case 1 => Runtime.appendOne(env, fs(0))
                  case 2 => Runtime.extend(env, Array(fs(0), fs(1)))
                  case _ => Runtime.extend(env, fs.toArray)
                body(extEnv)
              case None => dc match
                case Some(d) =>
                  Runtime.handlerMatchSelected(handlerDispatch)
                  d(env)
                case None => Done(Runtime.handlerMatchFailed(handlerDispatch, tag, fs.length))
          case other => dc match  // non-ADT scrutinee (String, Int, etc.) — fall to default
            case Some(d) =>
              Runtime.handlerMatchSelected(handlerDispatch)
              d(env)
            case None =>
              if handlerDispatch then
                Done(Runtime.handlerMatchFailed(true, Show.show(other), -1))
              else sys.error(s"match: scrutinee not Data: ${Show.show(other)}")
          }
      // Plugin functions are registered as Prim op-handlers (V2PluginRegistry.handlers);
      // the scalameta bridge lowers `f(x)` to Prim("f",[x]), but the self-hosted native
      // front emits App(Global("f"),[x]) → lookupGlobal miss → "unbound global: f". Redirect
      // to the IDENTICAL Prim path for names that are op-handlers and neither a user def nor
      // a registered global value. Safe: same dispatch the bridge uses (0-mismatch corpus);
      // effect runners (runAsync/serve/actors) are block-form, not handlers, so untouched.
      case App(Global(g), args)
          if !globals.contains(g) && !V2PluginRegistry.hasGlobal(g)
             && V2PluginRegistry.lookup(g).isDefined =>
        compile(Prim(g, args))
      case App(fn, args)
          if !isRawHandleStage(fn, args) &&
            (mayProduceAutoThreadOp(fn) || args.exists(mayProduceAutoThreadOp)) =>
        compileEffectAwareApplication(fn, args)
      case App(fn, args) =>
        // Global-call FC fast path: skip Done/run for the function lookup.
        // Uses tryFC for args (not tryFLC) so FloatV/StrV args pass through unchanged.
        // Uses lazy globals lookup to handle self-recursion (global not set during body compilation).
        val globalFastPath: Option[Code] = fn match
          case Global(g) if args.nonEmpty =>
            args match
              case List(a0) =>
                FastCode.tryFC(a0, globals).map { f0 =>
                  (env: Env) =>
                    val avs = new Array[Value](1); avs(0) = f0(env)
                    globals.getOrElse(g, V2PluginRegistry.lookupGlobal(g).getOrElse(sys.error(s"unbound global: $g"))) match
                      case c: ClosV => Call(c, avs)
                      case lv @ (DataV("Cons", _) | DataV("Nil", _)) =>
                        avs(0) match { case IntV(i) => Done(Prims.unlistPub(lv)(i.toInt)); case _ => sys.error("app: list index must be Int") }
                      case MapV(m) => Done(m(avs(0)))
                      case ForeignV(m: collection.mutable.Map[?, ?]) =>
                        Done(m.asInstanceOf[collection.mutable.Map[Value,Value]](avs(0)))
                      case DataV("Stub", fs) => Done(DataV("Stub", fs))  // propagate the missed-method breadcrumb
                      case v => Runtime.applyFallback(v, avs)
                }
              case List(a0, a1) =>
                FastCode.tryFC(a0, globals).flatMap { f0 =>
                  FastCode.tryFC(a1, globals).map { f1 =>
                    (env: Env) =>
                      val avs = new Array[Value](2); avs(0) = f0(env); avs(1) = f1(env)
                      globals.getOrElse(g, V2PluginRegistry.lookupGlobal(g).getOrElse(sys.error(s"unbound global: $g"))) match
                        case c: ClosV => Call(c, avs)
                        case v => Runtime.applyFallback(v, avs)
                  }
                }
              case _ => None
          case _ => None
        globalFastPath.getOrElse {
        if args.isEmpty then
          val fc = compile(fn)
          (env: Env) =>
            Runtime.value(fc, env) match
              case c: ClosV => Call(c, Runtime.emptyEnv)             // avoid toArray on empty list
              case v => Runtime.applyFallback(v, Runtime.emptyEnv)
        else args match
          // 1-arg fast path: avoid List alloc from acs.map(...).toArray
          case List(a0) =>
            val fc = compile(fn); val ac0 = compile(a0)
            (env: Env) =>
              val fv = Runtime.value(fc, env); val v0 = Runtime.value(ac0, env)
              fv match
                case c: ClosV =>
                  val avs = new Array[Value](1); avs(0) = v0; Call(c, avs)
                case lv @ (DataV("Cons", _) | DataV("Nil", _)) =>
                  v0 match { case IntV(i) => Done(Prims.unlistPub(lv)(i.toInt)); case _ => sys.error("app: list index must be Int") }
                case ForeignV(ab: collection.mutable.ArrayBuffer[?]) =>
                  v0 match { case IntV(i) => Done(ab.asInstanceOf[collection.mutable.ArrayBuffer[Value]](i.toInt)); case _ => sys.error("app: array index must be Int") }
                case MapV(m) => Done(m(v0))
                case ForeignV(m: collection.mutable.Map[?, ?]) =>
                  Done(m.asInstanceOf[collection.mutable.Map[Value, Value]](v0))
                case DataV("Stub", _) => Done(DataV("Stub", Vector.empty))
                case opv @ DataV("Op", _) =>
                  val avs1 = new Array[Value](1); avs1(0) = v0; Runtime.applyFallback(opv, avs1)
                case DataV(_, fields) =>
                  v0 match { case IntV(i) => Done(fields(i.toInt)); case _ => sys.error("app: DataV index must be Int") }
                case v => val avs = new Array[Value](1); avs(0) = v0; Runtime.applyFallback(v, avs)
          // 2-arg fast path: avoid List alloc from acs.map(...).toArray
          case List(a0, a1) =>
            val fc = compile(fn); val ac0 = compile(a0); val ac1 = compile(a1)
            (env: Env) =>
              val fv = Runtime.value(fc, env)
              val avs = new Array[Value](2); avs(0) = Runtime.value(ac0, env); avs(1) = Runtime.value(ac1, env)
              fv match
                case c: ClosV => Call(c, avs)
                case DataV("Stub", fs) => Done(DataV("Stub", fs))
                case v => Runtime.applyFallback(v, avs)
          // generic path for 3+ args
          case _ =>
            val fc = compile(fn); val acs = args.map(compile)
            (env: Env) =>
              val fv  = Runtime.value(fc, env)
              val avs = acs.map(ac => Runtime.value(ac, env)).toArray
              fv match
                case c: ClosV => Call(c, avs)
                case DataV("Stub", fs) => Done(DataV("Stub", fs))
                case v => Runtime.applyFallback(v, avs)
        }   // end globalFastPath.getOrElse
      case While(cond, body)
          if mayProduceAutoThreadOp(cond) || mayProduceAutoThreadOp(body) =>
        compileEffectAwareWhile(cond, body)
      case While(cond, body) =>
        // Try FastCode path: avoids Done boxing and trampoline per iteration
        (FastCode.tryFBc(cond, globals), FastCode.tryFC(body, globals)) match
          case (Some(fbc), Some(fb)) =>
            (env: Env) => { while fbc(env) do fb(env); Done(Value.UnitV) }
          case _ =>
            // Mixed: use tryFBc for condition even if body is slow (saves BoolV alloc per check)
            val fbcOpt = FastCode.tryFBc(cond, globals)
            val bc = compile(body)
            fbcOpt match
              case Some(fbc) =>
                (env: Env) =>
                  while fbc(env) do Runtime.value(bc, env)
                  Done(Value.UnitV)
              case None =>
                val cc = compile(cond)
                (env: Env) =>
                  while (Runtime.value(cc, env) match { case Value.BoolV(b) => b; case _ => false }) do
                    Runtime.value(bc, env)
                  Done(Value.UnitV)                                   // no trampoline bounce per iteration
      case Seq(terms) =>
        // Try FastCode path: prefer FLC-based long-set for arithmetic, then general FC
        val fastOpts = terms.map(t => FastCode.tryFCLongSet(t, globals).orElse(FastCode.tryFC(t, globals)))
        if fastOpts.forall(_.isDefined) then
          // Use Array + while instead of for-over-List: avoids ObjectRef alloc for the
          // `var last` capture that Scala generates when a mutable var escapes into a foreach lambda.
          val fcsArr = fastOpts.map(_.get).toArray
          val nFcs   = fcsArr.length
          (env: Env) =>
            def go(k: Int): Value =
              val v = fcsArr(k)(env)
              if k == nFcs - 1 then v
              else v match
                case opv @ Value.DataV("Op", _) if Runtime.isAutoThreadOp(opv) => Runtime.seqThreadOp(opv, () => go(k + 1))
                case _ => go(k + 1)
            Done(go(0))
        else
          val tcsArr = terms.map(compile).toArray
          val nTcs   = tcsArr.length
          (env: Env) =>
            def go(k: Int): Value =
              val v = Runtime.value(tcsArr(k), env)
              if k == nTcs - 1 then v
              else v match
                case opv @ Value.DataV("Op", _) if Runtime.isAutoThreadOp(opv) => Runtime.seqThreadOp(opv, () => go(k + 1))
                case _ => go(k + 1)
            Done(go(0))
      case Prim(op, args) =>
        val threadArguments = !isEffectPrim(op)
        inline def shouldThread(value: Value): Boolean =
          threadArguments && Runtime.isAutoThreadOp(value)

        // Fast paths for 1/2/3-arg primitives: avoid List[Value] allocation for args
        args match
          case List(a0) =>
            Prims.resolve1(op) match
              case Some(fn1) =>
                val ac0 = compile(a0)
                (env: Env) =>
                  val value0 = Runtime.value(ac0, env)
                  if shouldThread(value0) then Done(Runtime.letThreadOp(value0, fn1))
                  else Done(fn1(value0))
              case None =>
                val fn = Prims.resolve(op); val ac0 = compile(a0)
                (env: Env) =>
                  val value0 = Runtime.value(ac0, env)
                  if shouldThread(value0) then
                    Done(Runtime.letThreadOp(value0, resumed0 => fn(List(resumed0))))
                  else Done(fn(List(value0)))
          case List(a0, a1) =>
            Prims.resolve2(op) match
              case Some(fn2) =>
                val ac0 = compile(a0); val ac1 = compile(a1)
                (env: Env) =>
                  def evaluateSecond(value0: Value): Value =
                    val value1 = Runtime.value(ac1, env)
                    if shouldThread(value1) then
                      Runtime.letThreadOp(value1, resumed1 => fn2(value0, resumed1))
                    else fn2(value0, value1)

                  val value0 = Runtime.value(ac0, env)
                  if shouldThread(value0) then
                    Done(Runtime.letThreadOp(value0, evaluateSecond))
                  else Done(evaluateSecond(value0))
              case None =>
                val fn = Prims.resolve(op); val ac0 = compile(a0); val ac1 = compile(a1)
                (env: Env) =>
                  def evaluateSecond(value0: Value): Value =
                    val value1 = Runtime.value(ac1, env)
                    if shouldThread(value1) then
                      Runtime.letThreadOp(value1, resumed1 => fn(List(value0, resumed1)))
                    else fn(List(value0, value1))

                  val value0 = Runtime.value(ac0, env)
                  if shouldThread(value0) then
                    Done(Runtime.letThreadOp(value0, evaluateSecond))
                  else Done(evaluateSecond(value0))
          case List(a0, a1, a2) =>
            Prims.resolve3(op) match
              case Some(fn3) =>
                val ac0 = compile(a0); val ac1 = compile(a1); val ac2 = compile(a2)
                (env: Env) =>
                  def evaluateThird(value0: Value, value1: Value): Value =
                    val value2 = Runtime.value(ac2, env)
                    if shouldThread(value2) then
                      Runtime.letThreadOp(value2, resumed2 => fn3(value0, value1, resumed2))
                    else fn3(value0, value1, value2)

                  def evaluateSecond(value0: Value): Value =
                    val value1 = Runtime.value(ac1, env)
                    if shouldThread(value1) then
                      Runtime.letThreadOp(value1, resumed1 => evaluateThird(value0, resumed1))
                    else evaluateThird(value0, value1)

                  val value0 = Runtime.value(ac0, env)
                  if shouldThread(value0) then
                    Done(Runtime.letThreadOp(value0, evaluateSecond))
                  else Done(evaluateSecond(value0))
              case None =>
                // Special fast path: __arith__ with literal op — avoid List alloc on every call
                if op == "__arith__" then a0 match
                  case Lit(Const.CStr(fixedOp)) =>
                    val ac1 = compile(a1); val ac2 = compile(a2)
                    (env: Env) =>
                      def evaluateRight(value1: Value): Value =
                        val value2 = Runtime.value(ac2, env)
                        if shouldThread(value2) then
                          Runtime.letThreadOp(
                            value2,
                            resumed2 => Prims.arithFast(fixedOp, value1, resumed2),
                          )
                        else Prims.arithFast(fixedOp, value1, value2)

                      val value1 = Runtime.value(ac1, env)
                      if shouldThread(value1) then
                        Done(Runtime.letThreadOp(value1, evaluateRight))
                      else Done(evaluateRight(value1))
                  case _ =>
                    compileGenericPrimitive(op, args, threadArguments)
                else
                  compileGenericPrimitive(op, args, threadArguments)
          case _ =>                                                    // 0 or 4+ args: generic path
            compileGenericPrimitive(op, args, threadArguments)

    private def compileGenericPrimitive(
        op: String,
        args: List[Term],
        threadArguments: Boolean,
    ): Code =
      val fn = Prims.resolve(op)
      val argumentCodes = args.map(compile).toArray
      val argumentCount = argumentCodes.length
      (env: Env) =>
        def evaluate(index: Int, reversed: List[Value]): Value =
          if index == argumentCount then fn(reversed.reverse)
          else
            val value = Runtime.value(argumentCodes(index), env)
            if threadArguments && Runtime.isAutoThreadOp(value) then
              Runtime.letThreadOp(
                value,
                resumed => evaluate(index + 1, resumed :: reversed),
              )
            else evaluate(index + 1, value :: reversed)

        Done(evaluate(0, Nil))

    private def compileEffectAwareConstructor(tag: String, fields: List[Term]): Code =
      val fieldCodes = fields.map(compile).toArray
      val fieldCount = fieldCodes.length
      (env: Env) =>
        def evaluate(index: Int, reversed: List[Value]): Value =
          if index == fieldCount then
            val values = reversed.reverse.toArray
            DataV(tag, collection.immutable.ArraySeq.unsafeWrapArray(values))
          else
            val value = Runtime.value(fieldCodes(index), env)
            if Runtime.isAutoThreadOp(value) then
              Runtime.letThreadOp(
                value,
                resumed => evaluate(index + 1, resumed :: reversed),
              )
            else evaluate(index + 1, value :: reversed)

        Done(evaluate(0, Nil))

    private def compileEffectAwareApplication(function: Term, args: List[Term]): Code =
      val functionCode = compile(function)
      val argumentCodes = args.map(compile).toArray
      val argumentCount = argumentCodes.length

      def applyStep(functionValue: Value, arguments: Array[Value]): Step = functionValue match
        case closure: ClosV => Call(closure, arguments)
        case other          => Runtime.applyFallback(other, arguments)

      (env: Env) =>
        def evaluateRemainingAsValue(
            functionValue: Value,
            index: Int,
            reversed: List[Value],
        ): Value =
          if index == argumentCount then
            Runtime.completeStep(applyStep(functionValue, reversed.reverse.toArray))
          else
            val value = Runtime.value(argumentCodes(index), env)
            if Runtime.isAutoThreadOp(value) then
              Runtime.letThreadOp(
                value,
                resumed => evaluateRemainingAsValue(
                  functionValue,
                  index + 1,
                  resumed :: reversed,
                ),
              )
            else evaluateRemainingAsValue(functionValue, index + 1, value :: reversed)

        def evaluateRemainingAsStep(
            functionValue: Value,
            index: Int,
            reversed: List[Value],
        ): Step =
          if index == argumentCount then
            applyStep(functionValue, reversed.reverse.toArray)
          else
            val value = Runtime.value(argumentCodes(index), env)
            if Runtime.isAutoThreadOp(value) then
              Done(Runtime.letThreadOp(
                value,
                resumed => evaluateRemainingAsValue(
                  functionValue,
                  index + 1,
                  resumed :: reversed,
                ),
              ))
            else evaluateRemainingAsStep(functionValue, index + 1, value :: reversed)

        val functionValue = Runtime.value(functionCode, env)
        if Runtime.isAutoThreadOp(functionValue) then
          Done(Runtime.letThreadOp(
            functionValue,
            resolved => evaluateRemainingAsValue(resolved, 0, Nil),
          ))
        else evaluateRemainingAsStep(functionValue, 0, Nil)

    private def compileEffectAwareWhile(cond: Term, body: Term): Code =
      val conditionCode = compile(cond)
      val bodyCode = compile(body)
      (env: Env) =>
        def afterCondition(value: Value): Value = value match
          case BoolV(false) => UnitV
          case BoolV(true) =>
            val bodyValue = Runtime.value(bodyCode, env)
            if Runtime.isAutoThreadOp(bodyValue) then
              Runtime.seqThreadOp(bodyValue, () => loop())
            else loop()
          case other => sys.error(s"while: condition not Bool: ${Show.show(other)}")

        def loop(): Value =
          while true do
            val conditionValue = Runtime.value(conditionCode, env)
            if Runtime.isAutoThreadOp(conditionValue) then
              return Runtime.letThreadOp(conditionValue, afterCondition)
            conditionValue match
              case BoolV(false) => return UnitV
              case BoolV(true) =>
                val bodyValue = Runtime.value(bodyCode, env)
                if Runtime.isAutoThreadOp(bodyValue) then
                  return Runtime.seqThreadOp(bodyValue, () => loop())
              case other =>
                sys.error(s"while: condition not Bool: ${Show.show(other)}")
          UnitV

        Done(loop())

  def constV(c: Const): Value = c match
    case Const.CUnit     => Value.UnitV
    case Const.CBool(b)  => Value.BoolV(b)
    case Const.CInt(n)   => Value.IntV(n)
    case Const.CBig(n)   => Value.BigV(n)
    case Const.CFloat(d) => Value.FloatV(d)
    case Const.CStr(s)   => Value.StrV(s)
    case Const.CBytes(b) => Value.BytesV(b)

// ── FastCode: Value-returning closures (no Done boxing) ──────────────────────
// Used in While/If fast-paths to avoid 20+ Done allocations per iteration.
// Only covers expressions that are provably non-tail (primitives, locals, lits, seq).
object FastCode:
  import Value.*, Term.*

  type FC  = Env => Value         // fast: returns Value directly (no Done wrapping)
  type FLC = Env => Long          // fast long: returns unboxed Long (avoids IntV boxing)
  type FBc = Env => Boolean       // fast bool: avoids BoolV boxing for conditions
  type FCA = (Env, Value) => Value // fast with one synthetic appended Local(0)

  // Explicit Seq FC class — all Seq FCs share ONE class, so the `fb(env)` call site
  // in the fast While loop stays monomorphic regardless of Seq length.
  // `var last` in apply() is a plain JVM local — no ObjectRef boxing.
  final class SeqFastCode(private val fcs: Array[FC], private val n: Int) extends (Env => Value):
    def apply(env: Env): Value =
      def go(i: Int): Value =
        val v = fcs(i)(env)
        if i == n - 1 then v
        else v match
          case opv @ Value.DataV("Op", _) if Runtime.isAutoThreadOp(opv) => Runtime.seqThreadOp(opv, () => go(i + 1))
          case _ => go(i + 1)
      go(0)

  /** True if t (a match arm body) only references Local(k) for k < arity.
   *  When true, the arm can be called with a compact env of length = arity,
   *  avoiding the appendOne/extend that would prepend the base (scrutinee) env. */
  private def armBodyCompact(t: Term, arity: Int): Boolean = t match
    case Local(k)       => k < arity
    case Lit(_) | Global(_) => true
    case Prim(_, args)  => args.forall(armBodyCompact(_, arity))
    case Seq(terms)     => terms.forall(armBodyCompact(_, arity))
    case If(c, th, el)  => armBodyCompact(c, arity) && armBodyCompact(th, arity) && armBodyCompact(el, arity)
    case _              => false  // conservative: complex terms may reference outer locals

  /** Stricter than armBodyCompact: true only for pure, arithmetic-ish arm bodies
   *  whose compact env cannot be captured or handed to user/plugin code.  Those
   *  arms may reuse a tiny scratch env in the Match FC instead of allocating
   *  Array(fs...) on every dispatch. */
  private def armBodyScratchSafe(t: Term, arity: Int): Boolean = t match
    case Local(k) => k < arity
    case Lit(_)   => true
    case Prim("__arith__", args) => args.forall(armBodyScratchSafe(_, arity))
    case Prim(op, args) if op.startsWith("i.") || op.startsWith("f.") =>
      args.forall(armBodyScratchSafe(_, arity))
    case Seq(terms) => terms.forall(armBodyScratchSafe(_, arity))
    case If(c, th, el) =>
      armBodyScratchSafe(c, arity) && armBodyScratchSafe(th, arity) && armBodyScratchSafe(el, arity)
    case _ => false

  /** Compile a Lam(1) body against a virtual env = appendOne(baseEnv, appended),
   *  without materialising that env.  Local(0) reads the appended value; Local(k>0)
   *  reads baseEnv's Local(k-1).  Complex binders are intentionally rejected so a
   *  synthetic env can never be captured by a nested closure. */
  private def tryFCAppended(t: Term, globals: collection.mutable.Map[String, Value]): Option[FCA] = t match
    case term if Compiler.valuePositionsNeedEffectThreading(term) => None
    case Lit(k) =>
      val v = Compiler.constV(k); Some((_, _) => v)
    case Local(0) =>
      Some((_, appended) => appended)
    case Local(i) if i > 0 =>
      val n = i; Some((env, _) => env(env.length - n))
    case Global(g) =>
      val gn = g
      globals.get(gn) match
        case Some(v) => Some((_, _) => v)
        case None    => Some((_, _) => globals.getOrElse(gn, V2PluginRegistry.lookupGlobal(gn).getOrElse(
          if gn.startsWith("@") then { val cell = ForeignV(Array[Value](UnitV)); globals(gn) = cell; cell }
          else sys.error(s"unbound global: $gn"))))
    case Prim("cell.get", List(arg)) =>
      tryFCAppended(arg, globals).map { fca => (env, appended) =>
        fca(env, appended).asInstanceOf[ForeignV].h.asInstanceOf[Array[Value]](0)
      }
    case Prim("lcell.get", List(arg)) =>
      tryFCAppended(arg, globals).map { fca => (env, appended) =>
        IntV(fca(env, appended).asInstanceOf[LongCellV].v)
      }
    case Prim("cell.set", List(Local(c), body)) if c > 0 =>
      tryFCAppended(body, globals).map { fcb =>
        val cn = c
        (env, appended) =>
          val cell = env(env.length - cn).asInstanceOf[ForeignV].h.asInstanceOf[Array[Value]]
          cell(0) = fcb(env, appended)
          UnitV
      }
    case Prim("lcell.set", List(Local(c), body)) if c > 0 =>
      tryFCAppended(body, globals).map { fcb =>
        val cn = c
        (env, appended) =>
          env(env.length - cn).asInstanceOf[LongCellV].v = fcb(env, appended) match
            case IntV(x) => x
            case v       => sys.error(s"expected Int, got ${Show.show(v)}")
          UnitV
      }
    case Prim("__arith__", List(Lit(Const.CStr(op)), a0, a1)) =>
      tryFCAppended(a0, globals).flatMap { fc0 =>
        tryFCAppended(a1, globals).map { fc1 =>
          (env, appended) => Prims.arithFast(op, fc0(env, appended), fc1(env, appended)): Value
        }
      }
    case App(Global(g), appArgs) =>
      val argOpts = appArgs.map(tryFCAppended(_, globals))
      if argOpts.forall(_.isDefined) then
        val fca = argOpts.map(_.get).toArray
        val gn  = g
        globals.get(gn).collect { case closV: ClosV if closV.fcEntry.isDefined && closV.env.isEmpty =>
          val bodyFC = closV.fcEntry.get
          val sharedArgEnv = new Array[Value](fca.length)
          (env: Env, appended: Value) =>
            var i = 0
            while i < fca.length do { sharedArgEnv(i) = fca(i)(env, appended); i += 1 }
            bodyFC(sharedArgEnv)
        }.orElse(Some((env: Env, appended: Value) =>
          globals.getOrElse(gn, V2PluginRegistry.lookupGlobal(gn).getOrElse(sys.error(s"tryFCAppended App: unbound: $gn"))) match
            case closV: ClosV =>
              val argEnv =
                if closV.env.isEmpty then
                  val a = new Array[Value](fca.length)
                  var i = 0; while i < fca.length do { a(i) = fca(i)(env, appended); i += 1 }
                  a
                else
                  val a = new Array[Value](fca.length)
                  var i = 0; while i < fca.length do { a(i) = fca(i)(env, appended); i += 1 }
                  Runtime.extend(closV.env, a)
              closV.fcEntry match
                case Some(bodyFC) => bodyFC(argEnv)
                case None         => Runtime.run(closV.code, argEnv)
            case _ => sys.error("tryFCAppended App: not a function")
        ))
      else None
    case Prim(op, List(a0)) =>
      Prims.resolve1(op).flatMap { fn1 =>
        tryFCAppended(a0, globals).map { fc0 => (env, appended) => fn1(fc0(env, appended)) }
      }
    case Prim(op, List(a0, a1)) =>
      Prims.resolve2(op).flatMap { fn2 =>
        tryFCAppended(a0, globals).flatMap { fc0 =>
          tryFCAppended(a1, globals).map { fc1 =>
            (env, appended) => fn2(fc0(env, appended), fc1(env, appended))
          }
        }
      }
    case Prim(op, List(a0, a1, a2)) =>
      Prims.resolve3(op).flatMap { fn3 =>
        tryFCAppended(a0, globals).flatMap { fc0 =>
          tryFCAppended(a1, globals).flatMap { fc1 =>
            tryFCAppended(a2, globals).map { fc2 =>
              (env, appended) => fn3(fc0(env, appended), fc1(env, appended), fc2(env, appended))
            }
          }
        }
      }
    case _ => None

  /** Try to compile a term to a FastLongCode (Env => Long), eliminating IntV boxing.
   *  Covers Local lookups from LongCellV/IntV, arithmetic ops, and integer literals. */
  def tryFLC(t: Term, globals: collection.mutable.Map[String, Value]): Option[FLC] = t match
    case term if Compiler.valuePositionsNeedEffectThreading(term) => None
    case Lit(Const.CInt(n)) => Some(_ => n)
    case Local(i) =>
      // Optimistic: assume Local holds an IntV or LongCellV (function params, let-bindings)
      val n = i; Some((env: Env) => env(env.length - 1 - n) match
        case IntV(x) => x
        case lc: LongCellV => lc.v
        case _ => 0L)
    case Prim("lcell.get", List(Local(i))) =>
      val n = i; Some(env => env(env.length - 1 - n).asInstanceOf[LongCellV].v)
    case Prim("cell.get", List(Local(i))) =>
      // Optimistic: read IntV directly from foreign cell (works when cell holds IntV)
      val n = i; Some((env: Env) =>
        val cell = env(env.length - 1 - n).asInstanceOf[ForeignV].h.asInstanceOf[Array[Value]]
        cell(0) match { case IntV(x) => x; case _ => 0L })
    // arr.get — optimistic: array element is an Int; used in tight loops (e.g. array-update)
    case Prim("arr.get", List(a0, a1)) =>
      tryFC(a0, globals).flatMap { fca => tryFLC(a1, globals).map { fci =>
        (env: Env) => fca(env) match
          case ForeignV(ab: collection.mutable.ArrayBuffer[?]) =>
            ab.asInstanceOf[collection.mutable.ArrayBuffer[Value]](fci(env).toInt) match
              case IntV(x) => x; case _ => 0L
          case _ => 0L
      } }
    // fieldAt with literal index — optimistic: field is an Int (DataV with Int fields)
    case Prim("fieldAt", List(recv, Lit(Const.CInt(k)))) =>
      tryFC(recv, globals).map { fcr => (env: Env) =>
        fcr(env) match
          case DataV("Stub", _) => 0L  // stub: field of stub is 0
          case DataV(_, fields) => fields(k.toInt) match { case IntV(x) => x; case _ => 0L }
          case _ => 0L
      }
    // App(Global) — optimistic: returns 0L for non-Int-returning functions (Float→0L).
    // Safe for lcell.set callers (normSq, Int-valued globals). NOT used for cell.set bodies.
    // Uses fcEntry if available to skip the trampoline (one fewer Done alloc per call).
    // The per-call fca.map alloc is stack-allocated by JVM escape analysis (fresh, short-lived).
    case App(Global(name), args) =>
      val argOpts = args.map(tryFC(_, globals))
      if argOpts.forall(_.isDefined) then
        val fca = argOpts.map(_.get).toArray
        Some((env: Env) =>
          val fn = globals.getOrElse(name, V2PluginRegistry.lookupGlobal(name).getOrElse(sys.error(s"tryFLC App: unbound global: $name")))
          fn match
            case c: ClosV =>
              val argEnv = if c.env.isEmpty then fca.map(f => f(env): Value) else c.env ++ fca.map(f => f(env): Value)
              c.fcEntry match
                case Some(bodyFC) => bodyFC(argEnv) match { case IntV(x) => x; case _ => 0L }
                case None => Runtime.run(c.code, argEnv) match { case IntV(x) => x; case _ => 0L }
            case _ => 0L
        )
      else None
    case Prim("i.add", List(a0, a1)) =>
      tryFLC(a0, globals).flatMap(f0 => tryFLC(a1, globals).map(f1 => env => f0(env) + f1(env)))
    case Prim("i.sub", List(a0, a1)) =>
      tryFLC(a0, globals).flatMap(f0 => tryFLC(a1, globals).map(f1 => env => f0(env) - f1(env)))
    case Prim("i.mul", List(a0, a1)) =>
      tryFLC(a0, globals).flatMap(f0 => tryFLC(a1, globals).map(f1 => env => f0(env) * f1(env)))
    // __arith__ with literal op string — bridge-generated arithmetic (same as i.xxx but dynamic op)
    case Prim("__arith__", List(Lit(Const.CStr(op)), a0, a1))
        if op == "+" || op == "-" || op == "*" || op == "%" || op == "/" =>
      tryFLC(a0, globals).flatMap { f0 => tryFLC(a1, globals).map { f1 =>
        val fn: (Long, Long) => Long = op match
          case "+" => _ + _; case "-" => _ - _; case "*" => _ * _
          case "%" => _ % _; case "/" => _ / _; case _   => (_, _) => 0L
        (env: Env) => fn(f0(env), f1(env))
      } }
    // __method__("toInt"/"toLong", recv) — common Int conversions, fast-path via FLC (returns Long)
    case Prim("__method__", List(Lit(Const.CStr("toInt")), recv)) =>
      tryFLC(recv, globals).map { fr => env => fr(env).toInt.toLong }
    case Prim("__method__", List(Lit(Const.CStr("toLong")), recv)) =>
      tryFLC(recv, globals).map { fr => env => fr(env) }
    // App(Global).length/size — resolves a global function call and measures the returned
    // string/collection length in a single FLC step.  Specific pattern avoids adding a
    // general App(Global) case to tryFC (which causes JVM JIT interference on other benches).
    case Prim("__method__", List(Lit(Const.CStr(n)), App(Global(fname), fargs)))
        if n == "length" || n == "size" =>
      val argOpts = fargs.map(tryFC(_, globals))
      if argOpts.forall(_.isDefined) then
        val fca = argOpts.map(_.get).toArray
        Some((env: Env) =>
          globals.getOrElse(fname, sys.error(s"FLC AppLen: unbound: $fname")) match
            case c: ClosV =>
              val argEnv = c.env ++ fca.map(f => f(env): Value)
              Runtime.run(c.code, argEnv) match
                case StrV(s)        => s.length.toLong
                case DataV(_, fs)   => fs.length.toLong
                case _              => 0L
            case _ => 0L
        )
      else None
    // __method__("length"/"size", recv) — string/collection length for local/lit receivers.
    // HONEST variants only: Cons/Nil walk the real list (fs.length on a Cons cell
    // is always 2), ArrayBuffer reports its size, and unknown receivers ERROR
    // instead of the old tolerant `0L` — that silent zero masked the args-global
    // native shadowing and emptied every `while i < msg.length` loop over an
    // Array.fill result (busi qr: a data-less, mask-only QR matrix).
    case Prim("__method__", List(Lit(Const.CStr(n)), recv)) if n == "length" || n == "size" =>
      tryFC(recv, globals).map { fcr => (env: Env) =>
        fcr(env) match
          case StrV(s)                        => s.length.toLong
          case lv @ DataV("Cons" | "Nil", _)  => Prims.unlistPub(lv).length.toLong
          case DataV(_, fs)                   => fs.length.toLong
          case ForeignV(ab: collection.mutable.ArrayBuffer[?]) => ab.length.toLong
          case v                              => sys.error(s"length/size on ${Show.show(v)}")
      }
    // __method__("_N", recv) — tuple field accessor; returns Long only if the field is Int/Long
    case Prim("__method__", List(Lit(Const.CStr(n)), recv)) if n.matches("_\\d+") =>
      val idx = n.drop(1).toInt - 1
      tryFC(recv, globals).map { fcr => (env: Env) =>
        fcr(env) match
          case DataV(_, fields) => fields(idx) match { case IntV(x) => x; case _ => 0L }
          case _ => 0L
      }
    case _ => None

  /** Is this term PROVABLY Long-valued under FLC compilation?  tryFLC contains
   *  OPTIMISTIC leaves (App(Global), cell.get, arr.get, fieldAt, Local) that coerce
   *  any non-Int value to 0L — fine for lcell.set (lcells hold Long by construction),
   *  but generic cell.set bodies routed through FLC would silently store IntV(0) over
   *  a Map/Data/Float value (the map-ops corruption, found 2026-07-05).  cell.set may
   *  only take the FLC fast path when this predicate holds. */
  def flcProvablyLong(t: Term): Boolean = t match
    case Lit(Const.CInt(_))                  => true
    case Prim("lcell.get", List(Local(_)))   => true
    case Prim("i.add" | "i.sub" | "i.mul" | "i.div" | "i.mod", List(a, b)) =>
      flcProvablyLong(a) && flcProvablyLong(b)
    case Prim("__arith__", List(Lit(Const.CStr(op)), a, b)) if op.length == 1 && "+-*/%".contains(op) =>
      flcProvablyLong(a) && flcProvablyLong(b)
    case Prim("__method__", List(Lit(Const.CStr("toInt" | "toLong")), recv)) => flcProvablyLong(recv)
    case Prim("__method__", List(Lit(Const.CStr("length" | "size")), _))     => true
    case _ => false

  /** Try to compile a cell.set to a FastCode that stores a raw Long (no IntV alloc).
   *  Only handles `lcell.set` (typed Long cell — always safe to use FLC).
   *  `cell.set` is intentionally excluded: it can hold FloatV, StrV, etc., so using
   *  tryFLC (which coerces Float→0L) would silently corrupt non-Int cells. */
  def tryFCLongSet(t: Term, globals: collection.mutable.Map[String, Value]): Option[FC] = t match
    case term if Compiler.valuePositionsNeedEffectThreading(term) => None
    case Prim("lcell.set", List(Local(c), body)) =>
      tryFLC(body, globals).map { flc =>
        val cn = c
        (env: Env) => { env(env.length - 1 - cn).asInstanceOf[LongCellV].v = flc(env); UnitV }
      }
    case _ => None

  /** Float-safe FC for arm bodies and cell.set values: for __arith__, uses arithOp directly
   *  (correct for Float operands) instead of the FLC-first shortcut (which coerces Float→0L). */
  def tryFCValue(t: Term, globals: collection.mutable.Map[String, Value]): Option[FC] = t match
    case term if Compiler.valuePositionsNeedEffectThreading(term) => None
    case Prim("__arith__", List(Lit(Const.CStr(op)), a0, a1)) =>
      tryFCValue(a0, globals).flatMap { fc0 => tryFCValue(a1, globals).map { fc1 =>
        (env: Env) => Prims.arithFast(op, fc0(env), fc1(env)): Value
      } }
    case _ => tryFC(t, globals)

  /** Try to compile a term to a FastCode.  Returns None if the term
   *  requires a tail call (Lam, App, LetRec, Match with complex arms). */
  def tryFC(t: Term, globals: collection.mutable.Map[String, Value]): Option[FC] = t match
    case term if Compiler.valuePositionsNeedEffectThreading(term) => None
    case Lit(k) =>
      val v = Compiler.constV(k); Some(_ => v)
    case Local(i) =>
      val n = i; Some(env => env(env.length - 1 - n))
    case Global(g) =>
      // If the global is already set at compile time (def, val, or previously evaluated top-level term),
      // capture it directly as a constant — avoids a HashMap lookup per call in hot paths like
      // `shapes.foreach(...)` where `shapes` is a top-level val (100k HashMap.get → 0).
      val gn = g
      globals.get(gn) match
        case Some(v) => Some(_ => v)
        case None    => Some(_ => globals.getOrElse(gn, V2PluginRegistry.lookupGlobal(gn).getOrElse(
          if gn.startsWith("@") then { val cell = ForeignV(Array[Value](UnitV)); globals(gn) = cell; cell }
          else sys.error(s"unbound global: $gn"))))
    // lcell.get: return IntV(c.v) but for FC callers who need a Value
    case Prim("lcell.get", List(Local(i))) =>
      val n = i; Some((env: Env) => IntV(env(env.length - 1 - n).asInstanceOf[LongCellV].v))
    // cell.get: return IntV from ForeignV cell
    case Prim("cell.get", List(Local(i))) =>
      val n = i; Some((env: Env) =>
        env(env.length - 1 - n).asInstanceOf[ForeignV].h.asInstanceOf[Array[Value]](0) match
          case v: IntV => v; case v => v)
    // lcell.set: delegate to tryFCLongSet (lcell always holds Long — safe)
    case Prim("lcell.set", _) =>
      tryFCLongSet(t, globals)
    // cell.set: Float-safe body evaluation.
    // NOT using tryFCLongSet: FLC coerces Float→0L (wrong for var x: Double cells).
    // For __arith__ bodies, go straight to arithOp (handles FloatV+FloatV → FloatV).
    // App args handled via a LOCAL resolveArg — NOT exposed to global tryFC — so the
    // resulting App-FC class never appears in SeqFastCode and causes no JIT call-site
    // pollution for unrelated benchmarks (instance-field, mutual-recursion).
    case Prim("cell.set", List(Local(c), body)) =>
      // resolveArg: Float-safe argument compilation for cell.set.
      // Handles App inline (not in global tryFC) to avoid JIT call-site pollution in SeqFastCode.
      // Uses fcEntry for App targets: skips trampoline, direct FC call.
      def resolveArg(t: Term): Option[FC] = t match
        case App(Global(g), appArgs) =>
          val appArgFCs = appArgs.map(tryFC(_, globals))
          if appArgFCs.forall(_.isDefined) then
            val fca = appArgFCs.map(_.get).toArray; val gn = g
            // Compile-time fast path: if the callee's fcEntry is already set (defs compiled in
            // pass 1 before any call-site), capture it and use a pre-allocated argEnv.
            // Safe: bodyFC is pure (no trampoline, no mutation of argEnv), runs synchronously.
            globals.get(g).collect { case closV: ClosV if closV.fcEntry.isDefined && closV.env.isEmpty =>
              val bodyFC = closV.fcEntry.get
              val sharedArgEnv = new Array[Value](fca.length)
              (env: Env) =>
                var i = 0; while i < fca.length do { sharedArgEnv(i) = fca(i)(env); i += 1 }
                bodyFC(sharedArgEnv)
            }.orElse(Some((env: Env) =>
              globals.getOrElse(gn, sys.error(s"cell.set: unbound: $gn")) match
                case closV: ClosV =>
                  val argEnv = if closV.env.isEmpty then fca.map(f => f(env): Value)
                               else closV.env ++ fca.map(f => f(env): Value)
                  closV.fcEntry match
                    case Some(bodyFC) => bodyFC(argEnv)
                    case None         => Runtime.run(closV.code, argEnv)
                case _ => sys.error("cell.set: not a function")
            ))
          else None
        case _ => tryFCValue(t, globals)
      // FLC fast path: if body is pure Int arithmetic, compute unboxed Long → single IntV.
      // Safe: tryFLC fails for Float/String expressions (returns None), so we only
      // reach this for Int-valued bodies that would store an IntV anyway.
      // Avoids the 2 intermediate IntV allocs that the arithOp chain produces.
      val flcBody: Option[FC] = (if flcProvablyLong(body) then tryFLC(body, globals) else None).map { flc =>
        val cn2 = c
        (env: Env) =>
          val cell = env(env.length - 1 - cn2).asInstanceOf[ForeignV].h.asInstanceOf[Array[Value]]
          cell(0) = IntV(flc(env)); UnitV
      }
      flcBody.orElse {
        val fcBodyOpt: Option[FC] = body match
          case Prim("__arith__", List(Lit(Const.CStr(op)), a0, a1)) =>
            resolveArg(a0).flatMap { fc0 =>
              resolveArg(a1).map { fc1 =>
                (env: Env) => Prims.arithFast(op, fc0(env), fc1(env)): Value
              }
            }
          case _ => tryFCValue(body, globals)
        fcBodyOpt.map { fcBody =>
          val cn = c
          (env: Env) =>
            val cell = env(env.length - 1 - cn).asInstanceOf[ForeignV].h.asInstanceOf[Array[Value]]
            cell(0) = fcBody(env)
            UnitV
        }
      }
    // __arith__ with literal op — try FLC (unboxed Long) first, wrap result in IntV.
    // GUARD with flcProvablyLong: tryFLC reads a Local optimistically as Long and returns 0L for a FloatV,
    // so an unguarded Double `/` compiled 0L/0L → ArithmeticException (dsl-ast-builder), and `+`/`*`/… gave
    // silently wrong Long results. Non-provably-Long operands fall through to the general, Double-aware arith.
    case Prim("__arith__", List(Lit(Const.CStr(op)), a0, a1)) =>
      val flcOpt: Option[FC] =
        if flcProvablyLong(t) then tryFLC(t, globals).map { flc => (env: Env) => IntV(flc(env)): Value }
        else None
      flcOpt orElse
        // Non-numeric ops (++, string concat, etc.) — fall through to general dispatch
        tryFC(a0, globals).flatMap { fc0 => tryFC(a1, globals).map { fc1 =>
          (env: Env) => Prims.arithFast(op, fc0(env), fc1(env)): Value
        } }
    // __method__("foreach", list, lambda) fast path: traverse Cons/Nil list without
    // materialising a Vector (avoids unlist() O(n) alloc per call).
    case Prim("__method__", Lit(Const.CStr("foreach")) :: recv :: lambdaArg :: Nil) =>
      tryFC(recv, globals).flatMap { fcr =>
        // Inline-body path for Lam(1, body): skip closure creation + trampoline per element.
        // fcb receives extended env with the list element appended.
        val inlinePath: Option[FC] = lambdaArg match
          case Lam(1, lambdaBody) =>
            tryFCAppended(lambdaBody, globals).map { fcb =>
              (env: Env) =>
                var cur = fcr(env)
                while cur.isInstanceOf[DataV] && cur.asInstanceOf[DataV].tag == "Cons" do
                  val cons = cur.asInstanceOf[DataV]
                  fcb(env, cons.fields(0))
                  cur = cons.fields(1)
                UnitV
            }.orElse(tryFC(lambdaBody, globals).map { fcb =>
              (env: Env) =>
                var cur = fcr(env)
                while cur.isInstanceOf[DataV] && cur.asInstanceOf[DataV].tag == "Cons" do
                  val cons = cur.asInstanceOf[DataV]
                  fcb(Runtime.appendOne(env, cons.fields(0)))
                  cur = cons.fields(1)
                UnitV
            })
          case _ => None
        inlinePath orElse
          // ClosV path for Global-referenced lambdas
          tryFC(lambdaArg, globals).map { fcl =>
            (env: Env) =>
              val lam = fcl(env).asInstanceOf[ClosV]
              var cur = fcr(env)
              while cur.isInstanceOf[DataV] && cur.asInstanceOf[DataV].tag == "Cons" do
                val cons = cur.asInstanceOf[DataV]
                Runtime.run(lam.code, Runtime.appendOne(lam.env, cons.fields(0)))
                cur = cons.fields(1)
              UnitV
          }
      }
    // __method__ with literal method name — dispatch via methodOp (correct for all types).
    // NOT using tryFLC here: tryFLC is OPTIMISTIC (coerces Float/String→0L) which gives wrong
    // results for length/size on lists (returns cons-cell field count 2, not list length) and
    // for _N accessors on non-Int tuple fields.
    case Prim("__method__", Lit(Const.CStr(m)) :: recv :: args) =>
      tryFC(recv, globals).flatMap { fcr =>
        val argOpts = args.map(tryFC(_, globals))
        if argOpts.forall(_.isDefined) then
          val fca = argOpts.map(_.get)
          Some((env: Env) => Prims.methodOp(m, fcr(env), fca.map(f => f(env))): Value)
        else None
      }
    case Prim(op, List(a0)) =>
      Prims.resolve1(op).flatMap { fn1 =>
        tryFC(a0, globals).map { fc0 => env => fn1(fc0(env)) }
      }
    case Prim(op, List(a0, a1)) =>
      Prims.resolve2(op).flatMap { fn2 =>
        tryFC(a0, globals).flatMap { fc0 =>
          tryFC(a1, globals).map { fc1 => env => fn2(fc0(env), fc1(env)) }
        }
      }
    case Prim(op, List(a0, a1, a2)) =>
      Prims.resolve3(op).flatMap { fn3 =>
        tryFC(a0, globals).flatMap { fc0 =>
          tryFC(a1, globals).flatMap { fc1 =>
            tryFC(a2, globals).map { fc2 => env => fn3(fc0(env), fc1(env), fc2(env)) }
          }
        }
      }
    case Seq(terms) =>
      // tryFCLongSet before tryFC: allows lcell.set terms inside a Seq body (e.g., inside Let
      // bindings in a while loop).  Enables the full FC path for loops with val-bindings +
      // lcell mutations (e.g., tuple-monoid's while body).
      val opts = terms.map(t => tryFCLongSet(t, globals).orElse(tryFC(t, globals)))
      if opts.forall(_.isDefined) then
        // Direct captures for 1/2/3 terms: JIT inlines f0/f1/f2 as known types.
        // SeqFastCode fallback for n≥4 keeps class diversity bounded.
        val fcsArr = opts.map(_.get).toArray
        // Non-final bridge-effect values must be Op-checked
        // (Runtime.seqThreadOp) like every other Seq path — the plain
        // `f0(env); f1(env)` forms silently dropped runtime effects in statement
        // position.
        inline def step(v: Value, rest: () => Value): Value = v match
          case opv @ Value.DataV("Op", _) if Runtime.isAutoThreadOp(opv) => Runtime.seqThreadOp(opv, rest)
          case _ => rest()
        fcsArr.length match
          case 1 => val f0 = fcsArr(0); Some(env => f0(env))
          case 2 => val f0 = fcsArr(0); val f1 = fcsArr(1)
                    Some(env => step(f0(env), () => f1(env)))
          case 3 => val f0 = fcsArr(0); val f1 = fcsArr(1); val f2 = fcsArr(2)
                    Some(env => step(f0(env), () => step(f1(env), () => f2(env))))
          case n => Some(new SeqFastCode(fcsArr, n))
      else None
    case If(c, th, el) =>
      tryFC(c, globals).flatMap { fcc =>
        tryFC(th, globals).flatMap { fct =>
          tryFC(el, globals).map { fce =>
            env => (fcc(env) match { case BoolV(b) => b; case _ => false }) match
              case true  => fct(env)
              case false => fce(env)
          }
        }
      }
    case Let(rhs, body) =>
      val rOpts = rhs.map(tryFC(_, globals))
      if rOpts.forall(_.isDefined) then
        val rFcs = rOpts.map(_.get)
        tryFC(body, globals).map { fbody =>
          env =>
            var e = env
            for rfc <- rFcs do e = Runtime.appendOne(e, rfc(e))
            fbody(e)
        }
      else None
    case Ctor(tag, fields) =>
      val opts = fields.map(tryFC(_, globals))
      if opts.forall(_.isDefined) then
        val fcs = opts.map(_.get)
        // Constant Ctor: all fields are Lit → precompute once, return cached
        if fields.forall(_.isInstanceOf[Lit]) then
          val arr0 = fcs.map(fc => fc(Array.empty)).toArray
          val precomputed = DataV(tag, collection.immutable.ArraySeq.unsafeWrapArray(arr0))
          Some(_ => precomputed)
        else fcs.length match
          // Use ArraySeq.unsafeWrapArray: 2 allocs (Array+wrapper) vs 4 for Vector
          case 1 =>
            val fc0 = fcs(0)
            Some((env: Env) =>
              val a = new Array[Value](1); a(0) = fc0(env)
              DataV(tag, collection.immutable.ArraySeq.unsafeWrapArray(a)))
          case 2 =>
            val fc0 = fcs(0); val fc1 = fcs(1)
            Some((env: Env) =>
              val a = new Array[Value](2); a(0) = fc0(env); a(1) = fc1(env)
              DataV(tag, collection.immutable.ArraySeq.unsafeWrapArray(a)))
          case 3 =>
            val fc0 = fcs(0); val fc1 = fcs(1); val fc2 = fcs(2)
            Some((env: Env) =>
              val a = new Array[Value](3); a(0) = fc0(env); a(1) = fc1(env); a(2) = fc2(env)
              DataV(tag, collection.immutable.ArraySeq.unsafeWrapArray(a)))
          case n =>
            val fcsArr = fcs.toArray
            Some((env: Env) =>
              val a = new Array[Value](n); var k = 0; while k < n do { a(k) = fcsArr(k)(env); k += 1 }
              DataV(tag, collection.immutable.ArraySeq.unsafeWrapArray(a)))
      else None
    // __arith__("++") — tuple/string concat fast-path (avoids full trampoline for DataV++)
    case Prim("__arith__", List(Lit(Const.CStr("++")), a0, a1)) =>
      // Ctor ++ Ctor fast path: build result DataV directly from combined field FCs (no intermediate DataV).
      // Avoids: (1) creating an intermediate LHS DataV, (2) a Vector.++ call per iteration.
      val ctorFused: Option[FC] = (a0, a1) match
        case (Ctor(_, lf), Ctor(_, rf)) =>
          val allOpts = (lf ++ rf).map(tryFC(_, globals))
          if allOpts.forall(_.isDefined) then
            val fcs = allOpts.map(_.get).toArray
            val n   = fcs.length
            val tag = s"Tuple$n"
            Some(n match
              case 4 =>
                val f0 = fcs(0); val f1 = fcs(1); val f2 = fcs(2); val f3 = fcs(3)
                (env: Env) =>
                  val a = new Array[Value](4); a(0) = f0(env); a(1) = f1(env); a(2) = f2(env); a(3) = f3(env)
                  DataV(tag, collection.immutable.ArraySeq.unsafeWrapArray(a))
              case 2 =>
                val f0 = fcs(0); val f1 = fcs(1)
                (env: Env) =>
                  val a = new Array[Value](2); a(0) = f0(env); a(1) = f1(env)
                  DataV(tag, collection.immutable.ArraySeq.unsafeWrapArray(a))
              case _ =>
                (env: Env) =>
                  val a = new Array[Value](n); var k = 0; while k < n do { a(k) = fcs(k)(env); k += 1 }
                  DataV(tag, collection.immutable.ArraySeq.unsafeWrapArray(a))
            )
          else None
        case _ => None
      ctorFused.orElse {
        tryFC(a0, globals).flatMap { f0 => tryFC(a1, globals).map { f1 =>
          // If RHS is constant (all-Lit Ctor), precompute once and capture in closure
          val isConstRHS = a1.isInstanceOf[Ctor] && a1.asInstanceOf[Ctor].fields.forall(_.isInstanceOf[Lit])
          if isConstRHS then
            val constR = f1(Array.empty)
            (env: Env) =>
              (f0(env), constR) match
                case (DataV(lt, lf), DataV(rt, rf)) =>
                  val combined = lf ++ rf; DataV(s"Tuple${combined.length}", combined)
                case (StrV(l), StrV(r)) => StrV(l + r)
                case (l, r) => Prims.arithOp("++", l, r)
          else
            (env: Env) =>
              (f0(env), f1(env)) match
                case (DataV(lt, lf), DataV(rt, rf)) if lt.startsWith("Tuple") && rt.startsWith("Tuple") =>
                  val combined = lf ++ rf; DataV(s"Tuple${combined.length}", combined)
                case (StrV(l), StrV(r))  => StrV(l + r)
                case (StrV(l), IntV(r))  => StrV(l + r.toString)
                case (StrV(l), v)        => StrV(l + Show.show(v))
                case (l, r)              => Prims.arithOp("++", l, r)
        } }
      }
    case Match(scrut, arms, default) =>
      // Fast match: compile scrutinee and ALL arm bodies (using float-safe tryFCValue).
      // Returns None if any arm body isn't FC-able (e.g. recursive App, LetRec).
      tryFC(scrut, globals).flatMap { fcScrut =>
        val armFCOpts = arms.map(arm => tryFCValue(arm.body, globals).map(fcBody => (arm.tag, arm.arity, fcBody)))
        val defFCOpt  = default.map(d => tryFCValue(d, globals))
        if armFCOpts.forall(_.isDefined) && (default.isEmpty || defFCOpt.exists(_.isDefined)) then
          val armFCs  = armFCOpts.map(_.get)
          // Linear scan over Array is faster than HashMap for small (≤~8) arm counts:
          // avoids hash-compute + modulo + collision checks (~15ns saved per dispatch).
          val armTags    = armFCs.map(_._1).toArray
          val armArs     = armFCs.map(_._2).toArray
          val armBods    = armFCs.map(_._3).toArray
          val nArms      = armTags.length
          val defFC      = defFCOpt.flatten
          // For arms whose body only uses Local(0)..Local(arity-1) (pattern fields only, no outer
          // env vars), we can call armBod with a compact env of length = arity instead of
          // appendOne(baseEnv, field).  The JVM's escape-analysis can then stack-allocate the
          // compact env (a small fresh Array that doesn't outlive the armBod call).
          val armCompact = arms.zip(armFCOpts.map(_.get)).map { case (arm, (_, ar, _)) =>
            armBodyCompact(arm.body, ar)
          }.toArray
          val armScratchSafe = arms.zip(armFCOpts.map(_.get)).map { case (arm, (_, ar, _)) =>
            armBodyScratchSafe(arm.body, ar)
          }.toArray
          val scratch1 =
            if (0 until nArms).exists(k => armCompact(k) && armScratchSafe(k) && armArs(k) == 1) then new Array[Value](1)
            else null
          val scratch2 =
            if (0 until nArms).exists(k => armCompact(k) && armScratchSafe(k) && armArs(k) == 2) then new Array[Value](2)
            else null
          Some((env: Env) =>
            fcScrut(env) match
              case DataV(tag, fs) =>
                var k = 0; while k < nArms && armTags(k) != tag do k += 1
                if k < nArms then
                  val ar = armArs(k)
                  val extEnv =
                    if armCompact(k) && armScratchSafe(k) then ar match
                      case 0 => Runtime.emptyEnv
                      case 1 =>
                        val e = scratch1
                        e(0) = fs(0)
                        e
                      case 2 =>
                        val e = scratch2
                        e(0) = fs(0); e(1) = fs(1)
                        e
                      case _ => fs.toArray
                    else if armCompact(k) then ar match
                      case 0 => Runtime.emptyEnv
                      case 1 => Array(fs(0))
                      case 2 => Array(fs(0), fs(1))
                      case _ => fs.toArray
                    else ar match
                      case 0 => env
                      case 1 => Runtime.appendOne(env, fs(0))
                      case 2 => Runtime.extend(env, Array(fs(0), fs(1)))
                      case _ => Runtime.extend(env, fs.toArray)
                  armBods(k)(extEnv)
                else defFC.map(_(env)).getOrElse(Value.UnitV)
              case _ => defFC.map(_(env)).getOrElse(Value.UnitV)
          )
        else None
      }
    // Lam in while body: compile body once; create ClosV capturing current env per call.
    // This allows `shapes.foreach(s => ...)` in a while loop to fast-compile the outer loop.
    case Lam(arity, body) =>
      val handlerRoot = HandlerDispatchShape.isRoot(arity, body)
      val bodyC = Compiler.C(globals).compile(body, handlerRoot)
      val ar = arity
      Some((env: Env) =>
        if handlerRoot then Runtime.handlerClosure(env, ar, bodyC)
        else ClosV(env, ar, bodyC))
    // While as an FC term: used when a while loop appears inside a Seq, Let body, or foreach.
    // Both condition and body must be FC-able (otherwise fall back to compile path).
    case While(cond, body) =>
      tryFBc(cond, globals).flatMap { fbc =>
        tryFC(body, globals).map { fb =>
          (env: Env) => { while fbc(env) do fb(env); Value.UnitV }
        }
      }
    case _ => None

  /** tryFC with App(Global) support, for computing fcEntry of mutually-recursive functions.
   *  selfName: blocks App(Global(selfName)) to prevent direct self-recursion
   *  (self-recursive defs use the trampoline for TCO safety).
   *  App(Global) FCs capture the target ClosV directly — so fcEntry updates in the
   *  sibling def are visible at runtime without re-compilation. */
  def tryFCMutual(t: Term, globals: collection.mutable.Map[String, Value], selfName: String): Option[FC] = t match
    case App(Global(name), args) if name != selfName =>
      globals.get(name).collect { case closV: ClosV if closV.env.isEmpty =>
        val argOpts = args.map(a => tryFCMutual(a, globals, selfName))
        if argOpts.forall(_.isDefined) then
          val fca = argOpts.map(_.get).toArray
          // Carrier optimisation: for a single Long-typed arg, preallocate a LongCellV +
          // Array[Value](1) at compile time and reuse across all recursive calls.
          // Safe for tail-recursive mutual calls: each level reads carrier.v BEFORE the
          // recursive call overwrites it, so there is no aliasing hazard.
          // tryFLC(args(0)) gives the unboxed Long computation; carrier replaces IntV alloc.
          val carrierOpt: Option[FC] =
            if fca.length == 1 then
              tryFLC(args(0), globals).map { flc0 =>
                val carrier      = new LongCellV(0L)
                val sharedArgEnv = new Array[Value](1); sharedArgEnv(0) = carrier
                (env: Env) =>
                  carrier.v = flc0(env)
                  closV.fcEntry match
                    case Some(bodyFC) => bodyFC(sharedArgEnv)
                    case None =>
                      val fresh = new Array[Value](1); fresh(0) = IntV(carrier.v)
                      Runtime.run(closV.code, fresh)
              }
            else None
          carrierOpt.orElse(
            Some((env: Env) =>
              val argEnv = fca.map(f => f(env): Value)
              closV.fcEntry match
                case Some(bodyFC) => bodyFC(argEnv)
                case None => Runtime.run(closV.code, argEnv)
            )
          )
        else None
      }.flatten
    case If(c, th, el) =>
      tryFBc(c, globals).flatMap { fcc =>
        tryFCMutual(th, globals, selfName).flatMap { fct =>
          tryFCMutual(el, globals, selfName).map { fce =>
            (env: Env) => (fcc(env)) match
              case true  => fct(env)
              case false => fce(env)
          }
        }
      }
    case _ => tryFC(t, globals)

  /** Try to compile a condition term to a FastBoolCode (avoids BoolV allocation). */
  def tryFBc(t: Term, globals: collection.mutable.Map[String, Value]): Option[FBc] = t match
    case term if Compiler.mayProduceAutoThreadOp(term) => None
    // __arith__ comparisons — bridge generates these; fast path via unboxed Long comparison.
    // GUARD (ALL ops): only when BOTH operands are provably Long. tryFLC reads a Local optimistically
    // as a Long and returns 0L for a FloatV OR StrV (see the Local case), so an unguarded fast path makes
    // Double `<`/`>` inside a fold/loop always compare 0<0 → false (foldLeft over Doubles returned the
    // last element; min/max broken) — and unguarded `==`/`!=` made STRING equality of two locals
    // always 0==0 → TRUE (busi incomeFor: `if p == period` matched EVERY period; the July fact
    // leaked into the June query). Non-provably-Long operands fall through to the general,
    // type-honest compare.
    case Prim("__arith__", List(Lit(Const.CStr(op)), a0, a1))
        if (op == "==" || op == "!=" || op == "<" || op == "<=" || op == ">" || op == ">=")
           && flcProvablyLong(a0) && flcProvablyLong(a1) =>   // only when provably Long
      tryFLC(a0, globals).flatMap { f0 => tryFLC(a1, globals).map { f1 =>
        val fn: (Long, Long) => Boolean = op match
          case "<"  => _ < _; case "<=" => _ <= _; case ">"  => _ > _
          case ">=" => _ >= _; case "==" => _ == _; case "!=" => _ != _; case _ => (_, _) => false
        (env: Env) => fn(f0(env), f1(env))
      } }
    case Prim(op, List(a0, a1)) if op.startsWith("i.l") || op.startsWith("i.g") || op == "i.eq" =>
      // Integer comparisons: try FLC first to avoid IntV boxing of operands
      tryFLC(a0, globals).flatMap { flc0 =>
        tryFLC(a1, globals).map { flc1 =>
          val cmpFn: (Long, Long) => Boolean = op match
            case "i.lt" => _ < _
            case "i.le" => _ <= _
            case "i.gt" => _ > _
            case "i.ge" => _ >= _
            case "i.eq" => _ == _
            case _      => (_, _) => false
          (env: Env) => cmpFn(flc0(env), flc1(env))
        }
      } orElse {
        // Fallback to Value-based comparison
        val fn2bOpt: Option[(Value, Value) => Boolean] = op match
          case "i.lt"  => Some { case (IntV(x), IntV(y)) => x < y;  case _ => false }
          case "i.le"  => Some { case (IntV(x), IntV(y)) => x <= y; case _ => false }
          case "i.gt"  => Some { case (IntV(x), IntV(y)) => x > y;  case _ => false }
          case "i.ge"  => Some { case (IntV(x), IntV(y)) => x >= y; case _ => false }
          case "i.eq"  => Some { case (IntV(x), IntV(y)) => x == y; case (a,b) => a==b }
          case _       => None
        fn2bOpt.flatMap { fn2b =>
          tryFC(a0, globals).flatMap { fc0 =>
            tryFC(a1, globals).map { fc1 => (env: Env) => fn2b(fc0(env), fc1(env)) }
          }
        }
      }
    case Prim(op, List(a0, a1)) =>
      val fn2bOpt: Option[(Value, Value) => Boolean] = op match
        case "f.lt"  => Some { case (FloatV(x), FloatV(y)) => x < y; case _ => false }
        case "f.le"  => Some { case (FloatV(x), FloatV(y)) => x <= y; case _ => false }
        case "f.gt"  => Some { case (FloatV(x), FloatV(y)) => x > y; case _ => false }
        case "f.ge"  => Some { case (FloatV(x), FloatV(y)) => x >= y; case _ => false }
        case "f.eq"  => Some { case (FloatV(x), FloatV(y)) => x == y; case _ => false }
        case "seq"   => Some { case (StrV(a), StrV(b)) => a == b; case _ => false }
        case _       => None
      fn2bOpt.flatMap { fn2b =>
        tryFC(a0, globals).flatMap { fc0 =>
          tryFC(a1, globals).map { fc1 => (env: Env) => fn2b(fc0(env), fc1(env)) }
        }
      }
    case Prim("not", List(a0)) =>
      tryFBc(a0, globals).map { fbc => (env: Env) => !fbc(env) }
    case _ =>
      // Fallback: try FC and unbox BoolV
      tryFC(t, globals).map { fc => (env: Env) => fc(env) match { case BoolV(b) => b; case _ => false } }

// ── Plugin registry — lets external code add Prim handlers (v2-plugin-bridge) ──
// External bridge modules call V2PluginRegistry.register(op, fn) at startup
// to supply handlers for Prim ops unknown to the built-in Prims table.
// Prims.resolve falls back here before throwing "unimplemented primitive".
object V2PluginRegistry:
  type Fn = List[Value] => Value
  private val handlers = collection.mutable.HashMap[String, Fn]()
  def register(op: String, fn: Fn): Unit = handlers(op) = fn
  def lookup(op: String): Option[Fn] = handlers.get(op)

  // Tag-qualified runtime extension points. These keep plugin-owned data
  // callable without installing collision-prone global get/set/apply hooks.
  private val taggedApply = collection.mutable.HashMap[String, Fn]()
  private val taggedMethods = collection.mutable.HashMap[(String, String), Fn]()
  def registerTaggedApply(tag: String, fn: Fn): Unit = taggedApply(tag) = fn
  def lookupTaggedApply(tag: String): Option[Fn] = taggedApply.get(tag)
  def registerTaggedMethod(tag: String, name: String, fn: Fn): Unit =
    taggedMethods((tag, name)) = fn
  def lookupTaggedMethod(tag: String, name: String): Option[Fn] =
    taggedMethods.get((tag, name))

  // Global value registry — for runLogger/runState/handle etc. that appear as Global(name) in Core IR.
  private val globalValues = collection.mutable.HashMap[String, Value]()
  def registerGlobal(name: String, v: Value): Unit = globalValues(name) = v
  def lookupGlobal(name: String): Option[Value] = globalValues.get(name)
  def hasGlobal(name: String): Boolean = globalValues.contains(name)
  def allGlobalNames(): Iterable[String] = globalValues.keys

  /** Batch isolation: snapshot the registry right after plugin loading and
   *  restore it before each file, so one program's registrations/mutations
   *  (databases, cells, namespaces) cannot leak into the next — batch PASS
   *  counts were ±2 order-dependent without this. */
  final case class Snapshot(
      handlers: Map[String, Fn],
      globalValues: Map[String, Value],
      taggedApply: Map[String, Fn],
      taggedMethods: Map[(String, String), Fn])

  def snapshot(): Snapshot =
    Snapshot(handlers.toMap, globalValues.toMap, taggedApply.toMap, taggedMethods.toMap)
  def restore(snap: Snapshot): Unit =
    handlers.clear(); handlers ++= snap.handlers
    globalValues.clear(); globalValues ++= snap.globalValues
    taggedApply.clear(); taggedApply ++= snap.taggedApply
    taggedMethods.clear(); taggedMethods ++= snap.taggedMethods

  // ADT field-name registry: tag → ordered field names.
  // Populated by FrontendBridge so PluginBridge.v2ToV1 can produce named InstanceV fields.
  private val fieldNames = collection.mutable.HashMap[String, Vector[String]]()
  // Secondary index keyed by (tag, arity): two DIFFERENT case classes can share a
  // tag NAME (e.g. std/http.ssc `Request` vs a domain `Request`) — the plain
  // fieldNames map is last-registered-wins, which breaks field-name resolution for
  // the loser. Field ACCESS resolves against the layout whose arity matches the
  // receiver's actual field count (v2-req-form-type-collision).
  private val fieldNamesByArity = collection.mutable.HashMap[(String, Int), Vector[String]]()
  def registerFieldNames(tag: String, names: Vector[String]): Unit =
    fieldNames(tag) = names
    fieldNamesByArity((tag, names.length)) = names
  def lookupFieldNames(tag: String): Option[Vector[String]] = fieldNames.get(tag)
  /** Field names for `tag` whose arity matches the receiver (disambiguates two
   *  same-named case classes); falls back to the last-registered layout. */
  def lookupFieldNames(tag: String, arity: Int): Option[Vector[String]] =
    fieldNamesByArity.get((tag, arity)).orElse(fieldNames.get(tag))

  /** Start a standard native-provider session without retaining handlers or
   *  field layouts installed by an earlier compatibility-bridge run. */
  def clear(): Unit =
    handlers.clear()
    globalValues.clear()
    taggedApply.clear()
    taggedMethods.clear()
    fieldNames.clear()
    fieldNamesByArity.clear()

// ── Effect context — ThreadLocal stack for BlockForm effect runners ────────────
// PluginBridge installs one V2EffectHandler per active runXxx block.
// __method__ dispatch calls V2EffectContext.peek(tag) before "unimplemented".
object V2EffectContext:
  // Handler: (opName, args) => result. Isolates Runtime from SPI types.
  type EH = (String, List[Value]) => Value

  private val stack = ThreadLocal.withInitial[collection.mutable.Map[String, List[EH]]](
    () => collection.mutable.HashMap()
  )

  def push(effectTag: String, h: EH): Unit =
    val m = stack.get()
    m(effectTag) = h :: m.getOrElse(effectTag, Nil)

  def pop(effectTag: String): Unit =
    val m = stack.get()
    m.get(effectTag) match
      case Some(_ :: rest) => if rest.isEmpty then m.remove(effectTag) else m(effectTag) = rest
      case _               => ()

  def peek(effectTag: String): Option[EH] = stack.get().get(effectTag).flatMap(_.headOption)

// ── Primitives δ — resolved once at compile time (specs/10-core-ir.md §5) ─────

object Prims:
  import Value.*
  type Fn = List[Value] => Value

  private object NotBuiltin extends Function1[List[Value], Value]:
    def apply(args: List[Value]): Value =
      throw new IllegalStateException("non-builtin primitive sentinel invoked")

  private def resolveBuiltinRaw(op: String): Fn = op match
    case portable if PortableDecimal.primitiveNames.contains(portable) =>
      args => PortableDecimal.eval(portable, args)
    case portable if PortableEffects.primitiveNames.contains(portable) =>
      args => PortableEffects.eval(portable, args)
    case "i.add" => a => liftArith("+", a, numBin(a, _ + _, _ + _))
    case "i.sub" => a => liftArith("-", a, numBin(a, _ - _, _ - _))
    case "i.mul" => a => liftArith("*", a, numBin(a, _ * _, _ * _))
    case "i.div" => a => numBin(a, _ / _, _ / _)
    case "i.mod" => a => numBin(a, _ % _, _ % _)
    case "i.neg" => a => numUn(a, -_, -_)
    case "i.and" => a => IntV(int(a, 0) & int(a, 1))
    case "i.or"  => a => IntV(int(a, 0) | int(a, 1))
    case "i.xor" => a => IntV(int(a, 0) ^ int(a, 1))
    case "i.not" => a => IntV(~int(a, 0))
    case "i.shl" => a => IntV(int(a, 0) << int(a, 1))
    case "i.shr" => a => IntV(int(a, 0) >> int(a, 1))
    case "i.ushr"=> a => IntV(int(a, 0) >>> int(a, 1))
    case "i.eq"  => a => numCmp(a, _ == _, _ == _)
    case "i.lt"  => a => numCmp(a, _ <  _, _ <  _)
    case "i.le"  => a => numCmp(a, _ <= _, _ <= _)
    case "i.gt"  => a => numCmp(a, _ >  _, _ >  _)
    case "i.ge"  => a => numCmp(a, _ >= _, _ >= _)
    case "not"   => a => BoolV(!bool(a, 0))
    // BigInt
    case "big.add" => a => BigV(big(a, 0) + big(a, 1))
    case "big.sub" => a => BigV(big(a, 0) - big(a, 1))
    case "big.mul" => a => BigV(big(a, 0) * big(a, 1))
    case "big.div" => a => BigV(big(a, 0) / big(a, 1))
    case "big.mod" => a => BigV(big(a, 0) % big(a, 1))
    case "big.neg" => a => BigV(-big(a, 0))
    case "big.eq"  => a => BoolV(big(a, 0) == big(a, 1))
    case "big.lt"  => a => BoolV(big(a, 0) <  big(a, 1))
    case "big.le"  => a => BoolV(big(a, 0) <= big(a, 1))
    case "big.gt"  => a => BoolV(big(a, 0) >  big(a, 1))
    case "big.ge"  => a => BoolV(big(a, 0) >= big(a, 1))
    // Float (IEEE-754)
    case "f.add" => a => FloatV(flt(a, 0) + flt(a, 1))
    case "f.sub" => a => FloatV(flt(a, 0) - flt(a, 1))
    case "f.mul" => a => FloatV(flt(a, 0) * flt(a, 1))
    case "f.div" => a => FloatV(flt(a, 0) / flt(a, 1))
    case "f.neg" => a => FloatV(-flt(a, 0))
    case "f.sqrt"  => a => FloatV(math.sqrt(flt(a, 0)))
    case "f.floor" => a => FloatV(math.floor(flt(a, 0)))
    case "f.ceil"  => a => FloatV(math.ceil(flt(a, 0)))
    case "f.round" => a => FloatV(math.rint(flt(a, 0)))
    case "f.trunc" => a => FloatV(flt(a, 0).toLong.toDouble)
    case "f.eq" => a => BoolV(flt(a, 0) == flt(a, 1))
    case "f.lt" => a => BoolV(flt(a, 0) <  flt(a, 1))
    case "f.le" => a => BoolV(flt(a, 0) <= flt(a, 1))
    case "f.gt" => a => BoolV(flt(a, 0) >  flt(a, 1))
    case "f.ge" => a => BoolV(flt(a, 0) >= flt(a, 1))
    case "f.isNaN" => a => BoolV(flt(a, 0).isNaN)
    case "f.isInf" => a => BoolV(flt(a, 0).isInfinite)
    // numeric conversions (explicit)
    case "i->big"  => a => BigV(BigInt(int(a, 0)))
    case "big->i"  => a => IntV(big(a, 0).toLong)
    case "i->f"    => a => FloatV(int(a, 0).toDouble)
    case "f->i"    => a => IntV(flt(a, 0).toLong)
    case "big->f"  => a => FloatV(big(a, 0).toDouble)
    case "f->big"  => a => BigV(BigDecimal(flt(a, 0)).toBigInt)
    case "i->str"  => a => StrV(int(a, 0).toString)
    case "big->str"=> a => StrV(big(a, 0).toString)
    case "f->str"  => a => StrV(Writer.floatStr(flt(a, 0)))
    case "str->i"  => a => str(a, 0).toLongOption.fold(none)(n => some(IntV(n)))
    case "str->big"=> a => scala.util.Try(BigInt(str(a, 0))).toOption.fold(none)(b => some(BigV(b)))
    case "str->f"  => a => str(a, 0).toDoubleOption.fold(none)(d => some(FloatV(d)))
    // String (UTF-16 code units; O(1) indexing)
    case "slen"      => a => IntV(str(a, 0).length.toLong)
    case "sconcat"   => a => (a(0), a(1)) match {
      case (DataV(_, f1), DataV(_, f2)) =>
        val n = f1.length + f2.length; DataV(s"Tuple$n", f1 ++ f2)
      case _ => StrV(anyStr(a(0)) + anyStr(a(1)))
    }
    case "sslice"    => a => StrV(str(a, 0).substring(int(a, 1).toInt, int(a, 2).toInt))
    case "scodeAt"   => a => IntV(str(a, 0).charAt(int(a, 1).toInt).toLong)
    case "sfromCodes"=> a => StrV(unlist(a(0)).map(v => asInt(v).toChar).mkString)
    case "__fInterpolate__" => a =>
      if a.isEmpty then StrV("")
      else
        val sb = new StringBuilder(str(a, 0))
        var i = 1
        while i + 2 < a.length do
          val spec = str(a, i)
          sb.append(formatValue(spec, a(i + 1)))
          sb.append(str(a, i + 2))
          i += 3
        StrV(sb.toString)
    case "seq"       => a => BoolV(str(a, 0) == str(a, 1))
    case "scmp"      => a => IntV(str(a, 0).compareTo(str(a, 1)).toLong)
    case "sindexOf"  => a => IntV(str(a, 0).indexOf(str(a, 1)).toLong)
    case "str.split" => a => { val parts = str(a, 0).split(str(a, 1), -1); val nilV: Value = DataV("Nil", IndexedSeq.empty); parts.foldRight(nilV)((s, acc) => DataV("Cons", collection.immutable.ArraySeq(StrV(s), acc))) }
    case "str.trim"  => a => StrV(str(a, 0).trim)
    case "str.replace" => a => StrV(str(a, 0).replace(str(a, 1), str(a, 2)))
    case "str.lines" => a => { val parts = str(a, 0).split("\n", -1); val nilV: Value = DataV("Nil", IndexedSeq.empty); parts.foldRight(nilV)((s, acc) => DataV("Cons", collection.immutable.ArraySeq(StrV(s), acc))) }
    // Bytes
    case "blen"      => a => IntV(bytes(a, 0).length.toLong)
    case "bget"      => a => IntV((bytes(a, 0)(int(a, 1).toInt) & 0xff).toLong)
    case "bslice"    => a => BytesV(bytes(a, 0).slice(int(a, 1).toInt, int(a, 2).toInt))
    case "bconcat"   => a => BytesV(bytes(a, 0) ++ bytes(a, 1))
    case "str->utf8" => a => BytesV(str(a, 0).getBytes("UTF-8").toVector)
    case "utf8->str" => a => StrV(new String(bytes(a, 0).toArray, "UTF-8"))
    // Data (generic reflection)
    case "tagOf"   => a => StrV(asData(a(0))._1)
    case "arity"   => a => IntV(asData(a(0))._2.length.toLong)
    case "__autoPrint__" => a =>
      // v1 auto-output: print a fence's final expression value unless Unit
      // (or an unhandled effect Op — those surface through `handle`, not here).
      a(0) match
        case UnitV => UnitV
        case DataV("Op", _) => UnitV
        case v => println(anyStr(v)); UnitV
    case "__methodOrExt__" => a =>
      // __methodOrExt__(name, recv, args…, extensionClosure): receiver-owned
      // members win; otherwise call the user extension global.
      val mname = a.head match { case StrV(s) => s; case v => Show.show(v) }
      val recv  = a(1)
      val ext   = a.last.asInstanceOf[ClosV]
      val margs = a.drop(2).dropRight(1)
      def callExt(): Value = callClos(ext, (recv :: margs).toArray)
      def pluginOrExt(): Value =
        V2PluginRegistry.lookup(s"__method__.$mname") match
          case Some(fn) =>
            fn(a.dropRight(1)) match
              case DataV("Stub", _) => callExt()
              case handled          => handled
          case None => callExt()
      recv match
        case ForeignV(obj: NamedMethodObj) if obj.getField(mname).isDefined =>
          obj.getField(mname) match
            case Some(fn: ClosV)              => callClos(fn, margs.toArray)
            case Some(v) if margs.isEmpty     => v
            case _                            => callExt()
        case ForeignV(m: collection.immutable.Map[?, ?])
            if m.keysIterator.forall(_.isInstanceOf[String]) &&
               m.asInstanceOf[collection.immutable.Map[String, Value]].contains(mname) =>
          val mm = m.asInstanceOf[collection.immutable.Map[String, Value]]
          mm(mname) match
            case fn: ClosV if margs.isEmpty && fn.arity > 0 => fn
            case fn: ClosV                  => callClos(fn, margs.toArray)
            case v if margs.isEmpty         => v
            case _                          => callExt()
        case DataV(tag, fields) =>
          def methodOrPluginOrExt(): Value =
            methodOp(mname, recv, margs) match
              case DataV("Stub", _) => pluginOrExt()
              // A method-not-found result surfaces as the auto-thread Op
              // `Op("<tag>.<mname>", …)` — NOT a resolved value. Treat it like
              // a Stub and fall to the user extension (else `None.handleError`
              // et al. leaked the raw Op instead of dispatching the typeclass
              // extension — regressed by the remote-registry method-first path).
              case DataV("Op", IndexedSeq(StrV(lbl), _, _)) if lbl.endsWith("." + mname) =>
                pluginOrExt()
              case handled          => handled
          V2PluginRegistry.lookupFieldNames(tag, fields.length) match
            case Some(fnames) =>
              val i = fnames.indexOf(mname)
              if i >= 0 && i < fields.length then
                val fv = fields(i)
                if margs.isEmpty then fv
                else fv match
                  case fn: ClosV => callClos(fn, margs.toArray)
                  case lv @ (DataV("Cons", _) | DataV("Nil", _)) =>
                    margs.head match
                      case IntV(ix) => unlistPub(lv)(ix.toInt)
                      case _ => DataV("Stub", Vector(StrV(s"$tag.$mname")))
                  case MapV(m) => m(margs.head)
                  case ForeignV(m: collection.mutable.Map[?, ?]) =>
                    m.asInstanceOf[collection.mutable.Map[Value, Value]](margs.head)
                  case _ => DataV("Stub", Vector(StrV(s"$tag.$mname")))
              else methodOrPluginOrExt()
            case None => methodOrPluginOrExt()
        case _ => pluginOrExt()
    case "__arithExt__" => a =>
      // __arithExt__(opName, l, r, extensionClosure): numeric operands use the
      // kernel arith table; anything else calls the user extension (parser `|`,
      // Doc `++`, …).
      val num = (v: Value) => v match { case IntV(_) | FloatV(_) | BigV(_) => true; case _ => false }
      (a(0), a(3)) match
        case (StrV(op), ext: ClosV) =>
          // Bitwise `|` is primitive only for Int operands. Float/Big pairs are
          // not valid bitwise values and, when an extension is in scope, must
          // reach that extension just like ADTs do. Other ambiguous arithmetic
          // operators keep the established numeric-first behavior.
          val primitiveWins =
            if op == "|" then a(1).isInstanceOf[IntV] && a(2).isInstanceOf[IntV]
            else num(a(1)) && num(a(2))
          if primitiveWins then arithOp(op, a(1), a(2))
          else callClos(ext, Array(a(1), a(2)))
        case _ => sys.error("__arithExt__: bad args")
    case "__isNum2__" => a =>
      val num = (v: Value) => v match { case IntV(_) | FloatV(_) | BigV(_) => true; case _ => false }
      BoolV(num(a(0)) && num(a(1)))
    case "__effect__" => resolve("__method__")   // alias: effect-receiver ops (FastCode declines it)
    case "__effect_oneshot__" => a =>
      // Bridge-only marker: preserve ordinary effect/plugin/context dispatch,
      // then guard only the matching free Op. Identity remains structured and
      // is never recovered by parsing the legacy `Effect.operation` label.
      a match
        case StrV(effectId) :: StrV(operationName) :: receiver :: operationArgs =>
          val result = resolve("__method__")(
            StrV(operationName) :: receiver :: operationArgs)
          PortableEffects.guardOperation(effectId, operationName, result)
        case _ =>
          sys.error("__effect_oneshot__: expected effect id, operation name, receiver, and arguments")
    // Scala Predef.??? — a valid, resolvable prim that throws only when actually
    // evaluated (e.g. an untaken `else ???` branch never fires).
    case "__notImplemented__" => _ => throw new NotImplementedError("an implementation is missing")
    // Exceptions: `throw e` and `try BODY catch { case … } [finally …]` (native front lowers these to
    // these prims). The catch handler is a 1-arg closure receiving the caught VALUE — the thrown value
    // for `throw e`, or DataV("RuntimeException", [message]) for a host RuntimeException so a
    // `case e: RuntimeException => e.getMessage` arm works. Non-RuntimeException host errors propagate.
    case "__throw__" => a => throw new SscThrow(a(0))
    // Non-local return: `__throw_return__` unwinds to the nearest `__with_return__`
    // (a wrapped named-def body), which returns the carried value.
    case "__throw_return__" => a => throw new ReturnThrow(a(0))
    case "__with_return__"  => a =>
      val thunk = a(0).asInstanceOf[Value.ClosV]
      try callClos(thunk, Array.empty[Value]) catch { case r: ReturnThrow => r.value }
    case "__tryCatch__" => a =>
      val body = a(0).asInstanceOf[Value.ClosV]; val handler = a(1).asInstanceOf[Value.ClosV]
      tryRun(body, handler)
    case "__tryCatchFinally__" => a =>
      val body = a(0).asInstanceOf[Value.ClosV]; val handler = a(1).asInstanceOf[Value.ClosV]
      val fin = a(2).asInstanceOf[Value.ClosV]
      try tryRun(body, handler) finally callClos(fin, Array.empty)
    case "__tryFinally__" => a =>
      val body = a(0).asInstanceOf[Value.ClosV]; val fin = a(1).asInstanceOf[Value.ClosV]
      try callClos(body, Array.empty) finally callClos(fin, Array.empty)
    case "__mdStrip__" => a => a(0) match
      case StrV(s) =>
        // v1 Interpreter.stripIndent: drop blank edge lines, remove the
        // minimum leading-space indent of the non-blank body lines.
        val lines = s.split('\n').toList
        val body  = lines.dropWhile(_.isBlank).reverse.dropWhile(_.isBlank).reverse
        if body.isEmpty then StrV("")
        else
          val minIndent = body.filter(_.exists(_ != ' ')).map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)
          StrV(body.map(l => if l.isBlank then "" else l.drop(minIndent)).mkString("\n"))
      case other => other
    case "fieldAt" => a => a(0) match
      case DataV("Stub", _) => DataV("Stub", Vector.empty)  // stub: field access on Stub returns stub
      // 3-arg form fieldAt(recv, idx, name) on a data value: the index was
      // baked by the bridge from the GLOBAL field-name registry WITHOUT knowing
      // the receiver's type — a case class `Ref(name, head)` made `hits.head`
      // on a LIST read Cons.fields(1) (= the tail; Nil for a 1-element list,
      // surfacing as Op("Nil.trim") — the busi hub-boot blocker,
      // BUGS.md v2-head-field-dispatch-shadow). Resolve by the RECEIVER's own
      // registered field names; a tag without that field (Cons/Nil/Some/…)
      // falls back to full dynamic dispatch (builtin members stay builtin).
      case DataV(tag, fields) if a.length >= 3 =>
        val name = a(2) match { case StrV(s) => s; case other => Show.show(other) }
        // arity-matched layout: two case classes can share a tag NAME (http vs
        // domain `Request`) — resolve against the one whose arity == the
        // receiver's field count (v2-req-form-type-collision).
        V2PluginRegistry.lookupFieldNames(tag, fields.length) match
          case Some(names) =>
            val j = names.indexOf(name)
            if j >= 0 && j < fields.length then fields(j)
            else methodOp(name, a(0), Nil)
          case None => methodOp(name, a(0), Nil)
      case DataV(_, fields) =>
        val i = int(a, 1).toInt
        if i < fields.length then fields(i) else DataV("Stub", Vector.empty)  // graceful OOB
      case MapV(mm) if a.length >= 3 =>
        val fieldName = a(2) match { case StrV(s) => s; case other => Show.show(other) }
        mm.getOrElse(StrV(fieldName),
          mm.collectFirst { case (StrV(k), v) if k.equalsIgnoreCase(fieldName) => v }
            .getOrElse(sys.error(s"fieldAt: no column '$fieldName' in row ${mm.keys.map(anyStr).mkString("[", ",", "]")}")))
      // 3-arg form fieldAt(recv, idx, name): SQL rows are UNORDERED maps with
      // UPPERCASE column labels (Db.query; the [T] type arg is stripped so no
      // case-class decoding happens) — an index is meaningless there, so the
      // emitter also passes the FIELD NAME and we resolve by key,
      // case-insensitively.
      case ForeignV(m: collection.Map[?, ?]) if a.length >= 3
          && m.keysIterator.forall(_.isInstanceOf[String]) =>
        val mm = m.asInstanceOf[collection.Map[String, Value]]
        val fieldName = a(2) match { case StrV(s) => s; case other => Show.show(other) }
        mm.getOrElse(fieldName, sys.error(s"fieldAt: no field '$fieldName' in method-object"))
      case ForeignV(m: collection.Map[?, ?]) if a.length >= 3
          && m.keysIterator.forall(_.isInstanceOf[Value]) =>
        val mm = m.asInstanceOf[collection.Map[Value, Value]]
        val fieldName = a(2) match { case StrV(s) => s; case other => Show.show(other) }
        mm.getOrElse(StrV(fieldName),
          mm.collectFirst { case (StrV(k), v) if k.equalsIgnoreCase(fieldName) => v }
            .getOrElse(sys.error(s"fieldAt: no column '$fieldName' in row ${mm.keys.map(anyStr).mkString("[", ",", "]")}")))
      // Native/plugin result whose case class was NOT imported in this unit:
      // the global field registry has no entry, so route the NAMED access
      // through __method__ structural/plugin dispatch (handles ForeignV/
      // NamedMethodObj by field name) instead of blindly calling asData.
      // (BUGS.md v2-native-result-unregistered-field.) Positional-only form
      // (no name) still falls back to asData for genuine data values.
      case recv if a.length >= 3 =>
        val name = a(2) match { case StrV(s) => s; case other => Show.show(other) }
        methodOp(name, recv, Nil)
      case _ => asData(a(0))._2(int(a, 1).toInt)
    case "__isTag__" => a => a(0) match
      // arity < 0 = "any arity" — used by type-ascription patterns (`case _: T =>`)
      // where the test is on the tag alone, independent of field count.
      case DataV(t, fs) => val ar = int(a, 2).toInt; BoolV(t == str(a, 1) && (ar < 0 || fs.length == ar))
      // Primitive values are not DataV constructors, but source-level typed
      // patterns still use the same portable nominal test (`case s: String`).
      // They are necessarily arity-zero; aliases retain the ScalaScript surface
      // without exposing JVM classes to user code.
      case value =>
        val expected = str(a, 1)
        val arity = int(a, 2).toInt
        val primitive = value match
          case UnitV     => expected == "Unit"
          case BoolV(_)  => expected == "Boolean" || expected == "Bool"
          case IntV(_)   => expected == "Int" || expected == "Long"
          case BigV(_)   => expected == "BigInt"
          case FloatV(_) => expected == "Float" || expected == "Double"
          case StrV(_)   => expected == "String"
          case BytesV(_) => expected == "Bytes"
          case MapV(_)   => expected == "Map"
          case _         => false
        BoolV(primitive && (arity < 0 || arity == 0))
    // Target-neutral insertion-ordered mutable MapV.
    case "map.new"  => _ => MapV.empty
    case "map.get"  => a => asMap(a(0)).get(a(1)).fold(none)(some)
    case "map.put"  => a => asMap(a(0)).update(a(1), a(2)); UnitV
    case "map.has"  => a => BoolV(asMap(a(0)).contains(a(1)))
    case "map.del"  => a => asMap(a(0)).remove(a(1)); UnitV
    case "map.keys" => a => listOf(asMap(a(0)).keys.toSeq)
    case "map.size" => a => IntV(asMap(a(0)).size.toLong)
    // Array (Foreign, growable)
    case "__mk_arr__" => a => ForeignV(collection.mutable.ArrayBuffer.from(a))
    case "arr.new"   => _ => ForeignV(collection.mutable.ArrayBuffer[Value]())
    case "arr.len"   => a => IntV(asArr(a(0)).length.toLong)
    case "arr.get"   => a => asArr(a(0))(int(a, 1).toInt)
    case "arr.set"   => a => asArr(a(0))(int(a, 1).toInt) = a(2); UnitV
    case "arr.push"  => a => asArr(a(0)) += a(1); UnitV
    case "arr.pop"   => a => asArr(a(0)).remove(asArr(a(0)).length - 1)
    case "arr.slice" => a => ForeignV(collection.mutable.ArrayBuffer.from(asArr(a(0)).slice(int(a, 1).toInt, int(a, 2).toInt)))
    // Cell (Foreign, single mutable ref)
    case "__match_fail_prim__" => _ => sys.error("match: no matching case")
    case HandlerDispatchShape.SelectedPrimitive =>
      case event :: Nil => Runtime.handlerDispatchSelected(event)
      case args => sys.error(s"handler dispatch selected: expected 1 event, got ${args.length}")
    case HandlerDispatchShape.MissPrimitive =>
      case event :: Nil => Runtime.handlerDispatchMiss(event)
      case args => sys.error(s"handler dispatch miss: expected 1 event, got ${args.length}")
    case "__mk_method_obj__" => a =>
      val pairs = a.grouped(2).map { case List(StrV(k), v) => k -> v; case g => g(0).toString -> g(1) }.toList
      ForeignV(collection.immutable.Map.from(pairs))
    case "__math_obj__" => _ => ForeignV("__math__")
    case "cell.new" => a => ForeignV(scala.Array[Value](a(0)))
    case "cell.get" => a => asCell(a(0))(0)
    // Safe cell read: a mutable `var` class field is stored as a cell, but the
    // same field name may be a plain field in another class (dynamic dispatch),
    // so external `obj.field` reads unwrap ONLY when the value is actually a cell.
    case "cell.getOr" => a => a(0) match
      case ForeignV(arr: scala.Array[?]) if arr.length == 1 => arr(0).asInstanceOf[Value]
      case v => v
    // Force a `lazy val` field: the field is a cell holding either a
    // `__lazyThunk__(() => init)` (uncomputed) or the memoized value. On first
    // access, run the thunk, cache the result back into the cell, and return it.
    case "__lazyForce__" => a => a(0) match
      case ForeignV(_) =>
        asCell(a(0))(0) match
          case DataV("__lazyThunk__", IndexedSeq(thunk: ClosV)) =>
            val result = callClos(thunk, scala.Array.empty[Value])
            asCell(a(0))(0) = result
            result
          case computed => computed
      case v => v
    // Generic resolve is the path used by Emit.prim* in direct ASM. Keep it
    // effect-safe like resolve2 below: top-level vals are initialized through
    // cell.set, so storing a raw Op would make a following statement observe
    // and print the implementation value before the final unhandled-effect
    // check rejects it.
    case "cell.set" => a => a(1) match
      case op @ DataV("Op", _) => liftOverOp(op, x => { asCell(a(0))(0) = x; UnitV })
      case v => asCell(a(0))(0) = v; UnitV
    // Long cell: mutable long without Value boxing per store (for tight integer loops)
    case "lcell.new" => a => new LongCellV(asInt1(a(0)))
    case "lcell.get" => a => IntV(a(0).asInstanceOf[LongCellV].v)
    case "lcell.set" => a => a(1) match
      case op @ DataV("Op", _) =>
        liftOverOp(op, x => { a(0).asInstanceOf[LongCellV].v = asInt1(x); UnitV })
      case v => a(0).asInstanceOf[LongCellV].v = asInt1(v); UnitV
    // Double cell: mutable double without FloatV boxing per store (tight float loops)
    case "dcell.new" => a => new DoubleCellV(asFloat1(a(0)))
    case "dcell.get" => a => FloatV(a(0).asInstanceOf[DoubleCellV].v)
    case "dcell.set" => a => a(1) match
      case op @ DataV("Op", _) =>
        liftOverOp(op, x => { a(0).asInstanceOf[DoubleCellV].v = asFloat1(x); UnitV })
      case v => a(0).asInstanceOf[DoubleCellV].v = asFloat1(v); UnitV
    // I/O [eff]
    case "io.print"   => a => out(a(0), Console.out); UnitV
    // __regfields__(tag, [names…]) — the native front (ssc1-lower K62.28) registers each
    // case class's field names so runtime by-name field access (e.g. `.head` on a case class
    // with a `head` field) resolves, matching FrontendBridge.registerFieldNames.
    case "__regfields__" => a =>
      val tag = str(a, 0)
      val names = unlistPub(a(1)).map { case StrV(s) => s; case v => anyStr(v) }.toVector
      V2PluginRegistry.registerFieldNames(tag, names)
      UnitV
    case "__regmethod__" => a =>
      // (tag, methodName, closure): a case-class body method. Registered as a tagged
      // method so the existing __method__ dispatch (lookupTaggedMethod) calls it with
      // (self :: args), in place of the default DataV rendering.
      val clos = a(2)
      V2PluginRegistry.registerTaggedMethod(str(a, 0), str(a, 1),
        (args: List[Value]) => clos match {
          case fn: ClosV => callClos(fn, args.toArray)
          case other     => other
        })
      UnitV
    // Atomic string+newline: concurrent actors printing at once must not interleave (else two
    // `println`s produce "abcd\n\n" instead of "ab\ncd\n"). Sync on the shared stream.
    case "io.println" => a => Console.out.synchronized { out(a(0), Console.out); Console.out.println() }; UnitV
    case "io.eprint"  => a => out(a(0), Console.err); UnitV
    case "io.args"   => _ => strList(Runtime.argv)
    case "io.nanoTime"  => _ => IntV(System.nanoTime())
    case "io.readFile"  => a => BytesV(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(str(a, 0))).toVector)
    case "io.exists"    => a => BoolV(java.nio.file.Files.exists(java.nio.file.Path.of(str(a, 0))))
    case "io.writeFile" => a => java.nio.file.Files.write(java.nio.file.Path.of(str(a, 0)), bytes(a, 1).toArray); UnitV
    case "io.env"  => a => sys.env.get(str(a, 0)).fold(none)(s => some(StrV(s)))
    case "io.exit" => a => Runtime.exitHandler(int(a, 0).toInt); UnitV
    // Core IR serialization: a Data-tree (IrProg/IrLam/… built in ssc0) -> canonical bytecode
    case "coreir.encode" => a => StrV(IrEncode.program(a(0)))
    case "coreir.eval"   => a => Runtime.run(
      Compiler.compile(IrDecode.program(a(0))), Array.empty[Value])
    // coreir.decode : Str|Bytes -> IrProg — parse canonical Core IR text back into the IrProg
    // Data tree the tower consumes (inverse of coreir.encode). The text is read through the
    // BOUNDED + VALIDATED kernel Reader, so `#coreir.decode` inherits the same fail-closed
    // rejection of malformed/hostile capsules. Str is the canonical text; Bytes is its UTF-8
    // (so `#coreir.decode(#coreir.encode(v))` and `#coreir.decode(irBytes)` both round-trip).
    // Gives `encode ∘ decode = canonicalize` from .ssc (10-core-ir.md §5, 12-ir-format.md).
    case "coreir.decode" => a => a(0) match
      case StrV(s)   => IrToData.program(Reader.parseProgram(s))
      case BytesV(b) => IrToData.program(Reader.parseProgram(new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8)))
      case x         => sys.error(s"coreir.decode: expected Str|Bytes, got ${Show.show(x)}")
    // ── FrontendBridge collection factories ────────────────────────────────────────
    // Map(k->v, ...) factory: args are Tuple2 pairs (DataV("Tuple2", [k, v]))
    case "__mk_map__" => a =>
      val m = MapV.empty
      a.foreach {
        case DataV("Tuple2" | "Pair", Seq(k, v)) => m.entries(k) = v
        case DataV("->", Seq(k, v))     => m.entries(k) = v
        case pair => sys.error(s"Map factory: expected k->v pair, got ${Show.show(pair)}")
      }
      m
    // ── Dynamic dispatch primitives (for FrontendBridge — no static type info) ────
    // __arith__(op, lhs, rhs): type-dispatched arithmetic/comparison/string concat.
    // Keep this as a thin wrapper: the literal-op fast paths call `arithOp`
    // directly, so duplicating cases here makes literal and non-literal ops
    // diverge when ANF or other rewrites change the shape of the op argument.
    // Typed fast lambda at the resolve seam: the common numeric cases inline
    // here (small, monomorphic, JIT-friendly); everything else defers to the
    // big unified arithOp. Deleting this in a2985d911 cost 25x on fib.
    case "__arith__" => a => arithFast(str(a, 0), a(1), a(2))
    // __unary__(op, val): type-dispatched unary operators
    case "__unary__" => a =>
      val op = str(a, 0)
      a(1) match
        case IntV(n)   => op match { case "-" => IntV(-n); case "~" => IntV(~n); case _ => sys.error(s"__unary__: $op on Int") }
        case FloatV(d) => op match { case "-" => FloatV(-d); case _ => sys.error(s"__unary__: $op on Float") }
        case BoolV(b)  => op match { case "!" => BoolV(!b); case _ => sys.error(s"__unary__: $op on Bool") }
        case v => sys.error(s"__unary__: $op on ${Show.show(v)}")
    // __eq__(a, b): structural equality (works on all Value types including ADTs)
    case "__eq__" => a => liftArith("==", a, BoolV(a(0) == a(1)))
    // __method__(name, receiver, args...): method dispatch on receiver type
    // An APPLIED zero-arg method call `recv.name()`. Identical to __method__ except
    // that it NEVER eta-expands: the user wrote an argument list, so the call must
    // dispatch or fail. The lowerer emits this (instead of __method__) only from the
    // call path, because a bare selection `recv.name` and a call `recv.name()` lower
    // to byte-identical CoreIR — without this split a typo'd zero-arg method silently
    // became the eta-expansion closure and the program printed `<closure>` and exited 0.
    case "__method0__" => a =>
      val recv0 = a(1)
      def missed = sys.error(s"__method__: no dispatch for .${str(a, 0)} on ${Show.show(recv0)}")
      resolve("__method__")(a) match
        // (1) the eta-expansion fallback — `recv.name` as a function value.
        case c: ClosV if c.etaMethodRef != null => missed
        // (2) the missed-method BREADCRUMB. A DataV receiver whose method/field does
        // not resolve yields DataV("Stub", "<tag>.<name>") and keeps going, so a
        // typo'd call on a List/case class computed garbage and exited 0 too. An
        // applied call must not degrade gracefully. A Stub RECEIVER legitimately
        // propagates its existing breadcrumb (that error is already reported at the
        // point the stub was minted), so only a freshly-missed dispatch fails here.
        case DataV("Stub", _) if !isStubV(recv0) => missed
        case v => v
    case "__method__" => a =>
      val name = str(a, 0)
      val recv = a(1)
      val margs = a.drop(2).toList
      (recv, name, margs) match
        // Free-monad lifting: a method ON an unresolved effect Op defers itself
        // into the op's continuation (sequencing through blocks without CPS):
        // Box.read(n).foldLeft(z)(f) => Op(read, n, r -> k(r).foldLeft(z)(f))
        case (DataV("Op", IndexedSeq(l, ag, k)), _, _) =>
          val k2 = ClosV(Array[Value](k), 1, env2 =>
            Done(methodOp(name, runClos1(env2(0).asInstanceOf[ClosV], env2.last), margs)))
          DataV("Op", Vector(l, ag, k2))
        case (value @ DataV(tag, _), method, args)
            if V2PluginRegistry.lookupTaggedMethod(tag, method).isDefined =>
          V2PluginRegistry.lookupTaggedMethod(tag, method).get(value :: args)
        // Types are erased at the Core IR level: asInstanceOf is identity for ANY receiver
        case (v, "asInstanceOf", _)          => v
        // Runtime .copy on a record: the compatibility bridge encodes overrides
        // as (name | #index, value) pairs, while the self-hosted lowerer emits
        // ordinary positional values. The ACTUAL tag's registered field names
        // drive both rebuild forms (the convert-time path only fires when the
        // class is unambiguous).
        case (DataV(tag, fields), "copy", args) =>
          V2PluginRegistry.lookupFieldNames(tag, fields.length) match
            case Some(names) =>
              val explicitNames = args.length % 2 == 0 && args.grouped(2).forall {
                case List(StrV(n), _) => names.contains(n) ||
                  (n.startsWith("#") && n.drop(1).forall(_.isDigit))
                case _ => false
              }
              if explicitNames then
                val overrides = args.grouped(2).collect {
                  case List(StrV(n), v) => n -> v
                }.toMap
                DataV(tag, names.zipWithIndex.map { case (n, i) =>
                  overrides.get(s"#$i").orElse(overrides.get(n))
                    .getOrElse(if i < fields.length then fields(i) else UnitV)
                }.toVector)
              else if args.length <= fields.length then
                DataV(tag, (args ++ fields.drop(args.length)).toVector)
              else DataV("Stub", Vector(StrV(s"$tag.copy")))
            case None => DataV("Stub", Vector(StrV(s"$tag.copy")))
        case (IntV(n), "toString", Nil)      => StrV(n.toString)
        case (IntV(n), "toInt", Nil)         => IntV(n.toInt.toLong)    // truncate to 32-bit
        case (IntV(n), "toLong", Nil)        => IntV(n)
        case (IntV(n), "toByte", Nil)        => IntV(n.toByte.toLong)
        case (IntV(n), "toShort", Nil)       => IntV(n.toShort.toLong)
        // The v2 VM has no Char value type; a char is a single-code-point StrV
        // (same convention as toCharArray / sfromCodes). Returning IntV here made
        // `65.toChar.toString` render "65" (portable-codepoint-string-construction).
        case (IntV(n), "toChar", Nil)        => StrV((n & 0xffffL).toChar.toString)
        case (IntV(n), "toDouble", Nil)      => FloatV(n.toDouble)
        case (IntV(n), "toFloat", Nil)       => FloatV(n.toDouble)
        case (IntV(n), "abs", Nil)           => IntV(math.abs(n))
        // Char operations on a code-point IntV (v2 has no Char box). These are only
        // ever called when the Int IS a char (numbers have no `.toUpper`/`.isDigit`),
        // so treating the value as a char code is unambiguous. `toUpper`/`toLower`
        // return the transformed char CODE (IntV) so `str.map(c => c.toUpper)` renders
        // a String; the predicates return Bool.
        case (IntV(n), "toUpper", Nil)          => IntV(Character.toUpperCase(n.toInt).toLong)
        case (IntV(n), "toLower", Nil)          => IntV(Character.toLowerCase(n.toInt).toLong)
        case (IntV(n), "isDigit", Nil)          => BoolV(Character.isDigit(n.toInt))
        case (IntV(n), "isLetter", Nil)         => BoolV(Character.isLetter(n.toInt))
        case (IntV(n), "isLetterOrDigit", Nil)  => BoolV(Character.isLetterOrDigit(n.toInt))
        case (IntV(n), "isUpper", Nil)          => BoolV(Character.isUpperCase(n.toInt))
        case (IntV(n), "isLower", Nil)          => BoolV(Character.isLowerCase(n.toInt))
        case (IntV(n), "isWhitespace", Nil)     => BoolV(Character.isWhitespace(n.toInt))
        case (FloatV(d), "toString", Nil)    => StrV(Writer.floatStr(d))
        case (FloatV(d), "toInt", Nil)       => IntV(d.toLong)
        case (FloatV(d), "toLong", Nil)      => IntV(d.toLong)
        // Identity: a Float is already floating-point. The native frontend now
        // routes `.toDouble`/`.toFloat` here (it used to no-op them, which broke
        // Int.toDouble → integer division); these keep Float receivers unchanged.
        case (FloatV(d), "toDouble", Nil)    => FloatV(d)
        case (FloatV(d), "toFloat", Nil)     => FloatV(d)
        case (StrV(s), "length", Nil)        => IntV(s.length.toLong)
        case (StrV(s), "size", Nil)          => IntV(s.length.toLong)
        case (StrV(s), "isEmpty", Nil)       => BoolV(s.isEmpty)
        case (StrV(s), "nonEmpty", Nil)      => BoolV(s.nonEmpty)
        // v1 parity: String.toInt is PLAIN (throws on junk) — the FLC fast path
        // already returned a bare Long, so Option-wrapping here made the same
        // program behave differently depending on which path compiled it.
        case (StrV(s), "toInt", Nil)         =>
          try IntV(s.trim.toLong)
          catch case _: NumberFormatException =>
            throw new RecoverableError("String.toInt: invalid integer")
        case (StrV(s), "toIntOption", Nil)   => s.toLongOption.fold(none)(n => some(IntV(n)))
        // v1 semantics: .toDouble is the RAW conversion (throws on junk) —
        // the Option-returning variant is .toDoubleOption, mirroring .toInt.
        case (StrV(s), "toDouble", Nil)       => FloatV(s.trim.toDouble)
        case (StrV(s), "toFloat", Nil)        => FloatV(s.trim.toDouble)
        case (StrV(s), "toDoubleOption", Nil) => s.toDoubleOption.fold(none)(d => some(FloatV(d)))
        case (StrV(s), "trim", Nil)          => StrV(s.trim)
        case (StrV(s), "matchPrefix", List(StrV(pat))) =>
          // v1 DispatchRuntime: regex prefix match (lookingAt) -> Some(matched) | None
          val m = java.util.regex.Pattern.compile(pat).matcher(s)
          if m.lookingAt() then DataV("Some", Vector(StrV(s.substring(0, m.end()))))
          else DataV("None", Vector.empty)
        case (StrV(s), "toUpperCase", Nil)   => StrV(s.toUpperCase)
        case (StrV(s), "toLowerCase", Nil)   => StrV(s.toLowerCase)
        case (StrV(s), "reverse", Nil)       => StrV(s.reverse)
        case (StrV(s), "split", List(StrV(d))) => {
          val parts = s.split(d, -1)
          val nilV: Value = DataV("Nil", IndexedSeq.empty)
          parts.foldRight(nilV)((x, acc) => DataV("Cons", collection.immutable.ArraySeq(StrV(x), acc)))
        }
        // split(delimiter, limit) — the two-arg overload (v2-string-split-limit-overload). limit
        // follows Java/Scala String.split semantics: >0 caps field count (remainder joins the last
        // field), 0 drops trailing empty fields, <0 (e.g. -1) keeps them. busi's identity.ssc TSV
        // parser (readTsv) uses split(delim, -1) on every row so a blank trailing column survives.
        case (StrV(s), "split", List(StrV(d), IntV(limit))) => {
          val parts = s.split(d, limit.toInt)
          val nilV: Value = DataV("Nil", IndexedSeq.empty)
          parts.foldRight(nilV)((x, acc) => DataV("Cons", collection.immutable.ArraySeq(StrV(x), acc)))
        }
        case (StrV(s), "contains", List(StrV(sub))) => BoolV(s.contains(sub))
        // `s.contains('c')` — a Char arg is an IntV code (v2 has no Char box).
        case (StrV(s), "contains", List(IntV(ch))) => BoolV(s.contains(ch.toChar))
        case (StrV(s), "startsWith", List(StrV(pfx))) => BoolV(s.startsWith(pfx))
        case (StrV(s), "startsWith", List(StrV(pfx), IntV(off))) => BoolV(s.startsWith(pfx, off.toInt))
        // Pass each char as its code (IntV), consistent with `charAt`, char literals,
        // and the sibling `filter`/`takeWhile`/`dropWhile` handlers below. Passing a
        // 1-char StrV made `s.forall(_ == 'x')` (StrV vs IntV) always false and broke
        // every char-literal / Char-predicate closure (fence detection, blank-line
        // scans, setext underlines, …).
        case (StrV(s), "forall", List(fn: Value.ClosV)) =>
          BoolV(s.forall(c => callClos(fn, Array(IntV(c.toLong))) == BoolV(true)))
        case (StrV(s), "exists", List(fn: Value.ClosV)) =>
          BoolV(s.exists(c => callClos(fn, Array(IntV(c.toLong))) == BoolV(true)))
        // filter/filterNot are unambiguous — a Char predicate keeps the value a String.
        case (StrV(s), "filter", List(fn: Value.ClosV)) =>
          StrV(s.filter(c => callClos(fn, Array(IntV(c.toLong))) == BoolV(true)))
        case (StrV(s), "filterNot", List(fn: Value.ClosV)) =>
          StrV(s.filterNot(c => callClos(fn, Array(IntV(c.toLong))) == BoolV(true)))
        // map: v2 has NO Char box (chars are IntV), so — unlike v1, which returns a
        // String for a Char=>Char mapper and a List otherwise via static types — we
        // approximate at runtime: if every result is an IntV in 16-bit char range,
        // render a String (the dominant char=>char case, matching v1); otherwise a
        // List. Only divergence vs v1: char=>int-in-range arithmetic (`.map(c => c+1)`
        // yields a String here but a List on v1) — no Char type to tell them apart.
        case (StrV(s), "map", List(fn: Value.ClosV)) =>
          val rs = s.toList.map(c => callClos(fn, Array(IntV(c.toLong))))
          if rs.nonEmpty && rs.forall { case IntV(n) => n >= 0 && n <= 0xFFFF; case _ => false } then
            StrV(rs.iterator.map { case IntV(n) => n.toChar; case _ => ' ' }.mkString)
          else
            listOf(rs)
        case (StrV(s), "endsWith", List(StrV(sfx)))   => BoolV(s.endsWith(sfx))
        case (StrV(s), "take", List(IntV(n)))            => StrV(s.take(n.toInt))
        case (StrV(s), "drop", List(IntV(n)))            => StrV(s.drop(n.toInt))
        case (StrV(s), "takeRight", List(IntV(n)))       => StrV(s.takeRight(n.toInt))
        case (StrV(s), "dropRight", List(IntV(n)))       => StrV(s.dropRight(n.toInt))
        case (StrV(s), "substring", List(IntV(i)))      => StrV(s.substring(i.toInt))
        case (StrV(s), "substring", List(IntV(i), IntV(j))) => StrV(s.substring(i.toInt, j.toInt))
        case (StrV(s), "charAt", List(IntV(i)))         => IntV(s.charAt(i.toInt).toLong)
        // String head/last return a Char (an IntV code, like charAt); tail/init a String.
        case (StrV(s), "head", Nil) if s.nonEmpty       => IntV(s.charAt(0).toLong)
        case (StrV(s), "last", Nil) if s.nonEmpty       => IntV(s.charAt(s.length - 1).toLong)
        case (StrV(s), "headOption", Nil)               => if s.isEmpty then none else some(IntV(s.charAt(0).toLong))
        case (StrV(s), "lastOption", Nil)               => if s.isEmpty then none else some(IntV(s.charAt(s.length - 1).toLong))
        case (StrV(s), "tail", Nil)                     => StrV(s.drop(1))
        case (StrV(s), "init", Nil)                     => StrV(s.dropRight(1))
        case (StrV(s), "indexOf", List(StrV(sub)))      => IntV(s.indexOf(sub).toLong)
        case (StrV(s), "indexOf", List(IntV(ch)))       => IntV(s.indexOf(ch.toInt).toLong)
        case (StrV(s), "indexOf", List(StrV(sub), IntV(from))) => IntV(s.indexOf(sub, from.toInt).toLong)
        case (StrV(s), "indexOf", List(IntV(ch), IntV(from)))  => IntV(s.indexOf(ch.toInt, from.toInt).toLong)
        case (StrV(s), "lastIndexOf", List(StrV(sub)))  => IntV(s.lastIndexOf(sub).toLong)
        case (StrV(s), "lastIndexOf", List(IntV(ch)))   => IntV(s.lastIndexOf(ch.toInt).toLong)
        case (StrV(s), "lastIndexOf", List(StrV(sub), IntV(from))) => IntV(s.lastIndexOf(sub, from.toInt).toLong)
        case (StrV(s), "lastIndexOf", List(IntV(ch), IntV(from)))  => IntV(s.lastIndexOf(ch.toInt, from.toInt).toLong)
        case (StrV(s), "replace",     List(StrV(from), StrV(to))) => StrV(s.replace(from, to))
        case (StrV(s), "replaceAll",  List(StrV(regex), StrV(to))) => StrV(s.replaceAll(regex, to))
        case (StrV(s), "replaceFirst",List(StrV(regex), StrV(to))) => StrV(s.replaceFirst(regex, to))
        case (StrV(s), "matches",     List(StrV(regex))) => BoolV(s.matches(regex))
        case (StrV(s), "padTo",       List(IntV(n), StrV(pad))) => StrV(s.padTo(n.toInt, pad.head))
        case (StrV(s), "stripPrefix", List(StrV(pfx))) => StrV(if s.startsWith(pfx) then s.substring(pfx.length) else s)
        case (StrV(s), "stripSuffix", List(StrV(sfx))) => StrV(if s.endsWith(sfx) then s.substring(0, s.length - sfx.length) else s)
        case (StrV(s), "grouped",     List(IntV(n)))    => listOf(s.grouped(n.toInt).map(StrV(_)).toList)
        case (StrV(s), "linesIterator",  Nil)           => listOf(s.linesIterator.map(StrV(_)).toList)
        case (StrV(s), "linesWithSeparators", Nil)      => listOf(s.linesWithSeparators.map(StrV(_)).toList)
        case (StrV(s), "getBytes",    Nil)              => listOf(s.getBytes.map(b => IntV((b & 0xff).toLong)).toList)
        case (StrV(s), "getBytes",    List(StrV(enc))) =>
          listOf(s.getBytes(enc).map(b => IntV((b & 0xff).toLong)).toList)
        case (StrV(s), "toCharArray", Nil)              => listOf(s.toCharArray.map(c => StrV(c.toString)).toList)
        case (StrV(s), "matchPrefix", List(StrV(pat))) =>
          val m = java.util.regex.Pattern.compile(pat).matcher(s)
          if m.lookingAt() then DataV("Some", Array[Value](StrV(m.group()))) else DataV("None", Array.empty[Value])
        case (StrV(s), "filter",      List(fn: ClosV))  => StrV(s.filter(c => callClos(fn, Array(IntV(c.toLong))) == BoolV(true)))
        // K62.6c-rest: ssc chars are int CODES (`'x'` lowers to an Int), so a string
        // takeWhile/dropWhile predicate receives the char code, not a 1-char string (matches
        // the old _sel_takeWhile scodeAt path now that .takeWhile routes here via __method__).
        case (StrV(s), "takeWhile",   List(fn: ClosV))  => StrV(s.takeWhile(c => callClos(fn, Array(IntV(c.toLong))) == BoolV(true)))
        case (StrV(s), "dropWhile",   List(fn: ClosV))  => StrV(s.dropWhile(c => callClos(fn, Array(IntV(c.toLong))) == BoolV(true)))
        case (StrV(s), "indexWhere",  List(fn: ClosV))  => IntV(s.indexWhere(c => callClos(fn, Array(IntV(c.toLong))) == BoolV(true)).toLong)
        case (StrV(s), "count",       List(fn: ClosV))  => IntV(s.count(c => callClos(fn, Array(IntV(c.toLong))) == BoolV(true)).toLong)
        // ── scala.math object ──────────────────────────────────────────────────────
        case (ForeignV("__math__"), "Pi", Nil)         => FloatV(math.Pi)
        case (ForeignV("__math__"), "E", Nil)          => FloatV(math.E)
        case (ForeignV("__math__"), "abs", List(IntV(x)))   => IntV(math.abs(x))
        case (ForeignV("__math__"), "abs", List(FloatV(x))) => FloatV(math.abs(x))
        case (ForeignV("__math__"), "round", List(FloatV(x))) => IntV(math.round(x))
        case (ForeignV("__math__"), "round", List(IntV(x)))   => IntV(x)
        case (ForeignV("__math__"), "floor", List(FloatV(x))) => FloatV(math.floor(x))
        case (ForeignV("__math__"), "ceil", List(FloatV(x)))  => FloatV(math.ceil(x))
        case (ForeignV("__math__"), "sqrt", List(FloatV(x)))  => FloatV(math.sqrt(x))
        case (ForeignV("__math__"), "sqrt", List(IntV(x)))    => FloatV(math.sqrt(x.toDouble))
        case (ForeignV("__math__"), "pow", List(FloatV(b), FloatV(e)))  => FloatV(math.pow(b, e))
        case (ForeignV("__math__"), "pow", List(IntV(b), IntV(e)))      => FloatV(math.pow(b.toDouble, e.toDouble))
        case (ForeignV("__math__"), "sin", List(FloatV(x)))   => FloatV(math.sin(x))
        case (ForeignV("__math__"), "cos", List(FloatV(x)))   => FloatV(math.cos(x))
        case (ForeignV("__math__"), "tan", List(FloatV(x)))   => FloatV(math.tan(x))
        case (ForeignV("__math__"), "log", List(FloatV(x)))   => FloatV(math.log(x))
        case (ForeignV("__math__"), "log10", List(FloatV(x))) => FloatV(math.log10(x))
        case (ForeignV("__math__"), "exp", List(FloatV(x)))   => FloatV(math.exp(x))
        case (ForeignV("__math__"), "min", List(IntV(a), IntV(b)))      => IntV(math.min(a, b))
        case (ForeignV("__math__"), "max", List(IntV(a), IntV(b)))      => IntV(math.max(a, b))
        case (ForeignV("__math__"), "min", List(FloatV(a), FloatV(b)))  => FloatV(math.min(a, b))
        case (ForeignV("__math__"), "max", List(FloatV(a), FloatV(b)))  => FloatV(math.max(a, b))
        // ── BigInt (BigV) methods ───────────────────────────────────────────────
        case (BigV(n), "pow",          List(IntV(e)))  => BigV(n.pow(e.toInt))
        case (BigV(n), "toBigInt",     Nil)            => BigV(n)
        case (BigV(n), "toInt",        Nil)            => IntV(n.toLong)
        case (BigV(n), "toLong",       Nil)            => IntV(n.toLong)
        case (BigV(n), "toDouble",     Nil)            => FloatV(n.toDouble)
        case (BigV(n), "toFloat",      Nil)            => FloatV(n.toDouble)
        case (BigV(n), "toString",     Nil)            => StrV(n.toString)
        case (BigV(n), "toString",     List(IntV(r)))  => StrV(n.toString(r.toInt))
        case (BigV(n), "abs",          Nil)            => BigV(n.abs)
        case (BigV(n), "min",          List(BigV(m)))  => BigV(n.min(m))
        case (BigV(n), "max",          List(BigV(m)))  => BigV(n.max(m))
        case (BigV(n), "mod",          List(BigV(m)))  => BigV(n.mod(m))
        case (BigV(n), "compare",      List(BigV(m)))  => IntV(n.compare(m).toLong)
        case (BigV(n), "compareTo",    List(BigV(m)))  => IntV(n.compare(m).toLong)
        case (BigV(n), "gcd",          List(BigV(m)))  => BigV(n.gcd(m))
        case (IntV(n), "toBigInt",     Nil)            => BigV(BigInt(n))
        case (IntV(n), "pow",          List(IntV(e)))  => BigV(BigInt(n).pow(e.toInt))
        // ── Portable exact Decimal methods — all delegate to dec.* ──────────
        case (d: DecimalV, "toString" | "toPlainString", Nil) =>
          PortableDecimal.eval("dec.to-string", List(d))
        case (d: DecimalV, "toBigInt" | "toBigInteger", Nil) =>
          PortableDecimal.eval("dec.to-bigint", List(d))
        case (d: DecimalV, "toInt" | "toLong" | "longValue", Nil) =>
          PortableDecimal.eval("dec.to-bigint", List(d)) match
            case BigV(n) => IntV(n.toLong)
            case _       => sys.error("decimal: invalid to-bigint result")
        case (d: DecimalV, "toDouble", Nil) => FloatV(PortableDecimal.toJava(d).doubleValue())
        case (d: DecimalV, "toFloat", Nil)  => FloatV(PortableDecimal.toJava(d).doubleValue())
        case (d: DecimalV, "scale", Nil) => PortableDecimal.eval("dec.scale", List(d))
        case (d: DecimalV, "unscaledValue", Nil) => PortableDecimal.eval("dec.unscaled", List(d))
        case (d: DecimalV, "abs", Nil) => PortableDecimal.eval("dec.abs", List(d))
        case (d: DecimalV, "negate", Nil) => PortableDecimal.eval("dec.negate", List(d))
        case (d: DecimalV, "signum", Nil) => PortableDecimal.eval("dec.signum", List(d))
        case (d: DecimalV, "pow", List(exponent)) => PortableDecimal.eval("dec.pow", List(d, exponent))
        case (d: DecimalV, "compare" | "compareTo", List(other)) =>
          PortableDecimal.eval("dec.compare", List(d, other))
        case (d: DecimalV, "setScale", List(scale)) =>
          PortableDecimal.eval("dec.set-scale", List(d, scale, DataV("RoundingMode", Vector(StrV("HALF_UP")))))
        case (d: DecimalV, "setScale", List(scale, mode)) =>
          PortableDecimal.eval("dec.set-scale", List(d, scale, mode))
        case (d: DecimalV, "round", List(scale)) =>
          PortableDecimal.eval("dec.set-scale", List(d, scale, DataV("RoundingMode", Vector(StrV("HALF_UP")))))
        case (d: DecimalV, "divide", List(other, scale, mode)) =>
          PortableDecimal.eval("dec.div", List(d, other, scale, mode))
        case (IntV(n), "toDecimal", Nil) => PortableDecimal.construct(List(IntV(n)))
        case (BigV(n), "toDecimal", Nil) => PortableDecimal.construct(List(BigV(n)))
        case (DataV("Nil", _), "isEmpty", Nil)  => BoolV(true)
        case (DataV("Cons", _), "isEmpty", Nil) => BoolV(false)
        case (DataV("Nil", _), "nonEmpty", Nil)  => BoolV(false)
        case (DataV("Cons", _), "nonEmpty", Nil) => BoolV(true)
        // Self-hosted CoreIR preserves Scala-style parameter-list currying:
        // `xs.foldLeft(z) { f }` is App(__method__(foldLeft, xs, z), f).
        // Complete that partial call through the SAME full method dispatcher so
        // list/ArrayBuffer selection and foldThreadOp effect semantics stay
        // single-sourced for both the VM and direct ASM closure ABI.
        case (ls, "foldLeft", List(z)) if isList(ls) =>
          ClosV(Runtime.emptyEnv, 1, env => Done(methodOp("foldLeft", ls, List(z, env.last))))
        // Curried z-then-op arms for foldRight/scanLeft (mirror foldLeft): the native
        // front lowers `xs.foldRight(z)(op)` as app(app(sel,[z]),[op]); without this
        // 1-arg arm the z-application had no handler → Stub (never reached the 2-arg arm).
        case (ls, "foldRight", List(z)) if isList(ls) =>
          ClosV(Runtime.emptyEnv, 1, env => Done(methodOp("foldRight", ls, List(z, env.last))))
        case (ls, "scanLeft", List(z)) if isList(ls) =>
          ClosV(Runtime.emptyEnv, 1, env => Done(methodOp("scanLeft", ls, List(z, env.last))))
        // Mutable array (ForeignV(ArrayBuffer)) basics — Array.fill/tabulate
        // return these now (busi qr: g.length on an rs table).
        case (ForeignV(ab: collection.mutable.ArrayBuffer[?]), "length" | "size", Nil) =>
          IntV(ab.length.toLong)
        case (ForeignV(ab: collection.mutable.ArrayBuffer[?]), "isEmpty", Nil) =>
          BoolV(ab.isEmpty)
        case (ForeignV(ab: collection.mutable.ArrayBuffer[?]), "nonEmpty", Nil) =>
          BoolV(ab.nonEmpty)
        case (ForeignV(ab: collection.mutable.ArrayBuffer[?]), "toList", Nil) =>
          listOf(ab.asInstanceOf[collection.mutable.ArrayBuffer[Value]].toList)
        // Array/Map HOFs — same effect-aware traversal as the list HOFs (a real
        // ArrayBuffer flows out of Array.fill since v2-array-companion-list;
        // busi hub folds over such tables at module load).
        case (ForeignV(ab: collection.mutable.ArrayBuffer[?]), "foldLeft", List(z, fn: Value.ClosV)) =>
          foldThreadOp(ab.asInstanceOf[collection.mutable.ArrayBuffer[Value]].toList, z,
            (acc, x) => callClos(fn, Array(acc, x)))
        case (ForeignV(ab: collection.mutable.ArrayBuffer[?]), "map", List(fn: Value.ClosV)) =>
          mapThreadOp(ab.asInstanceOf[collection.mutable.ArrayBuffer[Value]].toList,
            x => callClos(fn, Array(x)),
            rs => ForeignV(collection.mutable.ArrayBuffer.from(rs)))
        case (ForeignV(ab: collection.mutable.ArrayBuffer[?]), "foreach", List(fn: Value.ClosV)) =>
          mapThreadOp(ab.asInstanceOf[collection.mutable.ArrayBuffer[Value]].toList,
            x => callClos(fn, Array(x)), _ => UnitV)
        case (MapV(entries), "foldLeft", List(z, fn: Value.ClosV)) =>
          foldThreadOp(entries.toList
              .map((k, v) => DataV("Tuple2", collection.immutable.ArraySeq(k, v))), z,
            (acc, x) => callClos(fn, Array(acc, x)))
        case (ForeignV(m: collection.mutable.Map[?, ?]), "foldLeft", List(z, fn: Value.ClosV)) =>
          foldThreadOp(m.asInstanceOf[collection.mutable.Map[Value, Value]].toList
              .map((k, v) => DataV("Tuple2", collection.immutable.ArraySeq(k, v))), z,
            (acc, x) => callClos(fn, Array(acc, x)))
        case (DataV("Nil", _), "length", Nil) | (DataV("Nil", _), "size", Nil) =>
          IntV(unlist(recv).length.toLong)
        case (DataV("Cons", _), "length", Nil) | (DataV("Cons", _), "size", Nil) =>
          IntV(unlist(recv).length.toLong)
        // Exception value (`try … catch { case e: RuntimeException => e.getMessage }`): the caught
        // value is DataV("<...>Exception"/"<...>Error", [message]); getMessage returns the message.
        case (DataV(tag, IndexedSeq(msg)), "getMessage", Nil)
            if tag.endsWith("Exception") || tag.endsWith("Error") => msg
        case (DataV(tag, IndexedSeq(msg)), "getLocalizedMessage", Nil)
            if tag.endsWith("Exception") || tag.endsWith("Error") => msg
        case (DataV("Nil", _), "head", Nil)  => sys.error("head on empty list")
        case (DataV("Cons", f), "head", Nil) => f(0)
        case (DataV("Cons", f), "tail", Nil) => f(1)
        case (DataV("Nil", _), "tail", Nil)  => sys.error("tail on empty list")
        // ── List HOFs (DataV Cons/Nil linked list) ──────────────────────────────
        // Tuple-spreading: a multi-param lambda `(a, b) => …` on a list of tuples
        // gets called with the tuple fields spread as separate args.
        case (ls, "map", List(fn: Value.ClosV)) if isList(ls) =>
          def step(x: Value): Value =
            if fn.arity > 1 then x match
              case DataV(tag, fs) if (tag == "Pair" || tag.startsWith("Tuple")) && fs.length == fn.arity =>
                callClos(fn, fs.toArray)
              case _ => callClos(fn, Array(x))
            else callClos(fn, Array(x))
          mapThreadOp(unlist(ls), step, listOf)
        case (ls, "flatMap", List(fn: Value.ClosV)) if isList(ls) =>
          def step(x: Value): Value =
            if fn.arity > 1 then x match
              case DataV(tag, fs) if (tag == "Pair" || tag.startsWith("Tuple")) && fs.length == fn.arity =>
                callClos(fn, fs.toArray)
              case _ => callClos(fn, Array(x))
            else callClos(fn, Array(x))
          mapThreadOp(unlist(ls), step, rs => listOf(rs.flatMap {
            case r2 @ (DataV("Cons", _) | DataV("Nil", _)) => unlist(r2)
            case scalar => List(scalar)
          }))
        case (ls, "filter", List(fn: Value.ClosV)) if isList(ls) =>
          val xs = unlist(ls)
          mapThreadOp(xs, x => callClos(fn, Array(x)),
            rs => listOf(xs.zip(rs).collect { case (x, Value.BoolV(true)) => x }))
        case (ls, "filterNot", List(fn: Value.ClosV)) if isList(ls) =>
          val xs = unlist(ls)
          mapThreadOp(xs, x => callClos(fn, Array(x)),
            rs => listOf(xs.zip(rs).collect { case (x, r) if r != Value.BoolV(true) => x }))
        case (ls, "foldLeft", List(z, fn: Value.ClosV)) if isList(ls) =>
          foldThreadOp(unlist(ls), z, (acc, x) => callClos(fn, Array(acc, x)))
        case (ls, "foldRight", List(z, fn: Value.ClosV)) if isList(ls) =>
          foldThreadOp(unlist(ls).reverse, z, (acc, x) => callClos(fn, Array(x, acc)))
        case (ls, "foreach", List(fn: Value.ClosV)) if isList(ls) =>
          foreachConsOp(ls, x => callClos(fn, Array(x)))
        case (ls, "find", List(fn: Value.ClosV)) if isList(ls) =>
          unlist(ls).find(x => callClos(fn, Array(x)) == Value.BoolV(true)) match
            case Some(v) => some(v); case None => none
        case (ls, "exists", List(fn: Value.ClosV)) if isList(ls) =>
          BoolV(unlist(ls).exists(x => callClos(fn, Array(x)) == Value.BoolV(true)))
        case (ls, "forall", List(fn: Value.ClosV)) if isList(ls) =>
          BoolV(unlist(ls).forall(x => callClos(fn, Array(x)) == Value.BoolV(true)))
        case (ls, "count", List(fn: Value.ClosV)) if isList(ls) =>
          IntV(unlist(ls).count(x => callClos(fn, Array(x)) == Value.BoolV(true)).toLong)
        case (ls, "sortBy", List(fn: Value.ClosV)) if isList(ls) =>
          listOf(unlist(ls).sortBy(x => callClos(fn, Array(x)))(valueOrdering))
        case (ls, "sortWith", List(fn: Value.ClosV)) if isList(ls) =>
          listOf(unlist(ls).sortWith((a, b) => callClos(fn, Array(a, b)) == Value.BoolV(true)))
        case (ls, "groupBy", List(fn: Value.ClosV)) if isList(ls) =>
          val groups = unlist(ls).groupBy(x => callClos(fn, Array(x)))
          val pairs = groups.map { case (k, vs) => DataV("Tuple2", collection.immutable.ArraySeq(k, listOf(vs))) }.toList
          listOf(pairs)
        case (ls, "zip", List(other)) if isList(ls) =>
          listOf(unlist(ls).zip(unlist(other)).map { case (a, b) => DataV("Tuple2", collection.immutable.ArraySeq(a, b)) })
        case (ls, "zipWithIndex", Nil) if isList(ls) =>
          listOf(unlist(ls).zipWithIndex.map { case (a, i) => DataV("Tuple2", collection.immutable.ArraySeq(a, IntV(i.toLong))) })
        case (ls, "take", List(IntV(n))) if isList(ls) => listOf(unlist(ls).take(n.toInt))
        case (ls, "drop", List(IntV(n))) if isList(ls) => listOf(unlist(ls).drop(n.toInt))
        case (ls, "takeRight", List(IntV(n))) if isList(ls) => listOf(unlist(ls).takeRight(n.toInt))
        case (ls, "dropRight", List(IntV(n))) if isList(ls) => listOf(unlist(ls).dropRight(n.toInt))
        case (ls, "indices", Nil) if isList(ls) => listOf(unlist(ls).indices.map(i => IntV(i.toLong): Value))
        case (ls, "slice", List(IntV(a), IntV(b))) if isList(ls) => listOf(unlist(ls).slice(a.toInt, b.toInt))
        case (ls, "updated", List(IntV(i), x)) if isList(ls) => listOf(unlist(ls).updated(i.toInt, x))
        case (ls, "patch", List(IntV(from), repl, IntV(rep))) if isList(ls) =>
          listOf(unlist(ls).patch(from.toInt, unlist(repl), rep.toInt))
        case (ls, "takeWhile", List(fn: Value.ClosV)) if isList(ls) =>
          listOf(unlist(ls).takeWhile(x => callClos(fn, Array(x)) == Value.BoolV(true)))
        case (ls, "dropWhile", List(fn: Value.ClosV)) if isList(ls) =>
          listOf(unlist(ls).dropWhile(x => callClos(fn, Array(x)) == Value.BoolV(true)))
        case (ls, "flatten", Nil) if isList(ls) => listOf(unlist(ls).flatMap(unlist))
        case (ls, "reverse", Nil) if isList(ls) => listOf(unlist(ls).reverse)
        case (ls, "distinct", Nil) if isList(ls) => listOf(unlist(ls).distinct)
        case (ls, "grouped", List(IntV(n))) if isList(ls) =>
          listOf(unlist(ls).grouped(n.toInt).map(listOf).toList)
        case (ls, "last", Nil) if isList(ls) => unlist(ls).last
        case (ls, "init", Nil) if isList(ls) => listOf(unlist(ls).init)
        case (ls, "headOption", Nil) if isList(ls) => unlist(ls).headOption.fold(none)(some)
        case (ls, "lastOption", Nil) if isList(ls) => unlist(ls).lastOption.fold(none)(some)
        case (ls, "indexWhere", List(fn: Value.ClosV)) if isList(ls) =>
          IntV(unlist(ls).indexWhere(x => callClos(fn, Array(x)) == Value.BoolV(true)).toLong)
        case (ls, "span", List(fn: Value.ClosV)) if isList(ls) =>
          val (a, b) = unlist(ls).span(x => callClos(fn, Array(x)) == Value.BoolV(true))
          DataV("Tuple2", collection.immutable.ArraySeq(listOf(a), listOf(b)))
        case (ls, "sliding", List(IntV(n))) if isList(ls) =>
          listOf(unlist(ls).sliding(n.toInt).map(listOf).toList)
        case (ls, "scanLeft", List(z, fn: Value.ClosV)) if isList(ls) =>
          listOf(unlist(ls).scanLeft(z)((acc, x) => callClos(fn, Array(acc, x))))
        case (ls, "sum", Nil) if isList(ls) =>
          unlist(ls).foldLeft[Value](IntV(0)) {
            case (IntV(a), IntV(b)) => IntV(a + b)
            case (FloatV(a), FloatV(b)) => FloatV(a + b)
            case (IntV(a), FloatV(b)) => FloatV(a + b)
            case (FloatV(a), IntV(b)) => FloatV(a + b)
            case (a, b) => sys.error(s"sum: cannot add ${Show.show(a)} and ${Show.show(b)}")
          }
        // K62.31: reduce / reduceLeft / reduceRight on a list (List or ArrayBuffer). The native
        // front routes `.reduce(f)` to __method__ (not a _sel_ prelude helper), and there was no
        // handler, so it returned a silent Stub.
        case (ls, "reduce" | "reduceLeft", List(fn: Value.ClosV)) if isList(ls) =>
          val xs = unlist(ls)
          if xs.isEmpty then sys.error("reduceLeft on empty list")
          else xs.reduceLeft((a, b) => callClos(fn, Array(a, b)))
        case (ls, "reduceRight", List(fn: Value.ClosV)) if isList(ls) =>
          val xs = unlist(ls)
          if xs.isEmpty then sys.error("reduceRight on empty list")
          else xs.reduceRight((a, b) => callClos(fn, Array(a, b)))
        case (ls, "max", Nil) if isList(ls) => unlist(ls).max(valueOrdering)
        case (ls, "min", Nil) if isList(ls) => unlist(ls).min(valueOrdering)
        case (ls, "maxBy", List(fn: Value.ClosV)) if isList(ls) =>
          unlist(ls).maxBy(x => callClos(fn, Array(x)))(valueOrdering)
        case (ls, "minBy", List(fn: Value.ClosV)) if isList(ls) =>
          unlist(ls).minBy(x => callClos(fn, Array(x)))(valueOrdering)
        case (ls, "sorted", Nil) if isList(ls) => listOf(unlist(ls).sorted(valueOrdering))
        case (ls, "sortWith", List(fn: Value.ClosV)) if isList(ls) =>
          listOf(unlist(ls).sortWith((a, b) => callClos(fn, Array(a, b)) == BoolV(true)))
        case (ls, "distinct", Nil) if isList(ls) => listOf(unlist(ls).distinct)
        case (ls, "flatten", Nil) if isList(ls) => listOf(unlist(ls).flatMap(x => unlist(x)))
        case (ls, "zip", List(rs)) if isList(ls) =>
          listOf(unlist(ls).zip(unlist(rs)).map { case (a, b) => DataV("Tuple2", collection.immutable.ArraySeq(a, b)) })
        case (ls, "mkString", Nil) if isList(ls) =>
          StrV(unlist(ls).map(anyStr).mkString)
        case (ls, "mkString", List(StrV(sep))) if isList(ls) =>
          StrV(unlist(ls).map(anyStr).mkString(sep))
        case (ls, "mkString", List(StrV(pre), StrV(sep), StrV(post))) if isList(ls) =>
          StrV(unlist(ls).map(anyStr).mkString(pre, sep, post))
        case (ls, "toList", Nil) if isList(ls) => ls
        case (ls, "iterator", Nil) if isList(ls) => ls  // list ops work on the Cons-list directly
        case (ls, "toSet", Nil) if isList(ls) =>
          listOf(unlist(ls).distinct)  // approximate set as distinct list
        case (ls, "toVector", Nil) if isList(ls) => ls
        case (ls, "contains", List(v)) if isList(ls) => BoolV(unlist(ls).contains(v))
        case (ls, "indexOf", List(v)) if isList(ls) => IntV(unlist(ls).indexOf(v).toLong)
        case (ls, "+:", List(v)) if isList(ls) => DataV("Cons", collection.immutable.ArraySeq(v, ls))
        case (ls, ":+" | "appended", List(v)) if isList(ls) => listOf(unlist(ls) :+ v)
        case (ls, "++", List(other)) if isList(ls) => listOf(unlist(ls) ++ unlist(other))
        case (ls, "splitAt", List(IntV(n))) if isList(ls) =>
          val (a, b) = unlist(ls).splitAt(n.toInt)
          DataV("Tuple2", collection.immutable.ArraySeq(listOf(a), listOf(b)))
        case (ls, "partition", List(fn: Value.ClosV)) if isList(ls) =>
          val (yes, no) = unlist(ls).partition(x => callClos(fn, Array(x)) == Value.BoolV(true))
          DataV("Tuple2", collection.immutable.ArraySeq(listOf(yes), listOf(no)))
        // ── Tuple accessors ──────────────────────────────────────────────────────
        // EXPRESSION-position effects: a method call on an un-handled Op lifts
        // over it — Op(l, a, k).m(args) becomes Op(l, a, x -> k(x).m(args)), so
        // `acc + Bump.tick().toLong` reaches the handler and THEN applies
        // .toLong to the resumed value (statement/binding positions are
        // covered by seqThreadOp/letThreadOp; this is the operand case).
        case (DataV("Op", IndexedSeq(l, arg, k)), mname, margs) =>
          val kc = k.asInstanceOf[ClosV]
          val k2 = ClosV(Runtime.emptyEnv, 1, env2 => {
            val resumed = Runtime.run(kc.code, if kc.env.isEmpty then Array(env2.last) else Runtime.extend(kc.env, Array(env2.last)))
            Done(resolve("__method__")(StrV(mname) :: resumed :: margs))
          })
          DataV("Op", Vector(l, arg, k2))
        case (DataV("Stub", _), n, _) if n.matches("_\\d+") || n == "fieldAt" =>
          DataV("Stub", Vector.empty)  // stub tuple/field accessor
        case (DataV(tag, fields), n, Nil) if tag.startsWith("Tuple") && n.matches("_\\d+") =>
          fields(n.drop(1).toInt - 1)
        case (DataV("Mirror", fields), "label", Nil)      => fields(0)
        case (DataV("Mirror", fields), "elemLabels", Nil) => fields(1)
        case (DataV("Mirror", fields), "elemTypes", Nil)  => fields(2)
        case (DataV(_, fields), "fieldAt", List(IntV(i))) => fields(i.toInt)
        // Restricted quoted-macro interpreter helpers. PluginBridge registers
        // the Expr/QuotedContext globals; the VM owns the method dispatch shape.
        case (DataV("Expr", fields), "asValue", Nil) =>
          fields.lift(1) match
            case Some(DataV("None", _)) | None => none
            case Some(v)                       => some(v)
        case (DataV("Expr", fields), "asTerm", Nil) =>
          DataV("ScalaScriptTerm", Vector(
            fields.headOption.getOrElse(StrV("<expr>")),
            fields.lift(1).getOrElse(UnitV)
          ))
        case (DataV("Expr", fields), "toString", Nil) =>
          StrV(s"Expr(${anyStr(fields.headOption.getOrElse(StrV("<expr>")))})")
        case (DataV("ScalaScriptTerm", fields), "name", Nil) =>
          fields.headOption.getOrElse(StrV("<expr>"))
        case (DataV("ScalaScriptTerm", fields), "value", Nil) =>
          fields.lift(1).getOrElse(UnitV)
        case (DataV("AgentSchemaInstance", fields), "decode", List(argsJson)) =>
          fields.lift(1) match
            case Some(fn: ClosV) => callClos(fn, Array(argsJson))
            case _               => DataV("Stub", Vector(StrV("AgentSchemaInstance.decode")))
        // ── Option methods ───────────────────────────────────────────────────────
        case (DataV("Some", Seq(v)), "get", Nil) => v
        case (DataV("None", _), "get", Nil) => sys.error("None.get")
        case (DataV("Some", _), "isEmpty", Nil)  => BoolV(false)
        case (DataV("None", _), "isEmpty", Nil)  => BoolV(true)
        case (DataV("Some", _), "isDefined", Nil) => BoolV(true)
        case (DataV("None", _), "isDefined", Nil) => BoolV(false)
        case (DataV("Some", Seq(v)), "getOrElse", List(_)) => v
        case (DataV("None", _), "getOrElse", List(d)) => d
        case (DataV("Some", Seq(v)), "map", List(fn: Value.ClosV)) => some(callClos(fn, Array(v)))
        case (DataV("None", _), "map", List(_)) => none
        case (DataV("Some", Seq(v)), "flatMap", List(fn: Value.ClosV)) => callClos(fn, Array(v))
        case (DataV("None", _), "flatMap", List(_)) => none
        case (DataV("Some", Seq(v)), "filter", List(fn: Value.ClosV)) =>
          if callClos(fn, Array(v)) == BoolV(true) then some(v) else none
        case (DataV("None", _), "filter", List(_)) => none
        case (DataV("Some", Seq(v)), "foreach", List(fn: Value.ClosV)) =>
          callClos(fn, Array(v)); UnitV
        case (DataV("None", _), "foreach", List(_)) => UnitV
        case (DataV("Some", Seq(v)), "orElse", List(_)) => some(v)
        case (DataV("None", _), "orElse", List(other)) => other
        case (DataV("Some", Seq(v)), "toList", Nil) => listOf(Seq(v))
        case (DataV("None", _), "toList", Nil) => listOf(Seq.empty)
        case (DataV("Some", Seq(v)), "fold", List(_, fn: Value.ClosV)) => callClos(fn, Array(v))
        case (DataV("None", _), "fold", List(default, _)) => default
        // exists/forall/contains/nonEmpty — were unhandled (None.exists → Op,
        // Some.exists → Stub); idiomatic `identity.exists(hasRole)` auth checks
        // dispatch here now (busi v2-option-exists). Match the list-method idiom.
        case (DataV("Some", Seq(v)), "exists", List(fn: Value.ClosV)) =>
          BoolV(callClos(fn, Array(v)) == BoolV(true))
        case (DataV("None", _), "exists", List(_)) => BoolV(false)
        case (DataV("Some", Seq(v)), "forall", List(fn: Value.ClosV)) =>
          BoolV(callClos(fn, Array(v)) == BoolV(true))
        case (DataV("None", _), "forall", List(_)) => BoolV(true)
        case (DataV("Some", Seq(v)), "contains", List(x)) => BoolV(v == x)
        case (DataV("None", _), "contains", List(_)) => BoolV(false)
        case (DataV("Some", _), "nonEmpty", Nil) => BoolV(true)
        case (DataV("None", _), "nonEmpty", Nil) => BoolV(false)
        // ── Either methods ───────────────────────────────────────────────────────
        case (DataV("Bench", _), "opaque", List(v)) => v
        case (DataV("BenchObj", _), "opaque", List(v)) => v
        // Seq/List/Vector/Map companion-object factories (recv = DataV(compName, _))
        case (DataV(t, _), "empty", Nil) if t == "List" || t == "Seq" || t == "Vector" => listOf(Seq.empty)
        case (DataV("Map", _), "empty", Nil) => MapV.empty
        // Array companion statics return a REAL mutable array (ForeignV(ArrayBuffer)) —
        // they were folded into the List lane and `Array.fill(512)(0)` came back a
        // Cons-list, so the first `m(i) = v` hit arr.set with "expected Array, got
        // List" (busi qr.ssc: every rs/gf table). Indexed App reads on ArrayBuffer
        // already work (compile fast path + applyFallback).
        case (DataV("Array", _), "tabulate", List(IntV(n), fn: Value.ClosV)) =>
          ForeignV(collection.mutable.ArrayBuffer.from((0 until n.toInt).map(i => callClos(fn, Array(IntV(i.toLong))))))
        case (DataV("Array", _), "fill", List(IntV(n), elem)) =>
          ForeignV(collection.mutable.ArrayBuffer.fill(n.toInt)(elem))
        case (DataV("List" | "Array" | "Seq" | "Vector", _), "tabulate", List(IntV(n), fn: Value.ClosV)) =>
          listOf((0 until n.toInt).map(i => callClos(fn, Array(IntV(i.toLong)))))
        case (DataV("List" | "Array" | "Seq" | "Vector", _), "fill", List(IntV(n), elem)) =>
          listOf(Seq.fill(n.toInt)(elem))
        case (DataV("List" | "Array" | "Seq" | "Vector", _), "empty", Nil) =>
          listOf(Seq.empty)
        case (DataV("List" | "Array" | "Seq" | "Vector", _), "range", List(IntV(from), IntV(to))) =>
          listOf((from until to).map(i => IntV(i): Value))
        case (DataV("List" | "Array" | "Seq" | "Vector", _), "range", List(IntV(from), IntV(to), IntV(step))) =>
          listOf((from until to by step).map(i => IntV(i): Value))
        // LazyList
        case (DataV("LazyList", _), "from", List(IntV(n))) =>
          ForeignV(LazyList.from(n.toInt).map(i => IntV(i.toLong): Value))
        case (DataV("LazyList", _), "from", List(IntV(n), IntV(step))) =>
          ForeignV(LazyList.iterate(n)(x => x + step).map(i => IntV(i): Value))
        case (ForeignV(ll: LazyList[?]), "map", List(fn: Value.ClosV)) =>
          ForeignV(ll.asInstanceOf[LazyList[Value]].map(v => callClos(fn, Array(v))))
        case (ForeignV(ll: LazyList[?]), "filter", List(fn: Value.ClosV)) =>
          ForeignV(ll.asInstanceOf[LazyList[Value]].filter(v => callClos(fn, Array(v)) match { case BoolV(b) => b; case _ => false }))
        case (ForeignV(ll: LazyList[?]), "take", List(IntV(n))) =>
          listOf(ll.asInstanceOf[LazyList[Value]].take(n.toInt))
        case (ForeignV(ll: LazyList[?]), "sum", Nil) =>
          ll.asInstanceOf[LazyList[Value]].foldLeft(0L) { case (acc, IntV(i)) => acc + i; case (acc, _) => acc } match
            case s => IntV(s)
        case (ForeignV(ll: LazyList[?]), "toList", Nil) =>
          listOf(ll.asInstanceOf[LazyList[Value]].toList)
        case (DataV("Right", Seq(v)), "map", List(fn: Value.ClosV)) => DataV("Right", collection.immutable.ArraySeq(callClos(fn, Array(v))))
        case (DataV("Left", _), "map", List(_)) => recv
        case (DataV("Right", Seq(v)), "flatMap", List(fn: Value.ClosV)) => callClos(fn, Array(v))
        case (DataV("Left", _), "flatMap", List(_)) => recv
        case (DataV("Right", Seq(v)), "fold", List(_, fn: Value.ClosV)) => callClos(fn, Array(v))
        case (DataV("Left",  Seq(v)), "fold", List(fn: Value.ClosV, _)) => callClos(fn, Array(v))
        case (DataV("Right", Seq(v)), "getOrElse", List(_)) => v
        case (DataV("Left",  _), "getOrElse", List(d)) => d
        case (DataV("Right", _), "isRight", Nil) => BoolV(true)
        case (DataV("Left",  _), "isRight", Nil) => BoolV(false)
        case (DataV("Right", _), "isLeft",  Nil) => BoolV(false)
        case (DataV("Left",  _), "isLeft",  Nil) => BoolV(true)
        case (DataV("Right", Seq(v)), "toOption", Nil) => some(v)
        case (DataV("Left",  _), "toOption", Nil) => none
        // ── Map/HashMap ──────────────────────────────────────────────────────────
        case (MapV(m), "size", Nil) => IntV(m.size.toLong)
        case (MapV(m), "get", List(k)) => m.get(k).fold(none)(some)
        case (MapV(m), "getOrElse", List(k, default)) => m.getOrElse(k, default)
        case (MapV(m), "apply", List(k)) => m(k)
        case (MapV(m), "updated", List(k, v)) =>
          val out = MapV.from(m)
          out.entries.update(k, v)
          out
        case (MapV(m), "removed", List(k)) =>
          val out = MapV.from(m)
          out.entries.remove(k)
          out
        case (MapV(m), "+", List(DataV("Tuple2" | "Pair", Seq(k, v)))) =>
          val out = MapV.from(m)
          out.entries.update(k, v)
          out
        case (MapV(m), "contains", List(k)) => BoolV(m.contains(k))
        case (MapV(m), "isEmpty", Nil) => BoolV(m.isEmpty)
        case (MapV(m), "nonEmpty", Nil) => BoolV(m.nonEmpty)
        case (MapV(m), "keys", Nil) => listOf(m.keys.toSeq)
        case (MapV(m), "values", Nil) => listOf(m.values.toSeq)
        case (MapV(m), "toList", Nil) =>
          listOf(m.toList.map { case (k, v) =>
            DataV("Tuple2", collection.immutable.ArraySeq(k, v))
          })
        case (ForeignV(m: collection.mutable.Map[?, ?]), "size", Nil) =>
          IntV(m.size.toLong)
        case (ForeignV(m: collection.mutable.Map[?, ?]), "get", List(k)) =>
          m.asInstanceOf[collection.mutable.Map[Value,Value]].get(k) match
            case Some(v) => some(v); case None => none
        case (ForeignV(m: collection.mutable.Map[?, ?]), "getOrElse", List(k, default)) =>
          m.asInstanceOf[collection.mutable.Map[Value,Value]].getOrElse(k, default)
        case (ForeignV(m: collection.mutable.Map[?, ?]), "apply", List(k)) =>
          m.asInstanceOf[collection.mutable.Map[Value,Value]](k)
        case (ForeignV(m: collection.mutable.Map[?, ?]), "updated", List(k, v)) =>
          val nm = m.asInstanceOf[collection.mutable.Map[Value,Value]].clone()
          nm.update(k, v); ForeignV(nm)
        case (ForeignV(m: collection.mutable.Map[?, ?]), "removed", List(k)) =>
          val nm = m.asInstanceOf[collection.mutable.Map[Value,Value]].clone()
          nm.remove(k); ForeignV(nm)
        case (ForeignV(m: collection.mutable.Map[?, ?]), "+", List(DataV("Tuple2" | "Pair", Seq(k, v)))) =>
          val nm = m.asInstanceOf[collection.mutable.Map[Value,Value]].clone()
          nm.update(k, v); ForeignV(nm)
        case (ForeignV(m: collection.mutable.Map[?, ?]), "contains", List(k)) =>
          BoolV(m.asInstanceOf[collection.mutable.Map[Value,Value]].contains(k))
        case (ForeignV(m: collection.mutable.Map[?, ?]), "isEmpty", Nil) =>
          BoolV(m.isEmpty)
        case (ForeignV(m: collection.mutable.Map[?, ?]), "nonEmpty", Nil) =>
          BoolV(m.nonEmpty)
        case (ForeignV(m: collection.mutable.Map[?, ?]), "keys", Nil) =>
          listOf(m.asInstanceOf[collection.mutable.Map[Value,Value]].keys.toSeq)
        case (ForeignV(m: collection.mutable.Map[?, ?]), "values", Nil) =>
          listOf(m.asInstanceOf[collection.mutable.Map[Value,Value]].values.toSeq)
        case (ForeignV(m: collection.mutable.Map[?, ?]), "toList", Nil) =>
          listOf(m.asInstanceOf[collection.mutable.Map[Value,Value]].toList.map {
            case (k, v) => DataV("Tuple2", collection.immutable.ArraySeq(k, v))
          })
        // ── Signal/cell — ForeignV(Array[Value]) with .get/.set/.update ──────────
        case (ForeignV(arr: Array[?]), "get", Nil) =>
          val rh = Runtime.signalReadHook.get(); if rh != null then rh(arr)
          arr.asInstanceOf[Array[Value]](0)
        // Row-style field access on a map value: `row.text` where the row is a
        // ForeignV(Map) (Db.query rows; v2 strips the [Todo] type arg so no
        // case-class decoding happens). Look the field up by KEY name, with a
        // case-insensitive fallback for SQL column labels (H2 upper-cases).
        // Placed AFTER the named map METHODS above so get/keys/… keep their
        // Scala semantics.
        case (MapV(mm), fieldName, Nil) if mm.keysIterator.forall(_.isInstanceOf[StrV]) =>
          mm.getOrElse(StrV(fieldName),
            mm.collectFirst { case (StrV(k), v) if k.equalsIgnoreCase(fieldName) => v }
              .getOrElse(sys.error(s"__method__: no column '$fieldName' in row ${mm.keys.map(anyStr).mkString("[", ",", "]")}")))
        case (ForeignV(m: collection.Map[?, ?]), fieldName, Nil)
            // Guard on the UNCAST keys: casting first made the forall lambda
            // cast String keys (method-objects) to Value → CCE inside forall.
            if m.keysIterator.forall(k => k.isInstanceOf[StrV]) =>
          val mm = m.asInstanceOf[collection.Map[Value, Value]]
          mm.getOrElse(StrV(fieldName),
            mm.collectFirst { case (StrV(k), v) if k.equalsIgnoreCase(fieldName) => v }
              .getOrElse(sys.error(s"__method__: no column '$fieldName' in row ${mm.keys.map(anyStr).mkString("[", ",", "]")}")))
        case (ForeignV(arr: Array[?]), "set", List(v)) =>
          arr.asInstanceOf[Array[Value]](0) = v
          val wh = Runtime.signalWriteHook; if wh != null then wh(arr)
          UnitV
        case (ForeignV(arr: Array[?]), "update", List(fn: ClosV)) =>
          val cur = arr.asInstanceOf[Array[Value]](0)
          arr.asInstanceOf[Array[Value]](0) = callClos(fn, Array(cur))
          val wh = Runtime.signalWriteHook; if wh != null then wh(arr)
          UnitV
        case (ForeignV(arr: Array[?]), "apply", Nil) =>
          val rh = Runtime.signalReadHook.get(); if rh != null then rh(arr)
          arr.asInstanceOf[Array[Value]](0)
        // ── Plugin-owned named-field objects (e.g. oauth AuthServer) ─────────────
        case (ForeignV(obj: NamedMethodObj), _, _) =>
          obj.getField(name) match
            case Some(fn: ClosV) => callClos(fn, margs.toArray)
            case Some(v) if margs.isEmpty => v
            case other =>
              V2PluginRegistry.lookup(s"__method__.$name") match
                case Some(fn) => fn(a)
                case None => sys.error(s"__method__: no field '$name' on named-method-obj ($other)")
        // ── Method object (given/typeclass instances) ─────────────────────────────
        case (ForeignV(m: collection.immutable.Map[?, ?]), _, _) =>
          val mm = m.asInstanceOf[collection.immutable.Map[String, Value]]
          mm.get(name) match
            case Some(fn: ClosV) if margs.isEmpty && fn.arity > 0 => fn  // eta-expand
            case Some(fn: ClosV) => callClos(fn, margs.toArray)
            case Some(v) if margs.isEmpty => v
            // When margs non-empty and value is not a ClosV, try calling with args
            case Some(v: ForeignV) =>
              V2PluginRegistry.lookup(s"__method__.$name") match
                case Some(fn) => fn(a)
                case None => sys.error(s"__method__: no method '$name' in method-object")
            case _ =>
              // Fall through to plugin registry before erroring
              V2PluginRegistry.lookup(s"__method__.$name") match
                case Some(fn) => fn(a)
                case None => sys.error(s"__method__: no method '$name' in method-object")
        // Function composition on closures (Scala Function1.andThen/compose) —
        // std/dsl pipelines chain passes with `.andThen`; there was NO arm for
        // ClosV receivers and the whole pipeline died on dispatch.
        case (f: ClosV, "andThen", List(g: ClosV)) =>
          ClosV(Runtime.emptyEnv, 1, env => Done(callClos(g, Array(callClos(f, Array(env.last))))))
        case (f: ClosV, "compose", List(g: ClosV)) =>
          ClosV(Runtime.emptyEnv, 1, env => Done(callClos(f, Array(callClos(g, Array(env.last))))))
        // By-name field access on a case-class value reached through an UNTYPED path
        // (e.g. `localVar.method().field`, where the front can't prove the receiver's
        // type and emits `__method__("field", …)` instead of the `_sel_field`
        // accessor). Resolve via the registered field names, matching the
        // `__methodOrExt__` field path. Without this the access returned a Stub.
        case (DataV(tag, fields), fieldName, Nil)
            if V2PluginRegistry.lookupFieldNames(tag, fields.length).exists(ns =>
                 ns.indexOf(fieldName) >= 0 && ns.indexOf(fieldName) < fields.length) =>
          fields(V2PluginRegistry.lookupFieldNames(tag, fields.length).get.indexOf(fieldName))
        case (v, "toString", Nil) => StrV(anyStr(v))
        case _ =>
          // An ACTIVE effect handler on an empty-DataV receiver wins over the
          // generic __method__.* natives: State.get() inside runState { … }
          // was swallowed by __method__.get's `case _ => Stub` fallback and
          // the runner's handler never saw the op. Dynamic scope > generic
          // method — nothing pushes contexts for Storage/map receivers, so
          // their __method__.get behaviour is unchanged.
          val activeCtx = recv match
            case DataV(ctxTag, IndexedSeq()) => V2EffectContext.peek(ctxTag)
            case _                            => None
          if activeCtx.isDefined then activeCtx.get.apply(name, margs)
          else V2PluginRegistry.lookup(s"__method__.$name") match
            case Some(fn) => fn(a)
            case None =>
              // Effect dispatch: DataV("Logger"/"State"/...) with no method → check effect context
              recv match
                case DataV(effectTag, IndexedSeq()) =>
                  // Dispatch order: an ACTIVE effect handler (dynamic scope) wins over a
                  // plugin native registered under the same "Tag.method" (static default).
                  // State.get is both a stdlib intrinsic AND a parameterized effect op —
                  // inside `runState { … }` the runner's handler must see it; the intrinsic
                  // (reading v1 TLS that is empty on v2) returned Stub and shadowed the
                  // whole State-effect family. Db.query/execute keep working: nothing
                  // pushes a "Db" context, so peek misses and the plugin handles them.
                  // Chain written with EXPLICIT Options (no nested equal-indent
                  // `case None =>` bodies — those parse as statement SEQUENCES and
                  // silently discard the taken branch: the __fallback__ arm ran but
                  // an Op was ALWAYS built and returned; Dataset.* leaked Ops on the
                  // assembled binary).
                  val viaCtx    = V2EffectContext.peek(effectTag).map(h => h(name, margs))
                  val viaPlugin = if viaCtx.isDefined then None
                                  else V2PluginRegistry.lookup(s"$effectTag.$name").map(_(margs))
                  val viaFall   = if viaCtx.isDefined || viaPlugin.isDefined then None
                                  else V2PluginRegistry.lookup(s"__fallback__.$effectTag.$name").map(_(margs))
                  viaCtx.orElse(viaPlugin).orElse(viaFall).getOrElse {
                    // No active handler: Free monad Op for typed `handle` dispatch.
                    // Multi-arg ops pack under the internal __EffArgs__ marker (NOT
                    // TupleN): the handler arm `case op(a, b, resume)` is op/3, so
                    // runEffectLoop must deliver the args unpacked — while a genuine
                    // single tuple argument stays one payload value (op/2).
                    PortableEffects.perform(s"$effectTag.$name", margs)
                  }
                case DataV("Stub", fs) =>
                  // A method ON a stub stays that stub — keep the ORIGINAL missed-method
                  // breadcrumb instead of burying it under Stub.<name> chains.
                  DataV("Stub", fs)
                case DataV(tag, fields) =>
                  // Registered FIELD access first (e.g. a function-typed field of a
                  // case class): return the field, or call it when args are given.
                  // Only then fall back to the batch Stub — carrying WHICH method
                  // missed (Stub(Tag.method)) so downstream errors self-describe.
                  // Arity-matched: two case classes can share a tag NAME (http vs
                  // domain `Request`) — resolve against the layout whose arity ==
                  // the receiver's field count (v2-req-form-type-collision: this is
                  // the field-WITH-ARGS path, e.g. `req.params("x")`).
                  V2PluginRegistry.lookupFieldNames(tag, fields.length) match
                    case Some(fnames) =>
                      val i = fnames.indexOf(name)
                      if i >= 0 && i < fields.length then
                        val fv = fields(i)
                        if margs.isEmpty then fv  // bare field access
                        else fv match
                          case fn: ClosV => callClos(fn, margs.toArray)
                          // field-with-args = APPLY the field's value: s.users(1) is
                          // list indexing on the `users` field, map fields likewise
                          case lv @ (DataV("Cons", _) | DataV("Nil", _)) =>
                            margs.head match
                              case IntV(ix) => unlistPub(lv)(ix.toInt)
                              case _ => DataV("Stub", Vector(StrV(s"$tag.$name")))
                          case MapV(m) => m(margs.head)
                          case ForeignV(m: collection.mutable.Map[?, ?]) =>
                            m.asInstanceOf[collection.mutable.Map[Value, Value]](margs.head)
                          case _ => DataV("Stub", Vector(StrV(s"$tag.$name")))
                      else DataV("Stub", Vector(StrV(s"$tag.$name")))
                    case None => DataV("Stub", Vector(StrV(s"$tag.$name")))
                case _ =>
                  // ETA-EXPANSION: a method SELECTED on a value but NOT applied
                  // (`list.exists(lc.contains)`) reaches here with zero args after no
                  // nullary dispatch matched (a real field/nullary method would have
                  // matched earlier). Treat it as the function value `x => recv.name(x)`
                  // — v2 is untyped so this is decided at dispatch time, not by the
                  // frontend. HOFs then call it per element.
                  //
                  // This fallback CANNOT tell a genuine method ref from a typo: both
                  // lower to the same `__method__(name, recv)` node. An APPLIED zero-arg
                  // call (`recv.name()`) is therefore emitted by the lowerer as
                  // `__method0__`, which rejects the marked closure below and fails
                  // closed. Only a BARE selection may legitimately land here.
                  if margs.isEmpty then
                    val eta = ClosV(Runtime.emptyEnv, 1, env => Done(methodOp(name, recv, List(env.last))))
                    eta.etaMethodRef = (name, recv)
                    eta
                  else
                    sys.error(s"__method__: no dispatch for .$name on ${Show.show(recv)}")
    case _ => NotBuiltin

  private[ssc] def resolveBuiltin(op: String): Option[Fn] =
    val fn = resolveBuiltinRaw(op)
    if fn eq NotBuiltin then None else Some(fn)

  private[ssc] def isBuiltin(op: String): Boolean =
    (resolveBuiltinRaw(op) ne NotBuiltin) ||
      resolve1(op).isDefined || resolve2(op).isDefined || resolve3(op).isDefined

  def resolve(op: String): Fn =
    resolveBuiltin(op)
      .orElse(V2PluginRegistry.lookup(op))
      .getOrElse((_: List[Value]) => sys.error(s"unimplemented primitive: $op"))

  /** Dispatch `__arith__(op, l, r)` without allocating a List[Value] for the common cases. */
  /** Run a 1-arg closure to a finished Value (trampolined). */
  def runClos1(k: ClosV, arg: Value): Value =
    Runtime.run(k.code, Runtime.extend(k.env, Array(arg)))

  private val charComparisonOps: Set[String] =
    Set("<", "<=", ">", ">=", "==", "!=")

  /** COMPACT numeric arith for hot seams (FastCode per-op lambdas, the
   *  bytecode lane's Emit.arith, resolve "__arith__"). Small enough for the
   *  JIT to inline into compiled loops — the unified arithOp below is a
   *  megamethod that stopped inlining after a2985d911 (fib 226ms -> 6.9s).
   *  Handles ONLY the common numeric pairs/ops; everything else defers. */
  def arithFast(op: String, l: Value, r: Value): Value =
    if op == "->" then DataV("Tuple2", collection.immutable.ArraySeq(l, r))
    else arithFastTyped(op, l, r)
  private def arithFastTyped(op: String, l: Value, r: Value): Value = l match
    case IntV(x) => r match
      case IntV(y) => op match
        case "+"  => IntV(x + y);  case "-"  => IntV(x - y);  case "*"  => IntV(x * y)
        case "<"  => BoolV(x < y); case "<=" => BoolV(x <= y)
        case ">"  => BoolV(x > y); case ">=" => BoolV(x >= y)
        case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
        case "/"  => IntV(x / y);  case "%"  => IntV(x % y)
        case _    => arithOp(op, l, r)
      case FloatV(y) => op match
        case "+" => FloatV(x + y); case "-" => FloatV(x - y); case "*" => FloatV(x * y); case "/" => FloatV(x / y)
        case _   => arithOp(op, l, r)
      case _ => arithOp(op, l, r)
    case FloatV(x) => r match
      case FloatV(y) => op match
        case "+" => FloatV(x + y); case "-" => FloatV(x - y); case "*" => FloatV(x * y); case "/" => FloatV(x / y)
        case "<" => BoolV(x < y);  case "<=" => BoolV(x <= y); case ">" => BoolV(x > y); case ">=" => BoolV(x >= y)
        case _   => arithOp(op, l, r)
      case IntV(y) => op match
        case "+" => FloatV(x + y); case "-" => FloatV(x - y); case "*" => FloatV(x * y); case "/" => FloatV(x / y)
        case _   => arithOp(op, l, r)
      case _ => arithOp(op, l, r)
    case _ => arithOp(op, l, r)

  def arithOp(op: String, l: Value, r: Value): Value = (l, r) match
    case _ if op == "->" =>
      DataV("Tuple2", collection.immutable.ArraySeq(l, r))
    // HOT PATH ORDER: `->` first (one string compare — Int->Int pairs are legal
    // map keys and must not hit the numeric error arms), then the numeric and
    // Str+Str pairs — a2985d911's unification put the exotic guards (char
    // comparisons with a Set lookup, Cons-minus, Map+pair, Op-lifting) in front
    // of them and pattern-match-heavy went 23.6 -> 348 ms/iter (15x): every
    // float mul in the loop waded through ~10 failed guarded patterns. None of
    // the moved-up cases overlap the exotic ones (an Op operand is a DataV and
    // matches no numeric/Str pair), so semantics are unchanged.
    case (IntV(x), IntV(y)) => op match
      case "+"  => IntV(x + y);  case "-"  => IntV(x - y);  case "*"  => IntV(x * y)
      case "/"  => IntV(x / y);  case "%"  => IntV(x % y)
      case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
      case "<"  => BoolV(x < y); case "<=" => BoolV(x <= y); case ">"  => BoolV(x > y); case ">=" => BoolV(x >= y)
      case "&"  => IntV(x & y);  case "|"  => IntV(x | y);  case "^"  => IntV(x ^ y)
      case "<<" => IntV(x << y.toInt); case ">>" => IntV(x >> y.toInt); case ">>>" => IntV(x >>> y.toInt)
      case "++" => StrV(x.toString + y.toString)
      case "to" =>
        val nilV: Value = DataV("Nil", IndexedSeq.empty)
        (x to y).foldRight(nilV)((i, acc) => DataV("Cons", collection.immutable.ArraySeq(IntV(i), acc)))
      case "until" =>
        val nilV: Value = DataV("Nil", IndexedSeq.empty)
        (x until y).foldRight(nilV)((i, acc) => DataV("Cons", collection.immutable.ArraySeq(IntV(i), acc)))
      case _ => sys.error(s"__arith__: unknown op $op for Int")
    case (FloatV(x), FloatV(y)) => op match
      case "+"  => FloatV(x + y); case "-" => FloatV(x - y); case "*" => FloatV(x * y)
      case "/"  => FloatV(x / y); case "%" => FloatV(x % y)
      case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
      case "<"  => BoolV(x < y); case "<=" => BoolV(x <= y); case ">"  => BoolV(x > y); case ">=" => BoolV(x >= y)
      case "++" => StrV(x.toString + y.toString)
      case _ => sys.error(s"__arith__: unknown op $op for Float")
    case (IntV(x), FloatV(y)) => op match
      case "+"  => FloatV(x + y); case "-" => FloatV(x - y); case "*" => FloatV(x * y)
      case "/"  => FloatV(x / y)
      case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
      case "<"  => BoolV(x < y); case "<=" => BoolV(x <= y); case ">"  => BoolV(x > y); case ">=" => BoolV(x >= y)
      case _ => sys.error(s"__arith__: unknown op $op for Int+Float")
    case (FloatV(x), IntV(y)) => op match
      case "+"  => FloatV(x + y); case "-" => FloatV(x - y); case "*" => FloatV(x * y)
      case "/"  => FloatV(x / y)
      case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
      case "<"  => BoolV(x < y); case "<=" => BoolV(x <= y); case ">"  => BoolV(x > y); case ">=" => BoolV(x >= y)
      case _ => sys.error(s"__arith__: unknown op $op for Float+Int")
    // EXPRESSION-position effects: an un-handled Op OPERAND lifts over the
    // arithmetic — `acc + Bump.tick().toLong` runs the handler first, then
    // the op applies to the resumed value (mirrors the __method__ lift).
    case (DataV("Op", IndexedSeq(lb, arg, k)), _) =>
      val kc = k.asInstanceOf[ClosV]
      val k2 = ClosV(Runtime.emptyEnv, 1, env2 => {
        val resumed = Runtime.run(kc.code, if kc.env.isEmpty then Array(env2.last) else Runtime.extend(kc.env, Array(env2.last)))
        Done(arithOp(op, resumed, r))
      })
      DataV("Op", Vector(lb, arg, k2))
    case (_, DataV("Op", IndexedSeq(rb, arg, k))) =>
      val kc = k.asInstanceOf[ClosV]
      val k2 = ClosV(Runtime.emptyEnv, 1, env2 => {
        val resumed = Runtime.run(kc.code, if kc.env.isEmpty then Array(env2.last) else Runtime.extend(kc.env, Array(env2.last)))
        Done(arithOp(op, l, resumed))
      })
      DataV("Op", Vector(rb, arg, k2))
    case (DataV("Cons" | "Nil", _), _) if op == "-" =>
      listOf(unlistPub(l).filterNot(_ == r))
    // Map + (k -> v): copy-on-write over the insertion-ordered MapV so the
    // v1 immutable-Map value semantics hold (`results + (partId -> result)`).
    case (MapV(m), DataV("Tuple2" | "Pair", IndexedSeq(k, v))) if op == "+" =>
      val out = MapV.from(m)
      out.entries(k) = v
      out
    case (ForeignV(m: collection.mutable.Map[?, ?]), DataV("Tuple2" | "Pair", IndexedSeq(k, v))) if op == "+" =>
      val nm = collection.mutable.HashMap.from(m.asInstanceOf[collection.mutable.Map[Value, Value]])
      nm(k) = v
      ForeignV(nm)
    // Char semantics: bridge char literals are Int codepoints, while chars
    // extracted from strings (charAt/forall) are 1-char strings — comparisons
    // between the two compare codepoints (c >= 'a' in parser predicates).
    case (StrV(s), IntV(n)) if s.length == 1 && charComparisonOps.contains(op) =>
      arithOp(op, IntV(s.charAt(0).toLong), IntV(n))
    case (IntV(n), StrV(s)) if s.length == 1 && charComparisonOps.contains(op) =>
      arithOp(op, IntV(n), IntV(s.charAt(0).toLong))
    case (StrV(x), StrV(y)) => op match
      case "++" | "+" => StrV(x + y)
      case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
      case "<"  => BoolV(x < y); case "<=" => BoolV(x <= y); case ">"  => BoolV(x > y); case ">=" => BoolV(x >= y)
      case _ => sys.error(s"__arith__: unknown op $op for String")
    // EXPRESSION-position effects: an un-handled Op OPERAND lifts over the
    // arithmetic — `acc + Bump.tick().toLong` runs the handler first, then
    // the op applies to the resumed value (mirrors the __method__ lift).
    case (DataV("Op", IndexedSeq(lb, arg, k)), _) =>
      val kc = k.asInstanceOf[ClosV]
      val k2 = ClosV(Runtime.emptyEnv, 1, env2 => {
        val resumed = Runtime.run(kc.code, if kc.env.isEmpty then Array(env2.last) else Runtime.extend(kc.env, Array(env2.last)))
        Done(arithOp(op, resumed, r))
      })
      DataV("Op", Vector(lb, arg, k2))
    case (_, DataV("Op", IndexedSeq(rb, arg, k))) =>
      val kc = k.asInstanceOf[ClosV]
      val k2 = ClosV(Runtime.emptyEnv, 1, env2 => {
        val resumed = Runtime.run(kc.code, if kc.env.isEmpty then Array(env2.last) else Runtime.extend(kc.env, Array(env2.last)))
        Done(arithOp(op, l, resumed))
      })
      DataV("Op", Vector(rb, arg, k2))
    // Everything else (char comparisons, list/tuple/Map/BigDecimal/actor cases,
    // generic fallback) lives in arithRest: keeping THIS method small keeps it
    // JIT-compilable and inlinable into hot loops — the a2985d911 unification
    // merged the whole dispatch table in here and the method blew past the
    // JIT size limits (pattern-match-heavy 23.6 -> 348 ms/iter).
    case _ => arithRest(op, l, r)

  private def arithRest(op: String, l: Value, r: Value): Value = (l, r) match
    // Set-as-list element removal: v2 sets are distinct lists (`.toSet`), so
    // `pending - partId` (std/mapreduce collect loops) = filterNot. Without
    // this the op fell through to the plugin stub and returned Unit, which
    // then blew up on the next `pending.isEmpty`.
    case (DataV("Cons" | "Nil", _), _) if op == "-" =>
      listOf(unlistPub(l).filterNot(_ == r))
    // Map + (k -> v): copy-on-write over the insertion-ordered MapV so the
    // v1 immutable-Map value semantics hold (`results + (partId -> result)`).
    case (MapV(m), DataV("Tuple2" | "Pair", IndexedSeq(k, v))) if op == "+" =>
      val out = MapV.from(m)
      out.entries(k) = v
      out
    case (ForeignV(m: collection.mutable.Map[?, ?]), DataV("Tuple2" | "Pair", IndexedSeq(k, v))) if op == "+" =>
      val nm = collection.mutable.HashMap.from(m.asInstanceOf[collection.mutable.Map[Value, Value]])
      nm(k) = v
      ForeignV(nm)
    // Bridge arithmetic is dynamically typed and therefore reaches __arith__
    // rather than the named big.* primitives. Keep both paths identical.
    case (BigV(x), BigV(y)) => bigArith(op, x, y)
    case (BigV(x), IntV(y)) => bigArith(op, x, BigInt(y))
    case (IntV(x), BigV(y)) => bigArith(op, BigInt(x), y)
    // Char semantics: bridge char literals are Int codepoints, while chars
    // extracted from strings (charAt/forall) are 1-char strings — comparisons
    // between the two compare codepoints (c >= 'a' in parser predicates).
    case (StrV(s), IntV(n)) if s.length == 1 && charComparisonOps.contains(op) =>
      arithOp(op, IntV(s.charAt(0).toLong), IntV(n))
    case (IntV(n), StrV(s)) if s.length == 1 && charComparisonOps.contains(op) =>
      arithOp(op, IntV(n), IntV(s.charAt(0).toLong))
    case (StrV(x), IntV(y)) => op match
      case "*"        => StrV(x * y.toInt)
      case "+" | "++" => StrV(x + y.toString)
      case "==" => BoolV(false); case "!=" => BoolV(true)
      case _   => sys.error(s"__arith__: unknown op $op for String+Int (l=\"$x\", r=$y)")
    case (IntV(x), StrV(y)) => op match
      case "+" | "++" => StrV(x.toString + y)
      case "==" => BoolV(false); case "!=" => BoolV(true)
      case _   => sys.error(s"__arith__: unknown op $op for Int+String")
    // Float↔String concat renders via floatStr (v1 collapses whole doubles:
    // "" + 1.0 is "1", not "1.0" — raw toString broke output parity).
    case (FloatV(x), StrV(y)) => op match
      case "+" | "++" => StrV(Writer.floatStr(x) + y)
      case "==" => BoolV(false); case "!=" => BoolV(true)
      case _   => sys.error(s"__arith__: unknown op $op for Float+String")
    case (StrV(x), FloatV(y)) => op match
      case "+" | "++" => StrV(x + Writer.floatStr(y))
      case "==" => BoolV(false); case "!=" => BoolV(true)
      case _   => sys.error(s"__arith__: unknown op $op for String+Float")
    case (StrV(x), DecimalV(y)) => op match
      case "+" | "++" => StrV(x + y)
      case "==" => BoolV(false); case "!=" => BoolV(true)
      case _   => sys.error(s"__arith__: unknown op $op for String+Decimal")
    case (DecimalV(x), StrV(y)) => op match
      case "+" | "++" => StrV(x + y)
      case "==" => BoolV(false); case "!=" => BoolV(true)
      case _   => sys.error(s"__arith__: unknown op $op for Decimal+String")
    case (BoolV(x), BoolV(y)) => op match
      case "==" => BoolV(x == y); case "!=" => BoolV(x != y)
      case _    => sys.error(s"__arith__: op $op not valid for Bool")
    // List :+ and ++ — bridge compiles infix ops as __arith__, but list ops live here too
    case (lv, rv) if op == ":+" && isList(lv) => listOf(unlist(lv) :+ rv)
    // `list ++ list` = concat; but `set + stringElement` is lowered to `++` (the
    // `+`→`++` string heuristic fires on a string operand), so a NON-list RHS is a
    // Set element to ADD (distinct), not a collection to unlist.
    case (lv, rv) if op == "++" && isList(lv) =>
      if isList(rv) then listOf(unlist(lv) ++ unlist(rv))
      else listOf((unlist(lv) :+ rv).distinct)
    // `+` on a list: `list ++ list` semantics when the RHS is itself a list, but
    // `set + element` (v2 sets are distinct lists — `retried = Set(); retried + partId`)
    // ADDS the element (distinct). A non-list RHS is an element, not a list to unlist.
    case (lv, rv) if op == "+" && isList(lv) =>
      if isList(rv) then listOf(unlist(lv) ++ unlist(rv))
      else listOf((unlist(lv) :+ rv).distinct)
    // Tuple concatenation: (a,b) ++ (c,d) = (a,b,c,d)
    case (DataV(lt, lf), DataV(rt, rf))
        if op == "++" && lt.startsWith("Tuple") && rt.startsWith("Tuple") =>
      val combined = lf ++ rf
      DataV(s"Tuple${combined.length}", combined)
    // Payment bridge Money values are plain DataV so payment examples can use
    // ordinary arithmetic without linking v2-core to the payments modules.
    case (DataV("Money", IndexedSeq(IntV(x), cx)), DataV("Money", IndexedSeq(IntV(y), cy))) =>
      def sameCurrency: Boolean = cx == cy
      op match
        case "+" if sameCurrency => DataV("Money", Vector(IntV(x + y), cx))
        case "-" if sameCurrency => DataV("Money", Vector(IntV(x - y), cx))
        case "==" => BoolV(x == y && sameCurrency)
        case "!=" => BoolV(x != y || !sameCurrency)
        case "<" if sameCurrency  => BoolV(x < y)
        case "<=" if sameCurrency => BoolV(x <= y)
        case ">" if sameCurrency  => BoolV(x > y)
        case ">=" if sameCurrency => BoolV(x >= y)
        case "+" | "-" | "<" | "<=" | ">" | ">=" => DataV("CurrencyMismatch", Vector(cx, cy))
        case _ => sys.error(s"__arith__: op $op not valid for Money+Money")
    case (DataV("Money", IndexedSeq(IntV(x), c)), y: DecimalV) =>
      import java.math.RoundingMode.HALF_EVEN
      op match
        case "*" => DataV("Money", Vector(IntV(new java.math.BigDecimal(x).multiply(PortableDecimal.toJava(y)).setScale(0, HALF_EVEN).longValueExact()), c))
        case "/" => DataV("Money", Vector(IntV(new java.math.BigDecimal(x).divide(PortableDecimal.toJava(y), 0, HALF_EVEN).longValueExact()), c))
        case _   => sys.error(s"__arith__: op $op not valid for Money+Decimal")
    case (DataV("Money", IndexedSeq(IntV(x), c)), IntV(y)) =>
      op match
        case "*" => DataV("Money", Vector(IntV(x * y), c))
        case "/" => DataV("Money", Vector(IntV(x / y), c))
        case _   => sys.error(s"__arith__: op $op not valid for Money+Int")
    case (lv, rv) if PortableDecimal.involvesDecimal(lv, rv) =>
      PortableDecimal.arith(op, lv, rv)
    case (lv, rv) => op match
      case "==" => BoolV(lv == rv)
      case "!=" => BoolV(lv != rv)
      case "+"  => StrV(anyStr(lv) + anyStr(rv))
      case "++" => StrV(anyStr(lv) + anyStr(rv))
      case "!"  =>
        // Actor send: actorRef ! msg. If the actor runtime is not installed,
        // old table dispatch treated the send as a no-op.
        V2PluginRegistry.lookup("actor.send") match
          case Some(sendFn) => sendFn(List(lv, rv))
          case None         => UnitV
      case ":=" =>
        // HTML attr-key assignment: attrKey := value → DataV("Attr", [name, value])
        lv match
          case ForeignV(obj: NamedMethodObj) =>
            obj.getField(":=") match
              case Some(fn: ClosV) => Runtime.run(fn.code, Runtime.extend(fn.env, Array(rv)))
              case Some(v)         => v
              case None            => UnitV
          case _ => UnitV
      case _ =>
        // Unknown operator with non-numeric types: treat as a declaration-style
        // statement (e.g. `effect Logger:` compiles to
        // __arith__("Logger", effectClosure, ()) in v2).
        UnitV

  private def bigArith(op: String, left: BigInt, right: BigInt): Value = op match
    case "+" => BigV(left + right)
    case "-" => BigV(left - right)
    case "*" => BigV(left * right)
    case "/" =>
      if right == 0 then sys.error("bigint: division by zero")
      BigV(left / right)
    case "%" =>
      if right == 0 then sys.error("bigint: division by zero")
      BigV(left % right)
    case "==" => BoolV(left == right)
    case "!=" => BoolV(left != right)
    case "<"  => BoolV(left < right)
    case "<=" => BoolV(left <= right)
    case ">"  => BoolV(left > right)
    case ">=" => BoolV(left >= right)
    case "++" => StrV(left.toString + right.toString)
    case other => sys.error(s"__arith__: op $other not valid for BigInt")

  /** Dispatch `__method__(name, recv, args...)` without trampoline — for FastCode. */
  def methodOp(name: String, recv: Value, args: List[Value]): Value =
    resolve("__method__")(StrV(name) :: recv :: args)

  /** The runtime's missed-method breadcrumb value (see the __method__ DataV arm). */
  private[ssc] def isStubV(v: Value): Boolean = v match
    case DataV("Stub", _) => true
    case _                => false

  // ── Effect-aware list traversal (bridged-lane list HOFs) ─────────────────────
  // A perform INSIDE a HOF lambda (`items.map(i => OperatorPlan(i.id,
  // Operator.decide(i)))`) makes the per-element call return an unresolved
  // DataV("Op",…); the eager natives used to collect those RAW into the result
  // (busi operator: hPlan was a list of Ops — .length matched, every field
  // access misfired). Like methodOp's receiver lifting, these live only behind
  // the bridge-emitted __method__ dispatch, so the Mira/hm lane (where Op values
  // in lists are legitimate data) never routes through them.
  /** Apply `step` per element; on an Op result, defer the REST of the traversal
   *  into the op's continuation (letThreadOp protocol), else accumulate. */
  /** Effect-aware foreach that walks the Cons/Nil chain DIRECTLY — no `unlist`
   *  materialisation and no result accumulation (mapThreadOp rebuilds a list
   *  that foreach immediately discards). An Op step-result defers the REST of
   *  the traversal into the op's continuation. The bytecode lane routes every
   *  `xs.foreach` through this runtime path (the VM has a compile-time fast
   *  path), so the materialisation showed up as ~unlist/List.drop in JFR. */
  def foreachConsOp(ls: Value, step: Value => Value): Value =
    var cur = ls
    while true do
      cur match
        case Value.DataV("Cons", fs) =>
          step(fs(0)) match
            case op @ Value.DataV("Op", _) =>
              val tail = fs(1)
              return Runtime.letThreadOp(op, _ => foreachConsOp(tail, step))
            case _ => cur = fs(1)
        case _ => return Value.UnitV
    Value.UnitV

  def mapThreadOp(xs: List[Value], step: Value => Value, rebuild: List[Value] => Value): Value =
    def go(rest: List[Value], acc: List[Value]): Value = rest match
      case Nil => rebuild(acc.reverse)
      case x :: t => step(x) match
        case op @ Value.DataV("Op", _) => Runtime.letThreadOp(op, r => go(t, r :: acc))
        case v => go(t, v :: acc)
    go(xs, Nil)

  /** Fold with an effect-aware step: an Op step-result defers the remaining
   *  fold into the op's continuation (the resumed value is the new acc). */
  def foldThreadOp(xs: List[Value], z: Value, step: (Value, Value) => Value): Value =
    def go(rest: List[Value], acc: Value): Value = rest match
      case Nil => acc
      case x :: t => step(acc, x) match
        case op @ Value.DataV("Op", _) => Runtime.letThreadOp(op, r => go(t, r))
        case v => go(t, v)
    go(xs, z)

  // ── Allocation-free fast paths for 1/2/3-arg primitives ─────────────────────
  // These avoid creating a List[Value] for arg passing on the hot path.
  type Fn1 = Value => Value
  type Fn2 = (Value, Value) => Value
  type Fn3 = (Value, Value, Value) => Value

  def resolve1(op: String): Option[Fn1] = op match
    case "cell.get" => Some(c  => asCell(c)(0))
    case "cell.new" => Some(v  => ForeignV(scala.Array[Value](v)))
    case "lcell.get"=> Some(c  => IntV(c.asInstanceOf[LongCellV].v))
    case "lcell.new"=> Some(v  => new LongCellV(asInt1(v)))
    case "dcell.get"=> Some(c  => FloatV(c.asInstanceOf[DoubleCellV].v))
    case "dcell.new"=> Some(v  => new DoubleCellV(asFloat1(v)))
    case "i.neg"    => Some { case IntV(n) => IntV(-n);  case FloatV(d) => FloatV(-d); case v => IntV(-asInt1(v)) }
    case "i.not"    => Some { case IntV(n) => IntV(~n);  case v => IntV(~asInt1(v)) }
    case "not"      => Some { case BoolV(b) => BoolV(!b); case v => sys.error(s"not: not Bool: ${Show.show(v)}") }
    case "slen"     => Some { case StrV(s) => IntV(s.length.toLong); case v => sys.error(s"slen: not Str: ${Show.show(v)}") }
    case "arr.len"  => Some(a  => IntV(asArr(a).length.toLong))
    case "arr.new"  => Some(_ => ForeignV(collection.mutable.ArrayBuffer[Value]()))
    case "arr.pop"  => Some(a  => { val buf = asArr(a); buf.remove(buf.length - 1) })
    case "arr.push" => None  // 2-arg
    case "tagOf"    => Some(v  => StrV(asData(v)._1))
    case "arity"    => Some(v  => IntV(asData(v)._2.length.toLong))
    case "map.size" => Some(m  => IntV(asMap(m).size.toLong))
    case "map.new"  => None  // 0-arg
    case "map.keys" => Some(m  => listOf(asMap(m).keys.toSeq))
    case "io.args"  => None  // 0-arg
    case "blen"     => Some { case BytesV(b) => IntV(b.length.toLong); case v => sys.error(s"blen: not Bytes") }
    case "str->utf8"=> Some { case StrV(s) => BytesV(s.getBytes("UTF-8").toVector); case v => sys.error("str->utf8: not Str") }
    case "utf8->str"=> Some { case BytesV(b) => StrV(new String(b.toArray, "UTF-8")); case v => sys.error("utf8->str: not Bytes") }
    case "str.trim" => Some { case StrV(s) => StrV(s.trim); case v => sys.error("str.trim: not Str") }
    case "str.lines"=> Some { case StrV(s) =>
                         val parts = s.split("\n", -1); val nilV: Value = DataV("Nil", IndexedSeq.empty)
                         parts.foldRight(nilV)((x, acc) => DataV("Cons", collection.immutable.ArraySeq(StrV(x), acc)))
                         case v => sys.error("str.lines: not Str") }
    case "i->str"   => Some { case IntV(n) => StrV(n.toString);   case v => sys.error("i->str: not Int") }
    case "i->big"   => Some { case IntV(n) => BigV(BigInt(n));     case v => sys.error("i->big: not Int") }
    case "big->i"   => Some { case BigV(n) => IntV(n.toLong);      case v => sys.error("big->i: not BigInt") }
    case "i->f"     => Some { case IntV(n) => FloatV(n.toDouble);  case v => sys.error("i->f: not Int") }
    case "f->i"     => Some { case FloatV(d) => IntV(d.toLong);    case v => sys.error("f->i: not Float") }
    case "f->str"   => Some { case FloatV(d) => StrV(Writer.floatStr(d)); case v => sys.error("f->str: not Float") }
    case "big->str" => Some { case BigV(n) => StrV(n.toString);    case v => sys.error("big->str: not BigInt") }
    case "runLogger"=> Some(f  => { Runtime.run(f.asInstanceOf[ClosV].code, f.asInstanceOf[ClosV].env); UnitV })
    case _          => None

  def resolve2(op: String): Option[Fn2] = op match
    // Register a runtime GLOBAL (top-level entry vals are v1 globals — pass-2
    // defs invoked later resolve them through V2PluginRegistry).
    case "global.reg" => Some { (n, v) => n match
      case StrV(name) => V2PluginRegistry.registerGlobal(name, v); UnitV
      case _          => sys.error("global.reg(name, value)") }
    // Numeric fast paths mirror the GENERAL table's numBin/numCmp polymorphism
    // (Int, Float, mixed). Keep the (IntV, IntV) hot case inline; delegate the cold
    // shapes — a fast path stricter than the general table silently diverges
    // (the sconcat/T5.4 and float-div/T5.6 lessons).
    case "i.add"    => Some { case (IntV(x),   IntV(y))   => IntV(x + y)
                              case (a, b)                  => liftArith2("+", a, b, numBin(List(a, b), _ + _, _ + _)) }
    case "i.sub"    => Some { case (IntV(x),   IntV(y))   => IntV(x - y)
                              case (a, b)                  => liftArith2("-", a, b, numBin(List(a, b), _ - _, _ - _)) }
    case "i.mul"    => Some { case (IntV(x),   IntV(y))   => IntV(x * y)
                              case (a, b)                  => liftArith2("*", a, b, numBin(List(a, b), _ * _, _ * _)) }
    case "i.div"    => Some { case (IntV(x),   IntV(y))   => IntV(x / y)
                              case (a, b)                  => liftArith2("/", a, b, numBin(List(a, b), _ / _, _ / _)) }
    case "i.mod"    => Some { case (IntV(x),   IntV(y))   => IntV(x % y)
                              case (a, b)                  => liftArith2("%", a, b, numBin(List(a, b), _ % _, _ % _)) }
    case "i.eq"     => Some { case (IntV(x),   IntV(y))   => BoolV(x == y)
                              case (a, b)                  => liftArith2("==", a, b, numCmp(List(a, b), _ == _, _ == _)) }
    case "i.lt"     => Some { case (IntV(x),   IntV(y))   => BoolV(x < y)
                              case (a, b)                  => liftArith2("<", a, b, numCmp(List(a, b), _ < _, _ < _)) }
    case "i.le"     => Some { case (IntV(x),   IntV(y))   => BoolV(x <= y)
                              case (a, b)                  => liftArith2("<=", a, b, numCmp(List(a, b), _ <= _, _ <= _)) }
    case "i.gt"     => Some { case (IntV(x),   IntV(y))   => BoolV(x > y)
                              case (a, b)                  => liftArith2(">", a, b, numCmp(List(a, b), _ > _, _ > _)) }
    case "i.ge"     => Some { case (IntV(x),   IntV(y))   => BoolV(x >= y)
                              case (a, b)                  => liftArith2(">=", a, b, numCmp(List(a, b), _ >= _, _ >= _)) }
    case "i.and"    => Some { case (IntV(x),   IntV(y))   => IntV(x & y);  case (a,b) => IntV(asInt1(a) & asInt1(b)) }
    case "i.or"     => Some { case (IntV(x),   IntV(y))   => IntV(x | y);  case (a,b) => IntV(asInt1(a) | asInt1(b)) }
    case "i.xor"    => Some { case (IntV(x),   IntV(y))   => IntV(x ^ y);  case (a,b) => IntV(asInt1(a) ^ asInt1(b)) }
    case "i.shl"    => Some { case (IntV(x),   IntV(y))   => IntV(x << y); case (a,b) => IntV(asInt1(a) << asInt1(b)) }
    case "i.shr"    => Some { case (IntV(x),   IntV(y))   => IntV(x >> y); case (a,b) => IntV(asInt1(a) >> asInt1(b)) }
    case "i.ushr"   => Some { case (IntV(x),   IntV(y))   => IntV(x >>> y);case (a,b) => IntV(asInt1(a) >>> asInt1(b)) }
    case "f.add"    => Some { case (FloatV(x), FloatV(y)) => FloatV(x + y); case (a,b) => sys.error("f.add: not Float") }
    case "f.sub"    => Some { case (FloatV(x), FloatV(y)) => FloatV(x - y); case (a,b) => sys.error("f.sub: not Float") }
    case "f.mul"    => Some { case (FloatV(x), FloatV(y)) => FloatV(x * y); case (a,b) => sys.error("f.mul: not Float") }
    case "f.div"    => Some { case (FloatV(x), FloatV(y)) => FloatV(x / y); case (a,b) => sys.error("f.div: not Float") }
    case "f.eq"     => Some { case (FloatV(x), FloatV(y)) => BoolV(x == y); case (a,b) => sys.error("f.eq: not Float") }
    case "f.lt"     => Some { case (FloatV(x), FloatV(y)) => BoolV(x <  y); case (a,b) => sys.error("f.lt: not Float") }
    case "f.le"     => Some { case (FloatV(x), FloatV(y)) => BoolV(x <= y); case (a,b) => sys.error("f.le: not Float") }
    case "f.gt"     => Some { case (FloatV(x), FloatV(y)) => BoolV(x >  y); case (a,b) => sys.error("f.gt: not Float") }
    case "f.ge"     => Some { case (FloatV(x), FloatV(y)) => BoolV(x >= y); case (a,b) => sys.error("f.ge: not Float") }
    case "sconcat"  => Some { case (StrV(a),  StrV(b))    => StrV(a + b)
                              case (DataV(_, f1), DataV(_, f2)) =>
                                val n = f1.length + f2.length; DataV(s"Tuple$n", f1 ++ f2)
                              case (a, b) => StrV(anyStr(a) + anyStr(b)) }  // mirror the general table: coerce like "s" + 42
    case "seq"      => Some { case (StrV(a),  StrV(b))    => BoolV(a == b); case (a,b) => sys.error("seq: not Str") }
    case "scmp"     => Some { case (StrV(a),  StrV(b))    => IntV(a.compareTo(b).toLong); case _ => sys.error("scmp: not Str") }
    case "sindexOf" => Some { case (StrV(a),  StrV(b))    => IntV(a.indexOf(b).toLong); case _ => sys.error("sindexOf: not Str") }
    case "str.split"=> Some { case (StrV(a),  StrV(b))    =>
                                val parts = a.split(b, -1)
                                val nilV: Value = DataV("Nil", IndexedSeq.empty)
                                parts.foldRight(nilV)((x, acc) => DataV("Cons", collection.immutable.ArraySeq(StrV(x), acc)))
                              case _ => sys.error("str.split: not Str") }
    // Var writes LIFT over un-handled effect Ops: `acc = acc + Bump.tick()`
    // stores through the continuation AFTER the handler resumes (the lcell
    // unboxing seam was the last place an Op could crash instead of lifting).
    case "cell.set" => Some { (c, v) => v match
      case op @ DataV("Op", _) => liftOverOp(op, x => { asCell(c)(0) = x; UnitV })
      case _ => asCell(c)(0) = v; UnitV }
    case "lcell.set"=> Some { (c, v) => v match
      case op @ DataV("Op", _) => liftOverOp(op, x => { c.asInstanceOf[LongCellV].v = asInt1(x); UnitV })
      case _ => c.asInstanceOf[LongCellV].v = asInt1(v); UnitV }
    case "dcell.set"=> Some { (c, v) => v match
      case op @ DataV("Op", _) => liftOverOp(op, x => { c.asInstanceOf[DoubleCellV].v = asFloat1(x); UnitV })
      case _ => c.asInstanceOf[DoubleCellV].v = asFloat1(v); UnitV }
    case "map.get"  => Some { (m, k) => asMap(m).get(k).fold(none)(some) }
    case "map.has"  => Some { (m, k) => BoolV(asMap(m).contains(k)) }
    case "map.del"  => Some { (m, k) => asMap(m).remove(k); UnitV }
    case "arr.get"  => Some { (a, i) => asArr(a)(asInt1(i).toInt) }
    case "arr.push" => Some { (a, v) => asArr(a) += v; UnitV }
    case "bget"     => Some { case (BytesV(b), IntV(i)) => IntV((b(i.toInt) & 0xff).toLong); case _ => sys.error("bget: bad args") }
    case "bconcat"  => Some { case (BytesV(a), BytesV(b)) => BytesV(a ++ b); case _ => sys.error("bconcat: bad args") }
    case "fieldAt"  => Some { (v, i) => v match { case DataV("Stub", _) => DataV("Stub", Vector.empty); case _ => asData(v)._2(asInt1(i).toInt) } }
    case "big.add"  => Some { case (BigV(x),BigV(y)) => BigV(x+y); case _ => sys.error("big.add") }
    case "big.sub"  => Some { case (BigV(x),BigV(y)) => BigV(x-y); case _ => sys.error("big.sub") }
    case "big.mul"  => Some { case (BigV(x),BigV(y)) => BigV(x*y); case _ => sys.error("big.mul") }
    case "big.div"  => Some { case (BigV(x),BigV(y)) => BigV(x/y); case _ => sys.error("big.div") }
    case "big.mod"  => Some { case (BigV(x),BigV(y)) => BigV(x%y); case _ => sys.error("big.mod") }
    case "big.eq"   => Some { case (BigV(x),BigV(y)) => BoolV(x==y); case _ => sys.error("big.eq") }
    case "big.lt"   => Some { case (BigV(x),BigV(y)) => BoolV(x<y);  case _ => sys.error("big.lt") }
    case "big.le"   => Some { case (BigV(x),BigV(y)) => BoolV(x<=y); case _ => sys.error("big.le") }
    case "big.gt"   => Some { case (BigV(x),BigV(y)) => BoolV(x>y);  case _ => sys.error("big.gt") }
    case "big.ge"   => Some { case (BigV(x),BigV(y)) => BoolV(x>=y); case _ => sys.error("big.ge") }
    case _          => None

  def resolve3(op: String): Option[Fn3] = op match
    case "sslice"   => Some { case (StrV(s), IntV(i), IntV(j)) => StrV(s.substring(i.toInt, j.toInt)); case _ => sys.error("sslice") }
    case "map.put"  => Some { (m, k, v) => asMap(m).update(k, v); UnitV }
    case "arr.set"  => Some { (a, i, v) => asArr(a)(asInt1(i).toInt) = v; UnitV }
    case "bslice"   => Some { case (BytesV(b), IntV(i), IntV(j)) => BytesV(b.slice(i.toInt, j.toInt)); case _ => sys.error("bslice") }
    case _          => None

  /** Lift a computation over an un-handled Op: Op(l, a, k) -> Op(l, a, x -> use(k(x))). */
  def liftOverOp(op: Value, use: Value => Value): Value = op match
    case DataV("Op", IndexedSeq(l, a, k)) =>
      val kc = k.asInstanceOf[ClosV]
      DataV("Op", Vector(l, a, ClosV(Runtime.emptyEnv, 1, env2 => {
        val resumed = Runtime.run(kc.code, if kc.env.isEmpty then Array(env2.last) else Runtime.extend(kc.env, Array(env2.last)))
        Done(resumed match { case op2 @ DataV("Op", _) => liftOverOp(op2, use); case r => use(r) })
      })))
    case v => use(v)

  private def asInt1(v: Value): Long = v match { case IntV(n) => n; case x => sys.error(s"expected Int, got ${Show.show(x)}") }
  // Double coercion for dcell.new/set — accepts Int too (a double var assigned an int
  // literal, e.g. `x = 5`), matching the VM's Int→Float widening in arithFast.
  private def asFloat1(v: Value): Double = v match { case FloatV(d) => d; case IntV(n) => n.toDouble; case x => sys.error(s"expected Double, got ${Show.show(x)}") }

  // numeric dispatch helpers: promote to Float when either operand is FloatV
  // Expression-position effects: when an operand of a fast binary prim is an unhandled
  // effect Op, thread it through arithOp (which lifts Ops into the op's continuation),
  // else take the fast fallback. Hot path pays only two null-cheap tag tests.
  private def liftArith(op: String, a: List[Value], fallback: => Value): Value =
    a(0) match
      case DataV("Op", _) => arithOp(op, a(0), a(1))
      case _ => a(1) match
        case DataV("Op", _) => arithOp(op, a(0), a(1))
        case _              => fallback

  // Two-arg (already-destructured) variant used by the binary fast-path table.
  private def liftArith2(op: String, a: Value, b: Value, fallback: => Value): Value =
    a match
      case DataV("Op", _) => arithOp(op, a, b)
      case _ => b match
        case DataV("Op", _) => arithOp(op, a, b)
        case _              => fallback

  private def numBin(a: List[Value], fi: (Long, Long) => Long, ff: (Double, Double) => Double): Value =
    (a(0), a(1)) match {
      case (FloatV(x), FloatV(y)) => FloatV(ff(x, y))
      case (FloatV(x), IntV(y))   => FloatV(ff(x, y.toDouble))
      case (IntV(x), FloatV(y))   => FloatV(ff(x.toDouble, y))
      case _                      => IntV(fi(int(a, 0), int(a, 1)))
    }
  private def numUn(a: List[Value], fi: Long => Long, ff: Double => Double): Value =
    a(0) match { case FloatV(x) => FloatV(ff(x)); case _ => IntV(fi(int(a, 0))) }
  private def numCmp(a: List[Value], fi: (Long, Long) => Boolean, ff: (Double, Double) => Boolean): Value =
    (a(0), a(1)) match {
      case (FloatV(x), FloatV(y)) => BoolV(ff(x, y))
      case (FloatV(x), IntV(y))   => BoolV(ff(x, y.toDouble))
      case (IntV(x), FloatV(y))   => BoolV(ff(x.toDouble, y))
      case _                      => BoolV(fi(int(a, 0), int(a, 1)))
    }

  // typed argument accessors
  private def int(a: List[Value], k: Int): Long = asInt(a(k))
  private def asInt(v: Value): Long = v match { case IntV(n) => n; case x => sys.error(s"expected Int, got ${Show.show(x)}") }
  private def big(a: List[Value], k: Int): BigInt = a(k) match { case BigV(n) => n; case v => sys.error(s"expected BigInt, got ${Show.show(v)}") }
  private def flt(a: List[Value], k: Int): Double = a(k) match { case FloatV(d) => d; case v => sys.error(s"expected Float, got ${Show.show(v)}") }
  private def str(a: List[Value], k: Int): String = a(k) match { case StrV(s) => s; case v => sys.error(s"expected Str, got ${Show.show(v)}") }
  private def formatValue(spec: String, v: Value): String =
    val conv = spec.reverse.find(_.isLetter).getOrElse('s').toLower
    val arg: AnyRef = conv match
      case 'd' | 'o' | 'x' =>
        v match
          case IntV(n)   => java.lang.Long.valueOf(n)
          case BigV(n)   => n.bigInteger
          case FloatV(d) => java.lang.Long.valueOf(d.toLong)
          case other     => java.lang.Long.valueOf(asInt(other))
      case 'f' | 'e' | 'g' | 'a' =>
        v match
          case FloatV(d) => java.lang.Double.valueOf(d)
          case IntV(n)   => java.lang.Double.valueOf(n.toDouble)
          case BigV(n)   => java.lang.Double.valueOf(n.toDouble)
          case other     => java.lang.Double.valueOf(asInt(other).toDouble)
      case 'b' =>
        java.lang.Boolean.valueOf(v == BoolV(true))
      case _ =>
        anyStr(v)
    String.format(java.util.Locale.US, spec, arg)
  /** User-visible deterministic display shared by standard host providers and
   *  the kernel's println/interpolation paths. This is a renderer over an
   *  already-built Value; it never parses source or data formats. */
  def display(v: Value): String = anyStr(v)

  private def anyStr(v: Value): String = v match
    case StrV(s)   => s
    // Stub breadcrumbs render as the bare tag in user-visible strings — the
    // App-path flattens stub fields (line ~632) so most stubs arrive empty;
    // un-flattened ones (methodOp propagation) must LOOK the same.
    case DataV("Stub", _) => "Stub"
    case IntV(n)   => n.toString
    case BoolV(b)  => b.toString
    case FloatV(d) => Writer.floatStr(d)
    case DecimalV(text) => text
    // Lists render with anyStr ELEMENTS here — deferring to Show.show made
    // rows inside List(...) render as "<foreign>" (show has no Map case and
    // quotes strings, both breaking v1 output parity in interpolations).
    case DataV("Cons", _) | DataV("Nil", _) =>
      s"List(${unlistPub(v).map(anyStr).mkString(", ")})"
    // Constructors and tuples render with anyStr FIELDS in interpolation —
    // v1 shows Some(got delivered) / (42, List(…)) UNQUOTED; deferring to
    // Show.show quoted the strings ("got delivered") and broke parity.
    // `_Raw(html)` is the HTML DSL's raw-markup wrapper — render its inner html
    // verbatim (mirrors the v1 HttpIntrinsics `_Raw` → fields("html") behaviour and
    // the scalameta path), not the default `_Raw(<…>)` constructor form.
    case DataV("_Raw", fields) if fields.nonEmpty => anyStr(fields.head)
    case DataV(tag, fields) if tag.startsWith("Tuple") && fields.nonEmpty =>
      s"(${fields.map(anyStr).mkString(", ")})"
    case DataV(tag, fields) if fields.nonEmpty && tag != "Op" && tag != "Stub" =>
      s"$tag(${fields.map(anyStr).mkString(", ")})"
    case MapV(entries) =>
      s"Map(${entries.iterator.map((k, x) => s"${anyStr(k)} -> ${anyStr(x)}").mkString(", ")})"
    // SQL result rows (and map.new maps) are ForeignV(scala Map) with Value
    // keys — v1 renders them Map(K -> V, …) with unquoted scalars; "<foreign>"
    // broke output parity for every SELECT-printing example. METHOD-OBJECTS
    // are ForeignV(Map[String, Value]) — keep them out of this arm (the cast
    // on their String keys crashed typeclass/generators with a CCE) and let
    // them fall to Show.show as before.
    case ForeignV(m: collection.Map[?, ?])
        if m.keysIterator.forall(_.isInstanceOf[Value]) =>
      s"Map(${m.asInstanceOf[collection.Map[Value, Value]].map((k, x) => s"${anyStr(k)} -> ${anyStr(x)}").mkString(", ")})"
    case _ => Show.show(v)
  private def bytes(a: List[Value], k: Int): Vector[Byte] = a(k) match { case BytesV(b) => b; case v => sys.error(s"expected Bytes, got ${Show.show(v)}") }
  private def bool(a: List[Value], k: Int): Boolean = a(k) match { case BoolV(b) => b; case v => sys.error(s"expected Bool, got ${Show.show(v)}") }
  private def asData(v: Value): (String, IndexedSeq[Value]) = v match { case DataV(t, fs) => (t, fs); case x => sys.error(s"expected Data, got ${Show.show(x)}") }
  private def asMap(v: Value): collection.mutable.Map[Value, Value] = v match
    case MapV(entries) => entries
    case ForeignV(m: collection.mutable.Map[Value, Value] @unchecked) => m
    case x => sys.error(s"expected Map, got ${Show.show(x)}")
  private def asArr(v: Value) = v match { case ForeignV(a: collection.mutable.ArrayBuffer[Value] @unchecked) => a; case x => sys.error(s"expected Array, got ${Show.show(x)}") }
  private def asCell(v: Value) = v match { case ForeignV(c: Array[Value] @unchecked) => c; case x => sys.error(s"expected Cell, got ${Show.show(x)}") }

  // Option / list helpers (the ssc0 ADT encoding the kernel speaks)
  private val none: Value = DataV("None", IndexedSeq.empty)
  private def some(v: Value): Value = DataV("Some", collection.immutable.ArraySeq(v))
  private def isList(v: Value): Boolean = v match
    case DataV("Nil", _) | DataV("Cons", _) => true
    case ForeignV(_: collection.mutable.ArrayBuffer[?]) => true
    case _ => false
  private def callClos(fn: Value.ClosV, args: Array[Value]): Value =
    Runtime.run(fn.code, if args.isEmpty then fn.env else Runtime.extend(fn.env, args))
  /** Run a `try` body thunk; on a ScalaScript `throw` or a host RuntimeException, invoke the catch
    * handler with the caught value. Non-RuntimeException host errors propagate to the boundary. */
  private def tryRun(body: Value.ClosV, handler: Value.ClosV): Value =
    try callClos(body, Array.empty)
    catch
      // A non-local `return` inside a `try` exits the method — it is NOT caught by
      // the user's `catch`. Re-throw so it unwinds to the enclosing __with_return__.
      case r: ReturnThrow      => throw r
      case c: ControlRunFailure => throw c
      case t: SscThrow         => callClos(handler, Array(t.value))
      case e: RuntimeException =>
        callClos(handler, Array(Value.DataV("RuntimeException",
          Vector(Value.StrV(Option(e.getMessage).getOrElse(""))))))
  private val valueOrdering: Ordering[Value] = Ordering.fromLessThan(valueLessThan)
  // Lexicographic less-than over scalars AND tuples, so `sortBy(x => (k1, k2))`
  // (common for multi-key sorts) orders correctly instead of leaving the list
  // untouched (tuples used to fall to `case _ => false` = never-less-than).
  private def valueLessThan(x: Value, y: Value): Boolean = (x, y) match
    case (IntV(a), IntV(b))     => a < b
    case (FloatV(a), FloatV(b)) => a < b
    case (StrV(a), StrV(b))     => a < b
    case (IntV(a), FloatV(b))   => a.toDouble < b
    case (FloatV(a), IntV(b))   => a < b.toDouble
    case (DataV("Tuple2", Seq(a1, b1)), DataV("Tuple2", Seq(a2, b2))) =>
      if valueLessThan(a1, a2) then true
      else if valueLessThan(a2, a1) then false
      else valueLessThan(b1, b2)
    case (DataV("Tuple3", Seq(a1, b1, c1)), DataV("Tuple3", Seq(a2, b2, c2))) =>
      if valueLessThan(a1, a2) then true
      else if valueLessThan(a2, a1) then false
      else if valueLessThan(b1, b2) then true
      else if valueLessThan(b2, b1) then false
      else valueLessThan(c1, c2)
    case _                      => false
  private def listOf(vs: Seq[Value]): Value =
    var acc: Value = DataV("Nil", IndexedSeq.empty)
    val it = vs.reverseIterator
    while it.hasNext do
      acc = DataV("Cons", collection.immutable.ArraySeq(it.next(), acc))
    acc
  private def strList(xs: Seq[String]): Value = listOf(xs.map(StrV(_)))
  private def unlist(v: Value): List[Value] = unlistPub(v)
  def unlistPub(v: Value): List[Value] =
    val out = collection.mutable.ListBuffer.empty[Value]
    var cur = v
    var done = false
    while !done do
      cur match
        case ForeignV(ab: collection.mutable.ArrayBuffer[?]) =>
          out ++= ab.asInstanceOf[collection.mutable.ArrayBuffer[Value]]
          done = true
        case DataV("Cons", Seq(h, t)) =>
          out += h
          cur = t
        case DataV("Nil", _) =>
          done = true
        case x =>
          sys.error(s"expected a list, got ${Show.show(x)}")
    out.toList

  // O(i) list indexed access without materializing the whole list; used by tryFLC App path.
  def listAt(v: Value, i: Int): Value = v match
    case DataV("Cons", Seq(h, _)) if i == 0 => h
    case DataV("Cons", Seq(_, t))            => listAt(t, i - 1)
    case _ => sys.error(s"list index out of bounds: $i")

  private def out(v: Value, ps: java.io.PrintStream): Unit = v match
    case StrV(s) => ps.print(s)
    // Containers render via anyStr (the parity display renderer: unquoted nested
    // strings — Some(x), List(a, b)), NOT Show.show, which quotes StrV children and
    // broke println parity on the native front (Some("x") / List("a", "b")). This
    // matches __autoPrint__ (line ~2024) and the anyStr contract documented above.
    case _ => ps.print(anyStr(v))

// coreir.encode — serialize an IR-as-Data tree (built by an ssc0 program) to the
// canonical Core IR text (specs/12-ir-format.md). The format stays owned by the kernel
// in ONE place; ssc0 emits IR by building Data and calling this. Tags: IrProg/IrDef/
// IrLit/IrLocal/IrGlobal/IrLam/IrApp/IrLet/IrLetRec/IrIf/IrCtor/IrMatch/IrArm/IrPrim and
// consts IrUnit/IrBool/IrInt/IrBig/IrFloat/IrStr. Lists = Cons/Nil, Option = Some/None.
object IrEncode:
  import Value.*
  def program(v: Value): String = v match
    case DataV("IrProg", Seq(defs, entry)) =>
      val ds = list(defs).map {
        case DataV("IrDef", Seq(StrV(n), b)) => s"(def $n ${term(b)})"
        case x => sys.error(s"coreir.encode: bad IrDef ${Show.show(x)}")
      }
      val defsStr = if ds.isEmpty then "(defs)" else s"(defs ${ds.mkString(" ")})"
      s"(program $defsStr (entry ${term(entry)}))"
    case x => sys.error(s"coreir.encode: expected IrProg, got ${Show.show(x)}")

  private def term(v: Value): String = v match
    case DataV("IrLit", Seq(c))            => s"(lit ${const(c)})"
    case DataV("IrLocal", Seq(IntV(i)))    => s"(local $i)"
    case DataV("IrGlobal", Seq(StrV(n)))   => s"(global $n)"
    case DataV("IrLam", Seq(IntV(ar), b))  => s"(lam $ar ${term(b)})"
    case DataV("IrApp", Seq(f, args))      => s"(app ${term(f)}${list(args).map(x => " " + term(x)).mkString})"
    case DataV("IrLet", Seq(rhs, b))       => s"(let (${list(rhs).map(term).mkString(" ")}) ${term(b)})"
    case DataV("IrLetRec", Seq(lams, b))   => s"(letrec (${list(lams).map(term).mkString(" ")}) ${term(b)})"
    case DataV("IrIf", Seq(c, t, e))       => s"(if ${term(c)} ${term(t)} ${term(e)})"
    case DataV("IrCtor", Seq(StrV(tg), fs))=> s"(ctor $tg${list(fs).map(x => " " + term(x)).mkString})"
    case DataV("IrPrim", Seq(StrV(op), as))=> s"(prim $op${list(as).map(x => " " + term(x)).mkString})"
    case DataV("IrWhile", Seq(c, b))       => s"(while ${term(c)} ${term(b)})"
    case DataV("IrSeq",   Seq(ts))         => s"(seq${list(ts).map(x => " " + term(x)).mkString})"
    case DataV("IrMatch", Seq(s, arms, d)) =>
      val a = list(arms).map {
        case DataV("IrArm", Seq(StrV(tg), IntV(ar), b)) => s"(arm $tg $ar ${term(b)})"
        case x => sys.error(s"coreir.encode: bad IrArm ${Show.show(x)}")
      }.mkString(" ")
      val dd = d match
        case DataV("Some", Seq(t)) => s" (default ${term(t)})"
        case DataV("None", _) => ""
        case x => sys.error(s"coreir.encode: bad default ${Show.show(x)}")
      s"(match ${term(s)} ($a)$dd)"
    case x => sys.error(s"coreir.encode: bad term ${Show.show(x)}")

  /** Every case renders through the kernel-owned [[Writer.const]] rather than re-spelling
    * the canonical syntax here. The old hand-rolled copies had drifted twice over:
    *   - `IrBytes` had **no case at all**, so `coreir.encode` of a bytes literal died with
    *     "bad const" even though `Const.CBytes`, the Reader, the Writer and `IrDecode` all
    *     support bytes — an asymmetric codec (`specs/coreir-inventory-gate.sh` now compares
    *     the encode and decode tag sets and fails on exactly this);
    *   - floats went through `Writer.floatStr` (the *user-visible* renderer) instead of the
    *     canonical `Writer.floatLit`, which silently destroyed `-0.0`.
    * Keeping one renderer means the canonical form has exactly one owner, per
    * `10-core-ir.md` invariant 6. The cases stay explicit (not a blanket delegation) so the
    * inventory gate can still enumerate the tag surface. */
  private def const(v: Value): String = v match
    case DataV("IrUnit", _)               => Writer.const(Const.CUnit)
    case DataV("IrBool", Seq(BoolV(b)))   => Writer.const(Const.CBool(b))
    case DataV("IrInt", Seq(IntV(n)))     => Writer.const(Const.CInt(n))
    case DataV("IrBig", Seq(BigV(n)))     => Writer.const(Const.CBig(n))
    case DataV("IrFloat", Seq(FloatV(d))) => Writer.const(Const.CFloat(d))
    case DataV("IrStr", Seq(StrV(s)))     => Writer.const(Const.CStr(s))
    case DataV("IrBytes", Seq(BytesV(b))) => Writer.const(Const.CBytes(b))
    case x => sys.error(s"coreir.encode: bad const ${Show.show(x)}")

  private def list(v: Value): List[Value] = v match
    case DataV("Cons", Seq(h, t)) => h :: list(t)
    case DataV("Nil", _) => Nil
    case x => sys.error(s"coreir.encode: expected list, got ${Show.show(x)}")

/** Structural decoder for the frozen self-hosted frontend ABI. Unlike
 *  [[Reader]], this consumes the `IrProg` value produced by the tower directly:
 *  no frontend-emitted CoreIR text crosses into the Scala seed. */
object IrDecode:
  import Value.*

  def program(v: Value): Program = v match
    case DataV("IrProg", Seq(defs, entry)) =>
      Program(list(defs).map(definition), term(entry))
    case x => sys.error(s"coreir.decode: expected IrProg, got ${Show.show(x)}")

  private def definition(v: Value): Def = v match
    case DataV("IrDef", Seq(StrV(name), body)) => Def(name, term(body))
    case x => sys.error(s"coreir.decode: bad IrDef ${Show.show(x)}")

  private def term(v: Value): Term = v match
    case DataV("IrLit", Seq(c))             => Term.Lit(constant(c))
    case DataV("IrLocal", Seq(IntV(i)))     => Term.Local(i.toInt)
    case DataV("IrGlobal", Seq(StrV(name))) => Term.Global(name)
    case DataV("IrLam", Seq(IntV(arity), body)) => Term.Lam(arity.toInt, term(body))
    case DataV("IrApp", Seq(fn, args))      => Term.App(term(fn), list(args).map(term))
    case DataV("IrLet", Seq(rhs, body))     => Term.Let(list(rhs).map(term), term(body))
    case DataV("IrLetRec", Seq(lams, body)) => Term.LetRec(list(lams).map(term), term(body))
    case DataV("IrIf", Seq(cond, yes, no))  => Term.If(term(cond), term(yes), term(no))
    case DataV("IrCtor", Seq(StrV(tag), fields)) => Term.Ctor(tag, list(fields).map(term))
    case DataV("IrPrim", Seq(StrV(op), args))    => Term.Prim(op, list(args).map(term))
    case DataV("IrWhile", Seq(cond, body))       => Term.While(term(cond), term(body))
    case DataV("IrSeq", Seq(terms))              => Term.Seq(list(terms).map(term))
    case DataV("IrMatch", Seq(scrutinee, arms, default)) =>
      val decodedArms = list(arms).map {
        case DataV("IrArm", Seq(StrV(tag), IntV(arity), body)) =>
          Arm(tag, arity.toInt, term(body))
        case x => sys.error(s"coreir.decode: bad IrArm ${Show.show(x)}")
      }
      val decodedDefault = default match
        case DataV("Some", Seq(body)) => Some(term(body))
        case DataV("None", _)         => None
        case x => sys.error(s"coreir.decode: bad default ${Show.show(x)}")
      Term.Match(term(scrutinee), decodedArms, decodedDefault)
    case x => sys.error(s"coreir.decode: bad term ${Show.show(x)}")

  private def constant(v: Value): Const = v match
    case DataV("IrUnit", _)              => Const.CUnit
    case DataV("IrBool", Seq(BoolV(b)))  => Const.CBool(b)
    case DataV("IrInt", Seq(IntV(n)))    => Const.CInt(n)
    case DataV("IrBig", Seq(BigV(n)))    => Const.CBig(n)
    case DataV("IrFloat", Seq(FloatV(d)))=> Const.CFloat(d)
    case DataV("IrStr", Seq(StrV(s)))    => Const.CStr(s)
    case DataV("IrBytes", Seq(BytesV(b)))=> Const.CBytes(b)
    case x => sys.error(s"coreir.decode: bad const ${Show.show(x)}")

  private def list(v: Value): List[Value] = Prims.unlistPub(v)

/** The Data-level inverse of [[IrDecode]]: rebuild the `IrProg` Data tree that the self-hosted
 *  tower consumes from a kernel [[Program]]. Together with the [[Reader]] this is the
 *  `coreir.decode : Str|Bytes -> IrProg` primitive, so `encode ∘ decode = canonicalize` and
 *  `decode ∘ encode = id` are expressible from `.ssc` (10-core-ir.md §5, 12-ir-format.md).
 *  Kernel-owned: the canonical wire form is read in exactly ONE place ([[Reader]]); this only
 *  re-shapes an already-parsed, already-validated term into the tower's `Ir*` Data vocabulary.
 *  Every tag/shape mirrors [[IrDecode]] and [[IrEncode]] so the three stay round-trippable. */
object IrToData:
  import Value.*
  private val nilV: Value = DataV("Nil", IndexedSeq.empty)
  private def cons(h: Value, t: Value): Value = DataV("Cons", collection.immutable.ArraySeq(h, t))
  private def listV(vs: List[Value]): Value = vs.foldRight(nilV)(cons)
  private def d0(tag: String): Value = DataV(tag, IndexedSeq.empty)
  private def d1(tag: String, a: Value): Value = DataV(tag, collection.immutable.ArraySeq(a))
  private def d2(tag: String, a: Value, b: Value): Value = DataV(tag, collection.immutable.ArraySeq(a, b))
  private def d3(tag: String, a: Value, b: Value, c: Value): Value = DataV(tag, collection.immutable.ArraySeq(a, b, c))

  def program(p: Program): Value = d2("IrProg", listV(p.defs.map(definition)), term(p.entry))

  private def definition(d: Def): Value = d2("IrDef", StrV(d.name), term(d.body))

  private def term(t: Term): Value = t match
    case Term.Lit(c)        => d1("IrLit", constant(c))
    case Term.Local(i)      => d1("IrLocal", IntV(i.toLong))
    case Term.Global(n)     => d1("IrGlobal", StrV(n))
    case Term.Lam(ar, b)    => d2("IrLam", IntV(ar.toLong), term(b))
    case Term.App(fn, as)   => d2("IrApp", term(fn), listV(as.map(term)))
    case Term.Let(rhs, b)   => d2("IrLet", listV(rhs.map(term)), term(b))
    case Term.LetRec(ls, b) => d2("IrLetRec", listV(ls.map(term)), term(b))
    case Term.If(c, th, el) => d3("IrIf", term(c), term(th), term(el))
    case Term.Ctor(tg, fs)  => d2("IrCtor", StrV(tg), listV(fs.map(term)))
    case Term.Prim(op, as)  => d2("IrPrim", StrV(op), listV(as.map(term)))
    case Term.While(c, b)   => d2("IrWhile", term(c), term(b))
    case Term.Seq(ts)       => d1("IrSeq", listV(ts.map(term)))
    case Term.Match(s, arms, default) =>
      val armsV = listV(arms.map(a => d3("IrArm", StrV(a.tag), IntV(a.arity.toLong), term(a.body))))
      val defV  = default match { case Some(x) => d1("Some", term(x)); case None => d0("None") }
      d3("IrMatch", term(s), armsV, defV)

  private def constant(c: Const): Value = c match
    case Const.CUnit     => d0("IrUnit")
    case Const.CBool(b)  => d1("IrBool", BoolV(b))
    case Const.CInt(n)   => d1("IrInt", IntV(n))
    case Const.CBig(n)   => d1("IrBig", BigV(n))
    case Const.CFloat(d) => d1("IrFloat", FloatV(d))
    case Const.CStr(s)   => d1("IrStr", StrV(s))
    case Const.CBytes(b) => d1("IrBytes", BytesV(b))

object Show:
  /** Pluggable renderer for opaque ForeignV payloads. The v1 bridge installs a
   *  callback that renders native v1 interpreter Values (DocV, MarkupV, …) via
   *  v1's own show — the v2 kernel itself stays v1-free. Return null to decline. */
  @volatile var foreignRenderer: (AnyRef => String | Null) | Null = null
  private[ssc] def tryForeign(h: AnyRef): String | Null =
    val fr = foreignRenderer
    if fr == null then null else fr(h)

  import Value.*
  def show(v: Value): String = v match
    case UnitV        => "()"
    case BoolV(x)     => x.toString
    case IntV(n)      => n.toString
    case BigV(n)      => n.toString
    case FloatV(d)    => Writer.floatStr(d)
    case DecimalV(s)  => s
    case StrV(s)      => "\"" + s + "\""
    case BytesV(bs)   => bs.map(x => f"${x & 0xff}%02x").mkString("#", "", "")
    case DataV("Cons", _) | DataV("Nil", _) =>
      def ul(x: Value): List[Value] = x match
        case DataV("Cons", Seq(h, t)) => h :: ul(t)
        case _ => Nil
      s"List(${ul(v).map(show).mkString(", ")})"
    case DataV("_Raw", fs) if fs.nonEmpty => fs.head match { case StrV(s) => s; case v => show(v) }
    // Source tuples are TupleN → `(a, b)`. "Pair" (a -> b arrows, mira's own tuples)
    // keeps its `Pair(a, b)` rendering via the generic ctor case below.
    case DataV(t, fs) if t.matches("Tuple\\d+") => s"(${fs.map(show).mkString(", ")})"
    case DataV(t, fs) => if fs.isEmpty then t else s"$t(${fs.map(show).mkString(", ")})"
    case MapV(entries) =>
      s"Map(${entries.iterator.map((k, value) => s"${show(k)} -> ${show(value)}").mkString(", ")})"
    case _: ClosV     => "<closure>"
    case ForeignV(nmo: NamedMethodObj) =>
      // A NamedMethodObj may expose a `_show` field (optics: `Lens(_.x)`, `Prism[?, Circle]`); use it
      // before the opaque foreign renderer / "<foreign>" fallback.
      nmo.getField("_show") match
        case Some(StrV(s)) => s
        case _ =>
          val r = tryForeign(nmo.underlying)
          if r == null then "<foreign>" else r
    case ForeignV(h)  =>
      val r = tryForeign(h)
      if r == null then "<foreign>" else r
    case c: LongCellV => s"<lcell:${c.v}>"
    case c: DoubleCellV => s"<dcell:${c.v}>"
