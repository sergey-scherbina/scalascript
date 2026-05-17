package scalascript.transform

import scalascript.ast
import scalascript.ir

/** AST → IR conversion.
 *
 *  Stage 2.1: near-no-op.  Copies the AST structure across with two
 *  shape changes:
 *
 *    1. `scalameta` trees are dropped — they're carried in
 *       `ast.Content.CodeBlock.tree` for in-process codegens but are not
 *       round-trippable through JSON.  Backends that need the tree
 *       re-parse from the `source` field.
 *
 *    2. Foreign-language fences (`html`, `css`, …) become
 *       `ir.Content.EmbeddedBlock` instead of `ir.Content.CodeBlock`.
 *       Distinguishes "host embedded language" (scalascript / ssc) from
 *       "blocks owned by a SourceLanguage plugin" (Stage 9 extraction).
 *
 *  Stages 3+ extend this: effect lowering populates
 *  `Perform`/`Handle`/`Resume` nodes inside `CodeBlock.body`; Stage 5
 *  rewrites `extern def` call sites to `ExternCall`.
 *
 *  The pass is intentionally pure data conversion.  No diagnostics, no
 *  failure modes — those layer on top in Stages 3-4. */
object Normalize:

  def apply(m: ast.Module): ir.NormalizedModule =
    ir.NormalizedModule(
      manifest = m.manifest.map(manifest),
      sections = m.sections.map(section),
      span     = m.span.map(span)
    )

  private def manifest(m: ast.Manifest): ir.Manifest =
    ir.Manifest(
      name         = m.name,
      version      = m.version,
      description  = m.description,
      dependencies = m.dependencies,
      exports      = m.exports,
      targets      = m.targets,
      routes       = m.routes.map(routeDecl),
      pkg          = m.pkg,
      span         = m.span.map(span)
    )

  private def routeDecl(r: ast.RouteDecl): ir.RouteDecl =
    ir.RouteDecl(r.method, r.path, r.handler, r.span.map(span))

  private def section(s: ast.Section): ir.Section =
    ir.Section(
      heading     = heading(s.heading),
      content     = s.content.map(content),
      subsections = s.subsections.map(section),
      span        = s.span.map(span)
    )

  private def heading(h: ast.Heading): ir.Heading =
    ir.Heading(h.level, h.text, h.span.map(span))

  private def content(c: ast.Content): ir.Content = c match
    case ast.Content.Prose(text, sp) =>
      ir.Content.Prose(text, sp.map(span))
    case ast.Content.CodeBlock(lang, source, _, sp) =>
      // Foreign-language fences (html, css, future scala, wat, …) become
      // EmbeddedBlock so SourceLanguage plugins (Stage 9) own them.
      if ast.Lang.isScalaScript(lang) then
        ir.Content.CodeBlock(source = source, body = Nil, span = sp.map(span))
      else
        ir.Content.EmbeddedBlock(language = lang, source = source, span = sp.map(span))
    case ast.Content.Import(path, bindings, sp) =>
      ir.Content.Import(path, bindings.map(importBinding), sp.map(span))
    case ast.Content.DataList(items, ordered, sp) =>
      ir.Content.DataList(items.map(listItem), ordered, sp.map(span))

  private def importBinding(b: ast.ImportBinding): ir.ImportBinding =
    ir.ImportBinding(b.name, b.alias, b.span.map(span))

  private def listItem(i: ast.ListItem): ir.ListItem =
    ir.ListItem(i.content, i.nested.map(listItem), i.span.map(span))

  private def span(s: ast.Span): ir.Span =
    ir.Span(position(s.start), position(s.end))

  private def position(p: ast.Position): ir.Position =
    ir.Position(p.line, p.column, p.offset)
