package scalascript.uniml.dialect.markdown

import scalascript.uniml.*
import scalascript.uniml.dialect.markdown.generated.MarkdownEntitiesGenerated

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
      val acc = result.roots.foldLeft(ProjectAcc(Vector.empty, Vector.empty)) { (acc, root) =>
        projectBlock(root, refs) match
          case Some(defn: MarkdownBlock.LinkDefinition) => acc.copy(references = acc.references :+ defn)
          case Some(block)                              => acc.copy(blocks = acc.blocks :+ block)
          case None                                     => acc
      }
      MarkdownProjectionResult(
        Some(MarkdownDocument(acc.blocks, acc.references)),
        result.diagnostics,
      )

  /** Root-level blocks split into document content and reference definitions. */
  private final case class ProjectAcc(
    blocks: Vector[MarkdownBlock],
    references: Vector[MarkdownBlock.LinkDefinition],
  )

  // ── reference definitions ────────────────────────────────────────────────

  private def collectDefinitions(roots: Vector[UniNode]): Map[String, MarkdownBlock.LinkDefinition] =
    def walk(map: Map[String, MarkdownBlock.LinkDefinition], node: UniNode): Map[String, MarkdownBlock.LinkDefinition] =
      node match
        case b @ UniNode.Branch(MdBranch.Definition, _, _, _) =>
          definitionOf(b) match
            case Some(defn) =>
              val key = MarkdownInlines.normalizeLabel(defn.label)
              // CommonMark: the FIRST definition of a label wins
              if map.contains(key) then map else map + (key -> defn)
            case None => map
        case UniNode.Branch(_, edges, _, _) => edges.foldLeft(map)((m, e) => walk(m, e.child))
        case _                              => map
    roots.foldLeft(Map.empty[String, MarkdownBlock.LinkDefinition])((m, root) => walk(m, root))

  /** The three parts of a definition, filled as its tokens appear (a later token
    * of the same kind overwrites — the fold keeps the foreach's last-wins). */
  private final case class DefnAcc(label: String, dest: String, title: Option[String])

  private def definitionOf(branch: UniNode.Branch): Option[MarkdownBlock.LinkDefinition] =
    val acc = branch.edges.foldLeft(DefnAcc("", "", None)) { (acc, edge) =>
      edge.child match
        case UniNode.Token(t) if t.kind == MdKind.ReferenceLabel =>
          acc.copy(label = t.lexeme.stripPrefix("[").stripSuffix("]"))
        case UniNode.Token(t) if t.kind == MdKind.Destination => acc.copy(dest = unwrapDestination(t.lexeme))
        case UniNode.Token(t) if t.kind == MdKind.Title       => acc.copy(title = Some(stripTitle(t.lexeme)))
        case _ => acc
    }
    val accLabel: String = acc.label
    if accLabel.isEmpty then None else Some(MarkdownBlock.LinkDefinition(acc.label, acc.dest, acc.title))

  // ── blocks ────────────────────────────────────────────────────────────

  private def projectBlock(node: UniNode, refs: Map[String, MarkdownBlock.LinkDefinition]): Option[MarkdownBlock] =
    node match
      case UniNode.Token(_) => None // root-level trivia
      case UniNode.Branch(kind, edges, _, _) => kind match
        case MdBranch.Paragraph =>
          Some(MarkdownBlock.Paragraph(trimBlockInlines(projectInlines(edges, refs))))
        case MdBranch.Heading =>
          val level = headingLevel(edges)
          val setext = edges.exists { case UniEdge(_, UniNode.Token(t)) => t.kind == MdKind.SetextUnderline; case _ => false }
          Some(MarkdownBlock.Heading(level, trimBlockInlines(projectInlines(edges, refs)), setext))
        case MdBranch.ThematicBreak => Some(MarkdownBlock.ThematicBreak)
        case MdBranch.Blockquote =>
          Some(MarkdownBlock.BlockQuote(edges.flatMap(e => projectBlock(e.child, refs))))
        case MdBranch.List =>
          val items = edges.collect {
            case UniEdge(_, UniNode.Branch(MdBranch.ListItem, itemEdges, _, _)) =>
              ListItem(itemEdges.flatMap(e => projectBlock(e.child, refs)), taskState(itemEdges))
          }
          Some(MarkdownBlock.ListBlock(listOrdered(edges), listStart(edges), tight = !listLoose(edges), items))
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

  /** CommonMark looseness: a list is loose if two of its items are separated by
    * a blank line, or an item directly contains two blocks separated by a blank
    * line. A blank line merely trailing the final item does not make it loose.
    * Blank lines surface in the CST as `markdown.blank` tokens inside items. */
  private def listLoose(edges: Vector[UniEdge]): Boolean =
    val items = edges.collect { case UniEdge(_, b @ UniNode.Branch(MdBranch.ListItem, _, _, _)) => b }
    items.iterator.zipWithIndex.exists { (item, idx) =>
      val itemEdges = item.edges
      val blankIdx = itemEdges.indexWhere {
        case UniEdge(_, UniNode.Token(t)) => t.kind == MdKind.Blank
        case _                            => false
      }
      if blankIdx < 0 then false
      else if idx < items.size - 1 then true // a blank in a non-final item separates items
      else itemEdges.drop(blankIdx + 1).exists { case UniEdge(_, _: UniNode.Branch) => true; case _ => false }
    }

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
    edges.collectFirst { case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.Info => decodeText(unescape(t.lexeme.trim)) }
      .filter(_.nonEmpty)

  private def codeLiteral(edges: Vector[UniEdge]): String =
    edges.collect {
      case UniEdge(_, UniNode.Token(t)) if t.kind == MdKind.CodeContent || (t.kind == MdKind.LineBreak && t.channel == TokenChannel.Embedded) =>
        t.lexeme
    }.mkString("")

  private def concatTokens(edges: Vector[UniEdge], kind: String): String =
    def walk(buf: Vector[String], node: UniNode): Vector[String] = node match
      case UniNode.Token(t) if t.kind == kind || (t.kind == MdKind.LineBreak && t.channel == TokenChannel.Embedded) => buf :+ t.lexeme
      case UniNode.Branch(_, es, _, _) => es.foldLeft(buf)((b, e) => walk(b, e.child))
      case _ => buf
    edges.foldLeft(Vector.empty[String])((b, e) => walk(b, e.child)).mkString("")

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
  /** CommonMark 4.8: "the paragraph's raw content is formed by concatenating the
    * lines and removing initial and final whitespace" — and 6.7, where the
    * spaces that MAKE a hard break are part of the break, not of the text before
    * it, while the next line's leading whitespace is dropped as well. Setext
    * heading content is trimmed by the same rule.
    *
    * All of it is trimming of the SEMANTIC view only: the CST keeps every one of
    * those characters, which is why this belongs here and not in the parser. */
  private def trimBlockInlines(is: Vector[MarkdownInline]): Vector[MarkdownInline] =
    // a break that ends the block is not a break at all — `foo  \n` is just `foo`
    val withoutTrailingBreak = is match
      case rest :+ MarkdownInline.SoftBreak => rest
      case rest :+ MarkdownInline.HardBreak => rest
      case _                                => is
    val n = withoutTrailingBreak.length
    withoutTrailingBreak.iterator.zipWithIndex.map { (inline, idx) =>
      inline match
        case MarkdownInline.Text(v) =>
          val afterBreak = idx > 0 && isBreak(withoutTrailingBreak(idx - 1))
          val beforeBreak = idx + 1 < n && isBreak(withoutTrailingBreak(idx + 1))
          val ledTrimmed = if idx == 0 || afterBreak then dropLeadingSpaces(v) else v
          val out = if idx == n - 1 || beforeBreak then dropTrailingSpaces(ledTrimmed) else ledTrimmed
          MarkdownInline.Text(out)
        case other => other
    }.toVector.filter {
      // a text run that was ENTIRELY the break's whitespace leaves nothing behind
      case MarkdownInline.Text("") => false
      case _                       => true
    }

  private def isBreak(inline: MarkdownInline): Boolean = inline match
    case MarkdownInline.SoftBreak | MarkdownInline.HardBreak => true
    case _                                                   => false

  private def dropLeadingSpaces(v: String): String =
    def start(i: Int): Int =
      if i < v.length && (v.charAt(i) == ' ' || v.charAt(i) == '\t') then start(i + 1) else i
    v.substring(start(0))

  private def dropTrailingSpaces(v: String): String =
    def end(i: Int): Int =
      if i > 0 && (v.charAt(i - 1) == ' ' || v.charAt(i - 1) == '\t') then end(i - 1) else i
    v.substring(0, end(v.length))

  private def projectInlines(edges: Vector[UniEdge], refs: Map[String, MarkdownBlock.LinkDefinition]): Vector[MarkdownInline] =
    def appendMerging(out: Vector[MarkdownInline], inline: MarkdownInline): Vector[MarkdownInline] =
      (out.lastOption, inline) match
        case (Some(MarkdownInline.Text(a)), MarkdownInline.Text(b)) =>
          out.dropRight(1) :+ MarkdownInline.Text(a + b)
        case _ => out :+ inline
    edges.foldLeft(Vector.empty[MarkdownInline]) { (out, edge) =>
      projectInline(edge, refs) match
        case Some(inline) => appendMerging(out, inline)
        case None         => out
    }

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
    case MdKind.Autolink      => val inner = t.lexeme.stripPrefix("<").stripSuffix(">"); Some(MarkdownInline.Autolink(autolinkDestination(inner), inner))
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
        // Shortcut `[foo]` and collapsed `[foo][]` carry no label of their own — the
        // lexeme is `]` or `][]`, so extractRefLabel yields "" and the LINK TEXT is
        // the label. Without the .filter this looked up the empty key and every
        // shortcut reference resolved to href="".
        val labelText =
          refLabel.map(extractRefLabel).filter(_.nonEmpty).getOrElse(rawLabel(edges))
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

  /** An autolink's TEXT and its destination differ for two of GFM's extended
    * forms: `www.x` links to `http://www.x` and a bare address to `mailto:`.
    * Derived here rather than carried on the token, so the token stream holds
    * nothing the source does not. */
  private def autolinkDestination(text: String): String =
    val lower = MdChars.asciiLower(text)
    if lower.startsWith("www.") then "http://" + text
    else if !lower.contains(":") && text.contains("@") then "mailto:" + text
    else text

  private def extractRefLabel(lex: String): String =
    // lexeme may be "][label]" or "]" for shortcut/collapsed forms. The opening
    // bracket must be found ESCAPE-AWARE: `[foo][ref\[]` has a `\[` inside the
    // label, and taking the last raw `[` sliced the label in half, so it matched
    // no definition and resolved to href="".
    def lastOpen(i: Int, open: Int): Int =
      if i >= lex.length then open
      else if lex.charAt(i) == '\\' then lastOpen(i + 2, open)
      else if lex.charAt(i) == '[' then lastOpen(i + 1, i)
      else lastOpen(i + 1, open)
    val open = lastOpen(0, -1)
    val close = lex.lastIndexOf(']')
    if open >= 0 && close > open then lex.substring(open + 1, close) else ""

  /** The label's RAW SOURCE, which is what CommonMark matches definitions on.
    * `[*foo* bar]` resolves against a definition spelled `[*foo* bar]`, not
    * against the rendered text `foo bar` — using the projected inlines here made
    * every shortcut reference whose label carries inline markup resolve to
    * href="", and the emphasis still rendered, so it looked like a link that had
    * merely lost its destination. */
  private def rawLabel(edges: Vector[UniEdge]): String =
    def walk(buf: Vector[String], node: UniNode): Vector[String] = node match
      case UniNode.Token(t)            => buf :+ t.lexeme
      case UniNode.Branch(_, es, _, _) => es.foldLeft(buf)((b, e) => walk(b, e.child))
    edges.filterNot(isLinkStructural).foldLeft(Vector.empty[String])((b, e) => walk(b, e.child)).mkString("")

  // ── decoding helpers ────────────────────────────────────────────────────

  private def unwrapDestination(lex: String): String = unwrapDestinationSlice(lex)

  /** Shared with the block scanner, which holds the raw `<...>` source slice and
    * must read it exactly as the projection does — one definition of what a
    * destination MEANS, used by both. */
  private[markdown] def unwrapDestinationSlice(lex: String): String =
    val s = if lex.startsWith("<") && lex.endsWith(">") then lex.substring(1, lex.length - 1) else lex
    decodeText(unescape(s))

  private def stripTitle(lex: String): String =
    val s =
      if lex.length >= 2 && ((lex.head == '"' && lex.last == '"') || (lex.head == '\'' && lex.last == '\'') || (lex.head == '(' && lex.last == ')')) then
        lex.substring(1, lex.length - 1)
      else lex
    decodeText(unescape(s))

  /** Entity decoding in the places CommonMark permits it and the token stream
    * does not already cover: DESTINATIONS, TITLES and fence INFO STRINGS. Inline
    * text goes through `markdown.entity` tokens instead, which is why this was
    * missed — `[foo](/f&ouml;&ouml;)` kept its entity while the same text in a
    * paragraph decoded. Code spans and raw HTML are deliberately NOT decoded. */
  private def decodeText(s: String): String =
    if !s.contains('&') then s
    else
      def walk(i: Int, buf: Vector[String]): Vector[String] =
        if i >= s.length then buf
        else if s.charAt(i) == '&' then
          val semi = s.indexOf(';', i + 1)
          val decoded = if semi < 0 then s else decodeEntity(s.substring(i, semi + 1))
          if semi >= 0 && decoded != s.substring(i, semi + 1) then walk(semi + 1, buf :+ decoded)
          else walk(i + 1, buf :+ "&")
        else walk(i + 1, buf :+ s.substring(i, i + 1))
      walk(0, Vector.empty).mkString("")

  private def unescape(s: String): String =
    if !s.contains('\\') then s
    else
      def walk(i: Int, buf: Vector[String]): Vector[String] =
        if i >= s.length then buf
        else if s.charAt(i) == '\\' && i + 1 < s.length && MdChars.isAsciiPunctuation(s.charAt(i + 1)) then
          walk(i + 2, buf :+ s.substring(i + 1, i + 2))
        else walk(i + 1, buf :+ s.substring(i, i + 1))
      walk(0, Vector.empty).mkString("")

  /** The WHATWG HTML5 named character references, generated from the pinned
    * snapshot in `uniml/corpus/markdown/whatwg-entities.json`. It replaced a
    * hand-typed table of roughly 250 names, which is why `&Dcaron;` and
    * `&HilbertSpace;` used to stay literal while `&copy;` decoded — the set was
    * a judgement call rather than the standard's.
    *
    * Only semicolon-terminated names are in it; CommonMark 6.2 recognises no
    * others, and an unknown name stays literal, which remains lossless. */
  private def namedEntities: Map[String, String] = MarkdownEntitiesGenerated.table

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
    else if cp <= 0xFFFF then cp.toChar.toString
    else
      val c = cp - 0x10000
      val hi = 0xD800 + (c >> 10)
      val lo = 0xDC00 + (c & 0x3FF)
      hi.toChar.toString + lo.toChar.toString
