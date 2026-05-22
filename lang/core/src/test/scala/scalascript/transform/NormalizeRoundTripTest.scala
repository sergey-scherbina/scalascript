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
    os.list(fixtureDir).filter(_.ext == "ssc").toList.sortBy(_.last)

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
