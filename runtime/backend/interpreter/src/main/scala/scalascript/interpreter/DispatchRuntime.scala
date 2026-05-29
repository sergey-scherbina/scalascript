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
          val parts = s.split(java.util.regex.Pattern.quote(sep))
          Pure(Value.ListV(parts.iterator.map(Value.StringV(_)).toList))
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
            case Value.ListV(items) => Value.StringV(items.iterator.map(Value.show).mkString)
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
          // Direct loop: avoids building intermediate ListV via mapSequenceStr.
          def loop(i: Int): Computation =
            if i >= s.length then Computation.PureUnit
            else interp.callValue1(f, Value.CharV(s.charAt(i)), env).flatMap(_ => loop(i + 1))
          loop(0)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "filter"      => args match
        case List(f) =>
          val sb = new java.lang.StringBuilder(s.length)
          def loop(i: Int): Computation =
            if i >= s.length then Pure(if sb.length == s.length then recv else Value.StringV(sb.toString))
            else interp.callValue1(f, Value.CharV(s.charAt(i)), env).flatMap {
              case Value.BoolV(true) => sb.append(s.charAt(i)); loop(i + 1)
              case _                 => loop(i + 1)
            }
          loop(0)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "count"       => args match
        case List(f) =>
          def loop(i: Int, acc: Long): Computation =
            if i >= s.length then Computation.pureIntV(acc)
            else interp.callValue1(f, Value.CharV(s.charAt(i)), env).flatMap {
              case Value.BoolV(true) => loop(i + 1, acc + 1L)
              case _                 => loop(i + 1, acc)
            }
          loop(0, 0L)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "exists"      => args match
        case List(f) =>
          def loop(i: Int): Computation =
            if i >= s.length then Computation.PureFalse
            else interp.callValue1(f, Value.CharV(s.charAt(i)), env).flatMap {
              case Value.BoolV(true) => Computation.PureTrue
              case _                 => loop(i + 1)
            }
          loop(0)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "forall"      => args match
        case List(f) =>
          def loop(i: Int): Computation =
            if i >= s.length then Computation.PureTrue
            else interp.callValue1(f, Value.CharV(s.charAt(i)), env).flatMap {
              case Value.BoolV(false) => Computation.PureFalse
              case _                  => loop(i + 1)
            }
          loop(0)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "toList"      =>
        val buf = new scala.collection.mutable.ArrayBuffer[Value](s.length)
        var i = 0
        while i < s.length do { buf += Value.CharV(s.charAt(i)); i += 1 }
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
        Pure(Value.ListV(parts.iterator.map(Value.StringV(_)).toList))
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
        while i < s.length do { buf += Value.TupleV(Value.CharV(s.charAt(i)) :: Value.intV(i.toLong) :: Nil); i += 1 }
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
      case "toUpper" | "toUpperCase" => Pure(Value.CharV(c.toUpper))
      case "toLower" | "toLowerCase" => Pure(Value.CharV(c.toLower))
      case "asDigit"        => Computation.pureIntV(c.asDigit.toLong)
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
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil => Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(Nil) :: Nil))
            case h :: rest =>
              interp.callValue1(f, h, env).flatMap {
                case Value.BoolV(true) => yesBuf += h; loop(rest)
                case _                 =>
                  Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(remaining) :: Nil))
              }
          loop(ls)
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
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil =>
              val resultMap = groups.iterator.map { (k, buf) => k -> Value.ListV(buf.toList) }.toMap
              Pure(Value.MapV(resultMap))
            case h :: rest =>
              interp.callValue1(kf, h, env).flatMap { k =>
                interp.callValue1(vf, h, env).flatMap { v =>
                  groups.getOrElseUpdate(k, new scala.collection.mutable.ArrayBuffer[Value]) += v
                  loop(rest)
                }
              }
          loop(ls)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "zipWithIndex" =>
        val ziBuf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
        var ziRem = ls; var ziIdx = 0
        while ziRem.nonEmpty do
          ziBuf += Value.TupleV(List(ziRem.head, Value.intV(ziIdx.toLong)))
          ziRem = ziRem.tail; ziIdx += 1
        Pure(Value.ListV(ziBuf.toList))
      case "indices"      =>
        val idxBuf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
        var idxI = 0
        while idxI < ls.length do { idxBuf += Value.intV(idxI.toLong); idxI += 1 }
        Pure(Value.ListV(idxBuf.toList))
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
                while i >= 0 do result2 = arr2(i)._1 :: result2; i -= 1
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
                while i >= 0 do result = arr(i)._1 :: result; i -= 1
                Value.ListV(result)
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
      case "partition"    => args match
        case List(f) =>
          val yesBuf = new scala.collection.mutable.ArrayBuffer[Value](ls.length / 2 + 1)
          val noBuf  = new scala.collection.mutable.ArrayBuffer[Value](ls.length / 2 + 1)
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil =>
              Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(noBuf.toList) :: Nil))
            case h :: rest =>
              interp.callValue1(f, h, env).flatMap {
                case Value.BoolV(true) => yesBuf += h; loop(rest)
                case _                 => noBuf  += h; loop(rest)
              }
          loop(ls)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "groupBy"      => args match
        case List(f) =>
          val groups = scala.collection.mutable.LinkedHashMap.empty[Value, scala.collection.mutable.ArrayBuffer[Value]]
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil =>
              val resultMap = groups.iterator.map { (k, buf) => k -> Value.ListV(buf.toList) }.toMap
              Pure(Value.MapV(resultMap))
            case h :: rest =>
              interp.callValue1(f, h, env).flatMap { k =>
                groups.getOrElseUpdate(k, new scala.collection.mutable.ArrayBuffer[Value]) += h
                loop(rest)
              }
          loop(ls)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "scanLeft"     => args match
        case List(init) =>
          Pure(Value.NativeFnV("scanLeft", {
            case List(f) =>
              val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length + 1)
              buf += init
              def loop(remaining: List[Value], acc: Value): Computation = remaining match
                case Nil       => Pure(Value.ListV(buf.toList))
                case h :: rest => interp.callValue2(f, acc, h, env).flatMap { v => buf += v; loop(rest, v) }
              loop(ls, init)
            case _ => throw InterpretError("scanLeft expects one function argument")
          }))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "collect"      => args match
        case List(f) =>
          val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
          def loop(remaining: List[Value]): Computation = remaining match
            case Nil => Pure(Value.ListV(buf.toList))
            case h :: rest =>
              interp.callValue1(f, h, env).flatMap {
                case Value.OptionV(Some(v)) => buf += v; loop(rest)
                case Value.OptionV(None)    => loop(rest)
                case v                      => buf += v; loop(rest)
              }
          loop(ls)
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
      case "foreach"   => args match
        case List(f) =>
          val it = m.iterator
          def loop(): Computation =
            if !it.hasNext then Computation.PureUnit
            else
              val (k, v) = it.next()
              interp.callValue1(f, Value.TupleV(List(k, v)), env).flatMap(_ => loop())
          loop()
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "mapValues" => args match
        case List(f) =>
          val it  = m.iterator
          val buf = scala.collection.mutable.Map.empty[Value, Value]
          def loop(): Computation =
            if !it.hasNext then Pure(Value.MapV(buf.toMap))
            else
              val (k, v) = it.next()
              interp.callValue1(f, v, env).flatMap { nv => buf += (k -> nv); loop() }
          loop()
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foldLeft"  => args match
        case List(init) =>
          Pure(Value.NativeFnV("foldLeft", {
            case List(f) =>
              val it = m.iterator
              def loop(acc: Value): Computation =
                if !it.hasNext then Pure(acc)
                else
                  val (k, v) = it.next()
                  interp.callValue2(f, acc, Value.TupleV(List(k, v)), env).flatMap(loop)
              loop(init)
            case _ => throw InterpretError("Map.foldLeft expects one function argument")
          }))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "exists"    => args match
        case List(f) =>
          val it = m.iterator
          def loop(): Computation =
            if !it.hasNext then Computation.PureFalse
            else
              val (k, v) = it.next()
              interp.callValue1(f, Value.TupleV(List(k, v)), env).flatMap {
                case Value.BoolV(true) => Computation.PureTrue
                case _                 => loop()
              }
          loop()
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "forall"    => args match
        case List(f) =>
          val it = m.iterator
          def loop(): Computation =
            if !it.hasNext then Computation.PureTrue
            else
              val (k, v) = it.next()
              interp.callValue1(f, Value.TupleV(List(k, v)), env).flatMap {
                case Value.BoolV(false) => Computation.PureFalse
                case _                  => loop()
              }
          loop()
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "count"     => args match
        case List(f) =>
          val it = m.iterator
          def loop(acc: Long): Computation =
            if !it.hasNext then Computation.pureIntV(acc)
            else
              val (k, v) = it.next()
              interp.callValue1(f, Value.TupleV(List(k, v)), env).flatMap {
                case Value.BoolV(true) => loop(acc + 1L)
                case _                 => loop(acc)
              }
          loop(0L)
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "find"      => args match
        case List(f) =>
          val it = m.iterator
          def loop(): Computation =
            if !it.hasNext then Computation.PureNone
            else
              val (k, v) = it.next()
              val entry = Value.TupleV(List(k, v))
              interp.callValue1(f, entry, env).flatMap {
                case Value.BoolV(true) => Pure(Value.OptionV(Some(entry)))
                case _                 => loop()
              }
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
            case Value.BoolV(true) => recv
            case _                 => Value.NoneV
          }
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "foreach"   => args match
        case List(f) => opt match
          case None    => Computation.PureUnit
          case Some(v) => interp.callValue1(f, v, env).flatMap(Computation.discardToUnit)
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
          case None    => Pure(Value.InstanceV("Left", Map("value" -> left)))
          case Some(v) => Pure(Value.InstanceV("Right", Map("value" -> v)))
        case _       => dispatchFallback(recv, name, args, env, interp)
      case "toLeft"    => args match
        case List(right) => opt match
          case None    => Pure(Value.InstanceV("Right", Map("value" -> right)))
          case Some(v) => Pure(Value.InstanceV("Left", Map("value" -> v)))
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
      case _ => extensionDispatch(recv, name, args, env, interp)
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
