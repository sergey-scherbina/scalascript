package scalascript.interpreter.vm.jit

import scala.meta.{Defn, Lit, Pat, Term, Tree}
import scalascript.interpreter.{Interpreter, Value}

// JitBailReason ADT lives in JitBailReason.scala (promoted to public type).
// This file contains the predicates, report data types, and the JitLint analyser.

// JitBailReason cases are defined in JitBailReason.scala (same package).

/** A single while-loop's lint result.  `bailReasons` is empty when the loop
 *  was compiled by the JIT successfully (or is cached as compiled).
 *
 *  `condLine` is the source line of the while condition; `bodyLine` is the
 *  line of the body block.  Both may be absent when position information
 *  is not attached (e.g. synthetic AST nodes in tests). */
final case class JitLintWhileReport(
  condLine:    Option[Int],
  bodyLine:    Option[Int],
  bailReasons: List[JitBailReason]
):
  def willJit: Boolean = bailReasons.isEmpty
  def humanReadable: String =
    val loc = (condLine, bodyLine) match
      case (Some(c), _) => s" (line $c)"
      case (_, Some(b)) => s" (body line $b)"
      case _            => ""
    val header = s"while$loc"
    if willJit then s"$header  [JIT OK]"
    else
      val body = bailReasons.map { r =>
        val fix = r.suggestedFix.fold("")(s => s"\n      fix: $s")
        s"  - ${r.description}$fix"
      }.mkString("\n")
      s"$header  [will NOT JIT]\n$body"

/** Side-by-side while-loop lint result for both backends, produced by
 *  `JitLint.lintWhileLoopsCompare`. */
final case class JitLintWhileCompareReport(
  condLine: Option[Int],
  bodyLine: Option[Int],
  javac:    JitLintWhileReport,
  asm:      JitLintWhileReport
):
  def bothJit:       Boolean = javac.willJit  && asm.willJit
  def asmOnlyFails:  Boolean = javac.willJit  && !asm.willJit
  def javacOnlyFails: Boolean = !javac.willJit && asm.willJit
  def bothFail:      Boolean = !javac.willJit && !asm.willJit
  def anyFail:       Boolean = !javac.willJit || !asm.willJit

  def humanReadable: String =
    val loc = (condLine, bodyLine) match
      case (Some(c), _) => s" (line $c)"
      case (_, Some(b)) => s" (body line $b)"
      case _            => ""
    val header   = s"while$loc"
    val javacTag = if javac.willJit then "[JAVAC OK]" else "[JAVAC FAIL]"
    val asmTag   = if asm.willJit   then "[ASM OK]"   else "[ASM FAIL]"
    val sb = new StringBuilder(s"$header  $javacTag $asmTag")
    if bothFail && javac.bailReasons == asm.bailReasons then
      appendReasons("  both", javac.bailReasons, sb)
    else
      if !javac.willJit then appendReasons("  JAVAC", javac.bailReasons, sb)
      if !asm.willJit   then appendReasons("  ASM",   asm.bailReasons,   sb)
    sb.toString

  private def appendReasons(
    label:   String,
    reasons: List[JitBailReason],
    sb:      StringBuilder
  ): Unit =
    sb.append(s"\n$label:")
    reasons.foreach { r =>
      sb.append(s"\n    - ${r.description}")
      r.suggestedFix.foreach(f => sb.append(s"\n      fix: $f"))
    }

/** A single Defn.Def's lint result. `bailReasons` is empty when the def
 *  would JIT successfully (or already has — JitBackend caches compile
 *  results and the lint asks the live cache). */
final case class JitLintReport(
  defName:     String,
  defLine:     Option[Int],
  bailReasons: List[JitBailReason]
):
  def willJit: Boolean = bailReasons.isEmpty
  def humanReadable: String =
    val header = s"def $defName${defLine.fold("")(l => s" (line $l)")}"
    if willJit then s"$header  [JIT OK]"
    else
      val body = bailReasons.map { r =>
        val fix = r.suggestedFix.fold("")(s => s"\n      fix: $s")
        s"  - ${r.description}$fix"
      }.mkString("\n")
      s"$header  [will NOT JIT]\n$body"

/** Side-by-side lint result for both backends, produced by
 *  `JitLint.lintInterpreterCompare`.  Useful for surfacing functions that
 *  JIT on Javac but not ASM (or vice-versa) so ASM-parity regressions are
 *  visible without running a full benchmark. */
final case class JitLintCompareReport(
  defName: String,
  defLine: Option[Int],
  javac:   JitLintReport,
  asm:     JitLintReport
):
  def bothJit:      Boolean = javac.willJit  && asm.willJit
  def asmOnlyFails: Boolean = javac.willJit  && !asm.willJit
  def javacOnlyFails: Boolean = !javac.willJit && asm.willJit
  def bothFail:     Boolean = !javac.willJit && !asm.willJit
  def anyFail:      Boolean = !javac.willJit || !asm.willJit

  def humanReadable: String =
    val header   = s"def $defName${defLine.fold("")(l => s" (line $l)")}"
    val javacTag = if javac.willJit then "[JAVAC OK]" else "[JAVAC FAIL]"
    val asmTag   = if asm.willJit   then "[ASM OK]"   else "[ASM FAIL]"
    val sb = new StringBuilder(s"$header  $javacTag $asmTag")
    if bothFail && javac.bailReasons == asm.bailReasons then
      appendReasons("  both", javac.bailReasons, sb)
    else
      if !javac.willJit then appendReasons("  JAVAC", javac.bailReasons, sb)
      if !asm.willJit   then appendReasons("  ASM",   asm.bailReasons,   sb)
    sb.toString

  private def appendReasons(
    label:   String,
    reasons: List[JitBailReason],
    sb:      StringBuilder
  ): Unit =
    sb.append(s"\n$label:")
    reasons.foreach { r =>
      sb.append(s"\n    - ${r.description}")
      r.suggestedFix.foreach(f => sb.append(s"\n      fix: $f"))
    }

