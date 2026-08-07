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
  /** A char is an INTEGER that prints as a character — v2's model (`CharV extends IntV`), kept so
    * the two lanes agree on `'x' + 1` (121) as well as on `println('x')` (x). */
  case VChar(c: Char)
  case VData(tag: Int, fields: Array[Value])
  case VClos(f: Int, captured: List[Value])
  case VArr(items: Array[Value])
  /** A BUILT-IN method applied to only some of its arguments — `xs.foldLeft(0)` waiting for its
    * function. v3 has no partial application in general; a `VClos` needs a lifted function index
    * and a built-in has none, so this is the shape that lets a curried BUILT-IN call work at all.
    * `CallV` on one of these finishes the invoke. */
  case VPartial(recv: Value, name: String, got: List[Value])
  /** A `Map`. INSERTION-ORDERED, because that is what the reference lane prints:
    * `Map(a -> 1, b -> 2)` in the order written, not sorted and not hashed. A `Vector` of pairs
    * rather than a hash map — v3's maps are small and written by hand, and preserving the order is
    * worth more here than a lookup that never gets long enough to matter. */
  case VMap(entries: scala.collection.mutable.ArrayBuffer[(Value, Value)])
  /** A `Set`, INSERTION-ORDERED and de-duplicated on construction — `Set(1, 2, 2, 3)` prints
    * `Set(1, 2, 3)` on the reference lane, in the order written. */
  case VSet(elems: List[Value])

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
    case Value.VFloat(d)  => showFloat(d)
    case Value.VChar(c)   => c.toString
    case Value.VStr(s)    => s
    case Value.VData(t, f) =>
      if f.isEmpty then "#" + t else "#" + t + "(" + f.toList.map(show).mkString(", ") + ")"
    case Value.VSet(xs)    => "Set(" + xs.length + " elements)"
    case Value.VMap(es)    => "Map(" + es.length + " entries)"
    case Value.VClos(f, _) => "<closure " + f + ">"
    case Value.VPartial(_, nm, _) => "<partial " + nm + ">"
    case Value.VArr(xs)    => "Array(" + xs.toList.map(show).mkString(", ") + ")"

  /** How the LANGUAGE prints a Double — deliberately NOT `Text.floatText`, which is the canonical
    * `.ssir` form. Sharing one helper between an IR serialisation and a program's output is the
    * duplicated/shared-helper trap this repository has paid for before: the two have different
    * contracts and only one of them may ever change for a formatting reason.
    *
    * The rule is the REFERENCE LANE's, measured rather than invented — `ssc3 run` goes through v2
    * and the corpus expectations are the ones every other lane is held to:
    *
    *     3.0 -> 3      -3.0 -> -3      -0.0 -> 0      123456789.0 -> 123456789
    *     2.5 -> 2.5    0.1+0.2 -> 0.30000000000000004      1/0.0 -> inf      0/0.0 -> nan
    *
    * Real Scala prints `3.0` here, so this is v1-parity behaviour rather than Scala behaviour. That
    * is a decision the reference lane already made; v3's job is that its TWO lanes agree, and if the
    * repository ever changes it, v3 inherits the change rather than forking it.
    *
    * The whole-number test is `d == d.toLong.toDouble` — pure arithmetic, no host library, so it
    * holds in the portable subset. It is also self-limiting: past 2^63 `toLong` saturates, the
    * round trip fails, and the value falls through to the general form instead of printing a lie. */
  private def showFloat(d: Double): String =
    if d.isNaN then "nan"
    else if d.isInfinite then (if d > 0.0 then "inf" else "-inf")
    else if d == d.toLong.toDouble then d.toLong.toString
    else d.toString

  /** How a value reaches the USER — deliberately separate from `show`, which names raw tags and is
    * for the executor's own diagnostics.
    *
    * `show` alone printed `#0(1, 2)` where the v2 lane prints `P(1, 2)`, and a list as its nested
    * Cons cells rather than `List(1, 2)`. That is EVERY program that prints a constructed value,
    * and the differential gate could not see it because no fixture printed one. The type names were
    * there all along, in the module `show` did not have.
    *
    * The shapes are the reference lane's, measured: `P(1, 2)`, `Some(3)`, `None` (no parens for a
    * nullary constructor), `List(1, 2)`. */
  def showV(m: Module, v: Value): String = v match
    case Value.VData(t, f) =>
      if isList(m, v) then "List(" + listOut(m, v).map(x => showV(m, x)).mkString(", ") + ")"
      else if t == tagOf(m, "Nil") then "List()"
      else
        val nm = if t >= 0 && t < m.types.length then m.types(t).name else "#" + t
        // A tuple prints as `(1, a)`, NOT `Tuple2(1, a)` — measured on the v1 interpreter, which is
        // the language's reference for this. The synthetic class is an implementation detail and
        // must not reach the output.
        if nm.startsWith("Tuple") && f.length >= 2 then
          "(" + f.toList.map(x => showV(m, x)).mkString(", ") + ")"
        else if f.isEmpty then nm
        else nm + "(" + f.toList.map(x => showV(m, x)).mkString(", ") + ")"
    // `<foreign>`, because that is what BOTH reference lanes print — an array is a host object to
    // v1 and v2, and they say so. Printing the contents would read better and would make the two
    // v3 lanes disagree on every program that prints an array, which invariant I-3 forbids. The
    // executor's own diagnostics still use `show`, which does print the contents.
    // `Map(a -> 1, b -> 2)`, in INSERTION order and with the arrow — measured off v1, which is what
    // the corpus expectations encode.
    case Value.VSet(xs) => "Set(" + xs.map(x => showV(m, x)).mkString(", ") + ")"
    case Value.VMap(es) =>
      "Map(" + es.toList.map((k, v) => showV(m, k) + " -> " + showV(m, v)).mkString(", ") + ")"
    case Value.VArr(_) => "<foreign>"
    case Value.VPartial(_, nm, _) => "<partial " + nm + ">"
    case other          => show(other)

  private def a0(as: List[Int]): Int = as.head

  private def regs0(m: Module, xs: List[Value], idx: Value): Value = idx match
    case Value.VInt(i) =>
      if i < 0 || i >= xs.length then
        throw ExecError("index " + i + " out of bounds for a list of " + xs.length)
      xs(i.toInt)
    case v => throw ExecError("list index " + show(v))

  private def intArg(v: Value, what: String): Int = v match
    case Value.VInt(n) => n.toInt
    case other         => throw ExecError(what + " expects an integer, got " + show(other))

  /** `distinct` by VALUE equality, not by reference — `eq` is the same comparison `==` uses, so a
    * list of equal data values collapses the way a reader expects. */
  private def dedup(xs: List[Value]): List[Value] =
    var out: List[Value] = Nil
    xs.foreach { x => if !out.exists(y => eq(y, x)) then out = out :+ x }
    out

  private def apply2(m: Module, f: Value, a: Value, b: Value): Value = f match
    case Value.VClos(fi, cap) => callFunc(m, fi, cap ++ List(a, b))
    case other                => throw ExecError("not a two-argument function: " + show(other))

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
    case Instr.Bin(op, _, d, a, b) => regs(d) = binOp(m, op, regs(a), regs(b)); Signal.Done

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
        case Value.VPartial(recv, nm, got) =>
          regs(d) = invoke(m, nm, recv, got ++ as.map(r => regs(r))); Signal.Done
        // `a(i)` on an ARRAY is an index, not a call. That is not a v3 invention: the bridge has
        // relied on it from the start — a frame read is `(app frame idx)` — so this is the executor
        // catching up with the semantics both lanes were already built on.
        // `m(k)` on a MAP is a lookup that yields the VALUE, not an Option — v1's `m("a")` is `1`.
        // A missing key is an error rather than a unit, because a silent unit is a wrong answer
        // that flows on and surfaces somewhere else.
        // `xs(i)` on a LIST. The corpus case that found it says so in its own description: `xs(i)`
        // and `xs.apply(i)` are the same operation and must agree on every lane.
        case Value.VData(_, _) if as.length == 1 && isList(m, regs(c)) =>
          val xs = listOut(m, regs(c))
          regs(a0(as)) match
            case Value.VInt(i) =>
              if i < 0 || i >= xs.length then
                throw ExecError("index " + i + " out of bounds for a list of " + xs.length)
              regs(d) = xs(i.toInt); Signal.Done
            case v => throw ExecError("list index " + show(v))
        case Value.VMap(es) if as.length == 1 =>
          val k = regs(a0(as))
          es.find((kk, _) => eq(kk, k)) match
            case Some((_, v)) => regs(d) = v; Signal.Done
            case None         => throw ExecError("key not found: " + show(k))
        case Value.VArr(xs) if as.length == 1 =>
          regs(a0(as)) match
            case Value.VInt(i) =>
              if i < 0 || i >= xs.length then
                throw ExecError("array index " + i + " out of bounds for length " + xs.length)
              regs(d) = xs(i.toInt); Signal.Done
            case v => throw ExecError("array index " + show(v))
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
    case Instr.Prim(d, p, as) => regs(d) = prim(m, m.prims(p), as.map(r => regs(r))); Signal.Done

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

  /** A total order over values, for `sorted`/`sortBy`/`min`/`max`. Numbers compare numerically,
    * strings lexicographically, and everything else by its printed form — which is what keeps the
    * result DEFINED rather than dependent on iteration order. The reference lane has its own
    * `valueOrdering`; the differential is what says whether these two agree, so any disagreement
    * shows up as a failing probe rather than as an argument. */
  private def cmp(a: Value, b: Value): Int = (a, b) match
    case (Value.VInt(x), Value.VInt(y))     => x.compareTo(y)
    case (Value.VFloat(x), Value.VFloat(y)) => x.compareTo(y)
    case (Value.VInt(x), Value.VFloat(y))   => x.toDouble.compareTo(y)
    case (Value.VFloat(x), Value.VInt(y))   => x.compareTo(y.toDouble)
    case (Value.VChar(x), Value.VChar(y))   => x.compareTo(y)
    case (Value.VStr(x), Value.VStr(y))     => x.compareTo(y)
    case (Value.VBool(x), Value.VBool(y))   => x.compareTo(y)
    case (x, y)                             => show(x).compareTo(show(y))

  private def someOf(m: Module, v: Value): Value =
    val t = tagOf(m, "Some")
    if t < 0 then throw ExecError("this module declares no `Some`")
    val f = new Array[Value](1)
    f(0) = v
    Value.VData(t, f)

  private def noneOf(m: Module): Value =
    val t = tagOf(m, "None")
    if t < 0 then throw ExecError("this module declares no `None`")
    Value.VData(t, new Array[Value](0))

  private def tup2(m: Module, a: Value, b: Value): Value =
    val t = tagOf(m, "Tuple2")
    if t < 0 then throw ExecError("this module declares no `Tuple2`")
    val f = new Array[Value](2)
    f(0) = a
    f(1) = b
    Value.VData(t, f)

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
      // A LANE DIVERGENCE, measured 2026-08-05: all four ran on the bridge and refused on the
      // executor. Not silence — the executor names the method — but a program that works on one
      // lane and not the other is exactly what invariant I-3 exists to prevent.
      case (Value.VStr(s), "substring") =>
        // The bounds are checked HERE rather than left to the host. A raw
        // `StringIndexOutOfBoundsException` is a CRASH to the corpus report — the bucket that says
        // "v3 neither ran it nor refused it cleanly" — while a named error is a refusal a reader
        // can act on. The reference lane throws too, so this is about the QUALITY of the failure.
        args match
          case Value.VInt(a) :: Nil =>
            if a < 0 || a > s.length then
              throw ExecError("substring(" + a + ") of a string of length " + s.length)
            Value.VStr(s.substring(a.toInt))
          case Value.VInt(a) :: Value.VInt(b) :: Nil =>
            if a < 0 || b > s.length || a > b then
              throw ExecError("substring(" + a + ", " + b + ") of a string of length " + s.length)
            Value.VStr(s.substring(a.toInt, b.toInt))
          case _ => throw ExecError("substring takes one or two integers")
      // ONE arm, all argument shapes. The first version split it in two and put the string case
      // first, so a CHARACTER argument hit the general throw before the arm meant for it was ever
      // reached — the arms were ordered by when they were written rather than by specificity.
      // TWO arguments: the second is a START OFFSET. Ignoring it did not fail — it returned the
      // first occurrence from zero, so a scan loop got an index BEHIND its own cursor and the
      // `substring(from, at)` that followed was backwards. The crash was three calls downstream of
      // the cause, which is what an ignored argument buys you.
      case (Value.VStr(s), "indexOf") =>
        val from = args.tail.headOption match
          case Some(Value.VInt(n)) => n.toInt
          case _                   => 0
        args.head match
          case Value.VStr(x)  => Value.VInt(s.indexOf(x, from).toLong)
          case Value.VChar(c) => Value.VInt(s.indexOf(c.toInt, from).toLong)
          case Value.VInt(n)  => Value.VInt(s.indexOf(n.toInt, from).toLong)
          case v              => throw ExecError("indexOf " + show(v))
      case (Value.VStr(s), "replace") =>
        (args.head, args.tail.head) match
          case (Value.VStr(a), Value.VStr(b)) => Value.VStr(s.replace(a, b))
          case _                              => throw ExecError("replace takes two strings")
      case (Value.VStr(s), "contains") =>
        args.head match
          case Value.VStr(x) => Value.VBool(s.contains(x))
          case v             => throw ExecError("contains " + show(v))
      case (Value.VStr(s), "startsWith") =>
        args.head match
          case Value.VStr(x) => Value.VBool(s.startsWith(x))
          case v             => throw ExecError("startsWith " + show(v))
      case (Value.VStr(s), "endsWith") =>
        args.head match
          case Value.VStr(x) => Value.VBool(s.endsWith(x))
          case v             => throw ExecError("endsWith " + show(v))
      case (Value.VStr(s), "nonEmpty") => Value.VBool(s.nonEmpty)
      case (Value.VStr(s), "reverse")  => Value.VStr(s.reverse)
      // A full-string regex match. Delegated to the HOST rather than given a matcher of its own:
      // the language has `matches` on every lane, so the portable subset can reach it, and a
      // hand-written engine would be a second regex semantics to keep in step with the reference.
      // This is I-1's boundary read correctly — the ban is on host CHARACTER CLASSIFICATION
      // deciding the language's syntax, not on using the host to implement a library method.
      case (Value.VStr(s), "matches") =>
        args.head match
          case Value.VStr(re) => Value.VBool(s.matches(re))
          case v              => throw ExecError("matches " + show(v))
      case (Value.VStr(s), "take")     => Value.VStr(s.take(intArg(args.head, "take")))
      case (Value.VStr(s), "drop")     => Value.VStr(s.drop(intArg(args.head, "drop")))
      case (Value.VStr(s), "toList")   => listIn(m, s.toList.map(c => Value.VChar(c)))
      case (Value.VStr(s), "lastIndexOf") =>
        val before = args.tail.headOption match
          case Some(Value.VInt(n)) => n.toInt
          case _                   => s.length
        args.head match
          case Value.VStr(x)  => Value.VInt(s.lastIndexOf(x, before).toLong)
          // A CHARACTER argument, which in this language is an integer that prints differently —
          // so both spellings arrive here and both must work.
          case Value.VChar(c) => Value.VInt(s.lastIndexOf(c.toInt, before).toLong)
          case Value.VInt(n)  => Value.VInt(s.lastIndexOf(n.toInt, before).toLong)
          case v              => throw ExecError("lastIndexOf " + show(v))
      case (Value.VStr(s), "count") =>
        Value.VInt(s.count(c => truthy(apply1(m, args.head, Value.VInt(c.toLong)))).toLong)
      case (Value.VInt(n), "abs")      => Value.VInt(if n < 0 then -n else n)
      case (Value.VFloat(d), "abs")    => Value.VFloat(if d < 0.0 then -d else d)
      // `charAt` returns an INT on the reference lane — "abc".charAt(1) is 98, not 'b'. Char
      // LITERALS are chars there and charAt is not; matching that is the point.
      case (Value.VStr(s), "charAt") =>
        args.head match
          case Value.VInt(i) =>
            if i < 0 || i >= s.length then throw ExecError("charAt " + i + " of a string of length " + s.length)
            Value.VInt(s.charAt(i.toInt).toLong)
          case v => throw ExecError("charAt " + show(v))
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
      case (Value.VSet(xs), "size")     => Value.VInt(xs.length.toLong)
      case (Value.VSet(xs), "isEmpty")  => Value.VBool(xs.isEmpty)
      case (Value.VSet(xs), "nonEmpty") => Value.VBool(xs.nonEmpty)
      case (Value.VSet(xs), "contains") => Value.VBool(xs.exists(y => eq(y, args.head)))
      case (Value.VSet(xs), "toList")   => listIn(m, xs)
      case (Value.VSet(xs), "sum") =>
        var acc = 0L
        xs.foreach { case Value.VInt(n) => acc = acc + n; case v => throw ExecError("sum over " + show(v)) }
        Value.VInt(acc)
      case (Value.VSet(xs), "mkString") =>
        val (pre, sep, post) = args match
          case Value.VStr(a) :: Value.VStr(b) :: Value.VStr(c) :: Nil => (a, b, c)
          case Value.VStr(a) :: Nil                                   => ("", a, "")
          case Nil                                                    => ("", "", "")
          case _ => throw ExecError("mkString takes no arguments, one, or three strings")
        Value.VStr(pre + xs.map(x => showV(m, x)).mkString(sep) + post)
      case (Value.VSet(xs), "subsetOf") =>
        args.head match
          case Value.VSet(ys) => Value.VBool(xs.forall(x => ys.exists(y => eq(y, x))))
          case v              => throw ExecError("subsetOf " + show(v))
      case (Value.VSet(xs), "++") =>
        args.head match
          case Value.VSet(ys) =>
            var out = xs
            ys.foreach { y => if !out.exists(z => eq(z, y)) then out = out :+ y }
            Value.VSet(out)
          case v => throw ExecError("++ with " + show(v))
      case (Value.VSet(xs), "map") =>
        var out: List[Value] = Nil
        xs.foreach { x =>
          val y = apply1(m, args.head, x)
          if !out.exists(z => eq(z, y)) then out = out :+ y
        }
        Value.VSet(out)
      case (Value.VSet(xs), "filter") =>
        Value.VSet(xs.filter(x => truthy(apply1(m, args.head, x))))
      case (Value.VSet(xs), "exists") =>
        Value.VBool(xs.exists(x => truthy(apply1(m, args.head, x))))
      case (Value.VSet(xs), "foreach") =>
        xs.foreach(x => apply1(m, args.head, x)); Value.VUnit
      case (Value.VSet(xs), "union") =>
        args.head match
          case Value.VSet(ys) =>
            var out = xs
            ys.foreach { y => if !out.exists(z => eq(z, y)) then out = out :+ y }
            Value.VSet(out)
          case v => throw ExecError("union with " + show(v))
      case (Value.VSet(xs), "intersect") =>
        args.head match
          case Value.VSet(ys) => Value.VSet(xs.filter(x => ys.exists(y => eq(y, x))))
          case v              => throw ExecError("intersect with " + show(v))
      case (Value.VSet(xs), "diff") =>
        args.head match
          case Value.VSet(ys) => Value.VSet(xs.filter(x => !ys.exists(y => eq(y, x))))
          case v              => throw ExecError("diff with " + show(v))
      case (Value.VMap(es), "size")     => Value.VInt(es.length.toLong)
      case (Value.VMap(es), "isEmpty")  => Value.VBool(es.isEmpty)
      case (Value.VMap(es), "nonEmpty") => Value.VBool(es.nonEmpty)
      case (Value.VMap(es), "contains") => Value.VBool(es.exists((k, _) => eq(k, args.head)))
      case (Value.VMap(es), "keys")     => listIn(m, es.toList.map(_._1))
      case (Value.VMap(es), "values")   => listIn(m, es.toList.map(_._2))
      case (Value.VMap(es), "get") =>
        es.find((k, _) => eq(k, args.head)) match
          case Some((_, v)) => someOf(m, v)
          case None         => noneOf(m)
      case (Value.VMap(es), "getOrElse") =>
        es.find((k, _) => eq(k, args.head)).map(_._2).getOrElse(args.tail.head)
      case (Value.VArr(xs), "length") => Value.VInt(xs.length.toLong)
      case (Value.VArr(xs), "size")   => Value.VInt(xs.length.toLong)
      case (_, "toString")            => Value.VStr(showV(m, recv))
      // A cast is the IDENTITY here. Types are erased at Tier 0 — `20-core-language.md` §2 — so
      // `asInstanceOf` has nothing to check and nothing to change; refusing it would refuse a
      // no-op. Any value, which is why it sits before the per-type arms.
      case (_, "asInstanceOf")        => recv
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
            case "flatMap" => listIn(m, xs.flatMap(x => listOut(m, apply1(m, args.head, x))))
            // Every one of these ran on the BRIDGE and refused here. Found by probing the two
            // lanes with one program per method rather than by reading either implementation:
            // 23 of 32 probes were bridge-only, which no amount of code reading had suggested.
            case "exists"  => Value.VBool(xs.exists(x => truthy(apply1(m, args.head, x))))
            case "forall"  => Value.VBool(xs.forall(x => truthy(apply1(m, args.head, x))))
            case "count"   => Value.VInt(xs.count(x => truthy(apply1(m, args.head, x))).toLong)
            case "find" =>
              xs.find(x => truthy(apply1(m, args.head, x))) match
                case Some(v) => someOf(m, v)
                case None    => noneOf(m)
            case "sorted"  => listIn(m, xs.sortWith((a, b) => cmp(a, b) < 0))
            case "sortBy" =>
              listIn(m, xs.sortWith((a, b) => cmp(apply1(m, args.head, a), apply1(m, args.head, b)) < 0))
            case "zip" =>
              listIn(m, xs.zip(listOut(m, args.head)).map((a, b) => tup2(m, a, b)))
            case "take"     => listIn(m, xs.take(intArg(args.head, "take")))
            case "drop"     => listIn(m, xs.drop(intArg(args.head, "drop")))
            case "distinct" => listIn(m, dedup(xs))
            case "contains" => Value.VBool(xs.exists(x => eq(x, args.head)))
            case "indexOf"  => Value.VInt(xs.indexWhere(x => eq(x, args.head)).toLong)
            case "last" =>
              if xs.isEmpty then throw ExecError("last of an empty list") else xs.last
            case "init" =>
              if xs.isEmpty then throw ExecError("init of an empty list") else listIn(m, xs.init)
            case "min" =>
              if xs.isEmpty then throw ExecError("min of an empty list")
              else xs.reduce((a, b) => if cmp(a, b) <= 0 then a else b)
            case "max" =>
              if xs.isEmpty then throw ExecError("max of an empty list")
              else xs.reduce((a, b) => if cmp(a, b) >= 0 then a else b)
            // `xs.foldLeft(z)(f)` — two argument lists, so the first invoke gets one argument and
            // must return something the second can apply. Revealed the moment curried application
            // became parseable: the construct existed on the bridge and the executor had no way to
            // express it.
            // Found by running the CORPUS through this lane rather than a hand-picked probe set.
            // The probes were the top ~30 method names by frequency and they all passed; the corpus
            // reaches the tail, and the tail is where the lane was still behind.
            case "toList"   => recv
            case "toSet" =>
              var out: List[Value] = Nil
              xs.foreach { x => if !out.exists(y => eq(y, x)) then out = out :+ x }
              Value.VSet(out)
            case "apply"    => 
              regs0(m, xs, args.head)
            case "filterNot" => listIn(m, xs.filterNot(x => truthy(apply1(m, args.head, x))))
            case "takeWhile" => listIn(m, xs.takeWhile(x => truthy(apply1(m, args.head, x))))
            case "zipWithIndex" =>
              listIn(m, xs.zipWithIndex.map((x, i) => tup2(m, x, Value.VInt(i.toLong))))
            case "dropWhile" => listIn(m, xs.dropWhile(x => truthy(apply1(m, args.head, x))))
            case "headOption" =>
              if xs.isEmpty then noneOf(m) else someOf(m, xs.head)
            case "lastOption" =>
              if xs.isEmpty then noneOf(m) else someOf(m, xs.last)
            case "slice"    => listIn(m, xs.slice(intArg(args.head, "slice"), intArg(args.tail.head, "slice")))
            case "scanLeft" if args.length == 1 => Value.VPartial(recv, "scanLeft", args)
            case "scanLeft" =>
              listIn(m, xs.scanLeft(args.head)((acc, x) => apply2(m, args.tail.head, acc, x)))
            case "foldLeft" if args.length == 1  => Value.VPartial(recv, "foldLeft", args)
            case "foldRight" if args.length == 1 => Value.VPartial(recv, "foldRight", args)
            case "foldLeft" =>
              xs.foldLeft(args.head)((acc, x) => apply2(m, args.tail.head, acc, x))
            case "foldRight" =>
              xs.foldRight(args.head)((x, acc) => apply2(m, args.tail.head, x, acc))
            case "reduce" =>
              if xs.isEmpty then throw ExecError("reduce of an empty list")
              else xs.reduce((a, b) => apply2(m, args.head, a, b))
            case "reverse" => listIn(m, xs.reverse)
            // A LANE DIVERGENCE, not a missing feature: the bridge ran `foreach` all along and the
            // executor did not. Invisible because no fixture used it.
            case "foreach" =>
              xs.foreach(x => apply1(m, args.head, x))
              Value.VUnit
            case "++"      => listIn(m, xs ++ listOut(m, args.head))
            case ":+"      => listIn(m, xs :+ args.head)
            case "+:"      => listIn(m, args.head :: xs)
            case "mkString" =>
              // THREE forms, not one: `mkString`, `mkString(sep)` and `mkString(start, sep, end)`.
              // Only the middle one was implemented, and the three-argument call silently used its
              // FIRST argument as the separator — `mkString("[", ", ", "]")` printed
              // `5[3[8[1[9[2`. A wrong answer, not a refusal, because the arity was never checked.
              val (pre, sep, post) = args match
                case Value.VStr(a) :: Value.VStr(b) :: Value.VStr(c) :: Nil => (a, b, c)
                case Value.VStr(a) :: Nil                                   => ("", a, "")
                case Nil                                                    => ("", "", "")
                case other => throw ExecError("mkString takes no arguments, one, or three strings")
              // `showV`, not `show`: this is OUTPUT, and `show` names raw tags. A zipped list
              // printed as #4(1, a) instead of (1, a) — the last of 32 parity probes to fall, and
              // the only one whose cause was in the PRINTER rather than in a missing method.
              Value.VStr(pre + xs.map(x => showV(m, x)).mkString(sep) + post)
            case other => throw ExecError("list method '" + other + "' is not implemented on this lane")
        else
          recv match
            // `Some`/`None` are ordinary constructors here, so their methods are too.
            case Value.VData(t, f) if t == tagOf(m, "Some") && name == "get" => f(0)
            case Value.VData(t, _) if t == tagOf(m, "Some") && name == "isEmpty" => Value.VBool(false)
            case Value.VData(t, _) if t == tagOf(m, "None") && name == "isEmpty" => Value.VBool(true)
            case Value.VData(t, f) if t == tagOf(m, "Some") && name == "getOrElse" => f(0)
            case Value.VData(t, _) if t == tagOf(m, "Some") && name == "isDefined" => Value.VBool(true)
            case Value.VData(t, _) if t == tagOf(m, "None") && name == "isDefined" => Value.VBool(false)
            case Value.VData(t, f) if t == tagOf(m, "Some") && name == "map" =>
              someOf(m, apply1(m, args.head, f(0)))
            case Value.VData(t, _) if t == tagOf(m, "None") && name == "map" => noneOf(m)
            case Value.VData(t, f) if t == tagOf(m, "Some") && name == "foreach" =>
              apply1(m, args.head, f(0)); Value.VUnit
            case Value.VData(t, _) if t == tagOf(m, "None") && name == "foreach" => Value.VUnit
            case Value.VData(t, f) if t == tagOf(m, "Some") && name == "exists" =>
              Value.VBool(truthy(apply1(m, args.head, f(0))))
            case Value.VData(t, _) if t == tagOf(m, "None") && name == "exists" => Value.VBool(false)
            case Value.VData(t, f) if t == tagOf(m, "Some") && name == "filter" =>
              if truthy(apply1(m, args.head, f(0))) then recv else noneOf(m)
            case Value.VData(t, _) if t == tagOf(m, "None") && name == "filter" => recv
            case Value.VData(t, f) if t == tagOf(m, "Some") && name == "toList" => listIn(m, List(f(0)))
            case Value.VData(t, _) if t == tagOf(m, "None") && name == "toList" => listIn(m, Nil)
            case Value.VData(t, _) if t == tagOf(m, "None") && name == "getOrElse" => args.head
            // Numeric conversions. `toInt` is IDENTITY on an integer because ScalaScript's `Int` is
            // 64-bit — it is not a narrowing here, and treating it as one would silently change
            // large values. Every arm below was checked against the v2 lane on the same program
            // rather than assumed; the two lanes must agree or the differential gate is worthless.
            // A STRING parsed as a number. `"3".toInt` — the markdown module reads an ordered
            // list's start attribute that way, and it was the last thing between this lane and
            // that corpus case.
            case Value.VStr(x) if name == "toInt" || name == "toLong" =>
              try Value.VInt(java.lang.Long.parseLong(x.trim))
              catch case _: NumberFormatException => throw ExecError("'" + x + "' is not an integer")
            case Value.VStr(x) if name == "toDouble" =>
              try Value.VFloat(x.trim.toDouble)
              catch case _: NumberFormatException => throw ExecError("'" + x + "' is not a number")
            case Value.VInt(n) if name == "toInt"  => Value.VInt(n.toInt.toLong)
            case Value.VInt(n) if name == "toLong" => Value.VInt(n)
            case Value.VInt(n) if name == "toDouble" => Value.VFloat(n.toDouble)
            case Value.VFloat(d) if name == "toInt"  => Value.VInt(d.toLong.toInt.toLong)
            case Value.VFloat(d) if name == "toLong" => Value.VInt(d.toLong)
            case Value.VFloat(d) if name == "toDouble" => Value.VFloat(d)
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

  // Takes the module for ONE arm: `"x = " + P(1, 2)` has to name the constructor the same way
  // `println` does, or a value prints one way on its own and another inside a string.
  private def binOp(m: Module, op: BinOp, a: Value, b: Value): Value = (op, a, b) match
    // `s + x` on a SET adds an element, and `s - x` removes one. The reference lane treats a set as
    // a value with these operators; without them `Set(1,2) + 3` reported an arithmetic error on a
    // program that is not arithmetic.
    case (BinOp.Add, Value.VSet(xs), y) =>
      if xs.exists(z => eq(z, y)) then Value.VSet(xs) else Value.VSet(xs :+ y)
    case (BinOp.Sub, Value.VSet(xs), y) => Value.VSet(xs.filter(z => !eq(z, y)))
    case (BinOp.Add, Value.VStr(x), y)               => Value.VStr(x + showV(m, y))
    // …and the OTHER way round. `1 + "x"` is a string on the reference lane; the executor handled
    // only a string on the LEFT and threw on the right, so `p._1 + p._2` over a mixed tuple failed
    // on one lane and printed on the other.
    case (BinOp.Add, x, Value.VStr(y))               => Value.VStr(showV(m, x) + y)
    // Past the two string arms, a char IS its code point — which is what makes `'x' + 1` 121 and
    // `'a' == 'a'` true without a second set of comparison arms.
    case (o, Value.VChar(c), b)                     => binOp(m, o, Value.VInt(c.toLong), b)
    case (o, a, Value.VChar(c))                     => binOp(m, o, a, Value.VInt(c.toLong))
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
    case (Value.VChar(x), Value.VChar(y))   => x == y
    case (Value.VChar(x), Value.VInt(y))    => x.toLong == y
    case (Value.VInt(x), Value.VChar(y))    => x == y.toLong
    case (Value.VBool(x), Value.VBool(y))   => x == y
    case (Value.VFloat(x), Value.VFloat(y)) => x == y
    case (Value.VUnit, Value.VUnit)         => true
    // A SET is equal by CONTENT, not by order — `Set(1,2) == Set(2,1)` is true, and it is the
    // membership equality the corpus case is named for. Without this arm the comparison fell to the
    // catch-all `false`, so two identical sets were unequal.
    case (Value.VSet(a), Value.VSet(b)) =>
      a.length == b.length && a.forall(x => b.exists(y => eq(y, x)))
    case (Value.VMap(a), Value.VMap(b)) =>
      a.length == b.length &&
        a.forall((k, v) => b.exists((k2, v2) => eq(k, k2) && eq(v, v2)))
    case (Value.VData(t1, f1), Value.VData(t2, f2)) =>
      t1 == t2 && f1.length == f2.length && f1.indices.forall(i => eq(f1(i), f2(i)))
    case _ => false

  /** The host boundary, and the only place in the executor that touches the outside world — which
    * is what invariant I-1 asks of the whole kernel. An unknown primitive is refused by NAME. */
  private def prim(m: Module, name: String, args: List[Value]): Value = name match
    case "io.println" =>
      println(if args.isEmpty then "" else showV(m, args.head))
      Value.VUnit
    case "__autoOutput__" =>
      // Prints only a non-Unit value, exactly as v2 does — the rule the front relies on so that a
      // `println(…)` tail does not print twice.
      if args.nonEmpty && args.head != Value.VUnit then println(showV(m, args.head))
      Value.VUnit
    // The reference lane's `char`: an Int in, a character out.
    // The map primitives, the same names v2 exposes — so the BRIDGE emits `(prim map.put …)` and
    // this lane implements the identical vocabulary rather than a parallel one.
    case "set.of" =>
      var out: List[Value] = Nil
      args.foreach { x => if !out.exists(y => eq(y, x)) then out = out :+ x }
      Value.VSet(out)
    case "map.new" => Value.VMap(scala.collection.mutable.ArrayBuffer.empty)
    case "map.put" =>
      args.head match
        case Value.VMap(es) =>
          val k = args.tail.head
          val i = es.indexWhere((kk, _) => eq(kk, k))
          if i < 0 then es.append((k, args.tail.tail.head)) else es(i) = (k, args.tail.tail.head)
          Value.VUnit
        case v => throw ExecError("map.put on " + show(v))
    case "map.get" =>
      args.head match
        case Value.VMap(es) => es.find((k, _) => eq(k, args.tail.head)).map(_._2).getOrElse(Value.VUnit)
        case v              => throw ExecError("map.get on " + show(v))
    case "char" =>
      args.head match
        case Value.VInt(n) => Value.VChar(n.toChar)
        case v             => throw ExecError("char of " + show(v))
    case "__throw__" =>
      throw ExecError(if args.isEmpty then "throw" else showV(m, args.head))
    case other => throw ExecError("unknown primitive '" + other + "'")
