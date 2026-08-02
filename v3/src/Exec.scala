package ssc3

// SSC3-3b — the executor. v3/specs/10-ssc-ir.md §1, v3/SPRINT.md SSC3-3b.
//
// The bridge (SSC3-3) makes v3 USABLE by inheriting v2's backends. This makes v3 BETTER than v2,
// and only in the three ways the bridge structurally cannot:
//
//   1. `TailCall` runs in CONSTANT STACK. v2 has no TCO — which is why its launchers pass
//      `-Xss512m` and why `mutual-recursion` (isEven(100000)) overflows through the bridge.
//   2. A frame is DATA. `Frame(func, regs)` is an object, not a position in the host call stack,
//      which is what makes a paused computation inspectable and, later, serializable.
//   3. The `kind` field is available to specialize on. v2's primitives are dynamically dispatched
//      and discard it.
//
// Written in the Scala 3 ∩ ScalaScript 2 subset. `Array` is the register file — the reason SSC3-1
// had to fix `new Array[T](n)` before any of this could exist.

enum Value:
  case VUnit
  case VBool(b: Boolean)
  case VInt(n: Long)
  case VFloat(d: Double)
  case VStr(s: String)
  case VData(tag: Int, fields: Array[Value])
  case VClos(f: Int, captured: List[Value])
  case VArr(items: Array[Value])

final case class ExecError(message: String) extends RuntimeException(message)

/** What running a region produced. Structured control flow means these are the only four
  * possibilities, and each is a value rather than an exception — an exception would unwind the host
  * stack, which is exactly what `TailCall` must not do. */
enum Signal:
  case Done
  case Branch(depth: Int)
  case Ret(v: Value)
  case Tail(f: Int, args: List[Value])

