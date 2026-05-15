package scalascript.interpreter

import scala.meta.Term

type Env = Map[String, Value]

enum Value:
  case IntV(v: Long)
  case DoubleV(v: Double)
  case StringV(v: String)
  case BoolV(v: Boolean)
  case CharV(v: Char)
  case UnitV
  case NullV
  case FunV(params: List[String], body: Term, closure: Env, name: String = "")
  /** Native function: must return a Computation. Pure built-ins return Pure(v); higher-order
   *  built-ins (map, filter, …) flatMap user callbacks to propagate effects. */
  case NativeFnV(name: String, f: List[Value] => Computation)
  case InstanceV(typeName: String, fields: Map[String, Value])
  case ListV(items: List[Value])
  case OptionV(inner: Option[Value])
  case TupleV(elems: List[Value])
  case MapV(entries: Map[Value, Value])
  case DocV(parts: List[Value])

object Value:
  def show(v: Value): String = v match
    case IntV(n)              => n.toString
    case DoubleV(d)           => if d == d.toLong.toDouble then d.toLong.toString else d.toString
    case StringV(s)           => s
    case BoolV(b)             => b.toString
    case CharV(c)             => c.toString
    case UnitV                => "()"
    case NullV                => "null"
    case ListV(items)         => items.map(show).mkString("List(", ", ", ")")
    case OptionV(None)        => "None"
    case OptionV(Some(v))     => s"Some(${show(v)})"
    case TupleV(elems)        => elems.map(show).mkString("(", ", ", ")")
    case MapV(m)              =>
      if m.isEmpty then "Map()"
      else m.map { case (k, v) => s"${show(k)} -> ${show(v)}" }.mkString("Map(", ", ", ")")
    case InstanceV(t, fields) =>
      if fields.isEmpty then t
      else fields.values.map(show).mkString(s"$t(", ", ", ")")
    case FunV(ps, _, _, _)    => s"<function(${ps.length})>"
    case NativeFnV(name, _)   => s"<native:$name>"
    case DocV(parts)          => parts.map(show).mkString("\n")

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
  /** Sequence: feed the result of `c` into `f`. O(1) — just wraps in FlatMap. */
  def flatMap(c: Computation, f: Value => Computation): Computation = FlatMap(c, f)

  def map(c: Computation, f: Value => Value): Computation =
    FlatMap(c, v => Pure(f(v)))

  /** Evaluate a list of computations in order, collecting their results in a ListV.
   *  The resulting Computation is a right-associated chain of FlatMaps. */
  def sequence(cs: List[Computation]): Computation =
    def loop(remaining: List[Computation], acc: List[Value]): Computation = remaining match
      case Nil       => Pure(Value.ListV(acc.reverse))
      case c :: rest => FlatMap(c, v => loop(rest, v :: acc))
    loop(cs, Nil)

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
private[interpreter] class ReturnSignal(val value: Value) extends Exception
private[interpreter] class TailCall(val args: List[Value]) extends Throwable(null, null, true, false)
private[interpreter] class MutualTailCall(val f: Value.FunV, val args: List[Value]) extends Throwable(null, null, true, false)
