package scalascript.codegen

import scalascript.ast.*
import scala.meta.*
import JsGenStringUtils.*

/** Content-toolkit JS emission for the JS backend — `contentDocument()` / content + toolkit
 *  runtime emission + the `ContentValue`/`ContentBlock`/`SectionContent`/`DocumentContent` → JS
 *  raw-object serializers + content-import scanning. Extracted verbatim from `JsGen`
 *  (codegen-megafile-deflation, mirrors JvmGenContentEmit) into a `self: JsGen =>` mixin to shrink
 *  the 5K-line core; behaviour-identical. */
private[codegen] trait JsGenContentEmit:
  self: JsGen =>

  private[codegen] def isContentStdImport(path: String): Boolean =
    path == "std/content.ssc" || path.endsWith("std/content.ssc")

  private[codegen] def isContentToolkitStdImport(path: String): Boolean =
    path == "std/ui/content.ssc" || path.endsWith("std/ui/content.ssc")

  private[codegen] def isContentHelperImport(path: String): Boolean =
    isContentStdImport(path) || isContentToolkitStdImport(path)

  private[codegen] def moduleUsesContentIntrinsics(module: Module): Boolean =
    def treeUsesContent(node: ScalaNode): Boolean =
      ScalaNode.fold(node) { tree =>
        tree.collect {
          case Term.Apply.After_4_6_0(Term.Name(name), _) if contentIntrinsicNames(name) => ()
        }.nonEmpty
      }

    def importUsesContent(imp: Content.Import): Boolean =
      isContentStdImport(imp.path) ||
        isContentToolkitStdImport(imp.path) ||
        imp.bindings.exists(binding => contentIntrinsicNames(binding.name))

    def sectionUsesContent(section: Section): Boolean =
      section.content.exists {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.exists(treeUsesContent) || contentIntrinsicNames.exists(cb.source.contains)
        case imp: Content.Import =>
          importUsesContent(imp)
        case _ => false
      } || section.subsections.exists(sectionUsesContent)

    module.sections.exists(sectionUsesContent)

  // True when the module imports the std/ui/content toolkit layer — used to gate
  // the contentToolkitNode/Block/Section runtime (the toolkit externs).
  private[codegen] def moduleUsesContentToolkitIntrinsics(module: Module): Boolean =
    def sectionImportsToolkit(section: Section): Boolean =
      section.content.exists {
        case imp: Content.Import => isContentToolkitStdImport(imp.path)
        case _                   => false
      } || section.subsections.exists(sectionImportsToolkit)
    module.sections.exists(sectionImportsToolkit)

  private[codegen] def jsRawOptionString(value: Option[String]): String =
    value.map(jsStringLit).getOrElse("null")

  private[codegen] def jsRawContentMap(values: Map[String, ContentValue]): String =
    values.map { (key, value) => s"${jsStringLit(key)}:${jsRawContentValue(value)}" }.mkString("{", ",", "}")

  private[codegen] def jsRawContentValue(value: ContentValue): String = value match
    case ContentValue.Str(v) =>
      s"""{t:"str",v:${jsStringLit(v)}}"""
    case ContentValue.Bool(v) =>
      s"""{t:"bool",v:$v}"""
    case ContentValue.Num(v) =>
      s"""{t:"num",v:${v.toString}}"""
    case ContentValue.ListV(values) =>
      s"""{t:"list",v:${values.map(jsRawContentValue).mkString("[", ",", "]")}}"""
    case ContentValue.MapV(values) =>
      s"""{t:"map",v:${jsRawContentMap(values)}}"""
    case ContentValue.NullV =>
      """{t:"null"}"""

  private[codegen] def jsRawInline(inline: ContentInline): String = inline match
    case ContentInline.Text(value) =>
      s"""{t:"Text",value:${jsStringLit(value)}}"""
    case ContentInline.Emphasis(children) =>
      s"""{t:"Emphasis",children:${children.map(jsRawInline).mkString("[", ",", "]")}}"""
    case ContentInline.Strong(children) =>
      s"""{t:"Strong",children:${children.map(jsRawInline).mkString("[", ",", "]")}}"""
    case ContentInline.Code(value) =>
      s"""{t:"Code",value:${jsStringLit(value)}}"""
    case ContentInline.Link(label, href, title) =>
      s"""{t:"Link",label:${label.map(jsRawInline).mkString("[", ",", "]")},href:${jsStringLit(href)},title:${jsRawOptionString(title)}}"""
    case ContentInline.Expr(source) =>
      s"""{t:"Expr",source:${jsStringLit(source)}}"""

  private[codegen] def jsRawEmbeddedKind(kind: EmbeddedKind): String = kind match
    case EmbeddedKind.StructuredData => jsStringLit("StructuredData")
    case EmbeddedKind.Executable     => jsStringLit("Executable")
    case EmbeddedKind.StringBlock    => jsStringLit("StringBlock")
    case EmbeddedKind.Opaque         => jsStringLit("Opaque")

  private[codegen] def jsRawContentBlock(block: ContentBlock): String = block match
    case ContentBlock.Paragraph(inlines, attrs) =>
      s"""{t:"Paragraph",inlines:${inlines.map(jsRawInline).mkString("[", ",", "]")},attrs:${jsRawContentMap(attrs)}}"""
    case ContentBlock.BulletList(items, attrs) =>
      val itemJs = items.map(_.map(jsRawContentBlock).mkString("[", ",", "]")).mkString("[", ",", "]")
      s"""{t:"BulletList",items:$itemJs,attrs:${jsRawContentMap(attrs)}}"""
    case ContentBlock.OrderedList(items, start, attrs) =>
      val itemJs = items.map(_.map(jsRawContentBlock).mkString("[", ",", "]")).mkString("[", ",", "]")
      s"""{t:"OrderedList",items:$itemJs,start:$start,attrs:${jsRawContentMap(attrs)}}"""
    case ContentBlock.Image(src, alt, title, attrs) =>
      s"""{t:"Image",src:${jsStringLit(src)},alt:${jsStringLit(alt)},title:${jsRawOptionString(title)},attrs:${jsRawContentMap(attrs)}}"""
    case ContentBlock.Table(headers, rows, alignments, attrs) =>
      val headerJs = headers.map(_.map(jsRawInline).mkString("[", ",", "]")).mkString("[", ",", "]")
      val rowsJs = rows.map(row => row.map(_.map(jsRawInline).mkString("[", ",", "]")).mkString("[", ",", "]")).mkString("[", ",", "]")
      val alignJs = alignments.map(jsStringLit).mkString("[", ",", "]")
      s"""{t:"Table",headers:$headerJs,rows:$rowsJs,alignments:$alignJs,attrs:${jsRawContentMap(attrs)}}"""
    case ContentBlock.Embedded(lang, source, kind, data, attrs) =>
      val dataJs = data.map(jsRawContentValue).getOrElse("null")
      s"""{t:"Embedded",lang:${jsStringLit(lang)},source:${jsStringLit(source)},kind:${jsRawEmbeddedKind(kind)},data:$dataJs,attrs:${jsRawContentMap(attrs)}}"""

  private[codegen] def jsRawSection(section: SectionContent): String =
    s"""{id:${jsStringLit(section.id)},level:${section.level},title:${jsStringLit(section.title)},attrs:${jsRawContentMap(section.attrs)},blocks:${section.blocks.map(jsRawContentBlock).mkString("[", ",", "]")},children:${section.children.map(jsRawSection).mkString("[", ",", "]")}}"""

  private[codegen] def jsRawDocument(doc: DocumentContent): String =
    s"""{manifest:${jsRawContentValue(doc.manifest)},title:${jsRawOptionString(doc.title)},description:${jsRawOptionString(doc.description)},attrs:${jsRawContentMap(doc.attrs)},sections:${doc.sections.map(jsRawSection).mkString("[", ",", "]")},blocks:${doc.blocks.map(jsRawContentBlock).mkString("[", ",", "]")}}"""

  private[codegen] def jsRawImportedDocuments: String =
    importedContentDocuments.toSeq.sortBy(_._1).map { (namespace, docs) =>
      s"${jsStringLit(namespace)}:${docs.map(jsRawDocument).mkString("[", ",", "]")}"
    }.mkString("{", ",", "}")

  /** Register the content documents of every TRANSITIVELY imported `.ssc`
   *  module (not just the entry module's direct imports), so a block/section
   *  defined in a deeply-imported module — e.g. `app.ssc -> rulepack_studio.ssc`
   *  with `@id=studio-preview @ui=toolkit` — is reachable from the browser
   *  registry.  The interpreter resolves `contentToolkitBlock(id)` against the
   *  *calling* module's document; the flattened JS bundle has no per-module
   *  current document, so the toolkit by-id lookups fall back across all of
   *  these registered documents (see `contentToolkitBlock`/`Section`).
   *  Cycle-protected; child imports resolve relative to the child's own dir,
   *  mirroring `scanContentUsage`. */
  private[codegen] def collectImportedContent(module: Module): Unit =
    importedContentDocuments.clear()
    val seen = scala.collection.mutable.Set.empty[String]
    def loopModule(m: Module, base: os.Path): Unit =
      def loopSection(section: Section): Unit =
        section.content.foreach {
          case imp: Content.Import if !isContentHelperImport(imp.path) =>
            resolveImportFrom(imp.path, base).foreach { resolved =>
              if seen.add(resolved.toString) then
                try
                  val childModule = scalascript.parser.Parser.parse(os.read(resolved))
                  childModule.document.foreach { doc =>
                    val namespace = importedContentNamespace(resolved, childModule)
                    importedContentDocuments.update(namespace, importedContentDocuments.getOrElse(namespace, Nil) :+ doc)
                  }
                  loopModule(childModule, resolved / os.up)
                catch case _: Throwable => ()
            }
          case _ => ()
        }
        section.subsections.foreach(loopSection)
      m.sections.foreach(loopSection)
    loopModule(module, baseDir.getOrElse(os.pwd))

  private[codegen] def resolveImportFrom(path: String, base: os.Path): Option[os.Path] =
    val initiallyResolved =
      try scalascript.imports.ImportResolver.resolve(path, base, moduleDeps, lockPath)
      catch case _: Throwable => base / os.RelPath(path)
    val resolved =
      if os.exists(initiallyResolved) then initiallyResolved
      else resolveStdImportFromProjectTree(path, base).getOrElse(initiallyResolved)
    Option.when(os.exists(resolved))(resolved)

  private[codegen] def moduleImportPaths(module: Module): List[String] =
    def loop(s: Section): List[String] =
      s.content.collect { case imp: Content.Import => imp.path } ++ s.subsections.flatMap(loop)
    module.sections.flatMap(loop)

  /** Walk the transitive `.ssc` import graph once and report whether any module
   *  uses content intrinsics and whether any imports the std/ui/content toolkit
   *  layer.  Content (and the toolkit) may be used only in a transitively
   *  imported module — e.g. `app.ssc -> rulepack_studio.ssc ->
   *  [contentToolkitBlock](std/ui/content.ssc)` — yet the runtime is emitted once
   *  for the top module, so the gate must see the whole graph or the transitive
   *  call site references an undefined `contentToolkit*` / `content*`.
   *  Cycle-protected; short-circuits once both flags are set. */
  private[codegen] def scanContentUsage(module: Module): (Boolean, Boolean) =
    val seen = scala.collection.mutable.Set.empty[String]
    var usesContent = false
    var usesToolkit = false
    def go(m: Module, base: os.Path): Unit =
      if !usesContent then usesContent = moduleUsesContentIntrinsics(m)
      if !usesToolkit then usesToolkit = moduleUsesContentToolkitIntrinsics(m)
      if !(usesContent && usesToolkit) then
        moduleImportPaths(m).foreach { path =>
          if !(usesContent && usesToolkit) then
            resolveImportFrom(path, base).foreach { resolved =>
              if seen.add(resolved.toString) then
                try go(scalascript.parser.Parser.parse(os.read(resolved)), resolved / os.up)
                catch case _: Throwable => ()
            }
        }
    go(module, baseDir.getOrElse(os.pwd))
    (usesContent, usesToolkit)

  private[codegen] def importedContentNamespace(resolvedPath: os.Path, module: Module): String =
    module.manifest.flatMap(_.name).map(_.trim).filter(_.nonEmpty).getOrElse {
      val last = resolvedPath.last
      if last.endsWith(".ssc") then last.stripSuffix(".ssc") else last
    }

  private[codegen] def emitContentRuntime(document: Option[DocumentContent]): Unit =
    val raw = document.map(jsRawDocument).getOrElse("null")
    line(s"const __ssc_content_document_raw = $raw;")
    line(s"const __ssc_content_imported_raw = ${jsRawImportedDocuments};")
    line("let __ssc_content_document_cache = undefined;")
    line("let __ssc_content_imported_cache = undefined;")
    line("let __ssc_content_sections_cache = undefined;")
    line("let __ssc_content_current_section_index = null;")
    line("function __ssc_content_error(msg) { throw new Error(msg); }")
    line("function __ssc_content_opt(v) { return v == null ? None : Some(v); }")
    line("function __ssc_content_map(raw) { const m = new Map(); Object.keys(raw || {}).forEach(k => m.set(k, __ssc_content_value(raw[k]))); return m; }")
    line("function __ssc_content_attrs(raw) { const m = new Map(); Object.keys(raw || {}).forEach(k => m.set(k, __ssc_content_value(raw[k]))); return m; }")
    line("function __ssc_content_value(raw) {")
    line("  if (!raw) return std.content.NullV;")
    line("  switch (raw.t) {")
    line("    case 'str': return std.content.Str(raw.v);")
    line("    case 'bool': return std.content.Bool(raw.v);")
    line("    case 'num': return std.content.Num(raw.v);")
    line("    case 'list': return std.content.ListV((raw.v || []).map(__ssc_content_value));")
    line("    case 'map': return std.content.MapV(__ssc_content_map(raw.v));")
    line("    case 'null': return std.content.NullV;")
    line("    default: return __ssc_content_error('contentDocument: unknown content value ' + raw.t);")
    line("  }")
    line("}")
    line("function __ssc_content_inline(raw) {")
    line("  switch (raw.t) {")
    line("    case 'Text': return std.content.Text(raw.value);")
    line("    case 'Emphasis': return std.content.Emphasis((raw.children || []).map(__ssc_content_inline));")
    line("    case 'Strong': return std.content.Strong((raw.children || []).map(__ssc_content_inline));")
    line("    case 'Code': return std.content.Code(raw.value);")
    line("    case 'Link': return std.content.Link((raw.label || []).map(__ssc_content_inline), raw.href, __ssc_content_opt(raw.title));")
    line("    case 'Expr': return std.content.Expr(raw.source);")
    line("    default: return __ssc_content_error('contentDocument: unknown inline ' + raw.t);")
    line("  }")
    line("}")
    line("function __ssc_content_embedded_kind(raw) {")
    line("  switch (raw) {")
    line("    case 'StructuredData': return std.content.StructuredData;")
    line("    case 'Executable': return std.content.Executable;")
    line("    case 'StringBlock': return std.content.StringBlock;")
    line("    case 'Opaque': return std.content.Opaque;")
    line("    default: return __ssc_content_error('contentDocument: unknown embedded kind ' + raw);")
    line("  }")
    line("}")
    line("function __ssc_content_block(raw) {")
    line("  switch (raw.t) {")
    line("    case 'Paragraph': return std.content.Paragraph((raw.inlines || []).map(__ssc_content_inline), __ssc_content_attrs(raw.attrs));")
    line("    case 'BulletList': return std.content.BulletList((raw.items || []).map(xs => xs.map(__ssc_content_block)), __ssc_content_attrs(raw.attrs));")
    line("    case 'OrderedList': return std.content.OrderedList((raw.items || []).map(xs => xs.map(__ssc_content_block)), raw.start, __ssc_content_attrs(raw.attrs));")
    line("    case 'Image': return std.content.Image(raw.src, raw.alt, __ssc_content_opt(raw.title), __ssc_content_attrs(raw.attrs));")
    line("    case 'Table': return std.content.Table((raw.headers || []).map(cell => cell.map(__ssc_content_inline)), (raw.rows || []).map(row => row.map(cell => cell.map(__ssc_content_inline))), raw.alignments || [], __ssc_content_attrs(raw.attrs));")
    line("    case 'Embedded': return std.content.Embedded(raw.lang, raw.source, __ssc_content_embedded_kind(raw.kind), __ssc_content_opt(raw.data == null ? null : __ssc_content_value(raw.data)), __ssc_content_attrs(raw.attrs));")
    line("    default: return __ssc_content_error('contentDocument: unknown block ' + raw.t);")
    line("  }")
    line("}")
    line("function __ssc_content_section(raw) {")
    line("  return std.content.SectionContent(raw.id, raw.level, raw.title, __ssc_content_attrs(raw.attrs), (raw.blocks || []).map(__ssc_content_block), (raw.children || []).map(__ssc_content_section));")
    line("}")
    line("function __ssc_content_deep_freeze(v) {")
    line("  if (Array.isArray(v)) v.forEach(__ssc_content_deep_freeze);")
    line("  else if (_isMap(v)) v.forEach(__ssc_content_deep_freeze);")
    line("  else if (v && typeof v === 'object') Object.values(v).forEach(__ssc_content_deep_freeze);")
    line("  if (v && typeof v === 'object') Object.freeze(v);")
    line("  return v;")
    line("}")
    line("function __ssc_content_document_from_raw(raw) {")
    line("  return __ssc_content_deep_freeze(std.content.DocumentContent(__ssc_content_value(raw.manifest), __ssc_content_opt(raw.title), __ssc_content_opt(raw.description), __ssc_content_attrs(raw.attrs), (raw.sections || []).map(__ssc_content_section), (raw.blocks || []).map(__ssc_content_block)));")
    line("}")
    line("function contentDocument() {")
    line("  if (__ssc_content_document_raw == null) return __ssc_content_error('contentDocument() is only available while running a parsed .ssc module');")
    line("  if (__ssc_content_document_cache === undefined) {")
    line("    __ssc_content_document_cache = __ssc_content_document_from_raw(__ssc_content_document_raw);")
    line("  }")
    line("  return __ssc_content_document_cache;")
    line("}")
    line("function __ssc_content_imported_documents() {")
    line("  if (__ssc_content_imported_cache !== undefined) return __ssc_content_imported_cache;")
    line("  const m = new Map();")
    line("  Object.keys(__ssc_content_imported_raw || {}).forEach(ns => m.set(ns, (__ssc_content_imported_raw[ns] || []).map(__ssc_content_document_from_raw)));")
    line("  __ssc_content_imported_cache = m;")
    line("  return m;")
    line("}")
    line("function __ssc_content_imported_unique(fn) {")
    line("  const out = new Map();")
    line("  __ssc_content_imported_documents().forEach((docs, ns) => {")
    line("    if (docs.length === 0) __ssc_content_error(fn + \": empty imported content namespace '\" + ns + \"'\");")
    line("    if (docs.length > 1) __ssc_content_error(fn + \": duplicate imported content namespace '\" + ns + \"'\");")
    line("    out.set(ns, docs[0]);")
    line("  });")
    line("  return out;")
    line("}")
    line("function contentModules() { return __ssc_content_imported_unique('contentModules()'); }")
    line("function __ssc_content_imported_document(namespace, fn) {")
    line("  const docs = __ssc_content_imported_documents().get(namespace) || [];")
    line("  if (docs.length === 0) return null;")
    line("  if (docs.length === 1) return docs[0];")
    line("  return __ssc_content_error(fn + \": duplicate imported content namespace '\" + namespace + \"'\");")
    line("}")
    line("function contentModule(namespace) { return __ssc_content_opt(__ssc_content_imported_document(namespace, 'contentModule(namespace)')); }")
    line("function __ssc_content_all_sections() {")
    line("  if (__ssc_content_sections_cache !== undefined) return __ssc_content_sections_cache;")
    line("  const out = [];")
    line("  function walk(section) { out.push(section); (section.children || []).forEach(walk); }")
    line("  contentDocument().sections.forEach(walk);")
    line("  __ssc_content_sections_cache = out;")
    line("  return out;")
    line("}")
    line("function __ssc_content_section_by_index(index) {")
    line("  const section = __ssc_content_all_sections()[index];")
    line("  if (!section) return __ssc_content_error('contentCurrentSection: missing generated section context');")
    line("  return section;")
    line("}")
    line("function __ssc_content_sections_deep_doc(doc) {")
    line("  const out = [];")
    line("  function walk(section) { out.push(section); (section.children || []).forEach(walk); }")
    line("  doc.sections.forEach(walk);")
    line("  return out;")
    line("}")
    line("function contentCurrentSection() {")
    line("  if (__ssc_content_current_section_index != null) return __ssc_content_section_by_index(__ssc_content_current_section_index);")
    line("  return __ssc_content_error('contentCurrentSection() is only available while running a parsed .ssc code block inside a Markdown section');")
    line("}")
    line("function __ssc_content_blocks_deep_from_block(block) {")
    line("  if (!block) return [];")
    line("  if (block._type === 'BulletList') return [block].concat(...block.items.flat().map(__ssc_content_blocks_deep_from_block));")
    line("  if (block._type === 'OrderedList') return [block].concat(...block.items.flat().map(__ssc_content_blocks_deep_from_block));")
    line("  return [block];")
    line("}")
    line("function __ssc_content_blocks_deep_from_section(section) {")
    line("  return section.blocks.flatMap(__ssc_content_blocks_deep_from_block).concat(section.children.flatMap(__ssc_content_blocks_deep_from_section));")
    line("}")
    line("function __ssc_content_blocks_deep(doc) {")
    line("  return doc.blocks.flatMap(__ssc_content_blocks_deep_from_block).concat(doc.sections.flatMap(__ssc_content_blocks_deep_from_section));")
    line("}")
    line("function __ssc_content_block_attrs(block) { return block && _isMap(block.attrs) ? block.attrs : new Map(); }")
    line("function __ssc_content_string_attr(attrs, name) { const v = attrs.get(name); return v && v._type === 'Str' ? v.value : null; }")
    line("function contentSection(id) {")
    line("  const matches = __ssc_content_all_sections().filter(section => section.id === id);")
    line("  if (matches.length === 0) return None;")
    line("  if (matches.length === 1) return Some(matches[0]);")
    line("  return __ssc_content_error(\"contentSection: duplicate section id '\" + id + \"'\");")
    line("}")
    line("function contentModuleSection(namespace, id) {")
    line("  const doc = __ssc_content_imported_document(namespace, 'contentModuleSection(namespace, id)');")
    line("  if (doc == null) return None;")
    line("  const matches = __ssc_content_sections_deep_doc(doc).filter(section => section.id === id);")
    line("  if (matches.length === 0) return None;")
    line("  if (matches.length === 1) return Some(matches[0]);")
    line("  return __ssc_content_error(\"contentSection: duplicate section id '\" + id + \"'\");")
    line("}")
    line("function contentBlock(id) {")
    line("  const matches = __ssc_content_blocks_deep(contentDocument()).filter(block => __ssc_content_string_attr(__ssc_content_block_attrs(block), 'id') === id);")
    line("  if (matches.length === 0) return None;")
    line("  if (matches.length === 1) return Some(matches[0]);")
    line("  return __ssc_content_error(\"contentBlock: duplicate block id '\" + id + \"'\");")
    line("}")
    line("function contentModuleBlock(namespace, id) {")
    line("  const doc = __ssc_content_imported_document(namespace, 'contentModuleBlock(namespace, id)');")
    line("  if (doc == null) return None;")
    line("  const matches = __ssc_content_blocks_deep(doc).filter(block => __ssc_content_string_attr(__ssc_content_block_attrs(block), 'id') === id);")
    line("  if (matches.length === 0) return None;")
    line("  if (matches.length === 1) return Some(matches[0]);")
    line("  return __ssc_content_error(\"contentBlock: duplicate block id '\" + id + \"'\");")
    line("}")
    line("function contentData(id) {")
    line("  const matches = __ssc_content_blocks_deep(contentDocument()).filter(block => block._type === 'Embedded' && block.kind && block.kind._type === 'StructuredData' && __ssc_content_string_attr(__ssc_content_block_attrs(block), 'id') === id).map(block => block.data && block.data._type === '_Some' ? block.data.value : null).filter(v => v != null);")
    line("  if (matches.length === 0) return None;")
    line("  if (matches.length === 1) return Some(matches[0]);")
    line("  return __ssc_content_error(\"contentData: duplicate structured data id '\" + id + \"'\");")
    line("}")
    line("function contentModuleData(namespace, id) {")
    line("  const doc = __ssc_content_imported_document(namespace, 'contentModuleData(namespace, id)');")
    line("  if (doc == null) return None;")
    line("  const matches = __ssc_content_blocks_deep(doc).filter(block => block._type === 'Embedded' && block.kind && block.kind._type === 'StructuredData' && __ssc_content_string_attr(__ssc_content_block_attrs(block), 'id') === id).map(block => block.data && block.data._type === '_Some' ? block.data.value : null).filter(v => v != null);")
    line("  if (matches.length === 0) return None;")
    line("  if (matches.length === 1) return Some(matches[0]);")
    line("  return __ssc_content_error(\"contentData: duplicate structured data id '\" + id + \"'\");")
    line("}")
    line("function __ssc_content_metadata_segments(path) {")
    line("  const trimmed = String(path).trim();")
    line("  if (!trimmed || trimmed.startsWith('.') || trimmed.endsWith('.') || trimmed.includes('..')) return __ssc_content_error('contentMetadata: path must be non-empty dot-separated segments');")
    line("  return trimmed.split('.');")
    line("}")
    line("function __ssc_content_metadata_path(value, segments) {")
    line("  if (segments.length === 0) return value;")
    line("  if (!value || value._type !== 'MapV') return null;")
    line("  const next = value.values.get(segments[0]);")
    line("  return next === undefined ? null : __ssc_content_metadata_path(next, segments.slice(1));")
    line("}")
    line("function contentMetadata(path) {")
    line("  const manifest = contentDocument().manifest;")
    line("  const root = manifest && manifest._type === 'MapV' ? manifest.values.get('content') : undefined;")
    line("  if (root === undefined) return None;")
    line("  const value = __ssc_content_metadata_path(root, __ssc_content_metadata_segments(path));")
    line("  return value == null ? None : Some(value);")
    line("}")
    line("function contentModuleMetadata(namespace, path) {")
    line("  const doc = __ssc_content_imported_document(namespace, 'contentModuleMetadata(namespace, path)');")
    line("  if (doc == null) return None;")
    line("  const manifest = doc.manifest;")
    line("  const root = manifest && manifest._type === 'MapV' ? manifest.values.get('content') : undefined;")
    line("  if (root === undefined) return None;")
    line("  const value = __ssc_content_metadata_path(root, __ssc_content_metadata_segments(path));")
    line("  return value == null ? None : Some(value);")
    line("}")
    line("function contentBind(value, bindings) {")
    line("  if (!bindings || bindings._type !== 'MapV') return __ssc_content_error('contentBind: bindings expected ContentValue.MapV, got ' + _show(bindings));")
    line("  if (value && value._type === 'DocumentContent') return __ssc_content_bind_document(value, bindings);")
    line("  if (value && value._type === 'SectionContent') return __ssc_content_bind_section(value, bindings);")
    line("  if (value && ['Paragraph','BulletList','OrderedList','Image','Table','Embedded'].includes(value._type)) return __ssc_content_bind_block(value, bindings);")
    line("  return __ssc_content_error('contentBind: expected DocumentContent, SectionContent, or ContentBlock, got ' + _show(value));")
    line("}")
    line("function __ssc_content_bind_document(doc, bindings) {")
    line("  return std.content.DocumentContent(doc.manifest, doc.title, doc.description, doc.attrs, doc.sections.map(s => __ssc_content_bind_section(s, bindings)), doc.blocks.map(b => __ssc_content_bind_block(b, bindings)));")
    line("}")
    line("function __ssc_content_bind_section(section, bindings) {")
    line("  return std.content.SectionContent(section.id, section.level, section.title, section.attrs, section.blocks.map(b => __ssc_content_bind_block(b, bindings)), section.children.map(s => __ssc_content_bind_section(s, bindings)));")
    line("}")
    line("function __ssc_content_bind_block(block, bindings) {")
    line("  switch (block._type) {")
    line("    case 'Paragraph': return std.content.Paragraph(block.inlines.map(i => __ssc_content_bind_inline(i, bindings)), block.attrs);")
    line("    case 'BulletList': return std.content.BulletList(block.items.map(item => item.map(b => __ssc_content_bind_block(b, bindings))), block.attrs);")
    line("    case 'OrderedList': return std.content.OrderedList(block.items.map(item => item.map(b => __ssc_content_bind_block(b, bindings))), block.start, block.attrs);")
    line("    case 'Image': return block;")
    line("    case 'Table': return std.content.Table(block.headers.map(cell => cell.map(i => __ssc_content_bind_inline(i, bindings))), block.rows.map(row => row.map(cell => cell.map(i => __ssc_content_bind_inline(i, bindings)))), block.alignments, block.attrs);")
    line("    case 'Embedded': return block;")
    line("    default: return __ssc_content_error('contentBind: expected ContentBlock, got ' + _show(block));")
    line("  }")
    line("}")
    line("function __ssc_content_bind_inline(inline, bindings) {")
    line("  switch (inline._type) {")
    line("    case 'Text': return inline;")
    line("    case 'Emphasis': return std.content.Emphasis(inline.children.map(i => __ssc_content_bind_inline(i, bindings)));")
    line("    case 'Strong': return std.content.Strong(inline.children.map(i => __ssc_content_bind_inline(i, bindings)));")
    line("    case 'Code': return inline;")
    line("    case 'Link': return std.content.Link(inline.label.map(i => __ssc_content_bind_inline(i, bindings)), inline.href, inline.title);")
    line("    case 'Expr': { const value = __ssc_content_bind_lookup(bindings, inline.source); return value === undefined ? inline : std.content.Text(__ssc_content_value_text(value)); }")
    line("    default: return __ssc_content_error('contentBind: expected ContentInline, got ' + _show(inline));")
    line("  }")
    line("}")
    line("function __ssc_content_bind_lookup(bindings, source) {")
    line("  if (typeof source !== 'string' || !/^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$/.test(source)) return undefined;")
    line("  let current = bindings;")
    line("  for (const segment of source.split('.')) {")
    line("    if (!current || current._type !== 'MapV') return undefined;")
    line("    const next = current.values.get(segment);")
    line("    if (next === undefined) return undefined;")
    line("    current = next;")
    line("  }")
    line("  return current;")
    line("}")
    line("function __ssc_content_value_text(value) {")
    line("  if (!value) return '';")
    line("  switch (value._type) {")
    line("    case 'Str': return value.value;")
    line("    case 'Bool': return String(value.value);")
    line("    case 'Num': return __ssc_content_number_string(value.value);")
    line("    case 'NullV': return '';")
    line("    case 'ListV': return value.values.map(__ssc_content_value_text).join(', ');")
    line("    case 'MapV': return '{' + Array.from(value.values.entries()).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => k + ': ' + __ssc_content_value_text(v)).join(', ') + '}';")
    line("    default: return _show(value);")
    line("  }")
    line("}")
    line("function contentPlainText(value) {")
    line("  if (value && value._type === 'SectionContent') return __ssc_content_section_plain_text(value);")
    line("  if (value && ['Paragraph','BulletList','OrderedList','Image','Table','Embedded'].includes(value._type)) return __ssc_content_block_plain_text(value);")
    line("  return __ssc_content_error('contentPlainText: expected SectionContent or ContentBlock, got ' + _show(value));")
    line("}")
    line("function __ssc_content_section_plain_text(section) {")
    line("  return [section.title].concat(section.blocks.map(__ssc_content_block_plain_text), section.children.map(__ssc_content_section_plain_text)).filter(s => s.length > 0).join('\\n');")
    line("}")
    line("function __ssc_content_block_list_plain_text(blocks) { return blocks.map(__ssc_content_block_plain_text).filter(s => s.length > 0).join(' '); }")
    line("function __ssc_content_block_plain_text(block) {")
    line("  switch (block._type) {")
    line("    case 'Paragraph': return block.inlines.map(__ssc_content_inline_plain_text).join('');")
    line("    case 'BulletList': return block.items.map(item => '- ' + __ssc_content_block_list_plain_text(item)).filter(s => s.trim().length > 0).join('\\n');")
    line("    case 'OrderedList': return block.items.map((item, idx) => String(block.start + idx) + '. ' + __ssc_content_block_list_plain_text(item)).filter(s => s.trim().length > 0).join('\\n');")
    line("    case 'Image': return block.alt === '' ? block.src : block.alt;")
    line("    case 'Table': return __ssc_content_table_plain_text(block);")
    line("    case 'Embedded': return block.lang === '' ? block.source : block.lang + ': ' + block.source;")
    line("    default: return __ssc_content_error('contentPlainText: expected ContentBlock, got ' + _show(block));")
    line("  }")
    line("}")
    line("function __ssc_content_table_plain_text(block) {")
    line("  return [block.headers.map(cell => cell.map(__ssc_content_inline_plain_text).join('')).join(' | ')].concat((block.rows || []).map(row => row.map(cell => cell.map(__ssc_content_inline_plain_text).join('')).join(' | '))).filter(s => s.trim().length > 0).join('\\n');")
    line("}")
    line("function __ssc_content_inline_plain_text(inline) {")
    line("  switch (inline._type) {")
    line("    case 'Text': return inline.value;")
    line("    case 'Emphasis': return inline.children.map(__ssc_content_inline_plain_text).join('');")
    line("    case 'Strong': return inline.children.map(__ssc_content_inline_plain_text).join('');")
    line("    case 'Code': return '`' + inline.value + '`';")
    line("    case 'Link': return inline.label.map(__ssc_content_inline_plain_text).join('') + ' (' + inline.href + ')';")
    line("    case 'Expr': return '${' + inline.source + '}';")
    line("    default: return __ssc_content_error('contentPlainText: expected ContentInline, got ' + _show(inline));")
    line("  }")
    line("}")
    line("function contentToMarkdown(value) {")
    line("  if (value && value._type === 'DocumentContent') return __ssc_content_document_markdown(value);")
    line("  if (value && value._type === 'SectionContent') return __ssc_content_section_markdown(value);")
    line("  if (value && ['Paragraph','BulletList','OrderedList','Image','Table','Embedded'].includes(value._type)) return __ssc_content_block_markdown(value);")
    line("  return __ssc_content_error('contentToMarkdown: expected DocumentContent, SectionContent, or ContentBlock, got ' + _show(value));")
    line("}")
    line("function __ssc_content_document_markdown(doc) {")
    line("  const front = __ssc_content_manifest_markdown(doc.manifest);")
    line("  return (front ? [front] : []).concat(doc.blocks.map(__ssc_content_block_markdown), doc.sections.map(__ssc_content_section_markdown)).filter(s => s.length > 0).join('\\n\\n');")
    line("}")
    line("function __ssc_content_manifest_markdown(value) {")
    line("  if (value && value._type === 'MapV' && value.values.size > 0) return '---\\n' + __ssc_content_yaml_map_lines(value.values, 0).join('\\n') + '\\n---';")
    line("  return null;")
    line("}")
    line("function __ssc_content_section_markdown(section) {")
    line("  const heading = '#'.repeat(__ssc_content_heading_level(section.level)) + ' ' + __ssc_content_markdown_text(section.title) + __ssc_content_heading_attrs(section.id, section.attrs);")
    line("  return [heading].concat(section.blocks.map(__ssc_content_block_markdown), section.children.map(__ssc_content_section_markdown)).filter(s => s.length > 0).join('\\n\\n');")
    line("}")
    line("function __ssc_content_block_markdown(block) {")
    line("  let body;")
    line("  switch (block._type) {")
    line("    case 'Paragraph': body = block.inlines.map(__ssc_content_inline_markdown).join(''); break;")
    line("    case 'BulletList': body = block.items.map(item => __ssc_content_list_item_markdown('- ', item)).join('\\n'); break;")
    line("    case 'OrderedList': body = block.items.map((item, idx) => __ssc_content_list_item_markdown(String(block.start + idx) + '. ', item)).join('\\n'); break;")
    line("    case 'Image': { const title = __ssc_content_option_string(block.title); const titlePart = title == null ? '' : ' ' + __ssc_content_quote_attr(title); body = '![' + __ssc_content_markdown_text(block.alt) + '](' + block.src + titlePart + ')'; break; }")
    line("    case 'Table': body = __ssc_content_table_markdown(block); break;")
    line("    case 'Embedded': body = __ssc_content_embedded_markdown(block); break;")
    line("    default: return __ssc_content_error('contentToMarkdown: expected ContentBlock, got ' + _show(block));")
    line("  }")
    line("  if (block._type === 'Embedded') return body;")
    line("  const directive = __ssc_content_metadata_directive(__ssc_content_block_attrs(block));")
    line("  return directive == null ? body : directive + '\\n' + body;")
    line("}")
    line("function __ssc_content_list_item_markdown(prefix, blocks) {")
    line("  const body = blocks.map(__ssc_content_block_markdown).filter(s => s.length > 0).join('\\n\\n');")
    line("  if (body.length === 0) return prefix.trim();")
    line("  const lines = body.split('\\n');")
    line("  return prefix + lines[0] + lines.slice(1).map(line => '\\n  ' + line).join('');")
    line("}")
    line("function __ssc_content_table_markdown(block) {")
    line("  const headerCells = (block.headers || []).map(cell => __ssc_content_table_cell_markdown(cell));")
    line("  const bodyRows = (block.rows || []).map(row => row.map(cell => __ssc_content_table_cell_markdown(cell)));")
    line("  const colCount = Math.max(headerCells.length, (block.alignments || []).length, ...bodyRows.map(row => row.length));")
    line("  const separator = Array.from({length: colCount}, (_, idx) => __ssc_content_table_separator((block.alignments || [])[idx] || 'default'));")
    line("  return [__ssc_content_markdown_table_row(__ssc_content_pad_cells(headerCells, colCount)), __ssc_content_markdown_table_row(separator)].concat(bodyRows.map(row => __ssc_content_markdown_table_row(__ssc_content_pad_cells(row, colCount)))).join('\\n');")
    line("}")
    line("function __ssc_content_table_cell_markdown(cell) { return cell.map(__ssc_content_inline_markdown).join('').replace(/\\n/g, ' ').replace(/\\|/g, '\\\\|'); }")
    line("function __ssc_content_pad_cells(cells, size) { return cells.concat(Array(Math.max(0, size - cells.length)).fill('')); }")
    line("function __ssc_content_markdown_table_row(cells) { return '| ' + cells.join(' | ') + ' |'; }")
    line("function __ssc_content_table_separator(alignment) { switch (alignment) { case 'left': return ':---'; case 'center': return ':---:'; case 'right': return '---:'; default: return '---'; } }")
    line("function __ssc_content_embedded_markdown(block) {")
    line("  const info = [block.lang].concat(__ssc_content_fence_attr_tokens(block.attrs)).filter(s => s.length > 0).join(' ');")
    line("  const fence = __ssc_content_fence_delimiter(block.source);")
    line("  const body = block.source.endsWith('\\n') ? block.source : block.source + '\\n';")
    line("  return info.length === 0 ? fence + '\\n' + body + fence : fence + info + '\\n' + body + fence;")
    line("}")
    line("function __ssc_content_inline_markdown(inline) {")
    line("  switch (inline._type) {")
    line("    case 'Text': return __ssc_content_markdown_text(inline.value);")
    line("    case 'Emphasis': return '*' + inline.children.map(__ssc_content_inline_markdown).join('') + '*';")
    line("    case 'Strong': return '**' + inline.children.map(__ssc_content_inline_markdown).join('') + '**';")
    line("    case 'Code': return __ssc_content_inline_code_markdown(inline.value);")
    line("    case 'Link': { const title = __ssc_content_option_string(inline.title); const titlePart = title == null ? '' : ' ' + __ssc_content_quote_attr(title); return '[' + inline.label.map(__ssc_content_inline_markdown).join('') + '](' + inline.href + titlePart + ')'; }")
    line("    case 'Expr': return '${' + inline.source + '}';")
    line("    default: return __ssc_content_error('contentToMarkdown: expected ContentInline, got ' + _show(inline));")
    line("  }")
    line("}")
    line("function __ssc_content_heading_attrs(id, attrs) {")
    line("  const tokens = [];")
    line("  if (id) tokens.push('#' + id);")
    line("  const cls = attrs.get('class');")
    line("  if (cls && cls._type === 'Str') cls.value.split(/\\s+/).filter(Boolean).forEach(c => tokens.push('.' + c));")
    line("  Array.from(attrs.entries()).filter(([k]) => k !== 'class').sort(([a], [b]) => a.localeCompare(b)).forEach(([k, v]) => { const t = __ssc_content_attr_token(k, v, ''); if (t != null) tokens.push(t); });")
    line("  return tokens.length === 0 ? '' : ' {' + tokens.join(' ') + '}';")
    line("}")
    line("function __ssc_content_metadata_directive(attrs) {")
    line("  const tokens = Array.from(attrs.entries()).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => __ssc_content_attr_token(k, v, '')).filter(t => t != null);")
    line("  return tokens.length === 0 ? null : '<!-- @meta ' + tokens.join(' ') + ' -->';")
    line("}")
    line("function __ssc_content_fence_attr_tokens(attrs) {")
    line("  const tokens = [];")
    line("  if (attrs.has('id')) { const t = __ssc_content_attr_token('id', attrs.get('id'), '@'); if (t != null) tokens.push(t); }")
    line("  Array.from(attrs.entries()).filter(([k]) => k !== 'id').sort(([a], [b]) => a.localeCompare(b)).forEach(([k, v]) => { const t = __ssc_content_attr_token(k, v, '@'); if (t != null) tokens.push(t); });")
    line("  return tokens;")
    line("}")
    line("function __ssc_content_attr_token(key, value, prefix) {")
    line("  if (!value) return prefix + key + '=null';")
    line("  switch (value._type) {")
    line("    case 'Bool': return prefix === '' && value.value === true ? key : prefix + key + '=' + String(value.value);")
    line("    case 'Str': return prefix + key + '=' + __ssc_content_attr_scalar(value.value);")
    line("    case 'Num': return prefix + key + '=' + __ssc_content_number_string(value.value);")
    line("    case 'NullV': return prefix + key + '=null';")
    line("    default: return prefix + key + '=' + __ssc_content_attr_scalar(__ssc_content_yaml_scalar(value));")
    line("  }")
    line("}")
    line("function __ssc_content_yaml_map_lines(values, indent) {")
    line("  return Array.from(values.entries()).sort(([a], [b]) => a.localeCompare(b)).flatMap(([k, v]) => __ssc_content_yaml_key_value_lines(k, v, indent));")
    line("}")
    line("function __ssc_content_yaml_key_value_lines(key, value, indent) {")
    line("  const pad = ' '.repeat(indent);")
    line("  if (value && value._type === 'MapV') return value.values.size === 0 ? [pad + __ssc_content_yaml_key(key) + ': {}'] : [pad + __ssc_content_yaml_key(key) + ':'].concat(__ssc_content_yaml_map_lines(value.values, indent + 2));")
    line("  if (value && value._type === 'ListV') return value.values.length === 0 ? [pad + __ssc_content_yaml_key(key) + ': []'] : [pad + __ssc_content_yaml_key(key) + ':'].concat(__ssc_content_yaml_list_lines(value.values, indent + 2));")
    line("  return [pad + __ssc_content_yaml_key(key) + ': ' + __ssc_content_yaml_scalar(value)];")
    line("}")
    line("function __ssc_content_yaml_list_lines(values, indent) {")
    line("  const pad = ' '.repeat(indent);")
    line("  return values.flatMap(value => {")
    line("    if (value && value._type === 'MapV') return value.values.size === 0 ? [pad + '- {}'] : [pad + '-'].concat(__ssc_content_yaml_map_lines(value.values, indent + 2));")
    line("    if (value && value._type === 'ListV') return value.values.length === 0 ? [pad + '- []'] : [pad + '-'].concat(__ssc_content_yaml_list_lines(value.values, indent + 2));")
    line("    return [pad + '- ' + __ssc_content_yaml_scalar(value)];")
    line("  });")
    line("}")
    line("function __ssc_content_yaml_scalar(value) {")
    line("  if (!value) return 'null';")
    line("  switch (value._type) {")
    line("    case 'Str': return __ssc_content_yaml_string(value.value);")
    line("    case 'Bool': return String(value.value);")
    line("    case 'Num': return __ssc_content_number_string(value.value);")
    line("    case 'NullV': return 'null';")
    line("    case 'MapV': return value.values.size === 0 ? '{}' : __ssc_content_quote_attr(_show(value));")
    line("    case 'ListV': return value.values.length === 0 ? '[]' : __ssc_content_quote_attr(_show(value));")
    line("    default: return __ssc_content_quote_attr(_show(value));")
    line("  }")
    line("}")
    line("function __ssc_content_option_string(value) { return value && value._type === '_Some' ? value.value : null; }")
    line("function __ssc_content_heading_level(value) { return Math.max(1, Math.min(6, Number(value) || 1)); }")
    line("function __ssc_content_number_string(value) { return Number.isInteger(value) ? String(value) : String(value); }")
    line("function __ssc_content_yaml_key(value) { return __ssc_content_safe_bare_scalar(value) ? value : __ssc_content_quote_attr(value); }")
    line("function __ssc_content_yaml_string(value) { return __ssc_content_safe_bare_scalar(value) ? value : __ssc_content_quote_attr(value); }")
    line("function __ssc_content_attr_scalar(value) { return __ssc_content_safe_attr_scalar(value) ? value : __ssc_content_quote_attr(value); }")
    line("function __ssc_content_quote_attr(value) { return JSON.stringify(String(value)); }")
    line("function __ssc_content_safe_bare_scalar(value) { return typeof value === 'string' && value.length > 0 && !['true','false','null'].includes(value.toLowerCase()) && /^[A-Za-z0-9_.\\/:@-]+$/.test(value); }")
    line("function __ssc_content_safe_attr_scalar(value) { return typeof value === 'string' && value.length > 0 && /^[A-Za-z0-9_.\\/:@-]+$/.test(value); }")
    line("function __ssc_content_markdown_text(value) { return String(value); }")
    line("function __ssc_content_inline_code_markdown(value) { const d = '`'.repeat(Math.max(1, __ssc_content_max_backtick_run(value) + 1)); return d + value + d; }")
    line("function __ssc_content_fence_delimiter(source) { return '`'.repeat(Math.max(3, __ssc_content_max_backtick_run(source) + 1)); }")
    line("function __ssc_content_max_backtick_run(value) { let best = 0, current = 0; for (const ch of String(value)) { if (ch === '`') { current += 1; best = Math.max(best, current); } else current = 0; } return best; }")
    if contentToolkitRuntimeEnabled then emitContentToolkitRuntime()

  /** JS port of JvmGen's content-toolkit runtime: renders authored Markdown
   *  content (sections, blocks, `toolkit:` control links, `@ui=toolkit` YAML
   *  control trees, GFM tables, and registered components) into a TkNode tree.
   *  Mirrors the JVM-codegen `_ssc_tk_*` helpers so the browser backend reaches
   *  parity for contentToolkitNode / contentToolkitBlock / contentToolkitSection.
   *  TkNode values are built as `{_type:'<Name>', <fields>}` — the same shape a
   *  `.ssc` case-class constructor compiles to — so `lower(...)` consumes them. */
  private[codegen] def emitContentToolkitRuntime(): Unit =
    line(ContentToolkitJs.source)

  private[codegen] def withContentCurrentSection(sectionIndex: Option[Int])(emit: => Unit): Unit =
    if contentRuntimeEnabled then
      sectionIndex match
        case Some(index) => line(s"__ssc_content_current_section_index = $index;")
        case None        => line("__ssc_content_current_section_index = null;")
      emit
      line("__ssc_content_current_section_index = null;")
    else emit

