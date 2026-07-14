package scalascript.compiler.plugin.content

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import scalascript.ast
import scalascript.backend.spi.{IntrinsicImpl, NativeContextFeatureKeys}
import scalascript.frontend.ReactiveSignal
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginContext, PluginError, PluginNative, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Num, Dbl, Bool, Lst, MapVal, InstAny, Opt}

object ContentIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(
    QualifiedName("contentDocument") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case Nil =>
          ctx.featureGet(NativeContextFeatureKeys.ContentDocument) match
            case Some(doc: ast.DocumentContent) => documentValue(doc)
            case _ => PluginError.raise("contentDocument() is only available while running a parsed .ssc module")
        case _ => PluginError.raise("contentDocument()")
    },
    QualifiedName("contentCurrentSection") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case Nil =>
          ctx.featureLocalGet(NativeContextFeatureKeys.ContentCurrentSection) match
            case Some(section: ast.SectionContent) => sectionValue(section)
            case _ =>
              PluginError.raise(
                "contentCurrentSection() is only available while running a parsed .ssc code block inside a Markdown section"
              )
        case _ => PluginError.raise("contentCurrentSection()")
    },
    // contentSection/contentBlock/contentData: current document first, then the
    // registered IMPORTED documents (same v2-bridge rationale as
    // toolkitSectionById — a module's own sections must stay reachable from the
    // module's code after the bridge inlines it under the entry document).
    QualifiedName("contentSection") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (id: String) :: Nil =>
          val hit = contentSectionById(currentDocument(ctx, "contentSection(id)"), id)
            .orElse(importedDocumentTable(ctx).values.flatten.toList.flatMap(d => contentSectionById(d, id)).headOption)
          PluginValue.option(hit.map(sectionValue))
        case _ => PluginError.raise("contentSection(id)")
    },
    QualifiedName("contentBlock") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (id: String) :: Nil =>
          val hit = contentBlockById(currentDocument(ctx, "contentBlock(id)"), id)
            .orElse(importedDocumentTable(ctx).values.flatten.toList.flatMap(d => contentBlockById(d, id)).headOption)
          PluginValue.option(hit.map(blockValue))
        case _ => PluginError.raise("contentBlock(id)")
    },
    QualifiedName("contentData") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (id: String) :: Nil =>
          val hit = contentDataById(currentDocument(ctx, "contentData(id)"), id)
            .orElse(importedDocumentTable(ctx).values.flatten.toList.flatMap(d => contentDataById(d, id)).headOption)
          PluginValue.option(hit.map(contentValue))
        case _ => PluginError.raise("contentData(id)")
    },
    QualifiedName("contentMetadata") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (path: String) :: Nil =>
          PluginValue.option(contentMetadataPath(currentDocument(ctx, "contentMetadata(path)"), path).map(contentValue))
        case _ => PluginError.raise("contentMetadata(path)")
    },
    QualifiedName("contentBind") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case value :: bindings :: Nil => contentBindValue(PluginValue.wrap(value), contentBindRoot(PluginValue.wrap(bindings)))
        case _ => PluginError.raise("contentBind(value, bindings)")
    },
    QualifiedName("contentPlainText") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case value :: Nil => contentPlainTextAny(value)
        case _            => PluginError.raise("contentPlainText(value)")
    },
    QualifiedName("contentToMarkdown") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case value :: Nil => contentToMarkdownAny(value)
        case _            => PluginError.raise("contentToMarkdown(value)")
    },
    QualifiedName("contentModules") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case Nil =>
          PluginValue.mapOf(uniqueImportedDocuments(ctx, "contentModules()").map {
            case (namespace, doc) => PluginValue.string(namespace) -> documentValue(doc)
          })
        case _ => PluginError.raise("contentModules()")
    },
    QualifiedName("contentModule") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (namespace: String) :: Nil =>
          PluginValue.option(importedDocument(ctx, namespace, "contentModule(namespace)").map(documentValue))
        case _ => PluginError.raise("contentModule(namespace)")
    },
    QualifiedName("contentModuleSection") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (namespace: String) :: (id: String) :: Nil =>
          PluginValue.option(importedDocument(ctx, namespace, "contentModuleSection(namespace, id)").flatMap(doc =>
            contentSectionById(doc, id).map(sectionValue)))
        case _ => PluginError.raise("contentModuleSection(namespace, id)")
    },
    QualifiedName("contentModuleBlock") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (namespace: String) :: (id: String) :: Nil =>
          PluginValue.option(importedDocument(ctx, namespace, "contentModuleBlock(namespace, id)").flatMap(doc =>
            contentBlockById(doc, id).map(blockValue)))
        case _ => PluginError.raise("contentModuleBlock(namespace, id)")
    },
    QualifiedName("contentModuleData") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (namespace: String) :: (id: String) :: Nil =>
          PluginValue.option(importedDocument(ctx, namespace, "contentModuleData(namespace, id)").flatMap(doc =>
            contentDataById(doc, id).map(contentValue)))
        case _ => PluginError.raise("contentModuleData(namespace, id)")
    },
    QualifiedName("contentModuleMetadata") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (namespace: String) :: (path: String) :: Nil =>
          PluginValue.option(importedDocument(ctx, namespace, "contentModuleMetadata(namespace, path)").flatMap(doc =>
            contentMetadataPath(doc, path).map(contentValue)))
        case _ => PluginError.raise("contentModuleMetadata(namespace, path)")
    },
    QualifiedName("contentToolkitNode") -> PluginNative.evalLegacy { (ctx, args) =>
      val options = args match
        case Nil      => ToolkitOptions()
        case one :: Nil => toolkitOptions(PluginValue.wrap(one))
        case _        => PluginError.raise("contentToolkitNode([options])")
      toolkitDocumentNode(ctx, contentBindDocument(currentDocument(ctx, "contentToolkitNode()"), options.bindings), options)
    },
    QualifiedName("contentToolkitBlock") -> PluginNative.evalLegacy { (ctx, args) =>
      val (id, options) = toolkitSelectorArgs("contentToolkitBlock", args)
      toolkitBlockById(ctx, contentBindDocument(currentDocument(ctx, "contentToolkitBlock(id)"), options.bindings), id, options)
    },
    QualifiedName("contentToolkitSection") -> PluginNative.evalLegacy { (ctx, args) =>
      val (id, options) = toolkitSelectorArgs("contentToolkitSection", args)
      toolkitSectionById(ctx, contentBindDocument(currentDocument(ctx, "contentToolkitSection(id)"), options.bindings), id, options)
    }
  )

  private case class ToolkitOptions(
    includeCode: Boolean = false,
    sectionGap: Int = 16,
    blockGap: Int = 8,
    listGap: Int = 4,
    wrapDocumentInCard: Boolean = false,
    wrapTopLevelSectionsInCards: Boolean = false,
    components: Map[String, PluginValue] = Map.empty,
    bindings: Map[String, ast.ContentValue] = Map.empty,
    actions: Map[String, PluginValue] = Map.empty,
    rowBindings: Map[String, PluginValue] = Map.empty,
    computed: Map[String, PluginValue] = Map.empty,
    slots: Map[String, PluginValue] = Map.empty
  )

  // buildColumn (Scope B.2): builds a typed DataColumn by invoking a registered
  // column-builder native (fieldColumn/moneyColumn/…) — a closure capturing the
  // PluginContext, so toolkitControl can construct columns from inline YAML
  // `columns:` specs without the content plugin depending on the column model.
  private case class ToolkitUiEnv(signals: Map[String, PluginValue], actions: Map[String, PluginValue] = Map.empty,
                                  rowBindings: Map[String, PluginValue] = Map.empty,
                                  buildColumn: Option[(String, List[Any]) => PluginValue] = None)
  private case class ToolkitLink(kind: String, query: Map[String, String], label: String)

  private def currentDocument(ctx: PluginContext, fn: String): ast.DocumentContent =
    ctx.featureGet(NativeContextFeatureKeys.ContentDocument) match
      case Some(doc: ast.DocumentContent) => doc
      case _ => PluginError.raise(s"$fn is only available while running a parsed .ssc module")

  private def importedDocumentTable(ctx: PluginContext): Map[String, List[ast.DocumentContent]] =
    ctx.featureGet(NativeContextFeatureKeys.ContentImportedModules) match
      case Some(table: Map[?, ?]) =>
        table.toList.collect {
          case (namespace: String, docs: List[?]) =>
            namespace -> docs.collect { case doc: ast.DocumentContent => doc }
        }.toMap
      case _ => Map.empty

  private def uniqueImportedDocuments(ctx: PluginContext, fn: String): Map[String, ast.DocumentContent] =
    importedDocumentTable(ctx).map {
      case (namespace, doc :: Nil) => namespace -> doc
      case (namespace, Nil) =>
        PluginError.raise(s"$fn: empty imported content namespace '$namespace'")
      case (namespace, _) =>
        PluginError.raise(s"$fn: duplicate imported content namespace '$namespace'")
    }

  private def importedDocument(ctx: PluginContext, namespace: String, fn: String): Option[ast.DocumentContent] =
    importedDocumentTable(ctx).get(namespace) match
      case None | Some(Nil) => None
      case Some(doc :: Nil) => Some(doc)
      case Some(_)          => PluginError.raise(s"$fn: duplicate imported content namespace '$namespace'")

  private def toolkitSelectorArgs(fn: String, args: List[Any]): (String, ToolkitOptions) =
    args match
      case (id: String) :: Nil =>
        (id, ToolkitOptions())
      case (id: String) :: options :: Nil =>
        (id, toolkitOptions(PluginValue.wrap(options)))
      case _ =>
        PluginError.raise(s"$fn(id, [options])")

  private def toolkitOptions(value: PluginValue): ToolkitOptions =
    val fields = value match
      case InstAny(inst) if inst.typeNameOf.contains("ContentToolkitOptions") => inst.fields
      case other => PluginError.raise(s"contentToolkitNode: expected ContentToolkitOptions, got ${PluginValue.showAny(other)}")
    ToolkitOptions(
      includeCode = boolField(fields, "includeCode", default = false),
      sectionGap = intField(fields, "sectionGap", default = 16),
      blockGap = intField(fields, "blockGap", default = 8),
      listGap = intField(fields, "listGap", default = 4),
      wrapDocumentInCard = boolField(fields, "wrapDocumentInCard", default = false),
      wrapTopLevelSectionsInCards = boolField(fields, "wrapTopLevelSectionsInCards", default = false),
      components = componentRegistry(fields.get("components")),
      bindings = contentValueMapField(fields.get("bindings")),
      actions = actionRegistry(fields.get("actions")),
      rowBindings = rowBindingRegistry(fields.get("rowBindings")),
      computed = computedRegistry(fields.get("computed")),
      slots = slotRegistry(fields.get("slots"))
    )

  // slots (Scope B.6): a Map[String, TkNode] — id -> a code-built node injected at
  // a `{type: slot, id}` control (the escape hatch for content the vocabulary can't
  // express).  The node is already a built TkNode and is returned verbatim.
  private def slotRegistry(value: Option[PluginValue]): Map[String, PluginValue] =
    value match
      case None | Some(PluginValue.nullV) => Map.empty
      case Some(MapVal(m)) =>
        m.collect { case (Str(k), v) => k -> v }
      case Some(other) =>
        PluginError.raise(s"ContentToolkitOptions.slots: expected Map[String, TkNode], got ${PluginValue.showAny(other)}")

  // actions: a Map[String, EventHandler] — id -> handler for toolkit:button?action=id.
  private def actionRegistry(value: Option[PluginValue]): Map[String, PluginValue] =
    value match
      case None | Some(PluginValue.nullV) => Map.empty
      case Some(MapVal(m)) =>
        m.collect { case (Str(k), v) => k -> v }
      case Some(other) =>
        PluginError.raise(s"ContentToolkitOptions.actions: expected Map[String, EventHandler], got ${PluginValue.showAny(other)}")

  // computed: a Map[String, Signal] (Scope B.5) — id -> a code-built derived signal
  // merged into the toolkit signal environment so YAML controls can reference it.
  private def computedRegistry(value: Option[PluginValue]): Map[String, PluginValue] =
    value match
      case None | Some(PluginValue.nullV) => Map.empty
      case Some(MapVal(m)) =>
        m.collect { case (Str(k), v) => k -> v }
      case Some(other) =>
        PluginError.raise(s"ContentToolkitOptions.computed: expected Map[String, Signal], got ${PluginValue.showAny(other)}")

  // rowBindings: a Map[String, ContentRowBinding] — id -> live-row source for
  // toolkit:table?rows=id.  Each value carries `rows` (a Signal), `columns`
  // (field-column descriptors), and `actions` (per-row actions).
  private def rowBindingRegistry(value: Option[PluginValue]): Map[String, PluginValue] =
    value match
      case None | Some(PluginValue.nullV) => Map.empty
      case Some(MapVal(m)) =>
        m.collect { case (Str(k), v) => k -> v }
      case Some(other) =>
        PluginError.raise(s"ContentToolkitOptions.rowBindings: expected Map[String, ContentRowBinding], got ${PluginValue.showAny(other)}")

  private def componentRegistry(value: Option[PluginValue]): Map[String, PluginValue] =
    value match
      case None => Map.empty
      case Some(Lst(entries)) =>
        entries.foldLeft(Map.empty[String, PluginValue]) { (acc, entry) =>
          val fields = entry match
            case InstAny(inst) if inst.typeNameOf.contains("ContentToolkitComponent") => inst.fields
            case other =>
              PluginError.raise(s"ContentToolkitOptions.components: expected ContentToolkitComponent, got ${PluginValue.showAny(other)}")
          val name = fields.get("name") match
            case Some(Str(v)) => v
            case Some(other) =>
              PluginError.raise(s"ContentToolkitComponent.name: expected String, got ${PluginValue.showAny(other)}")
            case None =>
              PluginError.raise("ContentToolkitComponent.name is required")
          val render = fields.getOrElse("render", PluginError.raise(s"ContentToolkitComponent('$name').render is required"))
          acc.updated(name, render)
        }
      case Some(other) =>
        PluginError.raise(s"ContentToolkitOptions.components: expected List, got ${PluginValue.showAny(other)}")

  private def boolField(fields: Map[String, PluginValue], name: String, default: Boolean): Boolean =
    fields.get(name) match
      case Some(Bool(v)) => v
      case None                 => default
      case Some(other)          => PluginError.raise(s"ContentToolkitOptions.$name: expected Boolean, got ${PluginValue.showAny(other)}")

  private def intField(fields: Map[String, PluginValue], name: String, default: Int): Int =
    fields.get(name) match
      case Some(Num(v)) => v.toInt
      case None                => default
      case Some(other)         => PluginError.raise(s"ContentToolkitOptions.$name: expected Int, got ${PluginValue.showAny(other)}")

  private def contentValueMapField(value: Option[PluginValue]): Map[String, ast.ContentValue] =
    value match
      case None | Some(PluginValue.nullV) => Map.empty
      case Some(InstAny(inst)) if inst.typeNameOf.contains("MapV") =>
        astContentValueMapEntries(inst, "ContentToolkitOptions.bindings")
      case Some(other) =>
        PluginError.raise(s"ContentToolkitOptions.bindings: expected ContentValue.MapV, got ${PluginValue.showAny(other)}")

  private def astContentValueMapEntries(value: PluginValue, context: String): Map[String, ast.ContentValue] =
    value match
      case InstAny(inst) if inst.typeNameOf.contains("MapV") =>
        bindField(inst.fields, "values", s"$context.values") match
          case MapVal(entries) =>
            entries.map {
              case (Str(key), value) => key -> astContentValue(value, s"$context.$key")
              case (key, _) => PluginError.raise(s"$context expected string keys, got ${PluginValue.showAny(key)}")
            }
          case other =>
            PluginError.raise(s"$context.values expected Map, got ${PluginValue.showAny(other)}")
      case other =>
        PluginError.raise(s"$context expected ContentValue.MapV, got ${PluginValue.showAny(other)}")

  private def astContentValue(value: PluginValue, context: String): ast.ContentValue =
    value match
      case InstAny(inst) if inst.typeNameOf.contains("Str") =>
        ast.ContentValue.Str(bindString(bindField(inst.fields, "value", s"$context.value"), s"$context.value"))
      case InstAny(inst) if inst.typeNameOf.contains("Bool") =>
        ast.ContentValue.Bool(bindBool(bindField(inst.fields, "value", s"$context.value"), s"$context.value"))
      case InstAny(inst) if inst.typeNameOf.contains("Num") =>
        ast.ContentValue.Num(bindDouble(bindField(inst.fields, "value", s"$context.value"), s"$context.value"))
      case InstAny(inst) if inst.typeNameOf.contains("NullV") =>
        ast.ContentValue.NullV
      case InstAny(inst) if inst.typeNameOf.contains("ListV") =>
        ast.ContentValue.ListV(bindList(bindField(inst.fields, "values", s"$context.values"), s"$context.values").map(astContentValue(_, context)))
      case InstAny(inst) if inst.typeNameOf.contains("MapV") =>
        ast.ContentValue.MapV(astContentValueMapEntries(inst, context))
      case other =>
        PluginError.raise(s"$context expected ContentValue, got ${PluginValue.showAny(other)}")

  private def instanceValue(typeName: String, fields: (String, PluginValue)*): PluginValue =
    // Array-backed instance preserving field declaration order (consumers read fieldNames/fieldsArr).
    PluginValue.orderedInstance(typeName, fields)

  // Build the toolkit env for a document/block/section: markdown signal defaults,
  // with code-registered computed signals (Scope B.5) merged in underneath (a
  // locally-declared signal of the same name wins), plus the action / rowBinding
  // registries.
  private def toolkitEnvFor(ctx: PluginContext, base: ToolkitUiEnv, options: ToolkitOptions): ToolkitUiEnv =
    // Closure to build a typed DataColumn from a registered column-builder native
    // (Scope B.2) — captures ctx so toolkitControl can construct inline columns.
    val buildColumn: (String, List[Any]) => PluginValue = (name, args) =>
      ctx.resolveGlobal(name) match
        case Some(fn) => ctx.invokeCallback(fn, args).asInstanceOf[PluginValue]
        case None     => PluginError.raise(
          s"contentToolkitNode: table column builder '$name' is not available — import it from std/ui/data (fcol/mcol/scol/dcol/lcol)")
    base.copy(signals = options.computed ++ base.signals,
              actions = options.actions, rowBindings = options.rowBindings,
              buildColumn = Some(buildColumn))

  private def toolkitDocumentNode(ctx: PluginContext, doc: ast.DocumentContent, options: ToolkitOptions): PluginValue =
    val env = toolkitEnvFor(ctx, toolkitMarkdownEnv(doc), options)
    val children =
      doc.blocks.map(toolkitBlockNode(ctx, doc, _, options, env)) ++
        doc.sections.map(toolkitSectionNode(ctx, doc, _, options, env, topLevel = true))
    val body = vstackNode(options.sectionGap, children)
    if options.wrapDocumentInCard then cardNode(List(body)) else body

  private def toolkitBlockById(ctx: PluginContext, doc: ast.DocumentContent, id: String, options: ToolkitOptions): PluginValue =
    blocksDeep(doc).filter(block => blockId(block).contains(id)) match
      case block :: Nil =>
        toolkitBlockNode(ctx, doc, block, options, toolkitEnvFor(ctx, toolkitMarkdownEnv(block), options))
      case Nil =>
        PluginError.raise(s"contentToolkitBlock: no block with id '$id'")
      case _ =>
        PluginError.raise(s"contentToolkitBlock: duplicate block id '$id'")

  private def toolkitSectionById(ctx: PluginContext, doc: ast.DocumentContent, id: String, options: ToolkitOptions): PluginValue =
    sectionsDeep(doc).filter(_.id == id) match
      case section :: Nil =>
        toolkitSectionNode(ctx, doc, section, options, toolkitEnvFor(ctx, toolkitMarkdownEnv(section), options), topLevel = true)
      case Nil =>
        // Not in the current document: fall back to the registered IMPORTED
        // documents. On v1 an imported module's code runs with ITS OWN document
        // as current, so `contentToolkitSection("id")` inside the module finds
        // the module's section; the v2 bridge inlines imports into one program
        // whose current document is the entry file's — the module's sections
        // are only reachable through ContentImportedModules. Fires only where
        // this previously raised, so behavior elsewhere is unchanged.
        importedDocumentTable(ctx).values.flatten.toList
          .flatMap(d => sectionsDeep(d).filter(_.id == id).map(s => (d, s))) match
          case (d2, s2) :: Nil =>
            toolkitSectionNode(ctx, d2, s2, options, toolkitEnvFor(ctx, toolkitMarkdownEnv(s2), options), topLevel = true)
          case Nil =>
            PluginError.raise(s"contentToolkitSection: no section with id '$id'")
          case _ =>
            PluginError.raise(s"contentToolkitSection: duplicate section id '$id'")
      case _ =>
        PluginError.raise(s"contentToolkitSection: duplicate section id '$id'")

  private def blocksDeep(doc: ast.DocumentContent): List[ast.ContentBlock] =
    doc.blocks.flatMap(block => blocksDeep(block)) ++
      doc.sections.flatMap(section => blocksDeep(section))

  private def blocksDeep(section: ast.SectionContent): List[ast.ContentBlock] =
    section.blocks.flatMap(block => blocksDeep(block)) ++
      section.children.flatMap(child => blocksDeep(child))

  private def blocksDeep(block: ast.ContentBlock): List[ast.ContentBlock] =
    block match
      case ast.ContentBlock.BulletList(items, _) =>
        block :: items.flatten.flatMap(child => blocksDeep(child))
      case ast.ContentBlock.OrderedList(items, _, _) =>
        block :: items.flatten.flatMap(child => blocksDeep(child))
      case _ =>
        block :: Nil

  private def sectionsDeep(doc: ast.DocumentContent): List[ast.SectionContent] =
    doc.sections.flatMap(section => sectionsDeep(section))

  private def sectionsDeep(section: ast.SectionContent): List[ast.SectionContent] =
    section :: section.children.flatMap(child => sectionsDeep(child))

  private def blockId(block: ast.ContentBlock): Option[String] =
    contentStringAttr(blockAttrs(block), "id")

  private def blockAttrs(block: ast.ContentBlock): Map[String, ast.ContentValue] =
    block match
      case ast.ContentBlock.Paragraph(_, attrs)             => attrs
      case ast.ContentBlock.BulletList(_, attrs)            => attrs
      case ast.ContentBlock.OrderedList(_, _, attrs)        => attrs
      case ast.ContentBlock.Image(_, _, _, attrs)           => attrs
      case ast.ContentBlock.Table(_, _, _, attrs)           => attrs
      case ast.ContentBlock.Embedded(_, _, _, _, attrs)     => attrs

  private def contentStringAttr(attrs: Map[String, ast.ContentValue], name: String): Option[String] =
    attrs.get(name) match
      case Some(ast.ContentValue.Str(value)) => Some(value)
      case _                                => None

  private def contentSectionById(doc: ast.DocumentContent, id: String): Option[ast.SectionContent] =
    sectionsDeep(doc).filter(_.id == id) match
      case Nil           => None
      case section :: Nil => Some(section)
      case _             => PluginError.raise(s"contentSection: duplicate section id '$id'")

  private def contentBlockById(doc: ast.DocumentContent, id: String): Option[ast.ContentBlock] =
    blocksDeep(doc).filter(block => blockId(block).contains(id)) match
      case Nil          => None
      case block :: Nil => Some(block)
      case _            => PluginError.raise(s"contentBlock: duplicate block id '$id'")

  private def componentRenderer(
      attrs: Map[String, ast.ContentValue],
      options: ToolkitOptions
  ): Option[(String, PluginValue)] =
    contentStringAttr(attrs, "component").flatMap(name => options.components.get(name).map(name -> _))

  private def contentDataById(doc: ast.DocumentContent, id: String): Option[ast.ContentValue] =
    val matches = blocksDeep(doc).collect {
      case block @ ast.ContentBlock.Embedded(_, _, ast.EmbeddedKind.StructuredData, data, _)
          if blockId(block).contains(id) =>
        data
    }
    matches match
      case Nil          => None
      case value :: Nil => value
      case _            => PluginError.raise(s"contentData: duplicate structured data id '$id'")

  private def contentMetadataPath(doc: ast.DocumentContent, path: String): Option[ast.ContentValue] =
    val segments = contentMetadataSegments(path)
    doc.manifest match
      case ast.ContentValue.MapV(root) =>
        root.get("content").flatMap(value => contentMetadataPath(value, segments))
      case _ =>
        None

  private def contentMetadataPath(value: ast.ContentValue, segments: List[String]): Option[ast.ContentValue] =
    segments match
      case Nil =>
        Some(value)
      case segment :: rest =>
        value match
          case ast.ContentValue.MapV(values) =>
            values.get(segment).flatMap(contentMetadataPath(_, rest))
          case _ =>
            None

  private def contentMetadataSegments(path: String): List[String] =
    val trimmed = path.trim
    if trimmed.isEmpty || trimmed.startsWith(".") || trimmed.endsWith(".") || trimmed.contains("..") then
      PluginError.raise("contentMetadata: path must be non-empty dot-separated segments")
    trimmed.split("\\.").toList

  private def resolvedComponentData(
      doc: ast.DocumentContent,
      attrs: Map[String, ast.ContentValue],
      fallback: Option[ast.ContentValue]
  ): Option[ast.ContentValue] =
    contentStringAttr(attrs, "data") match
      case Some(id) => contentDataById(doc, id)
      case None     => fallback

  private def renderComponent(ctx: PluginContext, name: String, render: PluginValue, context: PluginValue): PluginValue =
    ctx.invokeCallback(render, List(context)) match
      case value if PluginValue.isRuntimeValue(value) => PluginValue.wrap(value)
      case other =>
        PluginError.raise(s"contentToolkitNode: component '$name' returned non-value $other")

  private def sectionComponentContext(name: String, doc: ast.DocumentContent, section: ast.SectionContent): PluginValue =
    instanceValue("ContentComponentContext",
      "name"    -> PluginValue.string(name),
      "kind"    -> PluginValue.string("section"),
      "id"      -> PluginValue.string(section.id),
      "title"   -> optionString(Some(section.title)),
      "attrs"   -> attrsValue(section.attrs),
      "section" -> PluginValue.option(Some(sectionValue(section))),
      "block"   -> PluginValue.option(None),
      "data"    -> PluginValue.option(resolvedComponentData(doc, section.attrs, fallback = None).map(contentValue))
    )

  private def blockComponentContext(name: String, doc: ast.DocumentContent, block: ast.ContentBlock): PluginValue =
    val attrs = blockAttrs(block)
    instanceValue("ContentComponentContext",
      "name"    -> PluginValue.string(name),
      "kind"    -> PluginValue.string("block"),
      "id"      -> PluginValue.string(blockId(block).getOrElse("")),
      "title"   -> optionString(None),
      "attrs"   -> attrsValue(attrs),
      "section" -> PluginValue.option(None),
      "block"   -> PluginValue.option(Some(blockValue(block))),
      "data"    -> PluginValue.option(resolvedComponentData(doc, attrs, blockData(block)).map(contentValue))
    )

  private def blockData(block: ast.ContentBlock): Option[ast.ContentValue] =
    block match
      case ast.ContentBlock.Embedded(_, _, _, data, _) => data
      case _                                           => None

  private def toolkitSectionNode(
      ctx: PluginContext,
      doc: ast.DocumentContent,
      section: ast.SectionContent,
      options: ToolkitOptions,
      env: ToolkitUiEnv,
      topLevel: Boolean
  ): PluginValue =
    componentRenderer(section.attrs, options) match
      case Some((name, render)) =>
        return renderComponent(ctx, name, render, sectionComponentContext(name, doc, section))
      case None => ()
    val children =
      headingNode(section.level, section.title) ::
        (section.blocks.map(toolkitBlockNode(ctx, doc, _, options, env)) ++
          section.children.map(toolkitSectionNode(ctx, doc, _, options, env, topLevel = false)))
    val stack = vstackNode(options.blockGap, children)
    if topLevel && options.wrapTopLevelSectionsInCards then cardNode(List(stack)) else stack

  private def toolkitBlockNode(
      ctx: PluginContext,
      doc: ast.DocumentContent,
      block: ast.ContentBlock,
      options: ToolkitOptions,
      env: ToolkitUiEnv
  ): PluginValue =
    componentRenderer(blockAttrs(block), options) match
      case Some((name, render)) =>
        return renderComponent(ctx, name, render, blockComponentContext(name, doc, block))
      case None => ()
    block match
      case ast.ContentBlock.Paragraph(inlines, _) =>
        singleToolkitLink(inlines)
          .map(toolkitLinkNode(_, env))
          .getOrElse(textNode_(inlineText(inlines)))
      case ast.ContentBlock.BulletList(items, _) =>
        vstackNode(options.listGap, items.map { item =>
          toolkitListItemNode(item, env).getOrElse(rawTextNode("- " + blocksText(item)))
        })
      case ast.ContentBlock.OrderedList(items, start, _) =>
        vstackNode(options.listGap, items.zipWithIndex.map { case (item, idx) =>
          toolkitListItemNode(item, env).getOrElse(rawTextNode(s"${start + idx}. ${blocksText(item)}"))
        })
      case ast.ContentBlock.Image(src, alt, title, _) =>
        val label =
          if alt.isEmpty then src
          else title match
            case Some(t) => s"$alt ($t)"
            case None    => alt
        rawTextNode(label)
      case ast.ContentBlock.Table(headers, rows, _, _) =>
        tableNode(headers, rows)
      case ast.ContentBlock.Embedded(lang, _, _, data, attrs) if isToolkitUiBlock(lang, attrs) =>
        data match
          case Some(value) => toolkitUiNode(value, options, env)
          case None =>
            PluginError.raise("contentToolkitNode: @ui=toolkit requires a structured YAML/JSON/TOML block")
      case ast.ContentBlock.Embedded(lang, source, _, _, _) =>
        if options.includeCode then
          val label = if lang.isEmpty then "" else s"$lang\n"
          rawTextNode(label + source)
        else fragmentNode(Nil)

  private def isToolkitUiBlock(lang: String, attrs: Map[String, ast.ContentValue]): Boolean =
    attrs.get("ui").contains(ast.ContentValue.Str("toolkit")) &&
      Set("yaml", "yml", "json", "toml").contains(lang)

  private def toolkitUiNode(value: ast.ContentValue, options: ToolkitOptions, baseEnv: ToolkitUiEnv): PluginValue =
    val root = contentMap(value, "@ui=toolkit")
    val signals = root.get("signals").map(toolkitSignals).getOrElse(Map.empty)
    val env = ToolkitUiEnv(baseEnv.signals ++ signals, baseEnv.actions, baseEnv.rowBindings, baseEnv.buildColumn)
    root.get("controls")
      .orElse(root.get("control"))
      .map(toolkitControl(_, env, options))
      .getOrElse(PluginError.raise("contentToolkitNode: @ui=toolkit block requires controls"))

  private def toolkitMarkdownEnv(doc: ast.DocumentContent): ToolkitUiEnv =
    ToolkitUiEnv(markdownToolkitSignalDefaults(doc).foldLeft(Map.empty[String, PluginValue]) {
      case (acc, (name, initial)) =>
        if acc.contains(name) then acc else acc.updated(name, reactiveSignal(name, initial))
    })

  private def toolkitMarkdownEnv(section: ast.SectionContent): ToolkitUiEnv =
    ToolkitUiEnv(markdownToolkitSignalDefaults(section).foldLeft(Map.empty[String, PluginValue]) {
      case (acc, (name, initial)) =>
        if acc.contains(name) then acc else acc.updated(name, reactiveSignal(name, initial))
    })

  private def toolkitMarkdownEnv(block: ast.ContentBlock): ToolkitUiEnv =
    ToolkitUiEnv(markdownToolkitSignalDefaults(block).foldLeft(Map.empty[String, PluginValue]) {
      case (acc, (name, initial)) =>
        if acc.contains(name) then acc else acc.updated(name, reactiveSignal(name, initial))
    })

  private def markdownToolkitSignalDefaults(doc: ast.DocumentContent): List[(String, ast.ContentValue)] =
    doc.blocks.flatMap(markdownToolkitSignalDefaults) ++
      doc.sections.flatMap(markdownToolkitSignalDefaults)

  private def markdownToolkitSignalDefaults(section: ast.SectionContent): List[(String, ast.ContentValue)] =
    section.blocks.flatMap(markdownToolkitSignalDefaults) ++
      section.children.flatMap(markdownToolkitSignalDefaults)

  private def markdownToolkitSignalDefaults(block: ast.ContentBlock): List[(String, ast.ContentValue)] =
    block match
      case ast.ContentBlock.Paragraph(inlines, _) =>
        singleToolkitLink(inlines).flatMap(toolkitLinkSignalDefault).toList
      case ast.ContentBlock.BulletList(items, _) =>
        items.flatten.flatMap(markdownToolkitSignalDefaults)
      case ast.ContentBlock.OrderedList(items, _, _) =>
        items.flatten.flatMap(markdownToolkitSignalDefaults)
      case _ =>
        Nil

  private def singleToolkitLink(inlines: List[ast.ContentInline]): Option[ToolkitLink] =
    val significant = inlines.filter {
      case ast.ContentInline.Text(value) => value.trim.nonEmpty
      case _                             => true
    }
    significant match
      case ast.ContentInline.Link(label, href, _) :: Nil if href.startsWith("toolkit:") =>
        Some(parseToolkitLink(href, inlineText(label)))
      case _ =>
        None

  private def toolkitListItemNode(item: List[ast.ContentBlock], env: ToolkitUiEnv): Option[PluginValue] =
    item match
      case ast.ContentBlock.Paragraph(inlines, _) :: Nil =>
        singleToolkitLink(inlines).map(toolkitLinkNode(_, env))
      case _ =>
        None

  private def parseToolkitLink(href: String, label: String): ToolkitLink =
    val raw = href.stripPrefix("toolkit:")
    val question = raw.indexOf('?')
    val (kindPart, queryPart) =
      if question < 0 then (raw, "")
      else (raw.take(question), raw.drop(question + 1))
    val kind = normalizeControlKind(urlDecode(kindPart))
    if kind.isEmpty then PluginError.raise("contentToolkitNode: toolkit link requires a control kind")
    val query =
      if queryPart.isEmpty then Map.empty[String, String]
      else queryPart.split("&").toList.filter(_.nonEmpty).map { pair =>
        val eq = pair.indexOf('=')
        if eq < 0 then urlDecode(pair) -> ""
        else urlDecode(pair.take(eq)) -> urlDecode(pair.drop(eq + 1))
      }.toMap
    ToolkitLink(kind, query, label)

  private def urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def toolkitLinkSignalDefault(link: ToolkitLink): Option[(String, ast.ContentValue)] =
    link.query.get("signal").map { name =>
      val initial = link.kind match
        case "textfield" | "input" =>
          ast.ContentValue.Str(link.query.getOrElse("value", ""))
        case "checkbox" =>
          ast.ContentValue.Bool(toolkitLinkBoolValue(link, "checked")
            .orElse(toolkitLinkBoolValue(link, "value"))
            .getOrElse(false))
        case "button" | "signalbutton" =>
          ast.ContentValue.Bool(false)
        case "signaltext" =>
          ast.ContentValue.Str(link.query.getOrElse("value", ""))
        case _ =>
          ast.ContentValue.Str("")
      name -> initial
    }

  private def toolkitLinkNode(link: ToolkitLink, env: ToolkitUiEnv): PluginValue =
    link.kind match
      case "textfield" | "input" =>
        textFieldNode(
          signalRef(requiredToolkitQuery(link, "signal"), env, "toolkit:textField"),
          toolkitLinkLabel(link),
          toolkitLinkBool(link, "disabled", default = false),
          toolkitLinkBool(link, "required", default = false)
        )
      case "checkbox" =>
        checkboxNode(
          signalRef(requiredToolkitQuery(link, "signal"), env, "toolkit:checkbox"),
          toolkitLinkLabel(link),
          toolkitLinkBool(link, "disabled", default = false)
        )
      case "button" | "signalbutton" =>
        val label = toolkitLinkLabel(link)
        val disabled = toolkitLinkBool(link, "disabled", default = false)
        // `action=<id>` binds the button to a registered EventHandler (a server
        // write), turning a declarative Markdown link into a typed effect.  Without
        // it, the button sets a local signal (`signal=`) as before.
        link.query.get("action") match
          case Some(actionId) =>
            val handler = env.actions.getOrElse(actionId, PluginError.raise(
              s"contentToolkitNode: toolkit:button action '$actionId' is not registered " +
              s"(available: ${if env.actions.isEmpty then "<none>" else env.actions.keys.toList.sorted.mkString(", ")})"))
            link.query.get("enabledWhen") match
              case Some(name) =>
                showWhenNode(
                  signalRef(name, env, "toolkit:button.enabledWhen"),
                  actionButtonNode(handler, label, disabled),
                  actionButtonNode(handler, label, disabled = true)
                )
              case None =>
                actionButtonNode(handler, label, disabled)
          case None =>
            val signal = signalRef(requiredToolkitQuery(link, "signal"), env, "toolkit:button")
            val value = toolkitLinkLiteral(link.query.get("value").getOrElse("true"))
            link.query.get("enabledWhen") match
              case Some(name) =>
                showWhenNode(
                  signalRef(name, env, "toolkit:button.enabledWhen"),
                  signalButtonNode(signal, value, label, disabled),
                  signalButtonNode(signal, value, label, disabled = true)
                )
              case None =>
                signalButtonNode(signal, value, label, disabled)
      case "signaltext" =>
        signalTextNode(signalRef(requiredToolkitQuery(link, "signal"), env, "toolkit:signalText"))
      case "badge" =>
        badgeNode(link.query.getOrElse("text", toolkitLinkLabel(link)), link.query.getOrElse("variant", "default"))
      case "divider" =>
        dividerNode()
      case "table" =>
        // `rows=<id>` binds a live DataTable whose row source is a runtime signal
        // registered under <id> in rowBindings (3b), mirroring the action registry.
        val regionId = requiredToolkitQuery(link, "rows")
        val binding = env.rowBindings.getOrElse(regionId, PluginError.raise(
          s"contentToolkitNode: toolkit:table rows '$regionId' is not registered " +
          s"(available: ${if env.rowBindings.isEmpty then "<none>" else env.rowBindings.keys.toList.sorted.mkString(", ")})"))
        rowBindingDataTable(regionId, binding)
      case other =>
        PluginError.raise(s"contentToolkitNode: unsupported toolkit link control '$other'")

  // rowBindingDataTable — turn a registered ContentRowBinding into a DataTableNode.
  // Reuses the existing DataTableNode lowering (web <table> / Swing JTable); the
  // signal + field-columns + per-row actions pass through opaquely as Values.
  private def rowBindingDataTable(regionId: String, binding: PluginValue, overrideColumns: Option[PluginValue] = None): PluginValue =
    val fields = binding match
      case InstAny(inst) if inst.typeNameOf.contains("ContentRowBinding") => inst.fields
      case other => PluginError.raise(
        s"contentToolkitNode: toolkit:table rows '$regionId' expected a ContentRowBinding, got ${PluginValue.showAny(other)}")
    val rows = fields.getOrElse("rows", PluginError.raise(
      s"contentToolkitNode: ContentRowBinding('$regionId').rows is required"))
    // Inline YAML `columns:` (Scope B.2) override the registered columns for this
    // table; otherwise the ContentRowBinding's registered columns are used.
    val columns = overrideColumns.getOrElse(fields.get("columns") match
      case Some(cols) if cols.asList.isDefined => cols
      case Some(other) => PluginError.raise(
        s"contentToolkitNode: ContentRowBinding('$regionId').columns expected a List, got ${PluginValue.showAny(other)}")
      case None => PluginValue.list(Nil))
    val actions = fields.get("actions") match
      case Some(acts) if acts.asList.isDefined => acts
      case _                       => PluginValue.list(Nil)
    instanceValue("DataTableNode",
      "signal"  -> rows,
      "columns" -> columns,
      "actions" -> actions)

  // Build typed DataColumns from an inline YAML `columns:` list (Scope B.2) by
  // invoking the registered column-builder natives via env.buildColumn, so the
  // author can declare columns at the call site instead of only in code.
  private def toolkitInlineColumns(columnsValue: ast.ContentValue, env: ToolkitUiEnv): PluginValue =
    val build = env.buildColumn.getOrElse(PluginError.raise(
      "contentToolkitNode: inline table columns require a render context"))
    val specs = columnsValue match
      case ast.ContentValue.ListV(items) => items
      case other => PluginError.raise(
        s"contentToolkitNode: table columns expected a list, got ${contentValueKind(other)}")
    PluginValue.list(specs.map { spec =>
      val cf    = contentMap(spec, "table column")
      val label = firstContentString(cf, "label", "title").getOrElse(
        PluginError.raise("contentToolkitNode: table column requires a label"))
      val path  = firstContentString(cf, "path", "fieldPath").getOrElse(
        PluginError.raise("contentToolkitNode: table column requires a path"))
      val align = contentStringField(cf, "align", "")
      normalizeControlKind(contentStringField(cf, "kind", "text")) match
        case "text" | "" => build("fieldColumn",  List(label, path, align))
        case "date"      => build("dateColumn",   List(label, path, align, contentStringField(cf, "format", "")))
        case "money"     => build("moneyColumn",  List(label, path, align, contentStringField(cf, "currency", "USD"), contentStringField(cf, "locale", "")))
        case "status"    => build("statusColumn", List(label, path, align, toolkitColorMap(cf)))
        case "link"      => build("linkColumn",   List(label, path, align, contentStringField(cf, "url", "")))
        case other       => PluginError.raise(s"contentToolkitNode: unknown table column kind '$other'")
    })

  // colors: {open: green, blocked: red} → Value.MapV for statusColumn; null if absent.
  private def toolkitColorMap(cf: Map[String, ast.ContentValue]): Any =
    cf.get("colors") match
      case Some(ast.ContentValue.MapV(entries)) =>
        PluginValue.mapOf(entries.collect { case (k, ast.ContentValue.Str(v)) => PluginValue.string(k) -> PluginValue.string(v) })
      case _ => PluginValue.nullV

  private def toolkitLinkLabel(link: ToolkitLink): String =
    link.query.getOrElse("label", link.label)

  private def requiredToolkitQuery(link: ToolkitLink, name: String): String =
    link.query.get(name).filter(_.nonEmpty)
      .getOrElse(PluginError.raise(s"contentToolkitNode: toolkit:${link.kind} requires $name"))

  private def toolkitLinkBool(link: ToolkitLink, name: String, default: Boolean): Boolean =
    toolkitLinkBoolValue(link, name).getOrElse(default)

  private def toolkitLinkBoolValue(link: ToolkitLink, name: String): Option[Boolean] =
    link.query.get(name).map {
      case "true"  => true
      case "false" => false
      case other =>
        PluginError.raise(s"contentToolkitNode: toolkit:${link.kind} $name expected true or false, got '$other'")
    }

  private def toolkitLinkLiteral(value: String): PluginValue =
    value match
      case "true"  => PluginValue.bool(true)
      case "false" => PluginValue.bool(false)
      case other   => PluginValue.string(other)

  private def toolkitSignals(value: ast.ContentValue): Map[String, PluginValue] =
    contentMap(value, "signals").map { case (name, initial) =>
      name -> reactiveSignal(name, initial)
    }

  private def reactiveSignal(name: String, value: ast.ContentValue): PluginValue =
    value match
      case ast.ContentValue.Str(v)  => PluginValue.foreign("ReactiveSignal", new ReactiveSignal[String](name, v))
      case ast.ContentValue.Bool(v) => PluginValue.foreign("ReactiveSignal", new ReactiveSignal[Boolean](name, v))
      case ast.ContentValue.Num(v) if isIntLike(v) =>
        PluginValue.foreign("ReactiveSignal", new ReactiveSignal[Int](name, v.toInt))
      case ast.ContentValue.Num(v) =>
        PluginValue.foreign("ReactiveSignal", new ReactiveSignal[Double](name, v))
      case ast.ContentValue.NullV =>
        PluginValue.foreign("ReactiveSignal", new ReactiveSignal[String](name, ""))
      case other =>
        PluginError.raise(s"contentToolkitNode: signal '$name' default must be scalar, got ${contentValueKind(other)}")

  private def toolkitControl(value: ast.ContentValue, env: ToolkitUiEnv, options: ToolkitOptions): PluginValue =
    value match
      case ast.ContentValue.ListV(values) =>
        fragmentNode(values.map(toolkitControl(_, env, options)))
      case other =>
        val fields = contentMap(other, "control")
        val kind = normalizeControlKind(requiredContentString(fields, "type", "control"))
        kind match
          case "vstack" =>
            vstackNode(
              contentIntField(fields, "gap", options.blockGap),
              toolkitChildren(fields, env, options)
            )
          case "hstack" =>
            hstackNode(
              contentIntField(fields, "gap", options.blockGap),
              toolkitChildren(fields, env, options)
            )
          case "fragment" =>
            fragmentNode(toolkitChildren(fields, env, options))
          case "divider" =>
            dividerNode()
          case "heading" =>
            headingNode(
              contentIntField(fields, "level", 2),
              requiredContentString(fields, "text", "heading")
            )
          case "text" | "paragraph" =>
            textNode_(requiredContentString(fields, "text", "text"))
          case "rawtext" =>
            rawTextNode(requiredContentString(fields, "text", "rawText"))
          case "signaltext" =>
            signalTextNode(signalField(fields, env, "signalText", "signal", "value"))
          case "show" | "showwhen" =>
            showWhenNode(
              signalField(fields, env, "show", "signal", "condition"),
              requiredControlField(fields, env, options, "show", "then", "whenTrue"),
              optionalControlField(fields, env, options, "else", "whenFalse").getOrElse(fragmentNode(Nil))
            )
          case "textfield" | "input" =>
            textFieldNode(
              signalField(fields, env, "textField", "signal", "value"),
              contentStringField(fields, "label", ""),
              contentBoolField(fields, "disabled", default = false),
              contentBoolField(fields, "required", default = false)
            )
          case "checkbox" =>
            checkboxNode(
              signalField(fields, env, "checkbox", "signal", "checked"),
              contentStringField(fields, "label", ""),
              contentBoolField(fields, "disabled", default = false)
            )
          case "button" | "signalbutton" =>
            toolkitButton(fields, env)
          case "table" =>
            // {type: table, source: <id>} (alias rows:) binds a live DataTable to
            // the ContentRowBinding registered under <id> (Scope B.1).  Optional
            // inline `columns:` (Scope B.2) declare typed columns at the call site,
            // overriding the registered columns.
            val regionId = firstContentString(fields, "source", "rows").getOrElse(
              PluginError.raise("contentToolkitNode: table control requires source or rows"))
            val binding = env.rowBindings.getOrElse(regionId, PluginError.raise(
              s"contentToolkitNode: table source '$regionId' is not registered " +
              s"(available: ${if env.rowBindings.isEmpty then "<none>" else env.rowBindings.keys.toList.sorted.mkString(", ")})"))
            val inlineCols = fields.get("columns").map(toolkitInlineColumns(_, env))
            rowBindingDataTable(regionId, binding, inlineCols)
          case "badge" =>
            badgeNode(
              firstContentString(fields, "content", "text").getOrElse(""),
              contentStringField(fields, "variant", "default")
            )
          case "card" =>
            cardNode(
              optionalControlField(fields, env, options, "header").getOrElse(PluginValue.nullV),
              toolkitChildren(fields, env, options),
              optionalControlField(fields, env, options, "footer").getOrElse(PluginValue.nullV)
            )
          case "slot" =>
            // {type: slot, id: <id>} injects a code-built TkNode registered under
            // <id> in options.slots (Scope B.6) — the escape hatch.  Returned verbatim.
            val slotId = requiredContentString(fields, "id", "slot")
            options.slots.getOrElse(slotId, PluginError.raise(
              s"contentToolkitNode: slot '$slotId' is not registered " +
              s"(available: ${if options.slots.isEmpty then "<none>" else options.slots.keys.toList.sorted.mkString(", ")})"))
          case otherKind =>
            PluginError.raise(s"contentToolkitNode: unsupported control type '$otherKind'")

  private def toolkitButton(fields: Map[String, ast.ContentValue], env: ToolkitUiEnv): PluginValue =
    // {type: button, action: <id>} binds the button to a registered EventHandler
    // (a typed server write) — the YAML control-tree form of the
    // `toolkit:button?action=<id>` Markdown link (Scope B.1).  Without `action`,
    // the existing `signal`-bound button path is used unchanged.
    fields.get("action") match
      case Some(ast.ContentValue.Str(actionId)) =>
        val handler = env.actions.getOrElse(actionId, PluginError.raise(
          s"contentToolkitNode: button action '$actionId' is not registered " +
          s"(available: ${if env.actions.isEmpty then "<none>" else env.actions.keys.toList.sorted.mkString(", ")})"))
        val label = contentStringField(fields, "label", "")
        val disabled = contentBoolField(fields, "disabled", default = false)
        fields.get("enabledWhen") match
          case Some(ast.ContentValue.Str(name)) =>
            showWhenNode(
              signalRef(name, env, "button.enabledWhen"),
              actionButtonNode(handler, label, disabled),
              actionButtonNode(handler, label, disabled = true)
            )
          case Some(other) =>
            PluginError.raise(s"contentToolkitNode: button.enabledWhen expected String, got ${contentValueKind(other)}")
          case None =>
            actionButtonNode(handler, label, disabled)
      case Some(other) =>
        PluginError.raise(s"contentToolkitNode: button.action expected String, got ${contentValueKind(other)}")
      case None =>
        val signal = signalField(fields, env, "button", "signal")
        val value = fields.get("value").map(contentLiteral).getOrElse(PluginValue.bool(true))
        val label = contentStringField(fields, "label", "")
        fields.get("enabledWhen") match
          case Some(ast.ContentValue.Str(name)) =>
            showWhenNode(
              signalRef(name, env, "button.enabledWhen"),
              signalButtonNode(signal, value, label, disabled = false),
              signalButtonNode(signal, value, label, disabled = true)
            )
          case Some(other) =>
            PluginError.raise(s"contentToolkitNode: button.enabledWhen expected String, got ${contentValueKind(other)}")
          case None =>
            signalButtonNode(signal, value, label, contentBoolField(fields, "disabled", default = false))

  private def toolkitChildren(
    fields: Map[String, ast.ContentValue],
    env: ToolkitUiEnv,
    options: ToolkitOptions
  ): List[PluginValue] =
    fields.get("children").orElse(fields.get("body")) match
      case Some(ast.ContentValue.ListV(values)) => values.map(toolkitControl(_, env, options))
      case Some(other) =>
        PluginError.raise(s"contentToolkitNode: children expected List, got ${contentValueKind(other)}")
      case None => Nil

  private def requiredControlField(
    fields: Map[String, ast.ContentValue],
    env: ToolkitUiEnv,
    options: ToolkitOptions,
    context: String,
    names: String*
  ): PluginValue =
    optionalControlField(fields, env, options, names*)
      .getOrElse(PluginError.raise(s"contentToolkitNode: $context requires ${names.mkString(" or ")}"))

  private def optionalControlField(
    fields: Map[String, ast.ContentValue],
    env: ToolkitUiEnv,
    options: ToolkitOptions,
    names: String*
  ): Option[PluginValue] =
    names.iterator.flatMap(name => fields.get(name)).take(1).toList match
      case value :: Nil => Some(toolkitControl(value, env, options))
      case _            => None

  private def signalField(
    fields: Map[String, ast.ContentValue],
    env: ToolkitUiEnv,
    context: String,
    names: String*
  ): PluginValue =
    val name = names.iterator.flatMap(name => contentStringOption(fields, name)).take(1).toList match
      case value :: Nil => value
      case _ => PluginError.raise(s"contentToolkitNode: $context requires ${names.mkString(" or ")}")
    signalRef(name, env, context)

  private def signalRef(name: String, env: ToolkitUiEnv, context: String): PluginValue =
    env.signals.getOrElse(
      name,
      PluginError.raise(s"contentToolkitNode: $context references unknown signal '$name'")
    )

  private def contentMap(value: ast.ContentValue, context: String): Map[String, ast.ContentValue] =
    value match
      case ast.ContentValue.MapV(values) => values
      case other =>
        PluginError.raise(s"contentToolkitNode: $context expected object, got ${contentValueKind(other)}")

  private def requiredContentString(fields: Map[String, ast.ContentValue], name: String, context: String): String =
    contentStringOption(fields, name)
      .getOrElse(PluginError.raise(s"contentToolkitNode: $context requires $name"))

  private def contentStringField(fields: Map[String, ast.ContentValue], name: String, default: String): String =
    contentStringOption(fields, name).getOrElse(default)

  private def firstContentString(fields: Map[String, ast.ContentValue], names: String*): Option[String] =
    names.iterator.flatMap(name => contentStringOption(fields, name)).take(1).toList.headOption

  private def contentStringOption(fields: Map[String, ast.ContentValue], name: String): Option[String] =
    fields.get(name) match
      case Some(ast.ContentValue.Str(v)) => Some(v)
      case Some(other) =>
        PluginError.raise(s"contentToolkitNode: $name expected String, got ${contentValueKind(other)}")
      case None => None

  private def contentBoolField(fields: Map[String, ast.ContentValue], name: String, default: Boolean): Boolean =
    fields.get(name) match
      case Some(ast.ContentValue.Bool(v)) => v
      case Some(other) =>
        PluginError.raise(s"contentToolkitNode: $name expected Boolean, got ${contentValueKind(other)}")
      case None => default

  private def contentIntField(fields: Map[String, ast.ContentValue], name: String, default: Int): Int =
    fields.get(name) match
      case Some(ast.ContentValue.Num(v)) if isIntLike(v) => v.toInt
      case Some(other) =>
        PluginError.raise(s"contentToolkitNode: $name expected Int, got ${contentValueKind(other)}")
      case None => default

  private def contentLiteral(value: ast.ContentValue): PluginValue =
    value match
      case ast.ContentValue.Str(v)  => PluginValue.string(v)
      case ast.ContentValue.Bool(v) => PluginValue.bool(v)
      case ast.ContentValue.Num(v) if isIntLike(v) => PluginValue.int(v.toLong)
      case ast.ContentValue.Num(v)  => PluginValue.double(v)
      case ast.ContentValue.NullV   => PluginValue.nullV
      case other =>
        PluginError.raise(s"contentToolkitNode: literal expected scalar, got ${contentValueKind(other)}")

  private def isIntLike(value: Double): Boolean =
    value.isWhole && value >= Int.MinValue.toDouble && value <= Int.MaxValue.toDouble

  private def normalizeControlKind(value: String): String =
    value.toLowerCase(java.util.Locale.ROOT).filter(ch => ch != '-' && ch != '_')

  private def contentValueKind(value: ast.ContentValue): String =
    value match
      case ast.ContentValue.Str(_)   => "String"
      case ast.ContentValue.Bool(_)  => "Boolean"
      case ast.ContentValue.Num(_)   => "Number"
      case ast.ContentValue.ListV(_) => "List"
      case ast.ContentValue.MapV(_)  => "Object"
      case ast.ContentValue.NullV    => "Null"

  private def blocksText(blocks: List[ast.ContentBlock]): String =
    blocks.map(blockText).mkString(" ")

  private def blockText(block: ast.ContentBlock): String = block match
    case ast.ContentBlock.Paragraph(inlines, _) => inlineText(inlines)
    case ast.ContentBlock.BulletList(items, _)  => items.map(blocksText).mkString("; ")
    case ast.ContentBlock.OrderedList(items, start, _) =>
      items.zipWithIndex.map { case (item, idx) =>
        s"${start + idx}. ${blocksText(item)}"
      }.mkString("; ")
    case ast.ContentBlock.Image(src, alt, _, _) =>
      if alt.isEmpty then src else alt
    case ast.ContentBlock.Table(headers, rows, _, _) =>
      tableText(headers, rows)
    case ast.ContentBlock.Embedded(lang, source, _, _, _) =>
      if lang.isEmpty then source else s"$lang: $source"

  private def tableText(headers: List[List[ast.ContentInline]], rows: List[List[List[ast.ContentInline]]]): String =
    val header = headers.map(inlineText).mkString(" | ")
    val bodyRows = rows.map(row => row.map(inlineText).mkString(" | "))
    (header :: bodyRows).filter(_.trim.nonEmpty).mkString("\n")

  private def inlineText(inlines: List[ast.ContentInline]): String =
    inlines.map(inlineText).mkString

  private def inlineText(inline: ast.ContentInline): String = inline match
    case ast.ContentInline.Text(value) => value
    case ast.ContentInline.Emphasis(children) => inlineText(children)
    case ast.ContentInline.Strong(children)   => inlineText(children)
    case ast.ContentInline.Code(value)        => s"`$value`"
    case ast.ContentInline.Link(label, href, _) => inlineText(label) + s" ($href)"
    case ast.ContentInline.Expr(source)       => "${" + source + "}"

  private val contentBindPathPattern =
    "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$".r

  private def contentBindRoot(value: PluginValue): Map[String, PluginValue] =
    contentValueMapEntries(value, "contentBind: bindings expected ContentValue.MapV")

  private def contentBindValue(value: PluginValue, bindings: Map[String, PluginValue]): PluginValue =
    value match
      case InstAny(doc) if doc.typeNameOf.contains("DocumentContent") =>
        val fields = doc.fields
        instanceValue("DocumentContent",
          "manifest"    -> bindField(fields, "manifest", "DocumentContent.manifest"),
          "title"       -> bindField(fields, "title", "DocumentContent.title"),
          "description" -> bindField(fields, "description", "DocumentContent.description"),
          "attrs"       -> bindField(fields, "attrs", "DocumentContent.attrs"),
          "sections"    -> PluginValue.list(bindList(bindField(fields, "sections", "DocumentContent.sections"), "DocumentContent.sections").map(contentBindSection(_, bindings))),
          "blocks"      -> PluginValue.list(bindList(bindField(fields, "blocks", "DocumentContent.blocks"), "DocumentContent.blocks").map(contentBindBlock(_, bindings)))
        )
      case InstAny(section) if section.typeNameOf.contains("SectionContent") =>
        contentBindSection(section, bindings)
      case InstAny(block) if contentBlockValueTypeNames.contains(block.typeNameOf.getOrElse("")) =>
        contentBindBlock(block, bindings)
      case other =>
        PluginError.raise(s"contentBind: expected DocumentContent, SectionContent, or ContentBlock, got ${PluginValue.showAny(other)}")

  private def contentBindSection(value: PluginValue, bindings: Map[String, PluginValue]): PluginValue =
    val section = value match
      case InstAny(inst) if inst.typeNameOf.contains("SectionContent") => inst
      case other => PluginError.raise(s"contentBind: expected SectionContent, got ${PluginValue.showAny(other)}")
    val fields = section.fields
    instanceValue("SectionContent",
      "id"       -> bindField(fields, "id", "SectionContent.id"),
      "level"    -> bindField(fields, "level", "SectionContent.level"),
      "title"    -> bindField(fields, "title", "SectionContent.title"),
      "attrs"    -> bindField(fields, "attrs", "SectionContent.attrs"),
      "blocks"   -> PluginValue.list(bindList(bindField(fields, "blocks", "SectionContent.blocks"), "SectionContent.blocks").map(contentBindBlock(_, bindings))),
      "children" -> PluginValue.list(bindList(bindField(fields, "children", "SectionContent.children"), "SectionContent.children").map(contentBindSection(_, bindings)))
    )

  private def contentBindBlock(value: PluginValue, bindings: Map[String, PluginValue]): PluginValue =
    val block = value match
      case InstAny(inst) if contentBlockValueTypeNames.contains(inst.typeNameOf.getOrElse("")) => inst
      case other => PluginError.raise(s"contentBind: expected ContentBlock, got ${PluginValue.showAny(other)}")
    val fields = block.fields
    block.typeNameOf.getOrElse("") match
      case "Paragraph" =>
        instanceValue("Paragraph",
          "inlines" -> PluginValue.list(bindList(bindField(fields, "inlines", "Paragraph.inlines"), "Paragraph.inlines").map(contentBindInline(_, bindings))),
          "attrs"   -> bindField(fields, "attrs", "Paragraph.attrs")
        )
      case "BulletList" =>
        instanceValue("BulletList",
          "items" -> PluginValue.list(bindList(bindField(fields, "items", "BulletList.items"), "BulletList.items").map(item =>
            PluginValue.list(bindList(item, "BulletList.items.item").map(contentBindBlock(_, bindings)))
          )),
          "attrs" -> bindField(fields, "attrs", "BulletList.attrs")
        )
      case "OrderedList" =>
        instanceValue("OrderedList",
          "items" -> PluginValue.list(bindList(bindField(fields, "items", "OrderedList.items"), "OrderedList.items").map(item =>
            PluginValue.list(bindList(item, "OrderedList.items.item").map(contentBindBlock(_, bindings)))
          )),
          "start" -> bindField(fields, "start", "OrderedList.start"),
          "attrs" -> bindField(fields, "attrs", "OrderedList.attrs")
        )
      case "Image" =>
        instanceValue("Image",
          "src"   -> bindField(fields, "src", "Image.src"),
          "alt"   -> bindField(fields, "alt", "Image.alt"),
          "title" -> bindField(fields, "title", "Image.title"),
          "attrs" -> bindField(fields, "attrs", "Image.attrs")
        )
      case "Table" =>
        instanceValue("Table",
          "headers"    -> PluginValue.list(bindList(bindField(fields, "headers", "Table.headers"), "Table.headers").map(cell =>
            PluginValue.list(bindList(cell, "Table.headers.cell").map(contentBindInline(_, bindings)))
          )),
          "rows"       -> PluginValue.list(bindList(bindField(fields, "rows", "Table.rows"), "Table.rows").map(row =>
            PluginValue.list(bindList(row, "Table.rows.row").map(cell =>
              PluginValue.list(bindList(cell, "Table.rows.cell").map(contentBindInline(_, bindings)))
            ))
          )),
          "alignments" -> bindField(fields, "alignments", "Table.alignments"),
          "attrs"      -> bindField(fields, "attrs", "Table.attrs")
        )
      case "Embedded" =>
        instanceValue("Embedded",
          "lang"   -> bindField(fields, "lang", "Embedded.lang"),
          "source" -> bindField(fields, "source", "Embedded.source"),
          "kind"   -> bindField(fields, "kind", "Embedded.kind"),
          "data"   -> bindField(fields, "data", "Embedded.data"),
          "attrs"  -> bindField(fields, "attrs", "Embedded.attrs")
        )
      case other =>
        PluginError.raise(s"contentBind: expected ContentBlock, got $other")

  private def contentBindInline(value: PluginValue, bindings: Map[String, PluginValue]): PluginValue =
    val inline = value match
      case inst if PluginValue.isRuntimeValue(inst) => PluginValue.wrap(inst)
      case other => PluginError.raise(s"contentBind: expected ContentInline, got ${PluginValue.showAny(other)}")
    val fields = inline.fields
    inline.typeNameOf.getOrElse("") match
      case "Text" =>
        value
      case "Emphasis" =>
        instanceValue("Emphasis",
          "children" -> PluginValue.list(bindList(bindField(fields, "children", "Emphasis.children"), "Emphasis.children").map(contentBindInline(_, bindings)))
        )
      case "Strong" =>
        instanceValue("Strong",
          "children" -> PluginValue.list(bindList(bindField(fields, "children", "Strong.children"), "Strong.children").map(contentBindInline(_, bindings)))
        )
      case "Code" =>
        value
      case "Link" =>
        instanceValue("Link",
          "label" -> PluginValue.list(bindList(bindField(fields, "label", "Link.label"), "Link.label").map(contentBindInline(_, bindings))),
          "href"  -> bindField(fields, "href", "Link.href"),
          "title" -> bindField(fields, "title", "Link.title")
        )
      case "Expr" =>
        val source = bindString(bindField(fields, "source", "Expr.source"), "Expr.source")
        contentBindLookup(bindings, source)
          .map(value => instanceValue("Text", "value" -> PluginValue.string(contentValueText(value))))
          .getOrElse(value)
      case other =>
        PluginError.raise(s"contentBind: expected ContentInline, got $other")

  private def contentBindLookup(bindings: Map[String, PluginValue], source: String): Option[PluginValue] =
    if contentBindPathPattern.pattern.matcher(source).matches then
      val segments = source.split("\\.").toList
      segments match
        case head :: tail => bindings.get(head).flatMap(contentBindLookup(_, tail))
        case Nil          => None
    else None

  private def contentBindLookup(value: PluginValue, segments: List[String]): Option[PluginValue] =
    segments match
      case Nil => Some(value)
      case head :: tail =>
        value match
          case InstAny(inst) if inst.typeNameOf.contains("MapV") =>
            contentValueMapEntries(inst, "contentBind: nested binding expected ContentValue.MapV").get(head).flatMap(contentBindLookup(_, tail))
          case _ =>
            None

  private def contentBindDocument(doc: ast.DocumentContent, bindings: Map[String, ast.ContentValue]): ast.DocumentContent =
    if bindings.isEmpty then doc
    else doc.copy(
      sections = doc.sections.map(contentBindAstSection(_, bindings)),
      blocks = doc.blocks.map(contentBindAstBlock(_, bindings))
    )

  private def contentBindAstSection(section: ast.SectionContent, bindings: Map[String, ast.ContentValue]): ast.SectionContent =
    section.copy(
      blocks = section.blocks.map(contentBindAstBlock(_, bindings)),
      children = section.children.map(contentBindAstSection(_, bindings))
    )

  private def contentBindAstBlock(block: ast.ContentBlock, bindings: Map[String, ast.ContentValue]): ast.ContentBlock =
    block match
      case ast.ContentBlock.Paragraph(inlines, attrs) =>
        ast.ContentBlock.Paragraph(inlines.map(contentBindAstInline(_, bindings)), attrs)
      case ast.ContentBlock.BulletList(items, attrs) =>
        ast.ContentBlock.BulletList(items.map(_.map(contentBindAstBlock(_, bindings))), attrs)
      case ast.ContentBlock.OrderedList(items, start, attrs) =>
        ast.ContentBlock.OrderedList(items.map(_.map(contentBindAstBlock(_, bindings))), start, attrs)
      case image @ ast.ContentBlock.Image(_, _, _, _) =>
        image
      case ast.ContentBlock.Table(headers, rows, alignments, attrs) =>
        ast.ContentBlock.Table(
          headers.map(_.map(contentBindAstInline(_, bindings))),
          rows.map(_.map(_.map(contentBindAstInline(_, bindings)))),
          alignments,
          attrs
        )
      case embedded @ ast.ContentBlock.Embedded(_, _, _, _, _) =>
        embedded

  private def contentBindAstInline(inline: ast.ContentInline, bindings: Map[String, ast.ContentValue]): ast.ContentInline =
    inline match
      case text @ ast.ContentInline.Text(_) => text
      case ast.ContentInline.Emphasis(children) =>
        ast.ContentInline.Emphasis(children.map(contentBindAstInline(_, bindings)))
      case ast.ContentInline.Strong(children) =>
        ast.ContentInline.Strong(children.map(contentBindAstInline(_, bindings)))
      case code @ ast.ContentInline.Code(_) => code
      case ast.ContentInline.Link(label, href, title) =>
        ast.ContentInline.Link(label.map(contentBindAstInline(_, bindings)), href, title)
      case expr @ ast.ContentInline.Expr(source) =>
        contentBindAstLookup(bindings, source)
          .map(value => ast.ContentInline.Text(astContentValueText(value)))
          .getOrElse(expr)

  private def contentBindAstLookup(bindings: Map[String, ast.ContentValue], source: String): Option[ast.ContentValue] =
    if contentBindPathPattern.pattern.matcher(source).matches then
      source.split("\\.").toList match
        case head :: tail => bindings.get(head).flatMap(contentBindAstLookup(_, tail))
        case Nil          => None
    else None

  private def contentBindAstLookup(value: ast.ContentValue, segments: List[String]): Option[ast.ContentValue] =
    segments match
      case Nil => Some(value)
      case head :: tail =>
        value match
          case ast.ContentValue.MapV(values) => values.get(head).flatMap(contentBindAstLookup(_, tail))
          case _                             => None

  private def astContentValueText(value: ast.ContentValue): String =
    value match
      case ast.ContentValue.Str(v)    => v
      case ast.ContentValue.Bool(v)   => v.toString
      case ast.ContentValue.Num(v)    => numberString(v)
      case ast.ContentValue.NullV     => ""
      case ast.ContentValue.ListV(vs) => vs.map(astContentValueText).mkString(", ")
      case ast.ContentValue.MapV(vs)  =>
        vs.toList.sortBy(_._1).map { case (key, v) => s"$key: ${astContentValueText(v)}" }.mkString("{", ", ", "}")

  private def contentValueText(value: PluginValue): String =
    value match
      case InstAny(inst) if inst.typeNameOf.contains("Str") =>
        bindString(bindField(inst.fields, "value", "ContentValue.Str.value"), "ContentValue.Str.value")
      case InstAny(inst) if inst.typeNameOf.contains("Bool") =>
        bindBool(bindField(inst.fields, "value", "ContentValue.Bool.value"), "ContentValue.Bool.value").toString
      case InstAny(inst) if inst.typeNameOf.contains("Num") =>
        numberString(bindDouble(bindField(inst.fields, "value", "ContentValue.Num.value"), "ContentValue.Num.value"))
      case InstAny(inst) if inst.typeNameOf.contains("NullV") =>
        ""
      case InstAny(inst) if inst.typeNameOf.contains("ListV") =>
        bindList(bindField(inst.fields, "values", "ContentValue.ListV.values"), "ContentValue.ListV.values")
          .map(contentValueText)
          .mkString(", ")
      case InstAny(inst) if inst.typeNameOf.contains("MapV") =>
        contentValueMapEntries(inst, "ContentValue.MapV.values").toList.sortBy(_._1)
          .map { case (key, v) => s"$key: ${contentValueText(v)}" }
          .mkString("{", ", ", "}")
      case other =>
        PluginValue.showAny(other)

  private def contentValueMapEntries(value: PluginValue, context: String): Map[String, PluginValue] =
    value match
      case InstAny(inst) if inst.typeNameOf.contains("MapV") =>
        bindField(inst.fields, "values", "ContentValue.MapV.values") match
          case MapVal(entries) =>
            entries.map {
              case (Str(key), value) => key -> value
              case (key, _) => PluginError.raise(s"$context expected string keys, got ${PluginValue.showAny(key)}")
            }
          case other =>
            PluginError.raise(s"$context expected Map, got ${PluginValue.showAny(other)}")
      case other =>
        PluginError.raise(s"$context, got ${PluginValue.showAny(other)}")

  private def bindField(fields: Map[String, PluginValue], name: String, context: String): PluginValue =
    fields.getOrElse(name, PluginError.raise(s"contentBind: missing $context"))

  private def bindList(value: PluginValue, context: String): List[PluginValue] =
    value match
      case Lst(items) => items
      case other             => PluginError.raise(s"contentBind: $context expected List, got ${PluginValue.showAny(other)}")

  private def bindString(value: PluginValue, context: String): String =
    value match
      case Str(v) => v
      case other           => PluginError.raise(s"contentBind: $context expected String, got ${PluginValue.showAny(other)}")

  private def bindBool(value: PluginValue, context: String): Boolean =
    value match
      case Bool(v) => v
      case other          => PluginError.raise(s"contentBind: $context expected Boolean, got ${PluginValue.showAny(other)}")

  private def bindDouble(value: PluginValue, context: String): Double =
    value match
      case Dbl(v) => v
      case Num(v)    => v.toDouble
      case other            => PluginError.raise(s"contentBind: $context expected Number, got ${PluginValue.showAny(other)}")

  private val contentBlockValueTypeNames: Set[String] =
    Set("Paragraph", "BulletList", "OrderedList", "Image", "Table", "Embedded")

  private def contentPlainTextAny(value: Any): String =
    value match
      case v if PluginValue.isRuntimeValue(v) => contentPlainTextValue(PluginValue.wrap(v))
      case other =>
        PluginError.raise(s"contentPlainText: expected SectionContent or ContentBlock, got ${String.valueOf(other)}")

  private def contentPlainTextValue(value: PluginValue): String =
    value match
      case InstAny(section) if section.typeNameOf.contains("SectionContent") =>
        sectionPlainText(section)
      case InstAny(block) if contentBlockValueTypeNames.contains(block.typeNameOf.getOrElse("")) =>
        blockPlainText(block)
      case other =>
        PluginError.raise(s"contentPlainText: expected SectionContent or ContentBlock, got ${PluginValue.showAny(other)}")

  private def sectionPlainText(section: PluginValue): String =
    val fields = section.fields
    val title = requiredStringField(fields, "title", "SectionContent.title")
    val blocks = requiredListField(fields, "blocks", "SectionContent.blocks")
    val children = requiredListField(fields, "children", "SectionContent.children")
    val fragments =
      title ::
        blocks.map(blockPlainText) ++
        children.map(child => sectionPlainText(requiredInstance(child, "SectionContent", "SectionContent.children")))
    fragments.filter(_.nonEmpty).mkString("\n")

  private def blockPlainText(value: PluginValue): String =
    value match
      case InstAny(block) if block.typeNameOf.contains("Paragraph") =>
        val inlines = requiredListField(block.fields, "inlines", "Paragraph.inlines")
        inlinePlainText(inlines)
      case InstAny(block) if block.typeNameOf.contains("BulletList") =>
        val items = requiredListField(block.fields, "items", "BulletList.items")
        items.map(item => "- " + blockListPlainText(item, "BulletList.items")).filter(_.trim.nonEmpty).mkString("\n")
      case InstAny(block) if block.typeNameOf.contains("OrderedList") =>
        val fields = block.fields
        val start = requiredIntField(fields, "start", "OrderedList.start")
        val items = requiredListField(fields, "items", "OrderedList.items")
        items.zipWithIndex.map { case (item, idx) =>
          s"${start + idx}. ${blockListPlainText(item, "OrderedList.items")}"
        }.filter(_.trim.nonEmpty).mkString("\n")
      case InstAny(block) if block.typeNameOf.contains("Image") =>
        val fields = block.fields
        val src = requiredStringField(fields, "src", "Image.src")
        val alt = requiredStringField(fields, "alt", "Image.alt")
        if alt.isEmpty then src else alt
      case InstAny(block) if block.typeNameOf.contains("Table") =>
        val fields = block.fields
        val headers = requiredListField(fields, "headers", "Table.headers")
        val rows = requiredListField(fields, "rows", "Table.rows")
        tableValuePlainText(headers, rows)
      case InstAny(block) if block.typeNameOf.contains("Embedded") =>
        val fields = block.fields
        val lang = requiredStringField(fields, "lang", "Embedded.lang")
        val source = requiredStringField(fields, "source", "Embedded.source")
        if lang.isEmpty then source else s"$lang: $source"
      case other =>
        PluginError.raise(s"contentPlainText: expected ContentBlock, got ${PluginValue.showAny(other)}")

  private def blockListPlainText(value: PluginValue, context: String): String =
    val blocks = value match
      case Lst(items) => items
      case other =>
        PluginError.raise(s"contentPlainText: $context expected List[ContentBlock], got ${PluginValue.showAny(other)}")
    blocks.map(blockPlainText).filter(_.nonEmpty).mkString(" ")

  private def tableValuePlainText(headers: List[PluginValue], rows: List[PluginValue]): String =
    val header = headers.map(cell => inlinePlainText(requiredValueList(cell, "Table.headers"))).mkString(" | ")
    val bodyRows = rows.map(row =>
      requiredValueList(row, "Table.rows")
        .map(cell => inlinePlainText(requiredValueList(cell, "Table.rows.cell")))
        .mkString(" | ")
    )
    (header :: bodyRows).filter(_.trim.nonEmpty).mkString("\n")

  private def inlinePlainText(values: List[PluginValue]): String =
    values.map(inlinePlainText).mkString

  private def inlinePlainText(value: PluginValue): String =
    value match
      case InstAny(inline) if inline.typeNameOf.contains("Text") =>
        requiredStringField(inline.fields, "value", "Text.value")
      case InstAny(inline) if inline.typeNameOf.contains("Emphasis") =>
        inlinePlainText(requiredListField(inline.fields, "children", "Emphasis.children"))
      case InstAny(inline) if inline.typeNameOf.contains("Strong") =>
        inlinePlainText(requiredListField(inline.fields, "children", "Strong.children"))
      case InstAny(inline) if inline.typeNameOf.contains("Code") =>
        s"`${requiredStringField(inline.fields, "value", "Code.value")}`"
      case InstAny(inline) if inline.typeNameOf.contains("Link") =>
        val fields = inline.fields
        inlinePlainText(requiredListField(fields, "label", "Link.label")) +
          s" (${requiredStringField(fields, "href", "Link.href")})"
      case InstAny(inline) if inline.typeNameOf.contains("Expr") =>
        "${" + requiredStringField(inline.fields, "source", "Expr.source") + "}"
      case other =>
        PluginError.raise(s"contentPlainText: expected ContentInline, got ${PluginValue.showAny(other)}")

  private def requiredInstance(value: PluginValue, typeName: String, context: String): PluginValue =
    value match
      case InstAny(inst) if inst.typeNameOf.getOrElse("") == typeName => inst
      case other =>
        PluginError.raise(s"contentPlainText: $context expected $typeName, got ${PluginValue.showAny(other)}")

  private def requiredStringField(fields: Map[String, PluginValue], name: String, context: String): String =
    fields.get(name) match
      case Some(Str(value)) => value
      case Some(other) =>
        PluginError.raise(s"contentPlainText: $context expected String, got ${PluginValue.showAny(other)}")
      case None =>
        PluginError.raise(s"contentPlainText: missing $context")

  private def requiredIntField(fields: Map[String, PluginValue], name: String, context: String): Int =
    fields.get(name) match
      case Some(Num(value)) => value.toInt
      case Some(other) =>
        PluginError.raise(s"contentPlainText: $context expected Int, got ${PluginValue.showAny(other)}")
      case None =>
        PluginError.raise(s"contentPlainText: missing $context")

  private def requiredListField(fields: Map[String, PluginValue], name: String, context: String): List[PluginValue] =
    fields.get(name) match
      case Some(Lst(values)) => values
      case Some(other) =>
        PluginError.raise(s"contentPlainText: $context expected List, got ${PluginValue.showAny(other)}")
      case None =>
        PluginError.raise(s"contentPlainText: missing $context")

  private def requiredValueList(value: PluginValue, context: String): List[PluginValue] =
    value match
      case Lst(values) => values
      case other =>
        PluginError.raise(s"contentPlainText: $context expected List, got ${PluginValue.showAny(other)}")

  private def contentToMarkdownAny(value: Any): String =
    value match
      case v if PluginValue.isRuntimeValue(v) => contentMarkdownValue(PluginValue.wrap(v))
      case other =>
        PluginError.raise(
          s"contentToMarkdown: expected DocumentContent, SectionContent, or ContentBlock, got ${String.valueOf(other)}"
        )

  private def contentMarkdownValue(value: PluginValue): String =
    value match
      case InstAny(doc) if doc.typeNameOf.contains("DocumentContent") =>
        documentMarkdown(doc)
      case InstAny(section) if section.typeNameOf.contains("SectionContent") =>
        sectionMarkdown(section)
      case InstAny(block) if contentBlockValueTypeNames.contains(block.typeNameOf.getOrElse("")) =>
        blockMarkdown(block)
      case other =>
        PluginError.raise(
          s"contentToMarkdown: expected DocumentContent, SectionContent, or ContentBlock, got ${PluginValue.showAny(other)}"
        )

  private def documentMarkdown(doc: PluginValue): String =
    val fields = doc.fields
    val frontMatter = fields.get("manifest").flatMap(manifestMarkdown).toList
    val blocks = mdListField(fields, "blocks", "DocumentContent.blocks").map(blockMarkdown)
    val sections = mdListField(fields, "sections", "DocumentContent.sections").map { value =>
      sectionMarkdown(mdInstance(value, "SectionContent", "DocumentContent.sections"))
    }
    (frontMatter ++ blocks ++ sections).filter(_.nonEmpty).mkString("\n\n")

  private def manifestMarkdown(value: PluginValue): Option[String] =
    value match
      case InstAny(inst) if inst.typeNameOf.contains("MapV") =>
        val values = mdContentMapInstance(inst, "DocumentContent.manifest")
        if values.isEmpty then None
        else Some("---\n" + yamlMapLines(values, 0).mkString("\n") + "\n---")
      case InstAny(inst) if inst.typeNameOf.contains("NullV") =>
        None
      case other =>
        Some("---\nvalue: " + yamlScalar(other) + "\n---")

  private def sectionMarkdown(section: PluginValue): String =
    val fields = section.fields
    val level = clampHeadingLevel(mdIntField(fields, "level", "SectionContent.level"))
    val id = mdStringField(fields, "id", "SectionContent.id")
    val title = inlineTextMarkdown(List(PluginValue.instance("Text", Map("value" -> PluginValue.string(mdStringField(fields, "title", "SectionContent.title"))))))
    val attrs = mdContentValueMap(mdField(fields, "attrs", "SectionContent.attrs"), "SectionContent.attrs")
    val headingAttrs = headingAttrGroup(id, attrs)
    val heading = ("#" * level) + " " + title + headingAttrs
    val blocks = mdListField(fields, "blocks", "SectionContent.blocks").map(blockMarkdown)
    val children = mdListField(fields, "children", "SectionContent.children").map { value =>
      sectionMarkdown(mdInstance(value, "SectionContent", "SectionContent.children"))
    }
    (heading :: (blocks ++ children)).filter(_.nonEmpty).mkString("\n\n")

  private def blockMarkdown(value: PluginValue): String =
    val body = value match
      case InstAny(block) if block.typeNameOf.contains("Paragraph") =>
        inlineTextMarkdown(mdListField(block.fields, "inlines", "Paragraph.inlines"))
      case InstAny(block) if block.typeNameOf.contains("BulletList") =>
        val items = mdListField(block.fields, "items", "BulletList.items")
        items.map(item => listItemMarkdown("- ", item, "BulletList.items")).mkString("\n")
      case InstAny(block) if block.typeNameOf.contains("OrderedList") =>
        val fields = block.fields
        val start = mdIntField(fields, "start", "OrderedList.start")
        mdListField(fields, "items", "OrderedList.items").zipWithIndex.map { case (item, idx) =>
          listItemMarkdown(s"${start + idx}. ", item, "OrderedList.items")
        }.mkString("\n")
      case InstAny(block) if block.typeNameOf.contains("Image") =>
        val fields = block.fields
        val alt = inlineTextMarkdown(List(PluginValue.instance("Text", Map("value" -> PluginValue.string(mdStringField(fields, "alt", "Image.alt"))))))
        val src = mdStringField(fields, "src", "Image.src")
        val title = mdOptionString(mdField(fields, "title", "Image.title"), "Image.title")
          .map(t => " " + quoteMarkdownAttr(t))
          .getOrElse("")
        s"![${alt}](${src}${title})"
      case InstAny(block) if block.typeNameOf.contains("Table") =>
        val fields = block.fields
        tableMarkdown(
          mdListField(fields, "headers", "Table.headers"),
          mdListField(fields, "rows", "Table.rows"),
          mdStringListField(fields, "alignments", "Table.alignments")
        )
      case InstAny(block) if block.typeNameOf.contains("Embedded") =>
        embeddedMarkdown(block)
      case other =>
        PluginError.raise(s"contentToMarkdown: expected ContentBlock, got ${PluginValue.showAny(other)}")

    value match
      case InstAny(block) if block.typeNameOf.getOrElse("") != "Embedded" =>
        val attrs = mdContentValueMap(mdField(block.fields, "attrs", s"${block.typeNameOf.getOrElse("")}.attrs"), s"${block.typeNameOf.getOrElse("")}.attrs")
        metadataDirective(attrs).fold(body)(_ + "\n" + body)
      case _ =>
        body

  private def listItemMarkdown(prefix: String, item: PluginValue, context: String): String =
    val blocks = item match
      case Lst(values) => values
      case other =>
        PluginError.raise(s"contentToMarkdown: $context expected List[ContentBlock], got ${PluginValue.showAny(other)}")
    val body = blocks.map(blockMarkdown).filter(_.nonEmpty).mkString("\n\n")
    val lines = body.split("\n", -1).toList
    if body.isEmpty then prefix.trim
    else (prefix + lines.head) + lines.tail.map(line => "\n  " + line).mkString

  private def tableMarkdown(headers: List[PluginValue], rows: List[PluginValue], alignments: List[String]): String =
    val headerCells = headers.map(cell => tableCellMarkdown(cell, "Table.headers"))
    val bodyRows = rows.map(row =>
      mdValueList(row, "Table.rows")
        .map(cell => tableCellMarkdown(cell, "Table.rows.cell"))
    )
    val colCount = (headerCells.length :: alignments.length :: bodyRows.map(_.length)).max
    val paddedHeaders = padStrings(headerCells, colCount)
    val separator = (0 until colCount).toList.map(idx => tableSeparator(alignments.lift(idx).getOrElse("default")))
    val renderedRows = bodyRows.map(row => markdownTableRow(padStrings(row, colCount)))
    (markdownTableRow(paddedHeaders) :: markdownTableRow(separator) :: renderedRows).mkString("\n")

  private def tableCellMarkdown(value: PluginValue, context: String): String =
    inlineTextMarkdown(mdValueList(value, context)).replace("\n", " ").replace("|", "\\|")

  private def padStrings(values: List[String], size: Int): List[String] =
    values ++ List.fill(math.max(0, size - values.length))("")

  private def markdownTableRow(cells: List[String]): String =
    cells.mkString("| ", " | ", " |")

  private def tableSeparator(alignment: String): String =
    alignment match
      case "left"   => ":---"
      case "center" => ":---:"
      case "right"  => "---:"
      case _        => "---"

  private def embeddedMarkdown(block: PluginValue): String =
    val fields = block.fields
    val lang = mdStringField(fields, "lang", "Embedded.lang")
    val source = mdStringField(fields, "source", "Embedded.source")
    val attrs = mdContentValueMap(mdField(fields, "attrs", "Embedded.attrs"), "Embedded.attrs")
    val info = (lang :: fenceAttrTokens(attrs)).filter(_.nonEmpty).mkString(" ")
    val fence = fenceDelimiter(source)
    val body = if source.endsWith("\n") then source else source + "\n"
    if info.isEmpty then s"$fence\n$body$fence" else s"$fence$info\n$body$fence"

  private def inlineTextMarkdown(values: List[PluginValue]): String =
    values.map(inlineMarkdown).mkString

  private def inlineMarkdown(value: PluginValue): String =
    value match
      case InstAny(inline) if inline.typeNameOf.contains("Text") =>
        escapeMarkdownText(mdStringField(inline.fields, "value", "Text.value"))
      case InstAny(inline) if inline.typeNameOf.contains("Emphasis") =>
        "*" + inlineTextMarkdown(mdListField(inline.fields, "children", "Emphasis.children")) + "*"
      case InstAny(inline) if inline.typeNameOf.contains("Strong") =>
        "**" + inlineTextMarkdown(mdListField(inline.fields, "children", "Strong.children")) + "**"
      case InstAny(inline) if inline.typeNameOf.contains("Code") =>
        inlineCodeMarkdown(mdStringField(inline.fields, "value", "Code.value"))
      case InstAny(inline) if inline.typeNameOf.contains("Link") =>
        val fields = inline.fields
        val label = inlineTextMarkdown(mdListField(fields, "label", "Link.label"))
        val href = mdStringField(fields, "href", "Link.href")
        val title = mdOptionString(mdField(fields, "title", "Link.title"), "Link.title")
          .map(t => " " + quoteMarkdownAttr(t))
          .getOrElse("")
        s"[$label]($href$title)"
      case InstAny(inline) if inline.typeNameOf.contains("Expr") =>
        "${" + mdStringField(inline.fields, "source", "Expr.source") + "}"
      case other =>
        PluginError.raise(s"contentToMarkdown: expected ContentInline, got ${PluginValue.showAny(other)}")

  private def headingAttrGroup(id: String, attrs: Map[String, PluginValue]): String =
    val tokens =
      (if id.nonEmpty then List("#" + id) else Nil) ++
        classTokens(attrs) ++
        attrs.toList.filterNot { case (key, _) => key == "class" }.sortBy(_._1).flatMap { case (key, value) =>
          attrToken(key, value, prefix = "")
        }
    if tokens.isEmpty then "" else tokens.mkString(" {", " ", "}")

  private def metadataDirective(attrs: Map[String, PluginValue]): Option[String] =
    val tokens =
      attrs.toList.sortBy(_._1).flatMap { case (key, value) => attrToken(key, value, prefix = "") }
    if tokens.isEmpty then None else Some(tokens.mkString("<!-- @meta ", " ", " -->"))

  private def fenceAttrTokens(attrs: Map[String, PluginValue]): List[String] =
    val id = attrs.get("id").flatMap(value => attrToken("id", value, prefix = "@"))
    val rest =
      attrs.toList.filterNot(_._1 == "id").sortBy(_._1).flatMap { case (key, value) =>
        attrToken(key, value, prefix = "@")
      }
    id.toList ++ rest

  private def classTokens(attrs: Map[String, PluginValue]): List[String] =
    attrs.get("class").collect {
      case InstAny(inst) if inst.typeNameOf.contains("Str") =>
        mdStringField(inst.fields, "value", "ContentValue.Str.value")
          .split("\\s+")
          .toList
          .filter(_.nonEmpty)
          .map("." + _)
    }.getOrElse(Nil)

  private def attrToken(key: String, value: PluginValue, prefix: String): Option[String] =
    value match
      case InstAny(inst) if inst.typeNameOf.contains("Bool") =>
        val bool = mdBoolField(inst.fields, "value", "ContentValue.Bool.value")
        if prefix.isEmpty && bool then Some(key) else Some(s"$prefix$key=${bool.toString}")
      case InstAny(inst) if inst.typeNameOf.contains("Str") =>
        Some(s"$prefix$key=${attrScalar(mdStringField(inst.fields, "value", "ContentValue.Str.value"))}")
      case InstAny(inst) if inst.typeNameOf.contains("Num") =>
        Some(s"$prefix$key=${numberString(mdDoubleField(inst.fields, "value", "ContentValue.Num.value"))}")
      case InstAny(inst) if inst.typeNameOf.contains("NullV") =>
        Some(s"$prefix$key=null")
      case _ =>
        Some(s"$prefix$key=${attrScalar(yamlScalar(value))}")

  private def yamlMapLines(values: Map[String, PluginValue], indent: Int): List[String] =
    values.toList.sortBy(_._1).flatMap { case (key, value) =>
      yamlKeyValueLines(key, value, indent)
    }

  private def yamlKeyValueLines(key: String, value: PluginValue, indent: Int): List[String] =
    val pad = " " * indent
    value match
      case InstAny(inst) if inst.typeNameOf.contains("MapV") =>
        val values = mdContentMapInstance(inst, "ContentValue.MapV.values")
        if values.isEmpty then List(s"$pad${yamlKey(key)}: {}")
        else s"$pad${yamlKey(key)}:" :: yamlMapLines(values, indent + 2)
      case InstAny(inst) if inst.typeNameOf.contains("ListV") =>
        val values = mdListField(inst.fields, "values", "ContentValue.ListV.values")
        if values.isEmpty then List(s"$pad${yamlKey(key)}: []")
        else s"$pad${yamlKey(key)}:" :: yamlListLines(values, indent + 2)
      case _ =>
        List(s"$pad${yamlKey(key)}: ${yamlScalar(value)}")

  private def yamlListLines(values: List[PluginValue], indent: Int): List[String] =
    val pad = " " * indent
    values.flatMap {
      case InstAny(inst) if inst.typeNameOf.contains("MapV") =>
        val values = mdContentMapInstance(inst, "ContentValue.MapV.values")
        if values.isEmpty then List(s"$pad- {}")
        else s"$pad-" :: yamlMapLines(values, indent + 2)
      case InstAny(inst) if inst.typeNameOf.contains("ListV") =>
        val values = mdListField(inst.fields, "values", "ContentValue.ListV.values")
        if values.isEmpty then List(s"$pad- []")
        else s"$pad-" :: yamlListLines(values, indent + 2)
      case value =>
        List(s"$pad- ${yamlScalar(value)}")
    }

  private def yamlScalar(value: PluginValue): String =
    value match
      case InstAny(inst) if inst.typeNameOf.contains("Str") =>
        yamlString(mdStringField(inst.fields, "value", "ContentValue.Str.value"))
      case InstAny(inst) if inst.typeNameOf.contains("Bool") =>
        mdBoolField(inst.fields, "value", "ContentValue.Bool.value").toString
      case InstAny(inst) if inst.typeNameOf.contains("Num") =>
        numberString(mdDoubleField(inst.fields, "value", "ContentValue.Num.value"))
      case InstAny(inst) if inst.typeNameOf.contains("NullV") =>
        "null"
      case InstAny(inst) if inst.typeNameOf.contains("MapV") =>
        val values = mdContentMapInstance(inst, "ContentValue.MapV.values")
        if values.isEmpty then "{}" else quoteMarkdownAttr(PluginValue.showAny(value))
      case InstAny(inst) if inst.typeNameOf.contains("ListV") =>
        val values = mdListField(inst.fields, "values", "ContentValue.ListV.values")
        if values.isEmpty then "[]" else quoteMarkdownAttr(PluginValue.showAny(value))
      case Str(value) =>
        yamlString(value)
      case Bool(value) =>
        value.toString
      case Num(value) =>
        value.toString
      case Dbl(value) =>
        numberString(value)
      case PluginValue.nullV =>
        "null"
      case _ =>
        quoteMarkdownAttr(PluginValue.showAny(value))

  private def mdField(fields: Map[String, PluginValue], name: String, context: String): PluginValue =
    fields.getOrElse(name, PluginError.raise(s"contentToMarkdown: missing $context"))

  private def mdStringField(fields: Map[String, PluginValue], name: String, context: String): String =
    mdField(fields, name, context) match
      case Str(value) => value
      case other => PluginError.raise(s"contentToMarkdown: $context expected String, got ${PluginValue.showAny(other)}")

  private def mdIntField(fields: Map[String, PluginValue], name: String, context: String): Int =
    mdField(fields, name, context) match
      case Num(value) => value.toInt
      case other => PluginError.raise(s"contentToMarkdown: $context expected Int, got ${PluginValue.showAny(other)}")

  private def mdDoubleField(fields: Map[String, PluginValue], name: String, context: String): Double =
    mdField(fields, name, context) match
      case Dbl(value) => value
      case Num(value) => value.toDouble
      case other => PluginError.raise(s"contentToMarkdown: $context expected Number, got ${PluginValue.showAny(other)}")

  private def mdBoolField(fields: Map[String, PluginValue], name: String, context: String): Boolean =
    mdField(fields, name, context) match
      case Bool(value) => value
      case other => PluginError.raise(s"contentToMarkdown: $context expected Boolean, got ${PluginValue.showAny(other)}")

  private def mdListField(fields: Map[String, PluginValue], name: String, context: String): List[PluginValue] =
    mdField(fields, name, context) match
      case Lst(values) => values
      case other => PluginError.raise(s"contentToMarkdown: $context expected List, got ${PluginValue.showAny(other)}")

  private def mdValueList(value: PluginValue, context: String): List[PluginValue] =
    value match
      case Lst(values) => values
      case other => PluginError.raise(s"contentToMarkdown: $context expected List, got ${PluginValue.showAny(other)}")

  private def mdStringListField(fields: Map[String, PluginValue], name: String, context: String): List[String] =
    mdListField(fields, name, context).map {
      case Str(value) => value
      case other => PluginError.raise(s"contentToMarkdown: $context expected String list, got ${PluginValue.showAny(other)}")
    }

  private def mdContentValueMap(value: PluginValue, context: String): Map[String, PluginValue] =
    value match
      case MapVal(values) =>
        values.map {
          case (Str(key), value) => key -> value
          case (key, _) =>
            PluginError.raise(s"contentToMarkdown: $context expected String keys, got ${PluginValue.showAny(key)}")
        }
      case other =>
        PluginError.raise(s"contentToMarkdown: $context expected Map, got ${PluginValue.showAny(other)}")

  private def mdContentMapInstance(value: PluginValue, context: String): Map[String, PluginValue] =
    mdContentValueMap(mdField(value.fields, "values", context), context)

  private def mdOptionString(value: PluginValue, context: String): Option[String] =
    value match
      case Opt(None) => None
      case Opt(Some(Str(v))) => Some(v)
      case Opt(Some(other)) =>
        PluginError.raise(s"contentToMarkdown: $context expected Option[String], got Some(${PluginValue.showAny(other)})")
      case other =>
        PluginError.raise(s"contentToMarkdown: $context expected Option[String], got ${PluginValue.showAny(other)}")

  private def mdInstance(value: PluginValue, typeName: String, context: String): PluginValue =
    value match
      case InstAny(inst) if inst.typeNameOf.getOrElse("") == typeName => inst
      case other =>
        PluginError.raise(s"contentToMarkdown: $context expected $typeName, got ${PluginValue.showAny(other)}")

  private def clampHeadingLevel(level: Int): Int =
    math.max(1, math.min(6, level))

  private def numberString(value: Double): String =
    if value.isWhole && value >= Long.MinValue.toDouble && value <= Long.MaxValue.toDouble then value.toLong.toString
    else value.toString

  private def yamlKey(value: String): String =
    if isSafeBareScalar(value) then value else quoteMarkdownAttr(value)

  private def yamlString(value: String): String =
    if isSafeBareScalar(value) then value else quoteMarkdownAttr(value)

  private def attrScalar(value: String): String =
    if isSafeAttrScalar(value) then value else quoteMarkdownAttr(value)

  private def quoteMarkdownAttr(value: String): String =
    "\"" + value.flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case ch => ch.toString
    } + "\""

  private def isSafeBareScalar(value: String): Boolean =
    value.nonEmpty &&
      !Set("true", "false", "null").contains(value.toLowerCase(java.util.Locale.ROOT)) &&
      value.forall(ch => ch.isLetterOrDigit || ch == '-' || ch == '_' || ch == '.' || ch == '/' || ch == ':' || ch == '@')

  private def isSafeAttrScalar(value: String): Boolean =
    value.nonEmpty && value.forall(ch => ch.isLetterOrDigit || ch == '-' || ch == '_' || ch == '.' || ch == '/' || ch == ':' || ch == '@')

  private def escapeMarkdownText(value: String): String =
    value

  private def inlineCodeMarkdown(value: String): String =
    val ticks = maxBacktickRun(value) + 1
    val delimiter = "`" * math.max(1, ticks)
    s"$delimiter$value$delimiter"

  private def fenceDelimiter(source: String): String =
    "`" * math.max(3, maxBacktickRun(source) + 1)

  private def maxBacktickRun(value: String): Int =
    var best = 0
    var current = 0
    value.foreach { ch =>
      if ch == '`' then
        current += 1
        if current > best then best = current
      else current = 0
    }
    best

  private def vstackNode(gap: Int, children: List[PluginValue]): PluginValue =
    instanceValue("VStackNode",
      "gap" -> PluginValue.int(gap.toLong),
      "children" -> PluginValue.list(children)
    )

  private def hstackNode(gap: Int, children: List[PluginValue]): PluginValue =
    instanceValue("HStackNode",
      "gap" -> PluginValue.int(gap.toLong),
      "children" -> PluginValue.list(children)
    )

  private def dividerNode(): PluginValue =
    instanceValue("DividerNode")

  private def headingNode(level: Int, text: String): PluginValue =
    instanceValue("HeadingNode",
      "level" -> PluginValue.int(level.toLong),
      "text" -> PluginValue.string(text)
    )

  private def textNode_(text: String): PluginValue =
    instanceValue("TextNode_",
      "text" -> PluginValue.string(text)
    )

  private def rawTextNode(text: String): PluginValue =
    instanceValue("RawTextNode",
      "text" -> PluginValue.string(text)
    )

  private def tableColumn(label: String, key: String): PluginValue =
    instanceValue("TableColumn",
      "label" -> PluginValue.string(label),
      "key"   -> PluginValue.string(key)
    )

  private def tableNode(headers: List[List[ast.ContentInline]], rows: List[List[List[ast.ContentInline]]]): PluginValue =
    instanceValue("TableNode",
      "columns" -> PluginValue.list(headers.zipWithIndex.map { case (header, idx) =>
        tableColumn(inlineText(header), s"col$idx")
      }),
      "rows" -> PluginValue.list(rows.map(row =>
        PluginValue.list(row.map(cell => textNode_(inlineText(cell))))
      )),
      "sortCol" -> PluginValue.nullV
    )

  private def showWhenNode(signal: PluginValue, whenTrue: PluginValue, whenFalse: PluginValue): PluginValue =
    instanceValue("ShowWhenNode",
      "signal" -> signal,
      "whenTrue" -> whenTrue,
      "whenFalse" -> whenFalse
    )

  private def signalTextNode(signal: PluginValue): PluginValue =
    instanceValue("SignalTextNode",
      "signal" -> signal
    )

  private def fragmentNode(children: List[PluginValue]): PluginValue =
    instanceValue("FragmentNode",
      "children" -> PluginValue.list(children)
    )

  private def textFieldNode(value: PluginValue, label: String, disabled: Boolean, required: Boolean): PluginValue =
    instanceValue("TextFieldNode",
      "value" -> value,
      "label" -> PluginValue.string(label),
      "disabled" -> PluginValue.bool(disabled),
      "required" -> PluginValue.bool(required)
    )

  private def checkboxNode(checked: PluginValue, label: String, disabled: Boolean): PluginValue =
    instanceValue("CheckboxNode",
      "checked" -> checked,
      "label" -> PluginValue.string(label),
      "disabled" -> PluginValue.bool(disabled)
    )

  // `variant` is hardcoded "primary" here — the content-toolkit's markdown/YAML button
  // control has no variant option (out of scope for std-ui-button-variant); this keeps
  // the instance's field shape in sync with the 5-field SignalButtonNode/ActionButtonNode
  // (specs/std-ui-button-variant.md) without changing content-toolkit's rendered output.
  private def signalButtonNode(signal: PluginValue, value: PluginValue, label: String, disabled: Boolean): PluginValue =
    instanceValue("SignalButtonNode",
      "signal" -> signal,
      "value" -> value,
      "label" -> PluginValue.string(label),
      "disabled" -> PluginValue.bool(disabled),
      "variant" -> PluginValue.string("primary")
    )

  private def actionButtonNode(handler: PluginValue, label: String, disabled: Boolean): PluginValue =
    instanceValue("ActionButtonNode",
      "handler" -> handler,
      "label" -> PluginValue.string(label),
      "disabled" -> PluginValue.bool(disabled),
      "variant" -> PluginValue.string("primary")
    )

  private def badgeNode(content: String, variant: String): PluginValue =
    instanceValue("BadgeNode",
      "content" -> PluginValue.string(content),
      "variant" -> PluginValue.string(variant)
    )

  private def cardNode(body: List[PluginValue]): PluginValue =
    cardNode(PluginValue.nullV, body, PluginValue.nullV)

  // CardNode's `header`/`footer` are `List[TkNode]` (0-or-1 — empty = absent), so an
  // absent slot is the empty list and a present node is a singleton, not a bare `Any`.
  private def cardNode(header: PluginValue, body: List[PluginValue], footer: PluginValue): PluginValue =
    def slot(v: PluginValue): PluginValue = v match
      case PluginValue.nullV => PluginValue.list(Nil)
      case other       => PluginValue.list(List(other))
    instanceValue("CardNode",
      "header" -> slot(header),
      "body" -> PluginValue.list(body),
      "footer" -> slot(footer)
    )

  private def documentValue(doc: ast.DocumentContent): PluginValue =
    instanceValue("DocumentContent",
      "manifest"    -> contentValue(doc.manifest),
      "title"       -> optionString(doc.title),
      "description" -> optionString(doc.description),
      "attrs"       -> attrsValue(doc.attrs),
      "sections"    -> PluginValue.list(doc.sections.map(sectionValue)),
      "blocks"      -> PluginValue.list(doc.blocks.map(blockValue))
    )

  private def sectionValue(section: ast.SectionContent): PluginValue =
    instanceValue("SectionContent",
      "id"       -> PluginValue.string(section.id),
      "level"    -> PluginValue.int(section.level.toLong),
      "title"    -> PluginValue.string(section.title),
      "attrs"    -> attrsValue(section.attrs),
      "blocks"   -> PluginValue.list(section.blocks.map(blockValue)),
      "children" -> PluginValue.list(section.children.map(sectionValue))
    )

  private def blockValue(block: ast.ContentBlock): PluginValue = block match
    case ast.ContentBlock.Paragraph(inlines, attrs) =>
      instanceValue("Paragraph",
        "inlines" -> PluginValue.list(inlines.map(inlineValue)),
        "attrs"   -> attrsValue(attrs)
      )
    case ast.ContentBlock.BulletList(items, attrs) =>
      instanceValue("BulletList",
        "items" -> PluginValue.list(items.map(row => PluginValue.list(row.map(blockValue)))),
        "attrs" -> attrsValue(attrs)
      )
    case ast.ContentBlock.OrderedList(items, start, attrs) =>
      instanceValue("OrderedList",
        "items" -> PluginValue.list(items.map(row => PluginValue.list(row.map(blockValue)))),
        "start" -> PluginValue.int(start.toLong),
        "attrs" -> attrsValue(attrs)
      )
    case ast.ContentBlock.Image(src, alt, title, attrs) =>
      instanceValue("Image",
        "src"   -> PluginValue.string(src),
        "alt"   -> PluginValue.string(alt),
        "title" -> optionString(title),
        "attrs" -> attrsValue(attrs)
      )
    case ast.ContentBlock.Table(headers, rows, alignments, attrs) =>
      instanceValue("Table",
        "headers"    -> PluginValue.list(headers.map(cell => PluginValue.list(cell.map(inlineValue)))),
        "rows"       -> PluginValue.list(rows.map(row => PluginValue.list(row.map(cell => PluginValue.list(cell.map(inlineValue)))))),
        "alignments" -> PluginValue.list(alignments.map(value => PluginValue.string(value))),
        "attrs"      -> attrsValue(attrs)
      )
    case ast.ContentBlock.Embedded(lang, source, kind, data, attrs) =>
      instanceValue("Embedded",
        "lang"   -> PluginValue.string(lang),
        "source" -> PluginValue.string(source),
        "kind"   -> embeddedKindValue(kind),
        "data"   -> PluginValue.option(data.map(contentValue)),
        "attrs"  -> attrsValue(attrs)
      )

  private def embeddedKindValue(kind: ast.EmbeddedKind): PluginValue =
    instanceValue(kind.toString)

  private def inlineValue(inline: ast.ContentInline): PluginValue = inline match
    case ast.ContentInline.Text(value) =>
      instanceValue("Text", "value" -> PluginValue.string(value))
    case ast.ContentInline.Emphasis(children) =>
      instanceValue("Emphasis", "children" -> PluginValue.list(children.map(inlineValue)))
    case ast.ContentInline.Strong(children) =>
      instanceValue("Strong", "children" -> PluginValue.list(children.map(inlineValue)))
    case ast.ContentInline.Code(value) =>
      instanceValue("Code", "value" -> PluginValue.string(value))
    case ast.ContentInline.Link(label, href, title) =>
      instanceValue("Link",
        "label" -> PluginValue.list(label.map(inlineValue)),
        "href"  -> PluginValue.string(href),
        "title" -> optionString(title)
      )
    case ast.ContentInline.Expr(source) =>
      instanceValue("Expr", "source" -> PluginValue.string(source))

  private def contentValue(value: ast.ContentValue): PluginValue = value match
    case ast.ContentValue.Str(v)    => instanceValue("Str", "value" -> PluginValue.string(v))
    case ast.ContentValue.Bool(v)   => instanceValue("Bool", "value" -> PluginValue.bool(v))
    case ast.ContentValue.Num(v)    => instanceValue("Num", "value" -> PluginValue.double(v))
    case ast.ContentValue.ListV(vs) => instanceValue("ListV", "values" -> PluginValue.list(vs.map(contentValue)))
    case ast.ContentValue.MapV(vs)  => instanceValue("MapV", "values" -> attrsValue(vs))
    case ast.ContentValue.NullV     => instanceValue("NullV")

  private def attrsValue(attrs: Map[String, ast.ContentValue]): PluginValue =
    PluginValue.mapOf(attrs.map { case (k, v) => PluginValue.string(k) -> contentValue(v) })

  private def optionString(value: Option[String]): PluginValue =
    PluginValue.option(value.map(PluginValue.string))
