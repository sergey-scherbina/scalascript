package scalascript.x402.nonce

import scalascript.x402.{NonceStore, Bytes32}
import scalascript.redis.RedisClient
import scala.concurrent.Future
import scala.concurrent.duration.*

// ── Redis-backed NonceStore ───────────────────────────────────────────────────
// claim: SETNX x402:nonce:<hex> 1 PX <ttl-ms>
//        Returns true if the key was newly set (first use), false if already present.
// cleanup: no-op — TTL handles expiry automatically.

private class RedisNonceStoreImpl(client: RedisClient) extends NonceStore:

  private def keyFor(nonce: Bytes32): String = s"x402:nonce:$nonce"

  def claim(nonce: Bytes32, validBefore: BigInt): Future[Boolean] =
    val now       = System.currentTimeMillis() / 1000
    val ttlSecs   = (validBefore - now).max(BigInt(1)).toLong
    client.setNx(keyFor(nonce), "1", ttlSecs.seconds)

  def cleanup(): Future[Unit] =
    Future.unit   // TTL handles expiry; no-op here

object RedisNonceStore:
  def apply(client: RedisClient): NonceStore =
    new RedisNonceStoreImpl(client)
