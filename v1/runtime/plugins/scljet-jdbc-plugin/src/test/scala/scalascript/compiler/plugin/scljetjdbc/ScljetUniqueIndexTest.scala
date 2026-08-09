package scalascript.compiler.plugin.scljetjdbc

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files
import java.sql.{DriverManager, SQLException, Types}
import scala.jdk.CollectionConverters.*

/** Differential contract for SclJet-created UNIQUE indexes.
 *
 *  Rejection is compared against sqlite-jdbc, while successful persistence is
 *  checked by opening the SclJet-written file with reference SQLite. A
 *  same-engine write/read round trip cannot prove SQLite index ordering. */
class ScljetUniqueIndexTest extends AnyFunSuite:

  assert(ScljetDriver.Prefix == "jdbc:scljet:")
  Class.forName("org.sqlite.JDBC")

  private val UniqueMarker = "UNIQUE constraint failed: "

  private def normalizedUnique(ex: SQLException): String =
    val message = String.valueOf(ex.getMessage)
    val start = message.indexOf(UniqueMarker)
    assert(start >= 0, s"not a unique-constraint error: $message")
    message.substring(start).takeWhile(ch => ch != ')' && ch != '\n' && ch != '\r')

  private def uniqueFailure(url: String, setup: List[String], failing: String): String =
    val c = DriverManager.getConnection(url)
    try
      val s = c.createStatement()
      setup.foreach(s.executeUpdate)
      normalizedUnique(intercept[SQLException](s.executeUpdate(failing)))
    finally c.close()

  private case class DuplicateCase(name: String, setup: List[String], failing: String, expected: String)

  test("CREATE, INSERT and UPDATE duplicate rejection matches sqlite-jdbc"):
    val cases = List(
      DuplicateCase(
        "create",
        List("CREATE TABLE t(a INTEGER, b TEXT)", "INSERT INTO t VALUES (1,'x'),(1,'x')"),
        "CREATE UNIQUE INDEX ux ON t(a,b)",
        "UNIQUE constraint failed: t.a, t.b",
      ),
      DuplicateCase(
        "insert",
        List("CREATE TABLE t(a INTEGER, b TEXT)", "INSERT INTO t VALUES (1,'x')",
          "CREATE UNIQUE INDEX ux ON t(a,b)"),
        "INSERT INTO t VALUES (1,'x')",
        "UNIQUE constraint failed: t.a, t.b",
      ),
      DuplicateCase(
        "update",
        List("CREATE TABLE t(id INTEGER, a INTEGER)", "INSERT INTO t VALUES (1,10),(2,20)",
          "CREATE UNIQUE INDEX ux ON t(a)"),
        "UPDATE t SET a=10 WHERE id=2",
        "UNIQUE constraint failed: t.a",
      ),
      DuplicateCase(
        "integer-real",
        List("CREATE TABLE t(a)", "INSERT INTO t VALUES (1)", "CREATE UNIQUE INDEX ux ON t(a)"),
        "INSERT INTO t VALUES (1.0)",
        "UNIQUE constraint failed: t.a",
      ),
    )

    for scenario <- cases do
      val scljet = uniqueFailure("jdbc:scljet::memory:", scenario.setup, scenario.failing)
      val sqlite = uniqueFailure("jdbc:sqlite::memory:", scenario.setup, scenario.failing)
      assert(scljet == scenario.expected, s"${scenario.name}: scljet=$scljet")
      assert(sqlite == scenario.expected, s"${scenario.name}: sqlite=$sqlite")
      assert(scljet == sqlite, s"${scenario.name}: scljet=$scljet sqlite=$sqlite")

  test("SclJet-written REAL/BLOB unique indexes are reference-valid and introspect as unique"):
    val dir = Files.createTempDirectory("scljet-unique-index-")
    val db = dir.resolve("unique.db")
    try
      val c = DriverManager.getConnection(s"jdbc:scljet:${db.toString}")
      try
        val s = c.createStatement()
        s.executeUpdate("CREATE TABLE t(id INTEGER, n, b BLOB)")
        val insert = c.prepareStatement("INSERT INTO t VALUES (?,?,?)")

        def insertReal(id: Long, n: Double, bytes: Array[Byte]): Unit =
          insert.clearParameters()
          insert.setLong(1, id); insert.setDouble(2, n); insert.setBytes(3, bytes)
          assert(insert.executeUpdate() == 1)

        def insertLong(id: Long, n: Long, bytes: Array[Byte]): Unit =
          insert.clearParameters()
          insert.setLong(1, id); insert.setLong(2, n); insert.setBytes(3, bytes)
          assert(insert.executeUpdate() == 1)

        def insertNull(id: Long, bytes: Array[Byte]): Unit =
          insert.clearParameters()
          insert.setLong(1, id); insert.setNull(2, Types.NULL); insert.setBytes(3, bytes)
          assert(insert.executeUpdate() == 1)

        // Deliberately unsorted. The two large numeric keys are distinct under
        // SQLite's exact INTEGER-vs-REAL comparison despite Double rounding.
        insertReal(1L, 3.5, Array[Byte](3))
        insertReal(2L, -2.25, Array[Byte](-1))
        insertLong(3L, 9007199254740993L, Array[Byte](0))
        insertReal(4L, 9007199254740992.0, Array[Byte](2))
        s.executeUpdate("CREATE UNIQUE INDEX ux_n ON t(n)")
        s.executeUpdate("CREATE UNIQUE INDEX ux_b ON t(b)")

        // Exercise the compact rebuild path with two stored unique indexes.
        insertReal(5L, 2.0, Array[Byte](1, 2))
        insertNull(6L, Array[Byte](4))
        insertNull(7L, Array[Byte](5))

        val duplicateNumber = c.prepareStatement("INSERT INTO t VALUES (?,?,?)")
        duplicateNumber.setLong(1, 8L); duplicateNumber.setDouble(2, 3.5)
        duplicateNumber.setBytes(3, Array[Byte](6))
        assert(normalizedUnique(intercept[SQLException](duplicateNumber.executeUpdate())) ==
          "UNIQUE constraint failed: t.n")

        val duplicateBlob = c.prepareStatement("INSERT INTO t VALUES (?,?,?)")
        duplicateBlob.setLong(1, 8L); duplicateBlob.setDouble(2, 42.0)
        duplicateBlob.setBytes(3, Array[Byte](3))
        assert(normalizedUnique(intercept[SQLException](duplicateBlob.executeUpdate())) ==
          "UNIQUE constraint failed: t.b")

        val count = s.executeQuery("SELECT count(*) FROM t")
        assert(count.next() && count.getLong(1) == 7L, "failed inserts must be atomic")

        val metadata = c.getMetaData.getIndexInfo(null, null, "t", false, false)
        val indexes = scala.collection.mutable.ArrayBuffer.empty[String]
        while metadata.next() do
          val name = metadata.getString("INDEX_NAME")
          val nonUnique = metadata.getBoolean("NON_UNIQUE")
          val column = metadata.getString("COLUMN_NAME")
          indexes += s"$name|$nonUnique|$column"
        assert(indexes.toSet == Set("ux_b|false|b", "ux_n|false|n"))
        insert.close(); duplicateNumber.close(); duplicateBlob.close()
      finally c.close()

      val ref = DriverManager.getConnection(s"jdbc:sqlite:${db.toString}")
      try
        val s = ref.createStatement()
        val integrity = s.executeQuery("PRAGMA integrity_check")
        assert(integrity.next() && integrity.getString(1) == "ok")

        val count = s.executeQuery("SELECT count(*) FROM t")
        assert(count.next() && count.getLong(1) == 7L)

        val schema = s.executeQuery("SELECT name, sql FROM sqlite_schema WHERE name IN ('ux_b','ux_n') ORDER BY name")
        val schemaRows = scala.collection.mutable.ArrayBuffer.empty[String]
        while schema.next() do schemaRows += s"${schema.getString(1)}|${schema.getString(2)}"
        assert(schemaRows.toList == List(
          "ux_b|CREATE UNIQUE INDEX ux_b ON t(b)",
          "ux_n|CREATE UNIQUE INDEX ux_n ON t(n)",
        ))

        val duplicate = ref.prepareStatement("INSERT INTO t VALUES (?,?,?)")
        duplicate.setLong(1, 8L); duplicate.setDouble(2, 3.5); duplicate.setBytes(3, Array[Byte](6))
        assert(normalizedUnique(intercept[SQLException](duplicate.executeUpdate())) ==
          "UNIQUE constraint failed: t.n")
        duplicate.close()
      finally ref.close()
    finally
      if Files.exists(dir) then
        val files = Files.list(dir)
        try files.iterator().asScala.foreach(Files.deleteIfExists)
        finally files.close()
      Files.deleteIfExists(dir)
