package scalascript.uniml.dialect.xml

import scalascript.markup.{Markup, PureMarkupCodec}
import scalascript.uniml.*
import scala.collection.mutable.ArrayBuffer

final case class XmlLimits(
    core: Limits = Limits.default,
    maxSourceCodePoints: Long = 64L * 1024 * 1024,
    maxNameCodePoints: Int = 4096,
    maxAttributeCodePoints: Int = 16 * 1024 * 1024,
    maxTextCodePoints: Int = 16 * 1024 * 1024,
    maxDoctypeCodePoints: Int = 1024 * 1024,
    maxAttributesPerElement: Int = 100_000,
)

object XmlLimits:
  val default: XmlLimits = XmlLimits()

object XmlDialect extends DialectAdapter:
  val id: String = "xml.1.0"
  override val aliases: Set[String] = Set("xml", "application/xml", "text/xml")

  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] =
    XmlProcessor(source.source, XmlLimits.default)

  def withLimits(limits: XmlLimits): DialectAdapter = ConfiguredXmlDialect(limits)

private final case class ConfiguredXmlDialect(limits: XmlLimits) extends DialectAdapter:
  val id: String = XmlDialect.id
  override val aliases: Set[String] = XmlDialect.aliases
  def instructions(source: SourceInput): Processor[String, SourceChunk, VmToken] = XmlProcessor(source.source, limits)

final case class XmlValidationResult(
    roots: Vector[UniNode],
    diagnostics: Vector[Diagnostic],
    complete: Boolean,
)

final case class XmlMarkupProjection(
    document: Option[Markup.Doc],
    diagnostics: Vector[Diagnostic],
)

