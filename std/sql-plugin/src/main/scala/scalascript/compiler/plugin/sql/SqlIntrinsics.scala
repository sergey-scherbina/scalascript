package scalascript.compiler.plugin.sql

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName
import scalascript.interpreter.Value

/** Dynamic SQL intrinsics for route handlers and other runtime contexts.
 *
 *  `sql` fenced blocks run at module-load time against a static connection.
 *  These intrinsics let ordinary `.ssc` closures (route handlers, callbacks)
 *  issue parameterised queries against a named `databases:` entry at runtime.
 *
 *  Both intrinsics use `ctx.dbConnect(dbName)` to obtain a JDBC connection
 *  from the interpreter's `sqlRegistry` (populated from front-matter
 *  `databases:`), so no extra wiring is needed in user code.
 *
 *  Return values use plain Scala primitives and `Value.ListV` / `Value.MapV`
 *  so the interpreter's `wrapAnyAsValue` converts them correctly. */
object SqlIntrinsics:

  val table: Map[QualifiedName, IntrinsicImpl] = Map(

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
  )

  private def extractBinds(params: Value): List[Any] = params match
    case Value.ListV(items) => items.map(unwrapValue)
    case _                  => Nil

  private def unwrapValue(v: Value): Any = v match
    case Value.IntV(n)    => n
    case Value.DoubleV(d) => d
    case Value.StringV(s) => s
    case Value.BoolV(b)   => b
    case Value.UnitV      => null
    case Value.NullV      => null
    case _                => v.toString

  private def wrapJdbc(v: Any): Value = v match
    case null        => Value.NullV
    case s: String   => Value.StringV(s)
    case b: Boolean  => Value.BoolV(b)
    case n: Int      => Value.IntV(n.toLong)
    case n: Long     => Value.IntV(n)
    case n: Short    => Value.IntV(n.toLong)
    case n: Byte     => Value.IntV(n.toLong)
    case d: Double   => Value.DoubleV(d)
    case f: Float    => Value.DoubleV(f.toDouble)
    case other       => Value.StringV(other.toString)
