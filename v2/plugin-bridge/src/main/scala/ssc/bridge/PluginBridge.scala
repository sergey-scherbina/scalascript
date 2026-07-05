package ssc.bridge

import scalascript.backend.spi.{Backend, BlockContext, BlockForm, EffectHandler, IntrinsicImpl, NativeImpl, NativeContext, SpiValue}
import scalascript.interpreter.{DataValue, Value as V1Value}
import scalascript.interpreter.DataValue.*
import ssc.{Done, Runtime, Show, Value as V2Value, V2EffectContext, V2PluginRegistry}

/** Loads v1 Backend plugins from the classpath and registers:
 *   1. NativeImpl intrinsics — via V2PluginRegistry.register (existing).
 *   2. BlockForm effect runners (runLogger, runState, …) — as synthetic v2
 *      ClosV globals in V2PluginRegistry.registerGlobal. Each runner installs
 *      a V2EffectContext handler for the duration of its body, then pops it.
 *      Logger.log/State.get/etc. dispatch through __method__ → V2EffectContext.
 *   3. `handle` global — runs the Free-monad loop for typed `handle { case … }` effects.
 *
 *  Usage: call PluginBridge.loadAll() before running any v2 program that needs plugins. */
/** Preserves the tag from v1 Value.Foreign("tag", h) through the v2 ForeignV round-trip. */
private final case class TaggedForeign(tag: String, value: AnyRef)

