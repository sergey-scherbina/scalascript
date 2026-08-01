package ssc3

// SSC3-3 · V-0 — SSC IR → v2 Core IR, so v3 inherits the whole v2 backend fleet (VM, JVM bytecode,
// JS, Rust, native) instead of re-earning it. v3/specs/10-ssc-ir.md, v3/SPRINT.md SSC3-3.
//
// Raising a LINEAR form into v2's term tree is only tractable because SSC IR is structured by
// construction. From basic blocks this step would be the relooper.
//
// V-0 TRANSLATES THE REGISTER FILE AS ONE MUTABLE ARRAY. No SSA, no join points: a register is an
// array slot, so an assignment is a store and there is nothing to merge at the end of a region.
// Mechanical and obviously correct, and slow — every register access is an array operation through
// the VM. V-1 (SSC3-3c) raises registers to `Let` bindings where a measurement says it pays.
//
// Every emitted shape was READ OFF THE ORACLE — `v2/bin/ssc1-run.ssc0` on a program doing the same
// thing — rather than guessed from the reader's source. The two differ in exactly the places that
// matter: an array READ is an application `(app <arr> <idx>)`, not a `__method__ "apply"`.

object BridgeV2:

  final case class Unsupported(what: String)
      extends RuntimeException("v2 bridge V-0 does not translate " + what)

  // ── the shapes, named once ──────────────────────────────────────────────────
  private def sq(parts: List[String]): String = "(seq " + parts.mkString(" ") + ")"
  private def lit(s: String): String = "(lit " + s + ")"
  private def int(i: Int): String = lit("(int " + i + ")")

  /** The frame is `local 0` throughout: it is bound by ONE `let` and nothing else introduces a
    * binder, because every statement goes into a `seq`, which evaluates in the SAME environment.
    * That is what keeps de Bruijn indices from shifting under the translation — the single fact
    * that makes V-0 a mapping rather than an index-tracking exercise. */
  private val frame = "(local 0)"

  private def read(r: Int): String = "(app " + frame + " " + int(r) + ")"
  private def write(r: Int, v: String): String =
    "(prim __method__ " + lit("(str \"update\")") + " " + frame + " " + int(r) + " " + v + ")"

  private def litOf(l: Lit): String = l match
    case Lit.LUnit     => lit("unit")
    case Lit.LBool(b)  => lit(if b then "true" else "false")
    case Lit.LInt(n)   => lit("(int " + n + ")")
    case Lit.LBig(d)   => lit("(big " + d + ")")
    case Lit.LFloat(d) => lit("(float " + Text.floatText(d) + ")")
    case Lit.LStr(s)   => lit("(str " + quote(s) + ")")
    case Lit.LBytes(h) => lit("(bytes " + h + ")")

  private def quote(s: String): String =
    var out = "\""
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      out =
        if c == '"' then out + "\\\""
        else if c == '\\' then out + "\\\\"
        else if c == '\n' then out + "\\n"
        else if c == '\r' then out + "\\r"
        else if c == '\t' then out + "\\t"
        else out + c
      i += 1
    out + "\""

  private def arith(op: String, a: String, b: String): String =
    "(prim __arith__ " + lit("(str \"" + op + "\")") + " " + a + " " + b + ")"

  private def binName(o: BinOp): String = o match
    case BinOp.Add => "+"; case BinOp.Sub => "-"; case BinOp.Mul => "*"
    case BinOp.Div => "/"; case BinOp.Rem => "%"
    case BinOp.Lt => "<"; case BinOp.Le => "<="; case BinOp.Gt => ">"
    case BinOp.Ge => ">="; case BinOp.Eq => "=="; case BinOp.Ne => "!="
    // The bitwise family has no measured `__arith__` spelling yet, so it is REFUSED rather than
    // guessed. A wrong operator name would lower to a runtime miss far from here.
    case other => throw Unsupported("the " + other + " operator")

  // ── per-function state ──────────────────────────────────────────────────────
  private final case class Ctx(m: Module, f: Func):
    val retFlag: Int = f.nregs
    val retVal: Int = f.nregs + 1
    val frameSize: Int = f.nregs + 2

  /** Can this instruction leave the function? Everything after one that can must be guarded. */
  private def mayReturn(i: Instr): Boolean = i match
    case _: Instr.Ret      => true
    case _: Instr.TailCall => true
    case Instr.If(_, t, e) => t.exists(mayReturn) || e.exists(mayReturn)
    case _                 => false

  private def stmt(i: Instr, cx: Ctx): String = i match
    case Instr.Const(d, k)        => write(d, litOf(cx.m.consts(k)))
    case Instr.Move(d, a)         => write(d, read(a))
    case Instr.Bin(o, _, d, a, b) => write(d, arith(binName(o), read(a), read(b)))
    case Instr.Un(UnOp.Neg, _, d, a) => write(d, arith("-", int(0), read(a)))
    case Instr.If(c, t, e)        => "(if " + read(c) + " " + seqOf(t, cx) + " " + seqOf(e, cx) + ")"
    // A return is a STORE plus a flag, not a jump: the flag is what the guards below read. This is
    // the price of not having `break` in the target, and it is paid per statement rather than once.
    case Instr.Ret(a)             => sq(List(write(cx.retVal, read(a)), write(cx.retFlag, lit("true"))))
    case Instr.Call(d, fi, as)    => write(d, "(app (global " + cx.m.funcs(fi).name + ")" + args(as) + ")")
    // V-0 does NOT make this a tail call — v2 gives no TCO, so the constant-stack guarantee is one
    // of the three things only our own executor (SSC3-3b) can deliver. Correct, not constant-stack.
    case Instr.TailCall(fi, as) =>
      sq(List(write(cx.retVal, "(app (global " + cx.m.funcs(fi).name + ")" + args(as) + ")"),
              write(cx.retFlag, lit("true"))))
    // v3's `Prim` and v2's `prim` are the SAME boundary — the one door to the host — so this is a
    // direct mapping, not a call to a global. The first cut emitted `(app (global println) …)` and
    // died with "unbound global": in a bare `run-ir` there is no prelude to define it, and the
    // oracle's own `println` turns out to be a def wrapping `(prim io.println …)`. A module names
    // v2 prim spellings (`io.println`) in its prim table.
    case Instr.Prim(d, p, as)     => write(d, "(prim " + cx.m.prims(p) + args(as) + ")")
    case other                    => throw Unsupported(Text.opcode(other))

  private def args(as: List[Int]): String =
    if as.isEmpty then "" else " " + as.map(read).mkString(" ")

  /** Statements in one region. Everything after an instruction that MAY return is wrapped in a
    * guard, and everything before it is not — straight-line code pays nothing. */
  private def seqOf(body: List[Instr], cx: Ctx): String =
    var out: List[String] = Nil
    var guarded = false
    body.foreach { i =>
      val s = stmt(i, cx)
      out = (if guarded then "(if " + read(cx.retFlag) + " " + lit("unit") + " " + s + ")" else s) :: out
      if mayReturn(i) then guarded = true
    }
    if out.isEmpty then lit("unit") else sq(out.reverse)

  private def func(m: Module, f: Func): String =
    val cx = Ctx(m, f)
    // `Array.fill(n)(0)` — the frame. This is why SSC3-1 was on the critical path: V-0 stands on a
    // working mutable array, and `new Array[T](n)` was building one slot.
    val alloc =
      "(prim __method__ " + lit("(str \"fill\")") + " (ctor Array) " + int(cx.frameSize) + " " + int(0) + ")"
    // Parameters arrive as lam binders, innermost LAST: inside `(lam P …)` param i is `local (P-1-i)`,
    // and the frame's `let` shifts every one of them by one. Measured against the oracle, not
    // reasoned about: `(lam 2 …)` puts the FIRST parameter at `local 1`.
    val prologue =
      (0 until f.nparams).toList.map(i => write(i, "(local " + (f.nparams - i) + ")")) :+
        write(cx.retFlag, lit("false"))
    val bodyText = seqOf(f.body, cx)
    val whole = sq(prologue :+ bodyText :+ read(cx.retVal))
    "(def " + f.name + " (lam " + f.nparams + " (let (" + alloc + ") " + whole + ")))"

  /** The Core IR program text v2's Reader accepts. Verify BEFORE calling this — translating an
    * unverified module would hand v2 something no one has checked (invariant I-4). */
  def program(m: Module): String =
    val defs = m.funcs.map(f => func(m, f)).mkString(" ")
    val entryName = m.funcs(m.entry).name
    "(program (defs " + defs + ") (entry (app (global " + entryName + "))))"
