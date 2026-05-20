package scalascript.wallet.walletconnect

import scala.collection.concurrent.TrieMap

/** In-memory map of WC relay topics to their cryptographic state.
 *
 *  Every relay topic the wallet subscribes to (pairing topic from a
 *  `wc:` URI, or a freshly-derived session topic) has an associated
 *  32-byte symmetric key for ChaCha20-Poly1305 sealing. The session
 *  topic additionally remembers the peer's X25519 public key (when
 *  known) so we can re-derive the session symKey on restart or sign
 *  outbound Type-1 envelopes.
 *
 *  Thread-safe via `TrieMap` — callers may register, lookup, and
 *  forget topics from arbitrary threads without external locking.
 *  Intentionally in-memory; persistence (encrypted on-disk store) is
 *  a follow-up concern and slots in by replacing the backing map. */
final class WcSessionStore:

  import WcSessionStore.SessionEntry

  private val entries = TrieMap.empty[String, SessionEntry]

  /** Add (or replace) the entry for `topic`. Copies the byte arrays so
   *  callers can mutate or zero theirs without affecting the store. */
  def register(topic: String, symKey: Array[Byte], peerPub: Option[Array[Byte]] = None): Unit =
    entries.update(topic, SessionEntry(symKey.clone(), peerPub.map(_.clone())))

  /** Look up the entry for `topic`, if any. */
  def lookup(topic: String): Option[SessionEntry] =
    entries.get(topic)

  /** Drop the entry for `topic`. Idempotent. */
  def forget(topic: String): Unit =
    entries.remove(topic)
    ()

  /** Snapshot of currently-tracked topics. */
  def topics(): Set[String] =
    entries.keysIterator.toSet

  /** Whether `topic` is currently tracked. */
  def contains(topic: String): Boolean =
    entries.contains(topic)

  /** Remove every entry — handy in tests. */
  def clear(): Unit =
    entries.clear()

object WcSessionStore:
  /** Cryptographic state for a single relay topic. `peerPub` is `None`
   *  for pairing topics (the peer's X25519 key is unknown until the
   *  session-propose exchange completes). */
  final case class SessionEntry(symKey: Array[Byte], peerPub: Option[Array[Byte]])
