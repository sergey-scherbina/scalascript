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
  val CurrentVersion: Byte = 3   // v3 token-stream format is the default
  val V3Version: Byte      = 3   // kept as alias; same as CurrentVersion

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

  private def crc32of(bytes: Array[Byte], off: Int, len: Int): Int =
    import java.util.zip.CRC32
    val c = new CRC32()
    c.update(bytes, off, len)
    c.getValue.toInt

  // ─── Outer-payload gzip (v3 compressionFlag = 0x01) ─────────────────────

  private def gzipCompress(bytes: Array[Byte]): Array[Byte] =
    import java.io.ByteArrayOutputStream
    import java.util.zip.GZIPOutputStream
    val out = new ByteArrayOutputStream(bytes.length)
    val gz  = new GZIPOutputStream(out)
    try gz.write(bytes) finally gz.close()
    out.toByteArray

  private def gzipDecompress(bytes: Array[Byte], off: Int): Array[Byte] =
    import java.io.ByteArrayInputStream
    import java.util.zip.GZIPInputStream
    val gz = new GZIPInputStream(new ByteArrayInputStream(bytes, off, bytes.length - off))
    try gz.readAllBytes() finally gz.close()

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

  private case class ObjectStoreDeclPickle(
    name:      String,
    valueType: String,
    sync:      String         = "none",
    database:  String         = "default",
    store:     Option[String] = None,
    table:     Option[String] = None,
    key:       Option[String] = None,
    conflict:  String         = "manual",
    span:      Option[Span]   = None
  )

  private case class GraphDeclPickle(
    name:     String,
    model:    String         = "property",
    side:     String         = "server",
    backend:  String         = "in-memory",
    uri:      Option[String] = None,
    user:     Option[Array[Byte]] = None,
    password: Option[Array[Byte]] = None,
    span:     Option[Span]   = None
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

  private case class ClusterDeclPickle(
    name:         Option[String] = None,
    nodeId:       Option[String] = None,
    role:         Option[String] = None,
    bind:         Option[String] = None,
    advertiseUrl: Option[String] = None,
    seedNodes:    List[String] = Nil,
    authToken:    Option[Array[Byte]] = None,
    placement:    Map[String, String] = Map.empty,
    wire:         Map[String, String] = Map.empty,
    nodes:        Option[Int] = None,
    seedDiscovery: Option[String] = None,
    leaderElection: Option[String] = None,
    authTokenFrom: Option[String] = None,
    heartbeat:    Map[String, String] = Map.empty,
    quorum:       Option[Int] = None,
    span:         Option[Span] = None
  )

  private case class RemoteHandlerDeclPickle(
    name:         String,
    function:     String,
    path:         Option[String] = None,
    requestType:  Option[String] = None,
    responseType: Option[String] = None,
    span:         Option[Span] = None
  )

  private case class RemoteSourceDeclPickle(
    name:       String,
    source:     String,
    paramsType: Option[String] = None,
    itemType:   Option[String] = None,
    span:       Option[Span] = None
  )

  private case class RemoteBehaviorDeclPickle(
    name:     String,
    behavior: String,
    argsType: Option[String] = None,
    span:     Option[Span] = None
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
    objectStores:      List[ObjectStoreDeclPickle]      = Nil,
    graphs:            List[GraphDeclPickle]            = Nil,
    schemas:           List[TypeSchemaDeclPickle]       = Nil,
    frontendFramework: Option[String]                   = None,
    scripts:           Map[String, String]              = Map.empty,
    cluster:           Option[ClusterDeclPickle]        = None,
    remoteHandlers:    List[RemoteHandlerDeclPickle]    = Nil,
    remoteSources:     List[RemoteSourceDeclPickle]     = Nil,
    remoteBehaviors:   List[RemoteBehaviorDeclPickle]   = Nil,
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
  private given Codec[ObjectStoreDeclPickle] = deriveCodec[ObjectStoreDeclPickle]
  private given Codec[GraphDeclPickle] = deriveCodec[GraphDeclPickle]
  private given Codec[SchemaDefaultPickle] = deriveCodec[SchemaDefaultPickle]
  private given Codec[FieldSchemaDeclPickle] = deriveCodec[FieldSchemaDeclPickle]
  private given Codec[TypeSchemaDeclPickle] = deriveCodec[TypeSchemaDeclPickle]
  private given Codec[ClusterDeclPickle] = deriveCodec[ClusterDeclPickle]
  private given Codec[RemoteHandlerDeclPickle] = deriveCodec[RemoteHandlerDeclPickle]
  private given Codec[RemoteSourceDeclPickle] = deriveCodec[RemoteSourceDeclPickle]
  private given Codec[RemoteBehaviorDeclPickle] = deriveCodec[RemoteBehaviorDeclPickle]
  private given Codec[ManifestPickle]      = deriveCodec[ManifestPickle]

  private given Codec[ContentPickle.Prose]     = deriveCodec[ContentPickle.Prose]
  private given Codec[ContentPickle.CodeBlock] = deriveCodec[ContentPickle.CodeBlock]
  private given Codec[ContentPickle.Import]    = deriveCodec[ContentPickle.Import]
  private given Codec[ContentPickle.DataList]  = deriveCodec[ContentPickle.DataList]
  private given Codec[ContentPickle]           = deriveCodec[ContentPickle]

  private given Codec[HeadingPickle]           = deriveCodec[HeadingPickle]
  private lazy given Codec[SectionPickle]      = deriveCodec[SectionPickle]
  private given Codec[ModulePickle]            = deriveCodec[ModulePickle]

  // ─── Manifest → Pickle (used by v2 reader and v3 manifest CBOR blob) ────────

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
      objectStores      = m.objectStores.map(toPickle),
      graphs            = m.graphs.map(toPickle),
      schemas           = m.schemas.map(toPickle),
      frontendFramework = m.frontendFramework,
      scripts           = m.scripts,
      cluster           = m.cluster.map(toPickle),
      remoteHandlers    = m.remoteHandlers.map(toPickle),
      remoteSources     = m.remoteSources.map(toPickle),
      remoteBehaviors   = m.remoteBehaviors.map(toPickle),
      raw               = m.raw.collect { case (k, v: String) => k -> compress(v) },
      apiClients        = m.apiClients,
      span              = m.span
    )

  private def toPickle(c: ClusterDecl): ClusterDeclPickle =
    ClusterDeclPickle(c.name, c.nodeId, c.role, c.bind, c.advertiseUrl, c.seedNodes, c.authToken.map(compress), c.placement, c.wire, c.nodes, c.seedDiscovery, c.leaderElection, c.authTokenFrom, c.heartbeat, c.quorum, c.span)

  private def toPickle(h: RemoteHandlerDecl): RemoteHandlerDeclPickle =
    RemoteHandlerDeclPickle(h.name, h.function, h.path, h.requestType, h.responseType, h.span)

  private def toPickle(s: RemoteSourceDecl): RemoteSourceDeclPickle =
    RemoteSourceDeclPickle(s.name, s.source, s.paramsType, s.itemType, s.span)

  private def toPickle(b: RemoteBehaviorDecl): RemoteBehaviorDeclPickle =
    RemoteBehaviorDeclPickle(b.name, b.behavior, b.argsType, b.span)

  private def toPickle(db: DatabaseDecl): DatabaseDeclPickle =
    DatabaseDeclPickle(
      name     = db.name,
      url      = compress(db.url),
      user     = db.user.map(compress),
      password = db.password.map(compress),
      driver   = db.driver,
      span     = db.span
    )

  private def toPickle(store: ObjectStoreDecl): ObjectStoreDeclPickle =
    ObjectStoreDeclPickle(
      name      = store.name,
      valueType = store.valueType,
      sync      = store.sync,
      database  = store.database,
      store     = store.store,
      table     = store.table,
      key       = store.key,
      conflict  = store.conflict,
      span      = store.span
    )

  private def toPickle(graph: GraphDecl): GraphDeclPickle =
    GraphDeclPickle(
      name     = graph.name,
      model    = graph.model,
      side     = graph.side,
      backend  = graph.backend,
      uri      = graph.uri,
      user     = graph.user.map(compress),
      password = graph.password.map(compress),
      span     = graph.span
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
      objectStores      = pk.objectStores.map(fromPickle),
      graphs            = pk.graphs.map(fromPickle),
      schemas           = pk.schemas.map(fromPickle),
      frontendFramework = pk.frontendFramework,
      scripts           = pk.scripts,
      cluster           = pk.cluster.map(fromPickle),
      remoteHandlers    = pk.remoteHandlers.map(fromPickle),
      remoteSources     = pk.remoteSources.map(fromPickle),
      remoteBehaviors   = pk.remoteBehaviors.map(fromPickle),
      raw               = pk.raw.map { case (k, v) => k -> decompress(v) },
      apiClients        = pk.apiClients,
      span              = pk.span
    )

  private def fromPickle(pk: ClusterDeclPickle): ClusterDecl =
    ClusterDecl(pk.name, pk.nodeId, pk.role, pk.bind, pk.advertiseUrl, pk.seedNodes, pk.authToken.map(decompress), pk.placement, pk.wire, pk.nodes, pk.seedDiscovery, pk.leaderElection, pk.authTokenFrom, pk.heartbeat, pk.quorum, pk.span)

  private def fromPickle(pk: RemoteHandlerDeclPickle): RemoteHandlerDecl =
    RemoteHandlerDecl(pk.name, pk.function, pk.path, pk.requestType, pk.responseType, pk.span)

  private def fromPickle(pk: RemoteSourceDeclPickle): RemoteSourceDecl =
    RemoteSourceDecl(pk.name, pk.source, pk.paramsType, pk.itemType, pk.span)

  private def fromPickle(pk: RemoteBehaviorDeclPickle): RemoteBehaviorDecl =
    RemoteBehaviorDecl(pk.name, pk.behavior, pk.argsType, pk.span)

  private def fromPickle(pk: DatabaseDeclPickle): DatabaseDecl =
    DatabaseDecl(pk.name, decompress(pk.url), pk.user.map(decompress), pk.password.map(decompress), pk.driver, pk.span)

  private def fromPickle(pk: ObjectStoreDeclPickle): ObjectStoreDecl =
    ObjectStoreDecl(pk.name, pk.valueType, pk.sync, pk.database, pk.store, pk.table, pk.key, pk.conflict, pk.span)

  private def fromPickle(pk: GraphDeclPickle): GraphDecl =
    GraphDecl(pk.name, pk.model, pk.side, pk.backend, pk.uri, pk.user.map(decompress), pk.password.map(decompress), pk.span)

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

  // ─── Manifest pickle helpers (package-private for v3 writer/reader) ────────

  private[ast] def manifestToBytes(m: Manifest): Array[Byte] =
    Cbor.encode(toPickle(m)).toByteArray

  private[ast] def manifestFromBytes(bytes: Array[Byte]): Manifest =
    fromPickle(Try(Cbor.decode(bytes).to[ManifestPickle].value).get)

  // ─── Public API ───────────────────────────────────────────────────────────

  /** Serialize a [[Module]] to `.sscc` bytes using v3 token-stream format.
   *  Set env `SSC_V3_GZIP=1` to enable outer gzip compression. */
  def write(module: Module): Array[Byte] = writeV3(module)

  /** Write to v3 format unconditionally. For benchmarks.
   *  Pass `gzip = true` to enable outer gzip compression (SSC_V3_GZIP=1 also enables it). */
  def writeV3(module: Module, gzip: Boolean = sys.env.get("SSC_V3_GZIP").exists(_ != "0")): Array[Byte] =
    writeV3Impl(module, gzip)

  private val GzipFlag: Byte    = 0x01.toByte
  private val NoCompressFlag: Byte = 0x00.toByte

  private def writeV3Impl(module: Module, gzip: Boolean): Array[Byte] =
    val mBytes  = module.manifest.map(manifestToBytes).getOrElse(Array.empty[Byte])
    val rawPayload = SsccFormatV3.write(module, mBytes)
    val (compressionFlag, payload) =
      if gzip then (GzipFlag, gzipCompress(rawPayload))
      else         (NoCompressFlag, rawPayload)
    val crc = crc32of(payload)
    Magic ++ Array(V3Version, compressionFlag) ++ putBE32(crc) ++ payload

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
      else if version == V3Version then
        readV3(bytes)
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

  /** Decode-only read: trie + token stream without scalameta parse (`tree = None`).
   *  Useful for dependency resolution and benchmarking the irreducible I/O floor. */
  def readNoTrees(bytes: Array[Byte]): Either[String, Module] =
    readV3Impl(bytes, populateTrees = false)

  private def readV3(bytes: Array[Byte]): Either[String, Module] =
    readV3Impl(bytes, populateTrees = true)

  private def readV3Impl(bytes: Array[Byte], populateTrees: Boolean): Either[String, Module] =
    // v3 header: magic(4) + version(1) + compressionFlag(1) + crc32(4) = 10 bytes
    if bytes.length < 10 then return Left("truncated .sscc v3 header")
    val compressionFlag = bytes(5) & 0xff
    val storedCrc       = getBE32(bytes, 6)
    val payloadOff      = 10
    val actualCrc       = crc32of(bytes, payloadOff, bytes.length - payloadOff)
    if storedCrc != actualCrc then
      return Left(f"corrupt .sscc v3 file — CRC32 mismatch (stored 0x$storedCrc%08x, computed 0x$actualCrc%08x)")
    // Uncompressed: pass the original bytes + offset to avoid a 35KB copy.
    // Compressed: decompress first (copy unavoidable), pass offset 0 of the new array.
    if populateTrees then compressionFlag match
      case 0x00 => SsccFormatV3.read(bytes, payloadOff, bytes => manifestFromBytes(bytes))
      case 0x01 => Try(gzipDecompress(bytes, payloadOff)).toEither
                     .left.map(e => s"gzip decompress failed: ${e.getMessage}")
                     .flatMap(d => SsccFormatV3.read(d, 0, bytes => manifestFromBytes(bytes)))
      case c    => Left(s"unsupported .sscc v3 compression flag 0x${c.toHexString}")
    else compressionFlag match
      case 0x00 => SsccFormatV3.readNoTrees(bytes, payloadOff, bytes => manifestFromBytes(bytes))
      case 0x01 => Try(gzipDecompress(bytes, payloadOff)).toEither
                     .left.map(e => s"gzip decompress failed: ${e.getMessage}")
                     .flatMap(d => SsccFormatV3.readNoTrees(d, 0, bytes => manifestFromBytes(bytes)))
      case c    => Left(s"unsupported .sscc v3 compression flag 0x${c.toHexString}")
