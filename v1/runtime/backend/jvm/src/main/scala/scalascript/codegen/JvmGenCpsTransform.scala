package scalascript.codegen

import scala.meta.*

/** CPS transform: converts direct-style ssc code into monadic-style Scala that
 *  builds a Free tree at runtime. Pure sub-expressions stay as-is; function
 *  calls and effect ops are threaded through `_bind`. Lifted out of JvmGen to
 *  keep the generator navigable; self-typed because the transform reads/writes
 *  generator state (`tmpIdx`, `anyBoundNames`) and calls back into core
 *  emission (`emitExpr`, `emitReceiveMatcher`, type/cast helpers). */
private[codegen] trait JvmGenCpsTransform:
  self: JvmGen =>

  //
  // The CPS transform converts direct-style ssc code to monadic-style Scala
  // that builds a Free tree at runtime.  Pure sub-expressions stay as-is;
  // function calls and effect ops are threaded through `_bind`.

  /** Try to evaluate a binary infix expression at compile time.
   *  Returns Some(scala) when both operands are literals and the op is foldable.
   *  The returned string is valid Scala 3 source for the folded literal value.
   */
  private[codegen] def foldConstantScala(lhs: Term, op: String, rhs: Term): Option[String] =
    def escStr(s: String): String =
      "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t") + "\""
    (lhs, rhs) match
      case (Lit.Int(a), Lit.Int(b)) => op match
        case "+"  => Some((a + b).toString)
        case "-"  => Some((a - b).toString)
        case "*"  => Some((a * b).toString)
        case "/"  if b != 0 => Some((a / b).toString)
        case "%"  if b != 0 => Some((a % b).toString)
        case "<"  => Some((a < b).toString)
        case ">"  => Some((a > b).toString)
        case "<=" => Some((a <= b).toString)
        case ">=" => Some((a >= b).toString)
        case "==" => Some((a == b).toString)
        case "!=" => Some((a != b).toString)
        case _    => None
      case (Lit.Long(a), Lit.Long(b)) => op match
        case "+"  => Some(s"${a + b}L")
        case "-"  => Some(s"${a - b}L")
        case "*"  => Some(s"${a * b}L")
        case "/"  if b != 0 => Some(s"${a / b}L")
        case "%"  if b != 0 => Some(s"${a % b}L")
        case "<"  => Some((a < b).toString)
        case ">"  => Some((a > b).toString)
        case "<=" => Some((a <= b).toString)
        case ">=" => Some((a >= b).toString)
        case "==" => Some((a == b).toString)
        case "!=" => Some((a != b).toString)
        case _    => None
      case (Lit.Double(as), Lit.Double(bs)) =>
        // Lit.Double.value is a String in scalameta 4.x
        val a = as.toDouble; val b = bs.toDouble
        op match
          case "+"  => Some((a + b).toString)
          case "-"  => Some((a - b).toString)
          case "*"  => Some((a * b).toString)
          case "/"  => Some((a / b).toString)
          case "<"  => Some((a < b).toString)
          case ">"  => Some((a > b).toString)
          case "<=" => Some((a <= b).toString)
          case ">=" => Some((a >= b).toString)
          case "==" => Some((a == b).toString)
          case "!=" => Some((a != b).toString)
          case _    => None
      case (Lit.Boolean(a), Lit.Boolean(b)) => op match
        case "&&" => Some((a && b).toString)
        case "||" => Some((a || b).toString)
        case "==" => Some((a == b).toString)
        case "!=" => Some((a != b).toString)
        case _    => None
      case (Lit.String(a), Lit.String(b)) if op == "+" =>
        Some(escStr(a + b))
      case _ => None

  private def isSimpleCps(t: Term): Boolean = t match
    case _: Lit                                  => true
    case Term.Name(n) if !isEffectfulFun(n)      => true
    // Varargs spread `xs: _*` is a syntactic call form — can't be bound
    // as a value.  Pass through verbatim so `f(xs: _*)` stays as
    // `f(xs: _*)` not `_bind(xs: _*, …)`.
    case _: Term.Repeated                        => true
    // Function literals / partial functions / placeholder lambdas
    // (`_._1`, `_ + 1`) are pure values, never effectful.
    case _: Term.Function                        => true
    case _: Term.AnonymousFunction               => true
    case _: Term.PartialFunction                 => true
    // Named-arg assignments inside a call: `f(name = expr)`.  The CPS
    // emit should treat the assignment shape as syntactic — bind only
    // the value side if needed (handled separately when the time comes).
    case _: Term.Assign                          => true
    case _                                       => false

  /** Bind a list of CPS sub-expressions; pass their values into `k`. */
  private def bindArgsCps(args: List[Term])(k: List[String] => String): String =
    def emitSimple(t: Term): String = t match
      // A bare `{ case … }` lands in Any-typed positions (e.g. inside
      // `_dispatch(qual, "map", List({case …}))`).  Scala 3 can't infer
      // `x$1`'s type for the auto-expanded `x$1 => x$1 match { … }`
      // when the expected type is `Any`, so wrap it ourselves with an
      // explicit `Any` parameter.  The destructuring inside still works
      // (`case (a, b) => …` binds against the runtime tuple).
      //
      // Chunk 4 — case bodies are CPS-emitted (mirroring the Term.Match
      // arm in emitCpsExpr).  Without this, an effectful expression
      // inside a case body — `pid ! msg`, `receive`, `nested.method` —
      // stays as raw syntax and skips the CPS rewrites it needs.  Side
      // effects on pure case bodies are nil: `case x => f(x)` re-emits
      // as `case x => f(x)` (`f(x)` is `emitCpsApply` whose `case fun`
      // arm produces `f(${vs})` when there's nothing to bind).
      case pf: Term.PartialFunction =>
        val tmp   = freshTmp()
        val cases = pf.cases.map { c =>
          val guard = c.cond.map(g => s" if ${g.syntax}").getOrElse("")
          // Pattern-bound names (`case (k, vs) => …`, `case Some(info)
          // => …`) come from destructuring an `Any` value, so they're
          // also Any-typed.  Register them so the case body's
          // `.method` accesses (`vs.sorted`, `info.links`) route via
          // _dispatch instead of failing on `value sorted is not a
          // member of Any`.
          val boundNames = c.pat.collect { case scala.meta.Pat.Var(n) => n.value }.toSet
          val body = withAnyBoundNames(boundNames)(emitCpsExpr(c.body))
          s"case ${c.pat.syntax}${guard} => $body"
        }.mkString(" ")
        s"((${tmp}: Any) => ${tmp} match { $cases })"
      // Chunk 6 — Term.Function mirroring the PF treatment above.  A
      // bare `pid => pid ! msg` lands in an Any-typed position (e.g.
      // `_dispatch(pids, "foreach", List(pid => …))`) and Scala 3
      // can't infer `pid`'s type from the expected `Any`.  Widen
      // each param to its declared type (or `Any` when un-annotated)
      // and CPS-emit the body so an effectful expression inside the
      // body (`pid ! msg`) reaches the existing `Term.ApplyInfix("!")`
      // arm and becomes `Actor.send(pid, msg)`.  Pure bodies re-emit
      // unchanged because emitCpsApply's `case fun` arm produces
      // `f(${vs})` when there's nothing to bind — same as the PF
      // case-body argument above.
      case Term.Function.After_4_6_0(paramClause, body) =>
        val params = paramClause.values.map { p =>
          p.decltpe.map(t => s"${p.name.value}: ${t.syntax}")
            .getOrElse(s"${p.name.value}: Any")
        }.mkString(", ")
        // Widened (no-decltpe) params land as `Any` — register so the
        // Term.Select arm can _dispatch their `.member` accesses.
        val anyParams = paramClause.values.filter(_.decltpe.isEmpty).map(_.name.value).toSet
        s"(($params) => ${withAnyBoundNames(anyParams)(emitCpsExpr(body))})"
      // `_._1`, `_._2 == deadPid`, `_ + 1` — Scala can't infer the
      // expanded `x$N` parameter type when the placeholder shorthand
      // lands in an `Any`-typed slot.  Rewrite by string-substituting
      // the bare `_` for a fresh name, re-parsing, and CPS-emitting
      // through the registered-Any path so accesses like `_._1`
      // become `_dispatch(_t, "_1", Nil)` at runtime.
      case af: Term.AnonymousFunction =>
        import scala.meta.{dialects, *}
        val tmp      = freshTmp()
        val bodySrc  = af.body.syntax
        // Word-boundary regex: replace standalone `_` only, leaving
        // `_x`, `xs._1`, `xs: _*`, etc. alone.
        val rewritten = bodySrc.replaceAll("""(?<![A-Za-z0-9_])_(?![A-Za-z0-9_])""", tmp)
        dialects.Scala3(Input.String(rewritten)).parse[Term] match
          case Parsed.Success(parsedBody) =>
            s"(($tmp: Any) => ${withAnyBoundNames(Set(tmp))(emitCpsExpr(parsedBody))})"
          case _ =>
            // Re-parse failed: fall back to raw syntax (preserves the
            // original failure mode rather than introducing a new one).
            af.syntax
      // Named-arg RHS in CPS context — emit through the CPS pipeline so
      // chained calls on Any-typed values get `_dispatch`/`_bind`.
      // Otherwise `pending = assignments.map(_._1).toSet` would land
      // raw and the `.map` on Any-typed assignments would fail.
      case Term.Assign(lhs, rhs) =>
        s"${lhs.syntax} = ${emitCpsExpr(rhs)}"
      case _ => t.syntax
    def loop(remaining: List[Term], acc: List[String]): String = remaining match
      case Nil       => k(acc.reverse)
      case t :: rest =>
        if isSimpleCps(t) then loop(rest, emitSimple(t) :: acc)
        else
          val v = freshTmp()
          s"_bind(${emitCpsExpr(t)}, ($v: Any) => ${loop(rest, v :: acc)})"
    loop(args, Nil)

  private var tmpIdx = 0
  private def freshTmp(): String = { tmpIdx += 1; s"_t$tmpIdx" }

  // Chunk 6 — names statically bound to `Any` in the enclosing
  // Term.Function lambda(s) of the current emitCpsExpr context.
  // The Term.Select arm consults this so `node.address` becomes
  // `_dispatch(node, "address", Nil)` when `node` came from an
  // `(node: Any) =>` widened param (otherwise `.address` on `Any`
  // wouldn't typecheck).  Push at function entry, restore at exit
  // — robust to shadowing because we snapshot the previous set.
  private val anyBoundNames = scala.collection.mutable.Set.empty[String]
  /** Run `body` with `names` added to `anyBoundNames`; restore on
   *  exit (snapshot/restore pattern handles shadowed re-binds). */
  private def withAnyBoundNames[A](names: Set[String])(body: => A): A =
    if names.isEmpty then body
    else
      val prev = anyBoundNames.toSet
      anyBoundNames ++= names
      try body
      finally
        anyBoundNames.clear()
        anyBoundNames ++= prev

  /** Emit a Scala expression in CPS form. */
  private[codegen] def emitCpsExpr(term: Term): String = term match
    case _: Lit       => term.syntax
    case Term.Name(_) => term.syntax

    case Term.Block(stats)            => emitCpsBlock(stats)
    case t: Term.If                   =>
      val tmp = freshTmp()
      val thenJs = emitCpsExpr(t.thenp)
      val elseJs = t.elsep match
        case Lit.Unit() => "()"
        case e          => emitCpsExpr(e)
      if isSimpleCps(t.cond) then s"(if ${t.cond.syntax} then ($thenJs) else ($elseJs))"
      else
        s"_bind(${emitCpsExpr(t.cond)}, ($tmp: Any) => (if ${tmp}.asInstanceOf[Boolean] then ($thenJs) else ($elseJs)))"

    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md" =>
      bindArgsCps(args.map(_.asInstanceOf[Term])) { vs =>
        val sb2 = StringBuilder()
        sb2.append(s"""$prefix"""")
        for i <- parts.indices do
          sb2.append(parts(i).asInstanceOf[Lit.String].value
            .replace("\\", "\\\\").replace("\"", "\\\""))
          if i < args.length then sb2.append("${").append(vs(i)).append("}")
        sb2.append("\"")
        sb2.toString
      }

    // Registered interpolator (InterpolatorRegistry) — jvmEmit takes precedence.
    // User-defined interpolator: StringContext("p1","p2").prefix(arg1, arg2)
    case Term.Interpolate(Term.Name(prefix), parts, args) =>
      bindArgsCps(args.map(_.asInstanceOf[Term])) { vs =>
        val partStrs = parts.map(_.asInstanceOf[Lit.String].value)
        scalascript.compiler.plugin.InterpolatorRegistry.lookup(prefix) match
          case Some(impl) => impl.jvmEmit(partStrs, vs.toList)
          case None =>
            val scParts = partStrs.map(s => "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
              .mkString(", ")
            val argsStr = vs.mkString(", ")
            s"StringContext($scParts).$prefix($argsStr)"
      }

    case Term.Tuple(elems) =>
      bindArgsCps(elems) { vs => s"(${vs.mkString(", ")})" }

    case Term.Assign(lhs, rhs) =>
      val v = freshTmp()
      val cast = assignmentCast(lhs)
      s"_bind(${emitCpsExpr(rhs)}, ($v: Any) => { ${lhs.syntax} = $v$cast; () })"

    // A `while` reachable in a CPS context (an effect performed in the loop body):
    // lower to a trampolined recursive helper so the body's `perform`s thread
    // through `_bind` (which is lazy on a `_Computation`, so `_run`/`_handle`
    // trampoline it without growing the JVM stack). See specs/effect-cps-loops.md.
    case w: Term.While => emitWhileTrampoline(w, "()")

    // A `for i <- (lo until/to hi) do body` reachable in a CPS context (effect performed in the body):
    // desugar the Range generator to an index `var` + the same while-trampoline, so the body's
    // `perform`s thread through `_bind`. Without this the `for-do` emits raw and `acc + Eff.op()`
    // (Int + Any `_perform`) fails to compile. (effect-perform-in-fordo.) Non-range / multi-generator
    // for-do falls through to the raw fallback (unchanged).
    case t: Term.For if rangeForDo(t).isDefined =>
      val (iName, lo, hi, inclusive) = rangeForDo(t).get
      val cmp     = if inclusive then "<=" else "<"
      val wn      = "_wf" + freshTmp()
      val cpsBody = emitCpsExpr(t.body)
      s"{ var $iName: Int = ${lo.syntax}; def $wn(): Any = if ($iName $cmp ${hi.syntax}) " +
        s"_bind($cpsBody, (_wk: Any) => { $iName = $iName + 1; $wn() }) else (); _bind($wn(), (_wr: Any) => ()) }"

    // A `for x <- coll do body` over a non-Range pure collection: iterate via `.iterator` + the same
    // while-trampoline so the body's `perform`s thread through `_bind`. (effect-perform-in-fordo,
    // collection case.) `coll` is required pure (no perform); complex generator patterns fall through.
    case t: Term.For if collForDo(t).isDefined =>
      val (iName, coll) = collForDo(t).get
      val itn     = "_it" + freshTmp()
      val wn      = "_wf" + freshTmp()
      val cpsBody = emitCpsExpr(t.body)
      s"{ val $itn = (${coll.syntax}).iterator; def $wn(): Any = if ($itn.hasNext) { val $iName = $itn.next(); " +
        s"_bind($cpsBody, (_wk: Any) => $wn()) } else (); _bind($wn(), (_wr: Any) => ()) }"

    case Term.Function.After_4_6_0(paramClause, body) =>
      val params = paramClause.values.map { p =>
        val tpe = p.decltpe.map(t => s": ${t.syntax}").getOrElse(": Any")
        s"${p.name.value}${tpe}"
      }
      // Always paren-wrap: a single-param lambda with a type ascription
      // (`n: Any => body`) would be parsed as `n` of type `Any => body`,
      // not as a one-parameter lambda.  Parens disambiguate.
      val wrap = s"(${params.mkString(", ")})"
      val anyParams = paramClause.values.filter(_.decltpe.isEmpty).map(_.name.value).toSet
      s"$wrap => ${withAnyBoundNames(anyParams)(emitCpsExpr(body))}"

    // Nested handle inside CPS body
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          emitHandleForm(bodyArgClause.values.head.asInstanceOf[Term], pf.cases)
        case _ => "??? /* invalid handle */"

    // Nested runAsync inside CPS body — drive the inner Free tree to a
    // plain value, then continue the outer continuation with it.
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsync(() => ${emitExpr(bodyArgClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsyncParallel(() => ${emitCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val bodyJs = emitCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      val pathJs = bodyArgClause.values.lift(1)
        .map(p => emitExpr(p.asInstanceOf[Term]))
        .getOrElse("null")
      s"_runStorage(() => $bodyJs, $pathJs)"
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runStorage(() => ${emitCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])}, null)"

    // ── v1.6 Actors Phase 1 (inside CPS body) ──────────────────────────
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runActors(() => ${emitCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // runToList() on runStream result inside CPS body — same cast as emitExpr variant.
    case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("runToList" | "toList")), argClause)
        if argClause.values.isEmpty =>
      s"${emitExpr(qual.asInstanceOf[Term])}.asInstanceOf[_Source].runToList()"

    // Standard algebraic-effect runners nested inside a CPS body.
    // Body uses emitExpr (not emitCpsExpr) — see the emitExpr variant above.
    case Term.Apply.After_4_6_0(
        Term.Name("runLogger" | "runLoggerJson" | "runLoggerToList" |
                  "runRandom" | "runClock" | "runHttp" | "runEnv" |
                  "runStream" | "runCache" | "runCacheBypass" |
                  "runTx" | "runRetry" | "runRetryNoSleep"),
        bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val fn   = term.asInstanceOf[Term.Apply].fun.syntax
      val body = emitExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$fn(() => $body)"
    case Term.Apply.After_4_6_0(
        Term.Apply.After_4_6_0(
          Term.Name("runRandomSeeded" | "runClockAt" | "runEnvWith" |
                    "runHttpStub" | "runState" | "runAuthWith"),
          extraArgClause),
        bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val outerApply = term.asInstanceOf[Term.Apply].fun.asInstanceOf[Term.Apply]
      val fn    = outerApply.fun.syntax
      val extra = extraArgClause.values.map(v => emitExpr(v.asInstanceOf[Term])).mkString(", ")
      val body  = emitExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$fn($extra)(() => $body)"

    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive" | "receiveWithTimeout"), timeoutArgClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutArgClause.values.size == 1 =>
      val timeoutTerm = timeoutArgClause.values.head match
        case Term.Assign(Term.Name("timeout"), v)   => v
        case Term.Assign(Term.Name("timeoutMs"), v) => v
        case other: Term                            => other
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcher = emitReceiveMatcher(pf.cases)
          s"Actor.receive_t(_registerReceive($matcher), ${emitExpr(timeoutTerm.asInstanceOf[Term])})"
        case _ => "??? /* invalid receive */"

    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcher = emitReceiveMatcher(pf.cases)
          s"Actor.receive_(_registerReceive($matcher))"
        case _ => "??? /* invalid receive */"

    case Term.Apply.After_4_6_0(Term.Name("spawn"), argClause)
        if argClause.values.size == 1 =>
      // emitCpsExpr on a `() => …` Function literal already emits
      // `() => <cps-body>` — exactly the `() => Any` thunk `Actor.spawn`
      // expects.  Wrapping in another `() =>` would double-thunk.
      s"Actor.spawn(${emitCpsExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("spawn_link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.spawn_link(${emitCpsExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("spawnBounded"), argClause)
        if argClause.values.size == 3 =>
      val capSc      = emitExpr(argClause.values(0).asInstanceOf[Term])
      val overflowSc = emitExpr(argClause.values(1).asInstanceOf[Term])
      val thunkSc    = emitCpsExpr(argClause.values(2).asInstanceOf[Term])
      s"Actor.spawnBounded($capSc, $overflowSc, $thunkSc)"
    case Term.Apply.After_4_6_0(Term.Name("self"), argClause)
        if argClause.values.isEmpty =>
      "Actor.self()"
    case Term.Apply.After_4_6_0(Term.Name("exit"), argClause)
        if argClause.values.size == 2 =>
      val pidJs    = emitExpr(argClause.values(0).asInstanceOf[Term])
      val reasonJs = emitExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.exit($pidJs, $reasonJs)"
    // v1.6 Phase 2 — supervision primitives (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.link(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("monitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.monitor(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("demonitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.demonitor(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("trapExit"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.trapExit(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6 Phase 3 — distributed node primitives (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("startNode"), argClause)
        if argClause.values.size >= 1 =>
      val nodeId = emitExpr(argClause.values(0).asInstanceOf[Term])
      val url    = if argClause.values.size >= 2 then emitExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.startNode($nodeId, $url)"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.connectNode(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.connectNode(${emitExpr(argClause.values(0).asInstanceOf[Term])}, ${emitExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("joinCluster"), argClause)
        if argClause.values.size >= 1 =>
      val seeds = emitExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then emitExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.joinCluster($seeds, $token)"
    case Term.Apply.After_4_6_0(Term.Name("register"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.register(${emitExpr(argClause.values(0).asInstanceOf[Term])}, ${emitExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("whereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.whereis(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalRegister"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.globalRegister(${emitExpr(argClause.values(0).asInstanceOf[Term])}, ${emitExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalWhereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.globalWhereis(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6.x — scheduled sends (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("sendAfter"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => emitExpr(v.asInstanceOf[Term]))
      s"Actor.sendAfter(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("sendInterval"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => emitExpr(v.asInstanceOf[Term]))
      s"Actor.sendInterval(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("cancelTimer"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.cancelTimer(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6.x — process introspection (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("processInfo"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.processInfo(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — cluster visibility
    case Term.Apply.After_4_6_0(Term.Name("clusterMembers"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMembers()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeClusterEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeClusterEvents()"
    // v1.23 — phi-accrual failure detector
    case Term.Apply.After_4_6_0(Term.Name("phiOf"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.phiOf(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isSuspect"), argClause)
        if argClause.values.size >= 1 =>
      val nid = emitExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then emitExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
      s"Actor.isSuspect($nid, $thr)"
    // v1.23 — local node identity + phi vector
    case Term.Apply.After_4_6_0(Term.Name("selfNode"), argClause)
        if argClause.values.isEmpty =>
      "Actor.selfNode()"
    case Term.Apply.After_4_6_0(Term.Name("clusterHealth"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterHealth()"
    // v1.23 — cluster-wide failure detector
    case Term.Apply.After_4_6_0(Term.Name("broadcastHealth"), argClause)
        if argClause.values.isEmpty =>
      "Actor.broadcastHealth()"
    case Term.Apply.After_4_6_0(Term.Name("clusterIsDown"), argClause)
        if argClause.values.size >= 1 =>
      val nid = emitExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then emitExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
      s"Actor.clusterIsDown($nid, $thr)"
    // v1.23 — leader election (Bully)
    case Term.Apply.After_4_6_0(Term.Name("electLeader"), argClause)
        if argClause.values.isEmpty =>
      "Actor.electLeader()"
    case Term.Apply.After_4_6_0(Term.Name("currentLeader"), argClause)
        if argClause.values.isEmpty =>
      "Actor.currentLeader()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeLeaderEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeLeaderEvents()"
    case Term.Apply.After_4_6_0(Term.Name("setAutoReelect"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setAutoReelect(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — protocol switch + history
    case Term.Apply.After_4_6_0(Term.Name("useRaftLeaderElection"), argClause)
        if argClause.values.isEmpty =>
      "Actor.useRaftLeaderElection()"
    case Term.Apply.After_4_6_0(Term.Name("useExternalCoordinator"), argClause)
        if argClause.values.size == 4 =>
      val vs = argClause.values.map(v => emitExpr(v.asInstanceOf[Term]))
      s"Actor.useExternalCoordinator(${vs(0)}, ${vs(1)}, ${vs(2)}, ${vs(3)})"
    case Term.Apply.After_4_6_0(Term.Name("leaderProtocol"), argClause)
        if argClause.values.isEmpty =>
      "Actor.leaderProtocol()"
    case Term.Apply.After_4_6_0(Term.Name("leaderHistory"), argClause)
        if argClause.values.isEmpty =>
      "Actor.leaderHistory()"
    // v1.23 — drain / rolling-restart
    case Term.Apply.After_4_6_0(Term.Name("setDraining"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setDraining(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isDraining"), argClause)
        if argClause.values.isEmpty =>
      "Actor.isDraining()"
    case Term.Apply.After_4_6_0(Term.Name("drainingPeers"), argClause)
        if argClause.values.isEmpty =>
      "Actor.drainingPeers()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeDrainEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeDrainEvents()"
    // v1.23 — cluster metrics aggregation
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSet"), argClause)
        if argClause.values.size == 2 =>
      val n0 = emitExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = emitExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterMetricSet($n0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricGet"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricGet(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSum"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricSum(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricNames"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMetricNames()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeMetricEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeMetricEvents()"
    // v1.23 — auto-reconnect policy (2- or 3-arg form)
    case Term.Apply.After_4_6_0(Term.Name("setReconnectPolicy"), argClause)
        if argClause.values.size == 2 =>
      val ini = emitExpr(argClause.values(0).asInstanceOf[Term])
      val mx  = emitExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.setReconnectPolicy($ini, $mx)"
    case Term.Apply.After_4_6_0(Term.Name("setReconnectPolicy"), argClause)
        if argClause.values.size == 3 =>
      val ini    = emitExpr(argClause.values(0).asInstanceOf[Term])
      val mx     = emitExpr(argClause.values(1).asInstanceOf[Term])
      val giveUp = emitExpr(argClause.values(2).asInstanceOf[Term])
      s"Actor.setReconnectPolicy($ini, $mx, $giveUp)"
    // v1.23 — per-link heartbeat tuning
    case Term.Apply.After_4_6_0(Term.Name("setHeartbeatTimeout"), argClause)
        if argClause.values.size == 2 =>
      val iv   = emitExpr(argClause.values(0).asInstanceOf[Term])
      val dead = emitExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.setHeartbeatTimeout($iv, $dead)"
    // v1.23 — quorum-aware Bully threshold
    case Term.Apply.After_4_6_0(Term.Name("setQuorumSize"), argClause)
        if argClause.values.size == 1 =>
      val n = emitExpr(argClause.values(0).asInstanceOf[Term])
      s"Actor.setQuorumSize($n)"
    // v1.23 — cluster endpoint shared-secret
    case Term.Apply.After_4_6_0(Term.Name("setClusterAuthToken"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.setClusterAuthToken(${emitExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — periodic gossip re-discovery
    case Term.Apply.After_4_6_0(Term.Name("requestGossip"), argClause)
        if argClause.values.isEmpty =>
      "Actor.requestGossip()"
    // v1.23 — cluster configuration distribution
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigSet"), argClause)
        if argClause.values.size == 2 =>
      val k0 = emitExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = emitExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterConfigSet($k0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigGet"), argClause)
        if argClause.values.size == 1 =>
      val k0 = emitExpr(argClause.values(0).asInstanceOf[Term])
      s"Actor.clusterConfigGet($k0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigKeys"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterConfigKeys()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeConfigEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeConfigEvents()"

    case app: Term.Apply => emitCpsApply(app)

    // Chunk 6 — `throw expr` routes the inner through CPS so the
    // throwable construction (e.g. `throw DistributedError(failedNode,
    // reason)`) gets call-site cast injection from chunk 3.  When the
    // inner expression is complex we _bind first; simple inners get
    // a direct throw.
    case t: Term.Throw =>
      if isSimpleCps(t.expr) then s"throw ${t.expr.syntax}"
      else
        val v = freshTmp()
        s"_bind(${emitCpsExpr(t.expr)}, ($v: Any) => throw $v.asInstanceOf[Throwable])"

    // Chunk 5 — `expr.asInstanceOf[T]` wraps the inner emit so the
    // receive/match shapes that live inside the cast (e.g.
    // `receiveWithTimeout(t) { case … }.asInstanceOf[Boolean]` in
    // dep code) reach their CPS arms instead of falling through to
    // `other.syntax`.  Without this, the inner `receiveWithTimeout`
    // stays as a raw bare-name call and Scala reports `Not found:
    // receiveWithTimeout`.  General `f[T]` (Term.ApplyType without
    // the `.asInstanceOf` Select) keeps its verbatim path via the
    // fallback below — we only fire on the cast shape.
    case Term.ApplyType.After_4_6_0(
            Term.Select(qual, Term.Name("asInstanceOf")), tparams) =>
      val tStr = tparams.values.map(_.syntax).mkString(", ")
      val v    = freshTmp()
      s"_bind(${emitCpsExpr(qual)}, ($v: Any) => $v.asInstanceOf[$tStr])"

    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      if op.value == "=" && argClause.values.size == 1 then
        val v = freshTmp()
        val cast = assignmentCast(lhs)
        s"_bind(${emitCpsExpr(argClause.values.head.asInstanceOf[Term])}, ($v: Any) => { ${lhs.syntax} = $v$cast; () })"
      else
      // Scala parses `pid ! (a, b)` as `pid.!(a, b)` (2-arg infix) NOT as
      // `pid.!((a, b))` (1-arg tuple).  For `!` (actor send), reconstruct
      // the tuple syntactically when multiple values are present, so the
      // CPS-emitted Actor.send receives exactly one msg argument.
        val rhs = argClause.values match
          case List(single) => single
          case many         =>
            import scala.meta.{dialects, *}
            val tupleSrc = many.map(_.syntax).mkString("(", ", ", ")")
            dialects.Scala3(Input.String(tupleSrc)).parse[Term].get
        bindArgsCps(List(lhs, rhs)) { case List(vl, vr) =>
          op.value match
            case "==" | "!="    => s"($vl ${op.value} $vr)"
            case "!"            => s"Actor.send($vl, $vr)"
            case "&&" | "||"    => s"(${vl}.asInstanceOf[Boolean] ${op.value} ${vr}.asInstanceOf[Boolean])"
            // Arithmetic / comparison operators: operands are Any in CPS context,
            // so delegate to a runtime helper that pattern-matches on the actual
            // numeric / String types.
            case "+" | "-" | "*" | "/" | "%" |
                 "<" | ">" | "<=" | ">="          => s"""_binOp("${op.value}", $vl, $vr)"""
            case "::"                              => s"$vl :: $vr.asInstanceOf[List[Any]]"
            case "++" | ":::"                      => s"_tupleConcat($vl, $vr)"
            case ":+"                              => s"$vl.asInstanceOf[List[Any]] :+ $vr"
            case "+:"                              => s"$vl +: $vr.asInstanceOf[List[Any]]"
            case "->"                              => s"($vl, $vr)"
            case other                             => s"($vl $other $vr)"
          case _ => "/* infix arity */"
        }

    case Term.Select(qual, name) =>
      // When `qual` is a simple name/literal it stays statically typed
      // (`cluster.nodes` on `cluster: Cluster` works directly).  When it
      // requires a `_bind` (`expr.field` where `expr` itself is a CPS
      // computation), the lambda param is `Any` and `.field` fails to
      // typecheck — route through `_dispatch` which uses the same
      // reflection fallback that handles `.method(args)` in the Apply
      // arm above.  Chunk 6 — also dispatch when `qual` is a simple
      // Term.Name that we widened to `Any` in the surrounding
      // Term.Function lambda (`(node: Any) => node.address`).
      qual match
        case Term.Name(n) if anyBoundNames(n) =>
          s"""_dispatch(${qual.syntax}, "${name.value}", Nil)"""
        case _ if isSimpleCps(qual) =>
          s"${qual.syntax}.${name.value}"
        case _ =>
          val v = freshTmp()
          s"""_bind(${emitCpsExpr(qual)}, ($v: Any) => _dispatch($v, "${name.value}", Nil))"""

    case t: Term.Match =>
      bindArgsCps(List(t.expr)) { case List(sv) =>
        val arms = t.casesBlock.cases.map { c =>
          val guard = c.cond.map(g => s" if ${g.syntax}").getOrElse("")
          // Pattern-bound names come from destructuring a scrutinee that
          // is Any-typed (it's a _bind lambda param).  Register them so
          // field accesses like `info.links` route via _dispatch instead
          // of failing with "value links is not a member of Any".
          // Mirrors the identical treatment in bindArgsCps's PF branch.
          val boundNames = c.pat.collect { case scala.meta.Pat.Var(n) => n.value }.toSet
          val body = withAnyBoundNames(boundNames)(emitCpsExpr(c.body))
          s"  case ${c.pat.syntax}${guard} => $body"
        }.mkString("\n")
        s"($sv match {\n$arms\n})"
        case _ => "/* match */"
      }

    // Fallback to verbatim — caller should ensure no nested effects here.
    case other => other.syntax

  private[codegen] def emitCpsApply(app: Term.Apply): String =
    val args = app.argClause.values
    app.fun match
      // Effect op call: bind args, then _perform
      case Term.Select(Term.Name(eff), Term.Name(op)) if isEffectOpRef(eff, op) =>
        bindArgsCps(args) { vs =>
          val argTail = if vs.isEmpty then "" else ", " + vs.mkString(", ")
          s"""_perform("$eff", "$op"$argTail)"""
        }

      // Curried foldLeft: xs.foldLeft(init)(fn) — route through `_seqFoldLeft`
      // so an effectful `fn` is sequenced step by step instead of leaving a
      // Free tree at every accumulator step.
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldLeft")), initArgClause) =>
        bindArgsCps(qual :: initArgClause.values ++ args) { vs =>
          val q = vs.head; val init = vs(1); val f = vs(2)
          s"_seqFoldLeft($q.asInstanceOf[List[Any]], $init, $f.asInstanceOf[(Any, Any) => Any])"
        }

      // Qualified dep-defined effectful method call. Keep the static call
      // shape (rather than `_dispatch`) so the method may return a Free tree
      // that the surrounding CPS bind can unwrap.
      case Term.Select(qual, Term.Name(method)) if isEffectfulFun(method) =>
        bindArgsCps(qual :: args) { vs =>
          val q = vs.head
          val castedArgs = applyCalleeCasts(method, args, vs.tail)
          s"$q.$method(${castedArgs.mkString(", ")})"
        }

      // Generic qualified dep-defined effectful method call, e.g.
      // `DistributedDataset.run[A, B](...)`.
      case Term.ApplyType.After_4_6_0(Term.Select(qual, Term.Name(method)), typeArgClause)
          if isEffectfulFun(method) =>
        bindArgsCps(qual :: args) { vs =>
          val q = vs.head
          val targs = typeArgClause.values.map(_.syntax).mkString(", ")
          val typeArgMap = calleeTypeArgMap(method, typeArgClause.values)
          val castedArgs = applyCalleeCasts(method, args, vs.tail, typeArgMap)
          s"$q.$method[$targs](${castedArgs.mkString(", ")})"
        }

      // Generic bare dep-defined effectful function call.
      case Term.ApplyType.After_4_6_0(Term.Name(method), typeArgClause)
          if isEffectfulFun(method) =>
        bindArgsCps(args) { vs =>
          val targs = typeArgClause.values.map(_.syntax).mkString(", ")
          val typeArgMap = calleeTypeArgMap(method, typeArgClause.values)
          val castedArgs = applyCalleeCasts(method, args, vs, typeArgMap)
          s"$method[$targs](${castedArgs.mkString(", ")})"
        }

      // Method call: bind qual + args, then runtime-dispatch.  Inside CPS
      // every value is statically typed `Any`, so we can't let the Scala
      // typer resolve methods like `.map` directly — `_dispatch` does it
      // at runtime and threads Free results through `_seq*` helpers for
      // HOFs whose callbacks may produce a Free tree.
      case Term.Select(qual, Term.Name(method)) =>
        bindArgsCps(qual :: args) { vs =>
          // Strip `name = ` prefix from named args before they enter
          // `List(...)`.  Runtime `_dispatch` is positional (Java
          // reflection doesn't see param names anyway), and a literal
          // `List(timeoutMs = 1000)` would be parsed as
          // `List.apply(timeoutMs = 1000)` — `List` has no such param.
          val cleaned = vs.tail.map { v =>
            val eq = v.indexOf('=')
            // Conservative: only strip if the LHS of the first `=`
            // looks like a Scala identifier (avoid mangling expressions
            // that contain `=` such as `x == y` or `_ => …`).
            if eq > 0 && v.substring(0, eq).trim.matches("[A-Za-z_][A-Za-z0-9_]*") then
              v.substring(eq + 1).trim
            else v
          }
          val argList = cleaned.mkString(", ")
          val argSeq  = if cleaned.isEmpty then "Nil" else s"List($argList)"
          s"""_dispatch(${vs.head}, "${method}", $argSeq)"""
        }

      case fun =>
        // The function reference itself is always a callable value (not a
        // Free), so we never bind on `fun` — only on its args. The call's
        // result may be a Free; the caller's bind handles that.
        //
        // Chunk 3 — cast injection for known dep callees: when `fun` is
        // a bare name resolving to a `Defn.Def` or `Defn.Class` we
        // indexed in `inlineImport`, look up each param's declared type
        // and wrap the corresponding bound value as `v.asInstanceOf[T]`.
        // Without this, calls like `Cluster(nodeList, pids)` and
        // `collectResults(assignments = assignments, …)` fail because
        // `nodeList` / `assignments` are `Any` in the surrounding
        // continuation but the callee expects concrete types.
        val funInfo = fun match
          case Term.Name(n) =>
            Some((n, Map.empty[String, String]))
          case Term.ApplyType.After_4_6_0(Term.Name(n), typeArgClause) =>
            Some((n, calleeTypeArgMap(n, typeArgClause.values)))
          case Term.ApplyType.After_4_6_0(Term.Select(_, Term.Name(n)), typeArgClause) =>
            Some((n, calleeTypeArgMap(n, typeArgClause.values)))
          case _ =>
            None
        bindArgsCps(args) { vs =>
          val castedVs = funInfo match
            case None    => vs
            case Some((n, typeArgMap)) => applyCalleeCasts(n, args, vs, typeArgMap)
          // Chunk 9 — when `fun` is an Any-bound name (e.g.
          // `workers` from a CPS continuation, accessed as
          // `workers(idx)`), Scala won't apply Any as a function.
          // Cast through `List[Any]` so the call typechecks
          // (positional element access on Any-typed collections is
          // the dominant shape; reflection-call would be heavier).
          funInfo.map(_._1) match
            case Some(n) if anyBoundNames(n) =>
              // Index args also come in Any-typed; List.apply needs Int.
              val intArgs = castedVs.map(v => s"$v.asInstanceOf[Int]")
              s"${fun.syntax}.asInstanceOf[List[Any]](${intArgs.mkString(", ")})"
            case _ =>
              s"${fun.syntax}(${castedVs.mkString(", ")})"
        }

  private def assignmentCast(lhs: Term): String =
    lhs match
      case Term.Name(n) =>
        declaredVarTypes.get(n).map(t => s".asInstanceOf[$t]").getOrElse("")
      case _ => ""

  /** Lower a `while (cond) body` reachable in a CPS context to a trampolined
   *  recursive helper, then thread `continuation` after it completes:
   *  {{{ { def _w(): Any = if (cond) _bind(<cps body>, _ => _w()) else (); _bind(_w(), _ => <k>) } }}}
   *  `_bind` is lazy on a `_Computation`, so a body that performs every iteration
   *  recurses without growing the JVM stack (the runner trampolines it). The vars
   *  the loop reads/writes are real typed mutable vars in the enclosing block, so
   *  `cond` and the body's assignments compile. */
  /** Recognise a single-generator `for i <- (lo until/to hi) do …` over an integer Range.
   *  Returns `(loopVarName, lo, hi, inclusive)`; `None` for anything else (multi-generator,
   *  non-range source, `by`-stepped, filter guards). (effect-perform-in-fordo.) */
  private def rangeForDo(t: Term.For): Option[(String, Term, Term, Boolean)] =
    t.enumsBlock.enums match
      case List(g: scala.meta.Enumerator.Generator) =>
        (g.pat, g.rhs) match
          case (scala.meta.Pat.Var(scala.meta.Term.Name(iName)),
                Term.ApplyInfix.After_4_6_0(lo, Term.Name(op), _, ac))
              if (op == "until" || op == "to") && ac.values.lengthCompare(1) == 0 =>
            Some((iName, lo, ac.values.head.asInstanceOf[Term], op == "to"))
          case _ => None
      case _ => None

  /** Recognise a single-generator `for x <- coll do …` where `x` is a plain var and `coll` is a pure
   *  (effect-free) non-Range collection expression. Returns `(loopVarName, coll)`. (effect-perform-in-fordo.) */
  private def collForDo(t: Term.For): Option[(String, Term)] =
    if rangeForDo(t).isDefined then None
    else t.enumsBlock.enums match
      case List(g: scala.meta.Enumerator.Generator) =>
        g.pat match
          case scala.meta.Pat.Var(scala.meta.Term.Name(iName)) if !cpsTermPerforms(g.rhs) =>
            Some((iName, g.rhs))
          case _ => None
      case _ => None

  private def emitWhileTrampoline(w: Term.While, continuation: String): String =
    val wn   = "_w" + freshTmp()
    val body = emitCpsExpr(w.body)
    s"{ def $wn(): Any = if (${w.cond.syntax}) _bind($body, (_wk: Any) => $wn()) else (); _bind($wn(), (_wr: Any) => ${continuation}) }"

  /** True if a term reaches a `perform` (a user effect-op `Eff.op` or a call to
   *  an effectful function) ANYWHERE in its subtree. Unlike `termUsesEffects`,
   *  this descends through every `Tree` child (incl. `Term.ArgClause`), so it
   *  catches a perform nested inside an operator argument — `acc + Bump.tick()` —
   *  which decides whether a CPS-block assign must thread the continuation
   *  through `_bind` (a lazy effectful computation discarded in statement
   *  position would lose the perform). */
  private def cpsTermPerforms(t: scala.meta.Tree): Boolean = t match
    case Term.Select(Term.Name(eff), Term.Name(op)) if isEffectOpRef(eff, op)        => true
    case Term.Apply.After_4_6_0(Term.Name(n), _) if isEffectfulFun(n)                => true
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name(n)), _) if isEffectfulFun(n) => true
    case _                                                                            => t.children.exists(cpsTermPerforms)

  /** Best-effort type of a `var` initializer so it can be declared as a typed
   *  mutable `var` (and registered in `declaredVarTypes` for assignment casts).
   *  Covers the numeric-loop shapes the effect corpus uses: literals, a known
   *  local/param name, primitive `.toX` conversions, and arithmetic over them.
   *  `None` → no annotation (let Scala infer) and no cast registered. */
  private def inferVarType(rhs: Term): Option[String] = rhs match
    case Lit.Int(_)     => Some("Int")
    case Lit.Long(_)    => Some("Long")
    case Lit.Double(_)  => Some("Double")
    case Lit.Float(_)   => Some("Float")
    case Lit.Boolean(_) => Some("Boolean")
    case Lit.String(_)  => Some("String")
    case Lit.Char(_)    => Some("Char")
    case Term.Name(n)   => declaredVarTypes.get(n)
    case Term.ApplyInfix.After_4_6_0(l, Term.Name(op), _, argClause)
        if op.length == 1 && "+-*/%".contains(op) =>
      val rt = argClause.values.collectFirst { case t: Term => t }.flatMap(inferVarType)
      numericJoin(inferVarType(l), rt)
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name(m)), _) => convType(m)
    case Term.Select(_, Term.Name(m))                            => convType(m)
    case _                                                       => None

  private def convType(m: String): Option[String] = m match
    case "toLong"   => Some("Long")
    case "toInt"    => Some("Int")
    case "toDouble" => Some("Double")
    case "toFloat"  => Some("Float")
    case _          => None

  /** Widen two numeric primitive types to the dominant one (`Double` > `Float` >
   *  `Long` > `Int`); falls back to whichever side is known. */
  private def numericJoin(a: Option[String], b: Option[String]): Option[String] =
    val rank = Map("Double" -> 4, "Float" -> 3, "Long" -> 2, "Int" -> 1)
    (a, b) match
      case (Some(x), Some(y)) => Some(if rank.getOrElse(x, 0) >= rank.getOrElse(y, 0) then x else y)
      case (Some(x), None)    => Some(x)
      case (None, Some(y))    => Some(y)
      case _                  => None

  private val externalConstructorSigs: Map[String, (String, List[(String, String)])] =
    Map(
      "DatasetWirePartition" -> (
        "scalascript.typeddata.DatasetWirePartition",
        List(
          "partitionId" -> "Int",
          "values"      -> "Vector[scalascript.typeddata.JsonValue]"
        )
      )
    )

  private def qualifyDepTypeNames(tpeSyntax: String): String =
    depTypeNames.foldLeft(tpeSyntax) { case (acc, (name, pkg)) =>
      if pkg.isEmpty then acc
      else
        val qualified = (pkg :+ name).mkString(".")
        // Negative lookbehind for `.` so we don't re-prefix an
        // already-qualified occurrence.
        acc.replaceAll(
          s"(?<![.A-Za-z0-9_])${java.util.regex.Pattern.quote(name)}(?![A-Za-z0-9_])",
          qualified
        )
    }

  private def substituteAndQualifyType(
      t:           scala.meta.Type,
      tparamNames: Set[String],
      typeArgMap:  Map[String, String]
  ): String =
    val afterTparams =
      if tparamNames.isEmpty then t.syntax
      else
        tparamNames.foldLeft(t.syntax) { (acc, tp) =>
          val replacement = typeArgMap.getOrElse(tp, "Any")
          acc.replaceAll(s"\\b${java.util.regex.Pattern.quote(tp)}\\b", replacement)
        }
    qualifyDepTypeNames(afterTparams)

  private def knownCalleeHasSignature(callee: String): Boolean =
    depClasses.contains(callee) || depDefs.contains(callee) ||
      localDefSigs.contains(callee) || externalConstructorSigs.contains(callee)

  private def declaredResultType(
      t:           scala.meta.Type,
      tparamNames: Set[String],
      typeArgMap:  Map[String, String]
  ): Option[String] =
    t match
      case Type.ApplyInfix(lhs, Type.Name("!"), _) =>
        Some(substituteAndQualifyType(lhs, tparamNames, typeArgMap))
      case Type.Name("Any") =>
        None
      case other =>
        Some(substituteAndQualifyType(other, tparamNames, typeArgMap))

  private def calleeResultType(callee: String, typeArgMap: Map[String, String]): Option[String] =
    externalConstructorSigs.get(callee).map(_._1).orElse {
      depClasses.get(callee).map { cls =>
        val tparams = cls.tparamClause.values.map(_.name.value)
        val args =
          if tparams.isEmpty then ""
          else tparams.map(tp => typeArgMap.getOrElse(tp, "Any")).mkString("[", ", ", "]")
        qualifyDepTypeNames(callee + args)
      }.orElse {
        depDefs.get(callee).orElse(localDefSigs.get(callee)).flatMap { d =>
          val tparams = d.paramClauseGroups.flatMap(_.tparamClause.values).map(_.name.value).toSet
          d.decltpe.flatMap(t => declaredResultType(t, tparams, typeArgMap))
        }
      }
    }

  private def inferCpsValType(rhs: Term): Option[String] =
    rhs match
      case Term.Apply.After_4_6_0(Term.Name(callee), _) =>
        calleeResultType(callee, Map.empty)
      case Term.Apply.After_4_6_0(
            Term.ApplyType.After_4_6_0(Term.Name(callee), typeArgClause),
            _
          ) =>
        calleeResultType(callee, calleeTypeArgMap(callee, typeArgClause.values))
      case Term.Apply.After_4_6_0(Term.Select(_, Term.Name(callee)), _) =>
        calleeResultType(callee, Map.empty)
      case Term.Apply.After_4_6_0(
            Term.ApplyType.After_4_6_0(Term.Select(_, Term.Name(callee)), typeArgClause),
            _
          ) =>
        calleeResultType(callee, calleeTypeArgMap(callee, typeArgClause.values))
      case _ =>
        None

  /** Look up the declared param type for a call to a known dep def
   *  or dep class constructor, by position (`argIdx`) or by name
   *  (when the arg is `Term.Assign(Term.Name(n), _)`).  Returns the
   *  type's `syntax` form when present, `None` otherwise — including
   *  for varargs (`T*`) where `asInstanceOf[T*]` isn't a legal cast,
   *  and for types that reference a class-scoped type param (e.g.
   *  `List[T]` on `case class DistributedResult[T](items: List[T])`),
   *  where `T` is out of scope at the call site. */
  private[codegen] def calleeParamType(
      callee:  String,
      argIdx:  Int,
      argName: Option[String],
      typeArgMap: Map[String, String]
  ): Option[String] =
    val classDef = depClasses.get(callee)
    val defDef = depDefs.get(callee).orElse(localDefSigs.get(callee))
    if classDef.isEmpty && defDef.isEmpty then
      externalConstructorSigs.get(callee).flatMap { case (_, params) =>
        argName match
          case Some(n) => params.find(_._1 == n).map(_._2)
          case None    => params.lift(argIdx).map(_._2)
      }
    else {
      val ps: Option[List[scala.meta.Term.Param]] =
        classDef.map(_.ctor.paramClauses.flatMap(_.values).toList)
          .orElse(
            defDef.map(d =>
              d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).toList
            )
          )
      // Names of any class-level type params we need to exclude from
      // the cast (caller isn't inside the class scope so `T` is unbound).
      val tparamNames: Set[String] =
        classDef.map(_.tparamClause.values.map(_.name.value).toSet).getOrElse(Set.empty) ++
          defDef.map(_.paramClauseGroups.flatMap(_.tparamClause.values).map(_.name.value).toSet).getOrElse(Set.empty)
      ps.flatMap { params =>
        val targetOpt = argName match
          case Some(n) => params.find(_.name.value == n)
          case None    => params.lift(argIdx)
        targetOpt.flatMap { p =>
          p.decltpe match
            // Skip varargs — `asInstanceOf[Node*]` doesn't parse.
            case Some(_: scala.meta.Type.Repeated) => None
            case Some(t)                           => Some(substituteAndQualifyType(t, tparamNames, typeArgMap))
            case None                              => None
        }
      }
    }

  /** Apply per-arg casts in step with `vs` produced by `bindArgsCps`.
   *  Handles both positional args (cast the bound value as a whole) and
   *  named args (`vs(i)` looks like `"name = value"` — cast the rhs). */
  private def applyCalleeCasts(
      callee: String,
      args:   List[scala.meta.Term],
      vs:     List[String],
      typeArgMap: Map[String, String] = Map.empty
  ): List[String] =
    if !knownCalleeHasSignature(callee) then vs
    else
      vs.zip(args).zipWithIndex.map { case ((v, arg), i) =>
        arg match
          case scala.meta.Term.Assign(scala.meta.Term.Name(n), _) =>
            calleeParamType(callee, i, Some(n), typeArgMap) match
              case None => v
              case Some(t) =>
                val eqIdx = v.indexOf('=')
                if eqIdx < 0 then v
                else
                  val rhs = v.substring(eqIdx + 1).trim
                  s"$n = $rhs.asInstanceOf[$t]"
          case _ =>
            calleeParamType(callee, i, None, typeArgMap) match
              case None    => v
              case Some(t) => s"$v.asInstanceOf[$t]"
      }

  private def calleeTypeArgMap(
      callee: String,
      typeArgs: Seq[scala.meta.Type]
  ): Map[String, String] =
    depDefs.get(callee).orElse(localDefSigs.get(callee)) match
      case None => Map.empty
      case Some(d) =>
        d.paramClauseGroups.flatMap(_.tparamClause.values).map(_.name.value)
          .zip(typeArgs.map(_.syntax))
          .toMap

  /** Emit a Scala block in CPS form: thread vals + statements via `_bind`. */
  private def emitCpsBlock(stats: List[Stat]): String =
    if stats.isEmpty then "()"
    else
      // Nested `def f = receive { ... }` inside a runActors body —
      // emit with CPS body so `receive` / `!` reach their CPS arms
      // instead of staying raw and failing `Not found: receive`.
      // Conservative trigger: only when the def's body transitively
      // contains an effect primitive (same predicate dep-mode uses).
      def emitDefMaybeCps(d: Defn.Def): String =
        if containsEffectPrimitive(d.body) then
          val cpsBody = emitCpsExpr(d.body)
          if shouldPreserveCpsDeclaredResult(d) then
            s"def ${d.name.value}${emitEffectfulParamGroups(d)}${emitEffectfulResultType(d)} = ${castCpsResultToDeclared(d, cpsBody)}"
          else
            s"def ${d.name.value}${emitEffectfulParamGroups(d)}: Any = $cpsBody"
        else d.syntax
      def build(remaining: List[Stat]): String = remaining match
        case Nil => "()"
        case List(s) =>
          s match
            case t: Term => emitCpsExpr(t)
            case Defn.Val(_, List(Pat.Var(n)), tpe, rhs) =>
              emitCpsBindWithType(rhs, n.value, tpe, "()")
            case d: Defn.Def => s"{ ${emitDefMaybeCps(d)}; () }"
            case other => s"{ ${other.syntax}; () }"
        case s :: rest =>
          s match
            case Defn.Val(_, List(Pat.Var(n)), tpe, rhs) =>
              emitCpsBindWithType(rhs, n.value, tpe, build(rest))
            // A `var` in a CPS block must stay a REAL mutable `var` (not a `_bind`
            // lambda param) so a later `x = …` and a `while` over it compile. Emit
            // it typed (declared ascription, else inferred) and register the type so
            // assignments cast `Any → T`. An effectful rhs is `_bind`-threaded first.
            case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), tpe, rhs) =>
              val t = tpe.map(_.syntax).orElse(inferVarType(rhs))
              t.foreach(tt => declaredVarTypes(n.value) = tt)
              val ann = t.map(tt => s": $tt").getOrElse("")
              if cpsTermPerforms(rhs) then
                val v    = freshTmp()
                val cast = t.map(tt => s".asInstanceOf[$tt]").getOrElse("")
                s"_bind(${emitCpsExpr(rhs)}, ($v: Any) => { var ${n.value}$ann = $v$cast; ${build(rest)} })"
              else
                s"{ var ${n.value}$ann = ${rhs.syntax}; ${build(rest)} }"
            case w: Term.While =>
              emitWhileTrampoline(w, build(rest))
            case d: Defn.Def =>
              s"{ ${emitDefMaybeCps(d)}; ${build(rest)} }"
            case a: Term.Assign =>
              // An effectful assign (`x = … perform …`) emits a lazy `_Computation`
              // (`_bind` is lazy on a perform), so raw `{ comp; rest }` would discard
              // the perform. Thread the continuation through `_bind` instead. A pure
              // assign stays raw (eager) — unchanged behaviour.
              if cpsTermPerforms(a) then
                s"_bind(${emitCpsExpr(a)}, (${freshTmp()}: Any) => ${build(rest)})"
              else s"{ ${emitCpsExpr(a)}; ${build(rest)} }"
            case t: Term =>
              if isSimpleCps(t) then s"{ ${t.syntax}; ${build(rest)} }"
              else
                val tmp = freshTmp()
                s"_bind(${emitCpsExpr(t)}, (${tmp}: Any) => ${build(rest)})"
            case other => s"{ ${other.syntax}; ${build(rest)} }"
      build(stats)

  /** Emit a CPS `_bind(rhs, lambda)` that preserves the user's
   *  declared val type inside the lambda body — without breaking the
   *  runtime's `_bind(c: Any, f: Any => Any): Any` signature.
   *
   *  When the user wrote `val x: T = expr`, we emit:
   *    `_bind(rhs, ((x: T) => body).asInstanceOf[Any => Any])`
   *
   *  The `.asInstanceOf[Any => Any]` widens the function to the
   *  signature `_bind` expects; inside the body `x` still has type `T`
   *  so downstream constructor calls / field accesses typecheck the
   *  way the user intended.
   *
   *  When no type ascription is present, fall back to the legacy
   *  `(x: Any) => body` form unchanged. */
  private def emitCpsBindWithType(
      rhs:  Term,
      name: String,
      tpe:  Option[scala.meta.Type],
      body: => String
  ): String =
    // Pre-emit `rhs` so its tmp names (if any) come from the OUTER
    // scope's freshTmp counter, then build the continuation `body`
    // with `name` registered as Any-bound if tpe was None — so
    // `name.member` inside `body` routes via _dispatch (chunk 8).
    val rhsEmit = emitCpsExpr(rhs)
    val tpeSyntax = tpe.map(_.syntax).orElse(inferCpsValType(rhs))
    tpeSyntax match
      case None =>
        val bodyStr = withAnyBoundNames(Set(name))(body)
        s"_bind($rhsEmit, (${name}: Any) => ${bodyStr})"
      case Some(tSyntax) =>
        s"_bind($rhsEmit, ((${name}: ${tSyntax}) => ${body}).asInstanceOf[Any => Any])"
