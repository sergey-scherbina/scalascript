package scalascript.mcp.wallet

import scalascript.blockchain.spi.ChainId

/** One entry in the operation log. Records the *decision* and a hash
 *  of the result — never the raw signature bytes — so the audit
 *  trail is safe to surface to remote MCP clients. */
case class AuditEntry(
  timestamp:    Long,
  tool:         String,
  chainId:      Option[ChainId],
  decision:     String,         // "approved" / "rejected" / "auto" / "error"
  details:      Map[String, String] = Map.empty,
  resultDigest: Option[String]      = None,   // first 16 hex chars of sha256(result), if any
):
  def toJson: ujson.Value =
    ujson.Obj(
      "timestamp"    -> ujson.Num(timestamp.toDouble),
      "tool"         -> ujson.Str(tool),
      "chainId"      -> chainId.map(c => ujson.Str(c.caip2)).getOrElse(ujson.Null),
      "decision"     -> ujson.Str(decision),
      "details"      -> ujson.Obj.from(details.map { case (k, v) => k -> ujson.Str(v) }),
      "resultDigest" -> resultDigest.map(ujson.Str(_)).getOrElse(ujson.Null),
    )

/** Bounded in-memory operation log surfaced via the
 *  `wallet://audit` resource. Capacity defaults to 1000 entries —
 *  enough for typical session lengths without unbounded memory growth.
 *  Hosts that need persistence (Postgres / file) wrap this with their
 *  own writer. */
class AuditLog(capacity: Int = 1000):
  private val buf = scala.collection.mutable.ArrayDeque.empty[AuditEntry]

  def append(entry: AuditEntry): Unit = synchronized:
    buf += entry
    while buf.size > capacity do buf.removeHead()

  def snapshot: Seq[AuditEntry] = synchronized(buf.toSeq)

  def size: Int = synchronized(buf.size)

  def clear(): Unit = synchronized(buf.clear())
