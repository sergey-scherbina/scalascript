package scalascript.backend.spi

/** A host-neutral, **closed** value representation for the typed plugin SPI (block-forms /
 *  effect handlers — see `specs/polyglot-libraries.md §2d`).
 *
 *  The interpreter converts its runtime `Value` to/from `SpiValue` at the boundary, so a
 *  plugin stays decoupled from interpreter internals (the spi module must NOT depend on
 *  `scalascript.interpreter.Value`) yet matches values **type-safely** — exhaustive pattern
 *  matching instead of casting raw `Any`.
 *
 *  Effect operations deal almost entirely in primitives (`Logger.info(String): Unit`,
 *  `Random.nextInt(Long): Long`, `Clock.now(): Long`, `Env.get(String): Option[String]`), so
 *  the closed cases cover them fully. `Opaque` carries a value the SPI does not model — a
 *  closure, a user case-class instance — straight through without introspection (the interp
 *  round-trips it unchanged), so the contract never silently drops a value.
 *
 *  This is also the seed of the value mapping a cross-language host library needs at its edge
 *  (Task B, §4.1): the same `SpiValue` maps to `java.util.List` / a JS array / a Rust `Vec`. */
enum SpiValue:
  case IntV(value: Long)
  case DoubleV(value: Double)
  case StrV(value: String)
  /** A character — modelled distinctly from `StrV` so the boundary is lossless: a ScalaScript
   *  `Char` (e.g. `Random.pick(List('a','b'))`) round-trips as a `Char`, not a 1-char `String`. */
  case CharV(value: Char)
  case BoolV(value: Boolean)
  case UnitV
  case ListV(items: List[SpiValue])
  /** A `Vector` — modelled distinctly from `ListV` so the boundary preserves the collection type
   *  (and `Vector(…)` vs `List(…)` `toString`/indexing) across the round-trip. */
  case VectorV(items: List[SpiValue])
  case TupleV(items: List[SpiValue])
  case OptV(value: Option[SpiValue])
  case MapV(entries: List[(SpiValue, SpiValue)])
  /** A value the SPI does not model (closure / user object); round-tripped unchanged. */
  case Opaque(handle: AnyRef)

object SpiValue:
  /** Convenience for the common reply: a handler that just acknowledges (`Logger.info` → ()). */
  val unit: SpiValue = UnitV
