package scalascript.interpreter

import scala.meta.*
import Computation.Pure

/** Pattern matching, for-comprehension evaluation, and collection iteration helpers. */
private[interpreter] object PatternRuntime:

  def matchPat(pat: Pat, scrutinee: Value, env: Env, interp: Interpreter): Option[Env] = pat match
    case Pat.Wildcard()  => Some(env)
    case Pat.Var(name)   => Some(FrameMap.one(name.value, scrutinee, env))
    case lit: Lit =>
      val litV: Value = lit match
        case Lit.Int(v)     => Value.intV(v.toLong)
        case Lit.Long(v)    => Value.intV(v)
        case Lit.String(v)  => Value.StringV(v)
        case Lit.Boolean(v) => Value.boolV(v)
        case Lit.Double(v)  => Value.doubleV(v.toString.toDouble)
        case Lit.Null()     => Value.NullV
        case _              => Value.NullV
      Option.when(litV == scrutinee)(env)
    case Pat.Tuple(pats) =>
      scrutinee match
        case Value.TupleV(elems) if elems.length == pats.length =>
          pats.zip(elems).foldLeft(Option(env)) { (acc, pe) =>
            acc.flatMap(e => matchPat(pe._1, pe._2, e, interp))
          }
        case _ => None
    case Pat.Extract.After_4_6_0(fn, argClause) =>
      val typeNameOpt = fn match
        case Term.Name(n)                 => Some(n)
        case Term.Select(_, Term.Name(n)) => Some(n)
        case _                            => None
      typeNameOpt.flatMap { typeName =>
        val args = argClause.values
        scrutinee match
          case Value.InstanceV(t, fields) if t == typeName =>
            val order     = interp.typeFieldOrder.getOrElse(t, fields.keys.toList)
            val fieldVals = order.flatMap(fields.get)
            if args.length != fieldVals.length then None
            else args.zip(fieldVals).foldLeft(Option(env)) { (acc, pe) =>
              acc.flatMap(e => matchPat(pe._1, pe._2, e, interp))
            }
          case Value.OptionV(Some(v)) if typeName == "Some" && args.length == 1 =>
            matchPat(args.head, v, env, interp)
          case Value.OptionV(None) if typeName == "None" && args.isEmpty =>
            Some(env)
          case _ => None
      }
    // List cons pattern: `head :: tail` matches a non-empty ListV.
    case Pat.ExtractInfix.After_4_6_0(headPat, Term.Name("::"), tailClause) =>
      scrutinee match
        case Value.ListV(h :: t) if tailClause.values.length == 1 =>
          matchPat(headPat, h, env, interp).flatMap(e =>
            matchPat(tailClause.values.head, Value.ListV(t), e, interp))
        case _ => None
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
              var p = interp.parentTypes.get(t); var ok = false
              while p.isDefined && !ok do { ok = p.get == typeName; p = interp.parentTypes.get(p.get) }
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
        if matches then matchPat(inner, scrutinee, env, interp) else None
    case Pat.Alternative(lhs, rhs) =>
      List(lhs, rhs).iterator.flatMap(p => matchPat(p, scrutinee, env, interp)).nextOption()
    // @ binder: `xs @ pattern` — bind `xs` to the whole scrutinee, then match `pattern`
    case Pat.Bind(lhs: Pat.Var, rhs) =>
      matchPat(rhs, scrutinee, env, interp).map(e => e + (lhs.name.value -> scrutinee))
    case t: Term.Name =>
      env.get(t.value).orElse(interp.globals.get(t.value))
        .flatMap(v => Option.when(v == scrutinee)(env))
    case Term.Select(_, Term.Name(n)) =>
      env.get(n).orElse(interp.globals.get(n))
        .flatMap(v => Option.when(v == scrutinee)(env))
    case _ => None

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
    case Value.OptionV(Some(v)) => List(v)
    case Value.OptionV(None)    => Nil
    case _ => interp.located(s"Cannot iterate over ${Value.show(v)}")

  def evalForYield(enums: List[Enumerator], body: Term, env: Env, interp: Interpreter): Computation =
    enums match
      case Nil => interp.eval(body, env)
      case Enumerator.Generator(pat, rhs) :: rest =>
        interp.eval(rhs, env).flatMap { rhsV =>
          val items = evalCollection(rhsV, interp)
          val isLast = rest.isEmpty
          val branches = items.flatMap { item =>
            matchPat(pat, item, env, interp).map(patEnv => evalForYield(rest, body, patEnv, interp))
          }
          Computation.sequence(branches).map {
            case Value.ListV(results) if isLast =>
              Value.ListV(results)
            case Value.ListV(results) =>
              Value.ListV(results.flatMap {
                case Value.ListV(ls) => ls
                case v               => List(v)
              })
            case other => other
          }
        }
      case Enumerator.Guard(cond) :: rest =>
        interp.eval(cond, env).flatMap {
          case Value.BoolV(true) => evalForYield(rest, body, env, interp)
          case _                 => Pure(Value.ListV(Nil))
        }
      case Enumerator.Val(pat, rhs) :: rest =>
        interp.eval(rhs, env).flatMap { v =>
          matchPat(pat, v, env, interp) match
            case Some(patEnv) => evalForYield(rest, body, patEnv, interp)
            case None         => Pure(Value.ListV(Nil))
        }
      case _ :: rest => evalForYield(rest, body, env, interp)

  // evalForDo keeps loop vars separate from globals so assignments to outer vars are visible.
  def evalForDo(enums: List[Enumerator], body: Term, outerEnv: Env, loopVars: Env, interp: Interpreter): Computation =
    val env = outerEnv ++ interp.globals.toMap ++ loopVars
    enums match
      case Nil => interp.eval(body, env).map(_ => Value.UnitV)
      case Enumerator.Generator(pat, rhs) :: rest =>
        interp.eval(rhs, env).flatMap { rhsV =>
          val items = evalCollection(rhsV, interp)
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil => Pure(Value.UnitV)
            case item :: tail =>
              matchPat(pat, item, env, interp) match
                case Some(patEnv) =>
                  val newVars = patVarNames(pat).map(k => k -> patEnv(k)).toMap
                  evalForDo(rest, body, outerEnv, loopVars ++ newVars, interp).flatMap(_ => loop(tail))
                case None => loop(tail)
          loop(items)
        }
      case Enumerator.Guard(cond) :: rest =>
        interp.eval(cond, env).flatMap {
          case Value.BoolV(true) => evalForDo(rest, body, outerEnv, loopVars, interp)
          case _                 => Pure(Value.UnitV)
        }
      case Enumerator.Val(pat, rhs) :: rest =>
        interp.eval(rhs, env).flatMap { v =>
          matchPat(pat, v, env, interp) match
            case Some(patEnv) =>
              val newVars = patVarNames(pat).map(k => k -> patEnv(k)).toMap
              evalForDo(rest, body, outerEnv, loopVars ++ newVars, interp)
            case None => Pure(Value.UnitV)
        }
      case _ :: rest => evalForDo(rest, body, outerEnv, loopVars, interp)
