package scalascript

import org.scalatest.funsuite.AnyFunSuite

import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** v1.26 — pins the `conformance/sql-*.ssc` files' interpreter
 *  stdout against the matching `conformance/expected/sql-*.txt`.
 *
 *  Bypasses the cross-backend `conformance/run.sc` harness (which
 *  requires `bin/ssc`, scala-cli, node + a published runtime JAR for
 *  the JVM target).  This in-process check covers only the
 *  interpreter path — exactly what `backends: [int]` declares for
 *  these tests — and runs under plain `sbt test` without external
 *  tooling, so the regression net stays cheap to enforce on every
 *  PR. */
@org.scalatest.Ignore
class SqlConformanceCaptureTest extends AnyFunSuite {

  private def runConformance(name: String): Unit = {
    val src      = os.read(TestPaths.repoRoot / "conformance" / s"$name.ssc")
    val expected = os.read(TestPaths.repoRoot / "conformance" / "expected" / s"$name.txt").stripTrailing()
    val buf = java.io.ByteArrayOutputStream()
    val ps  = java.io.PrintStream(buf, true)
    Interpreter(ps).run(Parser.parse(src))
    ps.flush()
    val got = buf.toString.stripTrailing()
    assert(got == expected,
      s"""conformance/$name.ssc stdout mismatch.
         |--- expected ---
         |$expected
         |--- got ---
         |$got
         |--- end ---""".stripMargin)
  }

  test("conformance/sql-basic.ssc matches expected stdout under interpreter") {
    runConformance("sql-basic")
  }

  test("conformance/sql-transaction.ssc matches expected stdout under interpreter") {
    runConformance("sql-transaction")
  }
}
