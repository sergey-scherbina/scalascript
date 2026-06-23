package scalascript.compiler.plugin.sql

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.plugin.api.{PluginContext, PluginNative, PluginValue}
import scalascript.plugin.api.PluginValue.{Str, Num, Dbl, Bool, Chr, Lst, Inst, MapVal, Opt, Foreign}

/** SQL intrinsics: `DriverManager.getConnection` factory + dynamic
 *  `Db.query` / `Db.execute` for route handlers and runtime contexts.
 *
 *  `sql` fenced blocks run at module-load time against a static connection.
 *  `Db.*` intrinsics let closures issue queries via `ctx.dbConnect` at runtime.
 *
 *  Return values use plain Scala primitives and `PluginValue.list` / `PluginValue.mapOf`
 *  so the interpreter's `wrapAnyAsValue` converts them correctly. */
object SqlIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // DriverManager.getConnection(url): Connection
    // DriverManager.getConnection(url, user, password): Connection
    QualifiedName("DriverManager.getConnection") -> PluginNative.evalLegacy { (_, args) =>
      args match
        case List(url: String) =>
          PluginValue.foreign("Connection", java.sql.DriverManager.getConnection(url))
        case List(url: String, user: String, password: String) =>
          PluginValue.foreign("Connection", java.sql.DriverManager.getConnection(url, user, password))
        case other =>
          throw new RuntimeException(
            s"DriverManager.getConnection expects (url) or (url, user, password), " +
              s"got: ${other.map(_.getClass.getSimpleName).mkString(", ")}")
    },

    // Db.query("default", sql, List(p1, p2, ...)): List[Map[String, Any]]
    // Runs a SELECT and returns each row as a Map keyed by column name.
    QualifiedName("Db.query") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(dbName: String, sql: String, params) =>
          val bindList = extractBinds(params)
          val conn     = ctx.dbConnect(dbName)
          scalascript.sql.SqlRuntime.execute(conn, sql, bindList) match
            case scalascript.sql.SqlResult.Rows(rows) =>
              PluginValue.list(rows.map(row =>
                PluginValue.mapOf(row.columns.zip(row.values).map { (col, v) =>
                  PluginValue.string(col) -> wrapJdbc(v)
                }.toMap)
              ).toList)
            case _ => PluginValue.list(Nil)
        case _ => throw new RuntimeException("Db.query(dbName: String, sql: String, params: List[Any])")
    },

    // Db.execute("default", sql, List(p1, p2, ...)): Int
    // Runs an INSERT / UPDATE / DELETE and returns the affected-row count.
    QualifiedName("Db.execute") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(dbName: String, sql: String, params) =>
          val bindList = extractBinds(params)
          val conn     = ctx.dbConnect(dbName)
          scalascript.sql.SqlRuntime.execute(conn, sql, bindList) match
            case scalascript.sql.SqlResult.UpdateCount(n) => n.toLong
            case _                                        => 0L
        case _ => throw new RuntimeException("Db.execute(dbName: String, sql: String, params: List[Any])")
    },

    // Db.insert("default", "table", value): Int
    // Encodes interpreter case-class instances / maps as SQL columns.
    QualifiedName("Db.insert") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(dbName: String, table: String, value) =>
          val conn = ctx.dbConnect(dbName)
          scalascript.sql.SqlRuntime.insertRow(conn, table, rowFields(ctx, value)).toLong
        case _ => throw new RuntimeException("Db.insert(dbName: String, table: String, value: A)")
    },

    // Db.update("default", "table", "id", keyValue, value): Int
    // The key column is excluded from SET by SqlRuntime.updateRow.
    QualifiedName("Db.update") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(dbName: String, table: String, keyColumn: String, keyValue, value) =>
          val conn = ctx.dbConnect(dbName)
          scalascript.sql.SqlRuntime
            .updateRow(conn, table, keyColumn, keyValue, rowFields(ctx, value))
            .toLong
        case _ => throw new RuntimeException("Db.update(dbName: String, table: String, keyColumn: String, keyValue: Any, value: A)")
    },

    // ── PostgreSQL LISTEN / NOTIFY receive side ──────────────────────────────
    // The publish side already works via `Db.query("db", "SELECT pg_notify(?,?)", …)`.
    // Receiving needs a HELD connection draining getNotifications(), which the
    // stateless per-call Db.query cannot express — but ConnectionRegistry caches
    // one connection per db name for the module run, so LISTEN on it and a later
    // getNotifications on the SAME db name drain notifications delivered to it.

    // Db.pgListen("default", channel): Unit — start listening on a channel.
    QualifiedName("Db.pgListen") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(dbName: String, channel: String) =>
          runIdentStatement(ctx.dbConnect(dbName), "LISTEN", channel)
          PluginValue.unit
        case _ => throw new RuntimeException("Db.pgListen(dbName: String, channel: String)")
    },

    // Db.unlisten("default", channel): Unit — stop listening on a channel.
    QualifiedName("Db.unlisten") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(dbName: String, channel: String) =>
          runIdentStatement(ctx.dbConnect(dbName), "UNLISTEN", channel)
          PluginValue.unit
        case _ => throw new RuntimeException("Db.unlisten(dbName: String, channel: String)")
    },

    // Db.getNotifications("default"[, timeoutMs]): List[Map[String, Any]]
    // Drains pending LISTEN/NOTIFY notifications on the named connection; each is
    // {channel, payload, pid}. timeoutMs blocks up to that long waiting for one
    // (0 / omitted = non-blocking, return whatever is buffered). PostgreSQL-only.
    QualifiedName("Db.getNotifications") -> PluginNative.evalLegacy { (ctx, args) =>
      args match
        case List(dbName: String)             => drainNotifications(ctx.dbConnect(dbName), 0)
        case List(dbName: String, timeoutMs)  => drainNotifications(ctx.dbConnect(dbName), coerceInt(timeoutMs))
        case _ => throw new RuntimeException("Db.getNotifications(dbName: String[, timeoutMs: Int])")
    },
  )

  // LISTEN/UNLISTEN take an identifier, not a bind parameter, so the channel is
  // double-quoted (case-exact, matching pg_notify('channel', …)) with any embedded
  // quote doubled — preventing SQL injection through the channel name.
  private def runIdentStatement(conn: java.sql.Connection, verb: String, channel: String): Unit =
    val quoted = "\"" + channel.replace("\"", "\"\"") + "\""
    val st = conn.createStatement()
    try st.execute(s"$verb $quoted") finally st.close()

  private def coerceInt(v: Any): Int = v match
    case n: Int               => n
    case n: Long              => n.toInt
    case n: java.lang.Number  => n.intValue
    case Num(n)               => n.toInt
    case other                => throw new RuntimeException(s"Db.getNotifications: timeout must be an Int, got ${other}")

  private def drainNotifications(conn: java.sql.Connection, timeoutMs: Int): PluginValue =
    val pg =
      try conn.unwrap(classOf[org.postgresql.PGConnection])
      catch case _: Throwable =>
        throw new RuntimeException(
          "Db.getNotifications requires a PostgreSQL connection (LISTEN/NOTIFY is PostgreSQL-only)")
    val notes = pg.getNotifications(timeoutMs)
    if notes == null then PluginValue.list(Nil)
    else PluginValue.list(notes.toList.map { n =>
      PluginValue.mapOf(Map(
        PluginValue.string("channel") -> PluginValue.string(n.getName),
        PluginValue.string("payload") -> PluginValue.string(Option(n.getParameter).getOrElse("")),
        PluginValue.string("pid")     -> PluginValue.int(n.getPID.toLong),
      ))
    })

  private def extractBinds(params: Any): List[Any] = params match
    case Lst(items) => items.map(unwrapValue)
    case _          => Nil

  private def unwrapValue(v: Any): Any = v match
    case Num(n)           => n
    case Dbl(d)           => d
    case Str(s)           => s
    case Bool(b)          => b
    case Chr(c)           => c.toString
    case Opt(Some(inner)) => unwrapValue(inner)
    case other if PluginValue.isUnitOrNull(other) => null
    case Opt(None)        => null
    case other            => other.toString

  private def rowFields(ctx: PluginContext, value: Any): Vector[(String, Any)] = value match
    case Inst(typeName, fields) =>
      fields.iterator.toVector.map((name, fieldValue) => ctx.storageFieldName(typeName, name) -> unwrapValue(fieldValue))
    case MapVal(entries) =>
      entries.iterator.toVector.map {
        case (Str(name), fieldValue) => name -> unwrapValue(fieldValue)
        case (key, _) => throw new RuntimeException(s"Db typed write expected string column names, got ${PluginValue.showAny(key)}")
      }
    case other =>
      throw new RuntimeException(s"Db typed write expected case-class instance or Map, got ${PluginValue.showAny(other)}")

  private def wrapJdbc(v: Any): PluginValue = v match
    case null        => PluginValue.nullV
    case s: String   => PluginValue.string(s)
    case b: Boolean  => PluginValue.bool(b)
    case n: Int      => PluginValue.int(n.toLong)
    case n: Long     => PluginValue.int(n)
    case n: Short    => PluginValue.int(n.toLong)
    case n: Byte     => PluginValue.int(n.toLong)
    case d: Double   => PluginValue.double(d)
    case f: Float    => PluginValue.double(f.toDouble)
    case other       => PluginValue.string(other.toString)

