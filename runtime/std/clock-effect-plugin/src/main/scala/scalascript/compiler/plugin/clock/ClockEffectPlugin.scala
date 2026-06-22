package scalascript.compiler.plugin.clock

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule, ExportedSymbol}

/** The `Clock` effect, extracted from the interpreter core into a ServiceLoader plugin
 *  (core-minimization, polyglot-libraries §2d). Contributes two block-form effect-runners:
 *  `runClock { … }` (real wall clock) and `runClockAt(t0) { … }` (frozen at `t0` epoch-ms — the
 *  config-args form). One `ClockBlockForm` handles both: `newHandler` reads the optional frozen
 *  time from the config args and holds it as per-block state. Ops reply over the typed `SpiValue`. */
class ClockEffectPlugin extends Backend:
  def id:          String = "scalascript-clock-effect-interpreter"
  def displayName: String = "Clock effect (Interpreter)"
  def spiVersion:  String = SpiVersion.Current
  def capabilities: Capabilities = Capabilities(
    features = Set.empty, outputs = Set.empty, options = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                       = Set.empty

  /** core-min-prelude-migrate: declare the runner name(s) for `ssc check` (the keystone), removed from
   *  the hardcoded Typer prelude; resolves via the bundled plugin. `runClockAt` carried the typed
   *  `runnerType2("Clock")` core def — the typer does not enforce effect discharge, so `Any` suffices
   *  here (the interpreter resolves the runner via this plugin's block-form, not the type). */
  override def preludeSymbols: List[ExportedSymbol] = List(
    ExportedSymbol("runClock",   "runClock",   "def", "Any"),
    ExportedSymbol("runClockAt", "runClockAt", "def", "Any"),
  )
  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("clock-effect-plugin — interpreter only")))

  override def blockForms: Map[String, BlockForm] = Map(
    "runClock"   -> ClockBlockForm,
    "runClockAt" -> ClockBlockForm,
  )

/** `runClock { body }` (real clock) / `runClockAt(t0) { body }` (frozen at `t0`). */
private object ClockBlockForm extends BlockForm:
  def effectName: String = "Clock"
  def newHandler(ctx: BlockContext, args: List[SpiValue]): EffectHandler =
    val frozen = args match
      case List(SpiValue.IntV(t0)) => Some(t0)
      case _                       => None
    new ClockHandler(frozen)

private class ClockHandler(frozen: Option[Long]) extends EffectHandler:
  private def nowMs(): Long = frozen.getOrElse(java.lang.System.currentTimeMillis())
  private def nowIso(): String =
    val inst = java.time.Instant.ofEpochMilli(nowMs())
    java.time.format.DateTimeFormatter.ISO_INSTANT.format(inst)
  def reply(op: String, args: List[SpiValue]): SpiValue = op match
    case "now"    => SpiValue.IntV(nowMs())
    case "nowIso" => SpiValue.StrV(nowIso())
    case "sleep"  => args match
      case List(SpiValue.IntV(ms)) =>
        // frozen clocks make sleep a no-op so tests stay fast and deterministic.
        if frozen.isEmpty && ms > 0 then Thread.sleep(ms)
        SpiValue.UnitV
      case _ => throw new IllegalArgumentException("Clock.sleep(ms: Long)")
    case other => throw new IllegalArgumentException(s"Unknown Clock operation: $other")
