package scalascript.compiler.plugin.scljetjdbc

import java.math.BigDecimal
import java.sql.{ResultSet, ResultSetMetaData, SQLException, Statement, Types}
import ProxySupport.*

/** One column of a static result: its label and its `java.sql.Types` code. */
final case class StaticColumn(label: String, sqlType: Int)

/** A forward-only, read-only `ResultSet` over rows materialized on the JVM.
 *
 *  [[ScljetResultSet]] wraps an opaque façade `JdbcResultSet` Value and can only
 *  serve rows the engine produced.  `getGeneratedKeys` and the
 *  `DatabaseMetaData` catalog queries, by contrast, are JVM-side constructs —
 *  the last-insert rowid lives in the shim, and the JDBC catalog row shapes
 *  (`getTables`' 10 columns, `getColumns`' 24) exist nowhere in the engine.  This
 *  is their common substrate: a plain `(columns, rows)` cursor with the same
 *  proxy plumbing, where a `null` cell is SQL NULL.
 */
final class StaticResultSetState(
    val columns: List[StaticColumn],
    val rows: List[List[AnyRef]],
    val statement: Statement | Null):
  private val rowsArr = rows.map(_.toArray).toArray
  private var pos: Int = -1
  var lastWasNull: Boolean = false
  var closed: Boolean = false

  private def checkOpen(): Unit =
    if closed then throw SQLException("scljet JDBC: result set is closed")

  def next(): Boolean =
    checkOpen()
    if pos < rowsArr.length then pos += 1
    pos < rowsArr.length

  def rowCount: Int = rowsArr.length
  def row: Int = if pos < 0 || pos >= rowsArr.length then 0 else pos + 1
  def columnCount: Int = columns.length

  def columnLabel(col: Int): String =
    if col < 1 || col > columns.length then
      throw SQLException(s"scljet JDBC: column index $col out of range 1..${columns.length}")
    columns(col - 1).label

  def columnType(col: Int): Int =
    if col < 1 || col > columns.length then
      throw SQLException(s"scljet JDBC: column index $col out of range 1..${columns.length}")
    columns(col - 1).sqlType

  /** JDBC label lookup is case-insensitive; the first match wins. */
  def findColumn(label: String): Int =
    checkOpen()
    columns.indexWhere(_.label.equalsIgnoreCase(label)) match
      case -1 => throw SQLException(s"scljet JDBC: column '$label' not found")
      case i  => i + 1

  private def valueAt(col: Int): AnyRef =
    checkOpen()
    if pos < 0 then throw SQLException("scljet JDBC: no current row — call next() first")
    if pos >= rowsArr.length then throw SQLException("scljet JDBC: no current row (cursor past end)")
    if col < 1 || col > columns.length then
      throw SQLException(s"scljet JDBC: column index $col out of range 1..${columns.length}")
    val v = rowsArr(pos)(col - 1)
    lastWasNull = v == null
    v

  def getObject(col: Int): AnyRef = valueAt(col)

  def getString(col: Int): String = valueAt(col) match
    case null      => null
    case s: String => s
    case other     => other.toString

  def getLong(col: Int): Long = valueAt(col) match
    case null              => 0L
    case n: java.lang.Number => n.longValue
    case s: String         => parseLong(s)
    case other             => throw SQLException(s"scljet JDBC: '$other' is not an integer")

  def getDouble(col: Int): Double = valueAt(col) match
    case null                => 0.0
    case n: java.lang.Number => n.doubleValue
    case s: String           => parseDouble(s)
    case other               => throw SQLException(s"scljet JDBC: '$other' is not a real")

  def getBoolean(col: Int): Boolean = valueAt(col) match
    case null                => false
    case b: java.lang.Boolean => b.booleanValue
    case n: java.lang.Number => n.longValue != 0L
    case other               => other.toString.nonEmpty && other.toString != "0"

  def getBigDecimal(col: Int): BigDecimal = valueAt(col) match
    case null                     => null
    case d: BigDecimal            => d
    case n: java.lang.Number      => BigDecimal.valueOf(n.doubleValue)
    case s: String                =>
      try BigDecimal(s.trim)
      catch case _: NumberFormatException => throw SQLException(s"scljet JDBC: '$s' is not a valid decimal")
    case other                    => throw SQLException(s"scljet JDBC: '$other' is not a decimal")

  private def parseLong(s: String): Long =
    try java.lang.Long.parseLong(s.trim)
    catch case _: NumberFormatException => throw SQLException(s"scljet JDBC: '$s' is not an integer")

  private def parseDouble(s: String): Double =
    try java.lang.Double.parseDouble(s.trim)
    catch case _: NumberFormatException => throw SQLException(s"scljet JDBC: '$s' is not a real")

  def isBeforeFirst: Boolean = !closed && pos < 0 && rowsArr.nonEmpty
  def isAfterLast: Boolean   = !closed && pos >= rowsArr.length && rowsArr.nonEmpty
  def isFirst: Boolean       = pos == 0 && rowsArr.nonEmpty
  def isLast: Boolean        = pos >= 0 && pos == rowsArr.length - 1 && rowsArr.nonEmpty

