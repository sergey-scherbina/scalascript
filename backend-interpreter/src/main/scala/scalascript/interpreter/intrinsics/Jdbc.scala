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
)
