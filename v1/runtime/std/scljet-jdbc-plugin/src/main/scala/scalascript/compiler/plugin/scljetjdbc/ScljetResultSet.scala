package scalascript.compiler.plugin.scljetjdbc

import scalascript.interpreter.Value

import java.math.BigDecimal
import java.sql.{ResultSet, ResultSetMetaData, SQLException, Statement, Types}
import ProxySupport.*

/** Forward-only, read-only cursor over the façade's `JdbcResultSet`.
 *
 *  The `JdbcResultSet` is threaded as an opaque `Value`; navigation and reads
 *  delegate to the pure façade getters (`rsNext`, `rsGetLong`, `rsIsNull`, …),
 *  so the SqliteValue→JDBC type mapping is exactly the engine's (proven equal to
 *  reference sqlite3 by the portable conformance suite). */
final class ScljetResultSetState(
    val statement: Statement,
    initial: Value):
  private var rsValue: Value = initial   // façade JdbcResultSet (pos starts at -1)
  private var pos: Int = -1
  var lastWasNull: Boolean = false
  var closed: Boolean = false

  private def checkOpen(): Unit =
    if closed then throw SQLException("scljet JDBC: result set is closed")

  def rowCount: Int = ScljetEngine.asLong(ScljetEngine.call("rsRowCount", rsValue)).toInt

  def next(): Boolean =
    checkOpen()
    rsValue = ScljetEngine.call("rsNext", rsValue)
    pos += 1
    ScljetEngine.asBool(ScljetEngine.call("rsHasRow", rsValue))

  private def requireRow(): Unit =
    checkOpen()
    if pos < 0 then throw SQLException("scljet JDBC: no current row — call next() first")
    if !ScljetEngine.asBool(ScljetEngine.call("rsHasRow", rsValue)) then
      throw SQLException("scljet JDBC: no current row (cursor past end)")

  private def markNull(col: Int): Boolean =
    lastWasNull = ScljetEngine.asBool(ScljetEngine.call("rsIsNull", rsValue, Value.intV(col.toLong)))
    lastWasNull

  private def rawValueAt(col: Int): Value =
    ScljetEngine.call("rsValueAt", rsValue, Value.intV(col.toLong))

  // ── typed getters (1-based column index) ──────────────────────────────────
  def getLong(col: Int): Long =
    requireRow(); markNull(col); ScljetEngine.asLong(ScljetEngine.call("rsGetLong", rsValue, Value.intV(col.toLong)))
  def getInt(col: Int): Int =
    requireRow(); markNull(col); ScljetEngine.asLong(ScljetEngine.call("rsGetInt", rsValue, Value.intV(col.toLong))).toInt
  def getDouble(col: Int): Double =
    requireRow(); markNull(col); ScljetEngine.asDouble(ScljetEngine.call("rsGetDouble", rsValue, Value.intV(col.toLong)))
  def getBoolean(col: Int): Boolean =
    requireRow(); markNull(col); ScljetEngine.asBool(ScljetEngine.call("rsGetBoolean", rsValue, Value.intV(col.toLong)))
  def getString(col: Int): String =
    requireRow()
    if markNull(col) then null
    else ScljetEngine.asString(ScljetEngine.call("rsGetString", rsValue, Value.intV(col.toLong)))
  def getBigDecimal(col: Int): BigDecimal =
    requireRow()
    if markNull(col) then null
    else
      rawValueAt(col) match
        case v @ Value.InstanceV("SqlInteger", _) => BigDecimal.valueOf(ScljetEngine.asLong(ScljetEngine.field(v, "value")))
        case v @ Value.InstanceV("SqlReal", _)    => BigDecimal.valueOf(ScljetEngine.asDouble(ScljetEngine.field(v, "value")))
        case _ =>
          val s = getString(col)
          try BigDecimal(s.trim)
          catch case _: NumberFormatException => throw SQLException(s"scljet JDBC: '$s' is not a valid decimal")
  def getBytes(col: Int): Array[Byte] =
    requireRow()
    if markNull(col) then null
    else rawValueAt(col) match
      case v @ Value.InstanceV("SqlBlob", _) =>
        ScljetEngine.field(v, "value") match
          case slice => blobBytes(slice)
      case _ => getString(col) match
        case null => null
        case s    => s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
  def getObject(col: Int): AnyRef =
    requireRow()
    rawValueAt(col) match
      case Value.InstanceV("SqlNull", _)     => lastWasNull = true; null
      case v @ Value.InstanceV("SqlInteger", _) => lastWasNull = false; java.lang.Long.valueOf(ScljetEngine.asLong(ScljetEngine.field(v, "value")))
      case v @ Value.InstanceV("SqlReal", _)    => lastWasNull = false; java.lang.Double.valueOf(ScljetEngine.asDouble(ScljetEngine.field(v, "value")))
      case v @ Value.InstanceV("SqlText", _)    => lastWasNull = false; ScljetEngine.asString(ScljetEngine.field(v, "value"))
      case v @ Value.InstanceV("SqlBlob", _)    => lastWasNull = false; blobBytes(ScljetEngine.field(v, "value"))
      case other                                => lastWasNull = false; Value.show(other)

  private def blobBytes(slice: Value): Array[Byte] =
    ScljetEngine.call("byteSliceToList", slice) match
      case Value.ListV(items) => items.iterator.map(b => ScljetEngine.asLong(b).toByte).toArray
      case _                  => Array.emptyByteArray

  def findColumn(label: String): Int =
    checkOpen()
    val idx = ScljetEngine.asLong(ScljetEngine.call("rsFindColumn", rsValue, Value.StringV(label))).toInt
    if idx <= 0 then throw SQLException(s"scljet JDBC: column '$label' not found")
    idx

  def columnCount: Int = ScljetEngine.asLong(ScljetEngine.call("rsColumnCount", rsValue)).toInt
  def columnLabel(col: Int): String = ScljetEngine.asString(ScljetEngine.call("rsColumnLabel", rsValue, Value.intV(col.toLong)))

  /** Storage-class names of the first data row (row 0), computed once — the
   *  best-effort per-column type for `ResultSetMetaData`.  All "NULL" when the
   *  result is empty.  Reads a fresh advance of the initial snapshot so it never
   *  disturbs the live cursor. */
  lazy val columnTypeNames: Array[String] =
    val n = columnCount
    val out = Array.fill(n)("NULL")
    val row0 = ScljetEngine.call("rsNext", initial) // pos 0 on a fresh copy
    if ScljetEngine.asBool(ScljetEngine.call("rsHasRow", row0)) then
      var c = 1
      while c <= n do
        ScljetEngine.call("rsValueAt", row0, Value.intV(c.toLong)) match
          case Value.InstanceV(tn, _) => out(c - 1) = tn
          case _                      => ()
        c += 1
    out

  def isBeforeFirst: Boolean = !closed && pos < 0 && rowCount > 0
  def isAfterLast:  Boolean = !closed && pos >= rowCount && rowCount >= 0 && pos >= 0
  def isFirst:      Boolean = pos == 0 && rowCount > 0
  def row:          Int     = if pos < 0 || pos >= rowCount then 0 else pos + 1

