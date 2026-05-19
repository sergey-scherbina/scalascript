package scalascript.x402.nonce

import scalascript.x402.{NonceStore, Bytes32}
import scalascript.db.PgClient
import scala.concurrent.{ExecutionContext, Future}

// ── Postgres-backed NonceStore ────────────────────────────────────────────────
// Table: x402_nonces (nonce, valid_before, created_at)
// claim: INSERT … ON CONFLICT DO NOTHING, returns rowcount > 0
// cleanup: DELETE WHERE valid_before <= now

private class PgNonceStoreImpl(db: PgClient)(using ec: ExecutionContext) extends NonceStore:

  def claim(nonce: Bytes32, validBefore: BigInt): Future[Boolean] =
    db.execute(
      "INSERT INTO x402_nonces (nonce, valid_before) VALUES (?, ?) ON CONFLICT (nonce) DO NOTHING",
      nonce, validBefore.toLong,
    ).map(_ > 0)

  def cleanup(): Future[Unit] =
    val now = System.currentTimeMillis() / 1000
    db.execute("DELETE FROM x402_nonces WHERE valid_before <= ?", now).map(_ => ())

object PgNonceStore:
  def apply(db: PgClient)(using ExecutionContext): NonceStore =
    new PgNonceStoreImpl(db)

  val createTable: String =
    """CREATE TABLE IF NOT EXISTS x402_nonces (
      |  nonce        TEXT   NOT NULL PRIMARY KEY,
      |  valid_before BIGINT NOT NULL,
      |  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
      |)""".stripMargin
