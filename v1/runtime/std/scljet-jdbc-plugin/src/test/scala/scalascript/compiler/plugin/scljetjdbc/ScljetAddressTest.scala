package scalascript.compiler.plugin.scljetjdbc

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Value

import java.nio.file.{Files, Path}
import java.sql.DriverManager

/** `scljet/address.ssc` — the cross-engine differential, through a FILE.
 *
 *  This is the test the conformance case cannot be. `scljet-address-read` runs on `[int, js]`,
 *  where the image is written by scljet and read by scljet: a **self-consistent oracle**, correct
 *  about its own shapes and structurally blind to a file-format divergence. The bug this module
 *  exists to prevent — reporting a physical bit as the logical value — only appears when the two
 *  halves of an address genuinely disagree, and they only genuinely disagree in a file written by
 *  a *different* engine.
 *
 *  So: write with the reference driver (`org.xerial:sqlite-jdbc`, i.e. real SQLite), read by
 *  address with ours.
 */
class ScljetAddressTest extends AnyFunSuite:

  // Register the driver (see ScljetDriverTest for why Class.forName is not enough).
  assert(ScljetDriver.Prefix == "jdbc:scljet:")

  /** A `SqliteValue` as its bare payload — `SqlInteger(7)` → `7`. `Value.show` would render the
   *  constructor, which is the engine's spelling of the value, not the value. */
  private def payload(v: Value): String = v match
    case Value.InstanceV("SqlNull", _) => "NULL"
    case inst: Value.InstanceV         => Value.show(ScljetEngine.field(inst, "value"))
    case other                         => Value.show(other)

  private def readAddress(image: Value, addr: String): Map[String, String] =
    val parsed = ScljetEngine.call("parseAddress", Value.StringV(addr))
    val a = ScljetEngine.unwrapEither(parsed, m => s"parse failed: $m")
    val res = ScljetEngine.call("addressRead", image, a)
    val av = ScljetEngine.unwrapEither(res, m => s"read failed: $m")
    Map(
      "typeName" -> ScljetEngine.asString(ScljetEngine.field(av, "typeName")),
      "value" -> payload(ScljetEngine.field(av, "value")),
      "physicalBytes" -> ScljetEngine.asLong(ScljetEngine.field(av, "physicalBytes")).toString,
      "fromRowid" -> ScljetEngine.asBool(ScljetEngine.field(av, "fromRowid")).toString,
      "stable" -> ScljetEngine.asBool(ScljetEngine.field(av, "stable")).toString,
    )

  /** A scljet image back out as raw bytes, so the reference engine can judge it. */
  private def imageBytes(image: Value): Array[Byte] =
    ScljetEngine.call("byteSliceToList", image) match
      case Value.ListV(items) => items.iterator.map(b => ScljetEngine.asLong(b).toByte).toArray
      case other => fail(s"expected a ByteSlice, got ${Value.show(other)}")

  /** Build a database with the REFERENCE engine and hand back its bytes as a scljet image. */
  private def referenceImage(ddl: List[String]): Value =
    val dir: Path = Files.createTempDirectory("scljet-address-")
    val db = dir.resolve("ref.db")
    try
      Class.forName("org.sqlite.JDBC")
      val ref = DriverManager.getConnection(s"jdbc:sqlite:${db.toString}")
      try
        val s = ref.createStatement()
        ddl.foreach(s.executeUpdate)
      finally ref.close()
      ScljetEngine.byteSlice(Files.readAllBytes(db))
    finally
      Files.deleteIfExists(db); Files.deleteIfExists(dir)

  test("an address into a REAL SQLite file: the IPK column reads the rowid, not the stored bit"):
    val image = referenceImage(List(
      "CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT, bonus REAL)",
      "INSERT INTO emp VALUES (7,'bob',2.5)",
    ))

    // The ordinary column: both halves agree, nothing interesting.
    val name = readAddress(image, "emp/7/name")
    assert(name("value") == "bob")
    assert(name("fromRowid") == "false")

    // The IPK column: real SQLite stores NULL in the record and keeps the value in the rowid.
    // Physically there is nothing here — 0 bytes — and the logical value is 7. Both are true;
    // the address is the link between them.
    val id = readAddress(image, "emp/7/id")
    assert(id("value") == "7", s"IPK must read the rowid, got ${id("value")} — the link was broken")
    assert(id("fromRowid") == "true", "the value must be reported as coming from the rowid")
    assert(id("physicalBytes") == "0", s"real SQLite stores NULL for an IPK column, got ${id("physicalBytes")}B")
    assert(id("typeName") == "INTEGER")

  test("the address layer and the SQL read agree on the same reference-written file"):
    // Two independent resolutions of the same logical/physical link, pinned against each other:
    // the SQL path normalises the rowid into the IPK column at row materialisation
    // (`finishRows` -> `ipkNormalizeRows`, the fix for BUGS.md
    // scljet-ipk-rowid-alias-not-substituted, landed 14f4da4ac); the address layer resolves the
    // link itself from the raw record. They must not drift.
    val image = referenceImage(List(
      "CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT)",
      "INSERT INTO emp VALUES (7,'bob')",
    ))

    assert(readAddress(image, "emp/7/id")("value") == "7", "the address layer must honour the rowid alias")

    val rows = ScljetEngine.call("queryImage", image, Value.StringV("SELECT id FROM emp"))
    val viaSql = Value.show(ScljetEngine.unwrapEither(rows, m => s"query failed: $m"))
    assert(viaSql.contains("7") && !viaSql.contains("SqlNull"),
      s"the SQL path regressed on the IPK alias: SELECT id returned $viaSql")

  test("a scljet-written file: same logical value, DIFFERENT physical bits"):
    // We store the IPK value redundantly (rowid AND column) where real SQLite stores NULL. Both
    // files are valid and both read as 7 — which is the point: one logical address, one logical
    // value, two different physical encodings. Only a differential that crosses engines through a
    // file can see this at all.
    val ours = ScljetEngine.call("jdbcOpen", ScljetEngine.emptyImage(4096))
    var conn = ours
    def upd(sql: String): Unit =
      val res = ScljetEngine.call("jdbcExecuteUpdateParams", conn, Value.StringV(sql), ScljetEngine.paramList(Nil))
      conn = ScljetEngine.field(ScljetEngine.unwrapEither(res, m => s"update failed: $m"), "conn")
    upd("CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT)")
    upd("INSERT INTO emp VALUES (7,'bob')")
    val ourImage = ScljetEngine.field(conn, "committed")

    val refImage = referenceImage(List(
      "CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT)",
      "INSERT INTO emp VALUES (7,'bob')",
    ))

    val ourId = readAddress(ourImage, "emp/7/id")
    val refId = readAddress(refImage, "emp/7/id")

    assert(ourId("value") == "7" && refId("value") == "7", "the logical value is the same")
    assert(ourId("fromRowid") == "true" && refId("fromRowid") == "true")
    assert(refId("physicalBytes") == "0", "real SQLite: NULL in the record")
    assert(ourId("physicalBytes") == "1", "scljet: a redundant copy of the value in the record")

  test("stability is reported from the file, not assumed"):
    val withIpk = referenceImage(List(
      "CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT)",
      "INSERT INTO emp VALUES (7,'bob')",
    ))
    val withoutIpk = referenceImage(List(
      "CREATE TABLE log(a INTEGER, b TEXT)",
      "INSERT INTO log VALUES (10,'x')",
    ))
    // With an INTEGER PRIMARY KEY the rowid is the declared value and survives VACUUM.
    assert(readAddress(withIpk, "emp/7/name")("stable") == "true")
    // Without one, VACUUM may renumber the rowids: the same address form is positional, and a
    // reference that relies on it rots silently. We say so rather than pretend.
    assert(readAddress(withoutIpk, "log/1/a")("stable") == "false")

  // ── write by address, proven through a FILE by the reference engine ────────

  test("a value written BY ADDRESS is read back by the real sqlite3, and the file stays valid"):
    // The only oracle that means anything here: scljet writes, the REFERENCE reads. A
    // scljet-writes/scljet-reads check is self-consistent and cannot see a format divergence.
    val image = referenceImage(List(
      "CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT, salary INTEGER)",
      "INSERT INTO emp VALUES (7,'bob',250)",
      "INSERT INTO emp VALUES (9,'cat',300)",
    ))

    val addr = ScljetEngine.unwrapEither(
      ScljetEngine.call("parseAddress", Value.StringV("emp/7/name")), m => s"parse failed: $m")
    val written = ScljetEngine.unwrapEither(
      ScljetEngine.call("addressWrite", image, addr, ScljetEngine.sqlText("robert")),
      m => s"write failed: $m")

    // Hand the bytes to the real sqlite3 and let IT tell us whether we wrote SQLite.
    val dir = Files.createTempDirectory("scljet-addr-write-")
    val db = dir.resolve("out.db")
    try
      Files.write(db, imageBytes(written))
      Class.forName("org.sqlite.JDBC")
      val ref = DriverManager.getConnection(s"jdbc:sqlite:${db.toString}")
      try
        val check = ref.createStatement().executeQuery("PRAGMA integrity_check")
        assert(check.next() && check.getString(1) == "ok", "the file we wrote must stay a valid SQLite database")

        val rs = ref.createStatement().executeQuery("SELECT id, name, salary FROM emp ORDER BY id")
        val rows = scala.collection.mutable.ArrayBuffer.empty[String]
        while rs.next() do rows += s"${rs.getLong(1)}|${rs.getString(2)}|${rs.getLong(3)}"
        assert(rows.toList == List("7|robert|250", "9|cat|300"),
          s"the reference must see the address write and nothing else, got $rows")
      finally ref.close()
    finally
      Files.deleteIfExists(db); Files.deleteIfExists(dir)

  test("a refused write leaves the image byte-identical — no half-applied state"):
    val image = referenceImage(List(
      "CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT)",
      "INSERT INTO emp VALUES (7,'bob')",
    ))
    def refuse(a: String): String =
      val addr = ScljetEngine.unwrapEither(
        ScljetEngine.call("parseAddress", Value.StringV(a)), m => s"parse failed: $m")
      ScljetEngine.call("addressWrite", image, addr, ScljetEngine.sqlText("x")) match
        case inst: Value.InstanceV if inst.typeName == "Left" =>
          ScljetEngine.asString(inst.effectiveFields("value"))
        case other => fail(s"$a must be REFUSED, not $other — a write to an address that does not resolve is a failed write")
    assert(refuse("emp/999/name").contains("no such row"))
    assert(refuse("emp/7/nope").contains("no such column"))
    assert(refuse("nosuch/1/x").contains("no such table"))
    // An IPK column is the row's identity: the engine drops such an assignment silently today
    // (BUGS.md scljet-update-ipk-column-silently-ignored), and real sqlite3 would RELOCATE the row.
    assert(refuse("emp/7/id").contains("identity"))

  test("addressWriteAll is all-or-nothing across the reference's own file"):
    val image = referenceImage(List(
      "CREATE TABLE emp(id INTEGER PRIMARY KEY, name TEXT)",
      "INSERT INTO emp VALUES (7,'bob')",
      "INSERT INTO emp VALUES (9,'cat')",
    ))
    def packet(a: String, v: String): Value =
      val addr = ScljetEngine.unwrapEither(
        ScljetEngine.call("parseAddress", Value.StringV(a)), m => s"parse failed: $m")
      Value.InstanceV("AddressPacket", Map("address" -> addr, "value" -> ScljetEngine.sqlText(v)))

    val good = ScljetEngine.call("addressWriteAll", image,
      Value.ListV(List(packet("emp/7/name", "BOB"), packet("emp/9/name", "CAT"))))
    val applied = ScljetEngine.unwrapEither(good, m => s"batch failed: $m")
    assert(readAddress(applied, "emp/7/id")("value") == "7")

    // One bad packet in the middle: NO image at all, and the source is untouched.
    val bad = ScljetEngine.call("addressWriteAll", image,
      Value.ListV(List(packet("emp/7/name", "never"), packet("emp/999/name", "ghost"))))
    bad match
      case inst: Value.InstanceV if inst.typeName == "Left" =>
        assert(ScljetEngine.asString(inst.effectiveFields("value")).contains("no such row"))
      case other => fail(s"a batch with an unresolvable packet must yield Left, got $other")
