package scalascript.codegen

/** Pure string-quoting / JS-literal helpers shared by the JS code generator.
 *
 *  Extracted verbatim from `JsGen.scala` (jsgen-split-p1) — they hold no
 *  generator state, so `JsGen` imports `JsGenStringUtils.*` and the call
 *  sites stay unqualified with byte-identical output. */
object JsGenStringUtils:

  def jsQuote(s: String): String =
    val sb = StringBuilder().append('"')
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case c    => sb.append(c)
    }
    sb.append('"').toString

  /** Escape a string for a double-quoted JS literal. */
  def jsStringLit(s: String): String =
    val sb = StringBuilder()
    sb.append('"')
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      (c: @scala.annotation.switch) match
        case '\\' => sb.append("\\\\")
        case '"'  => sb.append("\\\"")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case _    =>
          if c < 0x20 || c == 0x7f then sb.append("\\u%04x".format(c.toInt))
          else                          sb.append(c)
      i += 1
    sb.append('"')
    sb.toString

  /** Build a JS template literal from the block source, routing every
   *  `${...}` interpolation through `_html_interp` (html-escape unless raw)
   *  or `_show` (css passthrough).  The expression text is preserved
   *  verbatim — JS evaluates it in the surrounding scope at runtime. */
  def stringBlockTemplate(src: String, escape: Boolean): String =
    val sb = StringBuilder()
    sb.append('`')
    var i = 0
    while i < src.length do
      if i + 1 < src.length && src.charAt(i) == '$' && src.charAt(i + 1) == '{' then
        val end = findBalancedClose(src, i + 2)
        if end < 0 then
          sb.append(jsTemplateEscape(src.substring(i))); i = src.length
        else
          val expr = src.substring(i + 2, end).trim
          val wrap = if escape then "_html_interp" else "_show"
          sb.append("${").append(wrap).append("(").append(expr).append(")}")
          i = end + 1
      else
        sb.append(jsTemplateEscape(src.charAt(i).toString))
        i += 1
    sb.append('`')
    sb.toString

  def jsTemplateEscape(s: String): String =
    s.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$")

  def findBalancedClose(src: String, from: Int): Int =
    var depth = 1
    var i = from
    while i < src.length && depth > 0 do
      src.charAt(i) match
        case '{' => depth += 1
        case '}' => depth -= 1; if depth == 0 then return i
        case _   => ()
      i += 1
    -1
