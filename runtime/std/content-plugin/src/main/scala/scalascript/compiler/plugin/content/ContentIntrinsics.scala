package scalascript.compiler.plugin.content

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
      toolkitDocumentNode(ctx, currentDocument(ctx, "contentToolkitNode()"), options)
    },
    QualifiedName("contentToolkitBlock") -> PluginNative.evalLegacy { (ctx, args) =>
      val (id, options) = toolkitSelectorArgs("contentToolkitBlock", args)
      toolkitBlockById(ctx, currentDocument(ctx, "contentToolkitBlock(id)"), id, options)
    },
    QualifiedName("contentToolkitSection") -> PluginNative.evalLegacy { (ctx, args) =>
      val (id, options) = toolkitSelectorArgs("contentToolkitSection", args)
      toolkitSectionById(ctx, currentDocument(ctx, "contentToolkitSection(id)"), id, options)
    }
  )

  private case class ToolkitOptions(
    includeCode: Boolean = false,
    sectionGap: Int = 16,
    blockGap: Int = 8,
    listGap: Int = 4,
    wrapDocumentInCard: Boolean = false,
    wrapTopLevelSectionsInCards: Boolean = false,
    components: Map[String, Value] = Map.empty
  )

  private case class ToolkitUiEnv(signals: Map[String, Value])

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
      components = componentRegistry(fields.get("components"))
    )

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

  private def instanceValue(typeName: String, fields: (String, Value)*): Value.InstanceV =
    val inst = Value.InstanceV(typeName, fields.toMap)
    inst.fieldNames = fields.map(_._1).toArray
    inst.fieldsArr = fields.map(_._2).toArray
    inst

  private def toolkitDocumentNode(ctx: PluginContext, doc: ast.DocumentContent, options: ToolkitOptions): Value =
    val children =
      doc.blocks.map(toolkitBlockNode(ctx, doc, _, options)) ++
        doc.sections.map(toolkitSectionNode(ctx, doc, _, options, topLevel = true))
    val body = vstackNode(options.sectionGap, children)
    if options.wrapDocumentInCard then cardNode(List(body)) else body

  private def toolkitBlockById(ctx: PluginContext, doc: ast.DocumentContent, id: String, options: ToolkitOptions): Value =
    blocksDeep(doc).filter(block => blockId(block).contains(id)) match
      case block :: Nil =>
        toolkitBlockNode(ctx, doc, block, options)
      case Nil =>
        throw InterpretError(s"contentToolkitBlock: no block with id '$id'")
      case _ =>
        throw InterpretError(s"contentToolkitBlock: duplicate block id '$id'")

  private def toolkitSectionById(ctx: PluginContext, doc: ast.DocumentContent, id: String, options: ToolkitOptions): Value =
    sectionsDeep(doc).filter(_.id == id) match
      case section :: Nil =>
        toolkitSectionNode(ctx, doc, section, options, topLevel = true)
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
      topLevel: Boolean
  ): Value =
    componentRenderer(section.attrs, options) match
      case Some((name, render)) =>
        return renderComponent(ctx, name, render, sectionComponentContext(name, doc, section))
      case None => ()
    val children =
      headingNode(section.level, section.title) ::
        (section.blocks.map(toolkitBlockNode(ctx, doc, _, options)) ++
          section.children.map(toolkitSectionNode(ctx, doc, _, options, topLevel = false)))
    val stack = vstackNode(options.blockGap, children)
    if topLevel && options.wrapTopLevelSectionsInCards then cardNode(List(stack)) else stack

  private def toolkitBlockNode(ctx: PluginContext, doc: ast.DocumentContent, block: ast.ContentBlock, options: ToolkitOptions): Value =
    componentRenderer(blockAttrs(block), options) match
      case Some((name, render)) =>
        return renderComponent(ctx, name, render, blockComponentContext(name, doc, block))
      case None => ()
    block match
      case ast.ContentBlock.Paragraph(inlines, _) =>
        textNode_(inlineText(inlines))
      case ast.ContentBlock.BulletList(items, _) =>
        vstackNode(options.listGap, items.map(item => rawTextNode("- " + blocksText(item))))
      case ast.ContentBlock.OrderedList(items, start, _) =>
        vstackNode(options.listGap, items.zipWithIndex.map { case (item, idx) =>
          rawTextNode(s"${start + idx}. ${blocksText(item)}")
        })
      case ast.ContentBlock.Image(src, alt, title, _) =>
        val label =
          if alt.isEmpty then src
          else title match
            case Some(t) => s"$alt ($t)"
            case None    => alt
        rawTextNode(label)
      case ast.ContentBlock.Embedded(lang, _, _, data, attrs) if isToolkitUiBlock(lang, attrs) =>
        data match
          case Some(value) => toolkitUiNode(value, options)
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

  private def toolkitUiNode(value: ast.ContentValue, options: ToolkitOptions): Value =
    val root = contentMap(value, "@ui=toolkit")
    val signals = root.get("signals").map(toolkitSignals).getOrElse(Map.empty)
    val env = ToolkitUiEnv(signals)
    root.get("controls")
      .orElse(root.get("control"))
      .map(toolkitControl(_, env, options))
      .getOrElse(throw InterpretError("contentToolkitNode: @ui=toolkit block requires controls"))

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
          case otherKind =>
            throw InterpretError(s"contentToolkitNode: unsupported control type '$otherKind'")

  private def toolkitButton(fields: Map[String, ast.ContentValue], env: ToolkitUiEnv): Value =
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
    case ast.ContentBlock.Embedded(lang, source, _, _, _) =>
      if lang.isEmpty then source else s"$lang: $source"

  private def inlineText(inlines: List[ast.ContentInline]): String =
    inlines.map(inlineText).mkString

  private def inlineText(inline: ast.ContentInline): String = inline match
    case ast.ContentInline.Text(value) => value
    case ast.ContentInline.Emphasis(children) => inlineText(children)
    case ast.ContentInline.Strong(children)   => inlineText(children)
    case ast.ContentInline.Code(value)        => s"`$value`"
    case ast.ContentInline.Link(label, href, _) => inlineText(label) + s" ($href)"
    case ast.ContentInline.Expr(source)       => "${" + source + "}"

  private val contentBlockValueTypeNames: Set[String] =
    Set("Paragraph", "BulletList", "OrderedList", "Image", "Embedded")

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
