package scalascript.compiler.plugin.deploy

import java.util.concurrent.ConcurrentLinkedDeque

/** In-memory ring-buffer audit log for cluster worker operations.
 *  Thread-safe; evicts oldest entries when capacity is exceeded. */
class AuditLog(val capacity: Int = 500):

  private val entries = new ConcurrentLinkedDeque[AuditEntry]()

  def record(event: String, nodeId: String, detail: String = "", actor: String = ""): Unit =
    val entry = AuditEntry(
      ts       = java.time.Instant.now().toString,
      event    = event,
      nodeId   = nodeId,
      detail   = detail,
      actor    = actor,
    )
    entries.addFirst(entry)
    while entries.size() > capacity do entries.removeLast()

  def recent(limit: Int = 50): List[AuditEntry] =
    val buf = List.newBuilder[AuditEntry]
    var n   = 0
    val it  = entries.iterator()
    while it.hasNext && n < limit do
      buf += it.next()
      n   += 1
    buf.result()

  def toJson(limit: Int = 50): String =
    val xs = recent(limit)
    val items = xs.map { e =>
      s"""{"ts":${jsonStr(e.ts)},"event":${jsonStr(e.event)},"nodeId":${jsonStr(e.nodeId)},"detail":${jsonStr(e.detail)},"actor":${jsonStr(e.actor)}}"""
    }.mkString(",\n  ")
    s"[\n  $items\n]"

  private def jsonStr(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

case class AuditEntry(
  ts:     String,
  event:  String,
  nodeId: String,
  detail: String,
  actor:  String,
)

object AuditEvents:
  val BundleLoaded    = "bundle_loaded"
  val BundleUnloaded  = "bundle_unloaded"
  val BundleRolledBack= "bundle_rolled_back"
  val BundleShipped   = "bundle_shipped"
  val BundleFailed    = "bundle_failed"
  val CircuitOpen     = "circuit_open"
  val CircuitClosed   = "circuit_closed"
  val LoadShed        = "load_shed"
  val ResourceDenied  = "resource_denied"
  val DepVerifyFailed = "dep_verify_failed"
  val SignatureInvalid= "signature_invalid"

object AuditLog:
  val global: AuditLog = new AuditLog(1000)
