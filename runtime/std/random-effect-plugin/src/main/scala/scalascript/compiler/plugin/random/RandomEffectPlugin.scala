package scalascript.compiler.plugin.random

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule, ExportedSymbol}

/** The `Random` effect, extracted from the interpreter core into a ServiceLoader plugin
 *  (core-minimization, polyglot-libraries §2d). Contributes two block-form effect-runners:
 *  `runRandom { … }` (system-seeded) and `runRandomSeeded(seed) { … }` (deterministic). The
 *  seeded form exercises the block-form SPI's *config-args* path — the leading `(seed)` clause is
 *  passed to `BlockForm.newHandler`, which the interpreter wires up generically. The interpreter
 *  owns the `Computation` trampoline; this handler replies over the typed `SpiValue` and holds the
 *  per-block RNG state, so the plugin never touches interpreter internals. */
class RandomEffectPlugin extends Backend:
  def id:          String = "scalascript-random-effect-interpreter"
  def displayName: String = "Random effect (Interpreter)"
  def spiVersion:  String = SpiVersion.Current
  def capabilities: Capabilities = Capabilities(
    features = Set.empty, outputs = Set.empty, options = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                       = Set.empty
  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("random-effect-plugin — interpreter only")))

  // Both keywords share one block-form: `runRandom` invokes `newHandler` with no config args (a
  // fresh system-seeded RNG); `runRandomSeeded` passes the seed as the single config arg.
  override def blockForms: Map[String, BlockForm] = Map(
    "runRandom"       -> RandomBlockForm,
    "runRandomSeeded" -> RandomBlockForm,
  )

  /** core-min-prelude-migrate: the plugin DECLARES its runner name for `ssc check` (the keystone),
   *  so it no longer has to be hardcoded in `Typer.createPrelude`'s `effectBuiltins`. Resolves via
   *  the bundled plugin's preludeSymbols. (`runRandomSeeded` keeps its typed core def for now — it
   *  carries the effect-discharge `runnerType2` signature, which migrates in a later step.) */
  override def preludeSymbols: List[ExportedSymbol] = List(
    ExportedSymbol("runRandom", "runRandom", "def", "Any"),
  )

/** `runRandom { body }` / `runRandomSeeded(seed) { body }` — a per-block `java.util.Random`. */
private object RandomBlockForm extends BlockForm:
  def effectName: String = "Random"
  def newHandler(ctx: BlockContext, args: List[SpiValue]): EffectHandler =
    val rng = args match
      case List(SpiValue.IntV(seed)) => new java.util.Random(seed)
      case _                         => new java.util.Random()
    new RandomHandler(rng)

private class RandomHandler(rng: java.util.Random) extends EffectHandler:
  def reply(op: String, args: List[SpiValue]): SpiValue = op match
    case "nextInt" => args match
      case List(SpiValue.IntV(n)) => SpiValue.IntV(rng.nextInt(n.toInt).toLong)
      case _                      => throw new IllegalArgumentException("Random.nextInt(n: Int)")
    case "nextDouble" =>
      SpiValue.DoubleV(rng.nextDouble())
    case "uuid" =>
      SpiValue.StrV(randomUuid())
    case "pick" => args match
      case List(SpiValue.ListV(items)) if items.nonEmpty => items(rng.nextInt(items.size))
      case _ => throw new IllegalArgumentException("Random.pick(xs: List[A]) — list must be non-empty")
    case other =>
      throw new IllegalArgumentException(s"Unknown Random operation: $other")

  /** RFC-4122 v4 UUID drawn from this handler's seeded RNG (parity with the former core impl). */
  private def randomUuid(): String =
    val bytes = new Array[Byte](16)
    rng.nextBytes(bytes)
    bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte
    bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte
    def hex(b: Byte) = f"${b & 0xff}%02x"
    val u = bytes.map(hex).mkString
    s"${u.take(8)}-${u.slice(8, 12)}-${u.slice(12, 16)}-${u.slice(16, 20)}-${u.drop(20)}"
