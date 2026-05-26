package scalascript.sql

import java.sql.{Connection, DriverManager}
import java.util.UUID

import org.scalatest.funsuite.AnyFunSuite
import scalascript.typeddata.{ObjectCodec, key}

class ObjectStoreRuntimeTest extends AnyFunSuite:

  Class.forName("org.h2.Driver")

  private def withConn[A](f: Connection => A): A =
    val dbName = s"objectstore-${UUID.randomUUID().toString.take(8)}"
    val conn = DriverManager.getConnection(s"jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1")
    try f(conn) finally conn.close()

  final case class Draft(@key id: String, title: String, done: Boolean = false) derives ObjectCodec

  test("put/get/all round trip typed objects through JDBC JSON storage"):
    withConn { conn =>
      val stored = ObjectStoreRuntime.put(conn, "drafts", Draft("d1", "Plan"))
      ObjectStoreRuntime.put(conn, "drafts", Draft("d2", "Ship", done = true))

      assert(stored.key == "d1")
      assert(stored.version == 1L)
      assert(ObjectStoreRuntime.get[Draft](conn, "drafts", "d1") == Some(Draft("d1", "Plan")))
      assert(ObjectStoreRuntime.all[Draft](conn, "drafts").map(_.id).toSet == Set("d1", "d2"))
    }

  test("explicit keys and optimistic versions are honored"):
    withConn { conn =>
      val first = ObjectStoreRuntime.put(conn, "drafts", Draft("ignored", "Plan"), key = Some("custom"))
      val second = ObjectStoreRuntime.put(conn, "drafts", Draft("ignored", "Updated"), key = Some("custom"), expectedVersion = Some(first.version))

      assert(second.version == first.version + 1L)
      assert(ObjectStoreRuntime.get[Draft](conn, "drafts", "custom").map(_.title) == Some("Updated"))

      val conflict = intercept[ObjectStoreConflict] {
        ObjectStoreRuntime.put(conn, "drafts", Draft("ignored", "Bad"), key = Some("custom"), expectedVersion = Some(first.version))
      }
      assert(conflict.actualVersion == Some(second.version))
    }

  test("delete creates tombstones visible through changes"):
    withConn { conn =>
      val first = ObjectStoreRuntime.put(conn, "drafts", Draft("d1", "Plan"))
      val deleted = ObjectStoreRuntime.delete(conn, "drafts", "d1", expectedVersion = Some(first.version))

      assert(deleted.deleted)
      assert(ObjectStoreRuntime.get[Draft](conn, "drafts", "d1").isEmpty)

      val changes = ObjectStoreRuntime.changes[Draft](conn, "drafts", sinceVersion = first.version)
      assert(changes.map(_.version) == Vector(deleted.version))
      assert(changes.head.deleted)
      assert(changes.head.value.isEmpty)
    }

  test("SQLite stores and reads object tombstone booleans"):
    Class.forName("org.sqlite.JDBC")
    val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
    try
      ObjectStoreRuntime.put(conn, "drafts", Draft("d1", "Plan"))
      ObjectStoreRuntime.delete(conn, "drafts", "d1")

      val changes = ObjectStoreRuntime.changes[Draft](conn, "drafts", sinceVersion = 0L)
      assert(changes.head.deleted)
      assert(changes.head.value.isEmpty)
    finally conn.close()
