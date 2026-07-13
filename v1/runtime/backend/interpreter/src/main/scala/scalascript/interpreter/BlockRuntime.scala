package scalascript.interpreter

import scala.collection.mutable
import scala.collection.immutable.{Map => IMap}
import scala.meta.*
import scalascript.transform.{DirectAnorm, DirectTypeUtils}
import Computation.{Pure, FlatMap}
import DirectMonadTag.*

/** Block evaluation (`evalBlock`), direct-monad do-notation (`evalDirectBlock`),
 *  and supporting helpers.
 */
private[interpreter] object BlockRuntime:

  def extractDirectMonadTag(typeArgs: List[scala.meta.Type]): DirectMonadTag =
    val name = typeArgs.headOption.flatMap(DirectTypeUtils.extractPrimaryMonad).getOrElse("?")
    name match
      case "Option" => OptionM
      case "List"   => ListM
      case "Either" => EitherM
      case "Async"  => AsyncM
      case _        => OtherM

  // ── effect-cps-p41 (specs/effect-vm-continuations.md §5) — compiled straight-line eff-block ──
  // A pre-classified statement of a straight-line effectful block. `runCompiled` (inside evalBlock)
  // replays these segments on every multi-shot `resume` instead of re-walking `step`'s per-statement
  // `s match` dispatch (+ list-cons + Defn.Val/Pat.Var unapply). See §5 for the win/non-win analysis:
  // it cuts the block-side structural re-walk + the pure-Int-arith re-eval; performs stay on the
  // unchanged `interp.eval` monadic path (effects never folded/reordered).
  private[interpreter] final class ArithPlan(val names: Array[String], val compute: Array[Long] => Long)
  private[interpreter] sealed abstract class CStep
  private[interpreter] final class CValStep(val name: String, val rhs: Term, val arith: ArithPlan | Null) extends CStep
  private[interpreter] final class CExprStep(val rhs: Term, val arith: ArithPlan | Null) extends CStep

  /** Compile a pure-Int-arithmetic expr (env-name + Int/Long literals; `+`/`-`/`*`, unary `±`) into
   *  an `Array[Long] => Long` closure over its distinct free names — the §2c `compileIntArith`
   *  analogue keyed by env name instead of perform-arg slot. Returns null for any shape not provably
   *  bit-identical to `interp.eval` over 64-bit `Long` (`/`,`%`, Double, conversions, calls, tuples).
   *  At replay the names are read from the block-local env and checked to be `IntV` before the closure
   *  runs; a non-`IntV`/absent operand falls back to `fastPrimitiveValue`/`interp.eval` (semantics
   *  unconditionally preserved). A constant expr (no names) is left to `fastPrimitiveValue`/const-cache. */
  private def compileEnvIntArith(t: Term): ArithPlan | Null =
    val names = scala.collection.mutable.LinkedHashSet.empty[String]
    def ok(e: Term): Boolean = e match
      case _: Lit.Int | _: Lit.Long => true
      case n: Term.Name             => names += n.value; true
      case Term.ApplyUnary(op, a)   => (op.value == "-" || op.value == "+") && ok(a)
      case ai: Term.ApplyInfix
          if ai.argClause.values.lengthCompare(1) == 0 &&
             (ai.op.value == "+" || ai.op.value == "-" || ai.op.value == "*") =>
        ok(ai.lhs) && ok(ai.argClause.values.head)
      case _ => false
    if !ok(t) || names.isEmpty then return null
    val nameArr = names.toArray
    val idx: Map[String, Int] = nameArr.iterator.zipWithIndex.toMap
    val compute = compileArithSlots(t, idx)
    if compute == null then null else new ArithPlan(nameArr, compute.nn)

  private def compileArithSlots(t: Term, idx: Map[String, Int]): (Array[Long] => Long) | Null =
    t match
      case Lit.Int(n)   => (_: Array[Long]) => n.toLong
      case Lit.Long(n)  => (_: Array[Long]) => n
      case Term.Name(x) => idx.get(x) match { case Some(i) => (a: Array[Long]) => a(i); case None => null }
      case Term.ApplyUnary(op, a) if op.value == "+" => compileArithSlots(a, idx)
      case Term.ApplyUnary(op, a) if op.value == "-" =>
        val c = compileArithSlots(a, idx); if c == null then null else { val cf = c.nn; (x: Array[Long]) => -cf(x) }
      case ai: Term.ApplyInfix if ai.argClause.values.lengthCompare(1) == 0 =>
        val l = compileArithSlots(ai.lhs, idx)
        val r = compileArithSlots(ai.argClause.values.head, idx)
        if l == null || r == null then null
        else
          val lf = l.nn; val rf = r.nn
          ai.op.value match
            case "+" => (a: Array[Long]) => lf(a) + rf(a)
            case "-" => (a: Array[Long]) => lf(a) - rf(a)
            case "*" => (a: Array[Long]) => lf(a) * rf(a)
            case _   => null
      case _ => null

  /** §5: recognise a straight-line effectful block — every statement is either a `val name = rhs`
   *  (single irrefutable `Pat.Var` binder) or a bare expression statement that is neither an
   *  assignment nor a compound-assignment. Returns the compiled segments, or **null to bail to
   *  `step`** for any other shape (destructuring/typed `Defn.Val`, `Defn.Var`, `Term.Assign`,
   *  compound-assign, `Defn.Def`, other `Stat`s — those need `step`'s local+global write-through /
   *  scoping). Requires ≥2 stats (single-statement blocks use `evalBlock`'s own fast path). */
  private def compileEffBlock(stats: List[Stat]): Array[CStep] | Null =
    val buf = scala.collection.mutable.ArrayBuffer.empty[CStep]
    var cur = stats
    while cur.nonEmpty do
      cur.head match
        case dv: Defn.Val =>
          dv.pats match
            case List(Pat.Var(n)) => buf += new CValStep(n.value, dv.rhs, compileEnvIntArith(dv.rhs))
            case _                => return null
        case _: Defn.Var         => return null
        case _: Term.Assign      => return null
        case ai: Term.ApplyInfix
            if ai.op.value.lengthIs > 1 && ai.op.value.last == '=' && !isCompareOp(ai.op.value) =>
          return null
        case t: Term             => buf += new CExprStep(t, compileEnvIntArith(t))
        case _                   => return null
      cur = cur.tail
    if buf.lengthIs < 2 then null else buf.toArray

  private val EmptyStringArray: Array[String] = Array.empty[String]

  /** interp-var-scope-leak-across-calls: the `var` names this block declares via a
   *  single-`Pat.Var` `Defn.Var`. This is EXACTLY the shape that `step` dual-writes to
   *  `interp.globals` (line ~227); destructuring vars and `Defn.Val` stay block-local
   *  (they write only `local`, so they never leak across calls). Scanned once per block
   *  AST and cached by `stats` identity in `interp.blockVarNamesCache`. */
  private def collectVarDeclNames(stats: List[Stat]): Array[String] =
    var buf: scala.collection.mutable.ArrayBuffer[String] = null
    var cur = stats
    while cur.nonEmpty do
      cur.head match
        case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, _) =>
          if buf == null then buf = scala.collection.mutable.ArrayBuffer.empty[String]
          buf += n.value
        case _ =>
      cur = cur.tail
    if buf == null then EmptyStringArray else buf.toArray

  /** Evaluate a block of statements; effects propagate through statements via flatMap.
   *  val/var declarations are threaded as Computation so effects in their rhs work. */
  def evalBlock(stats: List[Stat], env: Env, interp: Interpreter): Computation =
    // Single-statement block with no declaration: no new scope layer is needed,
    // so evaluate the lone term directly against the incoming env — no HashMap,
    // no MutableEnvView. A `Term` is never a `Defn.Val`/`Defn.Var`, so the
    // declaration-scoping guard below cannot apply. The local copy that the
    // general path threads exists only to keep *later* statements' reads
    // consistent — dead in a single-statement block.
    stats match
      // Plain `x = e` handled inline rather than via the big `eval` dispatch
      // (where Term.Assign matches late, behind ~40 special-form cases): eval
      // only the rhs, then write through to globals exactly as `step` does.
      case (assign: Term.Assign) :: Nil if assign.lhs.isInstanceOf[Term.Name] =>
        val x = assign.lhs.asInstanceOf[Term.Name].value
        return interp.eval(assign.rhs, env) match
          case Pure(v) => interp.globals(x) = v; Computation.PureUnit
          case c       => FlatMap(c, { v => interp.globals(x) = v; Computation.PureUnit })
      // Any other lone term (expression, compound-assign infix, nested block):
      // the general path already routes these through `eval`, so no extra
      // dispatch cost — and we still skip the env copy.
      case (t: Term) :: Nil => return interp.eval(t, env)
      case _                =>
    // When env is already a MutableEnvView (e.g. the while-loop fast path) and the block
    // has no local declarations (only assignments/expressions), reuse the underlying map
    // and view directly — no new HashMap, no copy.
    // For blocks with Defn.Val/Var we create a fresh layer so declarations stay block-scoped.
    // Use vars instead of val tuple to avoid the Tuple2 allocation that HotSpot fails to EA
    // when evalBlock is called 1M times per tight while loop.
    var localVar: mutable.Map[String, Value] = null
    var localViewVar: MutableEnvView = null
    // The MutableEnvView-reuse branch is the hot while-loop body path (assign/expr blocks, no
    // declarations); keep it on `step` (the compiled-eff-block path below targets declaration
    // blocks — the straight-line effect-continuation shape — so skip its cache lookup here).
    var reusedView = false
    if env.isInstanceOf[MutableEnvView] &&
       !stats.exists { case _: Defn.Val | _: Defn.Var => true; case _ => false }
    then
      val mev = env.asInstanceOf[MutableEnvView]
      localVar = mev.underlying
      localViewVar = mev
      reusedView = true
    else env match
      case fm: FrameMap =>
        // Only copy env entries that are absent from (or differ from) interp.globals.
        // These are params and shadowed vals; everything else is already visible via
        // interp.globals and the Term.Name fallback, so we skip copying it.
        // Shrinks the frame from O(N_env) to O(N_params + N_stale_closure) — typically 1–5 entries.
        // Walk only FrameMap local slots to avoid O(|globals|) iteration.
        // After the FrameMap chain, the terminal parent is either interp.globals
        // (skip it — huge) or a small closure map (iterate it to capture closures).
        val b = mutable.HashMap.empty[String, Value]
        var cur: Map[String, Value] = fm
        while cur.isInstanceOf[FrameMap] do
          val fm2 = cur.asInstanceOf[FrameMap]
          fm2.appendLocalTo(b, interp.globals)
          cur = fm2.parent
        // If the terminal parent is the real globals map, skip it.
        // Otherwise it's a closure HashMap — iterate and capture non-global entries.
        // IMPORTANT: params already in `b` (from the FrameMap chain above) take
        // priority — do NOT overwrite them with closure entries that happen to carry
        // a same-named but semantically-different value (e.g. the HTML `<a>` tag
        // from a child interpreter's enriched closure overwriting a param named `a`).
        if cur ne interp.globals then
          cur.foreachEntry { (k, v) =>
            if !b.contains(k) && interp.globals.getOrElse(k, null) != v then b(k) = v
          }
        localVar = b; localViewVar = new MutableEnvView(b)
      case _ =>
        val b2 = mutable.HashMap.empty[String, Value]
        env.foreachEntry { (k, v) => if interp.globals.getOrElse(k, null) != v then b2(k) = v }
        localVar = b2; localViewVar = new MutableEnvView(b2)
    // Effectively-final aliases: `step` (and its FlatMap continuations) close over
    // these. As `val`s they are captured by reference without `ObjectRef` boxing —
    // capturing the `var`s above forced a per-block `ObjectRef.create` (JFR: ~9% of
    // patternMatch allocations) on every multi-statement block in a hot loop.
    val local     = localVar
    val localView = localViewVar
    def step(remaining: List[Stat], lastVal: Value): Computation = remaining match
      case Nil => Pure(lastVal)
      case s :: rest =>
        s match
          // Type-test + direct field access (`dv.pats`/`dv.rhs`) instead of the
          // `Defn.Val(_, pats, _, rhs)` unapply, which allocates a `Some` + `Tuple4` per
          // statement — hot on the per-resume continuation re-eval path (effect-vm-cont-p3b:
          // `Tuple4` ~89 JFR samples in `step` across 3125 multi-shot paths).
          case dv: Defn.Val =>
            val pats = dv.pats
            // effect-cps-continuation slice 1: a single `val x = <pure expr>` (the arithmetic
            // "scoring" segments of an effect-body continuation, e.g. `val sb = b*b + sa`) binds a
            // bare Value via the fast path — no `evalCore` megamorphic dispatch, no `Pure` alloc
            // (that dispatch is ~49% of the multi-shot `effectMultiShotDeep` CPU re-walk). SAFE: a
            // non-null `fastPrimitiveValue` means the rhs is provably side-effect-free (effect ops
            // are `NativeFnV` → null → the monadic path below performs them; effectful fn bodies
            // don't compile → null), so binding directly cannot drop a `perform`. Only the single
            // `Pat.Var` shape; destructuring / anything else uses `interp.eval` unchanged.
            val fastBound: Boolean = pats match
              case List(Pat.Var(n)) =>
                val fv = EvalRuntime.fastPrimitiveValue(dv.rhs, localView, interp)
                if fv != null then { local(n.value) = fv; true } else false
              case _ => false
            if fastBound then step(rest, Value.UnitV)
            else interp.eval(dv.rhs, localView) match
              case Pure(rhsVal) =>
                pats match
                  case List(Pat.Var(n)) => local(n.value) = rhsVal
                  case List(pat) =>
                    val patEnv = PatternRuntime.matchPat(pat, rhsVal, localView, interp)
                    if patEnv == null then interp.located("Val pattern match failed")
                    else patEnv.foreach { (k, v) => local(k) = v }
                  case _ =>
                step(rest, Value.UnitV)
              case rhsC => FlatMap(rhsC, { rhsVal =>
                pats match
                  case List(Pat.Var(n)) => local(n.value) = rhsVal
                  case List(pat) =>
                    val patEnv = PatternRuntime.matchPat(pat, rhsVal, localView, interp)
                    if patEnv == null then interp.located("Val pattern match failed")
                    else patEnv.foreach { (k, v) => local(k) = v }
                  case _ =>
                step(rest, Value.UnitV)
              })
          case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
            interp.eval(rhs, localView) match
              case Pure(v) =>
                local(n.value) = v; interp.globals(n.value) = v; step(rest, Value.UnitV)
              case rhsC =>
                FlatMap(rhsC, { v => local(n.value) = v; interp.globals(n.value) = v; step(rest, Value.UnitV) })
          // Variable assignment: write to local AND globals so that both the
          // current evalBlock and any enclosing while loop (via freshEnv) see it.
          // This also keeps local in sync, avoiding a stale-read on the next statement
          // without needing a full O(N) refresh scan.
          // Type-only check on Term.Assign avoids Tuple2 from unapply; then direct
          // field access on the lhs Term.Name avoids the Some[String] from Name.unapply.
          case assign: Term.Assign if assign.lhs.isInstanceOf[Term.Name] =>
            val x = assign.lhs.asInstanceOf[Term.Name].value
            interp.eval(assign.rhs, localView) match
              case Pure(v) => local(x) = v; interp.globals(x) = v; step(rest, Value.UnitV)
              case c       => FlatMap(c, { v => local(x) = v; interp.globals(x) = v; step(rest, Value.UnitV) })
          // Compound assignment inside a block (x += n, x -= n, etc.).
          case Term.ApplyInfix.After_4_6_0(lhs: Term.Name, op, _, argClause)
              if op.value.lengthIs > 1 && op.value.last == '=' &&
                 !BlockRuntime.isCompareOp(op.value) =>
            val baseOp = op.value.init
            if argClause.values.lengthCompare(1) == 0 then
              val rhsC = interp.eval(argClause.values.head, localView)
              interp.eval(lhs, localView) match
                case Pure(lhsV) =>
                  rhsC match
                    case Pure(rv) =>
                      interp.infix2(lhsV, baseOp, rv, localView) match
                        case Pure(newV) => local(lhs.value) = newV; interp.globals(lhs.value) = newV; step(rest, Value.UnitV)
                        case c          => FlatMap(c, { newV => local(lhs.value) = newV; interp.globals(lhs.value) = newV; step(rest, Value.UnitV) })
                    case _ =>
                      FlatMap(rhsC, { rv =>
                        FlatMap(interp.infix2(lhsV, baseOp, rv, localView), { newV =>
                          local(lhs.value) = newV; interp.globals(lhs.value) = newV; step(rest, Value.UnitV) }) })
                case lhsC =>
                  FlatMap(lhsC, { lhsV =>
                    FlatMap(rhsC, { rv =>
                      FlatMap(interp.infix2(lhsV, baseOp, rv, localView), { newV =>
                        local(lhs.value) = newV; interp.globals(lhs.value) = newV; step(rest, Value.UnitV) }) }) })
            else
              val argComps   = argClause.values.map(interp.eval(_, localView))
              val argVsBlock = EvalRuntime.extractPureValues(argComps)
              interp.eval(lhs, localView) match
                case Pure(lhsV) if argVsBlock != null =>
                  interp.infix(lhsV, baseOp, argVsBlock, localView) match
                    case Pure(newV) => local(lhs.value) = newV; interp.globals(lhs.value) = newV; step(rest, Value.UnitV)
                    case c          => FlatMap(c, { newV => local(lhs.value) = newV; interp.globals(lhs.value) = newV; step(rest, Value.UnitV) })
                case lhsC =>
                  FlatMap(lhsC, { lhsV =>
                    interp.threadValues(argComps) { argVs =>
                      FlatMap(interp.infix(lhsV, baseOp, argVs, localView), { newV =>
                        local(lhs.value)          = newV
                        interp.globals(lhs.value) = newV
                        step(rest, Value.UnitV)
                      })
                    }
                  })
          case t: Term =>
            // effect-cps-continuation slice 2a: an expression statement — most importantly the
            // block's RESULT expr (e.g. `e*e + sd`, evaluated once per complete continuation path,
            // 3125× for effectMultiShotDeep — MORE than all the intermediate vals combined). Bind it
            // via the fast path (bare Value, no `evalCore` megamorphic dispatch / `Pure` alloc) when
            // it is provably pure; `fastPrimitiveValue` returns null for any effectful expr (a
            // `perform` etc.) → unchanged monadic path, so no effect is dropped or reordered.
            val fv = EvalRuntime.fastPrimitiveValue(t, localView, interp)
            if fv != null then step(rest, fv)
            else interp.eval(t, localView) match
              case Pure(v) => step(rest, v)
              case c       => FlatMap(c, v => step(rest, v))
          case stat =>
            interp.execStat(stat, local)
            step(rest, Value.UnitV)

    // ── effect-cps-p41 (§5): replay pre-compiled segments instead of re-walking `step` ──
    // Read each arith free name from the block-local map; return the Longs iff all are IntV,
    // else null (→ the step falls back to fastPrimitiveValue / interp.eval — semantics preserved).
    def readIntSlots(names: Array[String]): Array[Long] | Null =
      val out = new Array[Long](names.length)
      var k = 0
      while k < names.length do
        local.getOrElse(names(k), null) match
          case iv: Value.IntV => out(k) = iv.v; k += 1
          case _              => return null
      out
    def arithValue(plan: ArithPlan | Null): Value | Null =
      if plan == null then null
      else
        val slots = readIntSlots(plan.names)
        if slots == null then null else Value.intV(plan.compute(slots))
    // Pre-classified replay of `step`. Each arm MIRRORS the corresponding `step` arm (slice-1 Defn.Val
    // / slice-2a Term-result fast paths + the unchanged monadic perform path), with no per-statement
    // `s match` / list-cons / unapply, plus the compiled pure-Int-arith fast path. FlatMap
    // continuations re-enter `runCompiled(i+1)` exactly as `step`'s re-enter `step(rest)`.
    def runCompiled(steps: Array[CStep], i: Int, lastVal: Value): Computation =
      if i >= steps.length then Pure(lastVal)
      else steps(i) match
        case vs: CValStep =>
          val av = arithValue(vs.arith)
          if av != null then { local(vs.name) = av; runCompiled(steps, i + 1, Value.UnitV) }
          else
            val fv = EvalRuntime.fastPrimitiveValue(vs.rhs, localView, interp)
            if fv != null then { local(vs.name) = fv; runCompiled(steps, i + 1, Value.UnitV) }
            else interp.eval(vs.rhs, localView) match
              case Pure(v) => local(vs.name) = v; runCompiled(steps, i + 1, Value.UnitV)
              case c       => FlatMap(c, { v => local(vs.name) = v; runCompiled(steps, i + 1, Value.UnitV) })
        case es: CExprStep =>
          val av = arithValue(es.arith)
          if av != null then runCompiled(steps, i + 1, av)
          else
            val fv = EvalRuntime.fastPrimitiveValue(es.rhs, localView, interp)
            if fv != null then runCompiled(steps, i + 1, fv)
            else interp.eval(es.rhs, localView) match
              case Pure(v) => runCompiled(steps, i + 1, v)
              case c       => FlatMap(c, v => runCompiled(steps, i + 1, v))

    // Route a multi-statement DECLARATION block (the straight-line effect-continuation shape) to the
    // compiled replay when it recognises; cache the plan (or the bail sentinel) by `stats` identity.
    // The hot reused-view loop-body path keeps `step` (no cache lookup, no regression).
    val compiledSteps: Array[CStep] | Null =
      if reusedView then null
      else
        val cached = interp.effBlockCache.get(stats)
        if cached != null then
          if cached eq interp.EffBlockMiss then null else cached.asInstanceOf[Array[CStep]]
        else
          val c = compileEffBlock(stats)
          interp.effBlockCache.put(stats, if c == null then interp.EffBlockMiss else c.asInstanceOf[AnyRef])
          c
    def runDispatch(): Computation =
      if compiledSteps != null then runCompiled(compiledSteps, 0, Value.UnitV)
      else step(stats, Value.UnitV)

    // ── interp-var-scope-leak-across-calls ──
    // A `Defn.Var` in this block dual-writes `interp.globals` (see `step` ~line 246):
    // the write is load-bearing because the `while` loop re-reads its counter from
    // globals during the function body. Without per-call scoping a callee's `var X`
    // clobbers a caller's live `var X` of the same name (both land in the single
    // module-global `interp.globals` map keyed by name). Fix: snapshot the globals
    // value of each var name THIS block declares that ALREADY exists in globals (i.e.
    // an OUTER binding this declaration shadows), then restore those on block exit.
    //
    // Names ABSENT from globals at entry are intentionally left in place on exit — a
    // fresh name never clobbers anything, and a returned closure that dropped the var
    // (relying on the `interp.globals` fallback, EvalRuntime Term.Function capture)
    // must still read it after the function returns. Nested/recursive calls each
    // snapshot+restore their own shadow, so every stack level sees its own var.
    //
    // The `while` loop is unaffected: the counter lives in the ENCLOSING function
    // block and is restored only at that block's EXIT (after the loop), so the JIT /
    // fast-while paths read/write globals exactly as before during the loop. The
    // restore is deferred past effect suspension (it rides a `FlatMap` continuation so
    // it fires on true completion, not on a mid-block `perform`) and also fires on the
    // exception paths (`return`/throw) via the surrounding `catch`.
    // reusedView blocks declare no vars (guarded above), so skip the scan entirely.
    if reusedView then runDispatch()
    else
      val declNames: Array[String] =
        val cached = interp.blockVarNamesCache.get(stats)
        if cached != null then cached
        else
          val ns = collectVarDeclNames(stats)
          interp.blockVarNamesCache.put(stats, ns)
          ns
      if declNames.length == 0 then runDispatch()
      else
        // Snapshot only the OUTER (present-before) bindings this block shadows.
        var saved: List[(String, Value)] = Nil
        var k = 0
        while k < declNames.length do
          val g = interp.globals.getOrElse(declNames(k), null)
          if g != null then saved = (declNames(k), g) :: saved
          k += 1
        if saved eq Nil then runDispatch() // nothing shadowed → no restore needed
        else
          val savedList = saved
          def restore(): Unit =
            var s = savedList
            while s.nonEmpty do
              interp.globals(s.head._1) = s.head._2
              s = s.tail
          try
            runDispatch() match
              case p: Pure => restore(); p
              case comp    => FlatMap(comp, { v => restore(); Pure(v) })
          catch
            case e: Throwable => restore(); throw e

  /** True when `op` is a comparison operator ending in `=` (not a compound assignment). */
  private[interpreter] inline def isCompareOp(op: String): Boolean =
    op == ">=" || op == "<=" || op == "!=" || op == "=="

  /** Intercept monadic bind to auto-lift foreign monad values.
   *
   *  When `direct[Async]` (tag = AsyncM) and the bound value is an `Option`
   *  or `Either`, the block uses OptionT/EitherT semantics automatically. */
  private def liftBindValue(
    monadValue: Value,
    tag:        DirectMonadTag,
    cont:       Value => Computation,
    cur:        Env,
    interp:     Interpreter
  ): Computation =
    (tag, monadValue) match
      case (AsyncM,  ov: Value.OptionV) =>
        if ov.inner != null then cont(ov.inner) else Computation.PureNone
      case (AsyncM,  Value.InstanceV("Right", f)) if f.contains("value") => cont(f("value"))
      case (AsyncM,  Value.InstanceV("Left", _))                  => Pure(monadValue)
      case (EitherM, ov: Value.OptionV) =>
        if ov.inner != null then cont(ov.inner)
        else Pure(Value.InstanceV("Left", new IMap.Map1("value", Value.UnitV)))
      case (OptionM, Value.InstanceV("Right", f)) if f.contains("value") => cont(f("value"))
      case (OptionM, Value.InstanceV("Left", _))                  => Computation.PureNone
      case (ListM | OtherM, _) | _ =>
        val contFn = Value.NativeFnV("direct-lift-cont", args => cont(args.head))
        DispatchRuntime.dispatch1(monadValue, "flatMap", contFn, cur, interp)

  private def checkDirectBlockStatics(stats: List[Stat], interp: Interpreter): Unit =
    def isNestedDirect(t: Tree): Boolean = t match
      case app: Term.Apply =>
        app.fun match
          case Term.ApplyType.After_4_6_0(Term.Name("direct"), _) => true
          case _ => false
      case _ => false
    def go(t: Tree): Unit = t match
      case _: Term.Return =>
        interp.located("'return' inside a direct block escapes the flatMap chain — for early failure use the monad's zero (None, Nil, Left(err), …) instead")
      case _ if isNestedDirect(t) => ()
      case _: Defn.Def | _: Term.Function => ()
      case other => other.children.foreach(go)
    stats.foreach(go)

  /** Evaluate a `direct[M] { stmts }` block by desugaring bind-forms
   *  (`x = expr`) into `monadValue.flatMap { x => rest }` calls. */
  def evalDirectBlock(
    stats:  List[Stat],
    env:    Env,
    tag:    DirectMonadTag,
    interp: Interpreter
  ): Computation =
    checkDirectBlockStatics(stats, interp)
    val expanded = DirectAnorm.expand(stats)
    val varNames: Set[String] = expanded.collect {
      case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, _) => n.value
    }.toSet
    val prevInsideDirect = interp._insideDirectBlock.get()
    interp._insideDirectBlock.set(true)

    def step(remaining: List[Stat], cur: Env): Computation = remaining match
      case Nil => Computation.PureUnit

      case (t: Term.Throw) :: Nil =>
        interp.eval(t.expr, cur).flatMap { v =>
          Pure(Value.InstanceV("Left", new IMap.Map1("value", v)))
        }

      case (last: Term) :: Nil =>
        interp.eval(last, cur)

      case Term.Assign(Term.Name(x), rhs) :: rest if varNames.contains(x) =>
        FlatMap(interp.eval(rhs, cur), { v => step(rest, FrameMap.one(x, v, cur)) })

      case Term.Assign(Term.Name(x), rhs) :: rest =>
        FlatMap(interp.eval(rhs, cur), { monadValue =>
          liftBindValue(monadValue, tag, innerVal => step(rest, FrameMap.one(x, innerVal, cur)), cur, interp)
        })

      case Defn.Val(_, List(_: Pat.Wildcard), _, rhs) :: rest =>
        FlatMap(interp.eval(rhs, cur), { monadValue =>
          liftBindValue(monadValue, tag, _ => step(rest, cur), cur, interp)
        })

      case Defn.Val(_, pats, _, rhs) :: rest =>
        FlatMap(interp.eval(rhs, cur), { v =>
          pats match
            case List(Pat.Var(n)) => step(rest, FrameMap.one(n.value, v, cur))
            case List(pat) =>
              val patEnv = PatternRuntime.matchPat(pat, v, cur, interp)
              if patEnv == null then interp.located("direct block: val pattern match failed")
              else step(rest, cur ++ patEnv)
            case _ => step(rest, cur)
        })

      case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) :: rest =>
        FlatMap(interp.eval(rhs, cur), { v => step(rest, FrameMap.one(n.value, v, cur)) })

      case (t: Term.Throw) :: _ =>
        interp.eval(t.expr, cur).flatMap { v =>
          Pure(Value.InstanceV("Left", new IMap.Map1("value", v)))
        }

      case (t: Term) :: rest =>
        FlatMap(interp.eval(t, cur), _ => step(rest, cur))

      case (d: Defn.Def) :: rest =>
        val allClauses2       = d.paramClauseGroups.flatMap(_.paramClauses)
        val regularClauses2   = allClauses2.filter(_.mod.isEmpty)
        val usingClauses2     = allClauses2.filter(_.mod.nonEmpty)
        val regularParamVals2 = regularClauses2.flatMap(_.values).toList
        val usingParamVals2   = usingClauses2.flatMap(_.values).toList
        @annotation.nowarn("msg=deprecated")
        val cbUsingParams2: List[(String, String)] =
          d.paramClauseGroups.flatMap(_.tparamClause.values).flatMap { tp =>
            tp.cbounds.map { cb =>
              val tvName = tp.name.value
              val tcStr  = interp.typeToString(cb.asInstanceOf[scala.meta.Type])
              s"${tvName}$$${tcStr.takeWhile(_ != '[')}" -> s"$tcStr[$tvName]"
            }
          }
        val allRegularVals2 = regularParamVals2 ++ usingParamVals2
        val params2     = allRegularVals2.map(_.name.value) ++ cbUsingParams2.map(_._1)
        val defaults2   = allRegularVals2.map(_.default)    ++ cbUsingParams2.map(_ => None)
        val paramTypes2 = regularParamVals2.map(p => p.decltpe.fold("Any")(interp.typeToString))
        val usingInfo2: List[(String, String)] =
          usingParamVals2.map(p => p.name.value -> p.decltpe.fold("Any")(interp.typeToString)) ++ cbUsingParams2
        val capturedEnv: Map[String, Value] = cur match
          case fm: FrameMap =>
            val b = new scala.collection.mutable.HashMap[String, Value]
            var c: Map[String, Value] = fm
            while c.isInstanceOf[FrameMap] do
              c.asInstanceOf[FrameMap].appendLocalTo(b, interp.globals)
              c = c.asInstanceOf[FrameMap].parent
            if c ne interp.globals then
              c.foreachEntry { (k, v) => if !b.contains(k) && interp.globals.getOrElse(k, null) != v then b(k) = v }
            b.toMap
          case _ =>
            val b = new scala.collection.mutable.HashMap[String, Value]
            cur.foreachEntry { (k, v) => if interp.globals.getOrElse(k, null) != v then b(k) = v }
            b.toMap
        val rThrows2 = d.decltpe.exists(interp.isThrowsType)
        val fn = Value.FunV(params2, d.body, capturedEnv, d.name.value, defaults2, paramTypes2, usingInfo2, rThrows2)
        step(rest, FrameMap.one(d.name.value, fn, cur))

      case _ :: rest =>
        step(rest, cur)

    try step(expanded, env)
    finally interp._insideDirectBlock.set(prevInsideDirect)
