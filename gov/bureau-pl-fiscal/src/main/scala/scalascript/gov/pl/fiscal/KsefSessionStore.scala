package scalascript.gov.pl.fiscal

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Thread-safe in-memory KSeF session token cache.
 *  Session tokens are valid for 24 hours per KSeF spec. */
class KsefSessionStore(ttlSeconds: Long = 86_400L):

  private case class Entry(token: String, expiresAt: Instant)
  private val ref = AtomicReference[Option[Entry]](None)

  def get(): Option[String] =
    ref.get().filter(_.expiresAt.isAfter(Instant.now())).map(_.token)

  def put(token: String): Unit =
    ref.set(Some(Entry(token, Instant.now().plusSeconds(ttlSeconds))))

  def invalidate(): Unit =
    ref.set(None)

  def isValid: Boolean = get().isDefined
