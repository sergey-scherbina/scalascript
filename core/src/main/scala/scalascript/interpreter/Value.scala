package scalascript.interpreter

import scala.meta.Term

type Env = Map[String, Value]

/** A `Map[String, Value]` specialised for the interpreter's call-env hot
 *  path: a small "frame" of local bindings (params and self-ref) sitting
 *  on top of a `parent` map (closure captures). Three variants:
 *
 *  - `FrameMap1` — one slot, two direct fields (single allocation; the
 *    typical 1-arg lambda / 1-param function case).
 *  - `FrameMap2` — two slots, four direct fields (two-param case).
 *  - `FrameMapN` — three-or-more, parallel arrays.
 *
 *  Lookup is `name == slot ? value : parent.get(name)` — for one or two
 *  bindings this beats `HashMap.get`'s hash + bucket walk and avoids the
 *  HashMap2/3 allocation `closure.updated(name, value)` would do.
 *
 *  Mutation ops (`updated`, `removed`, `iterator`) flatten to an ordinary
 *  Map; they're rare in eval. */
sealed abstract class FrameMap
    extends scala.collection.immutable.AbstractMap[String, Value]:
  protected def parentMap: Map[String, Value]
  protected def flat: Map[String, Value]
  override def updated[V1 >: Value](key: String, value: V1): Map[String, V1] =
    flat.updated(key, value)
  override def removed(key: String): Map[String, Value] =
    flat.removed(key)

final class FrameMap1(n1: String, v1: Value, parent: Map[String, Value])
    extends FrameMap:
  override protected def parentMap: Map[String, Value] = parent
  override def get(key: String): Option[Value] =
    if key == n1 then Some(v1) else parent.get(key)
  override def getOrElse[V1 >: Value](key: String, default: => V1): V1 =
    if key == n1 then v1 else parent.getOrElse(key, default)
  override def contains(key: String): Boolean =
    key == n1 || parent.contains(key)
  override def iterator: Iterator[(String, Value)] =
    Iterator.single(n1 -> v1) ++ parent.iterator.filterNot(_._1 == n1)
  override protected def flat: Map[String, Value] =
    parent.updated(n1, v1)

final class FrameMap2(
  n1: String, v1: Value,
  n2: String, v2: Value,
  parent: Map[String, Value]
) extends FrameMap:
  override protected def parentMap: Map[String, Value] = parent
  override def get(key: String): Option[Value] =
    if key == n1 then Some(v1)
    else if key == n2 then Some(v2)
    else parent.get(key)
  override def getOrElse[V1 >: Value](key: String, default: => V1): V1 =
    if key == n1 then v1
    else if key == n2 then v2
    else parent.getOrElse(key, default)
  override def contains(key: String): Boolean =
    key == n1 || key == n2 || parent.contains(key)
  override def iterator: Iterator[(String, Value)] =
    Iterator(n1 -> v1, n2 -> v2) ++ parent.iterator.filterNot { case (k, _) =>
      k == n1 || k == n2
    }
  override protected def flat: Map[String, Value] =
    parent.updated(n1, v1).updated(n2, v2)

final class FrameMapN(
  slots: Array[String],
  vals:  Array[Value],
  parent: Map[String, Value]
) extends FrameMap:
  override protected def parentMap: Map[String, Value] = parent
  override def get(key: String): Option[Value] =
    var i = 0
    while i < slots.length do
      if slots(i) == key then return Some(vals(i))
      i += 1
    parent.get(key)
  override def getOrElse[V1 >: Value](key: String, default: => V1): V1 =
    var i = 0
    while i < slots.length do
      if slots(i) == key then return vals(i)
      i += 1
    parent.getOrElse(key, default)
  override def contains(key: String): Boolean =
    var i = 0
    while i < slots.length do
      if slots(i) == key then return true
      i += 1
    parent.contains(key)
  override def iterator: Iterator[(String, Value)] =
    val localKeys = slots.toSet
    slots.iterator.zip(vals.iterator) ++
      parent.iterator.filterNot { case (k, _) => localKeys.contains(k) }
  override protected def flat: Map[String, Value] =
    val b = Map.newBuilder[String, Value]
    parent.foreach(kv => b += kv)
    var i = 0
    while i < slots.length do
      b += (slots(i) -> vals(i))
      i += 1
    b.result()

object FrameMap:
  def one(name: String, value: Value, parent: Map[String, Value]): FrameMap =
    new FrameMap1(name, value, parent)
  def two(n1: String, v1: Value, n2: String, v2: Value, parent: Map[String, Value]): FrameMap =
    new FrameMap2(n1, v1, n2, v2, parent)
  def of(names: Array[String], vals: Array[Value], parent: Map[String, Value]): FrameMap =
    new FrameMapN(names, vals, parent)

enum Value:
  case IntV(v: Long)
  case DoubleV(v: Double)
  case StringV(v: String)
  case BoolV(v: Boolean)
  case CharV(v: Char)
  case UnitV
  case NullV
  /** A closure. `defaults(i)` is the default-value expression for parameter
   *  `params(i)`, or `None` if the parameter is required. The list may be
   *  shorter than `params` (in which case missing tail entries are treated
   *  as `None`). Defaults are evaluated lazily at call time, in an env that
   *  includes the closure plus all parameters bound to the left.
   */
  case FunV(
    params: List[String],
    body: Term,
    closure: Env,
    name: String = "",
    defaults: List[Option[Term]] = Nil
  )
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
    case InstanceV("_Raw", fields) =>
      // Pre-escaped HTML marker produced by `raw(s)`; show unwraps the body.
      fields.get("html").map(show).getOrElse("")
    case InstanceV("Lens", fields) =>
      fields.get("_path") match
        case Some(ListV(items)) =>
          val parts = items.collect { case StringV(s) => s }
          s"Lens(_.${parts.mkString(".")})"
        case _ => "Lens(?)"
    case InstanceV("Optional", fields) =>
      fields.get("_steps") match
        case Some(ListV(items)) => s"Optional(_.${formatSteps(items)})"
        case _ => "Optional(?)"
    case InstanceV("Traversal", fields) =>
      fields.get("_steps") match
        case Some(ListV(items)) => s"Traversal(_.${formatSteps(items)})"
        case _ => "Traversal(?)"
    case InstanceV("Prism", fields) =>
      fields.get("_variant") match
        case Some(StringV(v)) => s"Prism[?, $v]"
        case _ => "Prism(?)"
    case InstanceV(t, fields) =>
      if fields.isEmpty then t
      else fields.values.map(show).mkString(s"$t(", ", ", ")")
    case FunV(ps, _, _, _, _) => s"<function(${ps.length})>"
    case NativeFnV(name, _)   => s"<native:$name>"
    case DocV(parts)          => parts.map(show).mkString("\n")

  /** Render an optic's `_steps` array as a dotted path with `.some` / `.each` /
   *  `.index(i)` / `.at(k)` markers replaced by their source syntax. */
  private def formatSteps(items: List[Value]): String =
    items.collect {
      case StringV("__some__") => "some"
      case StringV("__each__") => "each"
      case TupleV(List(StringV("__index__"), IntV(i))) => s"index($i)"
      case TupleV(List(StringV("__at__"),    k))       => s"at(${show(k)})"
      case StringV(n)          => n
    }.mkString(".")

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
