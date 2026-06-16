package scalascript.interpreter.vm.jit

import scalascript.interpreter.Value

/** Narrow helpers for JIT-emitted method calls on ref-valued expressions.
 *
 *  The helpers deliberately cover only pure numeric reads. Unsupported receiver
 *  or result shapes throw, and JitRuntime falls back to the interpreter. */
object JitRefDispatch:

  private def longValue(v: Value): Long = v match
    case Value.IntV(n) => n
    case other         => throw new ClassCastException(s"expected IntV, got ${other.getClass.getName}")

  private def instanceValue(inst: Value.InstanceV): Value =
    val arr = inst.fieldsArr
    if arr != null && arr.nonEmpty then arr(0)
    else inst.fields.getOrElse("value", Value.UnitV)

  def getOrElseLong(recv: AnyRef, defaultValue: Long): Long = recv match
    case opt: Value.OptionV =>
      val inner = opt.inner
      if inner == null then defaultValue else longValue(inner)
    case inst: Value.InstanceV if inst.typeName == "Right" =>
      longValue(instanceValue(inst))
    case inst: Value.InstanceV if inst.typeName == "Left" =>
      defaultValue
    case other =>
      throw new ClassCastException(s"getOrElseLong unsupported receiver: ${other.getClass.getName}")

  def sizeLong(recv: AnyRef): Long = recv match
    case Value.ListV(items)    => items.length.toLong
    case v: Value.VectorV      => v.items.length.toLong
    case a: Value.ArrayV       => a.items.length.toLong
    case Value.MapV(entries)   => entries.size.toLong
    case Value.SetV(items)     => items.size.toLong
    case Value.TupleV(elems)   => elems.length.toLong
    case Value.StringV(value)  => value.length.toLong
    case other =>
      throw new ClassCastException(s"sizeLong unsupported receiver: ${other.getClass.getName}")

  /** `seq(i)` indexed read → the element's Long. `VectorV`/`ArrayV` are O(1); `ListV` is O(i) but
   *  correct. Out-of-range or a non-numeric element / non-seq receiver throws → the JIT entry catches
   *  and the loop falls back to tree-walk. (jit-collection-ops.) */
  def seqIndexLong(recv: AnyRef, idx: Long): Long = recv match
    case v: Value.VectorV   => longValue(v.items(idx.toInt))
    case Value.ListV(items) => longValue(items(idx.toInt))
    case a: Value.ArrayV    => longValue(a.items(idx.toInt))
    case other =>
      throw new ClassCastException(s"seqIndexLong unsupported receiver: ${other.getClass.getName}")

  /** `seq(i)` indexed read → the element as a ref `Value` (for non-numeric elements). */
  def seqIndexRef(recv: AnyRef, idx: Long): Object = recv match
    case v: Value.VectorV   => v.items(idx.toInt).asInstanceOf[Object]
    case Value.ListV(items) => items(idx.toInt).asInstanceOf[Object]
    case a: Value.ArrayV    => a.items(idx.toInt).asInstanceOf[Object]
    case other =>
      throw new ClassCastException(s"seqIndexRef unsupported receiver: ${other.getClass.getName}")

  def headLong(recv: AnyRef): Long = recv match
    case Value.ListV(head :: _)  => longValue(head)
    case Value.TupleV(head :: _) => longValue(head)
    case other =>
      throw new ClassCastException(s"headLong unsupported receiver: ${other.getClass.getName}")

  def getOrElseRef(recv: AnyRef, defaultValue: Value): Object = recv match
    case opt: Value.OptionV =>
      val inner = opt.inner
      if inner == null then defaultValue.asInstanceOf[Object] else inner.asInstanceOf[Object]
    case inst: Value.InstanceV if inst.typeName == "Right" =>
      instanceValue(inst).asInstanceOf[Object]
    case inst: Value.InstanceV if inst.typeName == "Left" =>
      defaultValue.asInstanceOf[Object]
    case other =>
      throw new ClassCastException(s"getOrElseRef unsupported receiver: ${other.getClass.getName}")

  def mapGetOrElseRef(recv: AnyRef, key: Value, defaultValue: Value): Object = recv match
    case Value.MapV(entries) =>
      entries.getOrElse(key, defaultValue).asInstanceOf[Object]
    case other =>
      throw new ClassCastException(s"mapGetOrElseRef unsupported receiver: ${other.getClass.getName}")

  def mkStringRef(recv: AnyRef): Object = recv match
    case Value.ListV(items) =>
      Value.StringV(items.iterator.map(Value.show).mkString).asInstanceOf[Object]
    case other =>
      throw new ClassCastException(s"mkStringRef unsupported receiver: ${other.getClass.getName}")

  def mkStringRef(recv: AnyRef, sep: String): Object = recv match
    case Value.ListV(items) =>
      Value.StringV(items.iterator.map(Value.show).mkString(sep)).asInstanceOf[Object]
    case other =>
      throw new ClassCastException(s"mkStringRef unsupported receiver: ${other.getClass.getName}")

  def mkStringRef(recv: AnyRef, start: String, sep: String, end: String): Object = recv match
    case Value.ListV(items) =>
      Value.StringV(items.iterator.map(Value.show).mkString(start, sep, end)).asInstanceOf[Object]
    case other =>
      throw new ClassCastException(s"mkStringRef unsupported receiver: ${other.getClass.getName}")

  private def bigIntValue(v: Value): BigInt = v match
    case Value.IntV(n)     => BigInt(n)
    case Value.BigIntV(n)  => n
    case Value.StringV(s)  => BigInt(s.trim)
    case other             => throw new ClassCastException(s"expected BigInt-compatible value, got ${other.getClass.getName}")

  private def decimalValue(v: Value): BigDecimal = v match
    case Value.IntV(n)      => BigDecimal(n)
    case Value.BigIntV(n)   => BigDecimal(n)
    case Value.DecimalV(n)  => n
    case Value.StringV(s)   => BigDecimal(s.trim)
    case other              => throw new ClassCastException(s"expected Decimal-compatible value, got ${other.getClass.getName}")

  def bigIntRef(value: Value): Value =
    Value.BigIntV(bigIntValue(value))

  def decimalRef(value: Value): Value =
    Value.DecimalV(decimalValue(value))

  def decimalRef(value: Value, scale: Long): Value =
    Value.DecimalV(BigDecimal(bigIntValue(value), scale.toInt))

  def bigIntAbs(recv: Value): Value =
    Value.BigIntV(bigIntValue(recv).abs)

  def bigIntNegate(recv: Value): Value =
    Value.BigIntV(-bigIntValue(recv))

  def bigIntPow(recv: Value, exponent: Long): Value =
    Value.BigIntV(bigIntValue(recv).pow(exponent.toInt))

  def bigIntGcd(recv: Value, other: Value): Value =
    Value.BigIntV(bigIntValue(recv).gcd(bigIntValue(other)))

  def bigIntToDecimal(recv: Value): Value =
    Value.DecimalV(BigDecimal(bigIntValue(recv)))

  def decimalAbs(recv: Value): Value =
    Value.DecimalV(decimalValue(recv).abs)

  def decimalNegate(recv: Value): Value =
    Value.DecimalV(-decimalValue(recv))

  def decimalPow(recv: Value, exponent: Long): Value =
    Value.DecimalV(decimalValue(recv).pow(exponent.toInt))

  def decimalSetScale(recv: Value, scale: Long): Value =
    Value.DecimalV(BigDecimal(decimalValue(recv).bigDecimal.setScale(scale.toInt, java.math.RoundingMode.HALF_UP)))

  def decimalToBigInt(recv: Value): Value =
    Value.BigIntV(decimalValue(recv).toBigInt)

  // Stage 8: BigInt + Decimal infix arithmetic helpers.
  def bigIntPlus(recv: Value, other: Value): Value =
    Value.BigIntV(bigIntValue(recv) + bigIntValue(other))
  def bigIntMinus(recv: Value, other: Value): Value =
    Value.BigIntV(bigIntValue(recv) - bigIntValue(other))
  def bigIntTimes(recv: Value, other: Value): Value =
    Value.BigIntV(bigIntValue(recv) * bigIntValue(other))
  def bigIntDiv(recv: Value, other: Value): Value =
    Value.BigIntV(bigIntValue(recv) / bigIntValue(other))
  def bigIntMod(recv: Value, other: Value): Value =
    Value.BigIntV(bigIntValue(recv) % bigIntValue(other))

  def decimalPlus(recv: Value, other: Value): Value =
    Value.DecimalV(decimalValue(recv) + decimalValue(other))
  def decimalMinus(recv: Value, other: Value): Value =
    Value.DecimalV(decimalValue(recv) - decimalValue(other))
  def decimalTimes(recv: Value, other: Value): Value =
    Value.DecimalV(decimalValue(recv) * decimalValue(other))
  def decimalDiv(recv: Value, other: Value): Value =
    Value.DecimalV(decimalValue(recv) / decimalValue(other))

  // Stage 8: collection concat helpers — `xs ++ ys` and `m ++ n`.
  def listConcat(recv: AnyRef, other: AnyRef): Object = (recv, other) match
    case (Value.ListV(a), Value.ListV(b)) => Value.ListV(a ++ b).asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"listConcat unsupported: ${recv.getClass.getName}, ${other.getClass.getName}")

  def mapConcat(recv: AnyRef, other: AnyRef): Object = (recv, other) match
    case (Value.MapV(a), Value.MapV(b)) => Value.MapV(a ++ b).asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"mapConcat unsupported: ${recv.getClass.getName}, ${other.getClass.getName}")

  /** Generic ++ dispatch — picks List vs Map vs Tuple at runtime by receiver shape. */
  def collectionConcat(recv: AnyRef, other: AnyRef): Object = recv match
    case _: Value.ListV => listConcat(recv, other)
    case _: Value.MapV  => mapConcat(recv, other)
    case a: Value.TupleV =>
      // T3: tuple `++` concat — `(a, b) ++ (c, d)` → TupleV(a, b, c, d).
      val b = other.asInstanceOf[Value.TupleV]
      Value.TupleV(a.elems ++ b.elems).asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"collectionConcat unsupported receiver: ${recv.getClass.getName}")

  // T3: tuple literal `(a, b, …)` — build a TupleV from positional Values.
  def newTupleRef(fields: Array[Value]): Object =
    Value.TupleV(fields.toList).asInstanceOf[Object]

  // T3: numeric tuple element access `t._n` (1-based). Returns the element's raw
  // Long (IntV.x or double-bits) for the numeric JIT path.
  def tupleIntElem(t: AnyRef, idx1: Int): Long =
    t.asInstanceOf[Value.TupleV].elems(idx1 - 1) match
      case Value.IntV(x)    => x
      case Value.DoubleV(d) => java.lang.Double.doubleToRawLongBits(d)
      case Value.BoolV(b)   => if b then 1L else 0L
      case other            => throw new ClassCastException(s"tupleIntElem: non-numeric element $other")

  // Stage 8: extra collection methods — tail/init/headOption/last/isEmpty/nonEmpty.
  def tailRef(recv: AnyRef): Object = recv match
    case Value.ListV(_ :: rest) => Value.ListV(rest).asInstanceOf[Object]
    case Value.ListV(Nil)       => throw new NoSuchElementException("tail of empty List")
    case _ =>
      throw new ClassCastException(s"tailRef unsupported receiver: ${recv.getClass.getName}")

  def initRef(recv: AnyRef): Object = recv match
    case Value.ListV(items) =>
      if items.isEmpty then throw new NoSuchElementException("init of empty List")
      else Value.ListV(items.init).asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"initRef unsupported receiver: ${recv.getClass.getName}")

  def headOptionRef(recv: AnyRef): Object = recv match
    case Value.ListV(head :: _) => Value.OptionV(head).asInstanceOf[Object]
    case Value.ListV(Nil)       => Value.NoneV.asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"headOptionRef unsupported receiver: ${recv.getClass.getName}")

  def lastLong(recv: AnyRef): Long = recv match
    case Value.ListV(items) if items.nonEmpty => longValue(items.last)
    case Value.ListV(Nil)                     => throw new NoSuchElementException("last of empty List")
    case _ =>
      throw new ClassCastException(s"lastLong unsupported receiver: ${recv.getClass.getName}")

  def isEmptyLong(recv: AnyRef): Long = recv match
    case Value.ListV(items)    => if items.isEmpty then 1L else 0L
    case Value.MapV(entries)   => if entries.isEmpty then 1L else 0L
    case Value.SetV(items)     => if items.isEmpty then 1L else 0L
    case Value.StringV(value)  => if value.isEmpty then 1L else 0L
    case _ =>
      throw new ClassCastException(s"isEmptyLong unsupported receiver: ${recv.getClass.getName}")

  def nonEmptyLong(recv: AnyRef): Long =
    if isEmptyLong(recv) == 0L then 1L else 0L

  // Stage 8: Option / Map / collection-contains helpers.
  def isDefinedLong(recv: AnyRef): Long = recv match
    case opt: Value.OptionV => if opt.inner != null then 1L else 0L
    case _ =>
      throw new ClassCastException(s"isDefinedLong unsupported receiver: ${recv.getClass.getName}")

  def optionGetRef(recv: AnyRef): Object = recv match
    case opt: Value.OptionV if opt.inner != null => opt.inner.asInstanceOf[Object]
    case _: Value.OptionV =>
      throw new NoSuchElementException("Option.get on None")
    case _ =>
      throw new ClassCastException(s"optionGetRef unsupported receiver: ${recv.getClass.getName}")

  def optionGetLong(recv: AnyRef): Long = recv match
    case opt: Value.OptionV if opt.inner != null => longValue(opt.inner)
    case _: Value.OptionV =>
      throw new NoSuchElementException("Option.get on None")
    case _ =>
      throw new ClassCastException(s"optionGetLong unsupported receiver: ${recv.getClass.getName}")

  /** Map.contains(key) / List.contains(x) / Set.contains(x) / String.contains(substr). */
  def containsLong(recv: AnyRef, key: Value): Long = recv match
    case Value.MapV(entries)  => if entries.contains(key) then 1L else 0L
    case Value.SetV(items)    => if items.contains(key) then 1L else 0L
    case Value.ListV(items)   => if items.contains(key) then 1L else 0L
    case Value.StringV(value) =>
      key match
        case Value.StringV(s) => if value.contains(s) then 1L else 0L
        case _ => throw new ClassCastException(s"containsLong String receiver expects StringV key, got ${key.getClass.getName}")
    case _ =>
      throw new ClassCastException(s"containsLong unsupported receiver: ${recv.getClass.getName}")

  def mapKeysRef(recv: AnyRef): Object = recv match
    case Value.MapV(entries) => Value.ListV(entries.keys.toList).asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"mapKeysRef unsupported receiver: ${recv.getClass.getName}")

  def mapValuesRef(recv: AnyRef): Object = recv match
    case Value.MapV(entries) => Value.ListV(entries.values.toList).asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"mapValuesRef unsupported receiver: ${recv.getClass.getName}")

  // Stage 8: String methods — trim/toUpperCase/toLowerCase return StringV;
  // toInt/toLong return Long; indexOf returns Long.
  def stringTrimRef(recv: AnyRef): Object = recv match
    case Value.StringV(s) => Value.StringV(s.trim).asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"stringTrimRef unsupported receiver: ${recv.getClass.getName}")

  def stringUpperRef(recv: AnyRef): Object = recv match
    case Value.StringV(s) => Value.StringV(s.toUpperCase).asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"stringUpperRef unsupported receiver: ${recv.getClass.getName}")

  def stringLowerRef(recv: AnyRef): Object = recv match
    case Value.StringV(s) => Value.StringV(s.toLowerCase).asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"stringLowerRef unsupported receiver: ${recv.getClass.getName}")

  def stringToIntLong(recv: AnyRef): Long = recv match
    case Value.StringV(s) => s.trim.toInt.toLong
    case _ =>
      throw new ClassCastException(s"stringToIntLong unsupported receiver: ${recv.getClass.getName}")

  def stringToLongLong(recv: AnyRef): Long = recv match
    case Value.StringV(s) => s.trim.toLong
    case _ =>
      throw new ClassCastException(s"stringToLongLong unsupported receiver: ${recv.getClass.getName}")

  def stringIndexOfLong(recv: AnyRef, needle: AnyRef): Long = (recv, needle) match
    case (Value.StringV(s), Value.StringV(t)) => s.indexOf(t).toLong
    case _ =>
      throw new ClassCastException(s"stringIndexOfLong unsupported: ${recv.getClass.getName}, ${needle.getClass.getName}")

  def stringStartsWithLong(recv: AnyRef, prefix: AnyRef): Long = (recv, prefix) match
    case (Value.StringV(s), Value.StringV(p)) => if s.startsWith(p) then 1L else 0L
    case _ =>
      throw new ClassCastException(s"stringStartsWithLong unsupported: ${recv.getClass.getName}, ${prefix.getClass.getName}")

  def stringEndsWithLong(recv: AnyRef, suffix: AnyRef): Long = (recv, suffix) match
    case (Value.StringV(s), Value.StringV(suf)) => if s.endsWith(suf) then 1L else 0L
    case _ =>
      throw new ClassCastException(s"stringEndsWithLong unsupported: ${recv.getClass.getName}, ${suffix.getClass.getName}")

  // Stage 8: builtin collection constructors — Nil, List(...), Set(...), Map(...).
  // Each takes a varargs of Values; JIT emits a fresh Object[] and passes it in.
  def buildListRef(items: Array[Object]): Object =
    Value.ListV(items.iterator.map(_.asInstanceOf[Value]).toList).asInstanceOf[Object]

  def buildSetRef(items: Array[Object]): Object =
    Value.SetV(items.iterator.map(_.asInstanceOf[Value]).toSet).asInstanceOf[Object]

  // jit-collection-ops slice 2: Array(...) / Vector(...) builtin constructors. A local
  // `val a = Array(...)` is the receiver an in-place `a(i)=x` store mutates (ArrayV is the
  // only seq with reference identity + mutation), so the JIT must build a real ArrayV.
  def buildArrayRef(items: Array[Object]): Object =
    Value.ArrayV(items.map(_.asInstanceOf[Value])).asInstanceOf[Object]

  def buildVectorRef(items: Array[Object]): Object =
    Value.VectorV(items.iterator.map(_.asInstanceOf[Value]).toVector).asInstanceOf[Object]

  /** `a(idx) = value` in-place store on a mutable `ArrayV` (slice 2). Mirrors the tree-walker's
   *  `ArrayV.update` desugaring; the element is written as an `IntV` (the numeric JIT lane). A
   *  non-Array receiver throws → the JIT entry catches and the loop falls back to tree-walk. */
  def arrayUpdateLong(recv: AnyRef, idx: Long, value: Long): Unit = recv match
    case a: Value.ArrayV => a.items(idx.toInt) = Value.intV(value)
    case other =>
      throw new ClassCastException(s"arrayUpdateLong unsupported receiver: ${other.getClass.getName}")

  /** Map(...) expects a list of (key, value) Value.TupleV pairs. */
  def buildMapRef(items: Array[Object]): Object =
    val builder = scala.collection.immutable.Map.newBuilder[Value, Value]
    var i = 0
    while i < items.length do
      items(i).asInstanceOf[Value] match
        case Value.TupleV(k :: v :: Nil) => builder += (k -> v)
        case other => throw new ClassCastException(s"buildMapRef: not a (k, v) tuple — $other")
      i += 1
    Value.MapV(builder.result()).asInstanceOf[Object]

  val NilRef: Object = Value.EmptyList.asInstanceOf[Object]
  val EmptySetRef: Object = Value.SetV(Set.empty).asInstanceOf[Object]
  val EmptyMapRef: Object = Value.EmptyMap.asInstanceOf[Object]

  // T3: case-class / ADT constructor — build an InstanceV with positional fields.
  // Mirrors StatRuntime's positional-field representation (fieldsArr + fieldNames +
  // typeTag) so subsequent GETFI/GETFR field reads and tag-switch matches work
  // identically to a tree-walked instance. The JIT emits the field Values and the
  // interned name array; `tag` comes from Interpreter.typeTagMap (0 = unregistered).
  def newInstanceRef(typeName: String, tag: Int, fields: Array[Value], names: Array[String]): Object =
    val inst = Value.InstanceV(typeName, Map.empty)
    inst.fieldsArr  = fields
    inst.fieldNames = names
    inst.typeTag    = tag
    inst.asInstanceOf[Object]

  // Stage 8: BigInt/Decimal comparison ops → Long (0/1).
  def bigIntLt(recv: Value, other: Value): Long =
    if bigIntValue(recv) < bigIntValue(other) then 1L else 0L
  def bigIntLe(recv: Value, other: Value): Long =
    if bigIntValue(recv) <= bigIntValue(other) then 1L else 0L
  def bigIntGt(recv: Value, other: Value): Long =
    if bigIntValue(recv) > bigIntValue(other) then 1L else 0L
  def bigIntGe(recv: Value, other: Value): Long =
    if bigIntValue(recv) >= bigIntValue(other) then 1L else 0L

  def decimalLt(recv: Value, other: Value): Long =
    if decimalValue(recv) < decimalValue(other) then 1L else 0L
  def decimalLe(recv: Value, other: Value): Long =
    if decimalValue(recv) <= decimalValue(other) then 1L else 0L
  def decimalGt(recv: Value, other: Value): Long =
    if decimalValue(recv) > decimalValue(other) then 1L else 0L
  def decimalGe(recv: Value, other: Value): Long =
    if decimalValue(recv) >= decimalValue(other) then 1L else 0L

  // Stage 8: Decimal modulo (completing the Decimal infix arith set).
  def decimalMod(recv: Value, other: Value): Value =
    Value.DecimalV(decimalValue(recv) % decimalValue(other))

  // Stage 8: Map.get(key) → Option[Value]; None if absent.
  def mapGetRef(recv: AnyRef, key: Value): Object = recv match
    case Value.MapV(entries) =>
      entries.get(key) match
        case Some(v) => Value.OptionV(v).asInstanceOf[Object]
        case None    => Value.NoneV.asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"mapGetRef unsupported receiver: ${recv.getClass.getName}")

  // Stage 8: Map.updated(k, v) → new Map (immutable update).
  def mapUpdatedRef(recv: AnyRef, key: Value, value: Value): Object = recv match
    case Value.MapV(entries) => Value.MapV(entries.updated(key, value)).asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"mapUpdatedRef unsupported receiver: ${recv.getClass.getName}")

  // Stage 8: Map.getOrElse(key, default) returning Long for numeric value maps.
  def mapGetOrElseLong(recv: AnyRef, key: Value, default: Long): Long = recv match
    case Value.MapV(entries) =>
      entries.get(key) match
        case Some(Value.IntV(n))    => n
        case Some(Value.DoubleV(d)) => d.toLong
        case _                      => default
    case _ =>
      throw new ClassCastException(s"mapGetOrElseLong unsupported receiver: ${recv.getClass.getName}")

  // Stage 8: String methods — replace, substring, charAt.
  def stringReplaceRef(recv: AnyRef, oldPat: AnyRef, newPat: AnyRef): Object = (recv, oldPat, newPat) match
    case (Value.StringV(s), Value.StringV(o), Value.StringV(n)) =>
      Value.StringV(s.replace(o, n)).asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"stringReplaceRef unsupported: ${recv.getClass.getName}")

  def stringSubstringRef(recv: AnyRef, from: Long): Object = recv match
    case Value.StringV(s) => Value.StringV(s.substring(from.toInt)).asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"stringSubstringRef unsupported: ${recv.getClass.getName}")

  def stringSubstring2Ref(recv: AnyRef, from: Long, to: Long): Object = recv match
    case Value.StringV(s) => Value.StringV(s.substring(from.toInt, to.toInt)).asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"stringSubstring2Ref unsupported: ${recv.getClass.getName}")

  def stringCharAtLong(recv: AnyRef, idx: Long): Long = recv match
    case Value.StringV(s) => s.charAt(idx.toInt).toLong
    case _ =>
      throw new ClassCastException(s"stringCharAtLong unsupported: ${recv.getClass.getName}")

  // Stage 8: String.split(sep) → ListV[StringV].
  def stringSplitRef(recv: AnyRef, sep: AnyRef): Object = (recv, sep) match
    case (Value.StringV(s), Value.StringV(t)) =>
      val parts = s.split(java.util.regex.Pattern.quote(t), -1)
      var list: List[Value] = Nil
      var i = parts.length - 1
      while i >= 0 do { list = Value.StringV(parts(i)) :: list; i -= 1 }
      Value.ListV(list).asInstanceOf[Object]
    case _ =>
      throw new ClassCastException(s"stringSplitRef unsupported: ${recv.getClass.getName}, ${sep.getClass.getName}")

  // Stage 8: `.toString` on any ref Value — returns StringV via Value.show.
  def toStringRef(recv: AnyRef): Object = recv match
    case v: Value => Value.StringV(Value.show(v)).asInstanceOf[Object]
    case _        => Value.StringV(recv.toString).asInstanceOf[Object]
