package scalascript.wire

/** Canonical runtime boundary type for all ScalaScript wire protocols.
 *
 *  Not the final user-facing typed-data model — typed APIs use
 *  `WireCodec[A]` to/from `WireValue`. Untyped actor messages and
 *  generated bridge code operate on `WireValue` directly.
 *
 *  Spec: docs/distributed-wire-protocol.md §Canonical Model */
enum WireValue:
  case Null
  case Unit
  case Bool(value: Boolean)
  case Int64(value: Long)
  case Float64(value: Double)
  case Str(value: String)
  case Bytes(value: Array[Byte])
  case Lst(values: Vector[WireValue])
  case Map(entries: Vector[(WireValue, WireValue)])
  case Object(typeName: String, fields: Vector[(String, WireValue)])
  case Tuple(values: Vector[WireValue])
  case Enum(typeName: String, caseName: String, value: Option[WireValue])
  case Pid(nodeId: String, localId: Long)
  case Error(code: String, message: String, details: Option[WireValue])

object WireValue:
  val True:  WireValue = Bool(true)
  val False: WireValue = Bool(false)

  def fromBoolean(b: Boolean): WireValue = if b then True else False
  def fromInt(n: Long): WireValue        = Int64(n)
  def fromDouble(d: Double): WireValue   = Float64(d)
  def fromString(s: String): WireValue   = Str(s)
  def fromBytes(b: Array[Byte]): WireValue = Bytes(b)

  def fromList(values: WireValue*): WireValue     = Lst(values.toVector)
  def fromSeq(values: Seq[WireValue]): WireValue  = Lst(values.toVector)
  def fromMap(entries: (WireValue, WireValue)*): WireValue = Map(entries.toVector)

  def kindOf(v: WireValue): String = v match
    case Null          => "null"
    case Unit          => "unit"
    case Bool(_)       => "bool"
    case Int64(_)      => "int64"
    case Float64(_)    => "float64"
    case Str(_)        => "string"
    case Bytes(_)      => "bytes"
    case Lst(_)        => "list"
    case Map(_)        => "map"
    case Object(t, _)  => s"object($t)"
    case Tuple(_)      => "tuple"
    case Enum(t, c, _) => s"enum($t.$c)"
    case Pid(_, _)     => "pid"
    case Error(c, _, _)=> s"error($c)"
