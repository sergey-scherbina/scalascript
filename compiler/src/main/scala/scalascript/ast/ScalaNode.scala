package scalascript.ast

/** Opaque wrapper for scala.meta.Tree.
 *  Outside this file the underlying type is invisible — only ASTNode[ScalaNode] is the interface.
 */
opaque type ScalaNode = scala.meta.Tree

object ScalaNode:
  def apply(tree: scala.meta.Tree): ScalaNode = tree

  given ASTNode[ScalaNode] with
    def kind(n: ScalaNode): String              = n.productPrefix
    def children(n: ScalaNode): List[ScalaNode] = n.children.toList
    override def span(n: ScalaNode): Option[Span] =
      val p = n.pos
      if p.isEmpty then None
      else Some(Span(
        Position(p.startLine, p.startColumn, p.start),
        Position(p.endLine,   p.endColumn,   p.end)
      ))
