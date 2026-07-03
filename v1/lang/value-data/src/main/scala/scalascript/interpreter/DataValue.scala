package scalascript.interpreter

/** The **pure-data scalar leaves** of the interpreter value type (value-unification, scalars-only).
 *
 *  These cases carry no `Env`/`Computation`/`Term` and never recursively contain another `Value`, so
 *  they are host-neutral and can eventually move to a module *below* `core` and be shared with
 *  `backend.spi.SpiValue` (deleting the scalar half of the `Value ↔ SpiValue` conversion). For now they
 *  live in `core`; the interpreter's `Value` is `DataValue | ValueRest` (see `Value.scala`), and
 *  `object Value` re-exports these via `export DataValue.*` so existing `Value.IntV(n)` / `case
 *  Value.IntV(n)` sites are unchanged.
 *
 *  The *container* cases (`ListV`/`VectorV`/`MapV`/…) and the *carriers* (`FunV`/`NativeFnV`) stay in
 *  `ValueRest` (core): a container can hold an arbitrary `Value` — including a closure (e.g.
 *  `List(() => 10)`) — so it cannot be self-contained host-neutral data. See `specs/value-unification.md`. */
enum DataValue:
  case IntV(v: Long)
  case DoubleV(v: Double)
  /** Arbitrary-precision signed integer (exact-numerics v1.64). Produced by
   *  the `BigInt(...)` built-in and by Int→BigInt widening in arithmetic. */
  case BigIntV(v: BigInt)
  /** Arbitrary-precision signed decimal with explicit scale (exact-numerics
   *  v1.64). Backed by `scala.math.BigDecimal`, whose `equals`/`hashCode` are
   *  value-based (`Decimal("1.0") == Decimal("1.00")`) — so numeric `==` and
   *  consistent hashing come for free. The workhorse for money. */
  case DecimalV(v: BigDecimal)
  case StringV(v: String)
  case BoolV(v: Boolean)
  case CharV(v: Char)
  case UnitV
  case NullV
