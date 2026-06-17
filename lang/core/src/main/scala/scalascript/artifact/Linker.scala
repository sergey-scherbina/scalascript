package scalascript.artifact

import scalascript.ir.*
import scala.collection.mutable.ListBuffer

/** Links a collection of compiled module artifacts into a single
 *  `NormalizedModule` suitable for handing to a `Backend.compile`.
 *
 *  The linker performs two passes:
 *
 *  1. **Symbol table construction** — collects every exported symbol from
 *     every `ModuleInterface` and maps short names to fully-qualified
 *     mangled names (e.g. `Card` in package `org.example.ui` → `org_example_ui_Card`).
 *
 *  2. **Module merging** — concatenates the sections of all
 *     `NormalizedModule` bodies in dependency order, rewriting cross-module
 *     `VarRef` nodes to use mangled FQNs so two modules can each export
 *     `Card` without collision.
 *
 *  The result is a single `NormalizedModule` that can be passed directly to
 *  `Backend.compile` — backends see it as a single compilation unit, exactly
 *  as if the whole tree had been parsed in one pass.
 *
 *  v2.0 / Stage 5 — linker pass.
 */
object Linker:

  /** A resolved symbol entry in the link table.
   *
   *  @param shortName  The unqualified name as written in source (e.g. `Card`).
   *  @param fqn        The fully-qualified mangled name (e.g. `org_example_ui_Card`).
   *  @param sourcePkg  The package segments of the module that defines this symbol.
   */
  case class SymbolEntry(shortName: String, fqn: String, sourcePkg: List[String])

  /** A single compiled module ready for linking.
   *
   *  @param iface  The module interface (provides the symbol table).
   *  @param body   The normalised module body.
   */
  case class CompiledModule(iface: ModuleInterface, body: NormalizedModule)

  private[artifact] case class MacroExpansion(paramNames: List[String], bodySource: String)

  /** Link a list of compiled modules in dependency order (entry module last).
   *
   *  @param modules    Modules in topological dependency order.  Each module's
   *                    dependencies should appear before it in the list.
   *  @return A fully-linked `NormalizedModule` ready for backend compilation.
   *
   *  Linking strategy:
   *  - The manifest of the last module (the "entry point") is used as the
   *    manifest of the merged module.
   *  - Sections from all modules are concatenated in order.
   *  - No cross-module VarRef rewriting is done at this layer yet — the
   *    backends re-parse from source so FQN mangling in the IR is deferred
   *    to Stage 5.2.  What the linker does today is ensure the merged
   *    `NormalizedModule` contains all sections and is structurally valid.
   */
  def link(modules: List[CompiledModule]): NormalizedModule =
    if modules.isEmpty then
      return NormalizedModule(manifest = None, sections = Nil)

    // Build the global symbol table: fqn → SymbolEntry
    val symTable = buildSymbolTable(modules)

    // arch-meta-v2-p3 — Build the cross-module inline table.
    // Each entry maps the inline def's short name (as it appears in source)
    // to its (paramNames, bodySource) pair from the defining module's .scim.
    // Only defs from *foreign* modules are included; a module's own inlines
    // are already expanded by the compiler before linking.
    val inlineTable = buildInlineTable(modules)
    val macroTable = buildMacroTable(modules)

    // Merge all sections, rewriting cross-module VarRef nodes in CodeBlocks
    // and expanding cross-module inline calls in CodeBlock.source.
    val allSections = modules.flatMap { cm =>
      expandAndRewriteSections(cm.body.sections, symTable, inlineTable, macroTable, cm.iface.pkg)
    }

    // Use the last module's manifest as the entry manifest.
    val entryManifest = modules.last.body.manifest

    NormalizedModule(
      manifest = entryManifest,
      sections = allSections
    )

  /** Build a symbol table mapping `(pkg, shortName)` to FQN.
   *  Cross-module references are detected when a name from one module's
   *  export list matches a `VarRef` in another module's code blocks. */
  private def buildSymbolTable(modules: List[CompiledModule]): Map[String, SymbolEntry] =
    val table = scala.collection.mutable.Map.empty[String, SymbolEntry]
    modules.foreach { cm =>
      cm.iface.exports.foreach { sym =>
        table(sym.fqn) = SymbolEntry(sym.name, sym.fqn, cm.iface.pkg)
        // Also index by short name — used for resolving VarRefs that are
        // already in the current module's scope.
        if !table.contains(sym.name) then
          table(sym.name) = SymbolEntry(sym.name, sym.fqn, cm.iface.pkg)
      }
    }
    table.toMap

  /** arch-meta-v2-p3 — Build the cross-module inline table.
   *
   *  Collects every `inline def` export (where `isInline = true` and
   *  `inlineBodySource` is non-empty) from every module.  All modules are
   *  included; when a consumer module calls one of these, `expandInlineSource`
   *  will substitute the call with a lambda-lifted form.
   *
   *  Key = short name (as it appears at a call site, without package prefix).
   *  If two modules export an inline def with the same short name, the last
   *  one wins — the same precedence as `buildSymbolTable`. */
  private def buildInlineTable(
      modules: List[CompiledModule]
  ): Map[String, (List[String], String)] =
    val table = scala.collection.mutable.Map.empty[String, (List[String], String)]
    def collectSymbol(sym: ExportedSymbol): Unit =
      if sym.isInline then
        sym.inlineBodySource.foreach { body =>
          table(sym.name) = (sym.inlineParamNames, body)
        }
      sym.nested.foreach(collectSymbol)
    modules.foreach { cm =>
      cm.iface.exports.foreach(collectSymbol)
    }
    table.toMap

  /** arch-meta-v2-p4 — Build the restricted quoted-macro expansion table.
   *
   *  The table is intentionally source-shaped for the first implementation:
   *  an inline macro entrypoint maps to a quoted body expression and the
   *  parameter names that may appear as `$param` splices in that body.
   *  Backends never see the macro helper calls; expansion happens in Linker. */
  private def buildMacroTable(modules: List[CompiledModule]): Map[String, MacroExpansion] =
    val table = scala.collection.mutable.Map.empty[String, MacroExpansion]
    def collectSymbol(sym: ExportedSymbol): Unit =
      sym.macroImpl.foreach { ref =>
        ref.expansionBodySource.foreach { body =>
          val params =
            if ref.quotedParams.nonEmpty then ref.quotedParams
            else sym.inlineParamNames
          table(sym.name) = MacroExpansion(params, body)
        }
      }
      sym.nested.foreach(collectSymbol)
    modules.foreach(_.iface.exports.foreach(collectSymbol))
    table.toMap

  /** arch-meta-v2-p3 — Expand cross-module inline calls in `CodeBlock.source`
   *  and rewrite `VarRef` nodes in code-block bodies to use FQNs when the
   *  name resolves to an import from a different module.
   *
   *  Source-level expansion uses a lambda-lifting strategy:
   *    `f(arg1, arg2)` → `((p1, p2) => body)(arg1, arg2)`
   *  This is hygienic: no capture of surrounding names, no alpha-renaming
   *  needed.  The ScalaScript compiler in the backend re-parses the expanded
   *  source and beta-reduces the immediately-applied lambda as part of normal
   *  compilation.
   *
   *  For code blocks that also have a populated `body: List[IrExpr]` (Stage
   *  3+ effect lowering), the VarRef rewriter runs on the IrExpr tree as
   *  before. */
  private def expandAndRewriteSections(
      sections:    List[Section],
      symTable:    Map[String, SymbolEntry],
      inlineTable: Map[String, (List[String], String)],
      macroTable:  Map[String, MacroExpansion],
      ownPkg:      List[String]
  ): List[Section] =
    sections.map { s =>
      s.copy(
        content     = s.content.map(c => expandAndRewriteContent(c, symTable, inlineTable, macroTable, ownPkg)),
        subsections = expandAndRewriteSections(s.subsections, symTable, inlineTable, macroTable, ownPkg)
      )
    }

  private def expandAndRewriteContent(
      content:     Content,
      symTable:    Map[String, SymbolEntry],
      inlineTable: Map[String, (List[String], String)],
      macroTable:  Map[String, MacroExpansion],
      ownPkg:      List[String]
  ): Content = content match
    case cb: Content.CodeBlock =>
      val macroExpandedSrc =
        if macroTable.nonEmpty then expandMacroSource(cb.source, macroTable)
        else cb.source
      val expandedSrc =
        if inlineTable.nonEmpty then expandInlineSource(macroExpandedSrc, inlineTable)
        else macroExpandedSrc
      val rewrittenBody =
        if cb.body.nonEmpty then cb.body.map(e => rewriteExpr(e, symTable, ownPkg))
        else cb.body
      if (expandedSrc eq cb.source) && (rewrittenBody eq cb.body) then cb
      else cb.copy(source = expandedSrc, body = rewrittenBody)
    case other => other

  /** arch-meta-v2-p3 — Expand cross-module `inline def` call sites in source text.
   *
   *  For each entry in `inlineTable` (name → (paramNames, bodySource)),
   *  finds call sites of the form `name(arg1, arg2, ...)` in `src` and
   *  replaces them with `((p1, p2) => body)(arg1, arg2, ...)`.
   *
   *  The scanner is careful about:
   *  - Word boundaries: only matches `name(` where `name` is not preceded by
   *    an identifier character (avoids matching `methodName` inside `fullName`).
   *  - Nested parentheses: uses a counter so `f(g(x), y)` is parsed as a
   *    single call with two args `g(x)` and `y`.
   *  - String literals: skips `"..."` and `'...'` so a name appearing inside
   *    a string is not treated as a call site.
   *
   *  No-arg inlines (`params = Nil`) are expanded to `(body)` without
   *  the lambda wrapper.  Multi-clause inlines are never stored in the table
   *  (filtered in `InterfaceExtractor.extractInlineInfo`). */
  private[artifact] def expandInlineSource(
      src:         String,
      inlineTable: Map[String, (List[String], String)]
  ): String =
    if inlineTable.isEmpty || src.isEmpty then return src

    val sb       = new java.lang.StringBuilder(src.length + 64)
    val len      = src.length
    var i        = 0
    var modified = false

    def isIdentChar(c: Char): Boolean =
      c.isLetterOrDigit || c == '_' || c == '$'

    // Skip a string literal starting at position j (src(j) == '"' or '\'').
    // Returns the index after the closing delimiter.
    def skipStringLit(j: Int): Int =
      val delim = src(j)
      var k = j + 1
      while k < len && src(k) != delim do
        if src(k) == '\\' then k += 1  // skip escaped char
        k += 1
      if k < len then k + 1 else k     // skip closing delimiter

    // Extract comma-separated call arguments from `src` starting just
    // after the opening '(' (i.e. `from` is the index of the first char
    // inside the parens).  Returns `Some((args, endPos))` where `endPos`
    // is the index of the matching ')'.
    def extractArgs(from: Int): Option[(List[String], Int)] =
      var depth   = 1
      var k       = from
      val argBuf  = new java.lang.StringBuilder
      val args    = scala.collection.mutable.ListBuffer.empty[String]
      // Bracket stacks for `[...]` and `{...}` — treated as depth-neutral
      // relative to the outer `(...)` counter so that `f(xs.map { _ + 1 })`
      // doesn't prematurely close the arg list on `}`.
      var squareDepth = 0
      var curlyDepth  = 0
      while k < len && depth > 0 do
        src(k) match
          case '"' | '\'' =>
            val end = skipStringLit(k)
            argBuf.append(src, k, end)
            k = end
          case '(' =>
            depth += 1
            argBuf.append(src(k))
            k += 1
          case ')' =>
            depth -= 1
            if depth > 0 then argBuf.append(src(k))
            k += 1
          case '[' =>
            squareDepth += 1
            argBuf.append(src(k))
            k += 1
          case ']' =>
            if squareDepth > 0 then squareDepth -= 1
            argBuf.append(src(k))
            k += 1
          case '{' =>
            curlyDepth += 1
            argBuf.append(src(k))
            k += 1
          case '}' =>
            if curlyDepth > 0 then curlyDepth -= 1
            argBuf.append(src(k))
            k += 1
          case ',' if depth == 1 && squareDepth == 0 && curlyDepth == 0 =>
            args += argBuf.toString.trim
            argBuf.setLength(0)
            k += 1
          case c =>
            argBuf.append(c)
            k += 1
      if depth != 0 then None
      else
        val last = argBuf.toString.trim
        val allArgs = if last.isEmpty && args.isEmpty then Nil
                      else (args += last).toList
        Some(allArgs -> (k - 1))   // k - 1 is the index of the closing ')'

    while i < len do
      src(i) match
        // Skip string literals to avoid false call-site detection inside strings.
        case '"' | '\'' =>
          val end = skipStringLit(i)
          sb.append(src, i, end)
          i = end
        case c =>
          // Check if any inline name starts at position i with a word boundary.
          if isIdentChar(c) && (i == 0 || !isIdentChar(src(i - 1))) then
            val matched = inlineTable.find { case (name, _) =>
              src.startsWith(name, i) &&
                (i + name.length >= len || !isIdentChar(src(i + name.length)))
            }
            matched match
              case Some((name, (params, body))) =>
                // Advance past the name; skip any whitespace; check for '('
                val afterName = i + name.length
                var j = afterName
                while j < len && src(j).isWhitespace do j += 1
                if j < len && src(j) == '(' then
                  extractArgs(j + 1) match
                    case Some((args, endPos)) =>
                      modified = true
                      if params.isEmpty then
                        sb.append('(').append(body).append(')')
                      else
                        sb.append("((")
                        sb.append(params.mkString(", "))
                        sb.append(") => ")
                        sb.append(body)
                        sb.append(")(")
                        sb.append(args.mkString(", "))
                        sb.append(')')
                      i = endPos + 1
                    case None =>
                      // Unmatched parens — emit as-is and move forward 1 char.
                      sb.append(src(i))
                      i += 1
                else
                  // Name not followed by '(' — not a call site.
                  sb.append(src(i))
                  i += 1
              case None =>
                sb.append(src(i))
                i += 1
          else
            sb.append(src(i))
            i += 1

    if modified then sb.toString else src

  /** arch-meta-v2-p4 — Expand restricted quoted-macro calls in source text.
   *
   *  The supported first slice is intentionally small and deterministic:
   *  quoted macro bodies are expression templates (`'{ $x + 1 }` or the
   *  parser-helper form `__ssc_quote_expr__(__ssc_splice__("x", x).+(1))`).
   *  A call `macroName(arg)` substitutes `$x` / `__ssc_splice__("x", x)` with
   *  the original call-site argument source and wraps the result in parens. */
  private[artifact] def expandMacroSource(
      src:        String,
      macroTable: Map[String, MacroExpansion]
  ): String =
    if macroTable.isEmpty || src.isEmpty then return src
    // arch-meta-v2 Track B — split the table: `Expr.asValue match` macros are
    // const-folded per call site (literal arg → `Some` branch, else the `None`
    // direct-quote fallback); every other macro is a uniform direct-quote
    // body expanded by lambda-lifting through `expandInlineSource`.
    val foldTable =
      macroTable.flatMap { case (name, m) => parseAsValueFold(m.bodySource).map(name -> _) }
    val afterQuotes =
      val quoteTable = macroTable.filterNot { case (name, _) => foldTable.contains(name) }
      if quoteTable.isEmpty then src
      else
        val inlineShape = quoteTable.view.mapValues(m => (m.paramNames, normalizeQuotedMacroBody(m.bodySource))).toMap
        expandInlineSource(src, inlineShape)
    if foldTable.isEmpty then afterQuotes
    else expandAsValueMatchSource(afterQuotes, foldTable)

  /** arch-meta-v2 Track B — a parsed `Expr.asValue match` macro body, ready for
   *  per-call-site const folding. `param` is the quoted parameter, `binder` the
   *  name bound by the `Some(_)` pattern; `someBranch` / `noneBranch` are the
   *  unwrapped branch expressions (splice markers already normalised). */
  private[artifact] case class AsValueFold(
      param:      String,
      binder:     String,
      someBranch: String,
      noneBranch: String
  )

  /** Parse a restricted `Expr.asValue match` macro body into its fold pieces.
   *  Recognised shape (after parser preprocessing):
   *    `<param>.asValue match { case Some(<binder>) => <some> case None => <none> }`
   *  (`case _ =>` is accepted in place of `case None =>`). Returns `None` for
   *  any other body — those route through the direct-quote path instead. */
  private[artifact] def parseAsValueFold(bodySource: String): Option[AsValueFold] =
    if !bodySource.contains(".asValue") then return None
    import scala.meta.*
    given Dialect = dialects.Scala3
    bodySource.trim.parse[Term].toOption.flatMap {
      case m: Term.Match =>
        m.expr match
          case Term.Select(Term.Name(param), Term.Name("asValue")) =>
            val cases    = m.casesBlock.cases
            val someCase = cases.find(_.pat.syntax.trim.startsWith("Some("))
            val noneCase = cases.find { c => val p = c.pat.syntax.trim; p == "None" || p == "_" }
            for
              sc     <- someCase
              binder <- """Some\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)""".r
                          .findFirstMatchIn(sc.pat.syntax).map(_.group(1))
              nc     <- noneCase
            yield AsValueFold(param, binder, unwrapMacroBranch(sc.body), unwrapMacroBranch(nc.body))
          case _ => None
      case _ => None
    }

  /** Unwrap a single macro branch to the source expression the `${ }` splice
   *  yields: `Expr(e)` → `e`, a direct quote → its normalised inner expression,
   *  anything else → verbatim source. */
  private[artifact] def unwrapMacroBranch(body: scala.meta.Term): String =
    import scala.meta.*
    body match
      case Term.Apply.After_4_6_0(Term.Name("Expr"), Term.ArgClause(List(arg), _)) =>
        arg.syntax.trim
      case Term.Apply.After_4_6_0(Term.Name("__ssc_quote_expr__"), Term.ArgClause(List(arg), _)) =>
        normalizeQuotedMacroBody(s"__ssc_quote_expr__(${arg.syntax})")
      case _ =>
        val s = body.syntax.trim
        if (s.startsWith("'{") && s.endsWith("}")) ||
           (s.startsWith("__ssc_quote_expr__(") && s.endsWith(")")) then
          normalizeQuotedMacroBody(s)
        else s

  /** True when `arg` is a compile-time constant (a literal, or a unary-negated
   *  literal). Only such arguments take the const-folded `Some` branch. */
  private[artifact] def isLiteralArg(arg: String): Boolean =
    import scala.meta.*
    given Dialect = dialects.Scala3
    arg.trim.parse[Term].toOption.exists {
      case _: Lit                       => true
      case Term.ApplyUnary(_, _: Lit)   => true
      case _                            => false
    }

  /** arch-meta-v2 Track B — Const-fold `Expr.asValue match` macro call sites.
   *  A call `name(arg)` is rewritten by lambda-lifting the selected branch:
   *  literal `arg` → `((binder) => someBranch)(arg)`; otherwise →
   *  `((param) => noneBranch)(arg)`. The backend beta-reduces the result.
   *  These macros take a single quoted argument. */
  private[artifact] def expandAsValueMatchSource(
      src:       String,
      foldTable: Map[String, AsValueFold]
  ): String =
    if foldTable.isEmpty || src.isEmpty then return src
    val sb       = new java.lang.StringBuilder(src.length + 64)
    val len      = src.length
    var i        = 0
    var modified = false

    def isIdentChar(c: Char): Boolean = c.isLetterOrDigit || c == '_' || c == '$'

    def skipStringLit(j: Int): Int =
      val delim = src(j)
      var k = j + 1
      while k < len && src(k) != delim do
        if src(k) == '\\' then k += 1
        k += 1
      if k < len then k + 1 else k

    // Extract the single argument between parens (from = index just inside '(').
    // Returns (argSource, indexOfMatchingCloseParen).
    def extractSingleArg(from: Int): Option[(String, Int)] =
      var depth = 1
      var k     = from
      val buf   = new java.lang.StringBuilder
      while k < len && depth > 0 do
        src(k) match
          case '"' | '\'' =>
            val end = skipStringLit(k); buf.append(src, k, end); k = end
          case '(' => depth += 1; buf.append('('); k += 1
          case ')' => depth -= 1; if depth > 0 then buf.append(')'); k += 1
          case c   => buf.append(c); k += 1
      if depth != 0 then None else Some(buf.toString.trim -> (k - 1))

    while i < len do
      src(i) match
        case '"' | '\'' =>
          val end = skipStringLit(i); sb.append(src, i, end); i = end
        case c =>
          if isIdentChar(c) && (i == 0 || !isIdentChar(src(i - 1))) then
            foldTable.find { case (name, _) =>
              src.startsWith(name, i) &&
                (i + name.length >= len || !isIdentChar(src(i + name.length)))
            } match
              case Some((name, fold)) =>
                var j = i + name.length
                while j < len && src(j).isWhitespace do j += 1
                if j < len && src(j) == '(' then
                  extractSingleArg(j + 1) match
                    case Some((arg, endPos)) =>
                      modified = true
                      val (bindVar, body) =
                        if isLiteralArg(arg) then (fold.binder, fold.someBranch)
                        else (fold.param, fold.noneBranch)
                      sb.append("((").append(bindVar).append(") => ")
                        .append(body).append(")(").append(arg).append(')')
                      i = endPos + 1
                    case None => sb.append(src(i)); i += 1
                else
                  sb.append(src(i)); i += 1
              case None =>
                sb.append(src(i)); i += 1
          else
            sb.append(src(i)); i += 1

    if modified then sb.toString else src

  private[artifact] def normalizeQuotedMacroBody(bodySource: String): String =
    val body = bodySource.trim
    val raw =
      if body.startsWith("'{") && body.endsWith("}") then
        body.substring(2, body.length - 1).trim
      else if body.startsWith("__ssc_quote_expr__(") && body.endsWith(")") then
        body.substring("__ssc_quote_expr__(".length, body.length - 1).trim
      else
        throw new IllegalArgumentException(unsupportedQuotedMacroBodyMessage(body))
    val helperFree = """__ssc_splice__\("([^"]+)"(?:\s*,[^)]*)?\)""".r.replaceAllIn(raw, m => m.group(1))
    """\$([A-Za-z_][A-Za-z0-9_]*)""".r.replaceAllIn(helperFree, m => m.group(1))

  private[artifact] def unsupportedQuotedMacroBodyMessage(bodySource: String): String =
    val body = bodySource.trim
    val base =
      "quoted macro error: unsupported macro body; restricted quoted macros must return a direct quoted expression, e.g. '{ $x + 1 }"
    val hint =
      if body.contains(".asValue match") || body.contains(".asValue.match") then
        "compile-time `Expr.asValue match` is const-folded only for the shape `x.asValue match { case Some(n) => ... case None => ... }` (a literal argument takes the `Some` branch); this body's shape is not recognised."
      else if body.startsWith("Expr(") || body.contains(" Expr(") || body.contains(".Expr(") then
        "`Expr(...)` construction is not link-expanded yet; use direct quote syntax `'{ ... }` in restricted quoted macros."
      else if body.contains("'{") || body.contains("__ssc_quote_expr__(") then
        "nested or non-top-level quoted expressions are not supported yet; the whole implementation body must be the direct quote."
      else if body.contains("$") || body.contains("__ssc_splice__(") then
        "splices are only supported inside a direct quoted expression body."
      else
        "supported body shape is exactly a direct quoted expression."
    s"$base ($hint)"

  private def rewriteExpr(
      expr:     IrExpr,
      symTable: Map[String, SymbolEntry],
      ownPkg:   List[String]
  ): IrExpr = expr match
    case VarRef(name) =>
      // If the name resolves to a symbol from a *foreign* module (different pkg),
      // rewrite to its FQN.  If it's in the same module, leave as-is.
      symTable.get(name) match
        case Some(entry) if entry.sourcePkg != ownPkg =>
          VarRef(entry.fqn)  // rewrite to mangled FQN
        case _ => expr
    case Call(target, args) =>
      Call(target, args.map(a => rewriteExpr(a, symTable, ownPkg)))
    case Perform(eff, op, args) =>
      Perform(eff, op, args.map(a => rewriteExpr(a, symTable, ownPkg)))
    case Handle(body, cases, ret) =>
      Handle(
        body  = rewriteExpr(body, symTable, ownPkg),
        cases = cases.map(c => c.copy(body = rewriteExpr(c.body, symTable, ownPkg))),
        ret   = ret.copy(body = rewriteExpr(ret.body, symTable, ownPkg))
      )
    case Resume(k, value) =>
      Resume(k, rewriteExpr(value, symTable, ownPkg))
    case TailCall(target, args) =>
      TailCall(target, args.map(a => rewriteExpr(a, symTable, ownPkg)))
    case ExternCall(name, args, span) =>
      ExternCall(name, args.map(a => rewriteExpr(a, symTable, ownPkg)), span)
    case MacroImpl(name, args, resultType, span) =>
      MacroImpl(name, args.map(a => rewriteExpr(a, symTable, ownPkg)), resultType, span)
    case MatchTree(scrutinee, root) =>
      MatchTree(rewriteExpr(scrutinee, symTable, ownPkg), rewriteNode(root, symTable, ownPkg))
    case Apply(fn, args) =>
      Apply(rewriteExpr(fn, symTable, ownPkg), args.map(a => rewriteExpr(a, symTable, ownPkg)))
    case Select(qual, name) =>
      // If the Select forms a package-qualified reference like `a.bar` or
      // `std.dsl.foo` and the joined path matches a foreign export's FQN,
      // collapse the entire chain into a single `VarRef(fqn)`.
      // Otherwise just recurse into qual.
      def qualifierPath(e: IrExpr): Option[List[String]] = e match
        case VarRef(n)        => Some(List(n))
        case Select(q2, n2)   => qualifierPath(q2).map(_ :+ n2)
        case _                => None
      qualifierPath(qual) match
        case Some(prefix) =>
          val candidateFqn = (prefix :+ name).mkString("_")
          symTable.get(candidateFqn) match
            case Some(entry) if entry.sourcePkg != ownPkg =>
              VarRef(entry.fqn)
            case _ =>
              Select(rewriteExpr(qual, symTable, ownPkg), name)
        case None =>
          Select(rewriteExpr(qual, symTable, ownPkg), name)
    case Lambda(params, body) =>
      // Lambda parameters shadow module-level names; don't rewrite a name
      // that's bound as a parameter of this lambda.
      val shadowed = params.toSet
      val newTable = if shadowed.isEmpty then symTable else symTable -- shadowed
      Lambda(params, rewriteExpr(body, newTable, ownPkg))
    case If(cond, thenp, elsep) =>
      If(
        rewriteExpr(cond, symTable, ownPkg),
        rewriteExpr(thenp, symTable, ownPkg),
        elsep.map(e => rewriteExpr(e, symTable, ownPkg))
      )
    case Block(stmts) =>
      Block(stmts.map(e => rewriteExpr(e, symTable, ownPkg)))
    case other => other  // Lit, Unsupported, literals — no sub-exprs to rewrite

  private def rewriteNode(
      node:     DecisionNode,
      symTable: Map[String, SymbolEntry],
      ownPkg:   List[String]
  ): DecisionNode = node match
    case Switch(cases, default) =>
      Switch(
        cases   = cases.map((p, n) => p -> rewriteNode(n, symTable, ownPkg)),
        default = default.map(d => rewriteNode(d, symTable, ownPkg))
      )
    case Leaf(action) =>
      Leaf(rewriteExpr(action, symTable, ownPkg))

  // ─── Symbol mangling ────────────────────────────────────────────────────

  /** Compute the mangled FQN for a symbol given its package segments and name.
   *
   *  Mangling convention: segments joined by `_` (underscore).
   *  Example: `org.example.ui` + `Card` → `org_example_ui_Card`.
   *
   *  This matches the `fqn` field in `ExportedSymbol` produced by
   *  `InterfaceExtractor.extract`. */
  def mangle(pkg: List[String], name: String): String =
    if pkg.isEmpty then name
    else (pkg :+ name).mkString("_")

  /** Collect all cross-module name collisions in a set of modules.
   *  Two modules have a collision when they export the same short name.
   *  Returns a list of `(shortName, pkgsExportingIt)` pairs. */
  def detectCollisions(modules: List[CompiledModule]): List[(String, List[List[String]])] =
    val byShortName = scala.collection.mutable.Map.empty[String, ListBuffer[List[String]]]
    modules.foreach { cm =>
      cm.iface.exports.foreach { sym =>
        byShortName.getOrElseUpdate(sym.name, ListBuffer.empty) += cm.iface.pkg
      }
    }
    byShortName.toList
      .filter { case (_, pkgs) => pkgs.size > 1 }
      .map { case (name, pkgs) => (name, pkgs.toList) }
      .sortBy(_._1)
