package scalascript.transform

import scalascript.ast
import scalascript.ir
import scalascript.artifact.InterfaceScope
import scalascript.backend.spi.{BackendOptions, Diagnostic, ScopeContext, SymbolKind}
import scalascript.compiler.plugin.SourceLanguageRegistry
import scalascript.typer.SType

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
      span     = m.span.map(span),
      document = m.document.map(documentContent)
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
      apiClients        = m.apiClients.map(apiClientDecl),
      databases         = m.databases.map(databaseDecl),
      objectStores      = m.objectStores.map(objectStoreDecl),
      graphs            = m.graphs.map(graphDecl),
      schemas           = m.schemas.map(typeSchemaDecl),
      frontendFramework = m.frontendFramework,
      scripts           = m.scripts,
      cluster           = m.cluster.map(clusterDecl),
      remoteHandlers    = m.remoteHandlers.map(remoteHandlerDecl),
      remoteSources     = m.remoteSources.map(remoteSourceDecl),
      remoteBehaviors   = m.remoteBehaviors.map(remoteBehaviorDecl),
      span              = m.span.map(span)
    )

  private def routeDecl(r: ast.RouteDecl): ir.RouteDecl =
    ir.RouteDecl(r.method, r.path, r.handler, r.span.map(span))

  private def documentContent(d: ast.DocumentContent): ir.DocumentContent =
    ir.DocumentContent(
      manifest    = contentValue(d.manifest),
      title       = d.title,
      description = d.description,
      attrs       = d.attrs.view.mapValues(contentValue).toMap,
      sections    = d.sections.map(sectionContent),
      blocks      = d.blocks.map(contentBlock)
    )

  private def sectionContent(s: ast.SectionContent): ir.SectionContent =
    ir.SectionContent(
      id       = s.id,
      level    = s.level,
      title    = s.title,
      attrs    = s.attrs.view.mapValues(contentValue).toMap,
      blocks   = s.blocks.map(contentBlock),
      children = s.children.map(sectionContent)
    )

  private def contentBlock(b: ast.ContentBlock): ir.ContentBlock = b match
    case ast.ContentBlock.Paragraph(inlines, attrs) =>
      ir.ContentBlock.Paragraph(inlines.map(contentInline), attrs.view.mapValues(contentValue).toMap)
    case ast.ContentBlock.BulletList(items, attrs) =>
      ir.ContentBlock.BulletList(items.map(_.map(contentBlock)), attrs.view.mapValues(contentValue).toMap)
    case ast.ContentBlock.OrderedList(items, start, attrs) =>
      ir.ContentBlock.OrderedList(items.map(_.map(contentBlock)), start, attrs.view.mapValues(contentValue).toMap)
    case ast.ContentBlock.Image(src, alt, title, attrs) =>
      ir.ContentBlock.Image(src, alt, title, attrs.view.mapValues(contentValue).toMap)
    case ast.ContentBlock.Embedded(lang, source, kind, data, attrs) =>
      ir.ContentBlock.Embedded(lang, source, embeddedKind(kind), data.map(contentValue), attrs.view.mapValues(contentValue).toMap)

  private def embeddedKind(k: ast.EmbeddedKind): ir.EmbeddedKind = k match
    case ast.EmbeddedKind.StructuredData => ir.EmbeddedKind.StructuredData
    case ast.EmbeddedKind.Executable     => ir.EmbeddedKind.Executable
    case ast.EmbeddedKind.StringBlock    => ir.EmbeddedKind.StringBlock
    case ast.EmbeddedKind.Opaque         => ir.EmbeddedKind.Opaque

  private def contentInline(i: ast.ContentInline): ir.ContentInline = i match
    case ast.ContentInline.Text(value)       => ir.ContentInline.Text(value)
    case ast.ContentInline.Emphasis(kids)    => ir.ContentInline.Emphasis(kids.map(contentInline))
    case ast.ContentInline.Strong(kids)      => ir.ContentInline.Strong(kids.map(contentInline))
    case ast.ContentInline.Code(value)       => ir.ContentInline.Code(value)
    case ast.ContentInline.Link(label, href, title) =>
      ir.ContentInline.Link(label.map(contentInline), href, title)
    case ast.ContentInline.Expr(source)      => ir.ContentInline.Expr(source)

  private def contentValue(v: ast.ContentValue): ir.ContentValue = v match
    case ast.ContentValue.Str(value)     => ir.ContentValue.Str(value)
    case ast.ContentValue.Bool(value)    => ir.ContentValue.Bool(value)
    case ast.ContentValue.Num(value)     => ir.ContentValue.Num(value)
    case ast.ContentValue.ListV(values)  => ir.ContentValue.ListV(values.map(contentValue))
    case ast.ContentValue.MapV(values)   => ir.ContentValue.MapV(values.view.mapValues(contentValue).toMap)
    case ast.ContentValue.NullV          => ir.ContentValue.NullV

  private def apiClientDecl(c: ast.ApiClientDecl): ir.ApiClientDecl =
    ir.ApiClientDecl(c.name, c.endpoints.map(apiEndpointDecl), c.span.map(span))

  private def apiEndpointDecl(e: ast.ApiEndpointDecl): ir.ApiEndpointDecl =
    ir.ApiEndpointDecl(
      e.name,
      e.method,
      e.path,
      e.requestType,
      e.responseType,
      e.stream,
      e.paginated,
      e.span.map(span),
      Some(apiEndpointTypeEvidence(e))
    )

  private def apiEndpointTypeEvidence(e: ast.ApiEndpointDecl): ir.ApiEndpointTypeEvidenceWire =
    val responseEvidence =
      typeStringEvidence(e.responseType, "api endpoint response type metadata")
    ir.ApiEndpointTypeEvidenceWire(
      request = Some(typeStringEvidence(e.requestType, "api endpoint request type metadata")),
      response = Some(responseEvidence),
      streamElement = e.stream.map(_ =>
        typeStringEvidence(e.responseType, "api endpoint stream element type metadata")
      )
    )

  private def clusterDecl(c: ast.ClusterDecl): ir.ClusterDecl =
    ir.ClusterDecl(c.name, c.nodeId, c.role, c.bind, c.advertiseUrl, c.seedNodes, c.authToken, c.placement, c.wire, c.nodes, c.seedDiscovery, c.leaderElection, c.authTokenFrom, c.heartbeat, c.quorum, c.span.map(span))

  private def remoteHandlerDecl(h: ast.RemoteHandlerDecl): ir.RemoteHandlerDecl =
    ir.RemoteHandlerDecl(
      h.name,
      h.function,
      h.path,
      h.requestType,
      h.responseType,
      h.span.map(span),
      Some(remoteHandlerTypeEvidence(h))
    )

  private def remoteHandlerTypeEvidence(h: ast.RemoteHandlerDecl): ir.ApiEndpointTypeEvidenceWire =
    ir.ApiEndpointTypeEvidenceWire(
      request = Some(optionalTypeStringEvidence(
        h.requestType,
        "remote handler request type metadata",
        "remote handler request type metadata is missing"
      )),
      response = Some(optionalTypeStringEvidence(
        h.responseType,
        "remote handler response type metadata",
        "remote handler response type metadata is missing"
      ))
    )

  private def optionalTypeStringEvidence(
      raw:           Option[String],
      declaredReason: String,
      missingReason:  String
  ): ir.TypeEvidenceWire =
    raw match
      case Some(value) => typeStringEvidence(value, declaredReason)
      case None        => unknownTypeEvidence(missingReason)

  private def typeStringEvidence(raw: String, declaredReason: String): ir.TypeEvidenceWire =
    val trimmed = raw.trim
    if trimmed.isEmpty then unknownTypeEvidence("type metadata is empty")
    else
      val parsed = InterfaceScope.parseSType(trimmed)
      if parsed == SType.Any && trimmed != SType.Any.show then
        unknownTypeEvidence(s"unsupported type metadata '$trimmed'")
      else if parsed.containsAny then
        ir.TypeEvidenceWire(parsed.show, "Unknown", Some("type metadata contains Any"))
      else
        ir.TypeEvidenceWire(parsed.show, "Declared", Some(declaredReason))

  private def unknownTypeEvidence(reason: String): ir.TypeEvidenceWire =
    ir.TypeEvidenceWire(SType.Any.show, "Unknown", Some(reason))

  private def remoteSourceDecl(s: ast.RemoteSourceDecl): ir.RemoteSourceDecl =
    ir.RemoteSourceDecl(s.name, s.source, s.paramsType, s.itemType, s.span.map(span))

  private def remoteBehaviorDecl(b: ast.RemoteBehaviorDecl): ir.RemoteBehaviorDecl =
    ir.RemoteBehaviorDecl(b.name, b.behavior, b.argsType, b.span.map(span))

  private def databaseDecl(d: ast.DatabaseDecl): ir.DatabaseDecl =
    ir.DatabaseDecl(d.name, d.url, d.user, d.password, d.driver, d.span.map(span))

  private def objectStoreDecl(s: ast.ObjectStoreDecl): ir.ObjectStoreDecl =
    ir.ObjectStoreDecl(s.name, s.valueType, s.sync, s.database, s.store, s.table, s.key, s.conflict, s.span.map(span))

  private def graphDecl(g: ast.GraphDecl): ir.GraphDecl =
    ir.GraphDecl(g.name, g.model, g.side, g.backend, g.uri, g.user, g.password, g.span.map(span))

  private def typeSchemaDecl(s: ast.TypeSchemaDecl): ir.TypeSchemaDecl =
    ir.TypeSchemaDecl(s.typeName, s.fields.map(fieldSchemaDecl), s.rejectUnknown, s.span.map(span))

  private def fieldSchemaDecl(f: ast.FieldSchemaDecl): ir.FieldSchemaDecl =
    ir.FieldSchemaDecl(f.fieldName, f.storageName, f.aliases, f.default.map(schemaDefault), f.key, f.span.map(span))

  private def schemaDefault(d: ast.SchemaDefault): ir.SchemaDefault = d match
    case ast.SchemaDefault.NullValue => ir.SchemaDefault.NullValue
    case ast.SchemaDefault.Bool(value) => ir.SchemaDefault.Bool(value)
    case ast.SchemaDefault.IntValue(value) => ir.SchemaDefault.IntValue(value)
    case ast.SchemaDefault.DoubleValue(value) => ir.SchemaDefault.DoubleValue(value)
    case ast.SchemaDefault.StringValue(value) => ir.SchemaDefault.StringValue(value)

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
    case ast.Content.CodeBlock(lang, source, tree, sp, _, _, attrs) =>
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
        // Stage 9+/A — all fenced tags (html, css, javascript, sql, xml,
        // transaction, scala, and third-party) route through the registry.
        // Built-in tags register via META-INF/services in backendScalaSource
        // / backendHtml / backendCss.  The fallback paths keep callers that
        // use `core` without those JARs on the classpath working.
        SourceLanguageRegistry.lookup(lang) match
          case Some(plugin) =>
            val artifact = plugin.compileBlock(source, NormalizeScope, BackendOptions(), attrs)
            if artifact.diagnostics.nonEmpty then
              val msg = artifact.diagnostics.map {
                case Diagnostic.GraphQLSdlError(m, l, c) if l > 0 => s"$m (line $l, col $c)"
                case d => d.toString
              }.mkString("\n")
              throw new RuntimeException(s"$lang block validation failed:\n$msg")
            withSpan(artifact.fragment, sp.map(span))
          case None if ast.Lang.isSql(lang) =>
            sqlBlock(source, attrs, sp.map(span), lang)
          case None if ast.Lang.isTransaction(lang) =>
            transactionBlock(source, attrs, sp.map(span), lang)
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

  /** Restore the AST-level span on a plugin-produced IR fragment.
   *  Plugins receive no span; Normalize re-applies it so diagnostics
   *  retain source positions. */
  private def withSpan(frag: ir.Content, sp: Option[ir.Span]): ir.Content =
    if sp.isEmpty then frag
    else frag match
      case x: ir.Content.EmbeddedBlock    => x.copy(span = sp)
      case x: ir.Content.SqlBlock         => x.copy(span = sp)
      case x: ir.Content.TransactionBlock => x.copy(span = sp)
      case x: ir.Content.CodeBlock        => x.copy(span = sp)
      case other                          => other

  /** Compatibility fallback for `sql` blocks when the bundled
   *  SqlSourceLanguage is not on the classpath (e.g. `core`-only tests). */
  private def sqlBlock(source: String, attrs: Map[String, String], sp: Option[ir.Span], lang: String): ir.Content =
    try
      val rewritten = SqlBindRewriter.rewriteJdbc(source)
      val side = attrs.get("side") match
        case Some("client") => ir.SqlSide.Client
        case _              => ir.SqlSide.Server
      ir.Content.SqlBlock(
        source = source,
        binds  = rewritten.binds,
        dbName = attrs.get("db"),
        span   = sp,
        side   = side
      )
    catch case _: SqlBindRewriter.RewriteError =>
      ir.Content.EmbeddedBlock(language = lang, source = source, span = sp)

  /** Compatibility fallback for `transaction` blocks when
   *  TransactionSourceLanguage is not on the classpath. */
  private def transactionBlock(source: String, attrs: Map[String, String], sp: Option[ir.Span], lang: String): ir.Content =
    try
      val stmts     = SqlBindRewriter.splitStatements(source)
      val rewritten = stmts.map(SqlBindRewriter.rewriteJdbc)
      ir.Content.TransactionBlock(
        sources = stmts,
        binds   = rewritten.map(_.binds),
        dbName  = attrs.get("db"),
        span    = sp
      )
    catch case _: SqlBindRewriter.RewriteError =>
      ir.Content.EmbeddedBlock(language = lang, source = source, span = sp)

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
