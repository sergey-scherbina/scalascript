package scalascript.interpreter

import scala.meta.*
import Computation.Pure

/** Call helpers: callValue, callFun (with TCO + factory stubs + defaults),
 *  callValueNamed (named-arg reordering), typeToString, isThrowsType,
 *  constValueOfType, applyDefaults, throwsAutoWrap.
 */
private[interpreter] object CallRuntime:

  def callValue(fn: Value, args: List[Value], env: Env, interp: Interpreter): Computation = fn match
    case f: Value.FunV      => callFun(f, args, interp)
    case f: Value.NativeFnV => f.f(args)
    case Value.InstanceV(_, fields) =>
      fields.get("apply") match
        case Some(f) => callValue(f, args, env, interp)
        case None    => interp.located(s"Instance is not callable")
    case _: Value.ListV | _: Value.MapV => DispatchRuntime.dispatch(fn, "apply", args, env, interp)
    case _ => interp.located(s"Not callable: ${Value.show(fn)}")

  def callValueNamed(fn: Value, namedArgs: List[(Option[String], Value)], env: Env, interp: Interpreter): Computation =
    fn match
      case f: Value.FunV =>
        val paramSet = f.params.toSet
        namedArgs.foreach {
          case (Some(n), _) if !paramSet.contains(n) =>
            interp.located(s"Unknown argument name '$n' for function '${if f.name.nonEmpty then f.name else "<anon>"}' (parameters: ${f.params.mkString(", ")})")
          case _ => ()
        }
        val slots = Array.fill[Option[Value]](f.params.length)(None)
        namedArgs.foreach {
          case (Some(n), v) =>
            val idx = f.params.indexOf(n)
            if idx >= 0 then slots(idx) = Some(v)
          case _ => ()
        }
        val positionals = namedArgs.collect { case (None, v) => v }
        val posIter = positionals.iterator
        // Index of the last regular (non-using) param; that's where vararg would live.
        val lastRegularIdx = f.params.length - 1 - f.usingParams.length
        val lastRegularIsVararg =
          lastRegularIdx >= 0 &&
          f.paramTypes.lift(lastRegularIdx).exists(_.endsWith("*"))
        for i <- slots.indices do
          if slots(i).isEmpty && posIter.hasNext then
            if lastRegularIsVararg && i == lastRegularIdx then
              // Collect all remaining positionals into a ListV for the vararg param.
              val varargVals = (Iterator.single(posIter.next()) ++ posIter).toList
              slots(i) = Some(Value.ListV(varargVals))
            else
              slots(i) = Some(posIter.next())
        val selfEntry = if f.name.nonEmpty then Map(f.name -> f) else Map.empty
        var baseEnv2  = f.closure ++ selfEntry
        val orderedArr = Array.fill[Value](f.params.length)(Value.UnitV)
        var partialFrom = -1  // first index with no value and no default
        for i <- f.params.indices do
          slots(i) match
            case Some(v) =>
              orderedArr(i) = v
              baseEnv2 = baseEnv2 + (f.params(i) -> v)
            case None =>
              val defaultOpt = f.defaults.lift(i).flatten
              defaultOpt match
                case Some(defaultTerm) =>
                  val v = Computation.run(interp.eval(defaultTerm, baseEnv2))
                  orderedArr(i) = v
                  baseEnv2 = baseEnv2 + (f.params(i) -> v)
                case None =>
                  if partialFrom < 0 then partialFrom = i
        if partialFrom >= 0 && namedArgs.nonEmpty then
          // Some required params are unsatisfied — return a partial closure so that
          // curried call sites like `f(a)(b, c)` work when `f` is stored flattened.
          val partialEnv = f.closure ++ f.params.take(partialFrom).zip(orderedArr.take(partialFrom)).toMap
          Pure(Value.FunV(
            f.params.drop(partialFrom),
            f.body,
            partialEnv,
            f.name,
            f.defaults.drop(partialFrom),
            f.paramTypes.drop(partialFrom),
            f.usingParams,
            f.returnsThrows
          ))
        else
          // If the vararg slot was never filled, truncate the args so callFun sees
          // only the filled positions and appends an empty ListV for the vararg.
          val filledCount =
            if lastRegularIsVararg && slots(lastRegularIdx).isEmpty
            then lastRegularIdx
            else f.params.length
          callFun(f, orderedArr.take(filledCount).toList, interp)
      case f: Value.NativeFnV => f.f(namedArgs.map(_._2))
      case Value.InstanceV(_, fields) =>
        fields.get("apply") match
          case Some(f) => callValueNamed(f, namedArgs, env, interp)
          case None    => interp.located(s"Instance is not callable")
      case _: Value.ListV | _: Value.MapV => DispatchRuntime.dispatch(fn, "apply", namedArgs.map(_._2), env, interp)
      case _ => interp.located(s"Not callable: ${Value.show(fn)}")

  def callFun(f: Value.FunV, args: List[Value], interp: Interpreter): Computation =
    val tupledArgs = args match
      case List(Value.TupleV(elems))
        if f.params.length > 1 && elems.length == f.params.length =>
        elems
      case _ => args
    val selfEntry = if f.name.nonEmpty then Map(f.name -> f) else Map.empty
    // True when the last regular parameter is varargs (type ends with "*").
    val lastIsVararg = f.params.nonEmpty &&
      f.paramTypes.lift(f.params.length - 1 - f.usingParams.length).exists(_.endsWith("*"))
    def packVarargs(rawArgs: List[Value]): List[Value] =
      if !lastIsVararg || rawArgs.length < f.params.length - 1 then rawArgs
      else
        // Pack surplus args into a ListV for the vararg slot (empty list when zero extras).
        val regularCount = f.params.length - 1
        rawArgs.take(regularCount) :+ Value.ListV(rawArgs.drop(regularCount))
    def resolveFactoryStubs(args: List[Value]): List[Value] =
      if f.usingParams.isEmpty || interp.givenFactories.isEmpty then args
      else
        val regularCount = f.params.length - f.usingParams.length
        val regularVals  = args.take(regularCount)
        args.zipWithIndex.map { case (v, i) =>
          if i >= regularCount then
            v match
              case Value.InstanceV(_, fs) if fs.contains("__factory__") =>
                val usingIdx = i - regularCount
                val typeKey  = f.usingParams.lift(usingIdx).map(_._2).getOrElse("")
                if typeKey.nonEmpty then
                  GivenRuntime.resolveGiven(typeKey, regularVals, f.closure, interp).getOrElse(v)
                else v
              case _ => v
          else v
        }
    // If the call provides fewer positional args than the function has params, and some of the
    // missing params are required (no default), return a partial closure so that curried calls
    // like `f(a)(b, c)` work when the def is stored with all param lists flattened.
    if tupledArgs.nonEmpty && tupledArgs.length < f.params.length && f.usingParams.isEmpty then
      // Fill as many defaults as we can; stop at the first required (no-default) param.
      var env2 = f.closure ++ selfEntry ++ f.params.take(tupledArgs.length).zip(tupledArgs).toMap
      var partialStart = -1
      val filled = scala.collection.mutable.ListBuffer.empty[Value]
      for i <- (tupledArgs.length until f.params.length) do
        if partialStart < 0 then
          f.defaults.lift(i).flatten match
            case Some(defaultTerm) =>
              val v = Computation.run(interp.eval(defaultTerm, env2))
              filled += v
              env2 = env2 + (f.params(i) -> v)
            case None =>
              partialStart = i
      if partialStart >= 0 then
        return Pure(Value.FunV(
          f.params.drop(partialStart),
          f.body,
          env2,
          f.name,
          f.defaults.drop(partialStart),
          f.paramTypes.drop(partialStart),
          f.usingParams,
          f.returnsThrows
        ))
    val effArgs =
      if lastIsVararg && tupledArgs.length == f.params.length - 1 then
        resolveFactoryStubs(packVarargs(tupledArgs))
      else if tupledArgs.length >= f.params.length then resolveFactoryStubs(packVarargs(tupledArgs))
      else if f.usingParams.nonEmpty then
        val regularCount = f.params.length - f.usingParams.length
        val withUsing =
          if tupledArgs.length >= regularCount then
            val regularArgVals = tupledArgs.take(regularCount)
            val resolved = f.usingParams.map { (pname, typeKey) =>
              GivenRuntime.resolveGiven(typeKey, regularArgVals, f.closure, interp)
                .getOrElse(interp.located(s"No given instance found for '$typeKey' (using parameter '$pname')"))
            }
            tupledArgs ++ resolved
          else
            tupledArgs
        if withUsing.length >= f.params.length then withUsing
        else applyDefaults(f.params, f.defaults, withUsing, f.closure ++ selfEntry, interp)
      else
        applyDefaults(f.params, f.defaults, tupledArgs, f.closure ++ selfEntry, interp)
    val info      = TcoRuntime.tcoInfoFor(f, interp)
    val hasMutualTail = info.tailTargets.nonEmpty && info.tailTargets.exists { n =>
      (interp.globals.get(n) orElse f.closure.get(n)).exists(_.isInstanceOf[Value.FunV])
    }
    if info.noNonTailSelf && (info.isSelfTailRec || hasMutualTail) then
      if Profiler.enabled && f.name.nonEmpty then
        Profiler.record(f.name, 0L)
      TcoRuntime.tcoTrampoline(f, effArgs, null, interp)
    else
      val withSelf = interp.closureWithSelfFor(f)
      val callEnv: Env = f.params match
        case Nil               => withSelf
        case p :: Nil          => FrameMap.one(p, effArgs.head, withSelf)
        case p1 :: p2 :: Nil   => FrameMap.two(p1, effArgs.head, p2, effArgs(1), withSelf)
        case ps                =>
          val names = ps.toArray
          val arr   = effArgs.iterator.take(names.length).toArray
          FrameMap.of(names, arr, withSelf)
      val frameName  = if f.name.nonEmpty then f.name else "<anon>"
      val relLine    = interp.currentSpan.map(_._1 + 1).getOrElse(0)
      val absDocLine = interp.debugBlockDocLine + relLine
      interp.callStack += ((frameName, interp.debugSourceFile, absDocLine))
      val t0 = if Profiler.enabled && f.name.nonEmpty then System.nanoTime() else 0L
      val result =
        try TcoRuntime.runUntilSuspension(interp.eval(f.body, callEnv))
        catch case r: ReturnSignal => Pure(r.value)
      if Profiler.enabled && f.name.nonEmpty then
        Profiler.record(f.name, System.nanoTime() - t0)
      if interp.callStack.nonEmpty then interp.callStack.remove(interp.callStack.length - 1)
      if f.returnsThrows then result.map(throwsAutoWrap)
      else result

  private def throwsAutoWrap(v: Value): Value = v match
    case Value.InstanceV("Left",  _) => v
    case Value.InstanceV("Right", _) => v
    case other => Value.InstanceV("Right", Map("value" -> other))

  def typeToString(t: scala.meta.Type): String = t match
    case scala.meta.Type.Name(n)         => n
    case ta: scala.meta.Type.Apply       => s"${typeToString(ta.tpe)}[${ta.argClause.values.map(typeToString).mkString(", ")}]"
    case scala.meta.Type.Function.After_4_6_0(params, r) => s"(${params.values.map(typeToString).mkString(", ")}) => ${typeToString(r)}"
    case scala.meta.Type.Tuple(ts)       => ts.map(typeToString).mkString("(", ", ", ")")
    case ti: scala.meta.Type.ApplyInfix  => s"${typeToString(ti.lhs)} ${ti.op.value} ${typeToString(ti.rhs)}"
    case tr: scala.meta.Type.Repeated    => s"${typeToString(tr.tpe)}*"
    case _                               => "_"

  def constValueOfType(tp: scala.meta.Type): Value = tp match
    case scala.meta.Lit.Int(n)      => Value.intV(n.toLong)
    case scala.meta.Lit.String(s)   => Value.StringV(s)
    case scala.meta.Lit.Boolean(b)  => Value.boolV(b)
    case scala.meta.Lit.Double(d)   => Value.doubleV(d.toDouble)
    case scala.meta.Lit.Long(l)     => Value.intV(l)
    case scala.meta.Type.Name(n)    => Value.StringV(n)
    case _                          => Value.StringV(tp.syntax)

  def isThrowsType(t: scala.meta.Type): Boolean = t match
    case ti: scala.meta.Type.ApplyInfix => ti.op.value == "throws"
    case _                              => false

  def applyDefaults(
    params:   List[String],
    defaults: List[Option[Term]],
    args:     List[Value],
    baseEnv:  Env,
    interp:   Interpreter
  ): List[Value] =
    if args.length >= params.length then args
    else
      val provided = args
      var env = baseEnv ++ params.zip(provided).toMap
      val filled = (provided.length until params.length).map { i =>
        val pname      = params(i)
        val defaultOpt = defaults.lift(i).flatten
        defaultOpt match
          case Some(defaultTerm) =>
            val v = Computation.run(interp.eval(defaultTerm, env))
            env = env + (pname -> v)
            v
          case None =>
            interp.located(s"missing argument for parameter '$pname'")
      }.toList
      provided ++ filled
