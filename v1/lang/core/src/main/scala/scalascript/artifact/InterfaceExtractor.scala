package scalascript.artifact

import scalascript.ir.*
import scalascript.ast
import scalascript.transform.EffectAnalysis
import scalascript.typer.{Typer, DefSummary, SType, TypeEvidence, SymbolKind as TSymbolKind}
import scalascript.interop.descriptor.DescriptorError
import scala.meta.*
import scala.collection.mutable.ListBuffer

/** Extracts a `ModuleInterface` from a compiled `NormalizedModule` + its
 *  original AST `Module`.
 *
 *  Strategy:
 *  1. Run `Typer` over the AST to collect `DefSummary` entries (names +
 *     best-effort types) for every top-level definition.
 *  2. Walk the AST to detect `given` instances and `extern def` declarations.
 *  3. Detect capability usage from `serve()`, `fetch()`, `connect()` etc.
 *  4. Build the `ModuleInterface` with the ABI magic + version guard.
 *
 *  The extractor is intentionally conservative: if it can't determine a
 *  type it falls back to `"Any"`.  Richer type information will arrive when
 *  the typer is extended in a later sprint.
 *
 *  v2.0 / Stage 2 — interface extraction.
 */
object InterfaceExtractor:

  private val hexDigits = "0123456789abcdef"

  /** Strict managed-interface extraction.
   *
   *  The descriptor is projected first, exclusively from declaration headers.
   *  Only after that succeeds do we invoke the legacy body-aware extractor and
   *  attach the canonical JSON to its additive opaque carrier. Ordinary
   *  [[extract]] remains the legacy-compatible path and leaves the carrier empty.
   */
  def extractManaged(
      module: ast.Module,
      sourceBytes: Array[Byte]
  ): Either[DescriptorError, ModuleInterface] =
    PreBodyApiDescriptorProducer.canonicalJson(module).map { descriptorJson =>
      extract(module, sourceBytes).copy(apiDescriptorV3 = Some(descriptorJson))
    }

  /** Compute a SHA-256 hex digest of raw bytes. */
  def sha256(bytes: Array[Byte]): String =
    val md     = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    val chars  = new Array[Char](digest.length << 1)
    var i = 0
    while i < digest.length do
      val b = digest(i)
      chars(i << 1)       = hexDigits.charAt((b >>> 4) & 0xF)
      chars((i << 1) + 1) = hexDigits.charAt(b & 0xF)
      i += 1
    new String(chars)

  /** Normalize CRLF (`\r\n`) and bare CR (`\r`) to LF (`\n`).
   *
   *  Fast path: if no `\r` byte is present (the common case on Unix and in
   *  all string payloads constructed in-process) the input is returned as-is
   *  with no allocation.  On Windows a `.ssc` file is typically stored with
   *  CRLF line endings; normalizing before hashing makes the `sourceHash`
   *  identical to the Unix equivalent so artifacts are cross-platform-portable.
   */
  def normalizeLineEndings(bytes: Array[Byte]): Array[Byte] =
    if !bytes.contains('\r'.toByte) then bytes
    else
      val out = new java.io.ByteArrayOutputStream(bytes.length)
      var i   = 0
      while i < bytes.length do
        val b = bytes(i)
        if b == '\r'.toByte then
          if i + 1 >= bytes.length || bytes(i + 1) != '\n'.toByte then out.write('\n'.toInt)
        else out.write(b.toInt)
        i += 1
      out.toByteArray

  /** SHA-256 hex digest of source file bytes with CRLF-normalization.
   *
   *  Use this for all `.ssc` source-file hash computations so that the same
   *  logical source produces the same hash regardless of whether the file was
   *  checked out with CRLF (Windows) or LF (Unix) line endings.  Use the
   *  lower-level [[sha256]] for already-normalized in-process string payloads
   *  (section hashes, interface-shape payloads, etc.). */
  def sourceFileHash(bytes: Array[Byte]): String =
    sha256(normalizeLineEndings(bytes))

  /** Stable identifier for a section in `sectionHashes`.  Built from the
   *  heading text + 0-based index in `module.sections` so duplicate
   *  headings (`"# Examples"` appearing twice) still get distinct keys.
   *  Pair-reads with [[computeSectionHashes]]. */
  def sectionId(s: ast.Section, idx: Int): String =
    s"${s.heading.text}:$idx"

  /** Cumulative per-section SHA-256 chain (Option A).
   *
   *  For each top-level section in `module.sections` the hash digests the
   *  *raw section source bytes* joined with the hashes of every prior
   *  section via `\n`.  Encoding: UTF-8.
   *
   *  Rationale: sections in a single `.ssc` share one module-level scope —
   *  a definition in section 1 is visible in section 3.  An isolated
   *  per-section hash would let consumers skip codegen on section 3 even
   *  when section 1 changed (broken: a renamed identifier in section 1 no
   *  longer resolves in section 3).  Cumulative hashing closes that hole
   *  while still letting the *last* section be cached when only it
   *  changed — common in tutorial-style `.ssc` files where the bottom
   *  hosts the "main" example block.
   *
   *  The section's source is reconstructed from its `Content.CodeBlock`
   *  bodies (other content kinds — prose, lists, imports — don't affect
   *  the compiled output and are intentionally excluded so a typo fix in
   *  a comment paragraph doesn't invalidate every downstream section).
   *  Headings ARE folded into the digest so re-titling a section is
   *  treated as a change (consumers may resolve `${section.title}` at
   *  compile time).
   *
   *  Returns a `Map[sectionId -> hex hash]`.  Empty when `module.sections`
   *  is empty (e.g. a manifest-only `.ssc`). */
  def computeSectionHashes(module: ast.Module): Map[String, String] =
    val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
    var accum: String = "" // running chain of prior section hashes
    module.sections.zipWithIndex.foreach { case (sec, idx) =>
      val raw = sectionRawSource(sec)
      val payload =
        if accum.isEmpty then raw
        else accum + "\n" + raw
      val h = sha256(payload.getBytes("UTF-8"))
      out(sectionId(sec, idx)) = h
      accum = if accum.isEmpty then h else accum + "\n" + h
    }
    out.toMap

  /** Per-section NON-cumulative SHA-256 hashes of just this section's
   *  own source (no chain).  Used by Option B to detect body-only changes
   *  on a single section without the cumulative-cascade side effect of
   *  [[computeSectionHashes]]. */
  def computeSectionOwnHashes(module: ast.Module): Map[String, String] =
    val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
    module.sections.zipWithIndex.foreach { case (sec, idx) =>
      val raw = sectionRawSource(sec)
      out(sectionId(sec, idx)) = sha256(raw.getBytes("UTF-8"))
    }
    out.toMap

  /** Per-section PUBLIC-INTERFACE SHA-256 hashes (Option B).
   *
   *  Hashes a section's exported `Defn.*` shapes (names + signatures),
   *  NOT the bodies.  Section N stays cached when its body changes if
   *  its interface (what it exports to later sections) is unchanged.
   *
   *  Body-level details (the right-hand side of a `def`, the contents
   *  of an `object` body) don't perturb this hash — only the SHAPE
   *  surfaced to later sections matters.  Pairs with the body-level
   *  `sectionHashes`: a consumer using `--section-cache=interface`
   *  walks both maps and concludes a section is stale only when
   *  (a) its own body hash changed OR (b) some prior section's
   *  INTERFACE hash changed.
   *
   *  Returns a `Map[sectionId -> hex hash]`.  Empty when `module.sections`
   *  is empty.  Sections with no top-level definitions hash to a stable
   *  empty-list digest. */
  def computeSectionInterfaceHashes(module: ast.Module): Map[String, String] =
    val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
    module.sections.zipWithIndex.foreach { case (sec, idx) =>
      val shape = sectionInterfaceShape(sec)
      // Canonical encoding: sort entries by (kind, name) so order-only
      // changes in scalameta's parse don't perturb the hash.
      val sorted = shape.sortBy { case (k, n, s) => (k, n, s) }
      val payload = sorted.iterator.map { case (k, n, s) => s"$k $n $s" }.mkString("\n")
      out(sectionId(sec, idx)) = sha256(payload.getBytes("UTF-8"))
    }
    out.toMap

  /** Collect a section's interface shape — list of (kind, name, signature)
   *  triples for every top-level `Defn.*` in every code block plus every
   *  subsection.  Externs are included with their (param-typed) signature;
   *  other defs use `typeToString` of decltpe (else "Any"). */
  private def sectionInterfaceShape(sec: ast.Section): List[(String, String, String)] =
    val out = scala.collection.mutable.ListBuffer.empty[(String, String, String)]
    def collectFromTree(node: scalascript.ast.ScalaNode): Unit =
      scalascript.ast.ScalaNode.fold(node) {
        case src: scala.meta.Source => src.stats.foreach(collectStat)
        case blk: scala.meta.Term.Block => blk.stats.foreach(collectStat)
        case other                       => collectStat(other)
      }
    def typeStr(t: Option[Type]): String = t.map(_.toString).getOrElse("Any")
    def defSig(d: Defn.Def): String =
      val ps = d.paramClauseGroups.flatMap(_.paramClauses).map { clause =>
        clause.values.map(p => s"${p.name.value}: ${typeStr(p.decltpe)}").mkString("(", ", ", ")")
      }.mkString
      s"$ps: ${typeStr(d.decltpe)}"
    def collectStat(t: scala.meta.Tree): Unit = t match
      case d: Defn.Def =>
        out += (("def", d.name.value, defSig(d)))
      case d: Defn.Val =>
        d.pats.foreach { case Pat.Var(n) => out += (("val", n.value, typeStr(d.decltpe))); case _ => () }
      case d: Defn.Var =>
        d.pats.foreach { case Pat.Var(n) => out += (("var", n.value, typeStr(d.decltpe))); case _ => () }
      case d: Defn.Class  => out += (("class",  d.name.value, ""))
      case d: Defn.Object => out += (("object", d.name.value, ""))
      case d: Defn.Trait  => out += (("trait",  d.name.value, ""))
      case d: Defn.Enum   => out += (("enum",   d.name.value, ""))
      case d: Defn.Type   => out += (("type",   d.name.value, ""))
      case _              => ()
    sec.content.foreach {
      case cb: ast.Content.CodeBlock =>
        cb.tree.foreach(collectFromTree)
      case _ => ()
    }
    sec.subsections.foreach { sub => out ++= sectionInterfaceShape(sub) }
    out.toList

  /** Reconstruct the load-bearing source of a section for the section-
   *  hash chain: heading text + every `Content.CodeBlock` source body
   *  (recursing into `subsections`).  Prose / lists / imports are
   *  excluded — they don't influence the compiled artifact.  Imports
   *  ARE included as their literal path string so a re-pointed import
   *  still invalidates the section. */
  private def sectionRawSource(sec: ast.Section): String =
    val sb = new StringBuilder
    sb.append("#" * sec.heading.level).append(' ').append(sec.heading.text).append('\n')
    sec.content.foreach {
      case cb: ast.Content.CodeBlock =>
        sb.append("```").append(cb.lang).append('\n')
          .append(cb.source).append('\n')
          .append("```\n")
      case imp: ast.Content.Import =>
        sb.append("[import](").append(imp.path).append(")\n")
      case _ => () // prose / data lists don't influence codegen
    }
    sec.subsections.foreach(sub => sb.append(sectionRawSource(sub)))
    sb.toString

  /** Extract a `ModuleInterface` from a parsed AST module + its source bytes.
   *
   *  @param module     The parsed AST module (from `Parser.parse`).
   *  @param sourceBytes The raw `.ssc` source bytes for the SHA-256 hash.
   *  @return A `ModuleInterface` ready to be serialised to `.scim` JSON.
   */
  def extract(module: ast.Module, sourceBytes: Array[Byte]): ModuleInterface =
    val pkg         = module.manifest.flatMap(_.pkg).getOrElse(Nil)
    val moduleName  = module.manifest.flatMap(_.name)
    val moduleVer   = module.manifest.flatMap(_.version)
    val deps        = module.manifest.map(_.dependencies).getOrElse(Map.empty)
    val srcHash     = sourceFileHash(sourceBytes)

    // Run the typer to collect definitions with best-effort types.
    val typed = Typer.typeCheck(module)

    // Collect all DefSummary entries from the typed module.
    val allDefs = ListBuffer.empty[DefSummary]
    def gatherSection(s: scalascript.typer.TypedSection): Unit =
      s.definitions.foreach {
        case scalascript.typer.TypedDef.CodeBlock(_, _, defs) => allDefs ++= defs
        case _ => ()
      }
      s.subsections.foreach(gatherSection)
    typed.sections.foreach(gatherSection)

    // When `package: foo.bar` is set in the front-matter, the parser wraps
    // every code block in nested `object foo: object bar: <body>` shells
    // (see `Parser.wrapSectionInPackage`).  The typer's top-level walk then
    // sees only `object foo` as a top-level definition, NOT the inner defs
    // that consumers actually want to import.  Strip the outer-shell
    // objects from the typer-derived list here; we re-collect the inner
    // user defs from the AST below (`scalaTrees` is populated next).
    if pkg.nonEmpty then
      val shellNames = pkg.toSet
      val filtered = allDefs.filterNot { d =>
        d.kind == TSymbolKind.Object && shellNames.contains(d.name)
      }
      allDefs.clear()
      allDefs ++= filtered

    // Also scan for `given` instances and `extern def` by walking the AST.
    val instances  = ListBuffer.empty[InstanceDecl]
    val externDefs = ListBuffer.empty[ExportedSymbol]

    // Collect every parsed scalameta tree across all code blocks — used by
    // both the structural extern walk and the AST capability detector.
    val scalaTrees = ListBuffer.empty[scala.meta.Tree]
    // Per-block `lineOffset` carried alongside the scalameta `Source` tree
    // so position queries on inner stats can translate scalameta's
    // (block-local) line back to the originating `.ssc` line.  Keyed by
    // identity hash of the scalameta `Source` node so multiple blocks
    // never collide.
    val blockLineOffsets = scala.collection.mutable.Map.empty[scala.meta.Tree, Int]
    def collectTrees(sec: ast.Section): Unit =
      sec.content.foreach {
        case cb: ast.Content.CodeBlock if ast.Lang.isParseable(cb.lang) =>
          cb.tree.foreach { node =>
            scalascript.ast.ScalaNode.fold(node) { t =>
              scalaTrees += t
              // Remember the lineOffset of the enclosing block so
              // [[positionFor]] (below) can produce file-level lines.
              blockLineOffsets(t) = cb.lineOffset
            }
          }
        case _ => ()
      }
      sec.subsections.foreach(collectTrees)
    module.sections.foreach(collectTrees)

    // ── Position resolution ───────────────────────────────────────────────
    //
    // For each top-level definition we want the file-level (line, column)
    // of the defining-name token (the `def`/`val`/`class`/... keyword's
    // *name identifier*).  scalameta gives us positions relative to the
    // input it parsed, which is the (possibly package-wrapped) code-block
    // source.  Translate:
    //
    //    file_line = block.lineOffset + (scalameta_line − pkg.size)
    //    file_col  = scalameta_col      − 2 * pkg.size
    //
    // (each `object pkg(i):` wrap layer adds one line at the head and
    // indents the body by two columns).  Without a package wrap both
    // adjustments are zero.  Negative results (the wrapper objects
    // themselves) are clamped to 0 — those symbols are filtered out of
    // the export list anyway.
    val wrapLines   = pkg.size
    val wrapColumns = 2 * pkg.size
    def positionFor(t: scala.meta.Tree): (Int, Int) =
      // Find the nearest enclosing `Source` we recorded an offset for.
      // scalameta's `parent` chain leads back up to the Source we folded
      // into `blockLineOffsets`; use `Option(...).getOrElse(0)` to stay
      // safe when the lookup misses (defensive — shouldn't happen).
      var cursor: scala.meta.Tree = t
      var offset = 0
      var found = false
      while (!found && cursor != null) do
        blockLineOffsets.get(cursor) match
          case Some(off) =>
            offset = off
            found = true
          case None =>
            cursor =
              if cursor.parent.isDefined then cursor.parent.get else null
      val pos = t.pos
      val rawLine = pos.startLine
      val rawCol  = pos.startColumn
      val line = math.max(0, offset + (rawLine - wrapLines))
      val col  = math.max(0, rawCol - wrapColumns)
      (line, col)

    // ── Package-shell walk ──────────────────────────────────────────────
    //
    // `Parser.wrapSectionInPackage` wraps every code block in
    // `object pkg(0): object pkg(1): ... <body>` when the manifest sets
    // `package: foo.bar`.  Descend through the matching `Defn.Object`
    // shells in each parsed `Source` and surface the inner top-level
    // user definitions — without this step, a `case class DocLine`
    // under `package: std.dsl` is invisible to consumers of the `.scim`.
    //
    // Inner stats are also fed back into `allDefs` so they appear in the
    // exports list with the right `fqn` (joined via `_` by `fqn(name)`).
    def unwrapPackage(stats: List[Stat], remaining: List[String]): List[Stat] =
      remaining match
        case Nil => stats
        case head :: tail =>
          stats.collectFirst {
            case obj: Defn.Object if obj.name.value == head =>
              unwrapPackage(obj.templ.body.stats, tail)
          }.getOrElse(Nil)

    /** Inner top-level stats reached after stripping the package shell.
     *  Empty when no `package:` is set, so the rest of the pipeline
     *  (extern / given walks below) sees the original trees unchanged. */
    val packageInnerStats: List[Stat] =
      if pkg.isEmpty then Nil
      else
        scalaTrees.toList.collect { case s: Source => s.stats }
          .flatMap(stats => unwrapPackage(stats, pkg))

    // Synthesize DefSummary entries for the package-inner top-level defs
    // so they flow through the normal `exports` pipeline (including the
    // manifest `exports:` filter).  Types are best-effort `Any` — the
    // typer doesn't currently descend into objects, so we cannot recover
    // richer types without re-running it on the inner stats.
    if pkg.nonEmpty then
      packageInnerStats.foreach {
        case d: Defn.Val =>
          d.pats.foreach {
            case Pat.Var(n) =>
              allDefs += DefSummary(n.value, TSymbolKind.Val, SType.Any, Nil)
            case _ => ()
          }
        case d: Defn.Var =>
          d.pats.foreach {
            case Pat.Var(n) =>
              allDefs += DefSummary(n.value, TSymbolKind.Var, SType.Any, Nil)
            case _ => ()
          }
        case d: Defn.Def =>
          // Skip extern markers — they are reported via `externDefs`, not
          // the generic exports list.
          if !EffectAnalysis.isExternDef(d.body) then
            allDefs += DefSummary(d.name.value, TSymbolKind.Def, SType.Any, Nil)
        case d: Defn.Class  => allDefs += DefSummary(d.name.value, TSymbolKind.Class,  SType.Any, Nil)
        case d: Defn.Object => allDefs += DefSummary(d.name.value, TSymbolKind.Object, SType.Any, Nil)
        case d: Defn.Trait  => allDefs += DefSummary(d.name.value, TSymbolKind.Trait,  SType.Any, Nil)
        case d: Defn.Enum   => allDefs += DefSummary(d.name.value, TSymbolKind.Enum,   SType.Any, Nil)
        case _              => ()
      }

    def fqn(name: String): String =
      if pkg.isEmpty then name
      else (pkg :+ name).mkString("_")

    /** Render a parameter `T` annotation (or `Any` when absent). */
    def typeToString(t: Option[Type]): String =
      t.map(_.toString).getOrElse("Any")

    /** Render a `def`'s signature as `(p1: T1, p2: T2): R` so the
     *  extern entry preserves more than the return type alone. */
    def defSignature(d: Defn.Def): String =
      val groups = d.paramClauseGroups.flatMap(_.paramClauses)
      val paramText = groups.map { clause =>
        val items = clause.values.map { p =>
          s"${p.name.value}: ${typeToString(p.decltpe)}"
        }
        items.mkString("(", ", ", ")")
      }.mkString
      val ret = typeToString(d.decltpe)
      if paramText.isEmpty then ret else s"$paramText: $ret"

    /** arch-meta-v2-p3 / C1 — Extract `inline def` metadata.
     *
     *  Returns `Some((firstClauseParamNames, bodySource))`. For a **single**
     *  parameter clause (`inline def f(a, b) = body`) the body is the def body
     *  verbatim. For **multiple** clauses (`inline def f(a)(b) = body`) the tail
     *  clauses are curried into the body — `((b) => body)` — so the existing
     *  single-clause call-site scanner expands `f(x)(y)` to
     *  `((a) => (b) => body)(x)(y)` with no scanner change: it rewrites the
     *  first clause and leaves the trailing `(y)` as an ordinary application.
     *  `using`/`given` clauses are dropped (resolved by the compiler).
     *  Returns `None` for non-inline defs. */
    def extractInlineInfo(d: Defn.Def): Option[(List[String], String)] =
      if !d.mods.exists(_.is[Mod.Inline]) then None
      else
        val clauses = d.paramClauseGroups.flatMap(_.paramClauses)
          .filterNot(_.values.exists(_.mods.exists(_.is[Mod.Using])))
          .map(_.values.map(_.name.value))
        clauses match
          case Nil            => Some(Nil   -> d.body.syntax)   // zero-arg
          case first :: Nil   => Some(first -> d.body.syntax)   // single clause (unchanged)
          case first :: rest  =>                                // multi-clause → curry the tail
            val curriedBody = rest.foldRight(d.body.syntax) { (clauseParams, acc) =>
              s"(${clauseParams.mkString(", ")}) => $acc"
            }
            Some(first -> curriedBody)

    /** arch-meta-v2-p4 — Extract restricted quoted macro entrypoints.
     *
     *  The parser preprocessor rewrites `${ fooImpl('x) }` to
     *  `__ssc_macro__(fooImpl(__ssc_quote__("x")))`, so this extractor can
     *  stay syntax-tree agnostic and read the stable helper-call shape. */
    def extractMacroImplRef(d: Defn.Def): Option[MacroImplRef] =
      if !d.mods.exists(_.is[Mod.Inline]) then None
      else
        val body = d.body.syntax.trim
        val Prefix = "__ssc_macro__("
        if !body.startsWith(Prefix) || !body.endsWith(")") then None
        else
          val inner = body.substring(Prefix.length, body.length - 1).trim
          val nameEnd = inner.indexOf('(')
          if nameEnd <= 0 || !inner.endsWith(")") then None
          else
            val implName = inner.substring(0, nameEnd).trim
            val argsText = inner.substring(nameEnd + 1, inner.length - 1)
            val quotedParam = """__ssc_quote__\("([^"]+)"(?:\s*,[^)]*)?\)""".r
            val params = quotedParam.findAllMatchIn(argsText).map(_.group(1)).toList
            Some(MacroImplRef(
              implName = implName,
              quotedParams = params,
              resultType = d.decltpe.map(_.toString)
            ))

    /** arch-meta-v2-p4 — Direct quoted expression body of a macro impl.
     *  `'{ $x + 1 }` is preprocessed to
     *  `__ssc_quote_expr__(__ssc_splice__("x", x).+(1))` by scalameta syntax
     *  rendering.  Store that body verbatim; Linker handles both original
     *  and preprocessed quote/splice spellings. */
    def extractMacroQuotedBody(d: Defn.Def): Option[String] =
      val body = d.body.syntax.trim
      if body.startsWith("__ssc_quote_expr__(") && body.endsWith(")") then Some(body)
      else if isAsValueMatchBody(d.body) then Some(body)
      else None

    /** arch-meta-v2 Track B — a macro impl that branches on `Expr.asValue` via
     *  `<param>.asValue match { case Some(_) => … case None => … }`.  The body
     *  is stored verbatim so the Linker can const-fold call sites with literal
     *  arguments (the `Some` branch) and fall back to the direct-quote `None`
     *  branch otherwise. */
    def isAsValueMatchBody(t: Term): Boolean = t match
      case m: Term.Match =>
        m.expr match
          case Term.Select(_, Term.Name("asValue")) => true
          case _                                    => false
      case _ => false

    def isMacroImplDef(d: Defn.Def): Boolean =
      d.decltpe.exists(_.toString.contains("Expr[")) || extractMacroQuotedBody(d).nonEmpty

    // ── Recursive nested-member extraction ────────────────────────────────
    //
    // Maximum depth for walking nested `Defn.Object` stats.  An object at
    // depth N may carry `nested` entries; an object at depth `MaxNestedDepth`
    // is treated as opaque (its `nested` stays `Nil`).  Three levels cover
    // the common cases (`pkg.sub.member`, `pkg.outer.inner.member`) without
    // unbounded recursion on pathological inputs.
    //
    // TODO(v2.x): lift to unbounded depth once we measure the .scim size /
    // typer cost; the strict-mode resolver doesn't need a hard cap.
    val MaxNestedDepth = 3

    /** Build an `ExportedSymbol` for a single top-level-ish stat, recursing
     *  into `Defn.Object` bodies up to `MaxNestedDepth - depth` further
     *  levels.  Returns `None` for stats that are not user-facing exports
     *  (e.g. `Defn.Given`, extern defs, bare expressions).  `parentFqn`
     *  is the dotted-then-underscore-joined FQN prefix of the enclosing
     *  symbol (e.g. `"pkg_Foo"`); the returned symbol's `fqn` is
     *  `s"${parentFqn}_${name}"` (or just `name` when `parentFqn` is empty). */
    def buildNestedSymbol(stat: Stat, parentFqn: String, depth: Int): Option[ExportedSymbol] =
      def joinFqn(name: String): String =
        if parentFqn.isEmpty then name else s"${parentFqn}_$name"
      stat match
        case d: Defn.Def if !EffectAnalysis.isExternDef(d.body) =>
          val (dl, dc) = positionFor(d.name)
          val inl = extractInlineInfo(d)
          val macroRef = extractMacroImplRef(d)
          val quotedBody = extractMacroQuotedBody(d)
          Some(ExportedSymbol(
            name = d.name.value,
            fqn  = joinFqn(d.name.value),
            kind = "def",
            tpe  = "Any",
            definitionLine   = dl,
            definitionColumn = dc,
            isInline         = inl.isDefined,
            inlineParamNames = inl.map(_._1).getOrElse(Nil),
            inlineBodySource = inl.map(_._2),
            macroImpl = macroRef,
            isMacroImpl = isMacroImplDef(d),
            macroQuotedBodySource = quotedBody
          ))
        case d: Defn.Val =>
          // Multi-pat `val (a, b) = …` is rare here; surface each Pat.Var.
          d.pats.collectFirst { case p @ Pat.Var(n) => (n.value, p) }.map { (n, p) =>
            val (dl, dc) = positionFor(p)
            ExportedSymbol(
              name = n, fqn = joinFqn(n), kind = "val", tpe = "Any",
              definitionLine = dl, definitionColumn = dc
            )
          }
        case d: Defn.Var =>
          d.pats.collectFirst { case p @ Pat.Var(n) => (n.value, p) }.map { (n, p) =>
            val (dl, dc) = positionFor(p)
            ExportedSymbol(
              name = n, fqn = joinFqn(n), kind = "var", tpe = "Any",
              definitionLine = dl, definitionColumn = dc
            )
          }
        case d: Defn.Class =>
          val (dl, dc) = positionFor(d.name)
          Some(ExportedSymbol(
            name = d.name.value,
            fqn  = joinFqn(d.name.value),
            kind = "class",
            tpe  = "Any",
            definitionLine = dl, definitionColumn = dc
          ))
        case d: Defn.Trait =>
          val (dl, dc) = positionFor(d.name)
          Some(ExportedSymbol(
            name = d.name.value,
            fqn  = joinFqn(d.name.value),
            kind = "trait",
            tpe  = "Any",
            definitionLine = dl, definitionColumn = dc
          ))
        case d: Defn.Enum =>
          val (dl, dc) = positionFor(d.name)
          Some(ExportedSymbol(
            name = d.name.value,
            fqn  = joinFqn(d.name.value),
            kind = "enum",
            tpe  = "Any",
            definitionLine = dl, definitionColumn = dc
          ))
        case d: Defn.Object =>
          val (dl, dc) = positionFor(d.name)
          val nested =
            if depth + 1 >= MaxNestedDepth then Nil
            else d.templ.body.stats.flatMap(s =>
              buildNestedSymbol(s, joinFqn(d.name.value), depth + 1)
            )
          Some(ExportedSymbol(
            name   = d.name.value,
            fqn    = joinFqn(d.name.value),
            kind   = "object",
            tpe    = "Any",
            nested = nested,
            definitionLine   = dl,
            definitionColumn = dc
          ))
        case _ => None

    /** Resolve, in `stats`, the nested-member list for a `Defn.Object`
     *  named `objName`.  Used to back-fill `nested` on top-level exports
     *  whose source-form is `object Foo: …` — the surrounding `rawExports`
     *  build path emits the parent as a flat `ExportedSymbol`, then we
     *  look up its body here and populate `nested` from the AST. */
    def nestedForObject(stats: List[Stat], objName: String, parentFqn: String): List[ExportedSymbol] =
      stats.collectFirst {
        case obj: Defn.Object if obj.name.value == objName =>
          obj.templ.body.stats.flatMap(s =>
            buildNestedSymbol(s, parentFqn, depth = 1)
          )
      }.getOrElse(Nil)

    def scanStats(stats: List[Stat]): Unit =
      stats.foreach {
        // given instance: `given eqInt: Eq[Int] = ...`  or `given Eq[Int] = ...`
        case d: Defn.Given =>
          // Scalameta surfaces an empty `name.value` for anonymous givens
          // (`given Eq[Int] with …`).  Detect that here and synthesize a
          // deterministic witness identity from the typeclass + type-param
          // below.  Don't trust the raw name — even for "named" forms,
          // some scalameta versions inject auto-synthesized names that
          // start with `given_`, which we want to regenerate stably.
          val explicitName = d.name.value
          // Attempt to pull the typeclass + type-param from the template
          // (best-effort: look at the parent type list).
          d.templ.inits.headOption.foreach { init =>
            init.tpe match
              case ta: Type.Apply =>
                val tc = ta.tpe match { case Type.Name(n) => n; case _ => "" }
                if tc.nonEmpty then
                  val typeParam = ta.argClause.values.headOption.map(_.toString).getOrElse("_")
                  val witnessName =
                    if explicitName.nonEmpty then explicitName
                    else synthGivenName(tc, ta.argClause.values.headOption)
                  instances += InstanceDecl(
                    typeclass   = tc,
                    typeParam   = typeParam,
                    witnessName = witnessName,
                    fqn         = fqn(witnessName)
                  )
              case Type.Name(tc) =>
                val witnessName =
                  if explicitName.nonEmpty then explicitName
                  else synthGivenName(tc, None)
                instances += InstanceDecl(
                  typeclass   = tc,
                  typeParam   = "_",
                  witnessName = witnessName,
                  fqn         = fqn(witnessName)
                )
              case _ => ()
          }
        // extern def: `extern def foo(...): T` is rewritten by
        // `Parser.preprocessExtern` to `def foo(...): T = __extern__`
        // *before* parsing, so the AST body is literally the marker
        // `Term.Name("__extern__")`.  `EffectAnalysis.isExternDef` is
        // the authoritative recogniser — reuse it instead of guessing.
        case dd: Defn.Def if EffectAnalysis.isExternDef(dd.body) =>
          val (dl, dc) = positionFor(dd.name)
          externDefs += ExportedSymbol(
            name = dd.name.value,
            fqn  = fqn(dd.name.value),
            kind = "extern",
            tpe  = defSignature(dd),
            definitionLine   = dl,
            definitionColumn = dc
          )
        case _ => ()
      }

    scalaTrees.foreach {
      case Source(stats)     =>
        // When a package shell wraps the body, scan the inner stats so
        // `extern def` / `given` declarations below the shell are still
        // discovered.  Otherwise scan the top-level stats as before.
        if pkg.nonEmpty then scanStats(unwrapPackage(stats, pkg))
        else scanStats(stats)
      case Term.Block(stats) => scanStats(stats)
      case other             => other.children.foreach {
        case s: Source     => scanStats(s.stats)
        case b: Term.Block => scanStats(b.stats)
        case _             => ()
      }
    }

    // Build exports from DefSummary list (excluding prelude builtins and params).
    //
    // The prelude-name filter only applies to non-packaged modules.  When
    // `package: foo` is set, the typer's top-level scan saw only the
    // shell `object foo` (already stripped above) — the remaining entries
    // come from the explicit AST package walk, so they are user defs by
    // construction and must NOT be confused with the typer's auto-injected
    // prelude names like `render` or `serve`.
    val preludeNames = Set(
      "println", "print", "assert", "require", "Some", "None",
      "List", "Map", "math", "doc", "render", "serve"
    )
    // Source-level top-level stats from which we recover nested-object
    // member lists.  With `package: foo.bar`, the user's defs live under
    // the synthetic shell and were already unwrapped above; without it,
    // walk every parsed `Source` directly.
    val topLevelStats: List[Stat] =
      if pkg.nonEmpty then packageInnerStats
      else scalaTrees.toList.collect { case s: Source => s.stats }.flatten

    // Map top-level symbol name → file-level (line, col) of its defining
    // identifier.  Used to populate `ExportedSymbol.definitionLine` /
    // `definitionColumn` so cross-module go-to-definition jumps to the
    // actual line, not (0,0).  Built from `topLevelStats` so the right
    // tree is consulted regardless of whether `package:` is set.
    val topLevelPositions: Map[String, (Int, Int)] =
      val out = scala.collection.mutable.LinkedHashMap.empty[String, (Int, Int)]
      topLevelStats.foreach {
        case d: Defn.Def    => out(d.name.value) = positionFor(d.name)
        case d: Defn.Val    =>
          d.pats.foreach {
            case p @ Pat.Var(n) => out(n.value) = positionFor(p)
            case _              => ()
          }
        case d: Defn.Var    =>
          d.pats.foreach {
            case p @ Pat.Var(n) => out(n.value) = positionFor(p)
            case _              => ()
          }
        case d: Defn.Class  => out(d.name.value) = positionFor(d.name)
        case d: Defn.Object => out(d.name.value) = positionFor(d.name)
        case d: Defn.Trait  => out(d.name.value) = positionFor(d.name)
        case d: Defn.Enum   => out(d.name.value) = positionFor(d.name)
        case d: Defn.Type   => out(d.name.value) = positionFor(d.name)
        case _              => ()
      }
      out.toMap

    // arch-lib-p2 — collect names of top-level defs annotated @internal so
    // the exported symbol can be marked isInternal = true in the interface.
    val internalNames: Set[String] =
      def hasInternalAnnot(mods: List[Mod]): Boolean =
        mods.exists {
          case Mod.Annot(init) =>
            (init.tpe match
              case Type.Name(n)                 => n == "internal"
              case Type.Select(_, Type.Name(n)) => n == "internal"
              case _                            => false)
          case _ => false
        }
      topLevelStats.flatMap {
        case d: Defn.Def    if hasInternalAnnot(d.mods) => List(d.name.value)
        case d: Defn.Val    if hasInternalAnnot(d.mods) => d.pats.collect { case Pat.Var(n) => n.value }
        case d: Defn.Var    if hasInternalAnnot(d.mods) => d.pats.collect { case Pat.Var(n) => n.value }
        case d: Defn.Class  if hasInternalAnnot(d.mods) => List(d.name.value)
        case d: Defn.Object if hasInternalAnnot(d.mods) => List(d.name.value)
        case d: Defn.Trait  if hasInternalAnnot(d.mods) => List(d.name.value)
        case _                                           => Nil
      }.toSet

    // arch-meta-v2-p3 — inline metadata for top-level defs.
    // `buildNestedSymbol` already populates isInline for nested objects;
    // this map covers the top-level path that goes through `allDefs`.
    val topLevelInlineInfo: Map[String, (List[String], String)] =
      topLevelStats.collect {
        case d: Defn.Def => extractInlineInfo(d).map(d.name.value -> _)
      }.flatten.toMap

    val topLevelMacroInfo: Map[String, MacroImplRef] =
      topLevelStats.collect {
        case d: Defn.Def => extractMacroImplRef(d).map(d.name.value -> _)
      }.flatten.toMap

    val topLevelMacroQuotedBodies: Map[String, String] =
      topLevelStats.collect {
        case d: Defn.Def => extractMacroQuotedBody(d).map(d.name.value -> _)
      }.flatten.toMap

    val topLevelMacroImplNames: Set[String] =
      topLevelStats.collect {
        case d: Defn.Def if isMacroImplDef(d) => d.name.value
      }.toSet

    val rawExports = allDefs
      .filterNot { d =>
        d.kind == TSymbolKind.Param ||
          (pkg.isEmpty && preludeNames.contains(d.name))
      }
      .map { d =>
        val nested =
          if d.kind == TSymbolKind.Object then
            nestedForObject(topLevelStats, d.name, fqn(d.name))
          else Nil
        val (dl, dc) = topLevelPositions.getOrElse(d.name, (0, 0))
        val inlineInfo = topLevelInlineInfo.get(d.name)
        val macroInfo = topLevelMacroInfo.get(d.name).map { ref =>
          val withBody = topLevelMacroQuotedBodies.get(ref.implName).orElse(ref.expansionBodySource)
          ref.copy(expansionBodySource = withBody)
        }
        ExportedSymbol(
          name             = d.name,
          fqn              = fqn(d.name),
          kind             = kindString(d.kind),
          tpe              = d.tpe.show,
          nested           = nested,
          definitionLine   = dl,
          definitionColumn = dc,
          isInternal       = internalNames.contains(d.name),
          isInline         = inlineInfo.isDefined,
          inlineParamNames = inlineInfo.map(_._1).getOrElse(Nil),
          inlineBodySource = inlineInfo.map(_._2),
          macroImpl = macroInfo,
          isMacroImpl = topLevelMacroImplNames.contains(d.name),
          macroQuotedBodySource = topLevelMacroQuotedBodies.get(d.name),
          evidence         = d.evidence.map(typeEvidenceWire)
        )
      }
      .toList

    // Respect `exports:` in the manifest: if non-empty, expose ONLY those
    // names in the interface so private helpers stay hidden from consumers.
    // An absent / empty `exports:` keeps the default behaviour (export
    // everything top-level).
    val manifestExports = module.manifest.map(_.exports).getOrElse(Nil)
    val exports =
      if manifestExports.isEmpty then rawExports
      else
        val allow = manifestExports.toSet
        rawExports.filter(s => allow.contains(s.name))

    // Detect capabilities by walking the parsed scalameta trees.  This
    // is the v2.0 AST-based replacement for the prior string-grep heuristic
    // (which fired inside string literals, comments, and renames).
    val capabilities = detectCapabilities(scalaTrees.toList)

    ModuleInterface(
      magic         = ArtifactVersion.magic,
      abiVersion    = ArtifactVersion.current,
      pkg           = pkg,
      moduleName    = moduleName,
      moduleVersion = moduleVer,
      sourceHash    = srcHash,
      exports       = exports,
      instances     = instances.toList,
      capabilities  = capabilities,
      externDefs    = externDefs.toList,
      dependencies  = deps,
      sectionHashes = computeSectionHashes(module),
      sectionOwnHashes = computeSectionOwnHashes(module),
      sectionInterfaceHashes = computeSectionInterfaceHashes(module),
      scalaFacade   = buildScalaFacade(pkg, exports)
    )

  /** Build the natural-FQN → mangled-FQN map for a module's exports.
   *
   *  After Tier 5 (`package`-clause emission in JvmGen), the JVM symbol
   *  for a `package: x.y` module's `def add` is `x.y.add` directly —
   *  the natural FQN IS the JVM path, no mangling needed.  This map
   *  becomes the identity for `pkg`-decorated modules.
   *
   *  For top-level `package: x.y`, nested members (depth ≤
   *  `MaxNestedDepth` via `ExportedSymbol.nested`) join `.` on both
   *  sides; e.g. `x.y.Card.apply` → `x.y.Card.apply`.
   *
   *  For modules WITHOUT `package:` (empty `pkg`), exports land in
   *  Scala 3's empty-package top-level wrapper (`<scriptName>_sc$package$`)
   *  which is unreachable from named-package consumers — these
   *  entries are SKIPPED to avoid surfacing a JVM symbol that can't
   *  actually be imported.
   *
   *  Respects `exports:` front-matter implicitly — the caller passes
   *  the already-filtered `exports` list, so private helpers stay out.
   *
   *  Tier 1 of the Scala ↔ ScalaScript interop spec
   *  (`specs/scala-interop.md`); updated for Tier 5 (Phase-2 split-runtime
   *  emission rewrites `object pkg: object sub:` → `package pkg.sub:`). */
  private def buildScalaFacade(pkg: List[String], exports: List[ExportedSymbol]): Map[String, String] =
    if pkg.isEmpty then return Map.empty
    val table = scala.collection.mutable.LinkedHashMap.empty[String, String]
    def emit(sym: ExportedSymbol, parentPath: List[String]): Unit =
      val fqn = (parentPath :+ sym.name).mkString(".")
      table(fqn) = fqn   // identity — JVM symbol == natural FQN
      sym.nested.foreach(child => emit(child, parentPath :+ sym.name))
    exports.foreach(sym => emit(sym, pkg))
    table.toMap

  /** Detect well-known capability markers by structurally walking the
   *  parsed scalameta trees and matching call-site identifiers against
   *  the canonical intrinsic table in [[CapabilityRegistry]].
   *
   *  This replaces the v1 raw-text heuristic, which:
   *    1. fired inside string literals (`val msg = "serve(...)"` falsely
   *       reported `Http`),
   *    2. fired inside `// serve(...)` comments,
   *    3. silently broke if an intrinsic was renamed without updating
   *       the grep list.
   *
   *  Best-effort shadowing avoidance: if a name is also defined as a
   *  top-level `Defn.Def` / `Defn.Val` in the module, calls to that name
   *  are assumed to refer to the user's definition and are NOT counted
   *  as capability uses.  Qualified calls (`Response.html`,
   *  `Dataset.of`) are always counted — qualified names cannot be
   *  shadowed by a bare local def.
   *
   *  Known limitations:
   *    - Local lexical scoping is not tracked: a `def serve` inside one
   *      block masks ALL uses of `serve` in the module, even from
   *      sibling blocks.  Refinement awaits Stage 5 IR-level analysis.
   *    - Imports renaming an intrinsic (`import std.http.serve as srv`)
   *      are not followed.
   */
  private def detectCapabilities(trees: List[scala.meta.Tree]): List[CapabilityDecl] =
    // Collect user-declared top-level names so we can skip calls that
    // resolve to a user def of the same simple name.
    val declared = scala.collection.mutable.Set.empty[String]
    def collectDecls(stats: List[Stat]): Unit = stats.foreach {
      // `extern def foo` is a declaration of an intrinsic, NOT a user
      // implementation that should shadow it.  Skip externs so calls to
      // `foo` are still recognised as capability uses.
      case d: Defn.Def if !EffectAnalysis.isExternDef(d.body) =>
        declared += d.name.value
      case d: Defn.Val    => d.pats.foreach { case Pat.Var(n) => declared += n.value; case _ => () }
      case d: Defn.Var    => d.pats.foreach { case Pat.Var(n) => declared += n.value; case _ => () }
      case d: Defn.Object => declared += d.name.value
      case d: Defn.Class  => declared += d.name.value
      case d: Defn.Trait  => declared += d.name.value
      case _              => ()
    }
    trees.foreach {
      case Source(stats)     => collectDecls(stats)
      case Term.Block(stats) => collectDecls(stats)
      case other             => other.children.foreach {
        case s: Source     => collectDecls(s.stats)
        case b: Term.Block => collectDecls(b.stats)
        case _             => ()
      }
    }

    val caps = scala.collection.mutable.Set.empty[String]

    /** Record a bare-name call if it isn't shadowed by a user decl. */
    def hitBare(name: String): Unit =
      if !declared.contains(name) then
        CapabilityRegistry.capabilityFor(name).foreach(caps += _)

    /** Record a qualified call `Qual.method`.  Qualified names aren't
     *  shadowable by a top-level bare def, so always count. */
    def hitQualified(qual: String, method: String): Unit =
      val full = s"$qual.$method"
      CapabilityRegistry.capabilityFor(full).foreach(caps += _)
      // Pure qualifier surface (e.g. `crypto.*`) maps wholesale to a capability.
      CapabilityRegistry.capabilityForQualifier(qual).foreach(caps += _)

    def walk(tree: scala.meta.Tree): Unit =
      tree match
        // f(args) where f is a bare name
        case Term.Apply.After_4_6_0(Term.Name(n), _) =>
          hitBare(n)
        // qual.method(args)
        case Term.Apply.After_4_6_0(Term.Select(Term.Name(qual), Term.Name(method)), _) =>
          hitQualified(qual, method)
        // bare `qual.method` reference (no parens) — e.g. used as a value.
        case Term.Select(Term.Name(qual), Term.Name(method)) =>
          hitQualified(qual, method)
        case _ => ()
      tree.children.foreach(walk)

    trees.foreach(walk)
    caps.toList.sorted.map(CapabilityDecl(_))

  /** Reduce a type expression to a stable, identifier-safe "head name"
   *  for use inside a synthesized witness identifier.
   *
   *  Convention (must be deterministic — same source → same name):
   *    - `Int`             → `"Int"`
   *    - `List[A]`         → `"List"`             (drop type-var arguments)
   *    - `Map[K, V]`       → `"Map"`              (head only)
   *    - `Pair[Int, Str]`  → `"Pair"`             (head only — same module's
   *                                               `given Eq[Pair[Int,Str]]`
   *                                               and `given Eq[Pair[A,B]]`
   *                                               share the witness slot
   *                                               by design; only one such
   *                                               instance is valid per
   *                                               typeclass per module)
   *    - `A.B`             → `"A_B"`              (qualified type names
   *                                               flattened with `_`)
   *    - anything weirder  → fall back to `"Any"` (kept stable across
   *                                               builds; no hashes,
   *                                               no random ids).
   */
  private def typeHeadName(t: Type): String = t match
    case Type.Name(n)              => n
    case Type.Apply.After_4_6_0(head, _) => typeHeadName(head)
    case Type.Select(qual, name)   =>
      // qual is Term.Ref — best-effort flatten to "A_B"
      s"${qual.toString.replace('.', '_')}_${name.value}"
    case Type.Project(qual, name)  => s"${typeHeadName(qual)}_${name.value}"
    case _                         => "Any"

  /** Synthesize a deterministic witness name for an anonymous `given`.
   *
   *  Examples:
   *    - `given Eq[Int] with …`     → `"given_Eq_Int"`
   *    - `given Eq[String] with …`  → `"given_Eq_String"`
   *    - `given [A] => Eq[List[A]]` → `"given_Eq_List"`  (drops type vars)
   *    - `given Show with …`        → `"given_Show"`
   *
   *  Identity is structural — same `(typeclass, type-arg head)` always
   *  produces the same witness name across builds.  No hashes, no
   *  random suffixes; two anonymous givens for the same `(Tc, T)` will
   *  collide on purpose (Scala-style: only one such instance is valid
   *  per module anyway). */
  private def synthGivenName(typeclass: String, typeArg: Option[Type]): String =
    typeArg match
      case Some(t) => s"given_${typeclass}_${typeHeadName(t)}"
      case None    => s"given_$typeclass"

  private def kindString(k: TSymbolKind): String = k match
    case TSymbolKind.Val     => "val"
    case TSymbolKind.Var     => "var"
    case TSymbolKind.Def     => "def"
    case TSymbolKind.Type    => "type"
    case TSymbolKind.Class   => "class"
    case TSymbolKind.Object  => "object"
    case TSymbolKind.Trait   => "trait"
    case TSymbolKind.Enum    => "enum"
    case TSymbolKind.Param   => "param"
    case TSymbolKind.TypeParam => "typeparam"

  private def typeEvidenceWire(evidence: TypeEvidence): TypeEvidenceWire =
    TypeEvidenceWire(
      tpe = evidence.tpe.show,
      kind = evidence.kind.toString,
      reason = evidence.reason
    )
