package scalascript.compiler.plugin.content

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

import scalascript.ast
import scalascript.backend.spi.{IntrinsicImpl, NativeContextFeatureKeys}
import scalascript.frontend.ReactiveSignal
import scalascript.interpreter.{InterpretError, Value}
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginContext, PluginNative}

object ContentIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(
    QualifiedName("contentDocument") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case Nil =>
          ctx.featureGet(NativeContextFeatureKeys.ContentDocument) match
            case Some(doc: ast.DocumentContent) => documentValue(doc)
            case _ => throw InterpretError("contentDocument() is only available while running a parsed .ssc module")
        case _ => throw InterpretError("contentDocument()")
    },
    QualifiedName("contentCurrentSection") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case Nil =>
          ctx.featureLocalGet(NativeContextFeatureKeys.ContentCurrentSection) match
            case Some(section: ast.SectionContent) => sectionValue(section)
            case _ =>
              throw InterpretError(
                "contentCurrentSection() is only available while running a parsed .ssc code block inside a Markdown section"
              )
        case _ => throw InterpretError("contentCurrentSection()")
    },
    QualifiedName("contentSection") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (id: String) :: Nil =>
          Value.optionV(contentSectionById(currentDocument(ctx, "contentSection(id)"), id).map(sectionValue))
        case _ => throw InterpretError("contentSection(id)")
    },
    QualifiedName("contentBlock") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (id: String) :: Nil =>
          Value.optionV(contentBlockById(currentDocument(ctx, "contentBlock(id)"), id).map(blockValue))
        case _ => throw InterpretError("contentBlock(id)")
    },
    QualifiedName("contentData") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (id: String) :: Nil =>
          Value.optionV(contentDataById(currentDocument(ctx, "contentData(id)"), id).map(contentValue))
        case _ => throw InterpretError("contentData(id)")
    },
    QualifiedName("contentMetadata") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (path: String) :: Nil =>
          Value.optionV(contentMetadataPath(currentDocument(ctx, "contentMetadata(path)"), path).map(contentValue))
        case _ => throw InterpretError("contentMetadata(path)")
    },
    QualifiedName("contentBind") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case (value: Value) :: (bindings: Value) :: Nil => contentBindValue(value, contentBindRoot(bindings))
        case _ => throw InterpretError("contentBind(value, bindings)")
    },
    QualifiedName("contentPlainText") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case value :: Nil => contentPlainTextAny(value)
        case _            => throw InterpretError("contentPlainText(value)")
    },
    QualifiedName("contentToMarkdown") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case value :: Nil => contentToMarkdownAny(value)
        case _            => throw InterpretError("contentToMarkdown(value)")
    },
    QualifiedName("contentModules") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case Nil =>
          Value.MapV(uniqueImportedDocuments(ctx, "contentModules()").map {
            case (namespace, doc) => Value.StringV(namespace) -> documentValue(doc)
          })
        case _ => throw InterpretError("contentModules()")
    },
    QualifiedName("contentModule") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (namespace: String) :: Nil =>
          Value.optionV(importedDocument(ctx, namespace, "contentModule(namespace)").map(documentValue))
        case _ => throw InterpretError("contentModule(namespace)")
    },
    QualifiedName("contentModuleSection") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (namespace: String) :: (id: String) :: Nil =>
          Value.optionV(importedDocument(ctx, namespace, "contentModuleSection(namespace, id)").flatMap(doc =>
            contentSectionById(doc, id).map(sectionValue)))
        case _ => throw InterpretError("contentModuleSection(namespace, id)")
    },
    QualifiedName("contentModuleBlock") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (namespace: String) :: (id: String) :: Nil =>
          Value.optionV(importedDocument(ctx, namespace, "contentModuleBlock(namespace, id)").flatMap(doc =>
            contentBlockById(doc, id).map(blockValue)))
        case _ => throw InterpretError("contentModuleBlock(namespace, id)")
    },
    QualifiedName("contentModuleData") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (namespace: String) :: (id: String) :: Nil =>
          Value.optionV(importedDocument(ctx, namespace, "contentModuleData(namespace, id)").flatMap(doc =>
            contentDataById(doc, id).map(contentValue)))
        case _ => throw InterpretError("contentModuleData(namespace, id)")
    },
    QualifiedName("contentModuleMetadata") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case (namespace: String) :: (path: String) :: Nil =>
          Value.optionV(importedDocument(ctx, namespace, "contentModuleMetadata(namespace, path)").flatMap(doc =>
            contentMetadataPath(doc, path).map(contentValue)))
        case _ => throw InterpretError("contentModuleMetadata(namespace, path)")
    },
    QualifiedName("contentToolkitNode") -> PluginNative.evalLegacy { (ctx, args) =>
      val options = args match
        case Nil      => ToolkitOptions()
        case (one: Value) :: Nil => toolkitOptions(one)
        case _        => throw InterpretError("contentToolkitNode([options])")
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
    components: Map[String, Value] = Map.empty,
    bindings: Map[String, ast.ContentValue] = Map.empty,
    actions: Map[String, Value] = Map.empty,
    rowBindings: Map[String, Value] = Map.empty,
    computed: Map[String, Value] = Map.empty,
    slots: Map[String, Value] = Map.empty
  )

  // buildColumn (Scope B.2): builds a typed DataColumn by invoking a registered
  // column-builder native (fieldColumn/moneyColumn/…) — a closure capturing the
  // PluginContext, so toolkitControl can construct columns from inline YAML
  // `columns:` specs without the content plugin depending on the column model.
  private case class ToolkitUiEnv(signals: Map[String, Value], actions: Map[String, Value] = Map.empty,
                                  rowBindings: Map[String, Value] = Map.empty,
                                  buildColumn: Option[(String, List[Any]) => Value] = None)
  private case class ToolkitLink(kind: String, query: Map[String, String], label: String)

  private def currentDocument(ctx: PluginContext, fn: String): ast.DocumentContent =
    ctx.featureGet(NativeContextFeatureKeys.ContentDocument) match
      case Some(doc: ast.DocumentContent) => doc
      case _ => throw InterpretError(s"$fn is only available while running a parsed .ssc module")

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
        throw InterpretError(s"$fn: empty imported content namespace '$namespace'")
      case (namespace, _) =>
        throw InterpretError(s"$fn: duplicate imported content namespace '$namespace'")
    }

  private def importedDocument(ctx: PluginContext, namespace: String, fn: String): Option[ast.DocumentContent] =
    importedDocumentTable(ctx).get(namespace) match
      case None | Some(Nil) => None
      case Some(doc :: Nil) => Some(doc)
      case Some(_)          => throw InterpretError(s"$fn: duplicate imported content namespace '$namespace'")

  private def toolkitSelectorArgs(fn: String, args: List[Any]): (String, ToolkitOptions) =
    args match
      case (id: String) :: Nil =>
        (id, ToolkitOptions())
      case (id: String) :: (options: Value) :: Nil =>
        (id, toolkitOptions(options))
      case _ =>
        throw InterpretError(s"$fn(id, [options])")

  private def toolkitOptions(value: Value): ToolkitOptions =
    val fields = value match
      case inst: Value.InstanceV if inst.typeName == "ContentToolkitOptions" => inst.effectiveFields
      case other => throw InterpretError(s"contentToolkitNode: expected ContentToolkitOptions, got ${Value.show(other)}")
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
  private def slotRegistry(value: Option[Value]): Map[String, Value] =
    value match
      case None | Some(Value.NullV) => Map.empty
      case Some(Value.MapV(m)) =>
        m.collect { case (Value.StringV(k), v) => k -> v }
      case Some(other) =>
        throw InterpretError(s"ContentToolkitOptions.slots: expected Map[String, TkNode], got ${Value.show(other)}")

  // actions: a Map[String, EventHandler] — id -> handler for toolkit:button?action=id.
  private def actionRegistry(value: Option[Value]): Map[String, Value] =
    value match
      case None | Some(Value.NullV) => Map.empty
      case Some(Value.MapV(m)) =>
        m.collect { case (Value.StringV(k), v) => k -> v }
      case Some(other) =>
        throw InterpretError(s"ContentToolkitOptions.actions: expected Map[String, EventHandler], got ${Value.show(other)}")

  // computed: a Map[String, Signal] (Scope B.5) — id -> a code-built derived signal
  // merged into the toolkit signal environment so YAML controls can reference it.
  private def computedRegistry(value: Option[Value]): Map[String, Value] =
    value match
      case None | Some(Value.NullV) => Map.empty
      case Some(Value.MapV(m)) =>
        m.collect { case (Value.StringV(k), v) => k -> v }
      case Some(other) =>
        throw InterpretError(s"ContentToolkitOptions.computed: expected Map[String, Signal], got ${Value.show(other)}")

  // rowBindings: a Map[String, ContentRowBinding] — id -> live-row source for
  // toolkit:table?rows=id.  Each value carries `rows` (a Signal), `columns`
  // (field-column descriptors), and `actions` (per-row actions).
  private def rowBindingRegistry(value: Option[Value]): Map[String, Value] =
    value match
      case None | Some(Value.NullV) => Map.empty
      case Some(Value.MapV(m)) =>
        m.collect { case (Value.StringV(k), v) => k -> v }
      case Some(other) =>
        throw InterpretError(s"ContentToolkitOptions.rowBindings: expected Map[String, ContentRowBinding], got ${Value.show(other)}")

  private def componentRegistry(value: Option[Value]): Map[String, Value] =
    value match
      case None => Map.empty
      case Some(Value.ListV(entries)) =>
        entries.foldLeft(Map.empty[String, Value]) { (acc, entry) =>
          val fields = entry match
            case inst: Value.InstanceV if inst.typeName == "ContentToolkitComponent" => inst.effectiveFields
            case other =>
              throw InterpretError(s"ContentToolkitOptions.components: expected ContentToolkitComponent, got ${Value.show(other)}")
          val name = fields.get("name") match
            case Some(Value.StringV(v)) => v
            case Some(other) =>
              throw InterpretError(s"ContentToolkitComponent.name: expected String, got ${Value.show(other)}")
            case None =>
              throw InterpretError("ContentToolkitComponent.name is required")
          val render = fields.getOrElse("render", throw InterpretError(s"ContentToolkitComponent('$name').render is required"))
          acc.updated(name, render)
        }
      case Some(other) =>
        throw InterpretError(s"ContentToolkitOptions.components: expected List, got ${Value.show(other)}")

  private def boolField(fields: Map[String, Value], name: String, default: Boolean): Boolean =
    fields.get(name) match
      case Some(Value.BoolV(v)) => v
      case None                 => default
      case Some(other)          => throw InterpretError(s"ContentToolkitOptions.$name: expected Boolean, got ${Value.show(other)}")

  private def intField(fields: Map[String, Value], name: String, default: Int): Int =
    fields.get(name) match
      case Some(Value.IntV(v)) => v.toInt
      case None                => default
      case Some(other)         => throw InterpretError(s"ContentToolkitOptions.$name: expected Int, got ${Value.show(other)}")

  private def contentValueMapField(value: Option[Value]): Map[String, ast.ContentValue] =
    value match
      case None | Some(Value.NullV) => Map.empty
      case Some(inst: Value.InstanceV) if inst.typeName == "MapV" =>
        astContentValueMapEntries(inst, "ContentToolkitOptions.bindings")
      case Some(other) =>
        throw InterpretError(s"ContentToolkitOptions.bindings: expected ContentValue.MapV, got ${Value.show(other)}")

  private def astContentValueMapEntries(value: Value, context: String): Map[String, ast.ContentValue] =
    value match
      case inst: Value.InstanceV if inst.typeName == "MapV" =>
        bindField(inst.effectiveFields, "values", s"$context.values") match
          case Value.MapV(entries) =>
            entries.map {
              case (Value.StringV(key), value) => key -> astContentValue(value, s"$context.$key")
              case (key, _) => throw InterpretError(s"$context expected string keys, got ${Value.show(key)}")
            }
          case other =>
            throw InterpretError(s"$context.values expected Map, got ${Value.show(other)}")
      case other =>
        throw InterpretError(s"$context expected ContentValue.MapV, got ${Value.show(other)}")

  private def astContentValue(value: Value, context: String): ast.ContentValue =
    value match
      case inst: Value.InstanceV if inst.typeName == "Str" =>
        ast.ContentValue.Str(bindString(bindField(inst.effectiveFields, "value", s"$context.value"), s"$context.value"))
      case inst: Value.InstanceV if inst.typeName == "Bool" =>
        ast.ContentValue.Bool(bindBool(bindField(inst.effectiveFields, "value", s"$context.value"), s"$context.value"))
      case inst: Value.InstanceV if inst.typeName == "Num" =>
        ast.ContentValue.Num(bindDouble(bindField(inst.effectiveFields, "value", s"$context.value"), s"$context.value"))
      case inst: Value.InstanceV if inst.typeName == "NullV" =>
        ast.ContentValue.NullV
      case inst: Value.InstanceV if inst.typeName == "ListV" =>
        ast.ContentValue.ListV(bindList(bindField(inst.effectiveFields, "values", s"$context.values"), s"$context.values").map(astContentValue(_, context)))
      case inst: Value.InstanceV if inst.typeName == "MapV" =>
        ast.ContentValue.MapV(astContentValueMapEntries(inst, context))
      case other =>
        throw InterpretError(s"$context expected ContentValue, got ${Value.show(other)}")

  private def instanceValue(typeName: String, fields: (String, Value)*): Value.InstanceV =
    val inst = Value.InstanceV(typeName, fields.toMap)
    inst.fieldNames = fields.map(_._1).toArray
    inst.fieldsArr = fields.map(_._2).toArray
    inst

  // Build the toolkit env for a document/block/section: markdown signal defaults,
  // with code-registered computed signals (Scope B.5) merged in underneath (a
  // locally-declared signal of the same name wins), plus the action / rowBinding
  // registries.
  private def toolkitEnvFor(ctx: PluginContext, base: ToolkitUiEnv, options: ToolkitOptions): ToolkitUiEnv =
    // Closure to build a typed DataColumn from a registered column-builder native
    // (Scope B.2) — captures ctx so toolkitControl can construct inline columns.
    val buildColumn: (String, List[Any]) => Value = (name, args) =>
      ctx.resolveGlobal(name) match
        case Some(fn) => ctx.invokeCallback(fn, args).asInstanceOf[Value]
        case None     => throw InterpretError(
          s"contentToolkitNode: table column builder '$name' is not available — import it from std/ui/data (fcol/mcol/scol/dcol/lcol)")
    base.copy(signals = options.computed ++ base.signals,
              actions = options.actions, rowBindings = options.rowBindings,
              buildColumn = Some(buildColumn))

  private def toolkitDocumentNode(ctx: PluginContext, doc: ast.DocumentContent, options: ToolkitOptions): Value =
    val env = toolkitEnvFor(ctx, toolkitMarkdownEnv(doc), options)
    val children =
      doc.blocks.map(toolkitBlockNode(ctx, doc, _, options, env)) ++
        doc.sections.map(toolkitSectionNode(ctx, doc, _, options, env, topLevel = true))
    val body = vstackNode(options.sectionGap, children)
    if options.wrapDocumentInCard then cardNode(List(body)) else body

  private def toolkitBlockById(ctx: PluginContext, doc: ast.DocumentContent, id: String, options: ToolkitOptions): Value =
    blocksDeep(doc).filter(block => blockId(block).contains(id)) match
      case block :: Nil =>
        toolkitBlockNode(ctx, doc, block, options, toolkitEnvFor(ctx, toolkitMarkdownEnv(block), options))
      case Nil =>
        throw InterpretError(s"contentToolkitBlock: no block with id '$id'")
      case _ =>
        throw InterpretError(s"contentToolkitBlock: duplicate block id '$id'")

  private def toolkitSectionById(ctx: PluginContext, doc: ast.DocumentContent, id: String, options: ToolkitOptions): Value =
    sectionsDeep(doc).filter(_.id == id) match
      case section :: Nil =>
        toolkitSectionNode(ctx, doc, section, options, toolkitEnvFor(ctx, toolkitMarkdownEnv(section), options), topLevel = true)
      case Nil =>
        throw InterpretError(s"contentToolkitSection: no section with id '$id'")
      case _ =>
        throw InterpretError(s"contentToolkitSection: duplicate section id '$id'")

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
      case _             => throw InterpretError(s"contentSection: duplicate section id '$id'")

  private def contentBlockById(doc: ast.DocumentContent, id: String): Option[ast.ContentBlock] =
    blocksDeep(doc).filter(block => blockId(block).contains(id)) match
      case Nil          => None
      case block :: Nil => Some(block)
      case _            => throw InterpretError(s"contentBlock: duplicate block id '$id'")

  private def componentRenderer(
      attrs: Map[String, ast.ContentValue],
      options: ToolkitOptions
  ): Option[(String, Value)] =
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
      case _            => throw InterpretError(s"contentData: duplicate structured data id '$id'")

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
      throw InterpretError("contentMetadata: path must be non-empty dot-separated segments")
    trimmed.split("\\.").toList

  private def resolvedComponentData(
      doc: ast.DocumentContent,
      attrs: Map[String, ast.ContentValue],
      fallback: Option[ast.ContentValue]
  ): Option[ast.ContentValue] =
    contentStringAttr(attrs, "data") match
      case Some(id) => contentDataById(doc, id)
      case None     => fallback

  private def renderComponent(ctx: PluginContext, name: String, render: Value, context: Value): Value =
    ctx.invokeCallback(render, List(context)) match
      case value: Value => value
      case other =>
        throw InterpretError(s"contentToolkitNode: component '$name' returned non-value $other")

  private def sectionComponentContext(name: String, doc: ast.DocumentContent, section: ast.SectionContent): Value =
    instanceValue("ContentComponentContext",
      "name"    -> Value.StringV(name),
      "kind"    -> Value.StringV("section"),
      "id"      -> Value.StringV(section.id),
      "title"   -> optionString(Some(section.title)),
      "attrs"   -> attrsValue(section.attrs),
      "section" -> Value.optionV(Some(sectionValue(section))),
      "block"   -> Value.optionV(None),
      "data"    -> Value.optionV(resolvedComponentData(doc, section.attrs, fallback = None).map(contentValue))
    )

  private def blockComponentContext(name: String, doc: ast.DocumentContent, block: ast.ContentBlock): Value =
    val attrs = blockAttrs(block)
    instanceValue("ContentComponentContext",
      "name"    -> Value.StringV(name),
      "kind"    -> Value.StringV("block"),
      "id"      -> Value.StringV(blockId(block).getOrElse("")),
      "title"   -> optionString(None),
      "attrs"   -> attrsValue(attrs),
      "section" -> Value.optionV(None),
      "block"   -> Value.optionV(Some(blockValue(block))),
      "data"    -> Value.optionV(resolvedComponentData(doc, attrs, blockData(block)).map(contentValue))
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
  ): Value =
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
  ): Value =
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
            throw InterpretError("contentToolkitNode: @ui=toolkit requires a structured YAML/JSON/TOML block")
      case ast.ContentBlock.Embedded(lang, source, _, _, _) =>
        if options.includeCode then
          val label = if lang.isEmpty then "" else s"$lang\n"
          rawTextNode(label + source)
        else fragmentNode(Nil)

  private def isToolkitUiBlock(lang: String, attrs: Map[String, ast.ContentValue]): Boolean =
    attrs.get("ui").contains(ast.ContentValue.Str("toolkit")) &&
      Set("yaml", "yml", "json", "toml").contains(lang)

  private def toolkitUiNode(value: ast.ContentValue, options: ToolkitOptions, baseEnv: ToolkitUiEnv): Value =
    val root = contentMap(value, "@ui=toolkit")
    val signals = root.get("signals").map(toolkitSignals).getOrElse(Map.empty)
    val env = ToolkitUiEnv(baseEnv.signals ++ signals, baseEnv.actions, baseEnv.rowBindings, baseEnv.buildColumn)
    root.get("controls")
      .orElse(root.get("control"))
      .map(toolkitControl(_, env, options))
      .getOrElse(throw InterpretError("contentToolkitNode: @ui=toolkit block requires controls"))

  private def toolkitMarkdownEnv(doc: ast.DocumentContent): ToolkitUiEnv =
    ToolkitUiEnv(markdownToolkitSignalDefaults(doc).foldLeft(Map.empty[String, Value]) {
      case (acc, (name, initial)) =>
        if acc.contains(name) then acc else acc.updated(name, reactiveSignal(name, initial))
    })

  private def toolkitMarkdownEnv(section: ast.SectionContent): ToolkitUiEnv =
    ToolkitUiEnv(markdownToolkitSignalDefaults(section).foldLeft(Map.empty[String, Value]) {
      case (acc, (name, initial)) =>
        if acc.contains(name) then acc else acc.updated(name, reactiveSignal(name, initial))
    })

  private def toolkitMarkdownEnv(block: ast.ContentBlock): ToolkitUiEnv =
    ToolkitUiEnv(markdownToolkitSignalDefaults(block).foldLeft(Map.empty[String, Value]) {
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

  private def toolkitListItemNode(item: List[ast.ContentBlock], env: ToolkitUiEnv): Option[Value] =
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
    if kind.isEmpty then throw InterpretError("contentToolkitNode: toolkit link requires a control kind")
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

  private def toolkitLinkNode(link: ToolkitLink, env: ToolkitUiEnv): Value =
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
            val handler = env.actions.getOrElse(actionId, throw InterpretError(
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
        val binding = env.rowBindings.getOrElse(regionId, throw InterpretError(
          s"contentToolkitNode: toolkit:table rows '$regionId' is not registered " +
          s"(available: ${if env.rowBindings.isEmpty then "<none>" else env.rowBindings.keys.toList.sorted.mkString(", ")})"))
        rowBindingDataTable(regionId, binding)
      case other =>
        throw InterpretError(s"contentToolkitNode: unsupported toolkit link control '$other'")

  // rowBindingDataTable — turn a registered ContentRowBinding into a DataTableNode.
  // Reuses the existing DataTableNode lowering (web <table> / Swing JTable); the
  // signal + field-columns + per-row actions pass through opaquely as Values.
  private def rowBindingDataTable(regionId: String, binding: Value, overrideColumns: Option[Value] = None): Value =
    val fields = binding match
      case inst: Value.InstanceV if inst.typeName == "ContentRowBinding" => inst.effectiveFields
      case other => throw InterpretError(
        s"contentToolkitNode: toolkit:table rows '$regionId' expected a ContentRowBinding, got ${Value.show(other)}")
    val rows = fields.getOrElse("rows", throw InterpretError(
      s"contentToolkitNode: ContentRowBinding('$regionId').rows is required"))
    // Inline YAML `columns:` (Scope B.2) override the registered columns for this
    // table; otherwise the ContentRowBinding's registered columns are used.
    val columns = overrideColumns.getOrElse(fields.get("columns") match
      case Some(cols: Value.ListV) => cols
      case Some(other) => throw InterpretError(
        s"contentToolkitNode: ContentRowBinding('$regionId').columns expected a List, got ${Value.show(other)}")
      case None => Value.ListV(Nil))
    val actions = fields.get("actions") match
      case Some(acts: Value.ListV) => acts
      case _                       => Value.ListV(Nil)
    instanceValue("DataTableNode",
      "signal"  -> rows,
      "columns" -> columns,
      "actions" -> actions)

  // Build typed DataColumns from an inline YAML `columns:` list (Scope B.2) by
  // invoking the registered column-builder natives via env.buildColumn, so the
  // author can declare columns at the call site instead of only in code.
  private def toolkitInlineColumns(columnsValue: ast.ContentValue, env: ToolkitUiEnv): Value =
    val build = env.buildColumn.getOrElse(throw InterpretError(
      "contentToolkitNode: inline table columns require a render context"))
    val specs = columnsValue match
      case ast.ContentValue.ListV(items) => items
      case other => throw InterpretError(
        s"contentToolkitNode: table columns expected a list, got ${contentValueKind(other)}")
    Value.ListV(specs.map { spec =>
      val cf    = contentMap(spec, "table column")
      val label = firstContentString(cf, "label", "title").getOrElse(
        throw InterpretError("contentToolkitNode: table column requires a label"))
      val path  = firstContentString(cf, "path", "fieldPath").getOrElse(
        throw InterpretError("contentToolkitNode: table column requires a path"))
      val align = contentStringField(cf, "align", "")
      normalizeControlKind(contentStringField(cf, "kind", "text")) match
        case "text" | "" => build("fieldColumn",  List(label, path, align))
        case "date"      => build("dateColumn",   List(label, path, align, contentStringField(cf, "format", "")))
        case "money"     => build("moneyColumn",  List(label, path, align, contentStringField(cf, "currency", "USD"), contentStringField(cf, "locale", "")))
        case "status"    => build("statusColumn", List(label, path, align, toolkitColorMap(cf)))
        case "link"      => build("linkColumn",   List(label, path, align, contentStringField(cf, "url", "")))
        case other       => throw InterpretError(s"contentToolkitNode: unknown table column kind '$other'")
    })

  // colors: {open: green, blocked: red} → Value.MapV for statusColumn; null if absent.
  private def toolkitColorMap(cf: Map[String, ast.ContentValue]): Any =
    cf.get("colors") match
      case Some(ast.ContentValue.MapV(entries)) =>
        Value.MapV(entries.collect { case (k, ast.ContentValue.Str(v)) => Value.StringV(k) -> Value.StringV(v) })
      case _ => Value.NullV

  private def toolkitLinkLabel(link: ToolkitLink): String =
    link.query.getOrElse("label", link.label)

  private def requiredToolkitQuery(link: ToolkitLink, name: String): String =
    link.query.get(name).filter(_.nonEmpty)
      .getOrElse(throw InterpretError(s"contentToolkitNode: toolkit:${link.kind} requires $name"))

  private def toolkitLinkBool(link: ToolkitLink, name: String, default: Boolean): Boolean =
    toolkitLinkBoolValue(link, name).getOrElse(default)

  private def toolkitLinkBoolValue(link: ToolkitLink, name: String): Option[Boolean] =
    link.query.get(name).map {
      case "true"  => true
      case "false" => false
      case other =>
        throw InterpretError(s"contentToolkitNode: toolkit:${link.kind} $name expected true or false, got '$other'")
    }

  private def toolkitLinkLiteral(value: String): Value =
    value match
      case "true"  => Value.boolV(true)
      case "false" => Value.boolV(false)
      case other   => Value.StringV(other)

  private def toolkitSignals(value: ast.ContentValue): Map[String, Value] =
    contentMap(value, "signals").map { case (name, initial) =>
      name -> reactiveSignal(name, initial)
    }

  private def reactiveSignal(name: String, value: ast.ContentValue): Value =
    value match
      case ast.ContentValue.Str(v)  => Value.Foreign("ReactiveSignal", new ReactiveSignal[String](name, v))
      case ast.ContentValue.Bool(v) => Value.Foreign("ReactiveSignal", new ReactiveSignal[Boolean](name, v))
      case ast.ContentValue.Num(v) if isIntLike(v) =>
        Value.Foreign("ReactiveSignal", new ReactiveSignal[Int](name, v.toInt))
      case ast.ContentValue.Num(v) =>
        Value.Foreign("ReactiveSignal", new ReactiveSignal[Double](name, v))
      case ast.ContentValue.NullV =>
        Value.Foreign("ReactiveSignal", new ReactiveSignal[String](name, ""))
      case other =>
        throw InterpretError(s"contentToolkitNode: signal '$name' default must be scalar, got ${contentValueKind(other)}")

  private def toolkitControl(value: ast.ContentValue, env: ToolkitUiEnv, options: ToolkitOptions): Value =
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
              throw InterpretError("contentToolkitNode: table control requires source or rows"))
            val binding = env.rowBindings.getOrElse(regionId, throw InterpretError(
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
              optionalControlField(fields, env, options, "header").getOrElse(Value.NullV),
              toolkitChildren(fields, env, options),
              optionalControlField(fields, env, options, "footer").getOrElse(Value.NullV)
            )
          case "slot" =>
            // {type: slot, id: <id>} injects a code-built TkNode registered under
            // <id> in options.slots (Scope B.6) — the escape hatch.  Returned verbatim.
            val slotId = requiredContentString(fields, "id", "slot")
            options.slots.getOrElse(slotId, throw InterpretError(
              s"contentToolkitNode: slot '$slotId' is not registered " +
              s"(available: ${if options.slots.isEmpty then "<none>" else options.slots.keys.toList.sorted.mkString(", ")})"))
          case otherKind =>
            throw InterpretError(s"contentToolkitNode: unsupported control type '$otherKind'")

  private def toolkitButton(fields: Map[String, ast.ContentValue], env: ToolkitUiEnv): Value =
    // {type: button, action: <id>} binds the button to a registered EventHandler
    // (a typed server write) — the YAML control-tree form of the
    // `toolkit:button?action=<id>` Markdown link (Scope B.1).  Without `action`,
    // the existing `signal`-bound button path is used unchanged.
    fields.get("action") match
      case Some(ast.ContentValue.Str(actionId)) =>
        val handler = env.actions.getOrElse(actionId, throw InterpretError(
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
            throw InterpretError(s"contentToolkitNode: button.enabledWhen expected String, got ${contentValueKind(other)}")
          case None =>
            actionButtonNode(handler, label, disabled)
      case Some(other) =>
        throw InterpretError(s"contentToolkitNode: button.action expected String, got ${contentValueKind(other)}")
      case None =>
        val signal = signalField(fields, env, "button", "signal")
        val value = fields.get("value").map(contentLiteral).getOrElse(Value.boolV(true))
        val label = contentStringField(fields, "label", "")
        fields.get("enabledWhen") match
          case Some(ast.ContentValue.Str(name)) =>
            showWhenNode(
              signalRef(name, env, "button.enabledWhen"),
              signalButtonNode(signal, value, label, disabled = false),
              signalButtonNode(signal, value, label, disabled = true)
            )
          case Some(other) =>
            throw InterpretError(s"contentToolkitNode: button.enabledWhen expected String, got ${contentValueKind(other)}")
          case None =>
            signalButtonNode(signal, value, label, contentBoolField(fields, "disabled", default = false))

  private def toolkitChildren(
    fields: Map[String, ast.ContentValue],
    env: ToolkitUiEnv,
    options: ToolkitOptions
  ): List[Value] =
    fields.get("children").orElse(fields.get("body")) match
      case Some(ast.ContentValue.ListV(values)) => values.map(toolkitControl(_, env, options))
      case Some(other) =>
        throw InterpretError(s"contentToolkitNode: children expected List, got ${contentValueKind(other)}")
      case None => Nil

  private def requiredControlField(
    fields: Map[String, ast.ContentValue],
    env: ToolkitUiEnv,
    options: ToolkitOptions,
    context: String,
    names: String*
  ): Value =
    optionalControlField(fields, env, options, names*)
      .getOrElse(throw InterpretError(s"contentToolkitNode: $context requires ${names.mkString(" or ")}"))

  private def optionalControlField(
    fields: Map[String, ast.ContentValue],
    env: ToolkitUiEnv,
    options: ToolkitOptions,
    names: String*
  ): Option[Value] =
    names.iterator.flatMap(name => fields.get(name)).take(1).toList match
      case value :: Nil => Some(toolkitControl(value, env, options))
      case _            => None

  private def signalField(
    fields: Map[String, ast.ContentValue],
    env: ToolkitUiEnv,
    context: String,
    names: String*
  ): Value =
    val name = names.iterator.flatMap(name => contentStringOption(fields, name)).take(1).toList match
      case value :: Nil => value
      case _ => throw InterpretError(s"contentToolkitNode: $context requires ${names.mkString(" or ")}")
    signalRef(name, env, context)

  private def signalRef(name: String, env: ToolkitUiEnv, context: String): Value =
    env.signals.getOrElse(
      name,
      throw InterpretError(s"contentToolkitNode: $context references unknown signal '$name'")
    )

  private def contentMap(value: ast.ContentValue, context: String): Map[String, ast.ContentValue] =
    value match
      case ast.ContentValue.MapV(values) => values
      case other =>
        throw InterpretError(s"contentToolkitNode: $context expected object, got ${contentValueKind(other)}")

  private def requiredContentString(fields: Map[String, ast.ContentValue], name: String, context: String): String =
    contentStringOption(fields, name)
      .getOrElse(throw InterpretError(s"contentToolkitNode: $context requires $name"))

  private def contentStringField(fields: Map[String, ast.ContentValue], name: String, default: String): String =
    contentStringOption(fields, name).getOrElse(default)

  private def firstContentString(fields: Map[String, ast.ContentValue], names: String*): Option[String] =
    names.iterator.flatMap(name => contentStringOption(fields, name)).take(1).toList.headOption

  private def contentStringOption(fields: Map[String, ast.ContentValue], name: String): Option[String] =
    fields.get(name) match
      case Some(ast.ContentValue.Str(v)) => Some(v)
      case Some(other) =>
        throw InterpretError(s"contentToolkitNode: $name expected String, got ${contentValueKind(other)}")
      case None => None

  private def contentBoolField(fields: Map[String, ast.ContentValue], name: String, default: Boolean): Boolean =
    fields.get(name) match
      case Some(ast.ContentValue.Bool(v)) => v
      case Some(other) =>
        throw InterpretError(s"contentToolkitNode: $name expected Boolean, got ${contentValueKind(other)}")
      case None => default

  private def contentIntField(fields: Map[String, ast.ContentValue], name: String, default: Int): Int =
    fields.get(name) match
      case Some(ast.ContentValue.Num(v)) if isIntLike(v) => v.toInt
      case Some(other) =>
        throw InterpretError(s"contentToolkitNode: $name expected Int, got ${contentValueKind(other)}")
      case None => default

  private def contentLiteral(value: ast.ContentValue): Value =
    value match
      case ast.ContentValue.Str(v)  => Value.StringV(v)
      case ast.ContentValue.Bool(v) => Value.boolV(v)
      case ast.ContentValue.Num(v) if isIntLike(v) => Value.intV(v.toLong)
      case ast.ContentValue.Num(v)  => Value.doubleV(v)
      case ast.ContentValue.NullV   => Value.NullV
      case other =>
        throw InterpretError(s"contentToolkitNode: literal expected scalar, got ${contentValueKind(other)}")

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

  private def contentBindRoot(value: Value): Map[String, Value] =
    contentValueMapEntries(value, "contentBind: bindings expected ContentValue.MapV")

  private def contentBindValue(value: Value, bindings: Map[String, Value]): Value =
    value match
      case doc: Value.InstanceV if doc.typeName == "DocumentContent" =>
        val fields = doc.effectiveFields
        instanceValue("DocumentContent",
          "manifest"    -> bindField(fields, "manifest", "DocumentContent.manifest"),
          "title"       -> bindField(fields, "title", "DocumentContent.title"),
          "description" -> bindField(fields, "description", "DocumentContent.description"),
          "attrs"       -> bindField(fields, "attrs", "DocumentContent.attrs"),
          "sections"    -> Value.ListV(bindList(bindField(fields, "sections", "DocumentContent.sections"), "DocumentContent.sections").map(contentBindSection(_, bindings))),
          "blocks"      -> Value.ListV(bindList(bindField(fields, "blocks", "DocumentContent.blocks"), "DocumentContent.blocks").map(contentBindBlock(_, bindings)))
        )
      case section: Value.InstanceV if section.typeName == "SectionContent" =>
        contentBindSection(section, bindings)
      case block: Value.InstanceV if contentBlockValueTypeNames.contains(block.typeName) =>
        contentBindBlock(block, bindings)
      case other =>
        throw InterpretError(s"contentBind: expected DocumentContent, SectionContent, or ContentBlock, got ${Value.show(other)}")

  private def contentBindSection(value: Value, bindings: Map[String, Value]): Value =
    val section = value match
      case inst: Value.InstanceV if inst.typeName == "SectionContent" => inst
      case other => throw InterpretError(s"contentBind: expected SectionContent, got ${Value.show(other)}")
    val fields = section.effectiveFields
    instanceValue("SectionContent",
      "id"       -> bindField(fields, "id", "SectionContent.id"),
      "level"    -> bindField(fields, "level", "SectionContent.level"),
      "title"    -> bindField(fields, "title", "SectionContent.title"),
      "attrs"    -> bindField(fields, "attrs", "SectionContent.attrs"),
      "blocks"   -> Value.ListV(bindList(bindField(fields, "blocks", "SectionContent.blocks"), "SectionContent.blocks").map(contentBindBlock(_, bindings))),
      "children" -> Value.ListV(bindList(bindField(fields, "children", "SectionContent.children"), "SectionContent.children").map(contentBindSection(_, bindings)))
    )

  private def contentBindBlock(value: Value, bindings: Map[String, Value]): Value =
    val block = value match
      case inst: Value.InstanceV if contentBlockValueTypeNames.contains(inst.typeName) => inst
      case other => throw InterpretError(s"contentBind: expected ContentBlock, got ${Value.show(other)}")
    val fields = block.effectiveFields
    block.typeName match
      case "Paragraph" =>
        instanceValue("Paragraph",
          "inlines" -> Value.ListV(bindList(bindField(fields, "inlines", "Paragraph.inlines"), "Paragraph.inlines").map(contentBindInline(_, bindings))),
          "attrs"   -> bindField(fields, "attrs", "Paragraph.attrs")
        )
      case "BulletList" =>
        instanceValue("BulletList",
          "items" -> Value.ListV(bindList(bindField(fields, "items", "BulletList.items"), "BulletList.items").map(item =>
            Value.ListV(bindList(item, "BulletList.items.item").map(contentBindBlock(_, bindings)))
          )),
          "attrs" -> bindField(fields, "attrs", "BulletList.attrs")
        )
      case "OrderedList" =>
        instanceValue("OrderedList",
          "items" -> Value.ListV(bindList(bindField(fields, "items", "OrderedList.items"), "OrderedList.items").map(item =>
            Value.ListV(bindList(item, "OrderedList.items.item").map(contentBindBlock(_, bindings)))
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
          "headers"    -> Value.ListV(bindList(bindField(fields, "headers", "Table.headers"), "Table.headers").map(cell =>
            Value.ListV(bindList(cell, "Table.headers.cell").map(contentBindInline(_, bindings)))
          )),
          "rows"       -> Value.ListV(bindList(bindField(fields, "rows", "Table.rows"), "Table.rows").map(row =>
            Value.ListV(bindList(row, "Table.rows.row").map(cell =>
              Value.ListV(bindList(cell, "Table.rows.cell").map(contentBindInline(_, bindings)))
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
        throw InterpretError(s"contentBind: expected ContentBlock, got $other")

  private def contentBindInline(value: Value, bindings: Map[String, Value]): Value =
    val inline = value match
      case inst: Value.InstanceV => inst
      case other => throw InterpretError(s"contentBind: expected ContentInline, got ${Value.show(other)}")
    val fields = inline.effectiveFields
    inline.typeName match
      case "Text" =>
        value
      case "Emphasis" =>
        instanceValue("Emphasis",
          "children" -> Value.ListV(bindList(bindField(fields, "children", "Emphasis.children"), "Emphasis.children").map(contentBindInline(_, bindings)))
        )
      case "Strong" =>
        instanceValue("Strong",
          "children" -> Value.ListV(bindList(bindField(fields, "children", "Strong.children"), "Strong.children").map(contentBindInline(_, bindings)))
        )
      case "Code" =>
        value
      case "Link" =>
        instanceValue("Link",
          "label" -> Value.ListV(bindList(bindField(fields, "label", "Link.label"), "Link.label").map(contentBindInline(_, bindings))),
          "href"  -> bindField(fields, "href", "Link.href"),
          "title" -> bindField(fields, "title", "Link.title")
        )
      case "Expr" =>
        val source = bindString(bindField(fields, "source", "Expr.source"), "Expr.source")
        contentBindLookup(bindings, source)
          .map(value => instanceValue("Text", "value" -> Value.StringV(contentValueText(value))))
          .getOrElse(value)
      case other =>
        throw InterpretError(s"contentBind: expected ContentInline, got $other")

  private def contentBindLookup(bindings: Map[String, Value], source: String): Option[Value] =
    if contentBindPathPattern.pattern.matcher(source).matches then
      val segments = source.split("\\.").toList
      segments match
        case head :: tail => bindings.get(head).flatMap(contentBindLookup(_, tail))
        case Nil          => None
    else None

  private def contentBindLookup(value: Value, segments: List[String]): Option[Value] =
    segments match
      case Nil => Some(value)
      case head :: tail =>
        value match
          case inst: Value.InstanceV if inst.typeName == "MapV" =>
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

  private def contentValueText(value: Value): String =
    value match
      case inst: Value.InstanceV if inst.typeName == "Str" =>
        bindString(bindField(inst.effectiveFields, "value", "ContentValue.Str.value"), "ContentValue.Str.value")
      case inst: Value.InstanceV if inst.typeName == "Bool" =>
        bindBool(bindField(inst.effectiveFields, "value", "ContentValue.Bool.value"), "ContentValue.Bool.value").toString
      case inst: Value.InstanceV if inst.typeName == "Num" =>
        numberString(bindDouble(bindField(inst.effectiveFields, "value", "ContentValue.Num.value"), "ContentValue.Num.value"))
      case inst: Value.InstanceV if inst.typeName == "NullV" =>
        ""
      case inst: Value.InstanceV if inst.typeName == "ListV" =>
        bindList(bindField(inst.effectiveFields, "values", "ContentValue.ListV.values"), "ContentValue.ListV.values")
          .map(contentValueText)
          .mkString(", ")
      case inst: Value.InstanceV if inst.typeName == "MapV" =>
        contentValueMapEntries(inst, "ContentValue.MapV.values").toList.sortBy(_._1)
          .map { case (key, v) => s"$key: ${contentValueText(v)}" }
          .mkString("{", ", ", "}")
      case other =>
        Value.show(other)

  private def contentValueMapEntries(value: Value, context: String): Map[String, Value] =
    value match
      case inst: Value.InstanceV if inst.typeName == "MapV" =>
        bindField(inst.effectiveFields, "values", "ContentValue.MapV.values") match
          case Value.MapV(entries) =>
            entries.map {
              case (Value.StringV(key), value) => key -> value
              case (key, _) => throw InterpretError(s"$context expected string keys, got ${Value.show(key)}")
            }
          case other =>
            throw InterpretError(s"$context expected Map, got ${Value.show(other)}")
      case other =>
        throw InterpretError(s"$context, got ${Value.show(other)}")

  private def bindField(fields: Map[String, Value], name: String, context: String): Value =
    fields.getOrElse(name, throw InterpretError(s"contentBind: missing $context"))

  private def bindList(value: Value, context: String): List[Value] =
    value match
      case Value.ListV(items) => items
      case other             => throw InterpretError(s"contentBind: $context expected List, got ${Value.show(other)}")

  private def bindString(value: Value, context: String): String =
    value match
      case Value.StringV(v) => v
      case other           => throw InterpretError(s"contentBind: $context expected String, got ${Value.show(other)}")

  private def bindBool(value: Value, context: String): Boolean =
    value match
      case Value.BoolV(v) => v
      case other          => throw InterpretError(s"contentBind: $context expected Boolean, got ${Value.show(other)}")

  private def bindDouble(value: Value, context: String): Double =
    value match
      case Value.DoubleV(v) => v
      case Value.IntV(v)    => v.toDouble
      case other            => throw InterpretError(s"contentBind: $context expected Number, got ${Value.show(other)}")

  private val contentBlockValueTypeNames: Set[String] =
    Set("Paragraph", "BulletList", "OrderedList", "Image", "Table", "Embedded")

  private def contentPlainTextAny(value: Any): String =
    value match
      case v: Value => contentPlainTextValue(v)
      case other =>
        throw InterpretError(s"contentPlainText: expected SectionContent or ContentBlock, got ${String.valueOf(other)}")

  private def contentPlainTextValue(value: Value): String =
    value match
      case section: Value.InstanceV if section.typeName == "SectionContent" =>
        sectionPlainText(section)
      case block: Value.InstanceV if contentBlockValueTypeNames.contains(block.typeName) =>
        blockPlainText(block)
      case other =>
        throw InterpretError(s"contentPlainText: expected SectionContent or ContentBlock, got ${Value.show(other)}")

  private def sectionPlainText(section: Value.InstanceV): String =
    val fields = section.effectiveFields
    val title = requiredStringField(fields, "title", "SectionContent.title")
    val blocks = requiredListField(fields, "blocks", "SectionContent.blocks")
    val children = requiredListField(fields, "children", "SectionContent.children")
    val fragments =
      title ::
        blocks.map(blockPlainText) ++
        children.map(child => sectionPlainText(requiredInstance(child, "SectionContent", "SectionContent.children")))
    fragments.filter(_.nonEmpty).mkString("\n")

  private def blockPlainText(value: Value): String =
    value match
      case block: Value.InstanceV if block.typeName == "Paragraph" =>
        val inlines = requiredListField(block.effectiveFields, "inlines", "Paragraph.inlines")
        inlinePlainText(inlines)
      case block: Value.InstanceV if block.typeName == "BulletList" =>
        val items = requiredListField(block.effectiveFields, "items", "BulletList.items")
        items.map(item => "- " + blockListPlainText(item, "BulletList.items")).filter(_.trim.nonEmpty).mkString("\n")
      case block: Value.InstanceV if block.typeName == "OrderedList" =>
        val fields = block.effectiveFields
        val start = requiredIntField(fields, "start", "OrderedList.start")
        val items = requiredListField(fields, "items", "OrderedList.items")
        items.zipWithIndex.map { case (item, idx) =>
          s"${start + idx}. ${blockListPlainText(item, "OrderedList.items")}"
        }.filter(_.trim.nonEmpty).mkString("\n")
      case block: Value.InstanceV if block.typeName == "Image" =>
        val fields = block.effectiveFields
        val src = requiredStringField(fields, "src", "Image.src")
        val alt = requiredStringField(fields, "alt", "Image.alt")
        if alt.isEmpty then src else alt
      case block: Value.InstanceV if block.typeName == "Table" =>
        val fields = block.effectiveFields
        val headers = requiredListField(fields, "headers", "Table.headers")
        val rows = requiredListField(fields, "rows", "Table.rows")
        tableValuePlainText(headers, rows)
      case block: Value.InstanceV if block.typeName == "Embedded" =>
        val fields = block.effectiveFields
        val lang = requiredStringField(fields, "lang", "Embedded.lang")
        val source = requiredStringField(fields, "source", "Embedded.source")
        if lang.isEmpty then source else s"$lang: $source"
      case other =>
        throw InterpretError(s"contentPlainText: expected ContentBlock, got ${Value.show(other)}")

  private def blockListPlainText(value: Value, context: String): String =
    val blocks = value match
      case Value.ListV(items) => items
      case other =>
        throw InterpretError(s"contentPlainText: $context expected List[ContentBlock], got ${Value.show(other)}")
    blocks.map(blockPlainText).filter(_.nonEmpty).mkString(" ")

  private def tableValuePlainText(headers: List[Value], rows: List[Value]): String =
    val header = headers.map(cell => inlinePlainText(requiredValueList(cell, "Table.headers"))).mkString(" | ")
    val bodyRows = rows.map(row =>
      requiredValueList(row, "Table.rows")
        .map(cell => inlinePlainText(requiredValueList(cell, "Table.rows.cell")))
        .mkString(" | ")
    )
    (header :: bodyRows).filter(_.trim.nonEmpty).mkString("\n")

  private def inlinePlainText(values: List[Value]): String =
    values.map(inlinePlainText).mkString

  private def inlinePlainText(value: Value): String =
    value match
      case inline: Value.InstanceV if inline.typeName == "Text" =>
        requiredStringField(inline.effectiveFields, "value", "Text.value")
      case inline: Value.InstanceV if inline.typeName == "Emphasis" =>
        inlinePlainText(requiredListField(inline.effectiveFields, "children", "Emphasis.children"))
      case inline: Value.InstanceV if inline.typeName == "Strong" =>
        inlinePlainText(requiredListField(inline.effectiveFields, "children", "Strong.children"))
      case inline: Value.InstanceV if inline.typeName == "Code" =>
        s"`${requiredStringField(inline.effectiveFields, "value", "Code.value")}`"
      case inline: Value.InstanceV if inline.typeName == "Link" =>
        val fields = inline.effectiveFields
        inlinePlainText(requiredListField(fields, "label", "Link.label")) +
          s" (${requiredStringField(fields, "href", "Link.href")})"
      case inline: Value.InstanceV if inline.typeName == "Expr" =>
        "${" + requiredStringField(inline.effectiveFields, "source", "Expr.source") + "}"
      case other =>
        throw InterpretError(s"contentPlainText: expected ContentInline, got ${Value.show(other)}")

  private def requiredInstance(value: Value, typeName: String, context: String): Value.InstanceV =
    value match
      case inst: Value.InstanceV if inst.typeName == typeName => inst
      case other =>
        throw InterpretError(s"contentPlainText: $context expected $typeName, got ${Value.show(other)}")

  private def requiredStringField(fields: Map[String, Value], name: String, context: String): String =
    fields.get(name) match
      case Some(Value.StringV(value)) => value
      case Some(other) =>
        throw InterpretError(s"contentPlainText: $context expected String, got ${Value.show(other)}")
      case None =>
        throw InterpretError(s"contentPlainText: missing $context")

  private def requiredIntField(fields: Map[String, Value], name: String, context: String): Int =
    fields.get(name) match
      case Some(Value.IntV(value)) => value.toInt
      case Some(other) =>
        throw InterpretError(s"contentPlainText: $context expected Int, got ${Value.show(other)}")
      case None =>
        throw InterpretError(s"contentPlainText: missing $context")

  private def requiredListField(fields: Map[String, Value], name: String, context: String): List[Value] =
    fields.get(name) match
      case Some(Value.ListV(values)) => values
      case Some(other) =>
        throw InterpretError(s"contentPlainText: $context expected List, got ${Value.show(other)}")
      case None =>
        throw InterpretError(s"contentPlainText: missing $context")

  private def requiredValueList(value: Value, context: String): List[Value] =
    value match
      case Value.ListV(values) => values
      case other =>
        throw InterpretError(s"contentPlainText: $context expected List, got ${Value.show(other)}")

  private def contentToMarkdownAny(value: Any): String =
    value match
      case v: Value => contentMarkdownValue(v)
      case other =>
        throw InterpretError(
          s"contentToMarkdown: expected DocumentContent, SectionContent, or ContentBlock, got ${String.valueOf(other)}"
        )

  private def contentMarkdownValue(value: Value): String =
    value match
      case doc: Value.InstanceV if doc.typeName == "DocumentContent" =>
        documentMarkdown(doc)
      case section: Value.InstanceV if section.typeName == "SectionContent" =>
        sectionMarkdown(section)
      case block: Value.InstanceV if contentBlockValueTypeNames.contains(block.typeName) =>
        blockMarkdown(block)
      case other =>
        throw InterpretError(
          s"contentToMarkdown: expected DocumentContent, SectionContent, or ContentBlock, got ${Value.show(other)}"
        )

  private def documentMarkdown(doc: Value.InstanceV): String =
    val fields = doc.effectiveFields
    val frontMatter = fields.get("manifest").flatMap(manifestMarkdown).toList
    val blocks = mdListField(fields, "blocks", "DocumentContent.blocks").map(blockMarkdown)
    val sections = mdListField(fields, "sections", "DocumentContent.sections").map { value =>
      sectionMarkdown(mdInstance(value, "SectionContent", "DocumentContent.sections"))
    }
    (frontMatter ++ blocks ++ sections).filter(_.nonEmpty).mkString("\n\n")

  private def manifestMarkdown(value: Value): Option[String] =
    value match
      case inst: Value.InstanceV if inst.typeName == "MapV" =>
        val values = mdContentMapInstance(inst, "DocumentContent.manifest")
        if values.isEmpty then None
        else Some("---\n" + yamlMapLines(values, 0).mkString("\n") + "\n---")
      case inst: Value.InstanceV if inst.typeName == "NullV" =>
        None
      case other =>
        Some("---\nvalue: " + yamlScalar(other) + "\n---")

  private def sectionMarkdown(section: Value.InstanceV): String =
    val fields = section.effectiveFields
    val level = clampHeadingLevel(mdIntField(fields, "level", "SectionContent.level"))
    val id = mdStringField(fields, "id", "SectionContent.id")
    val title = inlineTextMarkdown(List(Value.InstanceV("Text", Map("value" -> Value.StringV(mdStringField(fields, "title", "SectionContent.title"))))))
    val attrs = mdContentValueMap(mdField(fields, "attrs", "SectionContent.attrs"), "SectionContent.attrs")
    val headingAttrs = headingAttrGroup(id, attrs)
    val heading = ("#" * level) + " " + title + headingAttrs
    val blocks = mdListField(fields, "blocks", "SectionContent.blocks").map(blockMarkdown)
    val children = mdListField(fields, "children", "SectionContent.children").map { value =>
      sectionMarkdown(mdInstance(value, "SectionContent", "SectionContent.children"))
    }
    (heading :: (blocks ++ children)).filter(_.nonEmpty).mkString("\n\n")

  private def blockMarkdown(value: Value): String =
    val body = value match
      case block: Value.InstanceV if block.typeName == "Paragraph" =>
        inlineTextMarkdown(mdListField(block.effectiveFields, "inlines", "Paragraph.inlines"))
      case block: Value.InstanceV if block.typeName == "BulletList" =>
        val items = mdListField(block.effectiveFields, "items", "BulletList.items")
        items.map(item => listItemMarkdown("- ", item, "BulletList.items")).mkString("\n")
      case block: Value.InstanceV if block.typeName == "OrderedList" =>
        val fields = block.effectiveFields
        val start = mdIntField(fields, "start", "OrderedList.start")
        mdListField(fields, "items", "OrderedList.items").zipWithIndex.map { case (item, idx) =>
          listItemMarkdown(s"${start + idx}. ", item, "OrderedList.items")
        }.mkString("\n")
      case block: Value.InstanceV if block.typeName == "Image" =>
        val fields = block.effectiveFields
        val alt = inlineTextMarkdown(List(Value.InstanceV("Text", Map("value" -> Value.StringV(mdStringField(fields, "alt", "Image.alt"))))))
        val src = mdStringField(fields, "src", "Image.src")
        val title = mdOptionString(mdField(fields, "title", "Image.title"), "Image.title")
          .map(t => " " + quoteMarkdownAttr(t))
          .getOrElse("")
        s"![${alt}](${src}${title})"
      case block: Value.InstanceV if block.typeName == "Table" =>
        val fields = block.effectiveFields
        tableMarkdown(
          mdListField(fields, "headers", "Table.headers"),
          mdListField(fields, "rows", "Table.rows"),
          mdStringListField(fields, "alignments", "Table.alignments")
        )
      case block: Value.InstanceV if block.typeName == "Embedded" =>
        embeddedMarkdown(block)
      case other =>
        throw InterpretError(s"contentToMarkdown: expected ContentBlock, got ${Value.show(other)}")

    value match
      case block: Value.InstanceV if block.typeName != "Embedded" =>
        val attrs = mdContentValueMap(mdField(block.effectiveFields, "attrs", s"${block.typeName}.attrs"), s"${block.typeName}.attrs")
        metadataDirective(attrs).fold(body)(_ + "\n" + body)
      case _ =>
        body

  private def listItemMarkdown(prefix: String, item: Value, context: String): String =
    val blocks = item match
      case Value.ListV(values) => values
      case other =>
        throw InterpretError(s"contentToMarkdown: $context expected List[ContentBlock], got ${Value.show(other)}")
    val body = blocks.map(blockMarkdown).filter(_.nonEmpty).mkString("\n\n")
    val lines = body.split("\n", -1).toList
    if body.isEmpty then prefix.trim
    else (prefix + lines.head) + lines.tail.map(line => "\n  " + line).mkString

  private def tableMarkdown(headers: List[Value], rows: List[Value], alignments: List[String]): String =
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

  private def tableCellMarkdown(value: Value, context: String): String =
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

  private def embeddedMarkdown(block: Value.InstanceV): String =
    val fields = block.effectiveFields
    val lang = mdStringField(fields, "lang", "Embedded.lang")
    val source = mdStringField(fields, "source", "Embedded.source")
    val attrs = mdContentValueMap(mdField(fields, "attrs", "Embedded.attrs"), "Embedded.attrs")
    val info = (lang :: fenceAttrTokens(attrs)).filter(_.nonEmpty).mkString(" ")
    val fence = fenceDelimiter(source)
    val body = if source.endsWith("\n") then source else source + "\n"
    if info.isEmpty then s"$fence\n$body$fence" else s"$fence$info\n$body$fence"

  private def inlineTextMarkdown(values: List[Value]): String =
    values.map(inlineMarkdown).mkString

  private def inlineMarkdown(value: Value): String =
    value match
      case inline: Value.InstanceV if inline.typeName == "Text" =>
        escapeMarkdownText(mdStringField(inline.effectiveFields, "value", "Text.value"))
      case inline: Value.InstanceV if inline.typeName == "Emphasis" =>
        "*" + inlineTextMarkdown(mdListField(inline.effectiveFields, "children", "Emphasis.children")) + "*"
      case inline: Value.InstanceV if inline.typeName == "Strong" =>
        "**" + inlineTextMarkdown(mdListField(inline.effectiveFields, "children", "Strong.children")) + "**"
      case inline: Value.InstanceV if inline.typeName == "Code" =>
        inlineCodeMarkdown(mdStringField(inline.effectiveFields, "value", "Code.value"))
      case inline: Value.InstanceV if inline.typeName == "Link" =>
        val fields = inline.effectiveFields
        val label = inlineTextMarkdown(mdListField(fields, "label", "Link.label"))
        val href = mdStringField(fields, "href", "Link.href")
        val title = mdOptionString(mdField(fields, "title", "Link.title"), "Link.title")
          .map(t => " " + quoteMarkdownAttr(t))
          .getOrElse("")
        s"[$label]($href$title)"
      case inline: Value.InstanceV if inline.typeName == "Expr" =>
        "${" + mdStringField(inline.effectiveFields, "source", "Expr.source") + "}"
      case other =>
        throw InterpretError(s"contentToMarkdown: expected ContentInline, got ${Value.show(other)}")

  private def headingAttrGroup(id: String, attrs: Map[String, Value]): String =
    val tokens =
      (if id.nonEmpty then List("#" + id) else Nil) ++
        classTokens(attrs) ++
        attrs.toList.filterNot { case (key, _) => key == "class" }.sortBy(_._1).flatMap { case (key, value) =>
          attrToken(key, value, prefix = "")
        }
    if tokens.isEmpty then "" else tokens.mkString(" {", " ", "}")

  private def metadataDirective(attrs: Map[String, Value]): Option[String] =
    val tokens =
      attrs.toList.sortBy(_._1).flatMap { case (key, value) => attrToken(key, value, prefix = "") }
    if tokens.isEmpty then None else Some(tokens.mkString("<!-- @meta ", " ", " -->"))

  private def fenceAttrTokens(attrs: Map[String, Value]): List[String] =
    val id = attrs.get("id").flatMap(value => attrToken("id", value, prefix = "@"))
    val rest =
      attrs.toList.filterNot(_._1 == "id").sortBy(_._1).flatMap { case (key, value) =>
        attrToken(key, value, prefix = "@")
      }
    id.toList ++ rest

  private def classTokens(attrs: Map[String, Value]): List[String] =
    attrs.get("class").collect {
      case inst: Value.InstanceV if inst.typeName == "Str" =>
        mdStringField(inst.effectiveFields, "value", "ContentValue.Str.value")
          .split("\\s+")
          .toList
          .filter(_.nonEmpty)
          .map("." + _)
    }.getOrElse(Nil)

  private def attrToken(key: String, value: Value, prefix: String): Option[String] =
    value match
      case inst: Value.InstanceV if inst.typeName == "Bool" =>
        val bool = mdBoolField(inst.effectiveFields, "value", "ContentValue.Bool.value")
        if prefix.isEmpty && bool then Some(key) else Some(s"$prefix$key=${bool.toString}")
      case inst: Value.InstanceV if inst.typeName == "Str" =>
        Some(s"$prefix$key=${attrScalar(mdStringField(inst.effectiveFields, "value", "ContentValue.Str.value"))}")
      case inst: Value.InstanceV if inst.typeName == "Num" =>
        Some(s"$prefix$key=${numberString(mdDoubleField(inst.effectiveFields, "value", "ContentValue.Num.value"))}")
      case inst: Value.InstanceV if inst.typeName == "NullV" =>
        Some(s"$prefix$key=null")
      case _ =>
        Some(s"$prefix$key=${attrScalar(yamlScalar(value))}")

  private def yamlMapLines(values: Map[String, Value], indent: Int): List[String] =
    values.toList.sortBy(_._1).flatMap { case (key, value) =>
      yamlKeyValueLines(key, value, indent)
    }

  private def yamlKeyValueLines(key: String, value: Value, indent: Int): List[String] =
    val pad = " " * indent
    value match
      case inst: Value.InstanceV if inst.typeName == "MapV" =>
        val values = mdContentMapInstance(inst, "ContentValue.MapV.values")
        if values.isEmpty then List(s"$pad${yamlKey(key)}: {}")
        else s"$pad${yamlKey(key)}:" :: yamlMapLines(values, indent + 2)
      case inst: Value.InstanceV if inst.typeName == "ListV" =>
        val values = mdListField(inst.effectiveFields, "values", "ContentValue.ListV.values")
        if values.isEmpty then List(s"$pad${yamlKey(key)}: []")
        else s"$pad${yamlKey(key)}:" :: yamlListLines(values, indent + 2)
      case _ =>
        List(s"$pad${yamlKey(key)}: ${yamlScalar(value)}")

  private def yamlListLines(values: List[Value], indent: Int): List[String] =
    val pad = " " * indent
    values.flatMap {
      case inst: Value.InstanceV if inst.typeName == "MapV" =>
        val values = mdContentMapInstance(inst, "ContentValue.MapV.values")
        if values.isEmpty then List(s"$pad- {}")
        else s"$pad-" :: yamlMapLines(values, indent + 2)
      case inst: Value.InstanceV if inst.typeName == "ListV" =>
        val values = mdListField(inst.effectiveFields, "values", "ContentValue.ListV.values")
        if values.isEmpty then List(s"$pad- []")
        else s"$pad-" :: yamlListLines(values, indent + 2)
      case value =>
        List(s"$pad- ${yamlScalar(value)}")
    }

  private def yamlScalar(value: Value): String =
    value match
      case inst: Value.InstanceV if inst.typeName == "Str" =>
        yamlString(mdStringField(inst.effectiveFields, "value", "ContentValue.Str.value"))
      case inst: Value.InstanceV if inst.typeName == "Bool" =>
        mdBoolField(inst.effectiveFields, "value", "ContentValue.Bool.value").toString
      case inst: Value.InstanceV if inst.typeName == "Num" =>
        numberString(mdDoubleField(inst.effectiveFields, "value", "ContentValue.Num.value"))
      case inst: Value.InstanceV if inst.typeName == "NullV" =>
        "null"
      case inst: Value.InstanceV if inst.typeName == "MapV" =>
        val values = mdContentMapInstance(inst, "ContentValue.MapV.values")
        if values.isEmpty then "{}" else quoteMarkdownAttr(Value.show(value))
      case inst: Value.InstanceV if inst.typeName == "ListV" =>
        val values = mdListField(inst.effectiveFields, "values", "ContentValue.ListV.values")
        if values.isEmpty then "[]" else quoteMarkdownAttr(Value.show(value))
      case Value.StringV(value) =>
        yamlString(value)
      case Value.BoolV(value) =>
        value.toString
      case Value.IntV(value) =>
        value.toString
      case Value.DoubleV(value) =>
        numberString(value)
      case Value.NullV =>
        "null"
      case _ =>
        quoteMarkdownAttr(Value.show(value))

  private def mdField(fields: Map[String, Value], name: String, context: String): Value =
    fields.getOrElse(name, throw InterpretError(s"contentToMarkdown: missing $context"))

  private def mdStringField(fields: Map[String, Value], name: String, context: String): String =
    mdField(fields, name, context) match
      case Value.StringV(value) => value
      case other => throw InterpretError(s"contentToMarkdown: $context expected String, got ${Value.show(other)}")

  private def mdIntField(fields: Map[String, Value], name: String, context: String): Int =
    mdField(fields, name, context) match
      case Value.IntV(value) => value.toInt
      case other => throw InterpretError(s"contentToMarkdown: $context expected Int, got ${Value.show(other)}")

  private def mdDoubleField(fields: Map[String, Value], name: String, context: String): Double =
    mdField(fields, name, context) match
      case Value.DoubleV(value) => value
      case Value.IntV(value) => value.toDouble
      case other => throw InterpretError(s"contentToMarkdown: $context expected Number, got ${Value.show(other)}")

  private def mdBoolField(fields: Map[String, Value], name: String, context: String): Boolean =
    mdField(fields, name, context) match
      case Value.BoolV(value) => value
      case other => throw InterpretError(s"contentToMarkdown: $context expected Boolean, got ${Value.show(other)}")

  private def mdListField(fields: Map[String, Value], name: String, context: String): List[Value] =
    mdField(fields, name, context) match
      case Value.ListV(values) => values
      case other => throw InterpretError(s"contentToMarkdown: $context expected List, got ${Value.show(other)}")

  private def mdValueList(value: Value, context: String): List[Value] =
    value match
      case Value.ListV(values) => values
      case other => throw InterpretError(s"contentToMarkdown: $context expected List, got ${Value.show(other)}")

  private def mdStringListField(fields: Map[String, Value], name: String, context: String): List[String] =
    mdListField(fields, name, context).map {
      case Value.StringV(value) => value
      case other => throw InterpretError(s"contentToMarkdown: $context expected String list, got ${Value.show(other)}")
    }

  private def mdContentValueMap(value: Value, context: String): Map[String, Value] =
    value match
      case Value.MapV(values) =>
        values.map {
          case (Value.StringV(key), value) => key -> value
          case (key, _) =>
            throw InterpretError(s"contentToMarkdown: $context expected String keys, got ${Value.show(key)}")
        }
      case other =>
        throw InterpretError(s"contentToMarkdown: $context expected Map, got ${Value.show(other)}")

  private def mdContentMapInstance(value: Value.InstanceV, context: String): Map[String, Value] =
    mdContentValueMap(mdField(value.effectiveFields, "values", context), context)

  private def mdOptionString(value: Value, context: String): Option[String] =
    value match
      case Value.OptionV(null) => None
      case Value.OptionV(Value.StringV(v)) => Some(v)
      case Value.OptionV(other) =>
        throw InterpretError(s"contentToMarkdown: $context expected Option[String], got Some(${Value.show(other)})")
      case other =>
        throw InterpretError(s"contentToMarkdown: $context expected Option[String], got ${Value.show(other)}")

  private def mdInstance(value: Value, typeName: String, context: String): Value.InstanceV =
    value match
      case inst: Value.InstanceV if inst.typeName == typeName => inst
      case other =>
        throw InterpretError(s"contentToMarkdown: $context expected $typeName, got ${Value.show(other)}")

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

  private def vstackNode(gap: Int, children: List[Value]): Value =
    instanceValue("VStackNode",
      "gap" -> Value.intV(gap.toLong),
      "children" -> Value.ListV(children)
    )

  private def hstackNode(gap: Int, children: List[Value]): Value =
    instanceValue("HStackNode",
      "gap" -> Value.intV(gap.toLong),
      "children" -> Value.ListV(children)
    )

  private def dividerNode(): Value =
    instanceValue("DividerNode")

  private def headingNode(level: Int, text: String): Value =
    instanceValue("HeadingNode",
      "level" -> Value.intV(level.toLong),
      "text" -> Value.StringV(text)
    )

  private def textNode_(text: String): Value =
    instanceValue("TextNode_",
      "text" -> Value.StringV(text)
    )

  private def rawTextNode(text: String): Value =
    instanceValue("RawTextNode",
      "text" -> Value.StringV(text)
    )

  private def tableColumn(label: String, key: String): Value =
    instanceValue("TableColumn",
      "label" -> Value.StringV(label),
      "key"   -> Value.StringV(key)
    )

  private def tableNode(headers: List[List[ast.ContentInline]], rows: List[List[List[ast.ContentInline]]]): Value =
    instanceValue("TableNode",
      "columns" -> Value.ListV(headers.zipWithIndex.map { case (header, idx) =>
        tableColumn(inlineText(header), s"col$idx")
      }),
      "rows" -> Value.ListV(rows.map(row =>
        Value.ListV(row.map(cell => textNode_(inlineText(cell))))
      )),
      "sortCol" -> Value.NullV
    )

  private def showWhenNode(signal: Value, whenTrue: Value, whenFalse: Value): Value =
    instanceValue("ShowWhenNode",
      "signal" -> signal,
      "whenTrue" -> whenTrue,
      "whenFalse" -> whenFalse
    )

  private def signalTextNode(signal: Value): Value =
    instanceValue("SignalTextNode",
      "signal" -> signal
    )

  private def fragmentNode(children: List[Value]): Value =
    instanceValue("FragmentNode",
      "children" -> Value.ListV(children)
    )

  private def textFieldNode(value: Value, label: String, disabled: Boolean, required: Boolean): Value =
    instanceValue("TextFieldNode",
      "value" -> value,
      "label" -> Value.StringV(label),
      "disabled" -> Value.boolV(disabled),
      "required" -> Value.boolV(required)
    )

  private def checkboxNode(checked: Value, label: String, disabled: Boolean): Value =
    instanceValue("CheckboxNode",
      "checked" -> checked,
      "label" -> Value.StringV(label),
      "disabled" -> Value.boolV(disabled)
    )

  private def signalButtonNode(signal: Value, value: Value, label: String, disabled: Boolean): Value =
    instanceValue("SignalButtonNode",
      "signal" -> signal,
      "value" -> value,
      "label" -> Value.StringV(label),
      "disabled" -> Value.boolV(disabled)
    )

  private def actionButtonNode(handler: Value, label: String, disabled: Boolean): Value =
    instanceValue("ActionButtonNode",
      "handler" -> handler,
      "label" -> Value.StringV(label),
      "disabled" -> Value.boolV(disabled)
    )

  private def badgeNode(content: String, variant: String): Value =
    instanceValue("BadgeNode",
      "content" -> Value.StringV(content),
      "variant" -> Value.StringV(variant)
    )

  private def cardNode(body: List[Value]): Value =
    cardNode(Value.NullV, body, Value.NullV)

  private def cardNode(header: Value, body: List[Value], footer: Value): Value =
    instanceValue("CardNode",
      "header" -> header,
      "body" -> Value.ListV(body),
      "footer" -> footer
    )

  private def documentValue(doc: ast.DocumentContent): Value =
    instanceValue("DocumentContent",
      "manifest"    -> contentValue(doc.manifest),
      "title"       -> optionString(doc.title),
      "description" -> optionString(doc.description),
      "attrs"       -> attrsValue(doc.attrs),
      "sections"    -> Value.ListV(doc.sections.map(sectionValue)),
      "blocks"      -> Value.ListV(doc.blocks.map(blockValue))
    )

  private def sectionValue(section: ast.SectionContent): Value =
    instanceValue("SectionContent",
      "id"       -> Value.StringV(section.id),
      "level"    -> Value.intV(section.level.toLong),
      "title"    -> Value.StringV(section.title),
      "attrs"    -> attrsValue(section.attrs),
      "blocks"   -> Value.ListV(section.blocks.map(blockValue)),
      "children" -> Value.ListV(section.children.map(sectionValue))
    )

  private def blockValue(block: ast.ContentBlock): Value = block match
    case ast.ContentBlock.Paragraph(inlines, attrs) =>
      instanceValue("Paragraph",
        "inlines" -> Value.ListV(inlines.map(inlineValue)),
        "attrs"   -> attrsValue(attrs)
      )
    case ast.ContentBlock.BulletList(items, attrs) =>
      instanceValue("BulletList",
        "items" -> Value.ListV(items.map(row => Value.ListV(row.map(blockValue)))),
        "attrs" -> attrsValue(attrs)
      )
    case ast.ContentBlock.OrderedList(items, start, attrs) =>
      instanceValue("OrderedList",
        "items" -> Value.ListV(items.map(row => Value.ListV(row.map(blockValue)))),
        "start" -> Value.intV(start.toLong),
        "attrs" -> attrsValue(attrs)
      )
    case ast.ContentBlock.Image(src, alt, title, attrs) =>
      instanceValue("Image",
        "src"   -> Value.StringV(src),
        "alt"   -> Value.StringV(alt),
        "title" -> optionString(title),
        "attrs" -> attrsValue(attrs)
      )
    case ast.ContentBlock.Table(headers, rows, alignments, attrs) =>
      instanceValue("Table",
        "headers"    -> Value.ListV(headers.map(cell => Value.ListV(cell.map(inlineValue)))),
        "rows"       -> Value.ListV(rows.map(row => Value.ListV(row.map(cell => Value.ListV(cell.map(inlineValue)))))),
        "alignments" -> Value.ListV(alignments.map(value => Value.StringV(value))),
        "attrs"      -> attrsValue(attrs)
      )
    case ast.ContentBlock.Embedded(lang, source, kind, data, attrs) =>
      instanceValue("Embedded",
        "lang"   -> Value.StringV(lang),
        "source" -> Value.StringV(source),
        "kind"   -> embeddedKindValue(kind),
        "data"   -> Value.optionV(data.map(contentValue)),
        "attrs"  -> attrsValue(attrs)
      )

  private def embeddedKindValue(kind: ast.EmbeddedKind): Value =
    instanceValue(kind.toString)

  private def inlineValue(inline: ast.ContentInline): Value = inline match
    case ast.ContentInline.Text(value) =>
      instanceValue("Text", "value" -> Value.StringV(value))
    case ast.ContentInline.Emphasis(children) =>
      instanceValue("Emphasis", "children" -> Value.ListV(children.map(inlineValue)))
    case ast.ContentInline.Strong(children) =>
      instanceValue("Strong", "children" -> Value.ListV(children.map(inlineValue)))
    case ast.ContentInline.Code(value) =>
      instanceValue("Code", "value" -> Value.StringV(value))
    case ast.ContentInline.Link(label, href, title) =>
      instanceValue("Link",
        "label" -> Value.ListV(label.map(inlineValue)),
        "href"  -> Value.StringV(href),
        "title" -> optionString(title)
      )
    case ast.ContentInline.Expr(source) =>
      instanceValue("Expr", "source" -> Value.StringV(source))

  private def contentValue(value: ast.ContentValue): Value = value match
    case ast.ContentValue.Str(v)    => instanceValue("Str", "value" -> Value.StringV(v))
    case ast.ContentValue.Bool(v)   => instanceValue("Bool", "value" -> Value.boolV(v))
    case ast.ContentValue.Num(v)    => instanceValue("Num", "value" -> Value.doubleV(v))
    case ast.ContentValue.ListV(vs) => instanceValue("ListV", "values" -> Value.ListV(vs.map(contentValue)))
    case ast.ContentValue.MapV(vs)  => instanceValue("MapV", "values" -> attrsValue(vs))
    case ast.ContentValue.NullV     => instanceValue("NullV")

  private def attrsValue(attrs: Map[String, ast.ContentValue]): Value =
    Value.MapV(attrs.map { case (k, v) => Value.StringV(k) -> contentValue(v) })

  private def optionString(value: Option[String]): Value =
    Value.optionV(value.map(Value.StringV.apply))
