package ssc.bridge

import scalascript.backend.spi.{Backend, BlockContext, BlockForm, EffectHandler, IntrinsicImpl, NativeImpl, NativeContext, SpiValue}
import scalascript.interpreter.{DataValue, Value as V1Value}
import scalascript.interpreter.DataValue.*
import ssc.{Done, Runtime, Value as V2Value, V2EffectContext, V2PluginRegistry}

/** Loads v1 Backend plugins from the classpath and registers:
 *   1. NativeImpl intrinsics — via V2PluginRegistry.register (existing).
 *   2. BlockForm effect runners (runLogger, runState, …) — as synthetic v2
 *      ClosV globals in V2PluginRegistry.registerGlobal. Each runner installs
 *      a V2EffectContext handler for the duration of its body, then pops it.
 *      Logger.log/State.get/etc. dispatch through __method__ → V2EffectContext.
 *   3. `handle` global — runs the Free-monad loop for typed `handle { case … }` effects.
 *
 *  Usage: call PluginBridge.loadAll() before running any v2 program that needs plugins. */
object PluginBridge:

  /** Minimal NativeContext for stateless intrinsics (IO, hash, math, etc.). */
  private object MinimalCtx extends NativeContext:
    def out: java.io.PrintStream = Console.out
    def err: java.io.PrintStream = Console.err

  /** Load all Backend plugins via ServiceLoader; register NativeImpl prims AND
   *  BlockForm runners. Also registers the built-in `handle` global. */
  def loadAll(): Int =
    registerHandle()
    var count = 0
    val cl = Thread.currentThread().getContextClassLoader
    val loader = java.util.ServiceLoader.load(classOf[Backend], cl)
    val it = loader.iterator()
    while it.hasNext do
      scala.util.Try(it.next()).foreach { backend =>
        backend.intrinsics.foreach { case (qn, impl) =>
          val op = qn.toString
          impl match
            case NativeImpl(eval) =>
              V2PluginRegistry.register(op, args => {
                val v1Args: List[Any] = args.map(v2ToV1)
                val v1Result: Any = eval(MinimalCtx, v1Args)
                v1ToV2(v1Result)
              })
              count += 1
            case _ => // InlineCode / RuntimeCall: compile-time only, skip
        }
        backend.blockForms.foreach { case (runnerName, bf) =>
          registerBlockForm(runnerName, bf)
          count += 1
        }
      }
    count

  /** Load a specific Backend (e.g., for testing). */
  def loadBackend(backend: Backend): Int =
    registerHandle()
    var count = 0
    backend.intrinsics.foreach { case (qn, impl) =>
      val op = qn.toString
      impl match
        case NativeImpl(eval) =>
          V2PluginRegistry.register(op, args => {
            val v1Args: List[Any] = args.map(v2ToV1)
            val v1Result: Any = eval(MinimalCtx, v1Args)
            v1ToV2(v1Result)
          })
          count += 1
        case _ => // compile-time variants; not bridgeable
    }
    backend.blockForms.foreach { case (runnerName, bf) =>
      registerBlockForm(runnerName, bf)
      count += 1
    }
    count

  // ── BlockForm registration ──────────────────────────────────────────────────

  /** Register a BlockForm runner (e.g. runLogger, runState(s0)) as a v2 global.
   *
   *  Block-form call shapes handled:
   *   - 1-arg:  runLogger { body }   → Global("runLogger")(thunk)
   *   - 2-arg:  runState(s0) { body } → Global("runState")(s0)(thunk)
   *
   *  We register a curried 1-arg v2 ClosV that collects config args until it receives
   *  a thunk (arity-0 closure), then executes. Simple heuristic: the body thunk is
   *  always arity-0 (Lam(0, body)).  For multi-arg runners we curry eagerly. */
  private def registerBlockForm(name: String, bf: BlockForm): Unit =
    // Build a Scala-native v2 ClosV that acts as the runner global.
    // We pre-build a stable ClosV and capture it by reference.
    val runnerClosure = makeRunnerClosure(bf, accArgs = Nil)
    V2PluginRegistry.registerGlobal(name, runnerClosure)

  /** Recursively build a curried closure that collects config args until it sees
   *  a thunk (arity-0 ClosV), then executes the block-form. */
  private def makeRunnerClosure(bf: BlockForm, accArgs: List[V2Value]): V2Value =
    V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val arg = env.last // arity-1 called with [arg]; Local(0) = env.last
      arg match
        case thunk: V2Value.ClosV if thunk.arity == 0 =>
          // Got the body thunk — run the block form
          val cfgSpi: List[SpiValue] = accArgs.reverse.map(v2ToSpi)
          val bctx = makeBCtx()
          val handler = bf.newHandler(bctx, cfgSpi)
          val effectTag = bf.effectName
          val v2Handler: V2EffectContext.EH = (op, args) =>
            val spiArgs = args.map(v2ToSpi)
            val spiResult = handler.reply(op, spiArgs)
            spiToV2(spiResult)
          V2EffectContext.push(effectTag, v2Handler)
          val bodyResult = try
            callThunk(thunk)
          finally
            V2EffectContext.pop(effectTag)
          val spiBody = v2ToSpi(bodyResult)
          Done(spiToV2(bf.result(spiBody, handler)))
        case _ =>
          // Config arg — accumulate and return next curried closure
          Done(makeRunnerClosure(bf, arg :: accArgs))
    })

  /** Build a BlockContext for v2 use. applyFn calls v2 closure via Runtime.run. */
  private def makeBCtx(): BlockContext = new BlockContext:
    def out: java.io.PrintStream = Console.out
    override def applyFn(fn: SpiValue, args: List[SpiValue]): SpiValue =
      val fnV = spiToV2(fn)
      val argVs = args.map(spiToV2)
      v2ToSpi(callClosure(fnV, argVs))

  /** Call an arity-0 v2 ClosV (a thunk). */
  private def callThunk(c: V2Value.ClosV): V2Value =
    Runtime.run(c.code, c.env)

  /** Call any v2 closure with a list of args. */
  private def callClosure(fn: V2Value, args: List[V2Value]): V2Value = fn match
    case c: V2Value.ClosV =>
      val argsArr = args.toArray
      Runtime.run(c.code, if args.isEmpty then c.env else Runtime.extend(c.env, argsArr))
    case _ => sys.error(s"PluginBridge.callClosure: expected closure, got $fn")

  // ── `handle` global — Free-monad interpreter for typed effects ─────────────

  /** Register `handle` as a v2 global.
   *
   *  Typed effects compile to:
   *    handle(bodyResult)(handlerFn)
   *  where bodyResult may be:
   *    - DataV("Op", [StrV(label), arg, k])  — an effectful step (Free monad node)
   *    - any other value                      — pure result (treated as Return)
   *
   *  The handler lambda has shape: `lam 1 (match x { case op(args, resume) => ...; case Return(_) => ... })`
   *  so we call it with DataV(opName, [arg, resumeFn]) or DataV("Return", [result]).
   */
  private def registerHandle(): Unit =
    if V2PluginRegistry.lookupGlobal("handle").isDefined then return // idempotent
    // handle(bodyResult)(handlerFn) — curried, 2 sequential arity-1 applications
    val handleClosure = V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val bodyResult = env.last  // Local(0) = most recent arg
      // Inner closure captures bodyResult; when called with handler:
      // extend([bodyResult], [handler]) = [bodyResult, handler]
      // Local(0) = handler = env2.last; Local(1) = bodyResult = env2(0)
      Done(V2Value.ClosV(Array(bodyResult), 1, env2 => {
        val body    = env2(0)    // captured bodyResult
        val handler = env2.last  // new arg = handlerFn
        Done(runEffectLoop(body, handler))
      }))
    })
    V2PluginRegistry.registerGlobal("handle", handleClosure)
    // Also register "effect" as a no-op (FrontendBridge emits it for `effect Foo:` declarations)
    V2PluginRegistry.registerGlobal("effect", V2Value.ClosV(Runtime.emptyEnv, 1, _ => Done(V2Value.UnitV)))

  /** Run the Free-monad interpreter loop.
   *  - Op("EffTag.opName", arg, k): call handler with DataV(opName, [arg, resumeFn])
   *  - anything else:                call handler with DataV("Return", [v]) */
  private def runEffectLoop(v: V2Value, handler: V2Value): V2Value = v match
    case V2Value.DataV("Op", IndexedSeq(V2Value.StrV(label), arg, k)) =>
      val opName = label.split("\\.").last  // "Logger.log" → "log"
      // resumeFn captures k and handler; when called with r:
      // extend([k, handler], [r]) = [k, handler, r] → Local(0)=r=env2.last, Local(1)=handler=env2(1), Local(2)=k=env2(0)
      val resumeFn = V2Value.ClosV(Array(k.asInstanceOf[V2Value], handler), 1, env2 => {
        val resumeArg = env2.last // Local(0) = new arg r
        val kk        = env2(0)   // captured k (first in base env)
        val h         = env2(1)   // captured handler (second in base env)
        val next = callClosure(kk, List(resumeArg))
        Done(runEffectLoop(next, h))
      })
      val margs = arg match
        case V2Value.UnitV => List(resumeFn)
        case _             => List(arg, resumeFn)
      callClosure(handler, List(V2Value.DataV(opName, margs.toVector)))
    case _ =>
      // Pure result — call handler with Return(v)
      callClosure(handler, List(V2Value.DataV("Return", Vector(v))))

  // ── SpiValue ↔ V2Value conversion ─────────────────────────────────────────

  def v2ToSpi(v: V2Value): SpiValue = v match
    case V2Value.UnitV        => SpiValue.UnitV
    case V2Value.BoolV(b)     => SpiValue.BoolV(b)
    case V2Value.IntV(n)      => SpiValue.IntV(n)
    case V2Value.FloatV(d)    => SpiValue.DoubleV(d)
    case V2Value.StrV(s)      => SpiValue.StrV(s)
    case V2Value.BigV(n)      => SpiValue.BigIntV(n)
    case V2Value.DataV(tag, fields) =>
      SpiValue.ListV(fields.toList.map(v2ToSpi)) // best effort: fields as list
    case _                    => SpiValue.Opaque(v)

  def spiToV2(s: SpiValue): V2Value = s match
    case SpiValue.UnitV        => V2Value.UnitV
    case SpiValue.BoolV(b)     => V2Value.BoolV(b)
    case SpiValue.IntV(n)      => V2Value.IntV(n)
    case SpiValue.DoubleV(d)   => V2Value.FloatV(d)
    case SpiValue.StrV(s)      => V2Value.StrV(s)
    case SpiValue.BigIntV(n)   => V2Value.BigV(n)
    case SpiValue.ListV(items) =>
      items.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (x, acc) =>
        V2Value.DataV("Cons", Vector(spiToV2(x), acc))
      }
    case SpiValue.TupleV(elems) =>
      V2Value.DataV(s"Tuple${elems.length}", elems.map(spiToV2).toVector)
    case SpiValue.Opaque(v: V2Value) => v
    case SpiValue.Opaque(v)          => V2Value.ForeignV(v.asInstanceOf[AnyRef])

  // ── Value translation: v2 Value → v1 Value ─────────────────────────────

  /** Convert a v2 VM value to a v1 interpreter Value (for plugin arguments). */
  def v2ToV1(v: V2Value): V1Value = v match
    case V2Value.UnitV        => DataValue.UnitV
    case V2Value.BoolV(b)     => DataValue.BoolV(b)
    case V2Value.IntV(n)      => DataValue.IntV(n)
    case V2Value.FloatV(d)    => DataValue.DoubleV(d)
    case V2Value.StrV(s)      => DataValue.StringV(s)
    case V2Value.BigV(n)      => DataValue.BigIntV(n)
    case V2Value.BytesV(bs)   =>
      // Bytes: v1 has no BytesV; wrap as a list of IntV bytes
      val items = bs.toList.map(b => DataValue.IntV((b & 0xff).toLong): V1Value)
      scalascript.interpreter.Value.ListV(items)
    case V2Value.DataV(tag, fields) =>
      // Positional fields: expose as _0, _1, … for v1 InstanceV
      val fieldMap: Map[String, V1Value] = fields.zipWithIndex
        .map { case (fv, i) => s"_$i" -> v2ToV1(fv) }
        .toMap
      val inst = scalascript.interpreter.Value.InstanceV(tag, fieldMap)
      // Also populate positional arrays for v1 fast paths
      val arr: Array[V1Value] = fields.map(v2ToV1).toArray
      val names: Array[String] = fields.indices.map(i => s"_$i").toArray
      inst.fieldsArr = arr
      inst.fieldNames = names
      inst
    case V2Value.ForeignV(h) =>
      scalascript.interpreter.Value.Foreign(h.getClass.getSimpleName, h)
    case _ =>
      // Closures, LongCellV: not translatable — wrap as opaque Foreign
      scalascript.interpreter.Value.Foreign("v2Value", v.asInstanceOf[AnyRef])

  // ── Value translation: v1 Value → v2 Value ─────────────────────────────

  /** Convert a v1 interpreter Value to a v2 VM value (for plugin return values). */
  def v1ToV2(v: Any): V2Value = v match
    case DataValue.UnitV        => V2Value.UnitV
    case DataValue.BoolV(b)     => V2Value.BoolV(b)
    case DataValue.IntV(n)      => V2Value.IntV(n)
    case DataValue.DoubleV(d)   => V2Value.FloatV(d)
    case DataValue.StringV(s)   => V2Value.StrV(s)
    case DataValue.BigIntV(n)   => V2Value.BigV(n)
    case DataValue.DecimalV(d)  => V2Value.FloatV(d.toDouble)
    case DataValue.CharV(c)     => V2Value.StrV(c.toString)
    case DataValue.NullV        => V2Value.DataV("None", Vector.empty) // closest v2 equivalent
    case scalascript.interpreter.Value.InstanceV(tag, _) =>
      // Prefer positional fieldsArr if available (StatRuntime fast path)
      val inst = v.asInstanceOf[scalascript.interpreter.Value.InstanceV]
      val arr = inst.fieldsArr
      if arr != null then
        V2Value.DataV(tag, arr.toVector.map(v1ToV2))
      else
        V2Value.DataV(tag, inst.effectiveFields.values.toVector.map(v1ToV2))
    case scalascript.interpreter.Value.ListV(items) =>
      // Encode as a Cons/Nil chain (v2 list encoding)
      items.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (item, acc) =>
        V2Value.DataV("Cons", Vector(v1ToV2(item), acc))
      }
    case scalascript.interpreter.Value.VectorV(items) =>
      items.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (item, acc) =>
        V2Value.DataV("Cons", Vector(v1ToV2(item), acc))
      }
    case scalascript.interpreter.Value.OptionV(null) =>
      V2Value.DataV("None", Vector.empty)
    case scalascript.interpreter.Value.OptionV(inner) =>
      V2Value.DataV("Some", Vector(v1ToV2(inner)))
    case scalascript.interpreter.Value.TupleV(elems) =>
      V2Value.DataV(s"Tuple${elems.length}", elems.map(v1ToV2).toVector)
    case scalascript.interpreter.Value.Foreign(_, h: AnyRef) =>
      V2Value.ForeignV(h)
    case _ =>
      // Closures and other complex v1 values: wrap in ForeignV
      V2Value.ForeignV(v.asInstanceOf[AnyRef])
