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
