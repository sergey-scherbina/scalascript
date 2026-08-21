package ssc3.plugins

import ssc3.{Expr, Stmt, Param, Plugins, Pos}

/** `direct[M] { … }` — do-notation, and the FIRST real client of the syntax door.
  *
  * THE RULE IS THE REFERENCE'S, VERBATIM (`v2/lib/ssc1-lower.ssc0:2256-2312`, `directStmts`). Read
  * before writing rather than re-derived: this repository's goldens and its v2 bridge both encode
  * what the reference does, and a re-derivation is how a client comes to disagree with them in a
  * way no gate names. Every clause below cites the reference function it copies.
  *
  *   x = e            a BIND — `e.flatMap(x => rest)` — when `x` is not a `var` declared above it
  *   var c = init     kept, and `c` joins the mutable set, so a later `c = …` is an ASSIGNMENT
  *   val y = e        kept. `val _ = e` is NOT a bind-and-discard: the reference treats every `val`
  *                    as pure, and `tests/conformance/direct-syntax.ssc`'s own prose is wrong about
  *                    its own case. The code is what decides
  *   e                a non-final expression statement is kept; the FINAL one is the result
  *   {}               an empty block is unit
  *
  * WHY THE MUTABLE SET EXISTS, in one line: `x = Some(40)` and `counter = counter + x` are the same
  * node, `Expr.Assign`. What separates a bind from an assignment is whether a `var` of that name was
  * declared earlier in this block — which is why the reference threads a list rather than matching
  * on shape, and why `direct-syntax.ssc`'s r5 case is the one that catches getting it wrong.
  */
object DirectSyntax:

  /** `directSupportedMonad`, verbatim: `Option` and `List`, nothing else. The reference answers an
    * unsupported monad with a VARIABLE named `__unsupported_direct_<M>` — an unknown name, reported
    * wherever names are resolved. This refuses with a POSITION instead (rule 3), which is strictly
    * better: the message names the file, the line and the reason. */
  private val supported = Set("Option", "List")

  private def rewrite(m: Expr.Marker, ctx: Plugins.Ctx): Either[Plugins.Refusal, Expr] =
    val monad = m.typeArgs.headOption.getOrElse("")
    if monad.isEmpty then
      Left(Plugins.Refusal("direct needs its monad written as a type argument — `direct[Option] { … }`", m.pos))
    else if !supported.contains(monad) then
      Left(Plugins.Refusal(
        "direct[" + monad + "] is not supported — the desugaring is defined for Option and List, " +
        "and nothing else has a `flatMap` this rewrite may assume", m.pos))
    else m.args match
      case List(b @ Expr.Block(_, _, _)) => Right(desugar(b, monad))
      // Unreachable from either front — both build the marker only for the brace spelling — so this
      // is about a rewrite that MINTS a `direct` marker, which rule 2 allows.
      case _ => Left(Plugins.Refusal("direct expects a block: `direct[" + monad + "] { … }`", m.pos))

  private def desugar(b: Expr.Block, monad: String): Expr =
    def go(stmts: List[Stmt], mutables: Set[String]): Expr = stmts match
      case Nil => b.result.getOrElse(Expr.Block(Nil, None, b.pos))
      case st :: rest =>
        st match
          case Stmt.Exp(Expr.Assign(n, rhs, q)) if !mutables.contains(n) =>
            Expr.MethodCall(rhs, "flatMap",
                            List(Expr.Lambda(List(Param(n, q)), go(rest, mutables), q)), q)
          case Stmt.Val(n, _, true, _) => keep(st, go(rest, mutables + n))
          case _                       => keep(st, go(rest, mutables))
    def keep(st: Stmt, rest: Expr): Expr = Expr.Block(List(st), Some(rest), b.pos)
    go(b.stmts, Set.empty)

  def install(): Unit = Plugins.registerRewrite("direct", rewrite)
