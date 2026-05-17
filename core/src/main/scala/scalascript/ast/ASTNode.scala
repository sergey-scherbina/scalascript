package scalascript.ast

/** Minimal typeclass for any language's AST node.
 *
 *  `kind`     — discriminator string (class name, tag, etc.)
 *  `children` — direct child nodes (same type, homogeneous tree)
 *  `text`     — leaf text content, if any
 *  `span`     — source location, if available
 *
 *  Add new languages by providing a `given ASTNode[YourNode]`.
 *  No changes to this file needed.
 */
trait ASTNode[N]:
  def kind(n: N): String
  def children(n: N): List[N]
  def text(n: N): Option[String] = None
  def span(n: N): Option[Span]   = None

object ASTNode:
  def apply[N](using ev: ASTNode[N]): ASTNode[N] = ev

  extension [N: ASTNode](n: N)
    def kind: String          = ASTNode[N].kind(n)
    def children: List[N]     = ASTNode[N].children(n)
    def text: Option[String]  = ASTNode[N].text(n)
    def span: Option[Span]    = ASTNode[N].span(n)
    def isLeaf: Boolean       = ASTNode[N].children(n).isEmpty
