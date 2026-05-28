package scalascript.interpreter

/** Value ↔ JSON serialization for the distributed actor wire protocol.
 *
 *  Wire types:
 *    IntV(n)               → {"$t":"i","v":n}
 *    DoubleV(d)            → {"$t":"d","v":d}
 *    StringV(s)            → {"$t":"s","v":s}
 *    BoolV(b)              → {"$t":"b","v":b}
 *    UnitV                 → {"$t":"u"}
 *    ListV(vs)             → {"$t":"l","v":[...]}
 *    MapV(kvs)             → {"$t":"m","v":[[k,v],...]}
 *    TupleV(vs)            → {"$t":"tp","v":[...]}
 *    InstanceV("Pid",...)  → {"$t":"pid","n":nodeId,"id":localId}
 *    InstanceV(cls,fields) → {"$t":"o","cls":cls,"f":{...}}
 *    FunV / NativeFnV      → runtime error
 */
object ValueSerializer:

  def serialize(v: Value): String =
    val sb = new StringBuilder
    write(v, sb)
    sb.result()

  private def write(v: Value, sb: StringBuilder): Unit = v match
    case Value.IntV(n)    =>
      sb.append("{\"$t\":\"i\",\"v\":"); sb.append(n); sb.append("}")
    case Value.DoubleV(d) =>
      sb.append("{\"$t\":\"d\",\"v\":"); sb.append(d); sb.append("}")
    case Value.StringV(s) =>
      sb.append("{\"$t\":\"s\",\"v\":"); writeStr(s, sb); sb.append("}")
    case Value.BoolV(b)   =>
      sb.append("{\"$t\":\"b\",\"v\":"); sb.append(b); sb.append("}")
    case Value.UnitV      =>
      sb.append("{\"$t\":\"u\"}")
    case Value.ListV(vs)  =>
      sb.append("{\"$t\":\"l\",\"v\":[")
      writeSeq(vs, sb)
      sb.append("]}")
    case Value.MapV(kvs)  =>
      sb.append("{\"$t\":\"m\",\"v\":[")
      var first = true
      kvs.foreach { case (k, v2) =>
        if !first then sb.append(",")
        first = false
        sb.append("["); write(k, sb); sb.append(","); write(v2, sb); sb.append("]")
      }
      sb.append("]}")
    case Value.TupleV(vs) =>
      sb.append("{\"$t\":\"tp\",\"v\":[")
      writeSeq(vs, sb)
      sb.append("]}")
    case Value.InstanceV("Pid", fields) =>
      val nodeId  = fields.get("nodeId").collect { case Value.StringV(n) => n }.getOrElse("")
      val localId = fields.get("localId").collect { case Value.IntV(n) => n }.getOrElse(0L)
      sb.append("{\"$t\":\"pid\",\"n\":"); writeStr(nodeId, sb)
      sb.append(",\"id\":"); sb.append(localId); sb.append("}")
    case Value.InstanceV(cls, fields) =>
      sb.append("{\"$t\":\"o\",\"cls\":"); writeStr(cls, sb)
      sb.append(",\"f\":{")
      var first = true
      fields.foreach { case (k, v2) =>
        if !first then sb.append(",")
        first = false
        writeStr(k, sb); sb.append(":"); write(v2, sb)
      }
      sb.append("}}")
    case _: Value.FunV | _: Value.NativeFnV =>
      throw new RuntimeException("functions cannot be sent to remote nodes")
    case _ =>
      throw new RuntimeException(s"Cannot serialize value: $v")

  private def writeSeq(vs: List[Value], sb: StringBuilder): Unit =
    var first = true
    vs.foreach { v =>
      if !first then sb.append(",")
      first = false
      write(v, sb)
    }

  private def writeStr(s: String, sb: StringBuilder): Unit =
    sb.append('"')
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c    => sb.append(c)
    }
    sb.append('"')

  def deserialize(json: String): Value = fromParsed(JsonParser.parse(json))

  /** Convert a JsonParser-produced Value (MapV/ListV/primitives) back into
   *  the wire-encoded interpreter value using the "$t" type tag convention. */
  def fromParsed(v: Value): Value = v match
    case Value.MapV(m) =>
      def str(k: String) = m.get(Value.StringV(k)).collect { case Value.StringV(s) => s }
      def any(k: String) = m.get(Value.StringV(k))
      str("$t") match
        case None    => v  // not a wire-encoded value — return as-is
        case Some(t) => t match
          case "i"  => any("v") match
            case Some(Value.IntV(n))    => Value.IntV(n)
            case Some(Value.DoubleV(d)) => Value.IntV(d.toLong)
            case _                      => Value.IntV(0)
          case "d"  => any("v") match
            case Some(Value.DoubleV(d)) => Value.DoubleV(d)
            case Some(Value.IntV(n))    => Value.DoubleV(n.toDouble)
            case _                      => Value.DoubleV(0.0)
          case "s"  => Value.StringV(str("v").getOrElse(""))
          case "b"  => Value.BoolV(any("v").collect { case Value.BoolV(b) => b }.getOrElse(false))
          case "u"  => Value.UnitV
          case "l"  =>
            val items = any("v") match { case Some(Value.ListV(vs)) => vs; case _ => Nil }
            Value.ListV(items.map(fromParsed))
          case "m"  =>
            val pairs = any("v") match { case Some(Value.ListV(vs)) => vs; case _ => Nil }
            val kvs = pairs.collect {
              case Value.ListV(List(k, v2)) => (fromParsed(k), fromParsed(v2))
            }
            Value.MapV(kvs.toMap)
          case "tp" =>
            val items = any("v") match { case Some(Value.ListV(vs)) => vs; case _ => Nil }
            Value.TupleV(items.map(fromParsed))
          case "pid" =>
            val nodeId  = str("n").getOrElse("")
            val localId = any("id") match
              case Some(Value.IntV(n))    => n
              case Some(Value.DoubleV(d)) => d.toLong
              case _                      => 0L
            Value.InstanceV("Pid", Map("nodeId" -> Value.StringV(nodeId), "localId" -> Value.intV(localId)))
          case "o"  =>
            val cls = str("cls").getOrElse("")
            val fs  = any("f") match
              case Some(Value.MapV(fm)) =>
                fm.collect {
                  case (Value.StringV(k), v2) => k -> fromParsed(v2)
                }
              case _ => Map.empty[String, Value]
            Value.InstanceV(cls, fs)
          case _ => v
    case _ => v
