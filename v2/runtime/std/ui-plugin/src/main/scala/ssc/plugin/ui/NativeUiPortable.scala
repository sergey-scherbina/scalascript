package ssc.plugin.ui

import java.util.IdentityHashMap
import scala.collection.mutable
import ssc.{Show, Value}

private[ui] object NativeUiPortable:
  /** An externally immutable field vector whose private initialization hook lets
    * canonicalization preserve DataV -> MapV -> DataV cycles without retaining
    * any node from the caller's graph. */
  private final class PendingFields(size: Int) extends IndexedSeq[Value]:
    private val values = Array.fill[Value](size)(Value.UnitV)
    def length: Int = values.length
    def apply(index: Int): Value = values(index)
    private[NativeUiPortable] def initialize(index: Int, value: Value): Unit =
      values(index) = value

  private def childPath(path: String, key: Value, index: Int): String = key match
    case Value.StrV(name) => s"$path[\"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\"]"
    case _ => s"$path.<value:$index>"

  /** Validate before converting so closures remain the exact caller object.
    * A transitional host map reachable through a closure environment cannot be
    * copied without changing that closure's captured graph, and is rejected. */
  def canonical(value: Value, path: String): Value =
    val validated = IdentityHashMap[AnyRef, java.lang.Byte]()
    val closurePinned = IdentityHashMap[AnyRef, java.lang.Boolean]()
    var needsConversion = false

    def firstVisit(value: AnyRef, insideClosure: Boolean): Boolean =
      val bit: Byte = if insideClosure then 2 else 1
      val prior = Option(validated.get(value)).fold(0.toByte)(_.byteValue)
      if (prior & bit) != 0 then false
      else
        validated.put(value, java.lang.Byte.valueOf((prior | bit).toByte))
        true

    def validate(current: Value, at: String, insideClosure: Boolean): Unit = current match
      case Value.UnitV | _: Value.BoolV | _: Value.IntV | _: Value.BigV |
          _: Value.FloatV | _: Value.StrV | _: Value.BytesV | _: Value.DecimalV => ()
      case data @ Value.DataV(tag, fields) =>
        if insideClosure then closurePinned.put(data, java.lang.Boolean.TRUE)
        if firstVisit(data, insideClosure) then
          fields.zipWithIndex.foreach { case (field, index) =>
            validate(field, s"$at.$tag[$index]", insideClosure)
          }
      case closure: Value.ClosV =>
        if firstVisit(closure, insideClosure = true) then
          closure.env.zipWithIndex.foreach { case (captured, index) =>
            validate(captured, s"$at.<closure-env>[$index]", insideClosure = true)
          }
      case map: Value.MapV =>
        if insideClosure then closurePinned.put(map, java.lang.Boolean.TRUE)
        if firstVisit(map, insideClosure) then
          map.entries.iterator.zipWithIndex.foreach { case ((key, item), index) =>
            validate(key, s"$at.<key:$index>", insideClosure)
            validate(item, childPath(at, key, index), insideClosure)
          }
      case Value.ForeignV(hostMap: collection.Map[?, ?]) =>
        val host = hostMap.asInstanceOf[AnyRef]
        if insideClosure then
          throw new RuntimeException(s"$at contains a host map inside a closure environment; preserving closure identity forbids conversion")
        if firstVisit(host, insideClosure = false) then
          needsConversion = true
          hostMap.iterator.zipWithIndex.foreach { case ((key, item), index) =>
            (key, item) match
              case (portableKey: Value, portableValue: Value) =>
                validate(portableKey, s"$at.<key:$index>", insideClosure = false)
                validate(portableValue, childPath(at, portableKey, index), insideClosure = false)
              case _ =>
                throw new RuntimeException(s"$at contains a non-ScalaScript host map entry at index $index")
          }
      case Value.ForeignV(_) =>
        throw new RuntimeException(s"$at contains non-portable ForeignV")
      case _: Value.LongCellV | _: Value.DoubleCellV =>
        throw new RuntimeException(s"$at contains a target-specific mutable cell")

    validate(value, path, insideClosure = false)
    if !needsConversion then value
    else
      val converted = IdentityHashMap[AnyRef, Value]()

      def convert(current: Value, at: String): Value = current match
        case Value.UnitV | _: Value.BoolV | _: Value.IntV | _: Value.BigV |
            _: Value.FloatV | _: Value.StrV | _: Value.BytesV | _: Value.DecimalV => current
        case data @ Value.DataV(tag, fields) =>
          val prior = converted.get(data)
          if closurePinned.containsKey(data) then data
          else if prior != null then prior
          else
            val pending = PendingFields(fields.length)
            val copy = Value.DataV(tag, pending)
            converted.put(data, copy)
            fields.zipWithIndex.foreach { case (field, index) =>
              pending.initialize(index, convert(field, s"$at.$tag[$index]"))
            }
            copy
        case closure: Value.ClosV => closure
        case map: Value.MapV =>
          val prior = converted.get(map)
          if closurePinned.containsKey(map) then map
          else if prior != null then prior
          else
            val copy = Value.MapV.empty
            converted.put(map, copy)
            map.entries.iterator.zipWithIndex.foreach { case ((key, item), index) =>
              copy.entries(convert(key, s"$at.<key:$index>")) =
                convert(item, childPath(at, key, index))
            }
            copy
        case Value.ForeignV(hostMap: collection.Map[?, ?]) =>
          val host = hostMap.asInstanceOf[AnyRef]
          val prior = converted.get(host)
          if prior != null then prior
          else
            val copy = Value.MapV.empty
            converted.put(host, copy)
            hostMap.iterator.zipWithIndex.foreach { case ((key, item), index) =>
              val portableKey = key.asInstanceOf[Value]
              copy.entries(convert(portableKey, s"$at.<key:$index>")) =
                convert(item.asInstanceOf[Value], childPath(at, portableKey, index))
            }
            copy
        case Value.ForeignV(_) | _: Value.LongCellV | _: Value.DoubleCellV =>
          // The validation pass reports these with their stable path.
          throw new IllegalStateException(s"unvalidated NativeUi value at $at")

      convert(value, path)

  def stringMap(value: Value, path: String): Value.MapV =
    canonical(value, path) match
      case map: Value.MapV =>
        map.entries.keysIterator.foreach {
          case _: Value.StrV => ()
          case key => throw new RuntimeException(s"$path requires String keys, got ${Show.show(key)}")
        }
        map
      case other => throw new RuntimeException(s"$path expected Map[String, Value], got ${Show.show(other)}")

  def portableEquals(left: Value, right: Value): Boolean =
    val Active: Byte = 1
    val Equal: Byte = 2
    val Unequal: Byte = 3
    final case class Change(rights: IdentityHashMap[AnyRef, java.lang.Byte], right: AnyRef, prior: java.lang.Byte)

    val states = IdentityHashMap[AnyRef, IdentityHashMap[AnyRef, java.lang.Byte]]()
    val trail = mutable.ArrayBuffer.empty[Change]

    def status(a: AnyRef, b: AnyRef): Byte =
      val rights = states.get(a)
      if rights == null then 0 else Option(rights.get(b)).fold(0.toByte)(_.byteValue)

    def setStatus(a: AnyRef, b: AnyRef, next: Byte): Unit =
      var rights = states.get(a)
      if rights == null then
        rights = IdentityHashMap[AnyRef, java.lang.Byte]()
        states.put(a, rights)
      val prior = rights.get(b)
      trail += Change(rights, b, prior)
      rights.put(b, java.lang.Byte.valueOf(next))

    def rollback(mark: Int): Unit =
      while trail.length > mark do
        val change = trail.remove(trail.length - 1)
        if change.prior == null then change.rights.remove(change.right)
        else change.rights.put(change.right, change.prior)

    def composite(a: Value, b: Value)(compare: => Boolean): Boolean =
      val ar = a.asInstanceOf[AnyRef]
      val br = b.asInstanceOf[AnyRef]
      status(ar, br) match
        case Active | Equal => true
        case Unequal => false
        case _ =>
          setStatus(ar, br, Active)
          val result = compare
          setStatus(ar, br, if result then Equal else Unequal)
          result

    def loop(a: Value, b: Value): Boolean =
      if a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef] then true
      else (a, b) match
        case (Value.UnitV, Value.UnitV) => true
        case (Value.BoolV(x), Value.BoolV(y)) => x == y
        case (Value.IntV(x), Value.IntV(y)) => x == y
        case (Value.BigV(x), Value.BigV(y)) => x == y
        case (Value.FloatV(x), Value.FloatV(y)) =>
          java.lang.Double.doubleToLongBits(x) == java.lang.Double.doubleToLongBits(y)
        case (x: Value.DecimalV, y: Value.DecimalV) => x == y
        case (Value.StrV(x), Value.StrV(y)) => x == y
        case (Value.BytesV(x), Value.BytesV(y)) => x == y
        case (x: Value.ClosV, y: Value.ClosV) => x eq y
        case (x @ Value.DataV(tx, xs), y @ Value.DataV(ty, ys)) =>
          tx == ty && xs.length == ys.length && composite(x, y) {
            xs.indices.forall(index => loop(xs(index), ys(index)))
          }
        case (x: Value.MapV, y: Value.MapV) =>
          x.entries.size == y.entries.size && composite(x, y) {
            val leftEntries = x.entries.toVector
            val rightEntries = y.entries.toVector

            def matchEntry(index: Int, remaining: Vector[Int]): Boolean =
              if index == leftEntries.length then true
              else
                val (key, item) = leftEntries(index)
                remaining.exists { candidate =>
                  val mark = trail.length
                  val (otherKey, otherItem) = rightEntries(candidate)
                  val matched = loop(key, otherKey) && loop(item, otherItem) &&
                    matchEntry(index + 1, remaining.filterNot(_ == candidate))
                  if !matched then rollback(mark)
                  matched
                }

            matchEntry(0, rightEntries.indices.toVector)
          }
        case _ => false

    loop(left, right)
