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
  private[interpreter] def parent: Map[String, Value] = parentMap
  protected def flat: Map[String, Value]
  override def updated[V1 >: Value](key: String, value: V1): Map[String, V1] =
    flat.updated(key, value)
  override def removed(key: String): Map[String, Value] =
    flat.removed(key)
  /** Append only the local slots (not the parent chain) to `b`, filtering out
   *  entries whose value matches the same key in `globals`.  Used by lambda
   *  closure construction to avoid iterating the entire globals map. */
  private[interpreter] def appendLocalTo(
    b: scala.collection.mutable.Growable[(String, Value)],
    globals: scala.collection.mutable.Map[String, Value]
  ): Unit
  /** HashMap overload: uses `b(k) = v` (no Tuple2) instead of `b += (k -> v)`. */
  private[interpreter] def appendLocalTo(
    b: scala.collection.mutable.HashMap[String, Value],
    globals: scala.collection.mutable.HashMap[String, Value]
  ): Unit

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
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.Growable[(String, Value)], globals: scala.collection.mutable.Map[String, Value]): Unit =
    if globals.getOrElse(n1, null) != v1 then b += (n1 -> v1)
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.HashMap[String, Value], globals: scala.collection.mutable.HashMap[String, Value]): Unit =
    if globals.getOrElse(n1, null) != v1 then b(n1) = v1

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
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.Growable[(String, Value)], globals: scala.collection.mutable.Map[String, Value]): Unit =
    if globals.getOrElse(n1, null) != v1 then b += (n1 -> v1)
    if globals.getOrElse(n2, null) != v2 then b += (n2 -> v2)
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.HashMap[String, Value], globals: scala.collection.mutable.HashMap[String, Value]): Unit =
    if globals.getOrElse(n1, null) != v1 then b(n1) = v1
    if globals.getOrElse(n2, null) != v2 then b(n2) = v2

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
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.Growable[(String, Value)], globals: scala.collection.mutable.Map[String, Value]): Unit =
    var i = 0
    while i < slots.length do
      if globals.getOrElse(slots(i), null) != vals(i) then b += (slots(i) -> vals(i))
      i += 1
  override private[interpreter] def appendLocalTo(b: scala.collection.mutable.HashMap[String, Value], globals: scala.collection.mutable.HashMap[String, Value]): Unit =
    var i = 0
    while i < slots.length do
      if globals.getOrElse(slots(i), null) != vals(i) then b(slots(i)) = vals(i)
      i += 1

object FrameMap:
  def one(name: String, value: Value, parent: Map[String, Value]): FrameMap =
    new FrameMap1(name, value, parent)
  def two(n1: String, v1: Value, n2: String, v2: Value, parent: Map[String, Value]): FrameMap =
    new FrameMap2(n1, v1, n2, v2, parent)
  def of(names: Array[String], vals: Array[Value], parent: Map[String, Value]): FrameMap =
    new FrameMapN(names, vals, parent)
  /** Overlay `fields` on top of `parent` without allocating a merged HashMap.
   *  Used by the typeMethods dispatch path to inject instance fields into a
   *  method's closure without calling `fn.closure ++ fields`. */
  def fromMap(fields: Map[String, Value], parent: Map[String, Value]): Map[String, Value] =
    fields.size match
      case 0 => parent
      case 1 =>
        var k0: String = null
        var v0: Value = null
        fields.foreachEntry { (k, v) => k0 = k; v0 = v }
        new FrameMap1(k0, v0, parent)
      case 2 =>
        var k0: String = null
        var v0: Value = null
        var k1: String = null
        var v1: Value = null
        fields.foreachEntry { (k, v) =>
          if k0 == null then { k0 = k; v0 = v }
          else { k1 = k; v1 = v }
        }
        new FrameMap2(k0, v0, k1, v1, parent)
      case _ =>
        val keys = new Array[String](fields.size)
        val vals = new Array[Value](fields.size)
        var i = 0
        fields.foreachEntry { (k, v) => keys(i) = k; vals(i) = v; i += 1 }
        new FrameMapN(keys, vals, parent)

  /** Like fromMap but also inlines an extra (selfName, selfRef) slot so the
   *  self-reference for a named class method is available without an additional
   *  Map.updated call on the parent (which for MutableEnvView is a full copy). */
  def fromMapWithSelf(
    fields: Map[String, Value], selfName: String, selfRef: Value,
    parent: Map[String, Value]
  ): Map[String, Value] =
    fields.size match
      case 0 =>
        new FrameMap1(selfName, selfRef, parent)
      case 1 =>
        var k0: String = null
        var v0: Value = null
        fields.foreachEntry { (k, v) => k0 = k; v0 = v }
        new FrameMap2(k0, v0, selfName, selfRef, parent)
      case _ =>
        val n = fields.size + 1
        val keys = new Array[String](n)
        val vals = new Array[Value](n)
        var i = 0
        fields.foreachEntry { (k, v) => keys(i) = k; vals(i) = v; i += 1 }
        keys(n - 1) = selfName
        vals(n - 1) = selfRef
        new FrameMapN(keys, vals, parent)

