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
  // `!` IS NOT A METHOD ON THIS LANE, and that is a difference in SPELLING rather than in capability.
  // v3 lowers an operator the core does not define to a method call — `replyTo ! msg` is
  // `replyTo.!(msg)` — while v2 keeps `!` as a BINARY OPERATOR that `Prims.arithOp` routes to the
  // registered `actor.send`. The executor's fleet adapter bridges the two spellings; v2's own
  // dispatcher cannot be taught the same way, because `methodDispatch1` is the function that was
  // 49,384 bytecodes and silently un-JIT-able, and growing it is the one change this file's history
  // says not to make casually.
  //
  // So it is refused HERE, by name, which turns `__method__: no dispatch for .! on <foreign>` — a
  // Java stack trace, counted as a DIFF — into the honest UNSUPPORTED this list exists to produce.
  // MEASURED, not assumed: `List(1,2) ++ List(3)` works on this lane, so a blanket refusal of every
  // symbolic name would have been a regression, and `"a" ++ "b"` already fails identically on
  // origin/main so it is not this change's to answer for.
  private val executorOnlyMethods: Set[String] = Set("__lazyFrom__", "to", "until", "!")

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
  // SSC3-3c-rest stage 2. THE FRAME IS N CELLS, NOT ONE ARRAY — and the whole change fits in these
  // two functions because of how the cells are bound.
  //
  // `(let (b0 b1 … bN-1) body)` puts binding i at `local (N-1-i)`, so binding the cells in REVERSE
  // register order lands register `r` at `local r` exactly. No frame size in the address, no `cx`
  // threaded through forty call sites, and `sh` still means what it meant: how many binders sit
  // between here and the frame.
  //
  // Why cells rather than the immutable `Let` bindings SSC3-3c-rest originally proposed: a cell is
  // MUTABLE, so an `If` arm writes it and the code after reads it — the join problem the entry
  // wanted lambda parameters for does not arise. Measured on hand-written v2 Core IR before any of
  // this was written: cells are 10 of 10 rounds faster than the array, median 0.784 = 1.27×, while
  // the further step to immutable locals is 8 of 10 at 0.910 — 1.10× for a dominance analysis.
  //
  // `cell.get` is `asCell(a(0))(0)`; `arr.get` is `asArr(a(0))(int(a, 1).toInt)`, which additionally
  // pattern-matches the index out of a `Value` and narrows it. That is the difference, per access.
  private var cellFrame: Boolean = true
  private[ssc3] def useCellFrame(on: Boolean): Unit = cellFrame = on

  private def read(r: Int, sh: Int): String =
    if cellFrame then "(prim cell.get (local " + (r + sh) + "))"
    else "(prim arr.get " + frameAt(sh) + " " + int(r) + ")"
  private def write(r: Int, v: String, sh: Int): String =
    if cellFrame then "(prim cell.set (local " + (r + sh) + ") " + v + ")"
    else "(prim arr.set " + frameAt(sh) + " " + int(r) + " " + v + ")"

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

  // ── operators, and a result that came out BACKWARDS ─────────────────────────
  //
  // v2 has `i.add`/`i.sub`/`i.mul`/`i.lt`/… beside `__arith__`, and `Instr.Bin` carries the
  // `NumKind` the specializer proved, so emitting the direct prim where the operands are known
  // `I64` looks like free money. IT WAS MEASURED AND IT IS NOT. Two independent instruments:
  //
  //   hand-written v2 Core IR, the operators the ONLY difference, 15 rotated rounds:
  //     5 of 15, p 0.94, median 1.054 — the direct prims are about 5% SLOWER
  //   the bridge end to end, ONE binary, 30 alternating pairs, control inside each:
  //     10 of 30, p 0.98, median 1.067 — the same answer
  //
  // AND THE SOURCE SAYS WHY. `i.add` is `liftArith("+", a, numBin(a, _ + _, _ + _))`: two `Op`
  // type-tests, then `numBin`'s three float-pair tests, then two `asInt` matches, then TWO CLOSURE
  // CALLS through the function arguments. `__arith__` is `arithFast(str(a, 0), a(1), a(2))`: pull
  // the string out, two type matches, a string switch the JIT turns into a hash lookup, add. The
  // "direct" prim does strictly more work. There is deliberately no `intPrim` here, and this
  // comment is the record of why adding one back would be a repeat rather than an idea.
  //
  // HOW IT READ AS A WIN THE FIRST TIME, because that is the reusable part: the ceiling study that
  // said 1.15× compared a variant changing the operators AND dropping the `(x == false)` negation
  // from the loop condition. One variant, two changes, and I credited the wrong one. Split apart,
  // the negation removal alone is 12 of 15, p 0.018, median 0.863 — THAT is the win, it is kept,
  // and the operators cancel most of it.
  private var invertCond: Boolean = true
  private[ssc3] def useInvertCond(on: Boolean): Unit = invertCond = on

  private def opText(o: BinOp, k: NumKind, a: String, b: String): String =
    if isBitwise(o) then "(prim " + bitPrim(o) + " " + a + " " + b + ")"
    else arith(binName(o), a, b)

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

  private final case class Ctx(m: Module, f: Func, crossK: Int = -1, needsRest: Set[Int] = Set.empty):
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
  /** WHERE A CONTINUATION FINDS ITS OWN HANDLER. `effFind` parks the record it is about to run an
    * arm with here, and `armClos` reads it in its FIRST binding — nothing can perform in between, so
    * a nested activation cannot see a stale one. It exists because the arm's `k` has to become a
    * PAIR of (closure, record): resuming must apply the return clause of the handler the
    * continuation BELONGS to, and reading the stack top instead is wrong the moment the arm returns
    * a closure that outlives its `handle`.
    * (BUGS.md v3-bridge-lags-the-executor-on-cross-frame-effects, the escaped-continuation half.) */
  private val effCurRec   = "__ssc3_eff_currec__"
  private val effResume   = "__ssc3_eff_resume__"
  /** The CALLER continuations a perform still owes, and the pieces that consume them.
    *
    * `splitCallers` makes the rest of a caller a closure and parks it here for the duration of the
    * call; a perform composes its own continuation with everything parked since its `handle`. That
    * is the whole of crossing a call frame on this lane — see `splitCallers` for why the compiler
    * can build what the target cannot capture. */
  private val effPending  = "__ssc3_eff_pending__"
  private val effPush     = "__ssc3_eff_push__"
  private val effPop      = "__ssc3_eff_pop__"
  private val effCompose  = "__ssc3_eff_compose__"
  private val effChain    = "__ssc3_eff_chain__"
  private val effPushAll  = "__ssc3_eff_push_all__"
  private val effPopAll   = "__ssc3_eff_pop_all__"
  private val effRun      = "__ssc3_eff_run__"
  /** THE FLAG A HANDLED VALUE TRAVELS HOME BEHIND, and why it is a flag.
    *
    * An arm's value is the `handle`'s, so it has to pass THROUGH the split callers between the two
    * without any of them running its remainder — that remainder belongs to the continuation now. A
    * throw would do it, and so would a tagged wrapper, but the wrapper needs a way to tell a tagged
    * value from a user one and `__typename__` is not a prim this lane has. A cell asks nothing of
    * v2 beyond `cell.get`/`cell.set`, which the globals already rest on, and costs each split caller
    * one read to tell "the callee returned" from "the callee's handler answered".
    *
    * Set by `Perform` after the arm produces its value, cleared by the `Handle` that consumes it.
    * Handles nest properly, so an inner abort is cleared before control reaches an outer caller. */
  private val effAborting = "__ssc3_eff_aborting__"

  private val R_ARMS = 0
  private val R_HANDLES = 1
  private val R_RET = 2
  private val R_HASRET = 3
  private val R_PERFORMS = 4
  /** How deep `effPending` was when this `handle` began, so a perform takes only what was parked
    * INSIDE it — the same role `Exec.Delim.pendingDepth` plays. */
  private val R_PENDING = 5
  /** Per operation: does its arm take a CONTINUATION? Only then does the perform's argument array
    * end in one, and only then may it be composed with the caller frames. The tail-resumptive
    * encoding carries no continuation at all — its last argument is an ordinary parameter, and for
    * a nullary operation there is no last argument: composing blind read index -1 of an empty array
    * and `handle-tail-resumptive` died inside v2 with an `IndexOutOfBoundsException`. The emitter
    * knows this statically (`armIsCps`), so it is written into the record rather than guessed at. */
  private val R_ISCPS = 6

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

  /** A CONTINUATION THAT WOULD HAVE TO CROSS A CALL FRAME — REFUSED, not answered wrongly.
    *
    * `Cps.split` makes the rest of the PERFORMING function into a closure, and that is the whole
    * continuation only when the perform's value is the handled expression's value. Put a caller in
    * between — `handle(body())` where `body` calls a performing function and then goes on — and the
    * caller's remaining instructions belong to the continuation too. `Exec` captures them at run
    * time (`Exec.PendingFrame`); emitted v2 code cannot, because a v2 function has no way to hand
    * over "the rest of me".
    *
    * So the bridge says so. Before this it ANSWERED: `handle(body())` printed `END` where the
    * executor and every reference lane print `H:a|END`, and `effects-handler` printed `List()`
    * against `List(first, second, third)`. Two lanes of v3 disagreeing silently is the failure mode
    * the effects gate exists to catch, and it could not, because before the executor was fixed both
    * lanes were wrong in the same way. (v3-bridge-lags-the-executor-on-cross-frame-effects.)
    *
    * THE TEST IS STATIC AND DELIBERATELY CRUDE: a function that may perform — directly or through a
    * call — must be called in TAIL position, meaning the call is the last instruction of a body or
    * is followed only by the `ret` of its own register. A call nested inside an `if`, `loop` or
    * `try` is never that, and is refused for the same reason `Exec` refuses it: the remainder there
    * is a suffix of the REGION, not of the function.
    *
    * FIXING IT FOR REAL is one pass, and it would serve both lanes: split callers at such calls the
    * way `Cps` splits at performs, thread the caller's continuation, and compose. That would also
    * let `Exec`'s runtime capture go, which is the only reason the two lanes need two mechanisms. */
  /** THE CALLER'S REMAINDER, MADE A CLOSURE — so the bridge stops refusing what it could not follow.
    *
    * `Cps.split` turns the rest of the PERFORMING function into a closure. That is the whole
    * continuation only when the perform's value is the handled expression's value; put a caller in
    * between and the caller's remaining instructions belong to it too. `Exec` captures those frames
    * at run time. Emitted v2 code cannot — but it does not have to, because the compiler can build
    * them: split the CALLER at the call exactly as `Cps` splits at a perform, and the remainder
    * becomes an ordinary closure like any other.
    *
    * THE SHAPE, for `g` calling an effectful `f` with instructions after it:
    *
    *     g:     before
    *            MkClos kc, g$c, <every register>
    *            Prim __eff_push__ kc          -- so a perform deeper down can compose it
    *            Call d, f, args
    *            Prim __eff_pop__
    *            CallV d, kc, [d]              -- f returned NORMALLY: run the rest with its value
    *            Ret d
    *     g$c:   Move(d, nregs); after
    *
    * TWO HALVES, AND NEITHER WORKS ALONE. The `CallV` is what keeps the non-performing path right:
    * without it `after` would simply be dropped whenever `f` happened not to perform. And it is only
    * correct because a perform does NOT return through here — it unwinds to its `handle` carrying
    * the arm's value, so `kc` is reached exactly when `f` came back on its own. Splitting without
    * the unwind runs `after` on the arm's value; unwinding without the split loses `after`.
    *
    * REGISTERS ARE CAPTURED WHOLE, the same decision `Cps` documents and for the same reason: with
    * all of them captured the continuation's parameters are `0 .. nregs-1` in order and `after`
    * needs no renaming.
    *
    * WHAT IS STILL NOT SPLIT: a call inside an `if`, `loop` or `try`. A region's remainder is not a
    * suffix of an instruction list, so there is nothing to make a closure OF — the same boundary
    * `Cps.scala`'s header calls step 3. Those are still refused by name. */
  // ── the remainder of a function from inside a region ────────────────────────
  //
  // THE ONE THING THIS LANE CANNOT DO DIRECTLY, and why. `MkClos` captures registers BY VALUE
  // (`(prim cell.get …)` per capture), so a continuation built out of two closures — "finish the
  // region", then "finish what encloses it" — loses every register the first one wrote before the
  // second reads it. The executor has no such problem: there a region does not open a frame, both
  // remainders share one array, and the chain of `PendingFrame`s is the whole of it.
  //
  // So this lane reconstructs instead: the remainder of the function from a point inside nested
  // regions is rebuilt as ONE instruction list, which becomes ONE continuation function with ONE
  // frame. `specs/10-ssc-ir.md` §3 states the invariant both lanes answer — the rest of the
  // performing function, the rest of every enclosing list up to the `handle`, and for a loop the
  // back edge — and this is the second realisation of it.
  //
  // THE REBUILD, one rule per region:
  //
  //   Block / If arm / Switch arm   the remainder is `Block(inner)`, then the enclosing suffix.
  //                                 A `Block` consumes a branch level exactly as those three do,
  //                                 so nothing about depths changes.
  //   Try body                      `Try(d, inner, x, handler)` — the handler is re-installed for
  //                                 the remainder, which is right: what already ran cannot throw
  //                                 again. `Try` is branch-TRANSPARENT on both lanes, so it adds
  //                                 no level either.
  //   Loop                          `Block( Block(rest :+ br 1), Loop(splitBody) )` — see below.
  //
  // NOT INTO A `Handle`. Its body's remainder ends at the handle rather than at the function, so it
  // is a different contract (the value is the handle's, not a `ret`), and `splitCallers` keeps its
  // own case for the one shape that matters — a call at the top level of a handle body.
  //
  // ── the loop, which is the only interesting one ─────────────────────────────
  //
  // `specs/10-ssc-ir.md` §3 prescribes turning a `Loop` containing a `Perform` into a recursive
  // function. That route is sound and nobody took it; this is a different one that costs no new
  // calling convention, and it is written down here because the spec's is not what runs.
  //
  //     Loop( prefix ; HIT ; suffix )
  //
  // becomes, at the cut site,   Loop( prefix ; mkclos k,K ; HIT k ; ret d )
  // and the continuation K is   Block( Block( suffix' ; br 1 ) , Loop( prefix ; mkclos k,K ; HIT k ; ret d ) )
  //
  // The inner `Block` finishes the interrupted iteration: falling off its end is `br 1`, which
  // leaves the outer `Block` and so leaves the loop; a `br 0` in `suffix` — the back edge — leaves
  // only the inner one and drops straight into the loop, which is the next iteration.
  //
  // **K NAMES ITSELF**, and that is what makes this terminate. A reconstruction that copied the
  // UNSPLIT body would contain the same cross-frame instruction, get split again, and produce a
  // continuation of the same shape for ever. Copying the SPLIT body instead means the copy's own
  // `mkclos` already points at K, so there is nothing left to split and no second function to make.
  // `alreadySplit` is what stops the walk from cutting that copy again.
  //
  // DEPTHS SHIFT BY ONE past the loop, because the outer `Block` is a region the original did not
  // have. `br 0` — the back edge — is untouched: the inner `Block` stands exactly where the `Loop`
  // stood. Everything deeper is relative and `shiftOut` counts nesting as it walks.
  private def shiftOut(body: List[Instr], n: Int): List[Instr] = body.map {
    case Instr.Br(d)       => if d > n then Instr.Br(d + 1) else Instr.Br(d)
    case Instr.BrIf(c, d)  => if d > n then Instr.BrIf(c, d + 1) else Instr.BrIf(c, d)
    case Instr.Block(b)    => Instr.Block(shiftOut(b, n + 1))
    case Instr.Loop(b)     => Instr.Loop(shiftOut(b, n + 1))
    case Instr.If(c, t, e) => Instr.If(c, shiftOut(t, n + 1), shiftOut(e, n + 1))
    case Instr.Switch(s, arms, df) =>
      Instr.Switch(s, arms.map(a => SwitchArm(a.tag, shiftOut(a.body, n + 1))), shiftOut(df, n + 1))
    // `Try` and `Handle` consume no branch level on either lane — `Instr.Try` hands the body's
    // signal straight out and so does `Instr.Handle` — so their contents stay at the same nesting.
    case Instr.Try(d, b, x, h) => Instr.Try(d, shiftOut(b, n), x, shiftOut(h, n))
    case Instr.Handle(d, b, arms) =>
      Instr.Handle(d, shiftOut(b, n), arms.map(a => a.copy(body = shiftOut(a.body, n))))
    case other => other
  }

  /** Is the instruction at `i` one this walk already cut? The shape a cut leaves is
    * `mkclos k,K ; INSTR ; ret d`, and the loop rebuild copies it verbatim into the continuation —
    * so without this the copy is found, cut, and a second continuation of the same shape is made,
    * for ever. */
  private def alreadySplit(body: List[Instr], i: Int): Boolean =
    i > 0 && (body(i - 1) match { case _: Instr.MkClos => true; case _ => false }) && {
      val d = dstOfCut(body(i))
      // TWO SHAPES, because a cut ends differently depending on what it is ending. In a FUNCTION the
      // split is `mkclos ; INSTR ; ret d`. In a `handle` BODY it is `mkclos ; INSTR ; move dh,d ;
      // br n`, because the continuation stops at the handle and a `ret` would leave the function.
      // Knowing only the first shape is not a cosmetic gap: the walk re-finds the handle cut it just
      // made, cuts it again, and `splitCallers` never terminates — `ssc3 build` hung rather than
      // answering, which is how this was found.
      d >= 0 && (body.drop(i + 1) match
        case Instr.Ret(r) :: _                    => r == d
        case Instr.Move(_, r) :: Instr.Br(_) :: _ => r == d
        case _                                    => false)
    }

  /** Cut a body at the first instruction `hit` accepts, at ANY depth, and rebuild what follows.
    *
    * Returns the body with that instruction replaced by `repl`, and the whole remainder of the
    * function from just after it as one list. `None` when there is nothing to cut. */
  //
  // `repl` IS TOLD HOW DEEP THE CUT IS, and only one caller needs it — the one cutting inside a
  // `handle` body, where the replacement ends with `br <depth>` rather than with `ret` because the
  // continuation stops AT the handle. Counted in BRANCH-CONSUMING regions: `Block`, `Loop`, `If`
  // and `Switch` each add one, `Try` adds none because it hands a branch straight out on both
  // lanes. Threading it here rather than recomputing it at the call site is what keeps the number
  // and the nesting from disagreeing.
  private def cutAt(body: List[Instr], hit: (List[Instr], Int) => Boolean,
                    repl: (Instr, Int) => List[Instr],
                    depth: Int = 0): Option[(List[Instr], List[Instr])] =
    val i = body.indices.find(k => hit(body, k))
    i match
      // AT THIS LEVEL. What follows is unreachable in the cut body — `repl` ends the function — so
      // it moves wholesale into the remainder rather than being left behind in both.
      case Some(k) => Some((body.take(k) ++ repl(body(k), depth), body.drop(k + 1)))
      case None =>
        var out: Option[(List[Instr], List[Instr])] = None
        var k = 0
        while k < body.length && out.isEmpty do
          cutRegion(body(k), hit, repl, depth).foreach { (nr, pre) =>
            // The enclosing suffix stays in BOTH: reachable in the cut body through the region's
            // other arm, and part of the remainder once the region finishes.
            out = Some((body.take(k) ++ (nr :: body.drop(k + 1)), pre ++ body.drop(k + 1)))
          }
          k += 1
        out

  private def cutRegion(i: Instr, hit: (List[Instr], Int) => Boolean,
                        repl: (Instr, Int) => List[Instr], depth: Int): Option[(Instr, List[Instr])] = i match
    case Instr.Block(b) =>
      cutAt(b, hit, repl, depth + 1).map((nb, c) => (Instr.Block(nb), List(Instr.Block(c))))
    case Instr.If(c, t, e) =>
      cutAt(t, hit, repl, depth + 1).map((nt, k) => (Instr.If(c, nt, e), List(Instr.Block(k)))) orElse
        cutAt(e, hit, repl, depth + 1).map((ne, k) => (Instr.If(c, t, ne), List(Instr.Block(k))))
    case Instr.Switch(s, arms, df) =>
      val ai = arms.indices.find(j => cutAt(arms(j).body, hit, repl, depth + 1).isDefined)
      ai match
        case Some(j) =>
          cutAt(arms(j).body, hit, repl, depth + 1).map((nb, k) =>
            (Instr.Switch(s, arms.updated(j, SwitchArm(arms(j).tag, nb)), df), List(Instr.Block(k))))
        case None =>
          cutAt(df, hit, repl, depth + 1).map((nd, k) => (Instr.Switch(s, arms, nd), List(Instr.Block(k))))
    // `Try` ADDS NO LEVEL. `Instr.Try` hands its body's signal straight out on both lanes, so a
    // `br 0` inside a try body targets the region around the TRY, not the try.
    case Instr.Try(d, b, x, h) =>
      cutAt(b, hit, repl, depth).map((nb, k) => (Instr.Try(d, nb, x, h), List(Instr.Try(d, k, x, h)))) orElse
        cutAt(h, hit, repl, depth).map((nh, k) => (Instr.Try(d, b, x, nh), k))
    case Instr.Loop(b) =>
      cutAt(b, hit, repl, depth + 1).map { (nb, k) =>
        (Instr.Loop(nb),
         List(Instr.Block(List(Instr.Block(shiftOut(k, 0) :+ Instr.Br(1)),
                               Instr.Loop(shiftOut(nb, 0))))))
      }
    case _ => None

  /** The register a cut instruction writes — its answer, and what the continuation resumes with. */
  private def dstOfCut(i: Instr): Int = i match
    case Instr.Call(d, _, _)    => d
    case Instr.Perform(d, _, _) => d
    case other                  => -1

  /** THE REPLACEMENT FOR A CUT INSIDE A `handle` BODY: build the continuation, run the instruction,
    * put its answer where the `handle` reads its own, and LEAVE THE BODY.
    *
    * `br depth` targets the `Block` the caller wraps the rebuilt body in, so this ends the handle
    * body from any nesting inside it. A `ret` cannot serve: it would leave the whole function and
    * take the handle's answer with it, and on this lane it also sets CTL to -1, which no `endRegion`
    * ever clears.
    *
    * `Move(dh, d)` before the branch is right on BOTH paths, which is the same argument the function
    * case makes for its `ret`: if the callee performed, the arm's value goes home by the abort flag
    * and nothing here is reached; if it did not, the emitted call site has already applied the
    * continuation and `d` holds its answer. */
  private def replInHandle(dh: Int, kReg: Int, contIdx: Int, caps: List[Int],
                           depth: Int, i: Instr): List[Instr] =
    val d = dstOfCut(i)
    val body = i match
      case Instr.Call(dd, f, as)     => Instr.Call(dd, f, as)
      case Instr.Perform(dd, op, as) => Instr.Perform(dd, op, as :+ kReg)
      case other                     => other
    List(Instr.MkClos(kReg, contIdx, caps), body, Instr.Move(dh, d), Instr.Br(depth))

  private def splitCallers(m0: Module, needsRest: Set[Int]): (Module, Map[String, Int]) =
    var funcs = m0.funcs
    var kOf   = Map.empty[String, Int]
    var queue = funcs.indices.toList
    // NOTHING LEFT TO RUN, seen through the region wrappers `cutAt` builds. A call in tail
    // position needs no continuation — its value is already the function's — and the rebuild of an
    // empty region suffix is `Block(Nil)`, which is the same nothing spelled structurally.
    def tailish(rest: List[Instr], d: Int): Boolean = rest.forall {
      case Instr.Block(b)  => tailish(b, d)
      case Instr.Ret(r)    => r == d
      case _               => false
    }
    while queue.nonEmpty do
      val idx = queue.head
      queue = queue.tail
      val cur = funcs(idx)
      // THE CUT IS ATTEMPTED FIRST AND ITS RESULT IS THE TEST, rather than an index computed by
      // hand and then trusted. `cutAt` searches this level and then descends into `if`, `loop`,
      // `switch` and `try`; it deliberately does NOT enter a `handle`, whose body is the case
      // below. Asking it directly is what keeps the two searches from disagreeing about where a
      // call is — a hand-rolled `indexWhere` over `Instr.flatten` would find a call inside a handle
      // body, take this branch, and then cut nothing at all.
      val nregs   = cur.nregs
      // ONE `k` REGISTER PER FUNCTION, REUSED BY EVERY CUT IN IT. A region means a function can be
      // cut more than once — `if c then f() else g()` has two calls and only one of them moves out
      // — and the emitter carries a SINGLE `crossK`, so a second cut with a second register would
      // make the first call site read the wrong one. Reuse is safe because a `k` is live for
      // exactly two instructions: the `mkclos` that builds it and the call that consumes it.
      val kReg    = kOf.getOrElse(cur.name, nregs)
      val contIdx = funcs.length
      val caps    = (0 until nregs).toList
      def hitCall(b: List[Instr], k: Int): Boolean = b(k) match
        case Instr.Call(_, f, _) => needsRest.contains(f) && !alreadySplit(b, k)
        case _                   => false
      var cutD = -1
      def replCall(i: Instr, depth: Int): List[Instr] = i match
        case Instr.Call(d, f, as) =>
          cutD = d
          List(Instr.MkClos(kReg, contIdx, caps), Instr.Call(d, f, as), Instr.Ret(d))
        case other => List(other)
      val cut = cutAt(cur.body, hitCall, replCall).filter((_, rest) => !tailish(rest, cutD))
      val at  = if cut.isDefined then 0 else -1
      // THE HANDLE BODY IS A BODY TOO, and the one that matters most: `handle { program(); List() }`
      // — the shape `tests/conformance/effects-handler.ssc` is written in — puts the call there, not
      // at the top level of any function. Two differences from a function body: the continuation has
      // to END with the handle's own destination register, because a `handle` takes its value from
      // there rather than from a `ret`, and the split part must MOVE the answer into it, which is
      // right on both paths — a normal return and an arm's value arrive in the same register.
      // ONE CASE FOR THE WHOLE HANDLE BODY, at any depth in it — the top level and a region inside
      // it are the same cut with a different `br`.
      //
      // THE BODY IS WRAPPED IN A `Block` AND THE SPLIT LEAVES BY BRANCHING OUT OF IT. That is the
      // whole trick, and it is what a `ret` cannot do: a handle body's remainder ends AT the handle,
      // with the handle's own destination register, while `ret` leaves the FUNCTION and takes the
      // handle's answer with it. Branching to the wrapping block ends the body exactly where the
      // `Handle` reads `dh`.
      //
      // IT ALSO SKIPS THE ENCLOSING SUFFIXES, which is required and not a side effect. `cutAt`
      // leaves them in place because a region's OTHER arm still reaches them; on the path through
      // the cut they belong to the continuation, and the branch is what stops them running twice.
      // Right on both paths for the same reason `ret` is in a function: when the callee performs,
      // the arm's value travels home and nothing here runs; when it does not, the split call site
      // has already applied the continuation and `dh` holds its answer.
      val hIdx = if at >= 0 then -1 else cur.body.indices.find { j => cur.body(j) match
        case Instr.Handle(dh, hb, _) =>
          cutAt(hb, hitCall, (i, dep) => replInHandle(dh, kReg, contIdx, caps, dep, i)).isDefined
        case _ => false
      }.getOrElse(-1)
      if hIdx >= 0 then
        val Instr.Handle(dh, hbody, harms) = cur.body(hIdx): @unchecked
        var hd = -1
        def replH(i: Instr, dep: Int): List[Instr] =
          hd = dstOfCut(i)
          replInHandle(dh, kReg, contIdx, caps, dep, i)
        cutAt(hbody, hitCall, replH).foreach { (nhb, hrest) =>
          if hrest.nonEmpty then
            // THE CONTINUATION WRAPS THE REMAINDER IN A `Block` TOO, and it is the same block the
            // cut branches to — one on each side of the split. The rebuilt body keeps a copy of the
            // split instruction (the loop rebuild puts one back), and its `br` has to land
            // somewhere in the CONTINUATION as well: without this wrapper it targeted a block that
            // exists only in the function the cut came from, and `perform` inside a `while` inside
            // a handle body answered 1 where the executor said 12 — a wrong answer, not a refusal.
            val cont = Func(cur.name + "$h" + contIdx, nregs + 1, nregs + 1,
                            List(Instr.Move(hd, nregs), Instr.Block(hrest), Instr.Ret(dh)))
            val newHandle = Instr.Handle(dh, List(Instr.Block(nhb)), harms)
            val split = cur.copy(nregs = nregs + 1,
                                 body  = cur.body.updated(hIdx, newHandle))
            funcs = funcs.updated(idx, split) :+ cont
            kOf = kOf + (cur.name -> kReg)
            queue = queue :+ contIdx :+ idx
        }
      // AT ANY DEPTH, not only at the top level. The refusal that used to stand here for a call
      // inside a region is gone; what is left of it is named in `crossFrameRefusal`.
      cut.foreach { (nb, rest) =>
        val cont = Func(cur.name + "$c" + contIdx, nregs + 1, nregs + 1,
                        Instr.Move(cutD, nregs) :: rest)
        val split = cur.copy(nregs = nregs + 1, body = nb)
        funcs = funcs.updated(idx, split) :+ cont
        // THE CONTINUATION OF A LOOP CUT CONTAINS A SPLIT CALL OF ITS OWN — the copy of the split
        // body the rebuild puts back — and it needs `crossK` just as much as the function it came
        // from. Without this the copy emitted a BARE call: the continuation was built, never parked
        // and never applied, so the second iteration ran with nothing to come back to and the VM
        // recursed until the stack went. Asked of the body rather than assumed from the shape,
        // because only the loop rebuild produces it.
        if Instr.flatten(cont.body).exists {
             case Instr.MkClos(r, _, _) => r == kReg
             case _                     => false
           }
        then kOf = kOf + (cont.name -> kReg)
        // THE CONTINUATION'S REGISTER, by name, for the emitter. The call is the last thing this
        // function does now, so there is exactly one per split function and it is always the
        // register the split added — but recording it beats recomputing it, because "the last
        // register" is the kind of fact that stops being true the moment anything else is added.
        kOf = kOf + (cur.name -> kReg)
        // BOTH GO BACK ON THE QUEUE. The continuation, because its own body may cross another
        // frame; and the function ITSELF, because with regions a single cut no longer empties it —
        // `if c then f() else g()` keeps the second call in the arm the cut did not touch, and the
        // top-level rule never had to think about that because everything after a top-level cut
        // moved wholesale into the continuation. Terminates: a cut leaves `mkclos … ret`, which
        // `alreadySplit` no longer counts as a hit, so each pass removes one.
        queue = queue :+ contIdx :+ idx
      }
    (m0.copy(funcs = funcs), kOf)

  /** A `perform` STANDING INSIDE A REGION, given the continuation `Cps` could not build for it.
    *
    * `Cps.split` cuts a function at a `Perform` at the TOP LEVEL of its body, and says so: a
    * region's remainder is not a suffix of an instruction list, which is the boundary that file's
    * header calls step 3. Both lanes answer it now and neither answers it there, because the answer
    * is not the same on the two — the executor hands the perform the rest of its own list as a
    * frame (`Exec.performRest`), and this lane rebuilds that rest into one function, which is what
    * `cutAt` is for. One invariant, two realisations, exactly as `specs/10-ssc-ir.md` §3 says.
    *
    * WHICH PERFORMS. Only the ones the bridge refuses today, so this can add answers and cannot
    * change one:
    *
    *   * the arm is NOT tail-resumptive — `case op(k) => k(1) + k(2)` needs a real continuation and
    *     an unsplit perform has none, which is the refusal "neither takes a continuation nor is
    *     tail-resumptive";
    *   * or the same operation is CPS-encoded somewhere else in the module — a perform at a
    *     function's top level that `Cps` did cut. Mixed encodings for one operation are refused by
    *     `armIsCps` ("the bridge needs every `perform` of an operation to use one encoding"), and
    *     splitting the region one is what makes them agree rather than what makes them differ.
    *
    * A tail-resumptive arm whose operation is never CPS is left ALONE: that path costs no closure,
    * no compose and no unwind, and it is what most of the corpus takes. */
  private def splitRegionPerforms(m0: Module): Module =
    val arms: List[HandlerArm] = m0.funcs.flatMap(fn =>
      Instr.flatten(fn.body).collect { case Instr.Handle(_, _, as) => as }.flatten).filter(_.op >= 0)
    val armOf: Map[Int, HandlerArm] = arms.map(a => a.op -> a).toMap
    var cpsAlready: Set[Int] = Set.empty
    scanAll(m0) {
      case Instr.Perform(_, op, as) =>
        armOf.get(op).foreach(a => if as.length == a.params.length + 1 then cpsAlready = cpsAlready + op)
      case _ => ()
    }
    def wants(op: Int, n: Int): Boolean =
      armOf.get(op).exists(a =>
        n == a.params.length && (!isTailResumptive(a) || cpsAlready.contains(op)))
    var funcs = m0.funcs
    var queue = funcs.indices.toList
    while queue.nonEmpty do
      val idx = queue.head
      queue = queue.tail
      val cur     = funcs(idx)
      val nregs   = cur.nregs
      val kReg    = nregs
      val contIdx = funcs.length
      val caps    = (0 until nregs).toList
      def hitP(b: List[Instr], k: Int): Boolean = b(k) match
        case Instr.Perform(_, op, as) => wants(op, as.length) && !alreadySplit(b, k)
        case _                        => false
      var cutD = -1
      def replP(i: Instr, depth: Int): List[Instr] = i match
        case Instr.Perform(d, op, as) =>
          cutD = d
          List(Instr.MkClos(kReg, contIdx, caps), Instr.Perform(d, op, as :+ kReg), Instr.Ret(d))
        case other => List(other)
      // THE HANDLE BODY, first and separately, and now at ANY depth in it — same shape and same
      // `Block`-and-`br` trick `splitCallers` documents at its own version. The continuation of a
      // point inside a `handle` body ends AT the handle, with the handle's own destination
      // register, not with a `ret` that would leave the whole function.
      var hpd   = -1
      var hdhOf = -1
      def replPH(i: Instr, dep: Int): List[Instr] =
        hpd = dstOfCut(i)
        replInHandle(hdhOf, kReg, contIdx, caps, dep, i)
      val hIdx = cur.body.indices.find { j => cur.body(j) match
        case Instr.Handle(dh, hb, _) =>
          cutAt(hb, hitP, (i, dep) => replInHandle(dh, kReg, contIdx, caps, dep, i)).isDefined
        case _ => false
      }.getOrElse(-1)
      if hIdx >= 0 then
        val Instr.Handle(dh, hbody, harms) = cur.body(hIdx): @unchecked
        hdhOf = dh
        cutAt(hbody, hitP, replPH).foreach { (nhb, hrest) =>
          // Wrapped for the reason `splitCallers` gives at its own version: the `br` the cut leaves
          // behind needs the same target on both sides of the split.
          val cont = Func(cur.name + "$q" + contIdx, nregs + 1, nregs + 1,
                          List(Instr.Move(hpd, nregs), Instr.Block(hrest), Instr.Ret(dh)))
          val newHandle = Instr.Handle(dh, List(Instr.Block(nhb)), harms)
          funcs = funcs.updated(idx, cur.copy(nregs = nregs + 1,
                                              body  = cur.body.updated(hIdx, newHandle))) :+ cont
          queue = queue :+ contIdx :+ idx
        }
      else
        cutAt(cur.body, hitP, replP).foreach { (nb, rest) =>
          // AN EMPTY REMAINDER STILL HAS A VALUE. A perform that is the last thing a function does
          // has a continuation that answers what it was resumed with, and a body with no `ret`
          // returns whatever `retVal` was seeded with — unit. One instruction, and without it the
          // shortest possible case is the one that answers wrongly.
          val tail = if rest.isEmpty then List(Instr.Ret(cutD)) else rest
          val cont = Func(cur.name + "$q" + contIdx, nregs + 1, nregs + 1,
                          Instr.Move(cutD, nregs) :: tail)
          funcs = funcs.updated(idx, cur.copy(nregs = nregs + 1, body = nb)) :+ cont
          queue = queue :+ contIdx :+ idx
        }
    m0.copy(funcs = funcs)

  /** The functions whose continuation must reach past them — those that perform, or call one that
    * does, where the arm actually needs the rest. See `splitCallers` for what is then done about it
    * and `crossFrameRefusal` for what is left over. */
  private def crossFrameSet(m: Module): Set[Int] =
    def bodyHas(b: List[Instr])(pred: Instr => Boolean): Boolean =
      b.exists {
        case _: Instr.Handle => false
        case i               => pred(i) || bodyHas(Instr.children(i))(pred)
      }
    // ONLY A CPS PERFORM COUNTS — one that carries a continuation, `args = params + 1`. The
    // TAIL-RESUMPTIVE encoding has no continuation at all: the arm computes a value, that value is
    // the perform's, and the performing function carries on normally, so a caller in between is
    // simply not involved. Refusing on encoding-blind grounds turned
    // `head-field-effect-shadow` — a runner-style handler the bridge answers correctly — into a
    // refusal, which is a false one and cost a corpus PASS.
    // AND ONLY A NON-TAIL-RESUMPTIVE ARM, which is the second half of the same point. When the arm
    // resumes ONCE as its last act, "run the arm and use its value" and "capture the rest, hand it
    // over, resume it" are the SAME computation — the class this bridge was always correct for. A
    // caller in between is then irrelevant. `head-field-effect-shadow` is exactly that shape, CPS
    // encoded and cross-frame, and refusing it cost a corpus PASS for nothing.
    val arms: List[HandlerArm] = m.funcs.flatMap(fn =>
      Instr.flatten(fn.body).collect { case Instr.Handle(_, _, as) => as }.flatten).filter(_.op >= 0)
    val armParams: Map[Int, Int] = arms.map(a => a.op -> a.params.length).toMap
    val needsRest: Set[Int] = arms.filter(a => !isTailResumptive(a)).map(_.op).toSet
    def isCpsPerform(i: Instr): Boolean = i match
      case Instr.Perform(_, op, as) =>
        armParams.get(op).exists(_ + 1 == as.length) && needsRest.contains(op)
      case _                        => false
    var mayPerform: Set[Int] = m.funcs.indices.filter(i =>
      bodyHas(m.funcs(i).body)(isCpsPerform)).toSet
    var changed = true
    while changed do
      changed = false
      m.funcs.indices.foreach { i =>
        if !mayPerform.contains(i) &&
           bodyHas(m.funcs(i).body) { case Instr.Call(_, f, _) => mayPerform.contains(f); case _ => false }
        then
          mayPerform = mayPerform + i
          changed = true
      }
    mayPerform

  /** WHAT `splitCallers` CANNOT REACH, refused by name rather than answered wrongly.
    *
    * The splitter turns a caller's remainder into a closure wherever that remainder is a SUFFIX of
    * an instruction list — the top level of a function body, and the body of a `handle`. Inside an
    * `if`, a `loop`, a `switch` or a `try` it is not: the remainder there is a suffix of the REGION,
    * and everything after the region would be silently skipped. That is the boundary `Cps.scala`'s
    * header calls step 3, and it is the same one `Exec` refuses for the same reason.
    *
    * NO TAIL TEST HERE ANY MORE. It used to refuse a non-tail call outright; the splitter now fixes
    * exactly those, and its own output — a call followed by the `move` that lands the answer in the
    * handle's register — would have tripped the old test and refused the thing that had just been
    * repaired. */
  private def crossFrameRefusal(m: Module): Unit =
    val mayPerform = crossFrameSet(m)
    def refuse(callee: Int): Nothing =
      throw Unsupported(
        "`" + m.funcs(callee).name + "` performs an effect and is called inside an `if`, `loop` or " +
        "`try` that is inside a handler ARM, or inside a `handle` nested in another handle's body — " +
        "the rebuild that gives a region its continuation reaches a function's body and the body of " +
        "a `handle` standing at its top level, and neither of those; the executor runs it " +
        "(`ssc3 exec`)")
    // WHAT THE FLAG MEANS, AND IT HAS BEEN WRONG TWICE, so it is spelled out rather than named.
    // `unreached` is "the rebuild cannot cut here", and that is a property of WHERE the `handle` is,
    // not of whether there is one:
    //
    //   * a `Handle` at a function body's top level — `splitCallers` cuts its BODY at any depth, so
    //     the body is reached; its ARMS are not, because `cutAt` never enters a `Handle` and the
    //     handle path cuts only `hbody`;
    //   * a `Handle` anywhere else — inside a region, inside an arm, inside another handle's body —
    //     is found by neither, so everything under it is unreached.
    //
    // Getting this wrong in the widening direction refused a program that works
    // (`js-effect-multishot-long-fold`, 204 -> refusal); getting it wrong the other way would answer
    // instead of refusing, which is worse. Both directions are measured by fixtures.
    def walk(b: List[Instr], atFuncTop: Boolean, unreached: Boolean, inRegion: Boolean): Unit =
      b.zipWithIndex.foreach { (i, k) =>
        i match
          case Instr.Call(_, f, _) =>
            if unreached && inRegion && mayPerform.contains(f) && !alreadySplit(b, k) then refuse(f)
          case Instr.Handle(_, hb, arms) =>
            walk(hb, false, unreached || !atFuncTop, false)
            arms.foreach(a => walk(a.body, false, true, false))
          case Instr.Block(bb)   => walk(bb, false, unreached, true)
          case Instr.Loop(bb)    => walk(bb, false, unreached, true)
          case Instr.If(_, t, e) => walk(t, false, unreached, true); walk(e, false, unreached, true)
          case Instr.Switch(_, arms, df) =>
            arms.foreach(a => walk(a.body, false, unreached, true)); walk(df, false, unreached, true)
          case Instr.Try(_, bb, _, h) =>
            walk(bb, false, unreached, true); walk(h, false, unreached, true)
          case _ => ()
      }
    m.funcs.foreach(fn => walk(fn.body, true, false, false))

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
  /** The frame COPY an arm activation runs in, and where the `lam`'s own argument ends up behind it.
    *
    * ARRAY: one `arr.slice`, so the copy is `local 0` and the argument moves to `local 1`.
    *
    * CELLS: N fresh cells, each initialised from the corresponding outer one — and every single
    * initialiser reads `local (sh + n)`. That is not a typo and it is the same sequential-`let`
    * property `MkClos` documents: binding `i` runs with `i` binders already in scope, and binding
    * `i` is register `r = n-1-i` whose outer cell sat at `local (r + sh + 1)`, so it has moved to
    * `local (r + sh + 1 + i)` = `local (sh + n)` — constant, while naming a different cell each
    * time. Afterwards the copy occupies `local 0 … local (n-1)` in register order and the argument
    * has moved to `local n`. */
  private def cloneFrame(cx: Ctx, sh: Int): (List[String], String) =
    val n = cx.frameSize
    if cellFrame then
      (List.fill(n)("(prim cell.new (prim cell.get (local " + (sh + n) + ")))"), "(local " + n + ")")
    else (List(cloneOf("(local " + (sh + 1) + ")")), "(local 1)")

  private def armClos(cx: Ctx, arm: HandlerArm, sh: Int): String =
    val (cl, argsAt) = cloneFrame(cx, sh)
    val binds =
      arm.params.zipWithIndex.map((p, i) => write(p, aget(argsAt, int(i)), 0)) ++
        // `k` IS A PAIR — the closure and the record the arm is running under. See `effCurRec`.
        // Bound FIRST among the arm's own bindings, before anything in the body can perform and
        // park a different record.
        List(write(arm.k, mkarr(List(aget(argsAt, int(arm.params.length)),
                                     aget(glob(effCurRec), int(0)))), 0),
             write(cx.ctl, int(0), 0))
    "(lam 1 (let (" + cl.mkString(" ") + ") " +
      sq(binds ++ List(seqOf(arm.body, cx, 0), read(cx.retVal, 0))) + "))"

  /** The `case x => …` clause, likewise a one-argument closure — but it takes the VALUE, not an
    * argument array, because that is how `Exec.applyRet` calls it. `retVal` is seeded with that value
    * so a clause body that falls off its end answers the value unchanged, which is the `case _ => v`
    * arm of `applyRet` written in the target. */
  private def retClos(cx: Ctx, arm: HandlerArm, sh: Int): String =
    val (cl, valAt) = cloneFrame(cx, sh)
    val pre = arm.params.headOption.toList.map(p => write(p, valAt, 0)) ++
      List(write(cx.ctl, int(0), 0), write(cx.retVal, valAt, 0))
    "(lam 1 (let (" + cl.mkString(" ") + ") " +
      sq(pre ++ List(seqOf(arm.body, cx, 0), read(cx.retVal, 0))) + "))"

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
    // NO CLONE HERE, so this is written in terms of `read`/`write` and is the same text in both
    // representations: inside `(lam 1 …)` the frame is one binder deeper, and inside the `let` that
    // saves `ctl` it is two. The argument array is `local 1` there, and `local 0` is the saved `ctl`.
    val binds =
      arm.params.zipWithIndex.map((p, i) => write(p, aget("(local 1)", int(i)), sh + 2)) ++
        List(write(arm.k, lit("unit"), sh + 2), write(cx.ctl, int(0), sh + 2))
    // Read the answer, put `ctl` back, hand the answer over — in that order, because restoring
    // first would overwrite `retVal`'s guard and reading after would read the restored frame.
    val restore = "(let (" + read(cx.retVal, sh + 2) + ") " +
      sq(List(write(cx.ctl, "(local 1)", sh + 3), "(local 0)")) + ")"
    "(lam 1 (let (" + read(cx.ctl, sh + 1) + ") " +
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
    val defCurRec = "(def " + effCurRec + " " + mkarr(List(lit("unit"))) + ")"
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
                        // The record the arm is about to run under, for `armClos` to pair with `k`.
                        aset(glob(effCurRec), int(0), "(local 0)"),
                        // THE CONTINUATION IS COMPOSED WITH THE CALLER FRAMES this handler owes —
                        // and only for the CPS encoding, which is the only one that has a
                        // continuation to compose.
                        // `Cps.split` gave the rest of the PERFORMING function; `splitCallers` gave
                        // the rest of each caller between here and the `handle`, parked on
                        // `effPending`. Resuming has to run both, in that order, which is what
                        // `effCompose` builds. When nothing was parked the slice is empty and the
                        // composed closure just calls the original — the shape that worked before.
                        ifThen(aget(aget("(local 0)", int(R_ISCPS)), "(local 2)"),
                               aset("(local 1)", arith("-", alen("(local 1)"), int(1)),
                                    "(app " + glob(effCompose) + " " +
                                      aget("(local 1)", arith("-", alen("(local 1)"), int(1))) + " " +
                                      "(prim arr.slice " + glob(effPending) + " " +
                                        aget("(local 0)", int(R_PENDING)) + " " +
                                        alen(glob(effPending)) + "))"),
                               lit("unit")),
                        // THE ARM RUNS AT THE HANDLER'S OWN PENDING DEPTH, and the frames
                        // between are taken off while it does. They belong to the CONTINUATION now
                        // — it was just composed with exactly this slice — so a perform inside the
                        // arm, or inside a resume the arm makes, must not compose with them a
                        // second time. `Exec.Perform` spells it `pending = pending.drop(depth)`
                        // with a `finally` that puts it back, and this is that in the target.
                        //
                        // INVISIBLE UNTIL A CONTINUATION PARKED A FRAME OF ITS OWN. Before regions
                        // the second perform re-composed with the SAME closure it already had, and
                        // `cross-frame-statement`'s remainder is `"END"` — a function whose value
                        // does not depend on its argument, so running it twice answers what running
                        // it once answers and the fixture stayed green over the defect. With a loop
                        // the stale frame is the previous ITERATION's continuation: it resumed the
                        // loop from the state it had captured, for ever, and the v2 stack went.
                        //
                        // Inside this `let`: taken=0, record=1, args=2, op=3, i=4.
                        "(let ((prim arr.slice " + glob(effPending) + " " +
                            aget("(local 0)", int(R_PENDING)) + " " + alen(glob(effPending)) + ")) " +
                          sq(List(
                            "(app " + glob(effPopAll) + " (local 0) " + int(0) + ")",
                            "(prim __tryFinally__ (lam 0 (app " +
                              aget(aget("(local 1)", int(R_ARMS)), "(local 3)") + " (local 2))) " +
                              "(lam 0 (app " + glob(effPushAll) + " (local 0) " + int(0) + ")))")) +
                        ")")),
                      "(app " + glob(effFind) + " " + arith("-", "(local 3)", int(1)) +
                        " (local 2) (local 1))") + ")") + "))"
    // NOT POPPED WHILE THE ARM RUNS. `Exec` leaves the handler on the list, so a `resume` whose
    // continuation performs the same operation again finds the same handler — which is what makes
    // `two-performs-multi-shot` produce a cross product instead of failing on the second perform.
    val defPerform = "(def " + effPerform + " (lam 2 (app " + glob(effFind) + " " +
      arith("-", alen(stack), int(1)) + " (local 1) (local 0))))"
    // RESUMING, and everything it needs comes from the CONTINUATION rather than from the stack.
    //
    // `k` is the pair `armClos` built: `(aget k 0)` is the closure `Cps.split` made, `(aget k 1)` is
    // the handler record it belongs to. Reading the return clause off the stack TOP was right only
    // while the continuation is resumed inside its own `handle`; an arm that returns a closure —
    // `case op(k) => (s) => resume(())(s)` — is resumed when the stack is empty, and the old code
    // then skipped the lifting and handed back the computation's bare value. `Exec` fixed the same
    // thing by making the continuation a `VCont` that carries its frame; this is that, in the
    // target. (v3-bridge-lags-the-executor-on-cross-frame-effects.)
    //
    // THE HANDLER IS REINSTALLED FOR THE DURATION, for the reason `Exec.resumeCont` gives: a deep
    // handler's continuation may perform again, and by then the `handle` has returned, so nothing on
    // the stack could answer. `__tryFinally__` so an escaping throw still pops.
    //
    // `(lam 0 …)` does not shift locals on this lane — the `Handle` arm above relies on the same
    // fact when it wraps its body — so the indices below are the `let` depths only.
    val defResume = "(def " + effResume + " (lam 2 " +
      "(let (" + aget("(local 1)", int(1)) + ") " +                                    // rec=0 k=2 v=1
        "(let (" + aget(aget("(local 0)", int(R_PERFORMS)), int(0)) + ") " +           // before=0 rec=1 k=3 v=2
          "(let (" + sq(List(
              "(prim arr.push " + stack + " (local 1))",
              "(prim __tryFinally__ (lam 0 (app " + aget("(local 3)", int(0)) + " (local 2))) " +
                "(lam 0 (prim arr.pop " + stack + ")))")) + ") " +                     // raw=0 before=1 rec=2
            sq(List(
              // A perform INSIDE the resumed computation ends here: its arm's value is this
              // resume's value, exactly as `Exec.resumeCont` catches what is aimed at its own
              // delimiter. Clearing the flag is what stops it travelling further out.
              "(prim cell.set " + glob(effAborting) + " " + lit("false") + ")",
            ifThen(arith("!=", aget(aget("(local 2)", int(R_PERFORMS)), int(0)), "(local 1)"),
                   "(local 0)",
                   ifThen(aget("(local 2)", int(R_HASRET)),
                          "(app " + aget("(local 2)", int(R_RET)) + " (local 0))",
                          "(local 0)")))) +
          ")))" + "))"
    // ── crossing a call frame ────────────────────────────────────────────────
    val pend = glob(effPending)
    val defPending = "(def " + effPending + " (prim arr.new))"
    val defPush = "(def " + effPush + " (lam 1 " + sq(List(
      "(prim arr.push " + pend + " (local 0))", "(local 0)")) + "))"
    val defPop  = "(def " + effPop  + " (lam 1 " + sq(List(
      "(prim arr.pop " + pend + ")", "(local 0)")) + "))"
    // PUSH THE INHERITED CHAIN BACK WHILE THE FIRST HALF RUNS. A resumed computation may perform
    // AGAIN — two ticks of the same effect — and that inner perform's continuation owes the caller
    // frames this one has not run yet. Parking them only around our own walk left the second perform
    // composing with nothing, and `H:a|H:b|END` came out `H:a|END`: the first tick was right and the
    // rest of the program was lost. `Exec.resumeCont` sets `pending = c.seg` for exactly this.
    //
    // And if the inner perform DID take them over, the flag says so and the walk is skipped — the
    // inner arm's value is this resume's value, which is what `Exec` spells as catching the unwind
    // aimed at its own delimiter.
    val defPushAll = "(def " + effPushAll + " (lam 2 " +                   // chain=1, i=0
      ifThen(arith(">=", "(local 0)", alen("(local 1)")),
             lit("unit"),
             sq(List("(prim arr.push " + pend + " " + aget("(local 1)", "(local 0)") + ")",
                     "(app " + glob(effPushAll) + " (local 1) " + arith("+", "(local 0)", int(1)) + ")"))) + "))"
    val defPopAll = "(def " + effPopAll + " (lam 2 " +                     // chain=1, i=0
      ifThen(arith(">=", "(local 0)", alen("(local 1)")),
             lit("unit"),
             sq(List("(prim arr.pop " + pend + ")",
                     "(app " + glob(effPopAll) + " (local 1) " + arith("+", "(local 0)", int(1)) + ")"))) + "))"
    val defRun = "(def " + effRun + " (lam 3 " +                           // clos=2, chain=1, v=0
      sq(List("(app " + glob(effPushAll) + " (local 1) " + int(0) + ")",
              "(let ((app (local 2) (local 0))) " +                        // acc=0, clos=3, chain=2, v=1
                sq(List("(app " + glob(effPopAll) + " (local 2) " + int(0) + ")",
                        ifThen("(prim cell.get " + glob(effAborting) + ")",
                               "(local 0)",
                               "(app " + glob(effChain) + " (local 2) " + int(0) + " (local 0))"))) + ")")) + "))"
    // `(lam 2)` — clos, chain — and the closure it RETURNS is `(lam 1)`, inside which the enclosing
    // locals have each moved up by one. That shift is the one `armClos` documents.
    val defCompose = "(def " + effCompose + " (lam 2 (lam 1 (app " + glob(effRun) +
      " (local 2) (local 1) (local 0)))))"
    // chain, i, acc — walk what the callers parked, innermost first, feeding each the last answer.
    val defChain = "(def " + effChain + " (lam 3 " +
      ifThen(arith(">=", "(local 1)", alen("(local 2)")),
             "(local 0)",
             "(app " + glob(effChain) + " (local 2) " + arith("+", "(local 1)", int(1)) +
               " (app " + aget("(local 2)", "(local 1)") + " (local 0)))") + "))"
    val defAborting = "(def " + effAborting + " (prim cell.new " + lit("false") + "))"
    List(defStack, defCurRec, defTop, defPerforms, defFind, defPerform, defResume,
         defPending, defPush, defPop, defCompose, defChain, defAborting,
         defPushAll, defPopAll, defRun).mkString(" ")

  // ── instructions ────────────────────────────────────────────────────────────
  private def stmt(i: Instr, cx: Ctx, sh: Int): String = i match
    case Instr.Const(d, k)        => write(d, litOf(cx.m.consts(k)), sh)
    case Instr.Move(d, a)         => write(d, read(a, sh), sh)
    case Instr.Bin(o, k, d, a, b) => write(d, opText(o, k, read(a, sh), read(b, sh)), sh)
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
    case Instr.Call(d, fi, as) if cx.crossK >= 0 && cx.needsRest.contains(fi) =>
      // A CALL `splitCallers` CUT THE CALLER AT. Three things happen around it and each is needed:
      //
      //   * the caller's remainder — already a closure in `cx.crossK` — is PARKED, so a perform
      //     deeper down can compose it into the continuation it hands the arm;
      //   * on a NORMAL return the parked closure is called with the result, which is how the
      //     remainder still runs when the callee did not perform after all;
      //   * on an ABORT it is not, because the value belongs to the `handle` and the remainder now
      //     belongs to the continuation that took it over.
      //
      // The `pop` runs on both paths — it is the callee's own frame leaving, not a decision.
      // SHIFTS: the `let`'s BINDING runs in the outer environment and stays at `sh`; only its BODY
      // is one deeper. Getting that backwards reads register k at `sh+1`, which is textually the
      // same as register k+1 at `sh` — the ambiguity the `Resume` comment below already paid for.
      write(d, "(let (" + sq(List(
                 "(app " + glob(effPush) + " " + read(cx.crossK, sh) + ")",
                 "(app (global " + cx.m.funcs(fi).name + ")" + args(as, sh) + ")")) + ") " +
                 sq(List("(app " + glob(effPop) + " " + int(0) + ")",
                         ifThen("(prim cell.get " + glob(effAborting) + ")",
                                "(local 0)",
                                "(app " + read(cx.crossK, sh + 1) + " (local 0))"))) + ")", sh)

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
        mkarr(List(int(0))),
        // How deep the caller-continuation stack was when this `handle` began: a perform takes only
        // what was parked INSIDE it, never its own caller's remainder.
        alen(glob(effPending)),
        mkarr((0 until cx.nOps).toList.map(o =>
          lit(if ops.find(_.op == o).exists(a => armIsCps(cx, a)) then "true" else "false")))))
      // Inside the `let` the record is `local 0` and the frame has moved to `local (sh+1)`. The
      // record's closures were built in the binding expression, one env shallower — which is why
      // `armClos` is passed `sh` and looks for the frame at `sh+1` from inside its own `lam`.
      // THE FRAME IS ADDRESSED WITH `read`/`write`, NOT `aget`/`aset`, and this line is where that
      // went wrong once: with the cell representation the frame is no longer an array, and
      // `arr.get` on a cell dies inside v2 as `expected Array, got <foreign>`. The RECORD below is
      // a genuine array and keeps `aget`. Only `handle-return` reaches here — it is the one fixture
      // with a `case x => …` clause — which is why the effects gate caught it and nothing else did.
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
          ifThen(arith("==", read(cx.ctl, sh + 1), int(0)),
                 ifThen(arith("==", aget(aget("(local 0)", int(R_PERFORMS)), int(0)), int(0)),
                        write(d, "(app " + aget("(local 0)", int(R_RET)) + " " + read(d, sh + 1) + ")",
                              sh + 1),
                        lit("unit")),
                 lit("unit"))
      // The `handle` is where an abort stops: it clears the flag before the lift, so the value it
      // holds is an ordinary one again for everything outside.
      "(let (" + rec + ") " + sq(List("(prim arr.push " + glob(effStack) + " (local 0))", run,
                                      "(prim cell.set " + glob(effAborting) + " " + lit("false") + ")",
                                      lift)) + ")"

    case Instr.Perform(d, op, as) =>
      performIsAnswerable(cx, op)
      // THE FLAG GOES UP WITH THE VALUE. The arm's result is the `handle`'s, and between here and
      // there stand the callers `splitCallers` cut: each one has to hand it on rather than run its
      // own remainder, which now belongs to the continuation. Cleared by whoever consumes it — the
      // `handle`, or the `resume` that re-entered.
      write(d, "(let ((app " + glob(effPerform) + " " + int(op) + " " +
                 // The BINDING expression is evaluated in the outer environment — the `let` has not
                 // bound anything yet — so the operands stay at `sh`. Reading them one deeper is the
                 // shift mistake this file's `Resume` comment records paying for once already.
                 mkarr(as.map(r => read(r, sh))) + ")) " +
                 sq(List("(prim cell.set " + glob(effAborting) + " " + lit("true") + ")",
                         "(local 0)")) + ")", sh)

    // Resuming IS calling: after `Cps.split` the continuation is an ordinary closure, so this is an
    // application with the return-clause rule wrapped round it.
    //
    // NO `let` HERE, AND THAT IS NOT A SIMPLIFICATION FOR ITS OWN SAKE. `before` has to be read
    // BEFORE the continuation runs, and an argument list already gives that: `(app f a b)` evaluates
    // `a` then `b`. The `let` that used to name it put the operand reads ONE BINDER DEEPER than the
    // statement, and with the cell frame — where a register's address is `local (r + sh)` — a read
    // of register k at `sh+1` is textually identical to a read of register k+1 at `sh`. The
    // temporary fold matches on text, so it substituted into what it thought was its own register
    // and was in fact the CONTINUATION: `multi-shot` died with `app: not a function: 1`. Keeping
    // every operand at the statement's own shift removes the ambiguity at the source.
    case Instr.Resume(d, k, v) =>
      write(d, "(app " + glob(effResume) + " " + read(k, sh) + " " + read(v, sh) + ")", sh)

    // EVERY `Instr` NOW HAS AN ARM, so this is unreachable — and the compiler said so, on every
    // build, as `Unreachable case except for null`. A warning in a build is not free: this repo has
    // had one land in a gate's captured stderr and read as a program producing different output.
    // It is spelled `case null` rather than deleted because the match is over a Java-visible enum
    // and a null reaching here should still be a named refusal, not a `MatchError`.
    case null => throw Unsupported("a null instruction")

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

  /** Does this instruction emit its OWN operand reads at the statement's shift, or deeper?
    *
    * THE FOLD MATCHES ON TEXT, and the pattern it looks for is `read(d, sh)`. With the cell frame a
    * register's address is `local (r + sh)`, which means register `r` at shift `sh+1` and register
    * `r+1` at shift `sh` are THE SAME STRING. Any reader that binds something before reading its
    * operands therefore offers the fold a target that looks right and is not — `multi-shot` died
    * with `app: not a function: 1` when a `const` was folded over a `resume`'s continuation.
    *
    * `Resume` was fixed at the source by dropping its `let`. `MkClos` cannot be: `(let (c0 c1 …) …)`
    * binds SEQUENTIALLY, so capture `i` is genuinely read at `sh + i` and that is what makes closure
    * capture correct in the first place. So it is excluded, and the exclusion costs one folding
    * opportunity per closure rather than a class of wrong answers.
    *
    * The array frame was immune and that is worth saying, because it is why this was invisible until
    * the representation changed: there the text carries the register index literally,
    * `(prim arr.get (local sh) (lit (int r)))`, so shift and register cannot be confused. */
  private def readsAtOwnShift(i: Instr): Boolean = i match
    case _: Instr.MkClos => false
    case _               => true

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
           readsOf(arr(p + 1)).count(_ == d) == 1 && readsAtOwnShift(arr(p + 1))
        then
          // THE PREFIX IS ASKED OF `write`, NOT SPELLED AGAIN HERE. It used to read
          // `"(prim arr.set " + frameAt(sh) + …`, and when the frame became cells that string
          // stopped matching anything — so the fold silently stopped firing in the representation
          // it was written for, with no test able to notice: `ifloop` emitted 765 bytes with the
          // fold on and 765 with it off. Identical output across an on/off pair is what a dead
          // optimisation looks like, and it reads as "this buys nothing" rather than "this did not
          // run". `write(d, "", sh)` is `<prefix>)`, so dropping the last character IS the prefix,
          // and it can never drift from what `write` emits because it is what `write` emits.
          val pfx = write(d, "", sh).dropRight(1)
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
    // `brif c 1` exits when `c` holds, so the loop runs while it does NOT — and when the thing that
    // computed `c` is a comparison, the negation is free, because a comparison has an inverse.
    //
    // `Optimize.invertible` is consulted rather than re-stated: on floats a NaN compares false BOTH
    // ways, so `!(a >= b)` is not `a < b` there, and that rule must have one definition. It is the
    // same predicate `Optimize.invert` used to turn `lt` + `not` into `ge` in the first place, so
    // this is undoing a rewrite under exactly the condition that permitted it.
    val (reads, writes) = cx.census
    val inverted = pre.lastOption match
      case Some(Instr.Bin(o, k, d, a, b))
          if d == c && d >= cx.f.nparams && d < cx.f.nregs &&
             writes(d) == 1 && reads(d) == 1 && invertCond && Optimize.invertible(o, k) =>
        Some((pre.init, opText(Optimize.inverseOf(o), k, read(a, sh), read(b, sh)), a, b))
      case _ => None
    inverted match
      case Some((head, cmp, a, b)) =>
        // The comparison IS the condition now — the instruction that produced `c` is GONE rather
        // than folded. Its operands still want the fold, so the virtual reader reads both of them.
        val arr = (head :+ Instr.TailCall(0, List(a, b))).toArray
        val texts = (head.map(i => stmt(i, cx, sh)) :+ cmp).toArray
        if foldTemps then fold(arr, texts, cx, sh)
        sq(texts.filter(_ != null).toList)
      case None =>
        // The general form: compute `c`, then compare it against `false`. The trailing `Instr.Ret(c)`
        // is a VIRTUAL READER, never emitted — it exists so `fold` can see that the comparison reads
        // `c` and fold the statement computing it into the comparison.
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

  /** The frame's bindings, innermost-last, for whichever representation is on.
    *
    * CELLS: one binding per register, in REVERSE register order so that register `r` ends up at
    * `local r`. A parameter goes straight into its cell rather than being copied in by a prologue —
    * and the address it sits at is arithmetic worth doing once here rather than trusting twice.
    * Binding `r` is at index `i = N-1-r`, so `i` binders are already in scope when its initialiser
    * runs; parameter `r` starts at `local (P-1-r)` in the `lam` body, so it is at `local (P-1-r+i)`
    * = `local (P+N-2-2r)`. Registers at or above `nparams` start at 0, which is what `Array.fill`
    * gave them before. */
  private def frameBinds(cx: Ctx, f: Func): List[String] =
    val n = cx.frameSize
    if cellFrame then
      (0 until n).toList.reverse.map { r =>
        val init = if r < f.nparams then "(local " + (f.nparams + n - 2 - 2 * r) + ")" else int(0)
        "(prim cell.new " + init + ")"
      }
    else
      // `Array.fill(n)(0)` — the frame. This is why SSC3-1 was on the critical path rather than
      // beside it: V-0 stands on a working mutable array, and `new Array[T](n)` was building one slot.
      List("(prim __method__ " + lit("(str \"fill\")") + " (ctor Array) " + int(n) + " " + int(0) + ")")

  private def func(m: Module, f: Func, crossK: Int = -1, needsRest: Set[Int] = Set.empty): String =
    val cx = Ctx(m, f, crossK, needsRest)
    // Parameters arrive as lam binders, innermost LAST: inside `(lam P …)` param i is `local (P-1-i)`,
    // and the frame's `let` shifts every one of them by one. Measured against the oracle, not
    // reasoned about: `(lam 2 …)` puts the FIRST parameter at `local 1`.
    //
    // With cells there is no prologue at all — the parameters ARE the initialisers, and `ctl` starts
    // at 0 because every non-parameter cell does.
    val prologue =
      if cellFrame then Nil
      else (0 until f.nparams).toList.map(i => write(i, "(local " + (f.nparams - i) + ")", 0)) :+
             write(cx.ctl, int(0), 0)
    val whole = sq(prologue :+ seqOf(f.body, cx, 0) :+ read(cx.retVal, 0))
    "(def " + f.name + " (lam " + f.nparams + " (let (" + frameBinds(cx, f).mkString(" ") + ") " +
      whole + ")))"

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
    // CROSSING A CALL FRAME. `splitCallers` turns the caller's remainder into a closure so the
    // continuation can include it; what it cannot reach — a call inside an `if`, `loop` or `try`,
    // whose remainder is not a suffix of any instruction list — is still refused by name.
    // ORDER MATTERS AND IS ONE WAY ROUND. A region `perform` that gets a continuation turns its
    // function into one that performs in the CPS sense, which is what `crossFrameSet` looks for —
    // so the region split has to happen before the caller census, or the callers of that function
    // are never cut and the continuation stops at its own frame.
    val m1 = if usesEffects(m) then splitRegionPerforms(m) else m
    val needsRest = if usesEffects(m1) then crossFrameSet(m1) else Set.empty[Int]
    val (mm, kOf) = if needsRest.isEmpty then (m1, Map.empty[String, Int]) else splitCallers(m1, needsRest)
    if usesEffects(mm) then crossFrameRefusal(mm)
    // FIELD NAMES ARE HANDED TO v2, because on this lane v2 does the rendering and it can only apply
    // the `_show` rule to a class whose field names it knows. Its own front tells it with
    // `__regfields__` (`ssc1-lower` K62.28); the bridge had never said anything, so a value that
    // named its own rendering printed `Lens(_.x)` on the executor and `Optic(Lens, .x, <closure>,
    // <closure>)` here — the two lanes disagreeing about one program, which is what I-3 forbids.
    //
    // ONLY THE TYPES THAT HAVE NAMES, so a module of builtins emits exactly the text it emitted
    // before this existed and every earlier A/B stays comparable. Emitted as a `def` whose body runs
    // the prim, because `run-ir` executes definitions in order and there is no other preamble.
    val regs = mm.types.filter(_.fieldNames.nonEmpty).zipWithIndex.map { (t, i) =>
      "(def __ssc3_regfields_" + i.toString + " (prim __regfields__ " + lit("(str " + quote(t.name) + ")") + " " +
        t.fieldNames.foldRight("(ctor Nil)")((n, acc) =>
          "(ctor Cons " + lit("(str " + quote(n) + ")") + " " + acc + ")") + "))"
    }.mkString(" ")
    val eff = if usesEffects(m) then effectDefs + " " else ""
    val defs = (if regs.isEmpty then "" else regs + " ") +
               (if cells.isEmpty then "" else cells + " ") + eff +
      mm.funcs.map(f => func(mm, f, kOf.getOrElse(f.name, -1), needsRest)).mkString(" ")
    val entryName = mm.funcs(mm.entry).name
    "(program (defs " + defs + ") (entry (app (global " + entryName + "))))"
