package scalascript.interpreter

import Computation.{Pure, Perform, FlatMap}
import scala.collection.immutable.{Map => IMap}

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

  // Avoids s"Tuple$n" string allocation on every TupleV extensionDispatch call.
  private val tupleTypeNames: Array[String] = Array.tabulate(23)(i => s"Tuple$i")
  private def tupleTypeName(n: Int): String =
    if n < tupleTypeNames.length then tupleTypeNames(n) else s"Tuple$n"

  def dispatch(recv: Value, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    // Extensions early-exit: avoid 7 HashMap lookups when no extensions registered.
    if interp.extensions.nonEmpty then
      val typeName: String = recv match
        case _: Value.OptionV => "Option"
        case _: Value.ListV   => "List"
        case _: Value.IntV    => "Int"
        case _: Value.DoubleV => "Double"
        case _: Value.StringV => "String"
        case _: Value.BoolV   => "Boolean"
        case _: Value.MapV    => "Map"
        case _                => null
      if typeName != null then
        val typeExts = interp.extensions.getOrElse(typeName, null)
        if typeExts != null then
          val fn = typeExts.getOrElse(name, null)
          if fn != null then return interp.callValuePrepend(fn, recv, args, env)
    recv match
      case Value.StringV(s)        => dispatchString(recv, s, name, args, env, interp)
      case Value.ListV(ls)         => dispatchList(ls, name, args, env, interp)
      case Value.MapV(m)           => dispatchMap(m, name, args, env, interp)
      case Value.OptionV(opt)      => dispatchOption(recv, opt, name, args, env, interp)
      case Value.IntV(n)           => dispatchInt(n, name, args, env, interp)
      case Value.DoubleV(d)        => dispatchDouble(d, name, args, env, interp)
      case Value.CharV(c)          => dispatchChar(c, name, args, env, interp)
      case Value.BoolV(b)          => dispatchBool(recv, b, name, args, env, interp)
      case Value.TupleV(es)        => dispatchTuple(es, name, args, env, interp)
      case Value.UnitV             => dispatchUnit(recv, name, args, env, interp)
      case Value.InstanceV(t, f)   => dispatchInstance(recv, t, f, name, args, env, interp)
      case other                   => dispatchFallback(other, name, args, env, interp)

  /** Single-arg fast path: avoids allocating `arg :: Nil` per method call.
   *  Covers the most common 1-arg operations on List/Map/Option/String/Int/instances.
   *  Falls through to dispatch(recv, name, arg :: Nil, ...) for uncommon operations. */
  def dispatch1(recv: Value, name: String, arg: Value, env: Env, interp: Interpreter): Computation =
    if interp.extensions.nonEmpty then
      val typeName: String = recv match
        case _: Value.OptionV => "Option"
        case _: Value.ListV   => "List"
        case _: Value.IntV    => "Int"
        case _: Value.DoubleV => "Double"
        case _: Value.StringV => "String"
        case _: Value.BoolV   => "Boolean"
        case _: Value.MapV    => "Map"
        case _                => null
      if typeName != null then
        val typeExts = interp.extensions.getOrElse(typeName, null)
        if typeExts != null then
          val fn = typeExts.getOrElse(name, null)
          if fn != null then return interp.callValue2(fn, recv, arg, env)
    recv match
      case Value.ListV(ls)      => dispatchList1(ls, recv, name, arg, env, interp)
      case Value.MapV(m)        => dispatchMap1(m, name, arg, env, interp)
      case Value.OptionV(opt)   => dispatchOption1(recv, opt, name, arg, env, interp)
      case Value.StringV(s)     => dispatchString1(recv, s, name, arg, env, interp)
      case Value.IntV(n)        => dispatchInt1(n, name, arg, env, interp)
      case Value.DoubleV(d)     => dispatchDouble1(d, recv, name, arg, env, interp)
      case Value.InstanceV(t, f) => dispatchInstance1(recv, t, f, name, arg, env, interp)
      case _                    => dispatch(recv, name, arg :: Nil, env, interp)

  /** Two-arg fast path: avoids allocating `arg1 :: arg2 :: Nil` per method call.
   *  Covers the most common 2-arg operations on Map/String/Int.
   *  Falls through to dispatch(recv, name, arg1 :: arg2 :: Nil, ...) for uncommon ops. */
  def dispatch2(recv: Value, name: String, arg1: Value, arg2: Value, env: Env, interp: Interpreter): Computation =
    recv match
      case Value.MapV(m) => name match
        case "getOrElse" | "getOrDefault" => Pure(m.getOrElse(arg1, arg2))
        case "updated"   => Pure(Value.MapV(m.updated(arg1, arg2)))
        case _           => dispatchMap(m, name, arg1 :: arg2 :: Nil, env, interp)
      case Value.StringV(s) => name match
        case "replace"    => (arg1, arg2) match
          case (Value.StringV(a), Value.StringV(b)) => Pure(Value.StringV(s.replace(a, b)))
          case _                                    => dispatchString(recv, s, name, arg1 :: arg2 :: Nil, env, interp)
        case "substring"  => (arg1, arg2) match
          case (Value.IntV(a), Value.IntV(b)) =>
            val from = a.toInt.max(0).min(s.length)
            val to   = b.toInt.max(from).min(s.length)
            Pure(Value.StringV(s.substring(from, to)))
          case _ => dispatchString(recv, s, name, arg1 :: arg2 :: Nil, env, interp)
        case "slice"      => (arg1, arg2) match
          case (Value.IntV(a), Value.IntV(b)) =>
            val from = a.toInt.max(0).min(s.length)
            val to   = b.toInt.max(from).min(s.length)
            Pure(Value.StringV(s.slice(from, to)))
          case _ => dispatchString(recv, s, name, arg1 :: arg2 :: Nil, env, interp)
        case "padTo"      => (arg1, arg2) match
          case (Value.IntV(n), Value.CharV(c)) =>
            if n <= s.length then Pure(recv) else Pure(Value.StringV(s.padTo(n.toInt, c)))
          case _ => dispatchString(recv, s, name, arg1 :: arg2 :: Nil, env, interp)
        case _            => dispatchString(recv, s, name, arg1 :: arg2 :: Nil, env, interp)
      case Value.IntV(n) => name match
        case "clamp" => (arg1, arg2) match
          case (Value.IntV(lo), Value.IntV(hi)) => Computation.pureIntV(math.max(lo, math.min(hi, n)))
          case _ => dispatchInt(n, name, arg1 :: arg2 :: Nil, env, interp)
        case _ => dispatchInt(n, name, arg1 :: arg2 :: Nil, env, interp)
      case Value.ListV(ls) => name match
        case "slice" => (arg1, arg2) match
          case (Value.IntV(a), Value.IntV(b)) =>
            val from = a.toInt.max(0)
            val to   = b.toInt.min(ls.length)
            if from >= to then Computation.PureEmptyList
            else Pure(Value.ListV(ls.slice(from, to)))
          case _ => dispatchList(ls, name, arg1 :: arg2 :: Nil, env, interp)
        case "zip" => (arg1, arg2) match
          case (Value.ListV(bs), _) =>
            val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length.min(bs.length))
            var as = ls; var bsRem = bs
            while as.nonEmpty && bsRem.nonEmpty do
              buf += Value.TupleV(as.head :: bsRem.head :: Nil)
              as = as.tail; bsRem = bsRem.tail
            Pure(Value.ListV(buf.toList))
          case _ => dispatchList(ls, name, arg1 :: arg2 :: Nil, env, interp)
        case _ => dispatchList(ls, name, arg1 :: arg2 :: Nil, env, interp)
      case _ => dispatch(recv, name, arg1 :: arg2 :: Nil, env, interp)

  /** Sort ls using a precomputed keys list. Factored out of dispatchList/dispatchList1
   *  to avoid code duplication and allow both paths to use direct-match on mapSequence results. */
  private def sortByKeys(ls: List[Value], keys: List[Value]): Computation =
    val n = ls.length
    var allIntKeys = true; var kChk = keys
    while allIntKeys && kChk.nonEmpty do
      if !kChk.head.isInstanceOf[Value.IntV] then allIntKeys = false
      kChk = kChk.tail
    if allIntKeys then
      val arr2 = new Array[(Value, Long)](n)
      var i = 0; var lR = ls; var kR = keys
      while lR.nonEmpty do
        arr2(i) = (lR.head, kR.head.asInstanceOf[Value.IntV].v)
        i += 1; lR = lR.tail; kR = kR.tail
      java.util.Arrays.sort(arr2, java.util.Comparator.comparingLong[(Value, Long)](_._2))
      var result2: List[Value] = Nil; i = n - 1
      while i >= 0 do result2 = arr2(i)._1 :: result2; i -= 1
      Pure(Value.ListV(result2))
    else
      val arr = new Array[(Value, String)](n)
      var i = 0; var lRem = ls; var kRem = keys
      while lRem.nonEmpty do
        arr(i) = (lRem.head, Value.show(kRem.head))
        i += 1; lRem = lRem.tail; kRem = kRem.tail
      java.util.Arrays.sort(arr, java.util.Comparator.comparing[(Value, String), String](_._2))
      var result: List[Value] = Nil; i = n - 1
      while i >= 0 do result = arr(i)._1 :: result; i -= 1
      Pure(Value.ListV(result))

  /** Apply sortBy via a precomputed keys Computation.  Direct-matches keysC to avoid
   *  the .flatMap lambda allocation for the common Pure case. */
  private def applySortBy(ls: List[Value], recv: Value, keysC: Computation): Computation =
    keysC match
      case Pure(Value.ListV(keys)) => sortByKeys(ls, keys)
      case Pure(_)                 => Pure(recv)
      case comp                    => FlatMap(comp, {
        case Value.ListV(keys) => sortByKeys(ls, keys)
        case _                 => Pure(recv)
      })

  /** Find max/min element of ls by a key function applied to each element. */
  private def applyMaxBy(ls: List[Value], keysC: Computation, max: Boolean): Computation =
    keysC match
      case Pure(Value.ListV(keys)) =>
        var bestVal = ls.head; var bestKey = Value.show(keys.head)
        var lRem = ls.tail; var kRem = keys.tail
        while lRem.nonEmpty do
          val k = Value.show(kRem.head)
          if (if max then k > bestKey else k < bestKey) then { bestVal = lRem.head; bestKey = k }
          lRem = lRem.tail; kRem = kRem.tail
        Pure(bestVal)
      case Pure(_) => Pure(ls.head)
      case comp    => FlatMap(comp, {
        case Value.ListV(keys) =>
          var bestVal = ls.head; var bestKey = Value.show(keys.head)
          var lRem = ls.tail; var kRem = keys.tail
          while lRem.nonEmpty do
            val k = Value.show(kRem.head)
            if (if max then k > bestKey else k < bestKey) then { bestVal = lRem.head; bestKey = k }
            lRem = lRem.tail; kRem = kRem.tail
          Pure(bestVal)
        case _ => Pure(ls.head)
      })

  /** 1-arg fast path for List — avoids `arg :: Nil` allocation for the most common ops. */
  private def dispatchList1(ls: List[Value], recv: Value, name: String, arg: Value, env: Env, interp: Interpreter): Computation =
    name match
      case "map"        => Computation.mapSequence(ls, item => interp.callValue1(arg, item, env))
      case "filter"     => Computation.filterSequence(ls, item => interp.callValue1(arg, item, env))
      case "filterNot"  => Computation.filterNotSequence(ls, item => interp.callValue1(arg, item, env))
      case "foreach"    => Computation.foreachSequence(ls, item => interp.callValue1(arg, item, env))
      case "flatMap"    =>
        val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
        var rem = ls
        while rem.nonEmpty do
          interp.callValue1(arg, rem.head, env) match
            case Pure(Value.ListV(inner)) => buf ++= inner; rem = rem.tail
            case Pure(v)                  => buf += v; rem = rem.tail
            case comp =>
              val tail = rem.tail
              def loopRest(remaining: List[Value]): Computation = remaining match
                case Nil => Pure(Value.ListV(buf.toList))
                case h :: rest =>
                  FlatMap(interp.callValue1(arg, h, env), {
                    case Value.ListV(inner) => buf ++= inner; loopRest(rest)
                    case v                  => buf += v;      loopRest(rest)
                  })
              return FlatMap(comp, {
                case Value.ListV(inner) => buf ++= inner; loopRest(tail)
                case v                  => buf += v;      loopRest(tail)
              })
        Pure(Value.ListV(buf.toList))
      case "find"       =>
        var rem = ls
        while rem.nonEmpty do
          val h = rem.head
          interp.callValue1(arg, h, env) match
            case Pure(Value.BoolV(true)) => return Pure(Value.OptionV(Some(h)))
            case Pure(_)                 => rem = rem.tail
            case c =>
              val tail = rem.tail
              def restLoop(remaining: List[Value]): Computation = remaining match
                case Nil => Computation.PureNone
                case hh :: rest =>
                  FlatMap(interp.callValue1(arg, hh, env), {
                    case Value.BoolV(true) => Pure(Value.OptionV(Some(hh)))
                    case _                 => restLoop(rest)
                  })
              return FlatMap(c, {
                case Value.BoolV(true) => Pure(Value.OptionV(Some(h)))
                case _                 => restLoop(tail)
              })
        Computation.PureNone
      case "exists"     =>
        var rem = ls
        while rem.nonEmpty do
          interp.callValue1(arg, rem.head, env) match
            case Pure(Value.BoolV(true)) => return Computation.PureTrue
            case Pure(_)                 => rem = rem.tail
            case c =>
              val tail = rem.tail
              def restLoop(remaining: List[Value]): Computation = remaining match
                case Nil => Computation.PureFalse
                case h :: rest =>
                  FlatMap(interp.callValue1(arg, h, env), {
                    case Value.BoolV(true) => Computation.PureTrue
                    case _                 => restLoop(rest)
                  })
              return FlatMap(c, {
                case Value.BoolV(true) => Computation.PureTrue
                case _                 => restLoop(tail)
              })
        Computation.PureFalse
      case "forall"     =>
        var rem = ls
        while rem.nonEmpty do
          interp.callValue1(arg, rem.head, env) match
            case Pure(Value.BoolV(false)) => return Computation.PureFalse
            case Pure(_)                  => rem = rem.tail
            case c =>
              val tail = rem.tail
              def restLoop(remaining: List[Value]): Computation = remaining match
                case Nil => Computation.PureTrue
                case h :: rest =>
                  FlatMap(interp.callValue1(arg, h, env), {
                    case Value.BoolV(false) => Computation.PureFalse
                    case _                  => restLoop(rest)
                  })
              return FlatMap(c, {
                case Value.BoolV(false) => Computation.PureFalse
                case _                  => restLoop(tail)
              })
        Computation.PureTrue
      case "contains"   => Computation.pureBool(ls.contains(arg))
      case "indexOf"    => Computation.pureIntV(ls.indexOf(arg).toLong)
      case "appended"   => Pure(Value.ListV(ls :+ arg))
      case "prepended"  => Pure(Value.ListV(arg +: ls))
      case "take"       => arg match
        case Value.IntV(n) =>
          if n >= ls.length then Pure(recv)
          else if n <= 0 then Computation.PureEmptyList
          else Pure(Value.ListV(ls.take(n.toInt)))
        case _ => dispatchList(ls, name, arg :: Nil, env, interp)
      case "drop"       => arg match
        case Value.IntV(n) =>
          if n <= 0 then Pure(recv)
          else if n >= ls.length then Computation.PureEmptyList
          else Pure(Value.ListV(ls.drop(n.toInt)))
        case _ => dispatchList(ls, name, arg :: Nil, env, interp)
      case "apply"      => arg match
        case Value.IntV(i) =>
          if i < 0 || i >= ls.length then interp.located(s"index $i out of bounds for list of length ${ls.length}")
          else Pure(ls(i.toInt))
        case _ => dispatchList(ls, name, arg :: Nil, env, interp)
      case "grouped"    => arg match
        case Value.IntV(n) => Pure(Value.ListV(ls.grouped(n.toInt).map(Value.ListV(_)).toList))
        case _             => dispatchList(ls, name, arg :: Nil, env, interp)
      case "sliding"    => arg match
        case Value.IntV(n) => Pure(Value.ListV(ls.sliding(n.toInt).map(Value.ListV(_)).toList))
        case _             => dispatchList(ls, name, arg :: Nil, env, interp)
      // Common 1-arg aggregators — avoids `arg :: Nil` cons cell when called
      // via the curried `list.foldLeft(init)(f)` path.
      case "foldLeft"   =>
        Pure(Value.NativeFnV("foldLeft", {
          case List(f) => Computation.foldLeftSequence(ls, arg, (acc, h) => interp.callValue2(f, acc, h, env))
          case _       => throw InterpretError("foldLeft expects one function argument")
        }))
      case "foldRight"  =>
        Pure(Value.NativeFnV("foldRight", {
          case List(f) => Computation.foldLeftSequence(ls.reverse, arg, (acc, h) => interp.callValue2(f, h, acc, env))
          case _       => throw InterpretError("foldRight expects one function argument")
        }))
      case "scanLeft"   =>
        Pure(Value.NativeFnV("scanLeft", {
          case List(f) =>
            val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length + 1)
            buf += arg
            def loop(remaining: List[Value], acc: Value): Computation = remaining match
              case Nil       => Pure(Value.ListV(buf.toList))
              case h :: rest => FlatMap(interp.callValue2(f, acc, h, env), { v => buf += v; loop(rest, v) })
            loop(ls, arg)
          case _ => throw InterpretError("scanLeft expects one function argument")
        }))
      case "reduceLeft" => ls match
        case Nil    => interp.located("reduceLeft on empty list")
        case h :: t => Computation.foldLeftSequence(t, h, (acc, x) => interp.callValue2(arg, acc, x, env))
      case "partition"  =>
        Computation.partitionSequence(ls, item => interp.callValue1(arg, item, env))
      case "sortBy"     =>
        applySortBy(ls, recv, Computation.mapSequence(ls, item => interp.callValue1(arg, item, env)))
      case "maxBy"      =>
        if ls.isEmpty then interp.located("maxBy on empty list")
        else applyMaxBy(ls, Computation.mapSequence(ls, item => interp.callValue1(arg, item, env)), max = true)
      case "minBy"      =>
        if ls.isEmpty then interp.located("minBy on empty list")
        else applyMaxBy(ls, Computation.mapSequence(ls, item => interp.callValue1(arg, item, env)), max = false)
      case "count"      =>
        var rem = ls; var acc = 0L
        while rem.nonEmpty do
          interp.callValue1(arg, rem.head, env) match
            case Pure(Value.BoolV(true)) => acc += 1L; rem = rem.tail
            case Pure(_)                 => rem = rem.tail
            case c =>
              val acc0 = acc; val tail = rem.tail
              def restLoop(remaining: List[Value], cnt: Long): Computation = remaining match
                case Nil => Computation.pureIntV(cnt)
                case h :: rest =>
                  FlatMap(interp.callValue1(arg, h, env), {
                    case Value.BoolV(true) => restLoop(rest, cnt + 1L)
                    case _                 => restLoop(rest, cnt)
                  })
              return FlatMap(c, {
                case Value.BoolV(true) => restLoop(tail, acc0 + 1L)
                case _                 => restLoop(tail, acc0)
              })
        Computation.pureIntV(acc)
      case "collect"    =>
        val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
        var rem = ls
        while rem.nonEmpty do
          interp.callValue1(arg, rem.head, env) match
            case Pure(Value.OptionV(Some(v))) => buf += v; rem = rem.tail
            case Pure(Value.OptionV(None))    => rem = rem.tail
            case Pure(v)                      => buf += v; rem = rem.tail
            case comp =>
              val tail = rem.tail
              def loopRest(remaining: List[Value]): Computation = remaining match
                case Nil => Pure(Value.ListV(buf.toList))
                case h :: rest =>
                  FlatMap(interp.callValue1(arg, h, env), {
                    case Value.OptionV(Some(v)) => buf += v; loopRest(rest)
                    case Value.OptionV(None)    => loopRest(rest)
                    case v                      => buf += v; loopRest(rest)
                  })
              return FlatMap(comp, {
                case Value.OptionV(Some(v)) => buf += v; loopRest(tail)
                case Value.OptionV(None)    => loopRest(tail)
                case v                      => buf += v; loopRest(tail)
              })
        Pure(Value.ListV(buf.toList))
      case "span"       =>
        val yesBuf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
        var rem = ls
        while rem.nonEmpty do
          interp.callValue1(arg, rem.head, env) match
            case Pure(Value.BoolV(true)) => yesBuf += rem.head; rem = rem.tail
            case Pure(_)                 => return Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(rem) :: Nil))
            case comp =>
              val h = rem.head; val remaining = rem
              return FlatMap(comp, {
                case Value.BoolV(true) =>
                  yesBuf += h
                  def loopRest(rest: List[Value]): Computation = rest match
                    case Nil    => Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(Nil) :: Nil))
                    case hh :: t =>
                      FlatMap(interp.callValue1(arg, hh, env), {
                        case Value.BoolV(true) => yesBuf += hh; loopRest(t)
                        case _                 => Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(rest) :: Nil))
                      })
                  loopRest(rem.tail)
                case _ => Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(remaining) :: Nil))
              })
        Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(Nil) :: Nil))
      case "sortWith"   =>
        if ls.isEmpty || ls.tail.isEmpty then Pure(recv)
        else
          val arr = ls.toArray
          java.util.Arrays.sort(arr, (a: Value, b: Value) =>
            Computation.run(interp.callValue2(arg, a, b, env)) match
              case Value.BoolV(true) => -1
              case _                 => 1
          )
          var sortResult: List[Value] = Nil; var si = arr.length - 1
          while si >= 0 do sortResult = arr(si) :: sortResult; si -= 1
          Pure(Value.ListV(sortResult))
      case "groupBy"    =>
        val groups = scala.collection.mutable.LinkedHashMap.empty[Value, scala.collection.mutable.ArrayBuffer[Value]]
        var rem = ls
        while rem.nonEmpty do
          val h = rem.head
          interp.callValue1(arg, h, env) match
            case Pure(k) =>
              groups.getOrElseUpdate(k, new scala.collection.mutable.ArrayBuffer[Value]) += h
              rem = rem.tail
            case c =>
              val tail = rem.tail
              def loopRest(remaining: List[Value]): Computation = remaining match
                case Nil => Pure(Value.MapV(groups.iterator.map((k2, buf) => k2 -> Value.ListV(buf.toList)).toMap))
                case hh :: rest =>
                  FlatMap(interp.callValue1(arg, hh, env), { k2 =>
                    groups.getOrElseUpdate(k2, new scala.collection.mutable.ArrayBuffer[Value]) += hh
                    loopRest(rest)
                  })
              return FlatMap(c, { k =>
                groups.getOrElseUpdate(k, new scala.collection.mutable.ArrayBuffer[Value]) += h
                loopRest(tail)
              })
        Pure(Value.MapV(groups.iterator.map((k, buf) => k -> Value.ListV(buf.toList)).toMap))
      case "mkString"   => arg match
        case Value.StringV(sep) => Pure(Value.StringV(ls.iterator.map(Value.show).mkString(sep)))
        case _                  => dispatchList(ls, name, arg :: Nil, env, interp)
      case "zip"        => arg match
        case Value.ListV(other) =>
          val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length.min(other.length))
          var as = ls; var bs = other
          while as.nonEmpty && bs.nonEmpty do
            buf += Value.TupleV(as.head :: bs.head :: Nil)
            as = as.tail; bs = bs.tail
          Pure(Value.ListV(buf.toList))
        case _                  => dispatchList(ls, name, arg :: Nil, env, interp)
      case "takeWhile"  =>
        val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
        var rem = ls
        while rem.nonEmpty do
          val h = rem.head
          interp.callValue1(arg, h, env) match
            case Pure(Value.BoolV(true)) => buf += h; rem = rem.tail
            case Pure(_)                 => return Pure(Value.ListV(buf.toList))
            case c =>
              val tail = rem.tail
              return FlatMap(c, {
                case Value.BoolV(true) =>
                  buf += h
                  def loopRest(remaining: List[Value]): Computation = remaining match
                    case Nil     => Pure(Value.ListV(buf.toList))
                    case hh :: t =>
                      FlatMap(interp.callValue1(arg, hh, env), {
                        case Value.BoolV(true) => buf += hh; loopRest(t)
                        case _                 => Pure(Value.ListV(buf.toList))
                      })
                  loopRest(tail)
                case _ => Pure(Value.ListV(buf.toList))
              })
        Pure(Value.ListV(buf.toList))
      case "dropWhile"  =>
        var rem = ls
        while rem.nonEmpty do
          val h = rem.head
          interp.callValue1(arg, h, env) match
            case Pure(Value.BoolV(true)) => rem = rem.tail
            case Pure(_)                 => return Pure(Value.ListV(rem))
            case c =>
              val capturedRem = rem
              val tail = rem.tail
              def restLoop(remaining: List[Value]): Computation = remaining match
                case Nil     => Computation.PureEmptyList
                case hh :: t =>
                  FlatMap(interp.callValue1(arg, hh, env), {
                    case Value.BoolV(true) => restLoop(t)
                    case _                 => Pure(Value.ListV(remaining))
                  })
              return FlatMap(c, {
                case Value.BoolV(true) => restLoop(tail)
                case _                 => Pure(Value.ListV(capturedRem))
              })
        Computation.PureEmptyList
      case "intersect"  => arg match
        case Value.ListV(other) =>
          if ls.isEmpty || other.isEmpty then Computation.PureEmptyList
          else
            val otherSet = other.toSet
            val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length.min(other.length))
            var rem = ls
            while rem.nonEmpty do
              if otherSet.contains(rem.head) then buf += rem.head
              rem = rem.tail
            Pure(Value.ListV(buf.toList))
        case _ => dispatchList(ls, name, arg :: Nil, env, interp)
      case "diff"       => arg match
        case Value.ListV(other) =>
          if ls.isEmpty then Computation.PureEmptyList
          else if other.isEmpty then Pure(recv)
          else
            val otherSet = other.toSet
            val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
            var rem = ls
            while rem.nonEmpty do
              if !otherSet.contains(rem.head) then buf += rem.head
              rem = rem.tail
            Pure(Value.ListV(buf.toList))
        case _ => dispatchList(ls, name, arg :: Nil, env, interp)
      case "takeRight"  => arg match
        case Value.IntV(n) =>
          if n >= ls.length then Pure(recv)
          else if n <= 0 then Computation.PureEmptyList
          else Pure(Value.ListV(ls.takeRight(n.toInt)))
        case _ => dispatchList(ls, name, arg :: Nil, env, interp)
      case "dropRight"  => arg match
        case Value.IntV(n) =>
          if n <= 0 then Pure(recv)
          else if n >= ls.length then Computation.PureEmptyList
          else Pure(Value.ListV(ls.dropRight(n.toInt)))
        case _ => dispatchList(ls, name, arg :: Nil, env, interp)
      case "splitAt"    => arg match
        case Value.IntV(n) =>
          val (a, b) = ls.splitAt(n.toInt)
          Pure(Value.TupleV(Value.ListV(a) :: Value.ListV(b) :: Nil))
        case _ => dispatchList(ls, name, arg :: Nil, env, interp)
      case _            => dispatchList(ls, name, arg :: Nil, env, interp)

  /** 1-arg fast path for Map. */
  private def dispatchMap1(m: Map[Value, Value], name: String, arg: Value, env: Env, interp: Interpreter): Computation =
    name match
      case "get"        =>
        val gv = m.getOrElse(arg, null)
        if gv == null then Computation.PureNone else Pure(Value.OptionV(Some(gv)))
      case "apply"      => Pure(m.getOrElse(arg, interp.located(s"Key not found: ${Value.show(arg)}")))
      case "contains"   => Computation.pureBool(m.contains(arg))
      case "removed"    => Pure(Value.MapV(m - arg))
      case "-"          => Pure(Value.MapV(m - arg))
      case "map"        =>
        val mapIt  = m.iterator
        val mapBuf = scala.collection.mutable.Map.empty[Value, Value]
        while mapIt.hasNext do
          val (k, v) = mapIt.next()
          interp.callEntry(arg, k, v, env) match
            case Pure(Value.TupleV(nk :: nv :: Nil)) => mapBuf(nk) = nv
            case Pure(_)                             => // skip malformed
            case comp =>
              def loopMap(r: Value): Computation =
                r match
                  case Value.TupleV(nk :: nv :: Nil) => mapBuf(nk) = nv
                  case _                             =>
                if !mapIt.hasNext then Pure(Value.MapV(mapBuf.toMap))
                else
                  val (k2, v2) = mapIt.next()
                  FlatMap(interp.callEntry(arg, k2, v2, env), loopMap)
              return FlatMap(comp, loopMap)
        Pure(Value.MapV(mapBuf.toMap))
      case "filter"     =>
        val filtIt  = m.iterator
        val filtBuf = scala.collection.mutable.Map.empty[Value, Value]
        while filtIt.hasNext do
          val (k, v) = filtIt.next()
          interp.callEntry(arg, k, v, env) match
            case Pure(Value.BoolV(true))  => filtBuf(k) = v
            case Pure(_)                  => // skip
            case comp =>
              def loopFilt(r: Value): Computation =
                r match
                  case Value.BoolV(true) => filtBuf(k) = v
                  case _                 =>
                def rest(): Computation =
                  if !filtIt.hasNext then Pure(Value.MapV(filtBuf.toMap))
                  else
                    val (k2, v2) = filtIt.next()
                    FlatMap(interp.callEntry(arg, k2, v2, env), { r2 =>
                      if r2 == Value.BoolV(true) then filtBuf(k2) = v2
                      rest()
                    })
                rest()
              return FlatMap(comp, loopFilt)
        Pure(Value.MapV(filtBuf.toMap))
      case "foreach"    =>
        val it = m.iterator
        while it.hasNext do
          val (k, v) = it.next()
          interp.callEntry(arg, k, v, env) match
            case Pure(_) =>
            case c =>
              def restLoop(): Computation =
                if !it.hasNext then Computation.PureUnit
                else
                  val (k2, v2) = it.next()
                  FlatMap(interp.callEntry(arg, k2, v2, env), _ => restLoop())
              return FlatMap(c, _ => restLoop())
        Computation.PureUnit
      case "mapValues"  =>
        val it  = m.iterator
        val buf = scala.collection.mutable.Map.empty[Value, Value]
        while it.hasNext do
          val (k, v) = it.next()
          interp.callValue1(arg, v, env) match
            case Pure(nv) => buf(k) = nv
            case c =>
              def restLoop(): Computation =
                if !it.hasNext then Pure(Value.MapV(buf.toMap))
                else
                  val (k2, v2) = it.next()
                  FlatMap(interp.callValue1(arg, v2, env), { nv2 => buf(k2) = nv2; restLoop() })
              return FlatMap(c, { nv => buf(k) = nv; restLoop() })
        Pure(Value.MapV(buf.toMap))
      case "+"  => arg match
        case Value.TupleV(k :: v :: Nil) => Pure(Value.MapV(m.updated(k, v)))
        case _                           => dispatchMap(m, "+", arg :: Nil, env, interp)
      case "++" => arg match
        case Value.MapV(other) => Pure(Value.MapV(m ++ other))
        case _                 => dispatchMap(m, "++", arg :: Nil, env, interp)
      case "getOrElse" =>
        Pure(Value.NativeFnV("getOrElse", {
          case List(d) => Pure(m.getOrElse(arg, d))
          case _       => dispatchMap(m, "getOrElse", arg :: Nil, env, interp)
        }))
      case "getOrDefault" =>
        Pure(Value.NativeFnV("getOrDefault", {
          case List(d) => Pure(m.getOrElse(arg, d))
          case _       => dispatchMap(m, "getOrDefault", arg :: Nil, env, interp)
        }))
      case "foldLeft"    =>
        Pure(Value.NativeFnV("foldLeft", {
          case List(f) =>
            val it = m.iterator
            def loop(acc: Value): Computation =
              if !it.hasNext then Pure(acc)
              else
                val (k, v) = it.next()
                FlatMap(interp.callValue2(f, acc, Value.TupleV(k :: v :: Nil), env), loop)
            loop(arg)
          case _ => throw InterpretError("Map.foldLeft expects one function argument")
        }))
      case "exists"      =>
        val it = m.iterator
        while it.hasNext do
          val (k, v) = it.next()
          interp.callEntry(arg, k, v, env) match
            case Pure(Value.BoolV(true))  => return Computation.PureTrue
            case Pure(_)                  =>
            case comp =>
              return FlatMap(comp, {
                case Value.BoolV(true) => Computation.PureTrue
                case _ =>
                  def loopRest(): Computation =
                    if !it.hasNext then Computation.PureFalse
                    else
                      val (k2, v2) = it.next()
                      FlatMap(interp.callEntry(arg, k2, v2, env), {
                        case Value.BoolV(true) => Computation.PureTrue
                        case _                 => loopRest()
                      })
                  loopRest()
              })
        Computation.PureFalse
      case "forall"      =>
        val it = m.iterator
        while it.hasNext do
          val (k, v) = it.next()
          interp.callEntry(arg, k, v, env) match
            case Pure(Value.BoolV(false)) => return Computation.PureFalse
            case Pure(_)                  =>
            case comp =>
              return FlatMap(comp, {
                case Value.BoolV(false) => Computation.PureFalse
                case _ =>
                  def loopRest(): Computation =
                    if !it.hasNext then Computation.PureTrue
                    else
                      val (k2, v2) = it.next()
                      FlatMap(interp.callEntry(arg, k2, v2, env), {
                        case Value.BoolV(false) => Computation.PureFalse
                        case _                  => loopRest()
                      })
                  loopRest()
              })
        Computation.PureTrue
      case "count"       =>
        val it = m.iterator
        var acc = 0L
        while it.hasNext do
          val (k, v) = it.next()
          interp.callEntry(arg, k, v, env) match
            case Pure(Value.BoolV(true))  => acc += 1L
            case Pure(_)                  =>
            case comp =>
              val capturedAcc = acc
              return FlatMap(comp, { r =>
                val newAcc = if r == Value.BoolV(true) then capturedAcc + 1L else capturedAcc
                def loopRest(a: Long): Computation =
                  if !it.hasNext then Computation.pureIntV(a)
                  else
                    val (k2, v2) = it.next()
                    FlatMap(interp.callEntry(arg, k2, v2, env), {
                      case Value.BoolV(true) => loopRest(a + 1L)
                      case _                 => loopRest(a)
                    })
                loopRest(newAcc)
              })
        Computation.pureIntV(acc)
      case "find"        =>
        val it = m.iterator
        while it.hasNext do
          val (k, v) = it.next()
          interp.callEntry(arg, k, v, env) match
            case Pure(Value.BoolV(true))  => return Pure(Value.OptionV(Some(Value.TupleV(k :: v :: Nil))))
            case Pure(_)                  =>
            case comp =>
              return FlatMap(comp, {
                case Value.BoolV(true) => Pure(Value.OptionV(Some(Value.TupleV(k :: v :: Nil))))
                case _ =>
                  def loopRest(): Computation =
                    if !it.hasNext then Computation.PureNone
                    else
                      val (k2, v2) = it.next()
                      FlatMap(interp.callEntry(arg, k2, v2, env), {
                        case Value.BoolV(true) => Pure(Value.OptionV(Some(Value.TupleV(k2 :: v2 :: Nil))))
                        case _                 => loopRest()
                      })
                  loopRest()
              })
        Computation.PureNone
      case _            => dispatchMap(m, name, arg :: Nil, env, interp)

  /** 1-arg fast path for Option. */
  private def dispatchOption1(recv: Value, opt: Option[Value], name: String, arg: Value, env: Env, interp: Interpreter): Computation =
    name match
      case "getOrElse" => opt match
        case Some(v) => Pure(v)
        case None    => Pure(arg)
      case "orElse"    => opt match
        case Some(_) => Pure(recv)
        case None    => Pure(arg)
      case "contains"  => Computation.pureBool(opt.contains(arg))
      case "map"       => opt match
        case None    => Computation.PureNone
        case Some(v) => interp.callValue1(arg, v, env) match
          case Pure(rv) => Pure(Value.OptionV(Some(rv)))
          case c        => FlatMap(c, Computation.wrapSomeC)
      case "flatMap"   => opt match
        case None    => Computation.PureNone
        case Some(v) => interp.callValue1(arg, v, env) match
          case Pure(o: Value.OptionV) => Pure(o)
          case Pure(other)            => Pure(Value.OptionV(Some(other)))
          case c                      => FlatMap(c, Computation.wrapOptionC)
      case "filter"    => opt match
        case None    => Computation.PureNone
        case Some(v) => interp.callValue1(arg, v, env) match
          case Pure(Value.BoolV(true)) => Pure(recv)
          case Pure(_)                 => Computation.PureNone
          case c                       => FlatMap(c, { case Value.BoolV(true) => Pure(recv); case _ => Computation.PureNone })
      case "foreach"   => opt match
        case None    => Computation.PureUnit
        case Some(v) => Computation.mapUnit(interp.callValue1(arg, v, env))
      case "exists"    => opt match
        case None    => Computation.PureFalse
        case Some(v) => interp.callValue1(arg, v, env)
      case "forall"    => opt match
        case None    => Computation.PureTrue
        case Some(v) => interp.callValue1(arg, v, env)
      case "zip"       => opt match
        case None    => Computation.PureNone
        case Some(v) => arg match
          case Value.OptionV(Some(w)) => Pure(Value.OptionV(Some(Value.TupleV(v :: w :: Nil))))
          case _                      => Computation.PureNone
      case "fold"      =>
        Pure(Value.NativeFnV("fold", {
          case List(f) => opt match
            case None    => Pure(arg)
            case Some(v) => interp.callValue1(f, v, env)
          case _ => throw InterpretError("Option.fold expects one function argument")
        }))
      case "toRight"   => opt match
        case None    => Pure(Value.InstanceV("Left",  new scala.collection.immutable.Map.Map1("value", arg)))
        case Some(v) => Pure(Value.InstanceV("Right", new scala.collection.immutable.Map.Map1("value", v)))
      case "toLeft"    => opt match
        case None    => Pure(Value.InstanceV("Right", new scala.collection.immutable.Map.Map1("value", arg)))
        case Some(v) => Pure(Value.InstanceV("Left",  new scala.collection.immutable.Map.Map1("value", v)))
      case _           => dispatchOption(recv, opt, name, arg :: Nil, env, interp)

  /** 1-arg fast path for String single-arg operations. */
  private def dispatchString1(recv: Value, s: String, name: String, arg: Value, env: Env, interp: Interpreter): Computation =
    name match
      case "contains"    => arg match
        case Value.StringV(t) => Computation.pureBool(s.contains(t))
        case _                => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "startsWith"  => arg match
        case Value.StringV(t) => Computation.pureBool(s.startsWith(t))
        case _                => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "endsWith"    => arg match
        case Value.StringV(t) => Computation.pureBool(s.endsWith(t))
        case _                => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "split"       => arg match
        case Value.StringV(sep) =>
          val parts = s.split(java.util.regex.Pattern.quote(sep), -1)
          var list: List[Value] = Nil; var i = parts.length - 1
          while i >= 0 do { list = Value.StringV(parts(i)) :: list; i -= 1 }
          Pure(Value.ListV(list))
        case _                  => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "repeat"      => arg match
        case Value.IntV(n) => Pure(Value.StringV(s * n.toInt))
        case _             => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "take"        => arg match
        case Value.IntV(n) => Pure(Value.StringV(s.take(n.toInt)))
        case _             => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "drop"        => arg match
        case Value.IntV(n) => Pure(Value.StringV(s.drop(n.toInt)))
        case _             => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "apply" | "charAt" => arg match
        case Value.IntV(n) => Pure(Value.charV(s.charAt(n.toInt)))
        case _             => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "indexOf"    => arg match
        case Value.StringV(t) => Computation.pureIntV(s.indexOf(t).toLong)
        case Value.CharV(c)   => Computation.pureIntV(s.indexOf(c.toInt).toLong)
        case _                => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "codePointAt" => arg match
        case Value.IntV(i) =>
          if i < 0 || i >= s.length then interp.located(s"index $i out of bounds for string of length ${s.length}")
          else Computation.pureIntV(s.codePointAt(i.toInt).toLong)
        case _             => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "map"       =>
        Computation.mapSequenceStr(s, c => interp.callValue1(arg, Value.charV(c), env)) match
          case Pure(Value.ListV(items)) => Pure(Value.StringV(items.iterator.map(Value.show).mkString))
          case Pure(_)                  => Pure(Value.StringV(s))
          case comp                     => FlatMap(comp, {
            case Value.ListV(items) => Pure(Value.StringV(items.iterator.map(Value.show).mkString))
            case _                  => Pure(Value.StringV(s))
          })
      case "foreach"   =>
        var i = 0
        while i < s.length do
          interp.callValue1(arg, Value.charV(s.charAt(i)), env) match
            case Pure(_) => i += 1
            case c =>
              val start = i + 1
              def restLoop(j: Int): Computation =
                if j >= s.length then Computation.PureUnit
                else FlatMap(interp.callValue1(arg, Value.charV(s.charAt(j)), env), _ => restLoop(j + 1))
              return FlatMap(c, _ => restLoop(start))
        Computation.PureUnit
      case "filter"    =>
        val sb = new java.lang.StringBuilder(s.length)
        var i = 0
        while i < s.length do
          val c = s.charAt(i)
          interp.callValue1(arg, Value.charV(c), env) match
            case Pure(Value.BoolV(true)) => sb.append(c); i += 1
            case Pure(_)                 => i += 1
            case comp =>
              val start = i + 1
              def restLoop(j: Int): Computation =
                if j >= s.length then Pure(Value.StringV(sb.toString))
                else
                  val ch = s.charAt(j)
                  FlatMap(interp.callValue1(arg, Value.charV(ch), env), {
                    case Value.BoolV(true) => sb.append(ch); restLoop(j + 1)
                    case _                 => restLoop(j + 1)
                  })
              return FlatMap(comp, {
                case Value.BoolV(true) => sb.append(c); restLoop(start)
                case _                 => restLoop(start)
              })
        Pure(Value.StringV(sb.toString))
      case "takeWhile" =>
        var i = 0
        while i < s.length do
          interp.callValue1(arg, Value.charV(s.charAt(i)), env) match
            case Pure(Value.BoolV(true)) => i += 1
            case Pure(_)                 => return Pure(Value.StringV(s.substring(0, i)))
            case c =>
              val start = i + 1
              def restLoop(j: Int): Computation =
                if j >= s.length then Pure(Value.StringV(s))
                else FlatMap(interp.callValue1(arg, Value.charV(s.charAt(j)), env), {
                  case Value.BoolV(true) => restLoop(j + 1)
                  case _                 => Pure(Value.StringV(s.substring(0, j)))
                })
              return FlatMap(c, {
                case Value.BoolV(true) => restLoop(start)
                case _                 => Pure(Value.StringV(s.substring(0, i)))
              })
        Pure(recv)
      case "dropWhile" =>
        var i = 0
        while i < s.length do
          interp.callValue1(arg, Value.charV(s.charAt(i)), env) match
            case Pure(Value.BoolV(true)) => i += 1
            case Pure(_)                 => return Pure(Value.StringV(s.substring(i)))
            case c =>
              val start = i + 1
              def restLoop(j: Int): Computation =
                if j >= s.length then Computation.PureEmptyStr
                else FlatMap(interp.callValue1(arg, Value.charV(s.charAt(j)), env), {
                  case Value.BoolV(true) => restLoop(j + 1)
                  case _                 => Pure(Value.StringV(s.substring(j)))
                })
              return FlatMap(c, {
                case Value.BoolV(true) => restLoop(start)
                case _                 => Pure(Value.StringV(s.substring(i)))
              })
        Computation.PureEmptyStr
      case "count"       =>
        var i = 0; var acc = 0L
        while i < s.length do
          interp.callValue1(arg, Value.charV(s.charAt(i)), env) match
            case Pure(Value.BoolV(true)) => acc += 1L; i += 1
            case Pure(_)                 => i += 1
            case c =>
              val start = i + 1; val acc0 = acc
              def restLoop(j: Int, cnt: Long): Computation =
                if j >= s.length then Computation.pureIntV(cnt)
                else FlatMap(interp.callValue1(arg, Value.charV(s.charAt(j)), env), {
                  case Value.BoolV(true) => restLoop(j + 1, cnt + 1L)
                  case _                 => restLoop(j + 1, cnt)
                })
              return FlatMap(c, {
                case Value.BoolV(true) => restLoop(start, acc0 + 1L)
                case _                 => restLoop(start, acc0)
              })
        Computation.pureIntV(acc)
      case "exists"      =>
        var i = 0
        while i < s.length do
          interp.callValue1(arg, Value.charV(s.charAt(i)), env) match
            case Pure(Value.BoolV(true)) => return Computation.PureTrue
            case Pure(_)                 => i += 1
            case c =>
              val start = i + 1
              def restLoop(j: Int): Computation =
                if j >= s.length then Computation.PureFalse
                else FlatMap(interp.callValue1(arg, Value.charV(s.charAt(j)), env), {
                  case Value.BoolV(true) => Computation.PureTrue
                  case _                 => restLoop(j + 1)
                })
              return FlatMap(c, {
                case Value.BoolV(true) => Computation.PureTrue
                case _                 => restLoop(start)
              })
        Computation.PureFalse
      case "forall"      =>
        var i = 0
        while i < s.length do
          interp.callValue1(arg, Value.charV(s.charAt(i)), env) match
            case Pure(Value.BoolV(false)) => return Computation.PureFalse
            case Pure(_)                  => i += 1
            case c =>
              val start = i + 1
              def restLoop(j: Int): Computation =
                if j >= s.length then Computation.PureTrue
                else FlatMap(interp.callValue1(arg, Value.charV(s.charAt(j)), env), {
                  case Value.BoolV(false) => Computation.PureFalse
                  case _                  => restLoop(j + 1)
                })
              return FlatMap(c, {
                case Value.BoolV(false) => Computation.PureFalse
                case _                  => restLoop(start)
              })
        Computation.PureTrue
      case "stripPrefix" => arg match
        case Value.StringV(t) => Pure(if s.startsWith(t) then Value.StringV(s.substring(t.length)) else recv)
        case _                => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "stripSuffix" => arg match
        case Value.StringV(t) => Pure(if s.endsWith(t) then Value.StringV(s.substring(0, s.length - t.length)) else recv)
        case _                => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "matches"     => arg match
        case Value.StringV(regex) => Computation.pureBool(s.matches(regex))
        case _                    => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "substring"   => arg match
        case Value.IntV(a) => Pure(Value.StringV(s.substring(a.toInt.max(0).min(s.length))))
        case _             => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case _             => dispatchString(recv, s, name, arg :: Nil, env, interp)

  /** 1-arg fast path for InstanceV: field access and class method call. */
  private def dispatchInstance1(recv: Value, typeName: String, fields: Map[String, Value], name: String, arg: Value, env: Env, interp: Interpreter): Computation =
    typeName match
      case "Right" => name match
        case "map"     =>
          val inner = fields.getOrElse("value", Value.UnitV)
          interp.callValue1(arg, inner, env) match
            case Pure(v) => Pure(Value.InstanceV("Right", new IMap.Map1("value", v)))
            case c       => FlatMap(c, v => Pure(Value.InstanceV("Right", new IMap.Map1("value", v))))
        case "flatMap" => interp.callValue1(arg, fields.getOrElse("value", Value.UnitV), env)
        case "fold"    => interp.callValue1(arg, fields.getOrElse("value", Value.UnitV), env)
        case _         => dispatchInstance(recv, typeName, fields, name, arg :: Nil, env, interp)
      case "Left" => name match
        case "getOrElse" => Pure(arg)
        case "fold"      => interp.callValue1(fields.getOrElse("value", Value.UnitV), arg, env)
        case _           => dispatchInstance(recv, typeName, fields, name, arg :: Nil, env, interp)
      case "Pid" => name match
        case "tell" | "!" => Perform("Actor", "send", recv :: arg :: Nil)
        case _            => dispatchInstance(recv, typeName, fields, name, arg :: Nil, env, interp)
      case _ =>
        val typeMethodMap = interp.typeMethods.getOrElse(typeName, null)
        if typeMethodMap != null then
          val fn = typeMethodMap.getOrElse(name, null)
          if fn != null then interp.callTypeMethod1(fn, fields, arg)
          else dispatchInstance(recv, typeName, fields, name, arg :: Nil, env, interp)
        else
          dispatchInstance(recv, typeName, fields, name, arg :: Nil, env, interp)

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
          val parts = s.split(java.util.regex.Pattern.quote(sep))
          var list: List[Value] = Nil; var i = parts.length - 1
          while i >= 0 do { list = Value.StringV(parts(i)) :: list; i -= 1 }
          Pure(Value.ListV(list))
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
          else Pure(Value.charV(s.charAt(i.toInt)))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "head"        =>
        if s.isEmpty then interp.located("head on empty String") else Pure(Value.charV(s.head))
      case "last"        =>
        if s.isEmpty then interp.located("last on empty String") else Pure(Value.charV(s.last))
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
          Computation.mapSequenceStr(s, c => interp.callValue1(f, Value.charV(c), env)) match
            case Pure(Value.ListV(items)) => Pure(Value.StringV(items.iterator.map(Value.show).mkString))
            case Pure(_)                  => Pure(Value.StringV(s))
            case comp                     => FlatMap(comp, {
              case Value.ListV(items) => Pure(Value.StringV(items.iterator.map(Value.show).mkString))
              case _                  => Pure(Value.StringV(s))
            })
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "takeWhile"   => args match
        case List(f) =>
          var i = 0
          while i < s.length do
            interp.callValue1(f, Value.charV(s.charAt(i)), env) match
              case Pure(Value.BoolV(true)) => i += 1
              case Pure(_)                 => return Pure(Value.StringV(s.substring(0, i)))
              case c =>
                val start = i + 1
                def restLoop(j: Int): Computation =
                  if j >= s.length then Pure(Value.StringV(s))
                  else FlatMap(interp.callValue1(f, Value.charV(s.charAt(j)), env), {
                    case Value.BoolV(true) => restLoop(j + 1)
                    case _                 => Pure(Value.StringV(s.substring(0, j)))
                  })
                return FlatMap(c, {
                  case Value.BoolV(true) => restLoop(start)
                  case _                 => Pure(Value.StringV(s.substring(0, i)))
                })
          Pure(recv)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "dropWhile"   => args match
        case List(f) =>
          var i = 0
          while i < s.length do
            interp.callValue1(f, Value.charV(s.charAt(i)), env) match
              case Pure(Value.BoolV(true)) => i += 1
              case Pure(_)                 => return Pure(Value.StringV(s.substring(i)))
              case c =>
                val start = i + 1
                def restLoop(j: Int): Computation =
                  if j >= s.length then Computation.PureEmptyStr
                  else FlatMap(interp.callValue1(f, Value.charV(s.charAt(j)), env), {
                    case Value.BoolV(true) => restLoop(j + 1)
                    case _                 => Pure(Value.StringV(s.substring(j)))
                  })
                return FlatMap(c, {
                  case Value.BoolV(true) => restLoop(start)
                  case _                 => Pure(Value.StringV(s.substring(i)))
                })
          Computation.PureEmptyStr
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foreach"     => args match
        case List(f) =>
          var i = 0
          while i < s.length do
            interp.callValue1(f, Value.charV(s.charAt(i)), env) match
              case Pure(_) => i += 1
              case c =>
                val start = i + 1
                def restLoop(j: Int): Computation =
                  if j >= s.length then Computation.PureUnit
                  else FlatMap(interp.callValue1(f, Value.charV(s.charAt(j)), env), _ => restLoop(j + 1))
                return FlatMap(c, _ => restLoop(start))
          Computation.PureUnit
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "filter"      => args match
        case List(f) =>
          val sb = new java.lang.StringBuilder(s.length)
          var i = 0
          while i < s.length do
            val c = s.charAt(i)
            interp.callValue1(f, Value.charV(c), env) match
              case Pure(Value.BoolV(true)) => sb.append(c); i += 1
              case Pure(_)                 => i += 1
              case comp =>
                val start = i + 1
                def restLoop(j: Int): Computation =
                  if j >= s.length then Pure(Value.StringV(sb.toString))
                  else
                    val c2 = s.charAt(j)
                    FlatMap(interp.callValue1(f, Value.charV(c2), env), {
                      case Value.BoolV(true) => sb.append(c2); restLoop(j + 1)
                      case _                 => restLoop(j + 1)
                    })
                return FlatMap(comp, {
                  case Value.BoolV(true) => sb.append(c); restLoop(start)
                  case _                 => restLoop(start)
                })
          Pure(if sb.length == s.length then recv else Value.StringV(sb.toString))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "count"       => args match
        case List(f) =>
          var i = 0; var acc = 0L
          while i < s.length do
            interp.callValue1(f, Value.charV(s.charAt(i)), env) match
              case Pure(Value.BoolV(true)) => acc += 1L; i += 1
              case Pure(_)                 => i += 1
              case c =>
                val start = i + 1; val acc0 = acc
                def restLoop(j: Int, cnt: Long): Computation =
                  if j >= s.length then Computation.pureIntV(cnt)
                  else FlatMap(interp.callValue1(f, Value.charV(s.charAt(j)), env), {
                    case Value.BoolV(true) => restLoop(j + 1, cnt + 1L)
                    case _                 => restLoop(j + 1, cnt)
                  })
                return FlatMap(c, {
                  case Value.BoolV(true) => restLoop(start, acc0 + 1L)
                  case _                 => restLoop(start, acc0)
                })
          Computation.pureIntV(acc)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "exists"      => args match
        case List(f) =>
          var i = 0
          while i < s.length do
            interp.callValue1(f, Value.charV(s.charAt(i)), env) match
              case Pure(Value.BoolV(true)) => return Computation.PureTrue
              case Pure(_)                 => i += 1
              case c =>
                val start = i + 1
                def restLoop(j: Int): Computation =
                  if j >= s.length then Computation.PureFalse
                  else FlatMap(interp.callValue1(f, Value.charV(s.charAt(j)), env), {
                    case Value.BoolV(true) => Computation.PureTrue
                    case _                 => restLoop(j + 1)
                  })
                return FlatMap(c, {
                  case Value.BoolV(true) => Computation.PureTrue
                  case _                 => restLoop(start)
                })
          Computation.PureFalse
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "forall"      => args match
        case List(f) =>
          var i = 0
          while i < s.length do
            interp.callValue1(f, Value.charV(s.charAt(i)), env) match
              case Pure(Value.BoolV(false)) => return Computation.PureFalse
              case Pure(_)                  => i += 1
              case c =>
                val start = i + 1
                def restLoop(j: Int): Computation =
                  if j >= s.length then Computation.PureTrue
                  else FlatMap(interp.callValue1(f, Value.charV(s.charAt(j)), env), {
                    case Value.BoolV(false) => Computation.PureFalse
                    case _                  => restLoop(j + 1)
                  })
                return FlatMap(c, {
                  case Value.BoolV(false) => Computation.PureFalse
                  case _                  => restLoop(start)
                })
          Computation.PureTrue
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "toList"      =>
        val buf = new scala.collection.mutable.ArrayBuffer[Value](s.length)
        var i = 0
        while i < s.length do { buf += Value.charV(s.charAt(i)); i += 1 }
        Pure(Value.ListV(buf.toList))
      case "init"        =>
        if s.isEmpty then interp.located("init on empty String")
        else Pure(Value.StringV(s.init))
      case "tail"        =>
        if s.isEmpty then interp.located("tail on empty String")
        else Pure(Value.StringV(s.tail))
      case "slice"       => args match
        case List(Value.IntV(a), Value.IntV(b)) =>
          val from = a.toInt.max(0).min(s.length)
          val to   = b.toInt.max(from).min(s.length)
          Pure(Value.StringV(s.slice(from, to)))
        case _                                  => dispatchFallback(recv, name, args, env, interp)
      case "replaceAll"  => args match
        case List(Value.StringV(regex), Value.StringV(repl)) => Pure(Value.StringV(s.replaceAll(regex, repl)))
        case _                                               => dispatchFallback(recv, name, args, env, interp)
      case "replaceFirst" => args match
        case List(Value.StringV(regex), Value.StringV(repl)) => Pure(Value.StringV(s.replaceFirst(regex, repl)))
        case _                                               => dispatchFallback(recv, name, args, env, interp)
      case "matches"     => args match
        case List(Value.StringV(regex)) => Computation.pureBool(s.matches(regex))
        case _                          => dispatchFallback(recv, name, args, env, interp)
      case "stripMargin" =>
        Pure(Value.StringV(s.stripMargin))
      case "lines"       =>
        val parts = s.split("\\R", -1)
        var list: List[Value] = Nil; var i = parts.length - 1
        while i >= 0 do { list = Value.StringV(parts(i)) :: list; i -= 1 }
        Pure(Value.ListV(list))
      case "stripPrefix" => args match
        case List(Value.StringV(prefix)) =>
          Pure(if s.startsWith(prefix) then Value.StringV(s.substring(prefix.length)) else recv)
        case _                           => dispatchFallback(recv, name, args, env, interp)
      case "stripSuffix" => args match
        case List(Value.StringV(suffix)) =>
          Pure(if s.endsWith(suffix) then Value.StringV(s.substring(0, s.length - suffix.length)) else recv)
        case _                           => dispatchFallback(recv, name, args, env, interp)
      case "padTo"       => args match
        case List(Value.IntV(n), Value.CharV(c)) =>
          if n <= s.length then Pure(recv)
          else Pure(Value.StringV(s.padTo(n.toInt, c)))
        case _                                   => dispatchFallback(recv, name, args, env, interp)
      case "zipWithIndex" =>
        val buf = new scala.collection.mutable.ArrayBuffer[Value](s.length)
        var i = 0
        while i < s.length do { buf += Value.TupleV(Value.charV(s.charAt(i)) :: Value.intV(i.toLong) :: Nil); i += 1 }
        Pure(Value.ListV(buf.toList))
      case _ => dispatchFallback(recv, name, args, env, interp)

  // ── Char ────────────────────────────────────────────────────────────────────

  private def dispatchChar(c: Char, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    name match
      case "toInt"          => Computation.pureIntV(c.toInt.toLong)
      case "toLong"         => Computation.pureIntV(c.toLong)
      case "toString"       => Pure(Value.StringV(c.toString))
      case "isDigit"        => Computation.pureBool(c.isDigit)
      case "isLetter"       => Computation.pureBool(c.isLetter)
      case "isLetterOrDigit"=> Computation.pureBool(c.isLetterOrDigit)
      case "isUpper" | "isUpperCase" => Computation.pureBool(c.isUpper)
      case "isLower" | "isLowerCase" => Computation.pureBool(c.isLower)
      case "isWhitespace" | "isSpaceChar" => Computation.pureBool(c.isWhitespace)
      case "isControl"      => Computation.pureBool(c.isControl)
      case "toUpper" | "toUpperCase" => Pure(Value.charV(c.toUpper))
      case "toLower" | "toLowerCase" => Pure(Value.charV(c.toLower))
      case "asDigit"        => Computation.pureIntV(c.asDigit.toLong)
      case _ =>
        val ext = extensionDispatch(Value.charV(c), name, args, env, interp)
        if ext != null then ext else interp.located(s"No method '$name' on Char")

  // ── Boolean ─────────────────────────────────────────────────────────────────

  private def dispatchBool(recv: Value, b: Boolean, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    name match
      case "toString"  => Pure(Value.StringV(b.toString))
      case "!"         => Computation.pureBool(!b)
      case "unary_!"   => Computation.pureBool(!b)
      case "toInt"     => Computation.pureIntV(if b then 1L else 0L)
      case "&&"        => args match
        case List(Value.BoolV(r)) => Computation.pureBool(b && r)
        case _                    => dispatchFallback(recv, name, args, env, interp)
      case "||"        => args match
        case List(Value.BoolV(r)) => Computation.pureBool(b || r)
        case _                    => dispatchFallback(recv, name, args, env, interp)
      case "^"         => args match
        case List(Value.BoolV(r)) => Computation.pureBool(b ^ r)
        case _                    => dispatchFallback(recv, name, args, env, interp)
      case "==" | "equals" => args match
        case List(Value.BoolV(r)) => Computation.pureBool(b == r)
        case _                    => Computation.pureBool(false)
      case "!="        => args match
        case List(Value.BoolV(r)) => Computation.pureBool(b != r)
        case _                    => Computation.pureBool(true)
      case "compare"   => args match
        case List(Value.BoolV(r)) => Computation.pureIntV(b.compare(r).toLong)
        case _                    => dispatchFallback(recv, name, args, env, interp)
      case _ =>
        val ext = extensionDispatch(recv, name, args, env, interp)
        if ext != null then ext else interp.located(s"No method '$name' on Boolean")

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
      case "sorted"       =>
        if ls.isEmpty then Pure(recv)
        else
          // Numeric fast path: if all elements are IntV, sort natively without string keys.
          var isAllInt = true; var chk = ls
          while isAllInt && chk.nonEmpty do
            if !chk.head.isInstanceOf[Value.IntV] then isAllInt = false
            chk = chk.tail
          if isAllInt then
            val arr = new Array[Long](ls.length)
            var i = 0; var rem2 = ls
            while rem2.nonEmpty do arr(i) = rem2.head.asInstanceOf[Value.IntV].v; i += 1; rem2 = rem2.tail
            java.util.Arrays.sort(arr)
            var result2: List[Value] = Nil; i = arr.length - 1
            while i >= 0 do result2 = Value.intV(arr(i)) :: result2; i -= 1
            Pure(Value.ListV(result2))
          else
            // Schwartzian: one array of (Value, key) — no intermediate List passes.
            val arr = new Array[(Value, String)](ls.length)
            var i = 0; var rem = ls
            while rem.nonEmpty do
              val v = rem.head; arr(i) = (v, Value.show(v)); i += 1; rem = rem.tail
            java.util.Arrays.sort(arr, java.util.Comparator.comparing[(Value, String), String](_._2))
            var result: List[Value] = Nil; i = arr.length - 1
            while i >= 0 do result = arr(i)._1 :: result; i -= 1
            Pure(Value.ListV(result))
      case "toList"       => Pure(recv)
      case "toSet"        => Pure(Value.ListV(ls.distinct))
      case "flatten"      =>
        val flatBuf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
        var flatRem = ls
        while flatRem.nonEmpty do
          flatRem.head match
            case Value.ListV(inner) => flatBuf ++= inner
            case v                  => flatBuf += v
          flatRem = flatRem.tail
        Pure(Value.ListV(flatBuf.toList))
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
        if ls.isEmpty then interp.located("min on empty list")
        else
          var minVal = ls.head
          var minKey = minVal match { case Value.IntV(n) => n.toDouble; case Value.DoubleV(d) => d; case _ => 0.0 }
          var minRem = ls.tail
          while minRem.nonEmpty do
            val v = minRem.head
            val k = v match { case Value.IntV(n) => n.toDouble; case Value.DoubleV(d) => d; case _ => 0.0 }
            if k < minKey then { minVal = v; minKey = k }
            minRem = minRem.tail
          Pure(minVal)
      case "max"          =>
        if ls.isEmpty then interp.located("max on empty list")
        else
          var maxVal = ls.head
          var maxKey = maxVal match { case Value.IntV(n) => n.toDouble; case Value.DoubleV(d) => d; case _ => 0.0 }
          var maxRem = ls.tail
          while maxRem.nonEmpty do
            val v = maxRem.head
            val k = v match { case Value.IntV(n) => n.toDouble; case Value.DoubleV(d) => d; case _ => 0.0 }
            if k > maxKey then { maxVal = v; maxKey = k }
            maxRem = maxRem.tail
          Pure(maxVal)
      case "maxBy"        => args match
        case List(f) =>
          if ls.isEmpty then interp.located("maxBy on empty list")
          else applyMaxBy(ls, Computation.mapSequence(ls, item => interp.callValue1(f, item, env)), max = true)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "minBy"        => args match
        case List(f) =>
          if ls.isEmpty then interp.located("minBy on empty list")
          else applyMaxBy(ls, Computation.mapSequence(ls, item => interp.callValue1(f, item, env)), max = false)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "product"      =>
        var intAcc = 1L; var dblAcc = 1.0; var isDouble = false
        var rem = ls
        while rem.nonEmpty do
          rem.head match
            case Value.IntV(n)    => intAcc *= n
            case Value.DoubleV(d) => dblAcc *= d; isDouble = true
            case v                => return interp.located(s"Cannot multiply $v")
          rem = rem.tail
        if isDouble then Pure(Value.doubleV(intAcc.toDouble * dblAcc))
        else Computation.pureIntV(intAcc)
      case "span"         => args match
        case List(f) =>
          val yesBuf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
          var rem = ls
          while rem.nonEmpty do
            interp.callValue1(f, rem.head, env) match
              case Pure(Value.BoolV(true)) => yesBuf += rem.head; rem = rem.tail
              case Pure(_)                 => return Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(rem) :: Nil))
              case comp =>
                val h = rem.head; val remaining = rem
                return FlatMap(comp, {
                  case Value.BoolV(true) =>
                    yesBuf += h
                    def loopRest(rest: List[Value]): Computation = rest match
                      case Nil    => Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(Nil) :: Nil))
                      case hh :: t =>
                        FlatMap(interp.callValue1(f, hh, env), {
                          case Value.BoolV(true) => yesBuf += hh; loopRest(t)
                          case _                 => Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(rest) :: Nil))
                        })
                    loopRest(rem.tail)
                  case _ => Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(remaining) :: Nil))
                })
          Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(Nil) :: Nil))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "intersect"    => args match
        case List(Value.ListV(other)) =>
          val otherSet = other.toSet
          val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length.min(other.length))
          var rem = ls
          while rem.nonEmpty do
            if otherSet.contains(rem.head) then buf += rem.head
            rem = rem.tail
          Pure(Value.ListV(buf.toList))
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "diff"         => args match
        case List(Value.ListV(other)) =>
          val otherSet = other.toSet
          val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
          var rem = ls
          while rem.nonEmpty do
            if !otherSet.contains(rem.head) then buf += rem.head
            rem = rem.tail
          Pure(Value.ListV(buf.toList))
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "groupMap"     => args match
        case List(kf, vf) =>
          val groups = scala.collection.mutable.LinkedHashMap.empty[Value, scala.collection.mutable.ArrayBuffer[Value]]
          var gmRem = ls
          while gmRem.nonEmpty do
            val h = gmRem.head
            (interp.callValue1(kf, h, env), interp.callValue1(vf, h, env)) match
              case (Pure(k), Pure(v)) =>
                groups.getOrElseUpdate(k, new scala.collection.mutable.ArrayBuffer[Value]) += v
                gmRem = gmRem.tail
              case (kComp, vComp) =>
                val tail = gmRem.tail
                def loopRest(remaining: List[Value]): Computation = remaining match
                  case Nil =>
                    Pure(Value.MapV(groups.iterator.map { (k, buf) => k -> Value.ListV(buf.toList) }.toMap))
                  case hh :: rest =>
                    FlatMap(interp.callValue1(kf, hh, env), { k =>
                      FlatMap(interp.callValue1(vf, hh, env), { v =>
                        groups.getOrElseUpdate(k, new scala.collection.mutable.ArrayBuffer[Value]) += v
                        loopRest(rest)
                      })
                    })
                return FlatMap(kComp, { k =>
                  FlatMap(vComp, { v =>
                    groups.getOrElseUpdate(k, new scala.collection.mutable.ArrayBuffer[Value]) += v
                    loopRest(tail)
                  })
                })
          Pure(Value.MapV(groups.iterator.map { (k, buf) => k -> Value.ListV(buf.toList) }.toMap))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "zipWithIndex" =>
        val ziBuf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
        var ziRem = ls; var ziIdx = 0
        while ziRem.nonEmpty do
          ziBuf += Value.TupleV(ziRem.head :: Value.intV(ziIdx.toLong) :: Nil)
          ziRem = ziRem.tail; ziIdx += 1
        Pure(Value.ListV(ziBuf.toList))
      case "indices"      =>
        var list: List[Value] = Nil; var idxI = ls.length - 1
        while idxI >= 0 do { list = Value.intV(idxI.toLong) :: list; idxI -= 1 }
        Pure(Value.ListV(list))
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
          Pure(Value.TupleV(Value.ListV(a) :: Value.ListV(b) :: Nil))
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
            zipBuf += Value.TupleV(zipAs.head :: zipBs.head :: Nil)
            zipAs = zipAs.tail; zipBs = zipBs.tail
          Pure(Value.ListV(zipBuf.toList))
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "mkString"     => args match
        case Nil                      => Pure(Value.StringV(ls.iterator.map(Value.show).mkString))
        case List(Value.StringV(sep)) => Pure(Value.StringV(ls.iterator.map(Value.show).mkString(sep)))
        case List(Value.StringV(s), Value.StringV(sep), Value.StringV(e)) =>
          Pure(Value.StringV(ls.iterator.map(Value.show).mkString(s, sep, e)))
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "map"          => args match
        case List(f) => Computation.mapSequence(ls, item => interp.callValue1(f, item, env))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "flatMap"      => args match
        case List(f) =>
          val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
          var rem = ls
          while rem.nonEmpty do
            interp.callValue1(f, rem.head, env) match
              case Pure(Value.ListV(inner)) => buf ++= inner; rem = rem.tail
              case Pure(v)                  => buf += v; rem = rem.tail
              case comp =>
                val tail = rem.tail
                def loopRest(remaining: List[Value]): Computation = remaining match
                  case Nil => Pure(Value.ListV(buf.toList))
                  case h :: rest =>
                    FlatMap(interp.callValue1(f, h, env), {
                      case Value.ListV(inner) => buf ++= inner; loopRest(rest)
                      case v                  => buf += v;      loopRest(rest)
                    })
                return FlatMap(comp, {
                  case Value.ListV(inner) => buf ++= inner; loopRest(tail)
                  case v                  => buf += v;      loopRest(tail)
                })
          Pure(Value.ListV(buf.toList))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "filter"       => args match
        case List(f) => Computation.filterSequence(ls, item => interp.callValue1(f, item, env))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "filterNot"    => args match
        case List(f) => Computation.filterNotSequence(ls, item => interp.callValue1(f, item, env))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foreach"      => args match
        case List(f) => Computation.foreachSequence(ls, item => interp.callValue1(f, item, env))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "count"        => args match
        case List(f) =>
          var rem = ls; var acc = 0L
          while rem.nonEmpty do
            interp.callValue1(f, rem.head, env) match
              case Pure(Value.BoolV(true)) => acc += 1L; rem = rem.tail
              case Pure(_)                 => rem = rem.tail
              case c =>
                val acc0 = acc; val tail = rem.tail
                def restLoop(remaining: List[Value], cnt: Long): Computation = remaining match
                  case Nil => Computation.pureIntV(cnt)
                  case h :: rest =>
                    FlatMap(interp.callValue1(f, h, env), {
                      case Value.BoolV(true) => restLoop(rest, cnt + 1L)
                      case _                 => restLoop(rest, cnt)
                    })
                return FlatMap(c, {
                  case Value.BoolV(true) => restLoop(tail, acc0 + 1L)
                  case _                 => restLoop(tail, acc0)
                })
          Computation.pureIntV(acc)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "find"         => args match
        case List(f) =>
          var rem = ls
          while rem.nonEmpty do
            val h = rem.head
            interp.callValue1(f, h, env) match
              case Pure(Value.BoolV(true)) => return Pure(Value.OptionV(Some(h)))
              case Pure(_)                 => rem = rem.tail
              case c =>
                val tail = rem.tail
                def restLoop(remaining: List[Value]): Computation = remaining match
                  case Nil => Computation.PureNone
                  case hh :: rest =>
                    FlatMap(interp.callValue1(f, hh, env), {
                      case Value.BoolV(true) => Pure(Value.OptionV(Some(hh)))
                      case _                 => restLoop(rest)
                    })
                return FlatMap(c, {
                  case Value.BoolV(true) => Pure(Value.OptionV(Some(h)))
                  case _                 => restLoop(tail)
                })
          Computation.PureNone
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "exists"       => args match
        case List(f) =>
          var rem = ls
          while rem.nonEmpty do
            interp.callValue1(f, rem.head, env) match
              case Pure(Value.BoolV(true)) => return Computation.PureTrue
              case Pure(_)                 => rem = rem.tail
              case c =>
                val tail = rem.tail
                def restLoop(remaining: List[Value]): Computation = remaining match
                  case Nil => Computation.PureFalse
                  case h :: rest =>
                    FlatMap(interp.callValue1(f, h, env), {
                      case Value.BoolV(true) => Computation.PureTrue
                      case _                 => restLoop(rest)
                    })
                return FlatMap(c, {
                  case Value.BoolV(true) => Computation.PureTrue
                  case _                 => restLoop(tail)
                })
          Computation.PureFalse
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "forall"       => args match
        case List(f) =>
          var rem = ls
          while rem.nonEmpty do
            interp.callValue1(f, rem.head, env) match
              case Pure(Value.BoolV(false)) => return Computation.PureFalse
              case Pure(_)                  => rem = rem.tail
              case c =>
                val tail = rem.tail
                def restLoop(remaining: List[Value]): Computation = remaining match
                  case Nil => Computation.PureTrue
                  case h :: rest =>
                    FlatMap(interp.callValue1(f, h, env), {
                      case Value.BoolV(false) => Computation.PureFalse
                      case _                  => restLoop(rest)
                    })
                return FlatMap(c, {
                  case Value.BoolV(false) => Computation.PureFalse
                  case _                  => restLoop(tail)
                })
          Computation.PureTrue
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "sortBy"       => args match
        case List(f) =>
          applySortBy(ls, recv, Computation.mapSequence(ls, item => interp.callValue1(f, item, env)))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foldLeft"     => args match
        case List(init) =>
          Pure(Value.NativeFnV("foldLeft", {
            case List(f) =>
              Computation.foldLeftSequence(ls, init, (acc, h) => interp.callValue2(f, acc, h, env))
            case _ => throw InterpretError("foldLeft expects one function argument")
          }))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foldRight"    => args match
        case List(init) =>
          Pure(Value.NativeFnV("foldRight", {
            case List(f) =>
              Computation.foldLeftSequence(ls.reverse, init, (acc, h) => interp.callValue2(f, h, acc, env))
            case _ => throw InterpretError("foldRight expects one function argument")
          }))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "reduceLeft"   => args match
        case List(f) => ls match
          case Nil    => interp.located("reduceLeft on empty list")
          case h :: t => Computation.foldLeftSequence(t, h, (acc, x) => interp.callValue2(f, acc, x, env))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "partition"    => args match
        case List(f) => Computation.partitionSequence(ls, item => interp.callValue1(f, item, env))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "groupBy"      => args match
        case List(f) =>
          val groups = scala.collection.mutable.LinkedHashMap.empty[Value, scala.collection.mutable.ArrayBuffer[Value]]
          var rem = ls
          while rem.nonEmpty do
            val h = rem.head
            interp.callValue1(f, h, env) match
              case Pure(k) =>
                groups.getOrElseUpdate(k, new scala.collection.mutable.ArrayBuffer[Value]) += h
                rem = rem.tail
              case c =>
                val tail = rem.tail
                def loopRest(remaining: List[Value]): Computation = remaining match
                  case Nil => Pure(Value.MapV(groups.iterator.map((k2, buf) => k2 -> Value.ListV(buf.toList)).toMap))
                  case hh :: rest =>
                    FlatMap(interp.callValue1(f, hh, env), { k2 =>
                      groups.getOrElseUpdate(k2, new scala.collection.mutable.ArrayBuffer[Value]) += hh
                      loopRest(rest)
                    })
                return FlatMap(c, { k =>
                  groups.getOrElseUpdate(k, new scala.collection.mutable.ArrayBuffer[Value]) += h
                  loopRest(tail)
                })
          Pure(Value.MapV(groups.iterator.map((k, buf) => k -> Value.ListV(buf.toList)).toMap))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "scanLeft"     => args match
        case List(init) =>
          Pure(Value.NativeFnV("scanLeft", {
            case List(f) =>
              val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length + 1)
              buf += init
              def loop(remaining: List[Value], acc: Value): Computation = remaining match
                case Nil       => Pure(Value.ListV(buf.toList))
                case h :: rest => FlatMap(interp.callValue2(f, acc, h, env), { v => buf += v; loop(rest, v) })
              loop(ls, init)
            case _ => throw InterpretError("scanLeft expects one function argument")
          }))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "collect"      => args match
        case List(f) =>
          val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
          var rem = ls
          while rem.nonEmpty do
            interp.callValue1(f, rem.head, env) match
              case Pure(Value.OptionV(Some(v))) => buf += v; rem = rem.tail
              case Pure(Value.OptionV(None))    => rem = rem.tail
              case Pure(v)                      => buf += v; rem = rem.tail
              case comp =>
                val tail = rem.tail
                def loopRest(remaining: List[Value]): Computation = remaining match
                  case Nil => Pure(Value.ListV(buf.toList))
                  case h :: rest =>
                    FlatMap(interp.callValue1(f, h, env), {
                      case Value.OptionV(Some(v)) => buf += v; loopRest(rest)
                      case Value.OptionV(None)    => loopRest(rest)
                      case v                      => buf += v; loopRest(rest)
                    })
                return FlatMap(comp, {
                  case Value.OptionV(Some(v)) => buf += v; loopRest(tail)
                  case Value.OptionV(None)    => loopRest(tail)
                  case v                      => buf += v; loopRest(tail)
                })
          Pure(Value.ListV(buf.toList))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "unzip"        =>
        if ls.isEmpty then Pure(Value.TupleV(Value.ListV(Nil) :: Value.ListV(Nil) :: Nil))
        else
          val as = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
          val bs = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
          var rem = ls
          while rem.nonEmpty do
            rem.head match
              case Value.TupleV(a :: b :: _) => as += a; bs += b
              case _                          => ()
            rem = rem.tail
          Pure(Value.TupleV(Value.ListV(as.toList) :: Value.ListV(bs.toList) :: Nil))
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
        val tlIt = m.iterator
        while tlIt.hasNext do
          val (k, v) = tlIt.next()
          tlBuf += Value.TupleV(k :: v :: Nil)
        Pure(Value.ListV(tlBuf.toList))
      case "mkString" => Pure(Value.StringV(Value.show(recv)))
      case "contains" => args match
        case List(k)       => Computation.pureBool(m.contains(k))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "get"      => args match
        case List(k)       =>
          val gv = m.getOrElse(k, null)
          if gv == null then Computation.PureNone else Pure(Value.OptionV(Some(gv)))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "apply"    => args match
        case List(k)       => Pure(m.getOrElse(k, interp.located(s"Key not found: ${Value.show(k)}")))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "getOrElse" => args match
        case List(k, d)    => Pure(m.getOrElse(k, d))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "updated"  => args match
        case List(k, v)    => Pure(Value.MapV(m.updated(k, v)))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "removed"  => args match
        case List(k)       => Pure(Value.MapV(m - k))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "+"        => args match
        case List(Value.TupleV(k :: v :: Nil)) => Pure(Value.MapV(m.updated(k, v)))
        case _                              => dispatchFallback(recv, name, args, env, interp)
      case "++"       => args match
        case List(Value.MapV(other))        => Pure(Value.MapV(m ++ other))
        case _                              => dispatchFallback(recv, name, args, env, interp)
      case "-"        => args match
        case List(k)       => Pure(Value.MapV(m - k))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "map"      => args match
        case List(f) =>
          val mapIt  = m.iterator
          val mapBuf = scala.collection.mutable.Map.empty[Value, Value]
          while mapIt.hasNext do
            val (k, v) = mapIt.next()
            interp.callEntry(f, k, v, env) match
              case Pure(Value.TupleV(nk :: nv :: Nil)) => mapBuf(nk) = nv
              case Pure(_)                             => // skip malformed
              case comp =>
                def loopMap(r: Value): Computation =
                  r match
                    case Value.TupleV(nk :: nv :: Nil) => mapBuf(nk) = nv
                    case _                             => // skip malformed
                  if !mapIt.hasNext then Pure(Value.MapV(mapBuf.toMap))
                  else
                    val (k2, v2) = mapIt.next()
                    FlatMap(interp.callEntry(f, k2, v2, env), loopMap)
                return FlatMap(comp, loopMap)
          Pure(Value.MapV(mapBuf.toMap))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "filter"   => args match
        case List(f) =>
          val filtIt  = m.iterator
          val filtBuf = scala.collection.mutable.Map.empty[Value, Value]
          while filtIt.hasNext do
            val (k, v) = filtIt.next()
            interp.callEntry(f, k, v, env) match
              case Pure(Value.BoolV(true))  => filtBuf(k) = v
              case Pure(_)                  => // skip
              case comp =>
                def loopFilt(r: Value): Computation =
                  r match
                    case Value.BoolV(true) => filtBuf(k) = v
                    case _                 =>
                  def rest(): Computation =
                    if !filtIt.hasNext then Pure(Value.MapV(filtBuf.toMap))
                    else
                      val (k2, v2) = filtIt.next()
                      FlatMap(interp.callEntry(f, k2, v2, env), { r2 =>
                        if r2 == Value.BoolV(true) then filtBuf(k2) = v2
                        rest()
                      })
                  rest()
                return FlatMap(comp, loopFilt)
          Pure(Value.MapV(filtBuf.toMap))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foreach"   => args match
        case List(f) =>
          val it = m.iterator
          while it.hasNext do
            val (k, v) = it.next()
            interp.callEntry(f, k, v, env) match
              case Pure(_) =>
              case c =>
                def restLoop(): Computation =
                  if !it.hasNext then Computation.PureUnit
                  else
                    val (k2, v2) = it.next()
                    FlatMap(interp.callEntry(f, k2, v2, env), _ => restLoop())
                return FlatMap(c, _ => restLoop())
          Computation.PureUnit
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "mapValues" => args match
        case List(f) =>
          val it  = m.iterator
          val buf = scala.collection.mutable.Map.empty[Value, Value]
          while it.hasNext do
            val (k, v) = it.next()
            interp.callValue1(f, v, env) match
              case Pure(nv) => buf(k) = nv
              case c =>
                def restLoop(): Computation =
                  if !it.hasNext then Pure(Value.MapV(buf.toMap))
                  else
                    val (k2, v2) = it.next()
                    FlatMap(interp.callValue1(f, v2, env), { nv2 => buf(k2) = nv2; restLoop() })
                return FlatMap(c, { nv => buf(k) = nv; restLoop() })
          Pure(Value.MapV(buf.toMap))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foldLeft"  => args match
        case List(init) =>
          Pure(Value.NativeFnV("foldLeft", {
            case List(f) =>
              val it = m.iterator
              // Use FlatMap directly (not Computation.flatMap) so the trampoline
              // handles the loop iteratively — stack-safe for any map size.
              def loop(acc: Value): Computation =
                if !it.hasNext then Pure(acc)
                else
                  val (k, v) = it.next()
                  FlatMap(interp.callValue2(f, acc, Value.TupleV(k :: v :: Nil), env), loop)
              loop(init)
            case _ => throw InterpretError("Map.foldLeft expects one function argument")
          }))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "exists"    => args match
        case List(f) =>
          val it = m.iterator
          while it.hasNext do
            val (k, v) = it.next()
            interp.callEntry(f, k, v, env) match
              case Pure(Value.BoolV(true))  => return Computation.PureTrue
              case Pure(_)                  => // continue
              case comp =>
                return FlatMap(comp, {
                  case Value.BoolV(true) => Computation.PureTrue
                  case _ =>
                    def loopRest(): Computation =
                      if !it.hasNext then Computation.PureFalse
                      else
                        val (k2, v2) = it.next()
                        FlatMap(interp.callEntry(f, k2, v2, env), {
                          case Value.BoolV(true) => Computation.PureTrue
                          case _                 => loopRest()
                        })
                    loopRest()
                })
          Computation.PureFalse
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "forall"    => args match
        case List(f) =>
          val it = m.iterator
          while it.hasNext do
            val (k, v) = it.next()
            interp.callEntry(f, k, v, env) match
              case Pure(Value.BoolV(false)) => return Computation.PureFalse
              case Pure(_)                  => // continue
              case comp =>
                return FlatMap(comp, {
                  case Value.BoolV(false) => Computation.PureFalse
                  case _ =>
                    def loopRest(): Computation =
                      if !it.hasNext then Computation.PureTrue
                      else
                        val (k2, v2) = it.next()
                        FlatMap(interp.callEntry(f, k2, v2, env), {
                          case Value.BoolV(false) => Computation.PureFalse
                          case _                  => loopRest()
                        })
                    loopRest()
                })
          Computation.PureTrue
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "count"     => args match
        case List(f) =>
          val it = m.iterator
          var acc = 0L
          while it.hasNext do
            val (k, v) = it.next()
            interp.callEntry(f, k, v, env) match
              case Pure(Value.BoolV(true))  => acc += 1L
              case Pure(_)                  => // false, no increment
              case comp =>
                val capturedAcc = acc
                return FlatMap(comp, { r =>
                  val newAcc = if r == Value.BoolV(true) then capturedAcc + 1L else capturedAcc
                  def loopRest(a: Long): Computation =
                    if !it.hasNext then Computation.pureIntV(a)
                    else
                      val (k2, v2) = it.next()
                      FlatMap(interp.callEntry(f, k2, v2, env), {
                        case Value.BoolV(true) => loopRest(a + 1L)
                        case _                 => loopRest(a)
                      })
                  loopRest(newAcc)
                })
          Computation.pureIntV(acc)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "find"      => args match
        case List(f) =>
          val it = m.iterator
          while it.hasNext do
            val (k, v) = it.next()
            interp.callEntry(f, k, v, env) match
              case Pure(Value.BoolV(true))  => return Pure(Value.OptionV(Some(Value.TupleV(k :: v :: Nil))))
              case Pure(_)                  => // continue
              case comp =>
                return FlatMap(comp, {
                  case Value.BoolV(true) => Pure(Value.OptionV(Some(Value.TupleV(k :: v :: Nil))))
                  case _ =>
                    def loopRest(): Computation =
                      if !it.hasNext then Computation.PureNone
                      else
                        val (k2, v2) = it.next()
                        FlatMap(interp.callEntry(f, k2, v2, env), {
                          case Value.BoolV(true) => Pure(Value.OptionV(Some(Value.TupleV(k2 :: v2 :: Nil))))
                          case _                 => loopRest()
                        })
                    loopRest()
                })
          Computation.PureNone
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
        case Some(v) => Pure(Value.ListV(v :: Nil))
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
          case Some(v) => interp.callValue1(f, v, env) match
            case Pure(rv) => Pure(Value.OptionV(Some(rv)))
            case c        => FlatMap(c, Computation.wrapSomeC)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "flatMap"   => args match
        case List(f) => opt match
          case None    => Computation.PureNone
          case Some(v) => interp.callValue1(f, v, env) match
            case Pure(o: Value.OptionV) => Pure(o)
            case Pure(other)            => Pure(Value.OptionV(Some(other)))
            case c                      => FlatMap(c, Computation.wrapOptionC)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "filter"    => args match
        case List(f) => opt match
          case None    => Computation.PureNone
          case Some(v) => interp.callValue1(f, v, env) match
            case Pure(Value.BoolV(true)) => Pure(recv)
            case Pure(_)                 => Computation.PureNone
            case c                       => FlatMap(c, { case Value.BoolV(true) => Pure(recv); case _ => Computation.PureNone })
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foreach"   => args match
        case List(f) => opt match
          case None    => Computation.PureUnit
          case Some(v) => Computation.mapUnit(interp.callValue1(f, v, env))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "fold"      => args match
        case List(default) =>
          Pure(Value.NativeFnV("fold", {
            case List(f) => opt match
              case None    => Pure(default)
              case Some(v) => interp.callValue1(f, v, env)
            case _ => throw InterpretError("Option.fold expects one function argument")
          }))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "zip"       => args match
        case List(other) => opt match
          case None    => Computation.PureNone
          case Some(v) => other match
            case Value.OptionV(Some(w)) => Pure(Value.OptionV(Some(Value.TupleV(v :: w :: Nil))))
            case Value.NoneV | Value.OptionV(None) => Computation.PureNone
            case _                      => Computation.PureNone
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "flatten"   => opt match
        case None            => Computation.PureNone
        case Some(inner)     => inner match
          case o: Value.OptionV => Pure(o)
          case _                => Pure(recv)
      case "toRight"   => args match
        case List(left) => opt match
          case None    => Pure(Value.InstanceV("Left",  new IMap.Map1("value", left)))
          case Some(v) => Pure(Value.InstanceV("Right", new IMap.Map1("value", v)))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "toLeft"    => args match
        case List(right) => opt match
          case None    => Pure(Value.InstanceV("Right", new IMap.Map1("value", right)))
          case Some(v) => Pure(Value.InstanceV("Left",  new IMap.Map1("value", v)))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "exists"    => args match
        case List(f) => opt match
          case None    => Computation.PureFalse
          case Some(v) => interp.callValue1(f, v, env)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "forall"    => args match
        case List(f) => opt match
          case None    => Computation.PureTrue
          case Some(v) => interp.callValue1(f, v, env)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case _ => dispatchFallback(recv, name, args, env, interp)

  // ── Int ─────────────────────────────────────────────────────────────────────

  /** Single-arg fast path for Int — avoids `arg :: Nil` cons cell for max/min/to/until. */
  private def dispatchInt1(n: Long, name: String, arg: Value, env: Env, interp: Interpreter): Computation =
    val recv = Value.intV(n)
    name match
      case "max"   => arg match
        case Value.IntV(m)    => Computation.pureIntV(math.max(n, m))
        case Value.DoubleV(d) => Pure(Value.doubleV(math.max(n.toDouble, d)))
        case _                => dispatchFallback(recv, name, arg :: Nil, env, interp)
      case "min"   => arg match
        case Value.IntV(m)    => Computation.pureIntV(math.min(n, m))
        case Value.DoubleV(d) => Pure(Value.doubleV(math.min(n.toDouble, d)))
        case _                => dispatchFallback(recv, name, arg :: Nil, env, interp)
      case "to"    => arg match
        case Value.IntV(m) =>
          var list: List[Value] = Nil; var i = m
          while i >= n do { list = Value.intV(i) :: list; i -= 1 }
          Pure(Value.ListV(list))
        case _ => dispatchFallback(recv, name, arg :: Nil, env, interp)
      case "until" => arg match
        case Value.IntV(m) =>
          var list: List[Value] = Nil; var i = m - 1
          while i >= n do { list = Value.intV(i) :: list; i -= 1 }
          Pure(Value.ListV(list))
        case _ => dispatchFallback(recv, name, arg :: Nil, env, interp)
      case _       => dispatchInt(n, name, arg :: Nil, env, interp)

  private def dispatchInt(n: Long, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    val recv = Value.intV(n)
    name match
      case "toDouble"  => Pure(Value.doubleV(n.toDouble))
      case "toLong"    => Pure(recv)
      case "toInt"     => Pure(recv)
      case "toFloat"   => Pure(Value.doubleV(n.toDouble))
      case "toByte"    => Computation.pureIntV(n.toByte.toLong)
      case "toShort"   => Computation.pureIntV(n.toShort.toLong)
      case "abs"       => Computation.pureIntV(math.abs(n))
      case "toString"  => Pure(Value.StringV(n.toString))
      case "max"       => args match
        case List(Value.IntV(m))    => Computation.pureIntV(math.max(n, m))
        case List(Value.DoubleV(d)) => Pure(Value.doubleV(math.max(n.toDouble, d)))
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case "min"       => args match
        case List(Value.IntV(m))    => Computation.pureIntV(math.min(n, m))
        case List(Value.DoubleV(d)) => Pure(Value.doubleV(math.min(n.toDouble, d)))
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case "clamp"     => args match
        case List(Value.IntV(lo), Value.IntV(hi)) => Computation.pureIntV(math.max(lo, math.min(hi, n)))
        case _                                    => dispatchFallback(recv, name, args, env, interp)
      case "sign"      => Computation.pureIntV(java.lang.Long.signum(n).toLong)
      case "signum"    => Computation.pureIntV(java.lang.Long.signum(n).toLong)
      case "isEven"    => Computation.pureBool((n & 1L) == 0L)
      case "isOdd"     => Computation.pureBool((n & 1L) != 0L)
      case "toBinary"  => Pure(Value.StringV(java.lang.Long.toBinaryString(n)))
      case "toHex"     => Pure(Value.StringV(java.lang.Long.toHexString(n)))
      case "to"        => args match
        case List(Value.IntV(m)) =>
          var list: List[Value] = Nil; var i = m
          while i >= n do { list = Value.intV(i) :: list; i -= 1 }
          Pure(Value.ListV(list))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "until"     => args match
        case List(Value.IntV(m)) =>
          var list: List[Value] = Nil; var i = m - 1
          while i >= n do { list = Value.intV(i) :: list; i -= 1 }
          Pure(Value.ListV(list))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case _ => dispatchFallback(recv, name, args, env, interp)

  // ── Double ──────────────────────────────────────────────────────────────────

  /** 1-arg fast path for Double: avoids arg :: Nil cons cell for the common math ops. */
  private def dispatchDouble1(d: Double, @annotation.unused recv: Value, name: String, arg: Value, env: Env, interp: Interpreter): Computation =
    name match
      case "max"   => arg match
        case Value.DoubleV(b) => Pure(Value.doubleV(math.max(d, b)))
        case Value.IntV(b)    => Pure(Value.doubleV(math.max(d, b.toDouble)))
        case _                => dispatchDouble(d, name, arg :: Nil, env, interp)
      case "min"   => arg match
        case Value.DoubleV(b) => Pure(Value.doubleV(math.min(d, b)))
        case Value.IntV(b)    => Pure(Value.doubleV(math.min(d, b.toDouble)))
        case _                => dispatchDouble(d, name, arg :: Nil, env, interp)
      case "pow"   => arg match
        case Value.DoubleV(b) => Pure(Value.doubleV(math.pow(d, b)))
        case Value.IntV(b)    => Pure(Value.doubleV(math.pow(d, b.toDouble)))
        case _                => dispatchDouble(d, name, arg :: Nil, env, interp)
      case "atan2" => arg match
        case Value.DoubleV(b) => Pure(Value.doubleV(math.atan2(d, b)))
        case Value.IntV(b)    => Pure(Value.doubleV(math.atan2(d, b.toDouble)))
        case _                => dispatchDouble(d, name, arg :: Nil, env, interp)
      case _       => dispatchDouble(d, name, arg :: Nil, env, interp)

  private def dispatchDouble(d: Double, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    val recv = Value.doubleV(d)
    name match
      case "toInt"    => Computation.pureIntV(d.toLong)
      case "toLong"   => Computation.pureIntV(d.toLong)
      case "toFloat"  => Pure(recv)
      case "abs"      => Pure(Value.doubleV(math.abs(d)))
      case "toString" => Pure(Value.StringV(d.toString))
      case "round"    => Computation.pureIntV(math.round(d))
      case "floor"    => Pure(Value.doubleV(math.floor(d)))
      case "ceil"     => Pure(Value.doubleV(math.ceil(d)))
      case "sqrt"     => Pure(Value.doubleV(math.sqrt(d)))
      case "log"      => Pure(Value.doubleV(math.log(d)))
      case "log10"    => Pure(Value.doubleV(math.log10(d)))
      case "exp"      => Pure(Value.doubleV(math.exp(d)))
      case "sin"      => Pure(Value.doubleV(math.sin(d)))
      case "cos"      => Pure(Value.doubleV(math.cos(d)))
      case "tan"      => Pure(Value.doubleV(math.tan(d)))
      case "asin"     => Pure(Value.doubleV(math.asin(d)))
      case "acos"     => Pure(Value.doubleV(math.acos(d)))
      case "atan"     => Pure(Value.doubleV(math.atan(d)))
      case "isNaN"    => Computation.pureBool(d.isNaN)
      case "isInfinite" => Computation.pureBool(d.isInfinite)
      case "sign"     => Pure(Value.doubleV(math.signum(d)))
      case "signum"   => Pure(Value.doubleV(math.signum(d)))
      case "max"      => args match
        case List(Value.DoubleV(b)) => Pure(Value.doubleV(math.max(d, b)))
        case List(Value.IntV(b))    => Pure(Value.doubleV(math.max(d, b.toDouble)))
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case "min"      => args match
        case List(Value.DoubleV(b)) => Pure(Value.doubleV(math.min(d, b)))
        case List(Value.IntV(b))    => Pure(Value.doubleV(math.min(d, b.toDouble)))
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case "pow"      => args match
        case List(Value.DoubleV(b)) => Pure(Value.doubleV(math.pow(d, b)))
        case List(Value.IntV(b))    => Pure(Value.doubleV(math.pow(d, b.toDouble)))
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case "atan2"    => args match
        case List(Value.DoubleV(b)) => Pure(Value.doubleV(math.atan2(d, b)))
        case List(Value.IntV(b))    => Pure(Value.doubleV(math.atan2(d, b.toDouble)))
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case _ =>
        val ext = extensionDispatch(recv, name, args, env, interp)
        if ext != null then ext else interp.located(s"No method '$name' on Double")

  // ── Tuple ───────────────────────────────────────────────────────────────────

  private def dispatchTuple(es: List[Value], name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    lazy val recv = Value.TupleV(es)
    name match
      case "++"  => args match
        case List(Value.TupleV(bs)) => Pure(Value.TupleV(es ++ bs))
        case List(Value.UnitV)      => Pure(recv)
        case List(v)                => Pure(Value.TupleV(es :+ v))
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case "_1"     => if es.length > 0 then Pure(es(0)) else interp.located("_1 on empty tuple")
      case "_2"     => if es.length > 1 then Pure(es(1)) else interp.located("_2 on tuple with <2 elements")
      case "_3"     => if es.length > 2 then Pure(es(2)) else interp.located("_3 on tuple with <3 elements")
      case "_4"     => if es.length > 3 then Pure(es(3)) else interp.located("_4 on tuple with <4 elements")
      case "_5"     => if es.length > 4 then Pure(es(4)) else interp.located("_5 on tuple with <5 elements")
      case "_6"     => if es.length > 5 then Pure(es(5)) else interp.located("_6 on tuple with <6 elements")
      case "_7"     => if es.length > 6 then Pure(es(6)) else interp.located("_7 on tuple with <7 elements")
      case "_8"     => if es.length > 7 then Pure(es(7)) else interp.located("_8 on tuple with <8 elements")
      case "size" | "length" => Computation.pureIntV(es.length.toLong)
      case "toList" => Pure(Value.ListV(es))
      case "swap"   => es match
        case List(a, b) => Pure(Value.TupleV(b :: a :: Nil))
        case _          => dispatchFallback(recv, name, args, env, interp)
      case "head"   => if es.nonEmpty then Pure(es.head) else interp.located("head on empty tuple")
      case "tail"   => if es.nonEmpty then Pure(Value.TupleV(es.tail)) else interp.located("tail on empty tuple")
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
          case List(msg) => Perform("Actor", "send", recv :: msg :: Nil)
          case _         => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)
        case "address" if args.isEmpty =>
          Perform("Actor", "actorRefAddress", recv :: Nil)
        case "isLocal" if args.isEmpty =>
          Perform("Actor", "actorRefIsLocal", recv :: Nil)
        case "tryLocal" if args.isEmpty =>
          Perform("Actor", "actorRefTryLocal", recv :: Nil)
        case "publishAs" | "publish" => args match
          case List(Value.StringV(n)) => Perform("Actor", "actorRefPublish", recv :: Value.StringV(n) :: Nil)
          case _                      => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)
        case _ => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)

      case "ClusterCapability" => name match
        case "resolveSeeds" if args.isEmpty =>
          fields.get("seedResolver") match
            case Some(seedResolver @ Value.InstanceV("SeedResolver", _)) =>
              Perform("Actor", "resolveSeeds", seedResolver :: Nil)
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
        case "toSeq"     => Pure(Value.ListV(fields.getOrElse("value", Value.UnitV) :: Nil))
        case "getOrElse" => Pure(fields.getOrElse("value", Value.UnitV))
        case "map"       => args match
          case List(f) =>
            val inner = fields.getOrElse("value", Value.UnitV)
            interp.callValue1(f, inner, env) match
              case Pure(v) => Pure(Value.InstanceV("Right", new IMap.Map1("value", v)))
              case c       => FlatMap(c, v => Pure(Value.InstanceV("Right", new IMap.Map1("value", v))))
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

      case _ =>
        // Fast path for ordinary user case classes: skip the 6 special-type
        // string comparisons in dispatchInstanceFallback for types that aren't
        // Source / RemoteSource / ReactiveSignal (plugin-bridged types).
        if typeName == "Source" || typeName == "RemoteSource" || typeName == "ReactiveSignal" then
          dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)
        else
          val typeMethodMap = interp.typeMethods.getOrElse(typeName, null)
          if typeMethodMap != null then
            val fn = typeMethodMap.getOrElse(name, null)
            if fn != null then
              args match
                case List(a) => interp.callTypeMethod1(fn, fields, a)
                case _       => interp.callTypeMethod(fn, fields, args)
            else
              dispatchInstanceAfterMethods(recv, fields, name, args, env, interp)
          else if name == "getMessage" && args.isEmpty then
            Pure(fields.getOrElse("message", Value.EmptyStr))
          else
            dispatchInstanceAfterMethods(recv, fields, name, args, env, interp)

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
    else
      // Class methods (declared inside `class`/`case class` body).
      // Use two null-check lookups instead of get().exists() + (typeName)(name) to
      // avoid allocating an Option and eliminate the double-lookup hot path.
      val typeMethodMap = interp.typeMethods.getOrElse(typeName, null)
      if typeMethodMap != null then
        val fn = typeMethodMap.getOrElse(name, null)
        if fn != null then
          args match
            case List(a) => interp.callTypeMethod1(fn, fields, a)
            case _       => interp.callTypeMethod(fn, fields, args)
        else
          dispatchInstanceAfterMethods(recv, fields, name, args, env, interp)
      else
        dispatchInstanceAfterMethods(recv, fields, name, args, env, interp)

  private def dispatchInstanceAfterMethods(recv: Value, fields: Map[String, Value], name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    // No-arg / native field access (must precede enum-companion check so plain
    // field values like IntV(3) are returned directly instead of being "called")
    if args.isEmpty then
      val fieldV = fields.getOrElse(name, null)
      fieldV match
        case null =>
          if name == "toString" then Pure(Value.StringV(Value.show(recv)))
          else
            val ext = extensionDispatch(recv, name, Nil, env, interp)
            if ext != null then ext else interp.located(s"No field '$name'")
        case f: Value.FunV      if f.params.isEmpty => interp.callFun(f, Nil)
        case f: Value.NativeFnV                     => f.f(Nil)
        case v                                      => Pure(v)
    // Enum companion call (Color.RGB(1,2,3)) — only when args are present
    else
      val fv = fields.getOrElse(name, null)
      if fv != null then interp.callValue(fv, args, env)
      else
        val ext = extensionDispatch(recv, name, args, env, interp)
        if ext != null then ext else interp.located(s"No method '$name' on ${recv.getClass.getSimpleName}(${Value.show(recv)})")

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
            case _                => Pure(Value.TupleV(recv :: w :: Nil))
        case _ =>
          val ext = extensionDispatch(recv, name, args, env, interp)
          if ext != null then ext else interp.located(s"No method '$name' on ${recv.getClass.getSimpleName}(${Value.show(recv)})")
    else
      val ext = extensionDispatch(recv, name, args, env, interp)
      if ext != null then ext else interp.located(s"No method '$name' on ${recv.getClass.getSimpleName}(${Value.show(recv)})")

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
        case (Value.IntV(a),    Value.DoubleV(b)) => Computation.pureBool(a.toDouble < b)
        case (Value.DoubleV(a), Value.IntV(b))    => Computation.pureBool(a < b.toDouble)
        case _                                    => dispatch(lhs, op, args, env, interp)
      case ">" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureBool(a > b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Computation.pureBool(a > b)
        case (Value.IntV(a),    Value.DoubleV(b)) => Computation.pureBool(a.toDouble > b)
        case (Value.DoubleV(a), Value.IntV(b))    => Computation.pureBool(a > b.toDouble)
        case _                                    => dispatch(lhs, op, args, env, interp)
      case "<=" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureBool(a <= b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Computation.pureBool(a <= b)
        case (Value.IntV(a),    Value.DoubleV(b)) => Computation.pureBool(a.toDouble <= b)
        case (Value.DoubleV(a), Value.IntV(b))    => Computation.pureBool(a <= b.toDouble)
        case _                                    => dispatch(lhs, op, args, env, interp)
      case ">=" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureBool(a >= b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Computation.pureBool(a >= b)
        case (Value.IntV(a),    Value.DoubleV(b)) => Computation.pureBool(a.toDouble >= b)
        case (Value.DoubleV(a), Value.IntV(b))    => Computation.pureBool(a >= b.toDouble)
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
        case (Value.TupleV(as), Value.TupleV(bs)) => Pure(Value.TupleV(as ++ bs))
        case (Value.TupleV(_),  Value.UnitV)      => Pure(lhs)
        case (Value.TupleV(as), _)                => Pure(Value.TupleV(as :+ rhs))
        case (Value.UnitV,      Value.TupleV(_))  => Pure(rhs)
        case (Value.UnitV,      Value.UnitV)      => Computation.PureUnit
        case (Value.UnitV,      _)                => Pure(rhs)
        case (_,                Value.TupleV(bs)) => Pure(Value.TupleV(lhs :: bs))
        case (_,                Value.UnitV)      => Pure(lhs)
        case _                                    => Pure(Value.TupleV(lhs :: rhs :: Nil))
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
      case "->" => Pure(Value.TupleV(lhs :: rhs :: Nil))
      case "!" => lhs match
        case pid @ Value.InstanceV("Pid", _) => Perform("Actor", "send", pid :: rhs :: Nil)
        case _                               => dispatch(lhs, op, rhs :: Nil, env, interp)
      case ":=" => lhs match
        case Value.InstanceV("AttrKey", fields) =>
          val name = fields.get("name").map(Value.show).getOrElse("")
          Pure(Value.InstanceV("Attr", new IMap.Map2(
            "name",  Value.StringV(name),
            "value", Value.StringV(Value.show(rhs))
          )))
        case _ => dispatch(lhs, op, rhs :: Nil, env, interp)
      case _ => dispatch(lhs, op, rhs :: Nil, env, interp)

  private def extensionDispatch(recv: Value, method: String, args: List[Value], env: Env, interp: Interpreter): Computation | Null =
    val typeName = recv match
      case _: Value.IntV        => "Int"
      case _: Value.DoubleV     => "Double"
      case _: Value.StringV     => "String"
      case _: Value.BoolV       => "Boolean"
      case _: Value.ListV       => "List"
      case _: Value.OptionV     => "Option"
      case _: Value.MapV        => "Map"
      case Value.TupleV(elems)  => DispatchRuntime.tupleTypeName(elems.length)
      case Value.InstanceV(t,_) => t
      case _                    => "Any"
    val typeExts = interp.extensions.getOrElse(typeName, null)
    val direct = if typeExts != null then typeExts.getOrElse(method, null) else null
    if direct != null then return interp.callValuePrepend(direct, recv, args, env)
    // Walk parent-type chain
    var parent = interp.parentTypes.getOrElse(typeName, null)
    while parent != null do
      val parentExts = interp.extensions.getOrElse(parent, null)
      val fn = if parentExts != null then parentExts.getOrElse(method, null) else null
      if fn != null then return interp.callValuePrepend(fn, recv, args, env)
      parent = interp.parentTypes.getOrElse(parent, null)
    // Last-resort: scan globals for a typeclass instance ending in [typeName] that has the method
    val suffix = s"[$typeName]"
    val iter = interp.globals.iterator
    while iter.hasNext do
      iter.next() match
        case (_, Value.InstanceV(n, fields)) if n.endsWith(suffix) && fields.contains(method) =>
          return interp.callValuePrepend(fields(method), recv, args, env)
        case _ =>
    null
