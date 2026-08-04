package scalascript.interpreter

/** Hand-rolled recursive-descent JSON parser.  Used by the interpreter's
 *  `jsonParse(s)` builtin and by `WebServer` / `WsProxy` for the
 *  `req.json` field on incoming HTTP requests.
 *
 *  Lives outside [[Interpreter]] so the closure-free top-level helpers
 *  can be reused by callers that build [[Value]] structures without an
 *  Interpreter instance (HTTP request construction in particular).
 *
 *  Mirrors the JS/JVM emitters bit-for-bit so cross-backend round-trip
 *  is stable. */
object JsonParser:

  /** Parse a JSON document or throw [[ParseError]] on malformed input. */
  def parse(src: String): Value = ParserState(src).parseTop()

  /** Lenient parse — returns [[None]] on any parse failure.  Suitable for
   *  `req.json` where bad bodies become `req.json == None` rather than a
   *  500. */
  def parseOption(src: String): Option[Value] =
    try Some(parse(src)) catch case _: ParseError => None

  final class ParseError(msg: String) extends RuntimeException(msg)

  private final class ParserState(src: String):
    private var pos: Int    = 0
    private val len: Int    = src.length

    def parseTop(): Value =
      val v = parseValue()
      skipWs()
      if pos < len then err("trailing data after value")
      v

    private def err(msg: String): Nothing =
      throw ParseError(s"jsonParse: $msg at position $pos")

    private def skipWs(): Unit =
      while pos < len && { val c = src.charAt(pos); c == ' ' || c == '\t' || c == '\n' || c == '\r' } do pos += 1

    private def expect(s: String): Unit =
      if pos + s.length > len || src.substring(pos, pos + s.length) != s then err(s"expected '$s'")
      else pos += s.length

    private def parseString(): String =
      if pos >= len || src.charAt(pos) != '"' then err("expected '\"'")
      pos += 1
      val sb = StringBuilder()
      var done = false
      while !done do
        if pos >= len then err("unterminated string")
        src.charAt(pos) match
          case '"'  => pos += 1; done = true
          case '\\' =>
            pos += 1
            if pos >= len then err("dangling escape")
            src.charAt(pos) match
              case '"'  => sb.append('"');  pos += 1
              case '\\' => sb.append('\\'); pos += 1
              case '/'  => sb.append('/');  pos += 1
              case 'n'  => sb.append('\n'); pos += 1
              case 'r'  => sb.append('\r'); pos += 1
              case 't'  => sb.append('\t'); pos += 1
              case 'b'  => sb.append('\b'); pos += 1
              case 'f'  => sb.append('\f'); pos += 1
              case 'u'  =>
                pos += 1
                if pos + 4 > len then err("short unicode escape")
                val hex = src.substring(pos, pos + 4)
                try sb.append(Integer.parseInt(hex, 16).toChar)
                catch case _: NumberFormatException => err("bad unicode escape")
                pos += 4
              case c    => err(s"bad escape '\\$c'")
          case c    => sb.append(c); pos += 1
      sb.toString

    private def parseNumber(): Value =
      val start = pos
      if pos < len && src.charAt(pos) == '-' then pos += 1
      while pos < len && { val c = src.charAt(pos); c >= '0' && c <= '9' } do pos += 1
      var isDouble = false
      if pos < len && src.charAt(pos) == '.' then
        isDouble = true
        pos += 1
        while pos < len && { val c = src.charAt(pos); c >= '0' && c <= '9' } do pos += 1
      if pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E') then
        isDouble = true
        pos += 1
        if pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-') then pos += 1
        while pos < len && { val c = src.charAt(pos); c >= '0' && c <= '9' } do pos += 1
      val s = src.substring(start, pos)
      // A fractional/exponent JSON number becomes an EXACT decimal, not a binary64 double.
      //
      // This is the policy v1's own JSON core already states — `V1JsonCore.rawNumber` builds a
      // `BigDecimal` and its comment says "exact `Decimal` (lossless money), never a lossy
      // `Double`" — and the one v2's `NativeJsonCodec.rawNumber` implements. This site was the
      // dissenter, and since `jsonParse` routes here through `PluginApi.parseJson`, the corpus
      // GOLDEN recorded the lossy answer: `0.10` read back as `0.1`, `1.50` as `1.5`, and a
      // 34-significant-digit decimal collapsed to `0.1`. That matters beyond display, because the
      // same parser reads HTTP request bodies (`InterpreterHttpHandler`, `TypedHandlerWrapper`) and
      // actor wire messages (`ActorScheduler`) — an amount arriving as JSON was going through
      // binary64 on the lane the whole corpus treats as ground truth.
      // (v1-json-two-contradictory-number-policies.)
      //
      // On overflow fall back to the OLD double path rather than to V1JsonCore's `BigDecimal("0.0")`:
      // a pathological exponent should degrade to the previous behaviour, not silently become zero.
      //
      // Every exit from here raises ParseError, never a bare NumberFormatException. Both entry
      // points promise that in writing and neither kept it: `parse` says "throw ParseError on
      // malformed input" and `parseOption` says "returns None on ANY parse failure … suitable for
      // `req.json` where bad bodies become req.json == None rather than a 500". A `-` that starts
      // no number reached `Double.parseDouble` and escaped as NumberFormatException, which
      // parseOption does not catch.
      //
      // MEASURED 2026-08-04: a multipart POST body begins `--<boundary>`, so uploading ANY file to
      // a server whose handler reads `req.json` answered
      //     500 native HTTP handler failed: For input string: "-"
      // (tests/e2e/upload-smoke.sh, via InterpreterHttpHandler.liftRequest:195). The JVM lane
      // passed the same request, which is what made it look like a lane bug rather than this.
      def asDouble(text: String): Value =
        try Value.doubleV(text.toDouble)
        catch case _: NumberFormatException => err(s"invalid number: $text")
      if isDouble then
        try Value.DecimalV(BigDecimal(s))
        catch case _: RuntimeException => asDouble(s)
      else
        try Value.intV(s.toLong)
        catch case _: NumberFormatException => asDouble(s)

    private def parseValue(): Value =
      skipWs()
      if pos >= len then err("unexpected end of input")
      src.charAt(pos) match
        case '"' => Value.StringV(parseString())
        case 't' => expect("true");  Value.True
        case 'f' => expect("false"); Value.False
        case 'n' => expect("null");  Value.NoneV
        case '[' =>
          pos += 1; skipWs()
          val items = scala.collection.mutable.ListBuffer.empty[Value]
          if pos < len && src.charAt(pos) == ']' then pos += 1
          else
            var done = false
            while !done do
              items += parseValue()
              skipWs()
              if pos >= len then err("unterminated array")
              src.charAt(pos) match
                case ',' => pos += 1; skipWs()
                case ']' => pos += 1; done = true
                case c   => err(s"expected ',' or ']', got '$c'")
          Value.ListV(items.toList)
        case '{' =>
          pos += 1; skipWs()
          val entries = scala.collection.mutable.ListBuffer.empty[(Value, Value)]
          if pos < len && src.charAt(pos) == '}' then pos += 1
          else
            var done = false
            while !done do
              skipWs()
              val k = parseString()
              skipWs()
              if pos >= len || src.charAt(pos) != ':' then err("expected ':'")
              pos += 1
              val v = parseValue()
              entries += (Value.StringV(k) -> v)
              skipWs()
              if pos >= len then err("unterminated object")
              src.charAt(pos) match
                case ',' => pos += 1
                case '}' => pos += 1; done = true
                case c   => err(s"expected ',' or '}', got '$c'")
          Value.MapV(entries.toMap)
        case c if c == '-' || (c >= '0' && c <= '9') => parseNumber()
        case c   => err(s"unexpected character '$c'")
