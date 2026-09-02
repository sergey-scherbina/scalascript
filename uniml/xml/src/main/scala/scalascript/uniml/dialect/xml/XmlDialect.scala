package scalascript.uniml.dialect.xml

import scalascript.markup.{Markup, PureMarkupCodec}
import scalascript.uniml.*

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
    val source = result.roots.flatMap(UniNode.sourceTokens).sortBy(_.id).map(_.lexeme).mkString("")
    PureMarkupCodec.parse(source).left.map(error => Diagnostic(
      "uniml.xml.projection-invalid-cst",
      error.getMessage,
      Severity.Error,
      None,
      Some(XmlDialect.id),
    ))

  /** One element's namespace fold: the diagnostics so far, the bindings in force, and the
    * prefixes this element itself declared. Vector + contains rather than a Set for the
    * dedup, per the scanner's own rule below: v2 has no Set.empty companion. */
  private final case class NsBind(diags: Vector[Diagnostic], bindings: Map[String, String], declared: Vector[String])

  private def validateNamespaces(root: Markup.Element): Vector[Diagnostic] =
    def checkElement(element: Markup.Element, inherited: Map[String, String]): Vector[Diagnostic] =
      val bound = element.attrs.foldLeft(NsBind(Vector.empty, inherited, Vector.empty)) { (acc, attribute) =>
        namespaceDeclaration(attribute) match
          case None => acc
          case Some(decl) =>
            val prefix = decl._1
            val uri = decl._2
            val dup = acc.declared.contains(prefix)
            val diags0 = if dup then acc.diags :+ namespaceDiagnostic("duplicate namespace declaration") else acc.diags
            val declared = if dup then acc.declared else acc.declared :+ prefix
            if prefix == "xmlns" || uri == XmlnsNamespaceUri || (prefix == XmlNamespace && uri != XmlNamespaceUri) ||
                (prefix != XmlNamespace && uri == XmlNamespaceUri) || (prefix.nonEmpty && uri.isEmpty) then
              NsBind(diags0 :+ namespaceDiagnostic(s"invalid namespace binding '$prefix' -> '$uri'"), acc.bindings, declared)
            else NsBind(diags0, acc.bindings.updated(prefix, uri), declared)
      }
      val withElementPrefix = bound.diags ++ element.name.prefix.toVector.collect {
        case prefix if !bound.bindings.contains(prefix) => namespaceDiagnostic(s"unbound element prefix '$prefix'")
      }
      // duplicate EXPANDED attribute names: same Vector-based dedup, over (namespace, localName)
      val attrChecked = element.attrs.filter(namespaceDeclaration(_).isEmpty)
        .foldLeft((withElementPrefix, Vector.empty[(Option[String], String)])) { (acc, attribute) =>
          val namespace = attribute.name.prefix.flatMap(bound.bindings.get)
          val withPrefix = acc._1 ++ attribute.name.prefix.toVector.collect {
            case prefix if !bound.bindings.contains(prefix) => namespaceDiagnostic(s"unbound attribute prefix '$prefix'")
          }
          val key = (namespace, attribute.name.localName)
          if acc._2.contains(key) then
            (withPrefix :+ namespaceDiagnostic(s"duplicate expanded attribute '${attribute.name.localName}'"), acc._2)
          else (withPrefix, acc._2 :+ key)
        }
      element.children.collect { case child: Markup.Element => child }
        .foldLeft(attrChecked._1)((diags, child) => diags ++ checkElement(child, bound.bindings))
    checkElement(root, Map(XmlNamespace -> XmlNamespaceUri))

  private def resolveElement(element: Markup.Element, inherited: Map[String, String]): Markup.Element =
    val bindings = element.attrs.foldLeft(inherited) { (acc, attribute) =>
      namespaceDeclaration(attribute) match
        case Some(decl) => acc.updated(decl._1, decl._2)
        case None       => acc
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
  * All scanning state is one [[XmlScanState]] record threaded through the
  * per-construct scanners (each takes the state and returns the next one);
  * pure classifiers stay top-level. */
private object XmlScanner:
  /** The whole scan in flight, one field per former `var`, declaration order kept. */
  private final case class XmlScanState(
      output: Vector[VmToken],
      diagnostics: Vector[Diagnostic],
      elements: Vector[String],
      index: Int,
      position: SourcePosition,
      nextTokenId: Long,
      rootCount: Int,
      seenDoctype: Boolean,
      seenDeclaration: Boolean,
  )

  /** `scanName`'s result: the advanced state plus the name it read. */
  private final case class NamedScan(state: XmlScanState, name: String)

  def scan(source: SourceId, input: String, limits: XmlLimits): ProcessBatch[VmToken] =
    def eofDiagnostic(st: XmlScanState, code: String, message: String): Diagnostic =
      Diagnostic(code, message, Severity.Error, Some(SourceSpan(source, st.position, st.position)), Some(XmlDialect.id))

    def emitKnownRange(st: XmlScanState, lexeme: String, kind: String, channel: TokenChannel, instruction: VmInstruction): XmlScanState =
      val start = st.position
      val end = Unicode.advance(start, lexeme)
      val token = SourceToken(st.nextTokenId, kind, lexeme, SourceSpan(source, start, end), channel)
      st.copy(output = st.output :+ VmToken(token, instruction), position = end, nextTokenId = st.nextTokenId + 1)

    def emitWhole(st: XmlScanState, lexeme: String, kind: String, channel: TokenChannel, instruction: VmInstruction): XmlScanState =
      emitKnownRange(st.copy(index = st.index + lexeme.length), lexeme, kind, channel, instruction)

    def emitRestError(st: XmlScanState, code: String, message: String): XmlScanState =
      val lexeme = input.substring(st.index)
      emitWhole(st, lexeme, "xml.invalid", TokenChannel.Error, VmInstruction.Report(code, message))

    def scanDeclaration(st: XmlScanState): XmlScanState =
      val start = st.index
      val end = input.indexOf("?>", st.index + 5)
      if end < 0 then emitRestError(st, "uniml.xml.invalid-declaration", "unterminated XML declaration")
      else
        val lexeme = input.substring(start, end + 2)
        val valid = st.index == 0 && !st.seenDeclaration && isValidDeclaration(lexeme)
        emitWhole(st.copy(seenDeclaration = true), lexeme, "xml.declaration", TokenChannel.Syntax,
          if valid then VmInstruction.Emit(Some("document.declaration"))
          else VmInstruction.Report(if start == 0 then "uniml.xml.invalid-declaration" else "uniml.xml.declaration-position", "invalid XML declaration"))

    def scanDoctype(st: XmlScanState): XmlScanState =
      val start = st.index
      // index just past the `>` that closes the DOCTYPE (quotes and `[…]` subsets honoured), -1 if unterminated
      def close(cursor: Int, subsetDepth: Int, quote: Char): Int =
        if cursor >= input.length then -1
        else
          val char = input.charAt(cursor)
          if quote != ' ' then close(cursor + 1, subsetDepth, if char == quote then ' ' else quote)
          else if char == '\'' || char == '"' then close(cursor + 1, subsetDepth, char)
          else if char == '[' then close(cursor + 1, subsetDepth + 1, quote)
          else if char == ']' && subsetDepth > 0 then close(cursor + 1, subsetDepth - 1, quote)
          else if char == '>' && subsetDepth == 0 then cursor + 1
          else close(cursor + 1, subsetDepth, quote)
      val cursor = close(st.index + 9, 0, ' ')
      if cursor < 0 then emitRestError(st, "uniml.xml.invalid-doctype", "unterminated XML DOCTYPE")
      else
        val lexeme = input.substring(start, cursor)
        val codePoints = Unicode.codePointCount(lexeme)
        val validPosition = st.elements.isEmpty && st.rootCount == 0 && !st.seenDoctype
        val instruction =
          if codePoints > limits.maxDoctypeCodePoints then VmInstruction.Report("uniml.xml.limit.doctype", "XML DOCTYPE exceeds configured limit", Severity.Fatal)
          else if validPosition then VmInstruction.Emit(Some("document.doctype"))
          else VmInstruction.Report("uniml.xml.doctype-position", "DOCTYPE must appear once before the root element")
        emitWhole(st.copy(seenDoctype = true), lexeme, "xml.doctype", TokenChannel.Syntax, instruction)

    def scanName(st: XmlScanState, role: String): NamedScan =
      val start = st.index
      def nameEnd(i: Int): Int =
        if i < input.length && !isNameDelimiter(input.charAt(i)) then nameEnd(i + 1) else i
      val scanned = nameEnd(start)
      val stop = if scanned == start && scanned < input.length then scanned + 1 else scanned
      val lexeme = input.substring(start, stop)
      val tooLong = Unicode.codePointCount(lexeme) > limits.maxNameCodePoints
      val valid = !tooLong && validXmlName(lexeme) && lexeme.count(_ == ':') <= 1
      val emitted = emitKnownRange(st.copy(index = stop), lexeme,
        if valid then "xml.name" else "xml.invalid",
        if valid then TokenChannel.Syntax else TokenChannel.Error,
        if valid then VmInstruction.Emit(Some(role))
        else if tooLong then VmInstruction.Report("uniml.xml.limit.name", "XML name exceeds configured limit", Severity.Fatal)
        else VmInstruction.Report("uniml.xml.invalid-name", "invalid XML name"))
      NamedScan(emitted, lexeme)

    def scanMarkupWhitespace(st: XmlScanState): XmlScanState =
      val start = st.index
      def wsEnd(i: Int): Int =
        if i < input.length && isXmlWhitespace(input.charAt(i)) then wsEnd(i + 1) else i
      val stop = wsEnd(start)
      emitKnownRange(st.copy(index = stop), input.substring(start, stop), "xml.whitespace", TokenChannel.Trivia, VmInstruction.Emit(Some("markup.whitespace")))

    def scanAttributeValue(st: XmlScanState): XmlScanState =
      if st.index >= input.length || (input.charAt(st.index) != '\'' && input.charAt(st.index) != '"') then
        val reported = st.copy(diagnostics = st.diagnostics :+ eofDiagnostic(st, "uniml.xml.expected-attribute-value", "expected quoted XML attribute value"))
        if reported.index < input.length && input.charAt(reported.index) != '>' then
          val lexeme = input.substring(reported.index, reported.index + 1)
          emitWhole(reported, lexeme, "xml.invalid", TokenChannel.Error,
            VmInstruction.Report("uniml.xml.expected-attribute-value", "expected quoted XML attribute value"))
        else reported
      else
        val start = st.index
        val quote = input.charAt(st.index)
        def valueEnd(i: Int): Int =
          if i < input.length && input.charAt(i) != quote then valueEnd(i + 1) else i
        val closeAt = valueEnd(start + 1)
        val stop = if closeAt < input.length then closeAt + 1 else closeAt
        val lexeme = input.substring(start, stop)
        val content = lexeme.substring(1, math.max(1, lexeme.length - 1))
        // `input.substring(start, start + 1)` (the opening quote) rather than
        // `quote.toString`: v2 has no Char box, so `quote.toString` yields the code
        // point's decimal digits. Identical on the JVM (start holds the same quote char).
        val invalid = !lexeme.endsWith(input.substring(start, start + 1)) || content.contains('<') || !validAttributeReferences(content)
        val tooLong = Unicode.codePointCount(lexeme) > limits.maxAttributeCodePoints
        emitKnownRange(st.copy(index = stop), lexeme, "xml.attribute-value", TokenChannel.Syntax,
          if tooLong then VmInstruction.Report("uniml.xml.limit.attribute", "XML attribute value exceeds configured limit", Severity.Fatal)
          else if invalid then VmInstruction.Report("uniml.xml.expected-attribute-value", "invalid XML attribute value")
          else VmInstruction.Emit(Some("attribute.value")))

    def scanStartTag(st0: XmlScanState): XmlScanState =
      val parentOpen = st0.elements.nonEmpty
      val opened = emitWhole(st0, "<", "xml.start-open", TokenChannel.Syntax,
        VmInstruction.Open("xml.element", Some(if parentOpen then "content.child" else "document.root")))
      val named = scanName(opened, "element.name")
      val name = named.name
      val counted = if !parentOpen then named.state.copy(rootCount = named.state.rootCount + 1) else named.state
      val rootChecked =
        if counted.rootCount > 1 && !parentOpen then
          counted.copy(diagnostics = counted.diagnostics :+ tokenDiagnostic(counted.output.last.token, "uniml.xml.multiple-roots", "XML document has multiple root elements"))
        else counted
      val entered = rootChecked.copy(elements = rootChecked.elements :+ name)
      // Vector (with the preceding contains-check) instead of Set: v2 has no
      // Set.empty companion, and dedup is redundant here (a duplicate is reported
      // before the add, so the Vector never actually holds a duplicate).
      def attrs(st: XmlScanState, attributes: Vector[String], attributeCount: Int): XmlScanState =
        if st.index >= input.length then
          st.copy(diagnostics = st.diagnostics :+ eofDiagnostic(st, "uniml.xml.unexpected-eof", s"unterminated start tag <$name>"))
        else if input.startsWith("/>", st.index) then
          val closed = emitWhole(st, "/>", "xml.empty-close", TokenChannel.Syntax, VmInstruction.Close(Some("xml.element"), Some("empty-tag.close")))
          closed.copy(elements = closed.elements.dropRight(1))
        else if input.charAt(st.index) == '>' then
          emitWhole(st, ">", "xml.tag-close", TokenChannel.Syntax, VmInstruction.Emit(Some("start-tag.close")))
        else if isXmlWhitespace(input.charAt(st.index)) then attrs(scanMarkupWhitespace(st), attributes, attributeCount)
        else
          val attrNamed = scanName(st, "attribute.name")
          val attribute = attrNamed.name
          val count = attributeCount + 1
          val dupChecked =
            if attributes.contains(attribute) then
              attrNamed.state.copy(diagnostics = attrNamed.state.diagnostics :+ tokenDiagnostic(attrNamed.state.output.last.token, "uniml.xml.duplicate-attribute", s"duplicate XML attribute '$attribute'"))
            else attrNamed.state
          val limitChecked =
            if count > limits.maxAttributesPerElement then
              dupChecked.copy(diagnostics = dupChecked.diagnostics :+ tokenDiagnostic(dupChecked.output.last.token, "uniml.xml.limit.attribute", "too many XML attributes", Severity.Fatal))
            else dupChecked
          val ws1 = if limitChecked.index < input.length && isXmlWhitespace(input.charAt(limitChecked.index)) then scanMarkupWhitespace(limitChecked) else limitChecked
          val equaled =
            if ws1.index < input.length && input.charAt(ws1.index) == '=' then
              emitWhole(ws1, "=", "xml.equals", TokenChannel.Syntax, VmInstruction.Emit(Some("attribute.equals")))
            else ws1.copy(diagnostics = ws1.diagnostics :+ eofDiagnostic(ws1, "uniml.xml.expected-equals", s"expected '=' after attribute '$attribute'"))
          val ws2 = if equaled.index < input.length && isXmlWhitespace(input.charAt(equaled.index)) then scanMarkupWhitespace(equaled) else equaled
          attrs(scanAttributeValue(ws2), attributes :+ attribute, count)
      attrs(entered, Vector.empty, 0)

    def scanEndTag(st0: XmlScanState): XmlScanState =
      val opened = emitWhole(st0, "</", "xml.end-open", TokenChannel.Syntax,
        if st0.elements.nonEmpty then VmInstruction.Emit(Some("end-tag.open"))
        else VmInstruction.Report("uniml.xml.unexpected-end-tag", "end tag has no open element"))
      val named = scanName(opened, "end-tag.name")
      val name = named.name
      def ws(st: XmlScanState): XmlScanState =
        if st.index < input.length && isXmlWhitespace(input.charAt(st.index)) then ws(scanMarkupWhitespace(st)) else st
      val skipped = ws(named.state)
      if skipped.index < input.length && input.charAt(skipped.index) == '>' then
        val matches = skipped.elements.nonEmpty && skipped.elements.last == name
        val closed = emitWhole(skipped, ">", "xml.tag-close", TokenChannel.Syntax,
          if matches then VmInstruction.Close(Some("xml.element"), Some("end-tag.close"))
          else VmInstruction.Report("uniml.xml.mismatched-end-tag", s"end tag </$name> does not match the current element"))
        if matches then closed.copy(elements = closed.elements.dropRight(1)) else closed
      else skipped.copy(diagnostics = skipped.diagnostics :+ eofDiagnostic(skipped, "uniml.xml.unexpected-eof", s"unterminated end tag </$name>"))

    def scanOpaque(
        st: XmlScanState,
        terminator: String,
        kind: String,
        channel: TokenChannel,
        contentRole: String,
        validate: String => Option[(String, String)],
    ): XmlScanState =
      val end = input.indexOf(terminator, st.index + 2)
      if end < 0 then emitRestError(st, s"uniml.${kind.replace('.', '-')}", s"unterminated $kind")
      else
        val lexeme = input.substring(st.index, end + terminator.length)
        val role = if st.elements.nonEmpty then contentRole else "document.misc"
        val instruction = validate(lexeme) match
          case Some(problem) => VmInstruction.Report(problem._1, problem._2)
          case None          => VmInstruction.Emit(Some(role))
        emitWhole(st, lexeme, kind, channel, instruction)

    def scanCData(st: XmlScanState): XmlScanState =
      scanOpaque(st, "]]>", "xml.cdata", TokenChannel.Syntax, "content.cdata", _ =>
        if st.elements.isEmpty then Some("uniml.xml.invalid-cdata" -> "CDATA is only allowed inside element content") else None)

    def scanReference(st: XmlScanState): XmlScanState =
      val end = input.indexOf(';', st.index + 1)
      if end < 0 then emitRestError(st, "uniml.xml.invalid-reference", "unterminated XML reference")
      else
        val lexeme = input.substring(st.index, end + 1)
        val syntaxValid = isValidReference(lexeme)
        val valid = syntaxValid && numericReferenceValue(lexeme).forall(isLegalXmlCodePoint)
        emitWhole(st, lexeme, "xml.reference", TokenChannel.Syntax,
          if valid then VmInstruction.Emit(Some(if st.elements.nonEmpty then "content.reference" else "document.reference"))
          else VmInstruction.Report("uniml.xml.invalid-reference", "invalid XML reference"))

    def scanText(st: XmlScanState): XmlScanState =
      val start = st.index
      def textEnd(i: Int): Int =
        if i < input.length && input.charAt(i) != '<' && input.charAt(i) != '&' then textEnd(i + 1) else i
      val stop = textEnd(start)
      val lexeme = input.substring(start, stop)
      val outside = st.elements.isEmpty
      val whitespaceOnly = lexeme.forall(isXmlWhitespace)
      val invalid = lexeme.contains("]]>")
      val tooLong = Unicode.codePointCount(lexeme) > limits.maxTextCodePoints
      val instruction =
        if tooLong then VmInstruction.Report("uniml.xml.limit.text", "XML text exceeds configured limit", Severity.Fatal)
        else if invalid then VmInstruction.Report("uniml.xml.invalid-character", "']]>' is forbidden in ordinary XML character data")
        else if outside && !whitespaceOnly then VmInstruction.Report("uniml.xml.text-outside-root", "character data is not allowed outside the root element")
        else VmInstruction.Emit(Some(if outside then "document.misc" else "content.text"))
      emitKnownRange(st.copy(index = stop), lexeme, if outside && whitespaceOnly then "xml.whitespace" else "xml.text",
        if outside && whitespaceOnly then TokenChannel.Trivia else TokenChannel.Syntax, instruction)

    // the FIRST illegal XML 1.0 character, reported once (the scan itself stays whole-source)
    def validateSourceCharacters(): Vector[Diagnostic] =
      def walk(cursor: Int): Vector[Diagnostic] =
        if cursor >= input.length then Vector.empty
        else
          val first = input.charAt(cursor)
          val paired = Unicode.isHighSurrogate(first) && cursor + 1 < input.length && Unicode.isLowSurrogate(input.charAt(cursor + 1))
          val codePoint =
            if paired then
              0x10000 + ((first.toInt - 0xD800) << 10) + (input.charAt(cursor + 1).toInt - 0xDC00)
            else first.toInt
          val width = if paired then 2 else 1
          val rawSurrogate = !paired && (Unicode.isHighSurrogate(first) || Unicode.isLowSurrogate(first))
          if !isLegalXmlCodePoint(codePoint) || rawSurrogate then
            Vector(Diagnostic(
              "uniml.xml.invalid-character",
              f"illegal XML 1.0 character U+$codePoint%04X",
              Severity.Error,
              None,
              Some(XmlDialect.id),
            ))
          else walk(cursor + width)
      walk(0)

    def drive(st: XmlScanState): XmlScanState =
      if st.index >= input.length then st
      else if input.startsWith("<?xml", st.index) then drive(scanDeclaration(st))
      else if input.startsWith("<!--", st.index) then drive(scanOpaque(st, "-->", "xml.comment", TokenChannel.Comment, "content.comment", validateComment))
      else if input.startsWith("<![CDATA[", st.index) then drive(scanCData(st))
      else if input.startsWith("<!DOCTYPE", st.index) then drive(scanDoctype(st))
      else if input.startsWith("<?", st.index) then drive(scanOpaque(st, "?>", "xml.pi", TokenChannel.Syntax, "content.pi", validatePi))
      else if input.startsWith("</", st.index) then drive(scanEndTag(st))
      else if input.charAt(st.index) == '<' then drive(scanStartTag(st))
      else if input.charAt(st.index) == '&' then drive(scanReference(st))
      else drive(scanText(st))

    val initial = XmlScanState(Vector.empty, validateSourceCharacters(), Vector.empty, 0, SourcePosition.Start, 0L, 0, seenDoctype = false, seenDeclaration = false)
    val scanned = drive(initial)
    val rootChecked =
      if scanned.rootCount == 0 then
        scanned.copy(diagnostics = scanned.diagnostics :+ eofDiagnostic(scanned, "uniml.xml.missing-root", "XML document has no root element"))
      else scanned
    val closedChecked =
      if rootChecked.elements.nonEmpty then
        rootChecked.copy(diagnostics = rootChecked.diagnostics :+ eofDiagnostic(rootChecked, "uniml.xml.unexpected-eof", s"unclosed XML element <${rootChecked.elements.last}>"))
      else rootChecked
    ProcessBatch(closedChecked.output, closedChecked.diagnostics)

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
    val afterOpen = matchLiteral(lexeme, 0, "<?xml")
    val afterWs = matchRequiredWhitespace(lexeme, afterOpen)
    val afterVersion = matchLiteral(lexeme, afterWs, "version")
    val afterEquals = matchEquals(lexeme, afterVersion)
    val afterValue = matchQuotedLiteral(lexeme, afterEquals, "1.0")
    val afterEncoding = matchOptionalEncoding(lexeme, afterValue)
    val afterStandalone = matchOptionalStandalone(lexeme, afterEncoding)
    val afterTail = skipXmlWhitespace(lexeme, afterStandalone)
    matchLiteral(lexeme, afterTail, "?>") == lexeme.length

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
    else if from < s.length && isXmlWhitespace(s.charAt(from)) then skipXmlWhitespace(s, from + 1)
    else from

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
        def nameEnd(cursor: Int): Int =
          if cursor < s.length && isEncodingNameChar(s.charAt(cursor)) then nameEnd(cursor + 1) else cursor
        val cursor = nameEnd(from + 2)
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
    def walk(cursor: Int, first: Boolean): Boolean =
      if cursor >= value.length then true
      else
        val high = value.charAt(cursor)
        val paired = Unicode.isHighSurrogate(high) && cursor + 1 < value.length && Unicode.isLowSurrogate(value.charAt(cursor + 1))
        val codePoint =
          if paired then 0x10000 + ((high.toInt - 0xD800) << 10) + value.charAt(cursor + 1).toInt - 0xDC00
          else high.toInt
        val width = if paired then 2 else 1
        val ok = if first then isNameStartCodePoint(codePoint) else isNameCharCodePoint(codePoint)
        ok && walk(cursor + width, false)
    value.nonEmpty && walk(0, true)

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
    digits.flatMap { pair =>
      val value = pair._1
      val radix = pair._2
      // -1 is the failure sentinel the caller's range check rejects, exactly as before
      def fold(cursor: Int, result: Long): Long =
        if cursor >= value.length then result
        else
          val char = value.charAt(cursor)
          val digit =
            if char >= '0' && char <= '9' then char - '0'
            else if radix == 16 && char >= 'a' && char <= 'f' then char - 'a' + 10
            else if radix == 16 && char >= 'A' && char <= 'F' then char - 'A' + 10
            else -1
          if digit < 0 then -1L
          else
            val next = result * radix + digit
            if next > 0x10FFFF then -1L else fold(cursor + 1, next)
      if value.isEmpty then Some(-1)
      else
        val result = fold(0, 0L)
        if result < 0 then Some(-1) else Some(result.toInt)
    }

  private def isLegalXmlCodePoint(value: Int): Boolean =
    value == 0x9 || value == 0xA || value == 0xD ||
      (value >= 0x20 && value <= 0xD7FF) ||
      (value >= 0xE000 && value <= 0xFFFD) ||
      (value >= 0x10000 && value <= 0x10FFFF)

  private def validAttributeReferences(value: String): Boolean =
    def walk(cursor: Int): Boolean =
      if cursor >= value.length then true
      else if value.charAt(cursor) == '&' then
        val end = value.indexOf(';', cursor + 1)
        if end < 0 then false
        else
          val reference = value.substring(cursor, end + 1)
          isValidReference(reference) && numericReferenceValue(reference).forall(isLegalXmlCodePoint) &&
            walk(end + 1)
      else walk(cursor + 1)
    walk(0)
