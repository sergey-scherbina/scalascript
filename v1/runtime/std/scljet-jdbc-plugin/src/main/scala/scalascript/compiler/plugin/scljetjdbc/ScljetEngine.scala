package scalascript.compiler.plugin.scljetjdbc

import scalascript.interpreter.{Interpreter, Value}
import scalascript.parser.Parser

import java.sql.SQLException

/** Bridge from JVM Scala into the pure `scljet` SQLite engine.
 *
 *  The engine (`scljet/sql.ssc`, `scljet/jdbc.ssc`, …) is ScalaScript, not Scala.
 *  The only clean way to reach it from the JVM is the embedded tree-walking
 *  [[scalascript.interpreter.Interpreter]]: we bootstrap ONE interpreter that
 *  imports the portable JDBC façade + the engine index — exactly the import
 *  structure the green conformance case `tests/conformance/scljet-jdbc-basic.ssc`
 *  uses — so every façade function (`jdbcOpen`, `jdbcExecuteQueryParams`,
 *  `rsGetLong`, …) and every value constructor (`SqlInteger`, `SqlText`,
 *  `emptyDatabase`, `byteSliceUnsafe`, …) lands in `globals`.  The JVM shim then
 *  drives the façade purely through [[Interpreter.invoke]], threading the
 *  database image and result-set cursor as OPAQUE `Value`s that never have to be
 *  structurally understood on the JVM side.
 *
 *  The interpreter is single-threaded (mutable AST-identity caches), so every
 *  `invoke` is serialized on the engine monitor.  JDBC connections are normally
 *  single-threaded anyway; this only serializes the rare concurrent-connection
 *  case, which is acceptable for an in-memory engine.
 */