object PluginBridge:

  /** Minimal NativeContext for stateless intrinsics (IO, hash, math, etc.). */
  private object MinimalCtx extends NativeContext:
    def out: java.io.PrintStream = Console.out
    def err: java.io.PrintStream = Console.err

  /** Load all Backend plugins via ServiceLoader; register NativeImpl prims AND
   *  BlockForm runners. Also registers the built-in `handle` global. */
  def loadAll(): Int =
    registerHandle()
    registerActors()
    registerSys()
    registerInterpreterBuiltins()
    registerComputedCellDispatch()
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
              val nativeFn: List[V2Value] => V2Value = args => {
                val rawArgs: List[Any] = args.map(v2ToRaw)
                val rawResult: Any = eval(MinimalCtx, rawArgs)
                rawToV2(rawResult)
              }
              // Register as prim (for Prim(op, args) IR nodes)
              V2PluginRegistry.register(op, args => nativeFn(args))
              // Register as global (for App(Global(name), args) IR nodes)
              // arity=-1 = variadic: env = all call args (no captured env), skip arity check.
              V2PluginRegistry.registerGlobal(op, V2Value.ClosV(Runtime.emptyEnv, -1, env => {
                Done(nativeFn(env.toList))
              }))
              count += 1
            case _ => // InlineCode / RuntimeCall: compile-time only, skip
        }
        backend.blockForms.foreach { case (runnerName, bf) =>
          registerBlockForm(runnerName, bf)
          count += 1
        }
      }
    // 0-arity OS globals must be pre-computed values (not closures), because callers write
    // `cwd` not `cwd()`. Register AFTER plugins so we override any ClosV they registered.
    V2PluginRegistry.registerGlobal("cwd", V2Value.StrV(System.getProperty("user.dir", ".")))
    V2PluginRegistry.registerGlobal("sep", V2Value.StrV(java.io.File.separator))
    V2PluginRegistry.registerGlobal("platform", V2Value.DataV("JVM", Vector.empty))
    // Register plugin-owned extern type field names so v2ToV1(DataV) produces named fields.
    // These are types created in v2 user code but consumed by v1 plugins by name.
    V2PluginRegistry.registerFieldNames("Response",
      Vector("status", "headers", "body"))
    V2PluginRegistry.registerFieldNames("StreamResponse",
      Vector("status", "headers", "callback"))
    V2PluginRegistry.registerFieldNames("Request",
      Vector("method", "path", "body", "headers", "params", "query", "json", "form", "files", "session", "cookies", "bearerToken", "jwtClaims", "basicAuth"))
    // Build namespace objects for dotted globals: "oauth.authServer" → oauth = {authServer: ClosV}
    buildNamespaceObjects()
    count

  /** For each registered global of the form "a.b[.c...]", build nested namespace ForeignV(Map)
   *  objects so `oauth.authServer(...)` and `oauth.client.discoverAs(...)` work via __method__. */
  private def buildNamespaceObjects(): Unit =
    import V2Value.*
    // Collect all dotted names and group by their top-level prefix
    type NestedMap = scala.collection.mutable.Map[String, Any] // String→V2Value or String→NestedMap
    def treeOf(ns: NestedMap, parts: List[String], leaf: V2Value): Unit = parts match
      case Nil         => ()
      case key :: Nil  => ns(key) = leaf
      case key :: rest =>
        val child = ns.getOrElseUpdate(key, scala.collection.mutable.Map.empty[String, Any])
          .asInstanceOf[NestedMap]
        ns(key) = child
        treeOf(child, rest, leaf)

    val tree = scala.collection.mutable.Map.empty[String, NestedMap]
    V2PluginRegistry.allGlobalNames().foreach { name =>
      val parts = name.split("\\.").toList
      if parts.length >= 2 && parts.head.nonEmpty && !Character.isUpperCase(parts.head.charAt(0)) then
        val ns = tree.getOrElseUpdate(parts.head, scala.collection.mutable.Map.empty)
        V2PluginRegistry.lookupGlobal(name).foreach { fn => treeOf(ns, parts.tail, fn) }
    }

    def toForeignV(m: scala.collection.mutable.Map[String, Any]): V2Value =
      val im: scala.collection.immutable.Map[String, V2Value] =
        scala.collection.immutable.Map.from(m.map { case (k, v) =>
          k -> (v match
            case nested: scala.collection.mutable.Map[?, ?] =>
              toForeignV(nested.asInstanceOf[NestedMap])
            case v2: V2Value => v2
          )
        })
      ForeignV(im.asInstanceOf[AnyRef])

    tree.foreach { case (ns, methods) =>
      if V2PluginRegistry.lookupGlobal(ns).isEmpty then
        V2PluginRegistry.registerGlobal(ns, toForeignV(methods))
    }

  /** Load a specific Backend (e.g., for testing). */
  def loadBackend(backend: Backend): Int =
    registerHandle()
    var count = 0
    backend.intrinsics.foreach { case (qn, impl) =>
      val op = qn.toString
      impl match
        case NativeImpl(eval) =>
          val nativeFn: List[V2Value] => V2Value = args => {
            val rawArgs: List[Any] = args.map(v2ToRaw)
            rawToV2(eval(MinimalCtx, rawArgs))
          }
          V2PluginRegistry.register(op, args => nativeFn(args))
          V2PluginRegistry.registerGlobal(op, V2Value.ClosV(Runtime.emptyEnv, -1, env => Done(nativeFn(env.toList))))
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
  /** Register stubs for interpreter-internal globals that aren't in any SPI plugin.
   *  These are complex language features (async, generators, signals, storage, setArgs).
   *  Stubs allow programs to load/initialize without crashing on unbound; actual
   *  functionality requires proper implementation (open TODO). */
  private def registerInterpreterBuiltins(): Unit =
    import V2Value.*
    // println / print — variadic so println() works (0-arg prints a blank line)
    def showForPrint(v: V2Value): String = v match
      case V2Value.StrV(s) => s
      case other           => Show.show(other)
    if V2PluginRegistry.lookupGlobal("println").isEmpty then
      V2PluginRegistry.registerGlobal("println", ClosV(Runtime.emptyEnv, -1, env => {
        if env.isEmpty then Console.out.println()
        else Console.out.println(showForPrint(env(0)))
        Done(UnitV)
      }))
    if V2PluginRegistry.lookupGlobal("print").isEmpty then
      V2PluginRegistry.registerGlobal("print", ClosV(Runtime.emptyEnv, -1, env => {
        if env.nonEmpty then Console.out.print(showForPrint(env(0)))
        Done(UnitV)
      }))
    // setHttpServerBackend(name) — no-op in v2 (backend is fixed)
    if V2PluginRegistry.lookupGlobal("setHttpServerBackend").isEmpty then
      V2PluginRegistry.registerGlobal("setHttpServerBackend", ClosV(Runtime.emptyEnv, -1, _ => Done(UnitV)))
    // runAsync { body } — run the body immediately (no async in v2 yet)
    if V2PluginRegistry.lookupGlobal("runAsync").isEmpty then
      V2PluginRegistry.registerGlobal("runAsync", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.last
        Done(thunk match
          case c: ClosV => callClosure(c, Nil)
          case v => v)
      }))
    // runAsyncParallel { body } — same stub as runAsync
    if V2PluginRegistry.lookupGlobal("runAsyncParallel").isEmpty then
      V2PluginRegistry.registerGlobal("runAsyncParallel", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.last
        Done(thunk match
          case c: ClosV => callClosure(c, Nil)
          case v => v)
      }))
    // computed(f) — returns a thunk-based pseudo-signal cell that re-evaluates on .get
    if V2PluginRegistry.lookupGlobal("computed").isEmpty then
      V2PluginRegistry.registerGlobal("computed", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.last
        // Wrap thunk in a special ComputedCell: ForeignV(Array) where Array holds the thunk
        // The .get dispatch will call the thunk to get the current value
        thunk match
          case c: ClosV => Done(ForeignV(new ComputedCell(c).asInstanceOf[AnyRef]))
          case v => Done(ForeignV(Array[V2Value](v).asInstanceOf[AnyRef]))
      }))
    // generator { body } — returns a stub generator that always ends
    if V2PluginRegistry.lookupGlobal("generator").isEmpty then
      V2PluginRegistry.registerGlobal("generator", ClosV(Runtime.emptyEnv, -1, env => {
        Done(ForeignV(new StubGenerator(env.lastOption.getOrElse(UnitV)).asInstanceOf[AnyRef]))
      }))
    // runEphemeralStorage { body } — run body with an in-memory map store
    if V2PluginRegistry.lookupGlobal("runEphemeralStorage").isEmpty then
      V2PluginRegistry.registerGlobal("runEphemeralStorage", ClosV(Runtime.emptyEnv, -1, env => {
        val thunk = env.last
        val map = scala.collection.mutable.LinkedHashMap.empty[String, String]
        val prev = storageLocal.get()
        storageLocal.set(map)
        val result = try thunk match
          case c: ClosV => callClosure(c, Nil)
          case v => v
        finally storageLocal.set(prev)
        Done(result)
      }))
    // args — command-line args as a Cons/Nil list
    if V2PluginRegistry.lookupGlobal("args").isEmpty then
      val argsList = sys.props.get("scalascript.args")
        .map(_.split(",").toList)
        .getOrElse(Nil)
        .foldRight[V2Value](DataV("Nil", Vector.empty)) { (a, acc) =>
          DataV("Cons", Vector(StrV(a), acc))
        }
      V2PluginRegistry.registerGlobal("args", argsList)
    // startNode — cluster node stub
    if V2PluginRegistry.lookupGlobal("startNode").isEmpty then
      V2PluginRegistry.registerGlobal("startNode", ClosV(Runtime.emptyEnv, -1, _ => Done(UnitV)))
    // contentBlock — content rendering stub
    if V2PluginRegistry.lookupGlobal("contentBlock").isEmpty then
      V2PluginRegistry.registerGlobal("contentBlock", ClosV(Runtime.emptyEnv, -1, env =>
        Done(env.lastOption.getOrElse(UnitV))))
    if V2PluginRegistry.lookupGlobal("nanoTime").isEmpty then
      V2PluginRegistry.registerGlobal("nanoTime", ClosV(Runtime.emptyEnv, 0, _ => Done(IntV(System.nanoTime()))))
    // __throw__ — lowered from throw expressions in FrontendBridge
    if V2PluginRegistry.lookupGlobal("__throw__").isEmpty then
      V2PluginRegistry.registerGlobal("__throw__", ClosV(Runtime.emptyEnv, 1, env => {
        val msg = env.last match
          case StrV(s) => s
          case DataV(tag, fields) => s"$tag(${fields.mkString(", ")})"
          case v => v.toString
        throw new scalascript.interpreter.InterpretError(msg)
      }))
    // getenv(key[, default]) — interpreter built-in not in any SPI plugin
    if V2PluginRegistry.lookupGlobal("getenv").isEmpty then
      V2PluginRegistry.registerGlobal("getenv", ClosV(Runtime.emptyEnv, -1, env => {
        val key = env.headOption match { case Some(StrV(s)) => s; case Some(v) => v.toString; case None => "" }
        val default_ = if env.length >= 2 then env(1) else StrV("")
        val v = System.getenv(key)
        Done(if v != null && v.nonEmpty then StrV(v) else default_)
      }))
    // Decimal / BigDecimal — exact numeric type backed by java.math.BigDecimal
    if V2PluginRegistry.lookupGlobal("Decimal").isEmpty then
      registerDecimalSupport()
    // BigInt factory: BigInt(n) / BigInt("123")
    if V2PluginRegistry.lookupGlobal("BigInt").isEmpty then
      V2PluginRegistry.registerGlobal("BigInt", ClosV(Runtime.emptyEnv, -1, env => env.toList match
        case List(IntV(n)) => Done(BigV(BigInt(n)))
        case List(StrV(s)) => Done(BigV(BigInt(s)))
        case List(BigV(n)) => Done(BigV(n))
        case _             => Done(BigV(BigInt(0)))
      ))
    // Exception factories used in throw expressions
    Seq("RuntimeException","Exception","IllegalArgumentException","IllegalStateException","UnsupportedOperationException").foreach { exName =>
      if V2PluginRegistry.lookupGlobal(exName).isEmpty then
        V2PluginRegistry.registerGlobal(exName, ClosV(Runtime.emptyEnv, -1, env => {
          val msg = env.headOption.map {
            case StrV(s) => s; case v => v.toString
          }.getOrElse(exName)
          Done(DataV(exName, Vector(StrV(msg))))
        }))
    }

  private def registerDecimalSupport(): Unit =
    import java.math.{BigDecimal => JBD, RoundingMode => JRM, MathContext}
    import V2Value.*
    def toBD(v: V2Value): JBD = v match
      case StrV(s)           => new JBD(s.trim)
      case IntV(n)           => JBD.valueOf(n)
      case FloatV(d)         => JBD.valueOf(d)
      case ForeignV(bd: JBD) => bd
      case DataV("Decimal", fields) if fields.nonEmpty => toBD(fields.head)
      case _                 => throw scalascript.interpreter.InterpretError(s"Decimal: not a number: $v")
    def fromBD(bd: JBD): V2Value = ForeignV(bd)
    def toRM(v: V2Value): JRM = v match
      case DataV(name, _)    => try JRM.valueOf(name) catch case _: Throwable => JRM.HALF_UP
      case ForeignV(rm: JRM) => rm
      case _                 => JRM.HALF_UP
    val decimalCtor = ClosV(Runtime.emptyEnv, -1, env => env.toList match
      case List(v: V2Value)    => Done(fromBD(toBD(v)))
      case List(u: V2Value, s: V2Value) =>
        val unscaled = u match { case IntV(n) => BigInt(n); case BigV(n) => n; case _ => BigInt(toBD(u).unscaledValue()) }
        Done(fromBD(new JBD(unscaled.bigInteger, s match { case IntV(n) => n.toInt; case _ => 0 })))
      case _ => Done(fromBD(JBD.ZERO))
    )
    V2PluginRegistry.registerGlobal("Decimal", decimalCtor)
    V2PluginRegistry.registerGlobal("BigDecimal", decimalCtor)
    // RoundingMode object with fields
    val rmMap: Map[String, V2Value] = Seq("UP","DOWN","CEILING","FLOOR","HALF_UP","HALF_DOWN","HALF_EVEN","UNNECESSARY")
      .map(n => n -> ForeignV(JRM.valueOf(n))).toMap
    V2PluginRegistry.registerGlobal("RoundingMode", ForeignV(rmMap.asInstanceOf[AnyRef]))
    // Dispatch setScale/round/add/sub/mul/div/compareTo on ForeignV(JBD)
    V2PluginRegistry.register("__method__.setScale", args => args(1) match
      case ForeignV(bd: JBD) =>
        val scale = args(2) match { case IntV(n) => n.toInt; case _ => 2 }
        val rm    = if args.length >= 4 then toRM(args(3)) else JRM.HALF_UP
        fromBD(bd.setScale(scale, rm))
      case _ => sys.error(s"__method__: no dispatch for .setScale on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.round", args => args(1) match
      case ForeignV(bd: JBD) =>
        val scale = args(2) match { case IntV(n) => n.toInt; case FloatV(d) => d.toInt; case _ => 0 }
        fromBD(bd.setScale(scale, JRM.HALF_UP))
      case _ => sys.error(s"__method__: no dispatch for .round on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.toInt", args => args(1) match
      case ForeignV(bd: JBD) => IntV(bd.longValueExact())
      case _ => sys.error(s"__method__: no dispatch for .toInt on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.toLong", args => args(1) match
      case ForeignV(bd: JBD) => IntV(bd.longValueExact())
      case _ => sys.error(s"__method__: no dispatch for .toLong on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.toDouble", args => args(1) match
      case ForeignV(bd: JBD) => FloatV(bd.doubleValue())
      case _ => sys.error(s"__method__: no dispatch for .toDouble on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.scale", args => args(1) match
      case ForeignV(bd: JBD) => IntV(bd.scale())
      case _ => sys.error(s"__method__: no dispatch for .scale on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.unscaledValue", args => args(1) match
      case ForeignV(bd: JBD) => BigV(BigInt(bd.unscaledValue()))
      case _ => sys.error(s"__method__: no dispatch for .unscaledValue on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.compareTo", args => args(1) match
      case ForeignV(x: JBD) => args(2) match
        case ForeignV(y: JBD) => IntV(x.compareTo(y))
        case IntV(n)          => IntV(x.compareTo(JBD.valueOf(n)))
        case v                => IntV(x.compareTo(toBD(v)))
      case _ => sys.error(s"__method__: no dispatch for .compareTo on ${args(1)}")
    )
    V2PluginRegistry.register("__method__.abs", args => args(1) match
      case ForeignV(bd: JBD) => fromBD(bd.abs())
      case _ => sys.error(s"__method__: no dispatch for .abs on ${args(1)}")
    )
    // Arithmetic on Decimal: __arith__ dispatches "+","-","*","/" etc.
    V2PluginRegistry.register("decimal.add", args => fromBD(toBD(args(0)).add(toBD(args(1)))))
    V2PluginRegistry.register("decimal.sub", args => fromBD(toBD(args(0)).subtract(toBD(args(1)))))
    V2PluginRegistry.register("decimal.mul", args => fromBD(toBD(args(0)).multiply(toBD(args(1)))))
    V2PluginRegistry.register("decimal.div", args => {
      val (a, b) = (toBD(args(0)), toBD(args(1)))
      fromBD(a.divide(b, a.scale().max(b.scale()).max(10), JRM.HALF_UP))
    })
    V2PluginRegistry.register("decimal.lt",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) < 0))
    V2PluginRegistry.register("decimal.le",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) <= 0))
    V2PluginRegistry.register("decimal.gt",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) > 0))
    V2PluginRegistry.register("decimal.ge",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) >= 0))
    V2PluginRegistry.register("decimal.eq",  args => BoolV(toBD(args(0)).compareTo(toBD(args(1))) == 0))

  /** Register dispatch for ComputedCell.get and Storage.get. */
  private def registerComputedCellDispatch(): Unit =
    V2PluginRegistry.register("__method__.get", args => {
      // args = [StrV("get"), recv, ...margs]
      if args.length >= 2 then args(1) match
        case V2Value.ForeignV(cell: ComputedCell) => cell.get()
        case V2Value.DataV("Storage", _) =>
          val key = if args.length >= 3 then args(2) match { case V2Value.StrV(s) => s; case v => v.toString } else ""
          val map = storageLocal.get()
          if map == null then sys.error("Storage.get called outside runEphemeralStorage / runStorage")
          val sv = map.getOrElse(key, null)
          if sv != null then V2Value.DataV("Some", Vector(V2Value.StrV(sv)))
          else V2Value.DataV("None", Vector.empty)
        case other => sys.error(s"__method__.get: no handler for $other")
      else sys.error(s"__method__.get: too few args")
    })
    V2PluginRegistry.register("__method__.put", args => {
      if args.length >= 4 then args(1) match
        case V2Value.DataV("Storage", _) =>
          val key = args(2) match { case V2Value.StrV(s) => s; case v => v.toString }
          val value = args(3) match { case V2Value.StrV(s) => s; case v => v.toString }
          val map = storageLocal.get()
          if map != null then map(key) = value
          V2Value.UnitV
        case other => sys.error(s"__method__.put: no handler for $other")
      else sys.error(s"__method__.put: too few args")
    })
    V2PluginRegistry.register("__method__.remove", args => {
      if args.length >= 3 then args(1) match
        case V2Value.DataV("Storage", _) =>
          val key = args(2) match { case V2Value.StrV(s) => s; case v => v.toString }
          val map = storageLocal.get()
          if map != null then map.remove(key)
          V2Value.UnitV
        case other => sys.error(s"__method__.remove: no handler for $other")
      else sys.error(s"__method__.remove: too few args")
    })
    V2PluginRegistry.register("__method__.has", args => {
      if args.length >= 3 then args(1) match
        case V2Value.DataV("Storage", _) =>
          val key = args(2) match { case V2Value.StrV(s) => s; case v => v.toString }
          val map = storageLocal.get()
          V2Value.BoolV(map != null && map.contains(key))
        case other => sys.error(s"__method__.has: no handler for $other")
      else sys.error(s"__method__.has: too few args")
    })
    V2PluginRegistry.register("__method__.keys", args => {
      if args.length >= 2 then args(1) match
        case V2Value.DataV("Storage", _) =>
          val map = storageLocal.get()
          val keys = if map != null then map.keys.toList else Nil
          keys.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (k, acc) =>
            V2Value.DataV("Cons", Vector(V2Value.StrV(k), acc))
          }
        case other => sys.error(s"__method__.keys: no handler for $other")
      else sys.error(s"__method__.keys: too few args")
    })
    V2PluginRegistry.register("__method__.apply", args => {
      if args.length >= 2 then args(1) match
        case V2Value.ForeignV(cell: ComputedCell) => cell.get()
        case other => sys.error(s"__method__.apply: no handler for $other")
      else sys.error(s"__method__.apply: too few args")
    })

  /** Thread-local in-memory storage map for runEphemeralStorage / runStorage. */
  private val storageLocal: ThreadLocal[scala.collection.mutable.LinkedHashMap[String, String]] =
    new ThreadLocal()

  /** Computed signal: re-evaluates its thunk on every .get call (no reactive tracking). */
  private class ComputedCell(val thunk: V2Value.ClosV):
    def get(): V2Value = callClosure(thunk, Nil)

  /** Minimal stub generator (no yield support; stateless). */
  private class StubGenerator(thunk: V2Value):
    def hasNext: Boolean = false
    def next(): V2Value = sys.error("generator: hasNext was false")

  /** Register `sys` global: sys.env(key) / sys.env.getOrElse(k,d) / sys.exit(code). */
  private def registerSys(): Unit =
    import V2Value.*
    if V2PluginRegistry.lookupGlobal("sys").isDefined then return
    // envGetOrElse: called when sys.env.getOrElse(key, default) is used
    val envGetOrElse = ClosV(Runtime.emptyEnv, 2, env => {
      val key = env(env.length - 2) match { case StrV(s) => s; case v => v.toString }
      val dflt = env.last
      Done(Option(System.getenv(key)).map(StrV.apply).getOrElse(dflt))
    })
    val envGet = ClosV(Runtime.emptyEnv, 1, env => {
      val key = env.last match { case StrV(s) => s; case v => v.toString }
      Done(Option(System.getenv(key)).map(s => DataV("Some", Vector(StrV(s)))).getOrElse(DataV("None", Vector.empty)))
    })
    // envObj: sys.env alone → ForeignV(Map) with getOrElse/get methods for chaining
    val envObj = ForeignV(scala.collection.immutable.Map[String, V2Value](
      "getOrElse" -> envGetOrElse,
      "get"       -> envGet,
    ).asInstanceOf[AnyRef])
    // Register __method__.env handler: called when sys.env("KEY") or sys.env is accessed
    // (Runtime.scala ForeignV(Map) dispatch falls through to plugin when value is a ForeignV)
    V2PluginRegistry.register("__method__.env", args => {
      val margs = args.drop(2)  // args = [name, recv, ...margs]
      margs match
        case Nil           => envObj  // sys.env alone → envObj for chaining
        case List(StrV(k)) => Option(System.getenv(k)).map(StrV.apply).getOrElse(DataV("None", Vector.empty))
        case _             => DataV("None", Vector.empty)
    })
    val sysObj = ForeignV(scala.collection.immutable.Map[String, V2Value](
      "env"  -> envObj,  // 0-arg: sys.env → envObj; "KEY"-arg: handled by __method__.env plugin
      "exit" -> ClosV(Runtime.emptyEnv, 1, env => {
        System.exit(env.last match { case IntV(n) => n.toInt; case _ => 0 })
        Done(UnitV)
      }),
    ).asInstanceOf[AnyRef])
    V2PluginRegistry.registerGlobal("sys", sysObj)

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

  // ── Actor runtime — VirtualThread-per-actor model ───────────────────────────

  /** One actor's mailbox + thread state. */
  private class ActorMailbox:
    val queue = new java.util.concurrent.LinkedBlockingQueue[V2Value]()
    @volatile var thread: Thread | Null = null
    @volatile var dead: Boolean = false

  private val actorTL = new ThreadLocal[ActorMailbox | Null]:
    override def initialValue(): ActorMailbox | Null = null


  /** Register actor globals: spawn, receive, self, exit, runActors.
   *  `!` is dispatched via __arith__("!", actorRef, msg) in Runtime.scala's
   *  dispatch table (separate patch), so it is wired via V2PluginRegistry.register. */
  /** Build a receive-with-timeout closure. Returns a ClosV(arity=1) that takes a handler
   *  and either calls it with the message (returning Some(result)) or returns None on timeout. */
  private def receiveWithTimeout(ms: Long): V2Value =
    V2Value.ClosV(Array(V2Value.IntV(ms)), 1, env2 => {
      val handler = env2.last
      val timeoutMs = env2(0).asInstanceOf[V2Value.IntV].n
      val mb = actorTL.get()
      if mb == null then sys.error("receive(timeout) called outside an actor")
      val msg = if timeoutMs < 0 then mb.queue.take()
                else mb.queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      if msg == null || mb.dead then Done(V2Value.DataV("None", Vector.empty))
      else Done(V2Value.DataV("Some", Vector(callClosure(handler.asInstanceOf[V2Value.ClosV], List(msg)))))
    })

  /** Read the @timeout cell registered by registerActors. Returns -1 if unset. */
  private def readTimeoutMs(): Long =
    V2PluginRegistry.lookupGlobal("@timeout") match
      case Some(V2Value.ForeignV(arr: Array[V2Value] @unchecked)) =>
        arr(0) match { case V2Value.IntV(n) => n; case _ => -1L }
      case _ => -1L

  private def registerActors(): Unit =
    if V2PluginRegistry.lookupGlobal("runActors").isDefined then return
    // Register @timeout cell (used by receive(timeout = n) named-arg pattern)
    val timeoutCell = Array[V2Value](V2Value.IntV(-1))
    V2PluginRegistry.registerGlobal("@timeout", V2Value.ForeignV(timeoutCell.asInstanceOf[AnyRef]))

    // spawn { () => body } — starts a VirtualThread, returns ForeignV(mailbox)
    V2PluginRegistry.registerGlobal("spawn", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val thunk = env.last
      val mb = new ActorMailbox()
      val t = Thread.ofVirtual().start(() => {
        actorTL.set(mb)
        mb.thread = Thread.currentThread()
        try callThunk(thunk.asInstanceOf[V2Value.ClosV])
        catch case _: InterruptedException => ()
        ()
      })
      mb.thread = t
      Done(V2Value.ForeignV(mb))
    }))

    // receive { case msg => ... } — blocks on mailbox, calls handler with msg
    // receive(timeout = n) { case msg => ... } — with timeout (returns Some/None)
    V2PluginRegistry.registerGlobal("receive", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val arg = env.last
      arg match
        case handler: V2Value.ClosV =>
          // Direct receive: receive { case msg => ... }
          val mb = actorTL.get()
          if mb == null then sys.error("receive called outside an actor")
          val msg = mb.queue.take()
          if mb.dead then Done(V2Value.UnitV) else Done(callClosure(handler, List(msg)))
        case V2Value.UnitV =>
          // receive(timeout = n) {...}: named arg emits cell.set(@timeout, n) → UnitV
          // read the @timeout cell that was just set by cell.set
          val ms = readTimeoutMs()
          Done(receiveWithTimeout(ms))
        case V2Value.IntV(ms) =>
          // receive(ms)(handler) — directly passed Int timeout
          Done(receiveWithTimeout(ms))
        case _ =>
          sys.error(s"receive: unexpected arg $arg")
    }))

    // self() — returns current actor's mailbox ref
    V2PluginRegistry.registerGlobal("self", V2Value.ClosV(Runtime.emptyEnv, 0, env => {
      val mb = actorTL.get()
      if mb == null then sys.error("self() called outside an actor")
      Done(V2Value.ForeignV(mb))
    }))

    // exit(actorRef, reason) — mark dead + interrupt actor thread (2-arg, reason ignored)
    V2PluginRegistry.registerGlobal("exit", V2Value.ClosV(Runtime.emptyEnv, 2, env => {
      val ref = env(env.length - 2)  // first of 2 args
      ref match
        case V2Value.ForeignV(mb: ActorMailbox) =>
          mb.dead = true
          val t = mb.thread
          if t != null then t.interrupt()
        case _ => ()
      Done(V2Value.UnitV)
    }))

    // runActors { body } — BlockForm-like: runs body in a main actor VirtualThread
    // and waits for it to complete
    V2PluginRegistry.registerGlobal("runActors", V2Value.ClosV(Runtime.emptyEnv, 1, env => {
      val thunk = env.last.asInstanceOf[V2Value.ClosV]
      val mb = new ActorMailbox()
      @volatile var result: V2Value = V2Value.UnitV
      @volatile var err: Throwable | Null = null
      val latch = new java.util.concurrent.CountDownLatch(1)
      val t = Thread.ofVirtual().start(() => {
        actorTL.set(mb)
        mb.thread = Thread.currentThread()
        try
          result = callThunk(thunk)
        catch
          case e: InterruptedException => ()
          case e: Throwable => err = e
        finally latch.countDown()
        ()
      })
      mb.thread = t
      latch.await()
      val e = err
      if e != null then throw e
      Done(result)
    }))

    // Register "!" operator handler in __arith__ dispatch (ForeignV send)
    V2PluginRegistry.register("actor.send", args => args match
      case List(V2Value.ForeignV(mb: ActorMailbox), msg) =>
        if !mb.dead then mb.queue.put(msg)
        V2Value.UnitV
      case _ => V2Value.UnitV
    )

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

  // ── v2 ↔ raw-Any for NativeImpl (mirrors Interpreter.unwrapValueAsAny) ────────

  /** Convert v2 value to the raw-Any type that NativeImpl.eval expects.
   *  Scalars become JVM primitives; complex values become v1 Values (for
   *  plugins that pattern-match on v1 InstanceV / ListV / etc.). */
  private def v2ToRaw(v: V2Value): Any = v match
    case V2Value.StrV(s)        => s
    case V2Value.IntV(n)        => n
    case V2Value.FloatV(d)      => d
    case V2Value.BoolV(b)       => b
    case V2Value.UnitV          => ()
    case V2Value.DataV("None", _) => DataValue.NullV  // null-default compat: isNullish(NullV)=true
    case _                      => v2ToV1(v)  // complex → v1Value

  /** Convert the raw-Any returned by NativeImpl.eval back to a v2 value.
   *  Mirrors Interpreter.wrapAnyAsValue + the v1→v2 path. */
  private def rawToV2(a: Any): V2Value = a match
    case s: String  => V2Value.StrV(s)
    case n: Long    => V2Value.IntV(n)
    case i: Int     => V2Value.IntV(i.toLong)
    case d: Double  => V2Value.FloatV(d)
    case f: Float   => V2Value.FloatV(f.toDouble)
    case b: Boolean => V2Value.BoolV(b)
    case ()         => V2Value.UnitV
    case v1: V1Value => v1ToV2(v1)
    // v1 NativeFnV → variadic v2 ClosV (arity=-1, accepts any number of args)
    case nfv: scalascript.interpreter.Value.NativeFnV =>
      V2Value.ClosV(Runtime.emptyEnv, -1, env => {
        val v1Args = env.toList.map(v2ToV1)
        val computation = nfv.f(v1Args)
        import scalascript.interpreter.Computation
        Done(v1ToV2(Computation.run(computation)))
      })
    case lst: scala.collection.immutable.List[?] =>
      lst.foldRight[V2Value](V2Value.DataV("Nil", Vector.empty)) { (x, acc) =>
        V2Value.DataV("Cons", Vector(rawToV2(x.asInstanceOf[Any]), acc))
      }
    case null       => V2Value.DataV("None", Vector.empty)
    case other      => V2Value.ForeignV(other.asInstanceOf[AnyRef])

  // ── Value translation: v2 Value → v1 Value ─────────────────────────────

  /** Convert a v2 VM value to a v1 interpreter Value (for plugin arguments). */
  def v2ToV1(v: V2Value): V1Value = v match
    case V2Value.UnitV        => DataValue.UnitV
    case V2Value.BoolV(b)     => DataValue.BoolV(b)
    case V2Value.IntV(n)      => DataValue.IntV(n)
    case V2Value.FloatV(d)    => DataValue.DoubleV(d)
    case V2Value.StrV(s)      => DataValue.StringV(s)
    case V2Value.BigV(n)      => DataValue.BigIntV(n)
    // Cons/Nil list chain → v1 ListV
    case V2Value.DataV("Nil", _) =>
      scalascript.interpreter.Value.ListV(Nil)
    case cons @ V2Value.DataV("Cons", _) =>
      def toList(v: V2Value): List[V1Value] = v match
        case V2Value.DataV("Nil", _)           => Nil
        case V2Value.DataV("Cons", Seq(h, t))  => v2ToV1(h) :: toList(t)
        case _                                 => List(v2ToV1(v))
      scalascript.interpreter.Value.ListV(toList(cons))
    // Option variants
    case V2Value.DataV("None", _) =>
      scalascript.interpreter.Value.OptionV(null)
    case V2Value.DataV("Some", Seq(inner)) =>
      scalascript.interpreter.Value.OptionV(v2ToV1(inner))
    case V2Value.BytesV(bs)   =>
      // Bytes: v1 has no BytesV; wrap as a list of IntV bytes
      val items = bs.toList.map(b => DataValue.IntV((b & 0xff).toLong): V1Value)
      scalascript.interpreter.Value.ListV(items)
    case V2Value.DataV(tag, fields) if tag.startsWith("Tuple") && tag.drop(5).forall(_.isDigit) =>
      // TupleN → v1 TupleV
      scalascript.interpreter.Value.TupleV(fields.toList.map(v2ToV1))
    case V2Value.DataV(tag, fields) =>
      // Use named fields from the bridge's field registry when available;
      // fall back to positional _0/_1/… so plugins can still use positional access.
      val knownNames: Option[Vector[String]] = V2PluginRegistry.lookupFieldNames(tag)
      val fieldMap: Map[String, V1Value] = knownNames match
        case Some(names) if names.length == fields.length =>
          // Named field map (both named and positional keys for compatibility)
          (names.zip(fields).map { case (n, fv) => n -> v2ToV1(fv) } ++
           fields.zipWithIndex.map { case (fv, i) => s"_$i" -> v2ToV1(fv) }).toMap
        case _ =>
          fields.zipWithIndex.map { case (fv, i) => s"_$i" -> v2ToV1(fv) }.toMap
      val inst = scalascript.interpreter.Value.InstanceV(tag, fieldMap)
      val arr: Array[V1Value] = fields.map(v2ToV1).toArray
      val names: Array[String] = knownNames match
        case Some(ns) if ns.length == fields.length => ns.toArray
        case _ => fields.indices.map(i => s"_$i").toArray
      inst.fieldsArr = arr
      inst.fieldNames = names
      inst
    case V2Value.ForeignV(m: scala.collection.mutable.HashMap[?, ?]) =>
      // v2 Map (from __mk_map__) → v1 MapV so plugins can MapVal.unapply
      val converted = m.asInstanceOf[scala.collection.mutable.HashMap[V2Value, V2Value]]
        .map { case (k, v2) => v2ToV1(k) -> v2ToV1(v2) }.toMap
      scalascript.interpreter.Value.MapV(converted)
    case V2Value.ForeignV(m: scala.collection.immutable.Map[?, ?]) if {
          // Named-field maps (from InstanceV conversion) have String keys — treat as InstanceV
          m.nonEmpty && m.keys.head.isInstanceOf[String]
        } =>
      val nm = m.asInstanceOf[scala.collection.immutable.Map[String, V2Value]]
      val fieldMap = nm.map { case (k, v2) => k -> v2ToV1(v2) }.toMap
      val inst = scalascript.interpreter.Value.InstanceV("record", fieldMap)
      val names = nm.keys.toArray
      inst.fieldNames = names
      inst.fieldsArr  = names.map(k => fieldMap(k))
      inst
    case V2Value.ForeignV(m: scala.collection.immutable.Map[?, ?]) =>
      val converted = m.asInstanceOf[scala.collection.immutable.Map[V2Value, V2Value]]
        .map { case (k, v2) => v2ToV1(k) -> v2ToV1(v2) }
      scalascript.interpreter.Value.MapV(converted)
    case V2Value.ForeignV(nmo: ssc.Value.NamedMethodObj) =>
      nmo.underlying match
        case inst: scalascript.interpreter.Value.InstanceV => inst
        case v1: V1Value                                   => v1
        case other => scalascript.interpreter.Value.Foreign(other.getClass.getSimpleName, other)
    case V2Value.ForeignV(tf: TaggedForeign) =>
      scalascript.interpreter.Value.Foreign(tf.tag, tf.value)
    case V2Value.ForeignV(h) =>
      scalascript.interpreter.Value.Foreign(h.getClass.getSimpleName, h)
    case c: V2Value.ClosV =>
      // Wrap v2 ClosV as a v1 NativeFnV so plugins can call it via invokeCallback.
      scalascript.interpreter.Value.NativeFnV("v2Closure", v1Args => {
        val v2Args = v1Args.map(v1ToV2)
        val env    = if v2Args.isEmpty then c.env else Runtime.extend(c.env, v2Args.toArray)
        scalascript.interpreter.Computation.Pure(v2ToV1(Runtime.run(c.code, env)))
      })
    case _ =>
      // LongCellV and other non-translatable values — wrap as opaque Foreign
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
      val inst = v.asInstanceOf[scalascript.interpreter.Value.InstanceV]
      val effFields = inst.effectiveFields
      // If fields have real names (not just _0, _1…), wrap as NamedMethodObj so:
      //   (a) __method__ dispatch looks up by name, and
      //   (b) v2ToV1 round-trips back to the original InstanceV unchanged.
      val hasRealNames = effFields.nonEmpty &&
        !effFields.keys.forall(k => k.matches("_\\d+"))
      if hasRealNames then
        V2Value.ForeignV(new ssc.Value.NamedMethodObj {
          def getField(n: String): Option[ssc.Value] = effFields.get(n).map(v1ToV2)
          def underlying: AnyRef = inst
          override def toString: String = s"$tag(${effFields.keys.mkString(", ")})"
        })
      else
        val arr = inst.fieldsArr
        if arr != null then
          V2Value.DataV(tag, arr.toVector.map(v1ToV2))
        else
          V2Value.DataV(tag, effFields.values.toVector.map(v1ToV2))
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
    case scalascript.interpreter.Value.MapV(entries) =>
      val hm = scala.collection.mutable.HashMap[V2Value, V2Value]()
      entries.foreach { case (k, v) => hm(v1ToV2(k)) = v1ToV2(v) }
      V2Value.ForeignV(hm)
    case scalascript.interpreter.Value.Foreign(tag, h: AnyRef) =>
      V2Value.ForeignV(TaggedForeign(tag, h))
    // v1 NativeFnV → variadic v2 ClosV
    case nfv: scalascript.interpreter.Value.NativeFnV =>
      V2Value.ClosV(Runtime.emptyEnv, -1, env => {
        val v1Args = env.toList.map(v2ToV1)
        Done(v1ToV2(scalascript.interpreter.Computation.run(nfv.f(v1Args))))
      })
    case _ =>
      // Closures and other complex v1 values: wrap in ForeignV
      V2Value.ForeignV(v.asInstanceOf[AnyRef])
