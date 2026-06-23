package scalascript.interpreter

/** Free monad over effect operations — **trampolined**, stack-safe in the sense
 *  of "Stackless Scala With Free Monads" (Bjarnason 2012).
 *
 *  The data type has three cases:
 *
 *    Pure(v)                  — done; result is v
 *    Perform(eff, op, args)   — request an effect; the *rest* of the computation
 *                               lives in an outer FlatMap
 *    FlatMap(sub, k)          — explicit bind node: run sub, then continue with k
 *
 *  `flatMap` is **constant time** — it never inspects its argument, it only
 *  wraps in a FlatMap node. The work of stepping through a computation happens
 *  in `run` / handler `interp`, which use a `while`-loop and re-associate
 *  `FlatMap(FlatMap(c, g), f)` into `FlatMap(c, x => FlatMap(g(x), f))` (right-
 *  associated form) so an arbitrarily long bind chain is walked in O(1) Scala
 *  stack.
 *
 *  Handler semantics: each handled Perform is dispatched to its case with a
 *  real `resume` closure that re-enters `interp` on the captured continuation.
 *  `resume` may be called multiple times (multi-shot); side effects in the
 *  body run exactly once.
 */
enum Computation:
  case Pure(value: Value)
  case Perform(effectName: String, opName: String, args: List[Value])
  case FlatMap(sub: Computation, k: Value => Computation)

