package scalascript.interpreter

import scala.meta.*
import Computation.FlatMap

/** Pattern matching, for-comprehension evaluation, and collection iteration helpers. */
private[interpreter] object PatternRuntime:

  /** Compiled form of a single `Term.Match` case list.
   *  Each handler returns a `Computation` on success or `null` on no-match.
   *  The while-loop avoids `Option` allocations in the hot dispatch path. */
  final class CompiledMatch(
    private val handlers: Array[(Value, Env) => Computation | Null]
  ):
    def run(scrutV: Value, env: Env, interp: Interpreter): Computation =
      var i = 0
      while i < handlers.length do
        val r = handlers(i)(scrutV, env)
        if r != null then return r.asInstanceOf[Computation]
        i += 1
      interp.located(s"Match failure: ${Value.show(scrutV)}")

  /** Compile a Term.Match into a cached handler array.
   *  Called once per match expression (keyed by AST node identity).
   *  Falls back to the generic matchPat for complex patterns. */
  def compileMatch(t: scala.meta.Term.Match, interp: Interpreter): CompiledMatch =
    val handlers = t.casesBlock.cases.map(compileCase(_, interp)).toArray
    new CompiledMatch(handlers)

  /** Compile a Term.PartialFunction into a cached handler array (same machinery). */
  def compilePF(t: scala.meta.Term.PartialFunction, interp: Interpreter): CompiledMatch =
    val handlers = t.cases.map(compileCase(_, interp)).toArray
    new CompiledMatch(handlers)

  private def evalGuard(cond: Option[Term], env: Env, interp: Interpreter): Boolean =
    cond match
      case None    => true
      case Some(g) =>
        Computation.run(interp.eval(g, env)) match
          case Value.BoolV(b) => b
          case _              => false

  private def compileLit(lit: Lit): Value = lit match
    case Lit.Int(v)     => Value.intV(v.toLong)
    case Lit.Long(v)    => Value.intV(v)
    case Lit.String(v)  => Value.StringV(v)
    case Lit.Boolean(v) => Value.boolV(v)
    case Lit.Double(v)  => Value.doubleV(v.toString.toDouble)
    case Lit.Null()     => Value.NullV
    case _              => Value.NullV

  /** Build the pattern env from precomputed field order and binding names.
   *  Returns null if any required field is missing from `fields`. */
  private def buildPatEnv(
    fo:        Array[String],
    bindNames: Array[String | Null],
    fields:    Map[String, Value],
    env:       Env
  ): Env | Null =
    val n = fo.length
    n match
      case 0 => env
      case 1 =>
        val bname = bindNames(0)
        if bname == null then env
        else
          val v = fields.getOrElse(fo(0), null)
          if v == null then null else FrameMap.one(bname, v, env)
      case 2 =>
        val v0 = fields.getOrElse(fo(0), null)
        val v1 = fields.getOrElse(fo(1), null)
        if v0 == null || v1 == null then null
        else
          val b0 = bindNames(0)
          val b1 = bindNames(1)
          if b0 == null && b1 == null then env
          else if b0 == null then FrameMap.one(b1, v1, env)
          else if b1 == null then FrameMap.one(b0, v0, env)
          else FrameMap.two(b0, v0, b1, v1, env)
      case _ =>
        // General case: collect only non-wildcard bindings
        var bindCount = 0
        var i = 0
        while i < n do
          if bindNames(i) != null then bindCount += 1
          i += 1
        if bindCount == 0 then env
        else
          val names = new Array[String](bindCount)
          val vals  = new Array[Value](bindCount)
          var j = 0
          i = 0
          while i < n do
            val bname = bindNames(i)
            if bname != null then
              val v = fields.getOrElse(fo(i), null)
              if v == null then return null
              names(j) = bname
              vals(j)  = v
              j += 1
            i += 1
          FrameMap.of(names, vals, env)

  /** Compile a single Case into a fast handler.
   *  Simple patterns (Extract with Var/Wildcard args, Wildcard, Var, Lit) get
   *  fast-path compilation.  Complex patterns fall back to `matchPat`. */
  private def compileCase(c: Case, interp: Interpreter): (Value, Env) => Computation | Null =
    c.pat match

      case Pat.Wildcard() =>
        if c.cond.isEmpty then
          (_, env) => interp.eval(c.body, env)
        else
          (_, env) =>
            if evalGuard(c.cond, env, interp) then interp.eval(c.body, env)
            else null

      case Pat.Var(n) =>
        val name = n.value
        if c.cond.isEmpty then
          (scrutV, env) =>
            val patEnv = FrameMap.one(name, scrutV, env)
            interp.eval(c.body, patEnv)
        else
          (scrutV, env) =>
            val patEnv = FrameMap.one(name, scrutV, env)
            if evalGuard(c.cond, patEnv, interp) then interp.eval(c.body, patEnv)
            else null

      case lit: Lit =>
        val litV = compileLit(lit)
        if c.cond.isEmpty then
          (scrutV, env) =>
            if scrutV == litV then interp.eval(c.body, env) else null
        else
          (scrutV, env) =>
            if scrutV == litV && evalGuard(c.cond, env, interp) then interp.eval(c.body, env)
            else null

      case Pat.Extract.After_4_6_0(fn, argClause) =>
        val typeName: String | Null = fn match
          case Term.Name(n)                 => n
          case Term.Select(_, Term.Name(n)) => n
          case _                            => null
        val argPats = argClause.values.toArray
        val allSimple = argPats.forall(p => p.isInstanceOf[Pat.Var] || p.isInstanceOf[Pat.Wildcard])
        if typeName != null && allSimple then
          // Precompute binding names (null = wildcard, skip binding)
          val bindNames: Array[String | Null] = argPats.map {
            case Pat.Var(nn) => nn.value
            case _           => null
          }
          // Lazily populated field order on first successful match
          var fieldOrderCache: Array[String] = null
          val tn = typeName  // capture for closure
          val noGuard = c.cond.isEmpty
          (scrutV, env) =>
            scrutV match
              case Value.OptionV(Some(v)) if tn == "Some" && bindNames.length == 1 =>
                val bname = bindNames(0)
                val patEnv = if bname == null then env else FrameMap.one(bname, v, env)
                if noGuard || evalGuard(c.cond, patEnv, interp) then interp.eval(c.body, patEnv)
                else null
              case Value.NoneV if tn == "None" && bindNames.isEmpty =>
                if noGuard || evalGuard(c.cond, env, interp) then interp.eval(c.body, env)
                else null
              case Value.InstanceV(t, fields) if t == tn =>
                if fieldOrderCache == null then
                  fieldOrderCache = interp.typeFieldOrder
                    .getOrElse(tn, fields.keys.toList)
                    .toArray
                val fo = fieldOrderCache
                if bindNames.length != fo.length then null
                else
                  val patEnv = buildPatEnv(fo, bindNames, fields, env)
                  if patEnv == null then null
                  else if noGuard || evalGuard(c.cond, patEnv, interp) then interp.eval(c.body, patEnv)
                  else null
              case _ => null
        else
          fallbackCase(c, interp)

      case Pat.Alternative(lhs, rhs) =>
        // Compile each alternative; return first match
        val lhsH = compileCase(c.copy(pat = lhs), interp)
        val rhsH = compileCase(c.copy(pat = rhs), interp)
        (scrutV, env) =>
          val r = lhsH(scrutV, env)
          if r != null then r else rhsH(scrutV, env)

      case _ => fallbackCase(c, interp)

  private def fallbackCase(c: Case, interp: Interpreter): (Value, Env) => Computation | Null =
    if c.cond.isEmpty then
      (scrutV, env) =>
        val patEnv = matchPat(c.pat, scrutV, env, interp)
        if patEnv != null then interp.eval(c.body, patEnv) else null
    else
      (scrutV, env) =>
        val patEnv = matchPat(c.pat, scrutV, env, interp)
        if patEnv != null && evalGuard(c.cond, patEnv, interp) then interp.eval(c.body, patEnv)
        else null

  /** Match pattern against scrutinee. Returns the extended env on success, null on failure.
   *  Uses null instead of Option to avoid allocating Some wrappers on the hot match path. */
  def matchPat(pat: Pat, scrutinee: Value, env: Env, interp: Interpreter): Env | Null = pat match
    case Pat.Wildcard()  => env
    case Pat.Var(name)   => FrameMap.one(name.value, scrutinee, env)
    case lit: Lit =>
      val litV: Value = lit match
        case Lit.Int(v)     => Value.intV(v.toLong)
        case Lit.Long(v)    => Value.intV(v)
        case Lit.String(v)  => Value.StringV(v)
        case Lit.Boolean(v) => Value.boolV(v)
        case Lit.Double(v)  => Value.doubleV(v.toString.toDouble)
        case Lit.Null()     => Value.NullV
        case _              => Value.NullV
      if litV == scrutinee then env else null
    case Pat.Tuple(pats) =>
      scrutinee match
        case Value.TupleV(elems) if elems.length == pats.length =>
          var curEnv: Env | Null = env; var ps = pats; var es = elems
          while curEnv != null && ps.nonEmpty do
            curEnv = matchPat(ps.head, es.head, curEnv.asInstanceOf[Env], interp)
            ps = ps.tail; es = es.tail
          curEnv
        case _ => null
    case Pat.Extract.After_4_6_0(fn, argClause) =>
      val typeName: String | Null = fn match
        case Term.Name(n)                 => n
        case Term.Select(_, Term.Name(n)) => n
        case _                            => null
      if typeName == null then null
      else
        val args = argClause.values
        scrutinee match
          case Value.InstanceV(t, fields) if t == typeName =>
            val order = interp.typeFieldOrder.getOrElse(t, fields.keys.toList)
            if args.length != order.length then null
            else
              var curEnv: Env | Null = env; var as = args; var os = order
              while curEnv != null && as.nonEmpty do
                val fv = fields.getOrElse(os.head, null)
                curEnv = if fv == null then null
                         else matchPat(as.head, fv, curEnv.asInstanceOf[Env], interp)
                as = as.tail; os = os.tail
              curEnv
          case Value.OptionV(Some(v)) if typeName == "Some" && args.length == 1 =>
            matchPat(args.head, v, env, interp)
          case Value.NoneV if typeName == "None" && args.isEmpty =>
            env
          case _ => null
    // List cons pattern: `head :: tail` matches a non-empty ListV.
    case Pat.ExtractInfix.After_4_6_0(headPat, Term.Name("::"), tailClause) =>
      scrutinee match
        case Value.ListV(h :: t) if tailClause.values.length == 1 =>
          val e = matchPat(headPat, h, env, interp)
          if e != null then matchPat(tailClause.values.head, Value.ListV(t), e.asInstanceOf[Env], interp) else null
        case _ => null
    case Pat.Typed(inner, tpe) =>
      val typeName = tpe match
        case Type.Name(n)   => n
        case ta: Type.Apply => ta.tpe match { case Type.Name(n) => n; case _ => "" }
        case _              => ""
      if typeName.isEmpty then matchPat(inner, scrutinee, env, interp)
      else
        val matches = scrutinee match
          case Value.InstanceV(t, _) =>
            t == typeName || {
              var p = interp.parentTypes.getOrElse(t, null); var ok = false
              while p != null && !ok do { ok = p == typeName; p = interp.parentTypes.getOrElse(p, null) }
              ok
            }
          // IntV represents both Int and Long (stored as JVM Long internally).
          case _: Value.IntV    => typeName == "Int" || typeName == "Long"
          case _: Value.DoubleV => typeName == "Double" || typeName == "Float" || typeName == "Number"
          case _: Value.StringV => typeName == "String"
          case _: Value.BoolV   => typeName == "Boolean"
          case _: Value.CharV   => typeName == "Char"
          case _: Value.ListV   => typeName == "List"
          case _: Value.OptionV => typeName == "Option"
          case _: Value.MapV    => typeName == "Map"
          case _                => false
        if matches then matchPat(inner, scrutinee, env, interp) else null
    case Pat.Alternative(lhs, rhs) =>
      val l = matchPat(lhs, scrutinee, env, interp)
      if l != null then l else matchPat(rhs, scrutinee, env, interp)
    // @ binder: `xs @ pattern` — bind `xs` to the whole scrutinee, then match `pattern`
    case Pat.Bind(lhs: Pat.Var, rhs) =>
      val e = matchPat(rhs, scrutinee, env, interp)
      if e != null then e.asInstanceOf[Env] + (lhs.name.value -> scrutinee) else null
    case t: Term.Name =>
      val v = env.getOrElse(t.value, interp.globals.getOrElse(t.value, null))
      if v != null && v == scrutinee then env else null
    case Term.Select(_, Term.Name(n)) =>
      val v = env.getOrElse(n, interp.globals.getOrElse(n, null))
      if v != null && v == scrutinee then env else null
    case _ => null

  def patVarNames(pat: Pat): Set[String] = pat match
    case Pat.Var(n)           => Set(n.value)
    case Pat.Wildcard()       => Set.empty
    case Pat.Tuple(pats)      => pats.flatMap(patVarNames).toSet
    case Pat.Extract.After_4_6_0(_, argClause) => argClause.values.flatMap(patVarNames).toSet
    case Pat.Typed(inner, _)  => patVarNames(inner)
    case Pat.Bind(lhs: Pat.Var, rhs) => patVarNames(rhs) + lhs.name.value
    case _                    => Set.empty

  def evalCollection(v: Value, interp: Interpreter): List[Value] = v match
    case Value.ListV(ls)        => ls
    case Value.OptionV(Some(v)) => v :: Nil
    case Value.NoneV    => Nil
    case _ => interp.located(s"Cannot iterate over ${Value.show(v)}")

  def evalForYield(enums: List[Enumerator], body: Term, env: Env, interp: Interpreter): Computation =
    enums match
      case Nil => interp.eval(body, env)
      // Fast path: `for x <- items yield expr` — avoids N Option/Some allocations and
      // intermediate List[Computation] from the general branches+sequence approach.
      case Enumerator.Generator(scala.meta.Pat.Var(vn), rhs) :: Nil =>
        val varName = vn.value
        interp.eval(rhs, env).flatMap { rhsV =>
          Computation.mapSequence(evalCollection(rhsV, interp),
            item => interp.eval(body, FrameMap.one(varName, item, env)))
        }
      case Enumerator.Generator(pat, rhs) :: rest =>
        interp.eval(rhs, env).flatMap { rhsV =>
          val items = evalCollection(rhsV, interp)
          val isLast = rest.isEmpty
          val branches = items.flatMap { item =>
            val patEnv = matchPat(pat, item, env, interp)
            if patEnv == null then Nil else evalForYield(rest, body, patEnv, interp) :: Nil
          }
          Computation.sequence(branches).map {
            case Value.ListV(results) if isLast =>
              Value.ListV(results)
            case Value.ListV(results) =>
              Value.ListV(results.flatMap {
                case Value.ListV(ls) => ls
                case v               => v :: Nil
              })
            case other => other
          }
        }
      case Enumerator.Guard(cond) :: rest =>
        interp.eval(cond, env).flatMap {
          case Value.BoolV(true) => evalForYield(rest, body, env, interp)
          case _                 => Computation.PureEmptyList
        }
      case Enumerator.Val(pat, rhs) :: rest =>
        interp.eval(rhs, env).flatMap { v =>
          val patEnv = matchPat(pat, v, env, interp)
          if patEnv == null then Computation.PureEmptyList
          else evalForYield(rest, body, patEnv, interp)
        }
      case _ :: rest => evalForYield(rest, body, env, interp)

  // evalForDo keeps loop vars separate so assignments to outer vars are visible.
  // `interp.eval` already checks interp.globals as fallback for Term.Name lookups,
  // so we do not need to merge globals into env here.
  def evalForDo(enums: List[Enumerator], body: Term, outerEnv: Env, loopVars: Env, interp: Interpreter): Computation =
    val env = if loopVars.isEmpty then outerEnv else outerEnv ++ loopVars
    enums match
      case Nil => interp.eval(body, env).flatMap(Computation.discardToUnit)
      // Fast path: `for x <- items do body` (single Pat.Var generator, no guards).
      // Avoids matchPat Option/Some, patVarNames Set, and loopVars Map per iteration.
      case Enumerator.Generator(scala.meta.Pat.Var(vn), rhs) :: Nil =>
        val varName = vn.value
        FlatMap(interp.eval(rhs, env), { rhsV =>
          val items = evalCollection(rhsV, interp)
          def forDoLoop(remaining: List[Value]): Computation = remaining match
            case Nil => Computation.PureUnit
            case item :: tail =>
              FlatMap(interp.eval(body, FrameMap.one(varName, item, env)),
                _ => forDoLoop(tail))
          forDoLoop(items)
        })
      // Fast path: single generator — avoids patVarNames Set + newVars Map + recursive evalForDo per item.
      // patEnv from matchPat already extends `env` with the pattern bindings, so it's
      // equivalent to the outerEnv ++ loopVars ++ newVars that the general path would build.
      case Enumerator.Generator(pat, rhs) :: Nil =>
        FlatMap(interp.eval(rhs, env), { rhsV =>
          val items = evalCollection(rhsV, interp)
          def forDoLoop(remaining: List[Value]): Computation = remaining match
            case Nil => Computation.PureUnit
            case item :: tail =>
              val patEnv = matchPat(pat, item, env, interp)
              if patEnv == null then forDoLoop(tail)
              else FlatMap(interp.eval(body, patEnv), _ => forDoLoop(tail))
          forDoLoop(items)
        })
      case Enumerator.Generator(pat, rhs) :: rest =>
        FlatMap(interp.eval(rhs, env), { rhsV =>
          val items = evalCollection(rhsV, interp)
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil => Computation.PureUnit
            case item :: tail =>
              val patEnv = matchPat(pat, item, env, interp)
              if patEnv == null then loop(tail)
              else
                val newVars = patVarNames(pat).map(k => k -> patEnv(k)).toMap
                FlatMap(evalForDo(rest, body, outerEnv, loopVars ++ newVars, interp), _ => loop(tail))
          loop(items)
        })
      case Enumerator.Guard(cond) :: rest =>
        interp.eval(cond, env).flatMap {
          case Value.BoolV(true) => evalForDo(rest, body, outerEnv, loopVars, interp)
          case _                 => Computation.PureUnit
        }
      case Enumerator.Val(pat, rhs) :: rest =>
        interp.eval(rhs, env).flatMap { v =>
          val patEnv = matchPat(pat, v, env, interp)
          if patEnv == null then Computation.PureUnit
          else
            val newVars = patVarNames(pat).map(k => k -> patEnv(k)).toMap
            evalForDo(rest, body, outerEnv, loopVars ++ newVars, interp)
        }
      case _ :: rest => evalForDo(rest, body, outerEnv, loopVars, interp)
