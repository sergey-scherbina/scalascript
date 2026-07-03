package scalascript.backend.spi

/** Interpreter hook for executable `sql` fenced blocks.
 *
 *  The interpreter owns markdown traversal and result binding
 *  (`_sqlBlock_N`, `<Section>.sql`).  A plugin that understands the `sql`
 *  dialect owns SQL rewriting, connection selection, execution, and result
 *  encoding.
 */
trait SqlBlockRunner:
  def run(source: String, attrs: Map[String, String], ctx: SqlBlockContext): Any
  def runTransaction(source: String, attrs: Map[String, String], ctx: SqlBlockContext): Any =
    throw new UnsupportedOperationException("transaction fenced blocks are not supported by this SQL block runner")

trait SqlBlockContext:
  def evalExpression(source: String): Any
  def global(name: String): Option[Any]
  def dbConnect(dbName: String): java.sql.Connection
  def withTransaction[A](dbName: String)(run: java.sql.Connection => A): A
