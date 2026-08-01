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
    // Top-level `val`/`var` names whose INITIALISER may have effects. Dropping an unreferenced
    // binding also drops its initialiser, and that is only sound when the initialiser is pure:
    // `val unused = eff()` made the call — and its `println` — vanish from the bundle
    // (`js-unused-val-drops-side-effecting-call`). Forcing the NAME reachable, rather than only
    // scanning the rhs for references, is what keeps the statement itself: the emitter's
    // `isReachableStat` filters on the name, so seeding the rhs alone would have kept `eff`
    // defined and still elided the call that runs it.
    val effectfulBindings = mutable.ListBuffer.empty[String]

    // JsGen emits a `_sscMirror_<T>` for EVERY product class of a module that mentions `Mirror`,
    // and that mirror's `fromProduct` closure calls the constructor `T(...)`. The shaker cannot see
    // that reference: it is created by the emitter, not present in the AST, and the only mentions of
    // `T` in source are type positions (`summon[Mirror.Of[T]]`), which `collectNames` skips by
    // design. So `T` was pruned and the bundle died with `ReferenceError: T is not defined` the
    // moment `fromProduct` was called — sibling of the `derives` root below, one step over.
    // Gated on the module actually mentioning `Mirror`, mirroring JsGen's own `moduleUsesMirror`, so
    // a module that never uses reflection keeps shaking exactly as before.
    var usesMirror = false
    val productClasses = mutable.ListBuffer.empty[String]

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
              if !isTriviallyPure(d.rhs) then effectfulBindings += n.value
            case _ =>
              // Multi-pattern val: treat as side effect
              sideEffects += d.rhs
        case Defn.Var.After_4_7_2(_, List(Pat.Var(n)), _, rhs) =>
          allDeclared += n.value
          declBodies(n.value) = rhs :: Nil
          if !isTriviallyPure(rhs) then effectfulBindings += n.value
        case d: Defn.Var =>
          // multi-pat or unusual var: treat rhs as side effect
          d.children.collect { case t: Term => t }.foreach(sideEffects += _)
        case d: Defn.Class =>
          val name = d.name.value
          allDeclared += name
          if d.mods.exists(_.isInstanceOf[Mod.Case]) then productClasses += name
          // Constructor + all method bodies are reachable when class is reachable
          val bodies = d.templ.body.stats.collect { case dd: Defn.Def => dd.body: Tree }
          declBodies(name) = bodies
          // `case class T(…) derives TC` — JsGen unconditionally emits a Mirror for T and
          // `_ssc_def_given("TC_T", () => TC.derived(_sscMirror_T))`, a GLOBAL side effect, exactly
          // like the named-given registration handled below. Neither T nor TC need appear in any
          // TERM position for that to be emitted: the only mentions are the `derives` clause and
          // `summon[TC[T]]`, and `collectNames` deliberately skips `Type.Name` ("type references
          // don't create JS-level dependencies") — which is true everywhere EXCEPT here. So the
          // shaker pruned both, and the emitted registration referenced two names that no longer
          // existed: `ReferenceError: TC is not defined` (BUGS
          // `js-lane-missing-derives-and-coroutinecancel`). Seed them as side effects rather than
          // un-skipping Type.Name, which would keep nearly everything.
          // Stdlib structural derives (`derives Eq`) name nothing declared, so `enqueue` ignores
          // them — this can only ever KEEP code that JsGen is about to reference.
          if d.templ.derives.nonEmpty then
            sideEffects += Term.Name(name)
            d.templ.derives.foreach {
              case Type.Name(tc) => sideEffects += Term.Name(tc)
              case _             => ()
            }
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
        case cb: Content.CodeBlock if cb.isProgramCode && cb.source.contains("Mirror") =>
          usesMirror = true
          cb.tree.foreach { node =>
            ScalaNode.fold(node) {
              case Source(stats)     => collectStats(stats); ()
              case Term.Block(stats) => collectStats(stats); ()
              case t: Term           => sideEffects += t; ()
              case _                 => ()
            }
          }
        case cb: Content.CodeBlock if cb.isProgramCode =>
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

    // Product classes of a Mirror-using module — see the note above scanSection.
    if usesMirror then productClasses.foreach(enqueue)

    // Side-effectful top-level terms: scan them for name references immediately
    // (they run unconditionally — their referenced names become reachable)
    sideEffects.foreach { t =>
      namesIn(t).foreach(enqueue)
    }

    // Bindings whose initialiser may have effects are roots: the initialiser runs
    // unconditionally at module load whether or not anything reads the name.
    effectfulBindings.foreach(enqueue)

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

  /** Deliberately tiny and conservative: only shapes that provably cannot run user code count as
   *  pure. Everything else — any call, selection, `new`, block, `if` — is assumed effectful and its
   *  binding is kept. Being wrong in this direction costs bundle bytes; being wrong the other way
   *  deletes a side effect, which is what `js-unused-val-drops-side-effecting-call` was. A
   *  selection is NOT pure here: `O.x` can be a parameterless `def`. */
  private def isTriviallyPure(t: Term): Boolean = t match
    case _: Lit           => true
    case _: Term.Name     => true
    case Term.Tuple(args) => args.forall(isTriviallyPure)
    case _                => false

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
      // A constructor's type name IS a JS-level dependency, unlike the annotations skipped below:
      // `new C(args)` and `new C[T](args)` both compile to a CALL of the emitted factory `C(…)`.
      // Skipping it let the declaration be pruned and the bundle died at run time with
      // `C is not defined` — an emitter-synthesized reference invisible to this scanner, which is a
      // shape this backend has been bitten by before. The type ARGUMENTS stay skipped: `[Int]` is
      // erased and really does not reach the output.
      case init: Init              =>
        ctorNameOf(init.tpe).foreach(acc += _)
        init.argClauses.foreach(collectNames(_, acc))
      // For Type.Name we skip — type references don't create JS-level dependencies
      case _: Type.Name            => ()
      case _                       =>
        tree.children.foreach(collectNames(_, acc))

  /** Head class name of a constructor type, with type arguments peeled: `C` and `C[T]` alike. */
  private def ctorNameOf(t: Type): Option[String] = t match
    case Type.Name(n)   => Some(n)
    case ta: Type.Apply => ctorNameOf(ta.tpe)
    case _              => None
