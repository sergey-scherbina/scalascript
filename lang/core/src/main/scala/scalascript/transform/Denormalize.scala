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
      translations      = Map.empty,
      apiClients        = m.apiClients.map(apiClientDecl),
      databases         = m.databases.map(databaseDecl),
      objectStores      = m.objectStores.map(objectStoreDecl),
      schemas           = m.schemas.map(typeSchemaDecl),
      raw               = Map.empty,
      frontendFramework = m.frontendFramework,
      scripts           = m.scripts,
      span              = m.span.map(span)
    )

  private def routeDecl(r: ir.RouteDecl): ast.RouteDecl =
    ast.RouteDecl(r.method, r.path, r.handler, r.span.map(span))

  private def apiClientDecl(c: ir.ApiClientDecl): ast.ApiClientDecl =
    ast.ApiClientDecl(c.name, c.endpoints.map(apiEndpointDecl), c.span.map(span))

  private def apiEndpointDecl(e: ir.ApiEndpointDecl): ast.ApiEndpointDecl =
    ast.ApiEndpointDecl(e.name, e.method, e.path, e.requestType, e.responseType, e.span.map(span))

  private def databaseDecl(d: ir.DatabaseDecl): ast.DatabaseDecl =
    ast.DatabaseDecl(d.name, d.url, d.user, d.password, d.driver, d.span.map(span))

  private def objectStoreDecl(s: ir.ObjectStoreDecl): ast.ObjectStoreDecl =
    ast.ObjectStoreDecl(s.name, s.valueType, s.sync, s.database, s.store, s.table, s.key, s.conflict, s.span.map(span))

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
    case ir.Content.EmbeddedBlock(language, source, sp) =>
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
    ast.ImportBinding(b.name, b.alias, b.span.map(span))

  private def listItem(i: ir.ListItem): ast.ListItem =
    ast.ListItem(i.content, i.nested.map(listItem), i.span.map(span))

  private def span(s: ir.Span): ast.Span =
    ast.Span(position(s.start), position(s.end))

  private def position(p: ir.Position): ast.Position =
    ast.Position(p.line, p.column, p.offset)
