package scalascript.transform

import scalascript.ast
import scalascript.ir
import scalascript.backend.spi.{BackendOptions, ScopeContext, SymbolKind}
import scalascript.plugin.SourceLanguageRegistry

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
 *  Stage 9+/A: foreign-language fences now route through the
 *  `SourceLanguageRegistry` first.  A plugin claiming the fence tag
 *  produces an `ir.Content.NormalizedBlock` (= `ir.Content`) via its
 *  `compileBlock` method.  No plugin → fall back to the existing
 *  `EmbeddedBlock` shape (preserves existing behaviour for `html` /
 *  `css` blocks until 9+/B and 9+/C extract them as plugins). */
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
    case ast.Content.CodeBlock(lang, source, tree, sp, _, _) =>
      if ast.Lang.isScalaScript(lang) then
        // v2.0 / Stage 5+ — populate `body` with translated `IrExpr`
        // trees so `Linker.rewriteExpr` has real data to walk for
        // cross-module symbol rewriting.  The original `source` is
        // still emitted unchanged: backends today re-parse from it
        // (via `Denormalize` → `Parser.parseScalaSource`).
        val rewrittenSrc = rewriteConsole(source)
        val bodyIr = tree.toList.map(t =>
          ast.ScalaNode.fold(t)(AstToIr.toIrExpr)
        )
        ir.Content.CodeBlock(source = rewrittenSrc, body = bodyIr, span = sp.map(span))
      else
        // Stage 9+/A — ask the SourceLanguage registry first.  A
        // plugin claiming this fence tag produces the IR fragment
        // directly; otherwise fall back to the wrapping EmbeddedBlock
        // shape (Stage 2.1 default — kept for the still-hardcoded
        // html / css / scala paths in codegen until 9+/B and 9+/C
        // move them through here).
        SourceLanguageRegistry.lookup(lang) match
          case Some(plugin) =>
            plugin.compileBlock(source, NormalizeScope, BackendOptions()).fragment
          case None =>
            ir.Content.EmbeddedBlock(language = lang, source = source, span = sp.map(span))
    case ast.Content.Import(path, bindings, sp) =>
      ir.Content.Import(path, bindings.map(importBinding), sp.map(span))
    case ast.Content.DataList(items, ordered, sp) =>
      ir.Content.DataList(items.map(listItem), ordered, sp.map(span))

  /** 5+/B.3 — rewrite bare `println` / `print` to their `Console.*`
   *  qualified forms so all backends dispatch through the intrinsic table.
   *  The regex uses word-boundaries so identifiers like `myPrintln` are
   *  untouched; the substitution is purely textual (no AST parse). */
  // Matches bare `println` / `print` that are NOT already dot-qualified.
  private val PrintlnRe = raw"(?<!\.)(\bprintln\b)".r
  private val PrintRe   = raw"(?<!\.)(\bprint\b)".r

  private def rewriteConsole(src: String): String =
    val s1 = PrintlnRe.replaceAllIn(src,   "Console.println")
    val s2 = PrintRe.replaceAllIn(s1, "Console.print")
    s2

  /** Minimum-viable scope handed to `SourceLanguage.compileBlock` — no
   *  cross-block visibility (single-pass typing).  Stage 9+/A.2 builds
   *  the real two-pass `signatures` → `compileBlock` flow on top of
   *  this so plugins can reference module-wide symbols. */
  private object NormalizeScope extends ScopeContext:
    def isInScope(name: ir.QualifiedName): Boolean         = false
    def resolve(name: ir.QualifiedName):   Option[SymbolKind] = None

  private def importBinding(b: ast.ImportBinding): ir.ImportBinding =
    ir.ImportBinding(b.name, b.alias, b.span.map(span))

  private def listItem(i: ast.ListItem): ir.ListItem =
    ir.ListItem(i.content, i.nested.map(listItem), i.span.map(span))

  private def span(s: ast.Span): ir.Span =
    ir.Span(position(s.start), position(s.end))

  private def position(p: ast.Position): ir.Position =
    ir.Position(p.line, p.column, p.offset)