object Xml:
  def parse(source: SourceInput, limits: XmlLimits = XmlLimits.default): ParseResult =
    UniML.parse(source, XmlDialect.withLimits(limits), limits.core)

  def validate(result: ParseResult): XmlValidationResult =
    val baseComplete = !result.diagnostics.exists(d => d.severity == Severity.Error || d.severity == Severity.Fatal)
    if !baseComplete then XmlValidationResult(result.roots, result.diagnostics, complete = false)
    else
      parseMarkup(result) match
        case Left(diagnostic) => XmlValidationResult(result.roots, result.diagnostics :+ diagnostic, complete = false)
        case Right(document) =>
          val doctypeDiagnostics = document.docType.toVector.collect {
            case docType if docType.name != document.root.name.toXml =>
              Diagnostic(
                "uniml.xml.invalid-doctype",
                s"DOCTYPE root '${docType.name}' does not match document element '${document.root.name.toXml}'",
                Severity.Error,
                None,
                Some(XmlDialect.id),
              )
          }
          val namespaceDiagnostics = validateNamespaces(document.root)
          val allDiagnostics = result.diagnostics ++ doctypeDiagnostics ++ namespaceDiagnostics
          XmlValidationResult(result.roots, allDiagnostics,
            !allDiagnostics.exists(d => d.severity == Severity.Error || d.severity == Severity.Fatal))

  def projectMarkup(result: ParseResult): XmlMarkupProjection =
    val validation = validate(result)
    if !validation.complete then XmlMarkupProjection(None, validation.diagnostics)
    else if unresolvedReferences(result).nonEmpty then
      XmlMarkupProjection(None, validation.diagnostics ++ unresolvedReferences(result))
    else
      parseMarkup(result) match
        case Left(diagnostic) => XmlMarkupProjection(None, validation.diagnostics :+ diagnostic)
        case Right(document) =>
          val resolved = document.copy(root = resolveElement(document.root, Map(XmlNamespace -> XmlNamespaceUri)))
          val preRootMisc = result.roots.takeWhile {
            case UniNode.Branch("xml.element", _, _, _) => false
            case UniNode.Token(token) if token.kind == "xml.comment" || token.kind == "xml.pi" => true
            case _ => false
          }.nonEmpty
          val warnings =
            if preRootMisc then Vector(Diagnostic(
              "uniml.xml.projection-lossy-prolog",
              "Markup.Doc cannot retain comments or processing instructions before the root element",
              Severity.Warning,
              None,
              Some(XmlDialect.id),
            ))
            else Vector.empty
          XmlMarkupProjection(Some(resolved), validation.diagnostics ++ warnings)

  private def unresolvedReferences(result: ParseResult): Vector[Diagnostic] =
    result.roots.flatMap(UniNode.sourceTokens).collect {
      case token if token.kind == "xml.reference" &&
          !token.lexeme.matches("&(?:lt|gt|amp|apos|quot|#[0-9]+|#x[0-9A-Fa-f]+);") =>
        Diagnostic(
          "uniml.xml.unresolved-entity",
          s"entity reference '${token.lexeme}' has no bounded resolver",
          Severity.Error,
          Some(token.span),
          Some(XmlDialect.id),
        )
    }

  private val XmlNamespace = "xml"
  private val XmlNamespaceUri = "http://www.w3.org/XML/1998/namespace"
  private val XmlnsNamespaceUri = "http://www.w3.org/2000/xmlns/"

  private def parseMarkup(result: ParseResult): Either[Diagnostic, Markup.Doc] =
    val source = result.roots.flatMap(UniNode.sourceTokens).sortBy(_.id).map(_.lexeme).mkString
    PureMarkupCodec.parse(source).left.map(error => Diagnostic(
      "uniml.xml.projection-invalid-cst",
      error.getMessage,
      Severity.Error,
      None,
      Some(XmlDialect.id),
    ))

  private def validateNamespaces(root: Markup.Element): Vector[Diagnostic] =
    val diagnostics = Vector.newBuilder[Diagnostic]
    val stack = ArrayBuffer((root, Map(XmlNamespace -> XmlNamespaceUri)))
    while stack.nonEmpty do
      val (element, inherited) = stack.remove(stack.size - 1)
      var bindings = inherited
      val declaredPrefixes = scala.collection.mutable.HashSet.empty[String]
      element.attrs.foreach { attribute =>
        namespaceDeclaration(attribute).foreach { case (prefix, uri) =>
          if !declaredPrefixes.add(prefix) then diagnostics += namespaceDiagnostic("duplicate namespace declaration")
          if prefix == "xmlns" || uri == XmlnsNamespaceUri || (prefix == XmlNamespace && uri != XmlNamespaceUri) ||
              (prefix != XmlNamespace && uri == XmlNamespaceUri) || (prefix.nonEmpty && uri.isEmpty) then
            diagnostics += namespaceDiagnostic(s"invalid namespace binding '$prefix' -> '$uri'")
          else bindings = bindings.updated(prefix, uri)
        }
      }
      element.name.prefix.foreach { prefix =>
        if !bindings.contains(prefix) then diagnostics += namespaceDiagnostic(s"unbound element prefix '$prefix'")
      }
      val expanded = scala.collection.mutable.HashSet.empty[(Option[String], String)]
      element.attrs.filter(namespaceDeclaration(_).isEmpty).foreach { attribute =>
        val namespace = attribute.name.prefix.flatMap(bindings.get)
        attribute.name.prefix.foreach { prefix =>
          if !bindings.contains(prefix) then diagnostics += namespaceDiagnostic(s"unbound attribute prefix '$prefix'")
        }
        if !expanded.add(namespace -> attribute.name.localName) then
          diagnostics += namespaceDiagnostic(s"duplicate expanded attribute '${attribute.name.localName}'")
      }
      element.children.collect { case child: Markup.Element => child }.reverseIterator.foreach(child => stack += ((child, bindings)))
    diagnostics.result()

  private def resolveElement(element: Markup.Element, inherited: Map[String, String]): Markup.Element =
    var bindings = inherited
    element.attrs.foreach { attribute =>
      namespaceDeclaration(attribute).foreach { case (prefix, uri) => bindings = bindings.updated(prefix, uri) }
    }
    val resolvedName = element.name.copy(namespace = element.name.prefix.flatMap(bindings.get).orElse(bindings.get("")))
    val resolvedAttrs = element.attrs.filter(namespaceDeclaration(_).isEmpty).map { attribute =>
      attribute.copy(name = attribute.name.copy(namespace = attribute.name.prefix.flatMap(bindings.get)))
    }
    val resolvedChildren = element.children.map {
      case child: Markup.Element => resolveElement(child, bindings)
      case other                 => other
    }
    element.copy(name = resolvedName, attrs = resolvedAttrs, children = resolvedChildren)

  private def namespaceDeclaration(attribute: Markup.Attr): Option[(String, String)] =
    attribute.name match
      case Markup.QName(None, "xmlns", _)          => Some("" -> attribute.value)
      case Markup.QName(Some("xmlns"), prefix, _) => Some(prefix -> attribute.value)
      case _                                        => None

  private def namespaceDiagnostic(message: String): Diagnostic =
    Diagnostic("uniml.xml.invalid-namespace-binding", message, Severity.Error, None, Some(XmlDialect.id))

