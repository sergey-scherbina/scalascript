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

  def instructions(source: SourceInput): Processor[SourceChunk, VmToken] =
    XmlProcessor(source.source, XmlLimits.default)

  def withLimits(limits: XmlLimits): DialectAdapter = ConfiguredXmlDialect(limits)

private final case class ConfiguredXmlDialect(limits: XmlLimits) extends DialectAdapter:
  val id: String = XmlDialect.id
  override val aliases: Set[String] = XmlDialect.aliases
  def instructions(source: SourceInput): Processor[SourceChunk, VmToken] = XmlProcessor(source.source, limits)

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

private final class XmlProcessor(source: SourceId, limits: XmlLimits) extends Processor[SourceChunk, VmToken]:
  private val input = StringBuilder()
  private var sourceCodePoints = 0L
  private var limitDiagnostic: Option[Diagnostic] = None
  private var finished = false

  def push(chunk: SourceChunk): ProcessBatch[VmToken] =
    if finished then ProcessBatch(Vector.empty, Vector(finishedDiagnostic))
    else if limitDiagnostic.nonEmpty then ProcessBatch.empty
    else
      sourceCodePoints += Unicode.codePointCount(chunk.text).toLong
      if sourceCodePoints > limits.maxSourceCodePoints then
        val diagnostic = Diagnostic(
          "uniml.xml.limit.source",
          s"XML source exceeds the ${limits.maxSourceCodePoints} code-point limit",
          Severity.Fatal,
          None,
          Some(XmlDialect.id),
        )
        limitDiagnostic = Some(diagnostic)
        ProcessBatch(Vector.empty, Vector(diagnostic))
      else
        input.append(chunk.text)
        ProcessBatch.empty

  def finish(): ProcessBatch[VmToken] =
    if finished then ProcessBatch(Vector.empty, Vector(finishedDiagnostic))
    else
      finished = true
      limitDiagnostic match
        case Some(_) => ProcessBatch.empty
        case None    => XmlScanner(source, input.result(), limits).scan()

  private val finishedDiagnostic = Diagnostic(
    "uniml.xml.finished",
    "XML dialect processor cannot accept input or finish more than once",
    Severity.Error,
    None,
    Some(XmlDialect.id),
  )

private object XmlScanner:
  def apply(source: SourceId, input: String, limits: XmlLimits): XmlScanner =
    new XmlScanner(source, input, limits)

