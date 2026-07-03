package scalascript.codegen

import scala.meta.*

/** Pure-ish analysis queries lifted out of the JsGen generator to keep that
 *  file navigable:
 *    - effect / `awaitClient` detection over the parsed scalameta AST
 *    - self-call / tail-position analysis used by the mutual-recursion pass
 *
 *  Mixed into JsGen via a self type so the two effect queries can read the
 *  effectOps / effectfulFuns sets; the tail-call predicates are fully pure. */
private[codegen] trait JsGenAnalysisQueries:
  self: JsGen =>

  private[codegen] def containsAwaitClient(tree: scala.meta.Tree): Boolean =
    tree.collect {
      case Term.Apply.After_4_6_0(Term.Name("awaitClient"), _) => ()
    }.nonEmpty

  private[codegen] def isAwaitClientExpr(t: Term): Boolean = t match
    case Term.Apply.After_4_6_0(Term.Name("awaitClient"), _) => true
    case _ => false

  // True when 2+ generators all use awaitClient — safe to lower to sequential awaits.
  private[codegen] def enumeratorsNeedAsyncFor(enums: List[Enumerator]): Boolean =
    val generators = enums.collect { case Enumerator.Generator(_, rhs) => rhs }
    generators.length >= 2 && generators.forall(isAwaitClientExpr)

  /** True if `Eff.op` is a declared effect operation. */
  private[codegen] def isEffectOpRef(eff: String, op: String): Boolean =
    effectOps.contains(s"$eff.$op")

  /** True if `name` resolves to an effectful function. */
  private[codegen] def isEffectfulFun(name: String): Boolean =
    effectfulFuns.contains(name)

  private[codegen] def hasNonTailSelfCall(term: Term, fname: String, tailPos: Boolean): Boolean =
    import scala.meta.*
    term match
      case Term.Apply.After_4_6_0(Term.Name(`fname`), argClause) =>
        if tailPos then argClause.values.collect { case t: Term => t }
                                        .exists(hasNonTailSelfCall(_, fname, tailPos = false))
        else true
      case t: Term.If =>
        hasNonTailSelfCall(t.cond,  fname, tailPos = false) ||
        hasNonTailSelfCall(t.thenp, fname, tailPos = tailPos) ||
        hasNonTailSelfCall(t.elsep, fname, tailPos = tailPos)
      case Term.Block(stats) =>
        stats.dropRight(1).exists {
          case t: Term => hasNonTailSelfCall(t, fname, tailPos = false)
          case _       => false
        } || stats.lastOption.exists {
          case t: Term => hasNonTailSelfCall(t, fname, tailPos = tailPos)
          case _       => false
        }
      case t: Term.Match =>
        hasNonTailSelfCall(t.expr, fname, tailPos = false) ||
        t.casesBlock.cases.exists(c => hasNonTailSelfCall(c.body, fname, tailPos = tailPos))
      case other =>
        anywhereContainsSelfCall(other, fname)

  private[codegen] def anywhereContainsSelfCall(tree: scala.meta.Tree, fname: String): Boolean =
    tree match
      case Term.Apply.After_4_6_0(Term.Name(`fname`), _) => true
      case t => t.children.exists(anywhereContainsSelfCall(_, fname))
