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
 *  [[ScalaNode]] (opaque `scala.meta.Tree`) is serialized as its
 *  pretty-printed syntax and re-parsed with Scala 3 dialect on load.
 *  [[Manifest.raw]] (`Map[String, Any]`) is narrowed to
 *  `Map[String, String]`; non-String values are dropped (they are not
 *  used by any caller at runtime). */
object SsccFormat:

  val Magic: Array[Byte]   = Array(0x73.toByte, 0x73.toByte, 0x63.toByte, 0x63.toByte)
  val CurrentVersion: Byte = 1

  // ─── ReadWriters (order matters — used instances must precede using ones) ─

  given ReadWriter[Position] = macroRW
  given ReadWriter[Span]     = macroRW

  // ScalaNode is opaque over scala.meta.Tree.
  // Serialize as pretty-printed syntax; re-parse on load.
  given ReadWriter[ScalaNode] = readwriter[String].bimap(
    node => ScalaNode.fold(node)(t => { import scala.meta.XtensionSyntax; t.syntax }),
    src =>
      import scala.meta.*
      dialects.Scala3(Input.VirtualFile("<sscc>", src)).parse[Source] match
        case Parsed.Success(tree) => ScalaNode(tree)
        case _                    => ScalaNode(Source(Nil))
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
  given ReadWriter[Module]  = macroRW

  // ─── Public API ──────────────────────────────────────────────────────────

  /** Serialize a [[Module]] to `.sscc` bytes (magic + version + msgpack). */
  def write(module: Module): Array[Byte] =
    val payload = writeBinary(module)
    Magic ++ Array(CurrentVersion) ++ payload

  /** Deserialize a [[Module]] from `.sscc` bytes.
   *  Returns [[Left]] with a human-readable message on any error. */
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
