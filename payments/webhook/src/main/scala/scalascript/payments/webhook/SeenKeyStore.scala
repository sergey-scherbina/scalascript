package scalascript.payments.webhook

import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap

trait SeenKeyStore:
  def wasSeen(key: String): Boolean
  def markSeen(key: String, expiry: Duration): Unit

/** Default in-memory store. Correct for single-instance dev; not cluster-safe. */
class InMemorySeenKeyStore extends SeenKeyStore:
  private case class Entry(expiresAt: Instant)
  private val store = ConcurrentHashMap[String, Entry]()

  def wasSeen(key: String): Boolean =
    Option(store.get(key)) match
      case Some(e) if e.expiresAt.isAfter(Instant.now()) => true
      case Some(_)                                        => store.remove(key); false
      case None                                           => false

  def markSeen(key: String, expiry: Duration): Unit =
    store.put(key, Entry(Instant.now().plus(expiry)))
    ()
