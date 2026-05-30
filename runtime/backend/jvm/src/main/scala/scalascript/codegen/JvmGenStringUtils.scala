package scalascript.codegen

/** Pure string-escaping helpers extracted from JvmGen (behavior-preserving). */
object JvmGenStringUtils:

  def escapeStringLit(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"")

  /** Wrap `s` as a properly-escaped Scala double-quoted string literal,
   *  safe for embedding in emitted `.sc` source. */
  def scalaStringLiteral(s: String): String =
    "\"" + s
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
      + "\""

  /** Escape a string for inclusion in a double-quoted JS literal inside
   *  the emitted client-SQL JS bundle. */
  def jsLitForClientSql(s: String): String =
    val sb = StringBuilder("\"")
    s.foreach {
      case '\\' => sb.append("\\\\")
      case '"'  => sb.append("\\\"")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < 0x20 => sb.append("\\u%04x".format(c.toInt))
      case c    => sb.append(c)
    }
    sb.append("\"").toString