object Computation:
  // Singleton Pure wrappers for the most common return values — eliminates one
  // allocation per call on every hot dispatch path (isRight, isEmpty, foreach, etc.).
  val PureUnit:      Pure = Pure(Value.UnitV)
  val PureNull:      Pure = Pure(Value.NullV)
  val PureTrue:      Pure = Pure(Value.True)
  val PureFalse:     Pure = Pure(Value.False)
  val PureNone:      Pure = Pure(Value.NoneV)
  val PureEmptyList: Pure = Pure(Value.EmptyList)
  val PureEmptyStr:  Pure = Pure(Value.EmptyStr)

  inline def pureBool(b: Boolean): Pure = if b then PureTrue else PureFalse

  /** Lift an Option[Value] into a Pure Computation without allocating OptionV(null). */
  def pureOptionV(opt: Option[Value]): Pure = opt match
    case None    => PureNone
    case Some(v) => Pure(Value.OptionV(v))

  def pureIntV(n: Long): Pure =
    if n >= -2048L && n <= 16383L then Value._pureIntPool((n + 2048L).toInt)
    else Pure(Value.IntV(n))

  /** Wrap a `Value` in `Pure` using the singleton/pool-cached `Pure` wrapper
   *  when possible, falling back to fresh `Pure(v)` otherwise. Targets the
   *  hot `Term.Name` eval path in `EvalRuntime.evalCore` (JFR-2026-06-02
   *  showed Pure wrappers as a top allocator on `recursiveEval`).
   *
   *  Pool-cached IntV in the [-2048, 16383] range maps to its pre-built
   *  `_pureIntPool` entry; BoolV/UnitV/NullV/NoneV/EmptyList/EmptyStr each
   *  reuse their existing singleton `Pure`. Other values allocate fresh —
   *  the fall-through stays minimal so non-pool values pay only one extra
   *  pattern match. */
  def purify(v: Value): Pure = v match
    case Value.IntV(n) if n >= -2048L && n <= 16383L =>
      Value._pureIntPool((n + 2048L).toInt)
    case _: Value.BoolV    => if v eq Value.True then PureTrue else PureFalse
    case Value.UnitV       => PureUnit
    case Value.NullV       => PureNull
    case Value.NoneV       => PureNone
    case Value.EmptyList   => PureEmptyList
    case Value.EmptyStr    => PureEmptyStr
    case _                 => Pure(v)

  /** Sequence: feed the result of `c` into `f`. O(1) — just wraps in FlatMap. */
  def flatMap(c: Computation, f: Value => Computation): Computation = c match
    case Pure(v) => f(v)
    case _       => FlatMap(c, f)

  def map(c: Computation, f: Value => Value): Computation = c match
    case Pure(v) => Pure(f(v))
    case _       => FlatMap(c, v => Pure(f(v)))

  /** Cached continuation: discard result and return Unit.  Used by foreach-style callers
   *  to avoid allocating a new `_ => PureUnit` lambda on every call. */
  val discardToUnit: Value => Computation = _ => PureUnit

  /** Cached continuation: wrap a value in OptionV(v). Used by Option.map.
   *  Avoids one lambda allocation per Option.map call site. */
  val wrapSome: Value => Value = v => Value.someV(v)

  /** Cached Computation continuation: wrap result in OptionV(v) and lift to Pure.
   *  Using flatMap(wrapSomeC) instead of map(wrapSome) saves the `v => Pure(f(v))` lambda. */
  val wrapSomeC: Value => Computation = v => Pure(Value.someV(v))

  /** Cached Computation continuation for Option.flatMap: normalise any Value to OptionV. */
  val wrapOptionC: Value => Computation = {
    case o: Value.OptionV => Pure(o)
    case other            => Pure(Value.someV(other))
  }

  /** Run computation c and discard its result (return Unit).  Avoids one lambda allocation
   *  vs `.map(_ => Value.UnitV)` or `.flatMap(_ => PureUnit)`. */
  def mapUnit(c: Computation): Computation = c match
    case Pure(_) => PureUnit
    case _       => FlatMap(c, discardToUnit)

  /** Map f over ls and collect results into ListV without building an intermediate
   *  List[Computation].  All-Pure fast path: all f(item) results are Pure — no
   *  FlatMap nodes allocated, just ArrayBuffer → toList → ListV. */
  def mapSequence(ls: List[Value], f: Value => Computation): Computation =
    ls match
      case Nil      => PureEmptyList
      case x :: Nil =>
        f(x) match
          case Pure(v) => Pure(Value.ListV(v :: Nil))
          case comp    => FlatMap(comp, v => Pure(Value.ListV(v :: Nil)))
      case x :: y :: Nil =>
        f(x) match
          case Pure(v1) =>
            f(y) match
              case Pure(v2) => Pure(Value.ListV(v1 :: v2 :: Nil))
              case comp2    => FlatMap(comp2, v2 => Pure(Value.ListV(v1 :: v2 :: Nil)))
          case comp1 =>
            FlatMap(comp1, v1 =>
              f(y) match
                case Pure(v2) => Pure(Value.ListV(v1 :: v2 :: Nil))
                case comp2    => FlatMap(comp2, v2 => Pure(Value.ListV(v1 :: v2 :: Nil)))
            )
      case x :: y :: z :: Nil =>
        f(x) match
          case Pure(v1) =>
            f(y) match
              case Pure(v2) =>
                f(z) match
                  case Pure(v3)  => Pure(Value.ListV(v1 :: v2 :: v3 :: Nil))
                  case comp3     => FlatMap(comp3, v3 => Pure(Value.ListV(v1 :: v2 :: v3 :: Nil)))
              case comp2 =>
                FlatMap(comp2, v2 =>
                  f(z) match
                    case Pure(v3) => Pure(Value.ListV(v1 :: v2 :: v3 :: Nil))
                    case comp3    => FlatMap(comp3, v3 => Pure(Value.ListV(v1 :: v2 :: v3 :: Nil)))
                )
          case comp1 =>
            FlatMap(comp1, v1 =>
              f(y) match
                case Pure(v2) =>
                  f(z) match
                    case Pure(v3) => Pure(Value.ListV(v1 :: v2 :: v3 :: Nil))
                    case comp3    => FlatMap(comp3, v3 => Pure(Value.ListV(v1 :: v2 :: v3 :: Nil)))
                case comp2 =>
                  FlatMap(comp2, v2 =>
                    f(z) match
                      case Pure(v3) => Pure(Value.ListV(v1 :: v2 :: v3 :: Nil))
                      case comp3    => FlatMap(comp3, v3 => Pure(Value.ListV(v1 :: v2 :: v3 :: Nil)))
                  )
            )
      case _ =>
        val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
        var head = ls
        while head.nonEmpty do
          f(head.head) match
            case Pure(v) => buf += v; head = head.tail
            case comp    =>
              val rest0 = head.tail
              def loopRest(vs: List[Value]): Computation = vs match
                case Nil       => Pure(Value.ListV(buf.toList))
                case v :: rest => FlatMap(f(v), { rv => buf += rv; loopRest(rest) })
              return FlatMap(comp, { r => buf += r; loopRest(rest0) })
        Pure(Value.ListV(buf.toList))

  /** Like mapSequence but iterates over a String's chars without allocating List[Char]. */
  def mapSequenceStr(s: String, f: Char => Computation): Computation =
    if s.isEmpty then return PureEmptyList
    val buf = new scala.collection.mutable.ArrayBuffer[Value](s.length)
    var i = 0
    while i < s.length do
      f(s.charAt(i)) match
        case Pure(v) => buf += v; i += 1
        case comp    =>
          val start  = i + 1
          def loopRest(j: Int): Computation =
            if j >= s.length then Pure(Value.ListV(buf.toList))
            else FlatMap(f(s.charAt(j)), { rv => buf += rv; loopRest(j + 1) })
          return FlatMap(comp, { r => buf += r; loopRest(start) })
    Pure(Value.ListV(buf.toList))

  /** Like mapSequence but over an integer range [from, until) without allocating a List[Int]. */
  def mapSequenceRange(from: Int, until: Int, f: Int => Computation): Computation =
    if from >= until then return PureEmptyList
    val buf = new scala.collection.mutable.ArrayBuffer[Value](until - from)
    var i = from
    while i < until do
      f(i) match
        case Pure(v) => buf += v; i += 1
        case comp    =>
          val start  = i + 1
          def loopRest(j: Int): Computation =
            if j >= until then Pure(Value.ListV(buf.toList))
            else FlatMap(f(j), { rv => buf += rv; loopRest(j + 1) })
          return FlatMap(comp, { r => buf += r; loopRest(start) })
    Pure(Value.ListV(buf.toList))

  /** Like mapSequence but discards results (foreach semantics).
   *  All-Pure fast path: zero FlatMap allocations when f returns Pure for every element. */
  def foreachSequence(ls: List[Value], f: Value => Computation): Computation =
    var rem = ls
    while rem.nonEmpty do
      f(rem.head) match
        case Pure(_) => rem = rem.tail
        case comp    =>
          val tail = rem.tail
          def loopRest(remaining: List[Value]): Computation = remaining match
            case Nil    => PureUnit
            case h :: t => FlatMap(f(h), _ => loopRest(t))
          return FlatMap(comp, _ => loopRest(tail))
    PureUnit

  /** Like foreachSequence but collects elements where f returns BoolV(true) (filter semantics).
   *  All-Pure fast path: zero FlatMap allocations when f returns Pure(BoolV) every time. */
  def filterSequence(ls: List[Value], f: Value => Computation): Computation =
    if ls.isEmpty then return PureEmptyList
    val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
    var rem = ls
    while rem.nonEmpty do
      val h = rem.head
      f(h) match
        case Pure(Value.BoolV(true))  => buf += h; rem = rem.tail
        case Pure(_)                  => rem = rem.tail
        case comp =>
          val tail = rem.tail
          def loopRest(remaining: List[Value]): Computation = remaining match
            case Nil => Pure(Value.ListV(buf.toList))
            case hh :: t => FlatMap(f(hh), {
              case Value.BoolV(true) => buf += hh; loopRest(t)
              case _                 => loopRest(t)
            })
          return FlatMap(comp, {
            case Value.BoolV(true) => buf += h; loopRest(tail)
            case _                 => loopRest(tail)
          })
    Pure(Value.ListV(buf.toList))

  /** Like filterSequence but inverts the predicate (filterNot semantics). */
  def filterNotSequence(ls: List[Value], f: Value => Computation): Computation =
    if ls.isEmpty then return PureEmptyList
    val buf = new scala.collection.mutable.ArrayBuffer[Value](ls.length)
    var rem = ls
    while rem.nonEmpty do
      val h = rem.head
      f(h) match
        case Pure(Value.BoolV(false)) | Pure(Value.UnitV) => buf += h; rem = rem.tail
        case Pure(_)                  => rem = rem.tail
        case comp =>
          val tail = rem.tail
          def loopRest(remaining: List[Value]): Computation = remaining match
            case Nil => Pure(Value.ListV(buf.toList))
            case hh :: t => FlatMap(f(hh), {
              case Value.BoolV(true) => loopRest(t)
              case _                 => buf += hh; loopRest(t)
            })
          return FlatMap(comp, {
            case Value.BoolV(true) => loopRest(tail)
            case _                 => buf += h; loopRest(tail)
          })
    Pure(Value.ListV(buf.toList))

  /** foldLeft semantics with Pure fast-path: if f returns Pure on every step, zero FlatMap allocations. */
  def foldLeftSequence(ls: List[Value], init: Value, f: (Value, Value) => Computation): Computation =
    var rem = ls; var acc = init
    while rem.nonEmpty do
      f(acc, rem.head) match
        case Pure(v) => acc = v; rem = rem.tail
        case comp    =>
          val tail = rem.tail
          def loopRest(remaining: List[Value], curAcc: Value): Computation = remaining match
            case Nil       => Pure(curAcc)
            case h :: rest => FlatMap(f(curAcc, h), v => loopRest(rest, v))
          return FlatMap(comp, v => loopRest(tail, v))
    Pure(acc)

  /** partition semantics with Pure fast-path: splits list into (yes, no) tuple without FlatMap when f is pure. */
  def partitionSequence(ls: List[Value], f: Value => Computation): Computation =
    val yesBuf = new scala.collection.mutable.ArrayBuffer[Value](ls.length / 2 + 1)
    val noBuf  = new scala.collection.mutable.ArrayBuffer[Value](ls.length / 2 + 1)
    var rem = ls
    while rem.nonEmpty do
      val h = rem.head
      f(h) match
        case Pure(Value.BoolV(true)) => yesBuf += h; rem = rem.tail
        case Pure(_)                 => noBuf  += h; rem = rem.tail
        case comp =>
          val tail = rem.tail
          def loopRest(remaining: List[Value]): Computation = remaining match
            case Nil => Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(noBuf.toList) :: Nil))
            case hh :: rest => FlatMap(f(hh), {
              case Value.BoolV(true) => yesBuf += hh; loopRest(rest)
              case _                 => noBuf  += hh; loopRest(rest)
            })
          return FlatMap(comp, {
            case Value.BoolV(true) => yesBuf += h; loopRest(tail)
            case _                 => noBuf  += h; loopRest(tail)
          })
    Pure(Value.TupleV(Value.ListV(yesBuf.toList) :: Value.ListV(noBuf.toList) :: Nil))

  /** Evaluate a list of computations in order, collecting their results in a ListV.
   *  All-Pure fast path: skip FlatMap chain when every computation is already Pure. */
  def sequence(cs: List[Computation]): Computation =
    if cs.isEmpty then return PureEmptyList
    var rest    = cs
    val buf     = new scala.collection.mutable.ArrayBuffer[Value](cs.length)
    while rest.nonEmpty do
      rest.head match
        case Pure(v) => buf += v; rest = rest.tail
        case _       =>
          // Build initAcc from buf in reverse so that acc.reverse at the end
          // restores the original order of the already-accumulated pure values.
          var initAcc: List[Value] = Nil
          var i = 0
          while i < buf.length do { initAcc = buf(i) :: initAcc; i += 1 }
          def loop(remaining: List[Computation], acc: List[Value]): Computation = remaining match
            case Nil       => Pure(Value.ListV(acc.reverse))
            case c :: tail => FlatMap(c, v => loop(tail, v :: acc))
          return loop(rest, initAcc)
    val items = buf.toList
    if items.isEmpty then PureEmptyList else Pure(Value.ListV(items))

  /** Run a computation to a Value, erroring on any unhandled Perform.
   *  Uses a while-loop with FlatMap re-association — stack-safe regardless of
   *  how deep the bind chain or how many Pure short-circuits accumulate. */
  def run(c: Computation): Value =
    var current: Computation = c
    while true do
      current match
        case Pure(v)                => return v
        case Perform(eff, op, _)    =>
          throw InterpretError(s"Unhandled effect: $eff.$op (no handler in scope)")
        case FlatMap(sub, f) => sub match
          case Pure(v)                => current = f(v)
          case Perform(eff, op, _)    =>
            throw InterpretError(s"Unhandled effect: $eff.$op (no handler in scope)")
          case FlatMap(sub2, g)       =>
            current = FlatMap(sub2, x => FlatMap(g(x), f))
    throw InterpretError("unreachable")

  /** Lift a pure builtin into a Computation-returning NativeFnV body. */
  def pureFn(f: List[Value] => Value): List[Value] => Computation =
    args => Pure(f(args))

extension (c: Computation)
  def flatMap(f: Value => Computation): Computation = Computation.flatMap(c, f)
  def map(f: Value => Value): Computation          = Computation.map(c, f)

class InterpretError(msg: String) extends RuntimeException(msg)
/** Thrown by `Term.Throw` evaluation; carries the ScalaScript value that was thrown.
 *  Caught by `Term.Try` handlers in the interpreter. */
class ScriptException(val value: Value) extends RuntimeException(Value.show(value))
/** Like ScriptException but overrides fillInStackTrace to skip JVM trace capture.
 *  Used for @noTrace-annotated types thrown in hot loops. */
class ScriptExceptionNoTrace(value: Value) extends ScriptException(value):
  override def fillInStackTrace(): Throwable = this
private[interpreter] class ReturnSignal(val value: Value) extends Exception
private[interpreter] class TailCall(var args: List[Value]) extends Throwable(null, null, true, false)
private[interpreter] class MutualTailCall(var f: Value.FunV, var args: List[Value]) extends Throwable(null, null, true, false)
