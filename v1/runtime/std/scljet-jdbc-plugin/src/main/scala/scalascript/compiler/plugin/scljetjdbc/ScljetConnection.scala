package scalascript.compiler.plugin.scljetjdbc

import scalascript.interpreter.Value

import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.sql.{Connection, ResultSet, SQLException, Statement}
import ProxySupport.*

/** JDBC `Connection` state: owns the current database image (as an opaque façade
 *  `JdbcConnection` Value) and the autocommit / transaction threading.
 *
 *  Writes use the whole-image-rewrite model (specs/scljet-jdbc.md §"Connection",
 *  Model A): every `executeUpdate` returns a new image; in autocommit it becomes
 *  the durable image immediately, otherwise it is staged as `working` and
 *  promoted on `commit()`.  For a host file, the durable image is flushed to disk
 *  (read-modify-rewrite) on each durable change. */
final class ScljetConnectionState(
    val url: String,
    initialConn: Value,
    durablePath: Option[Path],
    initialReadOnly: Boolean):
  private var connValue: Value = initialConn
  var autoCommit: Boolean = true
  var readOnly: Boolean = initialReadOnly
  var closed: Boolean = false

  def checkOpen(): Unit =
    if closed then throw SQLException("scljet JDBC: connection is closed", "08003")

  private def flushDurable(): Unit =
    durablePath.foreach { path =>
      val committed = ScljetEngine.field(connValue, "committed")
      ScljetEngine.call("byteSliceToList", committed) match
        case Value.ListV(items) =>
          val bytes = items.iterator.map(b => ScljetEngine.asLong(b).toByte).toArray
          atomicReplace(path, bytes)
        case other =>
          throw SQLException(s"scljet JDBC: cannot serialize image: ${Value.show(other)}")
    }

  /** Write the whole image durably and crash-atomically.
   *
   *  The previous `Files.write(path, bytes)` truncated the target first, so a crash mid-write left
   *  a truncated/corrupt file with the old data already gone, and — with no fsync — even a
   *  returned-OK write could vanish on an OS/host crash. Instead: stage into a sibling temp file,
   *  force it to disk, atomically rename it over the target, then fsync the directory so the rename
   *  itself is durable. At every instant the target is either the complete old image or the
   *  complete new one — never a torn write.
   *
   *  This is the correct crash-safety primitive for the whole-image rewrite model. It does not add
   *  the incremental page journal (a write is still O(file)) or inter-process locking; those are
   *  tracked separately (specs/scljet-jdbc.md §"Durability boundary"). */
  private def atomicReplace(path: Path, bytes: Array[Byte]): Unit =
    val dir = Option(path.toAbsolutePath.getParent)
      .getOrElse(throw SQLException(s"scljet JDBC: cannot resolve the parent of $path", "08003"))
    val tmp = Files.createTempFile(dir, "." + path.getFileName.toString + ".", ".tmp")
    try
      val ch = FileChannel.open(tmp, StandardOpenOption.WRITE)
      try
        val buf = java.nio.ByteBuffer.wrap(bytes)
        while buf.hasRemaining do ch.write(buf)
        ch.force(true) // fsync the new image's bytes before it becomes the target
      finally ch.close()
      try Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE)
      catch
        // A filesystem without atomic rename (rare; e.g. some network mounts) — fall back to a
        // plain replace. Less crash-atomic, but still fsync'd and no worse than the old behaviour.
        case _: java.nio.file.AtomicMoveNotSupportedException =>
          Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
      syncDir(dir) // make the rename itself survive a crash
    catch
      case e: SQLException => Files.deleteIfExists(tmp); throw e
      case e: Throwable =>
        Files.deleteIfExists(tmp)
        throw SQLException(s"scljet JDBC: durable write to $path failed: ${e.getMessage}", "08003", e)

  /** fsync a directory so a rename into it is durable. Best-effort: some platforms/filesystems
   *  refuse to open a directory channel, in which case the rename is still ordered after the
   *  file's own fsync — we do not fail the write over a missing directory sync. */
  private def syncDir(dir: Path): Unit =
    try
      val ch = FileChannel.open(dir, StandardOpenOption.READ)
      try ch.force(true) finally ch.close()
    catch case _: Throwable => ()

  /** Run a SELECT and hand back a forward-only ResultSet bound to `owner`. */
  def executeQuery(sql: String, params: List[Value], owner: Statement): ResultSet =
    checkOpen()
    if readOnly && !isReadOnlyStatement(sql) then
      throw SQLException("scljet JDBC: connection is read-only", "25006")
    val res = ScljetEngine.call("jdbcExecuteQueryParams", connValue, Value.StringV(sql), ScljetEngine.paramList(params))
    val rsValue = ScljetEngine.unwrapEither(res, m => s"query failed: $m")
    ScljetResultSet.make(owner, rsValue)

  /** Run a DML/DDL statement; thread the new image; return (changes, rowid). */
  def executeUpdate(sql: String, params: List[Value]): (Long, Long) =
    checkOpen()
    if readOnly then throw SQLException("scljet JDBC: connection is read-only", "25006")
    val res = ScljetEngine.call("jdbcExecuteUpdateParams", connValue, Value.StringV(sql), ScljetEngine.paramList(params))
    val update = ScljetEngine.unwrapEither(res, m => s"update failed: $m")
    connValue = ScljetEngine.field(update, "conn")
    val changes = ScljetEngine.asLong(ScljetEngine.field(update, "changes"))
    val rowid   = ScljetEngine.asLong(ScljetEngine.field(update, "lastInsertRowid"))
    if autoCommit then flushDurable()
    (changes, rowid)

  def setAutoCommit(flag: Boolean): Unit =
    checkOpen()
    if flag != autoCommit then
      // jdbcSetAutoCommit(true) first commits pending working (JDBC contract).
      connValue = ScljetEngine.call("jdbcSetAutoCommit", connValue, Value.boolV(flag))
      autoCommit = flag
      if flag then flushDurable()

  def commit(): Unit =
    checkOpen()
    if autoCommit then throw SQLException("scljet JDBC: commit() called in autocommit mode")
    connValue = ScljetEngine.call("jdbcCommit", connValue)
    flushDurable()

  def rollback(): Unit =
    checkOpen()
    if autoCommit then throw SQLException("scljet JDBC: rollback() called in autocommit mode")
    connValue = ScljetEngine.call("jdbcRollback", connValue)

  private def isReadOnlyStatement(sql: String): Boolean =
    val kw = leadingKeyword(sql)
    kw == "SELECT" || kw == "WITH" || kw == "PRAGMA" || kw == "EXPLAIN"

  def isQuery(sql: String): Boolean =
    val kw = leadingKeyword(sql)
    kw == "SELECT" || kw == "WITH"

  /** Does this statement generate a rowid `getGeneratedKeys` should report?
   *  Only row-inserting statements do — SQLite's `last_insert_rowid()` is
   *  unchanged by UPDATE/DELETE/DDL. */
  def generatesKeys(sql: String): Boolean =
    val kw = leadingKeyword(sql)
    kw == "INSERT" || kw == "REPLACE"

  /** The image a reader should see: the staged working image inside an open
   *  transaction, else the committed one. Used by the catalog metadata. */
  def currentImage: Value =
    checkOpen()
    ScljetEngine.call("jdbcCurrent", connValue)

  private def leadingKeyword(sql: String): String =
    val t = sql.dropWhile(_.isWhitespace)
    t.takeWhile(c => c.isLetter).toUpperCase

