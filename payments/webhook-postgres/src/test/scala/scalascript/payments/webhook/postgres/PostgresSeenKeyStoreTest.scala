package scalascript.payments.webhook.postgres

import org.scalatest.funsuite.AnyFunSuite
import scalascript.db.PgClient

import java.time.Duration
import scala.concurrent.ExecutionContext

/** Unit tests for PostgresSeenKeyStore backed by an in-memory H2 database.
 *  Runs without a live Postgres server.
 */
class PostgresSeenKeyStoreTest extends AnyFunSuite:

  given ExecutionContext = ExecutionContext.global

  private def h2Store(tableName: String): PostgresSeenKeyStore =
    val pg = PgClient.connect(
      s"jdbc:h2:mem:webhook_${tableName}_${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
    )
    PostgresSeenKeyStore.applyAndCreate(pg, tableName = tableName, awaitTimeoutMs = 5_000)

  test("createTable — table is created idempotently") {
    val pg    = PgClient.connect(s"jdbc:h2:mem:wh_create_${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
    val store = PostgresSeenKeyStore(pg, tableName = "seen_keys_idem")
    store.createTable()
    store.createTable()
  }

  test("wasSeen returns false on first call — key was not seen") {
    val store = h2Store("test_wasSeen_first")
    assert(!store.wasSeen("evt-001"))
  }

  test("wasSeen returns true on second call — key was already claimed") {
    val store = h2Store("test_wasSeen_second")
    store.wasSeen("evt-002")
    assert(store.wasSeen("evt-002"))
  }

  test("markSeen — subsequent wasSeen returns true after markSeen") {
    val store = h2Store("test_markSeen")
    store.wasSeen("evt-003")
    store.markSeen("evt-003", Duration.ofDays(30))
    assert(store.wasSeen("evt-003"))
  }

  test("independent keys do not interfere") {
    val store = h2Store("test_independent")
    assert(!store.wasSeen("key-A"))
    assert(!store.wasSeen("key-B"))
    assert(store.wasSeen("key-A"))
    assert(store.wasSeen("key-B"))
  }

  test("advisory lock — concurrent claim: INSERT ON CONFLICT means first wasSeen wins") {
    val store = h2Store("test_advisory")
    val r1 = store.wasSeen("evt-race")
    val r2 = store.wasSeen("evt-race")
    assert(!r1, "first claim should succeed (not seen)")
    assert(r2,  "second claim should fail (already claimed)")
  }

  test("markSeen updates TTL on existing row") {
    val store = h2Store("test_update_ttl")
    store.wasSeen("evt-ttl")
    store.markSeen("evt-ttl", Duration.ofSeconds(10))
    store.markSeen("evt-ttl", Duration.ofDays(30))
    assert(store.wasSeen("evt-ttl"))
  }

  test("tableName is honoured — two stores on different tables are independent") {
    val storeA = h2Store("table_a")
    val storeB = h2Store("table_b")
    storeA.wasSeen("shared-key")
    assert(!storeB.wasSeen("shared-key"), "different tables are independent stores")
  }
