package ssc.plugin.content

import ssc.{Prims, Show, Value}
import ssc.plugin.{NativeContentModule, NativePlugin, NativePluginContext}

/** Read-only native exposure for content values projected by self-hosted .ssc. */
final class ContentNativePlugin extends NativePlugin:
  def id: String = "35-content"

  private val nil: Value = Value.DataV("Nil", Vector.empty)
  private val none: Value = Value.DataV("None", Vector.empty)

  private def list(values: IterableOnce[Value]): Value =
    values.iterator.toList.foldRight(nil)((head, tail) => Value.DataV("Cons", Vector(head, tail)))

  private def unlist(value: Value): List[Value] = Prims.unlistPub(value)
  private def option(value: Option[Value]): Value = value match
    case Some(inner) => Value.DataV("Some", Vector(inner))
    case None => none

  private def data(value: Value, tag: String, arity: Int): IndexedSeq[Value] = value match
    case Value.DataV(`tag`, fields) if fields.length == arity => fields
    case other => throw new IllegalArgumentException(s"expected $tag/$arity, got ${Show.show(other)}")

  private def attrs(value: Value): collection.mutable.LinkedHashMap[Value, Value] = value match
    case Value.MapV(entries) => entries
    case other => throw new IllegalArgumentException(s"expected content attrs map, got ${Show.show(other)}")

  private def attrString(value: Value, key: String): Option[String] =
    attrs(value).get(Value.StrV(key)).collect {
      case Value.DataV("Str", IndexedSeq(Value.StrV(text))) => text
    }

  private def current(modules: List[NativeContentModule]): NativeContentModule =
    modules.find(_.explicitRoot).getOrElse {
      throw new IllegalStateException("contentDocument() is unavailable: native compilation has no explicit root content")
    }

  private def helperImport(path: String): Boolean =
    path == "std/content.ssc" || path.endsWith("/std/content.ssc") ||
      path == "std/content-bind-core.ssc" || path.endsWith("/std/content-bind-core.ssc") ||
      path == "std/ui/content.ssc" || path.endsWith("/std/ui/content.ssc")

  private def directModules(modules: List[NativeContentModule]): List[NativeContentModule] =
    val root = current(modules)
    root.directImports.filterNot(helperImport).flatMap(path => modules.find(_.source == path))

  private def imported(
      modules: List[NativeContentModule],
      namespace: String,
      fn: String): Option[NativeContentModule] =
    val matches = directModules(modules).filter(_.namespace == namespace)
    matches match
      case Nil => None
      case module :: Nil => Some(module)
      case _ => throw new IllegalArgumentException(s"$fn: duplicate imported content namespace '$namespace'")

  private def sections(document: Value): List[Value] =
    unlist(data(document, "DocumentContent", 6)(4))

  private def blocks(documentOrSection: Value): List[Value] = documentOrSection match
    case Value.DataV("DocumentContent", fields) if fields.length == 6 => unlist(fields(5))
    case Value.DataV("SectionContent", fields) if fields.length == 6 => unlist(fields(4))
    case other => throw new IllegalArgumentException(s"expected document or section, got ${Show.show(other)}")

  private def sectionChildren(section: Value): List[Value] =
    unlist(data(section, "SectionContent", 6)(5))

  private def findSection(values: List[Value], id: String): Option[Value] = values match
    case Nil => None
    case section :: tail =>
      val fields = data(section, "SectionContent", 6)
      fields.head match
        case Value.StrV(`id`) => Some(section)
        case _ => findSection(sectionChildren(section), id).orElse(findSection(tail, id))

  private def findBlockIn(values: List[Value], id: String): Option[Value] = values match
    case Nil => None
    case block :: tail =>
      val blockAttrs = block match
        case Value.DataV("Paragraph" | "BulletList", IndexedSeq(_, value)) => Some(value)
        case Value.DataV("OrderedList", IndexedSeq(_, _, value)) => Some(value)
        case Value.DataV("Image" | "Table", IndexedSeq(_, _, _, value)) => Some(value)
        case Value.DataV("Embedded", IndexedSeq(_, _, _, _, value)) => Some(value)
        case _ => None
      blockAttrs.flatMap(attrString(_, "id")) match
        case Some(`id`) => Some(block)
        case _ => findBlockIn(tail, id)

  private def findBlockSections(values: List[Value], id: String): Option[Value] = values match
    case Nil => None
    case section :: tail =>
      findBlockIn(blocks(section), id)
        .orElse(findBlockSections(sectionChildren(section), id))
        .orElse(findBlockSections(tail, id))

  private def findBlock(document: Value, id: String): Option[Value] =
    findBlockIn(blocks(document), id).orElse(findBlockSections(sections(document), id))

  private def blockData(block: Value): Option[Value] = block match
    case Value.DataV("Embedded", IndexedSeq(_, _, _, Value.DataV("Some", IndexedSeq(value)), _)) => Some(value)
    case _ => None

  private def contentMap(value: Value): Option[collection.mutable.LinkedHashMap[Value, Value]] = value match
    case Value.DataV("MapV", IndexedSeq(Value.MapV(entries))) => Some(entries)
    case _ => None

  private def metadata(document: Value, path: String): Option[Value] =
    val manifest = data(document, "DocumentContent", 6).head
    val start = contentMap(manifest).flatMap(_.get(Value.StrV("content")))
    path.split('.').toList.filter(_.nonEmpty).foldLeft(start) { (current, part) =>
      current.flatMap(contentMap).flatMap(_.get(Value.StrV(part)))
    }

  private def inlineText(value: Value): String = value match
    case Value.DataV("Text" | "Code", IndexedSeq(Value.StrV(text))) => text
    case Value.DataV("Expr", IndexedSeq(Value.StrV(source))) => "${" + source + "}"
    case Value.DataV("Emphasis" | "Strong", IndexedSeq(children)) => unlist(children).map(inlineText).mkString
    case Value.DataV("Link", IndexedSeq(label, _, _)) => unlist(label).map(inlineText).mkString
    case _ => ""

  private def inlineMarkdown(value: Value): String = value match
    case Value.DataV("Text", IndexedSeq(Value.StrV(text))) => text
    case Value.DataV("Code", IndexedSeq(Value.StrV(text))) => s"`$text`"
    case Value.DataV("Expr", IndexedSeq(Value.StrV(source))) => "${" + source + "}"
    case Value.DataV("Emphasis", IndexedSeq(children)) => s"*${unlist(children).map(inlineMarkdown).mkString}*"
    case Value.DataV("Strong", IndexedSeq(children)) => s"**${unlist(children).map(inlineMarkdown).mkString}**"
    case Value.DataV("Link", IndexedSeq(label, Value.StrV(href), title)) =>
      val suffix = title match
        case Value.DataV("Some", IndexedSeq(Value.StrV(text))) => s" \"$text\""
        case _ => ""
      s"[${unlist(label).map(inlineMarkdown).mkString}]($href$suffix)"
    case _ => ""

  private def attrEntries(value: Value): List[(String, String)] =
    attrs(value).iterator.collect {
      case (Value.StrV(key), Value.DataV("Str", IndexedSeq(Value.StrV(text)))) => key -> text
    }.toList

  private def headingAttrs(value: Value): String =
    val entries = attrEntries(value)
    if entries.isEmpty then ""
    else
      val rendered = entries.map {
        case ("id", text) => s"#$text"
        case (key, text) => s"$key=$text"
      }.mkString(" ")
      s" {$rendered}"

  private def metadataPrefix(value: Value): String =
    val entries = attrEntries(value)
    if entries.isEmpty then ""
    else s"<!-- @meta ${entries.map { case (key, text) => s"$key=$text" }.mkString(" ")} -->\n"

  private def fenceAttrs(value: Value): String =
    val entries = attrEntries(value)
    if entries.isEmpty then ""
    else entries.map {
      case ("id", text) => s"@id=$text"
      case (key, text) => s"@$key=$text"
    }.mkString(" ", " ", "")

  private def plainBlock(value: Value): String = value match
    case Value.DataV("Paragraph", IndexedSeq(inlines, _)) => unlist(inlines).map(inlineText).mkString
    case Value.DataV("BulletList" | "OrderedList", IndexedSeq(items, _*)) =>
      unlist(items).map(item => unlist(item).map(plainBlock).mkString(" ")).mkString("\n")
    case Value.DataV("Image", IndexedSeq(_, Value.StrV(alt), _, _)) => alt
    case Value.DataV("Table", IndexedSeq(headers, rows, _, _)) =>
      val header = unlist(headers).map(cell => unlist(cell).map(inlineText).mkString).mkString(" | ")
      val body = unlist(rows).map(row => unlist(row).map(cell => unlist(cell).map(inlineText).mkString).mkString(" | "))
      (header :: body).mkString("\n")
    case Value.DataV("Embedded", IndexedSeq(_, Value.StrV(source), _, _, _)) => source
    case _ => ""

  private def plain(value: Value): String = value match
    case Value.DataV("SectionContent", fields) if fields.length == 6 =>
      val title = fields(2).asInstanceOf[Value.StrV].s
      (title :: blocks(value).map(plainBlock) ++ sectionChildren(value).map(plain)).filter(_.nonEmpty).mkString("\n")
    case Value.DataV("DocumentContent", _) =>
      (blocks(value).map(plainBlock) ++ sections(value).map(plain)).filter(_.nonEmpty).mkString("\n")
    case _ => plainBlock(value)

  private def markdownBlock(value: Value): String = value match
    case Value.DataV("Paragraph", IndexedSeq(inlines, blockAttrs)) =>
      metadataPrefix(blockAttrs) + unlist(inlines).map(inlineMarkdown).mkString
    case Value.DataV("BulletList", IndexedSeq(items, blockAttrs)) =>
      metadataPrefix(blockAttrs) + unlist(items).map(item => s"- ${unlist(item).map(markdownBlock).mkString(" ")}").mkString("\n")
    case Value.DataV("OrderedList", IndexedSeq(items, Value.IntV(start), blockAttrs)) =>
      metadataPrefix(blockAttrs) + unlist(items).zipWithIndex.map { case (item, index) =>
        s"${start + index}. ${unlist(item).map(markdownBlock).mkString(" ")}"
      }.mkString("\n")
    case Value.DataV("Image", IndexedSeq(Value.StrV(src), Value.StrV(alt), title, blockAttrs)) =>
      val titleText = title match
        case Value.DataV("Some", IndexedSeq(Value.StrV(text))) => s" \"$text\""
        case _ => ""
      metadataPrefix(blockAttrs) + s"![$alt]($src$titleText)"
    case Value.DataV("Table", IndexedSeq(headers, rows, alignments, blockAttrs)) =>
      val header = unlist(headers).map(cell => unlist(cell).map(inlineMarkdown).mkString)
      val aligns = unlist(alignments).map {
        case Value.StrV("left") => ":---"
        case Value.StrV("right") => "---:"
        case Value.StrV("center") => ":---:"
        case _ => "---"
      }
      val body = unlist(rows).map(row => s"| ${unlist(row).map(cell => unlist(cell).map(inlineMarkdown).mkString).mkString(" | ")} |")
      metadataPrefix(blockAttrs) +
        (s"| ${header.mkString(" | ")} |" :: s"| ${aligns.mkString(" | ")} |" :: body).mkString("\n")
    case Value.DataV("Embedded", IndexedSeq(Value.StrV(lang), Value.StrV(source), _, _, blockAttrs)) =>
      s"```$lang${fenceAttrs(blockAttrs)}\n$source\n```"
    case other => throw new IllegalArgumentException(s"contentToMarkdown: unsupported value ${Show.show(other)}")

  private def markdown(value: Value): String = value match
    case Value.DataV("SectionContent", fields) if fields.length == 6 =>
      val level = fields(1).asInstanceOf[Value.IntV].n.toInt
      val title = fields(2).asInstanceOf[Value.StrV].s
      val heading = "#" * level + " " + title + headingAttrs(fields(3))
      (heading :: blocks(value).map(markdownBlock) ++ sectionChildren(value).map(markdown)).mkString("\n\n")
    case Value.DataV("DocumentContent", _) =>
      (blocks(value).map(markdownBlock) ++ sections(value).map(markdown)).mkString("\n\n")
    case _ => markdownBlock(value)

  private def native(context: NativePluginContext, name: String, arity: Int)(fn: List[Value] => Value): Unit =
    context.register(name)(fn)
    context.registerGlobal(name, arity)(fn)

  def install(context: NativePluginContext): Unit =
    val modules = context.contentModules
    def rootDocument: Value = current(modules).document
    def moduleDocument(namespace: String, fn: String): Option[Value] = imported(modules, namespace, fn).map(_.document)

    native(context, "contentDocument", 0) {
      case Nil => rootDocument
      case _ => throw new IllegalArgumentException("contentDocument()")
    }
    native(context, "contentCurrentSection", 0) { _ =>
      throw new IllegalStateException("contentCurrentSection() is unavailable on native 2.1 without source-aware call identity")
    }
    native(context, "contentSection", 1) {
      case Value.StrV(id) :: Nil => option(findSection(sections(rootDocument), id))
      case _ => throw new IllegalArgumentException("contentSection(id)")
    }
    native(context, "contentBlock", 1) {
      case Value.StrV(id) :: Nil => option(findBlock(rootDocument, id))
      case _ => throw new IllegalArgumentException("contentBlock(id)")
    }
    native(context, "contentData", 1) {
      case Value.StrV(id) :: Nil => option(findBlock(rootDocument, id).flatMap(blockData))
      case _ => throw new IllegalArgumentException("contentData(id)")
    }
    native(context, "contentMetadata", 1) {
      case Value.StrV(path) :: Nil => option(metadata(rootDocument, path))
      case _ => throw new IllegalArgumentException("contentMetadata(path)")
    }
    native(context, "contentPlainText", 1) {
      case value :: Nil => Value.StrV(plain(value))
      case _ => throw new IllegalArgumentException("contentPlainText(value)")
    }
    native(context, "contentToMarkdown", 1) {
      case value :: Nil => Value.StrV(markdown(value))
      case _ => throw new IllegalArgumentException("contentToMarkdown(value)")
    }
    native(context, "contentModules", 0) {
      case Nil => Value.MapV.from(directModules(modules).map(module => Value.StrV(module.namespace) -> module.document))
      case _ => throw new IllegalArgumentException("contentModules()")
    }
    native(context, "contentModule", 1) {
      case Value.StrV(namespace) :: Nil => option(moduleDocument(namespace, "contentModule(namespace)"))
      case _ => throw new IllegalArgumentException("contentModule(namespace)")
    }
    native(context, "contentModuleSection", 2) {
      case Value.StrV(namespace) :: Value.StrV(id) :: Nil =>
        option(moduleDocument(namespace, "contentModuleSection(namespace, id)").flatMap(doc => findSection(sections(doc), id)))
      case _ => throw new IllegalArgumentException("contentModuleSection(namespace, id)")
    }
    native(context, "contentModuleBlock", 2) {
      case Value.StrV(namespace) :: Value.StrV(id) :: Nil =>
        option(moduleDocument(namespace, "contentModuleBlock(namespace, id)").flatMap(findBlock(_, id)))
      case _ => throw new IllegalArgumentException("contentModuleBlock(namespace, id)")
    }
    native(context, "contentModuleData", 2) {
      case Value.StrV(namespace) :: Value.StrV(id) :: Nil =>
        option(moduleDocument(namespace, "contentModuleData(namespace, id)").flatMap(findBlock(_, id)).flatMap(blockData))
      case _ => throw new IllegalArgumentException("contentModuleData(namespace, id)")
    }
    native(context, "contentModuleMetadata", 2) {
      case Value.StrV(namespace) :: Value.StrV(path) :: Nil =>
        option(moduleDocument(namespace, "contentModuleMetadata(namespace, path)").flatMap(metadata(_, path)))
      case _ => throw new IllegalArgumentException("contentModuleMetadata(namespace, path)")
    }
    context.registerFields("DocumentContent", Vector("manifest", "title", "description", "attrs", "sections", "blocks"))
    context.registerFields("SectionContent", Vector("id", "level", "title", "attrs", "blocks", "children"))
    context.registerFields("Paragraph", Vector("inlines", "attrs"))
    context.registerFields("BulletList", Vector("items", "attrs"))
    context.registerFields("OrderedList", Vector("items", "start", "attrs"))
    context.registerFields("Image", Vector("src", "alt", "title", "attrs"))
    context.registerFields("Table", Vector("headers", "rows", "alignments", "attrs"))
    context.registerFields("Embedded", Vector("lang", "source", "kind", "data", "attrs"))
    context.registerFields("Text", Vector("value"))
    context.registerFields("Emphasis", Vector("children"))
    context.registerFields("Strong", Vector("children"))
    context.registerFields("Code", Vector("value"))
    context.registerFields("Link", Vector("label", "href", "title"))
    context.registerFields("Expr", Vector("source"))
    context.registerFields("Str", Vector("value"))
    context.registerFields("Bool", Vector("value"))
    context.registerFields("Num", Vector("value"))
    context.registerFields("ListV", Vector("values"))
    context.registerFields("MapV", Vector("values"))
