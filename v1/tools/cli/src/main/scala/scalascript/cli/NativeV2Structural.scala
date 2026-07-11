package scalascript.cli

import _root_.ssc.{IrDecode, Program, Prims, Value}
import _root_.ssc.Value.*
import _root_.ssc.plugin.{NativeContentModule, NativeDatabaseConfig, NativeRuntimeConfig}

/** Target-independent manifest product decoded from the frozen frontend ABI.
 *  This is a structural mapping from `ssc.Value`; it does not parse YAML. */
private[cli] sealed trait NativeManifestValue
private[cli] case object NativeManifestNull extends NativeManifestValue
private[cli] final case class NativeManifestBool(value: Boolean) extends NativeManifestValue
private[cli] final case class NativeManifestInteger(raw: String) extends NativeManifestValue
private[cli] final case class NativeManifestDecimal(raw: String) extends NativeManifestValue
private[cli] final case class NativeManifestString(value: String) extends NativeManifestValue
private[cli] final case class NativeManifestArray(items: List[NativeManifestValue]) extends NativeManifestValue
private[cli] final case class NativeManifestObject(fields: List[(String, NativeManifestValue)]) extends NativeManifestValue

private[cli] final case class NativeSourceManifest(
    source: java.io.File,
    value: Option[NativeManifestValue])

private[cli] final case class NativeStructuralFrontend(
    program: Program,
    manifests: List[NativeSourceManifest],
    contentModules: List[NativeContentModule],
    config: NativeRuntimeConfig)

/** Decoder for
 *  `NativeCompilation(IrProg, List[Option[YamlResult]], List[MarkdownResult], paths)`.
 *  CoreIR, YAML, and Markdown are already structural values when they cross
 *  this boundary; the permanent Scala seed only validates and maps them. */
