package scalascript.compiler.plugin.sql

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.Value

/** SQL intrinsics: `DriverManager.getConnection` factory + dynamic
 *  `Db.query` / `Db.execute` for route handlers and runtime contexts.
 *
 *  `sql` fenced blocks run at module-load time against a static connection.
 *  `Db.*` intrinsics let closures issue queries via `ctx.dbConnect` at runtime.
 *
 *  Return values use plain Scala primitives and `Value.ListV` / `Value.MapV`
 *  so the interpreter's `wrapAnyAsValue` converts them correctly. */
object SqlIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

    // DriverManager.getConnection(url): Connection
    // DriverManager.getConnection(url, user, password): Connection
    QualifiedName("DriverManager.getConnection") -> NativeImpl((_, args) =>
      args match
        case List(url: String) =>
          Value.Foreign("Connection", java.sql.DriverManager.getConnection(url))
        case List(url: String, user: String, password: String) =>
          Value.Foreign("Connection", java.sql.DriverManager.getConnection(url, user, password))
        case other =>
          throw new RuntimeException(
            s"DriverManager.getConnection expects (url) or (url, user, password), " +
              s"got: ${other.map(_.getClass.getSimpleName).mkString(", ")}")
    ),

    // Db.query("default", sql, List(p1, p2, ...)): List[Map[String, Any]]
    // Runs a SELECT and returns each row as a Map keyed by column name.
    QualifiedName("Db.query") -> NativeImpl((ctx, args) =>
      args match
        case List(dbName: String, sql: String, params: Value) =>
          val bindList = extractBinds(params)
          val conn     = ctx.dbConnect(dbName)
          scalascript.sql.SqlRuntime.execute(conn, sql, bindList) match
            case scalascript.sql.SqlResult.Rows(rows) =>
              Value.ListV(rows.map(row =>
                Value.MapV(row.columns.zip(row.values).map { (col, v) =>
                  Value.StringV(col) -> wrapJdbc(v)
                }.toMap)
              ).toList)
            case _ => Value.ListV(Nil)
        case _ => throw new RuntimeException("Db.query(dbName: String, sql: String, params: List[Any])")
    ),

    // Db.execute("default", sql, List(p1, p2, ...)): Int
    // Runs an INSERT / UPDATE / DELETE and returns the affected-row count.
    QualifiedName("Db.execute") -> NativeImpl((ctx, args) =>
      args match
        case List(dbName: String, sql: String, params: Value) =>
          val bindList = extractBinds(params)
          val conn     = ctx.dbConnect(dbName)
          scalascript.sql.SqlRuntime.execute(conn, sql, bindList) match
            case scalascript.sql.SqlResult.UpdateCount(n) => n.toLong
            case _                                        => 0L
        case _ => throw new RuntimeException("Db.execute(dbName: String, sql: String, params: List[Any])")
    ),

    // Db.insert("default", "table", value): Int
    // Encodes interpreter case-class instances / maps as SQL columns.
    QualifiedName("Db.insert") -> NativeImpl((ctx, args) =>
      args match
        case List(dbName: String, table: String, value: Value) =>
          val conn = ctx.dbConnect(dbName)
          scalascript.sql.SqlRuntime.insertRow(conn, table, rowFields(ctx, value)).toLong
        case _ => throw new RuntimeException("Db.insert(dbName: String, table: String, value: A)")
    ),

    // Db.update("default", "table", "id", keyValue, value): Int
    // The key column is excluded from SET by SqlRuntime.updateRow.
    QualifiedName("Db.update") -> NativeImpl((ctx, args) =>
      args match
        case List(dbName: String, table: String, keyColumn: String, keyValue, value: Value) =>
          val conn = ctx.dbConnect(dbName)
          scalascript.sql.SqlRuntime
            .updateRow(conn, table, keyColumn, keyValue, rowFields(ctx, value))
            .toLong
        case _ => throw new RuntimeException("Db.update(dbName: String, table: String, keyColumn: String, keyValue: Any, value: A)")
    ),
  )

  private def extractBinds(params: Value): List[Any] = params match
    case Value.ListV(items) => items.map(unwrapValue)
    case _                  => Nil

  private def unwrapValue(v: Value): Any = v match
    case Value.IntV(n)    => n
    case Value.DoubleV(d) => d
    case Value.StringV(s) => s
    case Value.BoolV(b)   => b
    case Value.CharV(c)   => c.toString
    case Value.UnitV      => null
    case Value.NullV      => null
    case Value.OptionV(None) => null
    case Value.OptionV(Some(inner)) => unwrapValue(inner)
    case _                => v.toString

  private def rowFields(ctx: NativeContext, value: Value): Vector[(String, Any)] = value match
    case Value.InstanceV(typeName, fields) =>
      fields.iterator.toVector.map((name, fieldValue) => ctx.storageFieldName(typeName, name) -> unwrapValue(fieldValue))
    case Value.MapV(entries) =>
      entries.iterator.toVector.map {
        case (Value.StringV(name), fieldValue) => name -> unwrapValue(fieldValue)
        case (key, _) => throw new RuntimeException(s"Db typed write expected string column names, got ${Value.show(key)}")
      }
    case other =>
      throw new RuntimeException(s"Db typed write expected case-class instance or Map, got ${Value.show(other)}")

  private def wrapJdbc(v: Any): Value = v match
    case null        => Value.NullV
    case s: String   => Value.StringV(s)
    case b: Boolean  => Value.BoolV(b)
    case n: Int      => Value.intV(n.toLong)
    case n: Long     => Value.IntV(n)
    case n: Short    => Value.intV(n.toLong)
    case n: Byte     => Value.intV(n.toLong)
    case d: Double   => Value.DoubleV(d)
    case f: Float    => Value.DoubleV(f.toDouble)
    case other       => Value.StringV(other.toString)

