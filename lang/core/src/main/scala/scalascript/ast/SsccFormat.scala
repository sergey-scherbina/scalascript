package scalascript.ast

import io.bullet.borer.*
import io.bullet.borer.derivation.MapBasedCodecs.*
import scala.util.Try

/** Binary serialization of a parsed [[Module]] to/from a `.sscc` artifact.
 *
 *  Format (v2):
 *  {{{
 *    Bytes 0-3   magic   = 0x73 0x73 0x63 0x63  ("sscc")
 *    Byte  4     version = 0x02
 *    Bytes 5-8   crc32   = big-endian CRC32 of the CBOR payload (bytes 9+)
 *    Bytes 9+    CBOR-encoded ModulePickle
 *  }}}
 *
 *  All text fields (prose, code source, heading text, manifest description,
 *  database credentials, etc.) are gzip-compressed and stored as CBOR byte
 *  strings (major type 2).  No Base64 — raw bytes are native in CBOR.
 *
 *  For parseable (Scala/ScalaScript) code blocks, `source` is replaced with
 *  the canonical `tree.syntax` before compression.  [[ScalaNode]] is never
 *  stored — always written as CBOR null; the tree is reconstructed at load
 *  time by re-parsing the decompressed source.
 *
 *  [[Module.sourceText]] is NOT serialized.
 *  [[Manifest.raw]] (`Map[String, Any]`) is narrowed to `Map[String, String]`;
 *  non-String values are dropped (not used at runtime). */