object ScljetResultSet:
  def make(statement: Statement, initial: Value): ResultSet =
    val state = ScljetResultSetState(statement, initial)
    ProxySupport.proxy(classOf[ResultSet], ResultSetHandler(state))

  def metaData(state: ScljetResultSetState): ResultSetMetaData =
    ProxySupport.proxy(classOf[ResultSetMetaData], ResultSetMetaHandler(state))

  /** Storage-class → java.sql.Types, per specs/scljet-jdbc.md §"Value → Types". */
  def sqlType(typeName: String): Int = typeName match
    case "SqlInteger" => Types.BIGINT
    case "SqlReal"    => Types.DOUBLE
    case "SqlText"    => Types.VARCHAR
    case "SqlBlob"    => Types.BLOB
    case _            => Types.NULL
  def sqlTypeName(typeName: String): String = typeName match
    case "SqlInteger" => "INTEGER"
    case "SqlReal"    => "REAL"
    case "SqlText"    => "TEXT"
    case "SqlBlob"    => "BLOB"
    case _            => "NULL"
  def columnClassName(typeName: String): String = typeName match
    case "SqlInteger" => "java.lang.Long"
    case "SqlReal"    => "java.lang.Double"
    case "SqlText"    => "java.lang.String"
    case "SqlBlob"    => "[B"
    case _            => "java.lang.Object"

