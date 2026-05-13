package scalascript.lexer

import scalascript.ast.{Position, Span}
import scala.collection.mutable.ListBuffer

class Lexer(source: String):
  private var pos: Int = 0
  private var line: Int = 1
  private var column: Int = 1
  private var inCodeBlock: Boolean = false
  private var codeFenceChar: Char = '`'
  private var codeFenceLen: Int = 3
  private var inFrontMatter: Boolean = false
  private var frontMatterParsed: Boolean = false

  private def currentPos: Position = Position(line, column, pos)

  private def peek(offset: Int = 0): Char =
    val idx = pos + offset
    if idx < source.length then source(idx) else '\u0000'

  private def advance(): Char =
    val c = peek()
    pos += 1
    if c == '\n' then
      line += 1
      column = 1
    else
      column += 1
    c

  private def isAtEnd: Boolean = pos >= source.length

  private def matches(s: String): Boolean =
    source.regionMatches(pos, s, 0, s.length)

  private def consumeWhile(pred: Char => Boolean): String =
    val start = pos
    while !isAtEnd && pred(peek()) do advance()
    source.substring(start, pos)

  private def makeToken(kind: TokenKind, text: String, start: Position): Token =
    Token(kind, text, Span(start, currentPos))

  def tokenize(): List[Token] =
    val tokens = ListBuffer[Token]()

    // Check for front-matter at start
    if matches("---") && (pos == 0 || source(pos - 1) == '\n') then
      tokens ++= tokenizeFrontMatter()

    while !isAtEnd do
      val token = if inCodeBlock then nextCodeToken() else nextMarkdownToken()
      token.foreach(tokens += _)

    tokens += makeToken(TokenKind.EOF, "", currentPos)
    tokens.toList

  private def tokenizeFrontMatter(): List[Token] =
    val tokens = ListBuffer[Token]()
    val start = currentPos

    // Consume opening ---
    advance(); advance(); advance()
    tokens += makeToken(TokenKind.FrontMatterStart, "---", start)

    // Skip newline after ---
    if peek() == '\n' then advance()
    else if peek() == '\r' && peek(1) == '\n' then { advance(); advance() }

    // Consume YAML content until closing ---
    val contentStart = currentPos
    val content = StringBuilder()

    while !isAtEnd && !matches("---") do
      content += advance()

    if content.nonEmpty then
      tokens += makeToken(TokenKind.FrontMatterContent, content.toString.stripTrailing, contentStart)

    // Consume closing ---
    if matches("---") then
      val endStart = currentPos
      advance(); advance(); advance()
      tokens += makeToken(TokenKind.FrontMatterEnd, "---", endStart)

    // Skip newline after closing ---
    if peek() == '\n' then advance()
    else if peek() == '\r' && peek(1) == '\n' then { advance(); advance() }

    frontMatterParsed = true
    tokens.toList

  private def nextMarkdownToken(): Option[Token] =
    val start = currentPos
    val c = peek()

    c match
      case '\n' =>
        advance()
        Some(makeToken(TokenKind.Newline, "\n", start))

      case '\r' if peek(1) == '\n' =>
        advance(); advance()
        Some(makeToken(TokenKind.Newline, "\r\n", start))

      case '#' if column == 1 =>
        var level = 0
        while peek() == '#' && level < 6 do
          advance()
          level += 1
        // Skip space after #
        if peek() == ' ' then advance()
        Some(makeToken(TokenKind.Heading(level), "#" * level, start))

      case '`' if matches("```") || matches("~~~") =>
        tokenizeCodeFenceStart()

      case '`' =>
        tokenizeInlineCode()

      case '[' =>
        advance()
        Some(makeToken(TokenKind.LinkStart, "[", start))

      case ']' =>
        advance()
        Some(makeToken(TokenKind.LinkEnd, "]", start))

      case '(' =>
        advance()
        Some(makeToken(TokenKind.LinkTargetStart, "(", start))

      case ')' =>
        advance()
        Some(makeToken(TokenKind.LinkTargetEnd, ")", start))

      case '-' if column == 1 && peek(1) == ' ' =>
        advance()
        Some(makeToken(TokenKind.ListMarker, "-", start))

      case '*' if column == 1 && peek(1) == ' ' =>
        advance()
        Some(makeToken(TokenKind.ListMarker, "*", start))

      case '+' if column == 1 && peek(1) == ' ' =>
        advance()
        Some(makeToken(TokenKind.ListMarker, "+", start))

      case '<' if matches("<!--") =>
        tokenizeHtmlComment()

      case _ if c.isDigit && column == 1 =>
        val num = consumeWhile(_.isDigit)
        if peek() == '.' && peek(1) == ' ' then
          advance() // consume .
          Some(makeToken(TokenKind.OrderedListMarker, num + ".", start))
        else
          Some(makeToken(TokenKind.Text, num, start))

      case ' ' | '\t' =>
        val ws = consumeWhile(c => c == ' ' || c == '\t')
        None // Skip whitespace in markdown mode

      case _ =>
        val text = consumeWhile(c => c != '\n' && c != '\r' && c != '[' && c != ']' && c != '`' && c != '<')
        if text.nonEmpty then Some(makeToken(TokenKind.Text, text, start))
        else None

  private def tokenizeCodeFenceStart(): Option[Token] =
    val start = currentPos
    codeFenceChar = peek()
    codeFenceLen = 0

    while peek() == codeFenceChar do
      advance()
      codeFenceLen += 1

    // Get language tag
    val langStart = currentPos
    val lang = consumeWhile(c => c != '\n' && c != '\r').trim

    // Skip newline
    if peek() == '\n' then advance()
    else if peek() == '\r' && peek(1) == '\n' then { advance(); advance() }

    inCodeBlock = true

    if lang.nonEmpty then
      Some(makeToken(TokenKind.CodeLang, lang, langStart))
    else
      Some(makeToken(TokenKind.CodeFenceStart, codeFenceChar.toString * codeFenceLen, start))

  private def tokenizeInlineCode(): Option[Token] =
    val start = currentPos
    advance() // consume opening `

    // Check for interpolation ${
    if peek() == '$' && peek(1) == '{' then
      advance(); advance()
      return Some(makeToken(TokenKind.InterpolationStart, "`${", start))

    val content = StringBuilder()
    while !isAtEnd && peek() != '`' do
      content += advance()

    if peek() == '`' then advance() // consume closing `

    Some(makeToken(TokenKind.InlineCode, content.toString, start))

  private def tokenizeHtmlComment(): Option[Token] =
    val start = currentPos
    advance(); advance(); advance(); advance() // consume <!--

    val content = StringBuilder()
    while !isAtEnd && !matches("-->") do
      content += advance()

    if matches("-->") then
      advance(); advance(); advance() // consume -->

    Some(makeToken(TokenKind.HtmlCommentContent, content.toString.trim, start))

  private def nextCodeToken(): Option[Token] =
    skipWhitespaceInCode()

    if isAtEnd then return None

    val start = currentPos
    val c = peek()

    // Check for code fence end
    if column == 1 && isCodeFenceEnd then
      return tokenizeCodeFenceEnd()

    c match
      case '\n' =>
        advance()
        Some(makeToken(TokenKind.Newline, "\n", start))

      case '\r' if peek(1) == '\n' =>
        advance(); advance()
        Some(makeToken(TokenKind.Newline, "\r\n", start))

      case '"' =>
        tokenizeString()

      case '\'' =>
        tokenizeChar()

      case '(' => advance(); Some(makeToken(TokenKind.LParen, "(", start))
      case ')' => advance(); Some(makeToken(TokenKind.RParen, ")", start))
      case '[' => advance(); Some(makeToken(TokenKind.LBracket, "[", start))
      case ']' => advance(); Some(makeToken(TokenKind.RBracket, "]", start))
      case '{' => advance(); Some(makeToken(TokenKind.LBrace, "{", start))
      case '}' => advance(); Some(makeToken(TokenKind.RBrace, "}", start))
      case ',' => advance(); Some(makeToken(TokenKind.Comma, ",", start))
      case ';' => advance(); Some(makeToken(TokenKind.Semicolon, ";", start))
      case '@' => advance(); Some(makeToken(TokenKind.At, "@", start))

      case ':' =>
        advance()
        if peek() == ':' then
          advance()
          Some(makeToken(TokenKind.ColonColon, "::", start))
        else
          Some(makeToken(TokenKind.Colon, ":", start))

      case '.' => advance(); Some(makeToken(TokenKind.Dot, ".", start))

      case '=' =>
        advance()
        if peek() == '>' then
          advance()
          Some(makeToken(TokenKind.Arrow, "=>", start))
        else if peek() == '=' then
          advance()
          Some(makeToken(TokenKind.EqEq, "==", start))
        else
          Some(makeToken(TokenKind.Eq, "=", start))

      case '<' =>
        advance()
        if peek() == '-' then
          advance()
          Some(makeToken(TokenKind.LeftArrow, "<-", start))
        else if peek() == ':' then
          advance()
          Some(makeToken(TokenKind.Subtype, "<:", start))
        else if peek() == '=' then
          advance()
          Some(makeToken(TokenKind.LtEq, "<=", start))
        else
          Some(makeToken(TokenKind.Lt, "<", start))

      case '>' =>
        advance()
        if peek() == ':' then
          advance()
          Some(makeToken(TokenKind.Supertype, ">:", start))
        else if peek() == '=' then
          advance()
          Some(makeToken(TokenKind.GtEq, ">=", start))
        else
          Some(makeToken(TokenKind.Gt, ">", start))

      case '-' =>
        advance()
        if peek() == '>' then
          advance()
          Some(makeToken(TokenKind.RightArrow, "->", start))
        else if peek() == '=' then
          advance()
          Some(makeToken(TokenKind.MinusEq, "-=", start))
        else
          Some(makeToken(TokenKind.Minus, "-", start))

      case '+' =>
        advance()
        if peek() == '=' then
          advance()
          Some(makeToken(TokenKind.PlusEq, "+=", start))
        else
          Some(makeToken(TokenKind.Plus, "+", start))

      case '*' =>
        advance()
        if peek() == '=' then
          advance()
          Some(makeToken(TokenKind.StarEq, "*=", start))
        else
          Some(makeToken(TokenKind.Star, "*", start))

      case '/' =>
        if peek(1) == '/' then
          // Line comment
          while !isAtEnd && peek() != '\n' do advance()
          nextCodeToken()
        else if peek(1) == '*' then
          // Block comment
          advance(); advance()
          while !isAtEnd && !(peek() == '*' && peek(1) == '/') do advance()
          if !isAtEnd then { advance(); advance() }
          nextCodeToken()
        else
          advance()
          if peek() == '=' then
            advance()
            Some(makeToken(TokenKind.SlashEq, "/=", start))
          else
            Some(makeToken(TokenKind.Slash, "/", start))

      case '%' => advance(); Some(makeToken(TokenKind.Percent, "%", start))
      case '&' =>
        advance()
        if peek() == '&' then
          advance()
          Some(makeToken(TokenKind.AndAnd, "&&", start))
        else
          Some(makeToken(TokenKind.Ampersand, "&", start))

      case '|' =>
        advance()
        if peek() == '|' then
          advance()
          Some(makeToken(TokenKind.OrOr, "||", start))
        else
          Some(makeToken(TokenKind.Pipe, "|", start))

      case '^' => advance(); Some(makeToken(TokenKind.Caret, "^", start))
      case '~' => advance(); Some(makeToken(TokenKind.Tilde, "~", start))
      case '!' =>
        advance()
        if peek() == '=' then
          advance()
          Some(makeToken(TokenKind.NotEq, "!=", start))
        else
          Some(makeToken(TokenKind.Exclaim, "!", start))

      case '?' => advance(); Some(makeToken(TokenKind.Question, "?", start))
      case '#' => advance(); Some(makeToken(TokenKind.Hash, "#", start))
      case '_' if !peek(1).isLetterOrDigit =>
        advance()
        Some(makeToken(TokenKind.Underscore, "_", start))

      case _ if c.isLetter || c == '_' || c == '$' =>
        tokenizeIdentifier()

      case _ if c.isDigit =>
        tokenizeNumber()

      case _ =>
        advance()
        Some(makeToken(TokenKind.Error, c.toString, start))

  private def isCodeFenceEnd: Boolean =
    var i = 0
    while i < codeFenceLen && pos + i < source.length && source(pos + i) == codeFenceChar do
      i += 1
    i >= codeFenceLen && (pos + i >= source.length || source(pos + i) == '\n' || source(pos + i) == '\r')

  private def tokenizeCodeFenceEnd(): Option[Token] =
    val start = currentPos
    var count = 0
    while peek() == codeFenceChar do
      advance()
      count += 1
    inCodeBlock = false
    Some(makeToken(TokenKind.CodeFenceEnd, codeFenceChar.toString * count, start))

  private def skipWhitespaceInCode(): Unit =
    while !isAtEnd && (peek() == ' ' || peek() == '\t') do
      advance()

  private def tokenizeIdentifier(): Option[Token] =
    val start = currentPos
    val text = consumeWhile(c => c.isLetterOrDigit || c == '_' || c == '$')

    // Check for interpolated string
    if (text == "s" || text == "f" || text == "raw") && peek() == '"' then
      return tokenizeInterpolatedString(text)

    val kind = TokenKind.keywords.getOrElse(text, TokenKind.Identifier)
    Some(makeToken(kind, text, start))

  private def tokenizeNumber(): Option[Token] =
    val start = currentPos

    // Check for hex
    if peek() == '0' && (peek(1) == 'x' || peek(1) == 'X') then
      advance(); advance()
      val hex = consumeWhile(c => c.isDigit || ('a' to 'f').contains(c.toLower))
      val suffix = if peek() == 'L' || peek() == 'l' then { advance(); "L" } else ""
      val kind = if suffix.nonEmpty then TokenKind.LongLiteral else TokenKind.IntLiteral
      return Some(makeToken(kind, "0x" + hex + suffix, start))

    val intPart = consumeWhile(_.isDigit)
    var isDouble = false

    if peek() == '.' && peek(1).isDigit then
      advance()
      consumeWhile(_.isDigit)
      isDouble = true

    if peek() == 'e' || peek() == 'E' then
      advance()
      if peek() == '+' || peek() == '-' then advance()
      consumeWhile(_.isDigit)
      isDouble = true

    val text = source.substring(start.offset, pos)

    if peek() == 'L' || peek() == 'l' then
      advance()
      Some(makeToken(TokenKind.LongLiteral, text + "L", start))
    else if peek() == 'f' || peek() == 'F' || peek() == 'd' || peek() == 'D' then
      advance()
      Some(makeToken(TokenKind.DoubleLiteral, text, start))
    else if isDouble then
      Some(makeToken(TokenKind.DoubleLiteral, text, start))
    else
      Some(makeToken(TokenKind.IntLiteral, text, start))

  private def tokenizeString(): Option[Token] =
    val start = currentPos
    advance() // consume opening "

    // Check for triple-quoted string
    if peek() == '"' && peek(1) == '"' then
      advance(); advance()
      val content = StringBuilder()
      while !isAtEnd && !matches("\"\"\"") do
        content += advance()
      if matches("\"\"\"") then { advance(); advance(); advance() }
      return Some(makeToken(TokenKind.StringLiteral, content.toString, start))

    val content = StringBuilder()
    while !isAtEnd && peek() != '"' && peek() != '\n' do
      if peek() == '\\' then
        advance()
        if !isAtEnd then content += advance()
      else
        content += advance()

    if peek() == '"' then advance()

    Some(makeToken(TokenKind.StringLiteral, content.toString, start))

  private def tokenizeInterpolatedString(prefix: String): Option[Token] =
    val start = currentPos
    advance() // consume "

    val parts = ListBuffer[String]()
    val current = StringBuilder()

    while !isAtEnd && peek() != '"' do
      if peek() == '$' && peek(1) == '{' then
        parts += current.toString
        current.clear()
        advance(); advance() // consume ${
        var braceCount = 1
        while !isAtEnd && braceCount > 0 do
          val c = advance()
          if c == '{' then braceCount += 1
          else if c == '}' then braceCount -= 1
          if braceCount > 0 then current += c
        parts += current.toString
        current.clear()
      else if peek() == '$' && peek(1).isLetter then
        parts += current.toString
        current.clear()
        advance() // consume $
        val ident = consumeWhile(c => c.isLetterOrDigit || c == '_')
        parts += ident
        current.clear()
      else if peek() == '\\' then
        advance()
        if !isAtEnd then current += advance()
      else
        current += advance()

    if current.nonEmpty then parts += current.toString
    if peek() == '"' then advance()

    Some(makeToken(TokenKind.StringLiteral, parts.mkString, start))

  private def tokenizeChar(): Option[Token] =
    val start = currentPos
    advance() // consume opening '

    val c = if peek() == '\\' then
      advance()
      advance() match
        case 'n' => '\n'
        case 'r' => '\r'
        case 't' => '\t'
        case '\\' => '\\'
        case '\'' => '\''
        case '"' => '"'
        case other => other
    else
      advance()

    if peek() == '\'' then advance()

    Some(makeToken(TokenKind.CharLiteral, c.toString, start))

object Lexer:
  def tokenize(source: String): List[Token] =
    Lexer(source).tokenize()
