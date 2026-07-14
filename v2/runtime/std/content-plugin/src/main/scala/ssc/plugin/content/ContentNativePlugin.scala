package ssc.plugin.content

import ssc.{Prims, Show, Value}
import ssc.plugin.{NativeContentModule, NativePlugin, NativePluginContext}

/** Read-only native exposure for content values projected by self-hosted .ssc. */
final class ContentNativePlugin extends NativePlugin:
  def id: String = "35-content"

  private val nil: Value = Value.DataV("Nil", Vector.empty)
  private val none: Value = Value.DataV("None", Vector.empty)
  // Captured at install; lets the toolkit engine build real signals via the ui plugin's
  // `signal(name, default)` (cross-plugin), refreshed per loadAll (single-threaded run).
  private var pluginCtx: NativePluginContext = null

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

  // Heading attr group `{#id k=v ...}` — the section id (SectionContent field 0)
  // is authoritative; fall back to an `id` attr entry when it was carried in the
  // attrs map (explicit `{#id}` headings). Remaining attrs sort by key, matching
  // v1 ContentIntrinsics.headingAttrGroup. (@meta-derived component/source attrs
  // attach to the section, so a `## Plans` with `<!-- @meta ... -->` renders
  // `## Plans {#plans component=... source=...}`.)
  private def headingAttrs(idField: String, attrsValue: Value): String =
    val entries = attrEntries(attrsValue)
    val id = if idField.nonEmpty then idField else entries.collectFirst { case ("id", text) => text }.getOrElse("")
    val rest = entries.filterNot(_._1 == "id").sortBy(_._1)
    val tokens = (if id.nonEmpty then List(s"#$id") else Nil) ++ rest.map { case (key, text) => s"$key=$text" }
    if tokens.isEmpty then "" else tokens.mkString(" {", " ", "}")

  // ── YAML front-matter (DocumentContent.manifest → `---\n…\n---`) ──────────────
  private def numberString(d: Double): String =
    if d.isWhole && d >= Long.MinValue.toDouble && d <= Long.MaxValue.toDouble then d.toLong.toString
    else d.toString

  private def isSafeBareScalar(v: String): Boolean =
    v.nonEmpty &&
      !Set("true", "false", "null").contains(v.toLowerCase(java.util.Locale.ROOT)) &&
      v.forall(ch => ch.isLetterOrDigit || ch == '-' || ch == '_' || ch == '.' || ch == '/' || ch == ':' || ch == '@')

  private def quoteYaml(v: String): String =
    "\"" + v.flatMap {
      case '\\' => "\\\\"; case '"' => "\\\""; case '\n' => "\\n"
      case '\r' => "\\r"; case '\t' => "\\t"; case ch => ch.toString
    } + "\""

  private def yamlScalar(v: String): String = if isSafeBareScalar(v) then v else quoteYaml(v)

  private def yamlValueScalar(value: Value): String = value match
    case Value.DataV("Str", IndexedSeq(Value.StrV(s)))    => yamlScalar(s)
    case Value.DataV("Bool", IndexedSeq(Value.BoolV(b)))  => b.toString
    case Value.DataV("Num", IndexedSeq(Value.IntV(n)))    => n.toString
    case Value.DataV("Num", IndexedSeq(Value.FloatV(d)))  => numberString(d)
    case Value.DataV("NullV", _)                          => "null"
    case _                                                => quoteYaml(Show.show(value))

  private def yamlMapLines(entries: collection.mutable.LinkedHashMap[Value, Value], indent: Int): List[String] =
    val pad = " " * indent
    entries.toList.collect { case (Value.StrV(k), v) => k -> v }.sortBy(_._1).flatMap {
      case (key, Value.DataV("MapV", IndexedSeq(Value.MapV(inner)))) =>
        if inner.isEmpty then List(s"$pad${yamlScalar(key)}: {}")
        else s"$pad${yamlScalar(key)}:" :: yamlMapLines(inner, indent + 2)
      case (key, Value.DataV("ListV", IndexedSeq(inner))) =>
        val items = unlist(inner)
        if items.isEmpty then List(s"$pad${yamlScalar(key)}: []")
        else s"$pad${yamlScalar(key)}:" :: yamlListLines(items, indent + 2)
      case (key, value) => List(s"$pad${yamlScalar(key)}: ${yamlValueScalar(value)}")
    }

  private def yamlListLines(items: List[Value], indent: Int): List[String] =
    val pad = " " * indent
    items.flatMap {
      case Value.DataV("MapV", IndexedSeq(Value.MapV(inner))) =>
        if inner.isEmpty then List(s"$pad- {}")
        else s"$pad-" :: yamlMapLines(inner, indent + 2)
      case Value.DataV("ListV", IndexedSeq(inner)) =>
        val nested = unlist(inner)
        if nested.isEmpty then List(s"$pad- []")
        else s"$pad-" :: yamlListLines(nested, indent + 2)
      case value => List(s"$pad- ${yamlValueScalar(value)}")
    }

  private def manifestMarkdown(manifest: Value): Option[String] =
    contentMap(manifest) match
      case Some(entries) if entries.nonEmpty =>
        Some("---\n" + yamlMapLines(entries, 0).mkString("\n") + "\n---")
      case _ => None

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
      val id = fields(0) match { case Value.StrV(s) => s; case _ => "" }
      val level = fields(1).asInstanceOf[Value.IntV].n.toInt
      val title = fields(2).asInstanceOf[Value.StrV].s
      val heading = "#" * level + " " + title + headingAttrs(id, fields(3))
      (heading :: blocks(value).map(markdownBlock) ++ sectionChildren(value).map(markdown)).mkString("\n\n")
    case Value.DataV("DocumentContent", fields) if fields.length == 6 =>
      val front = manifestMarkdown(fields(0)).toList
      (front ++ blocks(value).map(markdownBlock) ++ sections(value).map(markdown)).filter(_.nonEmpty).mkString("\n\n")
    case _ => markdownBlock(value)

  private def native(context: NativePluginContext, name: String, arity: Int)(fn: List[Value] => Value): Unit =
    context.register(name)(fn)
    context.registerGlobal(name, arity)(fn)

  // ── content-toolkit: @ui=toolkit YAML controls → TkNode tree (v2-native port) ──
  // Ported from v1 ContentIntrinsics.{toolkitControl,toolkitSectionNode,toolkitBlockNode}.
  // Builds the self-hosted TkNode case-class DataVs (std/ui/nodes.ssc) from a section's
  // controls. Covers the primitive controls; unsupported ones raise a clear error (same
  // as v1). Slots come from ContentToolkitOptions (field index 11). (v2-content-toolkit.)

  // TkNode builders — field order MUST match std/ui/nodes.ssc case classes.
  private def vstackNodeV(gap: Int, children: List[Value]): Value =
    Value.DataV("VStackNode", Vector(Value.IntV(gap), list(children)))
  private def hstackNodeV(gap: Int, children: List[Value], wrap: Boolean): Value =
    Value.DataV("HStackNode", Vector(Value.IntV(gap), list(children), Value.BoolV(wrap)))
  private def headingNodeV(level: Int, text: String): Value =
    Value.DataV("HeadingNode", Vector(Value.IntV(level), Value.StrV(text)))
  private def textNodeV(text: String): Value =
    Value.DataV("TextNode_", Vector(Value.StrV(text)))
  private def badgeNodeV(content: String, variant: String): Value =
    Value.DataV("BadgeNode", Vector(Value.StrV(content), Value.StrV(variant)))
  private def dividerNodeV: Value = Value.DataV("DividerNode", Vector.empty)
  private def fragmentNodeV(children: List[Value]): Value =
    Value.DataV("FragmentNode", Vector(list(children)))

  // ContentValue decode: a parsed YAML value is DataV("Str"|"Num"|"Bool"|"MapV"|"ListV", …).
  private def cvMap(cv: Value): Option[collection.mutable.LinkedHashMap[Value, Value]] = cv match
    case Value.DataV("MapV", IndexedSeq(Value.MapV(entries))) => Some(entries)
    case _ => None
  private def cvList(cv: Value): List[Value] = cv match
    case Value.DataV("ListV", IndexedSeq(inner)) => unlist(inner)
    case _ => Nil
  private def cvString(cv: Value): Option[String] = cv match
    case Value.DataV("Str", IndexedSeq(Value.StrV(s))) => Some(s)
    case Value.DataV("Num", IndexedSeq(Value.StrV(s))) => Some(s)
    case _ => None
  private def cvInt(cv: Value): Option[Int] = cv match
    case Value.DataV("Num", IndexedSeq(Value.IntV(n)))   => Some(n.toInt)
    case Value.DataV("Num", IndexedSeq(Value.FloatV(d))) => Some(d.toInt)
    case Value.DataV("Num", IndexedSeq(Value.StrV(s)))   => s.toDoubleOption.map(_.toInt)
    case Value.DataV("Str", IndexedSeq(Value.StrV(s)))   => s.toIntOption
    case _ => None
  private def mfield(m: collection.mutable.LinkedHashMap[Value, Value], key: String): Option[Value] =
    m.get(Value.StrV(key))
  private def mString(m: collection.mutable.LinkedHashMap[Value, Value], key: String, default: String): String =
    mfield(m, key).flatMap(cvString).getOrElse(default)
  private def mStringFirst(m: collection.mutable.LinkedHashMap[Value, Value], keys: String*): Option[String] =
    keys.iterator.flatMap(k => mfield(m, k).flatMap(cvString)).nextOption()
  private def mInt(m: collection.mutable.LinkedHashMap[Value, Value], key: String, default: Int): Int =
    mfield(m, key).flatMap(cvInt).getOrElse(default)

  /** ContentToolkitOptions is DataV("ContentToolkitOptions", [11 fields…, slots]) — slots
   *  (field index 11) is a MapV(slotId -> TkNode). Returns the slot map or empty. */
  private def optionBlockGap(options: Value): Int = options match
    case Value.DataV("ContentToolkitOptions", fields) if fields.length >= 3 =>
      fields(2) match { case Value.IntV(n) => n.toInt; case _ => 8 }
    case _ => 8

  /** Flatten a registry map to (name -> value), tolerating both the canonical
   *  `{StrV -> v}` and v2's `{pair -> pair}` form (Map(pair) doesn't destructure). */
  private def pairMapPairs(entries: collection.mutable.LinkedHashMap[Value, Value]): List[(String, Value)] =
    entries.iterator.flatMap { case (k, v) =>
      List(k, v).collectFirst { case Value.DataV(t, IndexedSeq(Value.StrV(name), node)) if t.startsWith("Tuple") => name -> node }
        .orElse(k match { case Value.StrV(name) => Some(name -> v); case _ => None })
    }.toList

  /** All name->value entries across EVERY option Map field (slots / actions / computed /
   *  rowBindings), merged into one registry. Robust to a v2 native-frontend named-arg bug
   *  that field-SCRAMBLES ContentToolkitOptions (a named `computed=`/`rowBindings=` arg lands
   *  in the wrong positional slot); registry NAMES are distinct across kinds, so collecting
   *  from all fields recovers slots/actions/signals regardless of which slot the map ended up
   *  in. (v2-content-toolkit; the real fix is the native-frontend named-arg default-fill.) */
  private def optionRegistry(options: Value): Map[String, Value] = options match
    case Value.DataV("ContentToolkitOptions", fields) =>
      fields.toList.flatMap { case Value.MapV(e) => pairMapPairs(e); case _ => Nil }.toMap
    case _ => Map.empty

  private def resolveSlot(options: Value, slotId: String): Option[Value] =
    optionRegistry(options).get(slotId)

  private def cvBool(cv: Value): Option[Boolean] = cv match
    case Value.DataV("Bool", IndexedSeq(Value.BoolV(b))) => Some(b)
    case _ => None
  private def cvToRuntime(cv: Value): Value = cv match
    case Value.DataV("Str" | "Bool" | "Num", IndexedSeq(inner)) => inner
    case _ => Value.UnitV

  /** A YAML-declared signal — built via the ui plugin's `signal(name, default)` (a real
   *  NativeUiSignal the toolkit controls / formBody expect), resolved cross-plugin. */
  private def reactiveSignal(name: String, cv: Value): Value =
    val initial = cv match
      case Value.DataV("Str", IndexedSeq(s @ Value.StrV(_)))    => s
      case Value.DataV("Bool", IndexedSeq(b @ Value.BoolV(_)))  => b
      case Value.DataV("Num", IndexedSeq(n @ Value.IntV(_)))    => n
      case Value.DataV("Num", IndexedSeq(d @ Value.FloatV(_)))  => d
      case Value.DataV("Num", IndexedSeq(Value.StrV(s))) =>
        s.toLongOption.map(Value.IntV(_)).orElse(s.toDoubleOption.map(Value.FloatV(_))).getOrElse(Value.StrV(s))
      case _ => Value.StrV("")
    Option(pluginCtx).flatMap(_.resolveGlobal("signal")) match
      case Some(signalFn) => pluginCtx.invoke(signalFn, List(Value.StrV(name), initial))
      case None => Value.ForeignV(Array[Value](initial))  // fallback if the ui plugin isn't loaded

  private def buildSignals(blockDataMap: collection.mutable.LinkedHashMap[Value, Value]): Map[String, Value] =
    mfield(blockDataMap, "signals").flatMap(cvMap) match
      case Some(sigMap) => sigMap.iterator.collect { case (Value.StrV(name), cv) => name -> reactiveSignal(name, cv) }.toMap
      case None => Map.empty

  private def signalByName(signals: Map[String, Value], name: String, context: String): Value =
    signals.getOrElse(name, throw new IllegalArgumentException(
      s"contentToolkitNode: $context signal '$name' is not declared (available: ${if signals.isEmpty then "<none>" else signals.keys.toList.sorted.mkString(",")})"))

  private def toolkitButton(m: collection.mutable.LinkedHashMap[Value, Value], options: Value,
      signals: Map[String, Value], label: String, disabled: Boolean): Value =
    mfield(m, "action").flatMap(cvString) match
      case Some(actionId) =>
        val handler = optionRegistry(options).get(actionId).getOrElse(
          throw new IllegalArgumentException(s"contentToolkitNode: button action '$actionId' is not registered"))
        Value.DataV("ActionButtonNode", Vector(handler, Value.StrV(label), Value.BoolV(disabled), Value.StrV("primary"), Value.StrV("md")))
      case None =>
        val sig = signalByName(signals, mString(m, "signal", ""), "button")
        val value = mfield(m, "value").map(cvToRuntime).getOrElse(Value.BoolV(true))
        Value.DataV("SignalButtonNode", Vector(sig, value, Value.StrV(label), Value.BoolV(disabled), Value.StrV("primary"), Value.StrV("md")))

  /** Build a TkNode from a `@ui=toolkit` control. `signals` is the block's declared signal env. */
  private def toolkitControl(cv: Value, options: Value, signals: Map[String, Value]): Value =
    cv match
      case Value.DataV("ListV", IndexedSeq(inner)) =>
        fragmentNodeV(unlist(inner).map(toolkitControl(_, options, signals)))
      case _ =>
        val m = cvMap(cv).getOrElse(throw new IllegalArgumentException("contentToolkitNode: control must be a map"))
        val kind = mString(m, "type", "").trim.toLowerCase
        def children: List[Value] =
          mfield(m, "children").map(cvList).getOrElse(Nil).map(toolkitControl(_, options, signals))
        def sigRef(names: String*): Value =
          val nm = names.iterator.flatMap(k => mfield(m, k).flatMap(cvString)).nextOption()
            .getOrElse(throw new IllegalArgumentException(s"contentToolkitNode: $kind requires ${names.mkString(" or ")}"))
          signalByName(signals, nm, kind)
        val label = mString(m, "label", "")
        val disabled = mfield(m, "disabled").flatMap(cvBool).getOrElse(false)
        kind match
          case "vstack"              => vstackNodeV(mInt(m, "gap", optionBlockGap(options)), children)
          case "hstack"              => hstackNodeV(mInt(m, "gap", optionBlockGap(options)), children, false)
          case "fragment"            => fragmentNodeV(children)
          case "divider"             => dividerNodeV
          case "heading"             => headingNodeV(mInt(m, "level", 2), mString(m, "text", ""))
          case "text" | "paragraph"  => textNodeV(mString(m, "text", ""))
          case "rawtext"             => Value.DataV("RawTextNode", Vector(Value.StrV(mString(m, "text", ""))))
          case "badge"               => badgeNodeV(mStringFirst(m, "content", "text").getOrElse(""), mString(m, "variant", "default"))
          case "signaltext"          => Value.DataV("SignalTextNode", Vector(sigRef("signal", "value")))
          case "textfield" | "input" =>
            Value.DataV("TextFieldNode", Vector(sigRef("signal", "value"), Value.StrV(label), Value.BoolV(disabled),
              Value.BoolV(mfield(m, "required").flatMap(cvBool).getOrElse(false))))
          case "checkbox" =>
            Value.DataV("CheckboxNode", Vector(sigRef("signal", "checked"), Value.StrV(label), Value.BoolV(disabled)))
          case "button" | "signalbutton" => toolkitButton(m, options, signals, label, disabled)
          case "show" | "showwhen" =>
            Value.DataV("ShowWhenNode", Vector(
              sigRef("signal", "condition"),
              mfield(m, "then").orElse(mfield(m, "whenTrue")).map(toolkitControl(_, options, signals)).getOrElse(fragmentNodeV(Nil)),
              mfield(m, "else").orElse(mfield(m, "whenFalse")).map(toolkitControl(_, options, signals)).getOrElse(fragmentNodeV(Nil))))
          case "card" =>
            Value.DataV("CardNode", Vector(list(Nil), list(children), list(Nil)))
          case "slot" =>
            val slotId = mString(m, "id", "")
            resolveSlot(options, slotId).getOrElse(
              throw new IllegalArgumentException(s"contentToolkitNode: slot '$slotId' is not registered"))
          case "table" =>
            // {type: table, source: <id>} binds to a ContentRowBinding(rows, columns, actions)
            // registered under <id> → DataTableNode(rows, columns, actions, rowKeyPath="id").
            val regionId = mStringFirst(m, "source", "rows").getOrElse(
              throw new IllegalArgumentException("contentToolkitNode: table requires source or rows"))
            optionRegistry(options).get(regionId) match
              case Some(Value.DataV("ContentRowBinding", fs)) if fs.length >= 2 =>
                Value.DataV("DataTableNode", Vector(fs(0), fs(1), if fs.length >= 3 then fs(2) else list(Nil), Value.StrV("id")))
              case _ =>
                throw new IllegalArgumentException(s"contentToolkitNode: table source '$regionId' is not registered")
          case other =>
            throw new IllegalArgumentException(s"contentToolkitNode: unsupported control type '$other'")

  /** A `@ui=toolkit` embedded block: attrs carry `ui=toolkit`; its parsed data has `controls`. */
  private def isToolkitUiBlock(block: Value): Boolean = block match
    case Value.DataV("Embedded", IndexedSeq(_, _, _, _, blockAttrs)) =>
      attrString(blockAttrs, "ui").contains("toolkit")
    case _ => false

  private def toolkitBlockNode(block: Value, options: Value): Option[Value] =
    if isToolkitUiBlock(block) then
      blockData(block).flatMap(cvMap).map { bd =>
        // YAML-declared signals override code-registered `computed` signals (from the merged
        // option registry); slot/action nodes in the registry are harmless here (name-keyed).
        val signals = optionRegistry(options) ++ buildSignals(bd)
        mfield(bd, "controls").map(toolkitControl(_, options, signals)).getOrElse(fragmentNodeV(Nil))
      }
    else None  // non-toolkit blocks are omitted from the toolkit tree by default (includeCode=false)

  private def toolkitSectionNode(section: Value, options: Value): Value =
    val fields = data(section, "SectionContent", 6)
    val level  = fields(1) match { case Value.IntV(n) => n.toInt; case _ => 2 }
    val title  = fields(2) match { case Value.StrV(s) => s; case _ => "" }
    val blockNodes = unlist(fields(4)).flatMap(b => toolkitBlockNode(b, options))
    val childNodes = unlist(fields(5)).map(s => toolkitSectionNode(s, options))
    vstackNodeV(optionBlockGap(options), headingNodeV(level, title) :: (blockNodes ++ childNodes))

  private def toolkitSectionById(document: Value, id: String, options: Value): Value =
    findSection(sections(document), id) match
      case Some(section) => toolkitSectionNode(section, options)
      case None => throw new IllegalArgumentException(s"contentToolkitSection: no section with id '$id'")

  def install(context: NativePluginContext): Unit =
    pluginCtx = context
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
    // contentToolkitSection(id, options): builds the section's @ui=toolkit control tree
    // into a TkNode (v2-content-toolkit). Options carries the slot registry.
    native(context, "contentToolkitSection", 2) {
      case Value.StrV(id) :: options :: Nil => toolkitSectionById(rootDocument, id, options)
      case Value.StrV(id) :: Nil            => toolkitSectionById(rootDocument, id, Value.UnitV)
      case _ => throw new IllegalArgumentException("contentToolkitSection(id, options)")
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
