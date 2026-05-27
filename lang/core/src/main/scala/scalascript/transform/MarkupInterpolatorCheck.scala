package scalascript.transform

import scala.meta.*
import scalascript.ast
import scalascript.backend.spi.Diagnostic
import scalascript.markup.{PureMarkupCodec, Dialect}

/** Compile-time well-formedness checker for `xml"..."` string interpolators
 *  (v1.55.4).
 *
 *  Walks every scalascript `CodeBlock` in the module, uses its scalameta
 *  tree to locate `Term.Interpolate(Term.Name("xml"), parts, args)`
 *  occurrences, and validates the static structure of each interpolation:
 *
 *    1. The string parts are joined with a stand-in placeholder element
 *       (`<placeholder/>`) for each `${expr}` hole so the reconstructed
 *       candidate is valid XML when the hole values are well-formed.
 *    2. `PureMarkupCodec.parse` is called on the candidate string.
 *    3. A failed parse emits `Diagnostic.XmlParseError` with the
 *       parse-error message, line, and column.
 *    4. A successful parse continues normally (no diagnostic).
 *
 *  The placeholder strategy is intentionally conservative: if the static
 *  parts alone are not well-formed XML the diagnostic fires.  Dynamic
 *  holes (attribute values, text nodes) are replaced by a self-closing
 *  element so the reconstructed string remains structurally valid for
 *  common patterns:
 *
 *    xml"<root>${child}</root>"        → `<root><placeholder/></root>`  ✓
 *    xml"<a href=${url}/>"              → `<a href=<placeholder/>/>` which
 *                                          is not valid XML — the user should
 *                                          quote attribute values, so this
 *                                          correctly fires an error.
 *    xml"<root>"                        → `<root>` — not well-formed  ✗
 *    xml"<a></b>"                       → mismatched close tag          ✗
 *
 *  The check runs on the AST (before `Normalize`) so it can access the
 *  scalameta trees attached to each `CodeBlock`.  Blocks whose tree was
 *  not parsed (e.g. blocks with earlier parse errors) are skipped.
 */
object MarkupInterpolatorCheck:

  /** Placeholder element used in place of every `${expr}` hole when
   *  constructing the candidate XML string for parse validation. */
  private val Placeholder = "<placeholder/>"

  /** Walk `module` and return one `Diagnostic.XmlParseError` per
   *  malformed `xml"..."` interpolation found in any scalascript block. */
  def check(module: ast.Module): List[Diagnostic] =
    val diags = scala.collection.mutable.ListBuffer.empty[Diagnostic]
    module.sections.foreach(checkSection(_, diags))
    diags.toList

  private def checkSection(
    s:     ast.Section,
    diags: scala.collection.mutable.ListBuffer[Diagnostic]
  ): Unit =
    s.content.foreach(checkContent(_, diags))
    s.subsections.foreach(checkSection(_, diags))

  private def checkContent(
    c:     ast.Content,
    diags: scala.collection.mutable.ListBuffer[Diagnostic]
  ): Unit = c match
    case ast.Content.CodeBlock(lang, _, Some(tree), _, _, _, _)
        if ast.Lang.isScalaScript(lang) =>
      ast.ScalaNode.fold(tree)(t => collectInterpolations(t, diags))
    case _ => ()

  /** Walk a scalameta tree and check every `xml"..."` interpolation. */
  private def collectInterpolations(
    tree:  scala.meta.Tree,
    diags: scala.collection.mutable.ListBuffer[Diagnostic]
  ): Unit =
    tree.traverse {
      case Term.Interpolate(Term.Name("xml"), parts, _) =>
        checkInterpolation(parts, diags)
    }

  /** Build the candidate string from the string parts and validate it. */
  private def checkInterpolation(
    parts: List[scala.meta.Tree],
    diags: scala.collection.mutable.ListBuffer[Diagnostic]
  ): Unit =
    // parts has length (args.length + 1): [part0, part1, …, partN]
    // Between consecutive parts there is one arg placeholder.
    val sb = StringBuilder()
    parts.zipWithIndex.foreach { (part, i) =>
      sb.append(part.asInstanceOf[Lit.String].value)
      // After every part except the last, insert a placeholder for the hole.
      if i < parts.length - 1 then sb.append(Placeholder)
    }
    val candidate = sb.toString
    PureMarkupCodec.parse(candidate, Dialect.Xml1_0) match
      case Right(_) => ()
      case Left(err) =>
        diags += Diagnostic.XmlParseError(err.message, err.line, err.column)
