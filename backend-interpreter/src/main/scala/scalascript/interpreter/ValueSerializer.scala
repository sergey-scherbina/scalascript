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

  def deserialize(json: String): Value = fromValue(JsonParser.parse(json))

  private def fromValue(v: Value): Value = v match
    case Value.InstanceV(_, fields) =>
      val t = fields.get("$t").collect { case Value.StringV(s) => s }.getOrElse("")
      t match
        case "i"  => Value.IntV(fields("v") match { case Value.IntV(n) => n; case Value.DoubleV(d) => d.toLong })
        case "d"  => Value.DoubleV(fields("v") match { case Value.DoubleV(d) => d; case Value.IntV(n) => n.toDouble })
        case "s"  => Value.StringV(fields("v") match { case Value.StringV(s) => s; case other => Value.show(other) })
        case "b"  => Value.BoolV(fields("v") match { case Value.BoolV(b) => b; case _ => false })
        case "u"  => Value.UnitV
        case "l"  =>
          val items = fields("v") match { case Value.ListV(vs) => vs; case _ => Nil }
          Value.ListV(items.map(fromValue))
        case "m"  =>
          val pairs = fields("v") match { case Value.ListV(vs) => vs; case _ => Nil }
          val kvs = pairs.collect {
            case Value.ListV(List(k, v2)) => (fromValue(k), fromValue(v2))
          }
          Value.MapV(kvs.toMap)
        case "tp" =>
          val items = fields("v") match { case Value.ListV(vs) => vs; case _ => Nil }
          Value.TupleV(items.map(fromValue))
        case "pid" =>
          val nodeId  = fields.get("n").collect { case Value.StringV(s) => s }.getOrElse("")
          val localId = fields.get("id") match
            case Some(Value.IntV(n))    => n
            case Some(Value.DoubleV(d)) => d.toLong
            case _                      => 0L
          Value.InstanceV("Pid", Map("nodeId" -> Value.StringV(nodeId), "localId" -> Value.IntV(localId)))
        case "o"  =>
          val cls    = fields.get("cls").collect { case Value.StringV(s) => s }.getOrElse("")
          val fs     = fields.get("f") match
            case Some(Value.InstanceV(_, fmap)) => fmap.map { case (k, v2) => k -> fromValue(v2) }
            case _                              => Map.empty[String, Value]
          Value.InstanceV(cls, fs)
        case _ => v
    case _ => v
