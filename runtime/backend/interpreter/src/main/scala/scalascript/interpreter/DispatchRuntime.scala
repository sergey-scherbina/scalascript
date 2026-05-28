package scalascript.interpreter

import Computation.{Pure, Perform}

/** Built-in method dispatch: String, Char, Int, Double, Boolean, List, Option, Map,
 *  Tuple, Either, and instance field access.  User-defined extensions are checked
 *  first (via `interp.extensions`), then type-dispatched built-in methods.
 *
 *  v1.61.1 — Reorganized from a flat 300-case tuple match to a two-level dispatch:
 *  1. `recv match` on the value type (one instanceof check, O(1))
 *  2. `name match` inside each per-type method (Scala 3 compiles to hashCode switch, O(1))
 *  This eliminates the previous O(position-in-list) linear scan.
 *  Extensions early-exit added: if no user extensions are registered the
 *  7-way extension probe is skipped entirely.
 */
private[interpreter] object DispatchRuntime:

  def dispatch(recv: Value, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    // Extensions early-exit: avoid 7 HashMap lookups when no extensions registered.
    if interp.extensions.nonEmpty then
      val userExt = recv match
        case _: Value.OptionV => interp.extensions.get(("Option",  name)).map(interp.callValuePrepend(_, recv, args, env))
        case _: Value.ListV   => interp.extensions.get(("List",    name)).map(interp.callValuePrepend(_, recv, args, env))
        case _: Value.IntV    => interp.extensions.get(("Int",     name)).map(interp.callValuePrepend(_, recv, args, env))
        case _: Value.DoubleV => interp.extensions.get(("Double",  name)).map(interp.callValuePrepend(_, recv, args, env))
        case _: Value.StringV => interp.extensions.get(("String",  name)).map(interp.callValuePrepend(_, recv, args, env))
        case _: Value.BoolV   => interp.extensions.get(("Boolean", name)).map(interp.callValuePrepend(_, recv, args, env))
        case _: Value.MapV    => interp.extensions.get(("Map",     name)).map(interp.callValuePrepend(_, recv, args, env))
        case _                => None
      if userExt.isDefined then return userExt.get
    recv match
      case Value.StringV(s)        => dispatchString(recv, s, name, args, env, interp)
      case Value.ListV(ls)         => dispatchList(ls, name, args, env, interp)
      case Value.MapV(m)           => dispatchMap(m, name, args, env, interp)
      case Value.OptionV(opt)      => dispatchOption(recv, opt, name, args, env, interp)
      case Value.IntV(n)           => dispatchInt(n, name, args, env, interp)
      case Value.DoubleV(d)        => dispatchDouble(d, name, args, env, interp)
      case Value.CharV(c)          => dispatchChar(c, name, args, env, interp)
      case Value.TupleV(es)        => dispatchTuple(es, name, args, env, interp)
      case Value.UnitV             => dispatchUnit(recv, name, args, env, interp)
      case Value.InstanceV(t, f)   => dispatchInstance(recv, t, f, name, args, env, interp)
      case other                   => dispatchFallback(other, name, args, env, interp)

  // ── String ──────────────────────────────────────────────────────────────────

  private def dispatchString(recv: Value, s: String, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    name match
      case "length"      => Computation.pureIntV(s.length.toLong)
      case "size"        => Computation.pureIntV(s.length.toLong)
      case "isEmpty"     => Computation.pureBool(s.isEmpty)
      case "nonEmpty"    => Computation.pureBool(s.nonEmpty)
      case "trim"        => Pure(Value.StringV(s.trim))
      case "toUpperCase" => Pure(Value.StringV(s.toUpperCase))
      case "toLowerCase" => Pure(Value.StringV(s.toLowerCase))
      case "reverse"     => Pure(Value.StringV(s.reverse))
      case "toInt"       => Computation.pureIntV(s.toLong)
      case "toDouble"    => Pure(Value.doubleV(s.toDouble))
      case "toString"    => Pure(recv)
      case "mkString"    => Pure(recv)
      case "contains"    => args match
        case List(Value.StringV(t)) => Computation.pureBool(s.contains(t))
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case "startsWith"  => args match
        case List(Value.StringV(t)) => Computation.pureBool(s.startsWith(t))
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case "endsWith"    => args match
        case List(Value.StringV(t)) => Computation.pureBool(s.endsWith(t))
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case "matchPrefix" => args match
        case List(Value.StringV(pat)) =>
          val m = java.util.regex.Pattern.compile(pat).matcher(s)
          if m.lookingAt() then Pure(Value.OptionV(Some(Value.StringV(s.substring(0, m.end())))))
          else Computation.PureNone
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "split"       => args match
        case List(Value.StringV(sep)) =>
          Pure(Value.ListV(s.split(java.util.regex.Pattern.quote(sep)).toList.map(Value.StringV(_))))
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "take"        => args match
        case List(Value.IntV(n)) =>
          if n >= s.length then Pure(recv)
          else if n <= 0 then Computation.PureEmptyStr
          else Pure(Value.StringV(s.take(n.toInt)))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "drop"        => args match
        case List(Value.IntV(n)) =>
          if n <= 0 then Pure(recv)
          else if n >= s.length then Computation.PureEmptyStr
          else Pure(Value.StringV(s.drop(n.toInt)))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "substring"   => args match
        case List(Value.IntV(a)) =>
          Pure(Value.StringV(s.substring(a.toInt.max(0).min(s.length))))
        case List(Value.IntV(a), Value.IntV(b)) =>
          val from = a.toInt.max(0).min(s.length)
          val to   = b.toInt.max(from).min(s.length)
          Pure(Value.StringV(s.substring(from, to)))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "replace"     => args match
        case List(Value.StringV(a), Value.StringV(b)) => Pure(Value.StringV(s.replace(a, b)))
        case _                                        => dispatchFallback(recv, name, args, env, interp)
      case "charAt" | "apply" => args match
        case List(Value.IntV(i)) =>
          if i < 0 || i >= s.length then interp.located(s"index $i out of bounds for string of length ${s.length}")
          else Pure(Value.CharV(s.charAt(i.toInt)))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "head"        =>
        if s.isEmpty then interp.located("head on empty String") else Pure(Value.CharV(s.head))
      case "last"        =>
        if s.isEmpty then interp.located("last on empty String") else Pure(Value.CharV(s.last))
      case "indexOf"     => args match
        case List(Value.StringV(t)) => Computation.pureIntV(s.indexOf(t).toLong)
        case List(Value.CharV(c))   => Computation.pureIntV(s.indexOf(c.toInt).toLong)
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case "codePointAt" => args match
        case List(Value.IntV(i)) =>
          if i < 0 || i >= s.length then interp.located(s"index $i out of bounds for string of length ${s.length}")
          else Computation.pureIntV(s.codePointAt(i.toInt).toLong)
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "map"         => args match
        case List(f) =>
          Computation.mapSequenceStr(s, c => interp.callValue1(f, Value.CharV(c), env)).map {
            case Value.ListV(items) => Value.StringV(items.map(Value.show).mkString)
            case _                  => Value.StringV(s)
          }
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "takeWhile"   => args match
        case List(f) =>
          def loop(i: Int): Computation =
            if i >= s.length then Pure(Value.StringV(s))
            else interp.callValue1(f, Value.CharV(s.charAt(i)), env).flatMap {
              case Value.BoolV(true) => loop(i + 1)
              case _                 => Pure(Value.StringV(s.substring(0, i)))
            }
          loop(0)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "dropWhile"   => args match
        case List(f) =>
          def loop(i: Int): Computation =
            if i >= s.length then Computation.PureEmptyStr
            else interp.callValue1(f, Value.CharV(s.charAt(i)), env).flatMap {
              case Value.BoolV(true) => loop(i + 1)
              case _                 => Pure(Value.StringV(s.substring(i)))
            }
          loop(0)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foreach"     => args match
        case List(f) =>
          Computation.mapSequenceStr(s, c => interp.callValue1(f, Value.CharV(c), env)).flatMap(Computation.discardToUnit)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case _ => dispatchFallback(recv, name, args, env, interp)

  // ── Char ────────────────────────────────────────────────────────────────────

  private def dispatchChar(c: Char, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    name match
      case "toInt"    => Computation.pureIntV(c.toInt.toLong)
      case "toLong"   => Computation.pureIntV(c.toLong)
      case "toString" => Pure(Value.StringV(c.toString))
      case "isDigit"  => Computation.pureBool(c.isDigit)
      case "isLetter" => Computation.pureBool(c.isLetter)
      case _ => extensionDispatch(Value.CharV(c), name, args, env, interp)
                  .getOrElse(interp.located(s"No method '$name' on Char"))

  // ── List ────────────────────────────────────────────────────────────────────

  private def dispatchList(ls: List[Value], name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    lazy val recv = Value.ListV(ls)
    name match
      case "length"       => Computation.pureIntV(ls.length.toLong)
      case "size"         => Computation.pureIntV(ls.size.toLong)
      case "isEmpty"      => Computation.pureBool(ls.isEmpty)
      case "nonEmpty"     => Computation.pureBool(ls.nonEmpty)
      case "head"         => if ls.isEmpty then interp.located("head on Nil") else Pure(ls.head)
      case "tail"         => if ls.isEmpty then interp.located("tail on Nil") else if ls.tail.isEmpty then Computation.PureEmptyList else Pure(Value.ListV(ls.tail))
      case "last"         => if ls.isEmpty then interp.located("last on Nil") else Pure(ls.last)
      case "init"         => Pure(Value.ListV(ls.init))
      case "reverse"      => Pure(Value.ListV(ls.reverse))
      case "distinct"     => Pure(Value.ListV(ls.distinct))
      case "sorted"       => Pure(Value.ListV(ls.sortBy(Value.show)))
      case "toList"       => Pure(recv)
      case "toSet"        => Pure(Value.ListV(ls.distinct))
      case "flatten"      => Pure(Value.ListV(ls.flatMap { case Value.ListV(inner) => inner; case v => List(v) }))
      case "sum"          =>
        // Direct accumulator: avoids N intermediate IntV/DoubleV allocations from foldLeft.
        var intAcc   = 0L
        var dblAcc   = 0.0
        var isDouble = false
        var sumErr: Computation = null
        var sumRem = ls
        while sumRem.nonEmpty && sumErr == null do
          sumRem.head match
            case Value.IntV(n)    => intAcc += n
            case Value.DoubleV(d) => dblAcc += d; isDouble = true
            case v                => sumErr = interp.located(s"Cannot sum $v")
          sumRem = sumRem.tail
        if sumErr != null then sumErr
        else if isDouble then Pure(Value.doubleV(intAcc.toDouble + dblAcc))
        else Computation.pureIntV(intAcc)
      case "min"          =>
        Pure(ls.minBy { case Value.IntV(n) => n.toDouble; case Value.DoubleV(d) => d; case _ => 0.0 })
      case "max"          =>
        Pure(ls.maxBy { case Value.IntV(n) => n.toDouble; case Value.DoubleV(d) => d; case _ => 0.0 })
      case "zipWithIndex" =>
        val ziBuf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
        var ziRem = ls; var ziIdx = 0
        while ziRem.nonEmpty do
          ziBuf += Value.TupleV(List(ziRem.head, Value.intV(ziIdx.toLong)))
          ziRem = ziRem.tail; ziIdx += 1
        Pure(Value.ListV(ziBuf.toList))
      case "indices"      =>
        Pure(Value.ListV(ls.indices.map(i => Value.intV(i.toLong)).toList))
      case "contains"     => args match
        case List(v)                  => Computation.pureBool(ls.contains(v))
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "indexOf"      => args match
        case List(v)                  => Computation.pureIntV(ls.indexOf(v).toLong)
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "apply"        => args match
        case List(Value.IntV(i)) =>
          if i < 0 || i >= ls.length then interp.located(s"index $i out of bounds for list of length ${ls.length}")
          else Pure(ls(i.toInt))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "take"         => args match
        case List(Value.IntV(n)) =>
          if n >= ls.length then Pure(recv)
          else if n <= 0 then Computation.PureEmptyList
          else Pure(Value.ListV(ls.take(n.toInt)))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "drop"         => args match
        case List(Value.IntV(n)) =>
          if n <= 0 then Pure(recv)
          else if n >= ls.length then Computation.PureEmptyList
          else Pure(Value.ListV(ls.drop(n.toInt)))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "takeRight"    => args match
        case List(Value.IntV(n)) =>
          if n >= ls.length then Pure(recv)
          else if n <= 0 then Computation.PureEmptyList
          else Pure(Value.ListV(ls.takeRight(n.toInt)))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "dropRight"    => args match
        case List(Value.IntV(n)) =>
          if n <= 0 then Pure(recv)
          else if n >= ls.length then Computation.PureEmptyList
          else Pure(Value.ListV(ls.dropRight(n.toInt)))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "splitAt"      => args match
        case List(Value.IntV(n)) =>
          val (a, b) = ls.splitAt(n.toInt)
          Pure(Value.TupleV(List(Value.ListV(a), Value.ListV(b))))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "appended"     => args match
        case List(v)                  => Pure(Value.ListV(ls :+ v))
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "prepended"    => args match
        case List(v)                  => Pure(Value.ListV(v +: ls))
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "sliding"      => args match
        case List(Value.IntV(n))      => Pure(Value.ListV(ls.sliding(n.toInt).map(Value.ListV(_)).toList))
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "grouped"      => args match
        case List(Value.IntV(n))      => Pure(Value.ListV(ls.grouped(n.toInt).map(Value.ListV(_)).toList))
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "zip"          => args match
        case List(Value.ListV(other)) =>
          val zipBuf = new scala.collection.mutable.ArrayBuffer[Value](ls.length.min(other.length))
          var zipAs = ls; var zipBs = other
          while zipAs.nonEmpty && zipBs.nonEmpty do
            zipBuf += Value.TupleV(List(zipAs.head, zipBs.head))
            zipAs = zipAs.tail; zipBs = zipBs.tail
          Pure(Value.ListV(zipBuf.toList))
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "mkString"     => args match
        case Nil                      => Pure(Value.StringV(ls.map(Value.show).mkString))
        case List(Value.StringV(sep)) => Pure(Value.StringV(ls.map(Value.show).mkString(sep)))
        case List(Value.StringV(s), Value.StringV(sep), Value.StringV(e)) =>
          Pure(Value.StringV(ls.map(Value.show).mkString(s, sep, e)))
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "map"          => args match
        case List(f) => Computation.mapSequence(ls, item => interp.callValue1(f, item, env))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "flatMap"      => args match
        case List(f) =>
          val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil => Pure(Value.ListV(buf.toList))
            case h :: rest =>
              interp.callValue1(f, h, env).flatMap {
                case Value.ListV(inner) => buf ++= inner; loop(rest)
                case v                  => buf += v;      loop(rest)
              }
          loop(ls)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "filter"       => args match
        case List(f) =>
          // Direct loop: avoids intermediate ListV[BoolV] from mapSequence+map.
          val filtBuf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
          def filterLoop(remaining: List[Value]): Computation = remaining match
            case Nil => Pure(Value.ListV(filtBuf.toList))
            case h :: rest =>
              interp.callValue1(f, h, env).flatMap {
                case Value.BoolV(true) => filtBuf += h; filterLoop(rest)
                case _                 => filterLoop(rest)
              }
          filterLoop(ls)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "filterNot"    => args match
        case List(f) =>
          val fnBuf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
          def filterNotLoop(remaining: List[Value]): Computation = remaining match
            case Nil => Pure(Value.ListV(fnBuf.toList))
            case h :: rest =>
              interp.callValue1(f, h, env).flatMap {
                case Value.BoolV(true) => filterNotLoop(rest)
                case _                 => fnBuf += h; filterNotLoop(rest)
              }
          filterNotLoop(ls)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foreach"      => args match
        case List(f) =>
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil    => Computation.PureUnit
            case h :: t => interp.callValue1(f, h, env).flatMap(_ => loop(t))
          loop(ls)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "count"        => args match
        case List(f) =>
          def loop(remaining: List[Value], acc: Long): Computation = remaining match
            case Nil => Computation.pureIntV(acc)
            case h :: rest =>
              interp.callValue1(f, h, env).flatMap {
                case Value.BoolV(true) => loop(rest, acc + 1L)
                case _                 => loop(rest, acc)
              }
          loop(ls, 0L)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "find"         => args match
        case List(f) =>
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil => Computation.PureNone
            case h :: rest =>
              interp.callValue1(f, h, env).flatMap {
                case Value.BoolV(true) => Pure(Value.OptionV(Some(h)))
                case _                 => loop(rest)
              }
          loop(ls)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "exists"       => args match
        case List(f) =>
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil => Computation.PureFalse
            case h :: rest =>
              interp.callValue1(f, h, env).flatMap {
                case Value.BoolV(true) => Computation.PureTrue
                case _                 => loop(rest)
              }
          loop(ls)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "forall"       => args match
        case List(f) =>
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil => Computation.PureTrue
            case h :: rest =>
              interp.callValue1(f, h, env).flatMap {
                case Value.BoolV(false) => Computation.PureFalse
                case _                  => loop(rest)
              }
          loop(ls)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "sortBy"       => args match
        case List(f) =>
          Computation.mapSequence(ls, item => interp.callValue1(f, item, env)).map {
            case Value.ListV(keys) =>
              // Pre-compute sort keys once (O(n)) to avoid O(n log n) Value.show calls
              // during comparison. ls.zip(keys) also creates only one intermediate list.
              val keyed = ls.zip(keys).map { (v, k) => (v, Value.show(k)) }
              Value.ListV(keyed.sortBy(_._2).map(_._1))
            case _ => recv
          }
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foldLeft"     => args match
        case List(init) =>
          Pure(Value.NativeFnV("foldLeft", {
            case List(f) =>
              def loop(remaining: List[Value], acc: Value): Computation = remaining match
                case Nil       => Pure(acc)
                case h :: rest => interp.callValue2(f, acc, h, env).flatMap(v => loop(rest, v))
              loop(ls, init)
            case _ => throw InterpretError("foldLeft expects one function argument")
          }))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foldRight"    => args match
        case List(init) =>
          Pure(Value.NativeFnV("foldRight", {
            case List(f) =>
              def loop(remaining: List[Value], acc: Value): Computation = remaining match
                case Nil       => Pure(acc)
                case h :: rest => interp.callValue2(f, h, acc, env).flatMap(v => loop(rest, v))
              loop(ls.reverse, init)
            case _ => throw InterpretError("foldRight expects one function argument")
          }))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "reduceLeft"   => args match
        case List(f) => ls match
          case Nil => interp.located("reduceLeft on empty list")
          case h :: t =>
            def loop(remaining: List[Value], acc: Value): Computation = remaining match
              case Nil       => Pure(acc)
              case x :: rest => interp.callValue2(f, acc, x, env).flatMap(v => loop(rest, v))
            loop(t, h)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case _ => dispatchFallback(recv, name, args, env, interp)

  // ── Map ─────────────────────────────────────────────────────────────────────

  private def dispatchMap(m: Map[Value, Value], name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    lazy val recv = Value.MapV(m)
    name match
      case "size"     => Computation.pureIntV(m.size.toLong)
      case "isEmpty"  => Computation.pureBool(m.isEmpty)
      case "nonEmpty" => Computation.pureBool(m.nonEmpty)
      case "keys"     => Pure(Value.ListV(m.keys.toList))
      case "values"   => Pure(Value.ListV(m.values.toList))
      case "toList"   =>
        val tlBuf = new scala.collection.mutable.ArrayBuffer[Value](m.size)
        m.foreach { (k, v) => tlBuf += Value.TupleV(List(k, v)) }
        Pure(Value.ListV(tlBuf.toList))
      case "mkString" => Pure(Value.StringV(Value.show(recv)))
      case "contains" => args match
        case List(k)       => Computation.pureBool(m.contains(k))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "get"      => args match
        case List(k)       => Computation.pureOptionV(m.get(k))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "apply"    => args match
        case List(k)       => Pure(m.getOrElse(k, interp.located(s"Key not found: ${Value.show(k)}")))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "getOrElse" => args match
        case List(k, d)    => Pure(m.getOrElse(k, d))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "updated"  => args match
        case List(k, v)    => Pure(Value.MapV(m + (k -> v)))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "removed"  => args match
        case List(k)       => Pure(Value.MapV(m - k))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "+"        => args match
        case List(Value.TupleV(List(k, v))) => Pure(Value.MapV(m + (k -> v)))
        case _                              => dispatchFallback(recv, name, args, env, interp)
      case "++"       => args match
        case List(Value.MapV(other))        => Pure(Value.MapV(m ++ other))
        case _                              => dispatchFallback(recv, name, args, env, interp)
      case "-"        => args match
        case List(k)       => Pure(Value.MapV(m - k))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "map"      => args match
        case List(f) =>
          // Direct iterator loop: avoids m.toList, intermediate TupleV list, mapSequence ListV, collect.
          val mapIt  = m.iterator
          val mapBuf = scala.collection.mutable.Map.empty[Value, Value]
          def mapLoop(): Computation =
            if !mapIt.hasNext then Pure(Value.MapV(mapBuf.toMap))
            else
              val (k, v) = mapIt.next()
              interp.callValue1(f, Value.TupleV(List(k, v)), env).flatMap {
                case Value.TupleV(nk :: nv :: Nil) => mapBuf += (nk -> nv); mapLoop()
                case _                             => mapLoop()
              }
          mapLoop()
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "filter"   => args match
        case List(f) =>
          // Direct iterator loop: avoids m.toList, tuples list, mapSequence ListV[BoolV].
          val filtIt  = m.iterator
          val filtBuf = scala.collection.mutable.Map.empty[Value, Value]
          def filtLoop(): Computation =
            if !filtIt.hasNext then Pure(Value.MapV(filtBuf.toMap))
            else
              val (k, v) = filtIt.next()
              interp.callValue1(f, Value.TupleV(List(k, v)), env).flatMap {
                case Value.BoolV(true) => filtBuf += (k -> v); filtLoop()
                case _                 => filtLoop()
              }
          filtLoop()
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foreach"  => args match
        case List(f) =>
          val it = m.iterator
          def loop(): Computation =
            if !it.hasNext then Computation.PureUnit
            else
              val (k, v) = it.next()
              interp.callValue1(f, Value.TupleV(List(k, v)), env).flatMap(_ => loop())
          loop()
        case _       => dispatchFallback(recv, name, args, env, interp)
      case _ =>
        // String-key access shorthand: map.key (no args, unknown method name = key lookup)
        if args.isEmpty then Pure(m.getOrElse(Value.StringV(name), interp.located(s"No key '$name' in map")))
        else dispatchFallback(recv, name, args, env, interp)

  // ── Option ──────────────────────────────────────────────────────────────────

  private def dispatchOption(recv: Value, opt: Option[Value], name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    name match
      case "isDefined" => Computation.pureBool(opt.isDefined)
      case "isEmpty"   => Computation.pureBool(opt.isEmpty)
      case "nonEmpty"  => Computation.pureBool(opt.nonEmpty)
      case "toList"    => opt match
        case None    => Computation.PureEmptyList
        case Some(v) => Pure(Value.ListV(List(v)))
      case "get"       => opt match
        case Some(v) => Pure(v)
        case None    => interp.located("Option.get on None")
      case "contains"  => args match
        case List(v)    => Computation.pureBool(opt.contains(v))
        case _          => dispatchFallback(recv, name, args, env, interp)
      case "getOrElse" => args match
        case List(d)    => opt match
          case Some(v) => Pure(v)
          case None    => Pure(d)
        case _          => dispatchFallback(recv, name, args, env, interp)
      case "orElse"    => opt match
        case Some(_) => Pure(recv)
        case None    => args match
          case List(other) => Pure(other)
          case _           => dispatchFallback(recv, name, args, env, interp)
      case "map"       => args match
        case List(f) => opt match
          case None    => Computation.PureNone
          case Some(v) => interp.callValue1(f, v, env).flatMap(Computation.wrapSomeC)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "flatMap"   => args match
        case List(f) => opt match
          case None    => Computation.PureNone
          case Some(v) => interp.callValue1(f, v, env).map {
            case o: Value.OptionV => o
            case other            => Value.OptionV(Some(other))
          }
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "filter"    => args match
        case List(f) => opt match
          case None    => Computation.PureNone
          case Some(v) => interp.callValue1(f, v, env).map {
            case Value.BoolV(true) => Value.OptionV(Some(v))
            case _                 => Value.NoneV
          }
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foreach"   => args match
        case List(f) => opt match
          case None    => Computation.PureUnit
          case Some(v) => interp.callValue1(f, v, env).flatMap(Computation.discardToUnit)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case _ => dispatchFallback(recv, name, args, env, interp)

  // ── Int ─────────────────────────────────────────────────────────────────────

  private def dispatchInt(n: Long, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    val recv = Value.intV(n)
    name match
      case "toDouble"  => Pure(Value.doubleV(n.toDouble))
      case "toLong"    => Pure(recv)
      case "toInt"     => Pure(recv)
      case "abs"       => Computation.pureIntV(math.abs(n))
      case "toString"  => Pure(Value.StringV(n.toString))
      case "to"        => args match
        case List(Value.IntV(m)) =>
          val buf = new scala.collection.mutable.ArrayBuffer[Value](math.max(0, (m - n + 1).toInt))
          var i = n; while i <= m do { buf += Value.intV(i); i += 1 }
          Pure(Value.ListV(buf.toList))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "until"     => args match
        case List(Value.IntV(m)) =>
          val buf = new scala.collection.mutable.ArrayBuffer[Value](math.max(0, (m - n).toInt))
          var i = n; while i < m do { buf += Value.intV(i); i += 1 }
          Pure(Value.ListV(buf.toList))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case _ => dispatchFallback(recv, name, args, env, interp)

  // ── Double ──────────────────────────────────────────────────────────────────

  private def dispatchDouble(d: Double, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    name match
      case "toInt"    => Computation.pureIntV(d.toLong)
      case "toLong"   => Computation.pureIntV(d.toLong)
      case "abs"      => Pure(Value.doubleV(math.abs(d)))
      case "toString" => Pure(Value.StringV(d.toString))
      case "round"    => Computation.pureIntV(math.round(d))
      case "floor"    => Pure(Value.doubleV(math.floor(d)))
      case "ceil"     => Pure(Value.doubleV(math.ceil(d)))
      case _ => extensionDispatch(Value.doubleV(d), name, args, env, interp)
                  .getOrElse(interp.located(s"No method '$name' on Double"))

  // ── Tuple ───────────────────────────────────────────────────────────────────

  private def dispatchTuple(es: List[Value], name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    lazy val recv = Value.TupleV(es)
    name match
      case "++"  => args match
        case List(Value.TupleV(bs)) => Pure(Value.TupleV(es ++ bs))
        case List(Value.UnitV)      => Pure(recv)
        case List(v)                => Pure(Value.TupleV(es :+ v))
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case "_1"  => if es.length > 0 then Pure(es(0)) else interp.located("_1 on empty tuple")
      case "_2"  => if es.length > 1 then Pure(es(1)) else interp.located("_2 on tuple with <2 elements")
      case "_3"  => if es.length > 2 then Pure(es(2)) else interp.located("_3 on tuple with <3 elements")
      case "_4"  => if es.length > 3 then Pure(es(3)) else interp.located("_4 on tuple with <4 elements")
      case _ => dispatchFallback(recv, name, args, env, interp)

  // ── Unit ────────────────────────────────────────────────────────────────────

  private def dispatchUnit(recv: Value, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    name match
      case "++" => args match
        case List(Value.TupleV(bs)) => Pure(Value.TupleV(bs))
        case List(Value.UnitV)      => Computation.PureUnit
        case List(v)                => Pure(v)
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case _ => dispatchFallback(recv, name, args, env, interp)

  // ── InstanceV ───────────────────────────────────────────────────────────────

  private def dispatchInstance(recv: Value, typeName: String, fields: Map[String, Value], name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    typeName match
      case "Pid" => name match
        case "tell" | "!" => args match
          case List(msg) => Perform("Actor", "send", List(recv, msg))
          case _         => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)
        case "address" if args.isEmpty =>
          Perform("Actor", "actorRefAddress", List(recv))
        case "isLocal" if args.isEmpty =>
          Perform("Actor", "actorRefIsLocal", List(recv))
        case "tryLocal" if args.isEmpty =>
          Perform("Actor", "actorRefTryLocal", List(recv))
        case "publishAs" | "publish" => args match
          case List(Value.StringV(n)) => Perform("Actor", "actorRefPublish", List(recv, Value.StringV(n)))
          case _                      => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)
        case _ => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)

      case "ClusterCapability" => name match
        case "resolveSeeds" if args.isEmpty =>
          fields.get("seedResolver") match
            case Some(seedResolver @ Value.InstanceV("SeedResolver", _)) =>
              Perform("Actor", "resolveSeeds", List(seedResolver))
            case _ => interp.located("ClusterCapability missing seedResolver")
        case _ => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)

      case "Signal" => name match
        case "get" | "apply" =>
          fields.get("id") match
            case Some(Value.IntV(id)) => Pure(SignalRuntime.signalGet(interp, id))
            case _                    => interp.located("Signal handle missing id")
        case "set" => args match
          case List(v) => fields.get("id") match
            case Some(Value.IntV(id)) => SignalRuntime.signalSet(interp, id, v); Computation.PureUnit
            case _                    => interp.located("Signal handle missing id")
          case _       => dispatchFallback(recv, name, args, env, interp)
        case _ => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)

      case "Response" => name match
        case "withSession" => args match
          case List(Value.MapV(m)) =>
            Pure(Value.InstanceV("Response", fields + ("setSession" -> Value.MapV(m))))
          case _                   => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)
        case "clearSession" =>
          Pure(Value.InstanceV("Response", fields + ("setSession" -> Value.EmptyMap)))
        case "withHeader" => args match
          case List(Value.StringV(hname), Value.StringV(value)) =>
            val existing = fields.get("headers") match
              case Some(Value.MapV(m)) => m
              case _                   => Map.empty[Value, Value]
            val merged = existing + (Value.StringV(hname) -> Value.StringV(value))
            Pure(Value.InstanceV("Response", fields + ("headers" -> Value.MapV(merged))))
          case _           => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)
        case _ => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)

      case "Right" => name match
        case "isRight"   => Computation.PureTrue
        case "isLeft"    => Computation.PureFalse
        case "toOption"  => Pure(Value.OptionV(Some(fields.getOrElse("value", Value.UnitV))))
        case "swap"      => Pure(Value.InstanceV("Left", fields))
        case "toSeq"     => Pure(Value.ListV(List(fields.getOrElse("value", Value.UnitV))))
        case "getOrElse" => Pure(fields.getOrElse("value", Value.UnitV))
        case "map"       => args match
          case List(f) =>
            interp.callValue1(f, fields.getOrElse("value", Value.UnitV), env).map(v =>
              Value.InstanceV("Right", Map("value" -> v)))
          case _       => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)
        case "flatMap"   => args match
          case List(f) => interp.callValue1(f, fields.getOrElse("value", Value.UnitV), env)
          case _       => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)
        case "fold"      => args match
          case List(_, r) => interp.callValue1(r, fields.getOrElse("value", Value.UnitV), env)
          case _          => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)
        case _ => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)

      case "Left" => name match
        case "isRight"   => Computation.PureFalse
        case "isLeft"    => Computation.PureTrue
        case "toOption"  => Computation.PureNone
        case "swap"      => Pure(Value.InstanceV("Right", fields))
        case "toSeq"     => Computation.PureEmptyList
        case "getOrElse" => args match
          case List(d) => Pure(d)
          case _       => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)
        case "map"       => Pure(recv)
        case "flatMap"   => Pure(recv)
        case "fold"      => args match
          case List(l, _) => interp.callValue1(l, fields.getOrElse("value", Value.UnitV), env)
          case _          => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)
        case _ => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)

      case _ => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)

  private def dispatchInstanceFallback(recv: Value, typeName: String, fields: Map[String, Value], name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    // Source.distributed bridge — dispatches to DStreams plugin when loaded (v1.63.1)
    if typeName == "Source" && name == "distributed" then
      interp.globals.get("Source.distributed") match
        case Some(fn) => interp.callValuePrepend(fn, recv, args, env)
        case None     => interp.located("Source.distributed requires the DStreams plugin")
    // Source.remote bridge — dispatches to streams-bridge plugin when loaded (v1.63.6)
    else if typeName == "Source" && name == "remote" then
      interp.globals.get("Source.remote") match
        case Some(fn) => interp.callValuePrepend(fn, recv, args, env)
        case None     => interp.located("Source.remote requires the streams-bridge plugin")
    // RemoteSource.local bridge (v1.63.6)
    else if typeName == "RemoteSource" && name == "local" then
      interp.globals.get("remoteSourceLocal") match
        case Some(fn) =>
          val buffer = args.headOption.getOrElse(Value.intV(1024))
          interp.callValue2(fn, recv, buffer, env)
        case None => interp.located("remoteSourceLocal requires the streams-bridge plugin")
    // RemoteSource.distributed bridge (v1.63.6)
    else if typeName == "RemoteSource" && name == "distributed" then
      interp.globals.get("remoteSourceLocal") match
        case Some(localFn) =>
          interp.callValue2(localFn, recv, Value.intV(1024), env).flatMap { localSrc =>
            interp.globals.get("Source.distributed") match
              case Some(df) => interp.callValuePrepend(df, localSrc, args, env)
              case None     => interp.located("RemoteSource.distributed requires the DStreams plugin")
          }
        case None => interp.located("remoteSourceLocal requires the streams-bridge plugin")
    // ReactiveSignal bridge (no hard-coding streams into core)
    else if typeName == "ReactiveSignal" && name == "bind" then
      args match
        case List(source) =>
          interp.globals.get("ReactiveSignal.bind") match
            case Some(fn) => interp.callValue2(fn, recv, source, env)
            case None     => interp.located("No method 'bind' on ReactiveSignal")
        case _ => dispatchFallback(recv, name, args, env, interp)
    // Exception .getMessage alias
    else if name == "getMessage" && args.isEmpty then
      Pure(fields.getOrElse("message", Value.EmptyStr))
    // Class methods (declared inside `class`/`case class` body)
    else if interp.typeMethods.get(typeName).exists(_.contains(name)) then
      val fn = interp.typeMethods(typeName)(name)
      interp.callFun(fn.copy(closure = fn.closure ++ fields), args)
    // No-arg / native field access (must precede enum-companion check so plain
    // field values like IntV(3) are returned directly instead of being "called")
    else if args.isEmpty then
      val fieldV = fields.getOrElse(name, null)
      fieldV match
        case null =>
          if name == "toString" then Pure(Value.StringV(Value.show(recv)))
          else extensionDispatch(recv, name, Nil, env, interp)
            .getOrElse(interp.located(s"No field '$name'"))
        case f: Value.FunV      if f.params.isEmpty => interp.callFun(f, Nil)
        case f: Value.NativeFnV                     => f.f(Nil)
        case v                                      => Pure(v)
    // Enum companion call (Color.RGB(1,2,3)) — only when args are present
    else if fields.contains(name) then
      interp.callValue(fields(name), args, env)
    else
      extensionDispatch(recv, name, args, env, interp)
        .getOrElse(interp.located(s"No method '$name' on ${recv.getClass.getSimpleName}(${Value.show(recv)})"))

  // ── Cross-type ++ (bare operands) and final fallback ──────────────────────

  private def dispatchFallback(recv: Value, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    // Cross-type ++ cases: bare value on LEFT with TupleV/UnitV on right
    if name == "++" then
      args match
        case List(Value.TupleV(bs)) if !recv.isInstanceOf[Value.ListV] && !recv.isInstanceOf[Value.MapV] =>
          recv match
            case Value.UnitV      => Pure(Value.TupleV(bs))
            case Value.TupleV(as) => Pure(Value.TupleV(as ++ bs))   // shouldn't reach here but safe
            case _                => Pure(Value.TupleV(recv :: bs))
        case List(Value.UnitV) if !recv.isInstanceOf[Value.ListV] && !recv.isInstanceOf[Value.MapV] =>
          recv match
            case Value.TupleV(as) => Pure(Value.TupleV(as))         // shouldn't reach here but safe
            case _                => Pure(recv)
        case List(w) if !recv.isInstanceOf[Value.ListV] && !recv.isInstanceOf[Value.MapV] =>
          recv match
            case Value.TupleV(as) => Pure(Value.TupleV(as :+ w))    // shouldn't reach here but safe
            case Value.UnitV      => Pure(w)                         // shouldn't reach here but safe
            case _                => Pure(Value.TupleV(List(recv, w)))
        case _ => extensionDispatch(recv, name, args, env, interp)
                    .getOrElse(interp.located(s"No method '$name' on ${recv.getClass.getSimpleName}(${Value.show(recv)})"))
    else
      extensionDispatch(recv, name, args, env, interp)
        .getOrElse(interp.located(s"No method '$name' on ${recv.getClass.getSimpleName}(${Value.show(recv)})"))

  def infix(lhs: Value, op: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    infix2(lhs, op, if args.nonEmpty then args.head else Value.UnitV, args, env, interp)

  /** Infix fast path: rhs already extracted; args is the original list (used only in fallback). */
  def infix2(lhs: Value, op: String, rhs: Value, args: List[Value], env: Env, interp: Interpreter): Computation =
    // Dispatch on op first — Scala 3 compiles this to a hashCode-based O(1) switch
    // rather than the previous O(N) linear scan through 40 tuple-match cases.
    op match
      case "+" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureIntV(a + b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Pure(Value.doubleV(a + b))
        case (Value.IntV(a),    Value.DoubleV(b)) => Pure(Value.doubleV(a + b))
        case (Value.DoubleV(a), Value.IntV(b))    => Pure(Value.doubleV(a + b))
        case (Value.StringV(a), b)                => Pure(Value.StringV(a + Value.show(b)))
        case _                                    => dispatch(lhs, op, args, env, interp)
      case "-" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureIntV(a - b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Pure(Value.doubleV(a - b))
        case (Value.IntV(a),    Value.DoubleV(b)) => Pure(Value.doubleV(a - b))
        case (Value.DoubleV(a), Value.IntV(b))    => Pure(Value.doubleV(a - b))
        case _                                    => dispatch(lhs, op, args, env, interp)
      case "*" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureIntV(a * b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Pure(Value.doubleV(a * b))
        case (Value.IntV(a),    Value.DoubleV(b)) => Pure(Value.doubleV(a * b))
        case (Value.DoubleV(a), Value.IntV(b))    => Pure(Value.doubleV(a * b))
        case (Value.StringV(a), Value.IntV(n))    => Pure(Value.StringV(a * n.toInt))
        case _                                    => dispatch(lhs, op, args, env, interp)
      case "/" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureIntV(a / b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Pure(Value.doubleV(a / b))
        case (Value.IntV(a),    Value.DoubleV(b)) => Pure(Value.doubleV(a / b))
        case (Value.DoubleV(a), Value.IntV(b))    => Pure(Value.doubleV(a / b))
        case _                                    => dispatch(lhs, op, args, env, interp)
      case "%" => (lhs, rhs) match
        case (Value.IntV(a), Value.IntV(b)) => Computation.pureIntV(a % b)
        case _                              => dispatch(lhs, op, args, env, interp)
      case "==" => Computation.pureBool(lhs == rhs)
      case "!=" => Computation.pureBool(lhs != rhs)
      case "<" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureBool(a < b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Computation.pureBool(a < b)
        case _                                    => dispatch(lhs, op, args, env, interp)
      case ">" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureBool(a > b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Computation.pureBool(a > b)
        case _                                    => dispatch(lhs, op, args, env, interp)
      case "<=" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureBool(a <= b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Computation.pureBool(a <= b)
        case _                                    => dispatch(lhs, op, args, env, interp)
      case ">=" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureBool(a >= b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Computation.pureBool(a >= b)
        case _                                    => dispatch(lhs, op, args, env, interp)
      case "&&" => (lhs, rhs) match
        case (Value.BoolV(a), Value.BoolV(b)) => Computation.pureBool(a && b)
        case _                                => dispatch(lhs, op, args, env, interp)
      case "||" => (lhs, rhs) match
        case (Value.BoolV(a), Value.BoolV(b)) => Computation.pureBool(a || b)
        case _                                => dispatch(lhs, op, args, env, interp)
      case "::" => rhs match
        case Value.ListV(ls) => Pure(Value.ListV(lhs :: ls))
        case _               => dispatch(lhs, op, args, env, interp)
      case "++" => (lhs, rhs) match
        case (Value.ListV(a), Value.ListV(b)) =>
          if b.isEmpty then Pure(lhs)
          else if a.isEmpty then Pure(rhs)
          else Pure(Value.ListV(a ++ b))
        case _ => dispatch(lhs, op, args, env, interp)
      case ":::" => (lhs, rhs) match
        case (Value.ListV(a), Value.ListV(b)) =>
          if b.isEmpty then Pure(lhs)
          else if a.isEmpty then Pure(rhs)
          else Pure(Value.ListV(a ++ b))
        case _ => dispatch(lhs, op, args, env, interp)
      case ":+" => lhs match
        case Value.ListV(ls) => Pure(Value.ListV(ls :+ rhs))
        case _               => dispatch(lhs, op, args, env, interp)
      case "+:" => rhs match
        case Value.ListV(ls) => Pure(Value.ListV(lhs +: ls))
        case _               => dispatch(lhs, op, args, env, interp)
      case "->" => Pure(Value.TupleV(List(lhs, rhs)))
      case "!" => lhs match
        case pid @ Value.InstanceV("Pid", _) => Perform("Actor", "send", List(pid, rhs))
        case _                               => dispatch(lhs, op, args, env, interp)
      case ":=" => lhs match
        case Value.InstanceV("AttrKey", fields) =>
          val name = fields.get("name").map(Value.show).getOrElse("")
          Pure(Value.InstanceV("Attr", Map(
            "name"  -> Value.StringV(name),
            "value" -> Value.StringV(Value.show(rhs))
          )))
        case _ => dispatch(lhs, op, args, env, interp)
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
      interp.callValuePrepend(fn, recv, args, env)
    }.orElse {
      var parent: Option[String] = interp.parentTypes.get(typeName)
      var found: Option[Computation] = None
      while parent.isDefined && found.isEmpty do
        found = interp.extensions.get((parent.get, method)).map(fn => interp.callValuePrepend(fn, recv, args, env))
        parent = interp.parentTypes.get(parent.get)
      found
    }.orElse {
      interp.globals.values.collectFirst {
        case Value.InstanceV(name, fields)
          if name.endsWith(s"[$typeName]") && fields.contains(method) =>
          interp.callValuePrepend(fields(method), recv, args, env)
      }
    }