private final case class XmlProcessor(source: SourceId, limits: XmlLimits) extends Processor[String, SourceChunk, VmToken]:
  def start: String = ""

  def step(state: String, input: SourceChunk): Stepped[String, VmToken] =
    Stepped(state + input.text, ProcessBatch.empty)

  def stop(state: String): ProcessBatch[VmToken] =
    if Unicode.codePointCount(state).toLong > limits.maxSourceCodePoints then
      ProcessBatch(Vector.empty, Vector(Diagnostic(
        "uniml.xml.limit.source",
        s"XML source exceeds the ${limits.maxSourceCodePoints} code-point limit",
        Severity.Fatal,
        None,
        Some(XmlDialect.id),
      )))
    else XmlScanner.scan(source, state, limits)

/** Pure XML scanner: a single fold over the whole source that emits VM tokens.
  * All scanning state lives in local `var`s inside `scan` (no mutable object
  * fields), with a local imperative shell and immutable `Vector` accumulation;
  * the state-touching helpers are nested defs over those locals, pure classifiers
  * stay top-level. */
private object XmlScanner:
  def scan(source: SourceId, input: String, limits: XmlLimits): ProcessBatch[VmToken] =
    var output: Vector[VmToken] = Vector.empty
    var diagnostics: Vector[Diagnostic] = Vector.empty
    var elements: Vector[String] = Vector.empty
    var index = 0
    var position = SourcePosition.Start
    var nextTokenId = 0L
    var rootCount = 0
    var seenDoctype = false
    var seenDeclaration = false

    def eofDiagnostic(code: String, message: String): Diagnostic =
      Diagnostic(code, message, Severity.Error, Some(SourceSpan(source, position, position)), Some(XmlDialect.id))

    def emitKnownRange(startIndex: Int, lexeme: String, kind: String, channel: TokenChannel, instruction: VmInstruction): Unit =
      val start = position
      val end = Unicode.advance(start, lexeme)
      position = end
      val token = SourceToken(nextTokenId, kind, lexeme, SourceSpan(source, start, end), channel)
      nextTokenId += 1
      output = output :+ VmToken(token, instruction)
      val _ = startIndex

    def emitWhole(lexeme: String, kind: String, channel: TokenChannel, instruction: VmInstruction): Unit =
      val start = index
      index += lexeme.length
      emitKnownRange(start, lexeme, kind, channel, instruction)

    def emitRestError(code: String, message: String): Unit =
      val lexeme = input.substring(index)
      emitWhole(lexeme, "xml.invalid", TokenChannel.Error, VmInstruction.Report(code, message))

    def scanDeclaration(): Unit =
      val start = index
      val end = input.indexOf("?>", index + 5)
      if end < 0 then emitRestError("uniml.xml.invalid-declaration", "unterminated XML declaration")
      else
        val lexeme = input.substring(start, end + 2)
        val valid = index == 0 && !seenDeclaration && isValidDeclaration(lexeme)
        seenDeclaration = true
        emitWhole(lexeme, "xml.declaration", TokenChannel.Syntax,
          if valid then VmInstruction.Emit(Some("document.declaration"))
          else VmInstruction.Report(if start == 0 then "uniml.xml.invalid-declaration" else "uniml.xml.declaration-position", "invalid XML declaration"))

    def scanDoctype(): Unit =
      val start = index
      var cursor = index + 9
      var subsetDepth = 0
      var quote: Char = ' '
      var done = false
      while cursor < input.length && !done do
        val char = input.charAt(cursor)
        if quote != ' ' then
          if char == quote then quote = ' '
        else char match
          case '\'' | '"' => quote = char
          case '['         => subsetDepth += 1
          case ']' if subsetDepth > 0 => subsetDepth -= 1
          case '>' if subsetDepth == 0 => done = true
          case _ => ()
        cursor += 1
      if !done then emitRestError("uniml.xml.invalid-doctype", "unterminated XML DOCTYPE")
      else
        val lexeme = input.substring(start, cursor)
        val codePoints = Unicode.codePointCount(lexeme)
        val validPosition = elements.isEmpty && rootCount == 0 && !seenDoctype
        seenDoctype = true
        val instruction =
          if codePoints > limits.maxDoctypeCodePoints then VmInstruction.Report("uniml.xml.limit.doctype", "XML DOCTYPE exceeds configured limit", Severity.Fatal)
          else if validPosition then VmInstruction.Emit(Some("document.doctype"))
          else VmInstruction.Report("uniml.xml.doctype-position", "DOCTYPE must appear once before the root element")
        emitWhole(lexeme, "xml.doctype", TokenChannel.Syntax, instruction)

    def scanName(role: String): String =
      val start = index
      while index < input.length && !isNameDelimiter(input.charAt(index)) do index += 1
      if index == start && index < input.length then index += 1
      val lexeme = input.substring(start, index)
      val tooLong = Unicode.codePointCount(lexeme) > limits.maxNameCodePoints
      val valid = !tooLong && validXmlName(lexeme) && lexeme.count(_ == ':') <= 1
      emitKnownRange(start, lexeme, if valid then "xml.name" else "xml.invalid", if valid then TokenChannel.Syntax else TokenChannel.Error,
        if valid then VmInstruction.Emit(Some(role))
        else if tooLong then VmInstruction.Report("uniml.xml.limit.name", "XML name exceeds configured limit", Severity.Fatal)
        else VmInstruction.Report("uniml.xml.invalid-name", "invalid XML name"))
      lexeme

    def scanMarkupWhitespace(): Unit =
      val start = index
      while index < input.length && isXmlWhitespace(input.charAt(index)) do index += 1
      emitKnownRange(start, input.substring(start, index), "xml.whitespace", TokenChannel.Trivia, VmInstruction.Emit(Some("markup.whitespace")))

    def scanAttributeValue(): Unit =
      if index >= input.length || (input.charAt(index) != '\'' && input.charAt(index) != '"') then
        diagnostics = diagnostics :+ eofDiagnostic("uniml.xml.expected-attribute-value", "expected quoted XML attribute value")
        if index < input.length && input.charAt(index) != '>' then
          val lexeme = input.substring(index, index + 1)
          emitWhole(lexeme, "xml.invalid", TokenChannel.Error,
            VmInstruction.Report("uniml.xml.expected-attribute-value", "expected quoted XML attribute value"))
      else
        val start = index
        val quote = input.charAt(index)
        index += 1
        while index < input.length && input.charAt(index) != quote do index += 1
        if index < input.length then index += 1
        val lexeme = input.substring(start, index)
        val content = lexeme.substring(1, math.max(1, lexeme.length - 1))
        // `input.substring(start, start + 1)` (the opening quote) rather than
        // `quote.toString`: v2 has no Char box, so `quote.toString` yields the code
        // point's decimal digits. Identical on the JVM (start holds the same quote char).
        val invalid = !lexeme.endsWith(input.substring(start, start + 1)) || content.contains('<') || !validAttributeReferences(content)
        val tooLong = Unicode.codePointCount(lexeme) > limits.maxAttributeCodePoints
        emitKnownRange(start, lexeme, "xml.attribute-value", TokenChannel.Syntax,
          if tooLong then VmInstruction.Report("uniml.xml.limit.attribute", "XML attribute value exceeds configured limit", Severity.Fatal)
          else if invalid then VmInstruction.Report("uniml.xml.expected-attribute-value", "invalid XML attribute value")
          else VmInstruction.Emit(Some("attribute.value")))

    def scanStartTag(): Unit =
      val parentOpen = elements.nonEmpty
      emitWhole("<", "xml.start-open", TokenChannel.Syntax,
        VmInstruction.Open("xml.element", Some(if parentOpen then "content.child" else "document.root")))
      val name = scanName("element.name")
      if !parentOpen then rootCount += 1
      if rootCount > 1 && !parentOpen then diagnostics = diagnostics :+ tokenDiagnostic(output.last.token, "uniml.xml.multiple-roots", "XML document has multiple root elements")
      elements = elements :+ name
      // Vector (with the preceding contains-check) instead of Set: v2 has no
      // Set.empty companion, and dedup is redundant here (a duplicate is reported
      // before the add, so the Vector never actually holds a duplicate).
      var attributes: Vector[String] = Vector.empty
      var attributeCount = 0
      var closed = false
      while index < input.length && !closed do
        if input.startsWith("/>", index) then
          emitWhole("/>", "xml.empty-close", TokenChannel.Syntax, VmInstruction.Close(Some("xml.element"), Some("empty-tag.close")))
          elements = elements.dropRight(1)
          closed = true
        else if input.charAt(index) == '>' then
          emitWhole(">", "xml.tag-close", TokenChannel.Syntax, VmInstruction.Emit(Some("start-tag.close")))
          closed = true
        else if isXmlWhitespace(input.charAt(index)) then scanMarkupWhitespace()
        else
          val attribute = scanName("attribute.name")
          attributeCount += 1
          if attributes.contains(attribute) then diagnostics = diagnostics :+ tokenDiagnostic(output.last.token, "uniml.xml.duplicate-attribute", s"duplicate XML attribute '$attribute'")
          attributes = attributes :+ attribute
          if attributeCount > limits.maxAttributesPerElement then diagnostics = diagnostics :+ tokenDiagnostic(output.last.token, "uniml.xml.limit.attribute", "too many XML attributes", Severity.Fatal)
          if index < input.length && isXmlWhitespace(input.charAt(index)) then scanMarkupWhitespace()
          if index < input.length && input.charAt(index) == '=' then emitWhole("=", "xml.equals", TokenChannel.Syntax, VmInstruction.Emit(Some("attribute.equals")))
          else diagnostics = diagnostics :+ eofDiagnostic("uniml.xml.expected-equals", s"expected '=' after attribute '$attribute'")
          if index < input.length && isXmlWhitespace(input.charAt(index)) then scanMarkupWhitespace()
          scanAttributeValue()
      if !closed then diagnostics = diagnostics :+ eofDiagnostic("uniml.xml.unexpected-eof", s"unterminated start tag <$name>")

    def scanEndTag(): Unit =
      emitWhole("</", "xml.end-open", TokenChannel.Syntax,
        if elements.nonEmpty then VmInstruction.Emit(Some("end-tag.open"))
        else VmInstruction.Report("uniml.xml.unexpected-end-tag", "end tag has no open element"))
      val name = scanName("end-tag.name")
      while index < input.length && isXmlWhitespace(input.charAt(index)) do scanMarkupWhitespace()
      if index < input.length && input.charAt(index) == '>' then
        val matches = elements.nonEmpty && elements.last == name
        emitWhole(">", "xml.tag-close", TokenChannel.Syntax,
          if matches then VmInstruction.Close(Some("xml.element"), Some("end-tag.close"))
          else VmInstruction.Report("uniml.xml.mismatched-end-tag", s"end tag </$name> does not match the current element"))
        if matches then elements = elements.dropRight(1)
      else diagnostics = diagnostics :+ eofDiagnostic("uniml.xml.unexpected-eof", s"unterminated end tag </$name>")

    def scanOpaque(
        terminator: String,
        kind: String,
        channel: TokenChannel,
        contentRole: String,
        validate: String => Option[(String, String)],
    ): Unit =
      val end = input.indexOf(terminator, index + 2)
      if end < 0 then emitRestError(s"uniml.${kind.replace('.', '-')}", s"unterminated $kind")
      else
        val lexeme = input.substring(index, end + terminator.length)
        val role = if elements.nonEmpty then contentRole else "document.misc"
        val instruction = validate(lexeme) match
          case Some((code, message)) => VmInstruction.Report(code, message)
          case None                  => VmInstruction.Emit(Some(role))
        emitWhole(lexeme, kind, channel, instruction)

    def scanCData(): Unit =
      scanOpaque("]]>", "xml.cdata", TokenChannel.Syntax, "content.cdata", _ =>
        if elements.isEmpty then Some("uniml.xml.invalid-cdata" -> "CDATA is only allowed inside element content") else None)

    def scanReference(): Unit =
      val end = input.indexOf(';', index + 1)
      if end < 0 then emitRestError("uniml.xml.invalid-reference", "unterminated XML reference")
      else
        val lexeme = input.substring(index, end + 1)
        val syntaxValid = isValidReference(lexeme)
        val valid = syntaxValid && numericReferenceValue(lexeme).forall(isLegalXmlCodePoint)
        emitWhole(lexeme, "xml.reference", TokenChannel.Syntax,
          if valid then VmInstruction.Emit(Some(if elements.nonEmpty then "content.reference" else "document.reference"))
          else VmInstruction.Report("uniml.xml.invalid-reference", "invalid XML reference"))

    def scanText(): Unit =
      val start = index
      while index < input.length && input.charAt(index) != '<' && input.charAt(index) != '&' do index += 1
      val lexeme = input.substring(start, index)
      val outside = elements.isEmpty
      val whitespaceOnly = lexeme.forall(isXmlWhitespace)
      val invalid = lexeme.contains("]]>")
      val tooLong = Unicode.codePointCount(lexeme) > limits.maxTextCodePoints
      val instruction =
        if tooLong then VmInstruction.Report("uniml.xml.limit.text", "XML text exceeds configured limit", Severity.Fatal)
        else if invalid then VmInstruction.Report("uniml.xml.invalid-character", "']]>' is forbidden in ordinary XML character data")
        else if outside && !whitespaceOnly then VmInstruction.Report("uniml.xml.text-outside-root", "character data is not allowed outside the root element")
        else VmInstruction.Emit(Some(if outside then "document.misc" else "content.text"))
      emitKnownRange(start, lexeme, if outside && whitespaceOnly then "xml.whitespace" else "xml.text",
        if outside && whitespaceOnly then TokenChannel.Trivia else TokenChannel.Syntax, instruction)

    def validateSourceCharacters(): Unit =
      var cursor = 0
      var reported = false
      while cursor < input.length do
        val first = input.charAt(cursor)
        val (codePoint, width, paired) =
          if Unicode.isHighSurrogate(first) && cursor + 1 < input.length && Unicode.isLowSurrogate(input.charAt(cursor + 1)) then
            val high = first.toInt - 0xD800
            val low = input.charAt(cursor + 1).toInt - 0xDC00
            (0x10000 + (high << 10) + low, 2, true)
          else (first.toInt, 1, false)
        val rawSurrogate = !paired && (Unicode.isHighSurrogate(first) || Unicode.isLowSurrogate(first))
        if !reported && (!isLegalXmlCodePoint(codePoint) || rawSurrogate) then
          diagnostics = diagnostics :+ Diagnostic(
            "uniml.xml.invalid-character",
            f"illegal XML 1.0 character U+$codePoint%04X",
            Severity.Error,
            None,
            Some(XmlDialect.id),
          )
          reported = true
        cursor += width

    validateSourceCharacters()
    while index < input.length do
      if input.startsWith("<?xml", index) then scanDeclaration()
      else if input.startsWith("<!--", index) then scanOpaque("-->", "xml.comment", TokenChannel.Comment, "content.comment", validateComment)
      else if input.startsWith("<![CDATA[", index) then scanCData()
      else if input.startsWith("<!DOCTYPE", index) then scanDoctype()
      else if input.startsWith("<?", index) then scanOpaque("?>", "xml.pi", TokenChannel.Syntax, "content.pi", validatePi)
      else if input.startsWith("</", index) then scanEndTag()
      else if input.charAt(index) == '<' then scanStartTag()
      else if input.charAt(index) == '&' then scanReference()
      else scanText()
    if rootCount == 0 then diagnostics = diagnostics :+ eofDiagnostic("uniml.xml.missing-root", "XML document has no root element")
    if elements.nonEmpty then diagnostics = diagnostics :+ eofDiagnostic("uniml.xml.unexpected-eof", s"unclosed XML element <${elements.last}>")
    ProcessBatch(output, diagnostics)

  private def tokenDiagnostic(token: SourceToken, code: String, message: String, severity: Severity = Severity.Error): Diagnostic =
    Diagnostic(code, message, severity, Some(token.span), Some(XmlDialect.id))

  private def validateComment(value: String): Option[(String, String)] =
    if value.substring(4, value.length - 3).contains("--") then Some("uniml.xml.invalid-comment" -> "XML comment contains '--'") else None

  private def validatePi(value: String): Option[(String, String)] =
    val target = value.drop(2).takeWhile(char => !isXmlWhitespace(char) && char != '?')
    if target.isEmpty || target.equalsIgnoreCase("xml") then Some("uniml.xml.invalid-pi" -> "invalid XML processing-instruction target") else None

  private def isXmlWhitespace(char: Char): Boolean = char == ' ' || char == '\t' || char == '\n' || char == '\r'

  private def isNameDelimiter(char: Char): Boolean =
    isXmlWhitespace(char) || char == '/' || char == '>' || char == '=' || char == '\'' || char == '"' || char == '<'

  /** Portable equivalent of the XML-declaration regex (no regex, no java.*): validates
    * `<?xml WS+ version WS* = WS* Q 1.0 Q ( WS+ encoding WS* = WS* Q name Q )? ( WS+ standalone WS* = WS* Q (yes|no) Q )? WS* ?>`
    * over the whole lexeme. Cursor helpers thread a position and use -1 as a failure sentinel. */
  private def isValidDeclaration(lexeme: String): Boolean =
    var cursor = matchLiteral(lexeme, 0, "<?xml")
    cursor = matchRequiredWhitespace(lexeme, cursor)
    cursor = matchLiteral(lexeme, cursor, "version")
    cursor = matchEquals(lexeme, cursor)
    cursor = matchQuotedLiteral(lexeme, cursor, "1.0")
    cursor = matchOptionalEncoding(lexeme, cursor)
    cursor = matchOptionalStandalone(lexeme, cursor)
    cursor = skipXmlWhitespace(lexeme, cursor)
    cursor = matchLiteral(lexeme, cursor, "?>")
    cursor == lexeme.length

  /** Portable equivalent of the reference regex `&(?:#[0-9]+|#x[0-9A-Fa-f]+|[A-Za-z_:][A-Za-z0-9_.:-]*);`.
    * The named entities lt/gt/amp/apos/quot are subsumed by the entity-name form. */
  private def isValidReference(reference: String): Boolean =
    val length = reference.length
    if length < 3 || reference.charAt(0) != '&' || reference.charAt(length - 1) != ';' then false
    else
      val inner = reference.substring(1, length - 1)
      isNumericReferenceBody(inner) || isEntityName(inner)

  private def matchLiteral(s: String, from: Int, literal: String): Int =
    if from >= 0 && s.startsWith(literal, from) then from + literal.length else -1

  private def skipXmlWhitespace(s: String, from: Int): Int =
    if from < 0 then -1
    else
      var cursor = from
      while cursor < s.length && isXmlWhitespace(s.charAt(cursor)) do cursor += 1
      cursor

  private def matchRequiredWhitespace(s: String, from: Int): Int =
    val next = skipXmlWhitespace(s, from)
    if next > from then next else -1

  private def matchEquals(s: String, from: Int): Int =
    val atEquals = skipXmlWhitespace(s, from)
    if atEquals < 0 || atEquals >= s.length || s.charAt(atEquals) != '=' then -1
    else skipXmlWhitespace(s, atEquals + 1)

  private def matchQuotedLiteral(s: String, from: Int, value: String): Int =
    if from < 0 || from >= s.length then -1
    else
      val quote = s.charAt(from)
      if (quote != '\'' && quote != '"') || !s.startsWith(value, from + 1) then -1
      else
        val after = from + 1 + value.length
        if after < s.length && s.charAt(after) == quote then after + 1 else -1

  private def matchOptionalEncoding(s: String, from: Int): Int =
    if from < 0 then -1
    else
      val afterWhitespace = skipXmlWhitespace(s, from)
      if afterWhitespace > from && s.startsWith("encoding", afterWhitespace) then
        matchQuotedEncoding(s, matchEquals(s, afterWhitespace + "encoding".length))
      else from

  private def matchOptionalStandalone(s: String, from: Int): Int =
    if from < 0 then -1
    else
      val afterWhitespace = skipXmlWhitespace(s, from)
      if afterWhitespace > from && s.startsWith("standalone", afterWhitespace) then
        matchQuotedEnum(s, matchEquals(s, afterWhitespace + "standalone".length))
      else from

  private def matchQuotedEncoding(s: String, from: Int): Int =
    if from < 0 || from >= s.length then -1
    else
      val quote = s.charAt(from)
      if quote != '\'' && quote != '"' then -1
      else if from + 1 >= s.length || !isAsciiLetter(s.charAt(from + 1)) then -1
      else
        var cursor = from + 2
        while cursor < s.length && isEncodingNameChar(s.charAt(cursor)) do cursor += 1
        if cursor < s.length && s.charAt(cursor) == quote then cursor + 1 else -1

  private def matchQuotedEnum(s: String, from: Int): Int =
    val yes = matchQuotedLiteral(s, from, "yes")
    if yes >= 0 then yes else matchQuotedLiteral(s, from, "no")

  private def isNumericReferenceBody(inner: String): Boolean =
    if inner.startsWith("#x") then
      val digits = inner.substring(2)
      digits.nonEmpty && digits.forall(isHexDigit)
    else if inner.startsWith("#") then
      val digits = inner.substring(1)
      digits.nonEmpty && digits.forall(isAsciiDigit)
    else false

  private def isEntityName(inner: String): Boolean =
    inner.nonEmpty && isEntityNameStart(inner.charAt(0)) && inner.substring(1).forall(isEntityNameChar)

  private def isAsciiDigit(char: Char): Boolean = char >= '0' && char <= '9'

  private def isAsciiLetter(char: Char): Boolean =
    (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z')

  private def isHexDigit(char: Char): Boolean =
    isAsciiDigit(char) || (char >= 'a' && char <= 'f') || (char >= 'A' && char <= 'F')

  private def isEntityNameStart(char: Char): Boolean =
    isAsciiLetter(char) || char == '_' || char == ':'

  private def isEntityNameChar(char: Char): Boolean =
    isEntityNameStart(char) || isAsciiDigit(char) || char == '.' || char == '-'

  private def isEncodingNameChar(char: Char): Boolean =
    isAsciiLetter(char) || isAsciiDigit(char) || char == '.' || char == '_' || char == '-'

  private def validXmlName(value: String): Boolean =
    var cursor = 0
    var first = true
    var valid = value.nonEmpty
    while cursor < value.length && valid do
      val high = value.charAt(cursor)
      val (codePoint, width) =
        if Unicode.isHighSurrogate(high) && cursor + 1 < value.length && Unicode.isLowSurrogate(value.charAt(cursor + 1)) then
          (0x10000 + ((high.toInt - 0xD800) << 10) + value.charAt(cursor + 1).toInt - 0xDC00, 2)
        else (high.toInt, 1)
      valid = if first then isNameStartCodePoint(codePoint) else isNameCharCodePoint(codePoint)
      first = false
      cursor += width
    valid

  private def isNameStartCodePoint(value: Int): Boolean =
    value == ':' || value == '_' ||
      (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z') ||
      (value >= 0xC0 && value <= 0xD6) || (value >= 0xD8 && value <= 0xF6) ||
      (value >= 0xF8 && value <= 0x2FF) || (value >= 0x370 && value <= 0x37D) ||
      (value >= 0x37F && value <= 0x1FFF) || (value >= 0x200C && value <= 0x200D) ||
      (value >= 0x2070 && value <= 0x218F) || (value >= 0x2C00 && value <= 0x2FEF) ||
      (value >= 0x3001 && value <= 0xD7FF) || (value >= 0xF900 && value <= 0xFDCF) ||
      (value >= 0xFDF0 && value <= 0xFFFD) || (value >= 0x10000 && value <= 0xEFFFF)

  private def isNameCharCodePoint(value: Int): Boolean =
    isNameStartCodePoint(value) || value == '-' || value == '.' ||
      (value >= '0' && value <= '9') || value == 0xB7 ||
      (value >= 0x300 && value <= 0x36F) || (value >= 0x203F && value <= 0x2040)

  private def numericReferenceValue(reference: String): Option[Int] =
    val digits =
      if reference.startsWith("&#x") then Some(reference.substring(3, reference.length - 1) -> 16)
      else if reference.startsWith("&#") then Some(reference.substring(2, reference.length - 1) -> 10)
      else None
    digits.flatMap { case (value, radix) =>
      var result = 0L
      var cursor = 0
      var valid = value.nonEmpty
      while cursor < value.length && valid do
        val char = value.charAt(cursor)
        val digit =
          if char >= '0' && char <= '9' then char - '0'
          else if radix == 16 && char >= 'a' && char <= 'f' then char - 'a' + 10
          else if radix == 16 && char >= 'A' && char <= 'F' then char - 'A' + 10
          else -1
        if digit < 0 then valid = false
        else
          result = result * radix + digit
          if result > 0x10FFFF then valid = false
        cursor += 1
      if valid then Some(result.toInt) else Some(-1)
    }

  private def isLegalXmlCodePoint(value: Int): Boolean =
    value == 0x9 || value == 0xA || value == 0xD ||
      (value >= 0x20 && value <= 0xD7FF) ||
      (value >= 0xE000 && value <= 0xFFFD) ||
      (value >= 0x10000 && value <= 0x10FFFF)

  private def validAttributeReferences(value: String): Boolean =
    var cursor = 0
    var valid = true
    while cursor < value.length && valid do
      if value.charAt(cursor) == '&' then
        val end = value.indexOf(';', cursor + 1)
        if end < 0 then valid = false
        else
          val reference = value.substring(cursor, end + 1)
          val syntaxValid = isValidReference(reference)
          valid = syntaxValid && numericReferenceValue(reference).forall(isLegalXmlCodePoint)
          cursor = end
      cursor += 1
    valid
