package scalascript.interpreter.vm

import scala.meta.*
import scala.collection.mutable
import scalascript.interpreter.Value
import SscVm.*

/** Compiles a `Value.FunV` whose params are all integer-typed and whose body
 *  is in the supported subset (see docs/vm-jit-spec.md §5) into a [[CompiledFn]].
 *  Returns `None` on any unsupported construct — the caller then falls back to
 *  the tree-walking interpreter, so this can never change semantics.
 *
 *  v0 supports self-recursion only; mutually-recursive compiled functions are a
 *  follow-up (the callPool slot is already general).
 */
object VmCompiler:

  private final class Bail extends RuntimeException

  private val intTypes = Set("Int", "Long", "")

  def compile(fn: Value.FunV): Option[CompiledFn] =
    // All declared param types must be integer (empty = untyped, allowed in v0).
    val typesOk = fn.paramTypes.isEmpty ||
      fn.paramTypes.forall(t => intTypes.contains(t.trim))
    if !typesOk || fn.usingParams.nonEmpty || fn.defaults.exists(_.isDefined) then None
    else
      try Some(new Builder(fn).build())
      catch case _: Bail => None

  // ── per-function compilation state ───────────────────────────────
  private final class Builder(fn: Value.FunV):
    private val ops   = mutable.ArrayBuffer.empty[Int]
    private val as    = mutable.ArrayBuffer.empty[Int]
    private val bs    = mutable.ArrayBuffer.empty[Int]
    private val cs    = mutable.ArrayBuffer.empty[Int]
    private val consts = mutable.ArrayBuffer.empty[Long]
    private val locals = mutable.HashMap.empty[String, Int]
    private var nextReg = 0
    private var maxReg  = 0

    private def bail(): Nothing = throw new Bail

    private def freshReg(): Int =
      val r = nextReg; nextReg += 1
      if nextReg > maxReg then maxReg = nextReg
      r

    private def freshRegs(n: Int): Int =
      val base = nextReg; nextReg += n
      if nextReg > maxReg then maxReg = nextReg
      base

    private def constSlot(v: Long): Int =
      var i = 0
      while i < consts.length do { if consts(i) == v then return i; i += 1 }
      consts += v; consts.length - 1

    private def emit(op: Int, a: Int, b: Int, c: Int): Int =
      ops += op; as += a; bs += b; cs += c; ops.length - 1

    private def opcodeFor(op: String): Int = op match
      case "+"  => ADD; case "-"  => SUB; case "*" => MUL
      case "/"  => DIV; case "%"  => MOD
      case "<"  => LT;  case "<=" => LE; case ">" => GT; case ">=" => GE
      case "==" => EQ;  case "!=" => NE
      case _    => bail()

    // Compile an expression, returning the register holding its Long result.
    private def compileExpr(t: Term): Int = t match
      case Lit.Int(v)  => val r = freshReg(); emit(CONST, r, constSlot(v.toLong), 0); r
      case Lit.Long(v) => val r = freshReg(); emit(CONST, r, constSlot(v), 0); r

      case n: Term.Name =>
        locals.getOrElse(n.value, bail())

      case app: Term.ApplyInfix if app.argClause.values.lengthCompare(1) == 0 =>
        val opc = opcodeFor(app.op.value)
        val lr  = compileExpr(app.lhs)
        val rr  = compileExpr(app.argClause.values.head)
        val d   = freshReg(); emit(opc, d, lr, rr); d

      case t: Term.If =>
        val cr  = compileExpr(t.cond)
        val jf  = emit(JF, cr, -1, 0)            // patch to else-start
        val res = freshReg()
        val tr  = compileExpr(t.thenp); emit(MOVE, res, tr, 0)
        val jmp = emit(JMP, -1, 0, 0)            // patch to end
        bs(jf) = ops.length                      // JF else-target: else-branch starts here
        val er  = compileExpr(t.elsep); emit(MOVE, res, er, 0)
        as(jmp) = ops.length                     // end
        res

      // self-recursive call: f(args), arity 1 or 2 in v0
      case app: Term.Apply if isSelfCall(app) =>
        val args = app.argClause.values
        if args.lengthCompare(fn.params.length) != 0 then bail()
        val argBase = freshRegs(args.length)
        var i = 0
        while i < args.length do
          val ar = compileExpr(args(i))
          emit(MOVE, argBase + i, ar, 0)
          i += 1
        val d = freshReg(); emit(CALL, d, argBase, 0 /* self in callPool */); d

      case Term.Block(stats) =>
        compileStats(stats)

      case Term.While(cond, body) =>
        val start = ops.length
        val cr    = compileExpr(cond)
        val jf    = emit(JF, cr, -1, 0)
        compileStmt(body)
        emit(JMP, start, 0, 0)
        bs(jf) = ops.length
        val r = freshReg(); emit(CONST, r, constSlot(0L), 0); r  // while ⇒ unit ⇒ 0

      case _ => bail()

    // Compile `t` in TAIL position: every path ends in either a RET or a
    // jump back to instruction 0 (a self-tail-call turned into a loop). This
    // gives constant host-stack depth for tail recursion — the same shape the
    // JVM backend produces, and what makes deep `sumTco` not overflow.
    private def compileTail(t: Term): Unit = t match
      case ti: Term.If =>
        val cr = compileExpr(ti.cond)
        val jf = emit(JF, cr, -1, 0)
        compileTail(ti.thenp)        // then-branch self-terminates
        bs(jf) = ops.length
        compileTail(ti.elsep)        // else-branch self-terminates

      case app: Term.Apply if isSelfCall(app) =>
        val args = app.argClause.values
        if args.lengthCompare(fn.params.length) != 0 then bail()
        // Evaluate every arg into a temp BEFORE overwriting the param regs,
        // since args (e.g. `acc + n`) read the current params.
        val tmp = new Array[Int](args.length)
        var i = 0
        while i < args.length do { tmp(i) = compileExpr(args(i)); i += 1 }
        i = 0
        while i < args.length do { emit(MOVE, i, tmp(i), 0); i += 1 } // params = r0..r(n-1)
        emit(JMP, 0, 0, 0)           // loop to start

      case Term.Block(stats) =>
        if stats.isEmpty then bail()
        var rest = stats
        while rest.tail.nonEmpty do { compileStmt(rest.head); rest = rest.tail }
        compileTail(rest.head.asInstanceOf[Term])

      case other =>
        val r = compileExpr(other); emit(RET, r, 0, 0)

    private def isSelfCall(app: Term.Apply): Boolean =
      fn.name.nonEmpty && (app.fun match
        case n: Term.Name => n.value == fn.name
        case _            => false)

    // A statement whose value may be discarded (loop bodies, non-final stmts).
    private def compileStmt(t: Tree): Unit = t match
      case Defn.Val(_, List(Pat.Var(nm: Term.Name)), _, rhs) =>
        val r = compileExpr(rhs); locals(nm.value) = bindReg(r)
      case Defn.Var.After_4_7_2(_, List(Pat.Var(nm)), _, rhs) =>
        val r = compileExpr(rhs); locals(nm.value) = bindReg(r)
      case Term.Assign(nm: Term.Name, rhs) =>
        val dst = locals.getOrElse(nm.value, bail())
        val r   = compileExpr(rhs); emit(MOVE, dst, r, 0)
      case Term.Block(stats) => compileStats(stats); ()
      case e: Term            => compileExpr(e); ()
      case _                  => bail()

    // A `val x = e` should give `x` a stable home register. compileExpr may have
    // produced its result in a temp; move it into a fresh dedicated slot so later
    // reassignments (var) and reads are consistent.
    private def bindReg(src: Int): Int =
      val home = freshReg(); emit(MOVE, home, src, 0); home

    private def compileStats(stats: List[Stat]): Int =
      if stats.isEmpty then bail()
      var rest = stats
      while rest.tail.nonEmpty do { compileStmt(rest.head); rest = rest.tail }
      compileExpr(rest.head.asInstanceOf[Term])

    def build(): CompiledFn =
      // params occupy r0..r(arity-1)
      val arity = fn.params.length
      if arity < 1 || arity > 2 then bail()
      var i = 0
      while i < arity do { locals(fn.params(i)) = i; i += 1 }
      nextReg = arity; maxReg = arity
      compileTail(fn.body)           // emits RET / loop on every path
      val self = new CompiledFn(
        name = fn.name, arity = arity, numRegs = maxReg,
        op = ops.toArray, a = as.toArray, b = bs.toArray, c = cs.toArray,
        constPool = consts.toArray, callPool = Array.ofDim[CompiledFn](1)
      )
      self.callPool(0) = self  // v0: only self-recursion
      self
