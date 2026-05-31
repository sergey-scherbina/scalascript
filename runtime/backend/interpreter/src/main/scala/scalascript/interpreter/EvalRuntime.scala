package scalascript.interpreter

import scalascript.transform.DirectTypeUtils
import scala.collection.mutable
import scala.collection.immutable.{Map => IMap}
import scala.meta.*
import Computation.{Pure, FlatMap, Perform}

/** Expression evaluator: the core `eval(term, env, interp)` dispatch,
 *  plus the `evalArgs`, `collectApplyArgs`, and `threadValues` helpers.
 */
private[interpreter] object EvalRuntime:

  private def cachedLiteralValue(lit: Lit, interp: Interpreter): Value | Null =
    val cached = interp.litCache.get(lit)
    if cached != null then
      cached match
        case Pure(v) => v
        case _       => null
    else
      val c = lit match
        case Lit.Int(v)     => Computation.pureIntV(v.toLong)
        case Lit.Long(v)    => Computation.pureIntV(v)
        case Lit.Double(v)  => Pure(Value.doubleV(v.toString.toDouble))
        case Lit.Float(v)   => Pure(Value.doubleV(v.toString.toDouble))
        case Lit.Boolean(v) => Computation.pureBool(v)
        case _              => null
      if c == null then null
      else
        interp.litCache.put(lit, c)
        c.asInstanceOf[Pure].value

  private def fastValue(term: Term, env: Env, interp: Interpreter): Value | Null =
    term match
      case tn: Term.Name =>
        val ev = env.getOrElse(tn.value, null)
        if ev != null then ev else interp.globals.getOrElse(tn.value, null)
      case lit: Lit =>
        cachedLiteralValue(lit, interp)
      case _ => null

  // Tuple-free: arithmetic/ordering shared with DispatchRuntime.numericFast; the
  // Double/Bool equality and Bool short-circuit cases are kept here because
  // numericFast deliberately leaves them to the general path.
  private def fastPrimitiveInfix(lhs: Value, op: String, rhs: Value): Computation | Null =
    val nf = DispatchRuntime.numericFast(lhs, op, rhs)
    if nf != null then nf
    else lhs match
      case Value.DoubleV(a) => rhs match
        case Value.DoubleV(b) => op match
          case "==" => Computation.pureBool(a == b)
          case "!=" => Computation.pureBool(a != b)
          case _    => null
        case _ => null
      case Value.BoolV(a) => rhs match
        case Value.BoolV(b) => op match
          case "&&" => Computation.pureBool(a && b)
          case "||" => Computation.pureBool(a || b)
          case "==" => Computation.pureBool(a == b)
          case "!=" => Computation.pureBool(a != b)
          case _    => null
        case _ => null
      case _ => null

  private def fastPrimitiveInfixTerm(lhs: Term, op: String, rhs: Term, env: Env, interp: Interpreter): Computation | Null =
    val lv = fastPrimitiveValue(lhs, env, interp)
    if lv == null then null
    else
      val rv = fastPrimitiveValue(rhs, env, interp)
      if rv == null then null else fastPrimitiveInfix(lv, op, rv)

  /** Value-returning twin of `fastPrimitiveInfix`: same coverage, but yields the
   *  raw `Value` (or null) with no `Pure` wrapper. The hot while-assign loop only
   *  needs the Value, so this avoids allocating one throwaway `Pure` per infix
   *  per iteration (≈3 per arithLoop step). Mirrors `fastPrimitiveInfix` exactly:
   *  `numericFastValue` first, then the Double-eq / Bool cases it leaves out. */
  private def fastPrimitiveInfixValue(lhs: Value, op: String, rhs: Value): Value | Null =
    val nf = DispatchRuntime.numericFastValue(lhs, op, rhs)
    if nf != null then nf
    else lhs match
      case Value.DoubleV(a) => rhs match
        case Value.DoubleV(b) => op match
          case "==" => Value.boolV(a == b)
          case "!=" => Value.boolV(a != b)
          case _    => null
        case _ => null
      case Value.BoolV(a) => rhs match
        case Value.BoolV(b) => op match
          case "&&" => Value.boolV(a && b)
          case "||" => Value.boolV(a || b)
          case "==" => Value.boolV(a == b)
          case "!=" => Value.boolV(a != b)
          case _    => null
        case _ => null
      case _ => null

  private def fastPrimitiveValue(term: Term, env: Env, interp: Interpreter): Value | Null =
    term match
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        val lv = fastPrimitiveValue(lhs, env, interp)
        if lv == null then null
        else
          val rv = fastPrimitiveValue(argClause.values.head, env, interp)
          if rv == null then null else fastPrimitiveInfixValue(lv, op.value, rv)
      case _ => fastValue(term, env, interp)

  private final class FastAssignBody(val names: Array[String], val rhs: Array[Term])

  private final class OverlayEnvView(base: Env, overlay: mutable.HashMap[String, Value])
      extends scala.collection.immutable.AbstractMap[String, Value]:
    override def get(key: String): Option[Value] =
      overlay.get(key).orElse(base.get(key))
    override def getOrElse[V1 >: Value](key: String, default: => V1): V1 =
      val v = overlay.getOrElse(key, null)
      if v != null then v else base.getOrElse(key, default)
    override def contains(key: String): Boolean =
      overlay.contains(key) || base.contains(key)
    override def iterator: Iterator[(String, Value)] =
      overlay.iterator ++ base.iterator.filterNot { case (k, _) => overlay.contains(k) }
    override def updated[V1 >: Value](key: String, value: V1): Map[String, V1] =
      (base ++ overlay).updated(key, value)
    override def removed(key: String): Map[String, Value] =
      (base ++ overlay).removed(key)

  private def collectFastAssignBody(body: Term): FastAssignBody | Null =
    def one(assign: Term.Assign): FastAssignBody | Null =
      if assign.lhs.isInstanceOf[Term.Name] then
        new FastAssignBody(Array(assign.lhs.asInstanceOf[Term.Name].value), Array(assign.rhs))
      else null

    body match
      case assign: Term.Assign => one(assign)
      case Term.Block(stats) if stats.nonEmpty =>
        val names = new Array[String](stats.length)
        val rhs   = new Array[Term](stats.length)
        var rest  = stats
        var i     = 0
        while rest.nonEmpty do
          rest.head match
            case assign: Term.Assign if assign.lhs.isInstanceOf[Term.Name] =>
              names(i) = assign.lhs.asInstanceOf[Term.Name].value
              rhs(i) = assign.rhs
            case _ => return null
          i += 1
          rest = rest.tail
        new FastAssignBody(names, rhs)
      case _ => null

  private def previewFastAssignIteration(body: FastAssignBody, cond: Term, env: Env, interp: Interpreter): java.lang.Boolean | Null =
    val overlay = mutable.HashMap.empty[String, Value]
    val preview = new OverlayEnvView(env, overlay)
    try
      var i = 0
      while i < body.names.length do
        val v = fastPrimitiveValue(body.rhs(i), preview, interp)
        if v == null then return null
        overlay(body.names(i)) = v
        i += 1
      fastPrimitiveValue(cond, preview, interp) match
        case Value.BoolV(next) => java.lang.Boolean.valueOf(next)
        case _                 => null
    catch
      case scala.util.control.NonFatal(_) => null

  // ── Unboxed-long fast path for integer while-assign loops ───────────────
  // A while-loop whose body is only `name = <int-arith>` assignments and whose
  // condition is an integer comparison runs entirely in unboxed `long` slots,
  // boxing each var back to a pooled IntV only once on loop exit. This removes
  // the per-iteration IntV allocation that JFR showed is ~99% of arithLoop's
  // bytes (counter vars escape the −2048..16383 intV pool almost immediately).
  private sealed abstract class LExpr:
    def eval(slots: Array[Long]): Long
  private final class LConst(v: Long) extends LExpr:
    def eval(slots: Array[Long]): Long = v
  private final class LVar(idx: Int) extends LExpr:
    def eval(slots: Array[Long]): Long = slots(idx)
  private final class LBin(op: Int, l: LExpr, r: LExpr) extends LExpr:
    def eval(slots: Array[Long]): Long =
      val a = l.eval(slots); val b = r.eval(slots)
      op match
        case 0 => a + b
        case 1 => a - b
        case 2 => a * b
        case 3 => a / b
        case _ => a % b
  private sealed abstract class LCond:
    def eval(slots: Array[Long]): Boolean
  private final class LCmp(op: Int, l: LExpr, r: LExpr) extends LCond:
    def eval(slots: Array[Long]): Boolean =
      val a = l.eval(slots); val b = r.eval(slots)
      op match
        case 0 => a < b
        case 1 => a <= b
        case 2 => a > b
        case 3 => a >= b
        case 4 => a == b
        case _ => a != b
  private final class LAnd(l: LCond, r: LCond) extends LCond:
    def eval(slots: Array[Long]): Boolean = l.eval(slots) && r.eval(slots)
  private final class LOr(l: LCond, r: LCond) extends LCond:
    def eval(slots: Array[Long]): Boolean = l.eval(slots) || r.eval(slots)

  /** Tries to run the whole while-assign loop in unboxed `long` space. Returns
   *  `PureUnit` on success (slots boxed back to env+globals on exit), or null to
   *  bail to the Value-space loop (non-int var, unsupported op/term). Precondition:
   *  the caller has already confirmed the loop condition is true on entry. */
  private def tryLongWhileAssign(t: Term.While, body: FastAssignBody, frameView: MutableEnvView, interp: Interpreter): Computation | Null =
    val slotOfName = mutable.LinkedHashMap.empty[String, Int]
    // Slot index for an int-valued name, or -1 (bail). Registers on first use.
    def slotOf(name: String): Int =
      slotOfName.get(name) match
        case Some(i) => i
        case None =>
          frameView.getOrElse(name, null) match
            case Value.IntV(_) =>
              val i = slotOfName.size
              slotOfName(name) = i
              i
            case _ => -1
    def compileExpr(term: Term): LExpr | Null = term match
      case Lit.Int(v)  => new LConst(v.toLong)
      case Lit.Long(v) => new LConst(v)
      case tn: Term.Name =>
        val s = slotOf(tn.value)
        if s < 0 then null else new LVar(s)
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        val code = op.value match
          case "+" => 0
          case "-" => 1
          case "*" => 2
          case "/" => 3
          case "%" => 4
          case _   => -1
        if code < 0 then null
        else
          val l = compileExpr(lhs)
          if l == null then null
          else
            val r = compileExpr(argClause.values.head)
            if r == null then null else new LBin(code, l, r)
      case _ => null
    def compileCond(term: Term): LCond | Null = term match
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        op.value match
          case "&&" =>
            val l = compileCond(lhs)
            if l == null then null
            else
              val r = compileCond(argClause.values.head)
              if r == null then null else new LAnd(l, r)
          case "||" =>
            val l = compileCond(lhs)
            if l == null then null
            else
              val r = compileCond(argClause.values.head)
              if r == null then null else new LOr(l, r)
          case other =>
            val code = other match
              case "<"  => 0
              case "<=" => 1
              case ">"  => 2
              case ">=" => 3
              case "==" => 4
              case "!=" => 5
              case _    => -1
            if code < 0 then null
            else
              val l = compileExpr(lhs)
              if l == null then null
              else
                val r = compileExpr(argClause.values.head)
                if r == null then null else new LCmp(code, l, r)
      case _ => null

    // Register assigned names (must already be int-valued vars), then compile.
    var k = 0
    while k < body.names.length do
      if slotOf(body.names(k)) < 0 then return null
      k += 1
    val rhsL = new Array[LExpr](body.rhs.length)
    var j = 0
    while j < body.rhs.length do
      val c = compileExpr(body.rhs(j))
      if c == null then return null
      rhsL(j) = c
      j += 1
    val condL = compileCond(t.expr)
    if condL == null then return null
    // Initial slot values (every registered name was checked int-valued).
    val slots = new Array[Long](slotOfName.size)
    val it = slotOfName.iterator
    while it.hasNext do
      val (nm, idx) = it.next()
      frameView.getOrElse(nm, null) match
        case Value.IntV(v) => slots(idx) = v
        case _             => return null
    val assignSlot = new Array[Int](body.names.length)
    var a = 0
    while a < body.names.length do
      assignSlot(a) = slotOfName(body.names(a))
      a += 1
    // Run in unboxed long space (condition already true on entry → do-while).
    var running = true
    while running do
      var i = 0
      while i < rhsL.length do
        slots(assignSlot(i)) = rhsL(i).eval(slots)
        i += 1
      running = condL.eval(slots)
    // Box final values of assigned vars back into env + globals (once).
    val local = frameView.underlying
    var w = 0
    while w < body.names.length do
      val nm = body.names(w)
      val v  = Value.intV(slots(assignSlot(w)))
      local(nm) = v
      interp.globals(nm) = v
      w += 1
    Computation.PureUnit

  private def tryFastWhileAssign(t: Term.While, frameView: MutableEnvView, interp: Interpreter): Computation | Null =
    if interp.debugHooks.nonEmpty then null
    else
      val body = collectFastAssignBody(t.body)
      if body == null then null
      else
        fastPrimitiveValue(t.expr, frameView, interp) match
          case Value.BoolV(false) => Computation.PureUnit
          case Value.BoolV(true) =>
            val longLoop = tryLongWhileAssign(t, body, frameView, interp)
            if longLoop != null then longLoop
            else if previewFastAssignIteration(body, t.expr, frameView, interp) == null then null
            else
              val local = frameView.underlying
              var running = true
              while running do
                var i = 0
                while i < body.names.length do
                  val v = fastPrimitiveValue(body.rhs(i), frameView, interp)
                  if v == null then interp.located("Fast while assignment left the primitive path")
                  val name = body.names(i)
                  local(name) = v
                  interp.globals(name) = v
                  i += 1
                fastPrimitiveValue(t.expr, frameView, interp) match
                  case Value.BoolV(next) => running = next
                  case _                 => running = false
              Computation.PureUnit
          case _ => null

  // Head names of the special-form `Term.Apply` cases handled below (effect
  // runners, `validate`, `bench`, `receive`, `Focus`, …). A plain application
  // `f(args)` whose head is a `Term.Name` NOT in this set can skip the entire
  // special-form scan and go straight to generic call dispatch — that linear
  // scan was the dominant self-cost of `eval` on hot recursive calls (JFR).
  private val reservedApplyHeads: Set[String] = Set(
    "bench", "computed", "effect", "handle", "httpClient", "receive", "restartable",
    "runActors", "runAsync", "runAsyncParallel", "runAuthWith", "runCache", "runCacheBypass",
    "runClock", "runClockAt", "runEnv", "runEnvWith", "runEphemeralStorage", "runHttp",
    "runHttpStub", "runLogger", "runLoggerJson", "runLoggerToList", "runRandom",
    "runRandomSeeded", "runRetry", "runRetryNoSleep", "runState", "runStorage", "runStream",
    "runTx", "timeout", "validate", "Focus")

  /** Generic positional/named call dispatch for `f(args...)`, shared by the
   *  plain-application fast path and the `case _` fallthrough of `app.fun`. */
  private def evalPlainApply(app: Term.Apply, env: Env, interp: Interpreter): Computation =
    // Flatten nested Apply nodes so that curried calls like `f(a)(using b)`
    // are collected into a single `interp.callValue(f, [a, b])` invocation.
    val (baseFun, allArgTerms) = collectApplyArgs(app)
    val hasNamedArgs = allArgTerms.exists(_.isInstanceOf[Term.Assign])
    // Pure-free fast lane: name/literal head + name/literal args (no named args,
    // no debug hooks). Reads head and args as Values with no Pure wrappers and
    // dispatches directly; any non-fast operand falls through to the monadic
    // path below. callValue/callValue1 are the same calls the slow path makes.
    if !hasNamedArgs && interp.debugHooks.isEmpty then
      val fv = fastValue(baseFun, env, interp)
      if fv != null then
        allArgTerms match
          case one :: Nil =>
            val av = fastValue(one, env, interp)
            if av != null then return interp.callValue1(fv, av, env)
          case a :: b :: Nil =>
            val av1 = fastValue(a, env, interp)
            if av1 != null then
              val av2 = fastValue(b, env, interp)
              if av2 != null then return interp.callValue(fv, av1 :: av2 :: Nil, env)
          case _ => ()
    val funC     = eval(baseFun, env, interp)
    if hasNamedArgs then
      val namedComps = allArgTerms.map {
        case Term.Assign(Term.Name(n), rhs) => (Some(n), eval(rhs, env, interp))
        case other                          => (None,    eval(other, env, interp))
      }
      val comps = namedComps.map(_._2)
      funC match
        case Pure(fv) if comps.forall(_.isInstanceOf[Pure]) =>
          val namedVals = namedComps.map { case (k, Pure(v)) => (k, v); case (k, _) => (k, Value.UnitV) }
          CallRuntime.callValueNamed(fv, namedVals, env, interp)
        case _ =>
          FlatMap(funC, fv =>
            interp.threadValues(comps)(argVals =>
              CallRuntime.callValueNamed(fv, namedComps.map(_._1).zip(argVals), env, interp)))
    else if allArgTerms.lengthCompare(1) == 0 then
      val arg1C = eval(allArgTerms.head, env, interp)
      funC match
        case Pure(fv) =>
          arg1C match
            case Pure(av) => interp.callValue1(fv, av, env)
            case _        => FlatMap(arg1C, av => interp.callValue1(fv, av, env))
        case _ =>
          arg1C match
            case Pure(av) => FlatMap(funC, fv => interp.callValue1(fv, av, env))
            case _        => FlatMap(funC, fv => FlatMap(arg1C, av => interp.callValue1(fv, av, env)))
    else if allArgTerms.lengthCompare(2) == 0 then
      val arg1C = eval(allArgTerms.head, env, interp)
      val arg2C = eval(allArgTerms(1),   env, interp)
      funC match
        case Pure(fv) =>
          arg1C match
            case Pure(av1) =>
              arg2C match
                case Pure(av2) => interp.callValue(fv, av1 :: av2 :: Nil, env)
                case _         => FlatMap(arg2C, av2 => interp.callValue(fv, av1 :: av2 :: Nil, env))
            case _ =>
              arg2C match
                case Pure(av2) => FlatMap(arg1C, av1 => interp.callValue(fv, av1 :: av2 :: Nil, env))
                case _         => FlatMap(arg1C, av1 => FlatMap(arg2C, av2 => interp.callValue(fv, av1 :: av2 :: Nil, env)))
        case _ =>
          arg1C match
            case Pure(av1) if arg2C.isInstanceOf[Pure] =>
              FlatMap(funC, fv => interp.callValue(fv, av1 :: arg2C.asInstanceOf[Pure].value :: Nil, env))
            case _ =>
              FlatMap(funC, fv => FlatMap(arg1C, av1 => FlatMap(arg2C, av2 => interp.callValue(fv, av1 :: av2 :: Nil, env))))
    else
      val argComps = allArgTerms.map(eval(_, env, interp))
      val argVsPos = extractPureValues(argComps)
      if argVsPos != null then
        funC match
          case Pure(fv) => interp.callValue(fv, argVsPos, env)
          case _        => FlatMap(funC, fv => interp.callValue(fv, argVsPos, env))
      else
        FlatMap(funC, fv =>
          interp.threadValues(argComps)(argVals => interp.callValue(fv, argVals, env)))

  def eval(term: Term, env: Env, interp: Interpreter): Computation =
    interp.trackPos(term)
    // DAP step/breakpoint hook: called for every term so DebugHooks can decide
    // whether to suspend (breakpoint, stepIn, stepOver, stepOut).
    // currentSpan: block-relative 0-based line; docLine = debugBlockDocLine + blockLine + 1.
    // callStack.length is the current call depth (0 at top level).
    // Pattern match instead of .foreach: .foreach { hooks => ... } allocates a lambda
    // on every eval call even when debugHooks is None. Match desugars to isEmpty+get.
    interp.debugHooks match
      case Some(hooks) if interp.currentSpanLine >= 0 =>
        val blockLine  = interp.currentSpanLine
        val docLine    = interp.debugBlockDocLine + blockLine + 1
        val callFrames = interp.callStackToIndexedSeq.map { case (n, sf, l) =>
          scalascript.interpreter.debug.CallFrameEntry(n, sf, l)
        }
        val frame = scalascript.interpreter.debug.DebugFrame(0, "frame", interp.debugSourceFile, docLine, interp.callStackLength, env, callFrames)
        hooks.onStep(frame)
      case _ =>
    term match
    // Literals — interned by Lit identity so a hot loop reuses the same
    // `Pure(Value)` instance instead of reallocating on every eval. The
    // parser produces a stable AST; each `Lit.Int(1)` etc. is the same
    // Scala object across all visits.
    case lit: Lit =>
      val cached = interp.litCache.get(lit)
      if cached != null then cached
      else
        val c = lit match
          case Lit.Int(v)     => Computation.pureIntV(v.toLong)
          case Lit.Long(v)    => Computation.pureIntV(v)
          case Lit.Double(v)  => Pure(Value.doubleV(v.toString.toDouble))
          case Lit.Float(v)   => Pure(Value.doubleV(v.toString.toDouble))
          case Lit.String(v)  => Pure(Value.StringV(v))
          case Lit.Boolean(v) => Computation.pureBool(v)
          case Lit.Char(v)    => Pure(Value.charV(v))
          case Lit.Unit()     => Computation.PureUnit
          case Lit.Null()     => Computation.PureNull
          case _              => Computation.PureNull
        interp.litCache.put(lit, c)
        c

    // Name lookup: local env first, then interp.globals.
    // Phase 2 lazy loading: on first miss we trigger the plugin ServiceLoader
    // scan (ensurePluginsLoaded) so scripts like `hello.ssc` that never touch
    // a plugin never pay the cost.  After plugins load we re-check globals; if
    // still missing, the name is genuinely undefined.
    // Type-only check avoids Term.Name.unapply which allocates Some(name).
    case tn: Term.Name =>
      val name = tn.value
      // null-default lookups (not by-name getOrElse) to avoid a Function0 thunk per name.
      val ev = env.getOrElse(name, null)
      val v = if ev != null then ev else interp.globals.getOrElse(name, null)
      if v != null then Pure(v)
      else if interp._pluginsLoaded then interp.located(s"Undefined: $name")
      else
        interp.ensurePluginsLoaded()
        Pure(interp.globals.getOrElse(name, interp.located(s"Undefined: $name")))

    // Fast path: plain application `f(args)` where the head is a user-level
    // `Term.Name` that is NOT a reserved special form. Skips the ~40
    // special-form `Term.Apply` cases below — a linear scan that dominated
    // eval's self-time on hot recursive calls (e.g. fib). Curried calls
    // (head is itself an Apply) and method calls (head is a Select) fall
    // through to the full match unchanged.
    case app: Term.Apply if (app.fun match
          case n: Term.Name => !reservedApplyHeads.contains(n.value)
          case _            => false) =>
      evalPlainApply(app, env, interp)

    // ── Hot non-Apply terms, hoisted above the ~40 special-form `Term.Apply`
    //    cases below.  These types (ApplyInfix, If, Block, Match) are siblings
    //    of Term.Apply under Term, so their relative order with the Apply cases
    //    is irrelevant to semantics — but placing them first means recursive
    //    workloads (fib: If + 4×ApplyInfix per call; arith loops: ApplyInfix
    //    per iteration; pattern matching: Match) no longer pay ~40 failing
    //    instanceof/extractor checks on every visit.  JFR showed eval's own
    //    match body dominating self-time on these workloads.

    // Infix operators — handles both plain binary (a + b) and compound
    // assignment (x += e).  Using `case app: Term.ApplyInfix` avoids calling
    // Term.ApplyInfix.After_4_6_0.unapply, which allocates a Tuple4 even for
    // binary ops where the compound-assignment guard then fails.
    case app: Term.ApplyInfix =>
      val opStr = app.op.value
      app.lhs match
        // Compound assignment: x += e, x -= e, x *= e, x /= e, x %= e
        case lhsName: Term.Name
            if opStr.length > 1 && opStr.last == '=' && !BlockRuntime.isCompareOp(opStr) =>
          val baseOp   = opStr.init
          val argVals  = app.argClause.values
          if argVals.lengthCompare(1) == 0 then
            val rhsC = eval(argVals.head, env, interp)
            eval(lhsName, env, interp).flatMap { lhsV =>
              rhsC.flatMap { rv =>
                interp.infix2(lhsV, baseOp, rv, env).flatMap { newV =>
                  interp.globals(lhsName.value) = newV; Computation.PureUnit } } }
          else
            eval(lhsName, env, interp).flatMap { lhsV =>
              val argComps = argVals.map(eval(_, env, interp))
              interp.threadValues(argComps) { argVs =>
                interp.infix(lhsV, baseOp, argVs, env).flatMap { newV =>
                  interp.globals(lhsName.value) = newV
                  Computation.PureUnit
                }
              }
            }
        // Plain binary infix: a op b
        case lhs =>
          val argVals = app.argClause.values
          if argVals.lengthCompare(1) == 0 then
            val rhsTerm = argVals.head
            val fast =
              if interp.debugHooks.isEmpty then fastPrimitiveInfixTerm(lhs, opStr, rhsTerm, env, interp)
              else null
            if fast != null then fast
            else
              // Pure-free operand reads: a name/literal operand is fetched as a
              // Value with no Pure wrapper; only operands that may be effectful
              // (calls, blocks, …) take the monadic eval path. Names/literals are
              // side-effect-free reads, so evaluation order is preserved.
              val lvFast = fastValue(lhs, env, interp)
              val rvFast = fastValue(rhsTerm, env, interp)
              if lvFast != null && rvFast != null then
                interp.infix2(lvFast, opStr, rvFast, env)
              else if lvFast != null then
                val rhsC = eval(rhsTerm, env, interp)
                rhsC match
                  case Pure(rv) => interp.infix2(lvFast, opStr, rv, env)
                  case _        => FlatMap(rhsC, rv => interp.infix2(lvFast, opStr, rv, env))
              else if rvFast != null then
                val lhsC = eval(lhs, env, interp)
                lhsC match
                  case Pure(lv) => interp.infix2(lv, opStr, rvFast, env)
                  case _        => FlatMap(lhsC, lv => interp.infix2(lv, opStr, rvFast, env))
              else
                val lhsC = eval(lhs, env, interp)
                val rhsC = eval(rhsTerm, env, interp)
                lhsC match
                  case Pure(lv) =>
                    rhsC match
                      case Pure(rv) => interp.infix2(lv, opStr, rv, env)
                      case _        => FlatMap(rhsC, rv => interp.infix2(lv, opStr, rv, env))
                  case _ =>
                    rhsC match
                      case Pure(rv) => FlatMap(lhsC, lv => interp.infix2(lv, opStr, rv, env))
                      case _        => FlatMap(lhsC, lv => FlatMap(rhsC, rv => interp.infix2(lv, opStr, rv, env)))
          else
            val lhsC     = eval(lhs, env, interp)
            val argComps = argVals.map(eval(_, env, interp))
            val argVsInfix = extractPureValues(argComps)
            lhsC match
              case Pure(lhsV) if argVsInfix != null =>
                interp.infix(lhsV, opStr, argVsInfix, env)
              case _ =>
                FlatMap(lhsC, lhsV =>
                  interp.threadValues(argComps)(argVs => interp.infix(lhsV, opStr, argVs, env)))

    // if/then/else
    case t: Term.If =>
      eval(t.cond, env, interp) match
        case Pure(Value.BoolV(true))  => eval(t.thenp, env, interp)
        case Pure(Value.BoolV(false)) => eval(t.elsep, env, interp)
        case Pure(other)              => interp.located(s"if condition must be Boolean, got ${Value.show(other)}")
        case condC                    => FlatMap(condC, {
          case Value.BoolV(true)  => eval(t.thenp, env, interp)
          case Value.BoolV(false) => eval(t.elsep, env, interp)
          case other              => interp.located(s"if condition must be Boolean, got ${Value.show(other)}")
        })

    // Block { stmts; expr }
    // Type test (not Term.Block(stats) extractor) to avoid a per-eval Some alloc.
    case t: Term.Block =>
      BlockRuntime.evalBlock(t.stats, env, interp)

    // Pattern match — compiled-matcher cache keyed by the Term.Match node.
    case t: Term.Match =>
      var compiled = interp.matchCache.get(t)
      if compiled == null then
        compiled = PatternRuntime.compileMatch(t, interp)
        interp.matchCache.put(t, compiled)
      val compiledM = compiled
      // Pure-free scrutinee read: a plain name/literal scrutinee (the common case)
      // is fetched as a Value with no throwaway Pure wrapper.
      val fastScrut = fastValue(t.expr, env, interp)
      if fastScrut != null then compiledM.run(fastScrut, env, interp)
      else eval(t.expr, env, interp) match
        case Pure(scrutV) => compiledM.run(scrutV, env, interp)
        case exprC        => FlatMap(exprC, scrutV => compiledM.run(scrutV, env, interp))

    // Special form: handle(body) { case Eff.op(args, resume) => ... }
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          EffectsRuntime.evalHandle(bodyArgClause.values.head.asInstanceOf[Term], pf.cases, env, interp,
            interp.multiShotEffects)
        case _ => interp.located("handle expects a partial function { case Eff.op(args, resume) => ... }")

    // Special form: runAsync(body) — default Async handler.  The body is
    // a by-name expression; we evaluate it lazily so its interp.effects compose
    // into the Computation tree before the driver walks them.
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      AsyncRuntime.asyncInterp(eval(bodyArgClause.values.head, env, interp), interp)

    // Special form: runAsyncParallel(body) — alternate Async handler
    // that executes thunks passed to `async` / `parallel` on real
    // JVM threads (ExecutorService + CompletableFuture).  `await`
    // blocks the calling thread on the future; `parallel` returns
    // results in declared order regardless of completion order so
    // value-deterministic code still produces byte-identical output.
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      AsyncRuntime.asyncParInterp(eval(bodyArgClause.values.head, env, interp), interp)

    // Special forms: runStorage / runEphemeralStorage — Storage-effect
    // handlers.  The former hydrates from / persists to a JSON file
    // (path from the optional second arg or `SSC_STORAGE_PATH` env,
    // defaulting to `./ssc-storage.json`); the latter keeps the map
    // in-memory and discards it at scope exit.
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val pathOpt = bodyArgClause.values.lift(1).map { p =>
        Computation.run(eval(p, env, interp)) match
          case Value.StringV(s) => s
          case _                => StorageRuntime.defaultPath
      }
      StorageRuntime.interp(eval(bodyArgClause.values.head, env, interp), Some(pathOpt.getOrElse(StorageRuntime.defaultPath)))
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      StorageRuntime.interp(eval(bodyArgClause.values.head, env, interp), None)

    // ── v1.4 Logger effect handlers ───────────────────────────────────────
    // runLogger { body }        — writes "[LEVEL] msg\n" to `interp.out`
    // runLoggerJson { body }    — writes {"level":"…","msg":"…"} newline JSON
    // runLoggerToList { body }  — collects log lines; returns (result, list)
    case Term.Apply.After_4_6_0(Term.Name("runLogger"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.loggerRun(eval(bodyArgClause.values.head, env, interp), "text", interp.out)
    case Term.Apply.After_4_6_0(Term.Name("runLoggerJson"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.loggerRun(eval(bodyArgClause.values.head, env, interp), "json", interp.out)
    case Term.Apply.After_4_6_0(Term.Name("runLoggerToList"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.loggerToListRun(eval(bodyArgClause.values.head, env, interp))

    // ── v1.4 Random effect handlers ───────────────────────────────────────
    // runRandom { body }            — ThreadLocalRandom
    // runRandomSeeded(seed) { body } — deterministic LCG, seed is Long
    case Term.Apply.After_4_6_0(Term.Name("runRandom"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.randomRun(eval(bodyArgClause.values.head, env, interp), None)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runRandomSeeded"), seedClause),
        bodyClause)
        if seedClause.values.size == 1 && bodyClause.values.size == 1 =>
      val seed = Computation.run(eval(seedClause.values.head, env, interp)) match
        case Value.IntV(n) => n
        case _             => throw InterpretError("runRandomSeeded(seed: Long) { body }")
      EffectHandlers.randomRun(eval(bodyClause.values.head, env, interp), Some(seed))

    // ── v1.4 Clock effect handlers ────────────────────────────────────────
    // runClock { body }        — real wall clock; Clock.sleep → Thread.sleep
    // runClockAt(t0) { body }  — frozen at t0 ms epoch; sleep is a no-op
    case Term.Apply.After_4_6_0(Term.Name("runClock"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.clockRun(eval(bodyArgClause.values.head, env, interp), None)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runClockAt"), t0Clause),
        bodyClause)
        if t0Clause.values.size == 1 && bodyClause.values.size == 1 =>
      val t0 = Computation.run(eval(t0Clause.values.head, env, interp)) match
        case Value.IntV(n) => n
        case _             => throw InterpretError("runClockAt(t0: Long) { body }")
      EffectHandlers.clockRun(eval(bodyClause.values.head, env, interp), Some(t0))

    // ── v1.4 Env effect handlers ──────────────────────────────────────────
    // runEnv { body }               — reads real process env; Env.set is local
    // runEnvWith(Map(...)) { body }  — fixture map; Env.set mutates overlay
    case Term.Apply.After_4_6_0(Term.Name("runEnv"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.envRun(eval(bodyArgClause.values.head, env, interp), None)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runEnvWith"), mapClause),
        bodyClause)
        if mapClause.values.size == 1 && bodyClause.values.size == 1 =>
      val overlay = Computation.run(eval(mapClause.values.head, env, interp)) match
        case Value.MapV(m) =>
          m.map { (k, v) => Value.show(k) -> Value.show(v) }.toMap
        case _ => throw InterpretError("runEnvWith(map: Map[String, String]) { body }")
      EffectHandlers.envRun(eval(bodyClause.values.head, env, interp), Some(overlay))

    // ── v1.4 Http effect handlers ─────────────────────────────────────────
    // runHttp { body }                   — delegates to real httpGet/httpPost
    // runHttpStub(routes) { body }       — test stub: url→body map
    case Term.Apply.After_4_6_0(Term.Name("runHttp"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.httpRun(eval(bodyArgClause.values.head, env, interp), None, interp)
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runHttpStub"), routesClause),
        bodyClause)
        if routesClause.values.size == 1 && bodyClause.values.size == 1 =>
      val routes = Computation.run(eval(routesClause.values.head, env, interp)) match
        case m @ Value.MapV(_) => m
        case _ => throw InterpretError("runHttpStub(routes: Map[String, String]) { body }")
      EffectHandlers.httpRun(eval(bodyClause.values.head, env, interp), Some(routes), interp)

    // ── v1.51.6 Stream effect handler
    // runStream { body }  — discharges Stream.emit(x) performs; returns Source[A]
    case Term.Apply.After_4_6_0(Term.Name("runStream"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.streamRun(eval(bodyArgClause.values.head, env, interp), interp)

    // ── v1.4 State effect handlers ────────────────────────────────────────
    // runState(s0) { body }  — runs body intercepting State performs;
    //                          returns (finalState, result) as a tuple
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runState"), s0Clause),
        bodyClause)
        if s0Clause.values.size == 1 && bodyClause.values.size == 1 =>
      val s0 = Computation.run(eval(s0Clause.values.head, env, interp))
      EffectHandlers.stateRun(eval(bodyClause.values.head, env, interp), s0, interp)

    // ── v1.4 Auth effect handlers ─────────────────────────────────────────
    // runAuthWith(user) { body }  — injects a fixed user via thread-local;
    // body is run synchronously so the thread-local is set during execution.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("runAuthWith"), userClause),
        bodyClause)
        if userClause.values.size == 1 && bodyClause.values.size == 1 =>
      val user = Computation.run(eval(userClause.values.head, env, interp))
      val prior = interp._authUser.get()
      interp._authUser.set(Some(user))
      try Pure(Computation.run(eval(bodyClause.values.head, env, interp)))
      finally interp._authUser.set(prior)

    // ── v1.4 Retry effect handlers ────────────────────────────────────────
    // runRetry { body }        — real sleep between attempts
    // runRetryNoSleep { body } — test handler: retries without sleeping
    case Term.Apply.After_4_6_0(Term.Name("runRetry"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.retryRun(eval(bodyArgClause.values.head, env, interp), sleep = true, interp)
    case Term.Apply.After_4_6_0(Term.Name("runRetryNoSleep"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.retryRun(eval(bodyArgClause.values.head, env, interp), sleep = false, interp)

    // ── v1.4 Cache effect handlers ────────────────────────────────────────
    // runCache { body }        — explicit handler using process-local cache
    // runCacheBypass { body }  — caching disabled; always recomputes
    case Term.Apply.After_4_6_0(Term.Name("runCache"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.cacheRun(eval(bodyArgClause.values.head, env, interp), bypass = false, interp)
    case Term.Apply.After_4_6_0(Term.Name("runCacheBypass"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      EffectHandlers.cacheRun(eval(bodyArgClause.values.head, env, interp), bypass = true, interp)

    // ── v1.4 Tx effect handlers ───────────────────────────────────────────
    // runTx { body }  — default no-op handler (just runs body directly)
    case Term.Apply.After_4_6_0(Term.Name("runTx"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      eval(bodyArgClause.values.head, env, interp)

    // ── v1.5 httpClient(baseUrl) { block } ───────────────────────────────
    // Double-apply special form: evaluate body directly (not as a thunk) so
    // any statements inside the block run with feature-local HTTP state set,
    // then restore.
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("httpClient"), baseClause),
        bodyClause)
        if baseClause.values.size == 1 && bodyClause.values.size == 1 =>
      val baseComp = eval(baseClause.values.head, env, interp)
      baseComp match
        case Pure(Value.StringV(base)) =>
          val priorBase = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpBaseUrl)
          val priorT = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpTimeoutMs)
          val priorR = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpMaxRetries)
          val priorD = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpRetryDelayMs)
          interp.nativeFeatureLocalSet(interp.NativeFeatureKeys.HttpBaseUrl, base.stripSuffix("/"))
          try eval(bodyClause.values.head, env, interp)
          finally restoreHttpClientState(interp, priorBase, priorT, priorR, priorD)
        case _ =>
          FlatMap(baseComp, {
            case Value.StringV(base) =>
              val priorBase = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpBaseUrl)
              val priorT = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpTimeoutMs)
              val priorR = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpMaxRetries)
              val priorD = interp.nativeFeatureLocalGet(interp.NativeFeatureKeys.HttpRetryDelayMs)
              interp.nativeFeatureLocalSet(interp.NativeFeatureKeys.HttpBaseUrl, base.stripSuffix("/"))
              try eval(bodyClause.values.head, env, interp)
              finally restoreHttpClientState(interp, priorBase, priorT, priorR, priorD)
            case _ => throw InterpretError("httpClient(baseUrl: String) { body }")
          })

    // ── v1.6 Actors Phase 1 ────────────────────────────────────────────
    // `runActors { body }` installs an actor scheduler, spawns the body
    // as the root actor, and drives until quiescence.  The result is
    // whatever the root actor returned, or UnitV if it never did.
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      interp.actorInterp(eval(bodyArgClause.values.head, env, interp))

    // v1.5 Tier 5 #20 — `validate { body }` runs `body` with an active
    // validation collector.  Returns `Right(bodyResult)` when the body
    // ran without any `require*` complaint, else `Left(Map[field,
    // reason])` capturing every problem in document order.  `require*`
    // inside the block returns a safe default on miss/invalid so the
    // body keeps running and accumulates errors in one pass.
    case Term.Apply.After_4_6_0(Term.Name("validate"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val buf = mutable.LinkedHashMap.empty[String, String]
      interp.validationStack.push(buf)
      val bodyComp = try eval(bodyArgClause.values.head, env, interp)
                     finally ()  // never pop in the try — see below
      bodyComp.flatMap { result =>
        interp.validationStack.pop()
        if buf.nonEmpty then
          val errMap = Value.MapV(
            scala.collection.immutable.ListMap.from(
              buf.map { (k, v) => Value.StringV(k) -> Value.StringV(v) }
            )
          )
          Pure(Value.InstanceV("Left", new IMap.Map1("value", errMap)))
        else
          Pure(Value.InstanceV("Right", new IMap.Map1("value", result)))
      }

    // ── bench(name) { body } / bench(name, warmup, reps) { body } ───────────
    // Timing micro-benchmark: evaluates body warmup+reps times, prints a one-
    // line summary to interp.out, and returns the last result.
    //   bench("label") { expr }
    //   bench("label", 2, 7) { expr }   — explicit warmup / rep counts
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("bench"), nameClause),
        bodyClause)
        if nameClause.values.nonEmpty && bodyClause.values.size == 1 =>
      val nameVal = Computation.run(eval(nameClause.values.head, env, interp))
      val label = nameVal match
        case Value.StringV(s) => s
        case other            => Value.show(other)
      val warmup = nameClause.values.lift(1).map { t =>
        Computation.run(eval(t, env, interp)) match
          case Value.IntV(n) => n.toInt
          case _             => 2
      }.getOrElse(2)
      val reps = nameClause.values.lift(2).map { t =>
        Computation.run(eval(t, env, interp)) match
          case Value.IntV(n) => n.toInt
          case _             => 7
      }.getOrElse(7)
      val bodyTerm = bodyClause.values.head
      var lastResult: Value = Value.UnitV
      for _ <- 1 to warmup do
        lastResult = Computation.run(eval(bodyTerm, env, interp))
      val times = new Array[Long](reps)
      for i <- 0 until reps do
        val t0 = System.nanoTime()
        lastResult = Computation.run(eval(bodyTerm, env, interp))
        times(i) = (System.nanoTime() - t0) / 1_000_000L
      java.util.Arrays.sort(times)
      val p50 = times(reps / 2)
      val min = times(0)
      val max = times(reps - 1)
      interp.out.println(f"[bench] $label%-30s  p50=${p50}ms  min=${min}ms  max=${max}ms  ($reps reps)")
      Pure(lastResult)

    // `receive { case … }` — special form so we can pull interp.out the AST
    // cases at dispatch time.  Stashes (cases, env) and emits a Perform
    // whose payload is the integer token into that side table.
    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val id = interp.receiveSpecNext; interp.receiveSpecNext += 1
          interp.receiveSpecs(id) = (pf.cases, env)
          Perform("Actor", "receive", Value.intV(id) :: Nil)
        case _ =>
          interp.located("receive expects a partial function { case msg => ... }")

    // `receive(timeout = N) { case … }` — same dispatch, but the
    // driver also tracks a deadline.  On timeout the receive value
    // is None; on match it's Some(body-value).
    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive"), timeoutClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutClause.values.size == 1 =>
      val timeoutTerm = timeoutClause.values.head match
        case Term.Assign(Term.Name("timeout"), v) => v
        case other: Term                          => other
      val timeoutMs = Computation.run(eval(timeoutTerm, env, interp)) match
        case Value.IntV(n) => n
        case _ => throw InterpretError("receive timeout must be an Int (milliseconds)")
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val id = interp.receiveSpecNext; interp.receiveSpecNext += 1
          interp.receiveSpecs(id) = (pf.cases, env)
          Perform("Actor", "receive_t", Value.intV(id) :: Value.intV(timeoutMs) :: Nil)
        case _ =>
          interp.located("receive expects a partial function { case msg => ... }")

    // Special forms: `computed { ... }` / `effect { ... }` — by-name
    // bodies wrapped as zero-arg closures so the reactive machinery can
    // re-run them when dependencies change.  Without the special form
    // the block would be evaluated eagerly at call time and the runtime
    // would get a plain value instead of a thunk.
    case Term.Apply.After_4_6_0(Term.Name("computed"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val body  = bodyArgClause.values.head.asInstanceOf[Term]
      val thunk = Value.FunV(Nil, body, env, "")
      Pure(SignalRuntime.makeComputed(interp, thunk))
    case Term.Apply.After_4_6_0(Term.Name("effect"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val body  = bodyArgClause.values.head.asInstanceOf[Term]
      val thunk = Value.FunV(Nil, body, env, "")
      SignalRuntime.makeEffect(interp, thunk)
      Computation.PureUnit

    // ── v1.16 restartable { handlers } { body } ───────────────────────────
    // Common Lisp condition-system style handler: the body runs in a virtual
    // thread; when `throw e` fires inside it, the error is handed to the
    // handler partial function which returns a `Restart` decision:
    //   Restart.resume(v)   — body continues with `v` as the throw-expression result
    //   Restart.useDefault  — body continues with Value.UnitV (null/unit)
    //   Restart.rethrow     — exception propagates normally from interp restartable frame
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(Term.Name("restartable"), pfArgClause),
        bodyArgClause)
        if pfArgClause.values.size == 1 && bodyArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          EffectsRuntime.evalRestartable(bodyArgClause.values.head.asInstanceOf[Term], pf.cases, env, interp)
        case _ =>
          interp.located("restartable expects a partial function { case Error(…) => Restart.… }")

    // Function application: detect obj.method(args) and dispatch directly.
    // All sub-terms are evaluated eagerly here; the FlatMap chain composes
    // already-built Computations so placeholderIdx and other eval-time state
    // is observed correctly.
    case app: Term.Apply =>
      app.fun match
        // ── .copy(field = value, ...) on an InstanceV ────────────────
        // Named args arrive as Term.Assign(Term.Name(field), rhs); we have
        // to intercept BEFORE the generic eval path, otherwise Term.Assign
        // would fall into the var-assignment case and mutate interp.globals.
        case Term.Select(qual, Term.Name("copy")) =>
          OpticsRuntime.evalCopy(qual, app.argClause.values, env, interp)
        // ── Focus[T](_.a.b) / Focus(_.a.b) — Monocle-style lens ──────
        // Inspect the lambda body at AST level to extract a field-access
        // chain, then synthesise a Lens value with get / set / modify /
        // andThen. Done at AST level because the placeholder lambda is
        // otherwise erased to an opaque NativeFnV.
        case ta: Term.ApplyType if OpticsRuntime.isFocusName(ta.fun) =>
          OpticsRuntime.evalFocus(app.argClause.values, interp)
        case Term.Name("Focus") =>
          OpticsRuntime.evalFocus(app.argClause.values, interp)
        // ── direct[M] { stmts } — v1.8 do-notation sugar ─────────────
        case Term.ApplyType.After_4_6_0(Term.Name("direct"), typeArgClause) =>
          val typeArg = typeArgClause.values.headOption.getOrElse(Type.Name("?"))
          DirectTypeUtils.validateDirectTypeArg(typeArg)
          val tag = BlockRuntime.extractDirectMonadTag(typeArgClause.values)
          app.argClause.values match
            case List(block: Term.Block) => BlockRuntime.evalDirectBlock(block.stats, env, tag, interp)
            case List(single: Term)      => eval(single, env, interp)
            case _                       => interp.located("direct[M] expects a single block argument")
        // Db.query[T](...) — the interpreter intrinsic returns row maps;
        // preserve the erased type argument here and project each row map into
        // the registered case-class runtime shape so `ssc run` mirrors JvmGen.
        case Term.ApplyType.After_4_6_0(
              Term.Select(Term.Name("Db"), Term.Name("query")),
              typeArgClause
            ) if typeArgClause.values.nonEmpty =>
          val typeName = interp.typeToString(typeArgClause.values.head.asInstanceOf[scala.meta.Type])
          val qualC = eval(Term.Name("Db"), env, interp)
          val argComps = app.argClause.values.map {
            case Term.Assign(_, rhs) => eval(rhs, env, interp)
            case other               => eval(other, env, interp)
          }
          val argVsQ = extractPureValues(argComps)
          qualC match
            case Pure(qualV) if argVsQ != null =>
              DispatchRuntime.dispatch(qualV, "query", argVsQ, env, interp).map(projectTypedRows(typeName, _, interp))
            case _ =>
              FlatMap(qualC, qualV =>
                interp.threadValues(argComps)(argVals =>
                  DispatchRuntime.dispatch(qualV, "query", argVals, env, interp).map(projectTypedRows(typeName, _, interp))
                )
              )
        case Term.Select(qual, methodName: Term.Name) =>
          val method   = methodName.value
          val qualC    = eval(qual, env, interp)
          val argTerms = app.argClause.values
          // Named args (Term.Assign) must evaluate only the RHS; the full
          // Term.Assign path at line 2338 treats them as var-assignments and
          // returns UnitV, destroying the actual value.
          if argTerms.isEmpty then
            qualC match
              case Pure(qualV) => DispatchRuntime.dispatch(qualV, method, Nil, env, interp)
              case _           => FlatMap(qualC, qualV => DispatchRuntime.dispatch(qualV, method, Nil, env, interp))
          else if argTerms.lengthCompare(1) == 0 then
            val arg1C = argTerms.head match
              case Term.Assign(_, rhs) => eval(rhs, env, interp)
              case other               => eval(other, env, interp)
            qualC match
              case Pure(qv) =>
                arg1C match
                  case Pure(av) => DispatchRuntime.dispatch1(qv, method, av, env, interp)
                  case _        => FlatMap(arg1C, av => DispatchRuntime.dispatch1(qv, method, av, env, interp))
              case _ =>
                arg1C match
                  case Pure(av) => FlatMap(qualC, qv => DispatchRuntime.dispatch1(qv, method, av, env, interp))
                  case _        => FlatMap(qualC, qv => FlatMap(arg1C, av => DispatchRuntime.dispatch1(qv, method, av, env, interp)))
          else if argTerms.lengthCompare(2) == 0 then
            // 2-arg fast path: avoids extractPureValues ArrayBuffer + argComps List allocation.
            val arg1C = argTerms.head match
              case Term.Assign(_, rhs) => eval(rhs, env, interp)
              case other               => eval(other, env, interp)
            val arg2C = argTerms(1) match
              case Term.Assign(_, rhs) => eval(rhs, env, interp)
              case other               => eval(other, env, interp)
            qualC match
              case Pure(qv) =>
                arg1C match
                  case Pure(av1) =>
                    arg2C match
                      case Pure(av2) => DispatchRuntime.dispatch2(qv, method, av1, av2, env, interp)
                      case _         => FlatMap(arg2C, av2 => DispatchRuntime.dispatch2(qv, method, av1, av2, env, interp))
                  case _ =>
                    arg2C match
                      case Pure(av2) => FlatMap(arg1C, av1 => DispatchRuntime.dispatch2(qv, method, av1, av2, env, interp))
                      case _         => FlatMap(arg1C, av1 => FlatMap(arg2C, av2 => DispatchRuntime.dispatch2(qv, method, av1, av2, env, interp)))
              case _ =>
                arg1C match
                  case Pure(av1) if arg2C.isInstanceOf[Pure] =>
                    FlatMap(qualC, qv => DispatchRuntime.dispatch2(qv, method, av1, arg2C.asInstanceOf[Pure].value, env, interp))
                  case _ =>
                    FlatMap(qualC, qv =>
                      FlatMap(arg1C, av1 => FlatMap(arg2C, av2 => DispatchRuntime.dispatch2(qv, method, av1, av2, env, interp))))
          else
            val argComps = argTerms.map {
              case Term.Assign(_, rhs) => eval(rhs, env, interp)
              case other               => eval(other, env, interp)
            }
            val argVsS = extractPureValues(argComps)
            if argVsS != null then
              qualC match
                case Pure(qualV) => DispatchRuntime.dispatch(qualV, method, argVsS, env, interp)
                case _           => FlatMap(qualC, qualV => DispatchRuntime.dispatch(qualV, method, argVsS, env, interp))
            else
              FlatMap(qualC, qualV =>
                interp.threadValues(argComps)(argVals => DispatchRuntime.dispatch(qualV, method, argVals, env, interp)))
        // ── remoteStub[Api](baseUrl) / Remote.stub[Api](baseUrl) ─────────────
        // Pass the erased type-name as a second argument so RemoteIntrinsics
        // can look up the stored abstract method list and build per-method
        // NativeFnV entries that POST to {baseUrl}/{methodName}.
        case Term.ApplyType.After_4_6_0(Term.Name("remoteStub"), typeArgClause)
            if typeArgClause.values.nonEmpty && app.argClause.values.sizeIs == 1 =>
          val typeName = interp.typeToString(typeArgClause.values.head.asInstanceOf[scala.meta.Type])
          val baseUrlC = eval(app.argClause.values.head, env, interp)
          baseUrlC.flatMap { baseUrlV =>
            interp.callValue2(
              interp.globals.getOrElse("remoteStub", interp.located("remoteStub not found")),
              baseUrlV, Value.StringV(typeName), env)
          }
        case Term.ApplyType.After_4_6_0(
              Term.Select(Term.Name("Remote"), Term.Name("stub")), typeArgClause)
            if typeArgClause.values.nonEmpty && app.argClause.values.sizeIs == 1 =>
          val typeName = interp.typeToString(typeArgClause.values.head.asInstanceOf[scala.meta.Type])
          val baseUrlC = eval(app.argClause.values.head, env, interp)
          baseUrlC.flatMap { baseUrlV =>
            interp.callValue2(
              interp.globals.getOrElse("remoteStub", interp.located("remoteStub not found")),
              baseUrlV, Value.StringV(typeName), env)
          }
        // ── obj.method[T](args) — type args erased, dispatch with actual args
        // Mirrors the bare Term.Select(qual, method) path above so that
        // type-parameterised method calls like `Dataset.of[Int]()` reach
        // the dispatcher with the right argument list (otherwise the
        // standalone `Dataset.of` would auto-call as a no-arg NativeFnV
        // and then the outer `()` would fail on the resulting value).
        case Term.ApplyType.After_4_6_0(Term.Select(qual, Term.Name(method)), _) =>
          val qualC    = eval(qual, env, interp)
          val argComps = app.argClause.values.map {
            case Term.Assign(_, rhs) => eval(rhs, env, interp)
            case other               => eval(other, env, interp)
          }
          val argVsT = extractPureValues(argComps)
          if argVsT != null then
            qualC match
              case Pure(qualV) => DispatchRuntime.dispatch(qualV, method, argVsT, env, interp)
              case _           => FlatMap(qualC, qualV => DispatchRuntime.dispatch(qualV, method, argVsT, env, interp))
          else
            FlatMap(qualC, qualV =>
              interp.threadValues(argComps)(argVals => DispatchRuntime.dispatch(qualV, method, argVals, env, interp)))
        case _ =>
          evalPlainApply(app, env, interp)

    // '.!' outside a direct block (or inside a lambda/block in a direct block) — error
    case Term.Select(_, sn: Term.Name) if sn.value == "!" =>
      interp.located("'.!' can only appear in expression position directly inside a direct[M] block body; not inside lambdas or nested blocks")

    // Field / method selection: a.b  (no-arg call)
    case Term.Select(qual, sn: Term.Name) =>
      val method = sn.value
      eval(qual, env, interp) match
        case Pure(qualV) => DispatchRuntime.dispatch(qualV, method, Nil, env, interp)
        case qualC       => FlatMap(qualC, qualV => DispatchRuntime.dispatch(qualV, method, Nil, env, interp))

    // Fast path: s"${expr}" or s"prefix${expr}suffix" — 1-arg s-interpolation.
    // Avoids: 2 List allocations (cast map + evalArgs map), threadValues,
    // StringBuilder, for loop. Covers the dominant s"..." pattern.
    case Term.Interpolate(Term.Name("s"), List(p0: Lit.String, p1: Lit.String), List(arg: Term)) =>
      val pre = p0.value; val suf = p1.value
      eval(arg, env, interp) match
        case Pure(v) => Pure(Value.StringV(pre + Value.show(v) + suf))
        case argC    => FlatMap(argC, v => Pure(Value.StringV(pre + Value.show(v) + suf)))

    // Fast path: 2-arg s"${e1}mid${e2}" or similar.
    case Term.Interpolate(Term.Name("s"), List(p0: Lit.String, p1: Lit.String, p2: Lit.String), List(a1: Term, a2: Term)) =>
      val pre = p0.value; val mid = p1.value; val suf = p2.value
      val c1 = eval(a1, env, interp); val c2 = eval(a2, env, interp)
      c1 match
        case Pure(v1) =>
          c2 match
            case Pure(v2) => Pure(Value.StringV(pre + Value.show(v1) + mid + Value.show(v2) + suf))
            case _        => FlatMap(c2, v2 => Pure(Value.StringV(pre + Value.show(v1) + mid + Value.show(v2) + suf)))
        case _ =>
          c2 match
            case Pure(v2) => FlatMap(c1, v1 => Pure(Value.StringV(pre + Value.show(v1) + mid + Value.show(v2) + suf)))
            case _        => FlatMap(c1, v1 => FlatMap(c2, v2 => Pure(Value.StringV(pre + Value.show(v1) + mid + Value.show(v2) + suf))))

    // String interpolation s"..." / f"..." / md"..." / html"..." / css"..."
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md"
        || prefix == "html" || prefix == "css" =>
      evalArgs(args.map(_.asInstanceOf[Term]), env, interp) { argVs =>
        val sb = StringBuilder()
        // f"..." semantics: the literal part following each ${} can begin
        // with a Java/printf-style format spec applied to the preceding arg.
        //   `f"${pi}%.2f"` →  parts = ["", "%.2f"], spec consumed off parts(1)
        val fmtRe = "^%[-+# 0,(]*\\d*(?:\\.\\d+)?[bBhHsScCdoxXeEfgGaAtT%]".r
        for i <- parts.indices do
          val partStr = parts(i).asInstanceOf[Lit.String].value
          // The first part precedes any interpolation; never consumes a spec.
          val (consumedSpec, partRest) =
            if i > 0 && prefix == "f" then
              fmtRe.findFirstIn(partStr) match
                case Some(spec) => (Some(spec), partStr.substring(spec.length))
                case None       => (None, partStr)
            else (None, partStr)
          // Emit the interpolated value for index (i - 1) before interp part's text.
          if i > 0 then
            val v = argVs(i - 1)
            val rendered = consumedSpec match
              case Some(spec) =>
                val boxed: AnyRef = v match
                  case Value.IntV(n)    => java.lang.Long.valueOf(n)
                  case Value.DoubleV(d) => java.lang.Double.valueOf(d)
                  case Value.BoolV(b)   => java.lang.Boolean.valueOf(b)
                  case Value.CharV(c)   => java.lang.Character.valueOf(c)
                  case Value.StringV(s) => s
                  case other            => Value.show(other)
                try String.format(spec, boxed)
                catch case _: java.util.IllegalFormatException => Value.show(v)
              case None =>
                Value.show(v)
            sb ++= (if prefix == "html" then interp.htmlEscapeUnlessRaw(v, rendered) else rendered)
          sb ++= partRest
        val raw = sb.toString
        Pure(Value.StringV(if prefix == "md" then interp.stripIndent(raw) else raw))
      }

    // User-defined interpolator — build StringContext instance and call prefix fn.
    // Vararg `args: Any*` is packed into a single ListV so the body sees it as
    // an indexed list (args(0), args.length, etc.).
    case Term.Interpolate(Term.Name(prefix), parts, args) =>
      evalArgs(args.map(_.asInstanceOf[Term]), env, interp) { argVs =>
        val partVals = parts.map(p => Value.StringV(p.asInstanceOf[Lit.String].value))
        val sc = Value.InstanceV("StringContext", new IMap.Map1("parts", Value.ListV(partVals)))
        val scExts = interp.extensions.getOrElse("StringContext", null)
        val extFn = if scExts != null then scExts.getOrElse(prefix, null) else null
        val fn: Value = if extFn != null then extFn
          else
            val fv = env.getOrElse(prefix, interp.globals.getOrElse(prefix, null))
            if fv != null then fv else interp.located(s"Unknown interpolator '$prefix': not in scope")
        interp.callValue2(fn, sc, Value.ListV(argVs), env)
      }

    // Anonymous function with _ placeholders: _.field, _ + 1, _ + _, etc.
    case t: Term.AnonymousFunction =>
      Pure(Value.NativeFnV("anon", args => {
        val saved = interp._phIdxTL.get()
        interp._phIdxTL.set(0)
        val phEnv = env ++ args.zipWithIndex.map { (v, i) => s"_$$${i}" -> v }
        try eval(t.body, phEnv, interp)
        finally interp._phIdxTL.set(saved)
      }))

    // _ placeholder — numbered left-to-right via mutable counter
    case _: Term.Placeholder =>
      val i = interp._phIdxTL.get()
      interp._phIdxTL.set(interp._phIdxTL.get() + 1)
      Pure(env.getOrElse(s"_$$${i}", interp.located("Unexpected _")))

    // Lambda  x => body  or  (x, y) => body
    // Type test (not the After_4_6_0 extractor) to avoid a per-eval Some+Tuple2;
    // this case is hit once per foreach/map call in a hot loop.
    case t: Term.Function =>
      val paramClause = t.paramClause
      val body        = t.body
      // Drop only the keys whose env value still matches the live interp.globals —
      // those are top-level bindings that the lambda should re-read from
      // `interp.globals` at call time (so a `var` reassigned later is visible).
      // Genuine closure captures (outer def params, block-local vals) have
      // a different value in env than in interp.globals and must be kept; otherwise
      // a param like `a` for `def adder(a)` is stripped because interp.globals also
      // hold the `<a>` HTML tag under that name, and the inner `b => a + b`
      // would resolve `a` to the tag instead of the captured Int.
      // Build closure by walking only FrameMap local slots, skipping the globals
      // parent chain.  Avoids O(|globals|) iteration that env.filter would do.
      val closure: Map[String, Value] = env match
        case fm: FrameMap =>
          val b = new scala.collection.mutable.HashMap[String, Value]
          var cur: Map[String, Value] = fm
          while cur.isInstanceOf[FrameMap] do
            cur.asInstanceOf[FrameMap].appendLocalTo(b, interp.globals)
            cur = cur.asInstanceOf[FrameMap].parent
          // Include terminal non-FrameMap parent (e.g., a prior closure Map).
          // Local bindings already in `b` take priority — do NOT overwrite them.
          if cur ne interp.globals then
            cur.foreachEntry { (k, v) =>
              if !b.contains(k) && interp.globals.getOrElse(k, null) != v then b(k) = v
            }
          b.toMap
        case _ =>
          if env eq interp.globals then Map.empty
          else
            // foreachEntry (not .filter) so we never allocate a Tuple2 per entry;
            // builder stays null until a genuine capture appears, so the common
            // all-globals case (empty closure) allocates nothing.
            var b: scala.collection.mutable.HashMap[String, Value] = null
            env.foreachEntry { (k, v) =>
              if interp.globals.getOrElse(k, null) != v then
                if b == null then b = new scala.collection.mutable.HashMap[String, Value]
                b(k) = v
            }
            if b == null then Map.empty else b.toMap
      // Extract and cache lambda parameter metadata once per AST node. Typed
      // handler mounting reads paramTypes, with empty string for unannotated params.
      var paramInfo = interp.paramInfoCache.get(paramClause)
      if paramInfo == null then
        paramInfo = (
          paramClause.values.map(_.name.value),
          paramClause.values.map(p => p.decltpe.fold("")(interp.typeToString))
        )
        interp.paramInfoCache.put(paramClause, paramInfo)
      val (paramNames, paramTypes) = paramInfo
      Pure(Value.FunV(paramNames, body, closure, paramTypes = paramTypes))

    // Partial function  { case pat => body; ... }  — e.g. xs.map { case (k, v) => ... }
    // Compiled and cached per AST node so matchPat/Option allocations happen once, not per call.
    case t: Term.PartialFunction =>
      var compiled = interp.pfCache.get(t)
      if compiled == null then
        compiled = PatternRuntime.compilePF(t, interp)
        interp.pfCache.put(t, compiled)
      val compiledPF = compiled
      Pure(Value.NativeFnV("partial", args => {
        val arg = args match
          case List(v) => v
          case vs      => Value.TupleV(vs)
        compiledPF.run(arg, env, interp)
      }))

    // Tuple  (a, b, ...)
    // Fast paths for 2- and 3-tuples avoid the intermediate List[Computation]
    // allocation that evalArgs creates for all-Pure cases.
    // Nested match instead of (c1, c2) match to avoid Tuple2/Tuple3 allocation.
    case Term.Tuple(List(e1: Term, e2: Term)) =>
      val c1 = eval(e1, env, interp); val c2 = eval(e2, env, interp)
      c1 match
        case Pure(v1) =>
          c2 match
            case Pure(v2) => Pure(Value.TupleV(v1 :: v2 :: Nil))
            case _        => FlatMap(c2, v2 => Pure(Value.TupleV(v1 :: v2 :: Nil)))
        case _ =>
          c2 match
            case Pure(v2) => FlatMap(c1, v1 => Pure(Value.TupleV(v1 :: v2 :: Nil)))
            case _        => FlatMap(c1, v1 => FlatMap(c2, v2 => Pure(Value.TupleV(v1 :: v2 :: Nil))))
    case Term.Tuple(List(e1: Term, e2: Term, e3: Term)) =>
      val c1 = eval(e1, env, interp); val c2 = eval(e2, env, interp); val c3 = eval(e3, env, interp)
      c1 match
        case Pure(v1) =>
          c2 match
            case Pure(v2) =>
              c3 match
                case Pure(v3) => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil))
                case _        => FlatMap(c3, v3 => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil)))
            case _ =>
              c3 match
                case Pure(v3) => FlatMap(c2, v2 => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil)))
                case _        => FlatMap(c2, v2 => FlatMap(c3, v3 => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil))))
        case _ =>
          c2 match
            case Pure(v2) =>
              c3 match
                case Pure(v3) => FlatMap(c1, v1 => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil)))
                case _        => FlatMap(c1, v1 => FlatMap(c3, v3 => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil))))
            case _ =>
              c3 match
                case Pure(v3) => FlatMap(c1, v1 => FlatMap(c2, v2 => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil))))
                case _        => FlatMap(c1, v1 => FlatMap(c2, v2 => FlatMap(c3, v3 => Pure(Value.TupleV(v1 :: v2 :: v3 :: Nil)))))
    case Term.Tuple(elems) =>
      evalArgs(elems, env, interp)(vs => Pure(Value.TupleV(vs)))

    // new ClassName(args)
    case Term.New(Init.After_4_6_0(tpe, _, argClauses)) =>
      val typeName = tpe match { case Type.Name(n) => n; case _ => "?" }
      val argTerms = argClauses.toList.flatMap(_.values)
      evalArgs(argTerms, env, interp) { argVals =>
        env.getOrElse(typeName, interp.globals.getOrElse(typeName,
          interp.located(s"Unknown constructor: $typeName"))) match
            case c: Value.NativeFnV => c.f(argVals)
            case f: Value.FunV      => interp.callFun(f, argVals)
            case _ => interp.located(s"$typeName is not a constructor")
      }

    // for x <- xs yield f(x)
    case t: Term.ForYield =>
      PatternRuntime.evalForYield(t.enumsBlock.enums, t.body, env, interp)

    // for x <- xs do f(x)
    case t: Term.For =>
      PatternRuntime.evalForDo(t.enumsBlock.enums, t.body, env, Map.empty, interp).flatMap(Computation.discardToUnit)

    // while cond do body  — refresh env from interp.globals each iteration so mutations are visible.
    // Snapshot interp.globals at loop entry: only update a key on subsequent iterations if its
    // interp.globals value CHANGED since entry (i.e., was written by Term.Assign).  This prevents
    // a pre-existing interp.globals entry (e.g. the HTML `<a>` tag) from clobbering a local
    // `var a = 0` that happens to shadow it.
    //
    // Hot path: instead of creating a new Map each iteration, maintain a mutable frame
    // that is updated in-place and exposed via MutableEnvView.  O(K) lookup per iteration
    // where K = keys that changed in globals (typically 0 or 1); no Map allocation.
    //
    // Frame size optimization: only copy env entries that are absent from (or differ from)
    // interp.globals.  These are the locally-declared vars that the loop can mutate.
    // All other entries (builtins, global defs) are already in interp.globals and
    // EvalRuntime.Term.Name's globals fallback makes them visible without copying.
    // This shrinks frame from O(N_globals) to O(N_local_vars) — typically 2-5 entries.
    case t: Term.While =>
      val frame: scala.collection.mutable.HashMap[String, Value] = env match
        case fm: FrameMap =>
          val b = scala.collection.mutable.HashMap.empty[String, Value]
          var cur: Map[String, Value] = fm
          while cur.isInstanceOf[FrameMap] do
            val fm2 = cur.asInstanceOf[FrameMap]
            fm2.appendLocalTo(b, interp.globals)
            cur = fm2.parent
          if cur ne interp.globals then
            cur.foreachEntry { (k, v) =>
              if !b.contains(k) && interp.globals.getOrElse(k, null) != v then b(k) = v
            }
          b
        case _ =>
          val b2 = scala.collection.mutable.HashMap.empty[String, Value]
          env.foreachEntry { (k, v) => if interp.globals.getOrElse(k, null) != v then b2(k) = v }
          b2
      // Top-level loops already run in the interpreter globals map.  If the
      // compacted frame is empty and the incoming env is just a globals view,
      // execute the loop body directly against globals instead of creating a
      // side frame that must be refreshed every iteration.
      val useGlobalsFrame = frame.isEmpty && (env match
        case mev: MutableEnvView => mev.underlying.asInstanceOf[AnyRef] eq interp.globals.asInstanceOf[AnyRef]
        case _                   => false
      )
      // Snapshot the GLOBALS value of each frame key at loop entry.
      // refreshFn fires when a global was reassigned after loop start — so we compare
      // current globals vs. globals-at-entry, not globals vs. local-at-entry.
      val entrySnap: scala.collection.mutable.HashMap[String, Value] = {
        val s = scala.collection.mutable.HashMap.empty[String, Value]
        if !useGlobalsFrame then frame.foreachEntry { (k, _) => s(k) = interp.globals.getOrElse(k, null) }
        s
      }
      val frameView = new MutableEnvView(if useGlobalsFrame then interp.globals else frame)
      val fastLoop = tryFastWhileAssign(t, frameView, interp)
      if fastLoop != null then fastLoop
      else
        // Hoist per-iteration closures: allocated once per while-entry, reused across all iterations.
        val refreshFn: (String, Value) => Unit = (k, _) => {
          val gv = interp.globals.getOrElse(k, null)
          if gv != null && entrySnap.getOrElse(k, null) != gv then frame(k) = gv
        }
        lazy val loopCont: Value => Computation = _ => loop
        lazy val condCont: Value => Computation = {
          case Value.BoolV(true) => FlatMap(eval(t.body, frameView, interp), loopCont)
          case _                 => Computation.PureUnit
        }
        def loop: Computation =
          // Refresh mutable frame: only overwrite a key if globals changed since entry.
          if !useGlobalsFrame && frame.nonEmpty then frame.foreachEntry(refreshFn)
          // All-pure fast path: if both the condition and body are pure on the first
          // iteration, run subsequent iterations in a plain JVM while loop — zero
          // FlatMap allocations for tight loops (saves 2 allocs × N iterations).
          // Falls back to the trampoline path on the first effectful step.
          eval(t.expr, frameView, interp) match
            case Pure(Value.BoolV(true)) =>
              eval(t.body, frameView, interp) match
                case Pure(_) =>
                  var running = true
                  while running do
                    if !useGlobalsFrame && frame.nonEmpty then frame.foreachEntry(refreshFn)
                    eval(t.expr, frameView, interp) match
                      case Pure(Value.BoolV(true)) =>
                        eval(t.body, frameView, interp) match
                          case Pure(_)  => ()
                          case bodyComp => return FlatMap(bodyComp, loopCont)
                      case Pure(_) => running = false
                      case condComp => return FlatMap(condComp, condCont)
                  Computation.PureUnit
                case bodyComp => FlatMap(bodyComp, loopCont)
            case Pure(_)   => Computation.PureUnit
            case condComp  => FlatMap(condComp, condCont)
        loop

    // return expr  (non-local via exception)
    case Term.Return(expr) =>
      eval(expr, env, interp).flatMap(v => throw ReturnSignal(v))

    // var/field assignment
    case Term.Assign(Term.Name(name), rhs) =>
      eval(rhs, env, interp) match
        case Pure(v) => interp.globals(name) = v; Computation.PureUnit
        case c       => FlatMap(c, { v => interp.globals(name) = v; Computation.PureUnit })

    // summon[TC[T]] — retrieve a given instance from the table
    case t: Term.ApplyType =>
      (t.fun, t.argClause.values) match
        case (Term.Name("summon"), List(typeArg)) =>
          val key = interp.typeToString(typeArg.asInstanceOf[scala.meta.Type])
          // 1. Direct lookup in env / interp.globals
          val direct = env.getOrElse(key, interp.globals.getOrElse(key, null))
          val found: Value | Null =
            if direct != null then direct
            else
              // 2. For generic keys like "Show[A]" try:
              //    a) resolveGiven (infers concrete type from regular args if any)
              //    b) scan env for a synthetic context-bound param "A$TC"
              GivenRuntime.resolveGiven(key, Nil, env, interp) match
                case Some(v) => v
                case None =>
                  val tcEnd = key.indexOf('[')
                  if tcEnd > 0 then
                    val tc         = key.substring(0, tcEnd)
                    val typeArgStr = key.substring(tcEnd + 1, key.length - 1).trim
                    env.getOrElse(s"${typeArgStr}$$${tc}", null)
                  else null
          if found != null then Pure(found)
          else Pure(interp.located(s"No given instance for '$key'"))

        // Prism[Outer, Variant] — focus on a single sum-type variant.
        case (Term.Name("Prism"), List(_, variantType)) =>
          val variantName = variantType match
            case n: Type.Name => n.value
            case _            => interp.located("Prism[Outer, Variant]: Variant must be a simple type name")
          Pure(OpticsRuntime.buildPrism(variantName, interp))

        // compiletime.constValue[T] — return the compile-time constant value of type T
        case (Term.Select(Term.Name("compiletime"), Term.Name("constValue")), List(typeArg)) =>
          Pure(CallRuntime.constValueOfType(typeArg.asInstanceOf[scala.meta.Type]))

        // compiletime.summonInline[TC[T]] — look up a given instance
        case (Term.Select(Term.Name("compiletime"), Term.Name("summonInline")), List(typeArg)) =>
          val key = interp.typeToString(typeArg.asInstanceOf[scala.meta.Type])
          val fv = env.getOrElse(key, interp.globals.getOrElse(key, null))
          val found: Value | Null =
            if fv != null then fv
            else GivenRuntime.resolveGiven(key, Nil, env, interp).orNull
          if found != null then Pure(found)
          else Pure(interp.located(s"No given instance for '$key' (summonInline)"))

        // Mirror.of[T] — runtime view of the same product/sum metadata used by derives.
        case (Term.Select(Term.Name("Mirror"), Term.Name("of")), List(typeArg)) =>
          val typeName = interp.typeToString(typeArg.asInstanceOf[scala.meta.Type])
          Pure(DerivesRuntime.mirrorForType(typeName, interp))

        case _ => eval(t.fun, env, interp)  // other type applications — erase type args

    // Prefix unary operators: `!x`, `-x`, `+x`, `~x`.
    case t: Term.ApplyUnary =>
      val op = t.op.value
      @inline def applyUnary(v: Value): Computation = (op, v) match
        case ("!", Value.BoolV(b))   => Computation.pureBool(!b)
        case ("-", Value.IntV(n))    => Computation.pureIntV(-n)
        case ("-", Value.DoubleV(d)) => Pure(Value.doubleV(-d))
        case ("+", n: Value.IntV)    => Pure(n)
        case ("+", d: Value.DoubleV) => Pure(d)
        case ("~", Value.IntV(n))    => Computation.pureIntV(~n)
        case (_, other)              => interp.located(s"Cannot apply unary $op to ${Value.show(other)}")
      eval(t.arg, env, interp) match
        case Pure(v) => applyUnary(v)
        case c       => FlatMap(c, applyUnary)

    case t: Term.Throw =>
      eval(t.expr, env, interp).flatMap { v =>
        if interp._insideDirectBlock.get() then Pure(Value.InstanceV("Left", new IMap.Map1("value", v)))
        else
          // v1.16: check for an active restartable frame on interp thread.
          // If present, suspend the body and let the handler decide.
          val rsStack = interp.restartableStack()
          val rsHandle = rsStack.peekFirst()
          if rsHandle != null then
            rsHandle.errorQ.put(v)   // send error to handler thread
            rsHandle.resumeQ.take() match  // wait for handler's decision
              case Right(resumeVal) => Pure(resumeVal)   // resume with replacement
              case Left(rethrowVal) =>
                // Throw RestartableRethrow (not ScriptException) so the
                // body thread's catch wrapper knows interp is a terminal rethrow
                // and doesn't route it back through the handler again.
                throw RestartableRethrow(rethrowVal)
          else
            val isNoTrace = v match
              case Value.InstanceV(typeName, _) => interp.noTraceTypes.contains(typeName)
              case _ => false
            if isNoTrace then throw ScriptExceptionNoTrace(v)
            else throw ScriptException(v)
      }

    case t: Term.Try =>
      @annotation.nowarn("msg=deprecated")
      def tryCatch(thrownVal: Value, cause: Throwable): Value =
        t.catchp.iterator.flatMap { c =>
          val bound = PatternRuntime.matchPat(c.pat, thrownVal, Map.empty, interp)
          if bound == null then Iterator.empty else Iterator.single((c, bound))
        }.nextOption() match
          case Some((matchedCase, bound)) => Computation.run(eval(matchedCase.body, env ++ bound, interp))
          case None                       => throw cause
      val tryResult: Value =
        try Computation.run(eval(t.expr, env, interp))
        catch
          case rr: RestartableRethrow => throw rr  // v1.16: let terminal rethrows pass through
          case se: ScriptException  => tryCatch(se.value, se)
          case th: Throwable =>
            // Convert any JVM exception (NumberFormatException, InterpretError, etc.)
            // into a ScalaScript InstanceV so catch patterns can match it.
            val exTypeName = th.getClass.getSimpleName
            val msg = Option(th.getMessage).getOrElse(exTypeName)
            tryCatch(Value.InstanceV(exTypeName, new IMap.Map1("message", Value.StringV(msg))), th)
      t.finallyp match
        case Some(f) => Computation.run(eval(f, env, interp))
        case None    =>
      Pure(tryResult)

    case t: Term.Ascribe =>
      eval(t.expr, env, interp)

    case other => interp.located(s"Cannot eval: ${other.productPrefix}")

  // ─── Lenses / Optics — see OpticsRuntime.scala ─────────────────────

    /** Evaluate a list of argument terms eagerly to a list of Computations, then
   *  thread their values into `k` via FlatMap chain.
   *
   *  Eager evaluation matters because `eval` mutates `placeholderIdx`; deferring
   *  sub-term evaluation into a FlatMap continuation would observe a wrong index
   *  later. After interp call all sub-Computations are fully built; only the final
   *  composition (the FlatMap chain) is interpreted lazily. */
  private def evalArgs(args: List[Term], env: Env, interp: Interpreter)(k: List[Value] => Computation): Computation =
    val argComps = args.map(eval(_, env, interp))
    interp.threadValues(argComps)(k)

  /** Extract values from a list of Pure computations in one pass.
   *  Returns null if any computation is non-Pure (caller falls back to threadValues). */
  private[interpreter] def extractPureValues(comps: List[Computation]): List[Value] | Null =
    // Fast paths for 0–3 args avoid ArrayBuffer + toList entirely.
    // Non-pure small-arity returns null immediately without allocating the buffer.
    comps match
      case Nil                                     => Nil
      case List(Pure(v))                           => v :: Nil
      case List(_)                                 => null
      case List(Pure(v1), Pure(v2))                => v1 :: v2 :: Nil
      case List(_, _)                              => null
      case List(Pure(v1), Pure(v2), Pure(v3))      => v1 :: v2 :: v3 :: Nil
      case List(_, _, _)                           => null
      case _ =>
        val buf  = new scala.collection.mutable.ArrayBuffer[Value](comps.length)
        var rest = comps
        while rest.nonEmpty do
          rest.head match
            case Pure(v) => buf += v; rest = rest.tail
            case _       => return null
        buf.toList

  private def projectTypedRows(typeName: String, value: Value, interp: Interpreter): Value = value match
    case Value.ListV(rows) =>
      Value.ListV(rows.map(projectTypedRow(typeName, _, interp)))
    case other => other

  private def projectTypedRow(typeName: String, row: Value, interp: Interpreter): Value = row match
    case Value.MapV(entries) =>
      val order = interp.typeFieldOrder.getOrElse(typeName, null)
      val fields = if order != null then
        val schemas = interp.typeFieldSchemas.getOrElse(typeName,
          order.map(name => TypeFieldSchema(name, name, Nil, None, key = false)))
        if interp.rejectUnknownTypes.contains(typeName) then
          val known = schemas.iterator.flatMap(_.storageNames).map(_.toLowerCase(java.util.Locale.ROOT)).toSet
          entries.keys.collectFirst {
            case Value.StringV(k) if !known.contains(k.toLowerCase(java.util.Locale.ROOT)) => k
          }.foreach(name => throw InterpretError(s"$$.$name: unknown column '$name'"))
        schemas.map { schema =>
          schema.fieldName -> lookupRowField(entries, schema)
        }.toMap
      else
        entries.collect { case (Value.StringV(k), v) => k -> v }
      Value.InstanceV(typeName, fields)
    case other => other

  private def lookupRowField(entries: Map[Value, Value], schema: TypeFieldSchema): Value =
    val snIt = schema.storageNames.iterator
    while snIt.hasNext do
      val name = snIt.next()
      val exact = entries.getOrElse(Value.StringV(name), null)
      if exact != null then return exact
      val entIt = entries.iterator
      while entIt.hasNext do
        entIt.next() match
          case (Value.StringV(k), v) if k.equalsIgnoreCase(name) => return v
          case _ =>
    schema.default.getOrElse(Value.NullV)

  private def restoreHttpClientState(
      interp: Interpreter,
      priorBase: Option[Any],
      priorTimeout: Option[Any],
      priorRetries: Option[Any],
      priorRetryDelay: Option[Any]
  ): Unit =
    restoreFeatureLocal(interp, interp.NativeFeatureKeys.HttpBaseUrl, priorBase)
    restoreFeatureLocal(interp, interp.NativeFeatureKeys.HttpTimeoutMs, priorTimeout)
    restoreFeatureLocal(interp, interp.NativeFeatureKeys.HttpMaxRetries, priorRetries)
    restoreFeatureLocal(interp, interp.NativeFeatureKeys.HttpRetryDelayMs, priorRetryDelay)

  private def restoreFeatureLocal(interp: Interpreter, key: String, value: Option[Any]): Unit =
    value match
      case Some(v) => interp.nativeFeatureLocalSet(key, v)
      case None    => interp.nativeFeatureLocalRemove(key)

  /** Peel nested `Apply` nodes to collect all argument lists for a curried call.
   *  Only activates when the **outermost** `Apply` has a `using` argument clause
   *  (i.e. `mod = Some(Mod.Using())`).  In that case we collect all arg lists
   *  (regular + using) into a single flat list and return the base callee.
   *
   *  This handles `f(regularArgs)(using usingArgs)` without affecting ordinary
   *  curried calls like `onWebSocket(path) { handler }` which have no `using` mod.
   */
  def collectApplyArgs(app: Term.Apply): (Term, List[Term]) =
    // Only flatten when interp outer Apply carries `using` args
    if app.argClause.mod.isEmpty then (app.fun, app.argClause.values)
    else
      def peel(t: Term, acc: List[Term]): (Term, List[Term]) = t match
        case inner: Term.Apply => inner.fun match
          // Stop at select / type-apply / other complex funs
          case _: Term.Select | _: Term.ApplyType | _: Term.ApplyInfix =>
            (t, acc)
          case _ =>
            peel(inner.fun, inner.argClause.values ++ acc)
        case other => (other, acc)
      peel(app.fun, app.argClause.values)

  /** Thread a list of already-built Computations: bind each in order and feed
   *  the resulting values to `k`. */
  def threadValues(comps: List[Computation])(k: List[Value] => Computation): Computation =
    // Specialised fast paths for the most common small-arity cases (0–3 args).
    // Each pattern match avoids ArrayBuffer + toList + FlatMap chain overhead
    // when all arg evaluations are Pure — the common case for literal arguments.
    comps match
      case Nil => k(Nil)
      case List(Pure(v1)) => k(v1 :: Nil)
      case List(Pure(v1), Pure(v2)) => k(v1 :: v2 :: Nil)
      case List(Pure(v1), Pure(v2), Pure(v3)) => k(v1 :: v2 :: v3 :: Nil)
      case _ =>
        // General case: all-Pure check then FlatMap chain.
        var allPure = true
        var rest    = comps
        val buf     = new scala.collection.mutable.ArrayBuffer[Value](comps.length)
        while allPure && rest.nonEmpty do
          rest.head match
            case Pure(v) => buf += v; rest = rest.tail
            case _       => allPure = false
        if allPure then k(buf.toList)
        else
          def chain(remaining: List[Computation], acc: List[Value]): Computation = remaining match
            case Nil       => k(acc.reverse)
            case c :: rest => FlatMap(c, v => chain(rest, v :: acc))
          chain(comps, Nil)

  // ─── Block eval + direct[M] — see BlockRuntime.scala ─────────────────

  // ─── Call helpers ─────────────────────────────────────────────────
