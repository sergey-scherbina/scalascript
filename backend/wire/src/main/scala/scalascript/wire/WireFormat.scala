package scalascript.wire

/** Wire format identifiers and registry.
 *
 *  Spec: specs/distributed-wire-protocol.md §Negotiation */
object WireFormat:
  val Json    = "json"
  val MsgPack = "msgpack"
  val Cbor    = "cbor"

  val all: Set[String] = Set(Json, MsgPack, Cbor)

  def isValid(format: String): Boolean = all.contains(format)

/** Resource limits and negotiation config for wire traffic. */
case class WireLimits(
  maxFrameBytes:  Long = 16 * 1024 * 1024,    // 16 MiB per envelope
  maxDepth:       Int  = 128,                   // max nesting depth
  maxStringBytes: Long = 64 * 1024,             // 64 KiB per string field
  maxArrayItems:  Int  = 65536,                 // max list/map entries
)

object WireLimits:
  val default: WireLimits = WireLimits()
  val strict: WireLimits  = WireLimits(
    maxFrameBytes  = 4 * 1024 * 1024,
    maxDepth       = 32,
    maxStringBytes = 16 * 1024,
    maxArrayItems  = 4096,
  )

/** Front-matter `wire:` configuration block.
 *
 *  Spec: specs/distributed-wire-protocol.md §Global and Front-matter Configuration */
case class WireConfig(
  enabled:       Boolean            = false,
  format:        String             = WireFormat.Json,
  jsonFallback:  Boolean            = true,
  compression:   String             = "none",       // none | gzip | zstd
  integrity:     String             = "none",        // none | hmac-sha256
  maxFrameBytes: Long               = 16 * 1024 * 1024,
  surfaces:      WireSurfaces       = WireSurfaces(),
)

case class WireSurfaces(
  actors:      Boolean = false,
  dataset:     Boolean = false,
  dstream:     Boolean = false,
  rpc:         Boolean = false,
  objectSync:  Boolean = false,
)

object WireConfig:
  val default: WireConfig = WireConfig()
  val allEnabled: WireConfig = WireConfig(
    enabled  = true,
    format   = WireFormat.Json,
    surfaces = WireSurfaces(
      actors = true, dataset = true, dstream = true, rpc = true, objectSync = true,
    ),
  )

  /** Parse a `wire:` front-matter map (String → Any) into `WireConfig`. */
  def fromFrontMatter(m: Map[String, Any]): WireConfig =
    val surfaces = m.get("surfaces") match
      case Some(sm: Map[?, ?]) =>
        val s = sm.asInstanceOf[Map[String, Any]]
        WireSurfaces(
          actors     = s.get("actors").contains(true),
          dataset    = s.get("dataset").contains(true),
          dstream    = s.get("dstream").contains(true),
          rpc        = s.get("rpc").contains(true),
          objectSync = s.get("object-sync").contains(true) || s.get("objectSync").contains(true),
        )
      case _ => WireSurfaces()
    val fmt = m.get("format").collect { case s: String => s }.getOrElse(WireFormat.Json)
    WireConfig(
      enabled       = m.get("enabled").contains(true),
      format        = if WireFormat.isValid(fmt) then fmt else WireFormat.Json,
      jsonFallback  = m.get("jsonFallback").collect { case b: Boolean => b }.getOrElse(true),
      compression   = m.get("compression").collect { case s: String => s }.getOrElse("none"),
      integrity     = m.get("integrity").collect { case s: String => s }.getOrElse("none"),
      maxFrameBytes = m.get("maxFrameBytes").collect {
        case n: Long   => n
        case n: Int    => n.toLong
        case n: Double => n.toLong
      }.getOrElse(16 * 1024 * 1024L),
      surfaces = surfaces,
    )
