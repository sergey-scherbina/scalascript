package scalascript.codegen

import scalascript.ast.*
import scala.collection.mutable
import scala.meta.*

/** Dead-code elimination for generated JS.
 *
 *  Performs a conservative reachability analysis over the top-level declarations
 *  of a ScalaScript module.  Starting from entry points (@main def, manifest
 *  exports, and side-effectful top-level statements) a worklist algorithm
 *  transitively marks every referenced declaration as reachable.
 *
 *  The analysis is intentionally conservative: any name that appears anywhere
 *  in a reachable expression is kept (no control-flow sensitivity, no
 *  conditional-branch pruning).  This is the standard approach for a first-pass
 *  tree-shaker; smarter analysis is a follow-up.
 *
 *  Usage:
 *  {{{
 *    val result = TreeShaker.shake(module)
 *    // result.reachable  — Set[String] of reachable top-level names
 *    // result.total      — total number of named top-level declarations
 *  }}}
 */
object TreeShaker:

  /** Result of a tree-shaking pass.
   *
   *  @param reachable  names of top-level declarations that are reachable
   *                    from at least one entry point
   *  @param total      total count of named top-level declarations found
   *                    in the module (reachable + pruned)
   */
  case class Result(reachable: Set[String], total: Int):
    def pruned: Int  = total - reachable.size
    def kept:   Int  = reachable.size

  /** Run the worklist reachability analysis on `module`.
   *
   *  Entry points:
   *  - any `def main()` (zero-arg)
   *  - any name listed in `module.manifest.exports`
   *  - any `class` / `object` / `enum` / `given` whose name appears in exports
   *  - ALL top-level `Term` statements (side-effectful code that runs at load
   *    time — serve(...), println(...), etc.)
   */
  def shake(module: Module): Result =
    // ── Step 1: Collect all top-level declarations ──────────────────────────
    //
    // Map from declared name → the scala.meta tree whose children we scan
    // to find references when that name becomes reachable.
    val declBodies  = mutable.Map.empty[String, List[Tree]]
    // Names of all top-level declarations (used to compute `total`).
    val allDeclared = mutable.Set.empty[String]
    // Terms that run unconditionally at the top level (seeds for reachability).
    val sideEffects = mutable.ListBuffer.empty[Tree]

    def collectStats(stats: List[Stat]): Unit =
      stats.foreach {
        case d: Defn.Def =>
          val name = d.name.value
          allDeclared += name
          declBodies(name) = d.body :: Nil
        case d: Defn.Val =>
          d.pats match
            case List(Pat.Var(n)) =>
              allDeclared += n.value
              declBodies(n.value) = d.rhs :: Nil
            case _ =>
              // Multi-pattern val: treat as side effect
              sideEffects += d.rhs
        case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
          allDeclared += n.value
          declBodies(n.value) = rhs :: Nil
        case d: Defn.Var =>
          // multi-pat or unusual var: treat rhs as side effect
          d.children.collect { case t: Term => t }.foreach(sideEffects += _)
        case d: Defn.Class =>
          val name = d.name.value
          allDeclared += name
          // Constructor + all method bodies are reachable when class is reachable
          val bodies = d.templ.body.stats.collect { case dd: Defn.Def => dd.body: Tree }
          declBodies(name) = bodies
        case d: Defn.Object =>
          val name = d.name.value
          allDeclared += name
          val bodies = d.templ.body.stats.collect { case dd: Defn.Def => dd.body: Tree }
          // Include sub-vals and sub-terms in the object body as well
          val valBodies = d.templ.body.stats.collect {
            case dv: Defn.Val => dv.rhs: Tree
            case t: Term      => t: Tree
          }
          declBodies(name) = bodies ++ valBodies
        case d: Defn.Enum =>
          val name = d.name.value
          allDeclared += name
          // enum cases are included when the enum is reachable
          val caseTrees = d.templ.body.stats.collect {
            case ec: Defn.EnumCase          => ec: Tree
            case rec: Defn.RepeatedEnumCase => rec: Tree
          }
          declBodies(name) = caseTrees
          // Register EVERY case name — parametrized (`Circle`) AND parameterless
          // (`North`), including comma-form `case North, South` (RepeatedEnumCase)
          // — so a direct reference to any case makes the enclosing enum reachable.
          // Parameterless cases were previously omitted, so an enum used only via
          // bare nullary case names (e.g. `val d = North`) was pruned wholesale:
          // its `const North …` / companion never emitted, and Node failed at
          // runtime with `ReferenceError: North is not defined`.
          val caseNames = d.templ.body.stats.flatMap {
            case ec: Defn.EnumCase          => List(ec.name.value)
            case rec: Defn.RepeatedEnumCase => rec.cases.map(_.value)
            case _                          => Nil
          }
          caseNames.foreach { caseName =>
            allDeclared += caseName
            declBodies(caseName) = List(Term.Name(name))  // case reachability → enum reachability
          }
        case d: Defn.Given =>
          val explicitName = d.name.value
          val hasExtensions = d.templ.body.stats.exists(_.isInstanceOf[Defn.ExtensionGroup])
          if explicitName.nonEmpty && !hasExtensions then
            allDeclared += explicitName
            val bodies = d.templ.body.stats.collect { case dd: Defn.Def => dd.body: Tree }
            declBodies(explicitName) = bodies
            // Named givens also register in _ssc_givens (a global side-effect) — mark reachable
            // so the registration is emitted even when the name isn't directly referenced.
            sideEffects ++= bodies
          // Anonymous givens OR givens with extension groups install global _extensions state
          else
            sideEffects ++= d.templ.body.stats.collect {
              case dd: Defn.Def          => dd.body: Tree
              case eg: Defn.ExtensionGroup => eg: Tree
            }
        case d: Defn.ExtensionGroup =>
          // Extension methods: register each method as a declaration
          // whose reachability depends on the receiver type usage.
          // For simplicity (conservative): treat extension defs as side effects
          // (they install into the global _extensions table).
          d.body match
            case dd: Defn.Def =>
              sideEffects += dd.body
            case Term.Block(stmts) =>
              stmts.collect { case dd: Defn.Def => sideEffects += dd.body }
            case _ => ()
        case t: Term =>
          sideEffects += t
        case _ => ()
      }

    def scanSection(section: Section): Unit =
      section.content.foreach {
        case cb: Content.CodeBlock if Lang.isScalaScript(cb.lang) =>
          cb.tree.foreach { node =>
            ScalaNode.fold(node) {
              case Source(stats)     => collectStats(stats)
              case Term.Block(stats) => collectStats(stats)
              case t: Term           => sideEffects += t
              case _                 => ()
            }
          }
        case _ => ()
      }
      section.subsections.foreach(scanSection)

    module.sections.foreach(scanSection)

    // ── Step 2: Seed entry points ────────────────────────────────────────────
    val exportedNames: Set[String] =
      module.manifest.map(_.exports).getOrElse(Nil).toSet

    val reachable = mutable.Set.empty[String]
    val worklist  = mutable.Queue.empty[String]

    // Add a name to the worklist if it's declared and not yet visited
    def enqueue(name: String): Unit =
      if allDeclared.contains(name) && !reachable.contains(name) then
        reachable += name
        worklist.enqueue(name)

    // main() is always an entry point
    enqueue("main")

    // Manifest exports
    exportedNames.foreach(enqueue)

    // Side-effectful top-level terms: scan them for name references immediately
    // (they run unconditionally — their referenced names become reachable)
    sideEffects.foreach { t =>
      namesIn(t).foreach(enqueue)
    }

    // ── Step 3: Worklist expansion ───────────────────────────────────────────
    while worklist.nonEmpty do
      val name  = worklist.dequeue()
      val trees = declBodies.getOrElse(name, Nil)
      trees.flatMap(namesIn).foreach(enqueue)

    Result(reachable.toSet, allDeclared.size)

  // ── AST name-reference scanner ────────────────────────────────────────────
  //
  // Collect all `Term.Name` and `Type.Name` leaves from a tree.
  // This is deliberately conservative: any name mentioned in any expression
  // position counts as a reference.

  private def namesIn(tree: Tree): Set[String] =
    val acc = mutable.Set.empty[String]
    collectNames(tree, acc)
    acc.toSet

  private def collectNames(tree: Tree, acc: mutable.Set[String]): Unit =
    tree match
      case Term.Name(n)            => acc += n
      // Skip `this` and other keywords
      case _: Term.This            => ()
      case _: Term.Super           => ()
      case _: Lit                  => ()
      // For Type.Name we skip — type references don't create JS-level dependencies
      case _: Type.Name            => ()
      case _                       =>
        tree.children.foreach(collectNames(_, acc))