object ScljetEngine:

  /** Located `std/scljet` directory (dev tree or installed), used as the import
   *  base dir so `[…](index.ssc)` / `[…](jdbc.ssc)` resolve relatively. */
  private def scljetDir(): Option[os.Path] =
    // ImportResolver.stdPath is the dir CONTAINING `std/`; scljet installs as
    // std/scljet (a symlink to the repo-root engine in the dev tree).
    scalascript.imports.ImportResolver.stdPath
      .map(_ / "std" / "scljet")
      .filter(p => os.exists(p / "index.ssc") && os.exists(p / "jdbc.ssc") && os.exists(p / "address.ssc"))

  // A no-side-effect bootstrap module: import the façade + engine so every name
  // is bound in globals.  Relative imports resolve against baseDir = scljetDir.
  private val bootstrapRelative: String =
    """# scljet JDBC bridge bootstrap
      |
      |[ByteSlice, SqliteValue, SqlNull, SqlInteger, SqlReal, SqlText, SqlBlob, byteSliceToList, emptyDatabase, buildTableDatabase](index.ssc)
      |[JdbcConnection, JdbcUpdate, JdbcResultSet, jdbcOpen, jdbcCurrent, jdbcSetAutoCommit, jdbcCommit, jdbcRollback, jdbcExecuteUpdate, jdbcExecuteUpdateParams, jdbcExecuteQuery, jdbcExecuteQueryParams, rsNext, rsHasRow, rsIsNull, rsGetLong, rsGetInt, rsGetDouble, rsGetString, rsGetBoolean, rsFindColumn, rsColumnCount, rsColumnLabel, rsRowCount](jdbc.ssc)
      |[SqliteAddress, AddressedValue, parseAddress, renderAddress, addressRead](address.ssc)
      |""".stripMargin

  // Fallback bootstrap using library-path (`std/scljet/…`) resolution — used
  // when the dir can't be located directly but ssc.std.path discovery works.
  private val bootstrapLibrary: String =
    """# scljet JDBC bridge bootstrap
      |
      |[ByteSlice, SqliteValue, SqlNull, SqlInteger, SqlReal, SqlText, SqlBlob, byteSliceToList, emptyDatabase, buildTableDatabase](std/scljet/index.ssc)
      |[JdbcConnection, JdbcUpdate, JdbcResultSet, jdbcOpen, jdbcCurrent, jdbcSetAutoCommit, jdbcCommit, jdbcRollback, jdbcExecuteUpdate, jdbcExecuteUpdateParams, jdbcExecuteQuery, jdbcExecuteQueryParams, rsNext, rsHasRow, rsIsNull, rsGetLong, rsGetInt, rsGetDouble, rsGetString, rsGetBoolean, rsFindColumn, rsColumnCount, rsColumnLabel, rsRowCount](std/scljet/jdbc.ssc)
      |[SqliteAddress, AddressedValue, parseAddress, renderAddress, addressRead](std/scljet/address.ssc)
      |""".stripMargin

  private lazy val interp: Interpreter =
    // Swallow the façade's stdout (the bootstrap prints nothing, but be safe).
    val sink = java.io.PrintStream(java.io.OutputStream.nullOutputStream())
    scljetDir() match
      case Some(dir) =>
        val i = Interpreter(out = sink, baseDir = Some(dir))
        i.run(Parser.parse(bootstrapRelative))
        i
      case None =>
        val i = Interpreter(out = sink)
        i.run(Parser.parse(bootstrapLibrary))
        i

  /** Force bootstrap; surfaces a clear error if the engine can't be located. */
  def ensureLoaded(): Unit =
    try interp.globalsView
    catch case e: Throwable =>
      throw SQLException(s"scljet JDBC: cannot load the scljet engine — ${e.getMessage}", "08001", e)

  private def global(name: String): Value =
    interp.globalsView.getOrElse(name,
      throw SQLException(s"scljet JDBC: engine function '$name' not found (bootstrap failed?)", "08003"))

  /** Invoke a scljet global function by name.  Serialized on the interpreter. */
  def call(name: String, args: Value*): Value = synchronized:
    interp.invoke(global(name), args.toList)

  // ── Value construction (JVM → scljet) ────────────────────────────────────

  // NOTE: build the SqliteValue leaves DIRECTLY, not via `call("SqlInteger", …)`.
  // `Interpreter.globalsView` (which `call` looks up through) replaces case-class
  // constructors and case objects with a synthetic metadata-only placeholder
  // `FunV(body = Lit.Unit())` — invoking it yields `UnitV`, not a SqliteValue.
  // `Value.singleValue` reproduces the real single-field ctor's representation
  // (fieldsArr + fieldNames = ["value"]); `SqlNull` is a no-field case object.
  def sqlInteger(n: Long): Value = Value.singleValue("SqlInteger", Value.intV(n))
  def sqlReal(d: Double): Value   = Value.singleValue("SqlReal", Value.doubleV(d))
  def sqlText(s: String): Value   = Value.singleValue("SqlText", Value.StringV(s))
  def sqlBlob(bytes: Array[Byte]): Value = Value.singleValue("SqlBlob", byteSlice(bytes))
  def sqlNull: Value              = Value.InstanceV("SqlNull", Map.empty)

  /** `List[SqliteValue]` for the params vector. */
  def paramList(params: List[Value]): Value = Value.ListV(params)

  /** `ByteSlice` from raw bytes via the engine's own `byteSliceUnsafe`. */
  def byteSlice(bytes: Array[Byte]): Value =
    val ints = Value.ListV(bytes.iterator.map(b => Value.intV((b & 0xff).toLong)).toList)
    call("byteSliceUnsafe", ints)

  /** A fresh empty in-memory database image, ready for `CREATE TABLE`.
   *
   *  `emptyDatabase` produces a schema-format-0 / encoding-0 file (real SQLite
   *  fixes both on the first write). The pure engine's write path, however,
   *  rejects a CREATE against that state ("text cannot be decoded before the
   *  database encoding is fixed", then "schema format zero requires an empty
   *  schema"). We initialize the header to the same state a real DB has after its
   *  first write — the engine's own `tableFileHeader` values: schema format 4
   *  (byte 47) and text encoding UTF-8 = 1 (byte 59). */
  def emptyImage(pageSize: Int): Value =
    val base = unwrapEither(call("emptyDatabase", Value.intV(pageSize.toLong)),
      err => s"cannot create empty database: $err")
    val enc = unwrapEither(call("byteSliceUpdated", base, Value.intV(59L), Value.intV(1L)),
      err => s"cannot initialize database encoding: $err")
    unwrapEither(call("byteSliceUpdated", enc, Value.intV(47L), Value.intV(4L)),
      err => s"cannot initialize schema format: $err")

  // ── Value destructuring (scljet → JVM) ───────────────────────────────────

  def asLong(v: Value): Long = v match
    case Value.IntV(n)    => n
    case Value.DoubleV(d) => d.toLong
    case Value.BoolV(b)   => if b then 1L else 0L
    case other            => throw SQLException(s"scljet JDBC: expected integer, got ${Value.show(other)}")

  def asDouble(v: Value): Double = v match
    case Value.DoubleV(d) => d
    case Value.IntV(n)    => n.toDouble
    case other            => throw SQLException(s"scljet JDBC: expected real, got ${Value.show(other)}")

  def asString(v: Value): String = v match
    case Value.StringV(s) => s
    case other            => Value.show(other)

  def asBool(v: Value): Boolean = v match
    case Value.BoolV(b) => b
    case Value.IntV(n)  => n != 0L
    case other          => throw SQLException(s"scljet JDBC: expected boolean, got ${Value.show(other)}")

  /** Read a named field from a case-class `InstanceV`. */
  def field(v: Value, name: String): Value = v match
    case inst: Value.InstanceV =>
      inst.effectiveFields.getOrElse(name,
        throw SQLException(s"scljet JDBC: field '$name' missing on ${inst.typeName}"))
    case other =>
      throw SQLException(s"scljet JDBC: expected a record, got ${Value.show(other)}")

  /** Unwrap `Either[E, A]`: `Right(a)` → `a`; `Left(e)` → SQLException via `msg`. */
  def unwrapEither(v: Value, msg: String => String): Value = v match
    case inst: Value.InstanceV if inst.typeName == "Right" =>
      inst.effectiveFields.getOrElse("value", Value.UnitV)
    case inst: Value.InstanceV if inst.typeName == "Left" =>
      val e = inst.effectiveFields.getOrElse("value", Value.UnitV)
      throw ScljetErrors.toSqlException(leftMessage(e), msg)
    case other =>
      throw SQLException(s"scljet JDBC: expected Either, got ${Value.show(other)}")

  /** Best-effort human message from a `Left` payload — a bare String, or a
   *  record with a `message` field (e.g. `ByteError`/`SqliteError`). */
  private def leftMessage(e: Value): String = e match
    case Value.StringV(s) => s
    case inst: Value.InstanceV =>
      inst.effectiveFields.get("message").map(asString)
        .getOrElse(Value.show(inst))
    case other => Value.show(other)
