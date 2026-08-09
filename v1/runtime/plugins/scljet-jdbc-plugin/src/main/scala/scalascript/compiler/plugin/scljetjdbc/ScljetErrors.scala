package scalascript.compiler.plugin.scljetjdbc

import java.sql.{SQLException, SQLSyntaxErrorException, SQLIntegrityConstraintViolationException}

/** Maps engine `Left(message)` strings to the `java.sql` exception hierarchy and
 *  SQLState codes from `specs/scljet-jdbc.md` §"Error mapping".
 *
 *  The SQL layer returns `Either[String, _]`; a recognized message shape gets a
 *  more specific subclass, otherwise the general `HY000`. */
object ScljetErrors:

  def toSqlException(engineMessage: String, decorate: String => String): SQLException =
    val msg   = decorate(engineMessage)
    val lower = engineMessage.toLowerCase

    if lower.contains("constraint") || lower.contains("unique") || lower.contains("not null") then
      SQLIntegrityConstraintViolationException(msg, "23000")
    else if lower.contains("syntax") || lower.contains("parse") ||
            lower.contains("unexpected") || lower.contains("expected") ||
            lower.contains("no such") || lower.contains("unknown") then
      SQLSyntaxErrorException(msg, "42000")
    else if lower.contains("read-only") || lower.contains("readonly") then
      SQLException(msg, "25006")
    else if lower.contains("not a database") || lower.contains("corrupt") || lower.contains("malformed") then
      SQLException(msg, "58005")
    else
      SQLException(msg, "HY000")
