package scalascript.interpreter

import Computation.{Pure, Perform}

/** Built-in method dispatch: String, Char, Int, Double, Boolean, List, Option, Map,
 *  Tuple, Either, and instance field access.  User-defined extensions are checked
 *  first (via `interp.extensions`), then the built-in match.
 */
private[interpreter] object DispatchRuntime:

  def dispatch(recv: Value, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    // User-defined extensions take priority over built-in dispatch for primitive/std types.
    val userExt = recv match
      case _: Value.OptionV => interp.extensions.get(("Option", name)).map(interp.callValue(_, recv :: args, env))
      case _: Value.ListV   => interp.extensions.get(("List",   name)).map(interp.callValue(_, recv :: args, env))
      case _: Value.IntV    => interp.extensions.get(("Int",    name)).map(interp.callValue(_, recv :: args, env))
      case _: Value.DoubleV => interp.extensions.get(("Double", name)).map(interp.callValue(_, recv :: args, env))
      case _: Value.StringV => interp.extensions.get(("String", name)).map(interp.callValue(_, recv :: args, env))
      case _: Value.BoolV   => interp.extensions.get(("Boolean",name)).map(interp.callValue(_, recv :: args, env))
      case _: Value.MapV    => interp.extensions.get(("Map",    name)).map(interp.callValue(_, recv :: args, env))
      case _                => None
    if userExt.isDefined then userExt.get
    else
    (recv, name, args) match
      // ── String ──────────────────────────────────────────────────
      case (Value.StringV(s), "length",       Nil) => Pure(Value.intV(s.length.toLong))
      case (Value.StringV(s), "size",         Nil) => Pure(Value.intV(s.length.toLong))
      case (Value.StringV(s), "isEmpty",      Nil) => Pure(Value.BoolV(s.isEmpty))
      case (Value.StringV(s), "nonEmpty",     Nil) => Pure(Value.BoolV(s.nonEmpty))
      case (Value.StringV(s), "trim",         Nil) => Pure(Value.StringV(s.trim))
      case (Value.StringV(s), "toUpperCase",  Nil) => Pure(Value.StringV(s.toUpperCase))
      case (Value.StringV(s), "toLowerCase",  Nil) => Pure(Value.StringV(s.toLowerCase))
      case (Value.StringV(s), "reverse",      Nil) => Pure(Value.StringV(s.reverse))
      case (Value.StringV(s), "toInt",        Nil) => Pure(Value.intV(s.toLong))
      case (Value.StringV(s), "toDouble",     Nil) => Pure(Value.DoubleV(s.toDouble))
      case (Value.StringV(s), "toString",     Nil) => Pure(Value.StringV(s))
      case (Value.StringV(s), "contains",     List(Value.StringV(t))) => Pure(Value.BoolV(s.contains(t)))
      case (Value.StringV(s), "startsWith",   List(Value.StringV(t))) => Pure(Value.BoolV(s.startsWith(t)))
      case (Value.StringV(s), "matchPrefix",  List(Value.StringV(pat))) =>
        val m = java.util.regex.Pattern.compile(pat).matcher(s)
        if m.lookingAt() then Pure(Value.OptionV(Some(Value.StringV(s.substring(0, m.end())))))
        else Pure(Value.OptionV(None))
      case (Value.StringV(s), "endsWith",     List(Value.StringV(t))) => Pure(Value.BoolV(s.endsWith(t)))
      case (Value.StringV(s), "split",        List(Value.StringV(sep))) =>
        Pure(Value.ListV(s.split(java.util.regex.Pattern.quote(sep)).toList.map(Value.StringV(_))))
      case (Value.StringV(s), "mkString",     _)  => Pure(Value.StringV(s))
      case (Value.StringV(s), "take",         List(Value.IntV(n))) => Pure(Value.StringV(s.take(n.toInt)))
      case (Value.StringV(s), "drop",         List(Value.IntV(n))) => Pure(Value.StringV(s.drop(n.toInt)))
      case (Value.StringV(s), "substring",    List(Value.IntV(a))) =>
        Pure(Value.StringV(s.substring(a.toInt.max(0).min(s.length))))
      case (Value.StringV(s), "substring",    List(Value.IntV(a), Value.IntV(b))) =>
        val from = a.toInt.max(0).min(s.length)
        val to   = b.toInt.max(from).min(s.length)
        Pure(Value.StringV(s.substring(from, to)))
      case (Value.StringV(s), "replace",      List(Value.StringV(a), Value.StringV(b))) => Pure(Value.StringV(s.replace(a, b)))
      case (Value.StringV(s), "charAt",       List(Value.IntV(i))) =>
        if i < 0 || i >= s.length then interp.located(s"index $i out of bounds for string of length ${s.length}")
        else Pure(Value.CharV(s.charAt(i.toInt)))
      case (Value.StringV(s), "apply",        List(Value.IntV(i))) =>
        if i < 0 || i >= s.length then interp.located(s"index $i out of bounds for string of length ${s.length}")
        else Pure(Value.CharV(s.charAt(i.toInt)))
      case (Value.StringV(s), "head",         Nil) =>
        if s.isEmpty then interp.located("head on empty String") else Pure(Value.CharV(s.head))
      case (Value.StringV(s), "last",         Nil) =>
        if s.isEmpty then interp.located("last on empty String") else Pure(Value.CharV(s.last))
      case (Value.StringV(s), "indexOf",      List(Value.StringV(t))) => Pure(Value.intV(s.indexOf(t).toLong))
      case (Value.StringV(s), "indexOf",      List(Value.CharV(c)))   => Pure(Value.intV(s.indexOf(c.toInt).toLong))
      case (Value.StringV(s), "codePointAt",  List(Value.IntV(i)))    =>
        if i < 0 || i >= s.length then interp.located(s"index $i out of bounds for string of length ${s.length}")
        else Pure(Value.intV(s.codePointAt(i.toInt).toLong))
      // ── Char ────────────────────────────────────────────────────
      case (Value.CharV(c), "toInt",      Nil) => Pure(Value.intV(c.toInt.toLong))
      case (Value.CharV(c), "toLong",     Nil) => Pure(Value.intV(c.toLong))
      case (Value.CharV(c), "toString",   Nil) => Pure(Value.StringV(c.toString))
      case (Value.CharV(c), "isDigit",    Nil) => Pure(Value.BoolV(c.isDigit))
      case (Value.CharV(c), "isLetter",   Nil) => Pure(Value.BoolV(c.isLetter))
      case (Value.StringV(s), "map",          List(f)) =>
        Computation.sequence(s.toList.map(c => interp.callValue(f, List(Value.CharV(c)), env))).map {
          case Value.ListV(items) => Value.StringV(items.map(Value.show).mkString)
          case _                  => Value.StringV(s)
        }
      case (Value.StringV(s), "takeWhile",    List(f)) =>
        def loop(i: Int): Computation =
          if i >= s.length then Pure(Value.StringV(s))
          else interp.callValue(f, List(Value.CharV(s.charAt(i))), env).flatMap {
            case Value.BoolV(true) => loop(i + 1)
            case _                 => Pure(Value.StringV(s.substring(0, i)))
          }
        loop(0)
      case (Value.StringV(s), "dropWhile",    List(f)) =>
        def loop(i: Int): Computation =
          if i >= s.length then Pure(Value.StringV(""))
          else interp.callValue(f, List(Value.CharV(s.charAt(i))), env).flatMap {
            case Value.BoolV(true) => loop(i + 1)
            case _                 => Pure(Value.StringV(s.substring(i)))
          }
        loop(0)
      // ── List ────────────────────────────────────────────────────
      case (Value.ListV(ls), "length",     Nil)  => Pure(Value.intV(ls.length.toLong))
      case (Value.ListV(ls), "size",       Nil)  => Pure(Value.intV(ls.size.toLong))
      case (Value.ListV(ls), "indices",    Nil)  => Pure(Value.ListV(ls.indices.map(i => Value.intV(i.toLong)).toList))
      case (Value.ListV(ls), "apply",      List(Value.IntV(i))) =>
        if i < 0 || i >= ls.length then interp.located(s"index $i out of bounds for list of length ${ls.length}")
        else Pure(ls(i.toInt))
      case (Value.ListV(ls), "isEmpty",    Nil)  => Pure(Value.BoolV(ls.isEmpty))
      case (Value.ListV(ls), "nonEmpty",   Nil)  => Pure(Value.BoolV(ls.nonEmpty))
      case (Value.ListV(ls), "head",       Nil)  => Pure(ls.headOption.getOrElse(interp.located("head on Nil")))
      case (Value.ListV(ls), "tail",       Nil)  => Pure(Value.ListV(ls.tail))
      case (Value.ListV(ls), "last",       Nil)  => Pure(ls.lastOption.getOrElse(interp.located("last on Nil")))
      case (Value.ListV(ls), "init",       Nil)  => Pure(Value.ListV(ls.init))
      case (Value.ListV(ls), "reverse",    Nil)  => Pure(Value.ListV(ls.reverse))
      case (Value.ListV(ls), "distinct",   Nil)  => Pure(Value.ListV(ls.distinct))
      case (Value.ListV(ls), "sorted",     Nil)  => Pure(Value.ListV(ls.sortBy(Value.show)))
      case (Value.ListV(ls), "toList",     Nil)  => Pure(Value.ListV(ls))
      case (Value.ListV(ls), "toSet",      Nil)  => Pure(Value.ListV(ls.distinct))
      case (Value.ListV(ls), "contains",   List(v)) => Pure(Value.BoolV(ls.contains(v)))
      case (Value.ListV(ls), "indexOf",    List(v)) => Pure(Value.intV(ls.indexOf(v).toLong))
      case (Value.ListV(ls), "take",       List(Value.IntV(n))) => Pure(Value.ListV(ls.take(n.toInt)))
      case (Value.ListV(ls), "drop",       List(Value.IntV(n))) => Pure(Value.ListV(ls.drop(n.toInt)))
      case (Value.ListV(ls), "splitAt",    List(Value.IntV(n))) =>
        val (a, b) = ls.splitAt(n.toInt)
        Pure(Value.TupleV(List(Value.ListV(a), Value.ListV(b))))
      case (Value.ListV(ls), "takeRight",  List(Value.IntV(n))) => Pure(Value.ListV(ls.takeRight(n.toInt)))
      case (Value.ListV(ls), "dropRight",  List(Value.IntV(n))) => Pure(Value.ListV(ls.dropRight(n.toInt)))
      // Higher-order: sequence the callback computations
      case (Value.ListV(ls), "map",        List(f)) =>
        Computation.sequence(ls.map(item => interp.callValue(f, List(item), env)))
      case (Value.ListV(ls), "flatMap",    List(f)) =>
        Computation.sequence(ls.map(item => interp.callValue(f, List(item), env))).map {
          case Value.ListV(results) => Value.ListV(results.flatMap {
            case Value.ListV(inner) => inner
            case v                  => List(v)
          })
          case other => other
        }
      case (Value.ListV(ls), "filter",     List(f)) =>
        Computation.sequence(ls.map(item => interp.callValue(f, List(item), env))).map {
          case Value.ListV(flags) =>
            Value.ListV(ls.zip(flags).collect { case (v, Value.BoolV(true)) => v })
          case other => other
        }
      case (Value.ListV(ls), "filterNot",  List(f)) =>
        Computation.sequence(ls.map(item => interp.callValue(f, List(item), env))).map {
          case Value.ListV(flags) =>
            Value.ListV(ls.zip(flags).collect { case (v, Value.BoolV(false)) => v })
          case other => other
        }
      case (Value.ListV(ls), "foreach",    List(f)) =>
        Computation.sequence(ls.map(item => interp.callValue(f, List(item), env))).map(_ => Value.UnitV)
      case (Value.ListV(ls), "count",      List(f)) =>
        Computation.sequence(ls.map(item => interp.callValue(f, List(item), env))).map {
          case Value.ListV(flags) =>
            Value.intV(flags.count { case Value.BoolV(true) => true; case _ => false }.toLong)
          case _ => Value.intV(0)
        }
      case (Value.ListV(ls), "find",       List(f)) =>
        def loop(remaining: List[Value]): Computation = remaining match
          case Nil => Pure(Value.OptionV(None))
          case h :: rest =>
            interp.callValue(f, List(h), env).flatMap {
              case Value.BoolV(true) => Pure(Value.OptionV(Some(h)))
              case _                 => loop(rest)
            }
        loop(ls)
      case (Value.ListV(ls), "exists",     List(f)) =>
        def loop(remaining: List[Value]): Computation = remaining match
          case Nil => Pure(Value.BoolV(false))
          case h :: rest =>
            interp.callValue(f, List(h), env).flatMap {
              case Value.BoolV(true) => Pure(Value.BoolV(true))
              case _                 => loop(rest)
            }
        loop(ls)
      case (Value.ListV(ls), "forall",     List(f)) =>
        def loop(remaining: List[Value]): Computation = remaining match
          case Nil => Pure(Value.BoolV(true))
          case h :: rest =>
            interp.callValue(f, List(h), env).flatMap {
              case Value.BoolV(false) => Pure(Value.BoolV(false))
              case _                  => loop(rest)
            }
        loop(ls)
      case (Value.ListV(ls), "sortBy",     List(f)) =>
        Computation.sequence(ls.map(item => interp.callValue(f, List(item), env))).map {
          case Value.ListV(keys) =>
            Value.ListV(ls.zip(keys).sortBy(p => Value.show(p._2)).map(_._1))
          case _ => Value.ListV(ls)
        }
      case (Value.ListV(ls), "zip",        List(Value.ListV(other))) =>
        Pure(Value.ListV(ls.zip(other).map { case (a, b) => Value.TupleV(List(a, b)) }))
      case (Value.ListV(ls), "zipWithIndex", Nil) =>
        Pure(Value.ListV(ls.zipWithIndex.map { case (a, i) => Value.TupleV(List(a, Value.intV(i.toLong))) }))
      case (Value.ListV(ls), "mkString",   Nil)  => Pure(Value.StringV(ls.map(Value.show).mkString))
      case (Value.ListV(ls), "mkString",   List(Value.StringV(sep))) =>
        Pure(Value.StringV(ls.map(Value.show).mkString(sep)))
      case (Value.ListV(ls), "mkString",   List(Value.StringV(s), Value.StringV(sep), Value.StringV(e))) =>
        Pure(Value.StringV(ls.map(Value.show).mkString(s, sep, e)))
      case (Value.ListV(ls), "sum",        Nil)  =>
        Pure(ls.foldLeft[Value](Value.intV(0)) {
          case (Value.IntV(a),    Value.IntV(b))    => Value.intV(a + b)
          case (Value.DoubleV(a), Value.DoubleV(b)) => Value.DoubleV(a + b)
          case (Value.IntV(a),    Value.DoubleV(b)) => Value.DoubleV(a + b)
          case (Value.DoubleV(a), Value.IntV(b))    => Value.DoubleV(a + b)
          case (a, b) => interp.located(s"Cannot sum $a and $b")
        })
      case (Value.ListV(ls), "min",        Nil)  =>
        Pure(ls.minBy { case Value.IntV(n) => n.toDouble; case Value.DoubleV(d) => d; case _ => 0.0 })
      case (Value.ListV(ls), "max",        Nil)  =>
        Pure(ls.maxBy { case Value.IntV(n) => n.toDouble; case Value.DoubleV(d) => d; case _ => 0.0 })
      case (Value.ListV(ls), "foldLeft",   List(init)) =>
        Pure(Value.NativeFnV("foldLeft", {
          case List(f) =>
            def loop(remaining: List[Value], acc: Value): Computation = remaining match
              case Nil       => Pure(acc)
              case h :: rest => interp.callValue(f, List(acc, h), env).flatMap(v => loop(rest, v))
            loop(ls, init)
          case _ => throw InterpretError("foldLeft expects one function argument")
        }))
      case (Value.ListV(ls), "foldRight",  List(init)) =>
        Pure(Value.NativeFnV("foldRight", {
          case List(f) =>
            def loop(remaining: List[Value], acc: Value): Computation = remaining match
              case Nil       => Pure(acc)
              case h :: rest => interp.callValue(f, List(h, acc), env).flatMap(v => loop(rest, v))
            loop(ls.reverse, init)
          case _ => throw InterpretError("foldRight expects one function argument")
        }))
      case (Value.ListV(ls), "reduceLeft", List(f)) =>
        ls match
          case Nil => interp.located("reduceLeft on empty list")
          case h :: t =>
            def loop(remaining: List[Value], acc: Value): Computation = remaining match
              case Nil       => Pure(acc)
              case x :: rest => interp.callValue(f, List(acc, x), env).flatMap(v => loop(rest, v))
            loop(t, h)
      case (Value.ListV(ls), "flatten",    Nil) =>
        Pure(Value.ListV(ls.flatMap { case Value.ListV(inner) => inner; case v => List(v) }))
      case (Value.ListV(ls), "sliding",    List(Value.IntV(n))) =>
        Pure(Value.ListV(ls.sliding(n.toInt).map(Value.ListV(_)).toList))
      case (Value.ListV(ls), "grouped",    List(Value.IntV(n))) =>
        Pure(Value.ListV(ls.grouped(n.toInt).map(Value.ListV(_)).toList))
      case (Value.ListV(ls), "appended",   List(v)) => Pure(Value.ListV(ls :+ v))
      case (Value.ListV(ls), "prepended",  List(v)) => Pure(Value.ListV(v +: ls))
      // ── Map ─────────────────────────────────────────────────────
      case (Value.MapV(m), "size",       Nil)     => Pure(Value.intV(m.size.toLong))
      case (Value.MapV(m), "isEmpty",    Nil)     => Pure(Value.BoolV(m.isEmpty))
      case (Value.MapV(m), "nonEmpty",   Nil)     => Pure(Value.BoolV(m.nonEmpty))
      case (Value.MapV(m), "keys",       Nil)     => Pure(Value.ListV(m.keys.toList))
      case (Value.MapV(m), "values",     Nil)     => Pure(Value.ListV(m.values.toList))
      case (Value.MapV(m), "toList",     Nil)     =>
        Pure(Value.ListV(m.toList.map { (k, v) => Value.TupleV(List(k, v)) }))
      case (Value.MapV(m), "contains",   List(k)) => Pure(Value.BoolV(m.contains(k)))
      case (Value.MapV(m), "get",        List(k)) => Pure(Value.OptionV(m.get(k)))
      case (Value.MapV(m), "apply",      List(k)) =>
        Pure(m.getOrElse(k, interp.located(s"Key not found: ${Value.show(k)}")))
      case (Value.MapV(m), "getOrElse",  List(k, d)) => Pure(m.getOrElse(k, d))
      case (Value.MapV(m), "updated",    List(k, v)) => Pure(Value.MapV(m + (k -> v)))
      case (Value.MapV(m), "removed",    List(k))    => Pure(Value.MapV(m - k))
      // Scala syntax: `m + (k -> v)` parses as `m.+((k, v))` — accept the
      // tupled form as a shortcut for `.updated`, and `++` for map merge.
      case (Value.MapV(m), "+",  List(Value.TupleV(List(k, v))))  => Pure(Value.MapV(m + (k -> v)))
      case (Value.MapV(m), "++", List(Value.MapV(other)))         => Pure(Value.MapV(m ++ other))
      case (Value.MapV(m), "-",  List(k))                          => Pure(Value.MapV(m - k))
      case (Value.MapV(m), "map",        List(f)) =>
        Computation.sequence(m.toList.map { (k, v) =>
          interp.callValue(f, List(Value.TupleV(List(k, v))), env)
        }).map {
          case Value.ListV(entries) =>
            Value.MapV(entries.collect {
              case Value.TupleV(List(nk, nv)) => nk -> nv
            }.toMap)
          case _ => Value.MapV(Map.empty)
        }
      case (Value.MapV(m), "filter",     List(f)) =>
        val items = m.toList
        Computation.sequence(items.map { (k, v) =>
          interp.callValue(f, List(Value.TupleV(List(k, v))), env)
        }).map {
          case Value.ListV(flags) =>
            Value.MapV(items.zip(flags).collect {
              case ((k, v), Value.BoolV(true)) => k -> v
            }.toMap)
          case _ => Value.MapV(Map.empty)
        }
      case (Value.MapV(m), "foreach",    List(f)) =>
        Computation.sequence(m.toList.map { (k, v) =>
          interp.callValue(f, List(Value.TupleV(List(k, v))), env)
        }).map(_ => Value.UnitV)
      case (Value.MapV(m), "mkString",   Nil)  => Pure(Value.StringV(Value.show(Value.MapV(m))))
      // String-key access shorthand: map.key
      case (Value.MapV(m), key, Nil) =>
        Pure(m.getOrElse(Value.StringV(key), interp.located(s"No key '$key' in map")))
      // ── Option ──────────────────────────────────────────────────
      case (Value.OptionV(Some(v)), "get",        Nil) => Pure(v)
      case (Value.OptionV(opt),     "isDefined",  Nil) => Pure(Value.BoolV(opt.isDefined))
      case (Value.OptionV(opt),     "isEmpty",    Nil) => Pure(Value.BoolV(opt.isEmpty))
      case (Value.OptionV(opt),     "nonEmpty",   Nil) => Pure(Value.BoolV(opt.nonEmpty))
      case (Value.OptionV(opt),     "contains",   List(v)) => Pure(Value.BoolV(opt.contains(v)))
      case (Value.OptionV(Some(v)), "getOrElse",  _)   => Pure(v)
      case (Value.OptionV(None),    "getOrElse",  List(d)) => Pure(d)
      case (Value.OptionV(opt),     "map",        List(f)) =>
        opt match
          case None    => Pure(Value.OptionV(None))
          case Some(v) => interp.callValue(f, List(v), env).map(r => Value.OptionV(Some(r)))
      case (Value.OptionV(opt),     "flatMap",    List(f)) =>
        opt match
          case None    => Pure(Value.OptionV(None))
          case Some(v) => interp.callValue(f, List(v), env).map {
            case o: Value.OptionV => o
            case other            => Value.OptionV(Some(other))
          }
      case (Value.OptionV(opt),     "filter",     List(f)) =>
        opt match
          case None    => Pure(Value.OptionV(None))
          case Some(v) => interp.callValue(f, List(v), env).map {
            case Value.BoolV(true) => Value.OptionV(Some(v))
            case _                 => Value.OptionV(None)
          }
      case (Value.OptionV(opt),     "foreach",    List(f)) =>
        opt match
          case None    => Pure(Value.UnitV)
          case Some(v) => interp.callValue(f, List(v), env).map(_ => Value.UnitV)
      case (Value.OptionV(opt),     "toList",     Nil) => Pure(Value.ListV(opt.toList))
      case (Value.OptionV(None),    "orElse",     List(other)) => Pure(other)
      case (opt: Value.OptionV,     "orElse",     _) => Pure(opt)
      // ── Int / Double ─────────────────────────────────────────────
      case (Value.IntV(n),    "toDouble",  Nil) => Pure(Value.DoubleV(n.toDouble))
      case (Value.IntV(n),    "toLong",    Nil) => Pure(Value.intV(n))
      case (Value.IntV(n),    "toInt",     Nil) => Pure(Value.intV(n))
      case (Value.IntV(n),    "abs",       Nil) => Pure(Value.intV(math.abs(n)))
      case (Value.IntV(n),    "toString",  Nil) => Pure(Value.StringV(n.toString))
      case (Value.IntV(n),    "to",        List(Value.IntV(m))) =>
        Pure(Value.ListV((n to m).map(Value.IntV(_)).toList))
      case (Value.IntV(n),    "until",     List(Value.IntV(m))) =>
        Pure(Value.ListV((n until m).map(Value.IntV(_)).toList))
      case (Value.DoubleV(d), "toInt",     Nil) => Pure(Value.intV(d.toLong))
      case (Value.DoubleV(d), "toLong",    Nil) => Pure(Value.intV(d.toLong))
      case (Value.DoubleV(d), "abs",       Nil) => Pure(Value.DoubleV(math.abs(d)))
      case (Value.DoubleV(d), "toString",  Nil) => Pure(Value.StringV(d.toString))
      case (Value.DoubleV(d), "round",     Nil) => Pure(Value.intV(math.round(d)))
      case (Value.DoubleV(d), "floor",     Nil) => Pure(Value.DoubleV(math.floor(d)))
      case (Value.DoubleV(d), "ceil",      Nil) => Pure(Value.DoubleV(math.ceil(d)))
      // ── Tuple ────────────────────────────────────────────────────
      case (Value.TupleV(as), "++", List(Value.TupleV(bs))) => Pure(Value.TupleV(as ++ bs))
      case (Value.TupleV(as), "++", List(Value.UnitV))      => Pure(Value.TupleV(as))
      case (Value.UnitV,      "++", List(Value.TupleV(bs))) => Pure(Value.TupleV(bs))
      case (Value.UnitV,      "++", List(Value.UnitV))      => Pure(Value.UnitV)
      case (Value.TupleV(es), "_1", Nil) => Pure(es(0))
      case (Value.TupleV(es), "_2", Nil) => Pure(es(1))
      case (Value.TupleV(es), "_3", Nil) => Pure(es(2))
      case (Value.TupleV(es), "_4", Nil) => Pure(es(3))
      // ── Signal methods (reactive) — must precede the generic InstanceV
      // field-access paths so `.get`/`.set` don't fall into them. ──
      case (Value.InstanceV("Signal", fields), "get", Nil) =>
        fields.get("id") match
          case Some(Value.IntV(id)) => Pure(SignalRuntime.signalGet(interp, id))
          case _                    => interp.located("Signal handle missing id")
      case (Value.InstanceV("Signal", fields), "set", List(v)) =>
        fields.get("id") match
          case Some(Value.IntV(id)) => SignalRuntime.signalSet(interp, id, v); Pure(Value.UnitV)
          case _                    => interp.located("Signal handle missing id")
      case (Value.InstanceV("Signal", fields), "apply", Nil) =>
        // s() — sugar for s.get
        fields.get("id") match
          case Some(Value.IntV(id)) => Pure(SignalRuntime.signalGet(interp, id))
          case _                    => interp.located("Signal handle missing id")
      // Plugin-provided frontend signal bridge. The streams plugin registers
      // `ReactiveSignal.bind(signal, source)`; dispatch keeps method syntax
      // (`sig.bind(source)`) without hard-coding streams into the core.
      case (recv @ Value.Foreign("ReactiveSignal", _), "bind", List(source)) =>
        interp.globals.get("ReactiveSignal.bind") match
          case Some(fn) => interp.callValue(fn, List(recv, source), env)
          case None     => interp.located("No method 'bind' on ReactiveSignal")
      // ── Class method (declared inside `class`/`case class` body) ──
      case (Value.InstanceV(typeName, fields), fname, fargs)
        if interp.typeMethods.get(typeName).exists(_.contains(fname)) =>
        val fn = interp.typeMethods(typeName)(fname)
        // Re-bind the method's closure with this instance's data fields so the
        // body can refer to them by name (`x`, `y`, …).
        interp.callFun(fn.copy(closure = fn.closure ++ fields), fargs)
      // ── Response builder methods (cookie sessions) ───────────────
      // `resp.withSession(Map(...))` / `resp.clearSession()` attach a
      // `setSession` field; the HTTP runtime turns that into a Set-Cookie.
      // Must precede the InstanceV no-arg / enum-companion cases below so
      // `clearSession()` and `withSession(...)` aren't shadowed by field
      // lookup on the Response instance.
      case (Value.InstanceV("Response", fields), "withSession", List(Value.MapV(m))) =>
        Pure(Value.InstanceV("Response", fields + ("setSession" -> Value.MapV(m))))
      case (Value.InstanceV("Response", fields), "clearSession", Nil) =>
        Pure(Value.InstanceV("Response", fields + ("setSession" -> Value.MapV(Map.empty))))
      // `resp.withHeader("X-Trace-Id", "abc")` — used by std/middleware.ssc
      // to attach observability headers without rebuilding the Response
      // by hand.  Merges into the existing `headers` Map; later
      // withHeader calls overwrite earlier ones for the same key.
      case (Value.InstanceV("Response", fields), "withHeader", List(Value.StringV(name), Value.StringV(value))) =>
        val existing = fields.get("headers") match
          case Some(Value.MapV(m)) => m
          case _                   => Map.empty[Value, Value]
        val merged = existing + (Value.StringV(name) -> Value.StringV(value))
        Pure(Value.InstanceV("Response", fields + ("headers" -> Value.MapV(merged))))
      // ── Either (Left / Right) methods ────────────────────────────
      case (Value.InstanceV("Right", _),      "isRight",   Nil) => Pure(Value.BoolV(true))
      case (Value.InstanceV("Left",  _),      "isRight",   Nil) => Pure(Value.BoolV(false))
      case (Value.InstanceV("Right", _),      "isLeft",    Nil) => Pure(Value.BoolV(false))
      case (Value.InstanceV("Left",  _),      "isLeft",    Nil) => Pure(Value.BoolV(true))
      case (Value.InstanceV("Right", fields), "getOrElse", List(_)) =>
        Pure(fields.getOrElse("value", Value.UnitV))
      case (Value.InstanceV("Left",  _),      "getOrElse", List(d)) => Pure(d)
      case (Value.InstanceV("Right", fields), "map",       List(f)) =>
        interp.callValue(f, List(fields.getOrElse("value", Value.UnitV)), env).map(v =>
          Value.InstanceV("Right", Map("value" -> v)))
      case (Value.InstanceV("Left",  _),      "map",       List(_)) => Pure(recv)
      case (Value.InstanceV("Right", fields), "flatMap",   List(f)) =>
        interp.callValue(f, List(fields.getOrElse("value", Value.UnitV)), env)
      case (Value.InstanceV("Left",  _),      "flatMap",   List(_)) => Pure(recv)
      case (Value.InstanceV("Right", fields), "fold",      List(_, r)) =>
        interp.callValue(r, List(fields.getOrElse("value", Value.UnitV)), env)
      case (Value.InstanceV("Left",  fields), "fold",      List(l, _)) =>
        interp.callValue(l, List(fields.getOrElse("value", Value.UnitV)), env)
      case (Value.InstanceV("Right", fields), "toOption",  Nil) =>
        Pure(Value.OptionV(Some(fields.getOrElse("value", Value.UnitV))))
      case (Value.InstanceV("Left",  _),      "toOption",  Nil) =>
        Pure(Value.OptionV(None))
      case (Value.InstanceV("Right", fields), "swap",      Nil) =>
        Pure(Value.InstanceV("Left",  fields))
      case (Value.InstanceV("Left",  fields), "swap",      Nil) =>
        Pure(Value.InstanceV("Right", fields))
      case (Value.InstanceV("Right", fields), "toSeq",     Nil) =>
        Pure(Value.ListV(List(fields.getOrElse("value", Value.UnitV))))
      case (Value.InstanceV("Left",  _),      "toSeq",     Nil) =>
        Pure(Value.ListV(Nil))
      // ── Exception .getMessage — alias for .message field ─────────
      case (Value.InstanceV(_, fields), "getMessage", Nil) =>
        Pure(fields.getOrElse("message", Value.StringV("")))
      // ── Instance (case class / enum case) field access ───────────
      // No-arg defs and no-arg native fns are called automatically on access
      case (Value.InstanceV(_, fields), fname, Nil) =>
        fields.get(fname) match
          case Some(f: Value.FunV)      if f.params.isEmpty => interp.callFun(f, Nil)
          case Some(f: Value.NativeFnV)                     => f.f(Nil)
          case Some(v)                                       => Pure(v)
          // Scala's auto-generated `toString` on case classes / enum cases:
          // fall through to the generic Value.show path so users get the
          // expected "Circle(3.0)" rendering without having to define it.
          case None if fname == "toString"                   => Pure(Value.StringV(Value.show(recv)))
          // Fall through to extension method dispatch before erroring.
          case None =>
            extensionDispatch(recv, fname, Nil, env, interp)
              .getOrElse(interp.located(s"No field '$fname'"))
      // ── Enum companion call (Color.RGB(1,2,3)) ───────────────────
      case (Value.InstanceV(_, fields), fname, fargs) if fields.contains(fname) =>
        interp.callValue(fields(fname), fargs, env)
      // ── Generic ──────────────────────────────────────────────────
      case (v, "toString", Nil)   => Pure(Value.StringV(Value.show(v)))
      case (v, "apply",    fargs) => interp.callValue(v, fargs, env)
      // ── Extension method via given: "hello".show → Show[String].show("hello")
      case _ =>
        extensionDispatch(recv, name, args, env, interp)
          .getOrElse(interp.located(s"No method '$name' on ${recv.getClass.getSimpleName}(${Value.show(recv)})"))

  def infix(lhs: Value, op: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    val rhs = args.headOption.getOrElse(Value.UnitV)
    (lhs, op, rhs) match
      case (Value.InstanceV("AttrKey", fields), ":=", v) =>
        val name = fields.get("name").map(Value.show).getOrElse("")
        Pure(Value.InstanceV("Attr", Map(
          "name"  -> Value.StringV(name),
          "value" -> Value.StringV(Value.show(v))
        )))
      case (pid @ Value.InstanceV("Pid", _), "!", msg) =>
        Perform("Actor", "send", List(pid, msg))
      case (Value.IntV(a),    "+",  Value.IntV(b))    => Pure(Value.intV(a + b))
      case (Value.IntV(a),    "-",  Value.IntV(b))    => Pure(Value.intV(a - b))
      case (Value.IntV(a),    "*",  Value.IntV(b))    => Pure(Value.intV(a * b))
      case (Value.IntV(a),    "/",  Value.IntV(b))    => Pure(Value.intV(a / b))
      case (Value.IntV(a),    "%",  Value.IntV(b))    => Pure(Value.intV(a % b))
      case (Value.DoubleV(a), "+",  Value.DoubleV(b)) => Pure(Value.DoubleV(a + b))
      case (Value.DoubleV(a), "-",  Value.DoubleV(b)) => Pure(Value.DoubleV(a - b))
      case (Value.DoubleV(a), "*",  Value.DoubleV(b)) => Pure(Value.DoubleV(a * b))
      case (Value.DoubleV(a), "/",  Value.DoubleV(b)) => Pure(Value.DoubleV(a / b))
      case (Value.IntV(a),    "+",  Value.DoubleV(b)) => Pure(Value.DoubleV(a + b))
      case (Value.DoubleV(a), "+",  Value.IntV(b))    => Pure(Value.DoubleV(a + b))
      case (Value.IntV(a),    "*",  Value.DoubleV(b)) => Pure(Value.DoubleV(a * b))
      case (Value.DoubleV(a), "*",  Value.IntV(b))    => Pure(Value.DoubleV(a * b))
      case (Value.IntV(a),    "-",  Value.DoubleV(b)) => Pure(Value.DoubleV(a - b))
      case (Value.DoubleV(a), "-",  Value.IntV(b))    => Pure(Value.DoubleV(a - b))
      case (Value.IntV(a),    "/",  Value.DoubleV(b)) => Pure(Value.DoubleV(a / b))
      case (Value.DoubleV(a), "/",  Value.IntV(b))    => Pure(Value.DoubleV(a / b))
      case (Value.StringV(a), "+",  b)                => Pure(Value.StringV(a + Value.show(b)))
      case (Value.StringV(a), "*",  Value.IntV(n))    => Pure(Value.StringV(a * n.toInt))
      case (a, "==",  b) => Pure(Value.BoolV(a == b))
      case (a, "!=",  b) => Pure(Value.BoolV(a != b))
      case (Value.IntV(a),    "<",  Value.IntV(b))    => Pure(Value.BoolV(a < b))
      case (Value.IntV(a),    ">",  Value.IntV(b))    => Pure(Value.BoolV(a > b))
      case (Value.IntV(a),    "<=", Value.IntV(b))    => Pure(Value.BoolV(a <= b))
      case (Value.IntV(a),    ">=", Value.IntV(b))    => Pure(Value.BoolV(a >= b))
      case (Value.DoubleV(a), "<",  Value.DoubleV(b)) => Pure(Value.BoolV(a < b))
      case (Value.DoubleV(a), ">",  Value.DoubleV(b)) => Pure(Value.BoolV(a > b))
      case (Value.DoubleV(a), "<=", Value.DoubleV(b)) => Pure(Value.BoolV(a <= b))
      case (Value.DoubleV(a), ">=", Value.DoubleV(b)) => Pure(Value.BoolV(a >= b))
      case (Value.BoolV(a),   "&&", Value.BoolV(b))   => Pure(Value.BoolV(a && b))
      case (Value.BoolV(a),   "||", Value.BoolV(b))   => Pure(Value.BoolV(a || b))
      case (v, "::",  Value.ListV(ls))                => Pure(Value.ListV(v :: ls))
      case (Value.ListV(a), "++", Value.ListV(b))     => Pure(Value.ListV(a ++ b))
      case (Value.ListV(a), ":::", Value.ListV(b))    => Pure(Value.ListV(a ++ b))
      case (Value.ListV(ls), ":+", v)                 => Pure(Value.ListV(ls :+ v))
      case (v, "+:",  Value.ListV(ls))                => Pure(Value.ListV(v +: ls))
      case (k, "->", v)                               => Pure(Value.TupleV(List(k, v)))
      case _ => dispatch(lhs, op, args, env, interp)

  private def extensionDispatch(recv: Value, method: String, args: List[Value], env: Env, interp: Interpreter): Option[Computation] =
    val typeName = recv match
      case _: Value.IntV        => "Int"
      case _: Value.DoubleV     => "Double"
      case _: Value.StringV     => "String"
      case _: Value.BoolV       => "Boolean"
      case _: Value.ListV       => "List"
      case _: Value.OptionV     => "Option"
      case _: Value.MapV        => "Map"
      case Value.TupleV(elems)  => s"Tuple${elems.length}"
      case Value.InstanceV(t,_) => t
      case _                    => "Any"
    interp.extensions.get((typeName, method)).map { fn =>
      interp.callValue(fn, recv :: args, env)
    }.orElse {
      // Walk the parent chain (e.g. Right → Either, PChar → Parser, Red → Color).
      var parent: Option[String] = interp.parentTypes.get(typeName)
      var found: Option[Computation] = None
      while parent.isDefined && found.isEmpty do
        found = interp.extensions.get((parent.get, method)).map(fn => interp.callValue(fn, recv :: args, env))
        parent = interp.parentTypes.get(parent.get)
      found
    }.orElse {
      interp.globals.values.collectFirst {
        case Value.InstanceV(name, fields)
          if name.endsWith(s"[$typeName]") && fields.contains(method) =>
          interp.callValue(fields(method), recv :: args, env)
      }
    }
