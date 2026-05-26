package scalascript.typeddata

final case class VertexValue(id: String, labels: Set[String], properties: Map[String, JsonValue])

object VertexValue:
  def apply(id: String, label: String, properties: Map[String, JsonValue]): VertexValue =
    VertexValue(id, Set(label), properties)

final case class EdgeValue(id: Option[String], from: String, to: String, label: String, properties: Map[String, JsonValue])

enum RdfNode:
  case Iri(value: String)
  case Blank(id: String)
  case Literal(value: JsonValue)

final case class RdfTriple(subject: RdfNode, predicate: String, obj: RdfNode)
final case class RdfValue(subject: RdfNode, triples: Vector[RdfTriple])
