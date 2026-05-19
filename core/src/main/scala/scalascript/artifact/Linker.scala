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

    // Merge all sections, rewriting cross-module VarRef nodes in CodeBlocks.
    val allSections = modules.flatMap { cm =>
      rewriteSections(cm.body.sections, symTable, cm.iface.pkg)
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

  /** Rewrite `VarRef` nodes in code-block bodies to use FQNs when the name
   *  resolves to an import from a different module (i.e. a foreign package).
   *
   *  The current IR does not yet carry full expression bodies (code blocks
   *  store source, not IrExpr trees).  When Stage 3+ effect lowering populates
   *  `CodeBlock.body` the rewriter below will operate on real IrExpr trees.
   *  For now it is a no-op on the CodeBlock.source field — source rewriting
   *  is not performed (backends re-parse from source).
   *
   *  The method is still wired in so the linker call-site is correct and can
   *  be filled in incrementally as Stage 3+ lands. */
  private def rewriteSections(
      sections:  List[Section],
      symTable:  Map[String, SymbolEntry],
      ownPkg:    List[String]
  ): List[Section] =
    sections.map { s =>
      s.copy(
        content     = s.content.map(c => rewriteContent(c, symTable, ownPkg)),
        subsections = rewriteSections(s.subsections, symTable, ownPkg)
      )
    }

  private def rewriteContent(
      content:  Content,
      symTable: Map[String, SymbolEntry],
      ownPkg:   List[String]
  ): Content = content match
    case cb: Content.CodeBlock if cb.body.nonEmpty =>
      // Rewrite IrExpr nodes in the body list (populated by Stage 3+).
      cb.copy(body = cb.body.map(e => rewriteExpr(e, symTable, ownPkg)))
    case other => other

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
    case MatchTree(scrutinee, root) =>
      MatchTree(rewriteExpr(scrutinee, symTable, ownPkg), rewriteNode(root, symTable, ownPkg))
    case other => other  // Lit, literals, PatWildcard, etc. — no sub-exprs

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