private[cli] object NativeV2Structural:
  def decode(value: Value, roots: List[java.io.File]): NativeStructuralFrontend = value match
    case DataV("NativeCompilation", IndexedSeq(
          programValue, manifestValues, markdownValues, sourceValues)) =>
      val sources = list(sourceValues).map {
        case StrV(path) => path
        case other => abiError(s"source identity is not a string: ${show(other)}")
      }
      if sources.length != roots.length then
        abiError(s"source count ${sources.length} does not match root count ${roots.length}")
      roots.zip(sources).foreach { case (root, actual) =>
        val expected = portablePath(root.getCanonicalFile)
        if actual != expected then
          abiError(s"source identity mismatch: expected $expected, got $actual")
      }
      val rawManifests = list(manifestValues)
      if rawManifests.length != roots.length then
        abiError(s"manifest count ${rawManifests.length} does not match root count ${roots.length}")
      val manifests = roots.zip(rawManifests).map { case (root, raw) =>
        NativeSourceManifest(root, decodeResult(raw, portablePath(root)))
      }
      val contentModules = list(markdownValues).map(decodeContentModule)
      val expectedRoots = roots.map(root => portablePath(root.getCanonicalFile))
      val actualRoots = contentModules.collect { case module if module.explicitRoot => module.source }
      if actualRoots != expectedRoots then
        abiError(
          s"content root identities ${actualRoots.mkString("[", ", ", "]")} " +
            s"do not match roots ${expectedRoots.mkString("[", ", ", "]")}")
      val duplicateSources = contentModules.groupBy(_.source).collect {
        case (source, modules) if modules.lengthCompare(1) > 0 => source
      }.toList.sorted
      if duplicateSources.nonEmpty then
        abiError(s"duplicate content module source(s): ${duplicateSources.mkString(", ")}")
      val config = runtimeConfig(manifests).copy(contentModules = contentModules)
      NativeStructuralFrontend(
        program = IrDecode.program(programValue),
        manifests = manifests,
        contentModules = contentModules,
        config = config)
    case other => abiError(s"expected NativeCompilation/4, got ${show(other)}")

  private def decodeResult(value: Value, source: String): Option[NativeManifestValue] = value match
    case DataV("None", _) => None
    case DataV("Some", IndexedSeq(DataV("YamlOk", IndexedSeq(yaml, IntV(_))))) =>
      Some(decodeYaml(yaml))
    case DataV("Some", IndexedSeq(DataV("YamlErr", IndexedSeq(
          StrV(message), IntV(_), IntV(line), IntV(column))))) =>
      throw new IllegalArgumentException(s"$source:$line:$column: $message")
    case other => abiError(s"bad manifest result for $source: ${show(other)}")

  private def decodeYaml(value: Value): NativeManifestValue = value match
    case DataV("YamlNull", _)                    => NativeManifestNull
    case DataV("YamlBool", IndexedSeq(BoolV(v)))=> NativeManifestBool(v)
    case DataV("YamlInteger", IndexedSeq(StrV(v))) => NativeManifestInteger(v)
    case DataV("YamlDecimal", IndexedSeq(StrV(v))) => NativeManifestDecimal(v)
    case DataV("YamlString", IndexedSeq(StrV(v)))  => NativeManifestString(v)
    case DataV("YamlArray", IndexedSeq(items)) =>
      NativeManifestArray(list(items).map(decodeYaml))
    case DataV("YamlObject", IndexedSeq(fields)) =>
      NativeManifestObject(list(fields).map {
        case DataV("YamlField", IndexedSeq(StrV(key), fieldValue)) => key -> decodeYaml(fieldValue)
        case other => abiError(s"bad YAML field: ${show(other)}")
      })
    case other => abiError(s"bad YAML value: ${show(other)}")

  private def decodeContentModule(value: Value): NativeContentModule = value match
    case DataV("NativeContentModule", IndexedSeq(
          StrV(source), BoolV(explicitRoot), imports, StrV(namespace), document)) =>
      val directImports = list(imports).map {
        case StrV(path) => path
        case other => abiError(s"content import in $source is not a string: ${show(other)}")
      }
      if source.isEmpty then abiError("content module has an empty source identity")
      if namespace.isEmpty then abiError(s"content module $source has an empty namespace")
      document match
        case DataV("MarkdownError", IndexedSeq(
              StrV(message), IntV(_), IntV(line), IntV(column))) =>
          throw new IllegalArgumentException(s"$source:$line:$column: $message")
        case _ => validateDocument(document, source)
      NativeContentModule(source, explicitRoot, directImports, namespace, document)
    case other => abiError(s"bad native content module: ${show(other)}")

  private def validateDocument(value: Value, source: String): Unit = value match
    case DataV("DocumentContent", IndexedSeq(manifest, title, description, attrs, sections, blocks)) =>
      validateContentValue(manifest, source)
      validateOptionString(title, s"document title in $source")
      validateOptionString(description, s"document description in $source")
      validateAttrs(attrs, s"document attrs in $source")
      list(sections).foreach(validateSection(_, source))
      list(blocks).foreach(validateBlock(_, source))
    case other => abiError(s"bad DocumentContent in $source: ${show(other)}")

  private def validateSection(value: Value, source: String): Unit = value match
    case DataV("SectionContent", IndexedSeq(
          StrV(_), IntV(_), StrV(_), attrs, blocks, children)) =>
      validateAttrs(attrs, s"section attrs in $source")
      list(blocks).foreach(validateBlock(_, source))
      list(children).foreach(validateSection(_, source))
    case other => abiError(s"bad SectionContent in $source: ${show(other)}")

  private def validateBlock(value: Value, source: String): Unit = value match
    case DataV("Paragraph", IndexedSeq(inlines, attrs)) =>
      list(inlines).foreach(validateInline(_, source)); validateAttrs(attrs, s"paragraph attrs in $source")
    case DataV("BulletList", IndexedSeq(items, attrs)) =>
      list(items).foreach(item => list(item).foreach(validateBlock(_, source)))
      validateAttrs(attrs, s"bullet-list attrs in $source")
    case DataV("OrderedList", IndexedSeq(items, IntV(_), attrs)) =>
      list(items).foreach(item => list(item).foreach(validateBlock(_, source)))
      validateAttrs(attrs, s"ordered-list attrs in $source")
    case DataV("Image", IndexedSeq(StrV(_), StrV(_), title, attrs)) =>
      validateOptionString(title, s"image title in $source"); validateAttrs(attrs, s"image attrs in $source")
    case DataV("Table", IndexedSeq(headers, rows, alignments, attrs)) =>
      list(headers).foreach(cell => list(cell).foreach(validateInline(_, source)))
      list(rows).foreach(row => list(row).foreach(cell => list(cell).foreach(validateInline(_, source))))
      validateStrings(alignments, s"table alignments in $source")
      validateAttrs(attrs, s"table attrs in $source")
    case DataV("Embedded", IndexedSeq(StrV(_), StrV(_), kind, data, attrs)) =>
      kind match
        case DataV("StructuredData" | "Executable" | "StringBlock" | "Opaque", IndexedSeq()) => ()
        case other => abiError(s"bad embedded kind in $source: ${show(other)}")
      validateOption(data, validateContentValue(_, source), s"embedded data in $source")
      validateAttrs(attrs, s"embedded attrs in $source")
    case other => abiError(s"bad ContentBlock in $source: ${show(other)}")

  private def validateInline(value: Value, source: String): Unit = value match
    case DataV("Text" | "Code" | "Expr", IndexedSeq(StrV(_))) => ()
    case DataV("Emphasis" | "Strong", IndexedSeq(children)) =>
      list(children).foreach(validateInline(_, source))
    case DataV("Link", IndexedSeq(label, StrV(_), title)) =>
      list(label).foreach(validateInline(_, source)); validateOptionString(title, s"link title in $source")
    case other => abiError(s"bad ContentInline in $source: ${show(other)}")

  private def validateContentValue(value: Value, source: String): Unit = value match
    case DataV("Str", IndexedSeq(StrV(_))) | DataV("Bool", IndexedSeq(BoolV(_))) |
         DataV("Num", IndexedSeq(FloatV(_))) | DataV("NullV", IndexedSeq()) => ()
    case DataV("ListV", IndexedSeq(values)) => list(values).foreach(validateContentValue(_, source))
    case DataV("MapV", IndexedSeq(values: MapV)) =>
      values.entries.foreach {
        case (StrV(_), fieldValue) => validateContentValue(fieldValue, source)
        case (key, _) => abiError(s"content map in $source has non-string key: ${show(key)}")
      }
    case other => abiError(s"bad ContentValue in $source: ${show(other)}")

  private def validateAttrs(value: Value, label: String): Unit = value match
    case values: MapV => values.entries.foreach {
      case (StrV(_), fieldValue) => validateContentValue(fieldValue, label)
      case (key, _) => abiError(s"$label has non-string key: ${show(key)}")
    }
    case other => abiError(s"$label is not a map: ${show(other)}")

  private def validateOptionString(value: Value, label: String): Unit =
    validateOption(value, {
      case StrV(_) => ()
      case other => abiError(s"$label is not a string: ${show(other)}")
    }, label)

  private def validateOption(value: Value, validate: Value => Unit, label: String): Unit = value match
    case DataV("None", IndexedSeq()) => ()
    case DataV("Some", IndexedSeq(inner)) => validate(inner)
    case other => abiError(s"$label is not an option: ${show(other)}")

  private def validateStrings(value: Value, label: String): Unit =
    list(value).foreach {
      case StrV(_) => ()
      case other => abiError(s"$label contains a non-string: ${show(other)}")
    }

  private def runtimeConfig(manifests: List[NativeSourceManifest]): NativeRuntimeConfig =
    val databases = collection.mutable.LinkedHashMap.empty[String, NativeDatabaseConfig]
    val owners = collection.mutable.HashMap.empty[String, String]
    manifests.foreach { manifest =>
      manifest.value.foreach { rootValue =>
        val root = mapping(rootValue, s"front-matter in ${manifest.source.getPath}")
        field(root, "databases").foreach { databaseValue =>
          val entries = mapping(databaseValue, s"databases in ${manifest.source.getPath}")
          entries.foreach { case (name, rawConfig) =>
            val values = mapping(rawConfig, s"databases.$name in ${manifest.source.getPath}")
            val url = text(values, "url", name, manifest.source).filter(_.nonEmpty).getOrElse {
              throw new IllegalArgumentException(
                s"native database '$name' in ${manifest.source.getPath} requires a non-empty url")
            }
            val config = NativeDatabaseConfig(
              url = url,
              user = text(values, "user", name, manifest.source),
              password = text(values, "password", name, manifest.source),
              driver = text(values, "driver", name, manifest.source))
            databases.get(name) match
              case Some(previous) if previous != config =>
                throw new IllegalArgumentException(
                  s"conflicting native database '$name' in ${owners(name)} and ${manifest.source.getPath}")
              case Some(_) => ()
              case None =>
                databases(name) = config
                owners(name) = manifest.source.getPath
          }
        }
      }
    }
    NativeRuntimeConfig(databases.toMap)

  private def mapping(
      value: NativeManifestValue,
      label: String): List[(String, NativeManifestValue)] = value match
    case NativeManifestObject(fields) => fields
    case _ => throw new IllegalArgumentException(s"$label must be a YAML mapping")

  private def field(
      fields: List[(String, NativeManifestValue)],
      key: String): Option[NativeManifestValue] = fields.collectFirst { case (`key`, value) => value }

  private def text(
      fields: List[(String, NativeManifestValue)],
      key: String,
      database: String,
      source: java.io.File): Option[String] = field(fields, key).map {
    case NativeManifestString(value) => value
    case _ => throw new IllegalArgumentException(
      s"native database '$database' $key in ${source.getPath} must be a String")
  }

  private def list(value: Value): List[Value] = Prims.unlistPub(value)
  private def portablePath(file: java.io.File): String =
    file.getPath.replace(java.io.File.separatorChar, '/')
  private def show(value: Value): String = _root_.ssc.Show.show(value)
  private def abiError(message: String): Nothing =
    throw new IllegalStateException(s"invalid native frontend structural ABI: $message")
