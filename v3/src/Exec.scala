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

  // Module globals. A module-scope `var` is genuinely mutable state, so it lives in one array for
  // the run rather than being threaded through every call — the same decision the register frame
  // makes, one level up.
  private var globals: Array[Value] = new Array[Value](0)

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
    globals = new Array[Value](m.globals.length)
    var i = 0
    while i < m.globals.length do
      // `unit`, not a zero: a cell read before its initialiser runs is a real possibility, and
      // `unit` is what the other lane starts it as. Two lanes, one starting value.
      globals(i) = Value.VUnit
      i = i + 1
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
        // TOTAL, matching the bridge. Not a defensive default: a nested pattern tests the tag of a
        // FIELD, and a field is routinely not Data. `Right(42)` against `case Right(ByteRead(v, _))`
        // is a non-match in Scala, and throwing here made it a crash on one lane only.
        case _                 => regs(d) = Value.VInt(-1L); Signal.Done
    case Instr.Switch(s, arms, dflt) =>
      // A scrutinee that is not `Data` takes the DEFAULT rather than failing. That is v2's `match`
      // semantics and it is what makes a name that is both a field and a method resolvable at run
      // time: `r.head` on a record takes an arm, `xs.head` on a list falls through to dispatch.
      val chosen = regs(s) match
        case Value.VData(tg, _) => arms.find(a => a.tag == tg).map(a => a.body).getOrElse(dflt)
        case _                  => dflt
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
        case (Value.VArr(xs), Value.VInt(n)) =>
          if n < 0 || n >= xs.length then
            throw ExecError("index " + n + " is outside an array of " + xs.length)
          regs(d) = xs(n.toInt); Signal.Done
        case (x, _)                          => throw ExecError("array read on " + show(x))
    case Instr.ArrSet(a, ix, v) =>
      (regs(a), regs(ix)) match
        case (Value.VArr(xs), Value.VInt(n)) =>
          if n < 0 || n >= xs.length then
            throw ExecError("index " + n + " is outside an array of " + xs.length)
          xs(n.toInt) = regs(v); Signal.Done
        case (x, _)                          => throw ExecError("array write on " + show(x))
    case Instr.ArrLen(d, a) =>
      regs(a) match
        case Value.VArr(xs) => regs(d) = Value.VInt(xs.length.toLong); Signal.Done
        case v              => throw ExecError("array length of " + show(v))

    case Instr.GlobGet(d, g) => regs(d) = globals(g); Signal.Done
    case Instr.GlobSet(g, a) => globals(g) = regs(a); Signal.Done
    case Instr.Perform(_, _, _) | Instr.Handle(_, _, _) | Instr.Resume(_, _, _) =>
      throw ExecError("effects are not implemented in the executor yet")
    // The executor's own guard. `ExecError` is the only thing thrown by this lane, so catching it
    // is catching exactly what an SSC3 program can raise — a bare `catch Throwable` would also
    // swallow a StackOverflowError and report it as a caught user exception.
    case Instr.Try(d, b, x, h) =>
      try exec(m, b, regs)
      catch
        case e: ExecError =>
          regs(x) = Value.VStr(e.message)
          exec(m, h, regs)
    case Instr.Invoke(d, nm, r, as) =>
      val name = m.consts(nm) match
        case Lit.LStr(x) => x
        case _           => throw ExecError("an invoke whose name const is not a string")
      regs(d) = invoke(m, name, regs(r), as.map(x => regs(x)))
      Signal.Done
    case Instr.Prim(d, p, as) => regs(d) = prim(m.prims(p), as.map(r => regs(r))); Signal.Done

  // ── the method table ────────────────────────────────────────────────────────
  //
  // Enough of a library for the front's own programs to run on THIS lane as well as through the
  // bridge, which is what puts them under the differential gate. Every method here is one the
  // corpus or a fixture actually calls — the table grows by measurement, not by anticipation.
  //
  // A list is `Cons(head, tail)` / `Nil` as `VData`, and the tags are looked up BY NAME in the
  // module's type table rather than assumed, because they are per-module indices.

  private def tagOf(m: Module, name: String): Int =
    val i = m.types.indexWhere(t => t.name == name)
    if i < 0 then -1 else i

  private def listOut(m: Module, v: Value): List[Value] =
    val consT = tagOf(m, "Cons")
    var out: List[Value] = Nil
    var cur = v
    var go = true
    while go do
      cur match
        case Value.VData(t, f) if t == consT && f.length == 2 =>
          out = f(0) :: out
          cur = f(1)
        case _ => go = false
    out.reverse

  private def listIn(m: Module, xs: List[Value]): Value =
    val consT = tagOf(m, "Cons")
    val nilT = tagOf(m, "Nil")
    if consT < 0 || nilT < 0 then throw ExecError("this module declares no list constructors")
    var acc: Value = Value.VData(nilT, new Array[Value](0))
    xs.reverse.foreach { x =>
      val f = new Array[Value](2)
      f(0) = x
      f(1) = acc
      acc = Value.VData(consT, f)
    }
    acc

  private def isList(m: Module, v: Value): Boolean = v match
    case Value.VData(t, _) => t == tagOf(m, "Cons") || t == tagOf(m, "Nil")
    case _                 => false

  private def apply1(m: Module, f: Value, x: Value): Value = f match
    case Value.VClos(g, cap) => callFunc(m, g, cap :+ x)
    case v                   => throw ExecError("not a function: " + show(v))

  private def invoke(m: Module, name: String, recv: Value, args: List[Value]): Value =
    (recv, name) match
      case (Value.VStr(s), "length")      => Value.VInt(s.length.toLong)
      case (Value.VStr(s), "toUpperCase") => Value.VStr(s.toUpperCase)
      case (Value.VStr(s), "toLowerCase") => Value.VStr(s.toLowerCase)
      case (Value.VStr(s), "isEmpty")     => Value.VBool(s.isEmpty)
      case (Value.VStr(s), "trim")        => Value.VStr(s.trim)
      // NO string `++` here, deliberately. v2's `__method__` has no dispatch for it — `"ab" ++ "cd"`
      // dies with `no dispatch for .++ on "ab"` through the bridge — so implementing it on this lane
      // alone would make the same program behave differently depending on which backend ran it.
      // Two lanes disagreeing is the thing the differential exists to catch, and adding a
      // convenience that only one of them has is manufacturing exactly that. `+` concatenates
      // strings on both.
      case (Value.VStr(s), "split") =>
        args.head match
          case Value.VStr(sep) => listIn(m, s.split(java.util.regex.Pattern.quote(sep), -1).toList.map(x => Value.VStr(x)))
          case v               => throw ExecError("split by " + show(v))
      case (Value.VArr(xs), "length") => Value.VInt(xs.length.toLong)
      case (Value.VArr(xs), "size")   => Value.VInt(xs.length.toLong)
      case (_, "toString")            => Value.VStr(show(recv))
      case _ =>
        if isList(m, recv) then
          val xs = listOut(m, recv)
          name match
            case "size" | "length" => Value.VInt(xs.length.toLong)
            case "isEmpty"         => Value.VBool(xs.isEmpty)
            case "nonEmpty"        => Value.VBool(xs.nonEmpty)
            case "head"            => if xs.isEmpty then throw ExecError("head of an empty list") else xs.head
            case "tail"            => listIn(m, if xs.isEmpty then Nil else xs.tail)
            case "sum" =>
              var acc = 0L
              xs.foreach { case Value.VInt(n) => acc = acc + n; case v => throw ExecError("sum over " + show(v)) }
              Value.VInt(acc)
            case "map"     => listIn(m, xs.map(x => apply1(m, args.head, x)))
            case "filter"  => listIn(m, xs.filter(x => truthy(apply1(m, args.head, x))))
            case "reverse" => listIn(m, xs.reverse)
            case "++"      => listIn(m, xs ++ listOut(m, args.head))
            case "mkString" =>
              val sep = args.headOption match
                case Some(Value.VStr(x)) => x
                case _                   => ""
              Value.VStr(xs.map(show).mkString(sep))
            case other => throw ExecError("list method '" + other + "' is not implemented on this lane")
        else
          recv match
            // `Some`/`None` are ordinary constructors here, so their methods are too.
            case Value.VData(t, f) if t == tagOf(m, "Some") && name == "get" => f(0)
            case Value.VData(t, _) if t == tagOf(m, "Some") && name == "isEmpty" => Value.VBool(false)
            case Value.VData(t, _) if t == tagOf(m, "None") && name == "isEmpty" => Value.VBool(true)
            case Value.VData(t, f) if t == tagOf(m, "Some") && name == "getOrElse" => f(0)
            case Value.VData(t, _) if t == tagOf(m, "None") && name == "getOrElse" => args.head
            // Refused BY NAME, with the receiver shown: a program that needs a method this lane
            // lacks is told which one, rather than getting a wrong value.
            case _ => throw ExecError("method '" + name + "' on " + show(recv) + " is not implemented on this lane — `ssc3 run` uses the v2 runtime")

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
    // Converted AT THE SOURCE rather than caught wholesale. A blanket `catch RuntimeException`
    // around every instruction would also swallow a genuine executor bug and hand it to the
    // program's `catch` as if the program had caused it. Each of these has a message we wrote.
    case (BinOp.Div, Value.VInt(x), Value.VInt(y)) =>
      if y == 0L then throw ExecError("/ by zero") else Value.VInt(x / y)
    case (BinOp.Rem, Value.VInt(x), Value.VInt(y)) =>
      if y == 0L then throw ExecError("% by zero") else Value.VInt(x % y)
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
