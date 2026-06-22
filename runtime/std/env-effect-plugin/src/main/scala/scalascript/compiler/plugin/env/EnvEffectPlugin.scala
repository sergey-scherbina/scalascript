package scalascript.compiler.plugin.env

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}

/** The `Env` effect, extracted from the interpreter core into a ServiceLoader plugin
 *  (core-minimization, polyglot-libraries §2d). Contributes two block-form effect-runners:
 *  `runEnv { … }` (reads the real process environment; `Env.set` is local) and
 *  `runEnvWith(Map(…)) { … }` (a fixture overlay — the config-args form, exercising the SPI's
 *  `MapV` config path). One `EnvBlockForm` handles both: `newHandler` reads the optional overlay
 *  map from the config args and holds a per-block mutable local map. Ops reply over `SpiValue`. */
class EnvEffectPlugin extends Backend:
  def id:          String = "scalascript-env-effect-interpreter"
  def displayName: String = "Env effect (Interpreter)"
  def spiVersion:  String = SpiVersion.Current
  def capabilities: Capabilities = Capabilities(
    features = Set.empty, outputs = Set.empty, options = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                       = Set.empty
  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("env-effect-plugin — interpreter only")))

  override def blockForms: Map[String, BlockForm] = Map(
    "runEnv"     -> EnvBlockForm,
    "runEnvWith" -> EnvBlockForm,
  )

private object EnvSupport:
  /** Render a config/argument `SpiValue` to its string form (parity with the former `Value.show`);
   *  `Env` keys and values are strings in practice, so `StrV` is the hot case. */
  def str(v: SpiValue): String = v match
    case SpiValue.StrV(s)    => s
    case SpiValue.IntV(n)    => n.toString
    case SpiValue.DoubleV(d) => d.toString
    case SpiValue.BoolV(b)   => b.toString
    case other               => other.toString

/** `runEnv { body }` (real process env) / `runEnvWith(map) { body }` (fixture overlay). */
private object EnvBlockForm extends BlockForm:
  def effectName: String = "Env"
  def newHandler(ctx: BlockContext, args: List[SpiValue]): EffectHandler =
    val overlay = args match
      case List(SpiValue.MapV(entries)) =>
        Some(entries.map { (k, v) => EnvSupport.str(k) -> EnvSupport.str(v) }.toMap)
      case _ => None
    new EnvHandler(overlay)

private class EnvHandler(overlay: Option[Map[String, String]]) extends EffectHandler:
  private val local = scala.collection.mutable.Map.empty[String, String]
  overlay.foreach(m => local ++= m)

  /** local overlay first; only fall back to the real process env when no overlay was supplied. */
  private def lookup(key: String): Option[String] =
    local.get(key)
      .orElse(if overlay.isEmpty then Option(java.lang.System.getenv(key)).filter(_.nonEmpty) else None)

  def reply(op: String, args: List[SpiValue]): SpiValue = op match
    case "get" => args match
      case List(SpiValue.StrV(k)) => SpiValue.OptV(lookup(k).map(SpiValue.StrV(_)))
      case _                      => throw new IllegalArgumentException("Env.get(key: String)")
    case "set" => args match
      case List(SpiValue.StrV(k), v) => local(k) = EnvSupport.str(v); SpiValue.UnitV
      case _                         => throw new IllegalArgumentException("Env.set(key: String, value)")
    case "required" => args match
      case List(SpiValue.StrV(k)) => lookup(k) match
        case Some(v) => SpiValue.StrV(v)
        case None    => throw new IllegalArgumentException(s"Env.required: key '$k' not found in environment")
      case _ => throw new IllegalArgumentException("Env.required(key: String)")
    case other => throw new IllegalArgumentException(s"Unknown Env operation: $other")
