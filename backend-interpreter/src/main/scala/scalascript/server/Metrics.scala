package scalascript.server

import java.util.concurrent.atomic.AtomicLong

/** Process-global counters surfaced through the `metrics()` native.
 *
 *  Lives on a single object so every backend (interpreter `WsProxy`,
 *  JvmGen-emitted server, etc.) can poke the same counter set
 *  without each one having to plumb a metrics handle through every
 *  call site.  Counters are `AtomicLong` so updates from the
 *  selector / read-loop threads and from the executor don't race.
 *
 *  Naming convention: dotted, lowercase, namespace-prefixed
 *  (`ws.*`, `http.*`).  Stable across releases — log shippers
 *  scrape by exact name.
 */
object Metrics:

  // ── WebSocket ────────────────────────────────────────────────────

  /** Current live WS connections — single source of truth (also
   *  consulted by [[WsConnection.tryReserveSlot]] for the cap). */
  val wsActive:      AtomicLong = AtomicLong(0L)
  /** Cumulative successful upgrades (101 served, handler invoked). */
  val wsUpgraded:    AtomicLong = AtomicLong(0L)
  /** Cumulative refused upgrades (any 4xx/5xx returned at the
   *  upgrade dispatch: Origin denied, cap reached, auth refused,
   *  subprotocol mismatch). */
  val wsRejected:    AtomicLong = AtomicLong(0L)
  /** Cumulative inbound app messages (Text+Binary frames, after
   *  fragmentation reassembly; rate-limited drops don't count). */
  val wsMessagesIn:  AtomicLong = AtomicLong(0L)
  /** Cumulative outbound app frames (Text+Binary; control frames
   *  Ping/Pong/Close excluded). */
  val wsMessagesOut: AtomicLong = AtomicLong(0L)
  val wsBytesIn:     AtomicLong = AtomicLong(0L)
  val wsBytesOut:    AtomicLong = AtomicLong(0L)

  // ── HTTP ─────────────────────────────────────────────────────────

  /** Cumulative HTTP requests handled by the embedded HttpServer
   *  (incremented by `WebServer.handle` on every request). */
  val httpRequests:  AtomicLong = AtomicLong(0L)
  val http4xx:       AtomicLong = AtomicLong(0L)
  val http5xx:       AtomicLong = AtomicLong(0L)

  /** Build a snapshot Map view of every counter.  Allocates a fresh
   *  map per call; intended for scraping, not for hot-path use. */
  def snapshot(): Map[String, Long] = Map(
    "ws.active"       -> wsActive.get,
    "ws.upgraded"     -> wsUpgraded.get,
    "ws.rejected"     -> wsRejected.get,
    "ws.messages.in"  -> wsMessagesIn.get,
    "ws.messages.out" -> wsMessagesOut.get,
    "ws.bytes.in"     -> wsBytesIn.get,
    "ws.bytes.out"    -> wsBytesOut.get,
    "http.requests"   -> httpRequests.get,
    "http.4xx"        -> http4xx.get,
    "http.5xx"        -> http5xx.get
  )

  /** Reset every counter to zero.  Used by tests; not exposed to
   *  user code (no `resetMetrics()` native) — production code
   *  should treat counters as monotonic until process restart. */
  def reset(): Unit =
    wsActive.set(0L);      wsUpgraded.set(0L);     wsRejected.set(0L)
    wsMessagesIn.set(0L);  wsMessagesOut.set(0L)
    wsBytesIn.set(0L);     wsBytesOut.set(0L)
    httpRequests.set(0L);  http4xx.set(0L);        http5xx.set(0L)
