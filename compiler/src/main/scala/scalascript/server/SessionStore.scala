package scalascript.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in server-side session store.
 *
 *  When enabled via [[useStore]], `Response.html(...).withSession(payload)`
 *  no longer roundtrips the payload through the client cookie.  Instead
 *  it stores the payload in a process-local map keyed by a random SSID
 *  and sets `session=<b64url(json({"_ssid": ssid}))>.<sig>` as the
 *  cookie value.  `req.session` reads the SSID back out of the cookie
 *  payload, looks the entry up in the store, and surfaces the full
 *  Map[String, String] to the handler.
 *
 *  Why bother? Three reasons:
 *    1. Cookie size — browsers cap cookies at ~4KB.  Large payloads
 *       (lists of permissions, JWT bundles, …) overflow.
 *    2. Instant revocation — `clearSession()` wipes the store entry, so
 *       a stolen cookie stops working immediately even if it was
 *       captured before logout.
 *    3. Sensitive data — signed cookies are tamper-proof but readable
 *       by anyone with the bytes.  Server-side payloads stay on the
 *       server.
 *
 *  TTL: each `get` refreshes the entry's last-access timestamp.  A
 *  lazy sweep on every Nth access drops anything that hasn't been
 *  touched in `ttlSeconds`.  No background thread — keeps the runtime
 *  shape identical to the stateless mode. */
object SessionStore:
  private case class Entry(payload: Map[String, String], lastAccess: Long)

  private val enabled       = AtomicBoolean(false)
  private val store         = ConcurrentHashMap[String, Entry]()
  @volatile private var ttlMs: Long = 30L * 60L * 1000L  // 30 min idle by default
  private val accessCount   = java.util.concurrent.atomic.AtomicLong(0L)
  private val sweepEveryN   = 256                         // sweep on every 256th access

  /** Flip the store on for the rest of the process lifetime.  Safe to
   *  call more than once; later calls reset the TTL. */
  def useStore(ttlSeconds: Long = 30L * 60L): Unit =
    ttlMs = ttlSeconds * 1000L
    enabled.set(true)

  def isEnabled: Boolean = enabled.get()

  /** Store a fresh payload and return its SSID. */
  def put(payload: Map[String, String]): String =
    val bytes = new Array[Byte](24)
    java.security.SecureRandom().nextBytes(bytes)
    val ssid = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    store.put(ssid, Entry(payload, java.lang.System.currentTimeMillis()))
    maybeSweep()
    ssid

  /** Look an SSID up.  Returns `None` if missing or expired (and
   *  removes expired entries as a side-effect). */
  def get(ssid: String): Option[Map[String, String]] =
    Option(store.get(ssid)) match
      case None    => None
      case Some(e) =>
        val now = java.lang.System.currentTimeMillis()
        if now - e.lastAccess > ttlMs then
          store.remove(ssid, e)
          None
        else
          // Refresh last-access so an active session doesn't expire.
          store.put(ssid, e.copy(lastAccess = now))
          maybeSweep()
          Some(e.payload)

  /** Remove an entry — used by `clearSession()` for server-side logout. */
  def delete(ssid: String): Unit =
    store.remove(ssid)

  /** Drop entries that haven't been touched within `ttlMs`. */
  def sweep(): Unit =
    val now    = java.lang.System.currentTimeMillis()
    val cutoff = now - ttlMs
    val it     = store.entrySet().iterator()
    while it.hasNext do
      val e = it.next()
      if e.getValue.lastAccess < cutoff then it.remove()

  private def maybeSweep(): Unit =
    if accessCount.incrementAndGet() % sweepEveryN == 0 then sweep()

  /** Diagnostic. */
  def size: Int = store.size()

  /** Wipe everything — useful for tests. */
  def reset(): Unit =
    enabled.set(false)
    store.clear()
    ttlMs = 30L * 60L * 1000L
    accessCount.set(0L)
