package ssc3

// SSC3-J2 — closure compilation. Design: `specs/ssc3-jit.md` §3, "J2's design".
//
// The tree-walker decodes every instruction on every execution: a pattern match on `Instr`, then a
// read of each operand out of the node. Neither depends on the frame, so neither has to happen more
// than once per instruction in the PROGRAM rather than once per EXECUTION. Compiling a body to a
// vector of closures moves both to load time.
//
// `Instr.Const` is the clearest case. Interpreted it is a match, a pool index and — before J0a — an
// allocation. Compiled it is a closure holding the finished `Value`, and running it is one array
// store.
//
// COVERAGE IS COMPLETE FROM THIS COMMIT, and that is the one thing this file must not get wrong.
// v1's JIT compiled a subset and permanently disabled every function it could not handle, which is
// why `specs/jit-completeness.md` exists and why it lists 310 disabled functions. Here, an opcode
// that is not specialized compiles to `regs => Exec.stepOne(m, i, regs)` — the same interpreter arm,
// one closure deep. There is no program this lane refuses and no bail list to grow.
//
// Written in the Scala 3 ∩ ScalaScript 2 subset: function values (`Value.VLazy` already carries
// one), `Array`, local `var`/`while`, no mutable collections, no `val`/`var` members in the object.

/** A compiled instruction: given the frame, do it and say what happened.
  *
  * The SAME `Signal` the tree-walker returns, deliberately. Two execution strategies that agree on
  * the control-flow protocol can be compared program-for-program, which is what `--identity` does
  * with them, and a second protocol would have made that comparison impossible to write. */
type Op = Array[Value] => Signal

object Compile:

  /** Run a compiled body. The dispatch loop, and the reason the array is an array: `exec` walks a
    * `List[Instr]` and pays a pointer chase per instruction on every pass through a loop body. */
  def run(ops: Array[Op], regs: Array[Value]): Signal =
    var i = 0
    var out: Signal = Signal.Done
    var running = true
    while running && i < ops.length do
      val s = ops(i)(regs)
      if s == Signal.Done then i = i + 1
      else
        out = s
        running = false
    out

  def func(m: Module, f: Func): Array[Op] = body(m, f.body)

  def body(m: Module, b: List[Instr]): Array[Op] =
    var n = 0
    var c = b
    while c.nonEmpty do
      n = n + 1
      c = c.tail
    val out = new Array[Op](n)
    var i = 0
    c = b
    while c.nonEmpty do
      out(i) = one(m, c.head)
      c = c.tail
      i = i + 1
    out

  private def one(m: Module, i: Instr): Op = i match

    // ── the opcodes worth specializing ────────────────────────────────────────────────────────
    //
    // Chosen by what a loop body contains, which is where an interpreter spends its time: a
    // constant, a move, an arithmetic operation and a branch. Everything else is a call away and
    // costs nothing extra to leave interpreted until a measurement asks for it.

    case Instr.Const(d, k) =>
      // The `Value` is resolved HERE, once per instruction in the program. Nothing is left to do at
      // run time but the store.
      val v = Exec.constOf(m.consts(k))
      regs => { regs(d) = v; Signal.Done }

    case Instr.Move(d, a) => regs => { regs(d) = regs(a); Signal.Done }

    case Instr.Bin(op, kind, d, a, b) =>
      // `kind` is what `Specialize` proved (SSC3-J1), captured rather than re-read. `binK` still
      // falls back when the values are not the shape the kind claims, so a specializer defect stays
      // a performance outcome on this lane exactly as it is on the other one.
      regs => { regs(d) = Exec.binK(m, op, kind, regs(a), regs(b)); Signal.Done }

    case Instr.Br(d) =>
      // One `Signal.Branch` per program point instead of one per execution. Safe only because
      // `Signal.Branch` is an immutable case carrying an `Int`: sharing it is sharing a number.
      val s: Signal = Signal.Branch(d)
      regs => s

    case Instr.BrIf(c, d) =>
      val s: Signal = Signal.Branch(d)
      regs => if Exec.truthy(regs(c)) then s else Signal.Done

    case Instr.Ret(a) => regs => Signal.Ret(regs(a))

    // ── regions: the compiled sub-body replaces a re-walk of the list ─────────────────────────
    //
    // The branch arithmetic is `Exec`'s, restated rather than shared because there is nothing to
    // share — it is three lines and the two lanes must be independently readable for the
    // differential between them to be worth anything. `Br 0` leaves a `Block` or an `If` and
    // REPEATS a `Loop`; falling off the end of a loop exits it, which is WebAssembly's rule.

    case Instr.Block(b) =>
      val ops = body(m, b)
      regs =>
        run(ops, regs) match
          case Signal.Branch(0) => Signal.Done
          case Signal.Branch(d) => Signal.Branch(d - 1)
          case other            => other

    case Instr.Loop(b) =>
      val ops = body(m, b)
      regs =>
        var out: Signal = Signal.Done
        var looping = true
        while looping do
          run(ops, regs) match
            case Signal.Branch(0) => ()
            case Signal.Branch(d) => out = Signal.Branch(d - 1); looping = false
            case Signal.Done      => looping = false
            case other            => out = other; looping = false
        out

    case Instr.If(c, t, e) =>
      val tOps = body(m, t)
      val eOps = body(m, e)
      regs =>
        run(if Exec.truthy(regs(c)) then tOps else eOps, regs) match
          case Signal.Branch(0) => Signal.Done
          case Signal.Branch(d) => Signal.Branch(d - 1)
          case other            => other

    // ── everything else stays interpreted, ON PURPOSE ─────────────────────────────────────────
    //
    // Calls, data, arrays, globals, effects, `Try`, `Switch`, `Invoke`, `Prim`. Each is one closure
    // away from the arm the tree-walker uses, so this lane runs every program the other one runs,
    // today, and an opcode moves across when a measurement asks for it rather than when someone
    // notices it is missing.
    case other => regs => Exec.stepOne(m, other, regs)
