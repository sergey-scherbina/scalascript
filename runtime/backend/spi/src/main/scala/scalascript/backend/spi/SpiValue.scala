package scalascript.backend.spi

import scalascript.interpreter.DataValue

/** A host-neutral, **closed** value representation for the typed plugin SPI (block-forms /
 *  effect handlers — see `specs/polyglot-libraries.md §2d`).
 *
 *  value-unification (scalars-only): the **scalar leaves** are now the *shared* `DataValue` cases
 *  (`lang/value-data`), so they are **the same classes** the interpreter's `Value` uses — the scalar
 *  half of the `Value ↔ SpiValue` conversion is identity (no realloc/rewrap). The *container* cases
 *  (`ListV`/`VectorV`/`TupleV`/`OptV`/`MapV`) and `Opaque` stay SPI-private (`SpiRest`) because the
 *  interpreter's containers can hold an arbitrary `Value` incl. closures — they are not host-neutral
 *  data (see `specs/value-unification.md`, the container/closure obstacle).
 *
 *  `SpiValue` is a **union** `DataValue | SpiRest`; `object SpiValue` re-exports both, with
 *  `DataValue.StringV` re-exported as `SpiValue.StrV` (the historical SPI name), so existing
 *  `SpiValue.IntV` / `SpiValue.StrV` / `SpiValue.ListV` construction + patterns are unchanged.
 *
 *  Effect operations deal almost entirely in primitives (`Logger.info(String): Unit`,
 *  `Random.nextInt(Long): Long`, …), which the scalar leaves cover. `Opaque` carries a value the SPI
 *  does not model — a closure, a user case-class instance — straight through without introspection. */
type SpiValue = DataValue | SpiRest

/** The SPI-private, non-scalar `SpiValue` cases — containers (which hold `SpiValue` so they stay a
 *  closed host-neutral shape, distinct from the interpreter's closure-bearing containers) + `Opaque`. */
enum SpiRest:
  case ListV(items: List[SpiValue])
  /** A `Vector` — modelled distinctly from `ListV` so the boundary preserves the collection type. */
  case VectorV(items: List[SpiValue])
  case TupleV(items: List[SpiValue])
  case OptV(value: Option[SpiValue])
  case MapV(entries: List[(SpiValue, SpiValue)])
  /** A value the SPI does not model (closure / user object); round-tripped unchanged. */
  case Opaque(handle: AnyRef)

object SpiValue:
  // Scalar leaves shared with the interpreter `Value` (DataValue.StringV is the SPI's `StrV`).
  export DataValue.{StringV as StrV, *}
  // Containers + Opaque.
  export SpiRest.*
  /** Convenience for the common reply: a handler that just acknowledges (`Logger.info` → ()). */
  val unit: SpiValue = DataValue.UnitV
