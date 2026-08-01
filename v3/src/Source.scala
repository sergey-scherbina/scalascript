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
    if !lines.exists(isCodeFenceOpen) then text
    else
      var out: List[String] = Nil
      var inCode = false
      lines.foreach { l =>
        if inCode then
          if isFenceClose(l) then
            inCode = false
            out = "" :: out
          else out = l :: out
        else
          if isCodeFenceOpen(l) then inCode = true
          out = "" :: out
      }
      out.reverse.mkString("\n")

  private def trimmed(l: String): String = l.trim

  private def isCodeFenceOpen(l: String): Boolean =
    val t = trimmed(l)
    t == "```scalascript" || t == "```ssc"

  private def isFenceClose(l: String): Boolean = trimmed(l) == "```"
