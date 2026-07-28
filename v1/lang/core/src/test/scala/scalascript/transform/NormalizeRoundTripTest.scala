package scalascript.transform

import org.scalatest.funsuite.AnyFunSuite
import scalascript.parser.Parser
import scalascript.ir
import upickle.default.*

/** Round-trip every conformance fixture through the IR codecs.
 *
 *  Pipeline per fixture:
 *
 *      source  ──Parser──▶  ast.Module
 *                              │
 *                              ▼
 *                       Normalize.apply
 *                              │
 *                              ▼
 *                      ir.NormalizedModule
 *                       /              \
 *                      ▼                ▼
 *                  writeJs           writeBinary (MsgPack)
 *                      │                │
 *                      ▼                ▼
 *                    json            bytes
 *                      │                │
 *                      ▼                ▼
 *                  read[NM]          readBinary[NM]
 *                       \              /
 *                        ▼            ▼
 *                  must equal original IR
 *
 *  Stage 2.2 acceptance per docs/backend-spi-plan.md.  Once Stages 3+
 *  populate IrExpr nodes the same test exercises the full IR. */
class NormalizeRoundTripTest extends AnyFunSuite:

  // conformance/ fixtures live two directories up from the sbt module's working dir.
  private val fixtureDir: os.Path =
    val cwd  = os.pwd
    val root =
      if os.exists(cwd / "conformance") then cwd
      else cwd / os.up
    root / "conformance"

  private val fixtures: List[os.Path] =
    if os.exists(fixtureDir) then
      os.list(fixtureDir).filter(_.ext == "ssc").toList.sortBy(_.last)
    else Nil

  fixtures.foreach { path =>
    val name = path.baseName

    test(s"$name — JSON round-trip preserves IR"):
      val module = Parser.parse(os.read(path))
      val normal = Normalize(module)
      val json   = write(normal)
      val back   = read[ir.NormalizedModule](json)
      assert(back == normal, s"JSON round-trip mismatch for $name")

    test(s"$name — MsgPack round-trip preserves IR"):
      val module = Parser.parse(os.read(path))
      val normal = Normalize(module)
      val bytes  = writeBinary(normal)
      val back   = readBinary[ir.NormalizedModule](bytes)
      assert(back == normal, s"MsgPack round-trip mismatch for $name")
  }

  test("a fence attribute survives the SPI boundary (ir-normalize-drops-code-fence-attrs)"):
    // `@side=server` was honoured by INT — which interprets the ast.Module directly and never crosses
    // this boundary — and INVISIBLE to every SPI backend, because ir.Content.CodeBlock had no slot for
    // it and Denormalize rebuilt the block without one. A block meant to be server-only was therefore
    // emitted into the JS bundle. This pins the CARRY: the attribute must reach the IR and come back.
    // It deliberately does NOT assert that any backend ACTS on `side` — that is the separate follow-up
    // recorded in BUGS; asserting it here would make the test pass for a reason it does not verify.
    val src =
      """---
        |name: fence-attr-carry
        |---
        |
        |```scalascript @side=server
        |def main(): Int = 42
        |```
        |""".stripMargin
    val mod = Parser.parse(src)
    val norm = Normalize(mod)
    val irBlocks = norm.sections.flatMap(_.content).collect { case cb: ir.Content.CodeBlock => cb }
    assert(irBlocks.nonEmpty, "expected at least one ir.Content.CodeBlock")
    assert(
      irBlocks.exists(_.attrs.get("side").contains("server")),
      s"the IR dropped the fence attribute; got attrs=${irBlocks.map(_.attrs)}"
    )
    val back = Denormalize(norm)
    val astBlocks = back.sections.flatMap(_.content).collect { case cb: scalascript.ast.Content.CodeBlock => cb }
    assert(
      astBlocks.exists(_.attrs.get("side").contains("server")),
      s"the round trip dropped the fence attribute; got attrs=${astBlocks.map(_.attrs)}"
    )
