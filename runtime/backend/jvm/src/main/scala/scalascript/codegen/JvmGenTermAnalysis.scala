package scalascript.codegen

import scalascript.ast.*
import scalascript.transform.EffectAnalysis
import scala.meta.*

/** Block- and term-level predicates that decide whether a node needs custom
 *  emission (effects, mutual-TCO, direct blocks, registered interpolators,
 *  intrinsics, optics). Lifted out of JvmGen to slim the generator. Mixed in
 *  via a self type because a few predicates delegate to effect / clique /
 *  optic helpers that read generator state. */
private[codegen] trait JvmGenTermAnalysis:
  self: JvmGen =>

  private[codegen] def blockNeedsRewrite(node: ScalaNode): Boolean =
    blockUsesEffects(node)                    ||
    blockUsesMutualTco(node)                  ||
    blockHasAutoOutputTerm(node)              ||
    blockUsesIntrinsics(node)                 ||
    blockContainsExternDef(node)              ||
    blockContainsDirectBlock(node)            ||
    blockContainsRegisteredInterpolator(node) ||
    blockContainsModelAnnotation(node)        ||
    blockContainsNamedArgAscription(node)     ||
    blockContainsBareNameBlockCall(node)

  private[codegen] def blockContainsBareNameBlockCall(node: ScalaNode): Boolean =
    def go(t: scala.meta.Tree): Boolean = t match
      case app: Term.Apply =>
        (app.fun.isInstanceOf[Term.Name] &&
         app.argClause.values.size == 1 &&
         app.argClause.values.head.isInstanceOf[Term.Block]) ||
        app.children.exists(go)
      case other => other.children.exists(go)
    ScalaNode.fold(node)(go)

  private[codegen] def blockContainsModelAnnotation(node: ScalaNode): Boolean =
    def go(t: scala.meta.Tree): Boolean = t match
      case d: Defn.Class => d.mods.exists {
        case Mod.Annot(init) => init.tpe match
          case Type.Name(n) => n == "model"
          case Type.Select(_, Type.Name(n)) => n == "model"
          case _ => false
        case _ => false
      }
      case other => other.children.exists(go)
    ScalaNode.fold(node)(go)

  /** True if any Term.Apply has an argument of the form `name: value` (SSC
   *  named-arg syntax). Forces the block through emitStats so emitExprDeep
   *  can convert `name: value` → `name = value`. */
  private[codegen] def blockContainsNamedArgAscription(node: ScalaNode): Boolean =
    def go(t: scala.meta.Tree): Boolean = t match
      case app: Term.Apply =>
        app.argClause.values.exists {
          case Term.Ascribe(Term.Name(_), _) => true
          case _                             => false
        } || app.children.exists(go)
      case other => other.children.exists(go)
    ScalaNode.fold(node)(go)

  private[codegen] def termContainsNamedArgAscription(t: Term): Boolean =
    def go(n: scala.meta.Tree): Boolean = n match
      case app: Term.Apply =>
        app.argClause.values.exists {
          case Term.Ascribe(Term.Name(_), _) => true
          case _                             => false
        } || app.children.exists(go)
      case other => other.children.exists(go)
    go(t)

  /** True when any `Term.Apply(Term.Name(_), [Term.Block])` appears — i.e.
   *  a bare-name widget call like `Row { ... }` or `ScrollView { ... }`.
   *  Forces emission through emitExprDeep so the `f() { block }` rewrite
   *  fires (otherwise the block is passed to the first positional slot). */
  private[codegen] def termContainsBareNameBlockCall(t: Term): Boolean =
    def go(n: scala.meta.Tree): Boolean = n match
      case app: Term.Apply =>
        (app.fun.isInstanceOf[Term.Name] &&
         app.argClause.values.size == 1 &&
         app.argClause.values.head.isInstanceOf[Term.Block]) ||
        app.children.exists(go)
      case other => other.children.exists(go)
    go(t)

  // Interpolator prefixes that JvmGen handles natively via other code paths
  // (emitCpsExpr top case, emitExpr top case, or raw Scala emission).
  // Only non-built-in registered interpolators need the rewrite trigger.
  private[codegen] val jvmNativeInterpolators = Set("s", "f", "md", "sx", "html", "css")

  private[codegen] def blockContainsRegisteredInterpolator(node: ScalaNode): Boolean =
    def go(t: scala.meta.Tree): Boolean = t match
      case Term.Interpolate(Term.Name(prefix), _, _)
          if !jvmNativeInterpolators.contains(prefix) =>
        scalascript.compiler.plugin.InterpolatorRegistry.lookup(prefix).isDefined
      case other => other.children.exists(go)
    ScalaNode.fold(node)(go)

  /** v1.8 — force any block containing a direct[M] { ... } expression through
   *  emitStats so emitDirectBlock rewrites it to .flatMap chains (and so the
   *  Phase 5 static checks fire). */
  private[codegen] def blockContainsDirectBlock(node: ScalaNode): Boolean =
    def go(t: scala.meta.Tree): Boolean = t match
      case app: Term.Apply =>
        app.fun match
          case Term.ApplyType.After_4_6_0(Term.Name("direct"), _) => true
          case _ => app.children.exists(go)
      case other => other.children.exists(go)
    ScalaNode.fold(node)(go)

  /** Stage 5+/A.6 (Б-1) — force blocks that declare an `extern def`
   *  through `emitStats` so the extern stub gets filtered out (the
   *  intrinsic table provides the real impl).  Without this the
   *  `__extern__` body marker would emit verbatim and scala-cli
   *  would fail with "Not found: __extern__". */
  private[codegen] def blockContainsExternDef(node: ScalaNode): Boolean =
    def go(t: scala.meta.Tree): Boolean = t match
      case d: Defn.Def if EffectAnalysis.isExternDef(d.body) => true
      case other => other.children.exists(go)
    ScalaNode.fold(node)(go)

  /** Stage 5+/A.4 — force blocks that call a registered intrinsic
   *  through `emitExpr` so per-call-site dispatch fires.  Without
   *  this, simple `val t = nowMillis()` would passthrough as raw
   *  Scala and `nowMillis` would be unresolved (the Stage 5+/A.3
   *  prelude alias is gone). */
  private[codegen] def blockUsesIntrinsics(node: ScalaNode): Boolean =
    if intrinsics.isEmpty then false
    else
      val names = intrinsics.keySet.map(_.value)
      def go(t: scala.meta.Tree): Boolean = t match
        case Term.Apply.After_4_6_0(Term.Name(n), _) if names(n) => true
        case other => other.children.exists(go)
      ScalaNode.fold(node)(go)

  /** True if the top-level node ends with a bare expression — that's the
   *  trigger to take the walking emit path and inject the auto-output wrap. */
  private[codegen] def blockHasAutoOutputTerm(node: ScalaNode): Boolean =
    ScalaNode.fold(node) {
      case Source(stats)     => stats.lastOption.exists(_.isInstanceOf[Term])
      case Term.Block(stats) => stats.lastOption.exists(_.isInstanceOf[Term])
      case _: Term           => true
      case _                 => false
    }

  private[codegen] def blockUsesMutualTco(node: ScalaNode): Boolean =
    var found = false
    ScalaNode.fold(node) {
      case Source(stats)     => if statsUseMutualTco(stats) then found = true
      case Term.Block(stats) => if statsUseMutualTco(stats) then found = true
      case _                 => ()
    }
    found

  private[codegen] def statsUseMutualTco(stats: List[Stat]): Boolean =
    stats.exists {
      case d: Defn.Def => isInMutualClique(d.name.value)
      case _           => false
    }

  /** True if any effect declaration, handle call, effectful function defn, or
   *  effect-op reference appears within `node`. */
  private[codegen] def blockUsesEffects(node: ScalaNode): Boolean =
    var found = false
    ScalaNode.fold(node) {
      case Source(stats)     => if statsUseEffects(stats) then found = true
      case Term.Block(stats) => if statsUseEffects(stats) then found = true
      case t: Term           => if termUsesEffects(t)     then found = true
      case _                 => ()
    }
    found

  private[codegen] def statsUseEffects(stats: List[Stat]): Boolean =
    stats.exists {
      case d: Defn.Object =>
        d.templ.body.stats.exists {
          case dd: Defn.Def => isEffectOpDef(dd.body)
          case _            => false
        } ||
        // Strategy D — recurse into nested objects to find dep defs marked
        // effectful by the fixpoint.  Without this, deps wrapped as
        // `object std { object pkg { def f = … } }` bypass emit and are
        // returned as `block.src` verbatim, skipping Step 3's CPS emit.
        d.collect { case dd: Defn.Def if globalEffectfulDeps(dd.name.value) => () }.nonEmpty
      case d: Defn.Def =>
        isEffectfulFun(d.name.value) || termUsesEffects(d.body) || hasInterParamDefault(d)
      case _: Defn.Enum => true
      case t: Term      => termUsesEffects(t)
      case _            => false
    }

  private[codegen] def termUsesEffects(t: Term): Boolean = t match
    case Term.Apply.After_4_6_0(Term.Apply.After_4_6_0(Term.Name("handle"), _), _) => true
    case Term.Apply.After_4_6_0(Term.Name("runAsync"), _)                         => true
    case Term.Apply.After_4_6_0(Term.Name("runAsyncParallel"), _)                 => true
    case Term.Apply.After_4_6_0(Term.Name("runStorage"), _)                       => true
    case Term.Apply.After_4_6_0(Term.Name("runEphemeralStorage"), _)              => true
    case Term.Select(Term.Name(eff), Term.Name(op)) if isEffectOpRef(eff, op)     => true
    case Term.Apply.After_4_6_0(Term.Name(n), _) if isEffectfulFun(n)             => true
    case Term.Apply.After_4_6_0(Term.Select(_, Term.Name(n)), _) if isEffectfulFun(n) => true
    case Term.Apply.After_4_6_0(
          Term.ApplyType.After_4_6_0(Term.Select(_, Term.Name(n)), _),
          _
        ) if isEffectfulFun(n) => true
    case Term.Apply.After_4_6_0(
          Term.ApplyType.After_4_6_0(Term.Name(n), _),
          _
        ) if isEffectfulFun(n) => true
    // Bare-name actor intrinsics — `subscribeClusterEvents()`,
    // `clusterMembers()`, `spawn { ... }`, etc.  Without this case the
    // val-rhs path emits the syntax verbatim and the unqualified name
    // is unresolved at scala-cli compile time (the runtime exposes them
    // as `Actor.subscribeClusterEvents`, and the rewriter only fires
    // when the rhs goes through `emitExpr`).
    case Term.Apply.After_4_6_0(Term.Name(n), _) if actorBareNames(n)             => true
    case _ => t.children.exists {
      case tt: Term => termUsesEffects(tt)
      case _        => false
    }

  /** True if the term needs codegen rewriting (effect machinery,
   *  Focus → Lens expansion, Prism[O, V] → Prism literal) rather than
   *  verbatim Scala source emission. */
  private[codegen] def termNeedsCustomEmit(t: Term): Boolean =
    termUsesEffects(t) || termContainsFocus(t) || termContainsPrism(t) || termContainsIntrinsic(t) || termContainsDirectBlock(t) || termContainsRegisteredInterpolator(t) || termContainsNamedArgAscription(t) || termContainsBareNameBlockCall(t)

  private[codegen] def termContainsRegisteredInterpolator(t: Term): Boolean =
    def walk(n: Tree): Boolean = n match
      case Term.Interpolate(Term.Name(prefix), _, _)
          if !jvmNativeInterpolators.contains(prefix) =>
        scalascript.compiler.plugin.InterpolatorRegistry.lookup(prefix).isDefined
      case _ => n.children.exists(walk)
    walk(t)

  private[codegen] def termContainsDirectBlock(t: Term): Boolean =
    def go(n: Tree): Boolean = n match
      case app: Term.Apply =>
        app.fun match
          case Term.ApplyType.After_4_6_0(Term.Name("direct"), _) => true
          case _ => app.children.exists(go)
      case other => other.children.exists(go)
    go(t)

  /** Stage 5+/A.4 — `val t = nowMillis()` and similar val-bound
   *  intrinsic calls need the rhs to go through `emitExpr` (where
   *  the intrinsic dispatch fires).  Without this they emit the
   *  raw scalameta syntax and the bare intrinsic name is
   *  unresolved at scala-cli compile time. */
  private[codegen] def termContainsIntrinsic(t: Term): Boolean =
    if intrinsics.isEmpty then false
    else
      val names = intrinsics.keySet.map(_.value)
      def walk(n: Tree): Boolean = n match
        case Term.Apply.After_4_6_0(Term.Name(nm), _) if names(nm) => true
        case _ => n.children.exists(walk)
      walk(t)

  private[codegen] def termContainsFocus(t: Term): Boolean =
    var found = false
    def walk(n: Tree): Unit =
      if !found then n match
        case app: Term.Apply if isFocusApp(app) => found = true
        case _ => n.children.foreach(walk)
    walk(t)
    found

  private[codegen] def termContainsPrism(t: Term): Boolean =
    var found = false
    def walk(n: Tree): Unit =
      if !found then n match
        case ta: Term.ApplyType if isPrismApplyType(ta) => found = true
        case _ => n.children.foreach(walk)
    walk(t)
    found

  // ─── Default-param helpers ────────────────────────────────────────
  //
  // ScalaScript allows a default expression to reference earlier parameters
  // in the same clause:    def shift(x: Int, by: Int = x + 1): Int = x + by
  // Scala 3 forbids this. We emit a set of overloads that materialise the
  // defaults at call sites where they're visible.

  private[codegen] def hasInterParamDefault(d: Defn.Def): Boolean =
    d.paramClauseGroups.exists { group =>
      group.paramClauses.exists { clause =>
        val params = clause.values
        params.zipWithIndex.exists { case (p, i) =>
          p.default.exists { dflt =>
            val earlier = params.take(i).map(_.name.value).toSet
            earlier.nonEmpty && referencesAny(dflt, earlier)
          }
        }
      }
    }

  private[codegen] def referencesAny(term: Term, names: Set[String]): Boolean = term match
    case Term.Name(n) if names(n) => true
    case _ => term.children.exists {
      case t: Term => referencesAny(t, names)
      case _       => false
    }

