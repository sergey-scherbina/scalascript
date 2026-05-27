package scalascript.payments.webhook.postgres

import scalascript.payments.webhook.SeenKeyStore
import scalascript.db.{PgClient, RowDecoder}
import java.time.{Duration, Instant}
import java.sql.ResultSet
import scala.concurrent.Await
import scala.concurrent.duration.{Duration => SD, MILLISECONDS}

/** Cluster-safe idempotency store backed by PostgreSQL.
 *
 *  Requires a `webhook_seen_keys` table (auto-created when `autoCreate=true`):
 *  {{{
 *  CREATE TABLE IF NOT EXISTS webhook_seen_keys (
 *    event_key  TEXT        PRIMARY KEY,
 *    expires_at TIMESTAMP   NOT NULL
 *  );
 *  }}}
 *
 *  `markSeen` uses INSERT … ON CONFLICT DO NOTHING — atomic under concurrent
 *  writes thanks to the PRIMARY KEY constraint.  The first instance to insert
 *  a key wins; all subsequent attempts silently do nothing.
 *
 *  `wasSeen` filters on `expires_at` so logically-expired entries are ignored
 *  without requiring a background sweep.
 *
 *  @param db          Connected PgClient.
 *  @param table       Table name (default: "webhook_seen_keys").
 *  @param autoCreate  Whether to CREATE TABLE on first call (default: true).
 *  @param timeoutMs   Await timeout per DB call in milliseconds (default: 10000). */
class PostgresSeenKeyStore(
    db:          PgClient,
    table:       String  = "webhook_seen_keys",
    autoCreate:  Boolean = true,
    timeoutMs:   Long    = 10000L,
) extends SeenKeyStore:

  private val timeout = SD(timeoutMs, MILLISECONDS)

  @volatile private var tableEnsured = false

  private def ensureTable(): Unit =
    if autoCreate && !tableEnsured then
      Await.result(
        db.execute(
          s"CREATE TABLE IF NOT EXISTS $table (event_key TEXT PRIMARY KEY, expires_at TIMESTAMP NOT NULL)"
        ),
        timeout,
      )
      tableEnsured = true

  private given RowDecoder[Boolean] with
    def decode(rs: ResultSet): Boolean = rs.getBoolean(1)

  def wasSeen(key: String): Boolean =
    ensureTable()
    val now  = java.sql.Timestamp.from(Instant.now())
    val rows = Await.result(
      db.query[Boolean](
        s"SELECT COUNT(*) > 0 FROM $table WHERE event_key = ? AND expires_at > ?",
        key, now,
      ),
      timeout,
    )
    rows.headOption.getOrElse(false)

  def markSeen(key: String, expiry: Duration): Unit =
    ensureTable()
    val expiresAt = java.sql.Timestamp.from(Instant.now().plus(expiry))
    Await.result(
      db.execute(
        s"INSERT INTO $table(event_key, expires_at) VALUES(?, ?) ON CONFLICT DO NOTHING",
        key, expiresAt,
      ),
      timeout,
    )
    ()

  /** Remove all expired entries.  Call periodically from a maintenance job. */
  def purgeExpired(): Int =
    ensureTable()
    val now = java.sql.Timestamp.from(Instant.now())
    Await.result(
      db.execute(s"DELETE FROM $table WHERE expires_at <= ?", now),
      timeout,
    )
