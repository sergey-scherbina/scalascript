package scalascript.transform

import scalascript.ast
import scalascript.ir
import scalascript.parser.Parser

/** Reverse of `Normalize` — rebuilds an `ast.Module` from an
 *  `ir.NormalizedModule`.
 *
 *  Stage 5 transitional utility: backend adapters receive IR per the
 *  SPI signature but the existing codegens (`JvmGen`, `JsGen`,
 *  `ScalaJsBackend`, `Interpreter`) consume `ast.Module` with parsed
 *  scalameta trees.  Until those codegens migrate to IR-native
 *  traversal (post-Stage 5), the adapter calls `Denormalize` to
 *  reconstruct the AST view.
 *
 *  Shape changes vs `Normalize`:
 *
 *    - `ir.Content.CodeBlock(source, _, span)` → re-parsed via
 *      `Parser.parseScalaSource` to recover the scalameta tree.
 *      `body` (the placeholder `List[IrExpr]`) is ignored — Stage 3+
 *      will fill it; codegens still use the scalameta tree.
 *
 *    - `ir.Content.EmbeddedBlock(lang, source, span)` →
 *      `ast.Content.CodeBlock(lang, source, None, span)` — foreign
 *      blocks keep no scalameta tree (they aren't Scala dialect).
 *
 *  Performance: a re-parse on every compile() invocation.  Acceptable
 *  for Stage 5; the post-Stage-5 cleanup removes both Normalize and
 *  Denormalize when codegens consume IR directly. */
object Denormalize:

  def apply(m: ir.NormalizedModule): ast.Module =
    ast.Module(
      manifest = m.manifest.map(manifest),
      sections = m.sections.map(section),
      span     = m.span.map(span)
    )

  private def manifest(m: ir.Manifest): ast.Manifest =
    ast.Manifest(
      name         = m.name,
      version      = m.version,
      description  = m.description,
      dependencies = m.dependencies,
      exports      = m.exports,
      targets      = m.targets,
      routes       = m.routes.map(routeDecl),
      pkg          = m.pkg,
      translations = Map.empty,
      raw          = Map.empty,
      span         = m.span.map(span)
    )

  private def routeDecl(r: ir.RouteDecl): ast.RouteDecl =
    ast.RouteDecl(r.method, r.path, r.handler, r.span.map(span))

  private def section(s: ir.Section): ast.Section =
    ast.Section(
      heading     = heading(s.heading),
      content     = s.content.map(content),
      subsections = s.subsections.map(section),
      span        = s.span.map(span)
    )

  private def heading(h: ir.Heading): ast.Heading =
    ast.Heading(h.level, h.text, h.span.map(span))

  private def content(c: ir.Content): ast.Content = c match
    case ir.Content.Prose(text, sp) =>
      ast.Content.Prose(text, sp.map(span))
    case ir.Content.CodeBlock(source, _, sp) =>
      val tree = Parser.parseScalaSource(source)
      ast.Content.CodeBlock(ast.Lang.ScalaScript, source, tree, sp.map(span))
    case ir.Content.EmbeddedBlock(language, source, sp) =>
      ast.Content.CodeBlock(language, source, None, sp.map(span))
    case ir.Content.Import(path, bindings, sp) =>
      ast.Content.Import(path, bindings.map(importBinding), sp.map(span))
    case ir.Content.DataList(items, ordered, sp) =>
      ast.Content.DataList(items.map(listItem), ordered, sp.map(span))

  private def importBinding(b: ir.ImportBinding): ast.ImportBinding =
    ast.ImportBinding(b.name, b.alias, b.span.map(span))

  private def listItem(i: ir.ListItem): ast.ListItem =
    ast.ListItem(i.content, i.nested.map(listItem), i.span.map(span))

  private def span(s: ir.Span): ast.Span =
    ast.Span(position(s.start), position(s.end))

  private def position(p: ir.Position): ast.Position =
    ast.Position(p.line, p.column, p.offset)
