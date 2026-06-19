package scalascript.codegen

import scalascript.ast.*
import scala.meta.*
import scalascript.codegen.JvmGenStringUtils.*

/** Content-toolkit Scala-source emission for the JVM backend — `contentDocument()` /
 *  content-toolkit runtime + the `ContentValue`/`ContentBlock`/`SectionContent`/`DocumentContent`
 *  → Scala-source serializers. Extracted verbatim from `JvmGen` (codegen-megafile-deflation) into a
 *  `self: JvmGen =>` mixin to shrink the 5K-line core; behaviour-identical. */
private[codegen] trait JvmGenContentEmit:
  self: JvmGen =>

  private[codegen] def moduleUsesContentNames(module: Module, names: Set[String], includeStdContentImport: Boolean, includeToolkitImport: Boolean): Boolean =
    def treeUsesContent(node: ScalaNode): Boolean =
      ScalaNode.fold(node) { tree =>
        tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(name), _) if names(name) => ()
        }.nonEmpty
      }

    def sectionUsesContent(section: Section): Boolean =
      section.content.exists {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.exists(treeUsesContent) || names.exists(cb.source.contains)
        case imp: Content.Import =>
          (includeStdContentImport && isContentStdImport(imp.path)) ||
            (includeToolkitImport && isContentToolkitStdImport(imp.path)) ||
            imp.bindings.exists(binding => names(binding.name))
        case _ => false
      } || section.subsections.exists(sectionUsesContent)

    module.sections.exists(sectionUsesContent)

  private[codegen] def moduleUsesContentIntrinsics(module: Module): Boolean =
    moduleUsesContentNames(module, contentIntrinsicNames, includeStdContentImport = true, includeToolkitImport = true)

  private[codegen] def moduleUsesContentToolkitIntrinsics(module: Module): Boolean =
    moduleUsesContentNames(module, contentToolkitIntrinsicNames, includeStdContentImport = false, includeToolkitImport = true)

  private[codegen] def scalaOptionStringExpr(value: Option[String]): String =
    value.map(v => s"Some(${scalaStringLiteral(v)})").getOrElse("None")

  private[codegen] def scalaStringListExpr(values: List[String]): String =
    if values.isEmpty then "List.empty[String]"
    else values.map(scalaStringLiteral).mkString("List(", ", ", ")")

  private[codegen] def scalaContentValueMapExpr(values: Map[String, ContentValue]): String =
    if values.isEmpty then "Map.empty[String, std.content.ContentValue]"
    else
      values.map { (key, value) => s"${scalaStringLiteral(key)} -> ${scalaContentValueExpr(value)}" }
        .mkString("Map(", ", ", ")")

  private[codegen] def scalaContentValueExpr(value: ContentValue): String = value match
    case ContentValue.Str(v) =>
      s"std.content.ContentValue.Str(${scalaStringLiteral(v)})"
    case ContentValue.Bool(v) =>
      s"std.content.ContentValue.Bool($v)"
    case ContentValue.Num(v) =>
      s"std.content.ContentValue.Num(${v.toString})"
    case ContentValue.ListV(values) =>
      val items =
        if values.isEmpty then "List.empty[std.content.ContentValue]"
        else values.map(scalaContentValueExpr).mkString("List(", ", ", ")")
      s"std.content.ContentValue.ListV($items)"
    case ContentValue.MapV(values) =>
      s"std.content.ContentValue.MapV(${scalaContentValueMapExpr(values)})"
    case ContentValue.NullV =>
      "std.content.ContentValue.NullV"

  private[codegen] def scalaInlineListExpr(values: List[ContentInline]): String =
    if values.isEmpty then "List.empty[std.content.ContentInline]"
    else values.map(scalaContentInlineExpr).mkString("List(", ", ", ")")

  private[codegen] def scalaContentInlineExpr(inline: ContentInline): String = inline match
    case ContentInline.Text(value) =>
      s"std.content.ContentInline.Text(${scalaStringLiteral(value)})"
    case ContentInline.Emphasis(children) =>
      s"std.content.ContentInline.Emphasis(${scalaInlineListExpr(children)})"
    case ContentInline.Strong(children) =>
      s"std.content.ContentInline.Strong(${scalaInlineListExpr(children)})"
    case ContentInline.Code(value) =>
      s"std.content.ContentInline.Code(${scalaStringLiteral(value)})"
    case ContentInline.Link(label, href, title) =>
      s"std.content.ContentInline.Link(${scalaInlineListExpr(label)}, ${scalaStringLiteral(href)}, ${scalaOptionStringExpr(title)})"
    case ContentInline.Expr(source) =>
      s"std.content.ContentInline.Expr(${scalaStringLiteral(source)})"

  private[codegen] def scalaBlockListExpr(values: List[ContentBlock]): String =
    if values.isEmpty then "List.empty[std.content.ContentBlock]"
    else values.map(scalaContentBlockExpr).mkString("List(", ", ", ")")

  private[codegen] def scalaNestedBlockListExpr(values: List[List[ContentBlock]]): String =
    if values.isEmpty then "List.empty[List[std.content.ContentBlock]]"
    else values.map(scalaBlockListExpr).mkString("List(", ", ", ")")

  private[codegen] def scalaTableHeaderExpr(values: List[List[ContentInline]]): String =
    if values.isEmpty then "List.empty[List[std.content.ContentInline]]"
    else values.map(scalaInlineListExpr).mkString("List(", ", ", ")")

  private[codegen] def scalaTableRowsExpr(values: List[List[List[ContentInline]]]): String =
    if values.isEmpty then "List.empty[List[List[std.content.ContentInline]]]"
    else values.map(row => scalaTableHeaderExpr(row)).mkString("List(", ", ", ")")

  private[codegen] def scalaEmbeddedKindExpr(kind: EmbeddedKind): String = kind match
    case EmbeddedKind.StructuredData => "std.content.EmbeddedKind.StructuredData"
    case EmbeddedKind.Executable     => "std.content.EmbeddedKind.Executable"
    case EmbeddedKind.StringBlock    => "std.content.EmbeddedKind.StringBlock"
    case EmbeddedKind.Opaque         => "std.content.EmbeddedKind.Opaque"

  private[codegen] def scalaContentBlockExpr(block: ContentBlock): String = block match
    case ContentBlock.Paragraph(inlines, attrs) =>
      s"std.content.ContentBlock.Paragraph(${scalaInlineListExpr(inlines)}, ${scalaContentValueMapExpr(attrs)})"
    case ContentBlock.BulletList(items, attrs) =>
      s"std.content.ContentBlock.BulletList(${scalaNestedBlockListExpr(items)}, ${scalaContentValueMapExpr(attrs)})"
    case ContentBlock.OrderedList(items, start, attrs) =>
      s"std.content.ContentBlock.OrderedList(${scalaNestedBlockListExpr(items)}, $start, ${scalaContentValueMapExpr(attrs)})"
    case ContentBlock.Image(src, alt, title, attrs) =>
      s"std.content.ContentBlock.Image(${scalaStringLiteral(src)}, ${scalaStringLiteral(alt)}, ${scalaOptionStringExpr(title)}, ${scalaContentValueMapExpr(attrs)})"
    case ContentBlock.Table(headers, rows, alignments, attrs) =>
      s"std.content.ContentBlock.Table(${scalaTableHeaderExpr(headers)}, ${scalaTableRowsExpr(rows)}, ${scalaStringListExpr(alignments)}, ${scalaContentValueMapExpr(attrs)})"
    case ContentBlock.Embedded(lang, source, kind, data, attrs) =>
      val dataExpr = data.map(v => s"Some(${scalaContentValueExpr(v)})").getOrElse("None")
      s"std.content.ContentBlock.Embedded(${scalaStringLiteral(lang)}, ${scalaStringLiteral(source)}, ${scalaEmbeddedKindExpr(kind)}, $dataExpr, ${scalaContentValueMapExpr(attrs)})"

  private[codegen] def scalaSectionListExpr(values: List[SectionContent]): String =
    if values.isEmpty then "List.empty[std.content.SectionContent]"
    else values.map(scalaSectionExpr).mkString("List(", ", ", ")")

  private[codegen] def scalaSectionExpr(section: SectionContent): String =
    s"std.content.SectionContent(${scalaStringLiteral(section.id)}, ${section.level}, ${scalaStringLiteral(section.title)}, ${scalaContentValueMapExpr(section.attrs)}, ${scalaBlockListExpr(section.blocks)}, ${scalaSectionListExpr(section.children)})"

  private[codegen] def scalaDocumentExpr(doc: DocumentContent): String =
    s"std.content.DocumentContent(${scalaContentValueExpr(doc.manifest)}, ${scalaOptionStringExpr(doc.title)}, ${scalaOptionStringExpr(doc.description)}, ${scalaContentValueMapExpr(doc.attrs)}, ${scalaSectionListExpr(doc.sections)}, ${scalaBlockListExpr(doc.blocks)})"

  private[codegen] def scalaImportedDocumentsExpr: String =
    if importedContentDocuments.isEmpty then "Map.empty[String, List[std.content.DocumentContent]]"
    else
      importedContentDocuments.toSeq.sortBy(_._1).map { (namespace, docs) =>
        val docsExpr =
          if docs.isEmpty then "List.empty[std.content.DocumentContent]"
          else docs.map(scalaDocumentExpr).mkString("List(", ", ", ")")
        s"${scalaStringLiteral(namespace)} -> $docsExpr"
      }.mkString("Map(", ", ", ")")

  private[codegen] def collectDirectImportedContent(module: Module): Unit =
    importedContentDocuments.clear()
    def loop(section: Section): Unit =
      section.content.foreach {
        case imp: Content.Import if !isContentHelperImport(imp.path) =>
          resolveImportForContent(imp.path).foreach { resolved =>
            val childModule = scalascript.parser.Parser.parse(os.read(resolved))
            childModule.document.foreach { doc =>
              val namespace = importedContentNamespace(resolved, childModule)
              importedContentDocuments.update(namespace, importedContentDocuments.getOrElse(namespace, Nil) :+ doc)
            }
          }
        case _ => ()
      }
      section.subsections.foreach(loop)
    module.sections.foreach(loop)

  private[codegen] def resolveImportForContent(path: String): Option[os.Path] =
    val base = baseDir.getOrElse(os.pwd)
    val resolved =
      try scalascript.imports.ImportResolver.resolve(path, base, moduleDeps, lockPath)
      catch case _: Throwable => base / os.RelPath(path)
    Option.when(os.exists(resolved))(resolved)

  private[codegen] def importedContentNamespace(resolvedPath: os.Path, module: Module): String =
    module.manifest.flatMap(_.name).map(_.trim).filter(_.nonEmpty).getOrElse {
      val last = resolvedPath.last
      if last.endsWith(".ssc") then last.stripSuffix(".ssc") else last
    }

  private[codegen] def emitContentRuntime(document: Option[DocumentContent], includeToolkit: Boolean): String =
    val docExpr = document
      .map(scalaDocumentExpr)
      .getOrElse("""throw RuntimeException("contentDocument() is only available while running a parsed .ssc module")""")
    val importedDocsExpr = scalaImportedDocumentsExpr
    s"""
       |var _ssc_content_current_section_index: Option[Int] = None
       |lazy val _ssc_content_document: std.content.DocumentContent = $docExpr
       |lazy val _ssc_content_imported_documents: Map[String, List[std.content.DocumentContent]] = $importedDocsExpr
       |
       |def _ssc_content_flatten_sections(sections: List[std.content.SectionContent]): List[std.content.SectionContent] =
       |  sections.flatMap(section => section :: _ssc_content_flatten_sections(section.children))
       |
       |lazy val _ssc_content_sections: List[std.content.SectionContent] =
       |  _ssc_content_flatten_sections(_ssc_content_document.sections)
       |
       |def _ssc_content_section_by_index(index: Int): std.content.SectionContent =
       |  _ssc_content_sections.lift(index).getOrElse(
       |    throw RuntimeException("contentCurrentSection: missing generated section context")
       |  )
       |
       |def contentDocument(): std.content.DocumentContent = _ssc_content_document
       |
       |def _ssc_content_imported_unique(fn: String): Map[String, std.content.DocumentContent] =
       |  _ssc_content_imported_documents.map {
       |    case (namespace, doc :: Nil) => namespace -> doc
       |    case (namespace, Nil) => throw RuntimeException(s"$$fn: empty imported content namespace '$$namespace'")
       |    case (namespace, _) => throw RuntimeException(s"$$fn: duplicate imported content namespace '$$namespace'")
       |  }
       |
       |def contentModules(): Map[String, std.content.DocumentContent] =
       |  _ssc_content_imported_unique("contentModules()")
       |
       |def _ssc_content_imported_document(namespace: String, fn: String): Option[std.content.DocumentContent] =
       |  _ssc_content_imported_documents.get(namespace) match
       |    case None | Some(Nil) => None
       |    case Some(doc :: Nil) => Some(doc)
       |    case Some(_) => throw RuntimeException(s"$$fn: duplicate imported content namespace '$$namespace'")
       |
       |def contentModule(namespace: String): Option[std.content.DocumentContent] =
       |  _ssc_content_imported_document(namespace, "contentModule(namespace)")
       |
       |def contentCurrentSection(): std.content.SectionContent =
       |  _ssc_content_current_section_index.map(_ssc_content_section_by_index).getOrElse(
       |    throw RuntimeException("contentCurrentSection() is only available while running a parsed .ssc code block inside a Markdown section")
       |  )
       |
       |def _ssc_content_sections_deep(section: std.content.SectionContent): List[std.content.SectionContent] =
       |  section :: section.children.flatMap(_ssc_content_sections_deep)
       |
       |def _ssc_content_sections_deep(doc: std.content.DocumentContent): List[std.content.SectionContent] =
       |  doc.sections.flatMap(_ssc_content_sections_deep)
       |
       |def _ssc_content_blocks_deep(block: std.content.ContentBlock): List[std.content.ContentBlock] =
       |  block match
       |    case std.content.ContentBlock.BulletList(items, _) =>
       |      block :: items.flatten.flatMap(_ssc_content_blocks_deep)
       |    case std.content.ContentBlock.OrderedList(items, _, _) =>
       |      block :: items.flatten.flatMap(_ssc_content_blocks_deep)
       |    case _ =>
       |      block :: Nil
       |
       |def _ssc_content_blocks_deep(section: std.content.SectionContent): List[std.content.ContentBlock] =
       |  section.blocks.flatMap(_ssc_content_blocks_deep) ++ section.children.flatMap(_ssc_content_blocks_deep)
       |
       |def _ssc_content_blocks_deep(doc: std.content.DocumentContent): List[std.content.ContentBlock] =
       |  doc.blocks.flatMap(_ssc_content_blocks_deep) ++ doc.sections.flatMap(_ssc_content_blocks_deep)
       |
       |def _ssc_content_block_attrs(block: std.content.ContentBlock): Map[String, std.content.ContentValue] =
       |  block match
       |    case std.content.ContentBlock.Paragraph(_, attrs)        => attrs
       |    case std.content.ContentBlock.BulletList(_, attrs)       => attrs
       |    case std.content.ContentBlock.OrderedList(_, _, attrs)   => attrs
       |    case std.content.ContentBlock.Image(_, _, _, attrs)      => attrs
       |    case std.content.ContentBlock.Table(_, _, _, attrs)      => attrs
       |    case std.content.ContentBlock.Embedded(_, _, _, _, attrs) => attrs
       |
       |def _ssc_content_string_attr(attrs: Map[String, std.content.ContentValue], name: String): Option[String] =
       |  attrs.get(name).collect { case std.content.ContentValue.Str(value) => value }
       |
       |def contentSection(id: String): Option[std.content.SectionContent] =
       |  _ssc_content_sections.filter(_.id == id) match
       |    case Nil => None
       |    case section :: Nil => Some(section)
       |    case _ => throw RuntimeException(s"contentSection: duplicate section id '$$id'")
       |
       |def contentModuleSection(namespace: String, id: String): Option[std.content.SectionContent] =
       |  _ssc_content_imported_document(namespace, "contentModuleSection(namespace, id)").flatMap { doc =>
       |    _ssc_content_sections_deep(doc).filter(_.id == id) match
       |      case Nil => None
       |      case section :: Nil => Some(section)
       |      case _ => throw RuntimeException(s"contentSection: duplicate section id '$$id'")
       |  }
       |
       |def contentBlock(id: String): Option[std.content.ContentBlock] =
       |  _ssc_content_blocks_deep(_ssc_content_document)
       |    .filter(block => _ssc_content_string_attr(_ssc_content_block_attrs(block), "id").contains(id)) match
       |      case Nil => None
       |      case block :: Nil => Some(block)
       |      case _ => throw RuntimeException(s"contentBlock: duplicate block id '$$id'")
       |
       |def contentModuleBlock(namespace: String, id: String): Option[std.content.ContentBlock] =
       |  _ssc_content_imported_document(namespace, "contentModuleBlock(namespace, id)").flatMap { doc =>
       |    _ssc_content_blocks_deep(doc)
       |      .filter(block => _ssc_content_string_attr(_ssc_content_block_attrs(block), "id").contains(id)) match
       |        case Nil => None
       |        case block :: Nil => Some(block)
       |        case _ => throw RuntimeException(s"contentBlock: duplicate block id '$$id'")
       |  }
       |
       |def contentData(id: String): Option[std.content.ContentValue] =
       |  val matches = _ssc_content_blocks_deep(_ssc_content_document).collect {
       |    case block @ std.content.ContentBlock.Embedded(_, _, std.content.EmbeddedKind.StructuredData, Some(data), _)
       |        if _ssc_content_string_attr(_ssc_content_block_attrs(block), "id").contains(id) =>
       |      data
       |  }
       |  matches match
       |    case Nil => None
       |    case value :: Nil => Some(value)
       |    case _ => throw RuntimeException(s"contentData: duplicate structured data id '$$id'")
       |
       |def contentModuleData(namespace: String, id: String): Option[std.content.ContentValue] =
       |  val doc = _ssc_content_imported_document(namespace, "contentModuleData(namespace, id)")
       |  doc.flatMap { selected =>
       |    val matches = _ssc_content_blocks_deep(selected).collect {
       |      case block @ std.content.ContentBlock.Embedded(_, _, std.content.EmbeddedKind.StructuredData, Some(data), _)
       |          if _ssc_content_string_attr(_ssc_content_block_attrs(block), "id").contains(id) =>
       |        data
       |    }
       |    matches match
       |      case Nil => None
       |      case value :: Nil => Some(value)
       |      case _ => throw RuntimeException(s"contentData: duplicate structured data id '$$id'")
       |  }
       |
       |def _ssc_content_metadata_segments(path: String): List[String] =
       |  val trimmed = path.trim
       |  if trimmed.isEmpty || trimmed.startsWith(".") || trimmed.endsWith(".") || trimmed.contains("..") then
       |    throw RuntimeException("contentMetadata: path must be non-empty dot-separated segments")
       |  trimmed.split("\\\\.").toList
       |
       |def _ssc_content_metadata_path(value: std.content.ContentValue, segments: List[String]): Option[std.content.ContentValue] =
       |  segments match
       |    case Nil => Some(value)
       |    case segment :: rest =>
       |      value match
       |        case std.content.ContentValue.MapV(values) =>
       |          values.get(segment).flatMap(_ssc_content_metadata_path(_, rest))
       |        case _ =>
       |          None
       |
       |def contentMetadata(path: String): Option[std.content.ContentValue] =
       |  _ssc_content_document.manifest match
       |    case std.content.ContentValue.MapV(root) =>
       |      root.get("content").flatMap(value => _ssc_content_metadata_path(value, _ssc_content_metadata_segments(path)))
       |    case _ =>
       |      None
       |
       |def contentModuleMetadata(namespace: String, path: String): Option[std.content.ContentValue] =
       |  _ssc_content_imported_document(namespace, "contentModuleMetadata(namespace, path)").flatMap { doc =>
       |    doc.manifest match
       |      case std.content.ContentValue.MapV(root) =>
       |        root.get("content").flatMap(value => _ssc_content_metadata_path(value, _ssc_content_metadata_segments(path)))
       |      case _ =>
       |        None
       |  }
       |
       |def contentBind(value: Any, bindings: std.content.ContentValue): Any =
       |  bindings match
       |    case root @ std.content.ContentValue.MapV(_) =>
       |      value match
       |        case doc: std.content.DocumentContent => _ssc_content_bind_document(doc, root)
       |        case section: std.content.SectionContent => _ssc_content_bind_section(section, root)
       |        case block: std.content.ContentBlock => _ssc_content_bind_block(block, root)
       |        case other => throw RuntimeException("contentBind: expected DocumentContent, SectionContent, or ContentBlock, got " + String.valueOf(other))
       |    case other =>
       |      throw RuntimeException("contentBind: bindings expected ContentValue.MapV, got " + String.valueOf(other))
       |
       |def _ssc_content_bind_document(doc: std.content.DocumentContent, bindings: std.content.ContentValue.MapV): std.content.DocumentContent =
       |  std.content.DocumentContent(
       |    doc.manifest,
       |    doc.title,
       |    doc.description,
       |    doc.attrs,
       |    doc.sections.map(_ssc_content_bind_section(_, bindings)),
       |    doc.blocks.map(_ssc_content_bind_block(_, bindings))
       |  )
       |
       |def _ssc_content_bind_section(section: std.content.SectionContent, bindings: std.content.ContentValue.MapV): std.content.SectionContent =
       |  std.content.SectionContent(
       |    section.id,
       |    section.level,
       |    section.title,
       |    section.attrs,
       |    section.blocks.map(_ssc_content_bind_block(_, bindings)),
       |    section.children.map(_ssc_content_bind_section(_, bindings))
       |  )
       |
       |def _ssc_content_bind_block(block: std.content.ContentBlock, bindings: std.content.ContentValue.MapV): std.content.ContentBlock =
       |  block match
       |    case std.content.ContentBlock.Paragraph(inlines, attrs) =>
       |      std.content.ContentBlock.Paragraph(inlines.map(_ssc_content_bind_inline(_, bindings)), attrs)
       |    case std.content.ContentBlock.BulletList(items, attrs) =>
       |      std.content.ContentBlock.BulletList(items.map(_.map(_ssc_content_bind_block(_, bindings))), attrs)
       |    case std.content.ContentBlock.OrderedList(items, start, attrs) =>
       |      std.content.ContentBlock.OrderedList(items.map(_.map(_ssc_content_bind_block(_, bindings))), start, attrs)
       |    case image @ std.content.ContentBlock.Image(_, _, _, _) =>
       |      image
       |    case std.content.ContentBlock.Table(headers, rows, alignments, attrs) =>
       |      std.content.ContentBlock.Table(
       |        headers.map(_.map(_ssc_content_bind_inline(_, bindings))),
       |        rows.map(_.map(_.map(_ssc_content_bind_inline(_, bindings)))),
       |        alignments,
       |        attrs
       |      )
       |    case embedded @ std.content.ContentBlock.Embedded(_, _, _, _, _) =>
       |      embedded
       |
       |def _ssc_content_bind_inline(inline: std.content.ContentInline, bindings: std.content.ContentValue.MapV): std.content.ContentInline =
       |  inline match
       |    case text @ std.content.ContentInline.Text(_) => text
       |    case std.content.ContentInline.Emphasis(children) =>
       |      std.content.ContentInline.Emphasis(children.map(_ssc_content_bind_inline(_, bindings)))
       |    case std.content.ContentInline.Strong(children) =>
       |      std.content.ContentInline.Strong(children.map(_ssc_content_bind_inline(_, bindings)))
       |    case code @ std.content.ContentInline.Code(_) => code
       |    case std.content.ContentInline.Link(label, href, title) =>
       |      std.content.ContentInline.Link(label.map(_ssc_content_bind_inline(_, bindings)), href, title)
       |    case expr @ std.content.ContentInline.Expr(source) =>
       |      _ssc_content_bind_lookup(bindings, source)
       |        .map(value => std.content.ContentInline.Text(_ssc_content_value_text(value)))
       |        .getOrElse(expr)
       |
       |def _ssc_content_bind_lookup(bindings: std.content.ContentValue.MapV, source: String): Option[std.content.ContentValue] =
       |  if !source.matches("^[A-Za-z_][A-Za-z0-9_]*(\\\\.[A-Za-z_][A-Za-z0-9_]*)*$$") then None
       |  else
       |    source.split("\\\\.").toList match
       |      case head :: tail => bindings.values.get(head).flatMap(_ssc_content_bind_lookup(_, tail))
       |      case Nil => None
       |
       |def _ssc_content_bind_lookup(value: std.content.ContentValue, segments: List[String]): Option[std.content.ContentValue] =
       |  segments match
       |    case Nil => Some(value)
       |    case head :: tail =>
       |      value match
       |        case std.content.ContentValue.MapV(values) => values.get(head).flatMap(_ssc_content_bind_lookup(_, tail))
       |        case _ => None
       |
       |def _ssc_content_value_text(value: std.content.ContentValue): String =
       |  value match
       |    case std.content.ContentValue.Str(v) => v
       |    case std.content.ContentValue.Bool(v) => v.toString
       |    case std.content.ContentValue.Num(v) => _ssc_content_number_string(v)
       |    case std.content.ContentValue.NullV => ""
       |    case std.content.ContentValue.ListV(values) => values.map(_ssc_content_value_text).mkString(", ")
       |    case std.content.ContentValue.MapV(values) =>
       |      values.toList.sortBy(_._1).map { case (key, v) => key + ": " + _ssc_content_value_text(v) }.mkString("{", ", ", "}")
       |
       |def contentPlainText(value: Any): String =
       |  value match
       |    case section: std.content.SectionContent => _ssc_content_section_plain_text(section)
       |    case block: std.content.ContentBlock => _ssc_content_block_plain_text(block)
       |    case other => throw RuntimeException(s"contentPlainText: expected SectionContent or ContentBlock, got $$other")
       |
       |def _ssc_content_section_plain_text(section: std.content.SectionContent): String =
       |  (section.title :: (section.blocks.map(_ssc_content_block_plain_text) ++ section.children.map(_ssc_content_section_plain_text)))
       |    .filter(_.nonEmpty)
       |    .mkString("\\n")
       |
       |def _ssc_content_block_list_plain_text(blocks: List[std.content.ContentBlock]): String =
       |  blocks.map(_ssc_content_block_plain_text).filter(_.nonEmpty).mkString(" ")
       |
       |def _ssc_content_block_plain_text(block: std.content.ContentBlock): String =
       |  block match
       |    case std.content.ContentBlock.Paragraph(inlines, _) =>
       |      inlines.map(_ssc_content_inline_plain_text).mkString
       |    case std.content.ContentBlock.BulletList(items, _) =>
       |      items.map(item => "- " + _ssc_content_block_list_plain_text(item)).filter(_.trim.nonEmpty).mkString("\\n")
       |    case std.content.ContentBlock.OrderedList(items, start, _) =>
       |      items.zipWithIndex.map { case (item, idx) =>
       |        s"$${start + idx}. " + _ssc_content_block_list_plain_text(item)
       |      }.filter(_.trim.nonEmpty).mkString("\\n")
       |    case std.content.ContentBlock.Image(src, alt, _, _) =>
       |      if alt.isEmpty then src else alt
       |    case std.content.ContentBlock.Table(headers, rows, _, _) =>
       |      _ssc_content_table_plain_text(headers, rows)
       |    case std.content.ContentBlock.Embedded(lang, source, _, _, _) =>
       |      if lang.isEmpty then source else s"$$lang: $$source"
       |
       |def _ssc_content_table_plain_text(headers: List[List[std.content.ContentInline]], rows: List[List[List[std.content.ContentInline]]]): String =
       |  (headers.map(_ssc_content_table_cell_plain_text).mkString(" | ") ::
       |    rows.map(row => row.map(_ssc_content_table_cell_plain_text).mkString(" | ")))
       |    .filter(_.trim.nonEmpty)
       |    .mkString("\\n")
       |
       |def _ssc_content_table_cell_plain_text(cell: List[std.content.ContentInline]): String =
       |  cell.map(_ssc_content_inline_plain_text).mkString
       |
       |def _ssc_content_inline_plain_text(inline: std.content.ContentInline): String =
       |  inline match
       |    case std.content.ContentInline.Text(value) => value
       |    case std.content.ContentInline.Emphasis(children) => children.map(_ssc_content_inline_plain_text).mkString
       |    case std.content.ContentInline.Strong(children) => children.map(_ssc_content_inline_plain_text).mkString
       |    case std.content.ContentInline.Code(value) => s"`$$value`"
       |    case std.content.ContentInline.Link(label, href, _) =>
       |      label.map(_ssc_content_inline_plain_text).mkString + s" ($$href)"
       |    case std.content.ContentInline.Expr(source) => "$${" + source + "}"
       |
       |def contentToMarkdown(value: Any): String =
       |  value match
       |    case doc: std.content.DocumentContent => _ssc_content_document_markdown(doc)
       |    case section: std.content.SectionContent => _ssc_content_section_markdown(section)
       |    case block: std.content.ContentBlock => _ssc_content_block_markdown(block)
       |    case other => throw RuntimeException("contentToMarkdown: expected DocumentContent, SectionContent, or ContentBlock, got " + String.valueOf(other))
       |
       |def _ssc_content_document_markdown(doc: std.content.DocumentContent): String =
       |  val frontMatter = _ssc_content_manifest_markdown(doc.manifest).toList
       |  (frontMatter ++ doc.blocks.map(_ssc_content_block_markdown) ++ doc.sections.map(_ssc_content_section_markdown))
       |    .filter(_.nonEmpty)
       |    .mkString("\\n\\n")
       |
       |def _ssc_content_manifest_markdown(value: std.content.ContentValue): Option[String] =
       |  value match
       |    case std.content.ContentValue.MapV(values) if values.nonEmpty =>
       |      Some("---\\n" + _ssc_content_yaml_map_lines(values, 0).mkString("\\n") + "\\n---")
       |    case _ => None
       |
       |def _ssc_content_section_markdown(section: std.content.SectionContent): String =
       |  val title = _ssc_content_markdown_text(section.title)
       |  val heading = ("#" * _ssc_content_heading_level(section.level)) + " " + title + _ssc_content_heading_attrs(section.id, section.attrs)
       |  (heading :: (section.blocks.map(_ssc_content_block_markdown) ++ section.children.map(_ssc_content_section_markdown)))
       |    .filter(_.nonEmpty)
       |    .mkString("\\n\\n")
       |
       |def _ssc_content_block_markdown(block: std.content.ContentBlock): String =
       |  val body = block match
       |    case std.content.ContentBlock.Paragraph(inlines, _) =>
       |      inlines.map(_ssc_content_inline_markdown).mkString
       |    case std.content.ContentBlock.BulletList(items, _) =>
       |      items.map(item => _ssc_content_list_item_markdown("- ", item)).mkString("\\n")
       |    case std.content.ContentBlock.OrderedList(items, start, _) =>
       |      items.zipWithIndex.map { case (item, idx) => _ssc_content_list_item_markdown((start + idx).toString + ". ", item) }.mkString("\\n")
       |    case std.content.ContentBlock.Image(src, alt, title, _) =>
       |      val titlePart = title.map(t => " " + _ssc_content_quote_markdown_attr(t)).getOrElse("")
       |      "![" + _ssc_content_markdown_text(alt) + "](" + src + titlePart + ")"
       |    case std.content.ContentBlock.Table(headers, rows, alignments, _) =>
       |      _ssc_content_table_markdown(headers, rows, alignments)
       |    case block @ std.content.ContentBlock.Embedded(_, _, _, _, _) =>
       |      _ssc_content_embedded_markdown(block)
       |  block match
       |    case std.content.ContentBlock.Embedded(_, _, _, _, _) => body
       |    case _ => _ssc_content_metadata_directive(_ssc_content_block_attrs(block)).map(_ + "\\n" + body).getOrElse(body)
       |
       |def _ssc_content_list_item_markdown(prefix: String, blocks: List[std.content.ContentBlock]): String =
       |  val body = blocks.map(_ssc_content_block_markdown).filter(_.nonEmpty).mkString("\\n\\n")
       |  val lines = body.split("\\n", -1).toList
       |  if body.isEmpty then prefix.trim
       |  else prefix + lines.head + lines.tail.map(line => "\\n  " + line).mkString
       |
       |def _ssc_content_table_markdown(headers: List[List[std.content.ContentInline]], rows: List[List[List[std.content.ContentInline]]], alignments: List[String]): String =
       |  val headerCells = headers.map(_ssc_content_table_cell_markdown)
       |  val bodyRows = rows.map(row => row.map(_ssc_content_table_cell_markdown))
       |  val colCount = (List(headerCells.length, alignments.length) ++ bodyRows.map(_.length)).max
       |  val separator = (0 until colCount).toList.map(idx => _ssc_content_table_separator(alignments.lift(idx).getOrElse("default")))
       |  val renderedRows = bodyRows.map(row => _ssc_content_markdown_table_row(_ssc_content_pad_table_cells(row, colCount)))
       |  (_ssc_content_markdown_table_row(_ssc_content_pad_table_cells(headerCells, colCount)) ::
       |    _ssc_content_markdown_table_row(separator) ::
       |    renderedRows).mkString("\\n")
       |
       |def _ssc_content_table_cell_markdown(cell: List[std.content.ContentInline]): String =
       |  cell.map(_ssc_content_inline_markdown).mkString.replace("\\n", " ").replace("|", "\\\\|")
       |
       |def _ssc_content_pad_table_cells(cells: List[String], size: Int): List[String] =
       |  cells ++ List.fill(math.max(0, size - cells.length))("")
       |
       |def _ssc_content_markdown_table_row(cells: List[String]): String =
       |  cells.mkString("| ", " | ", " |")
       |
       |def _ssc_content_table_separator(alignment: String): String =
       |  alignment match
       |    case "left" => ":---"
       |    case "center" => ":---:"
       |    case "right" => "---:"
       |    case _ => "---"
       |
       |def _ssc_content_embedded_markdown(block: std.content.ContentBlock.Embedded): String =
       |  val info = (block.lang :: _ssc_content_fence_attr_tokens(block.attrs)).filter(_.nonEmpty).mkString(" ")
       |  val fence = _ssc_content_fence_delimiter(block.source)
       |  val body = if block.source.endsWith("\\n") then block.source else block.source + "\\n"
       |  if info.isEmpty then fence + "\\n" + body + fence else fence + info + "\\n" + body + fence
       |
       |def _ssc_content_inline_markdown(inline: std.content.ContentInline): String =
       |  inline match
       |    case std.content.ContentInline.Text(value) => _ssc_content_markdown_text(value)
       |    case std.content.ContentInline.Emphasis(children) => "*" + children.map(_ssc_content_inline_markdown).mkString + "*"
       |    case std.content.ContentInline.Strong(children) => "**" + children.map(_ssc_content_inline_markdown).mkString + "**"
       |    case std.content.ContentInline.Code(value) => _ssc_content_inline_code_markdown(value)
       |    case std.content.ContentInline.Link(label, href, title) =>
       |      val titlePart = title.map(t => " " + _ssc_content_quote_markdown_attr(t)).getOrElse("")
       |      "[" + label.map(_ssc_content_inline_markdown).mkString + "](" + href + titlePart + ")"
       |    case std.content.ContentInline.Expr(source) => "$${" + source + "}"
       |
       |def _ssc_content_heading_attrs(id: String, attrs: Map[String, std.content.ContentValue]): String =
       |  val idToken = if id.nonEmpty then List("#" + id) else Nil
       |  val classTokens = attrs.get("class").collect { case std.content.ContentValue.Str(value) => value.split("\\\\s+").toList.filter(_.nonEmpty).map("." + _) }.getOrElse(Nil)
       |  val rest = attrs.toList.filterNot(_._1 == "class").sortBy(_._1).flatMap { case (key, value) => _ssc_content_attr_token(key, value, "") }
       |  val tokens = idToken ++ classTokens ++ rest
       |  if tokens.isEmpty then "" else tokens.mkString(" {", " ", "}")
       |
       |def _ssc_content_metadata_directive(attrs: Map[String, std.content.ContentValue]): Option[String] =
       |  val tokens = attrs.toList.sortBy(_._1).flatMap { case (key, value) => _ssc_content_attr_token(key, value, "") }
       |  if tokens.isEmpty then None else Some(tokens.mkString("<!-- @meta ", " ", " -->"))
       |
       |def _ssc_content_fence_attr_tokens(attrs: Map[String, std.content.ContentValue]): List[String] =
       |  val id = attrs.get("id").flatMap(value => _ssc_content_attr_token("id", value, "@"))
       |  val rest = attrs.toList.filterNot(_._1 == "id").sortBy(_._1).flatMap { case (key, value) => _ssc_content_attr_token(key, value, "@") }
       |  id.toList ++ rest
       |
       |def _ssc_content_attr_token(key: String, value: std.content.ContentValue, prefix: String): Option[String] =
       |  value match
       |    case std.content.ContentValue.Bool(v) =>
       |      if prefix.isEmpty && v then Some(key) else Some(prefix + key + "=" + v.toString)
       |    case std.content.ContentValue.Str(v) =>
       |      Some(prefix + key + "=" + _ssc_content_attr_scalar(v))
       |    case std.content.ContentValue.Num(v) =>
       |      Some(prefix + key + "=" + _ssc_content_number_string(v))
       |    case std.content.ContentValue.NullV =>
       |      Some(prefix + key + "=null")
       |    case other =>
       |      Some(prefix + key + "=" + _ssc_content_attr_scalar(_ssc_content_yaml_scalar(other)))
       |
       |def _ssc_content_yaml_map_lines(values: Map[String, std.content.ContentValue], indent: Int): List[String] =
       |  values.toList.sortBy(_._1).flatMap { case (key, value) => _ssc_content_yaml_key_value_lines(key, value, indent) }
       |
       |def _ssc_content_yaml_key_value_lines(key: String, value: std.content.ContentValue, indent: Int): List[String] =
       |  val pad = " " * indent
       |  value match
       |    case std.content.ContentValue.MapV(values) =>
       |      if values.isEmpty then List(pad + _ssc_content_yaml_key(key) + ": {}")
       |      else (pad + _ssc_content_yaml_key(key) + ":") :: _ssc_content_yaml_map_lines(values, indent + 2)
       |    case std.content.ContentValue.ListV(values) =>
       |      if values.isEmpty then List(pad + _ssc_content_yaml_key(key) + ": []")
       |      else (pad + _ssc_content_yaml_key(key) + ":") :: _ssc_content_yaml_list_lines(values, indent + 2)
       |    case _ =>
       |      List(pad + _ssc_content_yaml_key(key) + ": " + _ssc_content_yaml_scalar(value))
       |
       |def _ssc_content_yaml_list_lines(values: List[std.content.ContentValue], indent: Int): List[String] =
       |  val pad = " " * indent
       |  values.flatMap {
       |    case std.content.ContentValue.MapV(values) =>
       |      if values.isEmpty then List(pad + "- {}") else (pad + "-") :: _ssc_content_yaml_map_lines(values, indent + 2)
       |    case std.content.ContentValue.ListV(values) =>
       |      if values.isEmpty then List(pad + "- []") else (pad + "-") :: _ssc_content_yaml_list_lines(values, indent + 2)
       |    case value =>
       |      List(pad + "- " + _ssc_content_yaml_scalar(value))
       |  }
       |
       |def _ssc_content_yaml_scalar(value: std.content.ContentValue): String =
       |  value match
       |    case std.content.ContentValue.Str(v) => _ssc_content_yaml_string(v)
       |    case std.content.ContentValue.Bool(v) => v.toString
       |    case std.content.ContentValue.Num(v) => _ssc_content_number_string(v)
       |    case std.content.ContentValue.NullV => "null"
       |    case std.content.ContentValue.MapV(values) => if values.isEmpty then "{}" else _ssc_content_quote_markdown_attr(value.toString)
       |    case std.content.ContentValue.ListV(values) => if values.isEmpty then "[]" else _ssc_content_quote_markdown_attr(value.toString)
       |
       |def _ssc_content_heading_level(value: Int): Int =
       |  math.max(1, math.min(6, value))
       |
       |def _ssc_content_number_string(value: Double): String =
       |  if value.isWhole && value >= Long.MinValue.toDouble && value <= Long.MaxValue.toDouble then value.toLong.toString
       |  else value.toString
       |
       |def _ssc_content_yaml_key(value: String): String =
       |  if _ssc_content_safe_bare_scalar(value) then value else _ssc_content_quote_markdown_attr(value)
       |
       |def _ssc_content_yaml_string(value: String): String =
       |  if _ssc_content_safe_bare_scalar(value) then value else _ssc_content_quote_markdown_attr(value)
       |
       |def _ssc_content_attr_scalar(value: String): String =
       |  if _ssc_content_safe_attr_scalar(value) then value else _ssc_content_quote_markdown_attr(value)
       |
       |def _ssc_content_quote_markdown_attr(value: String): String =
       |  val slash = 92.toChar.toString
       |  val quote = 34.toChar.toString
       |  val escaped = value
       |    .replace(slash, slash + slash)
       |    .replace(quote, slash + quote)
       |    .replace(10.toChar.toString, slash + "n")
       |    .replace(13.toChar.toString, slash + "r")
       |    .replace(9.toChar.toString, slash + "t")
       |  quote + escaped + quote
       |
       |def _ssc_content_safe_bare_scalar(value: String): Boolean =
       |  value.nonEmpty &&
       |    !Set("true", "false", "null").contains(value.toLowerCase(java.util.Locale.ROOT)) &&
       |    value.forall(ch => ch.isLetterOrDigit || ch == '-' || ch == '_' || ch == '.' || ch == '/' || ch == ':' || ch == '@')
       |
       |def _ssc_content_safe_attr_scalar(value: String): Boolean =
       |  value.nonEmpty && value.forall(ch => ch.isLetterOrDigit || ch == '-' || ch == '_' || ch == '.' || ch == '/' || ch == ':' || ch == '@')
       |
       |def _ssc_content_markdown_text(value: String): String = value
       |
       |def _ssc_content_inline_code_markdown(value: String): String =
       |  val delimiter = "`" * math.max(1, _ssc_content_max_backtick_run(value) + 1)
       |  delimiter + value + delimiter
       |
       |def _ssc_content_fence_delimiter(source: String): String =
       |  "`" * math.max(3, _ssc_content_max_backtick_run(source) + 1)
       |
       |def _ssc_content_max_backtick_run(value: String): Int =
       |  var best = 0
       |  var current = 0
       |  value.foreach { ch =>
       |    if ch == '`' then
       |      current += 1
       |      if current > best then best = current
       |    else current = 0
       |  }
       |  best
       |""".stripMargin +
    (if includeToolkit then
      s"""
       |def _ssc_tk_error(msg: String): Nothing = throw RuntimeException(msg)
       |
       |def _ssc_tk_str(value: std.content.ContentValue, ctx: String): String =
       |  value match
       |    case std.content.ContentValue.Str(v) => v
       |    case other => _ssc_tk_error(s"contentToolkitNode: $$ctx expected String, got $$other")
       |
       |def _ssc_tk_bool(value: std.content.ContentValue, ctx: String): Boolean =
       |  value match
       |    case std.content.ContentValue.Bool(v) => v
       |    case other => _ssc_tk_error(s"contentToolkitNode: $$ctx expected Boolean, got $$other")
       |
       |def _ssc_tk_int(value: std.content.ContentValue, ctx: String): Int =
       |  value match
       |    case std.content.ContentValue.Num(v) => v.toInt
       |    case other => _ssc_tk_error(s"contentToolkitNode: $$ctx expected Number, got $$other")
       |
       |def _ssc_tk_scalar(value: std.content.ContentValue, ctx: String): Any =
       |  value match
       |    case std.content.ContentValue.Str(v) => v
       |    case std.content.ContentValue.Bool(v) => v
       |    case std.content.ContentValue.Num(v) => if v.isValidInt then v.toInt else v
       |    case std.content.ContentValue.NullV => null
       |    case other => _ssc_tk_error(s"contentToolkitNode: $$ctx expected scalar, got $$other")
       |
       |def _ssc_tk_map(value: std.content.ContentValue, ctx: String): Map[String, std.content.ContentValue] =
       |  value match
       |    case std.content.ContentValue.MapV(values) => values
       |    case other => _ssc_tk_error(s"contentToolkitNode: $$ctx expected object, got $$other")
       |
       |def _ssc_tk_list(value: std.content.ContentValue, ctx: String): List[std.content.ContentValue] =
       |  value match
       |    case std.content.ContentValue.ListV(values) => values
       |    case other => _ssc_tk_error(s"contentToolkitNode: $$ctx expected list, got $$other")
       |
       |def _ssc_tk_field(obj: Map[String, std.content.ContentValue], name: String, ctx: String): std.content.ContentValue =
       |  obj.getOrElse(name, _ssc_tk_error(s"contentToolkitNode: $$ctx requires $$name"))
       |
       |def _ssc_tk_opt_str(obj: Map[String, std.content.ContentValue], name: String): Option[String] =
       |  obj.get(name).map(_ssc_tk_str(_, name))
       |
       |def _ssc_tk_opt_bool(obj: Map[String, std.content.ContentValue], name: String, default: Boolean = false): Boolean =
       |  obj.get(name).map(_ssc_tk_bool(_, name)).getOrElse(default)
       |
       |def _ssc_tk_opt_int(obj: Map[String, std.content.ContentValue], name: String, default: Int): Int =
       |  obj.get(name).map(_ssc_tk_int(_, name)).getOrElse(default)
       |
       |def _ssc_tk_signals(root: Map[String, std.content.ContentValue]): Map[String, Any] =
       |  root.get("signals") match
       |    case Some(std.content.ContentValue.MapV(values)) =>
       |      values.map { case (name, value) => name -> std.ui.primitives.signal(name, _ssc_tk_scalar(value, s"signal '$$name' default")) }
       |    case Some(other) => _ssc_tk_error(s"contentToolkitNode: signals expected object, got $$other")
       |    case None => Map.empty
       |
       |def _ssc_tk_signal(env: Map[String, Any], name: String, ctx: String): Any =
       |  env.getOrElse(name, _ssc_tk_error(s"contentToolkitNode: $$ctx references unknown signal '$$name'"))
       |
       |case class _SscTkLink(kind: String, query: Map[String, String], label: String)
       |
       |def _ssc_tk_decode(value: String): String =
       |  java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8)
       |
       |def _ssc_tk_normalize_kind(value: String): String =
       |  value.toLowerCase(java.util.Locale.ROOT).filter(ch => ch != '-' && ch != '_')
       |
       |def _ssc_tk_parse_link(href: String, label: String): _SscTkLink =
       |  val raw = href.stripPrefix("toolkit:")
       |  val question = raw.indexOf('?')
       |  val kindPart = if question < 0 then raw else raw.take(question)
       |  val queryPart = if question < 0 then "" else raw.drop(question + 1)
       |  val kind = _ssc_tk_normalize_kind(_ssc_tk_decode(kindPart))
       |  if kind.isEmpty then _ssc_tk_error("contentToolkitNode: toolkit link requires a control kind")
       |  val query =
       |    if queryPart.isEmpty then Map.empty[String, String]
       |    else queryPart.split("&").toList.filter(_.nonEmpty).map { pair =>
       |      val eq = pair.indexOf('=')
       |      if eq < 0 then _ssc_tk_decode(pair) -> ""
       |      else _ssc_tk_decode(pair.take(eq)) -> _ssc_tk_decode(pair.drop(eq + 1))
       |    }.toMap
       |  _SscTkLink(kind, query, label)
       |
       |def _ssc_tk_single_link(inlines: List[std.content.ContentInline]): Option[_SscTkLink] =
       |  val significant = inlines.filter {
       |    case std.content.ContentInline.Text(value) => value.trim.nonEmpty
       |    case _ => true
       |  }
       |  significant match
       |    case std.content.ContentInline.Link(label, href, _) :: Nil if href.startsWith("toolkit:") =>
       |      Some(_ssc_tk_parse_link(href, label.map(_ssc_content_inline_plain_text).mkString))
       |    case _ => None
       |
       |def _ssc_tk_link_bool_opt(link: _SscTkLink, name: String): Option[Boolean] =
       |  link.query.get(name).map {
       |    case "true" => true
       |    case "false" => false
       |    case other => _ssc_tk_error("contentToolkitNode: toolkit:" + link.kind + " " + name + " expected true or false, got '" + other + "'")
       |  }
       |
       |def _ssc_tk_link_bool(link: _SscTkLink, name: String, default: Boolean): Boolean =
       |  _ssc_tk_link_bool_opt(link, name).getOrElse(default)
       |
       |def _ssc_tk_link_label(link: _SscTkLink): String =
       |  link.query.getOrElse("label", link.label)
       |
       |def _ssc_tk_required_query(link: _SscTkLink, name: String): String =
       |  link.query.get(name).filter(_.nonEmpty).getOrElse(
       |    _ssc_tk_error("contentToolkitNode: toolkit:" + link.kind + " requires " + name))
       |
       |def _ssc_tk_link_literal(value: String): Any =
       |  value match
       |    case "true" => true
       |    case "false" => false
       |    case other => other
       |
       |def _ssc_tk_link_signal_default(link: _SscTkLink): Option[(String, Any)] =
       |  link.query.get("signal").map { name =>
       |    val initial: Any = link.kind match
       |      case "textfield" | "input" => link.query.getOrElse("value", "")
       |      case "checkbox" => _ssc_tk_link_bool_opt(link, "checked").orElse(_ssc_tk_link_bool_opt(link, "value")).getOrElse(false)
       |      case "button" | "signalbutton" => false
       |      case "signaltext" => link.query.getOrElse("value", "")
       |      case _ => ""
       |    name -> initial
       |  }
       |
       |def _ssc_tk_link_node(link: _SscTkLink, env: Map[String, Any]): std.ui.nodes.TkNode =
       |  link.kind match
       |    case "textfield" | "input" =>
       |      std.ui.nodes.TextFieldNode(
       |        _ssc_tk_signal(env, _ssc_tk_required_query(link, "signal"), "toolkit:textField"),
       |        _ssc_tk_link_label(link),
       |        _ssc_tk_link_bool(link, "disabled", false),
       |        _ssc_tk_link_bool(link, "required", false))
       |    case "checkbox" =>
       |      std.ui.nodes.CheckboxNode(
       |        _ssc_tk_signal(env, _ssc_tk_required_query(link, "signal"), "toolkit:checkbox"),
       |        _ssc_tk_link_label(link),
       |        _ssc_tk_link_bool(link, "disabled", false))
       |    case "button" | "signalbutton" =>
       |      val signal = _ssc_tk_signal(env, _ssc_tk_required_query(link, "signal"), "toolkit:button")
       |      val value = _ssc_tk_link_literal(link.query.getOrElse("value", "true"))
       |      val disabled = _ssc_tk_link_bool(link, "disabled", false)
       |      link.query.get("enabledWhen") match
       |        case Some(name) =>
       |          std.ui.nodes.ShowWhenNode(
       |            _ssc_tk_signal(env, name, "toolkit:button.enabledWhen"),
       |            std.ui.nodes.SignalButtonNode(signal, value, _ssc_tk_link_label(link), disabled),
       |            std.ui.nodes.SignalButtonNode(signal, value, _ssc_tk_link_label(link), true))
       |        case None =>
       |          std.ui.nodes.SignalButtonNode(signal, value, _ssc_tk_link_label(link), disabled)
       |    case "signaltext" =>
       |      std.ui.nodes.SignalTextNode(_ssc_tk_signal(env, _ssc_tk_required_query(link, "signal"), "toolkit:signalText"))
       |    case "badge" =>
       |      std.ui.nodes.BadgeNode(link.query.getOrElse("text", _ssc_tk_link_label(link)), link.query.getOrElse("variant", "default"))
       |    case "divider" =>
       |      std.ui.nodes.DividerNode()
       |    case other =>
       |      _ssc_tk_error("contentToolkitNode: unsupported toolkit link control '" + other + "'")
       |
       |def _ssc_tk_markdown_signal_defaults_block(block: std.content.ContentBlock): List[(String, Any)] =
       |  block match
       |    case std.content.ContentBlock.Paragraph(inlines, _) =>
       |      _ssc_tk_single_link(inlines).flatMap(_ssc_tk_link_signal_default).toList
       |    case std.content.ContentBlock.BulletList(items, _) =>
       |      items.flatten.flatMap(_ssc_tk_markdown_signal_defaults_block)
       |    case std.content.ContentBlock.OrderedList(items, _, _) =>
       |      items.flatten.flatMap(_ssc_tk_markdown_signal_defaults_block)
       |    case _ => Nil
       |
       |def _ssc_tk_markdown_signal_defaults_section(section: std.content.SectionContent): List[(String, Any)] =
       |  section.blocks.flatMap(_ssc_tk_markdown_signal_defaults_block) ++
       |    section.children.flatMap(_ssc_tk_markdown_signal_defaults_section)
       |
       |def _ssc_tk_markdown_signal_defaults_doc(doc: std.content.DocumentContent): List[(String, Any)] =
       |  doc.blocks.flatMap(_ssc_tk_markdown_signal_defaults_block) ++
       |    doc.sections.flatMap(_ssc_tk_markdown_signal_defaults_section)
       |
       |def _ssc_tk_markdown_env(defaults: List[(String, Any)]): Map[String, Any] =
       |  defaults.foldLeft(Map.empty[String, Any]) { case (acc, (name, initial)) =>
       |    if acc.contains(name) then acc else acc + (name -> std.ui.primitives.signal(name, initial))
       |  }
       |
       |def _ssc_tk_list_item(item: List[std.content.ContentBlock], env: Map[String, Any]): Option[std.ui.nodes.TkNode] =
       |  item match
       |    case std.content.ContentBlock.Paragraph(inlines, _) :: Nil =>
       |      _ssc_tk_single_link(inlines).map(_ssc_tk_link_node(_, env))
       |    case _ => None
       |
       |def _ssc_tk_markdown_block(block: std.content.ContentBlock, options: std.ui.content.ContentToolkitOptions, env: Map[String, Any]): Option[std.ui.nodes.TkNode] =
       |  block match
       |    case std.content.ContentBlock.Paragraph(inlines, _) =>
       |      _ssc_tk_single_link(inlines).map(_ssc_tk_link_node(_, env))
       |    case std.content.ContentBlock.BulletList(items, _) =>
       |      val rendered = items.map(item => _ssc_tk_list_item(item, env))
       |      if rendered.exists(_.isDefined) then
       |        Some(std.ui.nodes.VStackNode(options.listGap, items.zip(rendered).map {
       |          case (_, Some(node)) => node
       |          case (item, None) => std.ui.nodes.RawTextNode("- " + item.map(_ssc_content_block_plain_text).mkString(" "))
       |        }))
       |      else None
       |    case std.content.ContentBlock.OrderedList(items, start, _) =>
       |      val rendered = items.map(item => _ssc_tk_list_item(item, env))
       |      if rendered.exists(_.isDefined) then
       |        Some(std.ui.nodes.VStackNode(options.listGap, items.zip(rendered).zipWithIndex.map {
       |          case ((_, Some(node)), _) => node
       |          case ((item, None), idx) => std.ui.nodes.RawTextNode((start + idx).toString + ". " + item.map(_ssc_content_block_plain_text).mkString(" "))
       |        }))
       |      else None
       |    case _ => None
       |
       |def _ssc_tk_render_control(value: std.content.ContentValue, env: Map[String, Any]): std.ui.nodes.TkNode =
       |  val obj = _ssc_tk_map(value, "control")
       |  val kind = _ssc_tk_str(_ssc_tk_field(obj, "type", "control"), "control.type")
       |  kind match
       |    case "vstack" =>
       |      std.ui.nodes.VStackNode(_ssc_tk_opt_int(obj, "gap", 8), _ssc_tk_children(obj, env))
       |    case "hstack" =>
       |      std.ui.nodes.HStackNode(_ssc_tk_opt_int(obj, "gap", 8), _ssc_tk_children(obj, env))
       |    case "fragment" =>
       |      std.ui.nodes.FragmentNode(_ssc_tk_children(obj, env))
       |    case "divider" =>
       |      std.ui.nodes.DividerNode()
       |    case "heading" =>
       |      std.ui.nodes.HeadingNode(obj.get("level").map(_ssc_tk_int(_, "heading.level")).getOrElse(2), _ssc_tk_str(_ssc_tk_field(obj, "text", "heading"), "heading.text"))
       |    case "text" =>
       |      std.ui.nodes.TextNode_(_ssc_tk_str(_ssc_tk_field(obj, "text", "text"), "text.text"))
       |    case "rawText" =>
       |      std.ui.nodes.RawTextNode(_ssc_tk_str(_ssc_tk_field(obj, "text", "rawText"), "rawText.text"))
       |    case "signalText" =>
       |      val name = _ssc_tk_str(_ssc_tk_field(obj, "signal", "signalText"), "signalText.signal")
       |      std.ui.nodes.SignalTextNode(_ssc_tk_signal(env, name, "signalText"))
       |    case "show" =>
       |      val name = _ssc_tk_str(_ssc_tk_field(obj, "signal", "show"), "show.signal")
       |      val whenTrue = _ssc_tk_render_control(_ssc_tk_field(obj, "then", "show"), env)
       |      val whenFalse = obj.get("else").map(_ssc_tk_render_control(_, env)).getOrElse(std.ui.nodes.FragmentNode(Nil))
       |      std.ui.nodes.ShowWhenNode(_ssc_tk_signal(env, name, "show"), whenTrue, whenFalse)
       |    case "textField" =>
       |      val name = _ssc_tk_str(_ssc_tk_field(obj, "signal", "textField"), "textField.signal")
       |      std.ui.nodes.TextFieldNode(_ssc_tk_signal(env, name, "textField"), _ssc_tk_str(_ssc_tk_field(obj, "label", "textField"), "textField.label"), _ssc_tk_opt_bool(obj, "disabled"), _ssc_tk_opt_bool(obj, "required"))
       |    case "checkbox" =>
       |      val name = _ssc_tk_str(_ssc_tk_field(obj, "signal", "checkbox"), "checkbox.signal")
       |      std.ui.nodes.CheckboxNode(_ssc_tk_signal(env, name, "checkbox"), _ssc_tk_str(_ssc_tk_field(obj, "label", "checkbox"), "checkbox.label"), _ssc_tk_opt_bool(obj, "disabled"))
       |    case "button" =>
       |      val name = _ssc_tk_str(_ssc_tk_field(obj, "signal", "button"), "button.signal")
       |      val value = obj.get("value").map(_ssc_tk_scalar(_, "button.value")).getOrElse(true)
       |      std.ui.nodes.SignalButtonNode(_ssc_tk_signal(env, name, "button"), value, _ssc_tk_str(_ssc_tk_field(obj, "label", "button"), "button.label"), _ssc_tk_opt_bool(obj, "disabled"))
       |    case "badge" =>
       |      std.ui.nodes.BadgeNode(_ssc_tk_str(_ssc_tk_field(obj, "text", "badge"), "badge.text"), _ssc_tk_opt_str(obj, "variant").getOrElse("default"))
       |    case "card" =>
       |      std.ui.nodes.CardNode(Nil, _ssc_tk_children(obj, env), Nil)
       |    case other =>
       |      _ssc_tk_error(s"contentToolkitNode: unsupported control type '$$other'")
       |
       |def _ssc_tk_children(obj: Map[String, std.content.ContentValue], env: Map[String, Any]): List[std.ui.nodes.TkNode] =
       |  obj.get("children") match
       |    case Some(value) => _ssc_tk_list(value, "children").map(_ssc_tk_render_control(_, env))
       |    case None => Nil
       |
       |def _ssc_tk_yaml_block(block: std.content.ContentBlock, baseEnv: Map[String, Any]): Option[std.ui.nodes.TkNode] =
       |  block match
       |    case std.content.ContentBlock.Embedded(_, _, std.content.EmbeddedKind.StructuredData, Some(data), attrs)
       |        if _ssc_content_string_attr(attrs, "ui").contains("toolkit") =>
       |      val root = _ssc_tk_map(data, "@ui=toolkit")
       |      val env = baseEnv ++ _ssc_tk_signals(root)
       |      Some(_ssc_tk_render_control(_ssc_tk_field(root, "controls", "@ui=toolkit"), env))
       |    case _ => None
       |
       |def _ssc_tk_component_data(attrs: Map[String, std.content.ContentValue]): Option[std.content.ContentValue] =
       |  _ssc_content_string_attr(attrs, "data").flatMap(contentData)
       |
       |def _ssc_tk_component_for(name: String, options: std.ui.content.ContentToolkitOptions): Option[std.ui.content.ContentToolkitComponent] =
       |  options.components.find(_.name == name)
       |
       |def _ssc_tk_table_cell(cell: List[std.content.ContentInline]): std.ui.nodes.TkNode =
       |  std.ui.nodes.TextNode_(cell.map(_ssc_content_inline_plain_text).mkString)
       |
       |def _ssc_tk_table(headers: List[List[std.content.ContentInline]], rows: List[List[List[std.content.ContentInline]]]): std.ui.nodes.TkNode =
       |  val columns = headers.zipWithIndex.map { case (header, idx) =>
       |    std.ui.nodes.TableColumn(header.map(_ssc_content_inline_plain_text).mkString, "col" + idx.toString)
       |  }
       |  std.ui.nodes.TableNode(columns, rows.map(row => row.map(_ssc_tk_table_cell)), null)
       |
       |def _ssc_tk_block(block: std.content.ContentBlock, options: std.ui.content.ContentToolkitOptions, env: Map[String, Any]): std.ui.nodes.TkNode =
       |  val attrs = _ssc_content_block_attrs(block)
       |  _ssc_content_string_attr(attrs, "component")
       |    .flatMap(name => _ssc_tk_component_for(name, options).map(_.render(std.ui.content.ContentComponentContext(name, "block", _ssc_content_string_attr(attrs, "id").getOrElse(""), None, attrs, None, Some(block), _ssc_tk_component_data(attrs)))))
       |    .orElse(_ssc_tk_yaml_block(block, env))
       |    .orElse(_ssc_tk_markdown_block(block, options, env))
       |    .orElse(block match
       |      case std.content.ContentBlock.Table(headers, rows, _, _) => Some(_ssc_tk_table(headers, rows))
       |      case _ => None
       |    )
       |    .getOrElse(std.ui.nodes.TextNode_(_ssc_content_block_plain_text(block)))
       |
       |def _ssc_tk_section(section: std.content.SectionContent, options: std.ui.content.ContentToolkitOptions, env: Map[String, Any]): std.ui.nodes.TkNode =
       |  _ssc_content_string_attr(section.attrs, "component")
       |    .flatMap(name => _ssc_tk_component_for(name, options).map(_.render(std.ui.content.ContentComponentContext(name, "section", section.id, Some(section.title), section.attrs, Some(section), None, _ssc_tk_component_data(section.attrs)))))
       |    .getOrElse {
       |      val children = std.ui.nodes.HeadingNode(section.level, section.title) ::
       |        (section.blocks.map(_ssc_tk_block(_, options, env)) ++ section.children.map(_ssc_tk_section(_, options, env)))
       |      std.ui.nodes.VStackNode(options.blockGap, children)
       |    }
       |
       |def _ssc_tk_document(options: std.ui.content.ContentToolkitOptions): std.content.DocumentContent =
       |  contentBind(_ssc_content_document, options.bindings).asInstanceOf[std.content.DocumentContent]
       |
       |def _ssc_tk_block_by_id(doc: std.content.DocumentContent, id: String): Option[std.content.ContentBlock] =
       |  _ssc_content_blocks_deep(doc)
       |    .filter(block => _ssc_content_string_attr(_ssc_content_block_attrs(block), "id").contains(id)) match
       |      case Nil => None
       |      case block :: Nil => Some(block)
       |      case _ => throw RuntimeException(s"contentToolkitBlock: duplicate block id '$$id'")
       |
       |def _ssc_tk_section_by_id(doc: std.content.DocumentContent, id: String): Option[std.content.SectionContent] =
       |  _ssc_content_sections_deep(doc).filter(_.id == id) match
       |    case Nil => None
       |    case section :: Nil => Some(section)
       |    case _ => throw RuntimeException(s"contentToolkitSection: duplicate section id '$$id'")
       |
       |def contentToolkitNode(options: std.ui.content.ContentToolkitOptions = std.ui.content.ContentToolkitOptions()): std.ui.nodes.TkNode =
       |  val doc = _ssc_tk_document(options)
       |  val env = _ssc_tk_markdown_env(_ssc_tk_markdown_signal_defaults_doc(doc))
       |  std.ui.nodes.VStackNode(options.sectionGap,
       |    doc.blocks.map(_ssc_tk_block(_, options, env)) ++
       |      doc.sections.map(_ssc_tk_section(_, options, env)))
       |
       |def contentToolkitBlock(id: String, options: std.ui.content.ContentToolkitOptions = std.ui.content.ContentToolkitOptions()): std.ui.nodes.TkNode =
       |  _ssc_tk_block_by_id(_ssc_tk_document(options), id).map { block =>
       |    _ssc_tk_block(block, options, _ssc_tk_markdown_env(_ssc_tk_markdown_signal_defaults_block(block)))
       |  }.getOrElse(
       |    throw RuntimeException(s"contentToolkitBlock: no block with id '$$id'")
       |  )
       |
       |def contentToolkitSection(id: String, options: std.ui.content.ContentToolkitOptions = std.ui.content.ContentToolkitOptions()): std.ui.nodes.TkNode =
       |  _ssc_tk_section_by_id(_ssc_tk_document(options), id).map { section =>
       |    _ssc_tk_section(section, options, _ssc_tk_markdown_env(_ssc_tk_markdown_signal_defaults_section(section)))
       |  }.getOrElse(
       |    throw RuntimeException(s"contentToolkitSection: no section with id '$$id'")
       |  )
       |
       |""".stripMargin
    else "")

