package scalascript.payments.webhook.postgres

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterAll
import scalascript.db.PgClient
import scalascript.payments.webhook.SeenKeyStore
import java.time.Duration
import scala.compiletime.uninitialized
import scala.concurrent.ExecutionContext

class PostgresSeenKeyStoreTest extends AnyFunSuite with BeforeAndAfterAll:
  given ExecutionContext = ExecutionContext.global

  private var db: PgClient = uninitialized

  override def beforeAll(): Unit =
    db = PgClient.connect("jdbc:h2:mem:whktest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=FALSE")

  override def afterAll(): Unit =
    if db != null then db.close()

  private def freshStore(table: String): PostgresSeenKeyStore =
    PostgresSeenKeyStore(db, table = table, autoCreate = true, timeoutMs = 10000L)

  test("PostgresSeenKeyStore: wasSeen returns false for new key"):
    val store = freshStore("whk1")
    assert(!store.wasSeen("new-key"))

  test("PostgresSeenKeyStore: wasSeen returns true after markSeen"):
    val store = freshStore("whk2")
    store.markSeen("evt-a", Duration.ofDays(30))
    assert(store.wasSeen("evt-a"))

  test("PostgresSeenKeyStore: different keys are independent"):
    val store = freshStore("whk3")
    store.markSeen("evt-x", Duration.ofDays(30))
    assert(store.wasSeen("evt-x"))
    assert(!store.wasSeen("evt-y"))

  test("PostgresSeenKeyStore: markSeen is idempotent"):
    val store = freshStore("whk4")
    store.markSeen("evt-idem", Duration.ofDays(30))
    store.markSeen("evt-idem", Duration.ofDays(30))
    assert(store.wasSeen("evt-idem"))

  test("PostgresSeenKeyStore: expired entry treated as not-seen"):
    val store = freshStore("whk5")
    store.markSeen("evt-exp", Duration.ofMillis(-1))
    assert(!store.wasSeen("evt-exp"))

  test("PostgresSeenKeyStore: implements SeenKeyStore trait"):
    val store: SeenKeyStore = freshStore("whk6")
    assert(!store.wasSeen("any-key"))

  test("PostgresSeenKeyStore: purgeExpired removes expired rows"):
    val store = freshStore("whk7")
    store.markSeen("live-key",  Duration.ofDays(30))
    store.markSeen("dead-key", Duration.ofMillis(-1))
    val removed = store.purgeExpired()
    assert(removed >= 1)
    assert(store.wasSeen("live-key"))

  test("PostgresSeenKeyStore: autoCreate creates table on first call"):
    val store = freshStore("whk8")
    assert(!store.wasSeen("any"))

  test("PostgresSeenKeyStore: table isolation between instances with different table names"):
    val s1 = freshStore("whk_iso1")
    val s2 = freshStore("whk_iso2")
    s1.markSeen("shared", Duration.ofDays(1))
    assert(s1.wasSeen("shared"))
    assert(!s2.wasSeen("shared"))
