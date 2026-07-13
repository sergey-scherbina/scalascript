package scalascript.uniml.dialect.markdown

import scalascript.uniml.*
import scala.collection.mutable

/** Projects a parsed Markdown CST into the semantic [[MarkdownDocument]]. The
  * CST remains canonical; this view normalizes escapes/entities, code-span
  * whitespace, and resolves reference links from the collected definitions. Raw
  * HTML, destinations and `${expr}` text stay inert (never rendered/fetched). */
object MarkdownProjection:

  def project(result: ParseResult, profile: MarkdownProfile): MarkdownProjectionResult =
    val _ = profile // projection is profile-agnostic: GFM/ScalaScript nodes only exist if parsed
    if result.diagnostics.exists(d => d.severity == Severity.Fatal) then
      MarkdownProjectionResult(None, result.diagnostics)
    else
      val refs = collectDefinitions(result.roots)
      val blocks = Vector.newBuilder[MarkdownBlock]
      val references = Vector.newBuilder[MarkdownBlock.LinkDefinition]
      result.roots.foreach { root =>
        projectBlock(root, refs) match
          case Some(defn: MarkdownBlock.LinkDefinition) => references += defn
          case Some(block)                              => blocks += block
          case None                                     => ()
      }
      MarkdownProjectionResult(
        Some(MarkdownDocument(blocks.result(), references.result())),
        result.diagnostics,
      )

  // ── reference definitions ────────────────────────────────────────────────

  private def collectDefinitions(roots: Vector[UniNode]): Map[String, MarkdownBlock.LinkDefinition] =
    val map = mutable.LinkedHashMap.empty[String, MarkdownBlock.LinkDefinition]
    def walk(node: UniNode): Unit = node match
      case b @ UniNode.Branch(MdBranch.Definition, _, _, _) =>
        definitionOf(b).foreach { defn =>
          val key = MarkdownInlines.normalizeLabel(defn.label)
          if !map.contains(key) then map.put(key, defn)
        }
      case UniNode.Branch(_, edges, _, _) => edges.foreach(e => walk(e.child))
      case _                              => ()
    roots.foreach(walk)
    map.toMap

  private def definitionOf(branch: UniNode.Branch): Option[MarkdownBlock.LinkDefinition] =
    var label = ""
    var dest = ""
    var title: Option[String] = None
    branch.edges.foreach { edge =>
      edge.child match
        case UniNode.Token(t) if t.kind == MdKind.ReferenceLabel =>
          label = t.lexeme.stripPrefix("[").stripSuffix("]")
        case UniNode.Token(t) if t.kind == MdKind.Destination => dest = unwrapDestination(t.lexeme)
        case UniNode.Token(t) if t.kind == MdKind.Title       => title = Some(stripTitle(t.lexeme))
        case _ => ()
    }
    if label.isEmpty then None else Some(MarkdownBlock.LinkDefinition(label, dest, title))

  // ── blocks ────────────────────────────────────────────────────────────

  private def projectBlock(node: UniNode, refs: Map[String, MarkdownBlock.LinkDefinition]): Option[MarkdownBlock] =
    node match
      case UniNode.Token(_) => None // root-level trivia
      case UniNode.Branch(kind, edges, _, _) => kind match
        case MdBranch.Paragraph =>
          Some(MarkdownBlock.Paragraph(trimTrailingBreak(projectInlines(edges, refs))))
        case MdBranch.Heading =>
          val level = headingLevel(edges)
          val setext = edges.exists { case UniEdge(_, UniNode.Token(t)) => t.kind == MdKind.SetextUnderline; case _ => false }
          Some(MarkdownBlock.Heading(level, trimTrailingBreak(projectInlines(edges, refs)), setext))
        case MdBranch.ThematicBreak => Some(MarkdownBlock.ThematicBreak)
        case MdBranch.Blockquote =>
          Some(MarkdownBlock.BlockQuote(edges.flatMap(e => projectBlock(e.child, refs))))
        case MdBranch.List =>
          val items = edges.collect {
            case UniEdge(_, UniNode.Branch(MdBranch.ListItem, itemEdges, _, _)) =>
              ListItem(itemEdges.flatMap(e => projectBlock(e.child, refs)), taskState(itemEdges))
          }
          Some(MarkdownBlock.ListBlock(listOrdered(edges), listStart(edges), tight = true, items))
        case MdBranch.CodeBlock =>
          Some(MarkdownBlock.CodeBlock(codeInfo(edges), codeLiteral(edges), fenced = hasFence(edges)))
        case MdBranch.FrontMatter =>
          Some(MarkdownBlock.CodeBlock(Some("yaml"), codeLiteral(edges), fenced = true))
        case MdBranch.HtmlBlock =>
          Some(MarkdownBlock.HtmlBlock(concatTokens(edges, MdKind.Html)))
        case MdBranch.Table => Some(projectTable(edges))
        case MdBranch.Definition => definitionOf(node.asInstanceOf[UniNode.Branch])
        case _ => None

  private def headingLevel(edges: Vector[UniEdge]): Int =
    edges.collectFirst { case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.AtxMarker => t.lexeme.length }
      .orElse(edges.collectFirst {
        case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.SetextUnderline =>
          if t.lexeme.trim.startsWith("=") then 1 else 2
      })
      .getOrElse(1)

  private def listOrdered(edges: Vector[UniEdge]): Boolean =
    firstMarker(edges).exists(m => m.nonEmpty && MdChars.isAsciiDigit(m.charAt(0)))

  private def listStart(edges: Vector[UniEdge]): Option[Long] =
    firstMarker(edges).flatMap { m =>
      val digits = m.takeWhile(MdChars.isAsciiDigit)
      if digits.isEmpty then None else digits.toLongOption
    }

  private def firstMarker(edges: Vector[UniEdge]): Option[String] =
    edges.collectFirst {
      case UniEdge(_, UniNode.Branch(MdBranch.ListItem, itemEdges, _, _)) =>
        itemEdges.collectFirst { case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.ListMarker => t.lexeme }
    }.flatten

  private def taskState(edges: Vector[UniEdge]): Option[Boolean] =
    edges.collectFirst {
      case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.TaskMarker =>
        t.lexeme.contains('x') || t.lexeme.contains('X')
    }

  private def hasFence(edges: Vector[UniEdge]): Boolean =
    edges.exists { case UniEdge(_, UniNode.Token(t)) => t.kind == MdKind.FenceOpen || t.kind == MdKind.FenceClose; case _ => false }

  private def codeInfo(edges: Vector[UniEdge]): Option[String] =
    edges.collectFirst { case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.Info => t.lexeme.trim }
      .filter(_.nonEmpty)

  private def codeLiteral(edges: Vector[UniEdge]): String =
    val sb = StringBuilder()
    edges.foreach {
      case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.CodeContent || (t.kind == MdKind.LineBreak && t.channel == TokenChannel.Embedded) =>
        sb.append(t.lexeme)
      case _ => ()
    }
    sb.result()

  private def concatTokens(edges: Vector[UniEdge], kind: String): String =
    val sb = StringBuilder()
    def walk(node: UniNode): Unit = node match
      case UniNode.Token(t) if t.kind == kind || (t.kind == MdKind.LineBreak && t.channel == TokenChannel.Embedded) => sb.append(t.lexeme)
      case UniNode.Branch(_, es, _, _) => es.foreach(e => walk(e.child))
      case _ => ()
    edges.foreach(e => walk(e.child))
    sb.result()

  private def projectTable(edges: Vector[UniEdge]): MarkdownBlock =
    val rows = edges.collect { case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.TableRow => t.lexeme }
    val delim = edges.collectFirst { case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.TableDelim => t.lexeme }
    val header = rows.headOption.map(splitCells).getOrElse(Vector.empty)
    val body = rows.drop(1).map(splitCells)
    val alignments = delim.map(parseAlignments).getOrElse(Vector.empty)
    MarkdownBlock.Table(header, alignments, body)

  private def splitCells(row: String): Vector[TableCell] =
    val trimmed = row.trim.stripPrefix("|").stripSuffix("|")
    trimmed.split("\\|", -1).toVector.map(cell => TableCell(inlineText(cell.trim)))

  private def parseAlignments(delim: String): Vector[ColumnAlignment] =
    val trimmed = delim.trim.stripPrefix("|").stripSuffix("|")
    trimmed.split("\\|", -1).toVector.map { spec =>
      val s = spec.trim
      val left = s.startsWith(":")
      val right = s.endsWith(":")
      if left && right then ColumnAlignment.Center
      else if right then ColumnAlignment.Right
      else if left then ColumnAlignment.Left
      else ColumnAlignment.Default
    }

  private def inlineText(text: String): Vector[MarkdownInline] =
    if text.isEmpty then Vector.empty else Vector(MarkdownInline.Text(text))

  // ── inlines ────────────────────────────────────────────────────────────

  /** Drops the block-terminating soft break a paragraph/heading picks up from its
    * final line ending (kept in the CST, not part of the semantic content). */
  private def trimTrailingBreak(is: Vector[MarkdownInline]): Vector[MarkdownInline] =
    is match
      case rest :+ MarkdownInline.SoftBreak => rest
      case _                                => is

  private def projectInlines(edges: Vector[UniEdge], refs: Map[String, MarkdownBlock.LinkDefinition]): Vector[MarkdownInline] =
    val out = mutable.ArrayBuffer.empty[MarkdownInline]
    edges.foreach { edge => projectInline(edge, refs).foreach(appendMerging(out, _)) }
    out.toVector

  private def appendMerging(out: mutable.ArrayBuffer[MarkdownInline], inline: MarkdownInline): Unit =
    (out.lastOption, inline) match
      case (Some(MarkdownInline.Text(a)), MarkdownInline.Text(b)) =>
        out(out.size - 1) = MarkdownInline.Text(a + b)
      case _ => out += inline

  private def projectInline(edge: UniEdge, refs: Map[String, MarkdownBlock.LinkDefinition]): Option[MarkdownInline] =
    edge.child match
      case UniNode.Token(t) => projectInlineToken(t)
      case UniNode.Branch(kind, edges, _, _) => kind match
        case MdBranch.Emphasis      => Some(MarkdownInline.Emphasis(projectInlines(edges.filterNot(isDelimiterEdge), refs)))
        case MdBranch.Strong        => Some(MarkdownInline.Strong(projectInlines(edges.filterNot(isDelimiterEdge), refs)))
        case MdBranch.Strikethrough => Some(MarkdownInline.Strikethrough(projectInlines(edges.filterNot(isDelimiterEdge), refs)))
        case MdBranch.CodeSpan      => Some(MarkdownInline.Code(codeSpanValue(edges)))
        case MdBranch.Link          => Some(projectLink(edges, refs, image = false))
        case MdBranch.Image         => Some(projectLink(edges, refs, image = true))
        case MdBranch.Expression    => Some(MarkdownInline.Expression(expressionSource(edges)))
        case _                      => None

  private def projectInlineToken(t: SourceToken): Option[MarkdownInline] = t.kind match
    case MdKind.Text          => Some(MarkdownInline.Text(t.lexeme))
    case MdKind.Escape        => Some(MarkdownInline.Text(t.lexeme.substring(1)))
    case MdKind.Entity        => Some(MarkdownInline.Text(decodeEntity(t.lexeme)))
    case MdKind.SoftBreak     => Some(MarkdownInline.SoftBreak)
    case MdKind.HardBreak     => Some(MarkdownInline.HardBreak)
    case MdKind.Autolink      => val inner = t.lexeme.stripPrefix("<").stripSuffix(">"); Some(MarkdownInline.Autolink(inner, inner))
    case MdKind.Html          => Some(MarkdownInline.RawHtml(t.lexeme))
    case MdKind.DelimiterRun  => Some(MarkdownInline.Text(t.lexeme)) // unmatched literal delimiters
    case _                    => None

  private def codeSpanValue(edges: Vector[UniEdge]): String =
    val raw = edges.collectFirst { case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.CodeContent => t.lexeme }.getOrElse("")
    val spaced = raw.map(c => if c == '\n' || c == '\r' then ' ' else c)
    if spaced.length >= 2 && spaced.head == ' ' && spaced.last == ' ' && spaced.exists(_ != ' ') then
      spaced.substring(1, spaced.length - 1)
    else spaced

  private def expressionSource(edges: Vector[UniEdge]): String =
    edges.collectFirst { case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.ExpressionContent => t.lexeme }.getOrElse("")

  private def projectLink(edges: Vector[UniEdge], refs: Map[String, MarkdownBlock.LinkDefinition], image: Boolean): MarkdownInline =
    val label = projectInlines(edges.filterNot(isLinkStructural), refs)
    val destTok = edges.collectFirst { case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.Destination => unwrapDestination(t.lexeme) }
    val titleTok = edges.collectFirst { case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.Title => stripTitle(t.lexeme) }
    val refLabel = edges.collectFirst { case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.ReferenceLabel => t.lexeme }
    val (dest, title) = destTok match
      case Some(d) => (d, titleTok)
      case None =>
        val labelText = refLabel.map(extractRefLabel).getOrElse(plainText(label))
        refs.get(MarkdownInlines.normalizeLabel(labelText)) match
          case Some(defn) => (defn.destination, defn.title)
          case None       => ("", None)
    if image then MarkdownInline.Image(label, dest, title)
    else MarkdownInline.Link(label, dest, title)

  private def isDelimiterEdge(edge: UniEdge): Boolean = edge.child match
    case UniNode.Token(t) => t.kind == MdKind.DelimiterRun || t.kind == MdKind.StrikethroughRun
    case _                => false

  private def isLinkStructural(edge: UniEdge): Boolean = edge.child match
    case UniNode.Token(t) => t.kind == MdKind.LinkOpen || t.kind == MdKind.LinkClose ||
      t.kind == MdKind.DestOpen || t.kind == MdKind.Destination || t.kind == MdKind.Title ||
      t.kind == MdKind.DestClose || t.kind == MdKind.ReferenceLabel
    case _ => false

  private def extractRefLabel(lex: String): String =
    // lexeme may be "][label]" or "]" for shortcut/collapsed forms
    val open = lex.lastIndexOf('[')
    val close = lex.lastIndexOf(']')
    if open >= 0 && close > open then lex.substring(open + 1, close) else ""

  private def plainText(inlines: Vector[MarkdownInline]): String =
    val sb = StringBuilder()
    def walk(i: MarkdownInline): Unit = i match
      case MarkdownInline.Text(v)            => sb.append(v)
      case MarkdownInline.Code(v)            => sb.append(v)
      case MarkdownInline.Emphasis(cs)       => cs.foreach(walk)
      case MarkdownInline.Strong(cs)         => cs.foreach(walk)
      case MarkdownInline.Strikethrough(cs)  => cs.foreach(walk)
      case _                                 => ()
    inlines.foreach(walk)
    sb.result()

  // ── decoding helpers ────────────────────────────────────────────────────

  private def unwrapDestination(lex: String): String =
    val s = if lex.startsWith("<") && lex.endsWith(">") then lex.substring(1, lex.length - 1) else lex
    unescape(s)

  private def stripTitle(lex: String): String =
    val s =
      if lex.length >= 2 && ((lex.head == '"' && lex.last == '"') || (lex.head == '\'' && lex.last == '\'') || (lex.head == '(' && lex.last == ')')) then
        lex.substring(1, lex.length - 1)
      else lex
    unescape(s)

  private def unescape(s: String): String =
    if !s.contains('\\') then s
    else
      val sb = StringBuilder()
      var i = 0
      while i < s.length do
        if s.charAt(i) == '\\' && i + 1 < s.length && MdChars.isAsciiPunctuation(s.charAt(i + 1)) then
          sb.append(s.charAt(i + 1)); i += 2
        else { sb.append(s.charAt(i)); i += 1 }
      sb.result()

  // The 96 Latin-1 Supplement entity names in U+00A0..U+00FF order — a
  // contiguous block, so we generate their code points rather than hand-typing
  // them (removes transcription risk for the largest group).
  private val latin1Names: Vector[String] = Vector(
    "nbsp", "iexcl", "cent", "pound", "curren", "yen", "brvbar", "sect", "uml", "copy", "ordf", "laquo", "not", "shy", "reg", "macr",
    "deg", "plusmn", "sup2", "sup3", "acute", "micro", "para", "middot", "cedil", "sup1", "ordm", "raquo", "frac14", "frac12", "frac34", "iquest",
    "Agrave", "Aacute", "Acirc", "Atilde", "Auml", "Aring", "AElig", "Ccedil", "Egrave", "Eacute", "Ecirc", "Euml", "Igrave", "Iacute", "Icirc", "Iuml",
    "ETH", "Ntilde", "Ograve", "Oacute", "Ocirc", "Otilde", "Ouml", "times", "Oslash", "Ugrave", "Uacute", "Ucirc", "Uuml", "Yacute", "THORN", "szlig",
    "agrave", "aacute", "acirc", "atilde", "auml", "aring", "aelig", "ccedil", "egrave", "eacute", "ecirc", "euml", "igrave", "iacute", "icirc", "iuml",
    "eth", "ntilde", "ograve", "oacute", "ocirc", "otilde", "ouml", "divide", "oslash", "ugrave", "uacute", "ucirc", "uuml", "yacute", "thorn", "yuml",
  )

  /** The common HTML4 / XHTML named character references (numeric references are
    * decoded separately; unknown names stay literal, which remains lossless). */
  private val namedEntities: Map[String, String] =
    val latin1 = latin1Names.iterator.zipWithIndex.map((n, i) => n -> (0xA0 + i).toChar.toString).toMap
    latin1 ++ Map(
      "amp" -> "&", "lt" -> "<", "gt" -> ">", "quot" -> "\"", "apos" -> "'",
      // Latin Extended-A and spacing-modifier letters
      "OElig" -> "Œ", "oelig" -> "œ", "Scaron" -> "Š", "scaron" -> "š",
      "Yuml" -> "Ÿ", "fnof" -> "ƒ", "circ" -> "ˆ", "tilde" -> "˜",
      // Greek
      "Alpha" -> "Α", "Beta" -> "Β", "Gamma" -> "Γ", "Delta" -> "Δ", "Epsilon" -> "Ε", "Zeta" -> "Ζ",
      "Eta" -> "Η", "Theta" -> "Θ", "Iota" -> "Ι", "Kappa" -> "Κ", "Lambda" -> "Λ", "Mu" -> "Μ",
      "Nu" -> "Ν", "Xi" -> "Ξ", "Omicron" -> "Ο", "Pi" -> "Π", "Rho" -> "Ρ", "Sigma" -> "Σ",
      "Tau" -> "Τ", "Upsilon" -> "Υ", "Phi" -> "Φ", "Chi" -> "Χ", "Psi" -> "Ψ", "Omega" -> "Ω",
      "alpha" -> "α", "beta" -> "β", "gamma" -> "γ", "delta" -> "δ", "epsilon" -> "ε", "zeta" -> "ζ",
      "eta" -> "η", "theta" -> "θ", "iota" -> "ι", "kappa" -> "κ", "lambda" -> "λ", "mu" -> "μ",
      "nu" -> "ν", "xi" -> "ξ", "omicron" -> "ο", "pi" -> "π", "rho" -> "ρ", "sigmaf" -> "ς",
      "sigma" -> "σ", "tau" -> "τ", "upsilon" -> "υ", "phi" -> "φ", "chi" -> "χ", "psi" -> "ψ",
      "omega" -> "ω", "thetasym" -> "ϑ", "upsih" -> "ϒ", "piv" -> "ϖ",
      // General punctuation
      "ensp" -> " ", "emsp" -> " ", "thinsp" -> " ", "zwnj" -> "‌", "zwj" -> "‍", "lrm" -> "‎", "rlm" -> "‏",
      "ndash" -> "–", "mdash" -> "—", "lsquo" -> "‘", "rsquo" -> "’", "sbquo" -> "‚",
      "ldquo" -> "“", "rdquo" -> "”", "bdquo" -> "„", "dagger" -> "†", "Dagger" -> "‡",
      "bull" -> "•", "hellip" -> "…", "permil" -> "‰", "prime" -> "′", "Prime" -> "″",
      "lsaquo" -> "‹", "rsaquo" -> "›", "oline" -> "‾", "frasl" -> "⁄", "euro" -> "€",
      // Letterlike symbols and arrows
      "weierp" -> "℘", "image" -> "ℑ", "real" -> "ℜ", "trade" -> "™", "alefsym" -> "ℵ",
      "larr" -> "←", "uarr" -> "↑", "rarr" -> "→", "darr" -> "↓", "harr" -> "↔", "crarr" -> "↵",
      "lArr" -> "⇐", "uArr" -> "⇑", "rArr" -> "⇒", "dArr" -> "⇓", "hArr" -> "⇔",
      // Mathematical operators
      "forall" -> "∀", "part" -> "∂", "exist" -> "∃", "empty" -> "∅", "nabla" -> "∇",
      "isin" -> "∈", "notin" -> "∉", "ni" -> "∋", "prod" -> "∏", "sum" -> "∑",
      "minus" -> "−", "lowast" -> "∗", "radic" -> "√", "prop" -> "∝", "infin" -> "∞",
      "ang" -> "∠", "and" -> "∧", "or" -> "∨", "cap" -> "∩", "cup" -> "∪", "int" -> "∫",
      "there4" -> "∴", "sim" -> "∼", "cong" -> "≅", "asymp" -> "≈", "ne" -> "≠",
      "equiv" -> "≡", "le" -> "≤", "ge" -> "≥", "sub" -> "⊂", "sup" -> "⊃", "nsub" -> "⊄",
      "sube" -> "⊆", "supe" -> "⊇", "oplus" -> "⊕", "otimes" -> "⊗", "perp" -> "⊥", "sdot" -> "⋅",
      // Technical, geometric shapes and suits
      "lceil" -> "⌈", "rceil" -> "⌉", "lfloor" -> "⌊", "rfloor" -> "⌋", "lang" -> "〈", "rang" -> "〉",
      "loz" -> "◊", "spades" -> "♠", "clubs" -> "♣", "hearts" -> "♥", "diams" -> "♦",
    )

  private def decodeEntity(lex: String): String =
    if !lex.startsWith("&") || !lex.endsWith(";") then lex
    else
      val body = lex.substring(1, lex.length - 1)
      if body.startsWith("#x") || body.startsWith("#X") then
        try codePointToString(Integer.parseInt(body.substring(2), 16)) catch case _: Throwable => lex
      else if body.startsWith("#") then
        try codePointToString(Integer.parseInt(body.substring(1))) catch case _: Throwable => lex
      else namedEntities.getOrElse(body, lex)

  private def codePointToString(cp: Int): String =
    if cp <= 0 || cp > 0x10FFFF then "�"
    else new String(Character.toChars(cp))