object Exec:

  def show(v: Value): String = v match
    case Value.VUnit      => "()"
    case Value.VBool(b)   => if b then "true" else "false"
    case Value.VInt(n)    => n.toString
    case Value.VFloat(d)  => Text.floatText(d)
    case Value.VStr(s)    => s
    case Value.VData(t, f) =>
      if f.isEmpty then "#" + t else "#" + t + "(" + f.toList.map(show).mkString(", ") + ")"
    case Value.VClos(f, _) => "<closure " + f + ">"
    case Value.VArr(xs)    => "Array(" + xs.toList.map(show).mkString(", ") + ")"

  private def truthy(v: Value): Boolean = v match
    case Value.VBool(b) => b
    case Value.VInt(n)  => n != 0L
    case Value.VUnit    => false
    case _              => true

  def run(m: Module): Value =
    val e = Verify.module(m)
    // Invariant I-4: nothing executes unverified, and the executor is not an exception to it just
    // because it happens to be in the same process as the verifier.
    if e.isDefined then throw ExecError("refusing to run invalid IR: " + e.get.render)
    callFunc(m, m.entry, Nil)

  /** The trampoline. A `TailCall` returns here and re-enters the loop with a FRESH argument list
    * and no added host frame, which is the whole of the constant-stack guarantee. */
  def callFunc(m: Module, f0: Int, args0: List[Value]): Value =
    var fi = f0
    var args = args0
    var result: Value = Value.VUnit
    var running = true
    while running do
      val fn = m.funcs(fi)
      if args.length != fn.nparams then
        throw ExecError(fn.name + " takes " + fn.nparams + " argument(s), given " + args.length)
      val regs = new Array[Value](fn.nregs)
      var i = 0
      while i < fn.nregs do
        regs(i) = Value.VUnit
        i = i + 1
      i = 0
      args.foreach { a =>
        regs(i) = a
        i = i + 1
      }
      exec(m, fn.body, regs) match
        case Signal.Ret(v)       => result = v; running = false
        case Signal.Done         => result = Value.VUnit; running = false
        case Signal.Branch(d)    => throw ExecError("a branch left the function body (depth " + d + ")")
        case Signal.Tail(g, as)  => fi = g; args = as
    result

  private def exec(m: Module, body: List[Instr], regs: Array[Value]): Signal =
    var rest = body
    var out: Signal = Signal.Done
    var running = true
    while running && rest.nonEmpty do
      val s = step(m, rest.head, regs)
      s match
        case Signal.Done => rest = rest.tail
        case other       => out = other; running = false
    out

  private def step(m: Module, i: Instr, regs: Array[Value]): Signal = i match
    case Instr.Const(d, k) => regs(d) = constOf(m.consts(k)); Signal.Done
    case Instr.Move(d, a)  => regs(d) = regs(a); Signal.Done
    case Instr.Un(op, _, d, a) =>
      regs(d) = op match
        case UnOp.Neg  => regs(a) match
          case Value.VInt(n)   => Value.VInt(-n)
          case Value.VFloat(x) => Value.VFloat(-x)
          case v               => throw ExecError("neg on " + show(v))
        case UnOp.Not  => Value.VBool(!truthy(regs(a)))
        case UnOp.BNot => regs(a) match
          case Value.VInt(n) => Value.VInt(~n)
          case v             => throw ExecError("bnot on " + show(v))
      Signal.Done
    case Instr.Bin(op, _, d, a, b) => regs(d) = binOp(op, regs(a), regs(b)); Signal.Done

    // Structured control flow. A `Branch` propagates outward, losing one level per region — the
    // same rule the bridge implements with a counter, here as a returned value.
    case Instr.Block(b) =>
      exec(m, b, regs) match
        case Signal.Branch(0) => Signal.Done
        case Signal.Branch(d) => Signal.Branch(d - 1)
        case other            => other
    case Instr.Loop(b) =>
      var out: Signal = Signal.Done
      var looping = true
      while looping do
        exec(m, b, regs) match
          // A branch to a LOOP repeats it; a branch past it keeps unwinding. Falling off the end
          // EXITS, which is WebAssembly's rule and not the one most people expect.
          case Signal.Branch(0) => ()
          case Signal.Branch(d) => out = Signal.Branch(d - 1); looping = false
          case Signal.Done      => looping = false
          case other            => out = other; looping = false
      out
    case Instr.If(c, t, e) =>
      exec(m, if truthy(regs(c)) then t else e, regs) match
        case Signal.Branch(0) => Signal.Done
        case Signal.Branch(d) => Signal.Branch(d - 1)
        case other            => other
    case Instr.Br(d)      => Signal.Branch(d)
    case Instr.BrIf(c, d) => if truthy(regs(c)) then Signal.Branch(d) else Signal.Done

    case Instr.Call(d, f, as) => regs(d) = callFunc(m, f, as.map(r => regs(r))); Signal.Done
    case Instr.CallV(d, c, as) =>
      regs(c) match
        case Value.VClos(f, cap) => regs(d) = callFunc(m, f, cap ++ as.map(r => regs(r))); Signal.Done
        case v                   => throw ExecError("calling a non-function: " + show(v))
    case Instr.MkClos(d, f, caps) => regs(d) = Value.VClos(f, caps.map(r => regs(r))); Signal.Done
    // The point of the whole file: this does NOT recurse. It hands the trampoline a new target.
    case Instr.TailCall(f, as) => Signal.Tail(f, as.map(r => regs(r)))
    case Instr.Ret(a)          => Signal.Ret(regs(a))

    case Instr.MkData(d, t, as) => regs(d) = Value.VData(t, as.map(r => regs(r)).toArray); Signal.Done
    case Instr.Field(d, a, _, idx) =>
      regs(a) match
        case Value.VData(_, fs) => regs(d) = fs(idx); Signal.Done
        case v                  => throw ExecError("field read on " + show(v))
    case Instr.Tag(d, a) =>
      regs(a) match
        case Value.VData(t, _) => regs(d) = Value.VInt(t.toLong); Signal.Done
        case v                 => throw ExecError("tag of " + show(v))
    case Instr.Switch(s, arms, dflt) =>
      val t = regs(s) match
        case Value.VData(tg, _) => tg
        case v                  => throw ExecError("switch on " + show(v))
      val arm = arms.find(a => a.tag == t)
      val chosen = arm.map(a => a.body).getOrElse(dflt)
      exec(m, chosen, regs) match
        case Signal.Branch(0) => Signal.Done
        case Signal.Branch(d) => Signal.Branch(d - 1)
        case other            => other

    case Instr.NewArr(d, n) =>
      val len = regs(n) match
        case Value.VInt(x) => x.toInt
        case v             => throw ExecError("array length " + show(v))
      val a = new Array[Value](len)
      var i = 0
      while i < len do
        a(i) = Value.VInt(0L)
        i = i + 1
      regs(d) = Value.VArr(a)
      Signal.Done
    case Instr.ArrGet(d, a, ix) =>
      (regs(a), regs(ix)) match
        case (Value.VArr(xs), Value.VInt(n)) => regs(d) = xs(n.toInt); Signal.Done
        case (x, _)                          => throw ExecError("array read on " + show(x))
    case Instr.ArrSet(a, ix, v) =>
      (regs(a), regs(ix)) match
        case (Value.VArr(xs), Value.VInt(n)) => xs(n.toInt) = regs(v); Signal.Done
        case (x, _)                          => throw ExecError("array write on " + show(x))
    case Instr.ArrLen(d, a) =>
      regs(a) match
        case Value.VArr(xs) => regs(d) = Value.VInt(xs.length.toLong); Signal.Done
        case v              => throw ExecError("array length of " + show(v))

    case Instr.GlobGet(_, _) | Instr.GlobSet(_, _) =>
      throw ExecError("globals are not implemented in the executor yet")
    case Instr.Perform(_, _, _) | Instr.Handle(_, _, _) | Instr.Resume(_, _, _) =>
      throw ExecError("effects are not implemented in the executor yet")
    case Instr.Invoke(_, nm, _, _) =>
      // Dynamic dispatch needs a method table the executor does not have — v2's runtime is where
      // that lives. Refused BY NAME, so a program that needs it says which method rather than
      // producing a wrong value.
      val name = m.consts(nm) match
        case Lit.LStr(x) => x
        case _           => "?"
      throw ExecError("method dispatch '" + name + "' needs the v2 runtime — use `ssc3 run`")
    case Instr.Prim(d, p, as) => regs(d) = prim(m.prims(p), as.map(r => regs(r))); Signal.Done

  private def constOf(l: Lit): Value = l match
    case Lit.LUnit     => Value.VUnit
    case Lit.LBool(b)  => Value.VBool(b)
    case Lit.LInt(n)   => Value.VInt(n)
    case Lit.LFloat(d) => Value.VFloat(d)
    case Lit.LStr(s)   => Value.VStr(s)
    case Lit.LBig(d)   => Value.VStr(d)
    case Lit.LBytes(h) => Value.VStr(h)

  private def binOp(op: BinOp, a: Value, b: Value): Value = (op, a, b) match
    case (BinOp.Add, Value.VStr(x), y)               => Value.VStr(x + show(y))
    case (BinOp.Add, Value.VInt(x), Value.VInt(y))   => Value.VInt(x + y)
    case (BinOp.Sub, Value.VInt(x), Value.VInt(y))   => Value.VInt(x - y)
    case (BinOp.Mul, Value.VInt(x), Value.VInt(y))   => Value.VInt(x * y)
    case (BinOp.Div, Value.VInt(x), Value.VInt(y))   => Value.VInt(x / y)
    case (BinOp.Rem, Value.VInt(x), Value.VInt(y))   => Value.VInt(x % y)
    case (BinOp.Add, Value.VFloat(x), Value.VFloat(y)) => Value.VFloat(x + y)
    case (BinOp.Sub, Value.VFloat(x), Value.VFloat(y)) => Value.VFloat(x - y)
    case (BinOp.Mul, Value.VFloat(x), Value.VFloat(y)) => Value.VFloat(x * y)
    case (BinOp.Div, Value.VFloat(x), Value.VFloat(y)) => Value.VFloat(x / y)
    case (BinOp.Lt, Value.VInt(x), Value.VInt(y))    => Value.VBool(x < y)
    case (BinOp.Le, Value.VInt(x), Value.VInt(y))    => Value.VBool(x <= y)
    case (BinOp.Gt, Value.VInt(x), Value.VInt(y))    => Value.VBool(x > y)
    case (BinOp.Ge, Value.VInt(x), Value.VInt(y))    => Value.VBool(x >= y)
    case (BinOp.Eq, x, y)                            => Value.VBool(eq(x, y))
    case (BinOp.Ne, x, y)                            => Value.VBool(!eq(x, y))
    case (BinOp.BAnd, Value.VInt(x), Value.VInt(y))  => Value.VInt(x & y)
    case (BinOp.BOr, Value.VInt(x), Value.VInt(y))   => Value.VInt(x | y)
    case (BinOp.BXor, Value.VInt(x), Value.VInt(y))  => Value.VInt(x ^ y)
    case (BinOp.Shl, Value.VInt(x), Value.VInt(y))   => Value.VInt(x << y)
    case (BinOp.Shr, Value.VInt(x), Value.VInt(y))   => Value.VInt(x >> y)
    case (BinOp.UShr, Value.VInt(x), Value.VInt(y))  => Value.VInt(x >>> y)
    case (o, x, y) => throw ExecError(o.toString + " on " + show(x) + " and " + show(y))

  private def eq(a: Value, b: Value): Boolean = (a, b) match
    case (Value.VInt(x), Value.VInt(y))     => x == y
    case (Value.VStr(x), Value.VStr(y))     => x == y
    case (Value.VBool(x), Value.VBool(y))   => x == y
    case (Value.VFloat(x), Value.VFloat(y)) => x == y
    case (Value.VUnit, Value.VUnit)         => true
    case (Value.VData(t1, f1), Value.VData(t2, f2)) =>
      t1 == t2 && f1.length == f2.length && f1.indices.forall(i => eq(f1(i), f2(i)))
    case _ => false

  /** The host boundary, and the only place in the executor that touches the outside world — which
    * is what invariant I-1 asks of the whole kernel. An unknown primitive is refused by NAME. */
  private def prim(name: String, args: List[Value]): Value = name match
    case "io.println" =>
      println(if args.isEmpty then "" else show(args.head))
      Value.VUnit
    case "__autoOutput__" =>
      // Prints only a non-Unit value, exactly as v2 does — the rule the front relies on so that a
      // `println(…)` tail does not print twice.
      if args.nonEmpty && args.head != Value.VUnit then println(show(args.head))
      Value.VUnit
    case "__throw__" =>
      throw ExecError(if args.isEmpty then "throw" else show(args.head))
    case other => throw ExecError("unknown primitive '" + other + "'")
