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
    def walk(pending: List[UniNode], result: Vector[SourceToken]): Vector[SourceToken] =
      if pending.isEmpty then result
      else pending.head match
        case UniNode.Token(value) =>
          walk(pending.tail, result :+ value)
        case UniNode.Branch(_, edges, _, _) =>
          walk(edges.map(_.child).toList ::: pending.tail, result)
    walk(List(root), Vector.empty)