object ScljetStaticResultSet:
  def make(columns: List[StaticColumn], rows: List[List[AnyRef]], statement: Statement | Null = null): ResultSet =
    val state = StaticResultSetState(columns, rows, statement)
    ProxySupport.proxy(classOf[ResultSet], StaticResultSetHandler(state))

  /** `java.sql.Types` → the type name reported by `ResultSetMetaData`. */
  def typeName(t: Int): String = t match
    case Types.BIGINT   => "BIGINT"
    case Types.INTEGER  => "INTEGER"
    case Types.SMALLINT => "SMALLINT"
    case Types.DOUBLE   => "DOUBLE"
    case Types.VARCHAR  => "VARCHAR"
    case Types.BLOB     => "BLOB"
    case Types.BOOLEAN  => "BOOLEAN"
    case Types.NUMERIC  => "NUMERIC"
    case _              => "NULL"

  def className(t: Int): String = t match
    case Types.BIGINT   => "java.lang.Long"
    case Types.INTEGER  => "java.lang.Integer"
    case Types.SMALLINT => "java.lang.Short"
    case Types.DOUBLE   => "java.lang.Double"
    case Types.VARCHAR  => "java.lang.String"
    case Types.BLOB     => "[B"
    case Types.BOOLEAN  => "java.lang.Boolean"
    case Types.NUMERIC  => "java.math.BigDecimal"
    case _              => "java.lang.Object"

final class StaticResultSetHandler(state: StaticResultSetState) extends ProxyHandler("ResultSet"):
  private def colOf(args: Array[AnyRef], k: Int): Int =
    if isInt(args, k) then argInt(args, k) else state.findColumn(argStr(args, k))

  def dispatch(proxy: AnyRef, name: String, args: Array[AnyRef]): AnyRef = name match
    case "next"       => boxB(state.next())
    case "close"      => state.closed = true; unit
    case "isClosed"   => boxB(state.closed)
    case "wasNull"    => boxB(state.lastWasNull)
    case "getString" | "getNString" => state.getString(colOf(args, 0))
    case "getLong"    => boxL(state.getLong(colOf(args, 0)))
    case "getInt"     => boxI(state.getLong(colOf(args, 0)).toInt)
    case "getShort"   => java.lang.Short.valueOf(state.getLong(colOf(args, 0)).toShort)
    case "getByte"    => java.lang.Byte.valueOf(state.getLong(colOf(args, 0)).toByte)
    case "getDouble"  => boxD(state.getDouble(colOf(args, 0)))
    case "getFloat"   => boxF(state.getDouble(colOf(args, 0)).toFloat)
    case "getBoolean" => boxB(state.getBoolean(colOf(args, 0)))
    case "getBigDecimal" => state.getBigDecimal(colOf(args, 0))
    case "getObject"  => state.getObject(colOf(args, 0))
    case "findColumn" => boxI(state.findColumn(argStr(args, 0)))
    case "getMetaData" => ProxySupport.proxy(classOf[ResultSetMetaData], StaticResultSetMetaHandler(state))
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
    case "isLast"         => boxB(state.isLast)
    case _                => throw nse(name)

final class StaticResultSetMetaHandler(state: StaticResultSetState) extends ProxyHandler("ResultSetMetaData"):
  def dispatch(proxy: AnyRef, name: String, args: Array[AnyRef]): AnyRef = name match
    case "getColumnCount"     => boxI(state.columnCount)
    case "getColumnLabel"     => state.columnLabel(argInt(args, 0))
    case "getColumnName"      => state.columnLabel(argInt(args, 0))
    case "getColumnType"      => boxI(state.columnType(argInt(args, 0)))
    case "getColumnTypeName"  => ScljetStaticResultSet.typeName(state.columnType(argInt(args, 0)))
    case "getColumnClassName" => ScljetStaticResultSet.className(state.columnType(argInt(args, 0)))
    case "isNullable"         => boxI(ResultSetMetaData.columnNullableUnknown)
    case "isSigned"           =>
      val t = state.columnType(argInt(args, 0))
      boxB(t == Types.BIGINT || t == Types.INTEGER || t == Types.SMALLINT || t == Types.DOUBLE)
    case "isCaseSensitive"    => boxB(state.columnType(argInt(args, 0)) == Types.VARCHAR)
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
