package scalascript.markup

/** XML character escaping for element text content and attribute values.
 *  Escapes the five predefined XML entities and optionally additional chars.
 *
 *  `escapeText`  — safe for element text content (escapes < & > but not quotes).
 *  `escapeAttr`  — safe for double-quoted attribute values (escapes < & > " ').
 *  `escape`      — conservative: escapes all five entities; correct in all positions. */
object XmlEscape:

  def escape(s: String): String =
    val sb = StringBuilder(s.length + 8)
    var i = 0
    while i < s.length do
      s.charAt(i) match
        case '&'  => sb.append("&amp;")
        case '<'  => sb.append("&lt;")
        case '>'  => sb.append("&gt;")
        case '"'  => sb.append("&quot;")
        case '\'' => sb.append("&apos;")
        case c    => sb.append(c)
      i += 1
    sb.toString

  def escapeText(s: String): String =
    val sb = StringBuilder(s.length + 8)
    var i = 0
    while i < s.length do
      s.charAt(i) match
        case '&' => sb.append("&amp;")
        case '<' => sb.append("&lt;")
        case '>' => sb.append("&gt;")
        case c   => sb.append(c)
      i += 1
    sb.toString

  def escapeAttr(s: String): String =
    val sb = StringBuilder(s.length + 8)
    var i = 0
    while i < s.length do
      s.charAt(i) match
        case '&'  => sb.append("&amp;")
        case '<'  => sb.append("&lt;")
        case '"'  => sb.append("&quot;")
        case c    => sb.append(c)
      i += 1
    sb.toString

  def unescape(s: String): String =
    s.replace("&amp;", "&")
     .replace("&lt;", "<")
     .replace("&gt;", ">")
     .replace("&quot;", "\"")
     .replace("&apos;", "'")
