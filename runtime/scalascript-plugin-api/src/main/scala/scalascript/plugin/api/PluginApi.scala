package scalascript.plugin.api

import scalascript.backend.spi.NativeContext

/** Stable surface for ScalaScript plugin authors.
 *
 *  Plugin authors depend only on `scalascript-plugin-api`; they never
 *  import `scalascript.interpreter.*` directly.
 *
 *  Phase 1 ships the module with minimal opaque aliases and a type alias
 *  for `PluginContext`.  Phase 2 decomposes `PluginContext` into typed
 *  capability intersections (`HttpCap & WsCap & …`). */

/** Opaque handle to a ScalaScript runtime value.
 *  Backed by `Any` in the interpreter; plugins receive it from `NativeImpl.eval`
 *  and pass it back — they never pattern-match on the concrete representation. */
opaque type PluginValue = Any

object PluginValue:
  def wrap(v: Any): PluginValue = v
  extension (pv: PluginValue)
    def unwrap: Any = pv

/** Opaque wrapper for an interpreter-level runtime error. */
opaque type PluginError = Throwable

object PluginError:
  def apply(msg: String): PluginError   = new RuntimeException(msg)
  def wrap(t: Throwable): PluginError   = t
  extension (pe: PluginError)
    def message: String   = pe.getMessage
    def unwrap:  Throwable = pe

/** Opaque alias for the interpreter's `Computation[A]` monad.
 *  Phase 1 treats it as `Any`; the interpreter unwraps it at the SPI boundary.
 *  Use `PluginComputation.pure` to lift a `PluginValue`. */
opaque type PluginComputation = Any

object PluginComputation:
  def pure(v: PluginValue): PluginComputation = v
  extension (pc: PluginComputation)
    def unwrap: Any = pc

/** Stable JSON surface backed by `ujson.Value`.
 *  Prefer this over `scalascript.interpreter.JsonParser` in new plugins so that
 *  parser internals can change without breaking the plugin ABI. */
object JsonCodec:

  def parseString(src: String): Either[String, ujson.Value] =
    try Right(ujson.read(src))
    catch case e: ujson.ParseException => Left(e.getMessage)

  def stringify(v: ujson.Value): String = ujson.write(v)

  def obj(fields: (String, ujson.Value)*): ujson.Obj   = ujson.Obj(fields*)
  def arr(elems: ujson.Value*):            ujson.Arr   = ujson.Arr(elems*)
  def str(s: String):                      ujson.Str   = ujson.Str(s)
  def num(n: Double):                      ujson.Num   = ujson.Num(n)
  val True:  ujson.Bool = ujson.True
  val False: ujson.Bool = ujson.False
  val Null:  ujson.Null.type = ujson.Null

/** Plugin runtime context.
 *
 *  Phase 1: full re-export of `NativeContext` under a stable name.
 *  Phase 2 will introduce a capability decomposition so plugins declare
 *  exactly which capabilities (`HttpCap`, `WsCap`, …) they require. */
type PluginContext = NativeContext
