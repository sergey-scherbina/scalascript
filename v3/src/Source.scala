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
    // ANY FENCE, not any CODE fence. The rule is "a document with fences is fenced, and only its
    // code blocks are code" — asking `isCodeFenceOpen` here instead meant a file whose ONLY fence
    // is ```` ```text ```` counted as fenceless, so its markdown headings went to the parser and it
    // died on `#`. `std/index.ssc:70` is that file: an aggregator whose whole content is prose and
    // markdown-link imports, readable by uniml and not by this front, and the last of the four
    // one-sided files that SSC3-13 opened.
    //
    // Measured before the rule was changed rather than after: exactly TWO files in the tree have a
    // fence and no code fence — `std/index.ssc` and `std/graphql.ssc` — and BOTH are refused today,
    // so no working file can regress through this.
    if !lines.exists(l => trimmed(l).startsWith("```")) then lines.map(blankIfImport).mkString("\n")
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

  /** An import is a DECLARATION handled by `Loader`, not an expression. It is replaced by an
    * empty line rather than deleted for the same reason prose is: line numbers are what a
    * diagnostic points at.
    *
    * TWO SPELLINGS, one meaning. `[name](std/x.ssc)` is v3's own, and `import std.x.name` is the
    * one people write out of habit — `Loader.scalaImportTarget` maps the second onto the first, so
    * blanking them here is the same act for the same reason. */
  private def blankIfImport(l: String): String =
    val t = l.trim
    val close = t.indexOf("](")
    if t.startsWith("[") && close > 0 && t.endsWith(")") && t.substring(close + 2, t.length - 1).trim.endsWith(".ssc")
    then ""
    else if scalaImportPath(l).isDefined then ""
    else l

  /** The dotted path of a Scala-style `import` line, when the line is EXACTLY that and nothing else.
    *
    * STRICT ON PURPOSE, and the strictness was measured rather than guessed. Ten lines in the corpus
    * and the standard library begin with the word `import`; three of them are prose —
    * `` import ("No method 'empty' …") ``, `import resolves; the call always takes …` — and reading
    * those as imports is the same false positive the link scanner already learned about one comment
    * up. Requiring the whole line to be a dotted path of identifiers (optionally ending in `.*`, or
    * in a selector list that `selectorsToStar` has already turned into one) rejects all three, and
    * the caller only ever asks INSIDE a code fence, which rejects the four that sit in
    * ```` ```text ```` documentation blocks.
    *
    * THE STRICTNESS SURVIVED THE WIDENING, and that was probed rather than reasoned about: `import
    * a`, `import std.x.{y` with the brace unclosed, `import std.x as m` and a prose line beginning
    * `import (` are all still refused after selector lists were accepted.
    *
    * `import a` with no dot returns None deliberately: there is no member to drop, so no module can
    * be derived from it, and the parser refuses it BY NAME rather than this silently ignoring it. */
  def scalaImportPath(l: String): Option[String] =
    val t = l.trim
    if !t.startsWith("import") then None
    else
      val rest = t.substring(6)
      if rest.isEmpty || !(rest.charAt(0) == ' ' || rest.charAt(0) == '\t') then None
      else
        val p = selectorsToStar(rest.trim)
        val segs = p.split("\\.", -1).toList
        val ok = segs.length >= 2 && segs.zipWithIndex.forall { (s, i) =>
          if s == "*" then i == segs.length - 1 else isIdent(s)
        }
        if ok then Some(p) else None

  /** `a.b.{X, Y}` -> `a.b.*`. A SELECTOR LIST IS THE WHOLE MODULE, so it is normalised to the
    * spelling that already means that and everything downstream is untouched.
    *
    * The refusal this replaces argued the point itself — "an import brings the WHOLE module either
    * way" — and it was right about the semantics and wrong to refuse over them: `import a.b.{X, Y}`
    * is what people write, three modules under `std/mapreduce/` write it, and the owner's decision
    * of 2026-08-14 is that it works.
    *
    * NORMALISING RATHER THAN PARSING is what keeps this from becoming a second decision site.
    * `Loader.scalaImportTarget` already derives a module by dropping the last segment, so handing it
    * `a.b.*` reuses the path that exists instead of a new one that happens to agree with it. And
    * because `blankIfImport` asks the same function, widening HERE also stops the leftover ever
    * reaching `Parser.scala`'s refusal — one edit, three sites, no new place to disagree.
    *
    * The list's CONTENTS are not read, which is why a rename inside one costs nothing:
    * `std/mapreduce/shuffle.ssc:46` writes `{DatasetWire, DatasetWirePartition, JsonValue as
    * TJsonValue}` and every name in it arrives with the module regardless.
    *
    * SINGLE LINE ONLY, and deliberately: a list may be spread over several lines, which needs state
    * this line-at-a-time scanner does not have. Every multi-line one in the tree is under
    * `examples/`, which no corpus case loads, so nothing that runs depends on it — and an unclosed
    * `{` still fails the `isIdent` check below rather than being read as half a path. */
  private def selectorsToStar(p: String): String =
    val open = p.indexOf(".{")
    if open < 0 || !p.endsWith("}") then p else p.substring(0, open) + ".*"

  private def isIdent(s: String): Boolean =
    s.nonEmpty && (s.charAt(0).isLetter || s.charAt(0) == '_') &&
      s.forall(c => c.isLetterOrDigit || c == '_')

  private def trimmed(l: String): String = l.trim

  /** ```` ```scala ```` is CODE too, not prose.
    *
    * Excluding it was a guess — "other fence languages appear as data" — and
    * `standard-scala-mixed-runnable` is the case that says otherwise: it interleaves ```` ```scala ````
    * and ```` ```scalascript ```` blocks and expects both to run, in source order. Its name says so.
    * ```sql / ```yaml stay excluded, and those really are data. */
  // NOT private, and the reason is worth a line: `Loader.importsOf` has to ask the same question —
  // a Scala-style import counts only inside a code fence — and a second copy of this predicate is a
  // second answer to "what is code". `@doc` and the attribute handling below are exactly what a
  // divergent copy would omit first.
  def isCodeFenceOpen(l: String): Boolean =
    // The info string may carry ATTRIBUTES — ```` ```scalascript @id=defs ```` — and matching it
    // whole skipped the fence entirely, so `fence-attr-code` compiled with two of its three blocks
    // missing and no diagnostic. Only the first word names the language.
    val t = trimmed(l)
    if !t.startsWith("```") then false
    else
      val info = t.substring(3).trim
      val lang = info.takeWhile(c => c != ' ' && c != '\t')
      // `@doc` marks the block as DOCUMENTATION: it is shown, never compiled. `fence-doc-block`
      // is the case, and it says so in its own prose — "its `demo` must not become a program
      // definition". Reading attributes without reading this one traded a fence that was wrongly
      // SKIPPED for a fence that is wrongly RUN, which is the worse of the two.
      // `@doc=false` is an explicit opt-out, matching `SscCompose.docAttr`.
      val doc = info.split(Array(' ', '\t')).exists(a => a == "@doc" || (a.startsWith("@doc=") && a != "@doc=false"))
      !doc && (lang == "scalascript" || lang == "ssc" || lang == "scala")

  def isFenceClose(l: String): Boolean = trimmed(l) == "```"
