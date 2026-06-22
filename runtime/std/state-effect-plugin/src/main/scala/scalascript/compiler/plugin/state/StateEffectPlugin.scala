package scalascript.compiler.plugin.state

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** The `State` effect, extracted from the interpreter core into a ServiceLoader plugin
 *  (core-minimization, polyglot-libraries §2d). Contributes the `runState(s0) { … }` block-form
 *  (config-args path supplies the initial state). Unlike the pure-reply effects (Logger/Random/
 *  Clock/Env), `State.modify(f)` must *apply a ScalaScript closure* — it does so via the new
 *  `BlockContext.applyFn`, so the plugin still never touches interpreter internals directly. The
 *  block result is the `(finalState, bodyResult)` pair, produced by the `result` hook. */
class StateEffectPlugin extends Backend:
  def id:          String = "scalascript-state-effect-interpreter"
  def displayName: String = "State effect (Interpreter)"
  def spiVersion:  String = SpiVersion.Current
  def capabilities: Capabilities = Capabilities(
    features = Set.empty, outputs = Set.empty, options = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                       = Set.empty
  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("state-effect-plugin — interpreter only")))

  override def blockForms: Map[String, BlockForm] = Map(
    "runState" -> StateBlockForm,
  )

/** `runState(s0) { body }` — threads a mutable state cell through `State.get/set/modify`, and
 *  returns `(finalState, bodyResult)`. */
private object StateBlockForm extends BlockForm:
  def effectName: String = "State"
  def newHandler(ctx: BlockContext, args: List[SpiValue]): EffectHandler =
    new StateHandler(ctx, args.headOption.getOrElse(SpiValue.UnitV))
  override def result(bodyResult: SpiValue, handler: EffectHandler): SpiValue = handler match
    case h: StateHandler => SpiValue.TupleV(List(h.current, bodyResult))
    case _               => bodyResult

private class StateHandler(ctx: BlockContext, initial: SpiValue) extends EffectHandler:
  var current: SpiValue = initial
  def reply(op: String, args: List[SpiValue]): SpiValue = op match
    case "get" => current
    case "set" => args match
      case List(s) => current = s; SpiValue.UnitV
      case _       => throw new IllegalArgumentException("State.set(s)")
    case "modify" => args match
      case List(f) => current = ctx.applyFn(f, List(current)); SpiValue.UnitV
      case _       => throw new IllegalArgumentException("State.modify(f: S => S)")
    case other => throw new IllegalArgumentException(s"Unknown State operation: $other")