object SqlBlockRunnerImpl extends SqlBlockRunner:

  def run(source: String, attrs: Map[String, String], ctx: SqlBlockContext): Any =
    val rewritten = scalascript.transform.SqlBindRewriter.rewriteJdbc(source)
    val binds = rewritten.binds.map(expr => unwrapForJdbc(ctx.evalExpression(expr)))
    val conn = resolveSqlConnection(attrs, ctx)
    scalascript.sql.SqlRuntime.execute(conn, rewritten.sql, binds) match
      case scalascript.sql.SqlResult.Rows(rows) =>
        Value.ListV(rows.map(rowToValue).toList)
      case scalascript.sql.SqlResult.UpdateCount(n) =>
        Value.intV(n.toLong)

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
      case Some(scalascript.sql.SqlResult.Rows(rows))      => Value.ListV(rows.map(rowToValue).toList)
      case Some(scalascript.sql.SqlResult.UpdateCount(n))  => Value.intV(n.toLong)
      case None                                            => Value.UnitV

  private def resolveSqlConnection(attrs: Map[String, String], ctx: SqlBlockContext): java.sql.Connection =
    ctx.global("Connection") match
      case Some(Value.Foreign("Connection", c: java.sql.Connection)) => c
      case Some(Value.Foreign("DataSource", ds: javax.sql.DataSource)) => ds.getConnection
      case _ =>
        val dbName = attrs.getOrElse("db", "default")
        ctx.dbConnect(dbName)

  private def rowToValue(row: scalascript.sql.Row): Value =
    val pairs = row.columns.zip(row.values).map { case (col, v) =>
      Value.StringV(col) -> wrapJdbcValue(v)
    }
    Value.MapV(pairs.toMap)

  private def unwrapForJdbc(v: Any): Any = v match
    case Value.IntV(n)              => n
    case Value.DoubleV(d)           => d
    case Value.StringV(s)           => s
    case Value.BoolV(b)             => b
    case Value.CharV(c)             => c
    case Value.UnitV                => null
    case Value.NullV                => null
    case Value.OptionV(None)        => null
    case Value.OptionV(Some(inner)) => unwrapForJdbc(inner)
    case Value.Foreign(_, h)        => h
    case other                      => other

  private def wrapJdbcValue(v: Any): Value = v match
    case null        => Value.NullV
    case s: String   => Value.StringV(s)
    case b: Boolean  => Value.BoolV(b)
    case n: Int      => Value.intV(n.toLong)
    case n: Long     => Value.IntV(n)
    case n: Short    => Value.intV(n.toLong)
    case n: Byte     => Value.intV(n.toLong)
    case d: Double   => Value.DoubleV(d)
    case f: Float    => Value.DoubleV(f.toDouble)
    case bi: java.math.BigInteger => Value.IntV(bi.longValueExact)
    case bd: java.math.BigDecimal => Value.DoubleV(bd.doubleValue)
    case other       => Value.Foreign(Option(other).map(_.getClass.getSimpleName).getOrElse("?"), other)
