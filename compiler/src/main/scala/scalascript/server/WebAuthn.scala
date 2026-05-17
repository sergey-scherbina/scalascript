package scalascript.server

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** WebAuthn / FIDO2 passkey building blocks (RFC 8809 / W3C Web
 *  Authentication Level 2).
 *
 *  This file covers the parts that don't touch CBOR or ECDSA yet:
 *
 *    - [[challenge]]      : mint a fresh 32-byte challenge (base64url)
 *                            and stash it with a TTL so the matching
 *                            attestation / assertion call can consume it.
 *    - [[consumeChallenge]] : look up + remove a pending challenge.
 *                              Returns `false` if it's missing or expired.
 *    - [[CredentialStore]] : in-memory store of `(userId → List[Credential])`.
 *                            Each credential carries a base64url credentialId
 *                            and a base64url-encoded COSE public key plus a
 *                            monotonic signCount.
 *
 *  The actual attestation / assertion verification lives in a follow-up
 *  commit — needs a small CBOR reader and ECDSA P-256 verification.
 *  Splitting the challenge / store layer out lets the example wire up
 *  the JS-side `navigator.credentials.create / get` glue first and gives
 *  the verifier something concrete to target. */
object WebAuthn:
  private val b64Enc = Base64.getUrlEncoder.withoutPadding
  private val rng    = SecureRandom()

  // ── Pending challenges ────────────────────────────────────────────
  // Keyed by challenge string.  Carrying the userId lets a verifier
  // confirm the challenge was issued for the right account.  A fixed
  // 5-minute TTL — long enough for the user to interact with their
  // authenticator, short enough that a leaked challenge expires
  // before it's useful.
  private case class Pending(userId: String, issuedMs: Long)
  private val pending = ConcurrentHashMap[String, Pending]()
  private val TtlMs   = 5L * 60L * 1000L

  /** Mint a fresh challenge for `userId` and return it as a base64url
   *  string the client can pass straight into the WebAuthn API. */
  def challenge(userId: String): String =
    val bytes = new Array[Byte](32)
    rng.nextBytes(bytes)
    val s = b64Enc.encodeToString(bytes)
    pending.put(s, Pending(userId, java.lang.System.currentTimeMillis()))
    maybeSweep()
    s

  /** Consume a previously-issued challenge.  Returns `Some(userId)` if
   *  the challenge was outstanding and not expired; `None` otherwise.
   *  The challenge is removed from the store in both cases so it can't
   *  be replayed. */
  def consumeChallenge(s: String): Option[String] =
    Option(pending.remove(s)).filter { p =>
      java.lang.System.currentTimeMillis() - p.issuedMs <= TtlMs
    }.map(_.userId)

  private val sweepCounter = java.util.concurrent.atomic.AtomicLong(0L)
  private def maybeSweep(): Unit =
    if (sweepCounter.incrementAndGet() & 0xFF) == 0L then
      val cutoff = java.lang.System.currentTimeMillis() - TtlMs
      val it = pending.entrySet().iterator()
      while it.hasNext do
        val e = it.next()
        if e.getValue.issuedMs < cutoff then it.remove()

  // ── Credential store ──────────────────────────────────────────────
  // Process-local Map[userId → List[Credential]].  Per credential we
  // hold the credentialId (base64url, opaque to us), the COSE public
  // key (base64url-encoded raw CBOR bytes — verifier will decode), and
  // the latest signCount we've seen.  Authenticators bump signCount on
  // every assertion; a request whose count is ≤ the stored value is
  // either a replay or a cloned authenticator and must be rejected.
  final case class Credential(
      credentialId: String,
      publicKey:    String,
      signCount:    Long,
  )

  private val store = ConcurrentHashMap[String, java.util.List[Credential]]()

  def storePut(userId: String, c: Credential): Unit =
    val list = store.computeIfAbsent(userId, _ => java.util.ArrayList[Credential]())
    list.synchronized {
      // Replace any prior entry with the same credentialId so a re-
      // enrolment overwrites instead of duplicating.
      val it = list.iterator()
      while it.hasNext do
        if it.next().credentialId == c.credentialId then it.remove()
      list.add(c)
    }

  def storeGet(userId: String): List[Credential] =
    Option(store.get(userId))
      .map(l => l.synchronized(java.util.ArrayList(l)))
      .map(scala.jdk.CollectionConverters.ListHasAsScala(_).asScala.toList)
      .getOrElse(Nil)

  def storeFind(userId: String, credentialId: String): Option[Credential] =
    storeGet(userId).find(_.credentialId == credentialId)

  /** Update signCount after a successful assertion.  Returns true if
   *  the new count is strictly greater than the stored one (the
   *  authenticator counter advanced as expected); false on cloned /
   *  replayed signatures. */
  def storeUpdateSignCount(userId: String, credentialId: String, newCount: Long): Boolean =
    Option(store.get(userId)) match
      case None       => false
      case Some(list) =>
        list.synchronized {
          var ok = false
          val n = list.size
          var i = 0
          while i < n do
            val c = list.get(i)
            if c.credentialId == credentialId then
              if newCount > c.signCount then
                list.set(i, c.copy(signCount = newCount))
                ok = true
              i = n
            else i += 1
          ok
        }

  /** Wipe everything — tests. */
  def reset(): Unit =
    pending.clear()
    store.clear()
