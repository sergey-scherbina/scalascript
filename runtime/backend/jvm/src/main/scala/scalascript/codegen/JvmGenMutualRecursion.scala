package scalascript.codegen

import scalascript.ast.*
import scala.collection.mutable
import scala.meta.*

/** Mutual tail-recursion analysis: builds the tail-call graph among
 *  non-effectful single-clause functions and records every SCC of size >= 2
 *  in mutualGroups so the emitter can trampoline the clique. Lifted out of
 *  JvmGen; self-typed because analyzeMutualRecursion populates generator
 *  state. */
private[codegen] trait JvmGenMutualRecursion:
  self: JvmGen =>

  // ─── Mutual-recursion analysis ────────────────────────────────────
  //
  // Build a graph of tail-position calls between non-effectful, single-clause
  // functions (multi-clause and effectful functions are out of scope — the
  // CPS path already trampolines effects, and curried tail recursion is rare
  // enough that we skip it).  Compute SCCs; any SCC of size ≥ 2 is a mutual
  // tail-recursion clique that the emitter will trampoline.

  private[codegen] def analyzeMutualRecursion(blocks: List[JvmGen.Block]): Unit =
    mutualGroups.clear()
    val callGraph = mutable.Map[String, Set[String]]()

    def collectFuncs(stats: List[Stat]): Unit = stats.foreach {
      case d: Defn.Def
          if !isEffectfulFun(d.name.value)
          && !hasInterParamDefault(d)
          && isSingleClauseDef(d) =>
        callGraph(d.name.value) =
          tailCallTargets(d.body, d.name.value, tailPos = true)
      case _ => ()
    }

    blocks.foreach { block =>
      ScalaNode.fold(block.node) {
        case Source(stats)     => collectFuncs(stats)
        case Term.Block(stats) => collectFuncs(stats)
        case _                 => ()
      }
    }

    val funcNames = callGraph.keySet.toSet
    val sccs = findSCCs(callGraph.toMap, funcNames)
    sccs.filter(_.size > 1).foreach { scc =>
      scc.foreach { name => mutualGroups(name) = scc }
    }

  private def isSingleClauseDef(d: Defn.Def): Boolean =
    d.paramClauseGroups.size == 1 &&
    d.paramClauseGroups.head.paramClauses.size == 1

  /** Names of functions called in tail position inside `tree`, excluding
   *  `selfName` (self-recursion is handled by the while-loop reassignment
   *  inside _impl, not by a graph edge). */
  private def tailCallTargets(
      tree:     scala.meta.Tree,
      selfName: String,
      tailPos:  Boolean
  ): Set[String] =
    tree match
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) =>
        if tailPos && n != selfName then Set(n)
        else argClause.values.flatMap(a => tailCallTargets(a, selfName, false)).toSet
      case t: Term.If =>
        tailCallTargets(t.cond,  selfName, false) ++
        tailCallTargets(t.thenp, selfName, tailPos) ++
        tailCallTargets(t.elsep, selfName, tailPos)
      case Term.Block(stats) =>
        stats.dropRight(1).flatMap(s => tailCallTargets(s, selfName, false)).toSet ++
        stats.lastOption.map(s => tailCallTargets(s, selfName, tailPos)).getOrElse(Set.empty)
      case t: Term.Match =>
        tailCallTargets(t.expr, selfName, false) ++
        t.casesBlock.cases.flatMap(c => tailCallTargets(c.body, selfName, tailPos)).toSet
      case other =>
        other.children.flatMap(c => tailCallTargets(c, selfName, false)).toSet

  /** Tarjan's algorithm — returns the SCCs of the directed graph. */
  private def findSCCs(
      graph: Map[String, Set[String]],
      names: Set[String]
  ): List[Set[String]] =
    var idx = 0
    val stack   = mutable.Stack[String]()
    val onStk   = mutable.Set[String]()
    val nodeIdx = mutable.Map[String, Int]()
    val low     = mutable.Map[String, Int]()
    val result  = mutable.ListBuffer[Set[String]]()

    def connect(v: String): Unit =
      nodeIdx(v) = idx; low(v) = idx; idx += 1
      stack.push(v); onStk += v
      for w <- graph.getOrElse(v, Set.empty) if names.contains(w) do
        if !nodeIdx.contains(w) then
          connect(w)
          low(v) = low(v) min low(w)
        else if onStk.contains(w) then
          low(v) = low(v) min nodeIdx(w)
      if low(v) == nodeIdx(v) then
        val scc = mutable.Set[String]()
        var w = ""
        while { w = stack.pop(); onStk -= w; scc += w; w != v } do ()
        result += scc.toSet

    for v <- names do
      if !nodeIdx.contains(v) then connect(v)
    result.toList

  private[codegen] def isInMutualClique(name: String): Boolean =
    mutualGroups.contains(name)

  // ─── Block emission ───────────────────────────────────────────────
