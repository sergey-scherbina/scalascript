package scalascript.parser

import java.util.{LinkedHashMap, ArrayList}
import scala.collection.mutable

/** Minimal pure-Scala YAML subset parser — replaces snakeyaml in all .ssc
 *  frontmatter and manifest files.
 *
 *  Supported subset:
 *  - Block mappings (key: value) and block sequences (- item)
 *  - Flow sequences ([a, b, c]) and flow mappings ({k: v, ...})
 *  - Quoted strings (single ' and double "), including escape sequences in double-quoted
 *  - Scalars: null/~, true/false, integers, doubles, plain strings
 *  - Comments (# to end of line, outside quotes)
 *  - Nested structures up to arbitrary depth
 *
 *  Returns Java collection types matching snakeyaml's "native" load API so all
 *  existing call sites using `.asScala` / `java.util.Map` / `java.util.List` work unchanged:
 *  - block/flow mapping → java.util.LinkedHashMap[String, Any]
 *  - block/flow sequence → java.util.ArrayList[Any]
 *  - null/~ → null
 *  - true/false → java.lang.Boolean
 *  - integer → java.lang.Integer
 *  - double → java.lang.Double
 *  - string → java.lang.String */
object SimpleYaml:

  final class ParseError(msg: String) extends Exception(msg)

  /** Parse `src` and return the root value, cast to `T` (caller's responsibility). */
  def load[T](src: String): T = new Parser(src).parseDocument().asInstanceOf[T]

  private class Parser(src: String):

    private val raw   = src.split("\n", -1).toIndexedSeq
    private val lines = raw.map(stripComment)  // comments removed, trailing space stripped
    private var i     = 0

    // ── document entry ─────────────────────────────────────────────────────

    def parseDocument(): Any =
      skipBlank()
      if done then return null
      val s = cur.trim
      if s.startsWith("[") then { i += 1; parseFlowSeq(s.drop(1)) }
      else if s.startsWith("{") then { i += 1; parseFlowMap(s.drop(1)) }
      else
        val ind = curIndent
        if isSeqLine(cur) then parseBlockSeq(ind)
        else if isMapLine(cur) then parseBlockMap(ind)
        else { i += 1; parseScalar(s) }

    // ── block structures ────────────────────────────────────────────────────

    def parseBlockMap(mapIndent: Int): LinkedHashMap[String, Any] =
      val result = new LinkedHashMap[String, Any]()
      while !done && (lines(i).isBlank || curIndent == mapIndent && isMapLine(cur)) do
        skipBlank()
        if done || curIndent != mapIndent then return result
        val line    = cur
        val trimmed = line.stripLeading()
        i += 1
        val colonIdx = findKeyColon(trimmed)
        if colonIdx >= 0 then
          val key   = unquote(trimmed.take(colonIdx).trim)
          val after = trimmed.drop(colonIdx + 1).trim
          val value = parseInlineOrNext(after, mapIndent, fromMapValue = true)
          result.put(key, value)
      result

    def parseBlockSeq(seqIndent: Int): ArrayList[Any] =
      val result = new ArrayList[Any]()
      while !done && (lines(i).isBlank || curIndent == seqIndent && isSeqLine(cur)) do
        skipBlank()
        if done || curIndent != seqIndent then return result
        val trimmed = cur.stripLeading()
        i += 1
        val content = trimmed.drop(if trimmed.startsWith("- ") then 2 else 1).trim
        val item = parseInlineOrNext(content, seqIndent, fromMapValue = false)
        result.add(item)
      result

    // Parse a value that is either same-line (ahead) or on the next indented line(s).
    // `fromMapValue` = true when parsing the VALUE part of a block map entry; in that
    // context a `: ` inside a plain scalar is a YAML spec error (it would terminate the
    // scalar and start an ambiguous new mapping indicator).  SnakeYAML rejected these with
    // "mapping values are not allowed here"; we reproduce that behaviour so existing tests
    // and user diagnostics remain consistent.  Sequence items pass false — an inline
    // `key: value` in a `- key: value` line IS a legitimate nested map, not an error.
    private def parseInlineOrNext(ahead: String, parentIndent: Int, fromMapValue: Boolean): Any =
      if ahead.isEmpty then
        skipBlank()
        if !done && curIndent > parentIndent then
          if isSeqLine(cur) then parseBlockSeq(curIndent)
          else if isMapLine(cur) then parseBlockMap(curIndent)
          else { val s = cur.trim; i += 1; parseScalar(s) }
        else null
      else if ahead.startsWith("[") then parseFlowSeq(ahead.drop(1))
      else if ahead.startsWith("{") then parseFlowMap(ahead.drop(1))
      else if ahead == "|" || ahead == ">" then parseLiteralBlock(parentIndent)
      else if findKeyColon(ahead) >= 0 then
        if fromMapValue then
          throw new ParseError(
            s"mapping values are not allowed here (plain scalar value contains `: `); " +
            s"quote the value if it is a literal string: $ahead")
        parseFirstMapEntry(ahead, parentIndent)
      else parseScalar(ahead)

    // Parse an inline map entry (key: value on the same line as `-`) and any
    // sibling entries that follow at a deeper indentation than parentIndent.
    private def parseFirstMapEntry(firstEntry: String, parentIndent: Int): LinkedHashMap[String, Any] =
      val result = new LinkedHashMap[String, Any]()
      val ci     = findKeyColon(firstEntry)
      val key    = unquote(firstEntry.take(ci).trim)
      val after  = firstEntry.drop(ci + 1).trim
      skipBlank()
      val nextInd = if !done then curIndent else -1
      val mapInd  = if nextInd > parentIndent then nextInd else -1
      val value =
        if after.nonEmpty then
          if after.startsWith("[") then parseFlowSeq(after.drop(1))
          else if after.startsWith("{") then parseFlowMap(after.drop(1))
          else parseScalar(after)
        else if mapInd >= 0 then
          if isSeqLine(cur) then parseBlockSeq(mapInd)
          else if isMapLine(cur) then parseBlockMap(mapInd)
          else { val s = cur.trim; i += 1; parseScalar(s) }
        else null
      result.put(key, value)
      if after.nonEmpty && mapInd >= 0 then
        while !done && (lines(i).isBlank || curIndent == mapInd && isMapLine(cur)) do
          skipBlank()
          if !done && curIndent == mapInd then
            val t   = cur.stripLeading(); i += 1
            val ci2 = findKeyColon(t)
            if ci2 >= 0 then
              val k = unquote(t.take(ci2).trim)
              val a = t.drop(ci2 + 1).trim
              result.put(k, parseInlineOrNext(a, mapInd, fromMapValue = true))
      result

    // ── literal/folded block scalars (|, >) ────────────────────────────────

    private def parseLiteralBlock(parentIndent: Int): String =
      skipBlank()
      if done || curIndent <= parentIndent then return ""
      val blockIndent = curIndent
      val sb = new StringBuilder
      while !done && (lines(i).isBlank || curIndent >= blockIndent) do
        if lines(i).isBlank then sb.append('\n')
        else sb.append(cur.substring(blockIndent)).append('\n')
        i += 1
      sb.toString.stripTrailing()

    // ── flow structures ─────────────────────────────────────────────────────

    def parseFlowSeq(afterBracket: String): ArrayList[Any] =
      val content = collectFlow(afterBracket, '[', ']')
      val result  = new ArrayList[Any]()
      splitFlow(content).foreach { part =>
        val t = part.trim
        if t.nonEmpty then result.add(
          if t.startsWith("{") then parseFlowMap(t.drop(1).dropRight(if t.endsWith("}") then 1 else 0))
          else parseScalar(t)
        )
      }
      result

    def parseFlowMap(afterBrace: String): LinkedHashMap[String, Any] =
      val content = collectFlow(afterBrace, '{', '}')
      val result  = new LinkedHashMap[String, Any]()
      splitFlow(content).foreach { entry =>
        val t = entry.trim
        val colonIdx = findKeyColon(t)
        if colonIdx >= 0 then
          val key   = unquote(t.take(colonIdx).trim)
          val value = parseScalar(t.drop(colonIdx + 1).trim)
          result.put(key, value)
      }
      result

    // Collect everything up to the matching closing bracket, consuming lines as needed.
    // `open` / `close` are the bracket chars; `start` is everything after the opening bracket.
    private def collectFlow(start: String, open: Char, close: Char): String =
      val sb    = new StringBuilder
      var depth = 0
      def feed(s: String): Boolean =
        var found = false
        var j = 0
        while j < s.length && !found do
          s(j) match
            case c if c == open  => depth += 1; sb += c
            case c if c == close =>
              if depth == 0 then found = true   // matching close found
              else { depth -= 1; sb += c }
            case '\'' =>
              sb += '\''
              j += 1
              while j < s.length && s(j) != '\'' do { sb += s(j); j += 1 }
              if j < s.length then sb += '\''
            case '"' =>
              sb += '"'
              j += 1
              while j < s.length && s(j) != '"' do
                if s(j) == '\\' && j + 1 < s.length then { sb += '\\'; sb += s(j + 1); j += 1 }
                else sb += s(j)
                j += 1
              if j < s.length then sb += '"'
            case c => sb += c
          j += 1
        found
      var closed = feed(start)
      while !closed && !done do
        val line = cur; i += 1
        closed = feed(line)
      sb.toString

    // Split a flow collection's content by commas, respecting nesting and quotes
    private def splitFlow(content: String): List[String] =
      val parts = mutable.ListBuffer.empty[String]
      val sb    = new StringBuilder
      var depth = 0; var inS = false; var inD = false
      var j = 0
      while j < content.length do
        content(j) match
          case '\'' if !inD =>
            inS = !inS; sb += '\''
          case '"' if !inS =>
            inD = !inD; sb += '"'
          case '[' | '{' if !inS && !inD => depth += 1; sb += content(j)
          case ']' | '}' if !inS && !inD => depth -= 1; sb += content(j)
          case ',' if !inS && !inD && depth == 0 =>
            parts += sb.toString; sb.clear()
          case c => sb += c
        j += 1
      val last = sb.toString.trim
      if last.nonEmpty then parts += last
      parts.toList

    // ── scalars ─────────────────────────────────────────────────────────────

    def parseScalar(s: String): Any =
      val t = s.trim
      if t.startsWith("\"") && t.endsWith("\"") && t.length >= 2 then
        t.substring(1, t.length - 1)
          .replace("\\\"", "\"").replace("\\n", "\n")
          .replace("\\t", "\t").replace("\\r", "\r").replace("\\\\", "\\")
      else if t.startsWith("'") && t.endsWith("'") && t.length >= 2 then
        t.substring(1, t.length - 1).replace("''", "'")
      else t match
        case "null" | "~" | "" => null
        case "true"  | "True"  | "TRUE"  => java.lang.Boolean.TRUE
        case "false" | "False" | "FALSE" => java.lang.Boolean.FALSE
        case _ =>
          t.toIntOption.map(n => n: java.lang.Integer)
            .orElse(t.toDoubleOption.map(n => n: java.lang.Double))
            .getOrElse(t)

    // ── comment stripping ────────────────────────────────────────────────────

    private def stripComment(line: String): String =
      var inS = false; var inD = false; var j = 0
      val sb = new StringBuilder
      while j < line.length do
        line(j) match
          case '\'' if !inD => inS = !inS; sb += '\''; j += 1
          case '"'  if !inS => inD = !inD; sb += '"';  j += 1
          case '\\' if inD && j + 1 < line.length =>
            sb += '\\'; sb += line(j + 1); j += 2
          case '#' if !inS && !inD => j = line.length  // rest is comment
          case c => sb += c; j += 1
      sb.toString.stripTrailing()

    // ── helpers ──────────────────────────────────────────────────────────────

    private def done: Boolean = i >= lines.length
    private def cur: String   = lines(i)
    private def curIndent: Int = if done then -1 else lines(i).takeWhile(_ == ' ').length

    private def skipBlank(): Unit =
      while !done && lines(i).isBlank do i += 1

    private def isSeqLine(line: String): Boolean =
      val t = line.stripLeading()
      t.startsWith("- ") || t == "-"

    private def isMapLine(line: String): Boolean =
      val t = line.stripLeading()
      findKeyColon(t) >= 0

    // Find the index of the colon that separates a YAML key from its value:
    // first ':' not inside quotes, followed by ' ', '\t', or end of string.
    def findKeyColon(s: String): Int =
      var inS = false; var inD = false; var j = 0
      while j < s.length do
        s(j) match
          case '\'' if !inD => inS = !inS
          case '"'  if !inS =>
            inD = !inD
            if !inD then  // just closed a double-quoted segment — check next char
              if j + 1 < s.length then () // will be checked on next iteration
          case '\\' if inD && j + 1 < s.length => j += 1  // skip escaped char
          case ':' if !inS && !inD =>
            if j + 1 >= s.length || s(j + 1) == ' ' || s(j + 1) == '\t' then return j
          case _ => ()
        j += 1
      -1

    def unquote(s: String): String =
      val t = s.trim
      if t.length >= 2 && t.head == '"' && t.last == '"' then
        t.substring(1, t.length - 1)
          .replace("\\\"", "\"").replace("\\n", "\n")
          .replace("\\t", "\t").replace("\\\\", "\\")
      else if t.length >= 2 && t.head == '\'' && t.last == '\'' then
        t.substring(1, t.length - 1).replace("''", "'")
      else t
