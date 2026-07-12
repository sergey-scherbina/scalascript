package scalascript.uniml

enum Origin:
  case SourceBacked
  case Synthetic(reason: String)

final case class UniEdge(role: Option[String], child: UniNode)

enum UniNode:
  case Branch(kind: String, edges: Vector[UniEdge], span: SourceSpan, origin: Origin)
  case Token(value: SourceToken)

object UniNode:
  def sourceTokens(root: UniNode): Vector[SourceToken] =
    val result = Vector.newBuilder[SourceToken]
    var pending = List(root)
    while pending.nonEmpty do
      pending.head match
        case UniNode.Token(value) =>
          result += value
          pending = pending.tail
        case UniNode.Branch(_, edges, _, _) =>
          pending = edges.iterator.map(_.child).toList ::: pending.tail
    result.result()
