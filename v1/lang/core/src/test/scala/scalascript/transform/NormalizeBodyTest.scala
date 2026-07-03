package scalascript.transform

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser
import scalascript.ir

/** v2.0 / Stage 5+ — `Normalize` now populates `ir.Content.CodeBlock.body`
 *  with translated `IrExpr` trees instead of leaving it empty.  This
 *  unblocks `Linker.rewriteExpr` so cross-module symbol references can
 *  actually be rewritten at link time.
 *
 *  The test parses a tiny module, runs `Normalize`, and asserts that
 *  every scalascript code block now has a non-empty body whose root is
 *  an `ir.Block` containing at least one recognisable node (e.g. a
 *  `VarRef` or an `Apply`).  The exact shape is not pinned — the goal
 *  is to prove the body is no longer `Nil`. */
class NormalizeBodyTest extends AnyFunSuite:

  // A tiny module with a val and a def — exercises Lit, Apply, Term.Block,
  // and the Defn.Val / Defn.Def fallback to Unsupported.
  private val src =
    """# Sample
      |
      |```scalascript
      |val x = 1 + 2
      |def f(a: Int) = a + 1
      |```
      |""".stripMargin

  test("Normalize populates CodeBlock.body with IrExpr trees"):
    val module = Normalize(Parser.parse(src))
    val codeBlocks = module.sections.flatMap(_.content).collect {
      case cb: ir.Content.CodeBlock => cb
    }
    assert(codeBlocks.nonEmpty, "expected at least one code block in the IR")

    // Every scalascript code block has a populated body (no more `Nil`).
    codeBlocks.foreach { cb =>
      assert(cb.body.nonEmpty, s"expected non-empty body for code block; got Nil for source:\n${cb.source}")
    }

    // The first code block's body should be parseable as a Block (or a
    // top-level Block produced by Source-translation) whose statements
    // collectively reference at least one VarRef and at least one Apply.
    val firstBody = codeBlocks.head.body
    val allExprs = collectAll(firstBody)
    assert(
      allExprs.exists(_.isInstanceOf[ir.VarRef]),
      s"expected at least one VarRef in translated body; got: ${allExprs.map(_.getClass.getSimpleName).mkString(", ")}"
    )
    assert(
      allExprs.exists(_.isInstanceOf[ir.Apply]),
      s"expected at least one Apply in translated body; got: ${allExprs.map(_.getClass.getSimpleName).mkString(", ")}"
    )

  test("Normalize translates simple values to Lit nodes"):
    val tinySrc =
      """# Tiny
        |
        |```scalascript
        |val n = 42
        |```
        |""".stripMargin
    val module = Normalize(Parser.parse(tinySrc))
    val cb = module.sections.head.content.collectFirst {
      case cb: ir.Content.CodeBlock => cb
    }.getOrElse(fail("no code block in parsed module"))
    val allExprs = collectAll(cb.body)
    assert(
      allExprs.collect { case ir.Lit(ir.LitValue.IntL(42)) => true }.nonEmpty,
      s"expected to find Lit(IntL(42)) in body; got: ${allExprs.mkString(", ")}"
    )

  /** Walk every nested `IrExpr` in a list of bodies and return a flat
   *  list — used by assertions that look for specific node kinds
   *  regardless of nesting depth. */
  private def collectAll(roots: List[ir.IrExpr]): List[ir.IrExpr] =
    val buf = scala.collection.mutable.ListBuffer.empty[ir.IrExpr]
    def walk(e: ir.IrExpr): Unit =
      buf += e
      e match
        case ir.Block(stmts)          => stmts.foreach(walk)
        case ir.Apply(fn, args)       => walk(fn); args.foreach(walk)
        case ir.Select(qual, _)       => walk(qual)
        case ir.Lambda(_, body)       => walk(body)
        case ir.If(c, t, e)           => walk(c); walk(t); e.foreach(walk)
        case ir.Call(_, args)         => args.foreach(walk)
        case _                        => ()
    roots.foreach(walk)
    buf.toList
