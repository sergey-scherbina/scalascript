package ssc3

// The SSC IR verifier — v3/specs/10-ssc-ir.md §4.
//
// Validation is MANDATORY, not a debug mode: every load of IR runs this. An invalid module must be
// impossible to RUN, not merely unlikely to be produced. That is what buys the correctness this
// version exists for.
//
// The verifier reports the first failure with the instruction PATH that produced it. A verifier
// that says only "invalid" makes the reader bisect a module by hand, which is how people stop
// running it.

final case class VerifyError(path: String, message: String):
  def render: String = path + ": " + message

object Verify:

  /** `None` means the module is valid. */
  def module(m: Module): Option[VerifyError] =
    if m.entry < 0 || m.entry >= m.funcs.length then
      Some(VerifyError("module", "entry " + m.entry + " is not a function (" + m.funcs.length + " defined)"))
    else badType(m.types, 0).orElse(funcs(m, m.funcs, 0))

  // A TypeDef with a negative field count would make rule 4 vacuous — every index would be
  // rejected, so a malformed module would look like a malformed PROGRAM. Check the tables first.
  private def badType(ts: List[TypeDef], i: Int): Option[VerifyError] =
    if ts.isEmpty then None
    else if ts.head.fields < 0 then
      Some(VerifyError("type t" + i, "negative field count " + ts.head.fields))
    else badType(ts.tail, i + 1)

  private def funcs(m: Module, fs: List[Func], i: Int): Option[VerifyError] =
    if fs.isEmpty then None
    else
      val f = fs.head
      val here = "func " + f.name
      val e =
        if f.nparams < 0 || f.nregs < 0 then
          Some(VerifyError(here, "negative nparams/nregs"))
        else if f.nparams > f.nregs then
          Some(VerifyError(here, "nparams " + f.nparams + " exceeds nregs " + f.nregs))
        // Rule 5. A body that runs off its end has no defined result, so this is checked before the
        // instructions rather than after: the diagnostic is about the FUNCTION, not about whichever
        // instruction happens to be last.
        else if !Instr.endsInTerminator(f.body) then
          Some(VerifyError(here, "body does not end in a terminator (ret/tailcall/br)"))
        else block(m, f, f.body, here, 0, 0)
      e.orElse(funcs(m, fs.tail, i + 1))

  /** `depth` is the number of enclosing branchable regions, which is what a `Br` counts against. */
  private def block(m: Module, f: Func, body: List[Instr], path: String, idx: Int, depth: Int): Option[VerifyError] =
    if body.isEmpty then None
    else
      val at = path + " #" + idx
      one(m, f, body.head, at, depth).orElse(block(m, f, body.tail, path, idx + 1, depth))

  private def one(m: Module, f: Func, i: Instr, at: String, depth: Int): Option[VerifyError] =
    // Rule 1 — every register index is < nregs. Collected per instruction so the message can name
    // the offending register rather than only the instruction.
    def reg(r: Int, what: String): Option[VerifyError] =
      if r < 0 || r >= f.nregs then
        Some(VerifyError(at, what + " r" + r + " is outside the frame (nregs=" + f.nregs + ")"))
      else None
    def regs(rs: List[Int], what: String): Option[VerifyError] =
      if rs.isEmpty then None else reg(rs.head, what).orElse(regs(rs.tail, what))
    // Rule 3 — pool and table indices in range.
    def idxIn(v: Int, size: Int, what: String): Option[VerifyError] =
      if v < 0 || v >= size then Some(VerifyError(at, what + " " + v + " is out of range (0.." + (size - 1) + ")"))
      else None
    // Rule 2 — a branch may not leave more regions than enclose it.
    def brOk(d: Int): Option[VerifyError] =
      if d < 0 then Some(VerifyError(at, "negative branch depth " + d))
      else if d >= depth then
        Some(VerifyError(at, "br " + d + " leaves more regions than enclose it (depth=" + depth + ")"))
      else None
    // Rule 3, continued — a call's argument count must equal the callee's declared arity. Getting
    // this wrong is not a type error that shows up later; it is a frame built to the wrong size.
    def arity(fi: Int, n: Int): Option[VerifyError] =
      idxIn(fi, m.funcs.length, "function").orElse {
        val callee = m.funcs(fi)
        if callee.nparams != n then
          Some(VerifyError(at, "call to " + callee.name + " passes " + n + " argument(s), it takes " + callee.nparams))
        else None
      }

    i match
      case Instr.Const(d, k)          => reg(d, "dst").orElse(idxIn(k, m.consts.length, "const"))
      case Instr.Move(d, a)           => reg(d, "dst").orElse(reg(a, "src"))
      case Instr.Un(_, _, d, a)       => reg(d, "dst").orElse(reg(a, "operand"))
      case Instr.Bin(_, _, d, a, b)   => reg(d, "dst").orElse(reg(a, "lhs")).orElse(reg(b, "rhs"))

      case Instr.Block(b)             => block(m, f, b, at + " > block", 0, depth + 1)
      case Instr.Loop(b)              => block(m, f, b, at + " > loop", 0, depth + 1)
      case Instr.If(c, t, e) =>
        reg(c, "cond")
          .orElse(block(m, f, t, at + " > if.then", 0, depth + 1))
          .orElse(block(m, f, e, at + " > if.else", 0, depth + 1))
      case Instr.Br(d)                => brOk(d)
      case Instr.BrIf(c, d)           => reg(c, "cond").orElse(brOk(d))

      case Instr.Call(d, fi, as)      => reg(d, "dst").orElse(regs(as, "arg")).orElse(arity(fi, as.length))
      case Instr.CallV(d, c, as)      => reg(d, "dst").orElse(reg(c, "callee")).orElse(regs(as, "arg"))
      case Instr.MkClos(d, fi, cs)    => reg(d, "dst").orElse(regs(cs, "capture")).orElse(idxIn(fi, m.funcs.length, "function"))
      case Instr.TailCall(fi, as)     => regs(as, "arg").orElse(arity(fi, as.length))
      case Instr.Ret(a)               => reg(a, "result")

      case Instr.MkData(d, t, as) =>
        reg(d, "dst").orElse(regs(as, "field")).orElse(idxIn(t, m.types.length, "type")).orElse {
          val td = m.types(t)
          if td.fields != as.length then
            Some(VerifyError(at, td.name + " takes " + td.fields + " field(s), given " + as.length))
          else None
        }
      // Rule 4 — a field index is checked against the ONE type table, which is also what the
      // emitter reads. A second copy of the layout is the bug family this design removes.
      case Instr.Field(d, x, t, ix) =>
        reg(d, "dst").orElse(reg(x, "receiver")).orElse(idxIn(t, m.types.length, "type")).orElse {
          val td = m.types(t)
          if ix < 0 || ix >= td.fields then
            Some(VerifyError(at, "field " + ix + " of " + td.name + ", which has " + td.fields + " field(s)"))
          else None
        }
      case Instr.Tag(d, a) => reg(d, "dst").orElse(reg(a, "receiver"))
      case Instr.Switch(s, arms, dflt) =>
        reg(s, "scrutinee")
          .orElse(switchArms(m, f, arms, at, 0, depth))
          .orElse(block(m, f, dflt, at + " > switch.default", 0, depth + 1))

      case Instr.NewArr(d, n)         => reg(d, "dst").orElse(reg(n, "length"))
      case Instr.ArrGet(d, a, ix)     => reg(d, "dst").orElse(reg(a, "array")).orElse(reg(ix, "index"))
      case Instr.ArrSet(a, ix, v)     => reg(a, "array").orElse(reg(ix, "index")).orElse(reg(v, "value"))
      case Instr.ArrLen(d, a)         => reg(d, "dst").orElse(reg(a, "array"))
      case Instr.GlobGet(d, g)        => reg(d, "dst").orElse(idxIn(g, m.globals.length, "global"))
      case Instr.GlobSet(g, a)        => reg(a, "value").orElse(idxIn(g, m.globals.length, "global"))

      case Instr.Perform(d, _, as)    => reg(d, "dst").orElse(regs(as, "arg"))
      case Instr.Handle(d, b, arms) =>
        // An arm's `params` and `k` are ORDINARY REGISTER INDICES of this function's frame, so
        // rule 1 covers them here with the same helper as everything else. That is the whole reason
        // `specs/10-ssc-ir.md` §3 names them instead of fixing their positions in a frame of the
        // arm's own: an invariant this pass cannot state is not an invariant.
        def armRegs(as: List[HandlerArm]): Option[VerifyError] =
          if as.isEmpty then None
          else regs(as.head.params, "handler param")
                 .orElse(reg(as.head.k, "continuation"))
                 .orElse(armRegs(as.tail))
        reg(d, "dst")
          .orElse(armRegs(arms))
          .orElse(block(m, f, b, at + " > handle.body", 0, depth + 1))
          .orElse(handlerArms(m, f, arms, at, 0, depth))
      case Instr.Resume(d, k, v)      => reg(d, "dst").orElse(reg(k, "continuation")).orElse(reg(v, "value"))

      case Instr.Try(d, b, x, h) =>
        reg(d, "dst").orElse(reg(x, "exception"))
          .orElse(block(m, f, b, at + " > try.body", 0, depth + 1))
          .orElse(block(m, f, h, at + " > try.catch", 0, depth + 1))
      case Instr.Invoke(d, nm, r, as) =>
        reg(d, "dst").orElse(reg(r, "receiver")).orElse(regs(as, "arg"))
          .orElse(idxIn(nm, m.consts.length, "const")).orElse {
            // The method name must actually BE a name. Without this the pool index is just an int
            // and a backend would discover at run time that it was handed a number to dispatch on.
            m.consts(nm) match
              case Lit.LStr(_) => None
              case other       => Some(VerifyError(at, "invoke name const " + nm + " is not a string"))
          }
      case Instr.Prim(d, p, as)       => reg(d, "dst").orElse(regs(as, "arg")).orElse(idxIn(p, m.prims.length, "prim"))

  private def switchArms(m: Module, f: Func, arms: List[SwitchArm], at: String, i: Int, depth: Int): Option[VerifyError] =
    if arms.isEmpty then None
    else
      block(m, f, arms.head.body, at + " > switch#" + i, 0, depth + 1)
        .orElse(switchArms(m, f, arms.tail, at, i + 1, depth))

  private def handlerArms(m: Module, f: Func, arms: List[HandlerArm], at: String, i: Int, depth: Int): Option[VerifyError] =
    if arms.isEmpty then None
    else
      block(m, f, arms.head.body, at + " > handle#" + i, 0, depth + 1)
        .orElse(handlerArms(m, f, arms.tail, at, i + 1, depth))