/** Presents a `scala.collection.mutable.Map` as an immutable `Map[String, Value]`
 *  without copying it.  Used by `BlockRuntime.evalBlock` to avoid the
 *  `local.toMap` allocation on every statement.
 *
 *  Mutation ops (`updated`, `removed`) flatten to a copied Map — they are
 *  rare in the eval path (only when a `val/var` binding is used as a key). */
final class MutableEnvView(m: scala.collection.mutable.Map[String, Value])
    extends scala.collection.immutable.AbstractMap[String, Value]:
  override def get(key: String): Option[Value]     = m.get(key)
  override def getOrElse[V1 >: Value](key: String, default: => V1): V1 = m.getOrElse(key, default)
  override def contains(key: String): Boolean      = m.contains(key)
  override def iterator: Iterator[(String, Value)] = m.iterator
  override def updated[V1 >: Value](key: String, value: V1): Map[String, V1] =
    (m.toMap: Map[String, Value]).updated(key, value).asInstanceOf[Map[String, V1]]
  override def removed(key: String): Map[String, Value] = m.toMap.removed(key)

enum Value:
  case IntV(v: Long)
  case DoubleV(v: Double)
  /** Arbitrary-precision signed integer (exact-numerics v1.64). Produced by
   *  the `BigInt(...)` built-in and by Int→BigInt widening in arithmetic. */
  case BigIntV(v: BigInt)
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
   *
   *  `paramTypes` holds a type-annotation string for each *regular* (non-using)
   *  parameter; used for given-instance type inference.
   *  `usingParams` is an ordered list of `(paramName, typeKey)` pairs for all
   *  `using` / context-bound parameters.  When a call site omits these
   *  arguments, the interpreter resolves them automatically from the given
   *  table.
   */
  case FunV(
    params: List[String],
    body: Term,
    closure: Env,
    name: String = "",
    defaults: List[Option[Term]] = Nil,
    paramTypes: List[String] = Nil,
    usingParams: List[(String, String)] = Nil,
    returnsThrows: Boolean = false
  )
  /** Native function: must return a Computation. Pure built-ins return Pure(v); higher-order
   *  built-ins (map, filter, …) flatMap user callbacks to propagate effects. */
  case NativeFnV(name: String, f: List[Value] => Computation)
  case InstanceV(typeName: String, fields: Map[String, Value])
  case ListV(items: List[Value])
  case OptionV(inner: Value | Null)
  case TupleV(elems: List[Value])
  case MapV(entries: Map[Value, Value])
  case DocV(parts: List[Value])
  /** Parsed XML document produced by a fenced ` ```xml ` block.
   *  The `doc` value is the root `Markup.Doc` from `markup-core`. */
  case MarkupV(doc: scalascript.markup.Markup.Doc)
  /** Opaque JVM-handle bridge.  Wraps a Java object that the interpreter
   *  cannot inspect structurally but needs to thread through user code
   *  (e.g. `java.sql.Connection` for v1.26 sql blocks).  `typeName` is
   *  the human-readable Scala type name (`"Connection"`,
   *  `"DataSource"`, …) used by `resolveGiven` keyed lookups; `handle`
   *  is the JVM object passed verbatim to / from intrinsics. */
  case Foreign(typeName: String, handle: Any)