object SqlBlockRunnerImpl extends SqlBlockRunner:

  def run(source: String, attrs: Map[String, String], ctx: SqlBlockContext): Any =
    val rewritten = scalascript.transform.SqlBindRewriter.rewriteJdbc(source)
    val binds = rewritten.binds.map(expr => unwrapForJdbc(ctx.evalExpression(expr)))
    val conn = resolveSqlConnection(attrs, ctx)
    scalascript.sql.SqlRuntime.execute(conn, rewritten.sql, binds) match
      case scalascript.sql.SqlResult.Rows(rows) =>
        PluginValue.list(rows.map(rowToValue).toList)
      case scalascript.sql.SqlResult.UpdateCount(n) =>
        PluginValue.int(n.toLong)

  override def runTransaction(source: String, attrs: Map[String, String], ctx: SqlBlockContext): Any =
    val dbName = attrs.getOrElse("db", "default")
    val results = ctx.withTransaction(dbName) { conn =>
      scalascript.transform.SqlBindRewriter.splitStatements(source).map { stmtSrc =>
        val rewritten = scalascript.transform.SqlBindRewriter.rewriteJdbc(stmtSrc)
        val binds = rewritten.binds.map(expr => unwrapForJdbc(ctx.evalExpression(expr)))
        scalascript.sql.SqlRuntime.execute(conn, rewritten.sql, binds)
      }
    }
    results.lastOption match
      case Some(scalascript.sql.SqlResult.Rows(rows))      => PluginValue.list(rows.map(rowToValue).toList)
      case Some(scalascript.sql.SqlResult.UpdateCount(n))  => PluginValue.int(n.toLong)
      case None                                            => PluginValue.unit

  private def resolveSqlConnection(attrs: Map[String, String], ctx: SqlBlockContext): java.sql.Connection =
    ctx.global("Connection") match
      case Some(Foreign("Connection", c: java.sql.Connection)) => c
      case Some(Foreign("DataSource", ds: javax.sql.DataSource)) => ds.getConnection
      case _ =>
        val dbName = attrs.getOrElse("db", "default")
        ctx.dbConnect(dbName)

  private def rowToValue(row: scalascript.sql.Row): PluginValue =
    val pairs = row.columns.zip(row.values).map { case (col, v) =>
      PluginValue.string(col) -> wrapJdbcValue(v)
    }
    PluginValue.mapOf(pairs.toMap)

  private def unwrapForJdbc(v: Any): Any = v match
    case Num(n)           => n
    case Dbl(d)           => d
    case Str(s)           => s
    case Bool(b)          => b
    case Chr(c)           => c
    case Opt(Some(inner)) => unwrapForJdbc(inner)
    case other if PluginValue.isUnitOrNull(other) => null
    case Opt(None)        => null
    case Foreign(_, h)    => h
    case other            => other

  private def wrapJdbcValue(v: Any): PluginValue = v match
    case null        => PluginValue.nullV
    case s: String   => PluginValue.string(s)
    case b: Boolean  => PluginValue.bool(b)
    case n: Int      => PluginValue.int(n.toLong)
    case n: Long     => PluginValue.int(n)
    case n: Short    => PluginValue.int(n.toLong)
    case n: Byte     => PluginValue.int(n.toLong)
    case d: Double   => PluginValue.double(d)
    case f: Float    => PluginValue.double(f.toDouble)
    case bi: java.math.BigInteger => PluginValue.int(bi.longValueExact)
    case bd: java.math.BigDecimal => PluginValue.double(bd.doubleValue)
    case other       => PluginValue.foreign(Option(other).map(_.getClass.getSimpleName).getOrElse("?"), other)
