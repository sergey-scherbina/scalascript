package scalascript.ast

import upickle.default.*
import scala.util.Try

/** Binary serialization of a parsed [[Module]] to/from a `.sscc` artifact.
 *
 *  Format:
 *  {{{
 *    Bytes 0-3  magic   = 0x73 0x73 0x63 0x63  ("sscc")
 *    Byte  4    version = 0x01
 *    Bytes 5+   msgpack-encoded Module
 *  }}}
 *
 *  For parseable (Scala/ScalaScript) [[Content.CodeBlock]]s the `source` field
 *  is replaced with the normalized `tree.syntax` before serialization — this
 *  stores a canonical AST form rather than the original verbatim source text.
 *  [[Content.CodeBlock.tree]] is always written as nil; at load time it is
 *  reconstructed by parsing the (normalized) `source`.
 *
 *  [[Module.sourceText]] is NOT serialized — the raw file content has no
 *  place in a pre-compiled binary artifact; runtime paths that need it handle
 *  `None` already.
 *
 *  [[Manifest.raw]] (`Map[String, Any]`) is narrowed to
 *  `Map[String, String]`; non-String values are dropped (not used at runtime). */
object SsccFormat:

  val Magic: Array[Byte]   = Array(0x73.toByte, 0x73.toByte, 0x63.toByte, 0x63.toByte)
  val CurrentVersion: Byte = 1

  // ─── ReadWriters (order matters — used instances must precede using ones) ─

  given ReadWriter[Position] = macroRW
  given ReadWriter[Span]     = macroRW

  // ScalaNode is not stored in .sscc — always emit nil, always read back empty.
  // The tree is reconstructed at load time from the normalized CodeBlock.source.
  given ReadWriter[ScalaNode] = readwriter[ujson.Value].bimap(
    _  => ujson.Null,
    _  => ScalaNode(scala.meta.Source(Nil))
  )

  given ReadWriter[CodeBlockParseError]  = macroRW
  given ReadWriter[ImportBinding]        = macroRW
  given ReadWriter[ListItem]             = macroRW

  // Explicit product instances for Content variants so that macroRW[Content]
  // finds them rather than trying to re-derive them as sum types.
  given ReadWriter[Content.Prose]     = macroRW
  given ReadWriter[Content.CodeBlock] = macroRW
  given ReadWriter[Content.Import]    = macroRW
  given ReadWriter[Content.DataList]  = macroRW
  given ReadWriter[Content]           = macroRW

  given ReadWriter[RouteDecl]    = macroRW
  given ReadWriter[DatabaseDecl] = macroRW

  // Manifest.raw is Map[String, Any]; we keep only String-valued entries
  // since all runtime consumers pattern-match on String anyway.
  case class ManifestPickle(
    name:              Option[String]                   = None,
    version:           Option[String]                   = None,
    description:       Option[String]                   = None,
    dependencies:      Map[String, String]              = Map.empty,
    exports:           List[String]                     = Nil,
    targets:           List[String]                     = Nil,
    routes:            List[RouteDecl]                  = Nil,
    pkg:               Option[List[String]]             = None,
    translations:      Map[String, Map[String, String]] = Map.empty,
    databases:         List[DatabaseDecl]               = Nil,
    frontendFramework: Option[String]                   = None,
    scripts:           Map[String, String]              = Map.empty,
    raw:               Map[String, String]              = Map.empty,
    span:              Option[Span]                     = None
  ) derives ReadWriter

  given ReadWriter[Manifest] =
    readwriter[ManifestPickle].bimap(
      m => ManifestPickle(
        name              = m.name,
        version           = m.version,
        description       = m.description,
        dependencies      = m.dependencies,
        exports           = m.exports,
        targets           = m.targets,
        routes            = m.routes,
        pkg               = m.pkg,
        translations      = m.translations,
        databases         = m.databases,
        frontendFramework = m.frontendFramework,
        scripts           = m.scripts,
        raw               = m.raw.collect { case (k, v: String) => k -> v },
        span              = m.span
      ),
      p => Manifest(
        name              = p.name,
        version           = p.version,
        description       = p.description,
        dependencies      = p.dependencies,
        exports           = p.exports,
        targets           = p.targets,
        routes            = p.routes,
        pkg               = p.pkg,
        translations      = p.translations,
        databases         = p.databases,
        frontendFramework = p.frontendFramework,
        scripts           = p.scripts,
        raw               = p.raw,
        span              = p.span
      )
    )

  given ReadWriter[Heading] = macroRW
  given ReadWriter[Section] = macroRW

  // Module: drop sourceText — raw file content has no place in a pre-compiled
  // binary artifact.  Set to None on load; runtime paths that need it handle
  // None already (e.g. FencedConfigExtractor returns Nil for None).
  case class ModulePickle(
    manifest: Option[Manifest],
    sections: List[Section],
    span:     Option[Span] = None
  ) derives ReadWriter

  given ReadWriter[Module] = readwriter[ModulePickle].bimap(
    m  => ModulePickle(m.manifest, m.sections, m.span),
    pk => Module(pk.manifest, pk.sections, pk.span, sourceText = None)
  )

  // ─── Public API ──────────────────────────────────────────────────────────

  /** Serialize a [[Module]] to `.sscc` bytes (magic + version + msgpack).
   *  For parseable code blocks, `source` is replaced with the normalized
   *  `tree.syntax` so the binary stores the canonical AST form, not the
   *  original verbatim text. */
  def write(module: Module): Array[Byte] =
    val payload = writeBinary(normalizeForWrite(module))
    Magic ++ Array(CurrentVersion) ++ payload

  /** Deserialize a [[Module]] from `.sscc` bytes.
   *  Returns [[Left]] with a human-readable message on any error.
   *  Scala code-block trees are reconstructed from their (normalized) `source`. */
  def read(bytes: Array[Byte]): Either[String, Module] =
    if bytes.length < 5 then
      Left("truncated .sscc header")
    else if !bytes.take(4).sameElements(Magic) then
      Left("not a .sscc file (bad magic bytes)")
    else
      val version = bytes(4)
      if version > CurrentVersion then
        Left(
          s"unsupported .sscc version $version " +
          s"(this ssc supports up to $CurrentVersion) — please rebuild with current ssc"
        )
      else
        Try(readBinary[Module](bytes.drop(5))).toEither.left.map(_.getMessage)
          .map(reconstructTrees)

  // ─── Pre-write normalization ──────────────────────────────────────────────

  // Replace CodeBlock.source with tree.syntax for parseable blocks so the
  // binary holds the canonical parsed form, not the original verbatim text.
  private def normalizeForWrite(module: Module): Module =
    module.copy(sections = module.sections.map(normalizeSection))

  private def normalizeSection(section: Section): Section =
    section.copy(
      content     = section.content.map(normalizeContent),
      subsections = section.subsections.map(normalizeSection)
    )

  private def normalizeContent(content: Content): Content = content match
    case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
      cb.tree match
        case Some(t) =>
          val syntax = ScalaNode.fold(t) { tree =>
            import scala.meta.XtensionSyntax
            tree.syntax
          }
          cb.copy(source = syntax)
        case None => cb  // parse failed — keep original source as fallback
    case other => other

  // ─── Post-load tree reconstruction ───────────────────────────────────────

  private def reconstructTrees(module: Module): Module =
    module.copy(sections = module.sections.map(reconstructSection))

  private def reconstructSection(section: Section): Section =
    section.copy(
      content     = section.content.map(reconstructContent),
      subsections = section.subsections.map(reconstructSection)
    )

  private def reconstructContent(content: Content): Content = content match
    case cb: Content.CodeBlock if Lang.isParseable(cb.lang) =>
      import scala.meta.*
      val tree = dialects.Scala3(Input.VirtualFile("<sscc>", cb.source)).parse[Source] match
        case Parsed.Success(t) => Some(ScalaNode(t))
        case _                 => None
      cb.copy(tree = tree)
    case other => other
