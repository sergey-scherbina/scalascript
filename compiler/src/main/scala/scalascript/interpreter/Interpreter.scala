package scalascript.interpreter

import scalascript.ast.*
import scala.collection.mutable
import scala.meta.*

/** Tree-walking interpreter for ScalaScript documents.
 *
 *  Execution model:
 *  - Sections are processed in document order.
 *  - Each scala/ssc code block is executed eagerly (defs bind, exprs run).
 *  - After all sections, `main()` is auto-called if defined and not already invoked.
 */
class Interpreter:
  private val globals = mutable.Map.empty[String, Value]
  private var mainCalled = false

  // ─── Public API ──────────────────────────────────────────────────

  def run(module: Module): Unit =
    initBuiltins()
    module.sections.foreach(runSection)
    if !mainCalled then
      globals.get("main").foreach {
        case f: Value.FunV if f.params.isEmpty => callFun(f, Nil); mainCalled = true
        case _ => ()
      }

  // ─── Built-ins ───────────────────────────────────────────────────

  private def initBuiltins(): Unit =
    def native(name: String)(f: List[Value] => Value): Unit =
      globals(name) = Value.NativeFnV(name, f)

    native("println") { args => println(args.map(Value.show).mkString(" ")); Value.UnitV }
    native("print")   { args => print(args.map(Value.show).mkString(" "));   Value.UnitV }
    native("assert") {
      case List(Value.BoolV(true))             => Value.UnitV
      case List(Value.BoolV(false))            => throw InterpretError("Assertion failed")
      case List(Value.BoolV(true),  _)         => Value.UnitV
      case List(Value.BoolV(false), msg)       => throw InterpretError(s"Assertion failed: ${Value.show(msg)}")
      case _                                   => Value.UnitV
    }
    native("require") {
      case List(Value.BoolV(true))        => Value.UnitV
      case List(Value.BoolV(false), msg)  => throw InterpretError(s"Requirement failed: ${Value.show(msg)}")
      case _                              => Value.UnitV
    }
    native("Some")  { case List(v) => Value.OptionV(Some(v)) }
    native("List")  { args => Value.ListV(args) }
    native("Map")   { args => Value.InstanceV("Map", Map.empty) }   // stub
    globals("None") = Value.OptionV(None)

    // math pseudo-module
    native("math.sqrt")  { case List(Value.DoubleV(d)) => Value.DoubleV(math.sqrt(d))
                           case List(Value.IntV(n))    => Value.DoubleV(math.sqrt(n.toDouble)) }
    native("math.abs")   { case List(Value.DoubleV(d)) => Value.DoubleV(math.abs(d))
                           case List(Value.IntV(n))    => Value.IntV(math.abs(n)) }
    native("math.pow")   { case List(Value.DoubleV(a), Value.DoubleV(b)) => Value.DoubleV(math.pow(a, b))
                           case List(Value.IntV(a), Value.DoubleV(b))    => Value.DoubleV(math.pow(a.toDouble, b)) }
    native("math.floor") { case List(Value.DoubleV(d)) => Value.DoubleV(math.floor(d)) }
    native("math.ceil")  { case List(Value.DoubleV(d)) => Value.DoubleV(math.ceil(d)) }
    native("math.round") { case List(Value.DoubleV(d)) => Value.IntV(math.round(d)) }
    globals("math.Pi")   = Value.DoubleV(math.Pi)
    globals("math.E")    = Value.DoubleV(math.E)

  // ─── Section / block execution ───────────────────────────────────

  private def runSection(section: Section): Unit =
    section.content.foreach {
      case cb: Content.CodeBlock if cb.lang == "scala" || cb.lang == "ssc" =>
        cb.tree.foreach(execBlock)
      case _ => ()
    }
    section.subsections.foreach(runSection)

  private def execBlock(node: ScalaNode): Unit =
    ScalaNode.fold(node) {
      case Source(stats) => stats.foreach(s => execStat(s, globals))
      case other         => throw InterpretError(s"Expected Source, got ${other.productPrefix}")
    }

  // ─── Statement execution ─────────────────────────────────────────

  private def execStat(stat: Stat, env: mutable.Map[String, Value]): Unit = stat match
    case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
      env(n.value) = eval(rhs, env.toMap)

    case Defn.Var(_, List(Pat.Var(n)), _, Some(rhs)) =>
      env(n.value) = eval(rhs, env.toMap)

    case d: Defn.Def =>
      // paramClauseGroups is the Scala 3 API in scalameta 4.8+
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
      env(d.name.value) = Value.FunV(params, d.body, env.toMap)
      if d.name.value == "main" && params.isEmpty then mainCalled = false // reset so auto-call works

    case d: Defn.Object =>
      // register object name as a unit instance (fields populated by members)
      env(d.name.value) = Value.InstanceV(d.name.value, Map.empty)

    case d: Defn.Class =>
      // register a constructor native function
      val params = d.ctor.paramClauses.flatMap(_.values)
      val paramNames = params.map(_.name.value)
      val typeName = d.name.value
      env(typeName) = Value.NativeFnV(typeName,
        args => Value.InstanceV(typeName, paramNames.zip(args).toMap)
      )

    case t: Term =>
      val result = eval(t, env.toMap)
      // if main() was called explicitly, mark it so we don't double-call
      t match
        case Term.Apply(Term.Name("main"), _) => mainCalled = true
        case _                                => ()
      result

    case _ => () // type aliases, imports, etc.

  // ─── Expression evaluation ───────────────────────────────────────

  private def eval(term: Term, env: Env): Value = term match
    // Literals
    case Lit.Int(v)     => Value.IntV(v.toLong)
    case Lit.Long(v)    => Value.IntV(v)
    case Lit.Double(v)  => Value.DoubleV(v.toString.toDouble)
    case Lit.Float(v)   => Value.DoubleV(v.toString.toDouble)
    case Lit.String(v)  => Value.StringV(v)
    case Lit.Boolean(v) => Value.BoolV(v)
    case Lit.Char(v)    => Value.CharV(v)
    case Lit.Unit()     => Value.UnitV
    case Lit.Null()     => Value.NullV

    // Name lookup: local env first, then globals
    case Term.Name(name) =>
      env.getOrElse(name, globals.getOrElse(name,
        throw InterpretError(s"Undefined: $name")))

    // Function application: detect obj.method(args) and dispatch directly
    case app: Term.Apply =>
      val argVals = app.argClause.values.map(eval(_, env))
      app.fun match
        case Term.Select(qual, Term.Name(method)) =>
          val qualV = eval(qual, env)
          dispatch(qualV, method, argVals, env)
        case fun =>
          callValue(eval(fun, env), argVals, env)

    // Infix operators: a op b
    case Term.ApplyInfix(lhs, op, _, args) =>
      val lhsV = eval(lhs, env)
      val argVs = args.map(eval(_, env))
      infix(lhsV, op.value, argVs, env)

    // Field / method selection: a.b
    case Term.Select(qual, name) =>
      val qualV = eval(qual, env)
      dispatch(qualV, name.value, Nil, env)

    // Block { stmts; expr }
    case Term.Block(stats) =>
      val local = mutable.Map.from(env)
      var result: Value = Value.UnitV
      for s <- stats do s match
        case t: Term => result = eval(t, local.toMap)
        case stat    => execStat(stat, local)
      result

    // if/then/else
    case t: Term.If =>
      if eval(t.cond, env).asInstanceOf[Value.BoolV].v then eval(t.thenp, env) else eval(t.elsep, env)

    // String interpolation s"... ${expr} ..."
    case Term.Interpolate(Term.Name(prefix), parts, args) if prefix == "s" || prefix == "f" =>
      val sb = StringBuilder()
      for i <- parts.indices do
        sb ++= parts(i).asInstanceOf[Lit.String].value
        if i < args.length then sb ++= Value.show(eval(args(i).asInstanceOf[Term], env))
      Value.StringV(sb.toString)

    // Anonymous function with _ placeholders: _.method or _ + 1 etc.
    case Term.AnonymousFunction(body) =>
      Value.NativeFnV("anon", args =>
        eval(body.asInstanceOf[Term], env + ("_$0" -> args.headOption.getOrElse(Value.UnitV))))

    // Placeholder _ in anonymous function context
    case Term.Placeholder() =>
      env.getOrElse("_$0", throw InterpretError("Unexpected _"))

    // Lambda  x => body  or  (x, y) => body
    case Term.Function(params, body) =>
      Value.FunV(params.map(_.name.value), body, env)

    // Match / pattern match
    case t: Term.Match =>
      val scrutV = eval(t.expr, env)
      t.cases.iterator
        .flatMap { c =>
          matchPat(c.pat, scrutV, env).flatMap { patEnv =>
            val ok = c.cond.forall(g => eval(g, patEnv).asInstanceOf[Value.BoolV].v)
            Option.when(ok)(eval(c.body, patEnv))
          }
        }
        .nextOption()
        .getOrElse(throw InterpretError(s"Match failure: ${Value.show(scrutV)}"))

    // Tuple  (a, b, ...)
    case Term.Tuple(elems) => Value.TupleV(elems.map(eval(_, env)))

    // new ClassName(args)
    case Term.New(Init(tpe, _, argss)) =>
      val typeName = tpe match { case Type.Name(n) => n; case _ => "?" }
      val args = argss.flatMap(_.values).map(eval(_, env))
      env.getOrElse(typeName, globals.getOrElse(typeName,
        throw InterpretError(s"Unknown constructor: $typeName"))) match
          case c: Value.NativeFnV => c.f(args)
          case f: Value.FunV      => callFun(f, args)
          case _ => throw InterpretError(s"$typeName is not a constructor")

    // return expr  (non-local via exception)
    case Term.Return(expr) => throw ReturnSignal(eval(expr, env))

    // var assignment
    case Term.Assign(Term.Name(name), rhs) =>
      val v = eval(rhs, env)
      if env.contains(name) then throw InterpretError("Cannot assign to immutable val")
      globals(name) = v
      Value.UnitV

    case other => throw InterpretError(s"Cannot eval: ${other.productPrefix}")

  // ─── Dispatch ────────────────────────────────────────────────────

  private def callValue(fn: Value, args: List[Value], env: Env): Value = fn match
    case f: Value.FunV     => callFun(f, args)
    case f: Value.NativeFnV => f.f(args)
    // Partial application: fn.apply
    case _ => throw InterpretError(s"Not callable: ${Value.show(fn)}")

  private def callFun(f: Value.FunV, args: List[Value]): Value =
    val callEnv = globals.toMap ++ f.closure ++ f.params.zip(args).toMap
    try eval(f.body, callEnv)
    catch case r: ReturnSignal => r.value

  private def infix(lhs: Value, op: String, args: List[Value], env: Env): Value =
    val rhs = args.headOption.getOrElse(Value.UnitV)
    (lhs, op, rhs) match
      case (Value.IntV(a),    "+",  Value.IntV(b))    => Value.IntV(a + b)
      case (Value.IntV(a),    "-",  Value.IntV(b))    => Value.IntV(a - b)
      case (Value.IntV(a),    "*",  Value.IntV(b))    => Value.IntV(a * b)
      case (Value.IntV(a),    "/",  Value.IntV(b))    => Value.IntV(a / b)
      case (Value.IntV(a),    "%",  Value.IntV(b))    => Value.IntV(a % b)
      case (Value.DoubleV(a), "+",  Value.DoubleV(b)) => Value.DoubleV(a + b)
      case (Value.DoubleV(a), "-",  Value.DoubleV(b)) => Value.DoubleV(a - b)
      case (Value.DoubleV(a), "*",  Value.DoubleV(b)) => Value.DoubleV(a * b)
      case (Value.DoubleV(a), "/",  Value.DoubleV(b)) => Value.DoubleV(a / b)
      case (Value.IntV(a),    "+",  Value.DoubleV(b)) => Value.DoubleV(a + b)
      case (Value.DoubleV(a), "+",  Value.IntV(b))    => Value.DoubleV(a + b)
      case (Value.IntV(a),    "*",  Value.DoubleV(b)) => Value.DoubleV(a * b)
      case (Value.DoubleV(a), "*",  Value.IntV(b))    => Value.DoubleV(a * b)
      case (Value.IntV(a),    "-",  Value.DoubleV(b)) => Value.DoubleV(a - b)
      case (Value.DoubleV(a), "-",  Value.IntV(b))    => Value.DoubleV(a - b)
      case (Value.StringV(a), "+",  b)                => Value.StringV(a + Value.show(b))
      case (a, "==",  b) => Value.BoolV(a == b)
      case (a, "!=",  b) => Value.BoolV(a != b)
      case (Value.IntV(a),    "<",  Value.IntV(b))    => Value.BoolV(a < b)
      case (Value.IntV(a),    ">",  Value.IntV(b))    => Value.BoolV(a > b)
      case (Value.IntV(a),    "<=", Value.IntV(b))    => Value.BoolV(a <= b)
      case (Value.IntV(a),    ">=", Value.IntV(b))    => Value.BoolV(a >= b)
      case (Value.DoubleV(a), "<",  Value.DoubleV(b)) => Value.BoolV(a < b)
      case (Value.DoubleV(a), ">",  Value.DoubleV(b)) => Value.BoolV(a > b)
      case (Value.BoolV(a),   "&&", Value.BoolV(b))   => Value.BoolV(a && b)
      case (Value.BoolV(a),   "||", Value.BoolV(b))   => Value.BoolV(a || b)
      case (v, "::",  Value.ListV(ls))                 => Value.ListV(v :: ls)
      case (Value.ListV(a), "++", Value.ListV(b))      => Value.ListV(a ++ b)
      case (Value.ListV(a), ":::", Value.ListV(b))     => Value.ListV(a ++ b)
      // Fallback: treat as method call on lhs
      case _ => dispatch(lhs, op, args, env)

  private def dispatch(recv: Value, name: String, args: List[Value], env: Env): Value =
    (recv, name, args) match
      // ── String ──────────────────────────────────────────────────
      case (Value.StringV(s), "length",       Nil) => Value.IntV(s.length.toLong)
      case (Value.StringV(s), "size",         Nil) => Value.IntV(s.length.toLong)
      case (Value.StringV(s), "isEmpty",      Nil) => Value.BoolV(s.isEmpty)
      case (Value.StringV(s), "nonEmpty",     Nil) => Value.BoolV(s.nonEmpty)
      case (Value.StringV(s), "trim",         Nil) => Value.StringV(s.trim)
      case (Value.StringV(s), "toUpperCase",  Nil) => Value.StringV(s.toUpperCase)
      case (Value.StringV(s), "toLowerCase",  Nil) => Value.StringV(s.toLowerCase)
      case (Value.StringV(s), "reverse",      Nil) => Value.StringV(s.reverse)
      case (Value.StringV(s), "toInt",        Nil) => Value.IntV(s.toLong)
      case (Value.StringV(s), "toDouble",     Nil) => Value.DoubleV(s.toDouble)
      case (Value.StringV(s), "toString",     Nil) => Value.StringV(s)
      case (Value.StringV(s), "contains",     List(Value.StringV(t))) => Value.BoolV(s.contains(t))
      case (Value.StringV(s), "startsWith",   List(Value.StringV(t))) => Value.BoolV(s.startsWith(t))
      case (Value.StringV(s), "endsWith",     List(Value.StringV(t))) => Value.BoolV(s.endsWith(t))
      case (Value.StringV(s), "split",        List(Value.StringV(sep))) =>
        Value.ListV(s.split(java.util.regex.Pattern.quote(sep)).toList.map(Value.StringV(_)))
      case (Value.StringV(s), "mkString",     _)  => Value.StringV(s)
      case (Value.StringV(s), "take",         List(Value.IntV(n))) => Value.StringV(s.take(n.toInt))
      case (Value.StringV(s), "drop",         List(Value.IntV(n))) => Value.StringV(s.drop(n.toInt))
      case (Value.StringV(s), "replace",      List(Value.StringV(a), Value.StringV(b))) => Value.StringV(s.replace(a, b))
      case (Value.StringV(s), "map",          List(f)) =>
        Value.StringV(s.map(c => Value.show(callValue(f, List(Value.CharV(c)), env))).mkString)
      // ── List ────────────────────────────────────────────────────
      case (Value.ListV(ls), "length",     Nil)  => Value.IntV(ls.length.toLong)
      case (Value.ListV(ls), "size",       Nil)  => Value.IntV(ls.size.toLong)
      case (Value.ListV(ls), "isEmpty",    Nil)  => Value.BoolV(ls.isEmpty)
      case (Value.ListV(ls), "nonEmpty",   Nil)  => Value.BoolV(ls.nonEmpty)
      case (Value.ListV(ls), "head",       Nil)  => ls.headOption.getOrElse(throw InterpretError("head on Nil"))
      case (Value.ListV(ls), "tail",       Nil)  => Value.ListV(ls.tail)
      case (Value.ListV(ls), "last",       Nil)  => ls.lastOption.getOrElse(throw InterpretError("last on Nil"))
      case (Value.ListV(ls), "init",       Nil)  => Value.ListV(ls.init)
      case (Value.ListV(ls), "reverse",    Nil)  => Value.ListV(ls.reverse)
      case (Value.ListV(ls), "distinct",   Nil)  => Value.ListV(ls.distinct)
      case (Value.ListV(ls), "sorted",     Nil)  => Value.ListV(ls.sortBy(Value.show))
      case (Value.ListV(ls), "toList",     Nil)  => Value.ListV(ls)
      case (Value.ListV(ls), "toSet",      Nil)  => Value.ListV(ls.distinct)
      case (Value.ListV(ls), "contains",   List(v)) => Value.BoolV(ls.contains(v))
      case (Value.ListV(ls), "indexOf",    List(v)) => Value.IntV(ls.indexOf(v).toLong)
      case (Value.ListV(ls), "take",       List(Value.IntV(n))) => Value.ListV(ls.take(n.toInt))
      case (Value.ListV(ls), "drop",       List(Value.IntV(n))) => Value.ListV(ls.drop(n.toInt))
      case (Value.ListV(ls), "takeRight",  List(Value.IntV(n))) => Value.ListV(ls.takeRight(n.toInt))
      case (Value.ListV(ls), "dropRight",  List(Value.IntV(n))) => Value.ListV(ls.dropRight(n.toInt))
      case (Value.ListV(ls), "map",        List(f)) =>
        Value.ListV(ls.map(item => callValue(f, List(item), env)))
      case (Value.ListV(ls), "flatMap",    List(f)) =>
        Value.ListV(ls.flatMap { item => callValue(f, List(item), env) match
          case Value.ListV(inner) => inner; case v => List(v) })
      case (Value.ListV(ls), "filter",     List(f)) =>
        Value.ListV(ls.filter(item => callValue(f, List(item), env).asInstanceOf[Value.BoolV].v))
      case (Value.ListV(ls), "filterNot",  List(f)) =>
        Value.ListV(ls.filterNot(item => callValue(f, List(item), env).asInstanceOf[Value.BoolV].v))
      case (Value.ListV(ls), "foreach",    List(f)) =>
        ls.foreach(item => callValue(f, List(item), env)); Value.UnitV
      case (Value.ListV(ls), "count",      List(f)) =>
        Value.IntV(ls.count(item => callValue(f, List(item), env).asInstanceOf[Value.BoolV].v).toLong)
      case (Value.ListV(ls), "find",       List(f)) =>
        Value.OptionV(ls.find(item => callValue(f, List(item), env).asInstanceOf[Value.BoolV].v))
      case (Value.ListV(ls), "exists",     List(f)) =>
        Value.BoolV(ls.exists(item => callValue(f, List(item), env).asInstanceOf[Value.BoolV].v))
      case (Value.ListV(ls), "forall",     List(f)) =>
        Value.BoolV(ls.forall(item => callValue(f, List(item), env).asInstanceOf[Value.BoolV].v))
      case (Value.ListV(ls), "sortBy",     List(f)) =>
        Value.ListV(ls.sortBy(item => Value.show(callValue(f, List(item), env))))
      case (Value.ListV(ls), "zip",        List(Value.ListV(other))) =>
        Value.ListV(ls.zip(other).map { case (a, b) => Value.TupleV(List(a, b)) })
      case (Value.ListV(ls), "zipWithIndex", Nil) =>
        Value.ListV(ls.zipWithIndex.map { case (a, i) => Value.TupleV(List(a, Value.IntV(i.toLong))) })
      case (Value.ListV(ls), "mkString",   Nil)  => Value.StringV(ls.map(Value.show).mkString)
      case (Value.ListV(ls), "mkString",   List(Value.StringV(sep))) =>
        Value.StringV(ls.map(Value.show).mkString(sep))
      case (Value.ListV(ls), "mkString",   List(Value.StringV(s), Value.StringV(sep), Value.StringV(e))) =>
        Value.StringV(ls.map(Value.show).mkString(s, sep, e))
      case (Value.ListV(ls), "sum",        Nil)  =>
        ls.foldLeft[Value](Value.IntV(0)) {
          case (Value.IntV(a),    Value.IntV(b))    => Value.IntV(a + b)
          case (Value.DoubleV(a), Value.DoubleV(b)) => Value.DoubleV(a + b)
          case (Value.IntV(a),    Value.DoubleV(b)) => Value.DoubleV(a + b)
          case (Value.DoubleV(a), Value.IntV(b))    => Value.DoubleV(a + b)
          case (a, b) => throw InterpretError(s"Cannot sum $a and $b")
        }
      case (Value.ListV(ls), "min",        Nil)  =>
        ls.minBy { case Value.IntV(n) => n.toDouble; case Value.DoubleV(d) => d; case _ => 0.0 }
      case (Value.ListV(ls), "max",        Nil)  =>
        ls.maxBy { case Value.IntV(n) => n.toDouble; case Value.DoubleV(d) => d; case _ => 0.0 }
      case (Value.ListV(ls), "foldLeft",   List(init)) =>
        // foldLeft is curried: list.foldLeft(init)(f) → two Term.Apply nodes
        Value.NativeFnV("foldLeft", {
          case List(f) => ls.foldLeft(init)((acc, item) => callValue(f, List(acc, item), env))
          case _       => throw InterpretError("foldLeft expects one function argument")
        })
      case (Value.ListV(ls), "reduceLeft", List(f)) =>
        ls.reduceLeft((a, b) => callValue(f, List(a, b), env))
      case (Value.ListV(ls), "flatten",    Nil) =>
        Value.ListV(ls.flatMap { case Value.ListV(inner) => inner; case v => List(v) })
      case (Value.ListV(ls), "sliding",    List(Value.IntV(n))) =>
        Value.ListV(ls.sliding(n.toInt).map(Value.ListV(_)).toList)
      case (Value.ListV(ls), "grouped",    List(Value.IntV(n))) =>
        Value.ListV(ls.grouped(n.toInt).map(Value.ListV(_)).toList)
      // ── Option ──────────────────────────────────────────────────
      case (Value.OptionV(Some(v)), "get",        Nil) => v
      case (Value.OptionV(opt),     "isDefined",  Nil) => Value.BoolV(opt.isDefined)
      case (Value.OptionV(opt),     "isEmpty",    Nil) => Value.BoolV(opt.isEmpty)
      case (Value.OptionV(opt),     "nonEmpty",   Nil) => Value.BoolV(opt.nonEmpty)
      case (Value.OptionV(Some(v)), "getOrElse",  _)   => v
      case (Value.OptionV(None),    "getOrElse",  List(d)) => d
      case (Value.OptionV(opt),     "map",        List(f)) =>
        Value.OptionV(opt.map(v => callValue(f, List(v), env)))
      case (Value.OptionV(opt),     "flatMap",    List(f)) =>
        opt.fold[Value](Value.OptionV(None)) { v =>
          callValue(f, List(v), env) match
            case o: Value.OptionV => o
            case other            => Value.OptionV(Some(other))
        }
      case (Value.OptionV(opt),     "filter",     List(f)) =>
        Value.OptionV(opt.filter(v => callValue(f, List(v), env).asInstanceOf[Value.BoolV].v))
      case (Value.OptionV(opt),     "foreach",    List(f)) =>
        opt.foreach(v => callValue(f, List(v), env)); Value.UnitV
      case (Value.OptionV(opt),     "toList",     Nil) => Value.ListV(opt.toList)
      case (Value.OptionV(None),    "orElse",     List(other)) => other
      case (opt: Value.OptionV,     "orElse",     _) => opt
      // ── Int / Double ─────────────────────────────────────────────
      case (Value.IntV(n),    "toDouble",  Nil) => Value.DoubleV(n.toDouble)
      case (Value.IntV(n),    "toLong",    Nil) => Value.IntV(n)
      case (Value.IntV(n),    "toInt",     Nil) => Value.IntV(n)
      case (Value.IntV(n),    "abs",       Nil) => Value.IntV(math.abs(n))
      case (Value.IntV(n),    "toString",  Nil) => Value.StringV(n.toString)
      case (Value.DoubleV(d), "toInt",     Nil) => Value.IntV(d.toLong)
      case (Value.DoubleV(d), "toLong",    Nil) => Value.IntV(d.toLong)
      case (Value.DoubleV(d), "abs",       Nil) => Value.DoubleV(math.abs(d))
      case (Value.DoubleV(d), "toString",  Nil) => Value.StringV(d.toString)
      case (Value.DoubleV(d), "round",     Nil) => Value.IntV(math.round(d))
      case (Value.DoubleV(d), "floor",     Nil) => Value.DoubleV(math.floor(d))
      case (Value.DoubleV(d), "ceil",      Nil) => Value.DoubleV(math.ceil(d))
      // ── Tuple ────────────────────────────────────────────────────
      case (Value.TupleV(es), "_1", Nil) => es(0)
      case (Value.TupleV(es), "_2", Nil) => es(1)
      case (Value.TupleV(es), "_3", Nil) => es(2)
      case (Value.TupleV(es), "_4", Nil) => es(3)
      // ── Instance (case class) field access ───────────────────────
      case (Value.InstanceV(_, fields), name, Nil) =>
        fields.getOrElse(name, throw InterpretError(s"No field '$name'"))
      // ── Generic toString ─────────────────────────────────────────
      case (v, "toString", Nil) => Value.StringV(Value.show(v))
      case (v, "apply",    args) => callValue(v, args, env)
      case _ =>
        throw InterpretError(s"No method '$name' on ${recv.getClass.getSimpleName}(${Value.show(recv)})")

  // ─── Pattern matching ────────────────────────────────────────────

  private def matchPat(pat: Pat, scrutinee: Value, env: Env): Option[Env] = pat match
    case Pat.Wildcard()     => Some(env)
    case Pat.Var(name)      => Some(env + (name.value -> scrutinee))
    case lit: Lit =>
      val litV: Value = lit match
        case Lit.Int(v)     => Value.IntV(v.toLong)
        case Lit.Long(v)    => Value.IntV(v)
        case Lit.String(v)  => Value.StringV(v)
        case Lit.Boolean(v) => Value.BoolV(v)
        case Lit.Double(v)  => Value.DoubleV(v.toString.toDouble)
        case Lit.Null()     => Value.NullV
        case _              => Value.NullV
      Option.when(litV == scrutinee)(env)
    case Pat.Tuple(pats) =>
      scrutinee match
        case Value.TupleV(elems) if elems.length == pats.length =>
          pats.zip(elems).foldLeft(Option(env)) { (acc, pe) =>
            acc.flatMap(e => matchPat(pe._1, pe._2, e))
          }
        case _ => None
    case Pat.Extract(fn, args) =>
      val typeName = fn match { case Term.Name(n) => n; case _ => return None }
      scrutinee match
        case Value.InstanceV(t, fields) if t == typeName =>
          val fieldVals = fields.values.toList
          if args.length != fieldVals.length then None
          else args.zip(fieldVals).foldLeft(Option(env)) { (acc, pe) =>
            acc.flatMap(e => matchPat(pe._1, pe._2, e))
          }
        case _ => None
    case Pat.Typed(inner, _)  => matchPat(inner, scrutinee, env)
    case Pat.Alternative(lhs, rhs) =>
      List(lhs, rhs).iterator.flatMap(p => matchPat(p, scrutinee, env)).nextOption()
    case _ => None

object Interpreter:
  def run(module: Module): Unit = Interpreter().run(module)
