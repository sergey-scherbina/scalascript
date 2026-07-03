package scalascript

import org.scalatest.funsuite.AnyFunSuite
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** busi seq-132 regression — the interpreter module loader must evaluate each
 *  imported module **once per run**, not once per import edge. A diamond import of a
 *  module (imported both directly and transitively via an SPI module) previously
 *  re-ran the shared module per DAG path — exponential in diamond layers, OOM/hang on
 *  a large module at load time. We assert the shared module's top-level side effect
 *  fires exactly once and the program still computes correctly. */
class InterpModuleDedupTest extends AnyFunSuite:

  private def run(dir: os.Path, entry: String): String =
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(out = ps, baseDir = Some(dir)).run(Parser.parse(os.read(dir / entry)))
    ps.flush()
    buf.toString

  private def writeBig(dir: os.Path): Unit =
    os.write(dir / "big.ssc",
      """---
        |name: big
        |exports:
        |  - bigValue
        |---
        |# big
        |
        |```scalascript
        |println("LOADBIG")
        |val bigValue: Int = 42
        |```
        |""".stripMargin)

  test("a module imported via a diamond is evaluated exactly once (busi seq-132)"):
    val dir = os.temp.dir(prefix = "ssc-module-dedup-")
    writeBig(dir)
    // spi imports big (one edge onto big)
    os.write(dir / "spi.ssc",
      """---
        |name: spi
        |exports:
        |  - viaSpi
        |---
        |# spi
        |
        |[bigValue](big.ssc)
        |
        |```scalascript
        |val viaSpi: Int = bigValue + 1
        |```
        |""".stripMargin)
    // entry imports BOTH big and spi → diamond on big
    os.write(dir / "main.ssc",
      """# Main
        |
        |[bigValue](big.ssc)
        |[viaSpi](spi.ssc)
        |
        |```scalascript
        |println(bigValue + viaSpi)
        |```
        |""".stripMargin)
    val out = run(dir, "main.ssc")
    val loads = "LOADBIG".r.findAllMatchIn(out).size
    assert(loads == 1, s"big.ssc must load once, loaded $loads times; output:\n$out")
    assert(out.linesIterator.contains("85"), s"expected 42 + 43 = 85; output:\n$out")

  test("a deeper stacked diamond stays linear (would blow up without the cache)"):
    val dir = os.temp.dir(prefix = "ssc-module-dedup-deep-")
    writeBig(dir)
    // Three SPI layers each re-importing big AND the layer below — without dedup the
    // shared `big` would be evaluated 2^N times; with it, once.
    os.write(dir / "l1.ssc",
      """---
        |name: l1
        |exports:
        |  - v1
        |---
        |# l1
        |[bigValue](big.ssc)
        |```scalascript
        |val v1: Int = bigValue
        |```
        |""".stripMargin)
    os.write(dir / "l2.ssc",
      """---
        |name: l2
        |exports:
        |  - v2
        |---
        |# l2
        |[bigValue](big.ssc)
        |[v1](l1.ssc)
        |```scalascript
        |val v2: Int = bigValue + v1
        |```
        |""".stripMargin)
    os.write(dir / "l3.ssc",
      """---
        |name: l3
        |exports:
        |  - v3
        |---
        |# l3
        |[bigValue](big.ssc)
        |[v2](l2.ssc)
        |```scalascript
        |val v3: Int = bigValue + v2
        |```
        |""".stripMargin)
    os.write(dir / "main.ssc",
      """# Main
        |[bigValue](big.ssc)
        |[v3](l3.ssc)
        |```scalascript
        |println(v3)
        |```
        |""".stripMargin)
    val out = run(dir, "main.ssc")
    val loads = "LOADBIG".r.findAllMatchIn(out).size
    assert(loads == 1, s"big.ssc must load once across the deep diamond, loaded $loads times; output:\n$out")
    assert(out.linesIterator.contains("126"), s"expected 42*3 = 126; output:\n$out")
