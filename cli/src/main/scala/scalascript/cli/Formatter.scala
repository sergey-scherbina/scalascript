package scalascript.cli

/** Source-level formatter for `.ssc` files.
  *
  * Operates as a pure string transformation — it never touches the AST.
  * The formatter identifies structural regions (shebang, YAML front-matter,
  * headings, fenced code blocks, prose) and re-emits them with normalised
  * spacing.  Code block *contents* are preserved verbatim.
  */
object Formatter:

  /** Return the canonical form of `source`. */
  def format(source: String): String =
    val lines = source.split("\n", -1).toList
    formatLines(lines)

  /** Return true if `source` is not already in canonical form. */
  def needsFormatting(source: String): Boolean = format(source) != source

  // ── Key ordering for YAML front-matter ──────────────────────────────────

  private val knownKeyOrder: List[String] = List(
    "name", "version", "description", "main",
    "package", "exports", "dependencies", "routes"
  )

  private def fmKeyRank(key: String): (Int, String) =
    val idx = knownKeyOrder.indexOf(key)
    if idx >= 0 then (idx, key)
    else (knownKeyOrder.size, key)

  // ── Line classification ──────────────────────────────────────────────────

  private val fenceRe   = "^(```+|~~~+)(.*)".r
  private val headingRe = "^(#{1,6})([^ #\n].*)".r   // ##Title (no space after #s)
  private val yamlKeyRe = "^([A-Za-z_][A-Za-z0-9_-]*)\\s*:(.*)".r

  /** True if the trimmed line is a fence open or close marker. */
  private def isFenceMarker(trimmed: String): Boolean = fenceRe.matches(trimmed)

  /** Extract the actual fence characters (``` or ~~~) from a trimmed fence line. */
  private def fenceChars(trimmed: String): String =
    trimmed.takeWhile(c => c == '`' || c == '~')

  // ── YAML front-matter normalisation ─────────────────────────────────────

  /** Parse front-matter lines (between the two `---` markers).
    * Input: lines AFTER the opening `---`.
    * Returns (frontMatterLines, linesAfterClosingDash).
    */
  private def parseFrontMatter(lines: List[String]): (List[String], List[String]) =
    val end = lines.indexWhere(_.trim == "---")
    if end < 0 then (lines, Nil)
    else (lines.take(end), lines.drop(end + 1))

  /** Re-emit front-matter with normalised key order.
    * Top-level keys are sorted; continuation lines (indented) stay attached.
    */
  private def normaliseFrontMatter(fmLines: List[String]): List[String] =
    case class Entry(key: String, lines: List[String])

    val entries = scala.collection.mutable.ArrayBuffer.empty[Entry]
    var current = Option.empty[Entry]

    for line <- fmLines do
      val stripped = line.stripTrailing()
      stripped match
        case yamlKeyRe(k, _) if !stripped.startsWith(" ") && !stripped.startsWith("\t") =>
          current.foreach(e => entries += e)
          current = Some(Entry(k, List(stripped)))
        case _ =>
          current = current.map(e => e.copy(lines = e.lines :+ stripped))

    current.foreach(e => entries += e)

    val sorted = entries.sortBy(e => fmKeyRank(e.key))
    sorted.toList.flatMap(_.lines)

  // ── Heading normalisation ────────────────────────────────────────────────

  /** Ensure heading has exactly one space after the `#` characters. */
  private def normaliseHeading(line: String): String =
    line match
      case headingRe(hashes, rest) =>
        s"$hashes ${rest.strip()}"
      case _ => line

  private def isHeading(line: String): Boolean =
    line.nonEmpty && line.charAt(0) == '#'

  // ── Closing fence detection ──────────────────────────────────────────────

  /** Return true if `trimmedLine` is a valid closing fence for `openMarker`.
    * CommonMark: closing fence must use the same character and be at least
    * as long as the opening fence, with no other content.
    */
  private def isClosingFence(trimmedLine: String, openMarker: String): Boolean =
    trimmedLine.nonEmpty &&
      trimmedLine.forall(_ == openMarker.head) &&
      trimmedLine.length >= openMarker.length

  // ── Main formatting pass ─────────────────────────────────────────────────

  private def formatLines(rawLines: List[String]): String =
    // Strip trailing whitespace and \r from every line upfront
    val lines = rawLines.map(_.stripTrailing().replace("\r", ""))

    val out = scala.collection.mutable.ArrayBuffer.empty[String]

    var i = 0
    val n = lines.length

    // 1. Optional shebang
    if n > 0 && lines(0).startsWith("#!") then
      out += lines(0)
      i = 1

    // 2. Optional YAML front-matter
    if i < n && lines(i).trim == "---" then
      out += "---"
      i += 1
      val remaining = lines.drop(i)
      val (fmRaw, rest) = parseFrontMatter(remaining)
      val fmNorm = normaliseFrontMatter(fmRaw)
      out ++= fmNorm
      out += "---"
      // Advance i past the front-matter and closing ---
      i = n - rest.length
      // Exactly one blank line after closing ---
      out += ""

    // 3. Markdown body
    var inFence       = false
    var fenceMarker   = "```"  // the opening fence characters
    var justClosedFence = false  // true on the line immediately after a closing fence

    val bodyLines = lines.drop(i)

    for line <- bodyLines do
      if inFence then
        val trimmed = line.trim
        if isClosingFence(trimmed, fenceMarker) then
          out += line
          inFence = false
          justClosedFence = true
        else
          out += line
      else
        val trimmed = line.trim
        if isFenceMarker(trimmed) then
          // Ensure exactly one blank line before the fence
          if out.nonEmpty then
            while out.nonEmpty && out.last.isBlank do
              out.remove(out.length - 1)
            if out.nonEmpty then out += ""
          out += line
          inFence = true
          justClosedFence = false
          fenceMarker = fenceChars(trimmed)
        else if line.isBlank then
          // Collapse consecutive blank lines to one
          if out.nonEmpty && !out.last.isBlank then
            out += ""
          justClosedFence = false
        else if isHeading(line) then
          val normLine = normaliseHeading(line)
          // Ensure exactly one blank line before heading (unless at start)
          if out.nonEmpty then
            while out.nonEmpty && out.last.isBlank do
              out.remove(out.length - 1)
            if out.nonEmpty then out += ""
          out += normLine
          justClosedFence = false
        else
          // Prose line: if we just closed a fence and there's no blank yet, add one
          if justClosedFence && out.nonEmpty && !out.last.isBlank then
            out += ""
          out += line
          justClosedFence = false

    // 4. Strip trailing blank lines; add exactly one final newline
    while out.nonEmpty && out.last.isBlank do
      out.remove(out.length - 1)

    if out.isEmpty then "\n"
    else out.mkString("\n") + "\n"
