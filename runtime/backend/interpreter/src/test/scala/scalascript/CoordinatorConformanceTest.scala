package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.parser.Parser

/** Smoke test for `conformance/actors-cluster-coordinator.ssc` on the
 *  interpreter backend.  Pins the 4-arg `useExternalCoordinator`
 *  round-trip: switch → initial sync acquire → currentLeader =
 *  localNodeId → leaderHistory recorded. */
@org.scalatest.Ignore
class CoordinatorConformanceTest extends AnyFunSuite with Matchers:

  test("Coordinator initial sync acquire claims leadership"):
    val repoRoot = TestPaths.repoRoot
    val src = os.read(repoRoot / "conformance" / "actors-cluster-coordinator.ssc")
    val buf = java.io.ByteArrayOutputStream()
    Interpreter(java.io.PrintStream(buf), baseDir = Some(repoRoot)).run(Parser.parse(src))
    val out = buf.toString.linesIterator.toList
    info(out.mkString("\n"))
    out should contain ("proto=coord")
    out should contain ("leader=''")        // localNodeId is "" without startNode
    out should contain ("hist=1")
    out should contain ("ok")
