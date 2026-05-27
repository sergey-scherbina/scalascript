package scalascript.payments.webhook.postgres

import scalascript.payments.webhook.SeenKeyStore
import scalascript.db.{PgClient, RowDecoder}

import java.sql.SQLIntegrityConstraintViolationException
import java.time.Duration
import scala.concurrent.duration.{Duration as ScalaDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Postgres-backed SeenKeyStore with distributed advisory lock.
 *
 *  Uses a `seen_webhook_keys(idempotency_key TEXT PRIMARY KEY, expires_at TIMESTAMP)` table.
 *
 *  ## Distributed advisory lock pattern
 *
 *  `wasSeen(key)` runs within a transaction:
 *    1. Checks for an existing non-expired row. If found → return true (already processed).
 *    2. Tries to INSERT a claim row with short TTL (claimTtlSeconds).
 *    3. If INSERT succeeds → return false (we own this event).
 *    4. If INSERT fails with a duplicate-key violation → return true (another instance claimed it).
 *
 *  `markSeen(key, expiry)` updates the row's TTL to the final replay-protection window,
 *  or inserts if the claim row somehow no longer exists.
 *
 *  Call `createTable()` once at startup. Cleanup via:
 *    DELETE FROM seen_webhook_keys WHERE expires_at < CURRENT_TIMESTAMP
 */
class PostgresSeenKeyStore(
  pg:              PgClient,
  tableName:       String = "seen_webhook_keys",
  claimTtlSeconds: Long   = 300,
  awaitTimeoutMs:  Long   = 10_000,
)(using ExecutionContext) extends SeenKeyStore:

  private val awaitTimeout              = ScalaDuration(awaitTimeoutMs, "ms")
  private given RowDecoder[Int]         = summon[RowDecoder[Int]]

  def createTable(): Unit =
    Await.result(pg.execute(
      s"""CREATE TABLE IF NOT EXISTS $tableName (
         |  idempotency_key VARCHAR(512) NOT NULL PRIMARY KEY,
         |  expires_at      TIMESTAMP    NOT NULL
         |)""".stripMargin
    ), awaitTimeout)
    ()

  def wasSeen(idKey: String): Boolean =
    val result: Future[Boolean] = pg.transaction { tx =>
      tx.query[Int](
        s"SELECT 1 FROM $tableName WHERE idempotency_key = ? AND expires_at > CURRENT_TIMESTAMP",
        idKey
      ).flatMap { existing =>
        if existing.nonEmpty then Future.successful(true)
        else
          tx.execute(
            s"INSERT INTO $tableName(idempotency_key, expires_at) VALUES(?, DATEADD(SECOND, $claimTtlSeconds, CURRENT_TIMESTAMP))",
            idKey
          ).transform {
            case Success(_)                                          => Success(false)
            case Failure(_: SQLIntegrityConstraintViolationException) => Success(true)
            case Failure(e) if isDuplicateKey(e)                     => Success(true)
            case Failure(e)                                          => Failure(e)
          }
      }
    }
    Await.result(result, awaitTimeout)

  def markSeen(idKey: String, expiry: Duration): Unit =
    val secs = expiry.getSeconds
    Await.result(pg.transaction { tx =>
      tx.execute(
        s"UPDATE $tableName SET expires_at = DATEADD(SECOND, $secs, CURRENT_TIMESTAMP) WHERE idempotency_key = ?",
        idKey
      ).flatMap { updated =>
        if updated > 0 then Future.successful(())
        else tx.execute(
          s"INSERT INTO $tableName(idempotency_key, expires_at) VALUES(?, DATEADD(SECOND, $secs, CURRENT_TIMESTAMP))",
          idKey
        ).map(_ => ())
      }
    }, awaitTimeout)
    ()

  private def isDuplicateKey(e: Throwable): Boolean =
    e.getMessage != null && (
      e.getMessage.contains("Unique index or primary key violation") ||
      e.getMessage.contains("duplicate key") ||
      e.getMessage.contains("unique constraint") ||
      e.getMessage.contains("23505")
    )

object PostgresSeenKeyStore:
  def apply(
    pg:              PgClient,
    tableName:       String = "seen_webhook_keys",
    claimTtlSeconds: Long   = 300,
    awaitTimeoutMs:  Long   = 10_000,
  )(using ExecutionContext): PostgresSeenKeyStore =
    new PostgresSeenKeyStore(pg, tableName, claimTtlSeconds, awaitTimeoutMs)

  def applyAndCreate(
    pg:              PgClient,
    tableName:       String = "seen_webhook_keys",
    claimTtlSeconds: Long   = 300,
    awaitTimeoutMs:  Long   = 10_000,
  )(using ExecutionContext): PostgresSeenKeyStore =
    val store = new PostgresSeenKeyStore(pg, tableName, claimTtlSeconds, awaitTimeoutMs)
    store.createTable()
    store
