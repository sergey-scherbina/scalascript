package ssc3

// Literate `.ssc`: a source file may be Markdown with the program in ```scalascript fences.
//
// The convention is the project's, not v3's — fences have been OPTIONAL since 2026-07-09: a bare
// `.ssc` is the program in its entirety, a fenced one is literate. 377 of the 383 conformance cases
// are literate, so a front that could not read them could not be measured against the corpus at
// all, which is invariant I-5's whole apparatus.
//
// Other fence languages (```sql, ```scala, ```yaml) appear in those files as DATA and prose. Taking
// only ```scalascript is what keeps a SQL example in a doc comment from being compiled as a program.

object Source:

  /** Non-code lines become EMPTY LINES rather than disappearing.
    *
    * That keeps every line number identical to the original file, so `foo.ssc:42:7` points at line
    * 42 of what the author is looking at. Dropping the lines instead would have been one character
    * shorter and would make every diagnostic in a literate file point at the wrong place — the kind
    * of defect that is discovered only by someone confused enough to count lines by hand. */
  def program(text: String): String =
    val lines = text.split("\n", -1).toList
    // A bare `.ssc` is code in its entirety, so its import LINKS are in the middle of the program
    // and have to go — blanked, not removed, so every line number still matches the file.
    if !lines.exists(isCodeFenceOpen) then lines.map(blankIfImport).mkString("\n")
    else
      var out: List[String] = Nil
      var inCode = false
      lines.foreach { l =>
        if inCode then
          if isFenceClose(l) then
            inCode = false
            out = "" :: out
          else out = blankIfImport(l) :: out
        else
          if isCodeFenceOpen(l) then inCode = true
          out = "" :: out
      }
      out.reverse.mkString("\n")

  /** The last CODE line of each fenced block, 1-based.
    *
    * A `.ssc` document's contract is that the last non-Unit expression OF EACH top-level block is
    * printed, in source order — not the program's final value. Getting that wrong makes a
    * single-block program look correct while silently dropping every earlier block's tail, which is
    * the defect `multiblock-auto-output` exists to catch. */
  def blockEnds(text: String): List[Int] =
    val lines = text.split("\n", -1).toList
    var out: List[Int] = Nil
    var inCode = false
    var lastCode = 0
    var i = 0
    lines.foreach { l =>
      i = i + 1
      if inCode then
        if isFenceClose(l) then
          inCode = false
          if lastCode > 0 then out = lastCode :: out
        else if trimmed(l).nonEmpty then lastCode = i
      else if isCodeFenceOpen(l) then
        inCode = true
        lastCode = 0
    }
    out.reverse

  /** An import link is a DECLARATION handled by `Loader`, not an expression. It is replaced by an
    * empty line rather than deleted for the same reason prose is: line numbers are what a
    * diagnostic points at. */
  private def blankIfImport(l: String): String =
    val t = l.trim
    val close = t.indexOf("](")
    if t.startsWith("[") && close > 0 && t.endsWith(")") && t.substring(close + 2, t.length - 1).trim.endsWith(".ssc")
    then "" else l

  private def trimmed(l: String): String = l.trim

  private def isCodeFenceOpen(l: String): Boolean =
    val t = trimmed(l)
    t == "```scalascript" || t == "```ssc"

  private def isFenceClose(l: String): Boolean = trimmed(l) == "```"
