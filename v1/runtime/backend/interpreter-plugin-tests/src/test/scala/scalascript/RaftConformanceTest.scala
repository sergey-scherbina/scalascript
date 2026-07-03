package scalascript

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scalascript.interpreter.Interpreter
import scalascript.interpreter.actors.ActorsInterpreterPlugin
import scalascript.parser.Parser

/** Smoke test for `conformance/actors-cluster-raft.ssc` on the
 *  interpreter backend.  The conformance .ssc file itself is also
 *  consumed by the JS / JVM backends via the e2e cross-backend
 *  shell-script harness; this test pins the INT path. */
class RaftConformanceTest extends AnyFunSuite with Matchers:

  test("Raft single-node election claims self"):
    val src = os.read(TestPaths.repoRoot / "tests" / "conformance" / "actors-cluster-raft.ssc")
    val buf = java.io.ByteArrayOutputStream()
    val _i = Interpreter(java.io.PrintStream(buf)); _i.installPlugins(List(new ActorsInterpreterPlugin)); _i.run(Parser.parse(src))
    val out = buf.toString.linesIterator.toList
    info(out.mkString("\n"))
    out should contain ("proto=raft")
    out should contain ("histSize=1")
    out should contain ("leader=''")
    out should contain ("ok")
