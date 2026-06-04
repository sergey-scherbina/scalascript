package scalascript.compiler.plugin.content

import scalascript.ast
import scalascript.backend.spi.{IntrinsicImpl, NativeContextFeatureKeys}
import scalascript.interpreter.{InterpretError, Value}
import scalascript.ir.QualifiedName
import scalascript.plugin.api.PluginNative

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
    QualifiedName("contentToolkitNode") -> PluginNative.evalLegacy { (ctx, args) =>
      val options = args match
        case Nil      => ToolkitOptions()
        case (one: Value) :: Nil => toolkitOptions(one)
        case _        => throw InterpretError("contentToolkitNode([options])")
      ctx.featureGet(NativeContextFeatureKeys.ContentDocument) match
        case Some(doc: ast.DocumentContent) => toolkitDocumentNode(doc, options)
        case _ => throw InterpretError("contentToolkitNode() is only available while running a parsed .ssc module")
    }
  )

  private case class ToolkitOptions(
    includeCode: Boolean = false,
    sectionGap: Int = 16,
    blockGap: Int = 8,
    listGap: Int = 4,
    wrapDocumentInCard: Boolean = false,
    wrapTopLevelSectionsInCards: Boolean = false
  )

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
      wrapTopLevelSectionsInCards = boolField(fields, "wrapTopLevelSectionsInCards", default = false)
    )

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

  private def toolkitDocumentNode(doc: ast.DocumentContent, options: ToolkitOptions): Value =
    val children =
      doc.blocks.map(toolkitBlockNode(_, options)) ++
        doc.sections.map(toolkitSectionNode(_, options, topLevel = true))
    val body = vstackNode(options.sectionGap, children)
    if options.wrapDocumentInCard then cardNode(List(body)) else body

  private def toolkitSectionNode(section: ast.SectionContent, options: ToolkitOptions, topLevel: Boolean): Value =
    val children =
      headingNode(section.level, section.title) ::
        (section.blocks.map(toolkitBlockNode(_, options)) ++
          section.children.map(toolkitSectionNode(_, options, topLevel = false)))
    val stack = vstackNode(options.blockGap, children)
    if topLevel && options.wrapTopLevelSectionsInCards then cardNode(List(stack)) else stack

  private def toolkitBlockNode(block: ast.ContentBlock, options: ToolkitOptions): Value = block match
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
    case ast.ContentBlock.Embedded(lang, source, _, _, _) =>
      if options.includeCode then
        val label = if lang.isEmpty then "" else s"$lang\n"
        rawTextNode(label + source)
      else fragmentNode(Nil)

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

  private def vstackNode(gap: Int, children: List[Value]): Value =
    instanceValue("VStackNode",
      "gap" -> Value.intV(gap.toLong),
      "children" -> Value.ListV(children)
    )

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

  private def fragmentNode(children: List[Value]): Value =
    instanceValue("FragmentNode",
      "children" -> Value.ListV(children)
    )

  private def cardNode(body: List[Value]): Value =
    instanceValue("CardNode",
      "header" -> Value.NullV,
      "body" -> Value.ListV(body),
      "footer" -> Value.NullV
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
