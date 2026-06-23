package scalascript.interpreter

import scala.meta.Term

/** Runtime value ADT for the ScalaScript interpreter.  Defined as a
 *  `sealed trait` (not `enum`) so `InstanceV` can carry a mutable
 *  `fieldsArr: Array[Value]` without breaking the 399+ pattern-match
 *  sites that bind `(typeName, fields)` positionally. */
sealed trait Value

object Value:
  final case class IntV(v: Long)    extends Value
  final case class DoubleV(v: Double) extends Value
  /** Arbitrary-precision signed integer (exact-numerics v1.64). Produced by
   *  the `BigInt(...)` built-in and by Int→BigInt widening in arithmetic. */
  final case class BigIntV(v: BigInt) extends Value
  /** Arbitrary-precision signed decimal with explicit scale (exact-numerics
   *  v1.64). Backed by `scala.math.BigDecimal`, whose `equals`/`hashCode` are
   *  value-based (`Decimal("1.0") == Decimal("1.00")`) — so numeric `==` and
   *  consistent hashing come for free. The workhorse for money. */
  final case class DecimalV(v: BigDecimal) extends Value
  final case class StringV(v: String)  extends Value
  final case class BoolV(v: Boolean)   extends Value
  final case class CharV(v: Char)      extends Value
  case object UnitV extends Value
  case object NullV extends Value
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
  final case class FunV(
    params: List[String],
    body: Term,
    closure: Env,
    name: String = "",
    defaults: List[Option[Term]] = Nil,
    paramTypes: List[String] = Nil,
    usingParams: List[(String, String)] = Nil,
    returnsThrows: Boolean = false
  ) extends Value:
    /** Monomorphic inline cache for auto-resolved `using` / context-bound
     *  parameters (`GivenRuntime.resolveUsingAllCached`). Resolution depends only
     *  on this FunV's static info + the regular args' runtime types, so a
     *  single-entry cache keyed on a cheap arg type-signature skips the repeated
     *  `concretizeUsingKey` type-matching on monomorphic call sites. Holder is an
     *  immutable `(sig, resolvedValues, givensGeneration)` triple stored as
     *  `AnyRef` to avoid a core→runtime dependency; a benign data race just
     *  recomputes. Excluded from `equals`/`hashCode` (non-constructor field). */
    @transient var usingResolveCache: AnyRef = null
  /** Native function: must return a Computation. Pure built-ins return Pure(v); higher-order
   *  built-ins (map, filter, …) flatMap user callbacks to propagate effects. */
  final case class NativeFnV(name: String, f: List[Value] => Computation) extends Value
  /** ADT / class instance value. `fieldsArr` is populated by StatRuntime at
   *  construction when field order is known; hot readers (PatternRuntime,
   *  BytecodeJit) prefer `fieldsArr(idx)` over `fields.apply(name)`.
   *  Defined as a `final case class` (not an enum case) so we can add this
   *  mutable field without breaking existing two-arg pattern-match sites. */
  final case class InstanceV(typeName: String, fields: Map[String, Value]) extends Value:
    /** Positional fields; null when the instance bypasses StatRuntime.
     *  Index matches `Interpreter.typeFieldOrder(typeName)`. */
    var fieldsArr: Array[Value] | Null = null
    /** Parallel field-name array; set alongside fieldsArr by StatRuntime. Null for
     *  bare InstanceV constructed outside StatRuntime (intrinsics, actor runtime, etc.). */
    var fieldNames: Array[String] | Null = null
    /** Opaque int tag assigned by `Interpreter.typeTagFor`; 0 means unregistered.
     *  BytecodeJit emits `switch(inst.typeTag())` → JVM `tableswitch` (O(1))
     *  instead of `switch(inst.typeName())` → string hash+equals per arm. */
    var typeTag: Int = 0
    /** Effective fields as a Map: uses `fieldsArr + fieldNames` when set (StatRuntime
     *  instances), otherwise falls back to the `fields` constructor argument.
     *  Use this wherever code previously read `inst.fields` for named lookups. */
    def effectiveFields: Map[String, Value] =
      val arr   = fieldsArr
      val names = fieldNames
      if arr == null || names == null then fields
      else FrameMap.fromArrays(names, arr, Map.empty)
    override def equals(that: Any): Boolean = that match
      case other: InstanceV =>
        typeName == other.typeName && effectiveFields == other.effectiveFields
      case _ => false
    override def hashCode: Int = typeName.## * 31 + effectiveFields.##
  object InstanceV:
    def unapply(inst: InstanceV): Some[(String, Map[String, Value])] =
      Some((inst.typeName, inst.effectiveFields))
  /** The default eager linear sequence — backs `List`, `Seq`, and `Iterable` (all observably
   *  identical and all display as `List(…)` in an eager interpreter). */
  final case class ListV(items: List[Value])     extends Value
  /** A real **indexed** sequence backed by a Scala `Vector[Value]` — backs `Vector` and `IndexedSeq`
   *  so that `vec(i)` / `vec.updated(i, x)` are O(log₃₂ n) (effectively O(1)) instead of the O(n) a
   *  `List` would give. Cross-`Seq` equality with `ListV` (`Vector(1,2,3) == List(1,2,3)` is `true`,
   *  as in Scala) is handled in the `==` dispatch path, not via `equals`, so `ListV` stays untouched.
   *  (collection-vector-indexed.) */
  final case class VectorV(items: Vector[Value]) extends Value
  /** A real **mutable** array — distinct from `ListV` because `Array` has genuinely
   *  different runtime semantics: in-place update (`a(i) = x`) and **reference identity**
   *  (`Array(1,2,3) != Array(1,2,3)`). Case-class `==` compares the `Array[Value]` field
   *  by reference (JVM array equality), which is exactly Scala's `Array` identity.
   *  (collection-real-type.) */
  final case class ArrayV(items: Array[Value])   extends Value
  /** A real **lazy** list, backed by Scala's own `LazyList[Value]` so it gets laziness,
   *  memoization, infinite-stream support, and `toString` parity with the JVM backend
   *  (which raw-emits a real `scala.collection.immutable.LazyList`) for free.
   *  (collection-real-type.) */
  final case class LazyListV(underlying: LazyList[Value]) extends Value
  final case class OptionV(inner: Value | Null)  extends Value
  final case class TupleV(elems: List[Value])    extends Value
  final case class MapV(entries: Map[Value, Value]) extends Value
  /** An unordered, deduplicated collection. `Set(...)` builds one; `toSet`
   *  produces one from a list. Element identity is `Value` equality. */
  final case class SetV(items: Set[Value])       extends Value
  final case class DocV(parts: List[Value])      extends Value
  /** Parsed XML document produced by a fenced ` ```xml ` block.
   *  The `doc` value is the root `Markup.Doc` from `markup-core`. */
  final case class MarkupV(doc: scalascript.markup.Markup.Doc) extends Value
  /** Opaque JVM-handle bridge.  Wraps a Java object that the interpreter
   *  cannot inspect structurally but needs to thread through user code
   *  (e.g. `java.sql.Connection` for v1.26 sql blocks).  `typeName` is
   *  the human-readable Scala type name (`"Connection"`,
   *  `"DataSource"`, …) used by `resolveGiven` keyed lookups; `handle`
   *  is the JVM object passed verbatim to / from intrinsics. */
  final case class Foreign(typeName: String, handle: Any) extends Value
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

  // Pre-allocated `Some(IntV(n))` for small `n`, matching the IntV pool range.
  // Slashes Option-monad allocation on counter-driven Some(i).flatMap(...) chains
  // (option-chain bench: 3 OptionV allocs/iter × 300 iter × outer reps).
  private val _someIntVPool: Array[OptionV] =
    Array.tabulate((_poolMax - _poolMin + 1).toInt)(i => OptionV(_intVPool(i)))

  /** Smart constructor for `Some(v)` — returns an interned `OptionV` instance
   *  when `v` is a pooled `IntV`, else allocates a fresh `OptionV`. */
  def someV(v: Value): OptionV = v match
    case iv: IntV =>
      val n = iv.v
      if n >= _poolMin && n <= _poolMax then _someIntVPool((n - _poolMin).toInt)
      else OptionV(iv)
    case _ => OptionV(v)

  /** Field-name array shared by every single-field wrapper (`Right`/`Left`/`Some`/…).
   *  Read-only — instances point `fieldNames` at this singleton; never mutate it. */
  val SingleValueFieldNames: Array[String] = Array("value")

  /** Build a one-field `InstanceV` in the positional `fieldsArr` representation
   *  instead of allocating a single-entry `Map`. The field is named "value".
   *  Used by `Right`/`Left`/`Some` construction sites so readers take the
   *  `fieldsArr` fast path (`effectiveFields` reconstructs the Map on demand). */
  def singleValue(typeName: String, v: Value): InstanceV =
    val inst = InstanceV(typeName, Map.empty)
    inst.fieldsArr  = Array[Value](v)
    inst.fieldNames = SingleValueFieldNames
    inst

  /** Smart constructor for Option[Value]: avoids allocating OptionV(null)
   *  on cache-miss map lookups — returns the NoneV singleton instead.
   *  Routes Some(IntV) through `someV` for the small-int pool. */
  def optionV(opt: Option[Value]): OptionV = opt match
    case None    => NoneV
    case Some(v) => someV(v)

  def show(v: Value): String = v match
    case IntV(n)              => n.toString
    case DoubleV(d)           => if d == d.toLong.toDouble then d.toLong.toString else d.toString
    case BigIntV(n)           => n.toString
    case DecimalV(d)          => d.bigDecimal.toPlainString
    case StringV(s)           => s
    case BoolV(b)             => b.toString
    case CharV(c)             => c.toString
    case UnitV                => "()"
    case NullV                => "null"
    case ListV(items)         => items.iterator.map(show).mkString("List(", ", ", ")")
    case VectorV(items)       => items.iterator.map(show).mkString("Vector(", ", ", ")")
    // Readable `Array(1, 2, 3)`. DIVERGES from Scala's non-deterministic `[I@hash` toString
    // by design (`[I@hash` is useless and can't be cross-backend-asserted). (collection-real-type.)
    case ArrayV(items)        => items.iterator.map(show).mkString("Array(", ", ", ")")
    // `LazyList.toString` itself yields `LazyList(<not computed>)` / forced-prefix — exact JVM parity.
    case LazyListV(ll)        => ll.toString
    case OptionV(null)        => "None"
    case OptionV(v)           => s"Some(${show(v)})"
    case TupleV(elems)        => elems.iterator.map(show).mkString("(", ", ", ")")
    case SetV(items)          =>
      // sorted for deterministic rendering (a set is unordered)
      items.iterator.map(show).toList.sorted.mkString("Set(", ", ", ")")
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
    case inst: Value.InstanceV =>
      val fields = inst.effectiveFields
      if fields.isEmpty then inst.typeName
      else fields.values.iterator.map(show).mkString(s"${inst.typeName}(", ", ", ")")
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

