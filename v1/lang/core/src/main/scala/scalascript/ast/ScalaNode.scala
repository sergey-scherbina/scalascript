package scalascript.ast

/** Lazy wrapper for a scala.meta.Tree.
 *
 *  Constructed eagerly via `ScalaNode(tree)` when the tree is already parsed
 *  (e.g. Parser output), or lazily via `ScalaNode.deferred { ... }` when
 *  the parse should be deferred until first access (`.sscc` v3 read path).
 *
 *  Thread-safe: `lazy val` uses lock-free atomic init in Scala 3.
 *  On force failure (invariant violation: write-time tokenize succeeded but
 *  read-time parse failed), `tree` throws `IllegalStateException`.
 */
final class ScalaNode private (init: () => scala.meta.Tree):
  lazy val tree: scala.meta.Tree = init()

object ScalaNode:
  def apply(t: scala.meta.Tree): ScalaNode = new ScalaNode(() => t)

  /** Deferred constructor: parse is called on first `.tree` access.
   *  Mirrors the Parser's two-pass strategy: `parse[Source]` first, then
   *  `parse[Term]` (wrapped in braces) for script-mode blocks that contain
   *  top-level expressions alongside definitions.
   *  Throws `IllegalStateException` if both passes fail — format invariant
   *  violation (write-time tokenize succeeded ⟹ read-time parse must succeed). */
  def deferred(src: String): ScalaNode =
    new ScalaNode(() =>
      import scala.meta.*
      // Apply the placeholder type-lambda desugar (`type X = Map[Int, _]` →
      // `[A] =>> Map[Int, A]`) so a `.sscc`-cached block matches the direct
      // `Parser` parse. The token stream stores the placeholder source verbatim
      // (the desugar is a tree rewrite, not a string preprocessor), so a raw parse
      // alone would lose the type-lambda meaning. Native `=>>` round-trips already.
      val desugar = scalascript.parser.Parser.desugarTypeLambdaAliases
      dialects.Scala3(Input.VirtualFile("<sscc>", src)).parse[Source] match
        case Parsed.Success(t) => desugar(t)
        case _: Parsed.Error =>
          s"{\n$src\n}".parse[Term] match
            case Parsed.Success(t) => desugar(t)
            case Parsed.Error(_, msg, _) =>
              throw new IllegalStateException(
                s"sscc v3 lazy parse failed (format invariant violated): $msg")
    )

  /** Escape hatch for components that need direct scalameta access (e.g. interpreter).
   *  Prefer ASTNode[ScalaNode] for structural traversal. */
  def fold[A](n: ScalaNode)(f: scala.meta.Tree => A): A = f(n.tree)

  given ASTNode[ScalaNode] with
    def kind(n: ScalaNode): String              = n.tree.productPrefix
    def children(n: ScalaNode): List[ScalaNode] = n.tree.children.map(ScalaNode.apply).toList
    override def span(n: ScalaNode): Option[Span] =
      val p = n.tree.pos
      if p.isEmpty then None
      else Some(Span(
        Position(p.startLine, p.startColumn, p.start),
        Position(p.endLine,   p.endColumn,   p.end)
      ))
