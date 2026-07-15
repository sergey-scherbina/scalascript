package scalascript.interpreter

import Computation.{Pure, Perform, FlatMap}
import scala.collection.immutable.{Map => IMap}

/** Built-in method dispatch: String, Char, Int, Double, Boolean, List, Option, Map,
 *  Tuple, Either, and instance field access.  User-defined extensions are checked
 *  first (via `interp.extensions`) only when the receiver does not already have a
 *  real built-in member with that name; Scala member methods take precedence over
 *  extension methods.
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

  private def extensionReceiverTypeName(recv: Value): String | Null =
    recv match
      case _: Value.OptionV => "Option"
      case _: Value.ListV   => "List"
      case _: Value.IntV    => "Int"
      case _: Value.DoubleV => "Double"
      case _: Value.StringV => "String"
      case _: Value.BoolV   => "Boolean"
      case _: Value.MapV    => "Map"
      case _                => null

  private def isOptionValue(value: Value): Boolean =
    value match
      case _: Value.OptionV => true
      case _                => false

  private def hasBuiltinMemberBeforeExtension(recv: Value, name: String): Boolean =
    recv match
      case _: Value.OptionV =>
        name match
          case "map" | "flatMap" | "filter" | "foreach" | "exists" | "forall" |
              "fold" | "getOrElse" | "orElse" | "contains" | "zip" | "toList" |
              "isDefined" | "isEmpty" | "nonEmpty" | "get" | "getOrNull" |
              "getOrElseThrow" | "orNull" | "toRight" | "toLeft" =>
            true
          case _ => false
      case _: Value.ListV =>
        name match
          case "map" | "flatMap" | "foldLeft" | "foldRight" | "foreach" |
              "filter" | "filterNot" | "exists" | "forall" | "find" |
              "contains" | "indexOf" | "appended" | "prepended" | "take" |
              "drop" | "apply" | "grouped" | "sliding" | "scanLeft" |
              "reduceLeft" | "reduce" | "reduceRight" | "reduceOption" |
              "reduceLeftOption" | "transpose" | "patch" | "zipAll" | "scanRight" |
              "distinctBy" | "partition" | "count" | "collect" | "span" |
              "sortBy" | "sortWith" | "groupBy" | "mkString" | "zip" |
              "takeRight" | "dropRight" | "splitAt" | "intersect" | "diff" |
              "takeWhile" | "dropWhile" | "toList" | "toSeq" | "toIterable" |
              "head" | "tail" | "headOption" | "last" | "lastOption" |
              "isEmpty" | "nonEmpty" | "size" | "length" | "sum" | "product" |
              "min" | "max" | "reverse" | "distinct" | "sorted" | "flatten" |
              "zipWithIndex" | "++" | "::" | "+:" | ":+" =>
            true
          case _ => false
      case _ => false

  private def lookupDirectExtension(recv: Value, name: String, interp: Interpreter): Value.FunV | Null =
    if interp.extensions.isEmpty || hasBuiltinMemberBeforeExtension(recv, name) then null
    else
      val typeName = extensionReceiverTypeName(recv)
      if typeName == null then null
      else
        val typeExts = interp.extensions.getOrElse(typeName, null)
        if typeExts != null then typeExts.getOrElse(name, null) else null

  def dispatch(recv: Value, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    // `.asInstanceOf[T]` — types are erased; always a no-op at runtime.
    // Must be first: type-specific dispatchers (dispatchMap, dispatchList, etc.)
    // throw on unknown methods before reaching dispatchFallback.
    if name == "asInstanceOf" then return Pure(recv)
    // `.toString` is universal in Scala — every value has it. Intercept here (same
    // reason as asInstanceOf above): a type-specific dispatcher would otherwise mis-handle
    // it first — e.g. `map.toString` is read as a key lookup → "No key 'toString'". Render
    // via `Value.show`, the same path as println / string interpolation, so the result is
    // consistent. A case-class instance with a user-defined `toString` method keeps it.
    if name == "toString" && args.isEmpty then
      return recv match
        case inst: Value.InstanceV =>
          val tm = lookupTypeMethod(inst.typeName, "toString", interp)
          if tm != null then invokeTypeMethod(tm, recv, inst.effectiveFields, args, interp)
          else Pure(Value.StringV(Value.show(recv)))
        case _ => Pure(Value.StringV(Value.show(recv)))
    // Extensions early-exit: avoid 7 HashMap lookups when no extensions registered.
    val directExt = lookupDirectExtension(recv, name, interp)
    if directExt != null then return interp.callValuePrepend(directExt, recv, args, env)
    recv match
      case Value.StringV(s)        => dispatchString(recv, s, name, args, env, interp)
      case Value.ListV(ls)         => dispatchList(ls, name, args, env, interp)
      case v: Value.VectorV        => dispatchVector(v, name, args, env, interp)
      case a: Value.ArrayV         => dispatchArray(a, name, args, env, interp)
      case ll: Value.LazyListV     => dispatchLazyList(ll, name, args, env, interp)
      case Value.MapV(m)           => dispatchMap(m, name, args, env, interp)
      case Value.SetV(s)           => dispatchSet(s, name, args, env, interp)
      case Value.OptionV(opt)      => dispatchOption(recv, if opt == null then None else Some(opt), name, args, env, interp)
      case Value.IntV(n)           => dispatchInt(n, name, args, env, interp)
      case Value.DoubleV(d)        => dispatchDouble(d, name, args, env, interp)
      case Value.BigIntV(b)        => dispatchBigInt(b, name, args, env, interp)
      case Value.DecimalV(d)       => dispatchDecimal(d, name, args, env, interp)
      case Value.CharV(c)          => dispatchChar(c, name, args, env, interp)
      case Value.BoolV(b)          => dispatchBool(recv, b, name, args, env, interp)
      case Value.TupleV(es)        => dispatchTuple(es, name, args, env, interp)
      case Value.UnitV             => dispatchUnit(recv, name, args, env, interp)
      // Type-test, NOT `case InstanceV(t, f)`: the custom `InstanceV.unapply` allocates a
      // `Some` + `Tuple2` on every instance dispatch (the dominant alloc on typeclass/HOF
      // workloads — JFR ~600 MB on typeclassFoldMacro). Read the fields via the type-tested ref.
      case inst: Value.InstanceV   => dispatchInstance(recv, inst.typeName, inst.effectiveFields, name, args, env, interp)
      case Value.Foreign(t, _)     => dispatchForeign(recv, t, name, args, env, interp)
      // A parameterless native global (args/cwd/sep/platform/homedir/nowMillis/…) used
      // in receiver position — `args.length`, `platform.toUpperCase`, `args(0)` — is a
      // function value with no methods of its own. Auto-call it to its value (like the
      // bare-value auto-call) and re-dispatch on the result. Gated to registered plugin
      // natives so method-ref NativeFnVs (foldLeft/apply/…) are untouched.
      // (v1-args-native-method-gap.)
      case Value.NativeFnV(nm, fn) if interp.pluginNativeNames.contains(nm) =>
        FlatMap(fn(Nil), v => dispatch(v, name, args, env, interp))
      case other                   => dispatchFallback(other, name, args, env, interp)

  /** Single-arg fast path: avoids allocating `arg :: Nil` per method call.
   *  Covers the most common 1-arg operations on List/Map/Option/String/Int/instances.
   *  Falls through to dispatch(recv, name, arg :: Nil, ...) for uncommon operations. */
  def dispatch1(recv: Value, name: String, arg: Value, env: Env, interp: Interpreter): Computation =
    val directExt = lookupDirectExtension(recv, name, interp)
    if directExt != null then return interp.callValue2(directExt, recv, arg, env)
    recv match
      case Value.ListV(ls)      => dispatchList1(ls, recv, name, arg, env, interp)
      case Value.MapV(m)        => dispatchMap1(m, name, arg, env, interp)
      case Value.OptionV(opt)   => dispatchOption1(recv, if opt == null then None else Some(opt), name, arg, env, interp)
      case Value.StringV(s)     => dispatchString1(recv, s, name, arg, env, interp)
      case Value.IntV(n)        => dispatchInt1(n, name, arg, env, interp)
      case Value.DoubleV(d)     => dispatchDouble1(d, name, arg, env, interp)
      case inst: Value.InstanceV => dispatchInstance1(recv, inst.typeName, inst.effectiveFields, name, arg, env, interp)
      case Value.Foreign(t, _)   => dispatchForeign(recv, t, name, arg :: Nil, env, interp)
      case _                    => dispatch(recv, name, arg :: Nil, env, interp)

  /** Two-arg fast path: avoids allocating `arg1 :: arg2 :: Nil` per method call.
   *  Covers the most common 2-arg operations on Map/String/Int.
   *  Falls through to dispatch(recv, name, arg1 :: arg2 :: Nil, ...) for uncommon ops. */
  def dispatch2(recv: Value, name: String, arg1: Value, arg2: Value, env: Env, interp: Interpreter): Computation =
    val directExt = lookupDirectExtension(recv, name, interp)
    if directExt != null then return interp.callValuePrepend(directExt, recv, arg1 :: arg2 :: Nil, env)
    recv match
      case Value.MapV(m) => name match
        case "getOrElse" | "getOrDefault" =>
          val v = m.getOrElse(arg1, null)
          Pure(if v == null || (v eq Value.NullV) then arg2 else v)
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
        case "indexOf"    => (arg1, arg2) match
          case (Value.StringV(t), Value.IntV(from)) => Computation.pureIntV(s.indexOf(t, from.toInt).toLong)
          case (Value.CharV(c),   Value.IntV(from)) => Computation.pureIntV(s.indexOf(c.toInt, from.toInt).toLong)
          case (Value.IntV(c),    Value.IntV(from)) => Computation.pureIntV(s.indexOf(c.toInt, from.toInt).toLong)
          case _ => dispatchString(recv, s, name, arg1 :: arg2 :: Nil, env, interp)
        case "lastIndexOf" => (arg1, arg2) match
          case (Value.StringV(t), Value.IntV(from)) => Computation.pureIntV(s.lastIndexOf(t, from.toInt).toLong)
          case (Value.CharV(c),   Value.IntV(from)) => Computation.pureIntV(s.lastIndexOf(c.toInt, from.toInt).toLong)
          case (Value.IntV(c),    Value.IntV(from)) => Computation.pureIntV(s.lastIndexOf(c.toInt, from.toInt).toLong)
          case _ => dispatchString(recv, s, name, arg1 :: arg2 :: Nil, env, interp)
        case "split"      => (arg1, arg2) match
          case (Value.StringV(sep), Value.IntV(limit)) =>
            val parts = s.split(sep, limit.toInt)
            var list: List[Value] = Nil; var i = parts.length - 1
            while i >= 0 do { list = Value.StringV(parts(i)) :: list; i -= 1 }
            Pure(Value.ListV(list))
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

  /** 1-arg fast path for List — avoids `arg :: Nil` allocation for the most common ops. */
  private def dispatchList1(ls: List[Value], recv: Value, name: String, arg: Value, env: Env, interp: Interpreter): Computation =
    name match
      // `(0 to 10) by 2` — interp materializes the Range as a List, so `by(step)` re-steps it by
      // keeping every step-th element. (xbackend-range-by-step.)
      case "by" => arg match
        case Value.IntV(step) if step > 0 =>
          val buf = new scala.collection.mutable.ArrayBuffer[Value]((ls.length / step.toInt) + 1)
          var i = 0; var rem = ls
          while rem.nonEmpty do
            if i % step == 0 then buf += rem.head
            i += 1; rem = rem.tail
          Pure(Value.ListV(buf.toList))
        case _ => dispatchFallback(recv, name, arg :: Nil, env, interp)
      case "map"        => arg match
        case f: Value.FunV => CallRuntime.mapReusing(ls, f, env, interp)
        case _             => Computation.mapSequence(ls, item => interp.callValue1(arg, item, env))
      case "filter"     => Computation.filterSequence(ls, item => interp.callValue1(arg, item, env))
      case "filterNot"  => Computation.filterNotSequence(ls, item => interp.callValue1(arg, item, env))
      case "foreach"    => arg match
        case f: Value.FunV => CallRuntime.foreachReusing(ls, f, env, interp)
        case _             => Computation.foreachSequence(ls, item => interp.callValue1(arg, item, env))
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
            case Pure(Value.BoolV(true)) => return Pure(Value.OptionV(h))
            case Pure(_)                 => rem = rem.tail
            case c =>
              val tail = rem.tail
              def restLoop(remaining: List[Value]): Computation = remaining match
                case Nil => Computation.PureNone
                case hh :: rest =>
                  FlatMap(interp.callValue1(arg, hh, env), {
                    case Value.BoolV(true) => Pure(Value.OptionV(hh))
                    case _                 => restLoop(rest)
                  })
              return FlatMap(c, {
                case Value.BoolV(true) => Pure(Value.OptionV(h))
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
          case List(f: Value.FunV) => CallRuntime.foldLeftReusing(ls, arg, f, env, interp)
          case List(f)             => Computation.foldLeftSequence(ls, arg, (acc, h) => interp.callValue2(f, acc, h, env))
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
      case "count"      =>
        var rem = ls
        var acc = 0L
        while rem.nonEmpty do
          interp.callValue1(arg, rem.head, env) match
            case Pure(Value.BoolV(true)) => acc += 1L; rem = rem.tail
            case Pure(_)                 => rem = rem.tail
            case c =>
              val acc0 = acc
              val tail = rem.tail
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
          collectStep(arg, rem.head, env, interp) match
            case null                    => rem = rem.tail   // PF not defined here → skip
            case Pure(ov: Value.OptionV) => if ov.inner != null then buf += ov.inner; rem = rem.tail
            case Pure(v)                 => buf += v; rem = rem.tail
            case comp =>
              val tail = rem.tail
              def loopRest(remaining: List[Value]): Computation = remaining match
                case Nil => Pure(Value.ListV(buf.toList))
                case h :: rest =>
                  collectStep(arg, h, env, interp) match
                    case null                    => loopRest(rest)
                    case Pure(ov: Value.OptionV) => if ov.inner != null then buf += ov.inner; loopRest(rest)
                    case Pure(v)                 => buf += v; loopRest(rest)
                    case c => FlatMap(c, {
                      case ov: Value.OptionV => if ov.inner != null then buf += ov.inner; loopRest(rest)
                      case v                 => buf += v; loopRest(rest)
                    })
              return FlatMap(comp, {
                case ov: Value.OptionV => if ov.inner != null then buf += ov.inner; loopRest(tail)
                case v                 => buf += v; loopRest(tail)
              })
        Pure(Value.ListV(buf.toList))
      case "span"       =>
        val yesBuf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
        var rem = ls
        while rem.nonEmpty do
          val h = rem.head
          interp.callValue1(arg, h, env) match
            case Pure(Value.BoolV(true)) => yesBuf += h; rem = rem.tail
            case Pure(_)                 => return Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(rem) :: Nil))
            case comp =>
              val remaining = rem
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
      case "sortBy"     =>
        dispatchList(ls, "sortBy", arg :: Nil, env, interp)
      case "sortWith"   =>
        if ls.isEmpty || ls.tail.isEmpty then Pure(recv)
        else
          val arr = ls.toArray
          // `Value` is a union type (DataValue | ValueRest); java.util.Arrays.sort can't infer the
          // generic element bound for a union array, so sort over Array[AnyRef] and cast back.
          java.util.Arrays.sort(arr.asInstanceOf[Array[AnyRef]], (a: AnyRef, b: AnyRef) =>
            Computation.run(interp.callValue2(arg, a.asInstanceOf[Value], b.asInstanceOf[Value], env)) match
              case Value.BoolV(true) => -1
              case _                 => 1
          )
          var sorted: List[Value] = Nil
          var i = arr.length - 1
          while i >= 0 do
            sorted = arr(i) :: sorted
            i -= 1
          Pure(Value.ListV(sorted))
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
          var as = ls
          var bs = other
          while as.nonEmpty && bs.nonEmpty do
            buf += Value.TupleV(as.head :: bs.head :: Nil)
            as = as.tail
            bs = bs.tail
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
                    case Nil => Pure(Value.ListV(buf.toList))
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
                case Nil => Computation.PureEmptyList
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
      case _            => dispatchList(ls, name, arg :: Nil, env, interp)

  /** 1-arg fast path for Map. */
  private def dispatchMap1(m: Map[Value, Value], name: String, arg: Value, env: Env, interp: Interpreter): Computation =
    name match
      case "get"        =>
        val gv = m.getOrElse(arg, null)
        if gv == null then Computation.PureNone else Pure(Value.OptionV(gv))
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
        // FastTier 2-arg foreach short-circuit for the
        // `m.foreach((k, v) => acc = acc + paramRef)` shape — single-arg
        // dispatch site (the common case in `var total = 0; m.foreach(...)`).
        if arg.isInstanceOf[Value.FunV] then
          val fv = arg.asInstanceOf[Value.FunV]
          val ftD = FastTier.tryDoubleAccumForeachMap(m, fv, interp)
          if ftD != null then return ftD
          val ftL = FastTier.tryLongAccumForeachMap(m, fv, interp)
          if ftL != null then return ftL
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
      case "foldLeft"   =>
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
      case "exists"     =>
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
      case "forall"     =>
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
      case "count"      =>
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
      case "find"       =>
        val it = m.iterator
        while it.hasNext do
          val (k, v) = it.next()
          interp.callEntry(arg, k, v, env) match
            case Pure(Value.BoolV(true))  => return Pure(Value.OptionV(Value.TupleV(k :: v :: Nil)))
            case Pure(_)                  =>
            case comp =>
              return FlatMap(comp, {
                case Value.BoolV(true) => Pure(Value.OptionV(Value.TupleV(k :: v :: Nil)))
                case _ =>
                  def loopRest(): Computation =
                    if !it.hasNext then Computation.PureNone
                    else
                      val (k2, v2) = it.next()
                      FlatMap(interp.callEntry(arg, k2, v2, env), {
                        case Value.BoolV(true) => Pure(Value.OptionV(Value.TupleV(k2 :: v2 :: Nil)))
                        case _                 => loopRest()
                      })
                  loopRest()
              })
        Computation.PureNone
      case "getOrDefault" | "getOrElse" =>
        dispatchMap(m, name, arg :: Nil, env, interp)
      case _            => dispatchMap(m, name, arg :: Nil, env, interp)

  /** 1-arg fast path for Option. */
  private def dispatchOption1(recv: Value, opt: Option[Value], name: String, arg: Value, env: Env, interp: Interpreter): Computation =
    name match
      case "getOrElse" => opt match
        case Some(v) => Pure(v)
        case None    => Pure(arg)
      case "orElse" if isOptionValue(arg) => opt match
        case Some(_) => Pure(recv)
        case None    => Pure(arg)
      case "orElse" => dispatchFallback(recv, name, arg :: Nil, env, interp)
      case "contains"  => Computation.pureBool(opt.contains(arg))
      case "map"       => opt match
        case None    => Computation.PureNone
        case Some(v) => interp.callValue1(arg, v, env) match
          case Pure(rv) => Pure(Value.someV(rv))
          case c        => FlatMap(c, Computation.wrapSomeC)
      case "flatMap"   => opt match
        case None    => Computation.PureNone
        case Some(v) => interp.callValue1(arg, v, env) match
          case Pure(o: Value.OptionV) => Pure(o)
          case Pure(other)            => Pure(Value.someV(other))
          case c                      => FlatMap(c, Computation.wrapOptionC)
      case "filter"    => opt match
        case None    => Computation.PureNone
        case Some(v) => interp.callValue1(arg, v, env) match
          case Pure(Value.BoolV(true)) => Pure(recv)
          case Pure(_)                 => Computation.PureNone
          case c                       =>
            FlatMap(c, {
              case Value.BoolV(true) => Pure(recv)
              case _                 => Computation.PureNone
            })
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
          case ov: Value.OptionV =>
            if ov.inner != null then Pure(Value.OptionV(Value.TupleV(v :: ov.inner :: Nil)))
            else Computation.PureNone
          case _ => Computation.PureNone
      case "fold"      =>
        Pure(Value.NativeFnV("fold", {
          case List(f) => opt match
            case None    => Pure(arg)
            case Some(v) => interp.callValue1(f, v, env)
          case _ => throw InterpretError("Option.fold expects one function argument")
        }))
      case "toRight"   => opt match
        case None    => Pure(Value.singleValue("Left", arg))
        case Some(v) => Pure(Value.singleValue("Right", v))
      case "toLeft"    => opt match
        case None    => Pure(Value.singleValue("Right", arg))
        case Some(v) => Pure(Value.singleValue("Left", v))
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
          val parts = s.split(sep, -1)
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
        case Value.IntV(n)    => Computation.pureIntV(s.indexOf(n.toInt).toLong)
        case _                => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "lastIndexOf" => arg match
        case Value.StringV(t) => Computation.pureIntV(s.lastIndexOf(t).toLong)
        case Value.CharV(c)   => Computation.pureIntV(s.lastIndexOf(c.toInt).toLong)
        case Value.IntV(n)    => Computation.pureIntV(s.lastIndexOf(n.toInt).toLong)
        case _                => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "codePointAt" => arg match
        case Value.IntV(i) =>
          if i < 0 || i >= s.length then interp.located(s"index $i out of bounds for string of length ${s.length}")
          else Computation.pureIntV(s.codePointAt(i.toInt).toLong)
        case _             => dispatchString(recv, s, name, arg :: Nil, env, interp)
      case "map"       =>
        // `String.map(f)` is a `String` only when `f` yields a `Char`; otherwise a `Seq[B]`
        // (e.g. `"abc".map(_.toInt)` → `List(97, 98, 99)`). (interp-js-string-map-nonchar.)
        Computation.mapSequenceStr(s, c => interp.callValue1(arg, Value.charV(c), env)) match
          case Pure(Value.ListV(items)) => Pure(strMapResult(items))
          case Pure(other)              => Pure(other)
          case comp                     => FlatMap(comp, {
            case Value.ListV(items) => Pure(strMapResult(items))
            case other              => Pure(other)
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
  private def dispatchInstance1(recv: Value, typeName: String, fields0: Map[String, Value], name: String, arg: Value, env: Env, interp: Interpreter): Computation =
    val fields: Map[String, Value] = recv match
      case inst: Value.InstanceV if inst.fieldsArr != null && fields0.isEmpty =>
        instanceFieldsMap(inst)
      case _ => fields0
    typeName match
      case "Right" => name match
        case "map"     =>
          val inner = fields.getOrElse("value", Value.UnitV)
          interp.callValue1(arg, inner, env) match
            case Pure(v) => Pure(Value.singleValue("Right", v))
            case c       => FlatMap(c, v => Pure(Value.singleValue("Right", v)))
        case "flatMap" => interp.callValue1(arg, fields.getOrElse("value", Value.UnitV), env)
        case _         => dispatchInstance(recv, typeName, fields, name, arg :: Nil, env, interp)
      case "Left" => name match
        case "getOrElse" => Pure(arg)
        case "map" | "flatMap" => Pure(recv)
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
      case "capitalize"  => Pure(Value.StringV(if s.isEmpty then s else s.charAt(0).toUpper.toString + s.substring(1)))
      case "reverse"     => Pure(Value.StringV(s.reverse))
      case "toInt"       => Computation.pureIntV(s.toLong)
      case "toLong"      => Computation.pureIntV(s.toLong)
      case "toDouble"    => Pure(Value.doubleV(s.toDouble))
      case "toFloat"     => Pure(Value.doubleV(s.toDouble))
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
          if m.lookingAt() then Pure(Value.OptionV(Value.StringV(s.substring(0, m.end()))))
          else Computation.PureNone
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "split"       => args match
        case List(Value.StringV(sep)) =>
          val parts = s.split(sep, -1)
          var list: List[Value] = Nil; var i = parts.length - 1
          while i >= 0 do { list = Value.StringV(parts(i)) :: list; i -= 1 }
          Pure(Value.ListV(list))
        case List(Value.StringV(sep), Value.IntV(limit)) =>
          val parts = s.split(sep, limit.toInt)
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
      // ── Lexicographic ordering ─────────────────────────────────────────────
      // Needed for sort keys (UUID v7 time-ordering, dictionary keys etc.).
      // Java's `String.compareTo` is the canonical Unicode codepoint ordering.
      case "<"           => args match
        case List(Value.StringV(t)) => Computation.pureBool(s.compareTo(t) < 0)
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case ">"           => args match
        case List(Value.StringV(t)) => Computation.pureBool(s.compareTo(t) > 0)
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case "<="          => args match
        case List(Value.StringV(t)) => Computation.pureBool(s.compareTo(t) <= 0)
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case ">="          => args match
        case List(Value.StringV(t)) => Computation.pureBool(s.compareTo(t) >= 0)
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case "compareTo"   => args match
        case List(Value.StringV(t)) => Computation.pureIntV(s.compareTo(t).toLong)
        case _                      => dispatchFallback(recv, name, args, env, interp)
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
        case List(Value.StringV(t))                       => Computation.pureIntV(s.indexOf(t).toLong)
        case List(Value.CharV(c))                         => Computation.pureIntV(s.indexOf(c.toInt).toLong)
        case List(Value.IntV(n))                          => Computation.pureIntV(s.indexOf(n.toInt).toLong)
        case List(Value.StringV(t), Value.IntV(from))     => Computation.pureIntV(s.indexOf(t, from.toInt).toLong)
        case List(Value.CharV(c),   Value.IntV(from))     => Computation.pureIntV(s.indexOf(c.toInt, from.toInt).toLong)
        case List(Value.IntV(c),    Value.IntV(from))     => Computation.pureIntV(s.indexOf(c.toInt, from.toInt).toLong)
        case _                                            => dispatchFallback(recv, name, args, env, interp)
      case "lastIndexOf" => args match
        case List(Value.StringV(t))                       => Computation.pureIntV(s.lastIndexOf(t).toLong)
        case List(Value.CharV(c))                         => Computation.pureIntV(s.lastIndexOf(c.toInt).toLong)
        case List(Value.IntV(n))                          => Computation.pureIntV(s.lastIndexOf(n.toInt).toLong)
        case List(Value.StringV(t), Value.IntV(from))     => Computation.pureIntV(s.lastIndexOf(t, from.toInt).toLong)
        case List(Value.CharV(c),   Value.IntV(from))     => Computation.pureIntV(s.lastIndexOf(c.toInt, from.toInt).toLong)
        case List(Value.IntV(c),    Value.IntV(from))     => Computation.pureIntV(s.lastIndexOf(c.toInt, from.toInt).toLong)
        case _                                            => dispatchFallback(recv, name, args, env, interp)
      case "codePointAt" => args match
        case List(Value.IntV(i)) =>
          if i < 0 || i >= s.length then interp.located(s"index $i out of bounds for string of length ${s.length}")
          else Computation.pureIntV(s.codePointAt(i.toInt).toLong)
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "map"         => args match
        case List(f) =>
          Computation.mapSequenceStr(s, c => interp.callValue1(f, Value.charV(c), env)).map {
            case Value.ListV(items) => Value.StringV(items.iterator.map(Value.show).mkString)
            case _                  => Value.StringV(s)
          }
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
        var tlList: List[Value] = Nil; var tlI = s.length - 1
        while tlI >= 0 do { tlList = Value.charV(s.charAt(tlI)) :: tlList; tlI -= 1 }
        Pure(Value.ListV(tlList))
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
        var ziList: List[Value] = Nil; var ziI = s.length - 1
        while ziI >= 0 do
          ziList = Value.TupleV(Value.charV(s.charAt(ziI)) :: Value.intV(ziI.toLong) :: Nil) :: ziList
          ziI -= 1
        Pure(Value.ListV(ziList))
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
      // Char is numeric (Scala: `'a' - 32` is an Int). `+` with a String is
      // concatenation, matching Scala's `Char.+(String)`.
      case "+"  => args match
        case List(Value.CharV(b))   => Computation.pureIntV(c.toInt + b.toInt)
        case List(Value.IntV(n))    => Computation.pureIntV(c.toInt + n)
        case List(Value.StringV(s)) => Pure(Value.StringV(c.toString + s))
        case _                      => interp.located(s"No method '+' on Char")
      case "-"  => args match
        case List(Value.CharV(b)) => Computation.pureIntV(c.toInt - b.toInt)
        case List(Value.IntV(n))  => Computation.pureIntV(c.toInt - n)
        case _                    => interp.located(s"No method '-' on Char")
      case "*"  => args match
        case List(Value.CharV(b)) => Computation.pureIntV(c.toInt * b.toInt)
        case List(Value.IntV(n))  => Computation.pureIntV(c.toInt * n)
        case _                    => interp.located(s"No method '*' on Char")
      case "/"  => args match
        case List(Value.CharV(b)) => Computation.pureIntV(c.toInt / b.toInt)
        case List(Value.IntV(n))  => Computation.pureIntV(c.toInt / n)
        case _                    => interp.located(s"No method '/' on Char")
      case "%"  => args match
        case List(Value.CharV(b)) => Computation.pureIntV(c.toInt % b.toInt)
        case List(Value.IntV(n))  => Computation.pureIntV(c.toInt % n)
        case _                    => interp.located(s"No method '%' on Char")
      case "<"  => args match
        case List(Value.CharV(b)) => Computation.pureBool(c < b)
        case List(Value.IntV(n))  => Computation.pureBool(c.toInt < n.toInt)
        case _                    => interp.located(s"No method '<' on Char")
      case ">"  => args match
        case List(Value.CharV(b)) => Computation.pureBool(c > b)
        case List(Value.IntV(n))  => Computation.pureBool(c.toInt > n.toInt)
        case _                    => interp.located(s"No method '>' on Char")
      case "<=" => args match
        case List(Value.CharV(b)) => Computation.pureBool(c <= b)
        case List(Value.IntV(n))  => Computation.pureBool(c.toInt <= n.toInt)
        case _                    => interp.located(s"No method '<=' on Char")
      case ">=" => args match
        case List(Value.CharV(b)) => Computation.pureBool(c >= b)
        case List(Value.IntV(n))  => Computation.pureBool(c.toInt >= n.toInt)
        case _                    => interp.located(s"No method '>=' on Char")
      case "==" | "equals" => args match
        case List(Value.CharV(b)) => Computation.pureBool(c == b)
        case List(Value.IntV(n))  => Computation.pureBool(c.toInt == n.toInt)
        case _                    => Computation.pureBool(false)
      case "!=" => args match
        case List(Value.CharV(b)) => Computation.pureBool(c != b)
        case List(Value.IntV(n))  => Computation.pureBool(c.toInt != n.toInt)
        case _                    => Computation.pureBool(true)
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

  // ── Set ─────────────────────────────────────────────────────────────────────

  private def dispatchSet(s: Set[Value], name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    val recv = Value.SetV(s)
    def asSet(v: Value): Set[Value] = v match
      case Value.SetV(o)  => o
      case Value.ListV(o) => o.toSet
      case other          => Set(other)
    name match
      case "contains" | "apply" => args match
        case List(x) => Computation.pureBool(s.contains(x))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "size"     => Computation.pureIntV(s.size.toLong)
      case "isEmpty"  => Computation.pureBool(s.isEmpty)
      case "nonEmpty" => Computation.pureBool(s.nonEmpty)
      case "incl" | "+" => args match
        case List(x) => Pure(Value.SetV(s + x))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "excl" | "-" => args match
        case List(x) => Pure(Value.SetV(s - x))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "union" | "++" | "|" => args match
        case List(o) => Pure(Value.SetV(s ++ asSet(o)))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "diff" | "--" | "&~" => args match
        case List(o) => Pure(Value.SetV(s -- asSet(o)))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "intersect" | "&" => args match
        case List(o) => Pure(Value.SetV(s & asSet(o)))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "subsetOf" => args match
        case List(o) => Computation.pureBool(s.subsetOf(asSet(o)))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "toList" | "toSeq"          => Pure(Value.ListV(s.toList))
      case "toVector" | "toIndexedSeq" => Pure(Value.VectorV(s.toVector))
      case "toSet"     => Pure(recv)
      case "head"      => if s.isEmpty then interp.located("head of empty Set") else Pure(s.head)
      case "headOption" => Pure(Value.optionV(s.headOption))
      // Higher-order and rendering ops: reuse the List dispatch over `toList`,
      // re-wrapping the collection-returning ones back into a Set.
      case "map" | "filter" | "filterNot" =>
        dispatchList(s.toList, name, args, env, interp).map {
          case Value.ListV(xs) => Value.SetV(xs.toSet)
          case other           => other
        }
      case "foreach" =>
        // Direct Set-aware FastTier path: skips the `s.toList` allocation
        // that the generic `dispatchList(s.toList, …)` detour pays. For the
        // `patternMatchSet` shape — `var total: Double; set.foreach(s =>
        // total = total + area(s))` — FastTier iterates the Set via
        // `set.iterator` instead, saving O(N) `::`-cons cells per call.
        args match
          case List(f: Value.FunV) =>
            val ftD = FastTier.tryDoubleAccumForeachSet(s, f, interp)
            if ftD != null then return ftD
            val ftL = FastTier.tryLongAccumForeachSet(s, f, interp)
            if ftL != null then return ftL
            dispatchList(s.toList, name, args, env, interp)
          case _ => dispatchList(s.toList, name, args, env, interp)
      case "exists" | "forall" | "foldLeft" | "find" | "count" |
           "mkString" | "isEmpty" | "max" | "min" | "sum" =>
        dispatchList(s.toList, name, args, env, interp)
      case _ => dispatchFallback(recv, name, args, env, interp)

  // ── List ────────────────────────────────────────────────────────────────────

  /** Elements of a sequence-typed argument (List/Vector), or null if not a seq.
   *  Lets methods that take a collection arg accept both `List(...)` and `Vector(...)`. */
  private def seqElems(v: Value): List[Value] | Null = v match
    case Value.ListV(xs)   => xs
    case Value.VectorV(xs) => xs.toList
    case _                 => null

  private def dispatchList(ls: List[Value], name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    lazy val recv = Value.ListV(ls)
    name match
      case "length"       => Computation.pureIntV(ls.length.toLong)
      case "size"         => Computation.pureIntV(ls.size.toLong)
      case "isEmpty"      => Computation.pureBool(ls.isEmpty)
      case "nonEmpty"     => Computation.pureBool(ls.nonEmpty)
      case "head"         => if ls.isEmpty then interp.located("head on Nil") else Pure(ls.head)
      case "headOption"   => Pure(Value.optionV(ls.headOption))
      // `(0 to 10) by 2` — interp materializes the Range as a List; `by(step)` keeps every step-th
      // element. (xbackend-range-by-step.)
      case "by"           => args match
        case List(Value.IntV(step)) if step > 0 =>
          val buf = new scala.collection.mutable.ArrayBuffer[Value]((ls.length / step.toInt) + 1)
          var i = 0; var rem = ls
          while rem.nonEmpty do { if i % step == 0 then buf += rem.head; i += 1; rem = rem.tail }
          Pure(Value.ListV(buf.toList))
        case _ => dispatchFallback(recv, name, args, env, interp)
      case "indexWhere"   => args match
        case List(f) =>
          var i = 0; var rem = ls; var found = -1L
          while found < 0 && rem.nonEmpty do
            (Computation.run(interp.callValue1(f, rem.head, env)) match
              case Value.BoolV(true) => found = i.toLong
              case _                 => ())
            i += 1; rem = rem.tail
          Computation.pureIntV(found)
        case _ => dispatchFallback(recv, name, args, env, interp)
      case "lastOption"   => Pure(Value.optionV(ls.lastOption))
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
            while rem2.nonEmpty do
              arr(i) = rem2.head.asInstanceOf[Value.IntV].v
              i += 1
              rem2 = rem2.tail
            java.util.Arrays.sort(arr)
            var result2: List[Value] = Nil; i = arr.length - 1
            while i >= 0 do
              result2 = Value.intV(arr(i)) :: result2
              i -= 1
            Pure(Value.ListV(result2))
          else
            // Schwartzian: one array of (Value, key) — no intermediate List passes.
            val arr = new Array[(Value, String)](ls.length)
            var i = 0; var rem = ls
            while rem.nonEmpty do
              val v = rem.head; arr(i) = (v, Value.show(v)); i += 1; rem = rem.tail
            java.util.Arrays.sort(arr, java.util.Comparator.comparing[(Value, String), String](_._2))
            var result: List[Value] = Nil; i = arr.length - 1
            while i >= 0 do
              result = arr(i)._1 :: result
              i -= 1
            Pure(Value.ListV(result))
      case "toList" | "toSeq" | "toIterable" => Pure(recv)
      case "toVector" | "toIndexedSeq" => Pure(Value.VectorV(ls.toVector))   // O(1)-indexed real Vector
      case "toArray"      => Pure(Value.ArrayV(ls.toArray))                  // real mutable Array
      case "toSet"        => Pure(Value.SetV(ls.toSet))
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
          else
            Computation.mapSequence(ls, item => interp.callValue1(f, item, env)).map {
              case Value.ListV(keys) =>
                var bestVal  = ls.head
                var bestKey  = Value.show(keys.head)
                var lRem = ls.tail; var kRem = keys.tail
                while lRem.nonEmpty do
                  val k = Value.show(kRem.head)
                  if k > bestKey then { bestVal = lRem.head; bestKey = k }
                  lRem = lRem.tail; kRem = kRem.tail
                bestVal
              case _ => ls.head
            }
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "minBy"        => args match
        case List(f) =>
          if ls.isEmpty then interp.located("minBy on empty list")
          else
            Computation.mapSequence(ls, item => interp.callValue1(f, item, env)).map {
              case Value.ListV(keys) =>
                var bestVal  = ls.head
                var bestKey  = Value.show(keys.head)
                var lRem = ls.tail; var kRem = keys.tail
                while lRem.nonEmpty do
                  val k = Value.show(kRem.head)
                  if k < bestKey then { bestVal = lRem.head; bestKey = k }
                  lRem = lRem.tail; kRem = kRem.tail
                bestVal
              case _ => ls.head
            }
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
        val ziArr = ls.toArray
        var ziList: List[Value] = Nil; var ziI = ziArr.length - 1
        while ziI >= 0 do
          ziList = Value.TupleV(ziArr(ziI) :: Value.intV(ziI.toLong) :: Nil) :: ziList
          ziI -= 1
        Pure(Value.ListV(ziList))
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
        case List(Value.IntV(n))                   => Pure(Value.ListV(ls.sliding(n.toInt).map(Value.ListV(_)).toList))
        case List(Value.IntV(n), Value.IntV(step)) => Pure(Value.ListV(ls.sliding(n.toInt, step.toInt).map(Value.ListV(_)).toList))
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "patch"        => args match
        case List(Value.IntV(from), otherArg, Value.IntV(replaced)) if seqElems(otherArg) != null =>
          Pure(Value.ListV(ls.patch(from.toInt, seqElems(otherArg).nn, replaced.toInt)))
        case _                        => dispatchFallback(recv, name, args, env, interp)
      case "zipAll"       => args match
        case List(otherArg, thisElem, thatElem) if seqElems(otherArg) != null =>
          Pure(Value.ListV(ls.zipAll(seqElems(otherArg).nn, thisElem, thatElem).map((a, b) => Value.TupleV(a :: b :: Nil))))
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
        case List(f: Value.FunV) => CallRuntime.mapReusing(ls, f, env, interp)
        case List(f)             => Computation.mapSequence(ls, item => interp.callValue1(f, item, env))
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
        // FunV → route through CallRuntime.foreachReusing so FastTier sees the
        // closure and can specialize accumulator-shaped loops. (Non-FunV
        // callables — e.g. native fns — keep the generic monadic path.)
        // Closes a gap that previously made Set.foreach (which routes through
        // this general dispatchList path) miss FastTier entirely; List.foreach
        // already routed via `dispatchList1` so was unaffected.
        case List(f: Value.FunV) => CallRuntime.foreachReusing(ls, f, env, interp)
        case List(f)             => Computation.foreachSequence(ls, item => interp.callValue1(f, item, env))
        case _                   => dispatchFallback(recv, name, args, env, interp)
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
              case Pure(Value.BoolV(true)) => return Pure(Value.OptionV(h))
              case Pure(_)                 => rem = rem.tail
              case c =>
                val tail = rem.tail
                def restLoop(remaining: List[Value]): Computation = remaining match
                  case Nil => Computation.PureNone
                  case hh :: rest =>
                    FlatMap(interp.callValue1(f, hh, env), {
                      case Value.BoolV(true) => Pure(Value.OptionV(hh))
                      case _                 => restLoop(rest)
                    })
                return FlatMap(c, {
                  case Value.BoolV(true) => Pure(Value.OptionV(h))
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
          Computation.mapSequence(ls, item => interp.callValue1(f, item, env)).map {
            case Value.ListV(keys) =>
              val n = ls.length
              // Fast path: all keys are Int — sort numerically, not lexicographically.
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
                while i >= 0 do
                  result2 = arr2(i)._1 :: result2
                  i -= 1
                Value.ListV(result2)
              else
                // Build (value, strKey) array in one pass — avoids zip + map double-list.
                val arr = new Array[(Value, String)](n)
                var i = 0; var lRem = ls; var kRem = keys
                while lRem.nonEmpty do
                  arr(i) = (lRem.head, Value.show(kRem.head))
                  i += 1; lRem = lRem.tail; kRem = kRem.tail
                java.util.Arrays.sort(arr, java.util.Comparator.comparing[(Value, String), String](_._2))
                var result: List[Value] = Nil; i = n - 1
                while i >= 0 do
                  result = arr(i)._1 :: result
                  i -= 1
                Value.ListV(result)
            case _ => recv
          }
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foldLeft"     => args match
        case List(init) =>
          Pure(Value.NativeFnV("foldLeft", {
            case List(f: Value.FunV) => CallRuntime.foldLeftReusing(ls, init, f, env, interp)
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
      case "reduceLeft" | "reduce"  => args match
        // `reduce` is unordered in Scala but the standard List impl reduces left-to-right.
        case List(f) => ls match
          case Nil    => interp.located(s"$name on empty list")
          case h :: t => Computation.foldLeftSequence(t, h, (acc, x) => interp.callValue2(f, acc, x, env))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "reduceRight"  => args match
        // f(x0, f(x1, … f(x_{n-1}, x_n))) — seed with the last element, fold the init in reverse.
        case List(f) => ls match
          case Nil => interp.located("reduceRight on empty list")
          case _   => Computation.foldLeftSequence(ls.init.reverse, ls.last, (acc, x) => interp.callValue2(f, x, acc, env))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "reduceLeftOption" | "reduceOption" => args match
        case List(f) => ls match
          case Nil    => Pure(Value.OptionV(null))
          case h :: t => Computation.foldLeftSequence(t, h, (acc, x) => interp.callValue2(f, acc, x, env)) match
            case Pure(v) => Pure(Value.OptionV(v))
            case c       => FlatMap(c, v => Pure(Value.OptionV(v)))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "transpose"    => args match
        // Rows of a rectangular list-of-lists → columns. Pure; bails if a row is not a list.
        case Nil =>
          val rows = ls.map { case Value.ListV(inner) => inner; case _ => null }
          if rows.contains(null) then dispatchFallback(recv, name, args, env, interp)
          else Pure(Value.ListV(rows.transpose.map(col => Value.ListV(col))))
        case _   => dispatchFallback(recv, name, args, env, interp)
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
      case "scanRight"    => args match
        // result(i) = f(xs(i), result(i+1)), result(n) = init → build from the right.
        case List(init) =>
          Pure(Value.NativeFnV("scanRight", {
            case List(f) =>
              val buf = new scala.collection.mutable.ListBuffer[Value]()
              buf.prepend(init)
              def loop(remaining: List[Value], acc: Value): Computation = remaining match
                case Nil       => Pure(Value.ListV(buf.toList))
                case h :: rest => FlatMap(interp.callValue2(f, h, acc, env), { v => buf.prepend(v); loop(rest, v) })
              loop(ls.reverse, init)
            case _ => throw InterpretError("scanRight expects one function argument")
          }))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "distinctBy"   => args match
        // Keep the first element for each distinct key `f(elem)`.
        case List(f) =>
          val seen = scala.collection.mutable.HashSet.empty[Value]
          val buf  = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil       => Pure(Value.ListV(buf.toList))
            case h :: rest => FlatMap(interp.callValue1(f, h, env), { k =>
              if seen.add(k) then buf += h
              loop(rest)
            })
          loop(ls)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "collect"      => args match
        case List(f) =>
          val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
          var rem = ls
          while rem.nonEmpty do
            collectStep(f, rem.head, env, interp) match
              case null                         => rem = rem.tail   // PF not defined here → skip
              case Pure(ov: Value.OptionV) => if ov.inner != null then buf += ov.inner; rem = rem.tail
              case Pure(v)                      => buf += v; rem = rem.tail
              case comp =>
                val tail = rem.tail
                def loopRest(remaining: List[Value]): Computation = remaining match
                  case Nil => Pure(Value.ListV(buf.toList))
                  case h :: rest =>
                    collectStep(f, h, env, interp) match
                      case null                    => loopRest(rest)
                      case Pure(ov: Value.OptionV) => if ov.inner != null then buf += ov.inner; loopRest(rest)
                      case Pure(v)                 => buf += v; loopRest(rest)
                      case c => FlatMap(c, {
                        case ov: Value.OptionV => if ov.inner != null then buf += ov.inner; loopRest(rest)
                        case v                 => buf += v; loopRest(rest)
                      })
                return FlatMap(comp, {
                  case ov: Value.OptionV => if ov.inner != null then buf += ov.inner; loopRest(tail)
                  case v                 => buf += v; loopRest(tail)
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
      case "toList" | "toSeq" | "toVector" | "toIndexedSeq" =>
        val tlBuf = new scala.collection.mutable.ArrayBuffer[Value](m.size)
        val tlIt = m.iterator
        while tlIt.hasNext do
          val (k, v) = tlIt.next()
          tlBuf += Value.TupleV(k :: v :: Nil)
        if name == "toVector" || name == "toIndexedSeq" then Pure(Value.VectorV(tlBuf.toVector))
        else Pure(Value.ListV(tlBuf.toList))
      case "mkString" => Pure(Value.StringV(Value.show(recv)))
      case "contains" => args match
        case List(k)       => Computation.pureBool(m.contains(k))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "get"      => args match
        case List(k)       =>
          val gv = m.getOrElse(k, null)
          if gv == null then Computation.PureNone else Pure(Value.OptionV(gv))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "apply"    => args match
        case List(k)       => Pure(m.getOrElse(k, interp.located(s"Key not found: ${Value.show(k)}")))
        case _             => dispatchFallback(recv, name, args, env, interp)
      case "getOrElse" => args match
        case List(k, d)    =>
          val v = m.getOrElse(k, null)
          Pure(if v == null || (v eq Value.NullV) then d else v)
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
          // FastTier 2-arg foreach short-circuit for the
          // `m.foreach((k, v) => acc = acc + paramRef)` shape. Skips the
          // per-entry callEntry allocation chain. Mirrors dispatchMap1's hook.
          if f.isInstanceOf[Value.FunV] then
            val fv = f.asInstanceOf[Value.FunV]
            val ftD = FastTier.tryDoubleAccumForeachMap(m, fv, interp)
            if ftD != null then return ftD
            val ftL = FastTier.tryLongAccumForeachMap(m, fv, interp)
            if ftL != null then return ftL
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
              case Pure(Value.BoolV(true))  => return Pure(Value.OptionV(Value.TupleV(k :: v :: Nil)))
              case Pure(_)                  => // continue
              case comp =>
                return FlatMap(comp, {
                  case Value.BoolV(true) => Pure(Value.OptionV(Value.TupleV(k :: v :: Nil)))
                  case _ =>
                    def loopRest(): Computation =
                      if !it.hasNext then Computation.PureNone
                      else
                        val (k2, v2) = it.next()
                        FlatMap(interp.callEntry(f, k2, v2, env), {
                          case Value.BoolV(true) => Pure(Value.OptionV(Value.TupleV(k2 :: v2 :: Nil)))
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
      case "orElse"    => args match
        case List(other) if isOptionValue(other) => opt match
          case Some(_) => Pure(recv)
          case None    => Pure(other)
        case _ => dispatchFallback(recv, name, args, env, interp)
      case "map"       => args match
        case List(f) => opt match
          case None    => Computation.PureNone
          case Some(v) => interp.callValue1(f, v, env) match
            case Pure(rv) => Pure(Value.OptionV(rv))
            case c        => FlatMap(c, Computation.wrapSomeC)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "flatMap"   => args match
        case List(f) => opt match
          case None    => Computation.PureNone
          case Some(v) => interp.callValue1(f, v, env) match
            case Pure(o: Value.OptionV) => Pure(o)
            case Pure(other)            => Pure(Value.OptionV(other))
            case c                      => FlatMap(c, Computation.wrapOptionC)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "filter"    => args match
        case List(f) => opt match
          case None    => Computation.PureNone
          case Some(v) => interp.callValue1(f, v, env) match
            case Pure(Value.BoolV(true)) => Pure(recv)
            case Pure(_)                 => Computation.PureNone
            case c                       =>
              FlatMap(c, {
                case Value.BoolV(true) => Pure(recv)
                case _                 => Computation.PureNone
              })
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
            case ov: Value.OptionV =>
              if ov.inner != null then Pure(Value.OptionV(Value.TupleV(v :: ov.inner :: Nil)))
              else Computation.PureNone
            case _ => Computation.PureNone
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "flatten"   => opt match
        case None            => Computation.PureNone
        case Some(inner)     => inner match
          case o: Value.OptionV => Pure(o)
          case _                => Pure(recv)
      case "toRight"   => args match
        case List(left) => opt match
          case None    => Pure(Value.singleValue("Left", left))
          case Some(v) => Pure(Value.singleValue("Right", v))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "toLeft"    => args match
        case List(right) => opt match
          case None    => Pure(Value.singleValue("Right", right))
          case Some(v) => Pure(Value.singleValue("Left", v))
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

  // ── Array / Vector / LazyList — real Scala collection semantics ───────────────────────────────

  /** Ops that RETURN the same sequence type, so a typed sequence keeps its type through them
   *  (`Vector(1,2,3).map(_*2)` stays a `Vector`; `Array.map` stays an `Array`). (collection-real-type.) */
  private val kindPreservingOps: Set[String] = Set(
    "map", "filter", "filterNot", "take", "drop", "takeWhile", "dropWhile",
    "reverse", "sorted", "sortBy", "sortWith", "distinct", "tail", "init",
    "slice", "dropRight", "takeRight", "updated", "padTo", "appended", "prepended",
    "++", ":::", "concat", "intersect", "diff", "by", "collect")

  /** The element list backing any sequence Value (forces a LazyList). */
  private def seqAsList(v: Value): List[Value] = v match
    case l: Value.ListV      => l.items
    case v: Value.VectorV    => v.items.toList
    case a: Value.ArrayV     => a.items.toList
    case ll: Value.LazyListV => ll.underlying.toList
    case Value.SetV(s)       => s.toList
    case _                   => Nil

  /** Any sequence Value as a (lazy) `LazyList[Value]` — preserves laziness when the source is lazy. */
  private def seqAsLazy(v: Value): LazyList[Value] = v match
    case ll: Value.LazyListV => ll.underlying
    case l: Value.ListV      => l.items.to(LazyList)
    case v: Value.VectorV    => v.items.to(LazyList)
    case a: Value.ArrayV     => a.items.to(LazyList)
    case Value.SetV(s)       => s.to(LazyList)
    case _                   => LazyList.empty

  private def boolOf(v: Value): Boolean = v match
    case Value.BoolV(b) => b
    case _              => false

  /** Dispatch on a real **indexed** `Vector` (`Value.VectorV`). Index/update/head/last/length are
   *  handled directly on the `Vector[Value]` so they stay O(log₃₂ n); conversions return the right
   *  type; everything else delegates to the shared List dispatch and re-wraps a fresh sequence result
   *  as a `VectorV` for ops that keep the collection type (`Vector.map` returns a `Vector`).
   *  (collection-vector-indexed.) */
  private def dispatchVector(vv: Value.VectorV, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    val items = vv.items
    name match
      case "apply" => args match
        case List(Value.IntV(i)) =>
          val idx = i.toInt
          if idx < 0 || idx >= items.length then interp.located(s"index $idx out of bounds for vector of length ${items.length}")
          else Pure(items(idx))                                   // O(log₃₂ n) indexed read
        case _ => dispatchList(items.toList, name, args, env, interp)
      case "updated" => args match
        case List(Value.IntV(i), v) =>
          val idx = i.toInt
          if idx < 0 || idx >= items.length then interp.located(s"index $idx out of bounds for vector of length ${items.length}")
          else Pure(Value.VectorV(items.updated(idx, v)))         // O(log₃₂ n) functional update
        case _ => dispatchList(items.toList, name, args, env, interp)
      case "length" | "size"                 => Computation.pureIntV(items.length.toLong)
      case "head"                            => if items.isEmpty then interp.located("head on empty Vector") else Pure(items.head)
      case "last"                            => if items.isEmpty then interp.located("last on empty Vector") else Pure(items.last)
      case "isEmpty"                         => Computation.pureBool(items.isEmpty)
      case "nonEmpty"                        => Computation.pureBool(items.nonEmpty)
      case "toVector" | "toIndexedSeq" | "toSeq" => Pure(vv)
      case "toList" | "toIterable"           => Pure(Value.ListV(items.toList))
      case "toArray"                         => Pure(Value.ArrayV(items.toArray))
      case _ =>
        val comp = dispatchList(items.toList, name, args, env, interp)
        if !kindPreservingOps(name) then comp
        else comp match
          case Pure(lv: Value.ListV) => Pure(Value.VectorV(lv.items.toVector))
          case Pure(_)               => comp
          case c                     => c.map { case lv: Value.ListV => Value.VectorV(lv.items.toVector); case v => v }

  /** Dispatch on a real **mutable** `Array` (`Value.ArrayV`). `update` mutates in place; conversions
   *  return the right target type; everything else delegates to the shared List dispatch and re-wraps a
   *  sequence result as a FRESH mutable `ArrayV` (`Array.map` returns an `Array`). (collection-real-type.) */
  private def dispatchArray(a: Value.ArrayV, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    val items = a.items
    name match
      case "update" => args match
        case List(Value.IntV(i), v) =>
          val idx = i.toInt
          if idx < 0 || idx >= items.length then interp.located(s"index $idx out of bounds for array of length ${items.length}")
          else { items(idx) = v; Computation.PureUnit }
        case _ => interp.located("Array.update requires (Int index, value)")
      case "apply" => args match
        case List(Value.IntV(i)) =>
          val idx = i.toInt
          if idx < 0 || idx >= items.length then interp.located(s"index $idx out of bounds for array of length ${items.length}")
          else Pure(items(idx))
        case _ => dispatchList(items.toList, name, args, env, interp)
      case "length" | "size"                 => Computation.pureIntV(items.length.toLong)
      case "clone" | "toArray"               => Pure(Value.ArrayV(items.clone()))
      case "toList" | "toSeq" | "toIterable" => Pure(Value.ListV(items.toList))
      case "toVector" | "toIndexedSeq"       => Pure(Value.VectorV(items.toVector))
      case "sameElements" => args match
        case List(other) => Computation.pureBool(items.toList == seqAsList(other))
        case _           => interp.located("sameElements requires one argument")
      case _ =>
        // Delegate to List dispatch; re-wrap a fresh sequence result as a mutable ArrayV for ops that
        // keep the collection type (`Array.map`/`filter`/… return an `Array`).
        val comp = dispatchList(items.toList, name, args, env, interp)
        if !kindPreservingOps(name) then comp
        else comp match
          case Pure(lv: Value.ListV) => Pure(Value.ArrayV(lv.items.toArray))
          case Pure(_)               => comp
          case c                     => c.map { case lv: Value.ListV => Value.ArrayV(lv.items.toArray); case v => v }

  /** Dispatch on a real **lazy** `LazyList` (`Value.LazyListV`). Lazy-critical ops operate directly on
   *  the backing `LazyList[Value]` so infinite streams work and only the demanded prefix is forced;
   *  anything else forces to a List and reuses the shared dispatch. Element functions must be PURE
   *  (run via `Computation.run`). (collection-real-type.) */
  private def dispatchLazyList(llv: Value.LazyListV, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    val ll = llv.underlying
    def lazily(r: LazyList[Value]): Computation = Pure(Value.LazyListV(r))
    def fn1(f: Value): Value => Value = (x: Value) => Computation.run(interp.callValue1(f, x, env))
    name match
      case "map"        => args match { case List(f) => lazily(ll.map(fn1(f)));                       case _ => interp.located("LazyList.map(f)") }
      case "filter"     => args match { case List(f) => lazily(ll.filter(x => boolOf(fn1(f)(x))));    case _ => interp.located("LazyList.filter(p)") }
      case "filterNot" | "withFilter" => args match { case List(f) => lazily(ll.filterNot(x => boolOf(fn1(f)(x)))); case _ => interp.located("LazyList.filterNot(p)") }
      case "flatMap"    => args match { case List(f) => lazily(ll.flatMap(x => seqAsLazy(fn1(f)(x)))); case _ => interp.located("LazyList.flatMap(f)") }
      case "take"       => args match { case List(Value.IntV(n)) => lazily(ll.take(n.toInt));         case _ => interp.located("LazyList.take(n)") }
      case "drop"       => args match { case List(Value.IntV(n)) => lazily(ll.drop(n.toInt));         case _ => interp.located("LazyList.drop(n)") }
      case "takeWhile"  => args match { case List(f) => lazily(ll.takeWhile(x => boolOf(fn1(f)(x)))); case _ => interp.located("LazyList.takeWhile(p)") }
      case "dropWhile"  => args match { case List(f) => lazily(ll.dropWhile(x => boolOf(fn1(f)(x)))); case _ => interp.located("LazyList.dropWhile(p)") }
      case "zipWithIndex" => lazily(ll.zipWithIndex.map((v, i) => Value.TupleV(List(v, Value.intV(i)))))
      case "zip"        => args match { case List(other) => lazily(ll.zip(seqAsLazy(other)).map((x, y) => Value.TupleV(List(x, y)))); case _ => interp.located("LazyList.zip(other)") }
      case "++" | "concat" | "appendedAll" | "lazyAppendedAll" =>
        args match { case List(other) => lazily(ll.lazyAppendedAll(seqAsLazy(other))); case _ => interp.located("LazyList ++ other") }
      case "prepended" | "+:" => args match { case List(x) => lazily(LazyList.cons(x, ll)); case _ => interp.located("LazyList.+:(x)") }
      case "head"       => if ll.isEmpty then interp.located("head on empty LazyList") else Pure(ll.head)
      case "headOption" => Pure(Value.optionV(ll.headOption))
      case "tail"       => if ll.isEmpty then interp.located("tail on empty LazyList") else lazily(ll.tail)
      case "isEmpty"    => Computation.pureBool(ll.isEmpty)
      case "nonEmpty"   => Computation.pureBool(ll.nonEmpty)
      case "exists"     => args match { case List(f) => Computation.pureBool(ll.exists(x => boolOf(fn1(f)(x)))); case _ => interp.located("LazyList.exists(p)") }
      case "forall"     => args match { case List(f) => Computation.pureBool(ll.forall(x => boolOf(fn1(f)(x)))); case _ => interp.located("LazyList.forall(p)") }
      case "find"       => args match { case List(f) => Pure(Value.optionV(ll.find(x => boolOf(fn1(f)(x))))); case _ => interp.located("LazyList.find(p)") }
      case "apply"      => args match { case List(Value.IntV(i)) => Pure(ll(i.toInt)); case _ => interp.located("LazyList.apply(i)") }
      case "contains"   => args match { case List(x) => Computation.pureBool(ll.contains(x)); case _ => interp.located("LazyList.contains(x)") }
      case "toList" | "toSeq" | "toIterable" => Pure(Value.ListV(ll.toList))
      case "toVector" | "toIndexedSeq"       => Pure(Value.VectorV(ll.toVector))
      case "toArray"    => Pure(Value.ArrayV(ll.toArray))
      case "force"      => ll.force; Pure(llv)
      case _            => dispatchList(ll.toList, name, args, env, interp)   // force for the rest (finite only)

  /** Apply a `collect` element function, returning `null` to SKIP the element when a partial function
   *  isn't defined there (a `case`-guard that doesn't match raises a located "Match failure" rather than
   *  returning). An `Option`-returning fn handles its own skip via `None`. (interp-collect-partial.) */
  /** `String.map` result: a `String` when every mapped element is a `Char` (incl. the empty case),
   *  else a `List` (`Seq[B]`). (interp-js-string-map-nonchar.) */
  private def strMapResult(items: List[Value]): Value =
    if items.forall(_.isInstanceOf[Value.CharV]) then
      val sb = new java.lang.StringBuilder(items.length)
      items.foreach { case Value.CharV(c) => sb.append(c); case _ => () }
      Value.StringV(sb.toString)
    else Value.ListV(items)

  private def collectStep(f: Value, elem: Value, env: Env, interp: Interpreter): Computation | Null =
    try interp.callValue1(f, elem, env)
    catch case e: InterpretError if e.getMessage != null && e.getMessage.contains("Match failure:") => null

  private def dispatchInt1(n: Long, name: String, arg: Value, env: Env, interp: Interpreter): Computation =
    val recv = Value.intV(n)
    name match
      // `6 + "_"` — Scala's `any2stringadd` concatenates a number with a String. (interp-num-string-concat.)
      case "+" => arg match
        case Value.StringV(s) => Pure(Value.StringV(n.toString + s))
        case _                => dispatchInt(n, name, arg :: Nil, env, interp)
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
          if n > m then Computation.PureEmptyList
          else
            var list: List[Value] = Nil; var i = m
            while i >= n do { list = Value.intV(i) :: list; i -= 1 }
            Pure(Value.ListV(list))
        case _ => dispatchFallback(recv, name, arg :: Nil, env, interp)
      case "until" => arg match
        case Value.IntV(m) =>
          if n >= m then Computation.PureEmptyList
          else
            var list: List[Value] = Nil; var i = m - 1
            while i >= n do { list = Value.intV(i) :: list; i -= 1 }
            Pure(Value.ListV(list))
        case _ => dispatchFallback(recv, name, arg :: Nil, env, interp)
      case _       => dispatchInt(n, name, arg :: Nil, env, interp)

  private def dispatchInt(n: Long, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    val recv = Value.intV(n)
    name match
      // `6 + "_"` — `any2stringadd` concatenates a number with a String. (interp-num-string-concat.)
      case "+" => args match
        case List(Value.StringV(s)) => Pure(Value.StringV(n.toString + s))
        case _                      => dispatchFallback(recv, name, args, env, interp)
      case "toDouble"  => Pure(Value.doubleV(n.toDouble))
      case "toLong"    => Pure(recv)
      case "toInt"     => Pure(recv)
      case "toBigInt"  => Pure(Value.BigIntV(BigInt(n)))
      case "toDecimal" => Pure(Value.DecimalV(BigDecimal(n)))
      case "toFloat"   => Pure(Value.doubleV(n.toDouble))
      case "toByte"    => Computation.pureIntV(n.toByte.toLong)
      case "toShort"   => Computation.pureIntV(n.toShort.toLong)
      // `65.toChar` → 'A' — construct a UTF-16 code unit from an Int code point.
      // (portable-codepoint-string-construction.)
      case "toChar"    => Pure(Value.CharV(n.toChar))
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
      // ── bitwise operators (Int is Long-backed, so results are 64-bit; mask with `& 0xFF` etc.) ──
      case "&"   => args match
        case List(Value.IntV(m)) => Computation.pureIntV(n & m)
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "|"   => args match
        case List(Value.IntV(m)) => Computation.pureIntV(n | m)
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "^"   => args match
        case List(Value.IntV(m)) => Computation.pureIntV(n ^ m)
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "<<"  => args match
        case List(Value.IntV(m)) => Computation.pureIntV(n << m)
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case ">>"  => args match
        case List(Value.IntV(m)) => Computation.pureIntV(n >> m)
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case ">>>" => args match
        case List(Value.IntV(m)) => Computation.pureIntV(n >>> m)
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "unary_~"   => Computation.pureIntV(~n)
      case "to"        => args match
        case List(Value.IntV(m)) =>
          if n > m then Computation.PureEmptyList
          else
            var list: List[Value] = Nil; var i = m
            while i >= n do { list = Value.intV(i) :: list; i -= 1 }
            Pure(Value.ListV(list))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "until"     => args match
        case List(Value.IntV(m)) =>
          if n >= m then Computation.PureEmptyList
          else
            var list: List[Value] = Nil; var i = m - 1
            while i >= n do { list = Value.intV(i) :: list; i -= 1 }
            Pure(Value.ListV(list))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case _ => dispatchFallback(recv, name, args, env, interp)

  // ── BigInt (exact-numerics v1.64) ─────────────────────────────────────────────

  private def dispatchBigInt(n: BigInt, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    val recv = Value.BigIntV(n)
    def asBig(v: Value): BigInt = v match
      case Value.BigIntV(b) => b
      case Value.IntV(i)    => BigInt(i)
      case other            => throw InterpretError(s"BigInt.$name: expected an integer, got ${Value.show(other)}")
    name match
      case "toInt"      => Computation.pureIntV(n.toLong)
      case "toLong"     => Computation.pureIntV(n.toLong)
      case "toBigInt"   => Pure(recv)
      case "toDouble"   => Pure(Value.doubleV(n.toDouble))
      case "toFloat"    => Pure(Value.doubleV(n.toDouble))
      case "toString"   => Pure(Value.StringV(n.toString))
      case "abs"        => Pure(Value.BigIntV(n.abs))
      case "negate"     => Pure(Value.BigIntV(-n))
      case "sign"       => Computation.pureIntV(n.signum.toLong)
      case "signum"     => Computation.pureIntV(n.signum.toLong)
      case "isEven"     => Computation.pureBool(!n.testBit(0))
      case "isOdd"      => Computation.pureBool(n.testBit(0))
      case "bitLength"  => Computation.pureIntV(n.bitLength.toLong)
      case "pow"        => args match
        case List(Value.IntV(e)) => Pure(Value.BigIntV(n.pow(e.toInt)))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "gcd"        => args match
        case List(a)             => Pure(Value.BigIntV(n.gcd(asBig(a))))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "min"        => args match
        case List(a)             => Pure(Value.BigIntV(n.min(asBig(a))))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "max"        => args match
        case List(a)             => Pure(Value.BigIntV(n.max(asBig(a))))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "mod"        => args match
        case List(a)             => Pure(Value.BigIntV(n.mod(asBig(a))))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "isProbablePrime" => args match
        case List(Value.IntV(c)) => Computation.pureBool(n.isProbablePrime(c.toInt))
        case Nil                 => Computation.pureBool(n.isProbablePrime(20))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "toDecimal"  => Pure(Value.DecimalV(BigDecimal(n)))
      case "+" => args match { case List(a) => Pure(Value.BigIntV(n + asBig(a))); case _ => dispatchFallback(recv, name, args, env, interp) }
      case "-" => args match { case List(a) => Pure(Value.BigIntV(n - asBig(a))); case _ => dispatchFallback(recv, name, args, env, interp) }
      case "*" => args match { case List(a) => Pure(Value.BigIntV(n * asBig(a))); case _ => dispatchFallback(recv, name, args, env, interp) }
      case "/" => args match { case List(a) => Pure(Value.BigIntV(n / asBig(a))); case _ => dispatchFallback(recv, name, args, env, interp) }
      case "%" => args match { case List(a) => Pure(Value.BigIntV(n % asBig(a))); case _ => dispatchFallback(recv, name, args, env, interp) }
      case _ => dispatchFallback(recv, name, args, env, interp)

  // ── Decimal (exact-numerics v1.64) ────────────────────────────────────────────

  /** Parse a RoundingMode from a ScalaScript value: a String name
   *  ("HALF_UP", "HALF_EVEN", …) or a RoundingMode.X instance carrying `_name`. */
  private def roundingMode(v: Value): java.math.RoundingMode = v match
    case Value.StringV(s)                       => java.math.RoundingMode.valueOf(s.trim.toUpperCase)
    case Value.InstanceV("RoundingMode", f)     =>
      f.get("_name") match
        case Some(Value.StringV(s)) => java.math.RoundingMode.valueOf(s.trim.toUpperCase)
        case _                      => throw InterpretError("RoundingMode: missing name")
    case other => throw InterpretError(s"expected a RoundingMode, got ${Value.show(other)}")

  private def dispatchDecimal(d: BigDecimal, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    val recv = Value.DecimalV(d)
    def asDec(v: Value): BigDecimal = v match
      case Value.DecimalV(x) => x
      case Value.IntV(i)     => BigDecimal(i)
      case Value.BigIntV(b)  => BigDecimal(b)
      case Value.DoubleV(_)  => throw decimalDoubleMix(name)
      case other             => throw InterpretError(s"Decimal.$name: expected a number, got ${Value.show(other)}")
    name match
      case "scale"     => Computation.pureIntV(d.scale.toLong)
      case "precision" => Computation.pureIntV(d.precision.toLong)
      case "toDecimal" => Pure(recv)
      case "toBigInt"  => Pure(Value.BigIntV(d.toBigInt))
      case "toInt"     => Computation.pureIntV(d.toLong)
      case "toLong"    => Computation.pureIntV(d.toLong)
      case "toDouble"  => Pure(Value.doubleV(d.toDouble))
      case "toString"  => Pure(Value.StringV(d.bigDecimal.toPlainString))
      case "abs"       => Pure(Value.DecimalV(d.abs))
      case "negate"    => Pure(Value.DecimalV(-d))
      case "signum"    => Computation.pureIntV(d.signum.toLong)
      case "isZero"    => Computation.pureBool(d.signum == 0)
      case "min"       => args match { case List(a) => Pure(Value.DecimalV(d.min(asDec(a)))); case _ => dispatchFallback(recv, name, args, env, interp) }
      case "max"       => args match { case List(a) => Pure(Value.DecimalV(d.max(asDec(a)))); case _ => dispatchFallback(recv, name, args, env, interp) }
      case "pow"       => args match
        case List(Value.IntV(e)) => Pure(Value.DecimalV(d.pow(e.toInt)))
        case _                   => dispatchFallback(recv, name, args, env, interp)
      case "setScale"  => args match
        case List(Value.IntV(sc))             => Pure(Value.DecimalV(BigDecimal(d.bigDecimal.setScale(sc.toInt, java.math.RoundingMode.HALF_UP))))
        case List(Value.IntV(sc), modeV)      => Pure(Value.DecimalV(BigDecimal(d.bigDecimal.setScale(sc.toInt, roundingMode(modeV)))))
        case _                                => dispatchFallback(recv, name, args, env, interp)
      case "round"     => args match
        case List(modeV)                      => Pure(Value.DecimalV(BigDecimal(d.bigDecimal.setScale(0, roundingMode(modeV)))))
        case Nil                              => Pure(Value.DecimalV(BigDecimal(d.bigDecimal.setScale(0, java.math.RoundingMode.HALF_UP))))
        case _                                => dispatchFallback(recv, name, args, env, interp)
      case "divide"    => args match
        case List(divisor, Value.IntV(sc), modeV) =>
          Pure(Value.DecimalV(BigDecimal(d.bigDecimal.divide(asDec(divisor).bigDecimal, sc.toInt, roundingMode(modeV)))))
        case List(divisor, modeV) =>
          Pure(Value.DecimalV(BigDecimal(d.bigDecimal.divide(asDec(divisor).bigDecimal, d.scale, roundingMode(modeV)))))
        case _                                => dispatchFallback(recv, name, args, env, interp)
      case "compareTo" => args match { case List(a) => Computation.pureIntV(d.compare(asDec(a)).toLong); case _ => dispatchFallback(recv, name, args, env, interp) }
      case "+" => args match { case List(a) => Pure(Value.DecimalV(d + asDec(a))); case _ => dispatchFallback(recv, name, args, env, interp) }
      case "-" => args match { case List(a) => Pure(Value.DecimalV(d - asDec(a))); case _ => dispatchFallback(recv, name, args, env, interp) }
      case "*" => args match { case List(a) => Pure(Value.DecimalV(d * asDec(a))); case _ => dispatchFallback(recv, name, args, env, interp) }
      case "/" => args match { case List(a) => Pure(Value.DecimalV(d / asDec(a))); case _ => dispatchFallback(recv, name, args, env, interp) }
      case _ => dispatchFallback(recv, name, args, env, interp)

  // ── Double ──────────────────────────────────────────────────────────────────

  /** 1-arg fast path for Double: avoids arg :: Nil for common math ops. */
  private def dispatchDouble1(d: Double, name: String, arg: Value, env: Env, interp: Interpreter): Computation =
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
      // Arrow-vs-plus precedence fix: `Map("k" -> "prefix " + val)` parses as
      // `("k" -> "prefix ") + val` due to equal precedence of `->` and `+`.
      // When a 2-tuple's second element is a String, absorb `+` into that element.
      case "+"   => es match
        case List(k, Value.StringV(s)) if args.length == 1 =>
          Pure(Value.TupleV(k :: Value.StringV(s + Value.show(args.head)) :: Nil))
        case _ => dispatchFallback(recv, name, args, env, interp)
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

  private def dispatchInstance(recv: Value, typeName: String, fields0: Map[String, Value], name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    // Use fieldsArr-backed map when the instance was created by StatRuntime (fields0 == Map.empty).
    val fields: Map[String, Value] = recv match
      case inst: Value.InstanceV if inst.fieldsArr != null && fields0.isEmpty =>
        instanceFieldsMap(inst)
      case _ => fields0
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
          fields.getOrElse("seedResolver", null) match
            case seedResolver @ Value.InstanceV("SeedResolver", _) =>
              Perform("Actor", "resolveSeeds", seedResolver :: Nil)
            case _ => interp.located("ClusterCapability missing seedResolver")
        case _ => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)

      case "Signal" => name match
        case "get" | "apply" =>
          fields.getOrElse("id", null) match
            case Value.IntV(id) => Pure(SignalRuntime.signalGet(interp, id))
            case _              => interp.located("Signal handle missing id")
        case "set" => args match
          case List(v) => fields.getOrElse("id", null) match
            case Value.IntV(id) => SignalRuntime.signalSet(interp, id, v); Computation.PureUnit
            case _              => interp.located("Signal handle missing id")
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
            val existing = fields.getOrElse("headers", null) match
              case Value.MapV(m) => m
              case _             => Map.empty[Value, Value]
            val merged = existing + (Value.StringV(hname) -> Value.StringV(value))
            Pure(Value.InstanceV("Response", fields + ("headers" -> Value.MapV(merged))))
          case _           => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)
        case _ => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)

      case "Right" => name match
        case "isRight"   => Computation.PureTrue
        case "isLeft"    => Computation.PureFalse
        case "toOption"  => Pure(Value.OptionV(fields.getOrElse("value", Value.UnitV)))
        case "swap"      => Pure(Value.InstanceV("Left", fields))
        case "toSeq"     => Pure(Value.ListV(fields.getOrElse("value", Value.UnitV) :: Nil))
        case "getOrElse" => Pure(fields.getOrElse("value", Value.UnitV))
        case "map"       => args match
          case List(f) =>
            interp.callValue1(f, fields.getOrElse("value", Value.UnitV), env).map(v =>
              Value.singleValue("Right", v))
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

      case "Expr" => name match
        case "asValue" if args.isEmpty =>
          fields.get("value") match
            case Some(Value.NoneV) | None => Computation.PureNone
            case Some(v)                 => Pure(Value.OptionV(v))
        case "asTerm" if args.isEmpty =>
          Pure(Value.InstanceV("ScalaScriptTerm", Map(
            "name"  -> fields.getOrElse("name", Value.StringV("<expr>")),
            "value" -> fields.getOrElse("value", Value.UnitV)
          )))
        case "toString" if args.isEmpty =>
          val label = fields.get("name").map(Value.show).getOrElse("<expr>")
          Pure(Value.StringV(s"Expr($label)"))
        case _ => dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)

      case _ =>
        if isPluginBridgeInstance(typeName) then
          dispatchInstanceFallback(recv, typeName, fields, name, args, env, interp)
        else
          dispatchOrdinaryInstance(recv, typeName, fields, name, args, env, interp)

  private def isPluginBridgeInstance(typeName: String): Boolean =
    typeName == "Source" || typeName == "RemoteSource" || typeName == "ReactiveSignal"

  private def dispatchOrdinaryInstance(recv: Value, typeName: String, fields0: Map[String, Value], name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    val fields: Map[String, Value] = recv match
      case inst: Value.InstanceV if inst.fieldsArr != null && fields0.isEmpty =>
        instanceFieldsMap(inst)
      case _ => fields0
    if name == "getMessage" && args.isEmpty then
      Pure(fields.getOrElse("message", Value.EmptyStr))
    else
      val typeMethodMap = interp.typeMethods.getOrElse(typeName, null)
      if typeMethodMap != null then
        val fn = typeMethodMap.getOrElse(name, null)
        if fn != null then
          invokeTypeMethod(fn, recv, fields, args, interp)
        else
          dispatchInstanceAfterMethods(recv, fields, name, args, env, interp)
      else
        dispatchInstanceAfterMethods(recv, fields, name, args, env, interp)

  private def dispatchInstanceFallback(recv: Value, typeName: String, fields0: Map[String, Value], name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    val fields: Map[String, Value] = recv match
      case inst: Value.InstanceV if inst.fieldsArr != null && fields0.isEmpty =>
        instanceFieldsMap(inst)
      case _ => fields0
    // Source.distributed bridge — dispatches to DStreams plugin when loaded (v1.63.1)
    if typeName == "Source" && name == "distributed" then
      val fn = interp.globals.getOrElse("Source.distributed", null)
      if fn != null then interp.callValuePrepend(fn, recv, args, env)
      else interp.located("Source.distributed requires the DStreams plugin")
    // Source.remote bridge — dispatches to streams-bridge plugin when loaded (v1.63.6)
    else if typeName == "Source" && name == "remote" then
      val fn = interp.globals.getOrElse("Source.remote", null)
      if fn != null then interp.callValuePrepend(fn, recv, args, env)
      else interp.located("Source.remote requires the streams-bridge plugin")
    // RemoteSource.local bridge (v1.63.6)
    else if typeName == "RemoteSource" && name == "local" then
      val fn = interp.globals.getOrElse("remoteSourceLocal", null)
      if fn != null then
        val buffer = if args.nonEmpty then args.head else Value.intV(1024)
        interp.callValue2(fn, recv, buffer, env)
      else interp.located("remoteSourceLocal requires the streams-bridge plugin")
    // RemoteSource.distributed bridge (v1.63.6)
    else if typeName == "RemoteSource" && name == "distributed" then
      val localFn = interp.globals.getOrElse("remoteSourceLocal", null)
      if localFn != null then
        interp.callValue2(localFn, recv, Value.intV(1024), env).flatMap { localSrc =>
          val df = interp.globals.getOrElse("Source.distributed", null)
          if df != null then interp.callValuePrepend(df, localSrc, args, env)
          else interp.located("RemoteSource.distributed requires the DStreams plugin")
        }
      else interp.located("remoteSourceLocal requires the streams-bridge plugin")
    // ReactiveSignal bridge (no hard-coding streams into core)
    else if typeName == "ReactiveSignal" && name == "bind" then
      args match
        case List(source) =>
          val fn = interp.globals.getOrElse("ReactiveSignal.bind", null)
          if fn != null then interp.callValue2(fn, recv, source, env)
          else interp.located("No method 'bind' on ReactiveSignal")
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
          invokeTypeMethod(fn, recv, fields, args, interp)
        else
          dispatchInstanceAfterMethods(recv, fields, name, args, env, interp)
      else
        dispatchInstanceAfterMethods(recv, fields, name, args, env, interp)

  private def dispatchForeign(recv: Value, typeName: String, name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    // ReactiveSignal get/set — programmatic read/write, matching the JS lane's
    // sig.get()/sig.set(v) (signals.mjs). Read parity with `sig()` (CallRuntime);
    // write makes ui-signal behavior (e.g. std/ui/offline persistedSignal
    // write-back) testable INT==JS instead of browser-only.
    recv match
      case Value.Foreign("ReactiveSignal", sig: scalascript.frontend.ReactiveSignal[?])
          if (name == "get" && args.isEmpty) || (name == "set" && args.length == 1) =>
        if name == "get" then Pure(interp.wrapAnyAsValue(sig.apply()))
        else
          sig.asInstanceOf[scalascript.frontend.ReactiveSignal[Any]].set(interp.unwrapValueAsAny(args.head))
          Pure(Value.UnitV)
      // ReactiveSignal.id — a signal's stable cross-backend name (its first ctor arg).
      // The native v2 ui-plugin (NativeUiSignal) exposes it; the v1 interp + v2 bridge
      // lanes did not, so `signal(name,_).id` crashed. v2-bridged-ui-signal-id-field.
      case Value.Foreign("ReactiveSignal", sig: scalascript.frontend.ReactiveSignal[?])
          if name == "id" && args.isEmpty =>
        Pure(Value.StringV(sig.id))
      case _ =>
        interp.globals.get(s"$typeName.$name") match
          case Some(fn) => interp.callValuePrepend(fn, recv, args, env)
          case None     => dispatchFallback(recv, name, args, env, interp)

  /** Reconstruct a `Map[String, Value]` from `fieldsArr + fieldNames` for
   *  instances created by StatRuntime (which now stores `Map.empty` in `fields`).
   *  Used only for `callTypeMethod` to make instance fields available inside
   *  method bodies; field reads go through `resolveField` / `fieldsArr` directly. */
  private def instanceFieldsMap(inst: Value.InstanceV): Map[String, Value] =
    val arr   = inst.fieldsArr
    val names = inst.fieldNames
    if arr == null || names == null then inst.fields
    else FrameMap.fromArrays(names, arr, Map.empty)

  /** Look up a concrete type method `name`, walking the parent-type chain so a
   *  method inherited from a (sealed-)trait / superclass dispatches on a subtype
   *  instance — `enum case → enum → intermediate trait → trait` (busi seq-121). */
  private def lookupTypeMethod(typeName: String, name: String, interp: Interpreter): Value.FunV | Null =
    var t: String | Null = typeName
    while t != null do
      val m = interp.typeMethods.getOrElse(t, null)
      if m != null then
        val fn = m.getOrElse(name, null)
        if fn != null then return fn
      t = interp.parentTypes.getOrElse(t, null)
    null

  /** Invoke a resolved type method, binding `this` to the receiver when the body
   *  references it (cached). Keeps the common `this`-free path allocation-free. */
  private def invokeTypeMethod(fn: Value.FunV, recv: Value, fields: Map[String, Value], args: List[Value], interp: Interpreter): Computation =
    val callFields = if interp.methodUsesThis(fn.body) then fields.updated("this", recv) else fields
    args match
      case List(a) => interp.callTypeMethod1(fn, callFields, a)
      case _       => interp.callTypeMethod(fn, callFields, args)

  private def dispatchInstanceAfterMethods(recv: Value, fields: Map[String, Value], name: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    // Resolve field value: prefer fieldsArr index lookup (O(1)) when available,
    // otherwise fall back to the fields Map (non-StatRuntime instances).
    inline def resolveField(inst: Value.InstanceV): Value | Null =
      val arr = inst.fieldsArr
      if arr != null then
        val fo = interp.typeFieldOrder.getOrElse(inst.typeName, Nil)
        val idx = fo.indexOf(name)
        if idx >= 0 && idx < arr.length then arr(idx) else null
      else inst.fields.getOrElse(name, null)
    // No-arg / native field access (must precede enum-companion check so plain
    // field values like IntV(3) are returned directly instead of being "called")
    if args.isEmpty then
      val fieldV: Value | Null = recv match
        case inst: Value.InstanceV => resolveField(inst)
        case _                     => fields.getOrElse(name, null)
      fieldV match
        case null =>
          // An inherited concrete method from a parent trait/class — `e.kind` where
          // `kind` is defined on a sealed supertype (busi seq-121).  Fields shadow
          // inherited methods (checked above), so this only fires when no field matched.
          val inherited = recv match
            case inst: Value.InstanceV => lookupTypeMethod(inst.typeName, name, interp)
            case _                     => null
          if inherited != null && inherited.params.isEmpty then
            invokeTypeMethod(inherited, recv, fields, Nil, interp)
          else if name == "toString" then Pure(Value.StringV(Value.show(recv)))
          else
            val ext = extensionDispatch(recv, name, Nil, env, interp)
            if ext != null then ext else interp.located(s"No field '$name'")
        case f: Value.FunV      if f.params.isEmpty => interp.callFun(f, Nil)
        case f: Value.NativeFnV                     => f.f(Nil)
        case v                                      => Pure(v)
    // Enum companion call (Color.RGB(1,2,3)) — only when args are present
    else
      val fieldV: Value | Null = recv match
        case inst: Value.InstanceV => resolveField(inst)
        case _                     => fields.getOrElse(name, null)
      if fieldV != null then
        interp.callValue(fieldV, args, env)
      else
        val inherited = recv match
          case inst: Value.InstanceV => lookupTypeMethod(inst.typeName, name, interp)
          case _                     => null
        if inherited != null then
          invokeTypeMethod(inherited, recv, fields, args, interp)
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

  /** Decimal⊕Double is a deliberate error (exact-numerics §4.3): mixing exact
   *  decimal with inexact binary float silently loses precision. Convert
   *  explicitly with `.toDouble` or `.toDecimal`. */
  private def decimalDoubleMix(op: String): InterpretError =
    InterpretError(s"cannot mix Decimal and Double in '$op' — convert explicitly (.toDouble or .toDecimal)")

  def infix(lhs: Value, op: String, args: List[Value], env: Env, interp: Interpreter): Computation =
    val rhs = args match
      case Nil      => Value.UnitV
      case v :: Nil => v
      case _        => Value.TupleV(args)   // `a op (b, c)` — scalameta gives 2 args, Scala semantics say it's a tuple
    infix2(lhs, op, rhs, env, interp)

  /** Tuple-free fast path for primitive Int/Double arithmetic and ordering.
   *  Uses nested `lhs match → rhs match` rather than `(lhs, rhs) match` so the
   *  hot path allocates no Tuple2 per operation. Returns null for any operand
   *  or operator combination it does not handle, so callers fall through to the
   *  full dispatch below. Deliberately excludes `==`/`!=`/`&&`/`||` so equality
   *  and short-circuit semantics remain owned by the general path unchanged. */
  private[interpreter] def numericFast(lhs: Value, op: String, rhs: Value): Computation | Null =
    lhs match
      case Value.IntV(a) => rhs match
        case Value.IntV(b) => op match
          case "+"  => Computation.pureIntV(a + b)
          case "-"  => Computation.pureIntV(a - b)
          case "*"  => Computation.pureIntV(a * b)
          case "/"  => Computation.pureIntV(a / b)
          case "%"  => Computation.pureIntV(a % b)
          case "<"  => Computation.pureBool(a < b)
          case ">"  => Computation.pureBool(a > b)
          case "<=" => Computation.pureBool(a <= b)
          case ">=" => Computation.pureBool(a >= b)
          case _    => null
        case Value.DoubleV(b) => op match
          case "+"  => Pure(Value.doubleV(a + b))
          case "-"  => Pure(Value.doubleV(a - b))
          case "*"  => Pure(Value.doubleV(a * b))
          case "/"  => Pure(Value.doubleV(a / b))
          case "<"  => Computation.pureBool(a.toDouble < b)
          case ">"  => Computation.pureBool(a.toDouble > b)
          case "<=" => Computation.pureBool(a.toDouble <= b)
          case ">=" => Computation.pureBool(a.toDouble >= b)
          case _    => null
        case _ => null
      case Value.DoubleV(a) => rhs match
        case Value.DoubleV(b) => op match
          case "+"  => Pure(Value.doubleV(a + b))
          case "-"  => Pure(Value.doubleV(a - b))
          case "*"  => Pure(Value.doubleV(a * b))
          case "/"  => Pure(Value.doubleV(a / b))
          case "<"  => Computation.pureBool(a < b)
          case ">"  => Computation.pureBool(a > b)
          case "<=" => Computation.pureBool(a <= b)
          case ">=" => Computation.pureBool(a >= b)
          case _    => null
        case Value.IntV(b) => op match
          case "+"  => Pure(Value.doubleV(a + b))
          case "-"  => Pure(Value.doubleV(a - b))
          case "*"  => Pure(Value.doubleV(a * b))
          case "/"  => Pure(Value.doubleV(a / b))
          case "<"  => Computation.pureBool(a < b.toDouble)
          case ">"  => Computation.pureBool(a > b.toDouble)
          case "<=" => Computation.pureBool(a <= b.toDouble)
          case ">=" => Computation.pureBool(a >= b.toDouble)
          case _    => null
        case _ => null
      // Char compares numerically with Char and Int (Scala: `'a' == 97` is true).
      // Mirrors the CharV block in `numericFastValue`; without it the general
      // infix path (`infix2`) fell to structural `lhs == rhs`, so a `Char == Int`
      // (as the self-hosted json-core scanner does: `source.charAt(i) == 91`)
      // wrongly returned false.
      case Value.CharV(a) => rhs match
        case Value.CharV(b) => op match
          case "+"  => Computation.pureIntV(a.toInt + b.toInt)
          case "-"  => Computation.pureIntV(a.toInt - b.toInt)
          case "*"  => Computation.pureIntV(a.toInt * b.toInt)
          case "/"  => Computation.pureIntV(a.toInt / b.toInt)
          case "%"  => Computation.pureIntV(a.toInt % b.toInt)
          case "<"  => Computation.pureBool(a < b)
          case ">"  => Computation.pureBool(a > b)
          case "<=" => Computation.pureBool(a <= b)
          case ">=" => Computation.pureBool(a >= b)
          case "==" => Computation.pureBool(a == b)
          case "!=" => Computation.pureBool(a != b)
          case _    => null
        case Value.IntV(b) => op match
          case "+"  => Computation.pureIntV(a.toInt + b)
          case "-"  => Computation.pureIntV(a.toInt - b)
          case "*"  => Computation.pureIntV(a.toInt * b)
          case "/"  => Computation.pureIntV(a.toInt / b)
          case "%"  => Computation.pureIntV(a.toInt % b)
          case "<"  => Computation.pureBool(a.toInt < b)
          case ">"  => Computation.pureBool(a.toInt > b)
          case "<=" => Computation.pureBool(a.toInt <= b)
          case ">=" => Computation.pureBool(a.toInt >= b)
          case "==" => Computation.pureBool(a.toInt == b)
          case "!=" => Computation.pureBool(a.toInt != b)
          case _    => null
        case _ => null
      case _ => null

  /** Value-returning twin of `numericFast`: same operand/operator coverage, but
   *  yields the raw `Value` (or null) with no Computation wrapper. Lets pure-body
   *  thunks (PatternRuntime.compileExpr) fold nested arithmetic entirely in
   *  Value-space without allocating a throwaway Pure per sub-expression. Kept as
   *  a parallel match (not delegated to/from `numericFast`) because routing the
   *  Computation path through here would allocate a redundant intermediate IntV
   *  for out-of-pool results — measurably regressing arithLoop. */
  private[interpreter] def numericFastValue(lhs: Value, op: String, rhs: Value): Value | Null =
    lhs match
      case Value.IntV(a) => rhs match
        case Value.IntV(b) => op match
          case "+"  => Value.intV(a + b)
          case "-"  => Value.intV(a - b)
          case "*"  => Value.intV(a * b)
          case "/"  => Value.intV(a / b)
          case "%"  => Value.intV(a % b)
          case "<"  => Value.boolV(a < b)
          case ">"  => Value.boolV(a > b)
          case "<=" => Value.boolV(a <= b)
          case ">=" => Value.boolV(a >= b)
          case _    => null
        case Value.DoubleV(b) => op match
          case "+"  => Value.doubleV(a + b)
          case "-"  => Value.doubleV(a - b)
          case "*"  => Value.doubleV(a * b)
          case "/"  => Value.doubleV(a / b)
          case "<"  => Value.boolV(a.toDouble < b)
          case ">"  => Value.boolV(a.toDouble > b)
          case "<=" => Value.boolV(a.toDouble <= b)
          case ">=" => Value.boolV(a.toDouble >= b)
          case _    => null
        case _ => null
      case Value.DoubleV(a) => rhs match
        case Value.DoubleV(b) => op match
          case "+"  => Value.doubleV(a + b)
          case "-"  => Value.doubleV(a - b)
          case "*"  => Value.doubleV(a * b)
          case "/"  => Value.doubleV(a / b)
          case "<"  => Value.boolV(a < b)
          case ">"  => Value.boolV(a > b)
          case "<=" => Value.boolV(a <= b)
          case ">=" => Value.boolV(a >= b)
          case _    => null
        case Value.IntV(b) => op match
          case "+"  => Value.doubleV(a + b)
          case "-"  => Value.doubleV(a - b)
          case "*"  => Value.doubleV(a * b)
          case "/"  => Value.doubleV(a / b)
          case "<"  => Value.boolV(a < b.toDouble)
          case ">"  => Value.boolV(a > b.toDouble)
          case "<=" => Value.boolV(a <= b.toDouble)
          case ">=" => Value.boolV(a >= b.toDouble)
          case _    => null
        case _ => null
      case Value.CharV(a) => rhs match
        case Value.CharV(b) => op match
          case "+"  => Value.intV(a.toInt + b.toInt)
          case "-"  => Value.intV(a.toInt - b.toInt)
          case "*"  => Value.intV(a.toInt * b.toInt)
          case "/"  => Value.intV(a.toInt / b.toInt)
          case "%"  => Value.intV(a.toInt % b.toInt)
          case "<"  => Value.boolV(a < b)
          case ">"  => Value.boolV(a > b)
          case "<=" => Value.boolV(a <= b)
          case ">=" => Value.boolV(a >= b)
          case "==" => Value.boolV(a == b)
          case "!=" => Value.boolV(a != b)
          case _    => null
        case Value.IntV(b) => op match
          case "+"  => Value.intV(a.toInt + b)
          case "-"  => Value.intV(a.toInt - b)
          case "*"  => Value.intV(a.toInt * b)
          case "/"  => Value.intV(a.toInt / b)
          case "%"  => Value.intV(a.toInt % b)
          case "<"  => Value.boolV(a.toInt < b)
          case ">"  => Value.boolV(a.toInt > b)
          case "<=" => Value.boolV(a.toInt <= b)
          case ">=" => Value.boolV(a.toInt >= b)
          case "==" => Value.boolV(a.toInt == b)
          case "!=" => Value.boolV(a.toInt != b)
          case _    => null
        case _ => null
      case _ => null

  /** Infix fast path: rhs already extracted. args is created lazily (rhs :: Nil) only in the
   *  fallback dispatch path, so arithmetic/comparison fast paths pay zero allocation. */
  def infix2(lhs: Value, op: String, rhs: Value, env: Env, interp: Interpreter): Computation =
    val nf = numericFast(lhs, op, rhs)
    if nf != null then return nf
    // Dispatch on op first — Scala 3 compiles this to a hashCode-based O(1) switch
    // rather than the previous O(N) linear scan through 40 tuple-match cases.
    op match
      case "+" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureIntV(a + b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Pure(Value.doubleV(a + b))
        case (Value.IntV(a),    Value.DoubleV(b)) => Pure(Value.doubleV(a + b))
        case (Value.DoubleV(a), Value.IntV(b))    => Pure(Value.doubleV(a + b))
        case (Value.BigIntV(a), Value.BigIntV(b)) => Pure(Value.BigIntV(a + b))
        case (Value.BigIntV(a), Value.IntV(b))    => Pure(Value.BigIntV(a + BigInt(b)))
        case (Value.IntV(a),    Value.BigIntV(b)) => Pure(Value.BigIntV(BigInt(a) + b))
        case (Value.DecimalV(a), Value.DecimalV(b)) => Pure(Value.DecimalV(a + b))
        case (Value.DecimalV(a), Value.IntV(b))     => Pure(Value.DecimalV(a + BigDecimal(b)))
        case (Value.IntV(a),     Value.DecimalV(b)) => Pure(Value.DecimalV(BigDecimal(a) + b))
        case (Value.DecimalV(a), Value.BigIntV(b))  => Pure(Value.DecimalV(a + BigDecimal(b)))
        case (Value.BigIntV(a),  Value.DecimalV(b)) => Pure(Value.DecimalV(BigDecimal(a) + b))
        case (Value.DecimalV(_), Value.DoubleV(_))  => throw decimalDoubleMix("+")
        case (Value.DoubleV(_),  Value.DecimalV(_)) => throw decimalDoubleMix("+")
        case (Value.StringV(a), b)                => Pure(Value.StringV(a + Value.show(b)))
        case _                                    => dispatch(lhs, op, rhs :: Nil, env, interp)
      case "-" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureIntV(a - b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Pure(Value.doubleV(a - b))
        case (Value.IntV(a),    Value.DoubleV(b)) => Pure(Value.doubleV(a - b))
        case (Value.DoubleV(a), Value.IntV(b))    => Pure(Value.doubleV(a - b))
        case (Value.BigIntV(a), Value.BigIntV(b)) => Pure(Value.BigIntV(a - b))
        case (Value.BigIntV(a), Value.IntV(b))    => Pure(Value.BigIntV(a - BigInt(b)))
        case (Value.IntV(a),    Value.BigIntV(b)) => Pure(Value.BigIntV(BigInt(a) - b))
        case (Value.DecimalV(a), Value.DecimalV(b)) => Pure(Value.DecimalV(a - b))
        case (Value.DecimalV(a), Value.IntV(b))     => Pure(Value.DecimalV(a - BigDecimal(b)))
        case (Value.IntV(a),     Value.DecimalV(b)) => Pure(Value.DecimalV(BigDecimal(a) - b))
        case (Value.DecimalV(a), Value.BigIntV(b))  => Pure(Value.DecimalV(a - BigDecimal(b)))
        case (Value.BigIntV(a),  Value.DecimalV(b)) => Pure(Value.DecimalV(BigDecimal(a) - b))
        case (Value.DecimalV(_), Value.DoubleV(_))  => throw decimalDoubleMix("-")
        case (Value.DoubleV(_),  Value.DecimalV(_)) => throw decimalDoubleMix("-")
        case _                                    => dispatch(lhs, op, rhs :: Nil, env, interp)
      case "*" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureIntV(a * b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Pure(Value.doubleV(a * b))
        case (Value.IntV(a),    Value.DoubleV(b)) => Pure(Value.doubleV(a * b))
        case (Value.DoubleV(a), Value.IntV(b))    => Pure(Value.doubleV(a * b))
        case (Value.BigIntV(a), Value.BigIntV(b)) => Pure(Value.BigIntV(a * b))
        case (Value.BigIntV(a), Value.IntV(b))    => Pure(Value.BigIntV(a * BigInt(b)))
        case (Value.IntV(a),    Value.BigIntV(b)) => Pure(Value.BigIntV(BigInt(a) * b))
        case (Value.DecimalV(a), Value.DecimalV(b)) => Pure(Value.DecimalV(a * b))
        case (Value.DecimalV(a), Value.IntV(b))     => Pure(Value.DecimalV(a * BigDecimal(b)))
        case (Value.IntV(a),     Value.DecimalV(b)) => Pure(Value.DecimalV(BigDecimal(a) * b))
        case (Value.DecimalV(a), Value.BigIntV(b))  => Pure(Value.DecimalV(a * BigDecimal(b)))
        case (Value.BigIntV(a),  Value.DecimalV(b)) => Pure(Value.DecimalV(BigDecimal(a) * b))
        case (Value.DecimalV(_), Value.DoubleV(_))  => throw decimalDoubleMix("*")
        case (Value.DoubleV(_),  Value.DecimalV(_)) => throw decimalDoubleMix("*")
        case (Value.StringV(a), Value.IntV(n))    => Pure(Value.StringV(a * n.toInt))
        case _                                    => dispatch(lhs, op, rhs :: Nil, env, interp)
      case "/" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureIntV(a / b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Pure(Value.doubleV(a / b))
        case (Value.IntV(a),    Value.DoubleV(b)) => Pure(Value.doubleV(a / b))
        case (Value.DoubleV(a), Value.IntV(b))    => Pure(Value.doubleV(a / b))
        case (Value.BigIntV(a), Value.BigIntV(b)) => Pure(Value.BigIntV(a / b))
        case (Value.BigIntV(a), Value.IntV(b))    => Pure(Value.BigIntV(a / BigInt(b)))
        case (Value.IntV(a),    Value.BigIntV(b)) => Pure(Value.BigIntV(BigInt(a) / b))
        case (Value.DecimalV(a), Value.DecimalV(b)) => Pure(Value.DecimalV(a / b))
        case (Value.DecimalV(a), Value.IntV(b))     => Pure(Value.DecimalV(a / BigDecimal(b)))
        case (Value.IntV(a),     Value.DecimalV(b)) => Pure(Value.DecimalV(BigDecimal(a) / b))
        case (Value.DecimalV(a), Value.BigIntV(b))  => Pure(Value.DecimalV(a / BigDecimal(b)))
        case (Value.BigIntV(a),  Value.DecimalV(b)) => Pure(Value.DecimalV(BigDecimal(a) / b))
        case (Value.DecimalV(_), Value.DoubleV(_))  => throw decimalDoubleMix("/")
        case (Value.DoubleV(_),  Value.DecimalV(_)) => throw decimalDoubleMix("/")
        case _                                    => dispatch(lhs, op, rhs :: Nil, env, interp)
      case "%" => (lhs, rhs) match
        case (Value.IntV(a), Value.IntV(b)) => Computation.pureIntV(a % b)
        case (Value.BigIntV(a), Value.BigIntV(b)) => Pure(Value.BigIntV(a % b))
        case (Value.BigIntV(a), Value.IntV(b))    => Pure(Value.BigIntV(a % BigInt(b)))
        case (Value.IntV(a),    Value.BigIntV(b)) => Pure(Value.BigIntV(BigInt(a) % b))
        case _                              => dispatch(lhs, op, rhs :: Nil, env, interp)
      case "==" => (lhs, rhs) match
        case (Value.BigIntV(a), Value.IntV(b))     => Computation.pureBool(a == BigInt(b))
        case (Value.IntV(a),    Value.BigIntV(b))  => Computation.pureBool(BigInt(a) == b)
        case (Value.DecimalV(a), Value.IntV(b))    => Computation.pureBool(a == BigDecimal(b))
        case (Value.IntV(a),     Value.DecimalV(b))=> Computation.pureBool(BigDecimal(a) == b)
        case (Value.DecimalV(a), Value.BigIntV(b)) => Computation.pureBool(a == BigDecimal(b))
        case (Value.BigIntV(a), Value.DecimalV(b)) => Computation.pureBool(BigDecimal(a) == b)
        // Cross-`Seq` equality: a `List` and a `Vector` compare element-wise, as in real Scala
        // (`Vector(1,2,3) == List(1,2,3)` is `true`). Same-type combos use the default below.
        // (collection-vector-indexed.)
        case (l: Value.ListV, v: Value.VectorV) => Computation.pureBool(l.items == v.items)
        case (v: Value.VectorV, l: Value.ListV) => Computation.pureBool(v.items == l.items)
        case _                                     => Computation.pureBool(lhs == rhs)
      case "!=" => (lhs, rhs) match
        case (Value.BigIntV(a), Value.IntV(b))     => Computation.pureBool(a != BigInt(b))
        case (Value.IntV(a),    Value.BigIntV(b))  => Computation.pureBool(BigInt(a) != b)
        case (Value.DecimalV(a), Value.IntV(b))    => Computation.pureBool(a != BigDecimal(b))
        case (Value.IntV(a),     Value.DecimalV(b))=> Computation.pureBool(BigDecimal(a) != b)
        case (Value.DecimalV(a), Value.BigIntV(b)) => Computation.pureBool(a != BigDecimal(b))
        case (Value.BigIntV(a), Value.DecimalV(b)) => Computation.pureBool(BigDecimal(a) != b)
        case (l: Value.ListV, v: Value.VectorV) => Computation.pureBool(l.items != v.items)
        case (v: Value.VectorV, l: Value.ListV) => Computation.pureBool(v.items != l.items)
        case _                                     => Computation.pureBool(lhs != rhs)
      case "<" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureBool(a < b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Computation.pureBool(a < b)
        case (Value.IntV(a),    Value.DoubleV(b)) => Computation.pureBool(a.toDouble < b)
        case (Value.DoubleV(a), Value.IntV(b))    => Computation.pureBool(a < b.toDouble)
        case (Value.StringV(a), Value.StringV(b)) => Computation.pureBool(a.compareTo(b) < 0)
        case (Value.BigIntV(a), Value.BigIntV(b)) => Computation.pureBool(a < b)
        case (Value.BigIntV(a), Value.IntV(b))    => Computation.pureBool(a < BigInt(b))
        case (Value.IntV(a),    Value.BigIntV(b)) => Computation.pureBool(BigInt(a) < b)
        case (Value.DecimalV(a), Value.DecimalV(b)) => Computation.pureBool(a < b)
        case (Value.DecimalV(a), Value.IntV(b))     => Computation.pureBool(a < BigDecimal(b))
        case (Value.IntV(a),     Value.DecimalV(b)) => Computation.pureBool(BigDecimal(a) < b)
        case (Value.DecimalV(a), Value.BigIntV(b))  => Computation.pureBool(a < BigDecimal(b))
        case (Value.BigIntV(a),  Value.DecimalV(b)) => Computation.pureBool(BigDecimal(a) < b)
        case _                                    => dispatch(lhs, op, rhs :: Nil, env, interp)
      case ">" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureBool(a > b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Computation.pureBool(a > b)
        case (Value.IntV(a),    Value.DoubleV(b)) => Computation.pureBool(a.toDouble > b)
        case (Value.DoubleV(a), Value.IntV(b))    => Computation.pureBool(a > b.toDouble)
        case (Value.StringV(a), Value.StringV(b)) => Computation.pureBool(a.compareTo(b) > 0)
        case (Value.BigIntV(a), Value.BigIntV(b)) => Computation.pureBool(a > b)
        case (Value.BigIntV(a), Value.IntV(b))    => Computation.pureBool(a > BigInt(b))
        case (Value.IntV(a),    Value.BigIntV(b)) => Computation.pureBool(BigInt(a) > b)
        case (Value.DecimalV(a), Value.DecimalV(b)) => Computation.pureBool(a > b)
        case (Value.DecimalV(a), Value.IntV(b))     => Computation.pureBool(a > BigDecimal(b))
        case (Value.IntV(a),     Value.DecimalV(b)) => Computation.pureBool(BigDecimal(a) > b)
        case (Value.DecimalV(a), Value.BigIntV(b))  => Computation.pureBool(a > BigDecimal(b))
        case (Value.BigIntV(a),  Value.DecimalV(b)) => Computation.pureBool(BigDecimal(a) > b)
        case _                                    => dispatch(lhs, op, rhs :: Nil, env, interp)
      case "<=" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureBool(a <= b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Computation.pureBool(a <= b)
        case (Value.IntV(a),    Value.DoubleV(b)) => Computation.pureBool(a.toDouble <= b)
        case (Value.DoubleV(a), Value.IntV(b))    => Computation.pureBool(a <= b.toDouble)
        case (Value.StringV(a), Value.StringV(b)) => Computation.pureBool(a.compareTo(b) <= 0)
        case (Value.BigIntV(a), Value.BigIntV(b)) => Computation.pureBool(a <= b)
        case (Value.BigIntV(a), Value.IntV(b))    => Computation.pureBool(a <= BigInt(b))
        case (Value.IntV(a),    Value.BigIntV(b)) => Computation.pureBool(BigInt(a) <= b)
        case (Value.DecimalV(a), Value.DecimalV(b)) => Computation.pureBool(a <= b)
        case (Value.DecimalV(a), Value.IntV(b))     => Computation.pureBool(a <= BigDecimal(b))
        case (Value.IntV(a),     Value.DecimalV(b)) => Computation.pureBool(BigDecimal(a) <= b)
        case (Value.DecimalV(a), Value.BigIntV(b))  => Computation.pureBool(a <= BigDecimal(b))
        case (Value.BigIntV(a),  Value.DecimalV(b)) => Computation.pureBool(BigDecimal(a) <= b)
        case _                                    => dispatch(lhs, op, rhs :: Nil, env, interp)
      case ">=" => (lhs, rhs) match
        case (Value.IntV(a),    Value.IntV(b))    => Computation.pureBool(a >= b)
        case (Value.DoubleV(a), Value.DoubleV(b)) => Computation.pureBool(a >= b)
        case (Value.IntV(a),    Value.DoubleV(b)) => Computation.pureBool(a.toDouble >= b)
        case (Value.DoubleV(a), Value.IntV(b))    => Computation.pureBool(a >= b.toDouble)
        case (Value.StringV(a), Value.StringV(b)) => Computation.pureBool(a.compareTo(b) >= 0)
        case (Value.BigIntV(a), Value.BigIntV(b)) => Computation.pureBool(a >= b)
        case (Value.BigIntV(a), Value.IntV(b))    => Computation.pureBool(a >= BigInt(b))
        case (Value.IntV(a),    Value.BigIntV(b)) => Computation.pureBool(BigInt(a) >= b)
        case (Value.DecimalV(a), Value.DecimalV(b)) => Computation.pureBool(a >= b)
        case (Value.DecimalV(a), Value.IntV(b))     => Computation.pureBool(a >= BigDecimal(b))
        case (Value.IntV(a),     Value.DecimalV(b)) => Computation.pureBool(BigDecimal(a) >= b)
        case (Value.DecimalV(a), Value.BigIntV(b))  => Computation.pureBool(a >= BigDecimal(b))
        case (Value.BigIntV(a),  Value.DecimalV(b)) => Computation.pureBool(BigDecimal(a) >= b)
        case _                                    => dispatch(lhs, op, rhs :: Nil, env, interp)
      case "&&" => (lhs, rhs) match
        case (Value.BoolV(a), Value.BoolV(b)) => Computation.pureBool(a && b)
        case _                                => dispatch(lhs, op, rhs :: Nil, env, interp)
      case "||" => (lhs, rhs) match
        case (Value.BoolV(a), Value.BoolV(b)) => Computation.pureBool(a || b)
        case _                                => dispatch(lhs, op, rhs :: Nil, env, interp)
      case "::" => rhs match
        case Value.ListV(ls) => Pure(Value.ListV(lhs :: ls))
        case _               => dispatch(lhs, op, rhs :: Nil, env, interp)
      case "++" => (lhs, rhs) match
        case (Value.ListV(a), Value.ListV(b)) =>
          if b.isEmpty then Pure(lhs)
          else if a.isEmpty then Pure(rhs)
          else Pure(Value.ListV(a ++ b))
        case (Value.SetV(a), Value.SetV(b))       => Pure(Value.SetV(a ++ b))
        case (Value.SetV(a), Value.ListV(b))      => Pure(Value.SetV(a ++ b.toSet))
        case (Value.MapV(a),    Value.MapV(b))    => Pure(Value.MapV(a ++ b))
        case (Value.TupleV(as), Value.TupleV(bs)) => Pure(Value.TupleV(as ++ bs))
        case (Value.TupleV(_),  Value.UnitV)      => Pure(lhs)
        case (Value.TupleV(as), _)                => Pure(Value.TupleV(as :+ rhs))
        case (Value.UnitV,      Value.TupleV(_))  => Pure(rhs)
        case (Value.UnitV,      Value.UnitV)      => Computation.PureUnit
        case (Value.UnitV,      _)                => Pure(rhs)
        case (_,                Value.TupleV(bs)) => Pure(Value.TupleV(lhs :: bs))
        case (_,                Value.UnitV)      => Pure(lhs)
        case _ =>
          val ext = extensionDispatch(lhs, "++", rhs :: Nil, env, interp)
          if ext != null then ext else Pure(Value.TupleV(lhs :: rhs :: Nil))
      case ":::" => (lhs, rhs) match
        case (Value.ListV(a), Value.ListV(b)) =>
          if b.isEmpty then Pure(lhs)
          else if a.isEmpty then Pure(rhs)
          else Pure(Value.ListV(a ++ b))
        case _ => dispatch(lhs, op, rhs :: Nil, env, interp)
      case ":+" => lhs match
        case Value.ListV(ls) => Pure(Value.ListV(ls :+ rhs))
        case _               => dispatch(lhs, op, rhs :: Nil, env, interp)
      case "+:" => rhs match
        case Value.ListV(ls) => Pure(Value.ListV(lhs +: ls))
        case _               => dispatch(lhs, op, rhs :: Nil, env, interp)
      case "->" => Pure(Value.TupleV(lhs :: rhs :: Nil))
      case "!" => lhs match
        case pid @ Value.InstanceV("Pid", _) => Perform("Actor", "send", pid :: rhs :: Nil)
        case _                               => dispatch(lhs, op, rhs :: Nil, env, interp)
      case ":=" => lhs match
        case inst @ Value.InstanceV("AttrKey", _) =>
          val effF = instanceFieldsMap(inst)
          val nv = effF.getOrElse("name", null)
          val name = if nv == null then "" else Value.show(nv)
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
        case (_, inst: Value.InstanceV) if inst.typeName.endsWith(suffix) =>
          val effF = instanceFieldsMap(inst)
          if effF.contains(method) then
            return interp.callValuePrepend(effF(method), recv, args, env)
        case _ =>
    null
