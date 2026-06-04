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
    }
  )

  private def documentValue(doc: ast.DocumentContent): Value =
    Value.InstanceV("DocumentContent", Map(
      "manifest"    -> contentValue(doc.manifest),
      "title"       -> optionString(doc.title),
      "description" -> optionString(doc.description),
      "attrs"       -> attrsValue(doc.attrs),
      "sections"    -> Value.ListV(doc.sections.map(sectionValue)),
      "blocks"      -> Value.ListV(doc.blocks.map(blockValue))
    ))

  private def sectionValue(section: ast.SectionContent): Value =
    Value.InstanceV("SectionContent", Map(
      "id"       -> Value.StringV(section.id),
      "level"    -> Value.intV(section.level.toLong),
      "title"    -> Value.StringV(section.title),
      "attrs"    -> attrsValue(section.attrs),
      "blocks"   -> Value.ListV(section.blocks.map(blockValue)),
      "children" -> Value.ListV(section.children.map(sectionValue))
    ))

  private def blockValue(block: ast.ContentBlock): Value = block match
    case ast.ContentBlock.Paragraph(inlines, attrs) =>
      Value.InstanceV("Paragraph", Map(
        "inlines" -> Value.ListV(inlines.map(inlineValue)),
        "attrs"   -> attrsValue(attrs)
      ))
    case ast.ContentBlock.BulletList(items, attrs) =>
      Value.InstanceV("BulletList", Map(
        "items" -> Value.ListV(items.map(row => Value.ListV(row.map(blockValue)))),
        "attrs" -> attrsValue(attrs)
      ))
    case ast.ContentBlock.OrderedList(items, start, attrs) =>
      Value.InstanceV("OrderedList", Map(
        "items" -> Value.ListV(items.map(row => Value.ListV(row.map(blockValue)))),
        "start" -> Value.intV(start.toLong),
        "attrs" -> attrsValue(attrs)
      ))
    case ast.ContentBlock.Image(src, alt, title, attrs) =>
      Value.InstanceV("Image", Map(
        "src"   -> Value.StringV(src),
        "alt"   -> Value.StringV(alt),
        "title" -> optionString(title),
        "attrs" -> attrsValue(attrs)
      ))
    case ast.ContentBlock.Embedded(lang, source, kind, data, attrs) =>
      Value.InstanceV("Embedded", Map(
        "lang"   -> Value.StringV(lang),
        "source" -> Value.StringV(source),
        "kind"   -> embeddedKindValue(kind),
        "data"   -> Value.optionV(data.map(contentValue)),
        "attrs"  -> attrsValue(attrs)
      ))

  private def embeddedKindValue(kind: ast.EmbeddedKind): Value =
    Value.InstanceV(kind.toString, Map.empty)

  private def inlineValue(inline: ast.ContentInline): Value = inline match
    case ast.ContentInline.Text(value) =>
      Value.InstanceV("Text", Map("value" -> Value.StringV(value)))
    case ast.ContentInline.Emphasis(children) =>
      Value.InstanceV("Emphasis", Map("children" -> Value.ListV(children.map(inlineValue))))
    case ast.ContentInline.Strong(children) =>
      Value.InstanceV("Strong", Map("children" -> Value.ListV(children.map(inlineValue))))
    case ast.ContentInline.Code(value) =>
      Value.InstanceV("Code", Map("value" -> Value.StringV(value)))
    case ast.ContentInline.Link(label, href, title) =>
      Value.InstanceV("Link", Map(
        "label" -> Value.ListV(label.map(inlineValue)),
        "href"  -> Value.StringV(href),
        "title" -> optionString(title)
      ))
    case ast.ContentInline.Expr(source) =>
      Value.InstanceV("Expr", Map("source" -> Value.StringV(source)))

  private def contentValue(value: ast.ContentValue): Value = value match
    case ast.ContentValue.Str(v)    => Value.InstanceV("Str", Map("value" -> Value.StringV(v)))
    case ast.ContentValue.Bool(v)   => Value.InstanceV("Bool", Map("value" -> Value.boolV(v)))
    case ast.ContentValue.Num(v)    => Value.InstanceV("Num", Map("value" -> Value.doubleV(v)))
    case ast.ContentValue.ListV(vs) => Value.InstanceV("ListV", Map("values" -> Value.ListV(vs.map(contentValue))))
    case ast.ContentValue.MapV(vs)  => Value.InstanceV("MapV", Map("values" -> attrsValue(vs)))
    case ast.ContentValue.NullV     => Value.InstanceV("NullV", Map.empty)

  private def attrsValue(attrs: Map[String, ast.ContentValue]): Value =
    Value.MapV(attrs.map { case (k, v) => Value.StringV(k) -> contentValue(v) })

  private def optionString(value: Option[String]): Value =
    Value.optionV(value.map(Value.StringV.apply))
