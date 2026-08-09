package scalascript.compiler.plugin.logger

import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule, ExportedSymbol}

/** The `Logger` effect, extracted from the interpreter core into a ServiceLoader plugin
 *  (core-minimization, polyglot-libraries §2d). Contributes three block-form effect-runners —
 *  `runLogger` / `runLoggerJson` / `runLoggerToList` — via the `Backend.blockForms` SPI. The
 *  interpreter owns the `Computation` trampoline; these handlers reply over the typed `SpiValue`
 *  and write through `BlockContext.out`, so the plugin never touches interpreter internals. */
class LoggerEffectPlugin extends Backend:
  def id:          String = "scalascript-logger-effect-interpreter"
  def displayName: String = "Logger effect (Interpreter)"
  def spiVersion:  String = SpiVersion.Current
  def capabilities: Capabilities = Capabilities(
    features = Set.empty, outputs = Set.empty, options = Set.empty,
    spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
  def intrinsics:      Map[QualifiedName, IntrinsicImpl] = Map.empty
  def acceptedSources: Set[String]                       = Set.empty

  /** core-min-prelude-migrate: DECLARE the runner name(s) for `ssc check` (the keystone),
   *  removed from the hardcoded Typer prelude. The typer does not enforce effect discharge, so
   *  `Any` suffices; the interpreter resolves each runner via this plugin's block-form. */
  override def preludeSymbols: List[ExportedSymbol] = List(
    ExportedSymbol("runLogger", "runLogger", "def", "Any"),
    ExportedSymbol("runLoggerJson", "runLoggerJson", "def", "Any"),
    ExportedSymbol("runLoggerToList", "runLoggerToList", "def", "Any"),
  )
  def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
    CompileResult.Failed(List(Diagnostic.Generic("logger-effect-plugin — interpreter only")))

  override def blockForms: Map[String, BlockForm] = Map(
    "runLogger"       -> LoggerWriteBlockForm("text"),
    "runLoggerJson"   -> LoggerWriteBlockForm("json"),
    "runLoggerToList" -> LoggerToListBlockForm,
  )

private object LoggerSupport:
  /** Render one log argument like the core `Value.show` did — `Logger.<level>(msg)` is a String
   *  in practice, so `StrV` is the hot case; the rest are rendered for parity. */
  def msgOf(args: List[SpiValue]): String = args match
    case List(SpiValue.StrV(m))    => m
    case List(SpiValue.IntV(n))    => n.toString
    case List(SpiValue.DoubleV(d)) => d.toString
    case List(SpiValue.BoolV(b))   => b.toString
    case List(other)               => other.toString
    case _                         => ""

  def jsonStr(s: String): String =
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c    => sb.append(c)
    }
    sb.append('"').toString

/** `runLogger { body }` (text) / `runLoggerJson { body }` — writes each `Logger.<level>(msg)`. */
private class LoggerWriteBlockForm(format: String) extends BlockForm:
  def effectName: String = "Logger"
  def newHandler(ctx: BlockContext, args: List[SpiValue]): EffectHandler =
    new EffectHandler:
      def reply(op: String, args: List[SpiValue]): SpiValue =
        val msg = LoggerSupport.msgOf(args)
        format match
          case "json" => ctx.out.println(s"""{"level":"$op","msg":${LoggerSupport.jsonStr(msg)}}""")
          case _      => ctx.out.println(s"[${op.toUpperCase}] $msg")
        SpiValue.UnitV

/** `runLoggerToList { body }` — returns `(bodyResult, List((level, msg), …))`. */
private object LoggerToListBlockForm extends BlockForm:
  def effectName: String = "Logger"
  def newHandler(ctx: BlockContext, args: List[SpiValue]): EffectHandler = new CollectHandler
  override def result(bodyResult: SpiValue, handler: EffectHandler): SpiValue = handler match
    case h: CollectHandler =>
      SpiValue.TupleV(List(bodyResult, SpiValue.ListV(h.entries.toList.map { (lvl, m) =>
        SpiValue.TupleV(List(SpiValue.StrV(lvl), SpiValue.StrV(m)))
      })))
    case _ => bodyResult

private class CollectHandler extends EffectHandler:
  val entries = scala.collection.mutable.ListBuffer.empty[(String, String)]
  def reply(op: String, args: List[SpiValue]): SpiValue =
    entries += (op -> LoggerSupport.msgOf(args))
    SpiValue.UnitV