/** Backend-agnostic view of a JIT compile context, exposing just the name- and
 *  global-resolution queries the shape predicates need.  Each backend's private
 *  `GenCtx` implements this in terms of its own internals (slot indices for
 *  `AsmJitBackend`, Java variable names for `JavacJitBackend`).  Sharing this
 *  narrow interface — rather than `GenCtx` itself, whose two representations are
 *  structurally unrelated — lets `looksLongValue`/`objectRefFallbackAllowed`
 *  have a single definition that classifies identically on both backends. */
trait JitShapeCtx:
  /** `n` is a ref-typed param or binding (cannot be read as a Long). */
  def isRefName(n: String): Boolean
  /** `n` is a String-typed param. */
  def isStringName(n: String): Boolean
  /** `n` resolves to a local Long slot/variable in this compile unit.
   *  Abstracts the sole per-backend difference (`slotOf(n) >= 0` for ASM,
   *  `resolveLocal(n) != null` for Javac), keeping the predicate bodies
   *  byte-identical so they cannot drift. */
  def isLocalLong(n: String): Boolean
  /** `n` is a registered val-bound lambda (inlined at its call site). */
  def isLambda(n: String): Boolean
  /** Top-level global `n` is bound to an `IntV`. */
  def globalIsIntV(n: String): Boolean
  /** Top-level global `n` is bound to a `FunV`. */
  def globalIsFunV(n: String): Boolean
  /** True iff argument `argIdx` of a call to `fnName` is passed to a ref-typed
   *  parameter. Backend-specific (resolves the callee's `MethodSig` from
   *  codegen state), so each `GenCtx` implements it over its own `callParamIsRef`. */
  def callArgIsRef(fnName: String, argIdx: Int): Boolean

/** Pure predicates shared between the structural bail classifier and each
 *  `JitBackend` implementation.  Keeping the logic in one place prevents the
 *  lint and the backends from silently diverging after a backend change.
 *
 *  Most functions are pure (no interpreter access, no side effects) and operate
 *  on scala.meta AST nodes only.  The shape predicates additionally consult a
 *  `JitShapeCtx` for name/global resolution, but stay backend-agnostic through
 *  that interface. */