object Value:
  // Pool for common small integer values (-2048..16383). `intV(n)` returns a
  // cached instance for in-range values, avoiding a heap allocation on every
  // arithmetic result. Wider range covers typical loop counters beyond 1024
  // and keeps HTTP status codes (200–500) pooled.
  private val _poolMin = -2048L
  private val _poolMax = 16383L
  private val _intVPool: Array[IntV] =
    Array.tabulate((_poolMax - _poolMin + 1).toInt)(i => IntV(_poolMin + i))

  def intV(n: Long): IntV =
    if n >= _poolMin && n <= _poolMax then _intVPool((n - _poolMin).toInt)
    else IntV(n)

  // Pre-cached Pure(IntV) pool — parallel to _intVPool.  Every `Pure(intV(n))`
  // in the hot dispatch path hits this cache when n is in range, so both the
  // IntV AND the Pure wrapper are allocation-free.
  private[interpreter] val _pureIntPool: Array[Computation.Pure] =
    _intVPool.map(Computation.Pure(_))

  // Pre-cached DoubleV constants for 0.0 and 1.0.
  val DoubleZero: DoubleV = DoubleV(0.0)
  val DoubleOne:  DoubleV = DoubleV(1.0)

  def doubleV(d: Double): DoubleV =
    if d == 0.0 then DoubleZero
    else if d == 1.0 then DoubleOne
    else DoubleV(d)

  // Pool of CharV for the printable ASCII + control range (0–127).
  // Covers the entire 7-bit ASCII range so string.charAt / string.map over
  // ASCII text never allocates a fresh CharV.
  private val _charVPool: Array[CharV] = Array.tabulate(128)(i => CharV(i.toChar))

  def charV(c: Char): CharV =
    if c.toInt < 128 then _charVPool(c.toInt) else CharV(c)

  // Pre-cached constants used by the Computation monad's run loop.
  val True:      BoolV   = BoolV(true)
  val False:     BoolV   = BoolV(false)
  val NoneV:     OptionV  = OptionV(null)
  val EmptyList: ListV    = ListV(Nil)
  val EmptyMap:  MapV     = MapV(Map.empty)
  val EmptyStr:  StringV  = StringV("")

  def boolV(b: Boolean): BoolV = if b then True else False

  /** Smart constructor for Option[Value]: avoids allocating OptionV(null)
   *  on cache-miss map lookups — returns the NoneV singleton instead. */
  def optionV(opt: Option[Value]): OptionV = opt match
    case None    => NoneV
    case Some(v) => OptionV(v)

  def show(v: Value): String = v match
    case IntV(n)              => n.toString
    case DoubleV(d)           => if d == d.toLong.toDouble then d.toLong.toString else d.toString
    case BigIntV(n)           => n.toString
    case StringV(s)           => s
    case BoolV(b)             => b.toString
    case CharV(c)             => c.toString
    case UnitV                => "()"
    case NullV                => "null"
    case ListV(items)         => items.iterator.map(show).mkString("List(", ", ", ")")
    case OptionV(null)        => "None"
    case OptionV(v)           => s"Some(${show(v)})"
    case TupleV(elems)        => elems.iterator.map(show).mkString("(", ", ", ")")
    case MapV(m)              =>
      if m.isEmpty then "Map()"
      else m.iterator.map { case (k, v) => s"${show(k)} -> ${show(v)}" }.mkString("Map(", ", ", ")")
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
    case InstanceV("JsonValue", fields) =>
      // Render the wrapped raw value, hiding the bundled accessor
      // closures.  `v.toString` reads like JSON: strings quoted,
      // arrays / objects bracketed.
      fields.get("_inner").map(show).getOrElse("JsonValue(?)")
    case InstanceV(t, fields) =>
      if fields.isEmpty then t
      else fields.values.iterator.map(show).mkString(s"$t(", ", ", ")")
    case FunV(ps, _, _, _, _, _, _, _) => s"<function(${ps.length})>"
    case NativeFnV(name, _)   => s"<native:$name>"
    case DocV(parts)          => parts.map(show).mkString("\n")
    case MarkupV(doc)         => scalascript.markup.PureMarkupCodec.serialize(doc)
    case Foreign(tn, h)       =>
      val short = Option(h).map(_.getClass.getSimpleName).getOrElse("null")
      s"<foreign:$tn ($short)>"

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
  val wrapSome: Value => Value = v => Value.OptionV(v)

  /** Cached Computation continuation: wrap result in OptionV(v) and lift to Pure.
   *  Using flatMap(wrapSomeC) instead of map(wrapSome) saves the `v => Pure(f(v))` lambda. */
  val wrapSomeC: Value => Computation = v => Pure(Value.OptionV(v))

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