object ScljetConnection:
  def make(state: ScljetConnectionState): Connection =
    ProxySupport.proxy(classOf[Connection], ConnectionHandler(state))

final class ConnectionHandler(state: ScljetConnectionState) extends ProxyHandler("Connection"):
  def dispatch(proxy: AnyRef, name: String, args: Array[AnyRef]): AnyRef = name match
    case "createStatement" =>
      // no-arg, or (type, concurrency[, holdability]) — forward-only/read-only only.
      if args.length >= 2 then requireForwardReadOnly(argInt(args, 0), argInt(args, 1))
      ScljetStatement.make(state, proxy.asInstanceOf[Connection])
    case "prepareStatement" =>
      val sql = argStr(args, 0)
      if args.length >= 3 && isInt(args, 1) && isInt(args, 2) then
        requireForwardReadOnly(argInt(args, 1), argInt(args, 2))
      ScljetPreparedStatement.make(state, proxy.asInstanceOf[Connection], sql)
    case "setAutoCommit"        => state.setAutoCommit(argBool(args, 0)); unit
    case "getAutoCommit"        => boxB(state.autoCommit)
    case "commit"               => state.commit(); unit
    case "rollback"             =>
      if args.length == 0 then { state.rollback(); unit } else throw nse("rollback(Savepoint)")
    case "close"                => state.closed = true; unit
    case "isClosed"             => boxB(state.closed)
    case "isValid"              => boxB(!state.closed)
    case "setReadOnly"          => state.checkOpen(); state.readOnly = argBool(args, 0); unit
    case "isReadOnly"           => boxB(state.readOnly)
    case "getMetaData"          => ScljetMeta.databaseMetaData(state, proxy.asInstanceOf[Connection])
    case "setTransactionIsolation" =>
      if argInt(args, 0) == Connection.TRANSACTION_SERIALIZABLE then unit
      else throw nse("setTransactionIsolation(non-serializable)")
    case "getTransactionIsolation" => boxI(Connection.TRANSACTION_SERIALIZABLE)
    case "nativeSQL"            => argStr(args, 0)
    case "getCatalog"           => null
    case "setCatalog"           => unit
    case "getSchema"            => null
    case "setSchema"            => unit
    case "getWarnings"          => null
    case "clearWarnings"        => unit
    case "getHoldability"       => boxI(ResultSet.CLOSE_CURSORS_AT_COMMIT)
    case "setHoldability"       =>
      if argInt(args, 0) == ResultSet.CLOSE_CURSORS_AT_COMMIT then unit else throw nse("setHoldability(HOLD_CURSORS_OVER_COMMIT)")
    case "getNetworkTimeout"    => boxI(0)
    case "setNetworkTimeout"    => unit
    case "getTypeMap"           => new java.util.HashMap[String, Class[?]]()
    case "getClientInfo"        => if args.length == 1 then null else new java.util.Properties()
    case "setClientInfo"        => unit
    case _                      => throw nse(name)

  private def requireForwardReadOnly(rsType: Int, concurrency: Int): Unit =
    if rsType != ResultSet.TYPE_FORWARD_ONLY then throw nse(s"ResultSet type $rsType (only TYPE_FORWARD_ONLY)")
    if concurrency != ResultSet.CONCUR_READ_ONLY then throw nse(s"ResultSet concurrency $concurrency (only CONCUR_READ_ONLY)")
