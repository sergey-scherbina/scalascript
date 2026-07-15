package scalascript.compiler.plugin.scljetjdbc

import scalascript.interpreter.Value

import java.sql.{Connection, PreparedStatement, ResultSet, SQLException, Statement}
import scala.collection.mutable
import ProxySupport.*

/** Mutable state shared by Statement and PreparedStatement. */
final class StatementState(val conn: ScljetConnectionState):
  var currentRs: ResultSet | Null = null
  var updateCount: Long = -1
  var maxRows: Int = 0
  var closed: Boolean = false
  def checkOpen(): Unit =
    if closed then throw SQLException("scljet JDBC: statement is closed")

/** Statement methods shared by both the plain and prepared handlers. */
object StatementCommon:
  def dispatch(
      state: StatementState,
      connProxy: Connection,
      owner: Statement,
      label: String,
      name: String,
      args: Array[AnyRef]): AnyRef =
    def runQuery(sql: String, params: List[Value]): ResultSet =
      state.checkOpen()
      val rs = state.conn.executeQuery(sql, params, owner)
      state.currentRs = rs; state.updateCount = -1
      rs
    def runUpdate(sql: String, params: List[Value]): Long =
      state.checkOpen()
      val (changes, _) = state.conn.executeUpdate(sql, params)
      state.updateCount = changes; state.currentRs = null
      changes
    name match
      case "executeQuery"       => runQuery(argStr(args, 0), Nil)
      case "executeUpdate"      => boxI(runUpdate(argStr(args, 0), Nil).toInt)
      case "executeLargeUpdate" => boxL(runUpdate(argStr(args, 0), Nil))
      case "execute" =>
        val sql = argStr(args, 0)
        if state.conn.isQuery(sql) then { runQuery(sql, Nil); boxB(true) }
        else { runUpdate(sql, Nil); boxB(false) }
      case "getResultSet"        => state.currentRs
      case "getUpdateCount"      => boxI(if state.currentRs != null then -1 else state.updateCount.toInt)
      case "getLargeUpdateCount" => boxL(if state.currentRs != null then -1L else state.updateCount)
      case "getMoreResults"      => state.currentRs = null; boxB(false)
      case "close"               => state.closed = true; unit
      case "isClosed"            => boxB(state.closed)
      case "getConnection"       => connProxy
      case "getMaxRows"          => boxI(state.maxRows)
      case "setMaxRows"          => state.maxRows = argInt(args, 0); unit
      case "getLargeMaxRows"     => boxL(state.maxRows.toLong)
      case "setLargeMaxRows"     => state.maxRows = argLong(args, 0).toInt; unit
      case "getQueryTimeout"     => boxI(0)
      case "setQueryTimeout"     => unit
      case "getFetchSize"        => boxI(0)
      case "setFetchSize"        => unit
      case "getFetchDirection"   => boxI(ResultSet.FETCH_FORWARD)
      case "setFetchDirection"   =>
        if argInt(args, 0) == ResultSet.FETCH_FORWARD then unit
        else throw ProxySupport.nse(s"$label.setFetchDirection(non-forward)")
      case "getResultSetType"        => boxI(ResultSet.TYPE_FORWARD_ONLY)
      case "getResultSetConcurrency" => boxI(ResultSet.CONCUR_READ_ONLY)
      case "getResultSetHoldability" => boxI(ResultSet.CLOSE_CURSORS_AT_COMMIT)
      case "getWarnings"         => null
      case "clearWarnings"       => unit
      case "setEscapeProcessing" => unit
      case "getMaxFieldSize"     => boxI(0)
      case "setMaxFieldSize"     => unit
      case "isPoolable"          => boxB(false)
      case "setPoolable"         => unit
      case "closeOnCompletion"   => unit
      case "isCloseOnCompletion" => boxB(false)
      case "cancel"              => unit
      case "getGeneratedKeys"    => throw ProxySupport.nse(s"$label.getGeneratedKeys")
      case _                     => throw ProxySupport.nse(s"$label.$name")

object ScljetStatement:
  def make(conn: ScljetConnectionState, connProxy: Connection): Statement =
    ProxySupport.proxy(classOf[Statement], StatementHandler(StatementState(conn), connProxy))

final class StatementHandler(state: StatementState, connProxy: Connection) extends ProxyHandler("Statement"):
  def dispatch(proxy: AnyRef, name: String, args: Array[AnyRef]): AnyRef =
    StatementCommon.dispatch(state, connProxy, proxy.asInstanceOf[Statement], "Statement", name, args)

object ScljetPreparedStatement:
  def make(conn: ScljetConnectionState, connProxy: Connection, sql: String): PreparedStatement =
    ProxySupport.proxy(classOf[PreparedStatement], PreparedStatementHandler(StatementState(conn), connProxy, sql))

  /** Count `?` positional parameters, skipping single-quoted strings and
   *  double-quoted identifiers (a pragmatic approximation of the engine lexer). */
  def countParams(sql: String): Int =
    var i = 0; var n = 0; val len = sql.length
    while i < len do
      sql.charAt(i) match
        case '\'' =>
          i += 1
          while i < len && sql.charAt(i) != '\'' do i += 1
        case '"' =>
          i += 1
          while i < len && sql.charAt(i) != '"' do i += 1
        case '?' => n += 1
        case _   => ()
      i += 1
    n

