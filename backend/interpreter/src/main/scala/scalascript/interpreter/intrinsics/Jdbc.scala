package scalascript.interpreter

import scalascript.backend.spi.*
import scalascript.ir.QualifiedName

/** JDBC intrinsics for the tree-walking interpreter (v1.26).
 *
 *  Exposes `DriverManager.getConnection` so user code in `scalascript`
 *  blocks can mint a `java.sql.Connection` and bind it as a global the
 *  way SPEC § 3.3.1 promises:
 *
 *  ```scalascript
 *  val Connection: Connection = DriverManager.getConnection("jdbc:h2:mem:test")
 *  ```
 *
 *  A subsequent `sql` block in the same module picks the connection up
 *  via `globals.get("Connection")` — see `Interpreter.runSqlBlock`.
 *  This is the "override" path that beats the front-matter
 *  `databases:` registry.
 *
 *  The returned value is `Value.Foreign("Connection", conn)`: the
 *  interpreter does not introspect the connection structurally, just
 *  threads it through the `runSqlBlock` execution path.
 *
 *  Implementation note: the `getConnection` overloads bind without
 *  user credentials (1-arg) and with `(url, user, password)` (3-arg)
 *  — matching `java.sql.DriverManager` directly.  `Class.forName`
 *  loads the driver, belt-and-braces for environments where the JDBC
 *  ServiceLoader is disabled. */
val JdbcIntrinsics: Map[QualifiedName, IntrinsicImpl] = Map(

  QualifiedName("DriverManager.getConnection") -> NativeImpl((_, args) =>
    args match
      case List(url: String) =>
        val conn = java.sql.DriverManager.getConnection(url)
        Value.Foreign("Connection", conn)

      case List(url: String, user: String, password: String) =>
        val conn = java.sql.DriverManager.getConnection(url, user, password)
        Value.Foreign("Connection", conn)

      case other =>
        throw InterpretError(
          s"DriverManager.getConnection expects (url) or (url, user, password), " +
            s"got: ${other.map(_.getClass.getSimpleName).mkString(", ")}"
        )
  ),

  // Db.query("default", sql, List(param1, param2, ...)): List[Map[String, Any]]
  // Runs a SELECT against the named database from front-matter databases:.
  QualifiedName("Db.query") -> NativeImpl((ctx, args) =>
    args match
      case List(dbName: String, sql: String, params: Value) =>
        val bindList = params match
          case Value.ListV(items) => items.map(SectionRuntime.unwrapForJdbc)
          case _                  => Nil
        val conn = ctx.dbConnect(dbName)
        scalascript.sql.SqlRuntime.execute(conn, sql, bindList) match
          case scalascript.sql.SqlResult.Rows(rows) =>
            Value.ListV(rows.map(SectionRuntime.rowToValue).toList)
          case _ => Value.ListV(Nil)
      case _ => throw InterpretError("Db.query(dbName: String, sql: String, params: List[Any])")
  ),

  // Db.execute("default", sql, List(param1, param2, ...)): Int
  // Runs an INSERT / UPDATE / DELETE against the named database.
  QualifiedName("Db.execute") -> NativeImpl((ctx, args) =>
    args match
      case List(dbName: String, sql: String, params: Value) =>
        val bindList = params match
          case Value.ListV(items) => items.map(SectionRuntime.unwrapForJdbc)
          case _                  => Nil
        val conn = ctx.dbConnect(dbName)
        scalascript.sql.SqlRuntime.execute(conn, sql, bindList) match
          case scalascript.sql.SqlResult.UpdateCount(n) => n.toLong
          case _                                        => 0L
      case _ => throw InterpretError("Db.execute(dbName: String, sql: String, params: List[Any])")
  ),
)