final class ResultSetHandler(state: ScljetResultSetState) extends ProxyHandler("ResultSet"):
  private def colOf(args: Array[AnyRef], k: Int): Int =
    if isInt(args, k) then argInt(args, k) else state.findColumn(argStr(args, k))

  def dispatch(proxy: AnyRef, name: String, args: Array[AnyRef]): AnyRef = name match
    case "next"        => boxB(state.next())
    case "close"       => state.closed = true; unit
    case "isClosed"    => boxB(state.closed)
    case "wasNull"     => boxB(state.lastWasNull)
    case "getLong"     => boxL(state.getLong(colOf(args, 0)))
    case "getInt"      => boxI(state.getInt(colOf(args, 0)))
    case "getShort"    => java.lang.Short.valueOf(state.getInt(colOf(args, 0)).toShort)
    case "getByte"     => java.lang.Byte.valueOf(state.getInt(colOf(args, 0)).toByte)
    case "getDouble"   => boxD(state.getDouble(colOf(args, 0)))
    case "getFloat"    => boxF(state.getDouble(colOf(args, 0)).toFloat)
    case "getBoolean"  => boxB(state.getBoolean(colOf(args, 0)))
    case "getString"   => state.getString(colOf(args, 0))
    case "getNString"  => state.getString(colOf(args, 0))
    case "getBigDecimal" => state.getBigDecimal(colOf(args, 0))
    case "getBytes"    => state.getBytes(colOf(args, 0))
    case "getObject"   => state.getObject(colOf(args, 0))
    case "findColumn"  => boxI(state.findColumn(argStr(args, 0)))
    case "getMetaData" => ScljetResultSet.metaData(state)
    case "getStatement" => state.statement
    case "getType"        => boxI(ResultSet.TYPE_FORWARD_ONLY)
    case "getConcurrency" => boxI(ResultSet.CONCUR_READ_ONLY)
    case "getFetchDirection" => boxI(ResultSet.FETCH_FORWARD)
    case "setFetchDirection" =>
      if argInt(args, 0) == ResultSet.FETCH_FORWARD then unit else throw nse("setFetchDirection(non-forward)")
    case "getFetchSize"   => boxI(0)
    case "setFetchSize"   => unit
    case "getHoldability" => boxI(ResultSet.CLOSE_CURSORS_AT_COMMIT)
    case "getWarnings"    => null
    case "clearWarnings"  => unit
    case "getRow"         => boxI(state.row)
    case "isBeforeFirst"  => boxB(state.isBeforeFirst)
    case "isAfterLast"    => boxB(state.isAfterLast)
    case "isFirst"        => boxB(state.isFirst)
    case "isLast"         => boxB(state.row == state.rowCount && state.rowCount > 0)
    case "getCursorName"  => throw nse("getCursorName")
    case _                => throw nse(name)

final class ResultSetMetaHandler(state: ScljetResultSetState) extends ProxyHandler("ResultSetMetaData"):
  private def typeName(col: Int): String =
    val types = state.columnTypeNames
    if col >= 1 && col <= types.length then types(col - 1) else "NULL"

  def dispatch(proxy: AnyRef, name: String, args: Array[AnyRef]): AnyRef = name match
    case "getColumnCount"     => boxI(state.columnCount)
    case "getColumnLabel"     => state.columnLabel(argInt(args, 0))
    case "getColumnName"      => state.columnLabel(argInt(args, 0))
    case "getColumnType"      => boxI(ScljetResultSet.sqlType(typeName(argInt(args, 0))))
    case "getColumnTypeName"  => ScljetResultSet.sqlTypeName(typeName(argInt(args, 0)))
    case "getColumnClassName" => ScljetResultSet.columnClassName(typeName(argInt(args, 0)))
    case "isNullable"         => boxI(ResultSetMetaData.columnNullableUnknown)
    case "isSigned"           => val t = typeName(argInt(args, 0)); boxB(t == "SqlInteger" || t == "SqlReal")
    case "isCaseSensitive"    => boxB(typeName(argInt(args, 0)) == "SqlText")
    case "getPrecision"       => boxI(0)
    case "getScale"           => boxI(0)
    case "isAutoIncrement"    => boxB(false)
    case "isCurrency"         => boxB(false)
    case "isSearchable"       => boxB(true)
    case "isReadOnly"         => boxB(true)
    case "isWritable"         => boxB(false)
    case "isDefinitelyWritable" => boxB(false)
    case "getColumnDisplaySize" => boxI(40)
    case "getSchemaName"      => ""
    case "getTableName"       => ""
    case "getCatalogName"     => ""
    case _                    => throw nse(name)
