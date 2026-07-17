package scalascript.codegen

import scala.meta.*

/** CPS codegen for effectful contexts: inside a handle body (or an effectful
 *  function), expressions are emitted in CPS form, threading every operation
 *  that may depend on a Free value through `_bind`. Includes the pattern-match
 *  and for-comprehension codegen grouped under the same banner. Lifted out of
 *  JsGen to keep the generator navigable; self-typed because the emitters
 *  read/write generator state and call back into core emission (`genExpr`,
 *  `freshTmp`, etc.) on `self: JsGen`. Mirrors the JvmGen CPS split. */
private[codegen] trait JsGenCpsCodegen:
  self: JsGen =>

  //
  // Inside a handle body (or the body of an effectful function), expressions
  // are emitted in CPS form: every operation that may depend on a Free value
  // is threaded through `_bind`. Plain JS values double as Pure(value), so
  // pure sub-expressions don't pay any wrapping overhead.
  //
  // genCpsExpr(t) returns a JS expression that evaluates to a Free value:
  // either a plain JS value (Pure) or a {_tag:'Perform', eff, op, args, k} node.

  /** Whether `t` is a syntactically simple value reference: no sub-computation,
   *  guaranteed not to be a Perform. Used to avoid pointless `_bind` chains. */
  private def isSimpleCpsExpr(t: Term): Boolean = t match
    case _: Lit                                  => true
    case _: Term.Placeholder                     => true
    case Term.Name(n) if !isEffectfulFun(n)      => true
    case _                                       => false

  /** Bind a list of CPS sub-expressions; pass their resulting plain values to k.
   *  Simple sub-expressions are inlined without a bind. */
  private def bindArgsCps(args: List[Term])(k: List[String] => String): String =
    def loop(remaining: List[Term], acc: List[String]): String = remaining match
      case Nil       => k(acc.reverse)
      case t :: rest =>
        if isSimpleCpsExpr(t) then loop(rest, genExpr(t) :: acc)
        else
          val v = freshTmp()
          s"_bind(${genCpsExpr(t)}, $v => ${loop(rest, v :: acc)})"
    loop(args, Nil)

  /** Generate a JS expression in CPS form. */
  private[codegen] def genCpsExpr(term: Term): String = term match
    // Literals / names — pure values pass straight through
    case _: Lit              => genExpr(term)
    case _: Term.Placeholder => genExpr(term)
    case Term.Name(_)        => genExpr(term)

    // Block — chain stats through _bind
    case Term.Block(stats) => genCpsBlockAsIife(stats)

    // If — bind cond, then branch (each branch is CPS)
    case t: Term.If =>
      val thenJs = genCpsExpr(t.thenp)
      val elseJs = t.elsep match
        case Lit.Unit() => "undefined"
        case e          => genCpsExpr(e)
      if isSimpleCpsExpr(t.cond) then s"(${genExpr(t.cond)} ? ($thenJs) : ($elseJs))"
      else
        val tmp = freshTmp()
        s"_bind(${genCpsExpr(t.cond)}, $tmp => $tmp ? ($thenJs) : ($elseJs))"

    // String interpolation — bind args
    case Term.Interpolate(Term.Name(prefix), parts, args)
        if prefix == "s" || prefix == "f" || prefix == "md" =>
      bindArgsCps(args.map(_.asInstanceOf[Term])) { vs =>
        // f"…" format specs: see the twin in genExpr (js-f-interp-format-spec).
        val fmtRe = "^%[-+# 0,(]*\\d*(?:\\.\\d+)?[bBhHsScCdoxXeEfgGaAtT%]".r
        def fSpecAt(i: Int): Option[String] =
          if prefix == "f" && i >= 0 && i < parts.length then
            fmtRe.findFirstIn(parts(i).asInstanceOf[Lit.String].value)
          else None
        val sb2 = StringBuilder()
        sb2.append("`")
        for i <- parts.indices do
          val partRaw = parts(i).asInstanceOf[Lit.String].value
          val part = fSpecAt(i) match
            case Some(spec) if i > 0 => partRaw.substring(spec.length)
            case _                   => partRaw
          // Backslash first — see twin in genExpr.
          sb2.append(part.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$"))
          if i < args.length then
            val wrapped = fSpecAt(i + 1) match
              case Some(spec) => s"""_fmtSpec(${JsGenStringUtils.jsQuote(spec)}, ${vs(i)})"""
              case None       => s"_show(${vs(i)})"
            sb2.append("${").append(wrapped).append("}")
        sb2.append("`")
        val templateLiteral = sb2.toString
        if prefix == "md" then s"_md($templateLiteral)" else templateLiteral
      }

    // Registered interpolator (CPS path) — InterpolatorRegistry takes precedence.
    // User-defined interpolator (CPS path): _ext_StringContext_prefix(_sc([...]), [...])
    case Term.Interpolate(Term.Name(prefix), parts, args) =>
      bindArgsCps(args.map(_.asInstanceOf[Term])) { vs =>
        val partStrs = parts.map(_.asInstanceOf[Lit.String].value)
        scalascript.compiler.plugin.InterpolatorRegistry.lookup(prefix) match
          case Some(impl) => impl.jsEmit(partStrs, vs.toList)
          case None =>
            val partsJs = partStrs.map(s => "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
              .mkString("[", ", ", "]")
            val argsJs = vs.mkString("[", ", ", "]")
            s"_ext_StringContext_$prefix(_sc($partsJs), $argsJs)"
      }

    // Tuple
    case Term.Tuple(elems) =>
      bindArgsCps(elems) { vs =>
        s"Object.assign([${vs.mkString(", ")}], {_isTuple: true})"
      }

    // Unary op (`!x`, `-x`, `~x`, `+x`) — bind the (possibly effectful) operand,
    // then apply the op. Without this, `!query(...)` falls to genExpr and the
    // effectful operand gets `_run`-wrapped, running the effect outside the handler.
    case t: Term.ApplyUnary =>
      val opJs = t.op.value match
        case "!" => "!"
        case "-" => "-"
        case "+" => "+"
        case "~" => "~"
        case _   => ""
      if opJs.isEmpty || isSimpleCpsExpr(t.arg) then genExpr(t)
      else
        val v = freshTmp()
        s"_bind(${genCpsExpr(t.arg)}, $v => $opJs($v))"

    // Lambda — CPS body
    case Term.Function.After_4_6_0(paramClause, body) =>
      val params = paramClause.values.map(_.name.value)
      val bodyJs = withLocalBindings(params) { body match
        case Term.Block(stats) => genCpsBlockAsIife(stats)
        case expr              => genCpsExpr(expr)
      }
      val jsParams = params.map(localBindingName)
      if jsParams.length == 1 then s"${jsParams.head} => $bodyJs"
      else
        val arity  = jsParams.length
        val joined = jsParams.mkString(", ")
        s"((...__a) => { const [$joined] = (__a.length === 1 && Array.isArray(__a[0]) && __a[0].length === $arity) ? __a[0] : __a; return $bodyJs; })"

    // Anonymous function with placeholders — body is CPS
    case t: Term.AnonymousFunction =>
      phCounters = 0 :: phCounters
      val bodyJs = genCpsExpr(t.body)
      val count  = phCounters.head
      phCounters = phCounters.tail
      val params = (0 until count).map(i => s"_$$${i}")
      if params.isEmpty then s"() => $bodyJs"
      else s"(${params.mkString(", ")}) => $bodyJs"

    // Nested handle inside CPS body — returns Free that we treat like any value
    case Term.Apply.After_4_6_0(
      Term.Apply.After_4_6_0(Term.Name("handle"), bodyArgClause),
      pfArgClause
    ) if bodyArgClause.values.size == 1 =>
      pfArgClause.values match
        case List(pf: Term.PartialFunction) =>
          genHandleForm(bodyArgClause.values.head.asInstanceOf[Term], pf.cases)
        case _ => "/* invalid handle */ undefined"

    // Nested runAsync inside CPS body
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runAsync(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      // Inside a CPS body: no `await` — returns a Promise that _runAsyncParallelInner
      // handles via its thenable check (nested runAsyncParallel produces a sub-Promise
      // which _FlatMap's sub resolves via `await _runAsyncParallelInner(node.sub)`).
      s"_runAsyncParallel(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // Nested storage handlers inside CPS body
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), bodyArgClause)
        if bodyArgClause.values.size >= 1 =>
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      val pathJs = bodyArgClause.values.lift(1).map(p => genExpr(p.asInstanceOf[Term])).getOrElse("null")
      s"_runStorage(() => $bodyJs, $pathJs)"
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"_runStorage(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])}, null)"

    // ── v1.6 Actors Phase 1 (inside CPS body) ──────────────────────────
    case Term.Apply.After_4_6_0(Term.Name("runActors"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val awaitPrefix = if usesRunActors then "await " else ""
      s"${awaitPrefix}_runActors(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // v1.4 effect runners inside CPS body — same thunk-wrapping as genExpr.
    // runStream uses genExpr (not genCpsExpr) — see genExpr variant above.
    case Term.Apply.After_4_6_0(Term.Name("runStream"), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      val bodyJs = genExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"runStream(() => $bodyJs)"
    case Term.Apply.After_4_6_0(Term.Name(runner), bodyArgClause)
        if bodyArgClause.values.size == 1 &&
           Set("runLogger","runLoggerJson","runLoggerToList",
               "runRandom","runClock","runEnv","runHttp",
               "runRetry","runRetryNoSleep",
               "runCache","runCacheBypass","runTx").contains(runner) =>
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$runner(() => $bodyJs)"
    case Term.Apply.After_4_6_0(
          Term.Apply.After_4_6_0(Term.Name(runner), argClause),
          bodyArgClause)
        if bodyArgClause.values.size == 1 &&
           Set("runRandomSeeded","runClockAt","runEnvWith",
               "runState","runAuthWith","runHttpStub").contains(runner) =>
      val argJs  = argClause.values.map(v => genExpr(v.asInstanceOf[Term])).mkString(", ")
      val bodyJs = genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])
      s"$runner($argJs)(() => $bodyJs)"

    case Term.Apply.After_4_6_0(
            Term.Apply.After_4_6_0(Term.Name("receive"), timeoutArgClause),
            pfArgClause)
        if pfArgClause.values.size == 1 && timeoutArgClause.values.size == 1 =>
      val timeoutTerm = timeoutArgClause.values.head match
        case Term.Assign(Term.Name("timeout"), v) => v
        case other: Term                          => other
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcherJs = genReceiveMatcher(pf.cases)
          s"Actor.receive_t(_registerReceive($matcherJs), ${genExpr(timeoutTerm.asInstanceOf[Term])})"
        case _ => "/* invalid receive */ undefined"

    case Term.Apply.After_4_6_0(Term.Name("receive"), pfArgClause)
        if pfArgClause.values.size == 1 =>
      pfArgClause.values.head match
        case pf: Term.PartialFunction =>
          val matcherJs = genReceiveMatcher(pf.cases)
          s"Actor.receive_(_registerReceive($matcherJs))"
        case _ => "/* invalid receive */ undefined"

    case Term.Apply.After_4_6_0(Term.Name("spawn"), argClause)
        if argClause.values.size == 1 =>
      // The spawn arg is a behavior thunk.  genCpsExpr on a Function
      // emits a lambda whose body is `_bind`-chained — exactly the
      // shape `Actor.spawn(thunk)` expects.
      s"Actor.spawn(${genCpsExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("spawn_link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.spawn_link(${genCpsExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("spawnBounded"), argClause)
        if argClause.values.size == 3 =>
      val capJs      = genExpr(argClause.values(0).asInstanceOf[Term])
      val overflowJs = genExpr(argClause.values(1).asInstanceOf[Term])
      val thunkJs    = genCpsExpr(argClause.values(2).asInstanceOf[Term])
      s"Actor.spawnBounded($capJs, $overflowJs, $thunkJs)"
    case Term.Apply.After_4_6_0(Term.Name("self"), argClause)
        if argClause.values.isEmpty =>
      "Actor.self()"
    case Term.Apply.After_4_6_0(Term.Name("stop"), argClause)
        if argClause.values.isEmpty =>
      "Actor.stop()"
    case Term.Apply.After_4_6_0(Term.Name("exit"), argClause)
        if argClause.values.size == 2 =>
      val pidJs    = genExpr(argClause.values(0).asInstanceOf[Term])
      val reasonJs = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.exit($pidJs, $reasonJs)"
    // v1.6 Phase 2 — supervision primitives (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("link"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.link(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("monitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.monitor(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("demonitor"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.demonitor(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("trapExit"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.trapExit(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6 Phase 3 — distributed node primitives (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("startNode"), argClause)
        if argClause.values.size >= 1 =>
      val nodeId = genExpr(argClause.values(0).asInstanceOf[Term])
      val url    = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.startNode($nodeId, $url)"
    case Term.Apply.After_4_6_0(Term.Name("connectNode"), argClause)
        if argClause.values.size >= 1 =>
      val url   = genExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.connectNode($url, $token)"
    case Term.Apply.After_4_6_0(Term.Name("joinCluster"), argClause)
        if argClause.values.size >= 1 =>
      val seeds = genExpr(argClause.values(0).asInstanceOf[Term])
      val token = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "\"\""
      s"Actor.joinCluster($seeds, $token)"
    case Term.Apply.After_4_6_0(Term.Name("register"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.register(${genExpr(argClause.values(0).asInstanceOf[Term])}, ${genExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("whereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.whereis(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalRegister"), argClause)
        if argClause.values.size == 2 =>
      s"Actor.globalRegister(${genExpr(argClause.values(0).asInstanceOf[Term])}, ${genExpr(argClause.values(1).asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("globalWhereis"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.globalWhereis(${genExpr(argClause.values.head.asInstanceOf[Term])})"
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
      s"Actor.phiOf(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("isSuspect"), argClause)
        if argClause.values.size >= 1 =>
      val nid = genExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
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
      val nid = genExpr(argClause.values(0).asInstanceOf[Term])
      val thr = if argClause.values.size >= 2 then genExpr(argClause.values(1).asInstanceOf[Term]) else "8.0"
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
      s"Actor.setAutoReelect(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.23 — protocol switch + history
    case Term.Apply.After_4_6_0(Term.Name("useRaftLeaderElection"), argClause)
        if argClause.values.isEmpty =>
      "Actor.useRaftLeaderElection()"
    case Term.Apply.After_4_6_0(Term.Name("useExternalCoordinator"), argClause)
        if argClause.values.size == 4 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
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
      s"Actor.setDraining(${genExpr(argClause.values.head.asInstanceOf[Term])})"
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
      val n0 = genExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterMetricSet($n0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricGet"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricGet(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricSum"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.clusterMetricSum(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    case Term.Apply.After_4_6_0(Term.Name("clusterMetricNames"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterMetricNames()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeMetricEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeMetricEvents()"
    // v1.23 — auto-reconnect policy
    case Term.Apply.After_4_6_0(Term.Name("setReconnectPolicy"), argClause)
        if argClause.values.size == 2 =>
      val ini = genExpr(argClause.values(0).asInstanceOf[Term])
      val mx  = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.setReconnectPolicy($ini, $mx)"
    // v1.23 — periodic gossip re-discovery
    case Term.Apply.After_4_6_0(Term.Name("requestGossip"), argClause)
        if argClause.values.isEmpty =>
      "Actor.requestGossip()"
    // v1.23 — cluster configuration distribution
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigSet"), argClause)
        if argClause.values.size == 2 =>
      val k0 = genExpr(argClause.values(0).asInstanceOf[Term])
      val v0 = genExpr(argClause.values(1).asInstanceOf[Term])
      s"Actor.clusterConfigSet($k0, $v0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigGet"), argClause)
        if argClause.values.size == 1 =>
      val k0 = genExpr(argClause.values(0).asInstanceOf[Term])
      s"Actor.clusterConfigGet($k0)"
    case Term.Apply.After_4_6_0(Term.Name("clusterConfigKeys"), argClause)
        if argClause.values.isEmpty =>
      "Actor.clusterConfigKeys()"
    case Term.Apply.After_4_6_0(Term.Name("subscribeConfigEvents"), argClause)
        if argClause.values.isEmpty =>
      "Actor.subscribeConfigEvents()"
    // v1.6.x — scheduled sends (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("sendAfter"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.sendAfter(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("sendInterval"), argClause)
        if argClause.values.size == 3 =>
      val vs = argClause.values.map(v => genExpr(v.asInstanceOf[Term]))
      s"Actor.sendInterval(${vs(0)}, ${vs(1)}, ${vs(2)})"
    case Term.Apply.After_4_6_0(Term.Name("cancelTimer"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.cancelTimer(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.6.x — process introspection (inside CPS body)
    case Term.Apply.After_4_6_0(Term.Name("processInfo"), argClause)
        if argClause.values.size == 1 =>
      s"Actor.processInfo(${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.10 Generator inside CPS body — generator { } / generator[T] { }
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("generator"), _) | Term.Name("generator"),
        argClause) if argClause.values.size == 1 =>
      val bodyJs = argClause.values.head match
        case Term.Function.After_4_6_0(_, body) =>
          genGeneratorBody(body.asInstanceOf[Term])
        case other => s"function*() { return ${genExpr(other.asInstanceOf[Term])}; }"
      s"_makeGenerator($bodyJs)"
    case Term.Apply.After_4_6_0(Term.Name("suspend" | "emit"), argClause)
        if argClause.values.size == 1 =>
      s"(yield ${genExpr(argClause.values.head.asInstanceOf[Term])})"
    // v1.51.2 Streams inside CPS body
    case Term.Apply.After_4_6_0(
        Term.ApplyType.After_4_6_0(Term.Name("stream"), _) | Term.Name("stream"),
        argClause) if argClause.values.size == 1 =>
      val bodyJs = extractStreamBody(argClause.values.head.asInstanceOf[Term])
      s"_makeAsyncStream(($bodyJs)())"

    // Nested computed / effect inside CPS body — same wrapping as the
    // non-CPS form: by-name body becomes a zero-arg thunk.
    case Term.Apply.After_4_6_0(Term.Name(react @ ("computed" | "effect")), bodyArgClause)
        if bodyArgClause.values.size == 1 =>
      s"$react(() => ${genCpsExpr(bodyArgClause.values.head.asInstanceOf[Term])})"

    // Apply — function or method call
    case app: Term.Apply =>
      genCpsApply(app)

    // Infix — bind both sides, then apply op
    case Term.ApplyInfix.After_4_6_0(lhs, op, _, argClause) =>
      val rhsTerms = argClause.values.collect { case t: Term => t }
      val rhs: Term = rhsTerms match
        case List(single) => single
        case multi        => Term.Tuple(multi.map(_.asInstanceOf[scala.meta.Term]))
      bindArgsCps(List(lhs, rhs)) { case List(vl, vr) =>
        op.value match
          case "::"           => s"[$vl, ...$vr]"
          case ":+"           => s"[...$vl, $vr]"
          case "+:"           => s"[$vl, ...$vr]"
          case "++" | ":::"   => s"_tupleConcat($vl, $vr)"
          case "!"            => s"Actor.send($vl, $vr)"
          case "->"           => s"Object.assign([$vl, $vr], {_isTuple: true})"
          case "&&"           => s"($vl && $vr)"
          case "||"           => s"($vl || $vr)"
          case "to"           => s"_dispatch($vl, 'to', [$vr])"
          case "until"        => s"_dispatch($vl, 'until', [$vr])"
          // v1-js-long-precision-and-bitops: mirror the non-CPS ApplyInfix path
          // (JsGen.scala) — any arithmetic/comparison that isn't provably plain-Int
          // on both sides routes through the BigInt/Decimal-aware `_arith` (a native
          // JS `+` throws on BigInt+Number). Without this, a Long accumulator in an
          // effectful fold (`foldLeft(0L)((acc, x) => acc + x)` under `handle`) emitted
          // a raw `+` and crashed with "Cannot mix BigInt and other types" at runtime.
          // (js-effect-multishot-in-while-loop.)
          case "+" | "-" | "*" | "/" | "%" | "<" | ">" | "<=" | ">=" | "==" | "!="
              if isLongExpr(lhs) || isLongExpr(rhs) =>
            s"_arith('${op.value}', $vl, $vr)"
          case "*" =>
            if isIntExpr(lhs) && isIntExpr(rhs) then s"($vl * $vr)"
            else if isNumericExpr(lhs) && isNumericExpr(rhs) then s"($vl * $vr)"
            else s"(typeof ($vl) === 'string' ? ($vl).repeat($vr) : _arith('*', $vl, $vr))"
          case "==" if isNumericExpr(lhs) && isNumericExpr(rhs) => s"($vl === $vr)"
          case "!=" if isNumericExpr(lhs) && isNumericExpr(rhs) => s"($vl !== $vr)"
          case "=="           => s"_arith('==', $vl, $vr)"
          case "!="           => s"_arith('!=', $vl, $vr)"
          case "/" if isIntExpr(lhs) && isIntExpr(rhs) => s"Math.trunc($vl / $vr)"
          case "+" | "-" | "/" | "%" | "<" | ">" | "<=" | ">="
              if !(isIntExpr(lhs) && isIntExpr(rhs)) =>
            if isNumericExpr(lhs) && isNumericExpr(rhs) then s"($vl ${op.value} $vr)"
            else s"_arith('${op.value}', $vl, $vr)"
          case "&" | "|" | "^" | "<<" | ">>" | ">>>" => s"_bit('${op.value}', $vl, $vr)"
          // Symbolic user infix operator (`~>`, `<~`, …) — a method call in ssc;
          // dispatch instead of emitting a raw JS operator (mirrors the non-CPS
          // ApplyInfix path). Native arithmetic/comparison ops are excluded so
          // their raw both-Int fast path is preserved. (js-symbolic-infix-op.)
          case other if !JsGen.nativeInfixOps.contains(other) &&
                        other.exists(c => !(c.isLetterOrDigit || c == '_' || c == '$')) =>
            s"_dispatch($vl, '$other', [$vr])"
          case other          => s"($vl $other $vr)"
        case _ => "/* infix arity mismatch */"
      }

    // Select — bind qual, dispatch
    case Term.Select(qual, name) =>
      bindArgsCps(List(qual)) { case List(q) =>
        s"_dispatch($q, '${name.value}', [])"
        case _ => "/* select arity */"
      }

    // Match — bind scrutinee, then dispatch cases
    case t: Term.Match =>
      val scrutVar = freshTmp()
      val casesJs = t.casesBlock.cases.map(c => genCpsCase(scrutVar, c)).mkString(" else ")
      bindArgsCps(List(t.expr)) { case List(sv) =>
        s"(($scrutVar => { $casesJs else { throw new Error('Match failure: ' + _show($scrutVar)); } })($sv))"
        case _ => "/* match arity */"
      }

    // For-yield in CPS — fall back: the rhs collections / generators don't typically
    // perform effects, so direct codegen with bind on result suffices for now.
    case t: Term.ForYield => genForYield(t.enumsBlock.enums, t.body)
    // `for i <- (lo until/to hi) do body` with a `perform` in the body: desugar the Range generator
    // to an index `let` + the same while-trampoline so the body's effects thread through `_bind`.
    // `genForDo`'s `_forEach` runs the body via NON-CPS `genExpr`, so `acc + Eff.op()` would be
    // `acc + <Computation>` (NaN / "[object Object]"). (effect-perform-in-fordo.)
    case t: Term.For if jsRangeForDo(t).isDefined =>
      val (iName, lo, hi, inclusive) = jsRangeForDo(t).get
      val cmp = if inclusive then "<=" else "<"
      val wn  = freshTmp()
      s"(() => { let $iName = ${genExpr(lo)}; const $wn = () => ($iName $cmp ${genExpr(hi)}) ? " +
        s"_bind(${genCpsExpr(t.body)}, () => { $iName = $iName + 1; return $wn(); }) : undefined; return $wn(); })()"
    // `for x <- coll do body` over a non-Range pure collection (a JS array, e.g. a List): iterate by
    // index + the same trampoline so the body's effects thread through `_bind`. (effect-perform-in-fordo.)
    case t: Term.For if jsCollForDo(t).isDefined =>
      val (iName, coll) = jsCollForDo(t).get
      val arr = freshTmp(); val ix = freshTmp(); val wn = freshTmp()
      s"(() => { const $arr = ${genExpr(coll)}; let $ix = 0; const $wn = () => { if ($ix >= $arr.length) return undefined; " +
        s"const $iName = $arr[$ix]; $ix = $ix + 1; return _bind(${genCpsExpr(t.body)}, () => $wn()); }; return $wn(); })()"
    case t: Term.For      => genForDo(t.enumsBlock.enums, t.body)

    // Assign — thread the rhs (so a `perform` in it runs) then mutate the target.
    // JS arrow-function params (the CPS `var` bindings) are mutable, so `x = …`
    // works directly. Without this the assign falls to `genExpr` and the perform
    // is emitted as a raw `_dispatch` whose Computation result is never run.
    case Term.Assign(lhs, rhs) =>
      val v = freshTmp()
      s"_bind(${genCpsExpr(rhs)}, $v => { ${genExpr(lhs)} = $v; return undefined; })"

    // While — lower to a trampolined recursive helper so a `perform` in the body
    // threads through `_bind` (lazy on a Computation → the runner trampolines it
    // without growing the JS stack). See specs/effect-cps-loops.md.
    case t: Term.While =>
      val wn = freshTmp()
      s"(() => { const $wn = () => (${genExpr(t.cond)}) ? _bind(${genCpsExpr(t.body)}, () => $wn()) : undefined; return $wn(); })()"

    // Return
    case Term.Return(expr) => genCpsExpr(expr)

    // Default: try direct codegen (covers values, partial functions, etc.)
    case other => genExpr(other)

  /** Call site in CPS mode: bind args, then call. Handles effect ops specially. */
  private def genCpsApply(app: Term.Apply): String =
    val args = app.argClause.values
    app.fun match
      // Effect op: Eff.op(args) → _bind args then _perform
      case Term.Select(Term.Name(eff), Term.Name(op)) if isEffectOpRef(eff, op) =>
        bindArgsCps(args) { vs =>
          s"_perform('$eff', '$op', [${vs.mkString(", ")}])"
        }

      // Builtin constructors (with or without explicit type args)
      case Term.Name("Map") | Term.ApplyType.After_4_6_0(Term.Name("Map"), _) =>
        bindArgsCps(args) { vs => s"_Map(${vs.mkString(", ")})" }
      case Term.Name("List") | Term.ApplyType.After_4_6_0(Term.Name("List"), _) =>
        bindArgsCps(args) { vs => s"[${vs.mkString(", ")}]" }
      case Term.Name("Some") | Term.Name("_Some") =>
        bindArgsCps(args) { vs => s"_Some(${vs.mkString(", ")})" }
      case Term.Name("assert") =>
        bindArgsCps(args) { vs => s"assert(${vs.mkString(", ")})" }

      // foldLeft curried: bind qual + init + f
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldLeft")), initArgClause) =>
        bindArgsCps(qual :: initArgClause.values ++ args) { vs =>
          val q = vs.head; val init = vs(1); val f = vs(2)
          s"_seqFoldLeft($q, $init, $f)"
        }

      // foldRight curried
      case Term.Apply.After_4_6_0(Term.Select(qual, Term.Name("foldRight")), initArgClause) =>
        bindArgsCps(qual :: initArgClause.values ++ args) { vs =>
          val q = vs.head; val init = vs(1); val f = vs(2)
          s"(($q).reduceRight((acc, x) => ($f)(x, acc), $init))"
        }

      // Method call: obj.method(args) → _dispatch (or direct call for known singletons)
      case Term.Select(qual, Term.Name(method)) =>
        // Known stdlib singleton: emit direct call bypassing _dispatch array alloc.
        if qual.isInstanceOf[Term.Name] &&
           JsGen.stdlibDirectCall.contains((qual.asInstanceOf[Term.Name].value, method)) then
          val recv = qual.asInstanceOf[Term.Name].value
          bindArgsCps(args) { vs => s"$recv.$method(${vs.mkString(", ")})" }
        else
          bindArgsCps(qual :: args) { vs =>
            s"_dispatch(${vs.head}, '$method', [${vs.tail.mkString(", ")}])"
          }

      // RuntimeCall intrinsic (e.g. `nowMillis` → `Date.now`): apply the same
      // rewrite `genExpr`/`dispatchIntrinsicJs` does for Term.Apply sites, but
      // bind args CPS-style first so an effectful arg still threads through the
      // handler. Without this the CPS path emits the bare source name
      // (`nowMillis()`), which is undefined in a standalone `emit-js` bundle.
      case Term.Name(fname) if intrinsicRuntimeTarget(fname).isDefined =>
        val target = intrinsicRuntimeTarget(fname).get
        bindArgsCps(args) { vs => s"$target(${vs.mkString(", ")})" }

      // Regular function call: bind args, then call (function value itself is simple)
      case fun =>
        if isSimpleCpsExpr(fun) then
          bindArgsCps(args) { vs =>
            s"${genExpr(fun)}(${vs.mkString(", ")})"
          }
        else
          bindArgsCps(fun :: args) { vs =>
            s"${vs.head}(${vs.tail.mkString(", ")})"
          }

  /** Block as IIFE in CPS form — chains statements through _bind. */
  private[codegen] def genCpsBlockAsIife(stats: List[Stat]): String =
    if stats.isEmpty then "undefined"
    else
      def build(remaining: List[Stat]): String = remaining match
        case Nil => "undefined"
        case List(s) =>
          s match
            case t: Term => genCpsExpr(t)
            case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
              // Last statement is a binding — block evaluates to undefined.
              // Still bind it so its effects (if any) run.
              s"_bind(${genCpsExpr(rhs)}, ${localBindingName(n.value)} => undefined)"
            case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
              s"_bind(${genCpsExpr(rhs)}, ${localBindingName(n.value)} => undefined)"
            case stat =>
              s"(() => { ${genStatInline(stat)} return undefined; })()"
        case s :: rest =>
          s match
            case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
              val restJs = withLocalBindings(List(n.value))(build(rest))
              s"_bind(${genCpsExpr(rhs)}, ${localBindingName(n.value)} => $restJs)"
            case Defn.Val(_, List(pat), _, rhs) =>
              val patJs = genPatDestructure(pat)
              val patNames = patternNames(pat)
              val tmp = freshTmp()
              val restJs = withLocalBindings(patNames)(build(rest))
              s"_bind(${genCpsExpr(rhs)}, $tmp => { const $patJs = $tmp; return $restJs; })"
            case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
              // For simplicity, treat var like val in CPS context.
              val restJs = withLocalBindings(List(n.value))(build(rest))
              s"_bind(${genCpsExpr(rhs)}, ${localBindingName(n.value)} => $restJs)"
            case d: Defn.Def =>
              // Function definition in block — emit as nested function declaration
              val fnJs = genCpsInlineFn(d)
              s"((${d.name.value}) => ${build(rest)})($fnJs)"
            case t: Term =>
              if isSimpleCpsExpr(t) then s"(${genExpr(t)}, ${build(rest)})"
              else s"_bind(${genCpsExpr(t)}, _ => ${build(rest)})"
            case stat =>
              s"(() => { ${genStatInline(stat)} return ${build(rest)}; })()"
      build(stats)

  /** Emit a function definition as an inline function value in CPS form. */
  private def genCpsInlineFn(d: Defn.Def): String =
    val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
    val paramsStr = params.map(localBindingName).mkString(", ")
    d.body match
      case Term.Block(stats) => s"(${paramsStr}) => ${withLocalBindings(params)(genCpsBlockAsIife(stats))}"
      case expr              => s"(${paramsStr}) => ${withLocalBindings(params)(genCpsExpr(expr))}"

  /** CPS case generator — like genCase but the body is CPS. */
  private def genCpsCase(scrutVar: String, c: Case): String =
    val (cond, bindings) = genPattern(scrutVar, c.pat)
    val localNames = bindings.map(_._1)
    val bindingStmts = localBindingStmts(bindings)
    val bodyJs = withLocalBindings(localNames)(genCpsExpr(c.body))
    c.cond match
      case Some(guard) =>
        val guardExpr = withLocalBindings(localNames)(genExpr(guard))
        val condStr = if cond == "true" then s"($guardExpr)" else s"($cond) && ($guardExpr)"
        s"if ($condStr) { $bindingStmts return $bodyJs; }"
      case None =>
        val condStr = if cond == "true" then "true" else s"($cond)"
        s"if ($condStr) { $bindingStmts return $bodyJs; }"

  private[codegen] def genCase(scrutVar: String, c: Case): String =
    val (cond, bindings) = genPattern(scrutVar, c.pat)
    val localNames = bindings.map(_._1)
    val bindingStmts = localBindingStmts(bindings)
    val bodyJs = withLocalBindings(localNames)(genExpr(c.body))

    // Pattern guard: we need bindings set up before evaluating the guard.
    // We use a nested IIFE to set up bindings, evaluate guard, then run body.
    c.cond match
      case Some(guard) if bindings.nonEmpty =>
        // Put bindings and guard inside the if block
        val guardExpr = withLocalBindings(localNames)(genExpr(guard))
        val patCond = if cond == "true" then "" else s"($cond) && "
        s"if (${patCond}(() => { $bindingStmts return $guardExpr; })()) { $bindingStmts return $bodyJs; }"
      case Some(guard) =>
        val guardExpr = withLocalBindings(localNames)(genExpr(guard))
        val condStr = if cond == "true" then s"($guardExpr)" else s"($cond) && ($guardExpr)"
        s"if ($condStr) { return $bodyJs; }"
      case None =>
        val condStr = if cond == "true" then "true" else s"($cond)"
        s"if ($condStr) { $bindingStmts return $bodyJs; }"

  /** Returns (condition JS, list of (varName, expr) bindings).
   *  The condition must be true for the pattern to match.
   *  Bindings are set up when condition is true.
   */
  private[codegen] def genPattern(scrutVar: String, pat: Pat): (String, List[(String, String)]) = pat match
    case Pat.Wildcard() =>
      ("true", Nil)

    case Pat.Var(n) =>
      ("true", List(n.value -> scrutVar))

    case lit: Lit =>
      val litJs = lit match
        case Lit.Int(v)     => v.toString
        case Lit.Long(v)    => v.toString
        case Lit.Double(v)  => v.toString
        case Lit.String(v)  => "\"" + v.replace("\"", "\\\"") + "\""
        case Lit.Boolean(v) => v.toString
        case Lit.Null()     => "null"
        case _              => "undefined"
      (s"$scrutVar === $litJs", Nil)

    case Pat.Typed(inner, tpe) =>
      // Emit a type-test guard for union-type narrowing: `case s: String =>`.
      // Map the declared type name to a JS typeof / instanceof check.
      val typeName = tpe match
        case Type.Name(n)   => n
        case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n; case _ => "" }
        case _              => ""
      val typeCond = typeName match
        case "String"  => s"(typeof $scrutVar === 'string')"
        case "Int" | "Long" | "Double" | "Float" | "Number" =>
          s"(typeof $scrutVar === 'number')"
        case "Boolean" => s"(typeof $scrutVar === 'boolean')"
        case "RuntimeException" | "Exception" | "Throwable" =>
          s"($scrutVar instanceof Error || ($scrutVar && $scrutVar._type === '$typeName'))"
        case ""        => "true"    // unknown type — fall through
        case _ =>
          caseClassTagMap.get(typeName) match
            case Some(tag) => s"($scrutVar && $scrutVar._tag === $tag)"
            case None      =>
              // Supertype (sealed trait / parent enum / abstract class): an instance
              // carries only its own leaf `_type`, so an exact `_type === 'TkNode'`
              // check never matches a subtype. Widen to the closure of concrete
              // descendants when known (the JS analogue of the interp/JIT supertype
              // type-test fix); fall back to exact-name for an unknown type.
              subtypeClosure.get(typeName) match
                case Some(subs) if subs.nonEmpty =>
                  val names = (subs + typeName).toList.sorted
                  val ors   = names.map(n => s"$scrutVar._type === '$n'").mkString(" || ")
                  s"($scrutVar && ($ors))"
                case _ => s"($scrutVar && $scrutVar._type === '$typeName')"
      val (innerCond, bindings) = genPattern(scrutVar, inner)
      val cond =
        if typeCond == "true" then innerCond
        else if innerCond == "true" then typeCond
        else s"$typeCond && $innerCond"
      (cond, bindings)

    case Pat.Tuple(pats) =>
      val subConditions = pats.zipWithIndex.map { (p, i) =>
        genPattern(s"$scrutVar[$i]", p)
      }
      val cond = subConditions.map(_._1).filter(_ != "true").mkString(" && ")
      val bindings = subConditions.flatMap(_._2)
      (if cond.isEmpty then "true" else cond, bindings)

    // `h :: t` cons infix pattern — identical shape to Cons(h, t): a non-empty
    // list (JS array or Cons DataV). genPattern lacked this case, so any `::`
    // pattern — a plain `case h :: t =>` and especially one nested inside a Tuple
    // pattern like `case (ah :: at, bh :: _) =>` — fell through to the "true"
    // default, emitting no structural test and binding neither head nor tail.
    // (js-cons-infix-pattern.)
    case Pat.ExtractInfix.After_4_6_0(headPat, Term.Name("::"), tailClause)
        if tailClause.values.length == 1 =>
      val isArray = s"(Array.isArray($scrutVar) && $scrutVar.length > 0)"
      val isData  = s"($scrutVar && $scrutVar._type === 'Cons')"
      val head = s"(Array.isArray($scrutVar) ? $scrutVar[0] : Object.values($scrutVar).slice(1)[0])"
      val tail = s"(Array.isArray($scrutVar) ? $scrutVar.slice(1) : Object.values($scrutVar).slice(1)[1])"
      val subConds  = List(genPattern(head, headPat), genPattern(tail, tailClause.values.head))
      val subCond   = subConds.map(_._1).filter(_ != "true").mkString(" && ")
      val bindings  = subConds.flatMap(_._2)
      val cond = s"($isArray || $isData)" + (if subCond.nonEmpty then s" && $subCond" else "")
      (cond, bindings)

    case Pat.Extract.After_4_6_0(fn, argClause) =>
      val typeName = fn match
        case Term.Name(n)                 => n
        case Term.Select(_, Term.Name(n)) => n
        case _                            => "?"
      val args = argClause.values

      typeName match
        case "Cons" if args.length == 2 =>
          val isArray = s"(Array.isArray($scrutVar) && $scrutVar.length > 0)"
          val isData = s"($scrutVar && $scrutVar._type === 'Cons')"
          val head = s"(Array.isArray($scrutVar) ? $scrutVar[0] : Object.values($scrutVar).slice(1)[0])"
          val tail = s"(Array.isArray($scrutVar) ? $scrutVar.slice(1) : Object.values($scrutVar).slice(1)[1])"
          val subConds = List(genPattern(head, args.head), genPattern(tail, args(1)))
          val subCond = subConds.map(_._1).filter(_ != "true").mkString(" && ")
          val bindings = subConds.flatMap(_._2)
          val cond = s"($isArray || $isData)" + (if subCond.nonEmpty then s" && $subCond" else "")
          (cond, bindings)

        case "Some" =>
          val innerScrutVar = s"$scrutVar.value"
          val subConds = if args.isEmpty then Nil
            else args.zipWithIndex.map { (p, i) =>
              genPattern(if args.length == 1 then innerScrutVar else s"$innerScrutVar[$i]", p)
            }
          val subCond = subConds.map(_._1).filter(_ != "true").mkString(" && ")
          val bindings = subConds.flatMap(_._2)
          val cond = s"($scrutVar && $scrutVar._type === '_Some')" +
            (if subCond.nonEmpty then s" && $subCond" else "")
          (cond, bindings)

        case "None" =>
          (s"($scrutVar && $scrutVar._type === '_None')", Nil)

        case _ =>
          // Case class or enum case extract — use field names when available
          val knownFields = caseClassFieldsByType.get(typeName)
          val fieldTypes  = caseClassFieldTypeMap.get(typeName).getOrElse(Map.empty)
          val fields = args.zipWithIndex.map { (p, i) =>
            val fieldName = knownFields.flatMap(_.lift(i))
            val accessor = fieldName match
              case Some(fname) => s"$scrutVar.$fname"
              case None        => s"Object.values($scrutVar).slice(1)[$i]"
            // Track Double/Float-typed bound vars for direct JS arithmetic
            p match
              case Pat.Var(nm) =>
                fieldName.foreach { fname =>
                  fieldTypes.get(fname) match
                    case Some("Double" | "Float") => numericVars += nm.value
                    case _ => ()
                }
              case _ => ()
            genPattern(accessor, p)
          }
          val typeCond = caseClassTagMap.get(typeName) match
            case Some(tag) => s"($scrutVar && $scrutVar._tag === $tag)"
            case None      => s"($scrutVar && $scrutVar._type === '$typeName')"
          val subCond = fields.map(_._1).filter(_ != "true").mkString(" && ")
          val bindings = fields.flatMap(_._2)
          val cond = typeCond + (if subCond.nonEmpty then s" && $subCond" else "")
          (cond, bindings)

    case Pat.Alternative(lhs, rhs) =>
      // Either alternative matches, no bindings from alternatives typically
      val (lCond, _) = genPattern(scrutVar, lhs)
      val (rCond, _) = genPattern(scrutVar, rhs)
      (s"($lCond || $rCond)", Nil)

    // @ binder: `xs @ pattern` — bind `xs` to the whole scrutinee, then match `pattern`
    case Pat.Bind(lhs: Pat.Var, rhs) =>
      val (cond, bindings) = genPattern(scrutVar, rhs)
      (cond, (lhs.name.value -> scrutVar) :: bindings)

    // Enum singleton reference: case Red => or case Color.Red =>
    case t: Term.Name =>
      t.value match
        case "None" => (s"($scrutVar && $scrutVar._type === '_None')", Nil)
        case "Nil"  =>
          (s"((Array.isArray($scrutVar) && $scrutVar.length === 0) || $scrutVar === Nil || ($scrutVar && $scrutVar._type === 'Nil'))", Nil)
        case n      => (s"($scrutVar === $n || ($scrutVar && $scrutVar._type === '$n'))", Nil)

    case Term.Select(qual, Term.Name(n)) =>
      val qualJs = qual match
        case Term.Name(q) => s"$q.$n"
        case _            => n
      if n == "None" then (s"($scrutVar && $scrutVar._type === '_None')", Nil)
      else (s"($scrutVar === $qualJs || ($scrutVar && $scrutVar._type === '$n'))", Nil)

    case _ =>
      ("true", Nil)

  /** Recognise a single-generator `for i <- (lo until/to hi) do …` over an integer Range.
   *  Returns `(loopVarName, lo, hi, inclusive)`; `None` otherwise. (effect-perform-in-fordo.) */
  private def jsRangeForDo(t: Term.For): Option[(String, Term, Term, Boolean)] =
    t.enumsBlock.enums match
      case List(g: Enumerator.Generator) =>
        (g.pat, g.rhs) match
          case (scala.meta.Pat.Var(Term.Name(iName)),
                Term.ApplyInfix.After_4_6_0(lo, Term.Name(op), _, ac))
              if (op == "until" || op == "to") && ac.values.lengthCompare(1) == 0 =>
            Some((iName, lo, ac.values.head.asInstanceOf[Term], op == "to"))
          case _ => None
      case _ => None

  /** `for x <- coll do …` where `x` is a plain var and `coll` is a pure non-Range collection.
   *  Returns `(loopVarName, coll)`. (effect-perform-in-fordo.) */
  private def jsCollForDo(t: Term.For): Option[(String, Term)] =
    if jsRangeForDo(t).isDefined then None
    else t.enumsBlock.enums match
      case List(g: Enumerator.Generator) =>
        g.pat match
          case scala.meta.Pat.Var(Term.Name(iName)) if !jsForTermPerforms(g.rhs) => Some((iName, g.rhs))
          case _ => None
      case _ => None

  private[codegen] def jsForTermPerforms(t: scala.meta.Tree): Boolean = t match
    case Term.Select(Term.Name(eff), Term.Name(op)) if isEffectOpRef(eff, op)         => true
    case Term.Apply.After_4_6_0(Term.Name(n), _) if isEffectfulFun(n)                 => true
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name(n)), _) if isEffectfulFun(n) => true
    case _                                                                            => t.children.exists(jsForTermPerforms)

  private[codegen] def genForDo(enums: List[Enumerator], body: Term): String =
    if enumeratorsNeedAsyncFor(enums) then genAsyncForDo(enums, body)
    else genForDoHelper(enums, genExpr(body))

  private def genForDoHelper(enums: List[Enumerator], bodyJs: String): String = enums match
    case Nil => s"(() => { $bodyJs; })()"
    case Enumerator.Generator(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { _forEach($rhsJs, ($iterVar) => { $patJs $inner; }); })()"
    case Enumerator.Guard(cond) :: rest =>
      val condJs = genExpr(cond)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { if ($condJs) { $inner; } })()"
    case Enumerator.Val(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val v = freshTmp()
      val patJs = genForPatBinding(pat, v)
      val inner = genForDoHelper(rest, bodyJs)
      s"(() => { const $v = $rhsJs; $patJs $inner; })()"
    case _ :: rest => genForDoHelper(rest, bodyJs)

  private[codegen] def genForYield(enums: List[Enumerator], body: Term): String =
    if enumeratorsNeedAsyncFor(enums) then genAsyncForYield(enums, body)
    else genForYieldHelper(enums, genExpr(body))

  private def genForYieldHelper(enums: List[Enumerator], bodyJs: String): String = enums match
    case Nil => bodyJs
    case Enumerator.Generator(pat, rhs) :: Nil =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      if patJs.isEmpty then
        s"_dispatch($rhsJs, 'map', [($iterVar) => $bodyJs])"
      else
        s"_dispatch($rhsJs, 'map', [($iterVar) => { $patJs return $bodyJs; }])"
    case Enumerator.Generator(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val iterVar = freshTmp()
      val patJs = genForPatBinding(pat, iterVar)
      val inner = genForYieldHelper(rest, bodyJs)
      if patJs.isEmpty then
        s"_dispatch($rhsJs, 'flatMap', [($iterVar) => $inner])"
      else
        s"_dispatch($rhsJs, 'flatMap', [($iterVar) => { $patJs return $inner; }])"
    case Enumerator.Guard(cond) :: rest =>
      val condJs = genExpr(cond)
      val inner = genForYieldHelper(rest, bodyJs)
      // wrap in filter; but we need the generator context
      // For guard as first enum (unusual), filter is not trivially accessible
      // Return inner filtered - but we don't have a collection here, use conditional
      s"($condJs ? [$inner] : [])"
    case Enumerator.Val(pat, rhs) :: rest =>
      val rhsJs = genExpr(rhs)
      val v = freshTmp()
      val patJs = genForPatBinding(pat, v)
      val inner = genForYieldHelper(rest, bodyJs)
      s"(() => { const $v = $rhsJs; $patJs return $inner; })()"
    case _ :: rest => genForYieldHelper(rest, bodyJs)

  // Async for-yield: all generators use awaitClient → sequential awaits in async IIFE.
  // Returns a Promise; wrap with awaitClient(...) at the call site to get the value.
  private def genAsyncForYield(enums: List[Enumerator], body: Term): String =
    val stmts = scala.collection.mutable.ListBuffer[String]()
    for e <- enums do e match
      case Enumerator.Generator(pat, Term.Apply.After_4_6_0(Term.Name("awaitClient"), argClause))
          if argClause.values.size == 1 =>
        val promiseJs = genExpr(argClause.values.head.asInstanceOf[Term])
        val iterVar = freshTmp()
        val patJs = genForPatBinding(pat, iterVar)
        stmts += (if patJs.isEmpty then s"const $iterVar = await $promiseJs;"
                  else s"const $iterVar = await $promiseJs; $patJs")
      case Enumerator.Generator(pat, rhs) =>
        val rhsJs = genExpr(rhs)
        val iterVar = freshTmp()
        val patJs = genForPatBinding(pat, iterVar)
        stmts += (if patJs.isEmpty then s"const $iterVar = $rhsJs;"
                  else s"const $iterVar = $rhsJs; $patJs")
      case Enumerator.Guard(cond) =>
        stmts += s"if (!(${genExpr(cond)})) return undefined;"
      case Enumerator.Val(pat, rhs) =>
        val rhsJs = genExpr(rhs)
        val v = freshTmp()
        val patJs = genForPatBinding(pat, v)
        stmts += (if patJs.isEmpty then s"const $v = $rhsJs;"
                  else s"const $v = $rhsJs; $patJs")
      case _ => ()
    val bodyJs = genExpr(body)
    s"(async () => { ${stmts.mkString(" ")} return $bodyJs; })()"

  // Async for-do: all generators use awaitClient → sequential awaits in async IIFE.
  private def genAsyncForDo(enums: List[Enumerator], body: Term): String =
    val stmts = scala.collection.mutable.ListBuffer[String]()
    for e <- enums do e match
      case Enumerator.Generator(pat, Term.Apply.After_4_6_0(Term.Name("awaitClient"), argClause))
          if argClause.values.size == 1 =>
        val promiseJs = genExpr(argClause.values.head.asInstanceOf[Term])
        val iterVar = freshTmp()
        val patJs = genForPatBinding(pat, iterVar)
        stmts += (if patJs.isEmpty then s"const $iterVar = await $promiseJs;"
                  else s"const $iterVar = await $promiseJs; $patJs")
      case Enumerator.Generator(pat, rhs) =>
        val rhsJs = genExpr(rhs)
        val iterVar = freshTmp()
        val patJs = genForPatBinding(pat, iterVar)
        stmts += (if patJs.isEmpty then s"const $iterVar = $rhsJs;"
                  else s"const $iterVar = $rhsJs; $patJs")
      case Enumerator.Guard(cond) =>
        stmts += s"if (!(${genExpr(cond)})) return;"
      case Enumerator.Val(pat, rhs) =>
        val rhsJs = genExpr(rhs)
        val v = freshTmp()
        val patJs = genForPatBinding(pat, v)
        stmts += (if patJs.isEmpty then s"const $v = $rhsJs;"
                  else s"const $v = $rhsJs; $patJs")
      case _ => ()
    val bodyJs = genExpr(body)
    s"(async () => { ${stmts.mkString(" ")} $bodyJs; })()"