object JitPredicates:
  /** True iff the top-level result type of `t` is Boolean (comparison or
   *  short-circuit logical). `JavacJitBackend` always emits `long`-returning
   *  static methods; a bool-typed body would be misrepresented as Int 0/1. */
  // Receiver methods that always return Boolean AND that the JIT compiles to a
  // 0/1 long (see AsmJitBackend / JavacJitBackend string/collection methods).
  // A function whose body is one of these must be tagged resultIsBool so the
  // 0/1 long is boxed back as BoolV, not IntV.
  private val boolMethods        = Set("startsWith", "endsWith", "contains")
  private val boolNullaryMethods = Set("isEmpty", "nonEmpty", "isDefined")

  def isBoolReturning(t: Term): Boolean = t match
    case _: Lit.Boolean => true
    case Term.ApplyInfix.After_4_6_0(_, op, _, _) =>
      val s = op.value
      s == "<" || s == "<=" || s == ">" || s == ">=" || s == "==" || s == "!=" || s == "&&" || s == "||"
    case Term.ApplyUnary(op, _) if op.value == "!" => true
    // Boolean-returning method calls — `s.startsWith(p)`, `xs.contains(x)`, …
    // Without this the JIT-compiled `def isHashed(s): Boolean = s.startsWith("…")`
    // returned IntV(1) instead of true across (and within) module boundaries.
    // (Repro: busi pwhash `isHashed`.)
    case ap: Term.Apply =>
      ap.fun match
        case Term.Select(_, m) => boolMethods.contains(m.value)
        case _                 => false
    case Term.Select(_, m) => boolNullaryMethods.contains(m.value)
    case ti: Term.If =>
      isBoolReturning(ti.thenp) || isBoolReturning(ti.elsep)
    case tb: Term.Block =>
      tb.stats.lastOption match
        case Some(last: Term) => isBoolReturning(last)
        case _                => false
    case tm: Term.Match =>
      // A match expression is bool-returning when *all* of its arms produce a
      // Boolean.  Required so that `def isDebit(p): Boolean = p.side match
      // { case Debit => true; case Credit => false }` is tagged
      // resultIsBool=true — otherwise the JIT-compiled fn returns IntV(1/0)
      // and callers comparing `== true` always see false.  (Repro: 6 calls
      // to `isDebit` in busi's accountBalance, all returning 0.)
      val cs = tm.casesBlock.cases
      cs.nonEmpty && cs.forall(c => isBoolReturning(c.body))
    case _ => false

  /** True iff `t` is a Long-shaped expression — one the numeric (`walkLong`)
   *  codegen path can compile.  Shared by both JIT backends so the same program
   *  classifies identically under `SSC_JIT_BACKEND=asm` and the default Javac
   *  backend.  The only per-backend variation (local-name resolution) is folded
   *  into `JitShapeCtx.isLocalLong`. */
  def looksLongValue(t: Term, ctx: JitShapeCtx): Boolean = t match
    case Lit.Int(_) | Lit.Long(_) | Lit.Boolean(_) => true
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => looksLongValue(inner, ctx)
        case _           => false
    case ti: Term.If =>
      looksLongValue(ti.thenp, ctx) && looksLongValue(ti.elsep, ctx)
    case tn: Term.Name =>
      !ctx.isRefName(tn.value) && !ctx.isStringName(tn.value) &&
        (ctx.isLocalLong(tn.value) || ctx.globalIsIntV(tn.value))
    case Term.ApplyUnary(op, arg) if op.value == "-" || op.value == "+" =>
      looksLongValue(arg, ctx)
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) if argClause.values.lengthCompare(1) == 0 =>
      op.value match
        case "+" | "-" | "*" | "/" | "%" | "<" | "<=" | ">" | ">=" | "==" | "!=" =>
          looksLongValue(lhs, ctx) && looksLongValue(argClause.values.head, ctx)
        case _ => false
    case Term.Select(_: Term, Term.Name("size" | "head" | "length")) =>
      true
    case Term.Select(inner: Term, Term.Name("toLong" | "toInt")) =>
      looksLongValue(inner, ctx)
    case Term.Apply.After_4_6_0(Term.Select(_: Term, Term.Name("getOrElse")), argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      looksLongValue(argClause.values.head, ctx)
    // Stage 8: 2-arg getOrElse (Map) returns Long when default is Long-ish.
    case Term.Apply.After_4_6_0(Term.Select(_: Term, Term.Name("getOrElse")), argClause)
        if argClause.values.lengthCompare(2) == 0 =>
      looksLongValue(argClause.values(1), ctx)
    // Stage 8: other ref-receiver methods that return Long.
    case Term.Apply.After_4_6_0(Term.Select(_: Term, Term.Name(m)), _)
        if (m == "size" || m == "head" || m == "last" || m == "isEmpty" ||
            m == "nonEmpty" || m == "isDefined" || m == "get" || m == "contains" ||
            m == "toInt" || m == "toLong" || m == "indexOf" ||
            m == "startsWith" || m == "endsWith" || m == "charAt") =>
      true
    // Stage 8: Math intrinsics return Long.
    case Term.Apply.After_4_6_0(Term.Select(Term.Name("Math"), Term.Name(m)), _)
        if m == "max" || m == "min" || m == "abs" =>
      true
    // Stage 9 lambda-value-solo: local val-bound lambda call is Long-shaped
    // when the lambda is registered; walkLong inlines it at the call site.
    case Term.Apply.After_4_6_0(Term.Name(name), _) if ctx.isLambda(name) =>
      true
    // Stage 8: global function call (Term.Apply on Term.Name) that resolves to
    // a Long-returning FunV. Keeps `.toInt`/`.toLong` on `combineAll(xs)` on the
    // walkLong path rather than dropping into ref-chain fallback.
    case Term.Apply.After_4_6_0(Term.Name(name), _) =>
      ctx.globalIsFunV(name)
    case _ => false

  /** True iff `t` may fall back to the object-ref codegen path.  Shared by both
   *  backends; mirrors `looksLongValue` so a getOrElse whose default is
   *  Long-shaped is *not* routed to the ref fallback (it stays numeric). */
  def objectRefFallbackAllowed(t: Term, ctx: JitShapeCtx): Boolean = t match
    case Term.Apply.After_4_6_0(Term.Select(_: Term, Term.Name("mkString")), argClause) =>
      val args = argClause.values
      args.isEmpty || args.lengthCompare(1) == 0 || args.lengthCompare(3) == 0
    case Term.Apply.After_4_6_0(Term.Select(_: Term, Term.Name("getOrElse")), argClause)
        if argClause.values.lengthCompare(2) == 0 =>
      true
    case Term.Apply.After_4_6_0(Term.Select(_: Term, Term.Name("getOrElse")), argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      !looksLongValue(argClause.values.head, ctx)
    case _ => false

  // ── Pure shape classifiers shared by both JIT backends ──────────────────────
  // Total functions of scala.meta AST (and at most Value.FunV): no MethodVisitor,
  // no GenCtx, no codegen state.  Single source so the backends cannot drift.

  /** True iff `recv` is a numeric-object constructor receiver (`BigInt(_)` or
   *  `Decimal(_)`/`Decimal(_, _)`) — i.e. a `Value`-boxed numeric, not a Long. */
  def isNumericObjectReceiver(recv: Term): Boolean =
    isNumericObjectValueShape(recv)

  def isNumericObjectValueShape(t: Term): Boolean = t match
    case Term.Apply.After_4_6_0(Term.Name("BigInt"), argClause) =>
      argClause.values.lengthCompare(1) == 0
    case Term.Apply.After_4_6_0(Term.Name("Decimal"), argClause) =>
      argClause.values.lengthCompare(1) == 0 || argClause.values.lengthCompare(2) == 0
    case _ => false

  /** If `t` is `inner.map(unary)` with a recognised arithmetic lambda, return the
   *  inner receiver and the map op so the caller can fuse the map into the next
   *  stage and skip the intermediate wrapper allocation. */
  def peelMapUnary(t: Term): (Term, JitHofShape.UnaryLong) | Null =
    t match
      case ap: Term.Apply =>
        ap.fun match
          case Term.Select(inner: Term, Term.Name("map")) if ap.argClause.values.lengthCompare(1) == 0 =>
            ap.argClause.values.head match
              case fn: Term.Function =>
                val u = JitHofShape.unaryLong(fn)
                if u == null then null else (inner, u)
              case _ => null
          case _ => null
      case _ => null

  /** True iff every arm of `tm` is a `Pat.Tuple`/`Pat.Wildcard`/`Pat.Var` and at
   *  least one is a tuple pattern (so the scrutinee is a `TupleV`). */
  def isTupleMatch(tm: Term.Match): Boolean =
    val cases = tm.casesBlock.cases
    cases.nonEmpty &&
    cases.exists(_.pat.isInstanceOf[Pat.Tuple]) &&
    cases.forall { c =>
      c.pat match
        case _: Pat.Tuple    => true
        case _: Pat.Wildcard => true
        case _: Pat.Var      => true
        case _               => false
    }

  /** If `t` is a direct self-recursive call to `funName`, return its argument
   *  list (used to drive the TCO loop emitter). */
  def asSelfRecur(t: Term, funName: String): Option[List[Term]] = t match
    case ap: Term.Apply =>
      ap.fun match
        case fn: Term.Name if fn.value == funName => Some(ap.argClause.values)
        case _                                    => None
    case _ => None

  /** True iff every arm of `tm` is guard-free and matches an int/long literal or
   *  a wildcard/var — so the scrutinee can stay a Long (no ADT boxing). */
  def isLiteralIntMatch(tm: Term.Match): Boolean =
    tm.casesBlock.cases.forall { c =>
      c.cond.isEmpty && (c.pat match
        case _: Pat.Wildcard | _: Pat.Var => true
        case _: Lit.Int | _: Lit.Long     => true
        case _                            => false
      )
    }

  /** Classify which of `f`'s value params must be treated as object refs (vs
   *  Long): function-typed params, ADT-match scrutinees, and `param.field`
   *  receivers.  Literal-int matches keep the scrutinee as Long. */
  def classifyParamRefs(f: Value.FunV): Array[Boolean] =
    val paramIsRef = new Array[Boolean](f.params.length)
    // Function-typed params (FunV HOF callbacks) are always refs.
    f.paramTypes.zipWithIndex.foreach { case (pt, i) =>
      if pt.contains("=>") then paramIsRef(i) = true
    }
    def markRefScrutinees(t: scala.meta.Tree): Unit = t match
      case tm: Term.Match =>
        tm.expr match
          case n: Term.Name =>
            val idx = f.params.indexOf(n.value)
            // Literal-int matches keep the scrutinee as Long; only ADT matches need ref.
            if idx >= 0 && !isLiteralIntMatch(tm) then paramIsRef(idx) = true
          case _ =>
        markRefScrutinees(tm.expr)
        tm.casesBlock.cases.foreach(c => markRefScrutinees(c.body))
      // `param.field` access ⇒ param is an InstanceV ref, not a Long.
      case Term.Select(n: Term.Name, _) =>
        val idx = f.params.indexOf(n.value)
        if idx >= 0 then paramIsRef(idx) = true
      case _ => t.children.foreach(markRefScrutinees)
    markRefScrutinees(f.body)
    paramIsRef

  /** True iff `bindingName` is, anywhere in `armBody`, passed as a ref-typed
   *  argument to some call — meaning the pattern binding must be kept as an
   *  object ref rather than unboxed to a Long. The ref-ness of each call
   *  argument is resolved per-backend through `ctx.callArgIsRef` (which consults
   *  the callee's `MethodSig` in codegen state). */
  def bindingIsRef(armBody: Term, bindingName: String, ctx: JitShapeCtx): Boolean =
    var hit = false
    def walk(t: scala.meta.Tree): Unit =
      if hit then ()
      else t match
        case Term.Apply.After_4_6_0(Term.Name(fnName), argClause) =>
          var idx = 0
          argClause.values.foreach { arg =>
            arg match
              case Term.Name(n) if n == bindingName && ctx.callArgIsRef(fnName, idx) =>
                hit = true
              case other =>
                walk(other)
            idx += 1
          }
        case _ => t.children.foreach(walk)
    walk(armBody)
    hit

  /** Walk `fn.body` and the param/return metadata and collect every visible
   *  structural bail cliff.  Mirrors `JavacJitBackend.doCompile`'s early-bail
   *  predicates; backends may call this then append their own specifics.
   *  Returns Nil when no obvious cliff is found — caller reports UnknownShape. */
  def classifyBailReasons(fn: Value.FunV): List[JitBailReason] =
    val buf = scala.collection.mutable.ListBuffer.empty[JitBailReason]
    if fn.params.length > 3 then buf += JitBailReason.TooManyParams(fn.params.length)
    if fn.usingParams.nonEmpty then
      if hasTypeclassUsingDispatch(fn.body, fn.usingParams.map(_._1).toSet) then
        buf += JitBailReason.TypeclassUsingDispatch
      else
        buf += JitBailReason.UsingParams
    if fn.paramTypes.exists(_.endsWith("*")) then buf += JitBailReason.VarargParam
    if fn.returnsThrows then buf += JitBailReason.EffectReturn
    // BoolBody: only report when the body is bool-returning AND compilation still
    // failed (i.e. walkBool/walkFunctionBody could not handle the expression).
    // Most bool bodies now compile via the 0/1 fallback — this catches the rest.
    if isBoolReturning(fn.body) then buf += JitBailReason.BoolBody
    walkForBailCliffs(fn.body, fn.params.toSet, Set.empty, buf)
    buf.toList.distinct

  /** Recursive AST traversal: pushes a `JitBailReason` for each visible
   *  structural cliff.  `paramNames` is the owning function's parameter set,
   *  used to detect HOF calls (callee is a function-valued parameter).
   *  `localNames` tracks immutable local val names so direct local ref reads
   *  stay distinct from qualified module/global calls.
   *  Falls back to `Tree.children.foreach(…)` so every node is visited once. */
  def walkForBailCliffs(
    t:          Tree,
    paramNames: Set[String],
    localNames: Set[String],
    buf:        scala.collection.mutable.ListBuffer[JitBailReason]
  ): Unit =
    t match
      case b: Term.Block =>
        var locals = localNames
        b.stats.foreach {
          case v: Defn.Val =>
            walkForBailCliffs(v.rhs, paramNames, locals, buf)
            locals = locals ++ patNames(v.pats)
          case stat =>
            walkForBailCliffs(stat, paramNames, locals, buf)
        }
      case Term.Try.After_4_9_9(tryExpr, handlerOpt, finallyOpt) =>
        // Stage 5.3: allow try/catch with no finally and simple catch patterns.
        val cases = handlerOpt.toList.flatMap(_.cases)
        val simpleArm = cases.nonEmpty && cases.forall { c =>
          c.pat match
            case _: Pat.Wildcard | _: Pat.Var => true
            case _: Pat.Typed                 => true
            case _                            => false
        }
        if finallyOpt.nonEmpty || !simpleArm then buf += JitBailReason.TryCatch
        else
          walkForBailCliffs(tryExpr, paramNames, localNames, buf)
          var rem = cases
          while rem.nonEmpty do
            walkForBailCliffs(rem.head.body, paramNames, localNames, buf)
            rem = rem.tail
      case Term.Interpolate(_, _, _) =>
        buf += JitBailReason.InterpolatedString
        t.children.foreach(walkForBailCliffs(_, paramNames, localNames, buf))
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        if isRefLikeInfix(op.value, lhs, argClause.values.head) then
          buf += JitBailReason.ApplyInfixRefOp
        t.children.foreach(walkForBailCliffs(_, paramNames, localNames, buf))
      case _: Term.For | _: Term.ForYield =>
        buf += JitBailReason.ForComprehension
        t.children.foreach(walkForBailCliffs(_, paramNames, localNames, buf))
      case _: Term.New =>
        buf += JitBailReason.ObjectConstruction
        t.children.foreach(walkForBailCliffs(_, paramNames, localNames, buf))
      case _: Term.NewAnonymous =>
        buf += JitBailReason.NewAnonymousClass
        t.children.foreach(walkForBailCliffs(_, paramNames, localNames, buf))
      case _: Term.Throw =>
        buf += JitBailReason.ThrowExpression
        t.children.foreach(walkForBailCliffs(_, paramNames, localNames, buf))
      case _: Term.Return =>
        buf += JitBailReason.ExplicitReturn
        t.children.foreach(walkForBailCliffs(_, paramNames, localNames, buf))
      case _: Term.Tuple =>
        buf += JitBailReason.TupleConstruction
        t.children.foreach(walkForBailCliffs(_, paramNames, localNames, buf))
      case _: Term.Eta =>
        buf += JitBailReason.EtaExpansion
      case _: Term.ApplyType =>
        buf += JitBailReason.TypeApplicationCall
        t.children.foreach(walkForBailCliffs(_, paramNames, localNames, buf))
      case ap: Term.Apply =>
        ap.fun match
          case tn: Term.Name if paramNames.contains(tn.value) =>
            // Calling a parameter as a function: HOF pattern not yet supported.
            buf += JitBailReason.HofCall(tn.value)
          case tn: Term.Name
              if !localNames.contains(tn.value) && !isKnownDirectJitCallee(tn.value) =>
            buf += JitBailReason.DirectGlobalOrCtorCall
            t.children.foreach(walkForBailCliffs(_, paramNames, localNames, buf))
          case Term.Select(Term.Name(n), _) if paramNames.contains(n) =>
            // Method call directly on a param (e.g. `s.length`): handled by GETFI.
            t.children.foreach(walkForBailCliffs(_, paramNames, localNames, buf))
          case Term.Select(recv, method) =>
            // Method call on a non-param expression (ref chain, qualified call,
            // or HOF method). Keep the low-risk primitive ref-read bucket
            // separate from object/string/generic chains.
            val hasLambda = ap.argClause.values.exists(_.isInstanceOf[Term.Function])
            buf += (if hasLambda then JitBailReason.HofMethodCall
                    else classifyRefSelectCall(recv, method.value, ap.argClause.values, localNames))
            t.children.foreach(walkForBailCliffs(_, paramNames, localNames, buf))
          case _ =>
            if !ap.fun.isInstanceOf[Term.Name] && !ap.fun.isInstanceOf[Term.Select] then
              buf += JitBailReason.HigherOrderApplyShape
            t.children.foreach(walkForBailCliffs(_, paramNames, localNames, buf))
      case _: Term.Function =>
        buf += JitBailReason.LambdaValue
      case tm: Term.Match =>
        tm.expr match
          case _: Term.Name   => ()
          case _: Term.Select => ()  // Stage 5.5: hoisted to local at compile time
          case _              => buf += JitBailReason.NonAdtScrutinee
        tm.casesBlock.cases.foreach { c =>
          if c.cond.nonEmpty then buf += JitBailReason.PatternGuard
          c.pat match
            case _: Pat.Extract  => ()
            case _: Pat.Var      => ()
            case _: Pat.Wildcard => ()
            case _: Lit.Int | _: Lit.Long => ()  // literal-int arms now compile
            case t: Pat.Tuple =>
              // Stage 8: separate nested-tuple from generic non-extract.
              t.args.foreach {
                case _: Pat.Var | _: Pat.Wildcard => ()
                case _: Pat.Tuple                 => buf += JitBailReason.NestedTuplePattern
                case _                            => buf += JitBailReason.NonExtractPattern
              }
            case alt: Pat.Alternative =>
              // Only no-binding alternatives are compilable (Stage 5.4).
              def hasBindings(p: scala.meta.Pat): Boolean = p match
                case e: Pat.Extract => e.argClause.values.exists(!_.isInstanceOf[Pat.Wildcard])
                case _: Pat.Var => true
                case _ => false
              if hasBindings(alt.lhs) || hasBindings(alt.rhs) then buf += JitBailReason.AlternativeWithBindings
            case Pat.Typed(inner, scala.meta.Type.Name(_)) =>
              // Stage 8: `case _: T =>` / `case x: T =>` compile via walkArm.
              // Only flag if the inner pattern is more complex than Var/Wildcard.
              inner match
                case _: Pat.Var | _: Pat.Wildcard => ()
                case _                            => buf += JitBailReason.TypedPattern
            case _: Pat.Typed => buf += JitBailReason.TypedPattern
            case _ => buf += JitBailReason.NonExtractPattern
          walkForBailCliffs(c.body, paramNames, localNames, buf)
        }
      case _ => t.children.foreach(walkForBailCliffs(_, paramNames, localNames, buf))

  /** Backwards-compat overload: no param-name set (HOF detection disabled). */
  def walkForBailCliffs(t: Tree, buf: scala.collection.mutable.ListBuffer[JitBailReason]): Unit =
    walkForBailCliffs(t, Set.empty, Set.empty, buf)

  private def hasTypeclassUsingDispatch(t: Tree, usingNames: Set[String]): Boolean =
    t match
      case Term.ApplyType.After_4_6_0(Term.Name("summon"), _) => true
      case Term.Select(Term.Name(n), _) if usingNames.contains(n) => true
      case _ => t.children.exists(hasTypeclassUsingDispatch(_, usingNames))

  private def classifyRefSelectCall(
    recv:       Term,
    method:     String,
    args:       List[Term],
    localNames: Set[String]
  ): JitBailReason =
    recv match
      case Term.Name(n) if localNames.contains(n) =>
        if isPrimitiveRefRead(method, args) then JitBailReason.RefChainCall
        else JitBailReason.RefChainObjectCall
      case Term.Name(_) =>
        JitBailReason.QualifiedRefCall
      case Term.Apply.After_4_6_0(Term.Name(ctor), _) if isNumericObjectConstructor(ctor) =>
        JitBailReason.NumericObjectMethodCall
      case a: Term.Apply if a.fun.isInstanceOf[Term.Name] =>
        if isPrimitiveRefRead(method, args) then JitBailReason.RefChainCall
        else JitBailReason.RefChainObjectCall
      case _ =>
        if isPrimitiveRefRead(method, args) then JitBailReason.RefChainCall
        else JitBailReason.RefChainObjectCall

  private def isNumericObjectConstructor(name: String): Boolean =
    name == "BigInt" || name == "Decimal"

  private def isKnownDirectJitCallee(name: String): Boolean =
    name == "Some" || name == "Right" || name == "Left" ||
    name == "List" || name == "Set" || name == "Map" ||  // Stage 8: builtin ctors
    name == "BigInt" || name == "Decimal"  // numeric ctors (stage-7.7)

  private def isRefLikeInfix(op: String, lhs: Term, rhs: Term): Boolean =
    op match
      case "+" | "-" | "*" | "/" | "%" | "<" | "<=" | ">" | ">=" | "==" | "!=" | "&&" | "||" =>
        !isLongishExpr(lhs) || !isLongishExpr(rhs)
      case _ => true

  private def isPrimitiveRefRead(method: String, args: List[Term]): Boolean =
    method match
      // Stage 8 refchain-residual: 0-arg primitive-read accessors compile via
      // JitRefDispatch; classify these as RefChainCall, not RefChainObjectCall.
      case "size" | "head" | "last" | "length"
         | "isEmpty" | "nonEmpty" | "isDefined" =>
        args.isEmpty
      // 1-arg primitive predicates / lookups also dispatch through JitRefDispatch.
      case "contains" | "startsWith" | "endsWith" | "indexOf" =>
        args.lengthCompare(1) == 0
      case "charAt" =>
        args.lengthCompare(1) == 0
      // String/Option/List producing chains that already have JitRefDispatch
      // helpers — staying RefChainCall lets the residual bucket reflect only
      // genuinely unsupported shapes.
      case "tail" | "init" | "headOption" | "lastOption"
         | "toString" | "toLowerCase" | "toUpperCase" | "trim" =>
        args.isEmpty
      // mkString has three overloads (`()`, `(sep)`, `(start, sep, end)`),
      // all dispatched via JitRefDispatch.mkStringRef.
      case "mkString" =>
        args.isEmpty || args.lengthCompare(1) == 0 || args.lengthCompare(3) == 0
      case "split" | "replace" | "substring" =>
        args.lengthCompare(1) == 0 || args.lengthCompare(2) == 0
      case "getOrElse" =>
        // 1-arg getOrElse with Long default (Option) and 2-arg Map.getOrElse.
        (args.lengthCompare(1) == 0 && isLongishExpr(args.head)) ||
          args.lengthCompare(2) == 0
      case _ => false

  private def isLongishExpr(t: Term): Boolean =
    t match
      case _: Lit.Int | _: Lit.Long => true
      case _: Term.Name             => true
      case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
          if argClause.values.lengthCompare(1) == 0 =>
        op.value match
          case "+" | "-" | "*" | "/" | "%" =>
            isLongishExpr(lhs) && isLongishExpr(argClause.values.head)
          case _ => false
      case _ => false

  private def patNames(pats: List[Pat]): Set[String] =
    pats.flatMap(patNames).toSet

  private def patNames(p: Pat): Set[String] =
    p match
      case Pat.Var(name)      => Set(name.value)
      case Pat.Tuple(args)    => args.flatMap(patNames).toSet
      case Pat.Typed(lhs, _)  => patNames(lhs)
      case e: Pat.Extract =>
        e.argClause.values.flatMap(patNames).toSet
      case _ => Set.empty

  /** Classify why `tryCompileWhileLong` would fail for the given condition and
   *  body terms.  Returns Nil when no structural reason is detectable (the
   *  caller will report `UnknownShape`).
   *
   *  Two coarse structural reasons mirror the two bail points in
   *  `tryCompileWhileLong`:
   *  - `WhileCondShape` — condition is not a supported comparison/boolean form
   *  - `WhileBodyShape` — at least one body assignment has an unsupported RHS
   *
   *  These are surface-level checks only; the full walk is in
   *  `JavacJitBackend.walkLocalBoolCtx` / `walkLocalSlotCtx`.  A while loop
   *  that passes these checks may still fail at compile time for deeper
   *  reasons (e.g. a callee that doesn't resolve). */
  def classifyWhileBailReasons(cond: Term, body: Term): List[JitBailReason] =
    val buf = scala.collection.mutable.ListBuffer.empty[JitBailReason]
    // Check condition shape: must be a comparison/boolean infix, optionally
    // wrapped in a 1-statement block.
    if !isWhileCondSupported(cond) then buf += JitBailReason.WhileCondShape
    // Check body shape: collect all assignments; for each one the RHS must
    // look like an arithmetic expression over names and literals.
    if !allBodyAssignsSupported(body) then buf += JitBailReason.WhileBodyShape
    buf.toList

  private def isWhileCondSupported(t: Term): Boolean = t match
    case b: Term.Block if b.stats.lengthCompare(1) == 0 =>
      b.stats.head match
        case inner: Term => isWhileCondSupported(inner)
        case _           => false
    case Term.ApplyInfix.After_4_6_0(_, op, _, argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      op.value match
        case "<" | "<=" | ">" | ">=" | "==" | "!=" => true
        case "&&" | "||" =>
          isWhileCondSupported(argClause.values.head)  // check at least one branch
        case _ => false
    case _ => false

  private def allBodyAssignsSupported(body: Term): Boolean =
    // Extract all Assign nodes from the body.
    val assigns = scala.collection.mutable.ListBuffer.empty[Term.Assign]
    collectAssigns(body, assigns)
    assigns.nonEmpty && assigns.forall(a => isSlotExpr(a.rhs))

  private def collectAssigns(t: Term, out: scala.collection.mutable.ListBuffer[Term.Assign]): Unit =
    t match
      case a: Term.Assign => out += a
      case b: Term.Block  => b.stats.foreach {
        case inner: Term => collectAssigns(inner, out)
        case _           => ()
      }
      case _ => ()

  /** True if `t` is an arithmetic expression over names and integer literals —
   *  the kind of RHS that `walkLocalSlotCtx` can translate. */
  private def isSlotExpr(t: Term): Boolean = t match
    case _: Term.Name     => true
    case _: Lit.Int       => true
    case _: Lit.Long      => true
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause)
        if argClause.values.lengthCompare(1) == 0 =>
      op.value match
        case "+" | "-" | "*" | "/" | "%" | "&" | "|" | "^" | "<<" | ">>" | ">>>" =>
          isSlotExpr(lhs) && isSlotExpr(argClause.values.head)
        case _ => false
    case Term.ApplyUnary(op, arg) if op.value == "-" || op.value == "~" =>
      isSlotExpr(arg)
    case _: Term.Block if t.asInstanceOf[Term.Block].stats.lengthCompare(1) == 0 =>
      t.asInstanceOf[Term.Block].stats.head match
        case inner: Term => isSlotExpr(inner)
        case _           => false
    case _ => false

/** Analyser that walks the interpreter's globals after a module is loaded
 *  and reports JIT-compilability for every Defn.Def.
 *
 *  `lintFun` and `lintInterpreter` accept an explicit `backend` parameter
 *  (defaulting to `JitBackend.default`) so callers can target Javac, ASM, or
 *  any future backend without changing the env var.  `lintInterpreterCompare`
 *  runs both Javac and ASM in a single pass and returns a side-by-side diff.
 *
 *  Ground truth is always `backend.tryCompile` — the live compile cache —
 *  so the lint and the runtime can never diverge silently.  When compilation
 *  fails the structural reason is derived from `backend.classifyBailReasons`,
 *  which each backend may specialise beyond the shared `JitPredicates` base.
 *
 *  While-loop coverage (`lintWhileLoops`, `lintWhileLoopsCompare`) works by
 *  iterating `interp.whileJitCache`, which is populated during `runSections`
 *  as top-level while loops execute.  The cache stores a `WhileJitEntry` on
 *  success or the `WhileJitMiss` sentinel on failure; the lint reads these
 *  results directly without re-triggering compilation. */
object JitLint:

  /** Lint every top-level `def` in the interpreter's globals against
   *  `backend` (default: `JitBackend.default`).  Returns one report per
   *  FunV, sorted by name. */
  def lintInterpreter(
    interp:  Interpreter,
    backend: JitBackend = JitBackend.default
  ): List[JitLintReport] =
    interp.globals.iterator
      .collect { case (name, fn: Value.FunV) => (name, fn) }
      .toList
      .sortBy(_._1)
      .map { case (name, fn) => lintFun(name, fn, interp, backend) }

  /** Lint a single FunV against `backend` (default: `JitBackend.default`).
   *  Invoked from `lintInterpreter` or directly. */
  def lintFun(
    name:    String,
    fn:      Value.FunV,
    interp:  Interpreter,
    backend: JitBackend = JitBackend.default
  ): JitLintReport =
    val line = posLineOf(fn.body)
    if backend.tryCompile(fn, interp) != null then
      JitLintReport(name, line, Nil)
    else
      val reasons = backend.classifyBailReasons(fn)
      JitLintReport(name, line, if reasons.nonEmpty then reasons else List(JitBailReason.UnknownShape))

  /** Run both `JavacJitBackend` and `AsmJitBackend` on every top-level def
   *  and return a side-by-side comparison.  Functions that compile on Javac
   *  but not ASM (or vice-versa) are immediately visible without a benchmark
   *  run. */
  def lintInterpreterCompare(interp: Interpreter): List[JitLintCompareReport] =
    interp.globals.iterator
      .collect { case (name, fn: Value.FunV) => (name, fn) }
      .toList
      .sortBy(_._1)
      .map { case (name, fn) =>
        val line   = posLineOf(fn.body)
        val javacR = lintFun(name, fn, interp, JavacJitBackend)
        val asmR   = lintFun(name, fn, interp, AsmJitBackend)
        JitLintCompareReport(name, line, javacR, asmR)
      }

  /** Lint every top-level while loop that was executed during `runSections`
   *  against `backend`.  The source of truth is `interp.whileJitCache`, which
   *  is an `IdentityHashMap[Term.While, AnyRef]` populated by `EvalRuntime`
   *  as loops execute:
   *  - `WhileJitEntry`  — loop was compiled successfully
   *  - `WhileJitMiss`   — compilation was attempted and failed
   *
   *  For missed compilations, `JitPredicates.classifyWhileBailReasons` provides
   *  a surface-level structural reason (condition or body shape mismatch).
   *
   *  Loops are sorted by condition line number (ascending) to produce a stable,
   *  readable report regardless of HashMap iteration order.
   *
   *  Note: loops that were never executed (e.g. inside an `if false` branch)
   *  are not visible here — they will not appear in the cache and cannot be
   *  linted without a separate static parse pass. */
  def lintWhileLoops(
    interp:  Interpreter,
    backend: JitBackend = JitBackend.default
  ): List[JitLintWhileReport] =
    val buf = scala.collection.mutable.ListBuffer.empty[(Option[Int], JitLintWhileReport)]
    val it  = interp.whileJitCache.entrySet().iterator()
    while it.hasNext do
      val e        = it.next()
      val whileT   = e.getKey
      val condLine = posLineOf(whileT.expr)
      // e.getValue is either a WhileJitEntry (success) or the WhileJitMiss
      // sentinel (an opaque AnyRef, not a WhileJitEntry).  We probe using
      // tryCompileWhileLong so the same ground-truth cache applies regardless
      // of which backend we're linting against.
      val report = lintWhileSingle(whileT, interp, backend)
      buf += ((condLine, report))
    buf.toList.sortBy(_._1.getOrElse(Int.MaxValue)).map(_._2)

  /** Lint every top-level while loop against both `JavacJitBackend` and
   *  `AsmJitBackend`, returning a side-by-side comparison.  Loops that JIT
   *  on Javac but not ASM (or vice-versa) are flagged explicitly — useful
   *  for surfacing ASM-backend regressions before they show up at bench time. */
  def lintWhileLoopsCompare(interp: Interpreter): List[JitLintWhileCompareReport] =
    val buf = scala.collection.mutable.ListBuffer.empty[(Option[Int], JitLintWhileCompareReport)]
    val it  = interp.whileJitCache.entrySet().iterator()
    while it.hasNext do
      val entry   = it.next()
      val whileT  = entry.getKey
      val condLine = posLineOf(whileT.expr)
      val bodyLine = posLineOf(whileT.body)
      val javacR  = lintWhileSingle(whileT, interp, JavacJitBackend)
      val asmR    = lintWhileSingle(whileT, interp, AsmJitBackend)
      buf += ((condLine, JitLintWhileCompareReport(condLine, bodyLine, javacR, asmR)))
    buf.toList.sortBy(_._1.getOrElse(Int.MaxValue)).map(_._2)

  private def lintWhileSingle(
    whileT:  scala.meta.Term.While,
    interp:  Interpreter,
    backend: JitBackend
  ): JitLintWhileReport =
    val condLine = posLineOf(whileT.expr)
    val bodyLine = posLineOf(whileT.body)
    val assigns  = extractBodyAssigns(whileT.body)
    val entry    = backend.tryCompileWhileLong(whileT.expr, assigns._1, assigns._2, interp)
    if entry != null then
      JitLintWhileReport(condLine, bodyLine, Nil)
    else
      val reasons = JitPredicates.classifyWhileBailReasons(whileT.expr, whileT.body)
      JitLintWhileReport(condLine, bodyLine, if reasons.nonEmpty then reasons else List(JitBailReason.UnknownShape))

  /** Extract `(names, rhsTerms)` from a while body for use as the `names` and
   *  `rhs` arguments to `tryCompileWhileLong`.
   *
   *  In a compilable while body, top-level statements are `Term.Assign` nodes
   *  whose LHS is a `Term.Name`.  We collect those; non-assign statements
   *  produce an empty arrays pair, which will cause `tryCompileWhileLong` to
   *  return null (same as a structural bail). */
  private def extractBodyAssigns(body: scala.meta.Term): (Array[String], Array[scala.meta.Term]) =
    val assignments = scala.collection.mutable.ListBuffer.empty[(String, scala.meta.Term)]
    body match
      case b: scala.meta.Term.Block =>
        b.stats.foreach {
          case Term.Assign(Term.Name(n), rhs) => assignments += ((n, rhs))
          case _                              => ()
        }
      case Term.Assign(Term.Name(n), rhs) =>
        assignments += ((n, rhs))
      case _ => ()
    (assignments.map(_._1).toArray, assignments.map(_._2).toArray)

  private[jit] def posLineOf(t: scala.meta.Tree): Option[Int] =
    val p = t.pos
    if p == scala.meta.inputs.Position.None then None
    else Some(p.startLine + 1)
