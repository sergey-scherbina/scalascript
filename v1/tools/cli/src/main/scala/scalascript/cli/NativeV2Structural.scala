package scalascript.cli

import _root_.ssc.{IrDecode, Program, Prims, Value}
import _root_.ssc.Value.*
import _root_.ssc.plugin.{NativeDatabaseConfig, NativeRuntimeConfig}

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

/** Validated canonical Markdown product for one root source. The seed retains
 *  only the stable summary needed by the CLI; it never reparses source text. */
private[cli] final case class NativeSourceMarkdown(
    source: java.io.File,
    blockCount: Int)

private[cli] final case class NativeStructuralFrontend(
    program: Program,
    manifests: List[NativeSourceManifest],
    markdown: List[NativeSourceMarkdown],
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
      val rawMarkdown = list(markdownValues)
      if rawMarkdown.length != roots.length then
        abiError(s"Markdown result count ${rawMarkdown.length} does not match root count ${roots.length}")
      val markdown = roots.zip(rawMarkdown).map { case (root, raw) =>
        decodeMarkdown(raw, root, portablePath(root))
      }
      NativeStructuralFrontend(
        program = IrDecode.program(programValue),
        manifests = manifests,
        markdown = markdown,
        config = runtimeConfig(manifests))
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

  private def decodeMarkdown(
      value: Value,
      root: java.io.File,
      source: String): NativeSourceMarkdown = value match
    case DataV("MarkdownDocument", IndexedSeq(blocksValue)) =>
      val blocks = list(blocksValue)
      blocks.foreach(block => validateMarkdownBlock(block, source))
      NativeSourceMarkdown(root, blocks.length)
    case DataV("MarkdownError", IndexedSeq(
          StrV(message), IntV(_), IntV(line), IntV(column))) =>
      throw new IllegalArgumentException(s"$source:$line:$column: $message")
    case other => abiError(s"bad Markdown result for $source: ${show(other)}")

  private def validateMarkdownBlock(value: Value, source: String): Unit = value match
    case DataV("MarkdownHeading", IndexedSeq(IntV(_), StrV(_), StrV(_), IntV(_))) => ()
    case DataV("MarkdownImport", IndexedSeq(links, IntV(_))) =>
      list(links).foreach {
        case DataV("MarkdownLink", IndexedSeq(StrV(_), StrV(_))) => ()
        case other => abiError(s"bad Markdown link in $source: ${show(other)}")
      }
    case DataV("MarkdownParagraph", IndexedSeq(inlines, IntV(_))) =>
      list(inlines).foreach {
        case DataV("MarkdownText", IndexedSeq(StrV(_))) => ()
        case DataV("MarkdownExpr", IndexedSeq(StrV(_))) => ()
        case other => abiError(s"bad Markdown inline in $source: ${show(other)}")
      }
    case DataV("MarkdownFence", IndexedSeq(StrV(_), StrV(_), IntV(_))) => ()
    case DataV("MarkdownBulletList", IndexedSeq(items, IntV(_))) =>
      validateStrings(items, s"Markdown bullet list in $source")
    case DataV("MarkdownOrderedList", IndexedSeq(IntV(_), items, IntV(_))) =>
      validateStrings(items, s"Markdown ordered list in $source")
    case DataV("MarkdownImage", IndexedSeq(StrV(_), StrV(_), StrV(_), IntV(_))) => ()
    case DataV("MarkdownTable", IndexedSeq(headers, alignments, rows, IntV(_))) =>
      validateStrings(headers, s"Markdown table headers in $source")
      validateStrings(alignments, s"Markdown table alignments in $source")
      list(rows).foreach {
        case DataV("MarkdownTableRow", IndexedSeq(cells)) =>
          validateStrings(cells, s"Markdown table row in $source")
        case other => abiError(s"bad Markdown table row in $source: ${show(other)}")
      }
    case DataV("MarkdownMetadata", IndexedSeq(StrV(_), IntV(_))) => ()
    case other => abiError(s"bad Markdown block in $source: ${show(other)}")

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
