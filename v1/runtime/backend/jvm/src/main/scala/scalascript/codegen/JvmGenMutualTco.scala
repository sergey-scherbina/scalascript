package scalascript.codegen

import scala.meta.*

/** Mutual-TCO emission: for each function in an SCC of size ≥ 2, emits a
 *  trampolined `_f_impl` + thin `f` wrapper, plus the allocation-free merge for
 *  uniform-signature cliques. Lifted out of JvmGen to keep the generator
 *  navigable; self-typed because the emitters read clique/dep state and call
 *  back into core emission on `self: JvmGen`. */
private[codegen] trait JvmGenMutualTco:
  self: JvmGen =>

  //
  // For each function f in an SCC of size ≥ 2 we emit:
  //   def _f_impl(_p1: T1, _p2: T2): Any =
  //     var p1: T1 = _p1; var p2: T2 = _p2
  //     while true do
  //       <transformed body — self-calls reassign vars and fall through;
  //        friend-calls return a new _TailCall thunk; other expressions
  //        return their value.>
  //     throw new RuntimeException("unreachable")
  //
  //   def f(p1: T1, p2: T2): R =
  //     _trampoline(() => _f_impl(p1, p2)).asInstanceOf[R]

  // ─── Allocation-free mutual-TCO merge (uniform-signature cliques) ──────

  private def cliqueParamTypes(d: Defn.Def): List[String] =
    d.paramClauseGroups.head.paramClauses.head.values.map(_.decltpe.map(_.syntax).getOrElse("Any"))

  private def cliqueRetType(d: Defn.Def): String = d.decltpe.map(_.syntax).getOrElse("Any")

  /** Deterministic canonical member of a clique — the lexicographically
   *  smallest name. The merged impl + all wrappers are emitted there. */
  private[codegen] def canonicalCliqueMember(name: String): String =
    mutualGroups(name).toList.min

  /** A clique is mergeable into one allocation-free dispatch loop when every
   *  member has the SAME parameter-type list and the SAME return type (so they
   *  can share the merged function's positional params + return), and each is a
   *  known single-clause def. Member param NAMES may differ — each case aliases
   *  the shared slots to its own names. */
  private[codegen] def mergeableMutualClique(name: String): Boolean =
    val members = mutualGroups(name).toList
    members.forall(mutualDefs.contains) && {
      val defs = members.map(mutualDefs)
      val sigs = defs.map(d => (cliqueParamTypes(d), cliqueRetType(d)))
      sigs.distinct.sizeIs == 1 && cliqueParamTypes(defs.head).nonEmpty
    }

  /** Emit the merged dispatch loop for `name`'s clique + a wrapper per member.
   *  Members are tagged by their sorted index. A tail call to any member becomes
   *  `{ <reassign shared slots from current member's view>; _tag = <calleeTag> }`
   *  and the `while`-loop iterates — no allocation. */
  private[codegen] def emitMergedMutualClique(name: String): String =
    val members  = mutualGroups(name).toList.sorted          // stable tag order
    val tagOf    = members.zipWithIndex.toMap
    val defs     = members.map(mutualDefs)
    val ptypes   = cliqueParamTypes(defs.head)
    val ret      = cliqueRetType(defs.head)
    val arity    = ptypes.length
    val slotVars = (0 until arity).map(i => s"_mtco_s$i").toList   // shared mutable slots
    val implName = s"_mtco_${members.min}_impl"

    val slotParams = slotVars.zip(ptypes).map { (s, t) => s"$s: $t" }.mkString(", ")
    val slotDecls  = slotVars.zip(ptypes).map { (s, t) => s"  var ${s}v: $t = $s" }.mkString("\n")

    val cases = defs.zipWithIndex.map { (d, tag) =>
      val pnames = d.paramClauseGroups.head.paramClauses.head.values.map(_.name.value)
      // Alias the shared slots to this member's own parameter names (read-only).
      // Body + aliases are indented one level deeper than `case` (Scala 3 rule).
      val aliases = pnames.zipWithIndex.map { (p, i) => s"        val $p = ${slotVars(i)}v" }.mkString("\n")
      val bodyOut = StringBuilder()
      emitMergedCliqueBody(d.body, tagOf, slotVars, indent = 4, bodyOut)
      val aliasBlock = if aliases.nonEmpty then aliases + "\n" else ""
      s"      case $tag =>\n$aliasBlock${bodyOut.toString.stripTrailing}"
    }.mkString("\n")

    val impl =
      s"""def $implName(_tag: Int, $slotParams): $ret =
         |  var _t = _tag
         |$slotDecls
         |  while true do
         |    _t match
         |$cases
         |  throw new RuntimeException("unreachable")""".stripMargin

    val wrappers = defs.map { d =>
      val pnames  = d.paramClauseGroups.head.paramClauses.head.values.map(_.name.value)
      val sig     = pnames.zip(ptypes).map { (p, t) => s"$p: $t" }.mkString(", ")
      val callArgs = (tagOf(d.name.value) :: pnames).mkString(", ")
      s"def ${d.name.value}($sig): $ret = $implName($callArgs)"
    }.mkString("\n")

    s"$impl\n$wrappers"

  /** Body emitter for the merged clique: a tail call to ANY clique member →
   *  reassign the shared slots (via temps, to allow argument permutations) and
   *  set `_tag`; any other tail expression → `return`. */
  private def emitMergedCliqueBody(
      term: Term, tagOf: Map[String, Int], slotVars: List[String], indent: Int, out: StringBuilder
  ): Unit =
    val pad = "  " * indent
    term match
      case Term.Apply.After_4_6_0(Term.Name(callee), argClause) if tagOf.contains(callee) =>
        val args = argClause.values.map(_.syntax)
        val tmps = slotVars.indices.map(i => s"_mtco_n${indent}_$i").toList
        out.append(pad).append("{\n")
        tmps.zip(args).foreach { (t, a) => out.append(pad).append(s"  val $t = $a\n") }
        slotVars.zip(tmps).foreach { (s, t) => out.append(pad).append(s"  ${s}v = $t\n") }
        out.append(pad).append(s"  _t = ${tagOf(callee)}\n")
        out.append(pad).append("}\n")
      case t: Term.If =>
        out.append(pad).append(s"if ${t.cond.syntax} then\n")
        emitMergedCliqueBody(t.thenp, tagOf, slotVars, indent + 1, out)
        out.append(pad).append("else\n")
        emitMergedCliqueBody(t.elsep, tagOf, slotVars, indent + 1, out)
      case t: Term.Match =>
        out.append(pad).append(s"${t.expr.syntax} match\n")
        t.casesBlock.cases.foreach { c =>
          val guard = c.cond.map(g => s" if ${g.syntax}").getOrElse("")
          out.append(pad).append(s"  case ${c.pat.syntax}$guard =>\n")
          emitMergedCliqueBody(c.body, tagOf, slotVars, indent + 2, out)
        }
      case Term.Block(stats) =>
        stats.dropRight(1).foreach { s => out.append(pad).append(s.syntax).append("\n") }
        stats.lastOption match
          case Some(t: Term) => emitMergedCliqueBody(t, tagOf, slotVars, indent, out)
          case Some(s)       => out.append(pad).append(s.syntax).append("\n"); out.append(pad).append("return ()\n")
          case None          => out.append(pad).append("return ()\n")
      case other =>
        out.append(pad).append(s"return ${other.syntax}\n")

  private[codegen] def emitMutualTcoFun(d: Defn.Def): String =
    val fname   = d.name.value
    val params  = d.paramClauseGroups.head.paramClauses.head.values
    val friends = mutualGroups(fname) - fname

    def typeOf(p: Term.Param): String =
      p.decltpe.map(_.syntax).getOrElse("Any")

    val paramNames  = params.map(_.name.value)
    val implName    = s"_${fname}_impl"

    val implParams = params.map(p => s"_${p.name.value}: ${typeOf(p)}").mkString(", ")
    val varDecls   = params.map(p =>
      s"  var ${p.name.value}: ${typeOf(p)} = _${p.name.value}"
    ).mkString("\n")

    val bodyOut = StringBuilder()
    emitMutualTcoBody(d.body, fname, paramNames, friends, indent = 2, bodyOut)

    val impl =
      s"""def $implName($implParams): Any =
         |$varDecls
         |  while true do
         |${bodyOut.toString.stripTrailing}
         |  throw new RuntimeException("unreachable")""".stripMargin

    val wrapperRet = d.decltpe.map(t => s": ${t.syntax}").getOrElse("")
    val cast       = d.decltpe.map(t => s".asInstanceOf[${t.syntax}]").getOrElse("")
    val wrapperSig = params.map(p => s"${p.name.value}: ${typeOf(p)}").mkString(", ")
    val wrapperArgs = paramNames.mkString(", ")
    val wrapper    =
      s"def $fname($wrapperSig)$wrapperRet = _trampoline(() => $implName($wrapperArgs))$cast"

    s"$impl\n$wrapper"

  /** Recursively emit the body of `_f_impl` as Scala statements. Every leaf
   *  is either a self-call (reassign vars, let the while-loop iterate),
   *  a friend-call (return a _TailCall thunk), or any other expression
   *  (returned as the trampoline's final value). */
  private def emitMutualTcoBody(
      term:    Term,
      fname:   String,
      params:  List[String],
      friends: Set[String],
      indent:  Int,
      out:     StringBuilder
  ): Unit =
    val pad = "  " * indent
    term match
      // Self-tail-call: reassign params via temporaries, then fall through so
      // the enclosing while-loop iterates with the new arguments.
      case Term.Apply.After_4_6_0(Term.Name(`fname`), argClause) =>
        val args = argClause.values.map(_.syntax)
        val tmps = params.map(p => s"_new_$p")
        out.append(pad).append("{\n")
        tmps.zip(args).foreach { (t, a) =>
          out.append(pad).append("  val ").append(t).append(" = ").append(a).append("\n")
        }
        params.zip(tmps).foreach { (p, t) =>
          out.append(pad).append("  ").append(p).append(" = ").append(t).append("\n")
        }
        out.append(pad).append("}\n")

      // Friend-tail-call: hand the next step to the trampoline.
      case Term.Apply.After_4_6_0(Term.Name(n), argClause) if friends.contains(n) =>
        val args = argClause.values.map(_.syntax).mkString(", ")
        out.append(pad).append(s"return new _TailCall(() => _${n}_impl($args))\n")

      // Conditional in tail position: recurse into both branches.
      case t: Term.If =>
        out.append(pad).append(s"if ${t.cond.syntax} then\n")
        emitMutualTcoBody(t.thenp, fname, params, friends, indent + 1, out)
        out.append(pad).append("else\n")
        emitMutualTcoBody(t.elsep, fname, params, friends, indent + 1, out)

      // Match in tail position: recurse into each case body.
      case t: Term.Match =>
        out.append(pad).append(s"${t.expr.syntax} match\n")
        t.casesBlock.cases.foreach { c =>
          val guard = c.cond.map(g => s" if ${g.syntax}").getOrElse("")
          out.append(pad).append(s"  case ${c.pat.syntax}$guard =>\n")
          emitMutualTcoBody(c.body, fname, params, friends, indent + 2, out)
        }

      // Block: emit non-final stats verbatim, recurse into the tail expression.
      case Term.Block(stats) =>
        stats.dropRight(1).foreach { s =>
          out.append(pad).append(s.syntax).append("\n")
        }
        stats.lastOption match
          case Some(t: Term) =>
            emitMutualTcoBody(t, fname, params, friends, indent, out)
          case Some(s) =>
            out.append(pad).append(s.syntax).append("\n")
            out.append(pad).append("return ()\n")
          case None =>
            out.append(pad).append("return ()\n")

      // Anything else in tail position: this is the final value.
      case other =>
        out.append(pad).append(s"return ${other.syntax}\n")

