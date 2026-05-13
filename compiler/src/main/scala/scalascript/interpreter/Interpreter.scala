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
class Interpreter(out: java.io.PrintStream = System.out):
  private val globals      = mutable.Map.empty[String, Value]
  // givenTable(typeclassName)(concreteTypeArg) = instance InstanceV
  private val givenTable   = mutable.Map.empty[String, mutable.Map[String, Value]]
  private var mainCalled   = false
  private var placeholderIdx = 0

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

    native("println") { args => out.println(args.map(Value.show).mkString(" ")); Value.UnitV }
    native("print")   { args => out.print(args.map(Value.show).mkString(" "));   Value.UnitV }
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
    native("Some")  { case List(v) => Value.OptionV(Some(v)); case _ => throw InterpretError("Some takes 1 arg") }
    native("List")  { args => Value.ListV(args) }
    native("Map") { args =>
      val entries = args.collect { case Value.TupleV(List(k, v)) => k -> v }.toMap
      Value.MapV(entries)
    }
    globals("None") = Value.OptionV(None)

    native("math.sqrt")  { case List(Value.DoubleV(d)) => Value.DoubleV(math.sqrt(d))
                           case List(Value.IntV(n))    => Value.DoubleV(math.sqrt(n.toDouble))
                           case _ => Value.UnitV }
    native("math.abs")   { case List(Value.DoubleV(d)) => Value.DoubleV(math.abs(d))
                           case List(Value.IntV(n))    => Value.IntV(math.abs(n))
                           case _ => Value.UnitV }
    native("math.pow")   { case List(Value.DoubleV(a), Value.DoubleV(b)) => Value.DoubleV(math.pow(a, b))
                           case List(Value.IntV(a), Value.DoubleV(b))    => Value.DoubleV(math.pow(a.toDouble, b))
                           case _ => Value.UnitV }
    native("math.floor") { case List(Value.DoubleV(d)) => Value.DoubleV(math.floor(d)); case _ => Value.UnitV }
    native("math.ceil")  { case List(Value.DoubleV(d)) => Value.DoubleV(math.ceil(d));  case _ => Value.UnitV }
    native("math.round") { case List(Value.DoubleV(d)) => Value.IntV(math.round(d));    case _ => Value.UnitV }
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
      case Source(stats)     => stats.foreach(s => execStat(s, globals))
      case Term.Block(stats) => stats.foreach(s => execStat(s, globals))
      case t: Term           => eval(t, globals.toMap); ()
      case other             => throw InterpretError(s"Expected Source/Block, got ${other.productPrefix}")
    }

  // ─── Statement execution ─────────────────────────────────────────

  private def execStat(stat: Stat, env: mutable.Map[String, Value]): Unit = stat match
    case Defn.Val(_, List(Pat.Var(n)), _, rhs) =>
      env(n.value) = eval(rhs, env.toMap)

    case Defn.Var(_, List(Pat.Var(n)), _, Some(rhs)) =>
      env(n.value) = eval(rhs, env.toMap)

    case d: Defn.Def =>
      val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values).map(_.name.value)
      env(d.name.value) = Value.FunV(params, d.body, env.toMap)
      if d.name.value == "main" && params.isEmpty then mainCalled = false

    case d: Defn.Object =>
      val members = mutable.Map.empty[String, Value]
      d.templ.stats.foreach(s => execStat(s, members))
      env(d.name.value) = Value.InstanceV(d.name.value, members.toMap)

    case d: Defn.Class =>
      val params = d.ctor.paramClauses.flatMap(_.values)
      val paramNames = params.map(_.name.value)
      val typeName = d.name.value
      env(typeName) = Value.NativeFnV(typeName,
        args => Value.InstanceV(typeName, paramNames.zip(args).toMap)
      )

    case d: Defn.Enum =>
      val enumName = d.name.value
      val caseFields = mutable.Map.empty[String, Value]
      d.templ.stats.foreach {
        case ec: Defn.EnumCase =>
          val caseName = ec.name.value
          val paramNames = ec.ctor.paramClauses.flatMap(_.values).map(_.name.value)
          val v: Value =
            if paramNames.isEmpty then Value.InstanceV(caseName, Map.empty)
            else Value.NativeFnV(caseName, args => Value.InstanceV(caseName, paramNames.zip(args).toMap))
          env(caseName) = v
          caseFields(caseName) = v
        case _ => ()
      }
      // Enum companion object for qualified access (Color.Red)
      env(enumName) = Value.InstanceV(enumName, caseFields.toMap)

    case d: Defn.Trait =>
      // Register the typeclass name as a dispatcher in scope
      env(d.name.value) = Value.TypeclassV(d.name.value)

    case d: Defn.Given =>
      // Extract the parent type: e.g. `given Printable[Int] with` → tcName="Printable", typeArg="Int"
      d.templ.inits.headOption.foreach { init =>
        val (tcName, typeArg) = extractTCTypeArg(init.tpe).getOrElse(return)
        // Collect method implementations in a local env seeded with globals
        val members = mutable.Map.from(globals)
        d.templ.stats.foreach(s => execStat(s, members))
        // Keep only the methods declared in this given body
        val implNames = d.templ.stats.collect { case dd: Defn.Def => dd.name.value }.toSet
        val instance = Value.InstanceV(
          s"$tcName[$typeArg]",
          members.view.filterKeys(implNames.contains).toMap
        )
        givenTable.getOrElseUpdate(tcName, mutable.Map.empty)(typeArg) = instance
      }

    case _: Decl.Def => () // abstract method declaration — no body

    case t: Term =>
      val result = eval(t, env.toMap)
      t match
        case Term.Apply(Term.Name("main"), _) => mainCalled = true
        case _                                => ()
      result: @annotation.nowarn("msg=Discarded")

    case _ => () // type aliases, imports, exports, etc.

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

    // Field / method selection: a.b  (no-arg call)
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

    // String interpolation s"..." / f"..."
    case Term.Interpolate(Term.Name(prefix), parts, args) if prefix == "s" || prefix == "f" =>
      val sb = StringBuilder()
      for i <- parts.indices do
        sb ++= parts(i).asInstanceOf[Lit.String].value
        if i < args.length then sb ++= Value.show(eval(args(i).asInstanceOf[Term], env))
      Value.StringV(sb.toString)

    // Anonymous function with _ placeholders: _.field, _ + 1, _ + _, etc.
    case t: Term.AnonymousFunction =>
      val arity = t.body.collect { case _: Term.Placeholder => () }.length.max(1)
      Value.NativeFnV("anon", args => {
        val saved = placeholderIdx
        placeholderIdx = 0
        val phEnv = env ++ args.zipWithIndex.map { (v, i) => s"_$$${i}" -> v }
        try eval(t.body, phEnv)
        finally placeholderIdx = saved
      })

    // _ placeholder — numbered left-to-right via mutable counter
    case _: Term.Placeholder =>
      val i = placeholderIdx
      placeholderIdx += 1
      env.getOrElse(s"_$$${i}", throw InterpretError("Unexpected _"))

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

    // var/field assignment
    case Term.Assign(Term.Name(name), rhs) =>
      globals(name) = eval(rhs, env); Value.UnitV

    // summon[TC[T]] — retrieve a given instance from the table
    case t: Term.ApplyType =>
      (t.fun, t.argClause.values) match
        case (Term.Name("summon"), List(typeArg)) =>
          val (tcName, typeArgName) = extractTCTypeArg(typeArg)
            .getOrElse(throw InterpretError(s"summon: unsupported type"))
          givenTable.get(tcName).flatMap(_.get(typeArgName))
            .getOrElse(throw InterpretError(s"No given $tcName[$typeArgName]"))
        case _ => eval(t.fun, env)  // other type applications — erase type args

    case other => throw InterpretError(s"Cannot eval: ${other.productPrefix}")

  // ─── Call helpers ─────────────────────────────────────────────────

  private def callValue(fn: Value, args: List[Value], env: Env): Value = fn match
    case f: Value.FunV      => callFun(f, args)
    case f: Value.NativeFnV => f.f(args)
    case _ => throw InterpretError(s"Not callable: ${Value.show(fn)}")

  private def callFun(f: Value.FunV, args: List[Value]): Value =
    val callEnv = globals.toMap ++ f.closure ++ f.params.zip(args).toMap
    try eval(f.body, callEnv)
    catch case r: ReturnSignal => r.value

  // ─── Infix operators ──────────────────────────────────────────────

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
      case (Value.DoubleV(a), "<=", Value.DoubleV(b)) => Value.BoolV(a <= b)
      case (Value.DoubleV(a), ">=", Value.DoubleV(b)) => Value.BoolV(a >= b)
      case (Value.BoolV(a),   "&&", Value.BoolV(b))   => Value.BoolV(a && b)
      case (Value.BoolV(a),   "||", Value.BoolV(b))   => Value.BoolV(a || b)
      case (v, "::",  Value.ListV(ls))                 => Value.ListV(v :: ls)
      case (Value.ListV(a), "++", Value.ListV(b))      => Value.ListV(a ++ b)
      case (Value.ListV(a), ":::", Value.ListV(b))     => Value.ListV(a ++ b)
      case (k, "->", v)                                => Value.TupleV(List(k, v))
      // Fallback: method call on lhs
      case _ => dispatch(lhs, op, args, env)

  // ─── Dispatch ─────────────────────────────────────────────────────

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
        Value.NativeFnV("foldLeft",
          { case List(f) => ls.foldLeft(init)((acc, item) => callValue(f, List(acc, item), env))
            case _       => throw InterpretError("foldLeft expects one function argument") })
      case (Value.ListV(ls), "foldRight",  List(init)) =>
        Value.NativeFnV("foldRight",
          { case List(f) => ls.foldRight(init)((item, acc) => callValue(f, List(item, acc), env))
            case _       => throw InterpretError("foldRight expects one function argument") })
      case (Value.ListV(ls), "reduceLeft", List(f)) =>
        ls.reduceLeft((a, b) => callValue(f, List(a, b), env))
      case (Value.ListV(ls), "flatten",    Nil) =>
        Value.ListV(ls.flatMap { case Value.ListV(inner) => inner; case v => List(v) })
      case (Value.ListV(ls), "sliding",    List(Value.IntV(n))) =>
        Value.ListV(ls.sliding(n.toInt).map(Value.ListV(_)).toList)
      case (Value.ListV(ls), "grouped",    List(Value.IntV(n))) =>
        Value.ListV(ls.grouped(n.toInt).map(Value.ListV(_)).toList)
      case (Value.ListV(ls), "appended",   List(v)) => Value.ListV(ls :+ v)
      case (Value.ListV(ls), "prepended",  List(v)) => Value.ListV(v +: ls)
      // ── Map ─────────────────────────────────────────────────────
      case (Value.MapV(m), "size",       Nil)     => Value.IntV(m.size.toLong)
      case (Value.MapV(m), "isEmpty",    Nil)     => Value.BoolV(m.isEmpty)
      case (Value.MapV(m), "nonEmpty",   Nil)     => Value.BoolV(m.nonEmpty)
      case (Value.MapV(m), "keys",       Nil)     => Value.ListV(m.keys.toList)
      case (Value.MapV(m), "values",     Nil)     => Value.ListV(m.values.toList)
      case (Value.MapV(m), "toList",     Nil)     => Value.ListV(m.toList.map { (k, v) => Value.TupleV(List(k, v)) })
      case (Value.MapV(m), "contains",   List(k)) => Value.BoolV(m.contains(k))
      case (Value.MapV(m), "get",        List(k)) => Value.OptionV(m.get(k))
      case (Value.MapV(m), "apply",      List(k)) =>
        m.getOrElse(k, throw InterpretError(s"Key not found: ${Value.show(k)}"))
      case (Value.MapV(m), "getOrElse",  List(k, d)) => m.getOrElse(k, d)
      case (Value.MapV(m), "updated",    List(k, v)) => Value.MapV(m + (k -> v))
      case (Value.MapV(m), "removed",    List(k))    => Value.MapV(m - k)
      case (Value.MapV(m), "map",        List(f)) =>
        Value.MapV(m.map { (k, v) =>
          callValue(f, List(Value.TupleV(List(k, v))), env) match
            case Value.TupleV(List(nk, nv)) => nk -> nv
            case other => other -> Value.UnitV
        })
      case (Value.MapV(m), "filter",     List(f)) =>
        Value.MapV(m.filter { (k, v) =>
          callValue(f, List(Value.TupleV(List(k, v))), env).asInstanceOf[Value.BoolV].v })
      case (Value.MapV(m), "foreach",    List(f)) =>
        m.foreach { (k, v) => callValue(f, List(Value.TupleV(List(k, v))), env) }; Value.UnitV
      case (Value.MapV(m), "mkString",   Nil)  => Value.StringV(Value.show(Value.MapV(m)))
      // String-key access shorthand: map.key
      case (Value.MapV(m), key, Nil) =>
        m.getOrElse(Value.StringV(key),
          throw InterpretError(s"No key '$key' in map"))
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
      case (Value.IntV(n),    "to",        List(Value.IntV(m))) =>
        Value.ListV((n to m).map(Value.IntV(_)).toList)
      case (Value.IntV(n),    "until",     List(Value.IntV(m))) =>
        Value.ListV((n until m).map(Value.IntV(_)).toList)
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
      // ── Typeclass dynamic dispatch (Printable.show(42)) ──────────
      case (Value.TypeclassV(tcName), method, args) =>
        val concreteType = args.headOption.map(inferValueType).getOrElse("_")
        val instance = givenTable.get(tcName).flatMap(_.get(concreteType))
          .getOrElse(throw InterpretError(s"No given $tcName[$concreteType]"))
        dispatch(instance, method, args, env)
      // ── Instance (case class / enum case) field access ───────────
      case (Value.InstanceV(_, fields), fname, Nil) =>
        fields.getOrElse(fname, throw InterpretError(s"No field '$fname'"))
      // ── Enum companion call (Color.RGB(1,2,3)) ───────────────────
      case (Value.InstanceV(_, fields), fname, fargs) if fields.contains(fname) =>
        callValue(fields(fname), fargs, env)
      // ── Generic ──────────────────────────────────────────────────
      case (v, "toString", Nil)  => Value.StringV(Value.show(v))
      case (v, "apply",    fargs) => callValue(v, fargs, env)
      case _ =>
        throw InterpretError(s"No method '$name' on ${recv.getClass.getSimpleName}(${Value.show(recv)})")

  // Extract (typeclass, typeArg) from Type.Apply / Type.Name using field accessors
  // (Type.Apply unapply is deprecated in scalameta 4.17.0)
  private def extractTCTypeArg(tpe: Tree): Option[(String, String)] = tpe match
    case n: Type.Name => Some((n.value, "_"))
    case ta: Type.Apply =>
      ta.tpe match
        case n: Type.Name =>
          val typeArg = ta.argClause.values match
            case List(an: Type.Name) => an.value
            case _                   => "_"
          Some((n.value, typeArg))
        case _ => None
    case _ => None

  private def inferValueType(v: Value): String = v match
    case _: Value.IntV        => "Int"
    case _: Value.DoubleV     => "Double"
    case _: Value.StringV     => "String"
    case _: Value.BoolV       => "Boolean"
    case _: Value.CharV       => "Char"
    case _: Value.ListV       => "List"
    case _: Value.OptionV     => "Option"
    case _: Value.MapV        => "Map"
    case Value.InstanceV(t,_) => t
    case _                    => "Any"

  // ─── Pattern matching ────────────────────────────────────────────

  private def matchPat(pat: Pat, scrutinee: Value, env: Env): Option[Env] = pat match
    case Pat.Wildcard()  => Some(env)
    case Pat.Var(name)   => Some(env + (name.value -> scrutinee))
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
      val typeName = fn match
        case Term.Name(n)           => n
        case Term.Select(_, Term.Name(n)) => n   // qualified: Color.RGB(...)
        case _ => return None
      scrutinee match
        case Value.InstanceV(t, fields) if t == typeName =>
          val fieldVals = fields.values.toList
          if args.length != fieldVals.length then None
          else args.zip(fieldVals).foldLeft(Option(env)) { (acc, pe) =>
            acc.flatMap(e => matchPat(pe._1, pe._2, e))
          }
        case _ => None
    case Pat.Typed(inner, _)       => matchPat(inner, scrutinee, env)
    case Pat.Alternative(lhs, rhs) =>
      List(lhs, rhs).iterator.flatMap(p => matchPat(p, scrutinee, env)).nextOption()
    // Enum / singleton references: `case Red =>` or `case Color.Red =>`
    case t: Term.Name =>
      env.get(t.value).flatMap(v => Option.when(v == scrutinee)(env))
    case Term.Select(_, Term.Name(n)) =>
      env.get(n).flatMap(v => Option.when(v == scrutinee)(env))
    case _ => None

object Interpreter:
  def run(module: Module, out: java.io.PrintStream = System.out): Unit = Interpreter(out).run(module)
