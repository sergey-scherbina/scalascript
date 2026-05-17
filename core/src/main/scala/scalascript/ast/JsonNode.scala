package scalascript.ast

/** Opaque wrapper for ujson.Value (available via toolkit's upickle). */
opaque type JsonNode = ujson.Value

object JsonNode:
  def apply(v: ujson.Value): JsonNode = v

  given ASTNode[JsonNode] with
    def kind(n: JsonNode): String = n match
      case _: ujson.Obj  => "Object"
      case _: ujson.Arr  => "Array"
      case _: ujson.Str  => "String"
      case _: ujson.Num  => "Number"
      case _: ujson.Bool => "Boolean"
      case ujson.Null    => "Null"
    def children(n: JsonNode): List[JsonNode] = n match
      case o: ujson.Obj => o.value.values.toList
      case a: ujson.Arr => a.value.toList
      case _            => Nil
    override def text(n: JsonNode): Option[String] = n match
      case s: ujson.Str  => Some(s.value)
      case n: ujson.Num  => Some(n.value.toString)
      case b: ujson.Bool => Some(b.value.toString)
      case ujson.Null    => Some("null")
      case _             => None
