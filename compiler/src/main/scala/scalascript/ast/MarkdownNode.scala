package scalascript.ast

import org.commonmark.node.{Node as CmNode, Text, Code, FencedCodeBlock, IndentedCodeBlock}

/** Opaque wrapper for org.commonmark.node.Node.
 *  Children are traversed via the commonmark linked-list API (getFirstChild / getNext).
 */
opaque type MarkdownNode = CmNode

object MarkdownNode:
  def apply(node: CmNode): MarkdownNode = node

  given ASTNode[MarkdownNode] with
    def kind(n: MarkdownNode): String = n.getClass.getSimpleName
    def children(n: MarkdownNode): List[MarkdownNode] =
      val buf = collection.mutable.ListBuffer.empty[CmNode]
      var cur = n.getFirstChild
      while cur != null do
        buf += cur
        cur = cur.getNext
      buf.toList
    override def text(n: MarkdownNode): Option[String] = n match
      case t: Text              => Some(t.getLiteral)
      case c: Code              => Some(c.getLiteral)
      case f: FencedCodeBlock   => Some(f.getLiteral)
      case i: IndentedCodeBlock => Some(i.getLiteral)
      case _                    => None
