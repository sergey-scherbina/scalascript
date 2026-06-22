package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.backend.spi.*
import scalascript.ir.{QualifiedName, NormalizedModule}
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Proves the block-form / effect-handler plugin SPI (polyglot-libraries §2d) end-to-end: a
 *  plugin registers a `runTally { … }` block-form + a stateful "Tally" effect handler, and the
 *  interpreter dispatches the body through `EffectHandlers.runWithHandler`, replying over the
 *  typed, host-neutral `SpiValue` — the plugin never touches the interpreter's `Computation`. */
class BlockFormSpiTest extends AnyFunSuite:

  /** A test plugin contributing a `runTally { body }` effect-runner. `Tally.add(n)` accumulates
   *  a running sum and replies with the new total. */
  private class TallyTestPlugin extends Backend:
    def id:          String = "test-tally"
    def displayName: String = "Tally (test)"
    def spiVersion:  String = SpiVersion.Current
    def capabilities: Capabilities = Capabilities(
      features = Set.empty, outputs = Set.empty, options = Set.empty,
      spiRange = SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
    def intrinsics:      Map[QualifiedName, IntrinsicImpl] = Map.empty
    def acceptedSources: Set[String]                       = Set.empty
    def compile(module: NormalizedModule, opts: BackendOptions): CompileResult =
      CompileResult.Failed(List(Diagnostic.Generic("test plugin — interpreter only")))

    override def blockForms: Map[String, BlockForm] = Map(
      "runTally" -> new BlockForm:
        def effectName: String = "Tally"
        def newHandler(ctx: BlockContext, args: List[SpiValue]): EffectHandler =
          new EffectHandler:
            private var sum = 0L
            def reply(op: String, args: List[SpiValue]): SpiValue = (op, args) match
              case ("add", List(SpiValue.IntV(n))) => sum += n; SpiValue.IntV(sum)
              case _                               => SpiValue.UnitV
    )

  private def run(source: String): String =
    val buf    = java.io.ByteArrayOutputStream()
    val ps     = java.io.PrintStream(buf)
    val interp = Interpreter(out = ps)
    interp.installPlugins(List(new TallyTestPlugin))
    interp.run(Parser.parse("# Test\n\n```scalascript\n" + source + "\n```\n"))
    ps.flush()
    buf.toString.trim

  test("plugin block-form + effect handler dispatch through the SPI (runTally)"):
    val out = run(
      """effect Tally:
        |  def add(n: Int): Int
        |def prog(): Int =
        |  val a = Tally.add(10)
        |  val b = Tally.add(5)
        |  a + b
        |println(runTally { prog() })""".stripMargin)
    // add(10) → running total 10; add(5) → 15; a + b = 25. The body ran through the
    // plugin handler, with Int args/replies round-tripped Value↔SpiValue.
    assert(out == "25", s"expected 25 (10 + 15) via the plugin block-form, got: [$out]")

  // The two SPI paths the runTally proof above does NOT exercise — proven on a REAL feature's
  // semantics (Logger), the next core-minimization target: BlockContext.out + result-combination.

  /** A `logLine { body }` runner whose handler writes `[LEVEL] msg` to the interpreter's stdout —
   *  exactly like the core Logger — proving the `BlockContext.out` sink path. */
  private class LogLinePlugin extends Backend:
    def id = "test-logline"; def displayName = "LogLine (test)"; def spiVersion = SpiVersion.Current
    def capabilities = Capabilities(Set.empty, Set.empty, Set.empty,
      SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
    def intrinsics: Map[QualifiedName, IntrinsicImpl] = Map.empty
    def acceptedSources: Set[String] = Set.empty
    def compile(m: NormalizedModule, o: BackendOptions): CompileResult =
      CompileResult.Failed(List(Diagnostic.Generic("test plugin")))
    override def blockForms: Map[String, BlockForm] = Map(
      "logLine" -> new BlockForm:
        def effectName = "Log"
        def newHandler(ctx: BlockContext, args: List[SpiValue]): EffectHandler =
          new EffectHandler:
            def reply(op: String, args: List[SpiValue]): SpiValue = (op, args) match
              case (lvl, List(SpiValue.StrV(m))) => ctx.out.println(s"[${lvl.toUpperCase}] $m"); SpiValue.UnitV
              case _                             => SpiValue.UnitV
    )

  /** A `logCollect { body }` runner returning `(bodyResult, collectedMessages)` — proving the
   *  `BlockForm.result(bodyResult, handler)` state-combination path (Logger-toList style). */
  private class CollectHandler extends EffectHandler:
    val msgs = scala.collection.mutable.ListBuffer.empty[String]
    def reply(op: String, args: List[SpiValue]): SpiValue = (op, args) match
      case (_, List(SpiValue.StrV(m))) => msgs += m; SpiValue.UnitV
      case _                           => SpiValue.UnitV
  private class LogCollectPlugin extends Backend:
    def id = "test-logcollect"; def displayName = "LogCollect (test)"; def spiVersion = SpiVersion.Current
    def capabilities = Capabilities(Set.empty, Set.empty, Set.empty,
      SpiVersionRange(SpiVersion.Current, SpiVersion.Current))
    def intrinsics: Map[QualifiedName, IntrinsicImpl] = Map.empty
    def acceptedSources: Set[String] = Set.empty
    def compile(m: NormalizedModule, o: BackendOptions): CompileResult =
      CompileResult.Failed(List(Diagnostic.Generic("test plugin")))
    override def blockForms: Map[String, BlockForm] = Map(
      "logCollect" -> new BlockForm:
        def effectName = "Log"
        def newHandler(ctx: BlockContext, args: List[SpiValue]): EffectHandler = new CollectHandler
        override def result(bodyResult: SpiValue, handler: EffectHandler): SpiValue = handler match
          case h: CollectHandler =>
            SpiValue.TupleV(List(bodyResult, SpiValue.ListV(h.msgs.toList.map(SpiValue.StrV(_)))))
          case _ => bodyResult
    )

  private def runWith(plugin: Backend, source: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf)
    val interp = Interpreter(out = ps)
    interp.installPlugins(List(plugin))
    interp.run(Parser.parse("# Test\n\n```scalascript\n" + source + "\n```\n"))
    ps.flush(); buf.toString.trim

  test("block-form handler writes to ctx.out (Logger-style sink path)"):
    val out = runWith(new LogLinePlugin,
      """effect Log:
        |  def info(m: String): Unit
        |logLine { Log.info("hello") }
        |println("done")""".stripMargin)
    assert(out == "[INFO] hello\ndone", s"expected the handler to write to stdout, got: [$out]")

  test("block-form result combines body + handler state (Logger-toList style)"):
    val out = runWith(new LogCollectPlugin,
      """effect Log:
        |  def info(m: String): Unit
        |def prog(): Int =
        |  Log.info("a")
        |  Log.info("b")
        |  7
        |println(logCollect { prog() })""".stripMargin)
    // result(bodyResult=7, handler) → (7, List("a", "b")) — proves BlockForm.result.
    assert(out == "(7, List(a, b))", s"expected (7, List(a, b)) via result-combination, got: [$out]")
