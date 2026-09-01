package scalascript.markup

/** XML character escaping for element text content and attribute values.
 *  Escapes the five predefined XML entities and optionally additional chars.
 *
 *  `escapeText`  — safe for element text content (escapes < & > but not quotes).
 *  `escapeAttr`  — safe for double-quoted attribute values (escapes < & > " ').
 *  `escape`      — conservative: escapes all five entities; correct in all positions. */
object XmlEscape:

  def escape(s: String): String =
    s.iterator.map {
      case '&'  => "&amp;"
      case '<'  => "&lt;"
      case '>'  => "&gt;"
      case '"'  => "&quot;"
      case '\'' => "&apos;"
      case c    => c.toString
    }.mkString

  def escapeText(s: String): String =
    s.iterator.map {
      case '&' => "&amp;"
      case '<' => "&lt;"
      case '>' => "&gt;"
      case c   => c.toString
    }.mkString

  def escapeAttr(s: String): String =
    s.iterator.map {
      case '&'  => "&amp;"
      case '<'  => "&lt;"
      case '"'  => "&quot;"
      case c    => c.toString
    }.mkString

  def unescape(s: String): String =
    s.replace("&amp;", "&")
     .replace("&lt;", "<")
     .replace("&gt;", ">")
     .replace("&quot;", "\"")
     .replace("&apos;", "'")
