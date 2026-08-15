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

  /** Methods v3's EXECUTOR has and v2 does not — measured, one at a time, by running the program
    * through `ssc3 run --bridge` and reading what v2 said.
    *
    * `__lazyFrom__` can never be v2's: it is v3's own lowering of `LazyList.from`. `to` and `until`
    * are ordinary Scala methods that v2 simply does not dispatch today, so if it gains them the
    * name comes out of this list and the refusal stops.
    *
    * Not a general answer to "what does v2 implement" — nothing here knows that. It is the set that
    * turned a crash into a sentence, and it grows the same way it was built: by measurement.
    */
  private val executorOnlyMethods: Set[String] = Set("__lazyFrom__", "to", "until")

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

  // SSC3-3c, the cheap half. V-0's register access went through the GENERIC dispatchers: a read was
  // `(app frame idx)`, which walks v2's application path, and a write was
  // `(prim __method__ "update" frame idx v)`, which dispatches on a STRING method name per store.
  // `arr.get`/`arr.set` are v2's direct array prims and land on the same `ForeignV(ArrayBuffer)` the
  // frame already is — `Array.fill` answers one (`Runtime.scala`, `case (DataV("Array", _), "fill",
  // …)`), so this is a spelling change, not a representation change.
  //
  // It is NOT the `Let`-binding rewrite the SSC3-3c entry describes, and it does not pretend to be:
  // the frame is still one mutable array and there are still two prim calls per register access. It
  // is the part of that entry's win that costs no SSA and no join analysis.
  private def read(r: Int, sh: Int): String =
    "(prim arr.get " + frameAt(sh) + " " + int(r) + ")"
  private def write(r: Int, v: String, sh: Int): String =
    "(prim arr.set " + frameAt(sh) + " " + int(r) + " " + v + ")"

  private def arith(op: String, a: String, b: String): String =
    "(prim __arith__ " + lit("(str \"" + op + "\")") + " " + a + " " + b + ")"

  /** v2's direct array prims, spelled once. The frame, the handler stack and every effect record
    * are the SAME representation — `ForeignV(ArrayBuffer)` — so one set of helpers serves all
    * three, and a reader who has understood the frame has understood the effect runtime. */
  private def glob(n: String): String = "(global " + n + ")"
  private def aget(a: String, i: String): String = "(prim arr.get " + a + " " + i + ")"
  private def aset(a: String, i: String, v: String): String =
    "(prim arr.set " + a + " " + i + " " + v + ")"
  private def alen(a: String): String = "(prim arr.len " + a + ")"
  private def mkarr(xs: List[String]): String =
    "(prim __mk_arr__" + (if xs.isEmpty then "" else " " + xs.mkString(" ")) + ")"

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
    case other => throw Unsupported("the " + other + " operator")

  /** The bitwise family is NOT `__arith__` — it is a set of direct prims, `i.shl` / `i.shr` /
    * `i.ushr` / `i.and` / `i.or` / `i.xor`. Measured off the oracle, which is why it took until now:
    * the bridge refused these rather than guess a spelling, and a guessed operator name would have
    * lowered to a runtime miss far from the instruction that caused it. */
  private def bitPrim(o: BinOp): String = o match
    case BinOp.BAnd => "i.and"; case BinOp.BOr => "i.or"; case BinOp.BXor => "i.xor"
    case BinOp.Shl  => "i.shl"; case BinOp.Shr => "i.shr"; case BinOp.UShr => "i.ushr"
    case other      => throw Unsupported("the " + other + " operator")

  private def isBitwise(o: BinOp): Boolean = o match
    case BinOp.BAnd | BinOp.BOr | BinOp.BXor | BinOp.Shl | BinOp.Shr | BinOp.UShr => true
    case _ => false

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
    /** How wide an effect record's per-operation arrays are. Module-wide rather than per-handler,
      * because `Perform` indexes them with an operation number it carries and every record it walks
      * past must be able to answer "not mine" at that index. `lazy` — a module without effects never
      * asks, and asking costs a whole-module scan. */
    lazy val nOps: Int = opCount(m)
    /** Reads and writes per register, over the WHOLE function — computed once, because `seqOf` runs
      * per region and recomputing it there would be quadratic in the nesting. */
    lazy val census: (Array[Int], Array[Int]) =
      val reads = new Array[Int](f.nregs)
      val writes = new Array[Int](f.nregs)
      Instr.flatten(f.body).foreach { i =>
        writesOf(i).foreach(d => if d >= 0 && d < f.nregs then writes(d) = writes(d) + 1)
        readsOf(i).foreach(r => if r >= 0 && r < f.nregs then reads(r) = reads(r) + 1)
      }
      (reads, writes)

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

  /** The same, ELIDED when the region it closes cannot have set CTL.
    *
    * A region only ever runs with CTL at 0 — `seqOf` guards every statement after a diverting one,
    * a loop's repeat path resets it, and a function's prologue starts it there — so if nothing
    * inside the region writes CTL either, the decrement is reading a register it already knows is
    * zero. Two operations, and they are paid on the INSIDE of every `if` in every loop: measured on
    * a `while` with an `if` in it, 2 of the ~21 operations an iteration actually executes.
    *
    * Under the same switch as the structured `while` rather than a fourth flag of its own. Both are
    * the one idea — do not emit CTL machinery that cannot fire — and `--no-structured-loops` puts
    * every last piece of it back, which is what an OFF arm is for. */
  private def endRegionOf(body: List[Instr], cx: Ctx, sh: Int): String =
    if structuredLoops && !body.exists(mayDivert) then lit("unit") else endRegion(cx, sh)

  // ── recovering the `while` ──────────────────────────────────────────────────
  //
  // THE CTL FLAG IS THE BRIDGE'S DOMINANT COST, and it is paid where it hurts most. Counted on
  // `arith-loop`'s loop body, the text that runs a million times: of 30 v2 prim operations, TWENTY
  // are the apparatus — four `ctl == 0` guards at two operations each, the per-depth "go round
  // again" slot, the `ctl == 1` test at the bottom and the `endRegion` decrement. Six do the work.
  //
  // But the lowering emits exactly ONE shape for a `while`, and v2 HAS `while`:
  //
  //     (block (loop  <cond…>  (brif c 1)  <body…>  (br 0)))
  //
  // — leave the enclosing block when the condition holds, go round again otherwise. When neither
  // `cond` nor `body` diverts by any OTHER route, that is a `while` and nothing about it needs a
  // flag. The `brif` becomes the loop condition negated, the `br 0` disappears, and because the
  // structured form never writes CTL, `mayDivert` answers false for the whole loop — so the
  // statements AFTER it lose their guards too.
  //
  // This is a peephole on a shape the lowering guarantees, not a relooper. Anything that does not
  // match — a `return` inside the loop, a `br` to an outer depth, two exits — falls back to the CTL
  // form unchanged, which is why the fallback is worth keeping rather than replacing.
  private var structuredLoops: Boolean = true
  private[ssc3] def useStructuredLoops(on: Boolean): Unit = structuredLoops = on

  /** `(lead… (loop pre… (brif c 1) body… (br 0)))` → the four pieces of a `while`.
    *
    * `lead` is NOT always empty, and assuming it was is why the first version of this matched
    * nothing: `Optimize`'s loop-invariant lift (SSC3-J4a) moves a `Const` OUT of the loop and into
    * the enclosing list, which is the BLOCK's body. So the shape after optimisation is
    * `Block(hoisted… , Loop(…))`, and a pattern demanding `Block(List(Loop(…)))` is a pattern for
    * the un-optimised program — the one lane nobody runs. `brif 1` still exits the block, which is
    * still the statement after the loop, so `lead` changes nothing about the meaning. */
  private object WhileShape:
    def unapply(i: Instr): Option[(List[Instr], List[Instr], Int, List[Instr])] = i match
      case Instr.Block(bs) if bs.nonEmpty =>
        bs.last match
          case Instr.Loop(is) =>
            val at = is.indexWhere { case Instr.BrIf(_, 1) => true; case _ => false }
            if at < 0 || at >= is.length - 1 then None
            else
              (is(at), is.last) match
                case (Instr.BrIf(c, _), Instr.Br(0)) =>
                  Some((bs.init, is.take(at), c, is.slice(at + 1, is.length - 1)))
                case _ => None
          case _ => None
      case _ => None

  /** Is this the while shape AND will it actually be emitted as one? Both halves matter: a loop
    * whose body returns still has to carry the flag, so its `Br`/`BrIf` are still real. */
  private def isStructuredWhile(i: Instr): Boolean = structuredLoops && (i match
    case WhileShape(lead, pre, _, body) =>
      !lead.exists(mayDivert) && !pre.exists(mayDivert) && !body.exists(mayDivert)
    case _ => false)

  /** Does this instruction possibly leave CTL non-zero? Everything after one that can must be
    * guarded. Conservative on purpose: a `Br` that targets an inner region cannot actually escape,
    * but proving that is dataflow, and an extra guard costs speed where a missing one costs
    * correctness. */
  private def mayDivert(i: Instr): Boolean = i match
    // A STRUCTURED `while` CONSUMES ITS OWN `br`s, so it leaves CTL alone — and this line is what
    // lets the statements after a loop drop their guards, which is half of what the rewrite buys.
    // It is checked FIRST because the generic rule below would find the `brif` inside and say true.
    case i2 if isStructuredWhile(i2) => false
    case _: Instr.Ret      => true
    case _: Instr.TailCall => true
    case _: Instr.Br       => true
    case _: Instr.BrIf     => true
    // An ARM'S `ret` DIVERTS THE CLONE, NOT THIS FRAME — every arm body ends with one, so the
    // generic `children` rule would mark every `handle` as diverting and guard the whole rest of the
    // function behind a `ctl == 0` test that can never be false for that reason. Only the BODY runs
    // against this frame, so only the body is asked. Correct either way; this one is not slow.
    case Instr.Handle(_, b, _) => b.exists(mayDivert)
    case other             => Instr.children(other).exists(mayDivert)

  // ── effects ─────────────────────────────────────────────────────────────────
  //
  // WHAT THIS IS NOT: v2's own effect primitives. `effect.perform` / `effect.handle` implement
  // effects by THREADING an `Op` value out through evaluation and rebuilding the continuation from
  // v2's own term tree. Feeding a bridged program into that would put two continuation mechanisms in
  // series — v2 capturing a continuation over the register frame it knows nothing about — and the
  // frame is one MUTABLE array, so a continuation resumed twice would see the first resumption's
  // stores. That is a wrong answer, not a refusal, and it is the failure mode this whole file exists
  // to avoid. Measured first, not assumed: `v3/tests/effects/multi-shot.ssc` resumes twice.
  //
  // WHAT IT IS: the executor's own algorithm, emitted. `Cps.split` has ALREADY turned every
  // performing function inside out — read `ssc3 ir` on any effects fixture and a `perform` is
  //
  //     (mkclos 15 3 …)        ← the rest of the function, as an ordinary closure
  //     (perform 5 0 4 15)     ← performed with that closure as its LAST argument
  //
  // so the continuation is a value before the bridge ever sees it. Nothing here captures anything.
  // What is left is DYNAMICALLY-SCOPED DISPATCH: find the innermost handler with an arm for this
  // operation, run the arm, hand back its value. That needs three things v2 already has — a global
  // array used as a stack, closures, and `arr.slice` to copy a frame — and no new v2 primitive.
  //
  // THE SHAPE, one record per live `handle`, pushed on `__ssc3_eff_stack__`:
  //
  //     [0] arms      one slot per operation index: the arm's closure, or unit
  //     [1] handles   the same width, `true` where this handler has an arm
  //     [2] ret       the `case x => …` clause as a closure, or unit
  //     [3] hasRet    whether [2] is real
  //     [4] performs  a one-element array used as a counter
  //
  // `handles` is a separate BOOLEAN array rather than a null check on `arms` because the test has to
  // be `(if …)`-shaped: comparing a closure against a sentinel with `__arith__ "=="` asks v2 to
  // decide equality on a `ClosV`, which is a question with no good answer.
  //
  // WHY A COUNTER AND NOT A FLAG: `performs` is what makes the return clause apply EXACTLY ONCE, and
  // a boolean does not survive nesting — an inner `resume` clears what the outer one is about to
  // read, and `handle-return` comes out `List(List(List(11, 21), …))`. Read before and after, which
  // is what `Exec` does and why that file says so at length.

  private val effStack    = "__ssc3_eff_stack__"
  private val effPerform  = "__ssc3_eff_perform__"
  private val effFind     = "__ssc3_eff_find__"
  private val effTop      = "__ssc3_eff_top__"
  private val effPerforms = "__ssc3_eff_performs__"
  private val effAfter    = "__ssc3_eff_after_resume__"

  private val R_ARMS = 0
  private val R_HANDLES = 1
  private val R_RET = 2
  private val R_HASRET = 3
  private val R_PERFORMS = 4

  private def scanAll(m: Module)(f: Instr => Unit): Unit =
    def go(body: List[Instr]): Unit = body.foreach { i => f(i); go(Instr.children(i)) }
    m.funcs.foreach(fn => go(fn.body))

  /** One past the highest operation index the module mentions — from `Perform`s AND from arms, since
    * a handler may be written for an operation this module never performs. `-1` is the return
    * clause's marker and is deliberately not counted. */
  private def opCount(m: Module): Int =
    var best = -1
    scanAll(m) {
      case Instr.Perform(_, op, _)  => if op > best then best = op
      case Instr.Handle(_, _, arms) => arms.foreach(a => if a.op > best then best = a.op)
      case _                        => ()
    }
    best + 1

  private def usesEffects(m: Module): Boolean =
    var found = false
    scanAll(m) {
      case _: Instr.Handle | _: Instr.Perform | _: Instr.Resume => found = true
      case _                                                    => ()
    }
    found

  /** A fresh copy of the handling function's frame, per activation.
    *
    * NOT an optimisation to skip. An arm reads its `params` and `k` from the HANDLING function's
    * registers (`specs/10-ssc-ir.md` §3), and one array cannot serve two activations at once —
    * `case op(k) => k(1) + k(10)` over a function that performs twice re-enters the arm while the
    * outer activation is still live. The executor measured that as 8 where the answer is 12: a wrong
    * answer. `arr.slice a 0 (len a)` is v2's copy — `ArrayBuffer.from(_.slice(…))`, a new buffer. */
  private def cloneOf(src: String): String =
    "(prim arr.slice " + src + " " + int(0) + " " + alen(src) + ")"

  /** An operation arm as a one-argument closure: it takes the perform's ARGUMENT ARRAY and returns
    * the arm's value. Inside `(lam 1 …)` the argument is `local 0` and the enclosing frame has moved
    * to `local (sh+1)`; inside the `let` the clone is `local 0` and the arguments `local 1`, which is
    * why the body is translated at shift 0 — the frame it means IS the clone. */
  private def armClos(cx: Ctx, arm: HandlerArm, sh: Int): String =
    val binds =
      arm.params.zipWithIndex.map((p, i) => aset("(local 0)", int(p), aget("(local 1)", int(i)))) ++
        List(aset("(local 0)", int(arm.k), aget("(local 1)", int(arm.params.length))),
             aset("(local 0)", int(cx.ctl), int(0)))
    "(lam 1 (let (" + cloneOf("(local " + (sh + 1) + ")") + ") " +
      sq(binds ++ List(seqOf(arm.body, cx, 0), aget("(local 0)", int(cx.retVal)))) + "))"

  /** The `case x => …` clause, likewise a one-argument closure — but it takes the VALUE, not an
    * argument array, because that is how `Exec.applyRet` calls it. `retVal` is seeded with that value
    * so a clause body that falls off its end answers the value unchanged, which is the `case _ => v`
    * arm of `applyRet` written in the target. */
  private def retClos(cx: Ctx, arm: HandlerArm, sh: Int): String =
    val pre = arm.params.headOption.toList.map(p => aset("(local 0)", int(p), "(local 1)")) ++
      List(aset("(local 0)", int(cx.ctl), int(0)), aset("(local 0)", int(cx.retVal), "(local 1)"))
    "(lam 1 (let (" + cloneOf("(local " + (sh + 1) + ")") + ") " +
      sq(pre ++ List(seqOf(arm.body, cx, 0), aget("(local 0)", int(cx.retVal)))) + "))"

  /** THE SECOND ENCODING, and it is not a fallback — it is what the lowering emits whenever it
    * cannot split a performing function, most often because the `perform` is inside a `while`.
    * `handle-tail-resumptive` and `runner-in-the-language` are both this shape, so a bridge that
    * only did CPS would refuse two of the twelve effects fixtures.
    *
    * Here the `perform` carries NO continuation: the arm computes a value, that value IS the
    * perform's result, and the performing function simply carries on. `Exec` implements it by
    * writing `resumedWith` from a `Resume` whose `k` register holds `unit` — which means, at IR
    * level, that `resume(d, k, v)` and `move(d, v)` do the same thing. So the arm is translated by
    * REWRITING it into ordinary instructions rather than by a second runtime protocol.
    *
    * The arm runs in the handler's OWN frame, not a copy, because `Exec` does: a later read of a
    * register the arm wrote must see the same thing on both lanes. Only `ctl` is saved and restored
    * — the arm's trailing `ret` would otherwise tell the HANDLING function it had returned, which on
    * the executor is a `Signal` the `Perform` discards and here would be a store nothing undoes. */
  private def isTailResumptive(arm: HandlerArm): Boolean =
    def countResumes(body: List[Instr]): Int =
      body.map { case Instr.Resume(_, _, _) => 1; case o => countResumes(Instr.children(o)) }.sum
    val body = arm.body match
      case init :+ Instr.Ret(_) => init
      case other                => other
    body.nonEmpty && (body.last match
      case Instr.Resume(_, _, _) => countResumes(arm.body) == 1
      case _                     => false)

  private def deResume(body: List[Instr]): List[Instr] = body.map {
    case Instr.Resume(d, _, v)      => Instr.Move(d, v)
    case Instr.Block(b)             => Instr.Block(deResume(b))
    case Instr.Loop(b)              => Instr.Loop(deResume(b))
    case Instr.If(c, t, e)          => Instr.If(c, deResume(t), deResume(e))
    case Instr.Switch(s, arms, df)  => Instr.Switch(s, arms.map(a => a.copy(body = deResume(a.body))), deResume(df))
    case Instr.Try(d, b, x, h)      => Instr.Try(d, deResume(b), x, deResume(h))
    case Instr.Handle(d, b, arms)   => Instr.Handle(d, deResume(b), arms.map(a => a.copy(body = deResume(a.body))))
    case other                      => other
  }

  private def tailArmClos(cx: Ctx, arm: HandlerArm, sh: Int): String =
    if !isTailResumptive(arm) then
      throw Unsupported(
        "a handler for operation " + arm.op + " that neither takes a continuation nor is " +
        "tail-resumptive — its last act must be a single `resume`. This is the same shape v3's " +
        "own executor refuses, and for the same reason: capturing the continuation here needs a " +
        "reified stack neither lane has")
    val fr = "(local " + (sh + 2) + ")"
    val binds =
      arm.params.zipWithIndex.map((p, i) => aset(fr, int(p), aget("(local 1)", int(i)))) ++
        List(aset(fr, int(arm.k), lit("unit")), aset(fr, int(cx.ctl), int(0)))
    // Read the answer, put `ctl` back, hand the answer over — in that order, because restoring
    // first would overwrite `retVal`'s guard and reading after would read the restored frame.
    val restore = "(let (" + aget(fr, int(cx.retVal)) + ") " +
      sq(List(aset("(local " + (sh + 3) + ")", int(cx.ctl), "(local 1)"), "(local 0)")) + ")"
    "(lam 1 (let (" + aget("(local " + (sh + 1) + ")", int(cx.ctl)) + ") " +
      sq(binds ++ List(seqOf(deResume(arm.body), cx, sh + 2), restore)) + "))"

  /** Which of the two encodings this arm is reached by — decided from the MODULE, because the arm
    * alone cannot say. The `perform` carries the answer in its argument count, and every `perform`
    * of the operation must agree; a module mixing both for one operation is refused rather than
    * guessed at. An arm no `perform` reaches is translated as CPS and never runs. */
  private def armIsCps(cx: Ctx, arm: HandlerArm): Boolean =
    var counts: Set[Int] = Set.empty
    scanAll(cx.m) {
      case Instr.Perform(_, op, as) => if op == arm.op then counts = counts + as.length
      case _                        => ()
    }
    if counts.isEmpty || counts == Set(arm.params.length + 1) then true
    else if counts == Set(arm.params.length) then false
    else
      throw Unsupported(
        "operation " + arm.op + " performed with " + counts.toList.sorted.mkString("/") +
        " argument(s) against one handler binding " + arm.params.length + " — the bridge needs " +
        "every `perform` of an operation to use one encoding")

  /** A `perform` whose operation NO handler in the module answers. The executor raises this at run
    * time; refusing here is strictly narrower — a handler that exists may still not be installed —
    * and it turns what would otherwise be an uncaught v2 exception with a Java stack trace into the
    * one-line refusal `corpus-report.sh` can classify. */
  private def performIsAnswerable(cx: Ctx, op: Int): Unit =
    var found = false
    scanAll(cx.m) {
      case Instr.Handle(_, _, arms) => if arms.exists(_.op == op) then found = true
      case _                        => ()
    }
    if !found then
      throw Unsupported(
        "a `perform` of operation " + op + ", which no `handle` in this module answers — the " +
        "executor reports this as `no handler for effect operation " + op + "` when it runs")

  /** The six defs the effect runtime needs, emitted once per module and only when something uses
    * them. Written here as text rather than as a prelude file because the bridge's whole contract is
    * that `emit-v2` output is self-contained — `run-ir` installs no prelude. */
  private def effectDefs: String =
    val stack = glob(effStack)
    // `(def x (prim arr.new))` is evaluated ONCE at load, the same fact the global cells rest on.
    val defStack = "(def " + effStack + " (prim arr.new))"
    val defTop = "(def " + effTop + " (lam 0 " + aget(stack, arith("-", alen(stack), int(1))) + "))"
    val defPerforms = "(def " + effPerforms + " (lam 0 " +
      ifThen(arith(">", alen(stack), int(0)),
             aget(aget("(app " + glob(effTop) + ")", int(R_PERFORMS)), int(0)),
             int(0)) + "))"
    // `(lam 3)` — i, op, args — so i is `local 2`, op `local 1`, args `local 0`; the `let` that binds
    // the record shifts each by one. Recursion rather than a loop because a loop would need a mutable
    // cursor, and handler stacks are a handful of frames deep however long the program runs.
    val defFind = "(def " + effFind + " (lam 3 " +
      ifThen(arith("<", "(local 2)", int(0)),
             "(prim __throw__ " + lit("(str \"no handler for effect operation\")") + ")",
             "(let (" + aget(stack, "(local 2)") + ") " +
               ifThen(aget(aget("(local 0)", int(R_HANDLES)), "(local 2)"),
                      sq(List(
                        // The counter belongs to the handler that ANSWERS, which need not be the top
                        // of the stack — `Exec` bumps `h.performs` for the handler it found.
                        aset(aget("(local 0)", int(R_PERFORMS)), int(0),
                             arith("+", aget(aget("(local 0)", int(R_PERFORMS)), int(0)), int(1))),
                        "(app " + aget(aget("(local 0)", int(R_ARMS)), "(local 2)") + " (local 1))")),
                      "(app " + glob(effFind) + " " + arith("-", "(local 3)", int(1)) +
                        " (local 2) (local 1))") + ")") + "))"
    // NOT POPPED WHILE THE ARM RUNS. `Exec` leaves the handler on the list, so a `resume` whose
    // continuation performs the same operation again finds the same handler — which is what makes
    // `two-performs-multi-shot` produce a cross product instead of failing on the second perform.
    val defPerform = "(def " + effPerform + " (lam 2 (app " + glob(effFind) + " " +
      arith("-", alen(stack), int(1)) + " (local 1) (local 0))))"
    val defAfter = "(def " + effAfter + " (lam 2 " +
      ifThen(arith("!=", "(app " + glob(effPerforms) + ")", "(local 1)"),
             "(local 0)",
             ifThen(arith(">", alen(stack), int(0)),
                    "(let ((app " + glob(effTop) + ")) " +
                      ifThen(aget("(local 0)", int(R_HASRET)),
                             "(app " + aget("(local 0)", int(R_RET)) + " (local 1))",
                             "(local 1)") + ")",
                    "(local 0)")) + "))"
    List(defStack, defTop, defPerforms, defFind, defPerform, defAfter).mkString(" ")

  // ── instructions ────────────────────────────────────────────────────────────
  private def stmt(i: Instr, cx: Ctx, sh: Int): String = i match
    case Instr.Const(d, k)        => write(d, litOf(cx.m.consts(k)), sh)
    case Instr.Move(d, a)         => write(d, read(a, sh), sh)
    case Instr.Bin(o, _, d, a, b) =>
      if isBitwise(o) then
        write(d, "(prim " + bitPrim(o) + " " + read(a, sh) + " " + read(b, sh) + ")", sh)
      else write(d, arith(binName(o), read(a, sh), read(b, sh)), sh)
    case Instr.Un(UnOp.Neg, _, d, a) => write(d, arith("-", int(0), read(a, sh)), sh)
    // `not x` as `x == false`, built from operators already proven on this lane rather than from a
    // guessed `__arith__` spelling for `!`. A wrong operator name lowers to a runtime miss far from
    // here, which is the failure this whole file is written to avoid.
    case Instr.Un(UnOp.Not, _, d, a) => write(d, arith("==", read(a, sh), lit("false")), sh)
    // NAMED, not left to the catch-all. `Text.opcode` answers "un" for all three of them, and a
    // refusal that says "does not translate un" tells a reader which INSTRUCTION and not which
    // OPERATOR — the same distinction `binName` and `bitPrim` already make one line down.
    case Instr.Un(op, _, _, _) => throw Unsupported("the " + op + " operator")

    case Instr.If(c, t, e) =>
      // An `If` is a branchable region in this IR (the verifier counts it), so both arms end with
      // the same decrement a block does. Skipping that would make `br 0` inside an if mean
      // something different from `br 0` inside a block, for no reason a reader could guess.
      ifThen(read(c, sh),
             sq(List(seqOf(t, cx, sh), endRegionOf(t, cx, sh))),
             sq(List(seqOf(e, cx, sh), endRegionOf(e, cx, sh))))
    // The structured `while`, taken BEFORE the generic `Block` arm it would otherwise match. The
    // condition is `(seq <cond-statements> (c == false))` — v2's `while` re-evaluates its condition
    // term every iteration, so the statements that compute `c` live inside it rather than being
    // duplicated before the loop and again at the end of the body.
    case i2 @ WhileShape(lead, pre, c, body) if isStructuredWhile(i2) =>
      sq(List(seqOf(lead, cx, sh),
              "(while " + condOf(pre, c, cx, sh) + " " + seqOf(body, cx, sh) + ")"))
    case Instr.Block(b) => sq(List(seqOf(b, cx, sh), endRegionOf(b, cx, sh)))
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

    // A closure: capture NOW, apply later. The captured values are bound by a `let` OUTSIDE the
    // `lam`, so they are read at creation time — reading them from the frame inside the lambda
    // would read them at CALL time instead, and a closure built in a loop would then see the last
    // iteration's values. That is a real difference, not a stylistic one.
    //
    // Index arithmetic, once: `(let (c…) …)` binds the captures innermost-LAST, and `(lam k …)`
    // puts its own parameters below them. So inside the body a capture i of n sits at
    // `local (k + n - 1 - i)` and lambda parameter j of k at `local (k - 1 - j)`.
    case Instr.MkClos(d, fi, caps) =>
      val callee = cx.m.funcs(fi)
      val k = callee.nparams - caps.length
      if k < 0 then throw Unsupported("a closure capturing more values than its function takes")
      // `sh + i`, NOT `sh`. v2's `let` binds SEQUENTIALLY — `Runtime.appendOne(e, v)` per rhs — so
      // the i-th expression is evaluated in an env already extended by the i before it, and
      // `frameAt` is de Bruijn (`Local(i)` is `env(env.length - 1 - i)`). Reading the frame at a
      // fixed `sh` was therefore correct for the FIRST capture and one slot too shallow for every
      // one after it. With a single capture there is nothing to shift, which is exactly why
      // `mk(g) = x => g(x)` worked on the bridge and `comp(f, g) = x => f(g(x))` died with
      // `app: not a function` — the two-capture case is the smallest one that can expose it.
      val capBinds = caps.zipWithIndex.map((c, i) => read(c, sh + i)).mkString(" ")
      val capRefs = (0 until caps.length).toList.map(i => "(local " + (k + caps.length - 1 - i) + ")")
      val parRefs = (0 until k).toList.map(j => "(local " + (k - 1 - j) + ")")
      val applied = (capRefs ++ parRefs).mkString(" ")
      val body = "(app (global " + callee.name + ")" + (if applied.isEmpty then "" else " " + applied) + ")"
      write(d, "(let (" + capBinds + ") (lam " + k + " " + body + "))", sh)

    case Instr.CallV(d, c, as) => write(d, "(app " + read(c, sh) + args(as, sh) + ")", sh)
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
    // `(prim __tryCatch__ (lam 0 <body>) (lam 1 <handler>))`, read off the oracle. The body is a
    // THUNK so nothing in it runs before the guard is installed; the handler takes the caught value
    // as its single parameter, which is why the frame inside it sits one binder deeper.
    case Instr.Try(d, b, x, h) =>
      val bodyText = sq(List(seqOf(b, cx, sh), read(d, sh)))
      val handText = sq(List(write(x, "(local 0)", sh + 1), seqOf(h, cx, sh + 1), read(d, sh + 1)))
      write(d, "(prim __tryCatch__ (lam 0 " + bodyText + ") (lam 1 " + handText + "))", sh)
    case Instr.Invoke(d, nm, r, as) =>
      val mname = cx.m.consts(nm) match
        case Lit.LStr(x) => x
        case _           => throw Unsupported("an invoke whose name const is not a string")
      // REFUSE HERE rather than let v2 fail at the far end. An invoke this bridge forwards for a
      // method v2 does not have dies inside v2's dispatcher as a raw Java exception with a stack
      // trace and no idea what was asked for:
      //
      //   __method__: no dispatch for .filter on <closure>
      //   __method__: no dispatch for .until on 0
      //
      // A refusal naming the method and the lane is the standard the EXECUTOR is already held to —
      // its "not implemented on this lane" messages name the method — and `corpus-report.sh` has a
      // bucket for the other thing, "neither ran it nor refused it cleanly".
      // (BUGS.md v3-bridge-lazylist-crashes-with-a-java-stack-trace.)
      //
      // The list is MEASURED, not guessed, and it is short on purpose: these are the methods v3's
      // executor grew that v2 was then observed to lack. Re-measure by removing a name and running
      // the program through `ssc3 run --bridge`; if v2 has since gained it, the name comes out.
      if executorOnlyMethods.contains(mname) then
        // `Unsupported` already says "v2 bridge V-0 does not translate", so this completes that
        // sentence rather than starting a second one.
        throw Unsupported(
          "`" + mname + "`, which v3's executor implements and v2 does not — run this program with " +
          "`ssc3 run` rather than `ssc3 run --bridge`")
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
      // A DEFAULT arm, so `Tag` is TOTAL. Without it the tag of a value that is not Data has no
      // answer, and a pattern like `case Right(ByteRead(v, _))` tested against `Right(42)` — legal,
      // and simply a non-match in Scala — had no defined behaviour here and threw on the executor.
      // -1 is a tag no type index can equal, so the comparison is false and the arm falls through.
      write(d, "(match " + read(a, sh) + " (" + arms + ") (default " + int(-1) + "))", sh)
    case Instr.Switch(scrut, arms, dflt) =>
      // The arm bodies are the ONE place the frame moves: v2 binds the constructor's fields, so
      // everything inside is translated at shift + arity. `nested-loop` proves the loop slots; this
      // is the case that would catch getting the shift wrong.
      val armText = arms.map { a =>
        val td = cx.m.types(a.tag)
        "(arm " + td.name + " " + td.fields + " " +
          sq(List(seqOf(a.body, cx, sh + td.fields), endRegionOf(a.body, cx, sh + td.fields))) + ")"
      }.mkString(" ")
      "(match " + read(scrut, sh) + " (" + armText + ") (default " +
        sq(List(seqOf(dflt, cx, sh), endRegionOf(dflt, cx, sh))) + "))"

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

    // Cells, exactly as the oracle spells them: a global cell is a top-level `def` holding
    // `(prim cell.new …)`, read with `cell.get` and written with `cell.set`.
    case Instr.GlobGet(d, g) =>
      write(d, "(prim cell.get (global " + cellName(cx.m, g) + "))", sh)
    case Instr.GlobSet(g, a) =>
      "(prim cell.set (global " + cellName(cx.m, g) + ") " + read(a, sh) + ")"

    // ── effects ─────────────────────────────────────────────────────────────
    // NO `endRegion` HERE, and it is a decision rather than an omission: `Exec`'s `Handle` returns
    // the body's signal untouched, so a `br` does not stop at a handle boundary on that lane and
    // must not on this one. `Try` is emitted the same way for the same reason. The differential gate
    // is what holds the two lanes to one answer, so matching the executor is the rule.
    case Instr.Handle(d, body, arms) =>
      val ops = arms.filter(_.op >= 0)
      val ret = arms.find(_.op == -1)
      val rec = mkarr(List(
        mkarr((0 until cx.nOps).toList.map(o =>
          ops.find(_.op == o)
            .map(a => if armIsCps(cx, a) then armClos(cx, a, sh) else tailArmClos(cx, a, sh))
            .getOrElse(lit("unit")))),
        mkarr((0 until cx.nOps).toList.map(o => lit(if ops.exists(_.op == o) then "true" else "false"))),
        ret.map(a => retClos(cx, a, sh)).getOrElse(lit("unit")),
        lit(if ret.isDefined then "true" else "false"),
        mkarr(List(int(0)))))
      // Inside the `let` the record is `local 0` and the frame has moved to `local (sh+1)`. The
      // record's closures were built in the binding expression, one env shallower — which is why
      // `armClos` is passed `sh` and looks for the frame at `sh+1` from inside its own `lam`.
      val fr = "(local " + (sh + 1) + ")"
      // `__tryFinally__`, not a plain `seq`: the pop has to happen even when the body throws, or one
      // escaping exception leaves a dead handler on the stack and the NEXT perform answers to it.
      // `Exec` spells this `try … finally handlers = handlers.tail`.
      val run = "(prim __tryFinally__ (lam 0 " + seqOf(body, cx, sh + 1) + ") (lam 0 (prim arr.pop " +
        glob(effStack) + ")))"
      // The return clause applies to a body that finished WITHOUT performing — `ctl == 0` is that
      // "finished" (a `ret` leaves -1), and `performs == 0` is the "without performing". When either
      // fails the value is already the arm's own, lifted by the `resume` inside it.
      val lift = ret match
        case None => lit("unit")
        case Some(_) =>
          ifThen(arith("==", aget(fr, int(cx.ctl)), int(0)),
                 ifThen(arith("==", aget(aget("(local 0)", int(R_PERFORMS)), int(0)), int(0)),
                        aset(fr, int(d),
                             "(app " + aget("(local 0)", int(R_RET)) + " " + aget(fr, int(d)) + ")"),
                        lit("unit")),
                 lit("unit"))
      "(let (" + rec + ") " + sq(List("(prim arr.push " + glob(effStack) + " (local 0))", run, lift)) + ")"

    case Instr.Perform(d, op, as) =>
      performIsAnswerable(cx, op)
      write(d, "(app " + glob(effPerform) + " " + int(op) + " " + mkarr(as.map(r => read(r, sh))) + ")", sh)

    // Resuming IS calling: after `Cps.split` the continuation is an ordinary closure, so this is an
    // application with the return-clause rule wrapped round it. The `let` is what makes `before` a
    // reading taken BEFORE the continuation runs; inside it the frame is one binder deeper.
    case Instr.Resume(d, k, v) =>
      write(d, "(let ((app " + glob(effPerforms) + ")) (app " + glob(effAfter) + " (local 0) (app " +
              read(k, sh + 1) + " " + read(v, sh + 1) + ")))", sh)

    case other => throw Unsupported(Text.opcode(other))

  /** `__cell` rather than the bare name: the cell is a DEF holding a mutable box, and a program may
    * also have a function of the same name. Colliding them would be silent. */
  private def cellName(m: Module, g: Int): String = m.globals(g).name + "__cell"

  private def args(as: List[Int], sh: Int): String =
    if as.isEmpty then "" else " " + as.map(r => read(r, sh)).mkString(" ")

  // ── folding the temporaries ─────────────────────────────────────────────────
  //
  // MOST REGISTERS ARE NOT VARIABLES. Measured over a sample of bridged functions: 135 of 147
  // registers are written once and read at most once — they are the nodes of an expression tree that
  // the IR happens to spell as a register file. `Optimize.copyProp` cannot see this, because at IR
  // level a register IS the representation and folding one costs nothing there; on this lane each
  // one costs an `arr.set` at the definition and an `arr.get` at the use, both through v2's prim
  // dispatcher.
  //
  // So this folds the definition INTO the use, and it is a peephole rather than SSA: the definition
  // must be the statement IMMEDIATELY BEFORE the use, in the same list. Adjacency is what makes the
  // reordering argument short enough to be checkable — nothing runs between the two, so moving the
  // computation from one to the other cannot cross an effect.
  //
  // FIVE CONDITIONS, and each rules out a way this goes wrong:
  //   1. written once IN THE WHOLE FUNCTION — otherwise the folded value can be the stale one;
  //   2. read once in the whole function — otherwise the expression is duplicated, and a `call`
  //      would run twice;
  //   3. the read is a DIRECT operand of the next instruction, not something inside its body — a
  //      read inside a `Loop` executes per iteration and a read inside an `If` arm may not execute
  //      at all, and folding a `call` into either changes how often it happens;
  //   4. not a parameter — the prologue writes those, and the prologue is not an instruction, so
  //      condition 1 cannot see it;
  //   5. the definition's emitted text is exactly `write(d, VALUE, sh)` and the use's text contains
  //      the read pattern EXACTLY ONCE. This is the belt-and-braces check: it re-derives conditions
  //      1-3 from the text that will actually be emitted, so a case the census got wrong is skipped
  //      rather than mistranslated.
  //
  // The guard state is untouched: only a non-diverting instruction can match condition 5's shape,
  // and `seqOf` flips `guarded` only AFTER a diverting one — so the definition and the use are
  // always on the same side of the flip and the fold cannot move a computation into or out of a
  // `ctl == 0` test.
  private var foldTemps: Boolean = true
  private[ssc3] def useFoldTemps(on: Boolean): Unit = foldTemps = on

  /** Every register an instruction WRITES, and the difference from `Optimize.dstOf` IS the safety
    * argument. That one answers -1 for `Perform`, `Resume`, `Try` and `Handle`; a missed destination
    * only costs those passes a rewrite they could have made. Here a missed write is a register that
    * LOOKS written once when it is written twice, and folding its first value into a later read
    * would use a stale one — a wrong answer. Under-listing is the unsafe direction, so this lists
    * everything, including an arm's `params` and `k`. */
  private def writesOf(i: Instr): List[Int] = i match
    case Instr.Const(d, _)        => List(d)
    case Instr.Move(d, _)         => List(d)
    case Instr.Un(_, _, d, _)     => List(d)
    case Instr.Bin(_, _, d, _, _) => List(d)
    case Instr.Call(d, _, _)      => List(d)
    case Instr.CallV(d, _, _)     => List(d)
    case Instr.MkClos(d, _, _)    => List(d)
    case Instr.MkData(d, _, _)    => List(d)
    case Instr.Field(d, _, _, _)  => List(d)
    case Instr.Tag(d, _)          => List(d)
    case Instr.NewArr(d, _)       => List(d)
    case Instr.ArrGet(d, _, _)    => List(d)
    case Instr.ArrLen(d, _)       => List(d)
    case Instr.GlobGet(d, _)      => List(d)
    case Instr.Invoke(d, _, _, _) => List(d)
    case Instr.Prim(d, _, _)      => List(d)
    case Instr.Perform(d, _, _)   => List(d)
    case Instr.Resume(d, _, _)    => List(d)
    case Instr.Try(d, _, x, _)    => List(d, x)
    case Instr.Handle(d, _, arms) => d :: arms.flatMap(a => a.k :: a.params)
    case _                        => Nil

  /** Every register an instruction reads AS ITS OWN OPERAND — not the ones its body reads. The
    * distinction is condition 3: an operand is evaluated exactly once, before anything the
    * instruction does; a register read inside a `Loop` body is not. A region form lists nothing,
    * which is correct for the direct sense and makes it ineligible as a fold target. */
  private def readsOf(i: Instr): List[Int] = i match
    case Instr.Move(_, a)          => List(a)
    case Instr.Un(_, _, _, a)      => List(a)
    case Instr.Bin(_, _, _, a, b)  => List(a, b)
    case Instr.If(c, _, _)         => List(c)
    case Instr.BrIf(c, _)          => List(c)
    case Instr.Call(_, _, as)      => as
    case Instr.CallV(_, c, as)     => c :: as
    case Instr.MkClos(_, _, cs)    => cs
    case Instr.TailCall(_, as)     => as
    case Instr.Ret(a)              => List(a)
    case Instr.MkData(_, _, as)    => as
    case Instr.Field(_, a, _, _)   => List(a)
    case Instr.Tag(_, a)           => List(a)
    case Instr.Switch(s, _, _)     => List(s)
    case Instr.NewArr(_, n)        => List(n)
    case Instr.ArrGet(_, a, ix)    => List(a, ix)
    case Instr.ArrSet(a, ix, v)    => List(a, ix, v)
    case Instr.ArrLen(_, a)        => List(a)
    case Instr.GlobSet(_, a)       => List(a)
    case Instr.Perform(_, _, as)   => as
    case Instr.Resume(_, k, v)     => List(k, v)
    case Instr.Invoke(_, _, r, as) => r :: as
    case Instr.Prim(_, _, as)      => as
    case _                         => Nil

  private def occurrences(hay: String, needle: String): Int =
    var n = 0
    var i = hay.indexOf(needle)
    while i >= 0 do { n += 1; i = hay.indexOf(needle, i + needle.length) }
    n

  private def fold(arr: Array[Instr], texts: Array[String], cx: Ctx, sh: Int): Unit =
    val (reads, writes) = cx.census
    var p = 0
    while p < arr.length - 1 do
      val ds = writesOf(arr(p))
      if ds.length == 1 then
        val d = ds.head
        if d >= cx.f.nparams && d < cx.f.nregs && writes(d) == 1 && reads(d) == 1 &&
           readsOf(arr(p + 1)).count(_ == d) == 1
        then
          val pfx = "(prim arr.set " + frameAt(sh) + " " + int(d) + " "
          val t = texts(p)
          if t != null && t.startsWith(pfx) && t.endsWith(")") then
            val pat = read(d, sh)
            if occurrences(texts(p + 1), pat) == 1 then
              texts(p + 1) = texts(p + 1).replace(pat, t.substring(pfx.length, t.length - 1))
              texts(p) = null
      p += 1

  /** A structured `while`'s condition: the statements that compute `c`, then `c == false` — the
    * `brif` exits when `c` holds, so the loop runs while it does not.
    *
    * The trailing `Instr.Ret(c)` is a VIRTUAL READER and never emitted; it exists so `fold` can see
    * that the comparison reads `c` and fold the statement computing it into the comparison. Without
    * it the last link of the chain — usually the whole condition — would stay an array slot.
    * `Ret` is the right stand-in because `readsOf` gives it exactly `List(c)` and `writesOf` gives
    * it nothing, so it can never be mistaken for a definition. No guards: `pre` is known not to
    * divert, which is what let this shape be structured at all. */
  private def condOf(pre: List[Instr], c: Int, cx: Ctx, sh: Int): String =
    val arr = (pre :+ Instr.Ret(c)).toArray
    val texts = (pre.map(i => stmt(i, cx, sh)) :+ arith("==", read(c, sh), lit("false"))).toArray
    if foldTemps then fold(arr, texts, cx, sh)
    sq(texts.filter(_ != null).toList)

  /** Statements in one region. Everything after an instruction that may divert is wrapped in a
    * guard; everything before it is not, so straight-line code pays nothing. */
  private def seqOf(body: List[Instr], cx: Ctx, sh: Int): String =
    val arr = body.toArray
    val texts = arr.map(i => stmt(i, cx, sh))
    if foldTemps then fold(arr, texts, cx, sh)
    var out: List[String] = Nil
    var guarded = false
    var p = 0
    while p < arr.length do
      // `mayDivert` is asked of EVERY instruction, folded away or not. A folded one never diverts —
      // condition 5's shape excludes it — but reading the flag off the instruction list rather than
      // off what survived keeps the guard sequence a property of the IR.
      if texts(p) != null then
        out = (if guarded then ifThen(running(cx, sh), texts(p), lit("unit")) else texts(p)) :: out
      if mayDivert(arr(p)) then guarded = true
      p += 1
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
    // Every global is declared as a cell BEFORE any function, because a `def` may read one the
    // entry has not initialised yet — which is legal, and is why a cell starts as `unit`.
    val cells = m.globals.indices.toList
      .map(g => "(def " + cellName(m, g) + " (prim cell.new (lit unit)))").mkString(" ")
    // The effect runtime goes in only when something reaches it, so a module without effects emits
    // exactly the text it emitted before this feature existed — which is what makes the A/B of
    // everything else still comparable.
    val eff = if usesEffects(m) then effectDefs + " " else ""
    val defs = (if cells.isEmpty then "" else cells + " ") + eff +
      m.funcs.map(f => func(m, f)).mkString(" ")
    val entryName = m.funcs(m.entry).name
    "(program (defs " + defs + ") (entry (app (global " + entryName + "))))"