private final class XmlScanner(source: SourceId, input: String, limits: XmlLimits):
  private val output = ArrayBuffer.empty[VmToken]
  private val diagnostics = ArrayBuffer.empty[Diagnostic]
  private val elements = ArrayBuffer.empty[String]
  private var index = 0
  private var position = SourcePosition.Start
  private var nextTokenId = 0L
  private var rootCount = 0
  private var seenDoctype = false
  private var seenDeclaration = false

  def scan(): ProcessBatch[VmToken] =
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
    if rootCount == 0 then diagnostics += eofDiagnostic("uniml.xml.missing-root", "XML document has no root element")
    if elements.nonEmpty then diagnostics += eofDiagnostic("uniml.xml.unexpected-eof", s"unclosed XML element <${elements.last}>")
    ProcessBatch(output.toVector, diagnostics.toVector)

  private def scanDeclaration(): Unit =
    val start = index
    val end = input.indexOf("?>", index + 5)
    if end < 0 then emitRestError("uniml.xml.invalid-declaration", "unterminated XML declaration")
    else
      val lexeme = input.substring(start, end + 2)
      val valid = index == 0 && !seenDeclaration && lexeme.matches("<\\?xml\\s+version\\s*=\\s*(['\"])1\\.0\\1(?:\\s+encoding\\s*=\\s*(['\"])[A-Za-z][A-Za-z0-9._-]*\\2)?(?:\\s+standalone\\s*=\\s*(['\"])(?:yes|no)\\3)?\\s*\\?>")
      seenDeclaration = true
      emitWhole(lexeme, "xml.declaration", TokenChannel.Syntax,
        if valid then VmInstruction.Emit(Some("document.declaration"))
        else VmInstruction.Report(if start == 0 then "uniml.xml.invalid-declaration" else "uniml.xml.declaration-position", "invalid XML declaration"))

  private def scanDoctype(): Unit =
    val start = index
    var cursor = index + 9
    var subsetDepth = 0
    var quote: Char = 0.toChar
    var done = false
    while cursor < input.length && !done do
      val char = input.charAt(cursor)
      if quote != 0 then
        if char == quote then quote = 0.toChar
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

  private def scanStartTag(): Unit =
    val parentOpen = elements.nonEmpty
    emitWhole("<", "xml.start-open", TokenChannel.Syntax,
      VmInstruction.Open("xml.element", Some(if parentOpen then "content.child" else "document.root")))
    val name = scanName("element.name")
    if !parentOpen then rootCount += 1
    if rootCount > 1 && !parentOpen then diagnostics += tokenDiagnostic(output.last.token, "uniml.xml.multiple-roots", "XML document has multiple root elements")
    elements += name
    val attributes = scala.collection.mutable.HashSet.empty[String]
    var attributeCount = 0
    var closed = false
    while index < input.length && !closed do
      if input.startsWith("/>", index) then
        emitWhole("/>", "xml.empty-close", TokenChannel.Syntax, VmInstruction.Close(Some("xml.element"), Some("empty-tag.close")))
        elements.remove(elements.size - 1)
        closed = true
      else if input.charAt(index) == '>' then
        emitWhole(">", "xml.tag-close", TokenChannel.Syntax, VmInstruction.Emit(Some("start-tag.close")))
        closed = true
      else if isXmlWhitespace(input.charAt(index)) then scanMarkupWhitespace()
      else
        val attribute = scanName("attribute.name")
        attributeCount += 1
        if !attributes.add(attribute) then diagnostics += tokenDiagnostic(output.last.token, "uniml.xml.duplicate-attribute", s"duplicate XML attribute '$attribute'")
        if attributeCount > limits.maxAttributesPerElement then diagnostics += tokenDiagnostic(output.last.token, "uniml.xml.limit.attribute", "too many XML attributes", Severity.Fatal)
        if index < input.length && isXmlWhitespace(input.charAt(index)) then scanMarkupWhitespace()
        if index < input.length && input.charAt(index) == '=' then emitWhole("=", "xml.equals", TokenChannel.Syntax, VmInstruction.Emit(Some("attribute.equals")))
        else diagnostics += eofDiagnostic("uniml.xml.expected-equals", s"expected '=' after attribute '$attribute'")
        if index < input.length && isXmlWhitespace(input.charAt(index)) then scanMarkupWhitespace()
        scanAttributeValue()
    if !closed then diagnostics += eofDiagnostic("uniml.xml.unexpected-eof", s"unterminated start tag <$name>")

  private def scanEndTag(): Unit =
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
      if matches then elements.remove(elements.size - 1)
    else diagnostics += eofDiagnostic("uniml.xml.unexpected-eof", s"unterminated end tag </$name>")

  private def scanAttributeValue(): Unit =
    if index >= input.length || (input.charAt(index) != '\'' && input.charAt(index) != '"') then
      diagnostics += eofDiagnostic("uniml.xml.expected-attribute-value", "expected quoted XML attribute value")
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
      val invalid = !lexeme.endsWith(quote.toString) || content.contains('<') || !validAttributeReferences(content)
      val tooLong = Unicode.codePointCount(lexeme) > limits.maxAttributeCodePoints
      emitKnownRange(start, lexeme, "xml.attribute-value", TokenChannel.Syntax,
        if tooLong then VmInstruction.Report("uniml.xml.limit.attribute", "XML attribute value exceeds configured limit", Severity.Fatal)
        else if invalid then VmInstruction.Report("uniml.xml.expected-attribute-value", "invalid XML attribute value")
        else VmInstruction.Emit(Some("attribute.value")))

  private def scanCData(): Unit =
    scanOpaque("]]>", "xml.cdata", TokenChannel.Syntax, "content.cdata", _ =>
      if elements.isEmpty then Some("uniml.xml.invalid-cdata" -> "CDATA is only allowed inside element content") else None)

  private def scanReference(): Unit =
    val end = input.indexOf(';', index + 1)
    if end < 0 then emitRestError("uniml.xml.invalid-reference", "unterminated XML reference")
    else
      val lexeme = input.substring(index, end + 1)
      val syntaxValid = lexeme.matches("&(?:lt|gt|amp|apos|quot|#[0-9]+|#x[0-9A-Fa-f]+|[A-Za-z_:][A-Za-z0-9_.:-]*);")
      val valid = syntaxValid && numericReferenceValue(lexeme).forall(isLegalXmlCodePoint)
      emitWhole(lexeme, "xml.reference", TokenChannel.Syntax,
        if valid then VmInstruction.Emit(Some(if elements.nonEmpty then "content.reference" else "document.reference"))
        else VmInstruction.Report("uniml.xml.invalid-reference", "invalid XML reference"))

  private def scanText(): Unit =
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

  private def scanMarkupWhitespace(): Unit =
    val start = index
    while index < input.length && isXmlWhitespace(input.charAt(index)) do index += 1
    emitKnownRange(start, input.substring(start, index), "xml.whitespace", TokenChannel.Trivia, VmInstruction.Emit(Some("markup.whitespace")))

  private def scanName(role: String): String =
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

  private def scanOpaque(
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

  private def validateComment(value: String): Option[(String, String)] =
    if value.substring(4, value.length - 3).contains("--") then Some("uniml.xml.invalid-comment" -> "XML comment contains '--'") else None

  private def validatePi(value: String): Option[(String, String)] =
    val target = value.drop(2).takeWhile(char => !isXmlWhitespace(char) && char != '?')
    if target.isEmpty || target.equalsIgnoreCase("xml") then Some("uniml.xml.invalid-pi" -> "invalid XML processing-instruction target") else None

  private def emitRestError(code: String, message: String): Unit =
    val lexeme = input.substring(index)
    emitWhole(lexeme, "xml.invalid", TokenChannel.Error, VmInstruction.Report(code, message))

  private def emitWhole(lexeme: String, kind: String, channel: TokenChannel, instruction: VmInstruction): Unit =
    val start = index
    index += lexeme.length
    emitKnownRange(start, lexeme, kind, channel, instruction)

  private def emitKnownRange(startIndex: Int, lexeme: String, kind: String, channel: TokenChannel, instruction: VmInstruction): Unit =
    val start = position
    val end = Unicode.advance(start, lexeme)
    position = end
    val token = SourceToken(nextTokenId, kind, lexeme, SourceSpan(source, start, end), channel)
    nextTokenId += 1
    output += VmToken(token, instruction)
    val _ = startIndex

  private def tokenDiagnostic(token: SourceToken, code: String, message: String, severity: Severity = Severity.Error): Diagnostic =
    Diagnostic(code, message, severity, Some(token.span), Some(XmlDialect.id))

  private def eofDiagnostic(code: String, message: String): Diagnostic =
    Diagnostic(code, message, Severity.Error, Some(SourceSpan(source, position, position)), Some(XmlDialect.id))

  private def isXmlWhitespace(char: Char): Boolean = char == ' ' || char == '\t' || char == '\n' || char == '\r'

  private def isNameDelimiter(char: Char): Boolean =
    isXmlWhitespace(char) || char == '/' || char == '>' || char == '=' || char == '\'' || char == '"' || char == '<'

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

  private def validateSourceCharacters(): Unit =
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
        diagnostics += Diagnostic(
          "uniml.xml.invalid-character",
          f"illegal XML 1.0 character U+$codePoint%04X",
          Severity.Error,
          None,
          Some(XmlDialect.id),
        )
        reported = true
      cursor += width

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
          if result > 0x10FFFFL then valid = false
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
          val syntaxValid = reference.matches("&(?:lt|gt|amp|apos|quot|#[0-9]+|#x[0-9A-Fa-f]+|[A-Za-z_:][A-Za-z0-9_.:-]*);")
          valid = syntaxValid && numericReferenceValue(reference).forall(isLegalXmlCodePoint)
          cursor = end
      cursor += 1
    valid