object SsccFormat:

  val Magic: Array[Byte]   = Array(0x73.toByte, 0x73.toByte, 0x63.toByte, 0x63.toByte)
  val CurrentVersion: Byte = 2

  // ─── Compression ─────────────────────────────────────────────────────────

  private def compress(s: String): Array[Byte] =
    import java.io.ByteArrayOutputStream
    import java.util.zip.GZIPOutputStream
    import java.nio.charset.StandardCharsets.UTF_8
    val out = new ByteArrayOutputStream()
    val gz  = new GZIPOutputStream(out)
    try gz.write(s.getBytes(UTF_8)) finally gz.close()
    out.toByteArray

  private def decompress(bytes: Array[Byte]): String =
    import java.io.ByteArrayInputStream
    import java.util.zip.GZIPInputStream
    import java.nio.charset.StandardCharsets.UTF_8
    val gz = new GZIPInputStream(new ByteArrayInputStream(bytes))
    try new String(gz.readAllBytes(), UTF_8) finally gz.close()

  // ─── CRC32 helpers ───────────────────────────────────────────────────────

  private def crc32of(bytes: Array[Byte]): Int =
    import java.util.zip.CRC32
    val c = new CRC32()
    c.update(bytes)
    c.getValue.toInt

  private def putBE32(n: Int): Array[Byte] = Array(
    ((n >>> 24) & 0xff).toByte,
    ((n >>> 16) & 0xff).toByte,
    ((n >>>  8) & 0xff).toByte,
    ( n         & 0xff).toByte
  )

  private def getBE32(buf: Array[Byte], off: Int): Int =
    ((buf(off)     & 0xff) << 24) |
    ((buf(off + 1) & 0xff) << 16) |
    ((buf(off + 2) & 0xff) <<  8) |
     (buf(off + 3) & 0xff)

  // ─── Internal pickle types ────────────────────────────────────────────────
  // Text fields are Array[Byte] (gzip-compressed); CBOR stores them as native
  // byte strings — no Base64 overhead.

  private case class ListItemPickle(
    content: Array[Byte],
    nested:  List[ListItemPickle],
    span:    Option[Span] = None
  )

  private case class DatabaseDeclPickle(
    name:     String,
    url:      Array[Byte],
    user:     Option[Array[Byte]] = None,
    password: Option[Array[Byte]] = None,
    driver:   Option[String]      = None,
    span:     Option[Span]        = None
  )

  private case class SchemaDefaultPickle(
    kind:   String,
    string: Option[Array[Byte]] = None,
    bool:   Option[Boolean]     = None,
    int:    Option[Long]        = None,
    double: Option[Double]      = None
  )

  private case class FieldSchemaDeclPickle(
    fieldName:   String,
    storageName: Option[String]              = None,
    aliases:     List[String]                = Nil,
    default:     Option[SchemaDefaultPickle] = None,
    key:         Boolean                     = false,
    span:        Option[Span]                = None
  )

  private case class TypeSchemaDeclPickle(
    typeName:      String,
    fields:        List[FieldSchemaDeclPickle],
    rejectUnknown: Boolean      = false,
    span:          Option[Span] = None
  )

  private case class ManifestPickle(
    name:              Option[String]                   = None,
    version:           Option[String]                   = None,
    description:       Option[Array[Byte]]              = None,
    dependencies:      Map[String, String]              = Map.empty,
    exports:           List[String]                     = Nil,
    targets:           List[String]                     = Nil,
    routes:            List[RouteDecl]                  = Nil,
    pkg:               Option[List[String]]             = None,
    translations:      Map[String, Map[String, String]] = Map.empty,
    databases:         List[DatabaseDeclPickle]         = Nil,
    schemas:           List[TypeSchemaDeclPickle]       = Nil,
    frontendFramework: Option[String]                   = None,
    scripts:           Map[String, String]              = Map.empty,
    raw:               Map[String, Array[Byte]]         = Map.empty,
    apiClients:        List[ApiClientDecl]              = Nil,
    span:              Option[Span]                     = None
  )

  // ContentPickle mirrors Content but with compressed text/source fields.
  // ScalaNode (tree) is omitted — always null on write, reconstructed on read.
  private sealed trait ContentPickle
  private object ContentPickle:
    case class Prose(
      text: Array[Byte],
      span: Option[Span] = None
    ) extends ContentPickle

    case class CodeBlock(
      lang:       String,
      source:     Array[Byte],
      span:       Option[Span]                = None,
      parseError: Option[CodeBlockParseError] = None,
      lineOffset: Int                         = 0,
      attrs:      Map[String, String]         = Map.empty
    ) extends ContentPickle

    case class Import(
      path:     String,
      bindings: List[ImportBinding],
      span:     Option[Span] = None
    ) extends ContentPickle

    case class DataList(
      items:   List[ListItemPickle],
      ordered: Boolean,
      span:    Option[Span] = None
    ) extends ContentPickle

  private case class HeadingPickle(level: Int, text: Array[Byte], span: Option[Span] = None)

  private case class SectionPickle(
    heading:     HeadingPickle,
    content:     List[ContentPickle],
    subsections: List[SectionPickle],
    span:        Option[Span] = None
  )

  private case class ModulePickle(
    manifest: Option[ManifestPickle],
    sections: List[SectionPickle],
    span:     Option[Span] = None
  )

  // ─── Borer codecs ─────────────────────────────────────────────────────────
  // Order matters: dependency codecs must appear before their users.

  private given Codec[Position] = deriveCodec[Position]
  private given Codec[Span]     = deriveCodec[Span]

  private given Codec[CodeBlockParseError]  = deriveCodec[CodeBlockParseError]
  private given Codec[ImportBinding]        = deriveCodec[ImportBinding]
  private given Codec[RouteDecl]            = deriveCodec[RouteDecl]
  private given Codec[ApiEndpointDecl]      = deriveCodec[ApiEndpointDecl]
  private given Codec[ApiClientDecl]        = deriveCodec[ApiClientDecl]
  private lazy given Codec[ListItemPickle] = deriveCodec[ListItemPickle]

  private given Codec[DatabaseDeclPickle]  = deriveCodec[DatabaseDeclPickle]
  private given Codec[SchemaDefaultPickle] = deriveCodec[SchemaDefaultPickle]
  private given Codec[FieldSchemaDeclPickle] = deriveCodec[FieldSchemaDeclPickle]
  private given Codec[TypeSchemaDeclPickle] = deriveCodec[TypeSchemaDeclPickle]
  private given Codec[ManifestPickle]      = deriveCodec[ManifestPickle]

  private given Codec[ContentPickle.Prose]     = deriveCodec[ContentPickle.Prose]
  private given Codec[ContentPickle.CodeBlock] = deriveCodec[ContentPickle.CodeBlock]
  private given Codec[ContentPickle.Import]    = deriveCodec[ContentPickle.Import]
  private given Codec[ContentPickle.DataList]  = deriveCodec[ContentPickle.DataList]
  private given Codec[ContentPickle]           = deriveCodec[ContentPickle]

  private given Codec[HeadingPickle]           = deriveCodec[HeadingPickle]
  private lazy given Codec[SectionPickle]      = deriveCodec[SectionPickle]
  private given Codec[ModulePickle]            = deriveCodec[ModulePickle]

  // ─── Module → Pickle (compresses text, normalizes code source) ───────────

  private def toPickle(m: Module): ModulePickle =
    ModulePickle(m.manifest.map(toPickle), m.sections.map(toPickle), m.span)

  private def toPickle(m: Manifest): ManifestPickle =
    ManifestPickle(
      name              = m.name,
      version           = m.version,
      description       = m.description.map(compress),
      dependencies      = m.dependencies,
      exports           = m.exports,
      targets           = m.targets,
      routes            = m.routes,
      pkg               = m.pkg,
      translations      = m.translations,
      databases         = m.databases.map(toPickle),
      schemas           = m.schemas.map(toPickle),
      frontendFramework = m.frontendFramework,
      scripts           = m.scripts,
      raw               = m.raw.collect { case (k, v: String) => k -> compress(v) },
      apiClients        = m.apiClients,
      span              = m.span
    )

  private def toPickle(db: DatabaseDecl): DatabaseDeclPickle =
    DatabaseDeclPickle(
      name     = db.name,
      url      = compress(db.url),
      user     = db.user.map(compress),
      password = db.password.map(compress),
      driver   = db.driver,
      span     = db.span
    )

  private def toPickle(schema: TypeSchemaDecl): TypeSchemaDeclPickle =
    TypeSchemaDeclPickle(
      typeName      = schema.typeName,
      fields        = schema.fields.map(toPickle),
      rejectUnknown = schema.rejectUnknown,
      span          = schema.span
    )

  private def toPickle(field: FieldSchemaDecl): FieldSchemaDeclPickle =
    FieldSchemaDeclPickle(
      fieldName   = field.fieldName,
      storageName = field.storageName,
      aliases     = field.aliases,
      default     = field.default.map(toPickle),
      key         = field.key,
      span        = field.span
    )

  private def toPickle(default: SchemaDefault): SchemaDefaultPickle = default match
    case SchemaDefault.NullValue =>
      SchemaDefaultPickle(kind = "null")
    case SchemaDefault.Bool(value) =>
      SchemaDefaultPickle(kind = "bool", bool = Some(value))
    case SchemaDefault.IntValue(value) =>
      SchemaDefaultPickle(kind = "int", int = Some(value))
    case SchemaDefault.DoubleValue(value) =>
      SchemaDefaultPickle(kind = "double", double = Some(value))
    case SchemaDefault.StringValue(value) =>
      SchemaDefaultPickle(kind = "string", string = Some(compress(value)))

  private def toPickle(s: Section): SectionPickle =
    SectionPickle(
      heading     = HeadingPickle(s.heading.level, compress(s.heading.text), s.heading.span),
      content     = s.content.map(toPickle),
      subsections = s.subsections.map(toPickle),
      span        = s.span
    )

  private def toPickle(c: Content): ContentPickle = c match
    case p:  Content.Prose    => ContentPickle.Prose(compress(p.text), p.span)
    case i:  Content.Import   => ContentPickle.Import(i.path, i.bindings, i.span)
    case dl: Content.DataList => ContentPickle.DataList(dl.items.map(toPickle), dl.ordered, dl.span)
    case cb: Content.CodeBlock =>
      val src =
        if Lang.isParseable(cb.lang) then
          cb.tree match
            case Some(t) => ScalaNode.fold(t) { tree => import scala.meta.XtensionSyntax; tree.syntax }
            case None    => cb.source
        else cb.source
      ContentPickle.CodeBlock(cb.lang, compress(src), cb.span, cb.parseError, cb.lineOffset, cb.attrs)

  private def toPickle(item: ListItem): ListItemPickle =
    ListItemPickle(compress(item.content), item.nested.map(toPickle), item.span)

  // ─── Pickle → Module (decompresses + reconstructs Scala trees) ───────────

  private def fromPickle(pk: ModulePickle): Module =
    Module(pk.manifest.map(fromPickle), pk.sections.map(fromPickle), pk.span, sourceText = None)

  private def fromPickle(pk: ManifestPickle): Manifest =
    Manifest(
      name              = pk.name,
      version           = pk.version,
      description       = pk.description.map(decompress),
      dependencies      = pk.dependencies,
      exports           = pk.exports,
      targets           = pk.targets,
      routes            = pk.routes,
      pkg               = pk.pkg,
      translations      = pk.translations,
      databases         = pk.databases.map(fromPickle),
      schemas           = pk.schemas.map(fromPickle),
      frontendFramework = pk.frontendFramework,
      scripts           = pk.scripts,
      raw               = pk.raw.map { case (k, v) => k -> decompress(v) },
      apiClients        = pk.apiClients,
      span              = pk.span
    )

  private def fromPickle(pk: DatabaseDeclPickle): DatabaseDecl =
    DatabaseDecl(pk.name, decompress(pk.url), pk.user.map(decompress), pk.password.map(decompress), pk.driver, pk.span)

  private def fromPickle(pk: TypeSchemaDeclPickle): TypeSchemaDecl =
    TypeSchemaDecl(
      typeName      = pk.typeName,
      fields        = pk.fields.map(fromPickle),
      rejectUnknown = pk.rejectUnknown,
      span          = pk.span
    )

  private def fromPickle(pk: FieldSchemaDeclPickle): FieldSchemaDecl =
    FieldSchemaDecl(
      fieldName   = pk.fieldName,
      storageName = pk.storageName,
      aliases     = pk.aliases,
      default     = pk.default.map(fromPickle),
      key         = pk.key,
      span        = pk.span
    )

  private def fromPickle(pk: SchemaDefaultPickle): SchemaDefault = pk.kind match
    case "null"   => SchemaDefault.NullValue
    case "bool"   => SchemaDefault.Bool(pk.bool.getOrElse(false))
    case "int"    => SchemaDefault.IntValue(pk.int.getOrElse(0L))
    case "double" => SchemaDefault.DoubleValue(pk.double.getOrElse(0.0))
    case "string" => SchemaDefault.StringValue(pk.string.map(decompress).getOrElse(""))
    case other    => SchemaDefault.StringValue(other)

  private def fromPickle(pk: SectionPickle): Section =
    Section(
      heading     = Heading(pk.heading.level, decompress(pk.heading.text), pk.heading.span),
      content     = pk.content.map(fromPickle),
      subsections = pk.subsections.map(fromPickle),
      span        = pk.span
    )

  private def fromPickle(pk: ContentPickle): Content = pk match
    case p:  ContentPickle.Prose    => Content.Prose(decompress(p.text), p.span)
    case i:  ContentPickle.Import   => Content.Import(i.path, i.bindings, i.span)
    case dl: ContentPickle.DataList => Content.DataList(dl.items.map(fromPickle), dl.ordered, dl.span)
    case cb: ContentPickle.CodeBlock =>
      val src = decompress(cb.source)
      val tree =
        if Lang.isParseable(cb.lang) then
          import scala.meta.*
          dialects.Scala3(Input.VirtualFile("<sscc>", src)).parse[Source] match
            case Parsed.Success(t) => Some(ScalaNode(t))
            case _                 => None
        else None
      Content.CodeBlock(cb.lang, src, tree, cb.span, cb.parseError, cb.lineOffset, cb.attrs)

  private def fromPickle(pk: ListItemPickle): ListItem =
    ListItem(decompress(pk.content), pk.nested.map(fromPickle), pk.span)

  // ─── Public API ───────────────────────────────────────────────────────────

  /** Serialize a [[Module]] to `.sscc` bytes.
   *  Header: magic(4) + version(1) + crc32(4) + CBOR payload. */
  def write(module: Module): Array[Byte] =
    val payload = Cbor.encode(toPickle(module)).toByteArray
    Magic ++ Array(CurrentVersion) ++ putBE32(crc32of(payload)) ++ payload

  /** Deserialize a [[Module]] from `.sscc` bytes.
   *  Returns [[Left]] with a human-readable message on any error. */
  def read(bytes: Array[Byte]): Either[String, Module] =
    if bytes.length < 9 then
      Left("truncated .sscc header")
    else if !bytes.take(4).sameElements(Magic) then
      Left("not a .sscc file (bad magic bytes)")
    else
      val version = bytes(4)
      if version == 1 then
        Left("obsolete .sscc format (v1/msgpack) — please rebuild with current ssc")
      else if version > CurrentVersion then
        Left(
          s"unsupported .sscc version $version " +
          s"(this ssc supports up to $CurrentVersion) — please rebuild with current ssc"
        )
      else
        val storedCrc = getBE32(bytes, 5)
        val payload   = bytes.drop(9)
        val actualCrc = crc32of(payload)
        if storedCrc != actualCrc then
          Left(f"corrupt .sscc file — CRC32 mismatch (stored 0x$storedCrc%08x, computed 0x$actualCrc%08x)")
        else
          Try(Cbor.decode(payload).to[ModulePickle].value)
            .toEither.left.map(_.getMessage)
            .map(fromPickle)
