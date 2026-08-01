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
  private def sq(parts: List[String]): String =
    if parts.isEmpty then "(lit unit)" else "(seq " + parts.mkString(" ") + ")"
  private def lit(s: String): String = "(lit " + s + ")"
  private def int(i: Int): String = lit("(int " + i + ")")

  /** The frame is `local 0` throughout: it is bound by ONE `let` and nothing else introduces a
    * binder, because every statement goes into a `seq`, which evaluates in the SAME environment.
    * That is what keeps de Bruijn indices from shifting under the translation — the single fact
    * that makes V-0 a mapping rather than an index-tracking exercise. */
  /** `sh` is how many binders sit between here and the frame's `let`.
    *
    * It is 0 nearly everywhere, because `seq` evaluates in the SAME environment and nothing else
    * introduces a binder — that is the invariant the whole translation rests on. A `match` ARM is
    * the one exception: v2 binds the constructor's fields as locals, so inside an arm of arity k
    * the frame has moved to `local k`. Threading it explicitly is cheaper than the alternative,
    * which is remembering that one construct is different. */
  private def frameAt(sh: Int): String = "(local " + sh + ")"

  private def read(r: Int, sh: Int): String = "(app " + frameAt(sh) + " " + int(r) + ")"
  private def write(r: Int, v: String, sh: Int): String =
    "(prim __method__ " + lit("(str \"update\")") + " " + frameAt(sh) + " " + int(r) + " " + v + ")"

  private def arith(op: String, a: String, b: String): String =
    "(prim __arith__ " + lit("(str \"" + op + "\")") + " " + a + " " + b + ")"

  private def ifThen(c: String, t: String, e: String): String = "(if " + c + " " + t + " " + e + ")"

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

  private def binName(o: BinOp): String = o match
    case BinOp.Add => "+"; case BinOp.Sub => "-"; case BinOp.Mul => "*"
    case BinOp.Div => "/"; case BinOp.Rem => "%"
    case BinOp.Lt => "<"; case BinOp.Le => "<="; case BinOp.Gt => ">"
    case BinOp.Ge => ">="; case BinOp.Eq => "=="; case BinOp.Ne => "!="
    // The bitwise family has no measured `__arith__` spelling yet, so it is REFUSED rather than
    // guessed. A wrong operator name would lower to a runtime miss far from here.
    case other => throw Unsupported("the " + other + " operator")

  // ── control ─────────────────────────────────────────────────────────────────
  //
  // ONE control register carries everything the target lacks, because v2 has neither `break` nor an
  // early `return`:
  //
  //     CTL =  0   running normally
  //     CTL = -1   the function has returned; the value is in RET_VAL
  //     CTL =  n   branching, with n region boundaries still to unwind
  //
  // A statement runs only when CTL is 0, and the END of every region does `if CTL > 0 then CTL-1`.
  // That single pair implements `Br` at any depth, `BrIf`, and early `Ret` at once: a return sets
  // -1, which no decrement ever consumes, so it propagates out of every enclosing region untouched.
  //
  // Two flags would have been the obvious encoding and would need two guards on every statement and
  // a rule for what happens when both are set. One register has no such rule to get wrong.

  private final case class Ctx(m: Module, f: Func):
    val retVal: Int = f.nregs
    val ctl: Int = f.nregs + 1
    /** One slot per LOOP NESTING DEPTH, not per loop: sibling loops are never live at the same
      * time, so they share. That is why no counter has to be threaded through the translation. */
    val loopBase: Int = f.nregs + 2
    val frameSize: Int = loopBase + maxLoopDepth(f.body)

  private def maxLoopDepth(body: List[Instr]): Int =
    var best = 0
    body.foreach { i =>
      val d = i match
        case Instr.Loop(b) => 1 + maxLoopDepth(b)
        case other         => maxLoopDepth(Instr.children(other))
      if d > best then best = d
    }
    best

  private def ctlIs(cx: Ctx, op: String, v: Int, sh: Int): String = arith(op, read(cx.ctl, sh), int(v))
  private def running(cx: Ctx, sh: Int): String = ctlIs(cx, "==", 0, sh)

  /** End of a region: consume one level of an in-flight branch. `-1` (returned) is deliberately
    * untouched — that is what makes a return leave every enclosing region without a special case. */
  private def endRegion(cx: Ctx, sh: Int): String =
    ifThen(ctlIs(cx, ">", 0, sh), write(cx.ctl, arith("-", read(cx.ctl, sh), int(1)), sh), lit("unit"))

  /** Does this instruction possibly leave CTL non-zero? Everything after one that can must be
    * guarded. Conservative on purpose: a `Br` that targets an inner region cannot actually escape,
    * but proving that is dataflow, and an extra guard costs speed where a missing one costs
    * correctness. */
  private def mayDivert(i: Instr): Boolean = i match
    case _: Instr.Ret      => true
    case _: Instr.TailCall => true
    case _: Instr.Br       => true
    case _: Instr.BrIf     => true
    case other             => Instr.children(other).exists(mayDivert)

  // ── instructions ────────────────────────────────────────────────────────────
  private def stmt(i: Instr, cx: Ctx, sh: Int): String = i match
    case Instr.Const(d, k)        => write(d, litOf(cx.m.consts(k)), sh)
    case Instr.Move(d, a)         => write(d, read(a, sh), sh)
    case Instr.Bin(o, _, d, a, b) => write(d, arith(binName(o), read(a, sh), read(b, sh)), sh)
    case Instr.Un(UnOp.Neg, _, d, a) => write(d, arith("-", int(0), read(a, sh)), sh)
    // `not x` as `x == false`, built from operators already proven on this lane rather than from a
    // guessed `__arith__` spelling for `!`. A wrong operator name lowers to a runtime miss far from
    // here, which is the failure this whole file is written to avoid.
    case Instr.Un(UnOp.Not, _, d, a) => write(d, arith("==", read(a, sh), lit("false")), sh)

    case Instr.If(c, t, e) =>
      // An `If` is a branchable region in this IR (the verifier counts it), so both arms end with
      // the same decrement a block does. Skipping that would make `br 0` inside an if mean
      // something different from `br 0` inside a block, for no reason a reader could guess.
      ifThen(read(c, sh),
             sq(List(seqOf(t, cx, sh), endRegion(cx, sh))),
             sq(List(seqOf(e, cx, sh), endRegion(cx, sh))))
    case Instr.Block(b) => sq(List(seqOf(b, cx, sh), endRegion(cx, sh)))
    case Instr.Loop(b) =>
      // A WASM loop does NOT repeat by falling off its end — only a `br` to it repeats. So the
      // while-condition is a per-depth "go round again" slot, set false at the top of every
      // iteration and set true only by the branch check at the bottom.
      val slot = cx.loopBase + maxLoopDepth(b)
      val again = ifThen(ctlIs(cx, "==", 1, sh),
                         sq(List(write(cx.ctl, int(0), sh), write(slot, lit("true"), sh))),
                         endRegion(cx, sh))
      sq(List(write(slot, lit("true"), sh),
              "(while " + read(slot, sh) + " " +
                sq(List(write(slot, lit("false"), sh), seqOf(b, cx, sh), again)) + ")"))
    case Instr.Br(d)      => write(cx.ctl, int(d + 1), sh)
    case Instr.BrIf(c, d) => ifThen(read(c, sh), write(cx.ctl, int(d + 1), sh), lit("unit"))

    case Instr.Ret(a) => sq(List(write(cx.retVal, read(a, sh), sh), write(cx.ctl, int(-1), sh)))
    case Instr.Call(d, fi, as) =>
      write(d, "(app (global " + cx.m.funcs(fi).name + ")" + args(as, sh) + ")", sh)
    // V-0 does NOT make this a tail call — v2 gives no TCO, so the constant-stack guarantee is one
    // of the three things only our own executor (SSC3-3b) can deliver. Correct, not constant-stack.
    case Instr.TailCall(fi, as) =>
      sq(List(write(cx.retVal, "(app (global " + cx.m.funcs(fi).name + ")" + args(as, sh) + ")", sh),
              write(cx.ctl, int(-1), sh)))
    // v3's `Prim` and v2's `prim` are the SAME boundary — the one door to the host — so this is a
    // direct mapping, not a call to a global. The first cut emitted `(app (global println) …)` and
    // died with "unbound global": in a bare `run-ir` there is no prelude to define it, and the
    // oracle's own `println` turns out to be a def wrapping `(prim io.println …)`.
    // The name comes from the pool at TRANSLATION time, which is the whole reason it is a const
    // index: v2 wants `(lit (str "…"))` here, a literal rather than something read from a frame.
    case Instr.Invoke(d, nm, r, as) =>
      val mname = cx.m.consts(nm) match
        case Lit.LStr(x) => x
        case _           => throw Unsupported("an invoke whose name const is not a string")
      write(d, "(prim __method__ " + lit("(str " + quote(mname) + ")") + " " + read(r, sh) +
              args(as, sh) + ")", sh)
    case Instr.Prim(d, p, as) => write(d, "(prim " + cx.m.prims(p) + args(as, sh) + ")", sh)

    // ── data ────────────────────────────────────────────────────────────────
    // The tag space IS the type table: `MkData`'s `t`, `Field`'s `t` and a `SwitchArm`'s `tag` all
    // index the same list. One space rather than two removes the question of how they correspond.
    case Instr.MkData(d, t, as) =>
      write(d, "(ctor " + cx.m.types(t).name + args(as, sh) + ")", sh)
    // A field read is a ONE-ARM match. v2 has no field-by-index accessor, and going through the
    // matcher means the layout consulted is v2's own — there is no second notion of "field 1 of
    // Box" to disagree with the type table. Inside an arm of arity k, field i is `local (k-1-i)`,
    // the same innermost-last convention `lam` uses. No shift reaches the arm body here because
    // the body IS the bound local; the enclosing `write` happens outside the match.
    case Instr.Field(d, a, t, idx) =>
      val td = cx.m.types(t)
      write(d, "(match " + read(a, sh) + " ((arm " + td.name + " " + td.fields + " (local " +
              (td.fields - 1 - idx) + "))))", sh)
    // Likewise a match, one arm per declared type, each answering its own index. v2 exposes no tag
    // primitive, and inventing one would mean a second tag space.
    case Instr.Tag(d, a) =>
      val arms = cx.m.types.zipWithIndex
        .map((td, i) => "(arm " + td.name + " " + td.fields + " " + int(i) + ")")
        .mkString(" ")
      write(d, "(match " + read(a, sh) + " (" + arms + "))", sh)
    case Instr.Switch(scrut, arms, dflt) =>
      // The arm bodies are the ONE place the frame moves: v2 binds the constructor's fields, so
      // everything inside is translated at shift + arity. `nested-loop` proves the loop slots; this
      // is the case that would catch getting the shift wrong.
      val armText = arms.map { a =>
        val td = cx.m.types(a.tag)
        "(arm " + td.name + " " + td.fields + " " +
          sq(List(seqOf(a.body, cx, sh + td.fields), endRegion(cx, sh + td.fields))) + ")"
      }.mkString(" ")
      "(match " + read(scrut, sh) + " (" + armText + ") (default " +
        sq(List(seqOf(dflt, cx, sh), endRegion(cx, sh))) + "))"

    // ── arrays ──────────────────────────────────────────────────────────────
    // The same shapes the frame itself is built from, which is the strongest evidence they are
    // right: every program translated so far already exercises them.
    case Instr.NewArr(d, n) =>
      write(d, "(prim __method__ " + lit("(str \"fill\")") + " (ctor Array) " + read(n, sh) + " " +
              int(0) + ")", sh)
    case Instr.ArrGet(d, a, ix) => write(d, "(app " + read(a, sh) + " " + read(ix, sh) + ")", sh)
    case Instr.ArrSet(a, ix, v) =>
      "(prim __method__ " + lit("(str \"update\")") + " " + read(a, sh) + " " + read(ix, sh) +
        " " + read(v, sh) + ")"
    case Instr.ArrLen(d, a) =>
      write(d, "(prim __method__ " + lit("(str \"length\")") + " " + read(a, sh) + ")", sh)

    case other => throw Unsupported(Text.opcode(other))

  private def args(as: List[Int], sh: Int): String =
    if as.isEmpty then "" else " " + as.map(r => read(r, sh)).mkString(" ")

  /** Statements in one region. Everything after an instruction that may divert is wrapped in a
    * guard; everything before it is not, so straight-line code pays nothing. */
  private def seqOf(body: List[Instr], cx: Ctx, sh: Int): String =
    var out: List[String] = Nil
    var guarded = false
    body.foreach { i =>
      val s = stmt(i, cx, sh)
      out = (if guarded then ifThen(running(cx, sh), s, lit("unit")) else s) :: out
      if mayDivert(i) then guarded = true
    }
    sq(out.reverse)

  private def func(m: Module, f: Func): String =
    val cx = Ctx(m, f)
    // `Array.fill(n)(0)` — the frame. This is why SSC3-1 was on the critical path rather than
    // beside it: V-0 stands on a working mutable array, and `new Array[T](n)` was building one slot.
    val alloc =
      "(prim __method__ " + lit("(str \"fill\")") + " (ctor Array) " + int(cx.frameSize) + " " + int(0) + ")"
    // Parameters arrive as lam binders, innermost LAST: inside `(lam P …)` param i is `local (P-1-i)`,
    // and the frame's `let` shifts every one of them by one. Measured against the oracle, not
    // reasoned about: `(lam 2 …)` puts the FIRST parameter at `local 1`.
    val prologue =
      (0 until f.nparams).toList.map(i => write(i, "(local " + (f.nparams - i) + ")", 0)) :+
        write(cx.ctl, int(0), 0)
    val whole = sq(prologue :+ seqOf(f.body, cx, 0) :+ read(cx.retVal, 0))
    "(def " + f.name + " (lam " + f.nparams + " (let (" + alloc + ") " + whole + ")))"

  /** The Core IR program text v2's Reader accepts. Verify BEFORE calling this — translating an
    * unverified module would hand v2 something no one has checked (invariant I-4). */
  def program(m: Module): String =
    val defs = m.funcs.map(f => func(m, f)).mkString(" ")
    val entryName = m.funcs(m.entry).name
    "(program (defs " + defs + ") (entry (app (global " + entryName + "))))"