final class PreparedStatementHandler(state: StatementState, connProxy: Connection, sql: String)
    extends ProxyHandler("PreparedStatement"):
  private val params = mutable.LongMap.empty[Value]           // 1-based
  private val batch  = mutable.ArrayBuffer.empty[List[Value]]

  private def set(idx: Int, v: Value): Unit =
    if idx < 1 then throw SQLException(s"scljet JDBC: parameter index $idx must be >= 1")
    params(idx.toLong) = v

  private def boundParams(): List[Value] =
    val n = ScljetPreparedStatement.countParams(sql)
    (1 to n).map { i =>
      params.getOrElse(i.toLong, throw SQLException(s"scljet JDBC: parameter $i is not set"))
    }.toList

  private def objectToSql(v: AnyRef): Value = v match
    case null                       => ScljetEngine.sqlNull
    case n: java.lang.Integer       => ScljetEngine.sqlInteger(n.longValue)
    case n: java.lang.Long          => ScljetEngine.sqlInteger(n.longValue)
    case n: java.lang.Short         => ScljetEngine.sqlInteger(n.longValue)
    case n: java.lang.Byte          => ScljetEngine.sqlInteger(n.longValue)
    case n: java.lang.Double        => ScljetEngine.sqlReal(n.doubleValue)
    case n: java.lang.Float         => ScljetEngine.sqlReal(n.doubleValue)
    case b: java.lang.Boolean       => ScljetEngine.sqlInteger(if b.booleanValue then 1L else 0L)
    case s: String                  => ScljetEngine.sqlText(s)
    case a: Array[Byte]             => ScljetEngine.sqlBlob(a)
    case d: java.math.BigDecimal    => ScljetEngine.sqlText(d.toString)
    case other                      => ScljetEngine.sqlText(other.toString)

  def dispatch(proxy: AnyRef, name: String, args: Array[AnyRef]): AnyRef = name match
    // Parameter setters
    case "setInt" | "setLong" | "setShort" | "setByte" => set(argInt(args, 0), ScljetEngine.sqlInteger(argLong(args, 1))); unit
    case "setDouble" | "setFloat" => set(argInt(args, 0), ScljetEngine.sqlReal(argDouble(args, 1))); unit
    case "setString" | "setNString" => set(argInt(args, 0), ScljetEngine.sqlText(argStr(args, 1))); unit
    case "setBoolean" => set(argInt(args, 0), ScljetEngine.sqlInteger(if argBool(args, 1) then 1L else 0L)); unit
    case "setBytes"   => set(argInt(args, 0), ScljetEngine.sqlBlob(args(1).asInstanceOf[Array[Byte]])); unit
    case "setBigDecimal" =>
      val bd = args(1).asInstanceOf[java.math.BigDecimal]
      set(argInt(args, 0), if bd == null then ScljetEngine.sqlNull else ScljetEngine.sqlText(bd.toString)); unit
    case "setNull"    => set(argInt(args, 0), ScljetEngine.sqlNull); unit
    case "setObject"  => set(argInt(args, 0), objectToSql(args(1))); unit
    case "clearParameters" => params.clear(); unit
    // Prepared execution (no SQL argument)
    case "executeQuery" if args.length == 0 =>
      state.checkOpen()
      val rs = state.conn.executeQuery(sql, boundParams(), proxy.asInstanceOf[java.sql.Statement])
      state.currentRs = rs; state.updateCount = -1; rs
    case "executeUpdate" if args.length == 0 =>
      state.checkOpen()
      val (c, _) = state.conn.executeUpdate(sql, boundParams())
      state.updateCount = c; state.currentRs = null; boxI(c.toInt)
    case "executeLargeUpdate" if args.length == 0 =>
      state.checkOpen()
      val (c, _) = state.conn.executeUpdate(sql, boundParams())
      state.updateCount = c; state.currentRs = null; boxL(c)
    case "execute" if args.length == 0 =>
      state.checkOpen()
      if state.conn.isQuery(sql) then
        val rs = state.conn.executeQuery(sql, boundParams(), proxy.asInstanceOf[java.sql.Statement])
        state.currentRs = rs; state.updateCount = -1; boxB(true)
      else
        val (c, _) = state.conn.executeUpdate(sql, boundParams())
        state.updateCount = c; state.currentRs = null; boxB(false)
    // A PreparedStatement rejects the inherited Statement SQL-string overloads.
    case "executeQuery" | "executeUpdate" | "execute" | "executeLargeUpdate" =>
      throw SQLException(s"scljet JDBC: cannot call $name(String) on a PreparedStatement")
    // Batch (sequential; non-atomic)
    case "addBatch" if args.length == 0 => batch += boundParams(); unit
    case "clearBatch" => batch.clear(); unit
    case "executeBatch" =>
      state.checkOpen()
      val counts = batch.map { p =>
        val (c, _) = state.conn.executeUpdate(sql, p); c.toInt
      }.toArray
      batch.clear()
      counts
    case "getParameterMetaData" => ScljetMeta.parameterMetaData(ScljetPreparedStatement.countParams(sql))
    case "getMetaData" =>
      // ResultSetMetaData without executing DML: only meaningful for a SELECT.
      state.checkOpen()
      if state.conn.isQuery(sql) then
        val rs = state.conn.executeQuery(sql, boundParams(), proxy.asInstanceOf[java.sql.Statement])
        rs.getMetaData
      else null
    case _ =>
      StatementCommon.dispatch(state, connProxy, proxy.asInstanceOf[java.sql.Statement], "PreparedStatement", name, args)
