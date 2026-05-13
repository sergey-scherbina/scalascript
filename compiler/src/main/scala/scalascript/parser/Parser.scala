package scalascript.parser

import scalascript.ast.*
import scalascript.lexer.{Token, TokenKind, Lexer}
import org.yaml.snakeyaml.Yaml
import scala.jdk.CollectionConverters.*
import scala.collection.mutable.ListBuffer

class Parser(tokens: List[Token]):
  private var pos: Int = 0

  private def current: Token = tokens(pos)
  private def peek(offset: Int = 0): Token =
    val idx = pos + offset
    if idx < tokens.length then tokens(idx) else tokens.last

  private def isAtEnd: Boolean = current.kind == TokenKind.EOF

  private def advance(): Token =
    val tok = current
    if !isAtEnd then pos += 1
    tok

  private def check(kind: TokenKind): Boolean =
    !isAtEnd && current.kind == kind

  private def checkAny(kinds: TokenKind*): Boolean =
    kinds.exists(check)

  private def consume(kind: TokenKind, msg: String): Token =
    if check(kind) then advance()
    else throw ParseError(s"$msg, got ${current.kind} at ${current.span.start}")

  private def consumeIf(kind: TokenKind): Option[Token] =
    if check(kind) then Some(advance()) else None

  private def skipNewlines(): Unit =
    while check(TokenKind.Newline) do advance()

  def parse(): Module =
    val manifest = parseManifest()
    skipNewlines()
    val sections = parseSections(0)
    Module(manifest, sections)

  private def parseManifest(): Option[Manifest] =
    if !check(TokenKind.FrontMatterStart) then return None

    advance() // consume ---
    val content = if check(TokenKind.FrontMatterContent) then advance().text else ""
    consumeIf(TokenKind.FrontMatterEnd)
    skipNewlines()

    val yaml = Yaml()
    val raw = Option(yaml.load[java.util.Map[String, Any]](content))
      .map(_.asScala.toMap)
      .getOrElse(Map.empty)

    Some(Manifest(
      name = raw.get("name").collect { case s: String => s },
      version = raw.get("version").collect { case s: String => s },
      description = raw.get("description").collect { case s: String => s },
      dependencies = raw.get("dependencies").collect {
        case m: java.util.Map[?, ?] => m.asScala.map { case (k, v) => k.toString -> v.toString }.toMap
      }.getOrElse(Map.empty),
      exports = raw.get("exports").collect {
        case l: java.util.List[?] => l.asScala.map(_.toString).toList
      }.getOrElse(Nil),
      targets = raw.get("targets").collect {
        case l: java.util.List[?] => l.asScala.map(_.toString).toList
      }.getOrElse(Nil),
      raw = raw
    ))

  private def parseSections(minLevel: Int): List[Section] =
    val sections = ListBuffer[Section]()

    while !isAtEnd do
      current.kind match
        case TokenKind.Heading(level) if level > minLevel =>
          sections += parseSection(level)
        case TokenKind.Heading(level) if level <= minLevel =>
          return sections.toList
        case _ =>
          // Skip non-section content at top level
          if minLevel == 0 then
            parseContent() // discard top-level content outside sections
          else
            return sections.toList

    sections.toList

  private def parseSection(level: Int): Section =
    val headingToken = advance()
    val headingLevel = headingToken.kind match
      case TokenKind.Heading(l) => l
      case _ => 1

    // Collect heading text
    val textParts = ListBuffer[String]()
    while !isAtEnd && !check(TokenKind.Newline) do
      textParts += advance().text

    val heading = Heading(headingLevel, textParts.mkString.trim, Some(headingToken.span))
    skipNewlines()

    // Parse content until next heading of same or higher level
    val content = ListBuffer[Content]()
    while !isAtEnd && !current.kind.isInstanceOf[TokenKind.Heading] do
      parseContent().foreach(content += _)

    // Parse subsections
    val subsections = parseSections(level)

    Section(heading, content.toList, subsections, Some(headingToken.span))

  private def parseContent(): Option[Content] =
    skipNewlines()
    if isAtEnd then return None

    current.kind match
      case TokenKind.CodeLang | TokenKind.CodeFenceStart =>
        Some(parseCodeBlock())

      case TokenKind.LinkStart =>
        Some(parseImport())

      case TokenKind.ListMarker | TokenKind.OrderedListMarker =>
        Some(parseList())

      case TokenKind.Text | TokenKind.InlineCode | TokenKind.InterpolationStart =>
        Some(parseProse())

      case TokenKind.HtmlCommentContent =>
        advance() // skip comments
        None

      case TokenKind.Newline =>
        advance()
        None

      case _ =>
        advance() // skip unknown
        None

  private def parseCodeBlock(): Content.CodeBlock =
    val start = current.span

    val lang = current.kind match
      case TokenKind.CodeLang =>
        val l = advance().text
        skipNewlines()
        l
      case TokenKind.CodeFenceStart =>
        advance()
        skipNewlines()
        "scala"
      case _ => "scala"

    // Collect code content
    val code = StringBuilder()
    while !isAtEnd && !check(TokenKind.CodeFenceEnd) do
      val tok = advance()
      code ++= tok.text
      if tok.kind == TokenKind.Newline then () // already included

    consumeIf(TokenKind.CodeFenceEnd)
    skipNewlines()

    val codeStr = code.toString.trim
    val statements = if lang == "scala" || lang == "ssc" then
      parseScalaCode(codeStr)
    else
      Nil

    Content.CodeBlock(lang, codeStr, statements, Some(start))

  private def parseImport(): Content.Import =
    val start = current.span
    consume(TokenKind.LinkStart, "Expected [")

    // Parse binding names
    val names = ListBuffer[String]()
    while !isAtEnd && !check(TokenKind.LinkEnd) do
      val tok = advance()
      if tok.kind == TokenKind.Text || tok.kind == TokenKind.Identifier then
        tok.text.split(",").map(_.trim).filter(_.nonEmpty).foreach(names += _)

    consume(TokenKind.LinkEnd, "Expected ]")
    consume(TokenKind.LinkTargetStart, "Expected (")

    // Parse path
    val pathParts = ListBuffer[String]()
    while !isAtEnd && !check(TokenKind.LinkTargetEnd) do
      pathParts += advance().text

    consume(TokenKind.LinkTargetEnd, "Expected )")
    skipNewlines()

    val path = pathParts.mkString.trim
    val bindings = names.map(n => ImportBinding(n, None)).toList

    Content.Import(path, bindings, Some(start))

  private def parseList(): Content.DataList =
    val start = current.span
    val ordered = check(TokenKind.OrderedListMarker)
    val items = ListBuffer[ListItem]()

    while check(TokenKind.ListMarker) || check(TokenKind.OrderedListMarker) do
      advance() // consume marker
      val content = StringBuilder()
      while !isAtEnd && !check(TokenKind.Newline) && !check(TokenKind.ListMarker) && !check(TokenKind.OrderedListMarker) do
        content ++= advance().text
      items += ListItem(content.toString.trim, Nil)
      skipNewlines()

    Content.DataList(items.toList, ordered, Some(start))

  private def parseProse(): Content.Prose =
    val start = current.span
    val text = StringBuilder()
    val interpolations = ListBuffer[Interpolation]()

    while !isAtEnd && !check(TokenKind.Newline) && !current.kind.isInstanceOf[TokenKind.Heading] do
      current.kind match
        case TokenKind.InterpolationStart =>
          advance()
          // TODO: parse expression
          while !isAtEnd && !check(TokenKind.RBrace) do
            text ++= advance().text
          consumeIf(TokenKind.RBrace)
          consumeIf(TokenKind.InlineCode) // closing `

        case TokenKind.InlineCode =>
          text ++= "`"
          text ++= advance().text
          text ++= "`"

        case _ =>
          text ++= advance().text

    skipNewlines()
    Content.Prose(text.toString.trim, interpolations.toList, Some(start))

  // ============================================
  // Scala Code Parser
  // ============================================

  private def parseScalaCode(code: String): List[Statement] =
    val scalaParser = ScalaExprParser(code)
    scalaParser.parseStatements()

class ParseError(msg: String) extends Exception(msg)

object Parser:
  def parse(source: String): Module =
    val tokens = Lexer.tokenize(source)
    Parser(tokens).parse()

  def parseFile(path: os.Path): Module =
    parse(os.read(path))
