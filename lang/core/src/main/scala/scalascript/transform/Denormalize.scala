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
      span     = m.span.map(span),
      document = m.document.map(documentContent)
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
      translations      = Map.empty,
      apiClients        = m.apiClients.map(apiClientDecl),
      databases         = m.databases.map(databaseDecl),
      objectStores      = m.objectStores.map(objectStoreDecl),
      graphs            = m.graphs.map(graphDecl),
      schemas           = m.schemas.map(typeSchemaDecl),
      raw               = Map.empty,
      frontendFramework = m.frontendFramework,
      scripts           = m.scripts,
      cluster           = m.cluster.map(clusterDecl),
      remoteHandlers    = m.remoteHandlers.map(remoteHandlerDecl),
      remoteSources     = m.remoteSources.map(remoteSourceDecl),
      remoteBehaviors   = m.remoteBehaviors.map(remoteBehaviorDecl),
      span              = m.span.map(span)
    )

  private def routeDecl(r: ir.RouteDecl): ast.RouteDecl =
    ast.RouteDecl(r.method, r.path, r.handler, r.span.map(span))

  private def documentContent(d: ir.DocumentContent): ast.DocumentContent =
    ast.DocumentContent(
      manifest    = contentValue(d.manifest),
      title       = d.title,
      description = d.description,
      attrs       = d.attrs.view.mapValues(contentValue).toMap,
      sections    = d.sections.map(sectionContent),
      blocks      = d.blocks.map(contentBlock)
    )

  private def sectionContent(s: ir.SectionContent): ast.SectionContent =
    ast.SectionContent(
      id       = s.id,
      level    = s.level,
      title    = s.title,
      attrs    = s.attrs.view.mapValues(contentValue).toMap,
      blocks   = s.blocks.map(contentBlock),
      children = s.children.map(sectionContent)
    )

  private def contentBlock(b: ir.ContentBlock): ast.ContentBlock = b match
    case ir.ContentBlock.Paragraph(inlines, attrs) =>
      ast.ContentBlock.Paragraph(inlines.map(contentInline), attrs.view.mapValues(contentValue).toMap)
    case ir.ContentBlock.BulletList(items, attrs) =>
      ast.ContentBlock.BulletList(items.map(_.map(contentBlock)), attrs.view.mapValues(contentValue).toMap)
    case ir.ContentBlock.OrderedList(items, start, attrs) =>
      ast.ContentBlock.OrderedList(items.map(_.map(contentBlock)), start, attrs.view.mapValues(contentValue).toMap)
    case ir.ContentBlock.Image(src, alt, title, attrs) =>
      ast.ContentBlock.Image(src, alt, title, attrs.view.mapValues(contentValue).toMap)
    case ir.ContentBlock.Table(headers, rows, alignments, attrs) =>
      ast.ContentBlock.Table(
        headers.map(_.map(contentInline)),
        rows.map(_.map(_.map(contentInline))),
        alignments,
        attrs.view.mapValues(contentValue).toMap
      )
    case ir.ContentBlock.Embedded(lang, source, kind, data, attrs) =>
      ast.ContentBlock.Embedded(lang, source, embeddedKind(kind), data.map(contentValue), attrs.view.mapValues(contentValue).toMap)

  private def embeddedKind(k: ir.EmbeddedKind): ast.EmbeddedKind = k match
    case ir.EmbeddedKind.StructuredData => ast.EmbeddedKind.StructuredData
    case ir.EmbeddedKind.Executable     => ast.EmbeddedKind.Executable
    case ir.EmbeddedKind.StringBlock    => ast.EmbeddedKind.StringBlock
    case ir.EmbeddedKind.Opaque         => ast.EmbeddedKind.Opaque

  private def contentInline(i: ir.ContentInline): ast.ContentInline = i match
    case ir.ContentInline.Text(value)       => ast.ContentInline.Text(value)
    case ir.ContentInline.Emphasis(kids)    => ast.ContentInline.Emphasis(kids.map(contentInline))
    case ir.ContentInline.Strong(kids)      => ast.ContentInline.Strong(kids.map(contentInline))
    case ir.ContentInline.Code(value)       => ast.ContentInline.Code(value)
    case ir.ContentInline.Link(label, href, title) =>
      ast.ContentInline.Link(label.map(contentInline), href, title)
    case ir.ContentInline.Expr(source)      => ast.ContentInline.Expr(source)

  private def contentValue(v: ir.ContentValue): ast.ContentValue = v match
    case ir.ContentValue.Str(value)     => ast.ContentValue.Str(value)
    case ir.ContentValue.Bool(value)    => ast.ContentValue.Bool(value)
    case ir.ContentValue.Num(value)     => ast.ContentValue.Num(value)
    case ir.ContentValue.ListV(values)  => ast.ContentValue.ListV(values.map(contentValue))
    case ir.ContentValue.MapV(values)   => ast.ContentValue.MapV(values.view.mapValues(contentValue).toMap)
    case ir.ContentValue.NullV          => ast.ContentValue.NullV

  private def apiClientDecl(c: ir.ApiClientDecl): ast.ApiClientDecl =
    ast.ApiClientDecl(c.name, c.endpoints.map(apiEndpointDecl), c.span.map(span))

  private def apiEndpointDecl(e: ir.ApiEndpointDecl): ast.ApiEndpointDecl =
    ast.ApiEndpointDecl(e.name, e.method, e.path, e.requestType, e.responseType, e.stream, e.paginated, e.span.map(span))

  private def clusterDecl(c: ir.ClusterDecl): ast.ClusterDecl =
    ast.ClusterDecl(c.name, c.nodeId, c.role, c.bind, c.advertiseUrl, c.seedNodes, c.authToken, c.placement, c.wire, c.nodes, c.seedDiscovery, c.leaderElection, c.authTokenFrom, c.heartbeat, c.quorum, c.span.map(span))

  private def remoteHandlerDecl(h: ir.RemoteHandlerDecl): ast.RemoteHandlerDecl =
    ast.RemoteHandlerDecl(h.name, h.function, h.path, h.requestType, h.responseType, h.span.map(span))

  private def remoteSourceDecl(s: ir.RemoteSourceDecl): ast.RemoteSourceDecl =
    ast.RemoteSourceDecl(s.name, s.source, s.paramsType, s.itemType, s.span.map(span))

  private def remoteBehaviorDecl(b: ir.RemoteBehaviorDecl): ast.RemoteBehaviorDecl =
    ast.RemoteBehaviorDecl(b.name, b.behavior, b.argsType, b.span.map(span))

  private def databaseDecl(d: ir.DatabaseDecl): ast.DatabaseDecl =
    ast.DatabaseDecl(d.name, d.url, d.user, d.password, d.driver, d.span.map(span))

  private def objectStoreDecl(s: ir.ObjectStoreDecl): ast.ObjectStoreDecl =
    ast.ObjectStoreDecl(s.name, s.valueType, s.sync, s.database, s.store, s.table, s.key, s.conflict, s.span.map(span))

  private def graphDecl(g: ir.GraphDecl): ast.GraphDecl =
    ast.GraphDecl(g.name, g.model, g.side, g.backend, g.uri, g.user, g.password, g.span.map(span))

  private def typeSchemaDecl(s: ir.TypeSchemaDecl): ast.TypeSchemaDecl =
    ast.TypeSchemaDecl(s.typeName, s.fields.map(fieldSchemaDecl), s.rejectUnknown, s.span.map(span))

  private def fieldSchemaDecl(f: ir.FieldSchemaDecl): ast.FieldSchemaDecl =
    ast.FieldSchemaDecl(f.fieldName, f.storageName, f.aliases, f.default.map(schemaDefault), f.key, f.span.map(span))

  private def schemaDefault(d: ir.SchemaDefault): ast.SchemaDefault = d match
    case ir.SchemaDefault.NullValue => ast.SchemaDefault.NullValue
    case ir.SchemaDefault.Bool(value) => ast.SchemaDefault.Bool(value)
    case ir.SchemaDefault.IntValue(value) => ast.SchemaDefault.IntValue(value)
    case ir.SchemaDefault.DoubleValue(value) => ast.SchemaDefault.DoubleValue(value)
    case ir.SchemaDefault.StringValue(value) => ast.SchemaDefault.StringValue(value)

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
    case ir.Content.EmbeddedBlock(language, source, sp, _) =>
      ast.Content.CodeBlock(language, source, None, sp.map(span))
    case ir.Content.SqlBlock(source, _, dbName, sp, side) =>
      val attrs0 = dbName.fold(Map.empty[String, String])(n => Map("db" -> n))
      val attrs  = if side == ir.SqlSide.Client then attrs0 + ("side" -> "client") else attrs0
      ast.Content.CodeBlock(
        lang   = ast.Lang.Sql,
        source = source,
        tree   = None,
        span   = sp.map(span),
        attrs  = attrs
      )
    case ir.Content.TransactionBlock(sources, _, dbName, sp) =>
      // Round-trip: rejoin statements with ";\n" so the interpreter /
      // JvmGen can re-split via `SqlBindRewriter.splitStatements`.
      ast.Content.CodeBlock(
        lang   = ast.Lang.Transaction,
        source = sources.mkString(";\n"),
        tree   = None,
        span   = sp.map(span),
        attrs  = dbName.fold(Map.empty[String, String])(n => Map("db" -> n))
      )
    case ir.Content.Import(path, bindings, sp) =>
      ast.Content.Import(path, bindings.map(importBinding), sp.map(span))
    case ir.Content.DataList(items, ordered, sp) =>
      ast.Content.DataList(items.map(listItem), ordered, sp.map(span))

  private def importBinding(b: ir.ImportBinding): ast.ImportBinding =
    ast.ImportBinding(b.name, alias = b.alias, span = b.span.map(span))

  private def listItem(i: ir.ListItem): ast.ListItem =
    ast.ListItem(i.content, i.nested.map(listItem), i.span.map(span))

  private def span(s: ir.Span): ast.Span =
    ast.Span(position(s.start), position(s.end))

  private def position(p: ir.Position): ast.Position =
    ast.Position(p.line, p.column, p.offset)
